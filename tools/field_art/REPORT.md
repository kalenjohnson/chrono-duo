# Chrono Trigger (Android port) FIELD tile format — REPORT

**Headline: the port ships the original 1x SNES field tile graphics.**
`Game/field/map_bin/cg*.bin` are the untouched 4bpp 1x tile banks,
`Game/field/ChipTable/ChipTable_%04d.dat` the metatile definitions and
`Game/field/palette_bin/plt%d.bin` the BGR555 palettes. The 512x512
`Game/field/mapchip/mapchip_<a>_<b>_<page>.png` sheets are a *derived*,
2x-upscaled, smoothed, 256-colour-quantised bake of exactly that data.
`rebuild_mapchip.py` in this directory reconstructs any shipped sheet from the
1x sources and reaches **median 0.995 / p10 0.980 agreement across all 506
shipped sheet pages** (see section 7). The earlier note in `NOTES.md` that
"field tiles have no 1x source" is wrong and should be retired.

All addresses are `libchrono.so` virtual addresses; symbols are the shipped C++
symbols (mangled in `full.asm`, demangled here).

---

## 1. The load path — `FieldMap::load(int mapId)` @ **0x56da4c**

| step | call | meaning |
|---|---|---|
| 0x56da94 | `MapInfo::Load(&info, mapId)` | `Game/field/Mapinfo/mapinfo_%d.dat`, 24 B, into a 0x3C-byte struct at `sp+0x144` |
| 0x56dacc | `MapTable::Load(info+0x18, mapId)` | `Game/field/MapTable/MapTable_%04d.dat` — the map layout |
| 0x56dba0 | *(inline)* `bgsettable_%d.dat` with `info+0x04` | 8 bytes = the cg bank ids |
| 0x56dc48-0x56dcc4 | 8 x `ResourceData::getByte()` | those 8 bytes -> stack slots |
| 0x56e14c | *(inline)* `plt%d.bin` with `info+0x10` | BGR555 palette, u16 count + `count` words |
| 0x56e41c-0x56e518 | 8 x `ChipData::Load(cgId, slot, full)` | the 4bpp tile banks, slots 0..7 |
| 0x56e588 | `ChipTable::Load(info+0x08)` | `ChipTable_%04d.dat` — 2 pages of metatile refs |
| 0x570904 | `MapTable::LoadTexture(info+0x08, info+0x10, n)` | the `mapchip_%d_%d_%d.png` sheets |
| 0x57091c | `MapTable::ExpansionExt(chipTable, mapId)` | builds the drawable field textures |

`SceneSpecialEventBlackDream::init` @ **0x5c906c** is the same sequence in
miniature (map id 107 hard-coded) and is the easiest place to read the argument
wiring: `MapInfo::Load(&mi, 0x6b)`, `ChipTable::Load(mi[+0x08])`,
`MapTable::Load(mi[+0x18], 0x6b)`, `MapTable::LoadTexture(mi[+0x08],
mi[+0x10], 2)`.

## 2. `Mapinfo/mapinfo_%d.dat` — 24 bytes, 10 u16 + 4 u8

`MapInfo::Load(int)` @ **0x562bc4** reads ten `getShort()` into `this+0x00,
0x04, ... 0x24` and four `getByte()` into `this+0x28, 0x2c, 0x30, 0x34`, then
sets `this+0x38 = 1`. If bit 7 of the byte at `+0x28` is set it zeroes
`+0x28..+0x38` (0x562d4c). Correlating the struct offsets with the stack frame
of `FieldMap::load` (struct base `sp+0x144`):

| u16 idx | struct | consumer |
|---|---|---|
| 0 | +0x00 | `FieldMap::LoadMapMusic` (0x56db40) |
| **1** | +0x04 | **`bgsettable_%d.dat` index** (0x56db44) |
| **2** | +0x08 | **`ChipTable_%04d.dat` id — this is `a` in `mapchip_a_b_p`** |
| 3 | +0x0C | (unused in this path) |
| **4** | +0x10 | **`plt%d.bin` id — this is `b` in `mapchip_a_b_p`** |
| 5 | +0x14 | (unused in this path) |
| **6** | +0x18 | `MapTable_%04d.dat` id |
| 7 | +0x1C | cgext / secondary chip data id (0x56dd08) |
| 8 | +0x20 | `FieldMap::SetAtelNum` |
| 9 | +0x24 | `FieldMap::SetMapFlags` |
| u8 0..3 | +0x28..+0x34 | `MapTable::SetMapSizeKind` (map size/kind) |

