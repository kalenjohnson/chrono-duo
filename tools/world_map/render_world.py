#!/usr/bin/env python3
"""
render_world.py -- render the Chrono Trigger (Android / cocos2d-x port) overworld
maps offline, from the game's own asset files.

Everything here is derived from the disassembly of libchrono.so; see REPORT.md in
this directory for function names, addresses and evidence.

Usage:
    python3 render_world.py <extracted-assets-dir> <output-dir> [--libchrono <path>]

<extracted-assets-dir> must contain (flat, as produced by tools/ctres.py):
    Map_0000.dat .. Map_0007.dat
    worldchip_<chip>_<plt>_0.png  and  worldchip_<chip>_<plt>_1.png

--libchrono is optional; it is only needed to reproduce world 7, whose map data is
baked into the .so and overwrites Map_0002.dat at load time.  Without it world 7 is
rendered from Map_0002.dat and flagged in the output.

Outputs world_0.png .. world_7.png at 2x native scale (3072x2048).

Dependencies: Pillow, numpy.
"""
import argparse
import os
import struct
import sys

import numpy as np
from PIL import Image

# ---------------------------------------------------------------------------
# Format constants (all read out of libchrono.so, see REPORT.md)
# ---------------------------------------------------------------------------

GRID_W, GRID_H = 96, 64          # world::MapData::Expansion loops: x<0x60, y<0x40
LAYER_BYTES = GRID_W * GRID_H    # 0x1800, the layer stride in world::MapData::GetMapData
MAP_FILE_BYTES = 2 * LAYER_BYTES # 0x3000, the memcpy size at the end of WorldMap::LoadMap

SHEET_COLS = 16                  # index -> cell: col = b & 0x0F, row = b >> 4
CELL_PX = 32                     # worldchip_*.png is 512x512 = 2x of the 256x256 chip page

# WorldMapInfo::G_WORLDMAPINFO, 8 entries x 0x30 bytes at vaddr 0xbe2508.
# Fields used by WorldMap::LoadMap: +0x14 = palette id, +0x20 = chip-table id,
# +0x22 = map id.  world id is the WorldMapInfo ctor argument (== table row).
#   world: (chip, plt, map)
WORLD_INFO = {
    0: (0, 4, 0),
    1: (0, 5, 1),
    2: (2, 7, 3),
    3: (3, 8, 4),
    4: (4, 9, 5),
    5: (5, 10, 7),
    6: (4, 9, 6),
    7: (1, 6, 2),
}

# Human labels; see REPORT.md "Era identification" for how far these are evidenced.
WORLD_LABEL = {
    0: "1000 AD (Present)",
    1: "600 AD (Middle Ages)",
    2: "2300 AD (Future)",
    3: "65,000,000 BC (Prehistory)",
    4: "12,000 BC (Dark Ages, ground)",
    5: "12,000 BC (Zeal, sky)",
    6: "12,000 BC (Dark Ages, post-Zeal)",
    7: "1000 AD variant (Lavos-fallen / ending)",
}

# WorldMap::LoadMap 0x606bd4: `if (worldId == 7)` overwrite both map layers from a
# baked table.  world::MapData::Expansion 0x605f80: `if (worldId == 5)` the overlay
# layer is NOT composited into the base texture set (world 5's layers are swapped:
# layer 0 is the dense one).
BAKED_MAP_WORLD = 7
BAKED_MAP_VADDR = 0x3B1FF4       # adrp x8,0x3b1000 ; add x8,x8,#0xff4
BAKED_MAP_BYTES = 0xC000         # 12288 u32, one u32 per map byte
NO_OVERLAY_BAKE_WORLD = 5

# ELF LOAD segment that contains BAKED_MAP_VADDR (p_vaddr 0x0, p_offset 0x0), so
# vaddr == file offset for it.  Kept explicit in case a future .so differs.
_RODATA_VADDR = 0x0
_RODATA_OFFSET = 0x0


# ---------------------------------------------------------------------------


