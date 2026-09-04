package com.kalenjohnson.chronoduo.origart;

/**
 * Pure-Java pixel core that rebuilds one Chrono Trigger FIELD "mapchip"
 * sheet page from the game's own ORIGINAL 1x SNES tile graphics.
 *
 * The port ships, per field chip set, a pre-baked 512x512
 * {@code Game/field/mapchip/mapchip_<chipTable>_<palette>_<page>.png}. Those
 * sheets are 2x, smoothed and colour-quantised bakes -- the blurry art the
 * game actually draws for its animated / front / priority chips (see
 * tools/field_art/REPORT.md sections 6 and 8a). This class reconstructs the
 * same page from
 * <ul>
 *   <li>{@code Game/field/map_bin/cg&lt;n&gt;.bin} -- 4bpp 1x tile banks,</li>
 *   <li>{@code Game/field/ChipTable/ChipTable_%04d.dat} -- metatile refs,</li>
 *   <li>{@code Game/field/palette_bin/plt&lt;n&gt;.bin} -- BGR555 palettes,</li>
 *   <li>{@code Game/field/BGSetTable/bgsettable_&lt;n&gt;.dat} -- which cg
 *       bank goes in which ChipData slot,</li>
 * </ul>
 * reproducing exactly what {@code ChipTable::Expansion} @ 0x562098 /
 * {@code ChipTable::setChip_8_8} @ 0x56212c build at runtime, then
 * pixel-doubles it. The result is the crisp original of the shipped smoothed
 * sheet.
 *
 * This is the exact port of {@code tools/field_art/rebuild_mapchip.py}'s
 * render path, verified pixel-identical against it by
 * {@code tools/field_art/JavaMapchipCheck.java}. Deliberately free of any
 * Android dependency so that harness can {@code javac} it standalone --
 * Android glue (resources.bin extraction, PNG encoding, enumeration) lives
 * in {@link MapchipRebuilder}. All addresses in the comments are
 * {@code libchrono.so} virtual addresses; see tools/field_art/REPORT.md.
 */
public final class MapchipCore {

    /** ChipData::Load @ 0x560dcc writes each bank at {@code this + slot*0x1000}. */
    public static final int CG_BANK_BYTES = 0x1000;
    /** 0x40 bytes = 128 px at 4bpp -- one row of a bank (the cg header's "bytes per row"). */
    public static final int CG_ROW_BYTES = 0x40;
    /** Slots 0..6 are tile banks; slot 7 is the ext heap at ChipData+0x7000 and is not a bank. */
    public static final int CG_SLOTS = 7;

    /** ChipTable::Load @ 0x5613dc: 256 metatiles x 4 x (u16 tileref + u8 prio). */
    public static final int CHIPTABLE_PAGE_BYTES = 256 * 4 * 3; // 3072
    /** A ChipTable_%04d.dat is exactly two pages (6144 bytes); pages 2/3 of a few sheets have no source. */
    public static final int CHIPTABLE_PAGES = 2;

    /** A page expands to a 256x256 8-bit index image (32x32 8x8 tiles = 16x16 metatiles of 16x16 px). */
    public static final int PAGE_PX = 256;
    /** The shipped mapchip PNG is 2x of that. */
    public static final int SHEET_SCALE = 2;
    public static final int SHEET_PX = PAGE_PX * SHEET_SCALE; // 512

    // setChip_8_8 tile-ref bit fields (0x56212c-0x5621e4).
    private static final int TILE_MASK = 0x3FF;   // bits 0..9
    private static final int BLANK_MASK = 0x380;  // `bics wzr, #0x380, word` -> all three set = blank
    private static final int HFLIP_BIT = 0x400;   // bit 10
    private static final int VFLIP_BIT = 0x800;   // bit 11
    private static final int PAL_SHIFT = 12;      // bits 12..15 -> high nibble of the 8-bit index

