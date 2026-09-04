# Chrono Trigger (Android port) OVERWORLD art format — REPORT

**Headline: the port ships the original 1x SNES overworld tile graphics, and
the overworld's tile-reference bit layout is *not* the field's.**

`Game/world/map_bin/cg*.bin` are untouched 4bpp 1x tile banks,
`Game/world/Chip/Chip_%04d.dat` the metatile definitions and
`Game/world/plt_bin/plt%d.bin` the BGR555 palettes. The 512x512
`Game/world/worldchip_<a>_<b>_<page>.png` sheets are a *derived*, 2x-upscaled,
smoothed, 256-colour-quantised bake of exactly that data.
`rebuild_worldchip.py` in this directory reconstructs all 14 shipped sheets
from the 1x sources; **11 of 3584 metatile cells (0.31%) differ by more than a
colour tolerance, and none differs structurally** (section 5).

Separately, the 14 `Game/world/gif/<name>.png` sheets that ship next to a 1x
`<name>.bmp` are re-packed 2x bakes of that BMP — the same relationship the
chara sheets have — and are rebuilt by the existing
`tools/orig_art/rebuild_sheet.py` / `origart/SheetRebuilder` matcher
(section 7).

All addresses are `libchrono.so` virtual addresses; symbols are the shipped
C++ symbols (mangled in `full.asm`, demangled here). This report is about the
*art*; `tools/world_map/REPORT.md` covers the map layers, world→asset mapping
and compositing, and is not repeated here.

Tools in this directory:

```
python3 rebuild_worldchip.py <extracted-Game/world-dir> <out-dir> [--crops]
                                                        [--libchrono <so>]
python3 rebuild_worldchip.py <dir> --list
javac  -d <classes> app/src/main/java/com/kalenjohnson/chronoduo/origart/MapchipCore.java \
                    tools/world_art/JavaWorldchipCheck.java
java   -cp <classes> JavaWorldchipCheck <world-dir> <out-dir> [ref-dir]
```

---

## 1. The load path — `WorldMap::LoadMap(int worldId)` @ **0x606a30**

| step | call | meaning |
|---|---|---|
| 0x606a8c | `WorldMapInfo::WorldMapInfo(worldId)` | the 0x30-byte descriptor for this world |
| 0x606a94–0x606afc | `world::ChipData::Load(info[k], slot)`, `k` = u16 0..6 (+0x00..+0x0C), slots 0..6 | the 7 CG (4bpp 1x) banks |
| 0x606b00 | `world::Palette::Load(info u16 10, +0x14)` | `plt_bin/plt<n>.bin` |
| 0x606b14 | `world::Palette::ColAnim(worldId)` | palette colour cycling |
| 0x606b28 | `world::ChipTable::Load(info u16 16, +0x20)` | `Chip/Chip_%04d.dat` |
| 0x606b34–0x606bbc | two 32x32 loops of `ChipTable::setChip_8_8(chipData, page, x, y, word, false)` | expands both pages to 256x256 index images |
| 0x606d3c | `world::MapData::LoadTexture(info+0x20, info+0x14)` | the pre-rendered `worldchip` PNGs |

`world::ChipTable::Expansion(ChipData&)` @ **0x604770** is the same two loops
in one function (page 0 from `this+0`, page 1 from `this+0x1000`, both
`for y in 0..31 / for x in 0..31`), which is what `rebuild_worldchip.py` and
`MapchipCore.worldExpandPage` reproduce.

**There is no `bgsettable` for the overworld.** The field reads its cg bank
ids from `BGSetTable/bgsettable_%d.dat`; the overworld reads them straight out
of the in-binary `WorldMapInfo` row (section 2). That is why the Java copy of
the table has to be a hardcoded constant (section 8).

## 2. `WorldMapInfo::G_WORLDMAPINFO` @ **0xbe2508** — the bank/slot table

`WorldMapInfo::WorldMapInfo(int)` @ 0x60ae18 copies 24 `u16` from
`G_WORLDMAPINFO + worldId * 0x30`. The global is a `GLOB_DAT` relocation
(`0xbc70d8 → 0xbe2508`), and `WORLDMAPINFO_MAX` @ 0xbe2688 reads 8
(0xbe2688 − 0xbe2508 = 0x180 = 8 × 0x30). Read straight out of the shipped
ELF (`rebuild_worldchip.py --libchrono`):