There are 669 mapinfo files; they produce **255 distinct `(chipTable, palette)`
pairs**, and 252 of those have shipped `mapchip` sheets. The three that do not
(`(255,75)`, `(588,21)`, `(590,21)`) have no `bgsettable_255.dat` /
`ChipTable_0588.dat` / `ChipTable_0590.dat` either — they are dead entries.
**No shipped sheet lacks a mapinfo entry.** That the keying is exactly
`(mapinfo[2], mapinfo[4])` is therefore proven by coverage, not assumed.

## 3. `BGSetTable/bgsettable_%d.dat` — 8 bytes = the cg bank ids

`FieldMap::load` reads the 8 bytes at 0x56dc48-0x56dcc4 and feeds them to
`ChipData::Load` at 0x56e41c-0x56e518 in this order:

```
byte 0 -> slot 0     byte 4 -> slot 4
byte 1 -> slot 1     byte 5 -> slot 5
byte 2 -> slot 2     byte 6 -> slot 7  (the "ext" heap, not a tile bank)
byte 3 -> slot 3     byte 7 -> slot 6  (loaded with full = true)
```

A byte of `0x00` or `0xFF` means "no bank" (`cbz` / `cmp #0xff` guards) and the
slot is left blank. `bgsettable_0.dat = 7e 82 83 84 85 86 17 d8` is therefore
cg126/130/131/132/133/134 in slots 0..5, cg23 in the ext heap and cg216 in
slot 6.

The bgset index is *not* formally a function of `(a,b)` but is one in practice:
of the 255 `(a,b)` groups, **254 have a single bgset id**. The lone exception is
`(62, 21)`, whose maps use bgset 14 (`4a4b4c4d4e 2c ff ea`) or bgset 70
(`4a4b4c4d4e ee ff ea`) — they differ only in slot 5. Both reconstruct the
shipped sheet well (near16 0.983 vs 0.996 on page 0), so the sheet was baked
from one of the two.

## 4. `map_bin/cg%d.bin` — 4-byte header + a LINEAR 4bpp bitmap, **1x**

`ChipData::Load(int cgId, int slot, bool full)` @ **0x560dcc**:

```
a = getShort(); b = getShort();                      // the 4-byte header
size = full ? 0x1000 : b * ((a >> 1) & 0x3FFF);
if (slot == 7)  append `size` bytes to the vector at this+0x7000   // ext heap
else            ResourceData::copy(this + (slot << 12), size);     // 0x560f7c
```

Every shipped field bank has header `80 80 40 00`: `(0x8080 >> 1) & 0x3FFF =
0x40` bytes per row and `0x40 = 64` rows, i.e. **0x1000 bytes, 64 bytes (=128
px at 4bpp) per row, 64 rows** — a 128x64 px 4bpp image = 8 rows x 16 columns of
8x8 tiles = **128 tiles per bank**.

So `ChipData` is a flat `7 * 0x1000 = 0x7000`-byte buffer at stride 0x40, i.e. a
**128 x 448 px 4bpp bitmap**, and the `0x7000` mark is where the slot-7 ext
vector pointer lives.

**The packing is linear, not SNES-planar, and this is self-proving.** Three
independent facts land on the same constant:

1. `setChip_8_8` treats a tile ref as blank when `(word & 0x380) == 0x380`
   (`bics wzr, w10(0x380), w5` @ 0x562158), i.e. tile indices >= 0x380 = 896.
2. 7 slots x 128 tiles per bank = **896 tiles exactly**.
3. Under the linear address formula below, tile 0x380 is byte offset
   `(0x38 * 8) * 0x40 = 0x7000` — precisely the end of the bank area and the
   start of the ext heap.

The address `setChip_8_8` computes (0x5621e4-0x5622c8, unrolled 8x per row) is:

```
tile   = word & 0x3FF
colval = (word & 0x400) ? 7 - col : col        // bit 10 = H flip
rowval = (word & 0x800) ? 7 - row : row        // bit 11 = V flip
v      = (tile & 0x0F) * 8 + colval            // pixel column in the 128px bank
byte   = chipData[ ((tile >> 4) * 8 + rowval) * 0x40 + (v >> 1) ]
nibble = (v & 1) ? (byte & 0x0F) : (byte >> 4) // HIGH nibble = LEFT pixel
```

(`tst w0,#1` / `lsr w15,w13,#4` / `csel w13,w15,w13,eq` at 0x5622b4-0x5622cc is
the nibble select; the `w13` values 0/7, 1/6, 2/5, 3/4 built by the `csinc`/
`csel` chain at 0x562160-0x5621d0 are the eight `colval`s.)

## 5. `ChipTable/ChipTable_%04d.dat` — 6144 bytes = 2 pages x 256 metatiles x 4 x (u16+u8)

`ChipTable::Load(int)` @ **0x5613dc** runs two identical 16x16 loops (page 0 at
0x5614c0, page 1 at 0x561570). Per metatile it reads **four `(getShort,
getByte)` pairs** in the order **TL, TR, BL, BR** and stores:

```
page base       = this + page*0x1000
word(Y, X)      = u32 at page base + Y*0x80 + X*4      (Y,X on a 32x32 grid)
   metatile(R,C): TL=(2R,2C)  TR=(2R,2C+1)  BL=(2R+1,2C)  BR=(2R+1,2C+1)
priority byte   = this + 0x3000 + page*0x100 + R*0x10 + C
                  bits 0..3 = bit 0 of the four getByte()s (bfi chain 0x561524)
```

2 pages x 256 metatiles x 4 x 3 bytes = **6144 bytes = exactly the file size**.
`ChipTable::Expansion(ChipData&, int, int page)` @ **0x562098** then walks
`for y in 0..31 / for x in 0..31: setChip_8_8(chipData, page, x, y, word, page==2, extra)`,
confirming the 32x32 grid and the 0x80 row stride.

`ChipTable/ChipTableBg3_%04d.dat` is **3072 bytes = one page** in the same
format and is a different consumer — `ChipTable::LoadExt(int,int)` @
**0x5616a8** / `ChipTable::LoadWeather(int)` @ **0x561ea8** (BG3 / weather
layers). It is *not* the source of the `mapchip` sheets and `rebuild_mapchip.py`
does not touch it.

### Tile-reference bit fields (`setChip_8_8` @ **0x56212c**)

| bits | meaning | evidence |
|---|---|---|
| 0..9 | 8x8 tile index into ChipData | address formula, section 4 |
| 7,8,9 all set | **blank** (whole 8x8 left at index 0) | `bics wzr, #0x380, w5` @ 0x562158 |
| 10 | horizontal flip | `sbfx w14,w5,#10,#1` @ 0x56214c, `tst w5,#0x400` @ 0x562168 |
| 11 | vertical flip | `tst w5,#0x800` / `csel` @ 0x5622a0 |
| 12..15 | palette group (0..15) | `ubfx w10,w5,#12,#4` @ 0x562234, `lsl w14,w13,#4` @ 0x56217c |

Output pixel: `index = (nibble == 0) ? 0 : (nibble | (palette << 4))`. The
zero case is explicit — `csel w13, wzr, w13, eq` @ 0x5622fc — so **colour 0 of
every 16-colour group maps to output index 0, never to `pal*16`**. That index 0
is the transparency key; getting this wrong puts a halo round every metatile.

### The expanded page

`setChip_8_8` writes to `chipTable + 0x3200 + page*0x10000 + (y*8 + row)*0x100 +
x*8 + col` (base `w23 = 0x3200` @ 0x56218c, page shift `add x25, x12, x25, lsl
#16` @ 0x562208, row stride 0x100 via `add x8, x8, #0x100` @ 0x562288). So each
page expands to a **256x256 8-bit index image = 16x16 metatiles of 16x16 px, at
1x**, page 0 at `+0x3200`, page 1 at `+0x13200`.

## 6. `mapchip_<a>_<b>_<page>.png` — a *derived*, 2x, smoothed, colour sheet

`MapTable::LoadTexture(int a, int b, int count)` @ **0x56b874**:

```
this+0x98        = createTexture("Game/field/mapchip/mapchip_%d_%d_0.png", a, b)
this+0xa0        = createTexture("Game/field/mapchip/mapchip_%d_%d_1.png", a, b)
this+0xa8 + i*8  = createTexture("Game/field/mapchip/mapchip_%d_%d_%d.png", a, b, i+2)
                   for i = 0 .. count-2
```

(The first call looks argument-less in the disassembly because `w1`/`w2` still
hold the incoming `a`/`b` — they were only *copied* to `w20`/`w19` at
0x56b8ac.) `a` = `mapinfo[+0x08]` (ChipTable id), `b` = `mapinfo[+0x10]`
(palette id) — read directly at 0x5708f0/0x5708f8. Of the 252 shipped pairs,
238 ship pages {0,1}, 10 ship {0,1,2} and 4 ship {0,1,2,3}; the extra pages come
from `count` being 3 or 4 for a handful of map ids (0x1b-0x1d, 0x6b, 0xa5,
0x22c, 0x22e, 0x250 — the `cmp` chain at 0x5708b4-0x5708e8).

**512x512 = 2x of the 256x256 expanded page, laid out as 16x16 cells of 32x32
px** (one cell per metatile), page `p` corresponding to ChipTable page `p`.

**The sheets are colour art, not index images.** They are PNG mode P with an
adaptive 256-entry palette, and:

* the palette is *not* the game's plt — of the ~114 distinct colours used by
  `mapchip_0_0_0.png`, only 2 appear in any `plt*.bin`;
* pages 0 and 1 of the same `(a,b)` — one `b`, one plt — have **different**
  palettes (index 2 is `(5,3,7)` vs `(17,8,2)`), which a hardware palette could
  not be;
* palette entry 0 is a colour key (`(255,0,0)` on page 0, `(0,255,0)` on page 1)
  with `tRNS` set, i.e. the index-0 transparency of section 5 rendered out;
* only **42-59%** of 2x2 blocks are flat, so the upscale was a smoothing filter,
  not nearest-neighbour.

So the bake pipeline was: expand the ChipTable page from the 1x cg banks -> apply
`plt<b>.bin` -> upscale 2x with a smoothing filter -> quantise to 256 colours ->
PNG. `rebuild_mapchip.py` reproduces every step but the last two, which is
exactly the point: its output is the crisp original of the shipped smoothed sheet.

## 7. Verification — `rebuild_mapchip.py`

```
python3 tools/field_art/rebuild_mapchip.py <extracted Game/field dir> <out dir> \
        --map 107 --map 6 --map 112 --map 219 --crops
python3 tools/field_art/rebuild_mapchip.py <dir> --list-pairs
```

Because the shipped sheet is colour art, an index-vs-index comparison is
meaningless and is not reported. The metrics are:

* **alpha** — agreement of the transparent masks (shipped colour key vs our
  index-0 rule). Pure structure, no colour involved.
* **exact** — bit-identical RGB over opaque 2x pixels.
* **flat** — fraction of 2x2 blocks the shipped filter left flat.
* **flat_exact** — over those flat blocks, bit-identical RGB. Low even where the
  rebuild is right, because the sheet was requantised — it is reported only to
  show how much of the residual is quantisation (compare with flat_q4).
* **flat_q4 / flat_q8** — over those flat blocks, agreement within an RGB channel
  error of 4 / 8, i.e. *allowing only the 256-colour quantisation*.
* **near16** — rebuilt 1x pixel vs the *average* of the shipped 2x2 block within
  16, i.e. *allowing the smoothing filter as well*.

### The four inspected pairs

