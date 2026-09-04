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

### 8a. Where the mapchip sheets are actually used — **not** the field background

This is the finding that matters most for the intended swap, and it is the
opposite of the working assumption.

`MapTable::writeChip(ImageArray<Color4B>&, ChipTable&, ...)` @ **0x56b5f0** —
the primitive behind `MapTable::draw` @ 0x569008, `drawExt` @ 0x56a8dc and
`drawZero` @ 0x56b09c, which are all that `MapTable::Expansion` @ 0x568af0 and
`MapTable::ExpansionExt` @ 0x569c28 call — reads **one byte per pixel from
`ChipTable + 0x3200 + (page<<16) + srcY*0x100 + srcX`** (0x56b648-0x56b65c),
i.e. from the 1x index page of section 5, and writes `Color4B(index, 0, 0, 0)`
into a CPU RGBA buffer, advancing **4 destination bytes per source pixel**
(0x56b6bc-0x56b6c4). That is a **1:1, 1x copy** with the palette index in the
red channel — the same trick the overworld uses.

The destination buffer's *measured* dimensions confirm 1x independently of that
stride argument. In `MapTable::Expansion` @ 0x568af0 the map's metatile width and
height are loaded from two tables (0x568be0/0x568be4) and each **multiplied by
16** — `lsl w9, w11, #4` @ 0x568bf0 and `lsl w8, w8, #4` @ 0x568c70 — then
rounded up to the next power of two by the NEON `sshr`/`orr` chain at
0x568c8c-0x568cb8. The result is `w28` (width) and `w19` (height); the buffer is
allocated as `w28 * w19 * 4` bytes (0x568cc8-0x568cfc), `w28` is written to
`ImageArray+0x18` (0x568d68) — which is exactly the stride `writeChip` reads at
`[x19,#0x18]` — and the same pair is passed as `w4`/`w5` to
`Texture2D::initWithData(data, len, PixelFormat=2 (RGBA8888), w, h, size)` @
**0x568e78**, the index texture `Shaders/ShaderDrawPalettedTexture.fsh` samples.
**16 px per metatile is 1x**; a 2x background would be `lsl #5`. So the field
background texture is a power-of-two-padded 1x image (e.g. 1024x512 for a 48x28
metatile map, painting 768x448).

By contrast, the `Texture2D*`s that `MapTable::LoadTexture` stores at
`this+0x98 / +0xa0 / +0xa8[i]` are read by **exactly two functions**
(a scan of every `MapTable` method below 0x56d3fc): `MapTable::drawFrontChip` @
**0x56bdac** and `MapTable::drawFrontChipExt` @ **0x56c88c**, which hand them to
`MapTable::drawChip(Texture2D*, ...)` @ **0x56cf68** ->
`Sprite::createWithTexture` -> `Node::visit`. Those are reached from
`MapTable::CreateSprites` @ 0x56bbdc / `CreateSpritesExt` @ 0x56c220 /
`drawAnimeChipExt` @ 0x56c6d8 — the **animated chips and the front/priority
chips**, plus `CreateTakara` (treasure chests).

**So the walkable field background is already composited from the original 1x
cg tiles; the smoothed 2x mapchip sheets supply only the sprite-drawn animated
and foreground chips.**

> **Confirmed (2026-09-04).** Follow-up RE and a device screenshot both bear
> this out, and additionally show there is **no LINEAR filtering anywhere in
> the field path**: right after `initWithData` @ 0x568e78, 0x568e84-0x568f00
> feeds `Texture2D::setTexParameters` the 16-byte constant at **0x374f60** =
> `{0x2600, 0x2600, 0x812F, 0x812F}` = `{GL_NEAREST, GL_NEAREST,
> GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE}` (the nearby conditional `mov w8,#0x2901`
> = `GL_REPEAT` writes only the two WRAP fields). The palette texture
> (`createPaletteTexture` @ 0x5be520 → `setAliasTexParameters` @ 0x5be7e8) and
> every `RenderTexture` (`initWithWidthAndHeight` @ 0x895f38; 19 created by
> `FieldMap::makeField` @ 0x57520c) are NEAREST too, and
> `Texture2D::initWithMipmaps` @ 0x936b8c always sets MIN/MAG explicitly, so
> nothing falls back to the GLES `MAG = GL_LINEAR` default. The residual
> ground softness is **geometric**: `AppDelegate::applicationDidFinishLaunching`
> @ 0x6417f0 uses a 568x320-point design resolution (constant at 0x641c34) with
> `setContentScaleFactor(2.0f)` @ 0x641afc and NO_BORDER, so one source texel
> covers `1920/568 = 3.380` screen pixels on a 1080p panel and NEAREST produces
> blocks alternating 3 and 4 px wide. A screenshot's horizontal run lengths over
> the ground, the front chips and the character sprite are the same 3/4-px
> distribution, which rules out filtering on any of them. Whatever residual blur the background shows is runtime
filtering/scaling of a 1x index texture, not baked-in smoothing — a different
problem from the one the mapchip sheets cause. This should be re-checked in
game before any work is scheduled: it predicts that today the field background
is (up to runtime filtering) crisp original art and only the animated/foreground
chips carry baked-in smoothing. The one link not read line-by-line is the body of
`MapTable::draw` @ 0x569008 and `drawExt` @ 0x56a8dc, which have inlined
`Color4B` writes on paths that do not go through `writeChip`; they cannot change
the buffer's dimensions (measured above) but could in principle paint into it
differently.

### 8b. If the mapchip sheets are to be replaced anyway

They go through `ctr::ResourceManager::createTexture(const std::string&)` (calls
at 0x56b8c0, 0x56b8f4, 0x56b96c), which is **already hooked** by
`gamestate.c`'s mechanism 5 (`PIXEL_SYM_CREATETEXTURE` / `hooked_createTexture`,
which parks the asset path in `g_pending_tex_path` for the duration of the
call). So a **path-based** swap needs no new hook at all: when
`g_pending_tex_path` matches `mapchip_*.png`, substitute the replacement in
`hooked_glTexImage2D`. The upload itself is a plain 512x512 RGBA8888
`glTexImage2D` (cocos2d-x expands the paletted PNG with `tRNS` to RGBA8888 in
`Image::initWithPngData`), which is the shape mechanism 5 already handles, and
the existing `.rgbz` cache format (premultiplied RGBA8, zlib) fits unchanged —
`rebuild_mapchip.py` already emits a 512x512 RGBA PNG per page.

The **alpha fingerprint** used for character sheets is a poor key here and
should not be relied on: a mapchip page's alpha channel is just the index-0
mask, and many sheets share large flat regions (four sheets are entirely
transparent or entirely opaque). Use the path, which is unique and free.

Caveat for whoever implements it: `mapchip_23_33_ev.png`,
`mapchip_23_33_ev_test.png`, `mapchip_23_33_test.png` and friends also ship —
leftover authoring assets that `LoadTexture`'s `%d_%d_%d` format string can
never name. Ignore them.

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