| world | cg slots 0..6 (u16 0..6) | plt (u16 10) | chip (u16 16) | map (u16 17) | sheet pair |
|---|---|---|---|---|---|
| 0 | 0, 1, 2, 3, 4, 5, 6 | 4 | 0 | 0 | `worldchip_0_4_{0,1}` |
| 1 | 0, 1, 2, 3, 4, 5, 6 | 5 | 0 | 1 | `worldchip_0_5_{0,1}` |
| 2 | 0, 1, 11, 12, 13, 14, **128** | 7 | 2 | 3 | `worldchip_2_7_{0,1}` |
| 3 | 27, 28, 29, 30, 31, 32, 33 | 8 | 3 | 4 | `worldchip_3_8_{0,1}` |
| 4 | 0, 1, 2, 3, 15, 16, 17 | 9 | 4 | 5 | `worldchip_4_9_{0,1}` |
| 5 | 18, 19, 22, 23, 24, 25, 26 | 10 | 5 | 7 | `worldchip_5_10_{0,1}` |
| 6 | 0, 1, 2, 3, 15, 16, 17 | 9 | 4 | 6 | `worldchip_4_9_{0,1}` |
| 7 | 0, 1, 7, 8, 9, 10, 6 | 6 | 1 | 2 | `worldchip_1_6_{0,1}` |

The 8 worlds fold to **7 distinct `(chip, plt)` pairs = exactly the 7 shipped
sheet pairs = 14 shipped sheets**, with none left over and none missing.
Worlds 4 and 6 share `(4, 9)` **and have identical cg slots**, so which banks
bake a given sheet is unambiguous — unlike the field's `(62,21)`, which had
two candidate bgsets. `MapchipCore.worldSheetPairs()` asserts this rather than
assuming it.

`u16` 7 and 9 are 128 in every row; `u16` 8, 11, 12, 13 (values 20/21/34/35,
11..17, 2..12) are read by neither `LoadMap` nor any other `ChipData::Load`
call — the only 7 calls to `world::ChipData::Load` in the whole binary are the
ones at 0x606a9c..0x606afc. `u16` 14/15 are 6/7 in every row. `u16` 18 is the
`Id_%04d` column (see `tools/world_map/REPORT.md`).

## 3. `map_bin/cg%d.bin` — 4-byte header + a LINEAR 4bpp bitmap, 1x

`world::ChipData::Load(int cgId, int slot)` @ **0x606d9c**:

```
if (cgId == 0x80) return;                       // 0x606dc0 `cmp w1,#0x80; b.eq`
name = format("Game/world/map_bin/cg%d.bin", cgId);   // fmt @ 0x3665b5
getShort();                                     // 0x606e80  DISCARDED
rows = getShort();                              // 0x606e88
copy(this + (slot << 12), rows << 6);           // 0x606e8c-0x606e9c
```

Two differences from the field's `ChipData::Load` @ 0x560dcc worth stating:

* **128 is the only "no bank" sentinel.** There is no `cbz` and no
  `cmp #0xff`: `cg0.bin` is a real bank and *is* world 0's slot 0. Reusing the
  field's 0x00/0xFF rule would blank a live bank. Only world 2's slot 6 is
  empty (128).
* **The size is `rows * 0x40` only** — the first header `u16` is read and
  thrown away, where the field computes `b * ((a >> 1) & 0x3FFF)`. Numerically
  identical for every shipped bank (header `80 80 40 00` → 64 × 0x40 =
  0x1000), but the code genuinely differs.

So `ChipData` is a flat `7 * 0x1000 = 0x7000`-byte buffer at stride 0x40 —
a **128 × 448 px LINEAR 4bpp bitmap**, 8 rows × 16 columns of 8x8 tiles per
bank = 128 tiles per bank, 896 tiles total. There is no slot-7 ext heap in the
world path.

## 4. `Chip/Chip_%04d.dat` — 4096 bytes = 2 pages × 256 metatiles × 4 u16

