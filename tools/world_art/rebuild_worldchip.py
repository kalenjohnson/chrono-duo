#!/usr/bin/env python3
"""
rebuild_worldchip.py -- rebuild Chrono Trigger (Android / cocos2d-x port)
OVERWORLD "worldchip" sheets from the game's own ORIGINAL 1x SNES tile
graphics.

The port ships, per overworld chip set, a pre-baked 512x512
`Game/world/worldchip_<chip>_<palette>_<page>.png`.  Those sheets are 2x,
filtered (smoothed) and colour-quantised: they are the blurry art the game
actually draws for the overworld's front/priority chips.  This tool
reconstructs the same page from

    Game/world/map_bin/cg<n>.bin      4bpp 1x tile banks
    Game/world/Chip/Chip_%04d.dat     metatile definitions (2 pages)
    Game/world/plt_bin/plt<n>.bin     BGR555 palettes

reproducing exactly what `world::ChipTable::Expansion` @ 0x604770 /
`world::ChipTable::setChip_8_8` @ 0x604830 build at runtime, then
pixel-doubles it.  The result is the crisp original of the shipped smoothed
sheet.

Everything here is read out of the ARM64 disassembly of libchrono.so; see
REPORT.md in this directory for function names, addresses and evidence.

The overworld's tile-ref bit layout is NOT the field's:

    field  (ChipTable::setChip_8_8 @ 0x56212c):  10 hflip, 11 vflip, 12-15 palette
    world  (world::ChipTable::setChip_8_8 @ 0x604830):
           0-9 tile, 10-12 palette (3 bits), 13 priority, 14 hflip, 15 vflip

Usage:
    python3 rebuild_worldchip.py <extracted-Game/world-dir> <out-dir> [--crops]
    python3 rebuild_worldchip.py <dir> --list

<extracted-Game/world-dir> is laid out like the archive:
    Chip/Chip_%04d.dat  map_bin/cg<n>.bin  plt_bin/plt<n>.bin
    sheets/worldchip_<a>_<b>_<p>.png   (optional, only for verification)

Dependencies: Pillow, numpy.
"""
import argparse
import os
import struct
import sys

import numpy as np
from PIL import Image

# --------------------------------------------------------------------------
# Format constants -- all read out of libchrono.so, see REPORT.md
# --------------------------------------------------------------------------

# world::ChipData::Load @ 0x606d9c: dest = this + (slot << 12), and the size
# comes from the SECOND u16 of the 4-byte cg header alone (`lsl w2, w0, #6`,
# i.e. rows * 0x40).  The first u16 is read and discarded -- unlike the field,
# which computes b * ((a >> 1) & 0x3FFF).  Identical for every shipped bank
# (header 80 80 40 00 -> 64 rows * 0x40 = 0x1000) but the code differs.
CG_BANK_BYTES = 0x1000
CG_ROW_BYTES = 0x40          # 128 px at 4bpp
CG_SLOTS = 7                 # LoadMap fills slots 0..6; there is no ext heap
CG_NONE = 128                # `cmp w1, #0x80; b.eq <ret>` -- the ONLY "no bank"
                             # sentinel.  cg0.bin is a real bank (world 0 slot 0).

# world::ChipTable::Load @ 0x6044c8: per metatile 4 x u16 tileref, file order
# TL, TR, BL, BR; 16x16 metatiles per page, 2 pages.  NO priority byte (the
# field has one) -- priority is bit 13 of each word instead.
CHIPTABLE_PAGE_BYTES = 256 * 4 * 2          # 2048
CHIPTABLE_PAGES = 2                          # a Chip_%04d.dat is exactly 4096 B

# setChip_8_8 writes to chipTable + 0x2200 + page*0x10000, row stride 0x100:
# a 256x256 8-bit index page = 16x16 metatiles of 16x16 px, at 1x.
PAGE_PX = 256
CELL_PX = 16
SHEET_SCALE = 2
SHEET_PX = PAGE_PX * SHEET_SCALE             # 512

# world::ChipTable::setChip_8_8 @ 0x604830 tile-ref bit fields.
TILE_MASK = 0x3FF            # bits 0..9   (ubfiz w29,w5,#3,#4 | lsr/and #0x1f8)
BLANK_MASK = 0x380           # `bics wzr, #0x380, w5` -- all three set = blank
PAL_SHIFT = 10               # bits 10..12 -> `lsr w13,w5,#6; and w9,w13,#0x70`
PAL_MASK = 0x7               # only THREE palette bits, not four
PRIO_BIT = 0x2000            # bit 13 -- ChipTable::Load's per-metatile prio byte
HFLIP_BIT = 0x4000           # bit 14 -- `sbfx w14,w5,#14,#1`, selects the nibble
VFLIP_BIT = 0x8000           # bit 15 -- `sbfx w9,w5,#15,#1`, ORs 7 into the row

