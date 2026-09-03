package com.kalenjohnson.chronoduo.origart;

import java.io.IOException;

/**
 * Minimal parser for uncompressed indexed (paletted) Windows BMP files, as
 * used by the game's original 1x "Game/chara/bmp/&lt;name&gt;.bmp" art.
 *
 * The samples this was built against are BITMAPV4HEADER (biSize=108), 4bpp,
 * BI_RGB, bottom-up, with a 16-entry BGRx palette immediately following the
 * info header. 8bpp and 1bpp are also supported (same layout, 256- / 2-entry
 * palettes); anything else (RLE compression, 2/16/24/
 * 32bpp, palette sizes that don't fit) is rejected with IOException rather
 * than silently mis-decoded.
 *
 * Output is always top-down, row-major, one palette index per pixel
 * (0..255, stored as unsigned bytes), matching what PIL's Image.open(...)
 * in "P" mode + np.array(...) produces for the same file -- this is what
 * SheetRebuilder expects.
 */
public final class BmpIndexed {
    public final int width;
    public final int height;
    /** Row-major, top-down, one palette index (0..255) per pixel. */
    public final byte[] indices;
    /** Palette entries as ARGB ints, alpha forced to 0xFF. */
    public final int[] paletteArgb;

    private BmpIndexed(int width, int height, byte[] indices, int[] paletteArgb) {
        this.width = width;
        this.height = height;
        this.indices = indices;
        this.paletteArgb = paletteArgb;
    }

    public static BmpIndexed parse(byte[] data) throws IOException {
        if (data.length < 54 || data[0] != 'B' || data[1] != 'M') {
            throw new IOException("not a BMP file (bad magic)");
        }
        long bfOffBits = u32le(data, 10);
        long biSize = u32le(data, 14);
        if (14 + biSize > data.length) {
            throw new IOException("BMP info header extends past file end");
        }
        int width = i32le(data, 18);
        int heightRaw = i32le(data, 22);
        boolean topDown = heightRaw < 0;
        int height = Math.abs(heightRaw);
        int planes = u16le(data, 26);
        int bpp = u16le(data, 28);
        long compression = biSize >= 20 ? u32le(data, 30) : 0;
        if (planes != 1) {
            throw new IOException("unsupported BMP planes=" + planes);
        }
        if (bpp != 1 && bpp != 4 && bpp != 8) {
            throw new IOException("unsupported BMP bit depth=" + bpp + " (only 1/4/8bpp indexed supported)");
        }
        if (compression != 0) {
            throw new IOException("unsupported BMP compression=" + compression + " (only BI_RGB supported)");
        }
        if (width <= 0 || height <= 0) {
            throw new IOException("invalid BMP dimensions " + width + "x" + height);
        }

        long clrUsed = biSize >= 36 + 4 ? u32le(data, 46) : 0;
        int maxPaletteEntries = 1 << bpp;
        int paletteCount = clrUsed != 0 ? (int) clrUsed : maxPaletteEntries;
        if (paletteCount <= 0 || paletteCount > maxPaletteEntries) paletteCount = maxPaletteEntries;

        long paletteStart = 14 + biSize;
        long paletteBytesAvail = bfOffBits - paletteStart;
        int paletteEntriesAvail = (int) Math.max(0, paletteBytesAvail / 4);
        if (paletteEntriesAvail < paletteCount) paletteCount = paletteEntriesAvail;
        if (paletteStart + (long) paletteCount * 4 > data.length) {
            throw new IOException("BMP palette extends past file end");
        }

        int[] paletteArgb = new int[maxPaletteEntries];
        for (int i = 0; i < paletteCount; i++) {
            int off = (int) (paletteStart + i * 4L);
            int b = data[off] & 0xFF;
            int g = data[off + 1] & 0xFF;
            int r = data[off + 2] & 0xFF;
            paletteArgb[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        // Entries beyond paletteCount (shouldn't normally be referenced) default to opaque black.
        for (int i = paletteCount; i < maxPaletteEntries; i++) {
            paletteArgb[i] = 0xFF000000;
        }

        int rowStrideBytes = ((width * bpp + 31) / 32) * 4;
        long pixelDataLen = (long) rowStrideBytes * height;
        if (bfOffBits < 0 || bfOffBits + pixelDataLen > data.length) {
            throw new IOException("BMP pixel data extends past file end");
        }

        byte[] indices = new byte[width * height];
        for (int fileRow = 0; fileRow < height; fileRow++) {
            int outRow = topDown ? fileRow : (height - 1 - fileRow);
            int rowOff = (int) bfOffBits + fileRow * rowStrideBytes;
            int outRowBase = outRow * width;
            if (bpp == 8) {
                for (int x = 0; x < width; x++) {
                    indices[outRowBase + x] = data[rowOff + x];
                }
            } else if (bpp == 4) {
                for (int x = 0; x < width; x++) {
                    int b = data[rowOff + (x >> 1)] & 0xFF;
                    int idx = (x & 1) == 0 ? (b >> 4) & 0xF : b & 0xF;
                    indices[outRowBase + x] = (byte) idx;
                }
            } else { // bpp == 1 (two sheets in the game, e.g. c127_0/c157_0: single-colour silhouettes)
                for (int x = 0; x < width; x++) {
                    int b = data[rowOff + (x >> 3)] & 0xFF;
                    indices[outRowBase + x] = (byte) ((b >> (7 - (x & 7))) & 1);
                }
            }
        }

        return new BmpIndexed(width, height, indices, paletteArgb);
    }

    private static long u32le(byte[] b, int off) {
        return (b[off] & 0xffL) | (b[off + 1] & 0xffL) << 8
                | (b[off + 2] & 0xffL) << 16 | (b[off + 3] & 0xffL) << 24;
    }

    private static int i32le(byte[] b, int off) {
        return (int) u32le(b, off);
    }

    private static int u16le(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8;
    }
}