`world::ChipTable::Load(int)` @ **0x6044c8** runs two identical 16×16 loops.
Per metatile it reads **four `getShort()`** — no per-tile byte, unlike the
field — and widens them to `u32`:

```
page base    = this + page*0x1000          (page 0 @ 0x6045a4, page 1 @ 0x60463c)
word(Y, X)   = u32 at page base + Y*0x80 + X*4        (Y,X on a 32x32 grid)
   metatile(R,C): TL=(2R,2C) TR=(2R,2C+1) BL=(2R+1,2C) BR=(2R+1,2C+1)
priority     = this + 0x2000 + page*0x100 + R*0x10 + C
               bits 0..3 = BIT 13 of the TL/TR/BL/BR words
               (0x6045ec-0x604618: `lsr #12 & 2`, `lsr #11 & 4`, `lsr #10 & 8`,
                `bfxil w8,w20,#13,#1` — all four are bit 13)
```

2 × 256 × 4 × 2 = **4096 bytes = exactly the file size.**

### Tile-reference bit fields (`world::ChipTable::setChip_8_8` @ **0x604830**)

**This is the headline finding and it is not the field's layout.**

| bits | world meaning | field meaning (0x56212c) | evidence @ 0x604830 |
|---|---|---|---|
| 0..9 | 8x8 tile index | same | `ubfiz w29,w5,#3,#4` (low nibble → column) + `lsr w8,w5,#1; and #0x1f8` (bits 4..9 → row × 8) |
| 7,8,9 all set | **blank** | same | `mov w8,#0x380; bics wzr,w8,w5; b.ne` @ 0x60486c |
| 10..12 | **palette group (3 bits, 0..7)** | *hflip, vflip, palette bit 0* | `lsr w13,w5,#6; and w9,w13,#0x70` @ 0x6048dc/0x604934 |
| 13 | **priority** | *palette bit 1* | the prio byte in `ChipTable::Load`, above |
| 14 | **horizontal flip** | *palette bit 2* | `sbfx w14,w5,#14,#1` @ 0x6048d0; ORs 6 into the column and selects the LOW nibble (`tst w14,#1; csel w11,hi,lo,eq`) |
| 15 | **vertical flip** | *palette bit 3* | `sbfx w9,w5,#15,#1` @ 0x6048d4; `bfxil w8,w9,#0,#3` ORs 7 into the row |

Address formula (identical in shape to the field, only the flip bits move):

```
tile   = word & 0x3FF
colval = (word & 0x4000) ? 7 - col : col
rowval = (word & 0x8000) ? 7 - row : row
v      = (tile & 0x0F) * 8 + colval
byte   = chipData[ ((tile >> 4) * 8 + rowval) * 0x40 + (v >> 1) ]
nibble = (v & 1) ? (byte & 0x0F) : (byte >> 4)        // HIGH nibble = LEFT pixel
index  = (nibble == 0) ? 0 : (nibble | (((word >> 10) & 7) << 4))
```

Index 0 is the transparency key (`csel w13, wzr, w13, eq` @ 0x604954), so
colour 0 of *every* group maps to output index 0. Because the palette group is
only 3 bits, a statically baked page only ever emits indices 0..0x7F, even
though `plt<n>.bin` ships 256 entries — the top half is colour-animation
space (`world::Palette::ColAnim`, `colanim_bin/<n>_colanim.bin`).

The expanded page lands at `chipTable + 0x2200 + page*0x10000`, row stride
0x100 (`mov w8,#0x2200; add x8,x0,x8; add x16,x8,x9,lsl #16` @ 0x60484c–
0x604864), i.e. a **256x256 8-bit index image = 16x16 metatiles of 16x16 px,
at 1x** — and the shipped sheet is exactly 2x of that.

`world::Palette::Load` @ **0x604070** is a `u16` count followed by that many
BGR555 words, `Color4F(r/31, g/31, b/31, i ? 1 : 0)` (`ubfx #10,#5` → blue,
`ubfx #5,#5` → green, `and #0x1f` → red, `fdiv` by 31.0). The float→u8
rounding mode of `Color4B(const Color4F&)` is **not recoverable from the
shipped sheets** — the residual is ±1–2 and the sheets' adaptive 256-colour
quantisation is larger — so `MapchipCore.loadPalette`'s exact
`(c * 0x20E7F7) >> 18` is reused verbatim for both Python and Java. It does
not affect output quality.

