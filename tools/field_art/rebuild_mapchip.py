#!/usr/bin/env python3
"""
rebuild_mapchip.py -- rebuild Chrono Trigger (Android / cocos2d-x port) FIELD
"mapchip" sheets from the game's own ORIGINAL 1x SNES tile graphics.

The port ships, for every field chip set, a pre-baked 512x512 sheet
`Game/field/mapchip/mapchip_<chipTable>_<palette>_<page>.png`.  Those sheets are
2x, filtered (smoothed) and colour-quantised: they are the blurry art the game
actually draws.  This tool reconstructs the same sheet from
`Game/field/map_bin/cg*.bin` (4bpp 1x tile banks),
`Game/field/ChipTable/ChipTable_%04d.dat` (metatile definitions) and
`Game/field/palette_bin/plt%d.bin` (BGR555), reproducing exactly what
ChipTable::Expansion / ChipTable::setChip_8_8 build at runtime, then pixel-doubles
it.  The result is the crisp original of the shipped smoothed sheet.

Everything here is read out of the ARM64 disassembly of libchrono.so; see
REPORT.md in this directory for function names, addresses and evidence.

Usage:
    python3 rebuild_mapchip.py <extracted-Game/field-dir> <out-dir> [--map N ...]
                                                                   [--pair A B BGSET]
    python3 rebuild_mapchip.py <dir> --list-pairs

<extracted-Game/field-dir> is a directory laid out like the archive:
    Mapinfo/mapinfo_<n>.dat       BGSetTable/bgsettable_<n>.dat
    ChipTable/ChipTable_%04d.dat  map_bin/cg<n>.bin
    palette_bin/plt<n>.bin        mapchip/mapchip_<a>_<b>_<p>.png   (for --diff)

Dependencies: Pillow, numpy.
"""
import argparse
import collections
import glob
import os
import re
import struct
import sys

import numpy as np
from PIL import Image

# --------------------------------------------------------------------------
# Format constants -- all read out of libchrono.so, see REPORT.md
# --------------------------------------------------------------------------

# ChipData::Load 0x560dcc: dest = this + slot*0x1000, so every cg bank is
# 0x1000 bytes.  The bank is a LINEAR 4bpp bitmap, 0x40 bytes (=128 px) per row
# (that is the meaning of the 4-byte cg header, see cg_bank_size below), so a
# bank is 128x64 px = 8 rows x 16 cols of 8x8 tiles = 128 tiles.
CG_BANK_BYTES = 0x1000
CG_ROW_BYTES = 0x40          # 128 px at 4bpp
CG_SLOTS = 7                 # slots 0..6; slot 7 is the ext heap at ChipData+0x7000

# ChipTable::Load 0x5613dc: per metatile 4 x (u16 tileref + u8 prio), file order
# TL, TR, BL, BR; 16x16 metatiles per page, 2 pages.
CHIPTABLE_PAGE_BYTES = 256 * 4 * 3          # 3072
CHIPTABLE_PAGES = 2

# ChipTable::Expansion 0x562098 / setChip_8_8 0x56212c: a page expands to a
# 256x256 8-bit index image (32x32 8x8 tiles), i.e. 16x16 metatiles of 16x16 px.
PAGE_PX = 256
CELL_PX = 16                 # native metatile size
SHEET_SCALE = 2              # the shipped mapchip PNG is 2x -> 512x512

# setChip_8_8 tile-ref bit fields (0x56212c-0x5621e4)
TILE_MASK = 0x3FF            # bits 0..9
BLANK_MASK = 0x380           # `bics wzr, #0x380, word` -> all three bits set = blank
HFLIP_BIT = 0x400            # bit 10
VFLIP_BIT = 0x800            # bit 11
PAL_SHIFT = 12               # bits 12..15 -> high nibble of the 8-bit index

# FieldMap::load 0x56da4c: MapInfo is a 24-byte file read as 10 u16 + 4 u8.
MAPINFO_FIELDS = 14
MI_BGSET = 1                 # -> bgsettable_%d.dat          (sp+0x148)
MI_CHIPTABLE = 2             # -> ChipTable_%04d.dat, mapchip arg a   (sp+0x14c)
MI_PALETTE = 4               # -> plt%d.bin,          mapchip arg b   (sp+0x154)
MI_MAPTABLE = 6              # -> MapTable_%04d.dat                   (sp+0x15c)

# FieldMap::load 0x56dc48-0x56dcc4 reads 8 bytes from bgsettable and
# 0x56e404-0x56e518 feeds them to ChipData::Load in this slot order.
BGSET_TO_SLOT = {0: 0, 1: 1, 2: 2, 3: 3, 4: 4, 5: 5, 7: 6}   # byte 6 -> slot 7 (ext)
BGSET_SLOT6_FULL = True      # slot 6 is loaded with the `full` flag (size forced 0x1000)