| sheet | map | bgset | cg banks | alpha | exact | flat | flat_q4 | flat_q8 | near16 |
|---|---|---|---|---|---|---|---|---|---|
| `mapchip_0_0_0` | 6 | 0 | 126,130,131,132,133,134,216 | 0.9998 | 0.010 | 0.417 | 0.920 | 0.987 | **0.982** |
| `mapchip_0_0_1` | 6 | 0 | same | 0.9999 | 0.001 | 0.254 | 0.872 | 0.980 | **0.993** |
| `mapchip_20_27_0` | 112 | 20 | 39,40,41,42,43,44,226 | 0.9999 | 0.012 | 0.207 | 0.964 | 0.998 | **0.998** |
| `mapchip_20_27_1` | 112 | 20 | same | 0.9995 | 0.009 | 0.352 | 0.979 | 0.991 | **0.985** |
| `mapchip_23_33_0` | 107 | 23 | 103,104,105,106,107,202 | 0.9977 | 0.227 | 0.587 | 0.958 | 0.992 | **0.982** |
| `mapchip_23_33_1` | 107 | 23 | same | 1.0000 | 0.000 | 0.088 | 0.889 | 0.956 | **0.947** |
| `mapchip_43_57_0` | 219 | 43 | 61,62,63,186 | 1.0000 | 0.331 | 0.789 | 0.997 | 1.000 | **0.999** |
| `mapchip_43_57_1` | 219 | 43 | same | 0.9999 | 0.217 | 0.646 | 0.831 | 1.000 | **0.999** |

`(20,27)`, `(23,33)` and `(43,57)` all have `a` and `b` non-zero; `(0,0)` is
included as the degenerate-index case, not as one of those three.
`exact` is low and `flat_q4` high because the sheet was quantised: on flat blocks
the rebuilt and shipped colours differ by 1-3 per channel almost everywhere
(e.g. shipped `(12,61,109)` vs plt `(8,57,106)`).

### The whole corpus

Every shipped `(a,b)` pair, both pages — **506 sheets**:

| metric | mean | median | p10 |
|---|---|---|---|
| alpha | 0.9956 | 0.9998 | 0.9986 |
| flat_q4 | 0.9541 | 0.9729 | 0.9320 |
| flat_q8 | 0.9814 | 0.9958 | 0.9835 |
| near16 | 0.9787 | 0.9949 | 0.9797 |

Those numbers were produced with an earlier build of the tool that scored 4
degenerate sheets as `0.0000` and so pulled the means down slightly; the tool now
reports `n/a` for them and they are excluded. The 4 are `mapchip_37_50_1`,
`mapchip_54_88_1` and `mapchip_593_21_0` (shipped fully transparent, 0 opaque
pixels) and `mapchip_53_85_1` (shipped as one solid opaque colour) — there is
nothing to compare, not a mismatch. **Every non-degenerate sheet reconstructs.**
6 further page-slots were skipped for missing `bgsettable_255.dat` /
`ChipTable_0588.dat` / `ChipTable_0590.dat` (section 2).

### Eyeball

`--crops` writes `<out_dir>/sbs_<a>_<b>_p<page>.png`: 3x side-by-side crops
(shipped on the left, rebuilt on the right) of the highest-variance 96x96 window
of each sheet, the window it picked printed alongside.
They are indistinguishable in content and unambiguous in character: the shipped
half is soft, with filter ringing on every edge; the rebuilt half is the crisp
original, hard 2x2 pixel blocks throughout. Checked on `(0,0)` page 1 (a stone
colonnade), `(20,27)` page 1 (boulders, flowers, a wooden fence), `(43,57)`
page 0 (Guardia-prison-style barred cells) and `(23,33)` page 0 (a sky gradient).

---

## 8. Feasibility of runtime replacement

### 8a. Where the mapchip sheets are actually used — **the whole field, ground included**

**RETRACTED AND REPLACED (2026-09-04).** This section previously argued the
opposite: that the walkable ground was CPU-composited at 1x from the cg banks
into a paletted index texture, and that the smoothed 2x sheets fed only the
animated/front chips. That reading was built on `MapTable::Expansion` @ 0x568af0
and `MapTable::writeChip` @ 0x56b5f0 — and **`MapTable::Expansion` is never
called.** It has **no PLT stub, no `JUMP_SLOT` relocation, and zero direct `bl`s**
to 0x568af0, while its live sibling `ExpansionExt` @ 0x569c28 has a stub at
0xb3f860 taking 4 calls. (In this binary *every* intra-library call goes through
a PLT stub — `bl 0x<body>` is 0 for `ExpansionExt`, `drawFrontChip` and
`CreateSprites` too — so "no direct `bl`" alone proves nothing; the absence of a
stub *and* a relocation is what settles it.) `writeChip` likewise has no stub and
no direct call: it is reachable only from `draw`/`drawExt`/`drawZero`, which in
turn only `Expansion`/`ExpansionExt` reach.