`world::MapData::LoadTexture(int chip, int plt)` @ **0x606288** formats
`Game/world/worldchip_%d_%d_%d.png` (string @ 0x36cd15) with the page as 0
then 1, so the filename is `worldchip_<Chip_%04d id>_<plt id>_<page>.png`.

## 5. Verification — `worldchip`, all 14 sheets

`rebuild_worldchip.py <world-dir> <out> --crops`. Metrics as in
`tools/field_art/REPORT.md`: the shipped sheet is smoothed and re-quantised to
an adaptive palette that is not the game's `plt`, so index-vs-index means
nothing and only RGB metrics are reported.

* `inv` — **shipped-PNG-independent invariants**: `slot` counts tile refs
  addressing an *empty* cg slot, `range` counts refs with `tile >= 0x380`
  (= 7 slots × 128 tiles = 896, the same self-proving constant as the field's
  blank threshold). Both are 0 for all 14 sheets, i.e. the slot mapping is
  internally consistent before any comparison is made.
* `alpha` — transparent-mask agreement. **Not a quality metric here** (see the
  caveat below).
* `flat_q4/q8` — rebuilt 1x pixel vs the 2x2 blocks the shipped filter left
  flat, allowing an RGB channel error of 4/8.
* `near16` — rebuilt 1x pixel vs the *average* of the shipped 2x2 block, ±16,
  i.e. allowing the smoothing filter as well.
* `bad_cells` — 16x16-metatile cells where `near16` holds for < 80% of the
  cell. **This is the metric that matters**: an aggregate would hide a wrong
  cg bank in one slot, a per-cell count cannot.

| sheet | inv | alpha | flat_q4 | flat_q8 | near16 | bad cells | which |
|---|---|---|---|---|---|---|---|
| `worldchip_0_4_0` | 0/0 | 0.9929 | 0.8790 | 0.9911 | 0.9736 | **1** / 256 | (3,0) |
| `worldchip_0_4_1` | 0/0 | 0.9961 | 0.9231 | 0.9924 | 0.9914 | **0** | |
| `worldchip_0_5_0` | 0/0 | 0.9929 | 0.8570 | 0.9909 | 0.9685 | **1** | (3,0) |
| `worldchip_0_5_1` | 0/0 | 1.0000 | 0.9727 | 0.9956 | 0.9941 | **0** | |
| `worldchip_1_6_0` | 0/0 | 0.9956 | 0.8874 | 0.9746 | 0.9612 | **1** | (3,0) |
| `worldchip_1_6_1` | 0/0 | 0.9462 | 0.9401 | 0.9883 | 0.9915 | **0** | |
| `worldchip_2_7_0` | 0/0 | 0.9945 | 0.9796 | 0.9968 | 0.9790 | **0** | |
| `worldchip_2_7_1` | 0/0 | 0.8523 | 0.9615 | 0.9943 | 0.9992 | **0** | |
| `worldchip_3_8_0` | 0/0 | 0.9909 | 0.9586 | 0.9888 | 0.9671 | **2** | (3,13) (3,14) |
| `worldchip_3_8_1` | 0/0 | 0.9984 | 0.9265 | 0.9669 | 0.9801 | **3** | (0,12) (0,13) (4,7) |
| `worldchip_4_9_0` | 0/0 | 0.9908 | 0.7918 | 0.8612 | 0.9411 | **2** | (1,8) (2,6) |
| `worldchip_4_9_1` | 0/0 | 0.9902 | 0.9489 | 0.9741 | 0.9990 | **0** | |
| `worldchip_5_10_0` | 0/0 | 0.1328 | 0.9310 | 0.9465 | 0.9879 | **0** | |
| `worldchip_5_10_1` | 0/0 | 0.9945 | 0.8693 | 0.9890 | 0.9836 | **2** | (8,0) (8,1) |