    // MapInfo::Load @ 0x562bc4 reads a 24-byte file as 10 u16 + 4 u8; these are
    // the u16 indices FieldMap::load @ 0x56da4c consumes.
    public static final int MAPINFO_FIELDS = 14;
    /** -> BGSetTable/bgsettable_%d.dat */
    public static final int MI_BGSET = 1;
    /** -> ChipTable_%04d.dat; also the "a" in mapchip_a_b_p. */
    public static final int MI_CHIPTABLE = 2;
    /** -> palette_bin/plt%d.bin; also the "b" in mapchip_a_b_p. */
    public static final int MI_PALETTE = 4;
    /** -> MapTable_%04d.dat (unused here, kept for completeness). */
    public static final int MI_MAPTABLE = 6;

    /**
     * bgsettable byte index -> ChipData slot, from FieldMap::load's
     * ChipData::Load calls at 0x56e41c-0x56e518. Byte 6 goes to slot 7 (the
     * ext heap, not a bank) and is skipped here; byte 7 goes to slot 6, which
     * is the one loaded with {@code full = true}.
     */
    private static final int[] BGSET_BYTE_TO_SLOT = {0, 1, 2, 3, 4, 5, -1, 6};
    /** The slot whose ChipData::Load call passes {@code full = true} (size forced to 0x1000). */
    public static final int FULL_SLOT = 6;

    private MapchipCore() {}

    /** MapInfo::Load @ 0x562bc4: ten little-endian u16 then four u8, 24 bytes total. */
    public static int[] readMapinfo(byte[] b) {
        if (b == null || b.length < 24) {
            throw new IllegalArgumentException("mapinfo must be 24 bytes, got " + (b == null ? -1 : b.length));
        }
        int[] v = new int[MAPINFO_FIELDS];
        for (int i = 0; i < 10; i++) {
            v[i] = (b[i * 2] & 0xFF) | ((b[i * 2 + 1] & 0xFF) << 8);
        }
        for (int i = 0; i < 4; i++) {
            v[10 + i] = b[20 + i] & 0xFF;
        }
        return v;
    }

    /**
     * Maps an 8-byte bgsettable to {@code int[CG_SLOTS]} of cg bank ids, -1
     * where the slot is left blank. FieldMap::load guards each byte with
     * {@code cbz} / {@code cmp #0xff}, so 0x00 and 0xFF both mean "no bank".
     */
    public static int[] bgsetSlots(byte[] bgset) {
        if (bgset == null || bgset.length < 8) {
            throw new IllegalArgumentException("bgsettable must be 8 bytes, got "
                    + (bgset == null ? -1 : bgset.length));
        }
        int[] slots = new int[CG_SLOTS];
        for (int i = 0; i < CG_SLOTS; i++) slots[i] = -1;
        for (int i = 0; i < 8; i++) {
            int slot = BGSET_BYTE_TO_SLOT[i];
            if (slot < 0) continue;            // byte 6 -> the ext heap
            int cg = bgset[i] & 0xFF;
            if (cg == 0x00 || cg == 0xFF) continue;
            slots[slot] = cg;
        }
        return slots;
    }