#### What actually paints the field: `MapTable::drawFrontChip` @ **0x56bdac**

Read at the instruction level (0x56bdac-0x56be94):

```
srcX = (chip & 0x0F) * 16                    ubfiz w22, w6, #4, #4   @ 0x56bddc
srcY =  chip & 0xF0                          and   w23, w6, #0xf0    @ 0x56bdec
mask = chipTable[0x3000 + page*0x100 + chip] ldrb  w26, [x9, #0x3000]@ 0x56be04
if (!b) mask ^= 0xF                          eor/tst/csel   @ 0x56be3c-0x56be44
if (mask == 0xF)                             cmp w26, #0xf  @ 0x56be48
    Rect(srcX, srcY, 16.0f, 16.0f)           fmov s2/s3,#16.0 @ 0x56be5c/60
    tex = this[0x98 + page*8]                ldr x21,[x8,#0x98] @ 0x56be68
    Sprite::createWithTexture(tex, rect, false)                @ 0x56be7c
    setPosition(x, y)  ->  Node::visit
else  four 8x8 quadrant draws from the SAME texture (0x56bf0c/0x56bf78/0x56bfec/0x56c058)
```

`this + 0x98 + page*8` is exactly the `Texture2D*` array `MapTable::LoadTexture`
@ 0x56b874 fills with the `mapchip_%d_%d_%d.png` sheets (section 6). The
`b` argument inverts the priority mask, so **one call with `b = false` draws
every *non*-priority metatile — the ground — and one with `b = true` draws the
priority/front chips.** `MapTable::CreateSprites` @ 0x56bbdc drives it over the
whole visible window with **no anim/priority filter** (loop 0x56bd2c-0x56bda4:
one unconditional `bl drawFrontChip@plt` per cell, `w23 += 0x10` per column),
and `FieldMap::makeField` @ 0x57520c calls `CreateSprites`/`CreateSpritesExt`
eight times (0x5754ec, 0x57550c, 0x5755f4, 0x575614, 0x575688, 0x5756b8,
0x5756e8, 0x575708). `CreateSprites` has 16 call sites via its stub 0xb3f8a0.

**So the walkable ground is blitted from the smoothed 2x mapchip sheets, one
16x16-point sprite per metatile.** The baked smoothing survives at 1:1: with
`setContentScaleFactor(2.0)`, `Sprite::setTextureCoords` converts the 16x16-point
`Rect` to **32x32 pixels** of the 512x512 sheet — precisely one cell of its
16x16 grid — onto a 16x16-point footprint that is 32x32 device pixels. Source
and destination match exactly, so nothing resamples the sheet and nothing
recovers the 1x original.

#### The NPOT uploads were RenderTextures, not composited buffers

The device log's 1792x1536 / 1536x1536 / 864x448 / 1280x512 uploads carried
pixel data and no asset path, which was read as "CPU-composited colour buffers".
They are not. `RenderTexture::initWithWidthAndHeight`
(`cocos/2d/CCRenderTexture.cpp:205-231`) scales w/h by
`CC_CONTENT_SCALE_FACTOR()` (2.0), skips `ccNextPOT` when `supportsNPOT()`, then
`malloc`s and `memset(0)`s the buffer before `initWithData` — so **every
RenderTexture uploads non-NULL zeroed data with no path in flight, at NPOT
size.** `FieldMap::makeField` matches them literally:

| site | call | upload |
|---|---|---|
| 0x57543c/0x575440 | `RenderTexture::create(640, 256)` | **1280x512** |
| 0x57571c/0x575720 | `RenderTexture::create(432, 224)` | **864x448** |
| 0x5716a8 / 0x575b04 … | `create([FieldMap+0x330], [FieldMap+0x334])` (map size in points) | 896x768 -> **1792x1536**, 768x768 -> **1536x1536** |

`ExpansionExt`'s own geometry reading in the old text was right as far as it
went — 0x569d50 `lsl w10, w11, #4` and 0x56a0d4 `lsl w8, w8, #4` are ×16, there
is no `lsl #5` anywhere, and the POT round-up at 0x56a0e8-0x56a114 is real — so
its paletted index texture is POT and 1x, and therefore **cannot** be any of the
NPOT uploads above. The 512x512 no-path upload is the best candidate for it
(direct `initWithData`, never through `createTexture`, so no path is parked).
What the old text got wrong was not the arithmetic but the assumption that this
texture is what reaches the screen.