**Total: 11 bad cells out of 14 × 256 = 3584 (0.31%).** Inspected
individually, all 11 draw the *same art* as the shipped sheet, at the same
offsets, with the same alpha shape — they differ only in palette brightness
(e.g. `worldchip_0_4_0` cell (3,0) is a forest cluster that the shipped bake
renders noticeably lighter than `plt4.bin` group 3 gives).

The strongest evidence for what causes them: **`worldchip_0_4_0`, `0_5_0` and
`1_6_0` all fail at the same cell (3,0), with the same four tile refs
(`0xde6 0xde1 0xdf6 0xdf7`, all palette group 3) — across two different chip
tables (0 and 1) and three different palettes (4, 5, 6).** That makes it a
per-*tile* property, not a per-sheet bake artefact, which is exactly what
colour animation would look like: the shipped bake captured a
`world::Palette::ColAnim` phase for the animated entries rather than the raw
`plt` values. Not chased into `ColAnim` — the structure is right and the
difference is a hue shift on 0.3% of cells.

### The `alpha` caveat — the shipped sheets fill unused cells

`alpha` is low on three sheets (`5_10_0` 0.13, `2_7_1` 0.85, `1_6_1` 0.95) and
this is **not** an error. In every case the rebuilt opaque set is a strict
subset of the shipped one (`mine & ~ship` = 0 pixels, on every sheet), and the
extra shipped pixels are a flat colour filling *metatile cells the chip table
leaves blank* — cells whose four words all have `0x380` set, which
`setChip_8_8` provably never writes to, and which `MapData::Expansion` never
draws because map index 0 means "no metatile". `worldchip_5_10_0` is the
extreme: 14 of its 16 metatile rows are unused and the shipped sheet paints
them a flat slate blue, where the rebuild leaves them transparent. Cell (0,0)
of several page-1 sheets is the same story.

### Crops

`--crops` writes `sbs_<a>_<b>_p<page>.png` per sheet (shipped left, rebuilt
right, 3x nearest, on the highest-variance 96x96 window). Inspected: the
rebuilds are the same art, crisp instead of blurred, exactly as for the field
mapchips.

## 6. Overworld files that are *not* rebuildable

* **`Game/world/worldchipScr3_<n>_{2,3}.png` (10 sheets).**
  `WorldMap::initWeatherMap(int, float, const char*)` @ **0x60802c** is the
  only consumer. It formats `Game/world/worldchipScr3_%d_2.png` (@ 0x362b3c)
  and `..._3.png` (@ 0x36852f) with an index derived from the world state
  (`w20 = (this->0x378 - 1) < 2 ? 2 : arg1`, 0x60806c–0x60808c), hands each to
  `ctr::ResourceManager::createTexture` (0x608124, 0x6081cc) and wraps them in
  `cocos2d::Sprite::createWithTexture` (0x6082c4) inside a `Node`. **No chip
  table, no cg bank, no palette anywhere in the path** — and no `Bg3`/weather
  chip file ships under `Game/world` (the archive has only
  `Chip/Chip_0000..0005.dat`, all 4096 bytes = 2 pages). These are authored 2x
  textures with no 1x source. Documented and skipped.
* **`Game/common/worldChara.png`** — the overworld party sprite sheet. No
  `.bmp` sibling anywhere in resources.bin, and no cg/chip-table path. Now
  also disproved by measurement — see 6.1.
* **`Game/common/silbird.png`** — the Epoch ("silverd" / シルバード) overworld
  sprite, the one other `Game/common` sheet the overworld draws. Same
  category as `worldChara`: no `.bmp` sibling, so the pipeline is not extended
  to `Game/common` for it. (`Game/common` does ship four PNG/BMP pairs —
  `blackdream`, `lavos`, `warp_bg`, `warp_obj` — which are structurally the
  same "2x PNG next to 1x BMP" case section 7 handles. Who loads them, and
  whether any belongs in the pipeline, was **not chased**: out of scope here.)
* **The seven `Game/world/gif/<n>_wboa.bmp` that ship without a `.png`**
  (`1,2,3,4,5,6,7_wboa`). There is nothing to replace: the game never reads a
  PNG at those names.

### 6.1 `worldChara.png` — the measured negative

