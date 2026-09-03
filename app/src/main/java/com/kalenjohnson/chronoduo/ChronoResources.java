package com.kalenjohnson.chronoduo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads named files out of the game's assets/resources.bin, an "ARC1" archive:
 * a gzip-compressed, XOR-streamed file table pointing at gzip-compressed,
 * XOR-streamed entries. The whole archive is XOR-obfuscated with a stream
 * cipher keyed only by absolute file offset — there is no real key, and the
 * stream restarts at the start offset of whatever region is being decoded
 * (the header at 0, the table at header_offset, each entry at its own
 * entry_offset).
 *
 * Extracted entries are cached under the app's private files dir, keyed by
 * the *game's* version code (a game update can repack the archive), mirroring
 * ChronoRuntime's lib extraction cache.
 */
public final class ChronoResources {
    private static final String TAG = "ChronoDuo";
    private static final String ARCHIVE_ASSET = "resources.bin";

    private static final class TableEntry {
        final long offset;
        final int len;
        TableEntry(long offset, int len) { this.offset = offset; this.len = len; }
    }

    // Parsed once per process and reused across extract() calls; the table is
    // small (a few hundred KB inflated) so keeping it in memory is cheap.
    private static Map<String, TableEntry> table;

    private ChronoResources() {}

    /** Extracts one named entry (e.g. "Extension/face.png") to a cache file, or returns the cached copy. */
    public static synchronized File extract(Context ctx, AssetManager gameAssets, String entryName)
            throws IOException {
        File dir = cacheDir(ctx);
        String safeName = entryName.replace('/', '_');
        File out = new File(dir, safeName);
        if (out.isFile() && out.length() > 0) return out;

        if (table == null) table = parseTable(gameAssets);
        TableEntry e = table.get(entryName);
        if (e == null) throw new IOException("entry not found in resources.bin: " + entryName);

        byte[] data = decodeEntry(gameAssets, e);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(dir, safeName + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(data);
        }
        if (!tmp.renameTo(out)) throw new IOException("rename failed for " + out);
        Log.i(TAG, "extracted " + entryName + " (" + data.length + " bytes) from resources.bin");
        return out;
    }

    /**
     * Batch form for bootstrap-time extraction. Best-effort: entries that fail
     * (missing, archive unreadable, etc.) are logged and left out of the
     * result rather than aborting the whole batch.
     */
    public static Map<String, File> extractAll(Context ctx, AssetManager gameAssets, String[] names) {
        Map<String, File> out = new HashMap<>();
        for (String name : names) {
            try {
                out.put(name, extract(ctx, gameAssets, name));
            } catch (IOException e) {
                Log.w(TAG, "resources.bin extract failed for " + name, e);
            }
        }
        return out;
    }

    private static File cacheDir(Context ctx) throws IOException {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ChronoRuntime.CHRONO_PACKAGE, 0);
            return new File(ctx.getFilesDir(), "ctres/" + pi.getLongVersionCode());
        } catch (Exception e) {
            throw new IOException("cannot resolve game version for cache dir", e);
        }
    }

    // ---- ARC1 parsing ----------------------------------------------------

    private static Map<String, TableEntry> parseTable(AssetManager gameAssets) throws IOException {
        byte[] hdr = readRegion(gameAssets, 0, 16);
        String magic = new String(hdr, 0, 4, StandardCharsets.US_ASCII);
        if (!"ARC1".equals(magic)) {
            throw new IOException("resources.bin: bad magic \"" + magic + "\", expected ARC1");
        }
        long headerOffset = u32le(hdr, 8);
        int headerLen = (int) u32le(hdr, 12);

        byte[] tableRegion = readRegion(gameAssets, headerOffset, headerLen);
        int tableUncompLen = (int) u32be(tableRegion, 0);
        byte[] inflated = gunzip(tableRegion, 4, tableRegion.length - 4, tableUncompLen);

        int entryCount = (int) u32le(inflated, 0);
        int p = 4;
        long[] nameOff = new long[entryCount];
        long[] entryOff = new long[entryCount];
        int[] entryLen = new int[entryCount];
        for (int i = 0; i < entryCount; i++) {
            nameOff[i] = u32le(inflated, p);
            entryOff[i] = u32le(inflated, p + 4);
            entryLen[i] = (int) u32le(inflated, p + 8);
            p += 12;
        }
        Map<String, TableEntry> map = new HashMap<>(entryCount * 2);
        for (int i = 0; i < entryCount; i++) {
            String name = cString(inflated, (int) nameOff[i]);
            map.put(name, new TableEntry(entryOff[i], entryLen[i]));
        }
        Log.i(TAG, "resources.bin table parsed: " + entryCount + " entries");
        return map;
    }

    private static byte[] decodeEntry(AssetManager gameAssets, TableEntry e) throws IOException {
        byte[] region = readRegion(gameAssets, e.offset, e.len);
        long uncompLen = u32be(region, 0);
        if (uncompLen == 0) return new byte[0];
        return gunzip(region, 4, region.length - 4, (int) uncompLen);
    }

    /** Reads `length` bytes starting at `offset` in resources.bin and XOR-decodes them (stream restarts at `offset`). */
    private static byte[] readRegion(AssetManager gameAssets, long offset, int length) throws IOException {
        try (InputStream in = gameAssets.open(ARCHIVE_ASSET)) {
            skipFully(in, offset);
            byte[] buf = new byte[length];
            readFully(in, buf);
            xorDecode(buf, offset);
            return buf;
        }
    }

    private static void xorDecode(byte[] buf, long regionStartOffset) {
        long tmp = (0x19000000L + regionStartOffset) & 0xFFFFFFFFL;
        for (int i = 0; i < buf.length; i++) {
            tmp = (tmp * 0x41c64e6dL + 0x3039L) & 0xFFFFFFFFL;
            buf[i] ^= (byte) (tmp >>> 24);
        }
    }

    private static byte[] gunzip(byte[] src, int offset, int len, int expectedLen) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(src, offset, len))) {
            byte[] out = new byte[Math.max(expectedLen, 0)];
            readFully(gz, out);
            return out;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                // some InputStream implementations return 0 from skip() near
                // EOF or when buffering hasn't caught up; fall back to reading.
                if (in.read() < 0) throw new IOException("unexpected EOF while skipping");
                n--;
            } else {
                n -= skipped;
            }
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("unexpected EOF, wanted " + buf.length + " got " + off);
            off += n;
        }
    }

    private static long u32le(byte[] b, int off) {
        return (b[off] & 0xffL) | (b[off + 1] & 0xffL) << 8
                | (b[off + 2] & 0xffL) << 16 | (b[off + 3] & 0xffL) << 24;
    }

    private static long u32be(byte[] b, int off) {
        return (b[off] & 0xffL) << 24 | (b[off + 1] & 0xffL) << 16
                | (b[off + 2] & 0xffL) << 8 | (b[off + 3] & 0xffL);
    }

    private static String cString(byte[] b, int off) {
        int end = off;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }
}
