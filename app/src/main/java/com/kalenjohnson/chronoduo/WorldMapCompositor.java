package com.kalenjohnson.chronoduo;

/**
 * Pure-Java (no android.* imports) pixel core for rendering a Chrono Trigger
 * overworld map from the game's own asset bytes. Ported from
 * {@code tools/world_map/render_world.py} -- see
 * {@code tools/world_map/REPORT.md} for the disassembly evidence behind
 * every constant and rule here. Kept dependency-free so both
 * {@link WorldMapRenderer} (Android, via android.graphics.Bitmap) and the
 * desktop check harness ({@code tools/world_map/JavaRenderCheck.java}, via
 * java.awt.image.BufferedImage) can call the exact same compositing code.
 *
 * <p>Map format: {@code Game/world/Map/Map_%04d.dat} is 12,288 bytes = two
 * 96x64 u8 layers (layer 0 at byte 0, layer 1 at byte 0x1800, stride 0x1800
 * each); byte 0 in either layer means "no metatile" (transparent), not
 * metatile 0.
 *
 * <p>Chip pages: {@code Game/world/worldchip_<chip>_<plt>_<page>.png} is a
 * 512x512 ARGB sheet of 256 metatiles, 32x32px each (2x of the native 16x16
 * metatile), arranged {@code row = b >> 4, col = b & 0x0F}. Page 0 backs map
 * layer 0, page 1 backs map layer 1.
 *
 * <p>Compositing order (world::MapData::Expansion / WorldMap::LoadMap):
 * paint layer 0 with page 0 (background), then layer 1 with page 1 (base
 * terrain), then -- for every world except world 5 (Zeal, the sky, whose
 * layers are swapped and whose overlay bake is skipped) -- paint layer 0
 * with page 0 again on top (decoration). Within a metatile, compositing is
 * per pixel: a source pixel replaces the destination only when its alpha is
 * nonzero ("nonzero alpha wins"), never blended.
 */
public final class WorldMapCompositor {
    public static final int GRID_W = 96, GRID_H = 64;
    public static final int LAYER_BYTES = GRID_W * GRID_H;      // 0x1800
    public static final int MAP_FILE_BYTES = 2 * LAYER_BYTES;   // 0x3000

    public static final int SHEET_COLS = 16;
    public static final int CELL_PX = 32;
    public static final int PAGE_DIM = SHEET_COLS * CELL_PX;    // 512

    public static final int OUT_W = GRID_W * CELL_PX;           // 3072
    public static final int OUT_H = GRID_H * CELL_PX;           // 2048

    private WorldMapCompositor() {}

    /**
     * Composites one world's map into a new {@link #OUT_W}x{@link #OUT_H}
     * ARGB canvas (row-major, one {@code 0xAARRGGBB} int per pixel -- the
     * same packing {@code android.graphics.Bitmap#getPixels}/{@code
     * setPixels} and {@code java.awt.image.BufferedImage#getRGB}/{@code
     * setRGB} both use, so callers can hand this method decoded pixels from
     * either and get the array straight back out again).
     *
     * @param map    the raw 12,288-byte {@code Map_%04d.dat} contents
     *               (layer 0 then layer 1, {@link #LAYER_BYTES} each)
     * @param page0  the decoded {@code worldchip_<chip>_<plt>_0.png} pixels,
     *               {@link #PAGE_DIM}x{@link #PAGE_DIM}, row-major
     * @param page1  the decoded {@code worldchip_<chip>_<plt>_1.png} pixels,
     *               same shape
     * @param overlayLayer0OnTop whether to repaint layer 0/page 0 on top
     *               after the base pass -- {@code true} for every world
     *               except world 5
     */
    public static int[] composite(byte[] map, int[] page0, int[] page1, boolean overlayLayer0OnTop) {
        if (map == null || map.length != MAP_FILE_BYTES) {
            throw new IllegalArgumentException("map must be " + MAP_FILE_BYTES + " bytes, got "
                    + (map == null ? "null" : map.length));
        }
        if (page0 == null || page0.length != PAGE_DIM * PAGE_DIM) {
            throw new IllegalArgumentException("page0 must be " + (PAGE_DIM * PAGE_DIM) + " pixels, got "
                    + (page0 == null ? "null" : page0.length));
        }
        if (page1 == null || page1.length != PAGE_DIM * PAGE_DIM) {
            throw new IllegalArgumentException("page1 must be " + (PAGE_DIM * PAGE_DIM) + " pixels, got "
                    + (page1 == null ? "null" : page1.length));
        }

        int[] canvas = new int[OUT_W * OUT_H];
        paint(canvas, map, 0, page0);
        paint(canvas, map, LAYER_BYTES, page1);
        if (overlayLayer0OnTop) paint(canvas, map, 0, page0);
        return canvas;
    }

    /** Paints one layer (96x64 metatile indices, starting at {@code layerOff} in {@code map}) onto {@code canvas} using chip {@code page}. Index 0 skips the whole cell; within a drawn cell, only pixels with nonzero source alpha are copied. */
    private static void paint(int[] canvas, byte[] map, int layerOff, int[] page) {
        for (int y = 0; y < GRID_H; y++) {
            int dy = y * CELL_PX;
            int rowOff = layerOff + y * GRID_W;
            for (int x = 0; x < GRID_W; x++) {
                int b = map[rowOff + x] & 0xFF;
                if (b == 0) continue;
                int sy = (b >> 4) * CELL_PX;
                int sx = (b & 0x0F) * CELL_PX;
                int dx = x * CELL_PX;
                for (int py = 0; py < CELL_PX; py++) {
                    int srcRow = (sy + py) * PAGE_DIM + sx;
                    int dstRow = (dy + py) * OUT_W + dx;
                    for (int px = 0; px < CELL_PX; px++) {
                        int srcPixel = page[srcRow + px];
                        if ((srcPixel >>> 24) != 0) {
                            canvas[dstRow + px] = srcPixel;
                        }
                    }
                }
            }
        }
    }
}
