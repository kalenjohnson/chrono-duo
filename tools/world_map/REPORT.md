# Chrono Trigger (Android port) overworld map format — REPORT

Goal: render the full overworld of every era offline from the game's own asset
files. Everything below is read out of the ARM64 disassembly of `libchrono.so`
(addresses are `.so` virtual addresses, symbols are the shipped C++ symbols) and
then checked against the rendered output. This revision replaces an earlier pass
that had guessed the index mapping; the guesses were wrong in three places
(chip-page selection, world->file mapping, and cell-0 handling), all corrected
here.

Tool: `render_world.py` in this directory.

```
python3 render_world.py <dir-with-extracted-assets> <out-dir> [--libchrono <path-to-libchrono.so>]
```

Outputs `world_0.png` .. `world_7.png`, **2x scale, 3072x2048 px** each
(96x64 metatiles, native metatile 16x16 px = 1536x1024 at 1x; the shipped
`worldchip` sheets are already 2x, so 2x output needs no resampling).
`--libchrono` is only needed for world 7 (see below).

---

## 1. The load path

`WorldMap::LoadMap(int worldId)` @ **0x606a30** is the whole story. In order:

| step | call | meaning |
|---|---|---|
| 0x606a8c | `WorldMapInfo::WorldMapInfo(worldId)` | fetch the 0x30-byte descriptor for this world |
| 0x606a94-0x606aec | `world::ChipData::Load(info[k], slot)` for `k` at +0x00..+0x0C, slots 0..6 | 7 CG (4bpp tile graphics) banks |
| 0x606b00 | `world::Palette::Load(info+0x14)` | `plt_bin/plt<n>.bin`, BGR555 |
| 0x606b14 | `world::Palette::ColAnim(worldId)` | palette colour-cycling setup |
| 0x606b28 | `world::ChipTable::Load(info+0x20)` | `Chip/Chip_%04d.dat` |
| 0x606b34-0x606bbc | two 32x32 loops of `ChipTable::setChip_8_8(chipData, page, x, y, word, false)`, `page` = 0 then 1 | renders both chip pages into 256x256 8-bit images |
| 0x606bcc | `world::MapData::Load(info+0x22)` | `Map/Map_%04d.dat` -> `WorldMap+0x237e0` |
| 0x606bd4 | `if (worldId == 7)` ... | **overwrite** both map layers from a baked table (see section 5) |
| 0x606d3c | `world::MapData::LoadTexture(info+0x20, info+0x14)` | the pre-rendered `worldchip` PNGs |
| 0x606d4c | `world::MapData::Expansion(chipTable, worldId)` | build the drawable textures (section 3) |
| 0x606d60 | `memcpy(AsmBuffer+0x22000, mapData, 0x3000)` | map into the emulated SNES RAM the ported 65816 code reads |

Note the port is a *transliteration* of the original 65816 code (`WorldImpl`
is full of `Asm::_ld16`, `Asm::_st16`, `Asm::addressToBank_ptr` ...), with the
graphics side reimplemented in cocos2d. That is why the data files are
essentially raw SNES ROM blocks.

## 2. `Map/Map_%04d.dat` — 12,288 bytes = two 96x64 u8 layers

Proven, not inferred, by `world::MapData::GetMapData(int layer,int x,int y)`
@ **0x6066e0**:

```
addr = this + layer*0x1800 + y*0x60 + x
```

and identically by `WorldMap::GetMapData` @ **0x609b18** (same arithmetic, plus
the `WorldMap+0x237e0` base). `world::MapData::Expansion` @ 0x605d94 loops
`y < 0x40`, `x < 0x60`. So:

```
0x0000 .. 0x17FF   layer 0   96 cols x 64 rows, u8, row-major
0x1800 .. 0x2FFF   layer 1   96 cols x 64 rows, u8, row-major
```

**Index 0 means "no metatile"**, not "metatile 0": `Expansion` does
`cbz w0, next` at **0x605e70** (layer 0) and `cbz w1, next` at **0x605f0c**
(layer 1) and leaves those pixels at palette index 0. The earlier pass pasted
cell 0 unconditionally, which is where its spurious grass came from.

For every world except world 5, layer 1 is the dense base terrain (0 zero
bytes) and layer 0 is the sparse decoration layer. World 5's layers are
swapped — see section 4.

## 3. `Chip/Chip_%04d.dat` and the `worldchip` sheets — the two chip *pages*

This was the main error in the previous pass: `worldchip_<a>_<b>_0.png` and
`..._1.png` are **not** two animation frames. They are two independent chip
pages, one per map layer.

