"""
Decoder for Chrono Trigger DS custom NCG/NCL/NSC map format.

Format (all little-endian):

NCG (tile graphics), LZ10-compressed on disk:
  magic   "NCG\0"      4 bytes
  count   u16          number of 8x8 tiles
  flag    u16          0 = 4bpp tiles, 1 = 8bpp tiles
  data    count * (32 or 64) bytes    raw planar tile data (standard Nitro
                                       4bpp/8bpp packed pixel format, NOT compressed further)

NCL (palette), NOT compressed:
  magic   "NCL\0"      4 bytes
  count   u32          number of colors
  data    count * 2 bytes   BGR555 (0bbbbbgggggrrrrr), NDS standard

NSC (screen/tilemap), NOT compressed:
  magic   "NSC\0"       4 bytes
  count   u16           w*h (tile entries)
  flag    u16           mirrors NCG bpp flag (and maybe more)
  w       u8            width in tiles
  h       u8            height in tiles
  reserved u16
  data    count * 2 bytes  standard Nitro screen entries:
                             bits 0-9   tile index
                             bit 10     horizontal flip
                             bit 11     vertical flip
                             bits 12-15 palette index (4bpp mode only)

The tilemap is a simple row-major w x h grid (no 256x256 SC block reordering
needed since w,h are given explicitly and are usually <=32, i.e. it fits in
one screen block already).
"""
import struct
from PIL import Image


def decode_ncl(data):
    magic = data[0:4]
    assert magic == b"NCL\x00", magic
    count = struct.unpack_from("<I", data, 4)[0]
    colors = []
    off = 8
    for i in range(count):
        v = struct.unpack_from("<H", data, off)[0]
        off += 2
        r = (v & 0x1F) * 255 // 31
        g = ((v >> 5) & 0x1F) * 255 // 31
        b = ((v >> 10) & 0x1F) * 255 // 31
        colors.append((r, g, b))
    return colors


def decode_ncg(data_decompressed):
    magic = data_decompressed[0:4]
    assert magic == b"NCG\x00", magic
    count, flag = struct.unpack_from("<HH", data_decompressed, 4)
    bpp = 8 if flag else 4
    tile_bytes = 64 if bpp == 8 else 32
    off = 8
    tiles = []  # each tile: 8x8 list of palette indices
    for i in range(count):
        raw = data_decompressed[off:off + tile_bytes]
        off += tile_bytes
        pixels = [[0] * 8 for _ in range(8)]
        if bpp == 4:
            for py in range(8):
                for px in range(0, 8, 2):
                    b = raw[py * 4 + px // 2]
                    pixels[py][px] = b & 0xF
                    pixels[py][px + 1] = (b >> 4) & 0xF
        else:
            for py in range(8):
                for px in range(8):
                    pixels[py][px] = raw[py * 8 + px]
        tiles.append(pixels)
    return tiles, bpp


def decode_nsc(data):
    magic = data[0:4]
    assert magic == b"NSC\x00", magic
    count, flag = struct.unpack_from("<HH", data, 4)
    w = data[8]
    h = data[9]
    # 2 reserved bytes at offset 10-11
    entries = []
    off = 12
    for i in range(count):
        v = struct.unpack_from("<H", data, off)[0]
        off += 2
        entries.append(v)
    return w, h, flag, entries


def render_map(ncg_decompressed, ncl_data, nsc_data, transparent0=True):
    tiles, bpp = decode_ncg(ncg_decompressed)
    colors = decode_ncl(ncl_data)
    w, h, flag, entries = decode_nsc(nsc_data)

    img = Image.new("RGBA", (w * 8, h * 8), (0, 0, 0, 0))
    px = img.load()

    colors_per_pal = 16 if bpp == 4 else 256

    for ty in range(h):
        for tx in range(w):
            entry = entries[ty * w + tx]
            tile_idx = entry & 0x3FF
            hflip = (entry >> 10) & 1
            vflip = (entry >> 11) & 1
            pal_idx = (entry >> 12) & 0xF if bpp == 4 else 0
            if tile_idx >= len(tiles):
                continue
            tile = tiles[tile_idx]
            for py in range(8):
                sy = 7 - py if vflip else py
                for pxo in range(8):
                    sx = 7 - pxo if hflip else pxo
                    ci = tile[sy][sx]
                    if ci == 0 and transparent0:
                        continue
                    color_idx = pal_idx * colors_per_pal + ci
                    if color_idx >= len(colors):
                        color_idx = color_idx % len(colors)
                    r, g, b = colors[color_idx]
                    px[tx * 8 + pxo, ty * 8 + py] = (r, g, b, 255)
    return img