> **Not read line by line:** `MapTable::draw` @ 0x569008 / `drawExt` @ 0x56a8dc /
> `drawZero` @ 0x56b09c, and where `ExpansionExt` stores (and whether anything
> other than `~MapTable` @ 0x56bb4c consumes) the index texture. The positive
> evidence above — a verified sprite blit of every ground metatile from the
> mapchip sheet — stands on its own regardless of what that texture is for.

### 8b. Runtime replacement — the GL-upload swap was INERT (our bug); the FILE swap is the better fix

**Revised (2026-09-04).** The original text of this section said the sheets
go through `ctr::ResourceManager::createTexture(const std::string&)`, which
`gamestate.c`'s mechanism 5 already hooks, so a **path-based swap at
`glTexImage2D` needs no new hook at all**. That was built, all 504 rebuilt
pages were registered as path-keyed entries — and **it never fired**. On device
(Guardia Forest, every page registered) there was never a `replaced mapchip_`
line, while the one-shot alarm did fire:

```
E pixel-gfx: 512x512 upload with no asset path in flight while path-keyed
             replacement mapchip_0_117_0.png is registered at that size
I pixel-gfx: replaced c000_0.png by fingerprint     <- sprites DO swap
```

The premise was not wrong about the *call*: `MapTable::LoadTexture` @ 0x56b874
really does reach `createTexture` through the PLT, so the GOT hook is live
there —

```
56b8c0: bl 0xb3eed0 <_ZN3ctr15ResourceManager13createTextureE...@plt>
```

— it was wrong about the **ABI**, and that alone was enough to kill it.
`cocos2d::TextureCache::addImage(const std::string&)` @ 0x93e518 is an ordinary
member (`x0` = `this`, `x1` = the string), but **`ctr::ResourceManager::
createTexture(const std::string&)` @ 0x5be3bc is STATIC** — `x0` *is* the
string (0x5be3d4 `mov x19, x0`, then 0x5be3ec `mov x0, x19` into the
`(string, Image*)` overload @ 0x5be450). `gamestate.c` hooked both with the
member signature, so it read the path from `x1` for `createTexture` and
`g_pending_tex_path` stayed empty on every one of those loads. Forwarding still
worked by accident (`x0`/`x1` pass through untouched), so the failure was
silent. **So the GL mechanism was never actually tested** — it did not fail, it
never ran. (Section 8a, rewritten, settles the separate question of which layer
consumes the sheets: all of it, ground included.)

The signature is fixed, and with it the GL swap would very likely have worked —
`drawFrontChip` @ 0x56bdac samples the uploaded `Texture2D` directly
(`Sprite::createWithTexture` @ 0x56be7c), so replacing its pixels at
`glTexImage2D` reaches the ground as well as the front chips. The chip sheets
are still deliberately **not** put back on that path, because the file layer is
strictly more general and has fewer ways to be silently inert: it substitutes
*before decode*, so every consumer of the decoded image sees it; it needs no
fingerprint, no `.rgbz`, and no agreement on size or pixel format with the
upload; and the same hook covers `.dat`/`.bin` assets for anything later. After
one mechanism that failed invisibly for a whole build cycle, that margin is
worth taking.

**The fix is to substitute the file, before it is ever decoded.** Every asset
read in this game — archive entry and filesystem fallback alike — funnels
through one function:

```
ctr::ResourceManager::getData(const std::string& path, int* outLen)
    body 0x5be02c    PLT stub 0xb42b80    GOT slot 0xbcd038
```

* **Static member**: `x0` = `std::string*`, `x1` = `int*`, buffer returned in
  `x0`. No `this`, no sret, no `cocos2d::Data`, no `ResizableBuffer` — the
  string is read straight out of `x0` at 0x5be07c-0x5be090 (the `__ndk1` SSO
  test `tst w8,#0x1` / `csinc` / `csel` pair).