`world::ChipTable::Load(int)` @ **0x604510** reads the file as a stream of
`u16`s and, for each of 16x16 = 256 metatiles, reads 4 `u16` and widens them to
`u32` at `this + row*0x100 + col*8` (top pair) and `this + 0x80 + row*0x100 +
col*8` (bottom pair) — i.e. a **32x32 grid of 8x8 tile refs**, 1024 words =
0x1000 bytes per page. It does this twice: page 0 at `this+0x0000`, page 1 at
`this+0x1000`. 2 pages x 256 metatiles x 4 u16 = **4096 bytes = exactly the
file size.** It also derives a per-metatile priority/flip byte at `this+0x2000`
from bits 13/12/11/10 of the four words (0x6045ec-0x604618).

`WorldMap::LoadMap` then expands each page with `setChip_8_8(..., page, ...)`
into a 256x256 8-bit image: page 0 at `ChipTable+0x2200`, page 1 at
`ChipTable+0x12200` (both 0x10000 bytes).

`world::MapData::Expansion` @ **0x605d94** is the renderer:

- allocates two `0x180000`-byte buffers = **1536 x 1024** 8-bit pixels each
  (96*16 x 64*16), stride `0x600`;
- layer 0 -> buffer A using page 0 (`ChipTable+0x2200`, 0x605e80);
  layer 1 -> buffer B using page 1 (`ChipTable+0x12200`, 0x605f28);
- the cell address is `((b<<8) | (b<<4)) & 0xF0F0`, i.e.
  **`row = b >> 4, col = b & 0x0F`** in a 16x16 grid of 16x16-px cells;
- it blits 16 rows of 16 bytes per metatile (0x605e94-0x605eac);
- then slices each 1536x1024 buffer into 3x2 = 6 textures of 512x512, uploaded
  as RGBA with the palette **index in the red channel** (`Color4B(idx,0,0,0)`
  at 0x60607c) — the palette and colour animation are applied in the shader.

`world::MapData::LoadTexture(int chip,int plt)` @ **0x606288** formats
`Game/world/worldchip_%d_%d_%d.png` (string at 0x36cd15) twice, with the third
argument **0** then **1**, storing to `this+0x3000` and `this+0x3008`. So:

```
worldchip_<chipTableId>_<paletteId>_<page>.png
   512x512, 16x16 cells of 32x32 px  ==  2x of the 256x256 chip page
   page 0 <-> map layer 0     page 1 <-> map layer 1
```

`chipTableId` is `WorldMapInfo+0x20` (the `Chip_%04d` index, 0..5) and
`paletteId` is `WorldMapInfo+0x14` (the `plt%d.bin` index, 4..10). That is why
the shipped filenames are `0_4, 0_5, 1_6, 2_7, 3_8, 4_9, 5_10` — chip 0 is
shared by two worlds with different palettes.

**Where the sea comes from.** There is no separate ocean background. The ocean
is ordinary layer-1 metatiles on chip page 1 — index 205 is open water in the
1000 AD sheet, 2593 of 6144 cells. Rendering layer 1 against page **0**
(towns/castles/mountains) instead of page **1** (terrain/coast/water) is
precisely why the previous render had no sea. `gif/<era>_wboa.*` is **not**
needed for any world: every world's base layer is fully painted (0 zero bytes)
except world 7's, whose empty area is genuinely off-map.

## 4. Compositing order (and the world-5 special case)

`Expansion` builds two texture *sets* per world:

- **set 0** = buffer A (layer 0) alone;
- **set 1** = buffer B (layer 1), with buffer A composited on top where A's
  pixel is non-zero — `dst = (a != 0) ? a : b` at **0x606060-0x606068**.

The bake into set 1 is gated at **0x605f80** by `cmp w21, #5` where `w21` is
the `worldId` passed from `LoadMap`: **for world 5 the overlay is not baked
in.** That is exactly right, because world 5 (Zeal, the sky) is the one world
whose layers are swapped — its layer 0 is the dense cloud sea and layer 1 the
sparse floating islands.

That set 0 is drawn *behind* set 1 is the one link in this chain I did not read
out of the binary (`WorldMap::Init2` @ 0x6073c8 creates two `cocos2d::Node`
containers at 0x607424/0x60742c, one per set, each holding six 512x512
`RenderTexture`s; I did not trace their z-order). It is a no-op for every world
but 5, because those worlds' set 1 is fully opaque. For world 5 the evidence is
the output itself: with this order the floating islands sit on the cloud sea,
which is right; with the reverse order the clouds cover everything.

So the single rule that reproduces both cases:

```
paint layer 0 with page 0          (background; hidden for normal worlds)
paint layer 1 with page 1          (base terrain)
if worldId != 5: paint layer 0 with page 0 again   (decoration on top)
```

Within a drawn metatile, pixels whose palette index is 0 are transparent, so
cells are composited per pixel, not copied wholesale. (Skipping this makes
every town/castle metatile show a magenta/void halo where the sheet is
transparent.)

## 5. World -> asset mapping (read out of the binary, not guessed)

`WorldMapInfo::WorldMapInfo(int)` @ **0x60ae18** and
`WorldMapInfo::loadWorldMapInfo(int)` @ **0x60aef0** both do
`row = G_WORLDMAPINFO + worldId * 0x30` and copy 24 `u16`. The global is a
`GLOB_DAT` relocation, resolvable statically:

```
0xbc70d8  R_AARCH64_GLOB_DAT  WorldMapInfo::G_WORLDMAPINFO    -> 0xbe2508
0xbc70e0  R_AARCH64_GLOB_DAT  WorldMapInfo::WORLDMAPINFO_MAX  -> 0xbe2688  (= 8)
```

0xbe2688 - 0xbe2508 = 0x180 = 8 x 0x30, and `WORLDMAPINFO_MAX` reads 8. Fields
`+0x14` (palette), `+0x20` (chip), `+0x22` (map) are the three consumed by
`LoadMap`; fields at +0x2A and +0x2C are 0..7 identity, confirming the ctor
argument is the world id.

| world | chip (`Chip_%04d`) | plt (`plt%d.bin`) | map file | `worldchip` pair | `Id_%04d` (+0x24, inferred) | what it is |
|---|---|---|---|---|---|---|
| 0 | 0 | 4 | `Map_0000.dat` | `0_4_{0,1}` | 0 | 1000 AD, Present |
| 1 | 0 | 5 | `Map_0001.dat` | `0_5_{0,1}` | 1 | 600 AD, Middle Ages |
| 2 | 2 | 7 | `Map_0003.dat` | `2_7_{0,1}` | 2 | 2300 AD, ruined Future |
| 3 | 3 | 8 | `Map_0004.dat` | `3_8_{0,1}` | 3 | 65,000,000 BC, Prehistory |
| 4 | 4 | 9 | `Map_0005.dat` | `4_9_{0,1}` | 4 | 12,000 BC, Dark Ages (ice, Zeal intact) |
| 5 | 5 | 10 | `Map_0007.dat` | `5_10_{0,1}` | 6 | 12,000 BC, Kingdom of Zeal (sky) |
| 6 | 4 | 9 | `Map_0006.dat` | `4_9_{0,1}` | 5 | 12,000 BC, Dark Ages after Zeal falls |
| 7 | 1 | 6 | `Map_0002.dat`* | `1_6_{0,1}` | 2 | advanced/green future world (cutscene) |

\* **world 7's map file is overridden.** At **0x606bd4** `LoadMap` compares its
argument with 7 and, if equal, rewrites both layers (0x1800 bytes each, into
`WorldMap+0x237e0` and `+0x24fe0`) from a static table at **0x3b1ff4**,
0xC000 bytes = 12,288 `u32`. The NEON loop uses `tbl` with the index vector
`{0,4,8,...,60}` at 0x375a30 (take byte 0 of each `u32`) and then
`add v,v,-1`. So `map[i] = (u32table[i] & 0xFF) - 1`. The result differs from
`Map_0002.dat` in 939 bytes, i.e. it is a patched variant of it.
`render_world.py --libchrono` reproduces this; without the flag it falls back
to `Map_0002.dat` and says so.

In plain text, for the three files the previous pass could not place:
**`Map_0005.dat` is world 4**, **`Map_0006.dat` is world 6**, and
**`Map_0007.dat` is world 5** — the three 12,000 BC states (Dark Ages with Zeal
aloft, Dark Ages after Zeal falls, and the sky kingdom itself).

Independent corroboration of the table read: the 7 distinct `(chip, plt)`
pairs in the table are *exactly* the 7 shipped `worldchip` pairs, with `(4,9)`
used twice, and the map column uses all 8 `Map_%04d.dat` files exactly once.

The `Id` column is the one **inferred** entry: `LoadMap` never reads +0x24, and
`WorldImpl::IdDataLoad(int,int)` @ 0x6109c4 is called only from
`WorldImpl::ID_ExpToRam` @ 0x60e740 (tail call at 0x60e898) with an index that
comes out of the emulated SNES RAM, not out of `WorldMapInfo`. +0x24 is listed
because its 7-distinct-values-with-`2`-twice shape matches the 7 shipped
`Id_%04d.dat` files, which is suggestive and nothing more.