# WorldMapInfo::G_WORLDMAPINFO @ 0xbe2508 (R_AARCH64_GLOB_DAT at 0xbc70d8),
# 8 rows x 0x30 bytes, read as 24 little-endian u16.  WorldMap::LoadMap @
# 0x606a30 consumes:
#   u16 0..6  (+0x00..+0x0C) -> world::ChipData::Load(id, slot) for slots 0..6
#   u16 10    (+0x14)        -> world::Palette::Load       -> plt<n>.bin, "b"
#   u16 16    (+0x20)        -> world::ChipTable::Load     -> Chip_%04d.dat, "a"
#   u16 17    (+0x22)        -> world::MapData::Load       -> Map_%04d.dat
WORLDMAPINFO_VA = 0xbe2508
WORLDMAPINFO_ROWS = 8
WORLDMAPINFO_ROW_BYTES = 0x30
MI_CG0 = 0                   # u16 0..6 are the cg bank ids for slots 0..6
MI_PALETTE = 10              # -> plt%d.bin, the "b" in worldchip_a_b_p
MI_CHIP = 16                 # -> Chip_%04d.dat, the "a" in worldchip_a_b_p
MI_MAP = 17                  # -> Map_%04d.dat

# The table as it stands in the shipped libchrono.so, verified by
# read_worldmapinfo() below (and by tools/world_art/JavaWorldchipCheck.java
# against the Java copy).  world -> (cg slots 0..6, plt, chip, map).
WORLDMAPINFO = [
    ([0, 1, 2, 3, 4, 5, 6],       4,  0, 0),   # 0  1000 AD
    ([0, 1, 2, 3, 4, 5, 6],       5,  0, 1),   # 1   600 AD
    ([0, 1, 11, 12, 13, 14, 128], 7,  2, 3),   # 2  2300 AD (slot 6 empty)
    ([27, 28, 29, 30, 31, 32, 33], 8, 3, 4),   # 3  65,000,000 BC
    ([0, 1, 2, 3, 15, 16, 17],    9,  4, 5),   # 4  12,000 BC, Zeal aloft
    ([18, 19, 22, 23, 24, 25, 26], 10, 5, 7),  # 5  Kingdom of Zeal (sky)
    ([0, 1, 2, 3, 15, 16, 17],    9,  4, 6),   # 6  12,000 BC, Zeal fallen
    ([0, 1, 7, 8, 9, 10, 6],      6,  1, 2),   # 7  advanced future (cutscene)
]

# The 7 distinct (chip, plt) pairs, i.e. the 7 shipped worldchip sheet pairs.
# Worlds 4 and 6 share (4, 9) AND have identical cg slots, so the mapping from
# a sheet to the banks that bake it is unambiguous (unlike the field's (62,21)
# bgset ambiguity).
def sheet_pairs():
    seen = {}
    for wid, (slots, plt, chip, _map) in enumerate(WORLDMAPINFO):
        key = (chip, plt)
        if key in seen:
            assert seen[key][0] == slots, "ambiguous banks for worldchip_%d_%d" % key
            continue
        seen[key] = (slots, wid)
    return [(chip, plt, slots, wid) for (chip, plt), (slots, wid) in sorted(seen.items())]


# --------------------------------------------------------------------------