* **Covers both read paths.** It first calls `DetchmanResource::LoadFileEntry`
  @ 0x55e810 (the ARC1 archive in `resources.bin`: XOR-descramble with the LCG
  `seed = (base + fileOffset) * 0x41c64e6d + 0x3039`, taking `seed >> 24` per
  byte; then a 4-byte **big-endian** raw size; then
  `ZipUtils::inflateMemory`), and on a miss falls back to
  `DeviceInfo::getMainBundlePath() + path` → `ResourceManager::readFile` @
  0x5be9ec → `FileUtils::getDataFromFile`. Hooking `LoadFileEntry` would miss
  the fallback; hooking `FileUtils` would never see an archive entry at all.
* **Everything is downstream of it.** `ResourceData::ResourceData(const
  std::string&)` @ 0x5bdfc4 — the wrapper behind all 149 `.dat`/`.bin` reads,
  including `ChipData::Load`, `ChipTable::Load`, `MapInfo::Load` — is a thin
  shim over it; and `ctr::ResourceManager::createTexture(path)` @ 0x5be3bc
  reaches it via `createTexture(path, Image*)` @ 0x5be450, which does
  `getData → Image::initWithImageData(bytes, len) → free(bytes) →
  Texture2D::initWithImage`.
* **No bypass.** `grep -cE "bl[[:space:]]+0x5be02c\b" full.asm` is **0** —
  every call goes through the single PLT stub, and `llvm-objdump -R` shows
  exactly one `R_AARCH64_JUMP_SLOT` for the symbol, at 0xbcd038. It is also an
  exported dynamic symbol (`T` at 0x5be02c), so `dlsym` resolves the original.
* **Ownership: `malloc`/`free`.** The buffer originates in
  `ZipUtils::inflateMemory` (`malloc`/`realloc`) and callers free it plainly:
  `ResourceData::~ResourceData` @ 0x5be118 is `ldr x0,[x0]; b free@plt`, and
  `createTexture` does `bl initWithImageData; mov x0,x20; bl free@plt` at
  0x5be494. A substitute buffer **must** come from `malloc()` — never `new[]`,
  never a shared scratch buffer.

`gamestate.c` mechanism 7 patches that slot with `pixel_find_jump_slot` (the
same helper mechanisms 1/2/3/5 use) and, for reads whose basename matches a
registered replacement, returns the rebuilt PNG's own bytes in a fresh
`malloc()`. The game then decodes *our* sheet, so **every** consumer sees it —
GL upload, sprite draw and any CPU blit alike.

Two consequences for the rebuild pipeline:

* the substituted bytes are served **verbatim**, upstream of cocos2d-x's
  premultiply step — unlike the `.rgbz` payloads mechanism 5 uploads, which are
  premultiplied on purpose. Never premultiply a file substitution;
* the chip sheets need **no `.rgbz` at all**, and no fingerprint. The
  path-keyed `.rgbz` entries and their `alphaFp == redFp == 0` sentinel are
  gone; `OrigArtCache` now splits `mapchip_*.png` out of the cache and
  registers name+path pairs through `nativeRegisterFileSubstitutions`.

The **alpha fingerprint** remains a poor key here for the reason the original
text gave: a page's alpha channel is just the index-0 mask, and four sheets are
wholly transparent or wholly opaque. It is no longer used for them either way.

Caveat unchanged: `mapchip_23_33_ev.png`, `mapchip_23_33_ev_test.png`,
`mapchip_23_33_test.png` and friends also ship — leftover authoring assets that
`LoadTexture`'s `%d_%d_%d` format string can never name. Ignore them.

## 9. Files not needed for this rebuild

* `MapTable/MapTable_%04d.dat` — the map layout (which metatile goes where).
  Needed to render a whole field map, not to rebuild a chip sheet.
* `mapext_bin/mapext_%d.bin`, `ChipTable/ChipTableBg3_%04d.dat`,
  `weather_bin/*` — the BG3 / extension / weather layers
  (`MapTable::LoadMapExt` @ 0x562de0, `ChipTable::LoadExt` @ 0x5616a8,
  `ChipTable::LoadWeather` @ 0x561ea8).
* `PrioMap/PrioMap%d.dat`, `atel/*`, `BGAnime/bganimeinfo_%d.dat` — priority,
  event-trigger and BG-animation data.
* `map_bin/cgext%d.bin`, `cg646_%d.bin`, `cg_bg.bin` — the slot-7 ext heap and
  special cases; the seven addressable banks come from BGSetTable.