### Era -> world id

`WorldImpl::InitWorldMap` @ **0x60d9e8** is the only caller of
`WorldMap::LoadMap` (0x60db90). At 0x60db70-0x60db88 it reads a `u16` from
emulated SNES RAM `$2E100` (the current location index) and computes

```
if (loc < 0x1FB) worldId = loc - 0x1F0;   else keep previous
```

So the world id is `location - 0x1F0` — the original ROM's overworld location
range 0x1F0..0x1F7 in order. There is no separate 5-value "era" table in this
path: the game tracks the location index, and the 0..4 era enum used elsewhere
in the port is a label, not the selector. Worlds 4/5/6 are all 12,000 BC states
and worlds 0 and 7 share the 1000 AD landmass, so a 5-era enum cannot address
these 8 worlds one-to-one.

The era names in the table above come from the rendered art plus the structural
constraints (worlds 0/1 share chip table 0 and differ only in palette; 4 and 6
share chip 4 / palette 9 and differ only in map; 5 is the layer-swapped one;
2 and 7 share `Id_0002`). Worlds 0-6 are unambiguous on sight. World 7 is the
one I would not bet the house on — see section 7.

## 6. Party position -> pixels

`WorldImpl::GetPartyCharPos()` @ **0x60d81c** loads the `u8` at `$2E102` (X),
applies `Asm::_asl16b` three times (`<< 3`) and stores the 16-bit result to
`$2E283`; same for the `u8` at `$2E103` (Y) -> `$2E285`. So the stored party
coordinates are **8x8-tile units**:

```
1x render (1536x1024):  px = X * 8,   py = Y * 8      X in 0..191, Y in 0..127
2x render (3072x2048):  px = X * 16,  py = Y * 16
metatile cell:          col = X / 2,  row = Y / 2
```

That is consistent with the u8 range (a metatile-unit coordinate would only
reach 96/64 and waste most of the byte).

## 7. Verification

### Numeric, era 0 vs `Game/common/wb_mini.png`

Ground truth: `wb_mini.png` region `x 16..111, y 48..175` (96x128, stored
half-width), stretched 2x horizontally to 192x128, then reduced to the 96x64
metatile grid by 2x2 majority. Sea = the modal colour `(24,41,65)`; a second
water colour `(16,65,106)` is the mini-map's shallow/coastal ring.

Render mask: taken from the *indices*, not from pixel colour, so the metric is
independent of palette. Sea = layer 1 index == the modal base index (205,
2593 cells).

To make this a metric on the *render* and not merely on the map bytes, the mask
was cross-checked against `world_0.png` itself: each 32x32 output cell was
compared byte-for-byte with cell (row 12, col 13) of `worldchip_0_4_1.png`
(= index 205). The render-derived mask is **identical** to the index-derived
one (2593 cells, exact match), so the IoU below also covers cell addressing,
row/col order, cell size and non-interference from the overlay pass.

| offset (dy,dx) | IoU |
|---|---|
| (-1,-1) | 0.7784 |
| (-1, 0) | 0.8061 |
| (-1,+1) | 0.7875 |
| ( 0,-1) | 0.8079 |
| **( 0, 0)** | **0.8710** |
| ( 0,+1) | 0.8084 |
| (+1,-1) | 0.7893 |
| (+1, 0) | 0.8043 |
| (+1,+1) | 0.7807 |

A clean single peak at zero offset — no row-origin or crop misalignment, which
also confirms the 96x64 grid and the row-major reading.

The 0.871 residual is almost entirely the coastal ring, which the mini-map
paints as water but the base layer encodes as distinct shallow-water metatiles.
Widening both masks accordingly — ground truth = either water colour; render =
every base index that lies >90% inside the ground-truth water mask and occurs
>=10 times (`{1, 6, 7, 16, 23, 39, 55, 187, 188, 189, 190, 203, 204, 205, 206,
218, 219, 220, 221, 222}`, 3895 of 6144 cells) — gives **IoU 0.9335**
(3895 vs 4160 cells). Both numbers are reported because the first is the
assumption-free one.

### Eyeball, per world

Full-resolution outputs are `world_<n>.png`; the notes below were made from
downscaled previews of them.

- **world_0 — 1000 AD.** Textbook Chrono Trigger present-day map: dark blue
  ocean, Guardia continent top-left with Truce/Guardia Castle/Zenan Bridge,
  Medina island, the southern Porre landmass with the Sunken Desert, the
  eastern island. Matches `wb_mini` cell for cell.