def load_map_layers(assets_dir, world, lib_path=None):
    """Return (layer0, layer1) as uint8 (GRID_H, GRID_W) arrays, exactly as the
    game has them in RAM at WorldMap+0x237e0 after WorldMap::LoadMap()."""
    chip, plt, map_id = WORLD_INFO[world]
    note = ""

    data = None
    if world == BAKED_MAP_WORLD and lib_path:
        blob = open(lib_path, "rb").read()
        off = BAKED_MAP_VADDR - _RODATA_VADDR + _RODATA_OFFSET
        raw = blob[off:off + BAKED_MAP_BYTES]
        if len(raw) == BAKED_MAP_BYTES:
            # tbl v0 = {0,4,8,...,60} picks byte 0 of each u32; then add v1 (-1).
            data = bytes(((raw[i] - 1) & 0xFF) for i in range(0, BAKED_MAP_BYTES, 4))
            note = "map data taken from the baked table in libchrono.so (overrides Map_%04d.dat)" % map_id

    if data is None:
        path = os.path.join(assets_dir, "Map_%04d.dat" % map_id)
        data = open(path, "rb").read()
        if world == BAKED_MAP_WORLD:
            note = "WARNING: rendered from Map_%04d.dat; pass --libchrono for the real world 7 data" % map_id

    if len(data) != MAP_FILE_BYTES:
        raise ValueError("map for world %d: expected %d bytes, got %d"
                         % (world, MAP_FILE_BYTES, len(data)))

    l0 = np.frombuffer(data[:LAYER_BYTES], dtype=np.uint8).reshape(GRID_H, GRID_W)
    l1 = np.frombuffer(data[LAYER_BYTES:], dtype=np.uint8).reshape(GRID_H, GRID_W)
    return l0, l1, note


def load_page(assets_dir, chip, plt, page):
    """worldchip_<chip>_<plt>_<page>.png -- the pre-rendered chip page.

    Page 0 backs map layer 0 (ChipTable+0x2200), page 1 backs map layer 1
    (ChipTable+0x12200).  512x512 = 16x16 cells of 32x32px (2x of the 16x16
    native metatile)."""
    path = os.path.join(assets_dir, "worldchip_%d_%d_%d.png" % (chip, plt, page))
    im = Image.open(path).convert("RGBA")
    if im.size != (SHEET_COLS * CELL_PX, SHEET_COLS * CELL_PX):
        raise ValueError("%s: unexpected size %r" % (path, im.size))
    return np.asarray(im)


def paint(canvas, layer, page):
    """Paint `layer` (96x64 metatile indices) onto `canvas` using chip page `page`.

    Cell index 0 is skipped entirely -- world::MapData::Expansion does
    `cbz w0, next` (0x605e70 / 0x605f0c), leaving those metatiles at palette
    index 0.  Within a drawn metatile, individual pixels whose palette index is 0
    are likewise transparent (the composite at 0x606060 does
    `dst = (src != 0) ? src : dst`), so cells are composited per pixel, not
    copied wholesale."""
    for y in range(GRID_H):
        row = layer[y]
        dy = y * CELL_PX
        for x in range(GRID_W):
            b = int(row[x])
            if b == 0:
                continue
            sy = (b >> 4) * CELL_PX
            sx = (b & 0x0F) * CELL_PX
            src = page[sy:sy + CELL_PX, sx:sx + CELL_PX]
            dst = canvas[dy:dy + CELL_PX, x * CELL_PX:(x + 1) * CELL_PX]
            m = src[:, :, 3] != 0
            dst[m] = src[m]


def render_world(assets_dir, world, lib_path=None):
    chip, plt, map_id = WORLD_INFO[world]
    l0, l1, note = load_map_layers(assets_dir, world, lib_path)
    p0 = load_page(assets_dir, chip, plt, 0)
    p1 = load_page(assets_dir, chip, plt, 1)

    canvas = np.zeros((GRID_H * CELL_PX, GRID_W * CELL_PX, 4), dtype=np.uint8)

    # Draw order reproduces what the game composites:
    #   texture set 0 = layer 0 alone, drawn behind
    #   texture set 1 = layer 1, with layer 0 baked over it unless world == 5
    # so the visible result is layer0-behind, then layer1, then layer0-on-top
    # (the last step suppressed for world 5, whose layers are swapped).
    paint(canvas, l0, p0)
    paint(canvas, l1, p1)
    if world != NO_OVERLAY_BAKE_WORLD:
        paint(canvas, l0, p0)

    return Image.fromarray(canvas, "RGBA"), note


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets_dir")
    ap.add_argument("out_dir")
    ap.add_argument("--libchrono", default=None,
                    help="path to libchrono.so (needed for world 7's baked map data)")
    ap.add_argument("--worlds", default="0-7")
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    if "-" in args.worlds:
        a, b = args.worlds.split("-")
        worlds = range(int(a), int(b) + 1)
    else:
        worlds = [int(w) for w in args.worlds.split(",")]

    for w in worlds:
        chip, plt, map_id = WORLD_INFO[w]
        im, note = render_world(args.assets_dir, w, args.libchrono)
        out = os.path.join(args.out_dir, "world_%d.png" % w)
        im.save(out)
        print("wrote %s  world=%d  Map_%04d.dat  worldchip_%d_%d_{0,1}.png  %dx%d  %s%s"
              % (out, w, map_id, chip, plt, im.width, im.height, WORLD_LABEL[w],
                 ("  [" + note + "]") if note else ""))


if __name__ == "__main__":
    main()