# --------------------------------------------------------------------------


def read_mapinfo(root, n):
    """10 u16 + 4 u8, exactly MapInfo::Load @ 0x562bc4."""
    b = open(os.path.join(root, "Mapinfo", "mapinfo_%d.dat" % n), "rb").read()
    if len(b) != 24:
        raise ValueError("mapinfo_%d.dat: %d bytes" % (n, len(b)))
    return list(struct.unpack("<10H", b[:20])) + list(b[20:24])


def read_bgsettable(root, n):
    b = open(os.path.join(root, "BGSetTable", "bgsettable_%d.dat" % n), "rb").read()
    if len(b) != 8:
        raise ValueError("bgsettable_%d.dat: %d bytes" % (n, len(b)))
    return list(b)


def cg_bank_size(hdr, full):
    """ChipData::Load 0x560eb0-0x560ed0:
         a = getShort(); b = getShort();
         size = full ? 0x1000 : b * ((a >> 1) & 0x3FFF);
    For every shipped field cg bank that evaluates to 0x1000 (0x8080 -> 0x40
    bytes per row, 0x0040 -> 64 rows)."""
    a, b = struct.unpack("<HH", hdr)
    return CG_BANK_BYTES if full else b * ((a >> 1) & 0x3FFF)


def load_chipdata(root, bgset_bytes):
    """Build the 7 x 0x1000 ChipData buffer the way FieldMap::load does."""
    buf = bytearray(CG_SLOTS * CG_BANK_BYTES)
    banks = {}
    for bi, slot in BGSET_TO_SLOT.items():
        cg = bgset_bytes[bi]
        if cg == 0 or cg == 0xFF:            # `cbz`/`cmp #0xff` guards in FieldMap::load
            continue
        path = os.path.join(root, "map_bin", "cg%d.bin" % cg)
        if not os.path.exists(path):
            print("  warning: %s missing, slot %d left blank" % (path, slot), file=sys.stderr)
            continue
        raw = open(path, "rb").read()
        full = (slot == 6) and BGSET_SLOT6_FULL
        n = min(cg_bank_size(raw[:4], full), len(raw) - 4, CG_BANK_BYTES)
        buf[slot * CG_BANK_BYTES: slot * CG_BANK_BYTES + n] = raw[4:4 + n]
        banks[slot] = cg
    return bytes(buf), banks


def load_chiptable(root, chip_id):
    """ChipTable::Load @ 0x5613dc -> pages[page][y][x] = u16 tile ref,
    on the 32x32 grid ChipTable::Expansion walks (page base this+page*0x1000,
    row stride 0x80, 4 bytes per entry)."""
    path = os.path.join(root, "ChipTable", "ChipTable_%04d.dat" % chip_id)
    raw = open(path, "rb").read()
    pages = []
    prio = []
    for p in range(CHIPTABLE_PAGES):
        grid = np.zeros((32, 32), dtype=np.uint16)
        pr = np.zeros((16, 16), dtype=np.uint8)
        base = p * CHIPTABLE_PAGE_BYTES
        off = base
        for row in range(16):
            for col in range(16):
                bits = 0
                for k, (dy, dx) in enumerate(((0, 0), (0, 1), (1, 0), (1, 1))):
                    if off + 3 > len(raw):
                        w, f = 0, 0
                    else:
                        w = raw[off] | (raw[off + 1] << 8)
                        f = raw[off + 2]
                    off += 3
                    grid[row * 2 + dy, col * 2 + dx] = w
                    bits |= (f & 1) << k
                pr[row, col] = bits
        pages.append(grid)
        prio.append(pr)
    return pages, prio


def load_palette(root, plt_id):
    """FieldMap::load 0x56e1f8-0x56e26c: u16 count, then `count` BGR555 words,
    each channel scaled by (c * 0x20E7F7) >> 18 (== round(c*255/31))."""
    raw = open(os.path.join(root, "palette_bin", "plt%d.bin" % plt_id), "rb").read()
    n = struct.unpack_from("<H", raw, 0)[0]
    pal = np.zeros((256, 3), dtype=np.uint8)
    for i in range(min(n, 256)):
        v = struct.unpack_from("<H", raw, 2 + i * 2)[0]
        r, g, b = v & 31, (v >> 5) & 31, (v >> 10) & 31
        pal[i] = ((r * 0x20E7F7) >> 18, (g * 0x20E7F7) >> 18, (b * 0x20E7F7) >> 18)
    return pal