- **world_1 — 600 AD.** Same continental skeleton, greener/darker palette
  (chip 0, plt 5), fewer roads and bridges, and two solid near-black round
  regions — one at cols 39-45, one at cols 59-62, both rows 21-26, both built
  from base index 169 with 185 as their border, and index 169 occurs in no
  other world. These are genuine solid-dark metatiles in the sheet, not a
  rendering artefact or an unapplied colour animation: cell (row 10, col 9) is
  a flat near-black 16x16 block in `worldchip_0_5_1.png` **and** in
  `worldchip_0_4_1.png` (RGB 10,15,8 and 18,30,16), i.e. the same chip-0 tile
  refs render near-black under both palettes. They read as the Fiendlord-era
  dark forest.
- **world_2 — 2300 AD.** Brown ruined wasteland, shrunken and fragmented
  landmass, dome structures and the highway lattice. Correct. Note this
  contradicts a premise in the task brief: 1000 AD and 600 AD do share a
  terrain layout (same chip table 0, only the palette differs), but **2300 AD
  does not** — it has its own `Chip_0002`, its own palette and its own map
  file, and its coastline is visibly a different, broken-up world. Going with
  the data.
- **world_3 — 65,000,000 BC.** Single Pangaea-like continent, jungle and
  limestone ridges, a bright orange lava field with volcanoes near the middle
  (Tyrano Lair / Mt. Woe region). Correct.
- **world_4 — 12,000 BC (Zeal intact).** Ice age: white snowfields, drifting
  icebergs across the frozen sea, and a small dark-red structure floating in
  the middle of the map. Correct.
- **world_5 — Kingdom of Zeal (sky).** A tiled cloud sea filling the lower
  five-sixths of the canvas, a flat sky band across the top, and the floating
  islands with the Zeal mountain and waterfalls in the middle. This is the
  world whose layers are swapped and whose overlay is not baked — it renders
  correctly only with the `worldId != 5` rule from section 4. Very strong
  confirmation that the disassembly reading is right.
- **world_6 — 12,000 BC after Zeal falls.** The same ice palette as world 4 but
  a drastically reduced map: most of the land gone, a handful of islands and
  an ice-choked sea in the north-east. Correct.
- **world_7 — advanced green world (cutscene).** Occupies only cols 6..58,
  rows 1..37 of the grid, the rest genuinely empty (this is the only world with
  transparent regions in the output). Terrain silhouette is the *1000 AD*
  landmass, but rendered with chip table 1 / palette 6: green continents,
  paved multi-lane highways, silver dome/city structures. It reuses `Id_0002`
  and its map is baked into the binary rather than
  shipped as an asset, which points at a cutscene-only map rather than a
  playable one — most plausibly the pre-Lavos advanced world / restored-future
  flyover. Geometry and rendering are certainly right; the *name* is the one
  item in this report I would still call a reading of the art.

### Confidence

- **High (disassembly-proven):** map layer geometry and stride, index-0
  skipping, chip-page/layer pairing, `worldchip` filename argument order,
  the whole `G_WORLDMAPINFO` mapping table, the world-5 and world-7 special
  cases, the party-position shift.
- **High (proven + validated numerically):** world 0 rendering (IoU 0.871 /
  0.9335 at zero offset).
- **High (proven mapping + unambiguous art):** worlds 1-6 era identities.
- **Medium:** the *label* on world 7 only.

## 8. Files not needed for this render

- `map_bin/cg*.bin` (4bpp tile graphics) and `plt_bin/plt*.bin` (BGR555) — the
  raw inputs the port uses to build the chip pages at runtime. The shipped
  `worldchip_*.png` sheets are the pre-baked, 2x, palette-applied result, so
  neither is needed offline.
- `colanim_bin/<n>_colanim.bin` — drives `world::Palette::ColAnim`; the shipped
  sheets are frame 0, which is what this tool renders.
- `worldchipScr3_<a>_{2,3}.png` — the in-game minimap chip set
  (`WorldMap::enterMiniMap`/`markMiniMap`), a different asset family.
- `Id/Id_%04d.dat` (512 B) — walkability/trigger data, read by
  `WorldImpl::getIdData(int,int)` @ 0x60bb24 through the emulated 65816 path;
  not terrain.
- `SeId/SeId_%04d.dat`, `gif/<n>_wobj*.png` — ambient SFX regions and overworld
  object sprites (party marker, vehicles).
- `gif/<n>_wboa.*` — an overworld background image; **not** the ocean, and not
  required by any world (see section 3).
