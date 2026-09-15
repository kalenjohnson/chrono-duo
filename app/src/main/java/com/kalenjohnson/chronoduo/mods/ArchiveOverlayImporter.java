package com.kalenjohnson.chronoduo.mods;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Handles a mod that ships a whole replacement {@code resources.bin} (a
 * full ARC1 repack, e.g. "Orchestral Wonders", 508 MiB) instead of loose
 * per-file substitutions: diffs it entry-by-entry against the game's own
 * archive and writes only the entries that are new or actually different as
 * loose files under the mod directory, mirroring the loose-file layout
 * every other mod already uses (see {@link ModManager}'s class doc).
 *
 * <p>The ARC1 container format (magic, XOR stream cipher, gzip'd table and
 * entries) is the same one {@code ChronoResources} reads from the game's
 * asset -- this class independently reimplements just enough of it
 * ({@link #parseTable}/{@link #decodeEntry}/{@link #xorDecode}, deliberately
 * named and structured to mirror {@code ChronoResources}'s methods of the
 * same name) against an arbitrary {@link RegionSource} instead of an
 * Android {@code AssetManager}, so this stays pure Java and JVM-testable
 * (see {@code ArchiveOverlayImporterTest}'s {@code Arc1TestWriter}, which
 * round-trips synthetic archives through the same code paths).
 *
 * <p>Cost: for each of the mod archive's entries, only the 4-byte
 * uncompressed-length prefix is read from the corresponding game entry (a
 * single {@link RegionSource#readRaw} call each) -- the expensive full
 * decode+gunzip+byte-compare only happens when both sides report the same
 * uncompressed size (required, since a repack recompresses everything and a
 * byte-for-byte compressed comparison would false-positive on every
 * unchanged entry). For a 508 MiB, ~9,500-entry archive where most entries
 * are unchanged, this means one central-directory-sized pass (a few hundred
 * KiB, the gzip'd table) plus one 4-byte random read per entry, with the
 * full decode reserved for entries that are actually candidates for being
 * identical -- i.e. bounded by the archive's real size, not quadratic in
 * entry count, as long as {@code gameSource}/{@code modSource} are true
 * random-access (see {@link RegionSource}, and {@link
 * ModManager.GameArchiveSourceFactory}'s doc for how the game side gets
 * one on Android: {@code AssetManager#openFd} gives a seekable {@code
 * FileDescriptor} when the asset is stored uncompressed in the APK, which
 * {@code resources.bin} -- already internally gzip'd -- always is).
 */
public final class ArchiveOverlayImporter {
    private static final String TAG = "ChronoDuo";
    private static final String MARKER_NAME = ".from-archive";

    private ArchiveOverlayImporter() {}

    /**
     * Random-access reader over an ARC1 container's raw (still XOR-obfuscated)
     * bytes -- {@link #readRaw} must return the SAME bytes a plain seek+read
     * over the underlying archive would, with no decoding applied; {@link
     * #parseTable}/{@link #decodeEntry} apply {@link #xorDecode} themselves.
     * {@link #close} is best-effort (a no-op default for a source with
     * nothing to release).
     */
    public interface RegionSource extends Closeable {
        byte[] readRaw(long offset, int length) throws IOException;

        @Override
        default void close() throws IOException {
        }
    }

    /** {@link RegionSource} backed by a plain {@link File} via {@link RandomAccessFile} -- used for the mod's own (always-a-real-file-after-extraction) resources.bin, and for both sides in tests. */
    public static final class FileRegionSource implements RegionSource {
        private final RandomAccessFile raf;

        public FileRegionSource(File f) throws IOException {
            this.raf = new RandomAccessFile(f, "r");
        }

        @Override
        public byte[] readRaw(long offset, int length) throws IOException {
            raf.seek(offset);
            byte[] buf = new byte[length];
            raf.readFully(buf);
            return buf;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    public interface ProgressCallback {
        void onProgress(int done, int total);
    }

    /** Per-entry outcome counts from {@link #overlay} -- also what {@code .from-archive} records. */
    public static final class Result {
        public final int total, added, changed, identical;

        Result(int total, int added, int changed, int identical) {
            this.total = total;
            this.added = added;
            this.changed = changed;
            this.identical = identical;
        }
    }

    // --- ARC1 primitives (mirrors ChronoResources#parseTable/decodeEntry/xorDecode) ---

    static final class TableEntry {
        final long offset;
        final int len;

        TableEntry(long offset, int len) {
            this.offset = offset;
            this.len = len;
        }
    }

    static Map<String, TableEntry> parseTable(RegionSource src) throws IOException {
        byte[] hdr = readRegion(src, 0, 16);
        String magic = new String(hdr, 0, 4, StandardCharsets.US_ASCII);
        if (!"ARC1".equals(magic)) {
            throw new IOException("resources.bin: bad magic \"" + magic + "\", expected ARC1");
        }
        long headerOffset = u32le(hdr, 8);
        int headerLen = (int) u32le(hdr, 12);

        byte[] tableRegion = readRegion(src, headerOffset, headerLen);
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
        Map<String, TableEntry> map = new LinkedHashMap<>(entryCount * 2);
        for (int i = 0; i < entryCount; i++) {
            map.put(cString(inflated, (int) nameOff[i]), new TableEntry(entryOff[i], entryLen[i]));
        }
        return map;
    }

    /** Reads just the 4-byte uncompressed-length prefix of an entry, without gunzip'ing the rest -- the XOR keystream only depends on absolute offset, so a short read is byte-identical to truncating a full-region read. */
    static long peekUncompLen(RegionSource src, TableEntry e) throws IOException {
        byte[] prefix = readRegion(src, e.offset, Math.min(4, e.len));
        return prefix.length >= 4 ? u32be(prefix, 0) : 0;
    }

    static byte[] decodeEntry(RegionSource src, TableEntry e) throws IOException {
        byte[] region = readRegion(src, e.offset, e.len);
        long uncompLen = u32be(region, 0);
        if (uncompLen == 0) return new byte[0];
        return gunzip(region, 4, region.length - 4, (int) uncompLen);
    }

    private static byte[] readRegion(RegionSource src, long offset, int length) throws IOException {
        byte[] buf = src.readRaw(offset, length);
        xorDecode(buf, offset);
        return buf;
    }

    /** Self-inverse XOR stream cipher, identical to {@code ChronoResources#xorDecode} -- also (ab)used by the test's ARC1 writer to obfuscate plaintext, since XOR-with-the-same-keystream is its own inverse. */
    static void xorDecode(byte[] buf, long regionStartOffset) {
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

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("unexpected EOF, wanted " + buf.length + " got " + off);
            off += n;
        }
    }

    static long u32le(byte[] b, int off) {
        return (b[off] & 0xffL) | (b[off + 1] & 0xffL) << 8
                | (b[off + 2] & 0xffL) << 16 | (b[off + 3] & 0xffL) << 24;
    }

    static long u32be(byte[] b, int off) {
        return (b[off] & 0xffL) << 24 | (b[off + 1] & 0xffL) << 16
                | (b[off + 2] & 0xffL) << 8 | (b[off + 3] & 0xffL);
    }

    static String cString(byte[] b, int off) {
        int end = off;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    // --- overlay ------------------------------------------------------------

    /**
     * Diffs {@code modResourcesBin} (a mod's full ARC1 repack) against
     * {@code gameSource} (the game's own archive) entry-by-entry: an entry
     * absent from the game archive, or whose uncompressed size differs, or
     * (sizes equal) whose decompressed bytes differ, is written as a loose
     * file at {@code <modDir>/<archive path>} (zip-slip checked via {@link
     * ModManager#safeResolve} -- entry names come from the mod's own
     * archive and are just as attacker-controlled as a zip entry name).
     * Streams entry-by-entry; never holds both archives fully in memory.
     * On success, deletes {@code modResourcesBin} (and its parent directory
     * too, if that leaves it empty -- the common case of a mod built as
     * {@code <wrapper>/resources.bin} with nothing else in the wrapper) and
     * writes a {@code .from-archive} marker under {@code modDir} recording
     * the counts. {@code gameSource} is NOT closed by this method (the
     * caller opened it, the caller closes it); {@code modResourcesBin} is
     * opened and closed internally.
     */
    public static Result overlay(File modResourcesBin, RegionSource gameSource, File modDir,
                                  ProgressCallback progress) throws IOException {
        int added = 0, changed = 0, identical = 0, total;
        try (FileRegionSource modSource = new FileRegionSource(modResourcesBin)) {
            Map<String, TableEntry> modTable = parseTable(modSource);
            Map<String, TableEntry> gameTable = parseTable(gameSource);
            total = modTable.size();
            int i = 0;
            long lastReport = -1;
            for (Map.Entry<String, TableEntry> me : modTable.entrySet()) {
                i++;
                String name = me.getKey();
                TableEntry modEntry = me.getValue();
                TableEntry gameEntry = gameTable.get(name);
                boolean write;
                if (gameEntry == null) {
                    write = true;
                    added++;
                } else {
                    long modLen = peekUncompLen(modSource, modEntry);
                    long gameLen = peekUncompLen(gameSource, gameEntry);
                    if (modLen != gameLen) {
                        write = true;
                        changed++;
                    } else {
                        byte[] modBytes = decodeEntry(modSource, modEntry);
                        byte[] gameBytes = decodeEntry(gameSource, gameEntry);
                        if (!Arrays.equals(modBytes, gameBytes)) {
                            write = true;
                            changed++;
                        } else {
                            write = false;
                            identical++;
                        }
                    }
                }
                if (write) {
                    writeEntry(modDir, name, decodeEntry(modSource, modEntry));
                }
                long now = System.currentTimeMillis();
                if (progress != null && (i == total || lastReport < 0 || now - lastReport >= 500)) {
                    progress.onProgress(i, total);
                    lastReport = now;
                }
            }
        }

        File parent = modResourcesBin.getParentFile();
        modResourcesBin.delete();
        if (parent != null && !parent.equals(modDir)) {
            String[] remaining = parent.list();
            if (remaining != null && remaining.length == 0) parent.delete();
        }
        writeMarker(modDir, total, added, changed, identical);
        return new Result(total, added, changed, identical);
    }

    private static void writeEntry(File modDir, String archivePath, byte[] data) throws IOException {
        File out = ModManager.safeResolve(modDir, archivePath);
        if (out == null) {
            throw new IOException("resources.bin overlay entry escapes mod directory: " + archivePath);
        }
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create directory " + parent);
        }
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(data);
        }
    }

    private static void writeMarker(File modDir, int total, int added, int changed, int identical) {
        File marker = new File(modDir, MARKER_NAME);
        String content = "total=" + total + " added=" + added + " changed=" + changed
                + " identical=" + identical + "\n";
        try (OutputStream os = new FileOutputStream(marker)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            android.util.Log.w(TAG, "mods: could not write " + MARKER_NAME + " for " + modDir, e);
        }
    }
}
