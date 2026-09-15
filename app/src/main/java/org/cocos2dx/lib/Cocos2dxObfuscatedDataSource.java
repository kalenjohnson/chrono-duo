package org.cocos2dx.lib;

import android.content.res.AssetFileDescriptor;
import android.media.MediaDataSource;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * MediaDataSource over an uncompressed APK asset whose bytes are XOR-obfuscated
 * with {@code out[i] = in[i] ^ (0xFF - (i & 0xFF))}. Chrono Trigger's cutscenes
 * ({@code 001.dat}..{@code 008.dat}, {@code 007-en.dat}) are plain H.264/AAC MP4s
 * wrapped this way, so MediaPlayer rejects the raw asset ("Can't play this video").
 * De-obfuscating on the fly with positional reads keeps playback zero-copy and
 * seekable; nothing is written to disk.
 */
public class Cocos2dxObfuscatedDataSource extends MediaDataSource {
    private final AssetFileDescriptor mAfd;
    private final FileInputStream mStream;
    private final FileChannel mChannel;
    private final long mStart;
    private final long mLength;

    public Cocos2dxObfuscatedDataSource(AssetFileDescriptor afd) throws IOException {
        mAfd = afd;
        mStart = afd.getStartOffset();
        mLength = afd.getLength();
        // Plain FileInputStream on the shared fd: positional channel reads (pread)
        // never move the descriptor's own offset, so this is safe alongside the
        // AssetManager's other users of the APK.
        mStream = new FileInputStream(afd.getFileDescriptor());
        mChannel = mStream.getChannel();
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
        for (int k = 0; k < total; k++) {
            buffer[offset + k] ^= (byte) (0xFF - ((position + k) & 0xFF));
        }
        return total == 0 ? -1 : total;
    }

    @Override
    public long getSize() {
        return mLength;
    }

    @Override
    public void close() throws IOException {
        try {
            mChannel.close();
            mStream.close();
        } finally {
            mAfd.close();
        }
    }
}