def read_worldmapinfo(so_path):
    """Re-read G_WORLDMAPINFO straight out of libchrono.so (ELF PT_LOAD walk),
    so the hardcoded WORLDMAPINFO above can be checked rather than trusted."""
    d = open(so_path, "rb").read()
    if d[:4] != b"\x7fELF":
        raise ValueError("not an ELF: " + so_path)
    e_phoff = struct.unpack_from("<Q", d, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", d, 0x36)[0]
    e_phnum = struct.unpack_from("<H", d, 0x38)[0]
    segs = []
    for i in range(e_phnum):
        p = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", d, p)[0]
        if p_type != 1:                      # PT_LOAD
            continue
        p_offset, p_vaddr = struct.unpack_from("<QQ", d, p + 0x08)
        p_filesz = struct.unpack_from("<Q", d, p + 0x20)[0]
        segs.append((p_offset, p_vaddr, p_filesz))
    off = None
    for p_offset, p_vaddr, p_filesz in segs:
        if p_vaddr <= WORLDMAPINFO_VA < p_vaddr + p_filesz:
            off = p_offset + (WORLDMAPINFO_VA - p_vaddr)
    if off is None:
        raise ValueError("0x%x not mapped by any PT_LOAD" % WORLDMAPINFO_VA)
    rows = []
    for w in range(WORLDMAPINFO_ROWS):
        v = struct.unpack_from("<24H", d, off + w * WORLDMAPINFO_ROW_BYTES)
        rows.append((list(v[MI_CG0:MI_CG0 + CG_SLOTS]), v[MI_PALETTE], v[MI_CHIP], v[MI_MAP]))
    return rows


def load_chipdata(root, slots):
    """Build the flat 7 x 0x1000 ChipData buffer WorldMap::LoadMap assembles --
    a 128 x 448 px LINEAR 4bpp bitmap at stride 0x40.  Slot id 128 = no bank."""
    buf = bytearray(CG_SLOTS * CG_BANK_BYTES)
    used = {}
    for slot, cg in enumerate(slots):
        if cg == CG_NONE:
            continue
        path = os.path.join(root, "map_bin", "cg%d.bin" % cg)
        if not os.path.exists(path):
            print("  warning: %s missing, slot %d left blank" % (path, slot), file=sys.stderr)
            continue
        raw = open(path, "rb").read()
        _a, rows = struct.unpack_from("<HH", raw, 0)
        n = min(rows * CG_ROW_BYTES, len(raw) - 4, CG_BANK_BYTES)
        buf[slot * CG_BANK_BYTES: slot * CG_BANK_BYTES + n] = raw[4:4 + n]
        used[slot] = cg
    return bytes(buf), used


def load_chiptable(root, chip_id):
    """world::ChipTable::Load @ 0x6044c8 -> pages[page][y][x] = u16 tile ref, on
    the 32x32 grid Expansion walks (page base this + page*0x1000, row stride
    0x80, 4 bytes per entry; the file stores 2 bytes per entry)."""
    raw = open(os.path.join(root, "Chip", "Chip_%04d.dat" % chip_id), "rb").read()
    pages = []
    for p in range(CHIPTABLE_PAGES):
        grid = np.zeros((32, 32), dtype=np.uint16)
        off = p * CHIPTABLE_PAGE_BYTES
        for row in range(16):
            for col in range(16):
                for dy, dx in ((0, 0), (0, 1), (1, 0), (1, 1)):
                    w = struct.unpack_from("<H", raw, off)[0] if off + 2 <= len(raw) else 0
                    off += 2
                    grid[row * 2 + dy, col * 2 + dx] = w
        pages.append(grid)
    return pages


def load_palette(root, plt_id):
    """world::Palette::Load @ 0x604070: u16 count, then `count` BGR555 words
    (r = bits 0..4, g = 5..9, b = 10..14, `Color4F(r/31, g/31, b/31, i ? 1:0)`).
    The float->u8 rounding mode is not recoverable from the shipped sheets (the
    residual is +-1 and their adaptive 256-colour quantisation is larger), so
    the field's exact `(c * 0x20E7F7) >> 18` is reused verbatim -- it is what
    MapchipCore.loadPalette does, keeping Python and Java bit-identical."""
    raw = open(os.path.join(root, "plt_bin", "plt%d.bin" % plt_id), "rb").read()
    n = struct.unpack_from("<H", raw, 0)[0]
    pal = np.zeros((256, 3), dtype=np.uint8)
    for i in range(min(n, 256, (len(raw) - 2) // 2)):
        v = struct.unpack_from("<H", raw, 2 + i * 2)[0]
        r, g, b = v & 31, (v >> 5) & 31, (v >> 10) & 31
        pal[i] = ((r * 0x20E7F7) >> 18, (g * 0x20E7F7) >> 18, (b * 0x20E7F7) >> 18)
    return pal


def expand_page(chipdata, grid):
    """world::ChipTable::Expansion @ 0x604770 + setChip_8_8 @ 0x604830.

    Returns a 256x256 uint8 index page.  Index 0 is transparent: setChip_8_8
    does `csel w13, wzr, w13, eq` on a zero 4bpp nibble, so colour 0 of *every*
    8-colour-group maps to output index 0, never to pal*16."""
    b = np.frombuffer(chipdata, dtype=np.uint8)
    px = np.empty(b.size * 2, dtype=np.uint8)
    px[0::2] = b >> 4                                # HIGH nibble = LEFT pixel
    px[1::2] = b & 0x0F
    px = px.reshape(-1, CG_ROW_BYTES * 2)            # (448, 128)

    out = np.zeros((PAGE_PX, PAGE_PX), dtype=np.uint8)
    for y in range(32):
        for x in range(32):
            w = int(grid[y, x])
            if (w & BLANK_MASK) == BLANK_MASK:
                continue
            tile = w & TILE_MASK
            ty, tx = (tile >> 4) * 8, (tile & 0x0F) * 8
            t = px[ty:ty + 8, tx:tx + 8]
            if w & HFLIP_BIT:
                t = t[:, ::-1]
            if w & VFLIP_BIT:
                t = t[::-1, :]
            pal = (w >> PAL_SHIFT) & PAL_MASK
            out[y * 8:y * 8 + 8, x * 8:x * 8 + 8] = np.where(t == 0, 0, t | (pal << 4))
    return out


def render_sheet(root, chip_id, plt_id, slots, page):
    chipdata, used = load_chipdata(root, slots)
    grids = load_chiptable(root, chip_id)
    pal = load_palette(root, plt_id)
    idx = expand_page(chipdata, grids[page])
    rgba = np.zeros((PAGE_PX, PAGE_PX, 4), dtype=np.uint8)
    rgba[:, :, :3] = pal[idx]
    rgba[:, :, 3] = np.where(idx == 0, 0, 255)
    one = Image.fromarray(rgba, "RGBA")
    two = Image.fromarray(np.repeat(np.repeat(rgba, SHEET_SCALE, 0), SHEET_SCALE, 1), "RGBA")
    return idx, one, two, used, grids[page]


# --------------------------------------------------------------------------
# verification
# --------------------------------------------------------------------------


def check_invariants(grid, slots):
    """Shipped-PNG-independent sanity: every non-blank tile ref must address a
    filled slot, and `tile < 0x380` must hold (7 slots x 128 tiles = 896 =
    0x380, exactly the blank threshold -- the same self-proving constant as the
    field)."""
    bad_slot = bad_range = 0
    for w in np.unique(grid):
        w = int(w)
        if (w & BLANK_MASK) == BLANK_MASK:
            continue
        tile = w & TILE_MASK
        if tile >= CG_SLOTS * 128:
            bad_range += int((grid == w).sum())
            continue
        if slots[tile // 128] == CG_NONE:
            bad_slot += int((grid == w).sum())
    return bad_slot, bad_range


def compare(root, chip_id, plt_id, page, rebuilt_2x):
    """Compare the rebuilt 2x sheet with the shipped one.  The shipped sheet is
    2x, smoothed and quantised to an adaptive 256-colour palette that is not the
    game's plt, so only RGB metrics mean anything (see the field REPORT):

      alpha       transparent-mask agreement (pure structure, no colour)
      flat_q<=N   rebuilt 1x pixel vs the shipped 2x2 blocks its filter left
                  flat, allowing an RGB channel error of N
      near<=16    rebuilt 1x pixel vs the AVERAGE of the shipped 2x2 block,
                  i.e. allowing the smoothing filter too
      bad_cells   16x16-metatile cells where near<=16 holds for < 80% of the
                  cell's pixels -- the metric that actually catches a wrong cg
                  bank in a slot (an aggregate would hide it)
    """
    path = os.path.join(root, "sheets", "worldchip_%d_%d_%d.png" % (chip_id, plt_id, page))
    ship = np.asarray(Image.open(path).convert("RGBA")).astype(np.int32)
    mine = np.asarray(rebuilt_2x).astype(np.int32)
    if ship.shape != mine.shape:
        raise ValueError("size mismatch %r vs %r" % (ship.shape, mine.shape))

    sa = ship[:, :, 3] != 0
    ma = mine[:, :, 3] != 0
    alpha = float((sa == ma).mean())
    op = ma & sa

    s = ship[:, :, :3].reshape(PAGE_PX, 2, PAGE_PX, 2, 3)
    m1 = mine[0::2, 0::2, :3]
    op1 = op[0::2, 0::2]
    flat = (np.all(s[:, 0, :, 0] == s[:, 0, :, 1], -1) & np.all(s[:, 0, :, 0] == s[:, 1, :, 0], -1)
            & np.all(s[:, 0, :, 0] == s[:, 1, :, 1], -1))
    fsel = flat & op1
    nf = int(fsel.sum())
    fd = np.abs(s[:, 0, :, 0] - m1).max(axis=-1)
    f = lambda sel: (float(sel.sum()) / nf) if nf else None

    avg = s.mean(axis=(1, 3))
    d = np.abs(avg - m1).max(axis=-1)
    n1 = int(op1.sum())
    near = float(((d <= 16) & op1).sum()) / n1 if n1 else None

    ok = (d <= 16) | ~op1
    cell = ok.reshape(16, CELL_PX, 16, CELL_PX).mean(axis=(1, 3))
    bad_cells = sorted(map(tuple, np.argwhere(cell < 0.8).tolist()))

    return dict(alpha=alpha, flat_n=nf, flat_q4=f((fd <= 4) & fsel), flat_q8=f((fd <= 8) & fsel),
                near=near, bad_cells=bad_cells, shipped=path)


def side_by_side(root, chip_id, plt_id, page, rebuilt_2x, out_path, win=96, zoom=3):
    """Shipped (left) vs rebuilt (right) on the highest-variance window."""
    ship = Image.open(os.path.join(root, "sheets", "worldchip_%d_%d_%d.png"
                                   % (chip_id, plt_id, page))).convert("RGBA")
    sa = np.asarray(ship).astype(np.int32)
    best = None
    for y in range(0, sa.shape[0] - win + 1, 32):
        for x in range(0, sa.shape[1] - win + 1, 32):
            v = sa[y:y + win, x:x + win, :3].std()
            if best is None or v > best[0]:
                best = (v, x, y)
    _, x, y = best
    box = (x, y, x + win, y + win)
    grey = Image.new("RGBA", (win, win), (40, 40, 40, 255))
    a = Image.alpha_composite(grey, ship.crop(box))
    b = Image.alpha_composite(grey, rebuilt_2x.crop(box))
    im = Image.new("RGB", (win * 2 + 6, win), (255, 0, 255))
    im.paste(a.convert("RGB"), (0, 0))
    im.paste(b.convert("RGB"), (win + 6, 0))
    im = im.resize((im.width * zoom, im.height * zoom), Image.NEAREST)
    im.save(out_path)
    return out_path, box


# --------------------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", help="extracted Game/world directory")
    ap.add_argument("out_dir", nargs="?", default=None)
    ap.add_argument("--list", action="store_true", help="print the sheet/bank table and exit")
    ap.add_argument("--crops", action="store_true", help="also write side-by-side crops")
    ap.add_argument("--libchrono", help="verify WORLDMAPINFO against this libchrono.so")
    args = ap.parse_args()

    if args.libchrono:
        rows = read_worldmapinfo(args.libchrono)
        ok = rows == WORLDMAPINFO
        print("G_WORLDMAPINFO @ 0x%x: %s" % (WORLDMAPINFO_VA, "MATCHES table" if ok else "DIFFERS"))
        for w, r in enumerate(rows):
            print("  world %d  cg=%s plt=%d chip=%d map=%d" % (w, r[0], r[1], r[2], r[3]))
        if not ok:
            return 1

    pairs = sheet_pairs()
    if args.list:
        for chip, plt_id, slots, wid in pairs:
            print("worldchip_%d_%d_{0,1}  cg=%s  (first world %d)" % (chip, plt_id, slots, wid))
        return 0

    if not args.out_dir:
        ap.error("out_dir required")
    os.makedirs(args.out_dir, exist_ok=True)

    for chip, plt_id, slots, wid in pairs:
        for page in (0, 1):
            idx, one, two, used, grid = render_sheet(args.root, chip, plt_id, slots, page)
            stem = "worldchip_%d_%d_%d" % (chip, plt_id, page)
            two.save(os.path.join(args.out_dir, stem + ".png"))
            one.save(os.path.join(args.out_dir, stem + "_1x.png"))
            bad_slot, bad_range = check_invariants(grid, slots)
            line = "%s  cg=%s  inv:slot=%d,range=%d" % (stem, used, bad_slot, bad_range)
            try:
                r = compare(args.root, chip, plt_id, page, two)
                fmt = lambda v: "n/a   " if v is None else "%.4f" % v
                line += ("  alpha=%.4f flat=%d flat_q4=%s flat_q8=%s near16=%s bad_cells=%d %s"
                         % (r["alpha"], r["flat_n"], fmt(r["flat_q4"]), fmt(r["flat_q8"]),
                            fmt(r["near"]), len(r["bad_cells"]), r["bad_cells"] or ""))
            except FileNotFoundError:
                line += "  (no shipped sheet)"
            print(line)
            if args.crops:
                p, box = side_by_side(args.root, chip, plt_id, page, two,
                                      os.path.join(args.out_dir, "sbs_%d_%d_p%d.png"
                                                   % (chip, plt_id, page)))
                print("    crop %s  window=%r  (shipped left, rebuilt right)" % (p, box))
    return 0


if __name__ == "__main__":
    sys.exit(main())