`tools/world_art/rebuild_worldchara.py` tests the one remaining hypothesis:
that the overworld frames are the *field* frames, and so can be found inside
`Game/chara/bmp/c00N_*.bmp`. Run it to reproduce; it exits 1.

```
python3 tools/world_art/rebuild_worldchara.py <worldChara.png> <chara-bmp-dir> <out-dir> \
    --corpus <dir-of-bmps> [--corpus <dir-of-bmps> ...]
```

Every directory argument is a flat directory of `.bmp` files extracted with
`tools/ctres.py`; `<chara-bmp-dir>` needs at least `c000_0.bmp` ..
`c006_1.bmp`, and the `--corpus` dirs together should cover all 708 shipped
BMPs (`Game/chara/bmp/` 629, `Game/battle/oef/` 54, `Game/world/gif/` 21,
`Game/common/` 4) to reproduce the numbers below.

**The sheet.** 384x384 RGBA, 8 rows x 8 columns of 48x48 cells. Segmenting on
`alpha > 0` — what `rebuild_sheet.segment_png` does — gives **2914**
components, because the alpha channel carries a wide halo of 1..46-valued
pixels. At any threshold in **64..192** it gives **71**, of which **63** are
larger than 8x8 and 8 are sparkle specks. 63 = 7*8 + 7: rows 0..6 are the
seven party members in character-id order at 8 frames each, row 7 is one
extra pose per character. The layout is exactly as expected; only the source
is missing.

**It is a 2x upscale, but of something that does not ship.** 2x2 blocks
aligned to phase (0,0) have a mean intra-block channel range of **66**, vs
**141** at phase (1,1) — real 2x block structure. But only **4.6%** of those
blocks are flat within +-8, and the sheet holds **31681** distinct opaque
colours (a `Game/chara/png` sheet holds 256). This asset went through a lossy
pipeline; there is no crisp 1x anywhere behind it.

**No source in the corpus.** Each of the 63 sprites was alpha-weighted 2x2
box-downsampled to 1x (more generous than `rebuild_sheet`'s "topleft"
decimation, which on this sheet samples noise) and matched with
`rebuild_sheet`'s masked SAD + horizontal flips against: its own character's
bitmaps at the +-2 size gate; all seven characters' for row 7; and, as a
fallback at a widened +-5 gate, the **whole corpus — every BMP in the
archive: 16194 components from all 708** (629 `Game/chara/bmp/`, 54
`Game/battle/oef/`, 21 `Game/world/gif/`, 4 `Game/common/`).

| row | character | matched | best SAD | worst SAD |
|---|---|---|---|---|
| 0 | Crono | 0/8 | 201.1 | 212.4 |
| 1 | Marle | 0/8 | 209.9 | 229.0 |
| 2 | Lucca | 0/8 | 166.5 | 202.9 |
| 3 | Robo | 0/8 | 168.0 | 198.0 |
| 4 | Frog | 0/8 | 126.0 | 193.4 |
| 5 | Ayla | 0/8 | 195.2 | 216.2 |
| 6 | Magus | 0/8 | 181.1 | 192.5 |
| 7 | extra pose | 0/7 | 166.5 | 216.3 |

**0/63** against a `SCORE_THRESHOLD` of **60.0** — for scale, a true match
scores near 0 (`7_wobj0` in section 7 is a plain pixel-double and rebuilds at
0.0 on all 161 frames). Best whole-corpus shape agreement is IoU ~0.85, and it is always
on the *wrong character*: `worldchara_sbs.png` (shipped left, best match
right) shows the right-hand sheet filled with unrelated NPCs, monsters and
rocks. The overworld party sprites are separate, smaller art (~12x22 at 1x,
against ~16x24 for the field frames) that ships only as this one 2x PNG.

**Not shipped: the box-downsample.** The 2x block structure does mean a box
downsample recovers a clean-looking 1x, which re-doubled would give a crisp
sheet — the script writes it as `worldchara_1x.png` for inspection. It is
deliberately **not** wired into the pipeline: its colours are averages
recovered from a lossy 2x asset, not palette entries, so it is a new authored
asset, not the game's own original art. Everything behind the pixel-graphics
switch outputs the game's own pixels (BMP palette entries via
`SheetRebuilder`, `plt<n>.bin` colours via `MapchipCore`); a synthetic sheet
does not belong behind the same switch.