    /**
     * Builds the flat {@code CG_SLOTS * 0x1000} ChipData buffer FieldMap::load
     * assembles -- a 128 x 448 px LINEAR 4bpp bitmap at stride 0x40.
     *
     * @param bankBySlot raw {@code cg<n>.bin} file bytes per slot (index 0..6),
     *                   null for a blank slot. Each file is a 4-byte header
     *                   followed by the bitmap; ChipData::Load @ 0x560eb0
     *                   computes {@code size = full ? 0x1000 : b * ((a >> 1) &
     *                   0x3FFF)} from that header (0x8080 0x0040 -> 0x1000 for
     *                   every shipped field bank).
     */
    public static byte[] buildChipData(byte[][] bankBySlot) {
        byte[] buf = new byte[CG_SLOTS * CG_BANK_BYTES];
        for (int slot = 0; slot < CG_SLOTS; slot++) {
            byte[] raw = (bankBySlot != null && slot < bankBySlot.length) ? bankBySlot[slot] : null;
            if (raw == null || raw.length < 4) continue;
            int a = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8);
            int bRows = (raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8);
            int size = (slot == FULL_SLOT) ? CG_BANK_BYTES : bRows * ((a >> 1) & 0x3FFF);
            int n = Math.min(Math.min(size, raw.length - 4), CG_BANK_BYTES);
            if (n <= 0) continue;
            System.arraycopy(raw, 4, buf, slot * CG_BANK_BYTES, n);
        }
        return buf;
    }

    /**
     * ChipTable::Load @ 0x5613dc -> one page's 32x32 grid of u16 tile refs, in
     * the order ChipTable::Expansion @ 0x562098 walks it. Per metatile the
     * file holds four (u16 tileref, u8 prio) triples in TL, TR, BL, BR order,
     * landing at grid positions (2R,2C) (2R,2C+1) (2R+1,2C) (2R+1,2C+1).
     * Returns a row-major {@code int[32*32]}.
     */
    public static int[] loadChipTablePage(byte[] raw, int page) {
        int[] grid = new int[32 * 32];
        int off = page * CHIPTABLE_PAGE_BYTES;
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                for (int k = 0; k < 4; k++) {
                    int dy = k >> 1, dx = k & 1;   // TL, TR, BL, BR
                    int w = 0;
                    if (raw != null && off + 3 <= raw.length) {
                        w = (raw[off] & 0xFF) | ((raw[off + 1] & 0xFF) << 8);
                    }
                    off += 3;
                    grid[(row * 2 + dy) * 32 + (col * 2 + dx)] = w;
                }
            }
        }
        return grid;
    }

    /**
     * Reads the four priority bits per metatile (bit 0 of each of the four
     * per-tile bytes, `bfi` chain @ 0x561524) into a {@code int[16*16]}. Not
     * used by the sheet rebuild -- the shipped sheet carries no priority
     * information -- but the parse is here so callers that need it don't have
     * to duplicate the walk.
     */
    public static int[] loadChipTablePrio(byte[] raw, int page) {
        int[] prio = new int[16 * 16];
        int off = page * CHIPTABLE_PAGE_BYTES;
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                int bits = 0;
                for (int k = 0; k < 4; k++) {
                    int f = 0;
                    if (raw != null && off + 3 <= raw.length) f = raw[off + 2] & 0xFF;
                    off += 3;
                    bits |= (f & 1) << k;
                }
                prio[row * 16 + col] = bits;
            }
        }
        return prio;
    }

    /**
     * FieldMap::load 0x56e1f8-0x56e26c: a {@code plt<n>.bin} is a u16 entry
     * count followed by that many BGR555 words; each 5-bit channel is scaled
     * by {@code (c * 0x20E7F7) >> 18} (== round(c*255/31)). Returns 256
     * {@code 0x00RRGGBB} entries (unset entries stay black).
     */
    public static int[] loadPalette(byte[] raw) {
        int[] pal = new int[256];
        if (raw == null || raw.length < 2) return pal;
        int n = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8);
        int max = Math.min(Math.min(n, 256), (raw.length - 2) / 2);
        for (int i = 0; i < max; i++) {
            int v = (raw[2 + i * 2] & 0xFF) | ((raw[3 + i * 2] & 0xFF) << 8);
            int r = ((v & 31) * 0x20E7F7) >> 18;
            int g = (((v >> 5) & 31) * 0x20E7F7) >> 18;
            int b = (((v >> 10) & 31) * 0x20E7F7) >> 18;
            pal[i] = (r << 16) | (g << 8) | b;
        }
        return pal;
    }

    /**
     * ChipTable::Expansion @ 0x562098 + setChip_8_8 @ 0x56212c: expands one
     * 32x32 tile-ref grid into the 256x256 8-bit index page the runtime keeps
     * at {@code chipTable + 0x3200 + page*0x10000}.
     *
     * <p>Index 0 is the transparency key: setChip_8_8 does {@code csel w13,
     * wzr, w13, eq} on a zero 4bpp nibble, so colour 0 of <em>every</em>
     * 16-colour group maps to output index 0, never to {@code pal*16}. Getting
     * that wrong puts a halo round every metatile.</p>
     *
     * @param chipData the {@code CG_SLOTS * 0x1000} buffer from {@link #buildChipData}
     * @param grid     a page grid from {@link #loadChipTablePage}
     * @return {@code byte[PAGE_PX*PAGE_PX]} of 8-bit indices, row-major
     */
    public static byte[] expandPage(byte[] chipData, int[] grid) {
        byte[] out = new byte[PAGE_PX * PAGE_PX];
        int bankRowPx = CG_ROW_BYTES * 2; // 128 px per ChipData row
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int w = grid[y * 32 + x] & 0xFFFF;
                if ((w & BLANK_MASK) == BLANK_MASK) continue;  // `bics wzr, #0x380, word`
                int tile = w & TILE_MASK;
                int ty = (tile >> 4) * 8;                      // row of 8x8 tiles in the 128px-wide bank area
                int tx = (tile & 0x0F) * 8;                    // pixel column of the tile
                boolean hflip = (w & HFLIP_BIT) != 0;
                boolean vflip = (w & VFLIP_BIT) != 0;
                int palHi = ((w >> PAL_SHIFT) & 0x0F) << 4;
                for (int row = 0; row < 8; row++) {
                    int srcRow = ty + (vflip ? 7 - row : row);
                    int dstBase = (y * 8 + row) * PAGE_PX + x * 8;
                    int rowByteBase = srcRow * CG_ROW_BYTES;
                    for (int col = 0; col < 8; col++) {
                        int v = tx + (hflip ? 7 - col : col);  // pixel column in the 128px bank
                        if (v >= bankRowPx) continue;
                        int byteOff = rowByteBase + (v >> 1);
                        if (byteOff < 0 || byteOff >= chipData.length) continue;
                        int by = chipData[byteOff] & 0xFF;
                        // HIGH nibble = LEFT pixel (`lsr w15,w13,#4` / `csel` @ 0x5622b4)
                        int nib = ((v & 1) == 1) ? (by & 0x0F) : (by >> 4);
                        out[dstBase + col] = (byte) (nib == 0 ? 0 : (nib | palHi));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Colours an expanded index page and pixel-doubles it into the shipped
     * sheet's 512x512 geometry.
     *
     * @param idx 256x256 indices from {@link #expandPage}
     * @param pal 256 {@code 0x00RRGGBB} entries from {@link #loadPalette}
     * @return {@code int[SHEET_PX*SHEET_PX]} of NON-premultiplied ARGB; index 0
     *         becomes alpha 0 (its RGB is still {@code pal[0]}, matching
     *         rebuild_mapchip.py byte for byte), everything else alpha 255.
     */
    public static int[] colorize2x(byte[] idx, int[] pal) {
        int[] out = new int[SHEET_PX * SHEET_PX];
        for (int y = 0; y < PAGE_PX; y++) {
            int rowBase0 = (y * SHEET_SCALE) * SHEET_PX;
            int rowBase1 = rowBase0 + SHEET_PX;
            for (int x = 0; x < PAGE_PX; x++) {
                int i = idx[y * PAGE_PX + x] & 0xFF;
                int argb = pal[i] | (i == 0 ? 0 : 0xFF000000);
                int c = x * SHEET_SCALE;
                out[rowBase0 + c] = argb;
                out[rowBase0 + c + 1] = argb;
                out[rowBase1 + c] = argb;
                out[rowBase1 + c + 1] = argb;
            }
        }
        return out;
    }

    /**
     * One-shot convenience: raw file bytes in, 512x512 non-premultiplied ARGB
     * out. Equivalent to {@code colorize2x(expandPage(buildChipData(banks),
     * loadChipTablePage(chipTable, page)), loadPalette(plt))}.
     */
    public static int[] renderSheet2x(byte[][] bankBySlot, byte[] chipTable, byte[] plt, int page) {
        byte[] chipData = buildChipData(bankBySlot);
        int[] grid = loadChipTablePage(chipTable, page);
        byte[] idx = expandPage(chipData, grid);
        return colorize2x(idx, loadPalette(plt));
    }

    // ----------------------------------------------------------------------
    // OVERWORLD ("worldchip") variant.
    //
    // The overworld runs a parallel, DIFFERENT implementation of the same idea
    // -- world::ChipTable::Load @ 0x6044c8, world::ChipTable::Expansion @
    // 0x604770, world::ChipTable::setChip_8_8 @ 0x604830, world::ChipData::Load
    // @ 0x606d9c -- and its tile-reference bit layout is NOT the field's:
    //
    //   field  (setChip_8_8 @ 0x56212c):        0-9 tile, 10 hflip, 11 vflip,
    //                                           12-15 palette (4 bits)
    //   world  (world::setChip_8_8 @ 0x604830): 0-9 tile, 10-12 palette (3 bits),
    //                                           13 priority, 14 hflip, 15 vflip
    //
    // and its Chip_%04d.dat stores 2 bytes per tile ref (no per-tile priority
    // byte; priority is bit 13 of the word). Everything downstream of the index
    // page -- loadPalette, colorize2x -- is shared verbatim.
    // See tools/world_art/REPORT.md.
    // ----------------------------------------------------------------------

    /** world::ChipData::Load @ 0x606d9c: WorldMap::LoadMap fills slots 0..6; there is no ext heap. */
    public static final int WORLD_CG_SLOTS = 7;
    /**
     * The ONLY "no bank" sentinel in world::ChipData::Load (`cmp w1, #0x80;
     * b.eq <ret>`). Unlike the field, 0 is a REAL bank id here -- cg0.bin is
     * world 0's slot 0 -- so {@link #bgsetSlots}' 0x00/0xFF rule must not be
     * reused.
     */
    public static final int WORLD_CG_NONE = 128;
    /** world::ChipTable::Load @ 0x6044c8: 256 metatiles x 4 x u16, no priority byte. */
    public static final int WORLD_CHIPTABLE_PAGE_BYTES = 256 * 4 * 2; // 2048
    /** A Chip_%04d.dat is exactly two pages (4096 bytes). */
    public static final int WORLD_CHIPTABLE_PAGES = 2;

    // world::ChipTable::setChip_8_8 @ 0x604830 tile-ref bit fields.
    private static final int WORLD_PAL_SHIFT = 10;   // `lsr w13,w5,#6; and w9,w13,#0x70`
    private static final int WORLD_PAL_MASK = 0x7;   // THREE bits, not four
    private static final int WORLD_HFLIP_BIT = 0x4000; // `sbfx w14,w5,#14,#1` -> nibble select
    private static final int WORLD_VFLIP_BIT = 0x8000; // `sbfx w9,w5,#15,#1`  -> ORs 7 into the row
    /** bit 13 -- world::ChipTable::Load's per-metatile priority byte (`bfxil w8,w20,#13,#1`). Not used by the sheet rebuild. */
    public static final int WORLD_PRIO_BIT = 0x2000;

    /**
     * {@code WorldMapInfo::G_WORLDMAPINFO} @ 0xbe2508 (resolved from the
     * {@code R_AARCH64_GLOB_DAT} at 0xbc70d8; {@code WORLDMAPINFO_MAX} @
     * 0xbe2688 reads 8, and 0xbe2688-0xbe2508 = 0x180 = 8 x 0x30), read as
     * 8 rows of 24 little-endian u16. The columns kept here are the ones
     * {@code WorldMap::LoadMap} @ 0x606a30 consumes for the chip sheets:
     * <pre>
     *   [0..6] u16 0..6 (+0x00..+0x0C) -> world::ChipData::Load(id, slot), slots 0..6
     *   [7]    u16 10   (+0x14)        -> plt&lt;n&gt;.bin      -- the "b" in worldchip_a_b_p
     *   [8]    u16 16   (+0x20)        -> Chip_%04d.dat  -- the "a" in worldchip_a_b_p
     * </pre>
     * Hardcoded rather than parsed: there is no {@code bgsettable} equivalent
     * for the overworld in resources.bin (the bank ids live only in this
     * in-binary table), and there are exactly 8 rows. {@code
     * tools/world_art/rebuild_worldchip.py --libchrono} re-reads the table out
     * of the ELF and {@code tools/world_art/JavaWorldchipCheck.java} proves
     * this copy renders the same sheets, so the constant is checked, not
     * trusted.
     */
    public static final int[][] WORLDMAPINFO = {
            {0, 1, 2, 3, 4, 5, 6, 4, 0},          // world 0  1000 AD
            {0, 1, 2, 3, 4, 5, 6, 5, 0},          // world 1   600 AD
            {0, 1, 11, 12, 13, 14, 128, 7, 2},    // world 2  2300 AD   (slot 6 empty)
            {27, 28, 29, 30, 31, 32, 33, 8, 3},   // world 3  65,000,000 BC
            {0, 1, 2, 3, 15, 16, 17, 9, 4},       // world 4  12,000 BC, Zeal aloft
            {18, 19, 22, 23, 24, 25, 26, 10, 5},  // world 5  Kingdom of Zeal (sky)
            {0, 1, 2, 3, 15, 16, 17, 9, 4},       // world 6  12,000 BC, Zeal fallen
            {0, 1, 7, 8, 9, 10, 6, 6, 1},         // world 7  advanced future (cutscene)
    };
    /** Index of the palette id inside a {@link #WORLDMAPINFO} row. */
    public static final int WMI_PALETTE = 7;
    /** Index of the ChipTable id inside a {@link #WORLDMAPINFO} row. */
    public static final int WMI_CHIP = 8;

    /**
     * The distinct {@code (chip, palette, cgSlots[7])} sheet groups behind the
     * 7 shipped {@code worldchip_<a>_<b>_{0,1}.png} pairs, in ascending
     * (chip, palette) order. Worlds 4 and 6 share (4, 9) <em>and</em> have
     * identical cg slots, so a sheet maps unambiguously to the banks that bake
     * it (unlike the field's (62,21) bgset ambiguity); this method asserts
     * that rather than assuming it.
     *
     * @return one {@code int[9]} per sheet pair, laid out like a
     *         {@link #WORLDMAPINFO} row
     */
    public static int[][] worldSheetPairs() {
        java.util.TreeMap<Integer, int[]> byKey = new java.util.TreeMap<>();
        for (int[] row : WORLDMAPINFO) {
            int key = (row[WMI_CHIP] << 16) | row[WMI_PALETTE];
            int[] prev = byKey.get(key);
            if (prev == null) {
                byKey.put(key, row);
            } else {
                for (int s = 0; s < WORLD_CG_SLOTS; s++) {
                    if (prev[s] != row[s]) {
                        throw new IllegalStateException("ambiguous cg banks for worldchip_"
                                + row[WMI_CHIP] + "_" + row[WMI_PALETTE]);
                    }
                }
            }
        }
        return byKey.values().toArray(new int[0][]);
    }

    /**
     * Builds the flat {@code WORLD_CG_SLOTS * 0x1000} ChipData buffer
     * {@code WorldMap::LoadMap} assembles -- the same 128 x 448 px LINEAR 4bpp
     * bitmap at stride 0x40 the field uses, but sized from the cg header's
     * SECOND u16 alone: world::ChipData::Load @ 0x606e80-0x606e9c reads two
     * {@code getShort()}s, <em>discards the first</em> and computes
     * {@code size = rows &lt;&lt; 6}. Identical to the field's
     * {@code b * ((a &gt;&gt; 1) &amp; 0x3FFF)} for every shipped bank (header
     * {@code 80 80 40 00}), but the code genuinely differs.
     *
     * @param bankBySlot raw {@code cg<n>.bin} bytes per slot 0..6, null for an
     *                   empty slot
     */
    public static byte[] worldBuildChipData(byte[][] bankBySlot) {
        byte[] buf = new byte[WORLD_CG_SLOTS * CG_BANK_BYTES];
        for (int slot = 0; slot < WORLD_CG_SLOTS; slot++) {
            byte[] raw = (bankBySlot != null && slot < bankBySlot.length) ? bankBySlot[slot] : null;
            if (raw == null || raw.length < 4) continue;
            int rows = (raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8);
            int n = Math.min(Math.min(rows * CG_ROW_BYTES, raw.length - 4), CG_BANK_BYTES);
            if (n <= 0) continue;
            System.arraycopy(raw, 4, buf, slot * CG_BANK_BYTES, n);
        }
        return buf;
    }

    /**
     * world::ChipTable::Load @ 0x6044c8 -> one page's 32x32 grid of u16 tile
     * refs, in the order world::ChipTable::Expansion @ 0x604770 walks it. Per
     * metatile the file holds four u16 in TL, TR, BL, BR order, landing at grid
     * positions (2R,2C) (2R,2C+1) (2R+1,2C) (2R+1,2C+1) -- the runtime widens
     * them to u32 at {@code page*0x1000 + Y*0x80 + X*4}.
     */
    public static int[] worldLoadChipTablePage(byte[] raw, int page) {
        int[] grid = new int[32 * 32];
        int off = page * WORLD_CHIPTABLE_PAGE_BYTES;
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                for (int k = 0; k < 4; k++) {
                    int dy = k >> 1, dx = k & 1;   // TL, TR, BL, BR
                    int w = 0;
                    if (raw != null && off + 2 <= raw.length) {
                        w = (raw[off] & 0xFF) | ((raw[off + 1] & 0xFF) << 8);
                    }
                    off += 2;
                    grid[(row * 2 + dy) * 32 + (col * 2 + dx)] = w;
                }
            }
        }
        return grid;
    }

    /**
     * world::ChipTable::Expansion @ 0x604770 + world::setChip_8_8 @ 0x604830:
     * expands one 32x32 tile-ref grid into the 256x256 8-bit index page the
     * runtime keeps at {@code chipTable + 0x2200 + page*0x10000}.
     *
     * <p>Same blank rule as the field ({@code bics wzr, #0x380, word} -- bits
     * 7, 8 and 9 all set), same "nibble 0 -> output index 0" transparency key
     * ({@code csel w13, wzr, w13, eq}), same HIGH-nibble-is-the-left-pixel
     * packing. Only the palette/flip bits move (see the class comment).</p>
     */
    public static byte[] worldExpandPage(byte[] chipData, int[] grid) {
        byte[] out = new byte[PAGE_PX * PAGE_PX];
        int bankRowPx = CG_ROW_BYTES * 2; // 128 px per ChipData row
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int w = grid[y * 32 + x] & 0xFFFF;
                if ((w & BLANK_MASK) == BLANK_MASK) continue;
                int tile = w & TILE_MASK;
                int ty = (tile >> 4) * 8;
                int tx = (tile & 0x0F) * 8;
                boolean hflip = (w & WORLD_HFLIP_BIT) != 0;
                boolean vflip = (w & WORLD_VFLIP_BIT) != 0;
                int palHi = ((w >> WORLD_PAL_SHIFT) & WORLD_PAL_MASK) << 4;
                for (int row = 0; row < 8; row++) {
                    int srcRow = ty + (vflip ? 7 - row : row);
                    int dstBase = (y * 8 + row) * PAGE_PX + x * 8;
                    int rowByteBase = srcRow * CG_ROW_BYTES;
                    for (int col = 0; col < 8; col++) {
                        int v = tx + (hflip ? 7 - col : col);
                        if (v >= bankRowPx) continue;
                        int byteOff = rowByteBase + (v >> 1);
                        if (byteOff < 0 || byteOff >= chipData.length) continue;
                        int by = chipData[byteOff] & 0xFF;
                        int nib = ((v & 1) == 1) ? (by & 0x0F) : (by >> 4);
                        out[dstBase + col] = (byte) (nib == 0 ? 0 : (nib | palHi));
                    }
                }
            }
        }
        return out;
    }

    /**
     * One-shot overworld convenience: raw file bytes in, 512x512
     * non-premultiplied ARGB out. {@link #loadPalette} and {@link #colorize2x}
     * are shared with the field path unchanged.
     */
    public static int[] worldRenderSheet2x(byte[][] bankBySlot, byte[] chipTable, byte[] plt, int page) {
        byte[] chipData = worldBuildChipData(bankBySlot);
        int[] grid = worldLoadChipTablePage(chipTable, page);
        byte[] idx = worldExpandPage(chipData, grid);
        return colorize2x(idx, loadPalette(plt));
    }
}
