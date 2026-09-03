package com.kalenjohnson.chronoduo.dsimport;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for Chrono Trigger DS's custom NCG/NCL/NSC minimap format. Direct
 * port of tools/ds_maps/decode_map.py -- see that file's docstring for the
 * on-disk format. Produces pixel-for-pixel identical output to
 * PIL-based render_map(), including its transparent-background handling.
 */
public final class MinimapDecoder {

    private MinimapDecoder() {}

    public static final class Rendered {
        public final int width;
        public final int height;
        /** ARGB pixels, row-major, 0xAARRGGBB (alpha 0 == fully transparent, matching PIL's (0,0,0,0) fill). */
        public final int[] argb;

        public Rendered(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static void requireMagic(byte[] data, String magic) {
        for (int i = 0; i < 4; i++) {
            char expected = i < magic.length() ? magic.charAt(i) : '\0';
            if ((data[i] & 0xFF) != expected) {
                throw new IllegalArgumentException("Bad magic, expected \"" + magic + "\\0\"");
            }
        }
    }

    /** Each tile is an 8x8 grid of palette indices, stored row-major [y][x]. */
    static final class NcgResult {
        List<int[][]> tiles;
        int bpp;
    }

    static NcgResult decodeNcg(byte[] decompressed) {
        requireMagic(decompressed, "NCG");
        int count = u16(decompressed, 4);
        int flag = u16(decompressed, 6);
        int bpp = flag != 0 ? 8 : 4;
        int tileBytes = bpp == 8 ? 64 : 32;

        NcgResult result = new NcgResult();
        result.bpp = bpp;
        result.tiles = new ArrayList<>(count);

        int off = 8;
        for (int i = 0; i < count; i++) {
            int[][] pixels = new int[8][8];
            if (bpp == 4) {
                for (int py = 0; py < 8; py++) {
                    for (int px = 0; px < 8; px += 2) {
                        int b = decompressed[off + py * 4 + px / 2] & 0xFF;
                        pixels[py][px] = b & 0xF;
                        pixels[py][px + 1] = (b >>> 4) & 0xF;
                    }
                }
            } else {
                for (int py = 0; py < 8; py++) {
                    for (int px = 0; px < 8; px++) {
                        pixels[py][px] = decompressed[off + py * 8 + px] & 0xFF;
                    }
                }
            }
            off += tileBytes;
            result.tiles.add(pixels);
        }
        return result;
    }

    /** Each color packed as 0x00RRGGBB. */
    static int[] decodeNcl(byte[] data) {
        requireMagic(data, "NCL");
        long count = u32(data, 4);
        int[] colors = new int[(int) count];
        int off = 8;
        for (int i = 0; i < count; i++) {
            int v = u16(data, off);
            off += 2;
            int r = (v & 0x1F) * 255 / 31;
            int g = ((v >>> 5) & 0x1F) * 255 / 31;
            int b = ((v >>> 10) & 0x1F) * 255 / 31;
            colors[i] = (r << 16) | (g << 8) | b;
        }
        return colors;
    }

    static final class NscResult {
        int w, h, flag;
        int[] entries;
    }

    static NscResult decodeNsc(byte[] data) {
        requireMagic(data, "NSC");
        NscResult result = new NscResult();
        int count = u16(data, 4);
        result.flag = u16(data, 6);
        result.w = data[8] & 0xFF;
        result.h = data[9] & 0xFF;
        // offset 10-11: reserved, ignored.
        result.entries = new int[count];
        int off = 12;
        for (int i = 0; i < count; i++) {
            result.entries[i] = u16(data, off);
            off += 2;
        }
        return result;
    }

    /**
     * Renders an area/room minimap. Mirrors decode_map.py's render_map()
     * with transparent0=True (the default, and the only mode used by
     * render_all.py).
     */
    public static Rendered renderMap(byte[] ncgDecompressed, byte[] nclData, byte[] nscData) {
        NcgResult ncg = decodeNcg(ncgDecompressed);
        int[] colors = decodeNcl(nclData);
        NscResult nsc = decodeNsc(nscData);

        int width = nsc.w * 8;
        int height = nsc.h * 8;
        int[] img = new int[width * height]; // all-zero == fully transparent, matches PIL's (0,0,0,0) fill

        int colorsPerPal = ncg.bpp == 4 ? 16 : 256;

        for (int ty = 0; ty < nsc.h; ty++) {
            for (int tx = 0; tx < nsc.w; tx++) {
                int entry = nsc.entries[ty * nsc.w + tx];
                int tileIdx = entry & 0x3FF;
                int hflip = (entry >>> 10) & 1;
                int vflip = (entry >>> 11) & 1;
                int palIdx = ncg.bpp == 4 ? (entry >>> 12) & 0xF : 0;

                if (tileIdx >= ncg.tiles.size()) continue;
                int[][] tile = ncg.tiles.get(tileIdx);

                for (int py = 0; py < 8; py++) {
                    int sy = vflip != 0 ? 7 - py : py;
                    for (int pxo = 0; pxo < 8; pxo++) {
                        int sx = hflip != 0 ? 7 - pxo : pxo;
                        int ci = tile[sy][sx];
                        if (ci == 0) continue; // transparent0

                        int colorIdx = palIdx * colorsPerPal + ci;
                        if (colorIdx >= colors.length) colorIdx = colorIdx % colors.length;
                        int rgb = colors[colorIdx];

                        int px = tx * 8 + pxo;
                        int pyAbs = ty * 8 + py;
                        img[pyAbs * width + px] = 0xFF000000 | rgb;
                    }
                }
            }
        }

        return new Rendered(width, height, img);
    }
}