## 7. `Game/world/gif/*` — 2x PNG next to 1x BMP, re-packed

14 basenames ship both: `0_wboa`, `{0,1,3,4,5,6,7}_wobj0`,
`{0,1,4,5,6}_wobj1`, `4_kodai_break`. The BMPs are **8bpp BI_RGB, bottom-up**,
`BITMAPV4HEADER` (biSize 0x6c) except `7_wobj0` (`BITMAPINFOHEADER`, 0x28) —
all accepted by `origart/BmpIndexed` unchanged.

The PNG is exactly 2x the BMP's dimensions (128x64 → 256x128; `4_kodai_break`
128x96 → 256x192; `7_wobj0` 128x128 → 256x256), but it is **not a
pixel-double**: a plain 2x of the BMP matches the PNG on only 0–15% of opaque
pixels. The frames are re-packed, exactly like the chara sheets — so the
rebuild is the existing segment-and-match path
(`tools/orig_art/rebuild_sheet.py` / `origart/SheetRebuilder`), unchanged, and
no new matcher was written.

| sheet | png frames | bmp frames | matched | notes |
|---|---|---|---|---|
| `0_wboa` | 18 | 19 | **3** | a backdrop, not a sprite atlas — segments poorly |
| `0_wobj0` | 57 | 84 | 38 | |
| `0_wobj1` | 15 | 15 | 12 | |
| `1_wobj0` | 41 | 41 | 40 | |
| `1_wobj1` | 1 | 1 | 1 | |
| `3_wobj0` | 16 | 16 | 10 | pterodactyls; scores 38–59, near the 60 threshold |
| `4_kodai_break` | 41 | 41 | **41** | |
| `4_wobj0` = `5_wobj0` = `6_wobj0` | 2 | 2 | 2 | byte-identical files (same md5) |
| `4_wobj1` = `5_wobj1` = `6_wobj1` | 69 | 77 | 65 | byte-identical files (same md5) |
| `7_wobj0` | 161 | 161 | **161**, all score 0.0 | already a plain 2x pixel-double |

**507 of 563 frames matched.** Unmatched frames fall back to the shipped
(smoothed) pixels verbatim, so a rebuilt sheet is never *worse* than the
shipped one; `0_wboa` is simply mostly unimproved. `7_wobj0` scoring 0.0 on
every frame is a useful control: it is the one pair that *is* a straight
pixel-double, and the matcher reproduces it exactly rather than degrading it.

### No holes: the opaque-mask check

`SheetRebuilder.rebuild` zero-fills its output and writes only regions covered
by a PNG-alpha component, so a region that fails to segment would come out
*transparent*, not "unchanged" — the real failure mode for `0_wboa`, which is
a full-frame backdrop rather than a sprite atlas and matched only 3/18 frames.
Checked directly, per sheet: `shipOpaque & ~mineOpaque`, and then how much of
that loss lies **two or more pixels inside** the shipped opaque region
(eroded twice).

```
0_wboa.png         ship_opaque=20065  lost=79   deep-interior=0
0_wobj0.png        ship_opaque=12294  lost=33   deep-interior=0
4_kodai_break.png  ship_opaque=22864  lost=52   deep-interior=0
4_wobj1.png        ship_opaque= 8472  lost=115  deep-interior=0
7_wobj0.png        ship_opaque=19756  lost=0    deep-interior=0
... (all 14)                          total 662 deep-interior=0
```

**Zero interior pixels lost on any of the 14 sheets.** Every lost pixel is on
the boundary — the anti-aliased halo the shipped smoothing added and the crisp
1x original does not have — which is the same trimming the chara sheets
already ship with. `0_wboa` is safe.

Visual check: the rebuilt `3_wobj0` frames sit at the shipped frame offsets
and match the BMP source frame for frame.

## 8. Java parity and the on-device wiring

`origart/MapchipCore` grew an overworld section — `WORLDMAPINFO`,
`worldSheetPairs()`, `worldBuildChipData`, `worldLoadChipTablePage`,
`worldExpandPage`, `worldRenderSheet2x` — **added alongside** the field
methods, none of which changed (`tools/field_art/JavaMapchipCheck` still
proves the field path). `loadPalette` and `colorize2x` are shared verbatim.