def expand_page(chipdata, grid):
    """ChipTable::Expansion @ 0x562098 + setChip_8_8 @ 0x56212c.

    Returns a 256x256 uint8 index page.  Index 0 is transparent: setChip_8_8 does
    `csel w13, wzr, w13, eq` on a zero 4bpp nibble, so colour 0 of *every* 16-colour
    group maps to output index 0, never to pal*16."""
    # Unpack the whole ChipData buffer to 4bpp nibbles once: 128 px wide, 448 rows.
    b = np.frombuffer(chipdata, dtype=np.uint8)
    hi = b >> 4                                     # left pixel of the byte pair
    lo = b & 0x0F                                   # right pixel
    px = np.empty(b.size * 2, dtype=np.uint8)
    px[0::2] = hi
    px[1::2] = lo
    px = px.reshape(-1, CG_ROW_BYTES * 2)           # (448, 128)

    out = np.zeros((PAGE_PX, PAGE_PX), dtype=np.uint8)
    for y in range(32):
        for x in range(32):
            w = int(grid[y, x])
            if (w & BLANK_MASK) == BLANK_MASK:      # `bics wzr, #0x380, word`
                continue
            tile = w & TILE_MASK
            ty, tx = (tile >> 4) * 8, (tile & 0x0F) * 8
            t = px[ty:ty + 8, tx:tx + 8]
            if w & HFLIP_BIT:
                t = t[:, ::-1]
            if w & VFLIP_BIT:
                t = t[::-1, :]
            pal = (w >> PAL_SHIFT) & 0x0F
            v = np.where(t == 0, 0, t | (pal << 4)).astype(np.uint8)
            out[y * 8:y * 8 + 8, x * 8:x * 8 + 8] = v
    return out


def render_sheet(root, chip_id, plt_id, bgset_id, page):
    """Rebuild mapchip_<chip_id>_<plt_id>_<page>.png at 1x (256x256 RGBA) and 2x."""
    bgset = read_bgsettable(root, bgset_id)
    chipdata, banks = load_chipdata(root, bgset)
    grids, _prio = load_chiptable(root, chip_id)
    pal = load_palette(root, plt_id)
    idx = expand_page(chipdata, grids[page])

    rgba = np.zeros((PAGE_PX, PAGE_PX, 4), dtype=np.uint8)
    rgba[:, :, :3] = pal[idx]
    rgba[:, :, 3] = np.where(idx == 0, 0, 255)
    one = Image.fromarray(rgba, "RGBA")
    two = Image.fromarray(np.repeat(np.repeat(rgba, SHEET_SCALE, 0), SHEET_SCALE, 1), "RGBA")
    return idx, one, two, banks


# --------------------------------------------------------------------------
# comparison against the shipped sheet
# --------------------------------------------------------------------------


def compare(root, chip_id, plt_id, page, rebuilt_2x):
    """Compare the rebuilt 2x sheet with the shipped one.

    The shipped sheet is *colour* art, not an index image: it is PNG mode P with an
    adaptive 256-entry palette that is not the game's plt (see REPORT.md section 5),
    produced by upscaling the 1x page with a smoothing filter and then quantising the
    result to 256 colours.  So an index-vs-index comparison is meaningless and only
    RGB comparisons are reported:

      alpha       -- agreement of the transparent masks (shipped colour-key vs our
                     index-0 rule).  Pure structure, no colour involved.
      exact       -- fraction of opaque 2x pixels whose RGB is bit-identical
      flat_exact  -- same, restricted to the 2x2 blocks the shipped sheet left flat
                     (i.e. where its filter did nothing) -- these are directly
                     comparable pixels
      flat_q<=N   -- as flat_exact but allowing an RGB channel error of N, which is
                     what the sheet's 256-colour quantisation costs
      near<=16    -- rebuilt 1x pixel vs the *average* of the shipped 2x2 block,
                     i.e. allowing the smoothing filter as well
    """
    path = os.path.join(root, "mapchip", "mapchip_%d_%d_%d.png" % (chip_id, plt_id, page))
    ship = Image.open(path)
    ship_rgba = np.asarray(ship.convert("RGBA")).astype(np.int32)
    mine = np.asarray(rebuilt_2x).astype(np.int32)
    if ship_rgba.shape != mine.shape:
        raise ValueError("size mismatch %r vs %r" % (ship_rgba.shape, mine.shape))

    sa = ship_rgba[:, :, 3] != 0
    ma = mine[:, :, 3] != 0
    alpha = float((sa == ma).mean())

    op = ma & sa
    n = int(op.sum())
    exact = float((np.all(ship_rgba[:, :, :3] == mine[:, :, :3], axis=-1) & op).sum()) / max(n, 1)

    s = ship_rgba[:, :, :3].reshape(PAGE_PX, 2, PAGE_PX, 2, 3)
    m1 = mine[0::2, 0::2, :3]
    op1 = op[0::2, 0::2]
    flat = np.all(s[:, 0, :, 0] == s[:, 0, :, 1], -1) & np.all(s[:, 0, :, 0] == s[:, 1, :, 0], -1) \
        & np.all(s[:, 0, :, 0] == s[:, 1, :, 1], -1)
    fsel = flat & op1
    nf = int(fsel.sum())
    fd = np.abs(s[:, 0, :, 0] - m1).max(axis=-1)
    flat_exact = float(((fd == 0) & fsel).sum()) / max(nf, 1)
    flat_q4 = float(((fd <= 4) & fsel).sum()) / max(nf, 1)
    flat_q8 = float(((fd <= 8) & fsel).sum()) / max(nf, 1)

    avg = s.mean(axis=(1, 3))
    d = np.abs(avg - m1).max(axis=-1)
    n1 = int(op1.sum())
    near = float(((d <= 16) & op1).sum()) / n1 if n1 else None

    if not nf:                       # nothing comparable: an all-transparent or
        flat_exact = flat_q4 = flat_q8 = None   # all-one-colour shipped sheet
    return dict(opaque=n, alpha=alpha, exact=exact, flat=float(flat.mean()), flat_n=nf,
                flat_exact=flat_exact, flat_q4=flat_q4, flat_q8=flat_q8, near=near,
                shipped=path)


