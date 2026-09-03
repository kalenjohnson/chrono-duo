package com.kalenjohnson.chronoduo.origart;

import android.content.res.AssetManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Standalone reader for the "ARC1" resources.bin file table -- just enough
 * to list every entry name. com.kalenjohnson.chronoduo.ChronoResources
 * already parses this same table (and does the real per-entry extraction,
 * which this class defers to via ChronoResources#extract), but keeps its
 * parsed table and the header/xor/gunzip helpers private, with no accessor
 * for the name list. Rather than edit that file, this duplicates the small
 * amount of table-walking logic needed to enumerate names; if
 * ChronoResources ever grows a public list()/entryNames(), this class (and
 * OrigArtRebuilder's use of it) can be deleted in favor of that.
 *
 * Kept in lockstep with ChronoResources' ARC1 format handling: gzip +
 * XOR-stream-cipher-by-absolute-offset container, little-endian table of
 * (nameOffset, entryOffset, entryLen) triples pointing into a C-string pool.
 */
final class ArcTable {
    private static final String ARCHIVE_ASSET = "resources.bin";

    private ArcTable() {}

    static List<String> listEntries(AssetManager gameAssets) throws IOException {
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
        for (int i = 0; i < entryCount; i++) {
            nameOff[i] = u32le(inflated, p);
            // entryOffset, entryLen not needed here; extraction goes through ChronoResources.
            p += 12;
        }
        List<String> names = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            names.add(cString(inflated, (int) nameOff[i]));
        }
        return names;
    }

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