`MapchipCore.WORLDMAPINFO` is the one hardcoded constant in the pipeline
(there is no `bgsettable` to read, section 1). It is checked two ways:
`rebuild_worldchip.py --libchrono <so>` re-reads `G_WORLDMAPINFO` out of the
ELF's PT_LOAD segments and diffs it against the Python copy, and
`JavaWorldchipCheck` renders all 14 sheets from the Java copy and compares
them to the Python renders:

```
$ python3 tools/world_art/rebuild_worldchip.py $W $OUT --libchrono $S/lib/libchrono.so
G_WORLDMAPINFO @ 0xbe2508: MATCHES table
$ java -cp $S/wclasses JavaWorldchipCheck $W $S/world_java_out $OUT
=== Summary ===
Matched: 14/14                     # every sheet pixel-identical
```

and for the gif half, the existing `tools/orig_art/JavaRebuildCheck` (which
already proves `SheetRebuilder` == `rebuild_sheet.py` for the chara sheets)
run over all 14 world pairs: **14/14 at 100.0000% pixel match.**

On device, `origart/WorldchipRebuilder.rebuildAll` writes all 28 outputs
(14 `worldchip_*.png` + 14 gif sheets) into `<filesDir>/orig_art/` under the
shipped basenames, as the third phase ("overworld") of
`AppActivity#requestOrigArtBuild`. `OrigArtCache.refresh` registers them for
FILE-level substitution — `gamestate.c`'s `ctr::ResourceManager::getData` hook
serves the PNG bytes in place of the archive entry — via the anchored patterns
in `OrigArtCache.FILE_SUBSTITUTED`:

```
mapchip_\d+_\d+_\d+\.png     worldchip_\d+_\d+_\d+\.png
\d+_wobj\d+\.png             \d+_wboa\.png             \d+_kodai_break\.png
```

Anchored, not prefix-matched, specifically so `worldchip_\d+_\d+_\d+` can
never catch `worldchipScr3_0_2.png` (which has no source and is never
rebuilt).

The hook matches on **basename**, against every asset the game reads — so the
question is not just whether the 28 rebuilt names are unique, but whether any
*other* asset family has a basename these patterns could claim. Checked over
the whole 9494-entry name table:

```
$ awk -F/ '{print $NF}' listing.txt | sort -u     | grep -E '^([0-9]+_wobj[0-9]+|[0-9]+_wboa|[0-9]+_kodai_break)\.png$'
```

returns exactly 14 names, and every one of them resolves to a single
`Game/world/gif/` path — no collisions, no near-misses. The
`mapchip_\d+_\d+_\d+\.png` / `worldchip_\d+_\d+_\d+\.png` patterns
likewise resolve to only `Game/field/mapchip/` and `Game/world/` respectively.
All 28 basenames are unique across the table, which is what makes basename
matching in the `getData` hook safe.

Device log lines to grep:

```
adb logcat -s ChronoDuo | grep -E 'worldchip rebuild|orig_art_cache: registered'
adb logcat | grep 'pixel-gfx: substituted worldchip_'
adb logcat | grep -E 'pixel-gfx: substituted [0-9]+_(wobj|wboa|kodai)'
```

## 9. Confidence

* **High (disassembly-proven):** the cg-slot table and its 128 sentinel, the
  `Chip_%04d.dat` layout, the 10–12/13/14/15 bit fields, the 256x256 page
  geometry, the `worldchip` filename argument order, the `worldchipScr3` and
  `worldChara` dead ends.
* **High (measured):** the `worldChara` negative — 0 of 63 sprites match at
  the 60.0 threshold against all 16194 components of all 708 shipped BMPs
  (section 6.1), reproducible with `rebuild_worldchara.py`.
* **High (proven + measured):** the 14-sheet rebuild — 0 invariant violations,
  11/3584 cells off, all of those colour-only.
* **High (byte-level):** Java == Python on all 14 chip sheets and all 14 gif
  sheets.
* **Medium:** the *cause* of those 11 cells (colour animation is the
  hypothesis; not chased into `Palette::ColAnim`).