def side_by_side(root, chip_id, plt_id, page, rebuilt_2x, out_path, win=96, zoom=3):
    """Shipped (left) vs rebuilt (right), on the `win`x`win` window of the sheet with
    the highest colour variance -- i.e. the most detailed patch, not a fixed corner."""
    ship = Image.open(os.path.join(root, "mapchip", "mapchip_%d_%d_%d.png"
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


def index_pairs(root):
    """(chipTable, palette) -> {bgset ids} -> [map ids], from every mapinfo file."""
    out = collections.defaultdict(lambda: collections.defaultdict(list))
    for p in glob.glob(os.path.join(root, "Mapinfo", "mapinfo_*.dat")):
        n = int(re.search(r"_(\d+)\.dat$", p).group(1))
        v = read_mapinfo(root, n)
        out[(v[MI_CHIPTABLE], v[MI_PALETTE])][v[MI_BGSET]].append(n)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", help="extracted Game/field directory")
    ap.add_argument("out_dir", nargs="?", default=None)
    ap.add_argument("--map", type=int, action="append", default=[],
                    help="rebuild the sheets for this field map id (repeatable)")
    ap.add_argument("--pair", nargs=3, type=int, action="append", default=[],
                    metavar=("CHIP", "PLT", "BGSET"))
    ap.add_argument("--list-pairs", action="store_true")
    ap.add_argument("--crops", action="store_true", help="also write side-by-side crops")
    args = ap.parse_args()

    idx = index_pairs(args.root)
    if args.list_pairs:
        for (a, b), bg in sorted(idx.items()):
            print("mapchip_%d_%d  bgset=%s  maps=%s"
                  % (a, b, sorted(bg), sorted(m for v in bg.values() for m in v)[:6]))
        return

    jobs = []
    for m in args.map:
        v = read_mapinfo(args.root, m)
        jobs.append((v[MI_CHIPTABLE], v[MI_PALETTE], v[MI_BGSET], m))
    for a, b, g in args.pair:
        jobs.append((a, b, g, None))
    if not jobs:
        ap.error("nothing to do: pass --map/--pair/--list-pairs")
    if not args.out_dir:
        ap.error("out_dir required")
    os.makedirs(args.out_dir, exist_ok=True)

    for chip, plt_id, bgset, mapid in jobs:
        for page in (0, 1):
            i1, one, two, banks = render_sheet(args.root, chip, plt_id, bgset, page)
            stem = "mapchip_%d_%d_%d" % (chip, plt_id, page)
            two.save(os.path.join(args.out_dir, stem + "_rebuilt.png"))
            one.save(os.path.join(args.out_dir, stem + "_rebuilt_1x.png"))
            line = "%s  map=%s bgset=%d cg=%s" % (stem, mapid, bgset,
                                                  {k: banks[k] for k in sorted(banks)})
            try:
                r = compare(args.root, chip, plt_id, page, two)
                f = lambda v: "n/a   " if v is None else "%.4f" % v
                line += ("  alpha=%.4f exact=%.4f | flat=%.3f(%d) flat_exact=%s "
                         "flat_q4=%s flat_q8=%s | near16=%s"
                         % (r["alpha"], r["exact"], r["flat"], r["flat_n"], f(r["flat_exact"]),
                            f(r["flat_q4"]), f(r["flat_q8"]), f(r["near"])))
            except FileNotFoundError:
                line += "  (no shipped sheet)"
            print(line)
            if args.crops:
                p, box = side_by_side(args.root, chip, plt_id, page, two,
                                      os.path.join(args.out_dir, "sbs_%d_%d_p%d.png"
                                                   % (chip, plt_id, page)))
                print("    crop %s  window=%r  (shipped left, rebuilt right)" % (p, box))


if __name__ == "__main__":
    main()
