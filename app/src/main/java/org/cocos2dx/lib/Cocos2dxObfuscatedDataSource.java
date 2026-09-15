package org.cocos2dx.lib;

import android.content.res.AssetFileDescriptor;
import android.media.MediaDataSource;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * MediaDataSource over an uncompressed APK asset whose bytes are XOR-obfuscated
 * with {@code out[i] = in[i] ^ (0xFF - (i & 0xFF))}. Chrono Trigger's cutscenes
 * ({@code 001.dat}..{@code 008.dat}, {@code 007-en.dat}) are plain H.264/AAC MP4s
 * wrapped this way, so MediaPlayer rejects the raw asset ("Can't play this video").
 * De-obfuscating on the fly with positional reads keeps playback zero-copy and
 * seekable; nothing is written to disk.
 *
 * <p>Also usable over a plain {@link File} on disk (e.g. a mod's FMV
 * replacement under {@code ModManager}'s mod root) via the {@link
 * #Cocos2dxObfuscatedDataSource(File)} constructor -- see {@code
 * Cocos2dxVideoView#openVideo()}'s mod-override path.
 */
public class Cocos2dxObfuscatedDataSource extends MediaDataSource {
    private final AssetFileDescriptor mAfd;
    private final FileInputStream mStream;
    private final RandomAccessFile mRaf;
    private final FileChannel mChannel;
    private final long mStart;
    private final long mLength;

    public Cocos2dxObfuscatedDataSource(AssetFileDescriptor afd) throws IOException {
        mAfd = afd;
        mRaf = null;
        mStart = afd.getStartOffset();
        mLength = afd.getLength();
        // Plain FileInputStream on the shared fd: positional channel reads (pread)
        // never move the descriptor's own offset, so this is safe alongside the
        // AssetManager's other users of the APK.
        mStream = new FileInputStream(afd.getFileDescriptor());
        mChannel = mStream.getChannel();
    }

    /**
     * Same XOR de-obfuscation, but over a stand-alone file on disk (a mod's
     * FMV replacement) rather than an APK asset entry. {@code file}'s entire
     * contents are treated as obfuscated from offset 0.
     */
    public Cocos2dxObfuscatedDataSource(File file) throws IOException {
        mAfd = null;
        mStream = null;
        mStart = 0;
        mRaf = new RandomAccessFile(file, "r");
        mChannel = mRaf.getChannel();
        mLength = mRaf.length();
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (position < 0 || position >= mLength) {
            return -1;
        }
        if (size > mLength - position) {
            size = (int) (mLength - position);
        }
        ByteBuffer bb = ByteBuffer.wrap(buffer, offset, size);
        long filePos = mStart + position;
        int total = 0;
        while (bb.hasRemaining()) {
            int n = mChannel.read(bb, filePos + total);
            if (n < 0) break;
            total += n;
        }
        deobfuscate(buffer, offset, total, position);
        return total == 0 ? -1 : total;
    }

    /**
     * Applies {@code out[i] = in[i] ^ (0xFF - (i & 0xFF))} in place to {@code
     * count} bytes of {@code buffer} starting at {@code offset}, where {@code
     * filePosition} is the absolute obfuscated-file offset of {@code
     * buffer[offset]} (the XOR key depends on absolute file position, not the
     * buffer offset). Factored out of {@link #readAt} so the pure XOR/position
     * logic can be exercised without a MediaDataSource/Android dependency.
     */
    static void deobfuscate(byte[] buffer, int offset, int count, long filePosition) {
        for (int k = 0; k < count; k++) {
            buffer[offset + k] ^= (byte) (0xFF - ((filePosition + k) & 0xFF));
        }
    }

    @Override
    public long getSize() {
        return mLength;
    }

    @Override
    public void close() throws IOException {
        try {
            mChannel.close();
            if (mStream != null) mStream.close();
            if (mRaf != null) mRaf.close();
        } finally {
            if (mAfd != null) mAfd.close();
        }
    }
}
