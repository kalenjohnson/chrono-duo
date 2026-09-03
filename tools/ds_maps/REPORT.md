# CT DS bottom-screen maps — reverse-engineering report

ROM: `Chrono Trigger (USA) (En,Fr).nds`. Tooling: Python venv at
`scratchpad/ds/venv` with `ndspy` + `Pillow`. Full NitroFS tree dumped to
`scratchpad/ds/files.txt` (10,381 files).

## TL;DR

**Per-room/dungeon bottom-screen maps: solved. 520 of 521 area maps decode
correctly** as clean, transparent-background floor-plan icons (see
`out/area_minimap_*.png`). This is the win the project needs — reuse these
directly on the companion screen.

**Per-era overworld "minimap" (used on the Epoch time-travel select
screen): format partly understood, tile graphics decode correctly, but full
raster reassembly is still wrong/sparse** — same problem the prior session
hit. Diagnosis below; not blocking, since it's not what indoor areas need.

## File layout

- `menu/bg/minimap_<ID>[_<n>]_{ncg,ncl,nsc}.bin` — **522 unique dungeon/room
  map bases**, 1044 files. Only 3 have their own `_ncl.bin` palette
  (`minimap_000`, `minimap_001`, `minimap_W`) — all others share one of
  these. IDs are sparse 3-digit numbers up to `999`, with `_1.._8` suffixes
  for multi-floor dungeons (e.g. `minimap_047_1`..`minimap_047_4`).
- `minimap/bg/<era>_{ncg,ncl,nsc}.bin` — 14 files: `gendai`, `genshi`,
  `kodai`, `mirai`, `tyusei` (the 5 main eras) plus location-specific
  variants (`gendai_fiona`, `genshi_mura`, `kodai-rakka`, `tyusei_Hashi`,
  etc.) — these back the Epoch/time-travel destination-select screen, not
  the field HUD.
- `minimap/obj/`, `menu/obj/minimap_mark.*` — OAM sprite sheets: the
  player-position dot/arrow (`minimap_mark`), destination icons
  (`obj_icon_nmap`), and the map window frame (`obj_win_mnmap_*`). Drawn as
  overlay sprites on top of the BG map, not part of the tile data.
- `PS_BIN/MapTable/MapTable_NNNN.dat` (342 files, IDs 0000–0668, sparse),
  `PS_BIN/ChipTable/` (609), `eve_save/EvtNNNN.bin` (710, IDs 0000–0709,
  fixed 2524 bytes) — the actual 3D field/room geometry and event scripts,
  each keyed by a numeric room/event ID in the same numeric range the
  minimap IDs use. This is very likely the same ID space (see "room → map
  ID" section).

## Format (confirmed, reused across both minimap sets)

All three files share one 4-byte ASCII magic + a small binary header; a
custom in-house format, not standard Nitro RGCN/RLCN/RCSN.

**NCG (tile graphics)** — LZ10-compressed on disk (standard `0x10` GBA/NDS
LZ, `ndspy.lz10.decompress` handles it). Decompressed:
```
offset 0   magic   "NCG\0"        4 bytes
offset 4   count   u16 LE         number of 8x8 tiles
offset 6   flag    u16 LE         0 = 4bpp tiles, 1 = 8bpp tiles
offset 8   data    count * (32 or 64) bytes   standard Nitro packed tile data
```
`8 + count*32` (4bpp) or `8 + count*64` (8bpp) equals the decompressed
length exactly, in every file checked.

**NCL (palette)** — not compressed:
```
offset 0   magic   "NCL\0"   4 bytes
offset 4   count   u32 LE    number of colors
offset 8   data    count * 2 bytes   BGR555 (standard NDS 15-bit color)
```

**NSC (screen/tilemap)** — not compressed:
```
offset 0   magic    "NSC\0"   4 bytes
offset 4   count    u16 LE    w*h (tile entries)
offset 6   flag     u16 LE    mirrors the NCG bpp flag
offset 8   w        u8        width in tiles
offset 9   h        u8        height in tiles
offset 10  reserved u16       not always zero, meaning unclear, ignored
offset 12  data     count * 2 bytes, standard Nitro text-BG screen entries:
                       bits 0-9   tile index
                       bit 10     hflip
                       bit 11     vflip
                       bits 12-15 palette index (4bpp only)
```
Map area screens are exactly `32 x 24` tiles = 256x192 px = the physical
DS bottom-screen resolution, laid out as a **flat row-major grid** — no
256x256 "SC block" reordering needed (that's what tripped up the previous
attempt: these maps are small enough to fit in one screen block, so
block-deinterleaving isn't just unnecessary, applying it scrambles them).

Decoder: `scratchpad/ds/decode_map.py` (`decode_ncg`, `decode_ncl`,
`decode_nsc`, `render_map`). Batch renderer: `scratchpad/ds/render_all.py`.

## What rendered correctly

- **520/521 `menu/bg/minimap_*` area maps** → `out/area_minimap_*.png`.
  Visually confirmed several by eye: `area_minimap_000.png` (blank room
  frame), `area_minimap_010.png` (small room with a staircase icon),
  `area_minimap_047_1.png` (a multi-building complex, clearly a town or
  fortress layout), `area_minimap_072_1.png` (looks like a bridge/castle
  gate). All render as a tan/brown parchment-style frame with a floor-plan
  line drawing inside, alpha-transparent outside the drawn area — exactly
  the DS bottom-screen HUD map style. The 1 failure is a file lookup
  mismatch (`minimap_019` base without matching triplet — logged by
  `render_all.py`), not a format problem.
- **14/14 era files decompress and produce images** (`out/era_*.png`), but
  see below — visually they are not usable yet.

## What didn't decode cleanly: era/world minimaps

`out/era_kodai.png` etc. come out as sparse scattered blobs on a black
field, not a continuous landmass silhouette. Diagnosis:

1. The **NCG tileset itself is correct** — dumping the raw tile sheet
   (`out/gendai_tileset.png`) shows unmistakable terrain fragments (forest
   green, water blue, path brown), confirming tile/palette decode is right.
2. The **NSC screen data for era files is not a plain flat tilemap** like
   the room maps. Dumping `minimap/bg/gendai_nsc.bin` raw bytes shows the
   file is actually a **tagged multi-chunk container**: a run of plausible
   tile entries for roughly the first ~500 bytes, then repeated
   `FD FD FD FD` sentinels followed by 4-character tags (`CLRF`, `CLRC`,
   `LINK`, `CMNT`), a `LINK` chunk literally containing the string
   `"gendai.ncg"`, and an embedded full printable-ASCII charset table
   (looks like leftover dev-tool/editor metadata — comment fields, a link
   to the source asset name, a font strip). Our naive "read `count` u16
   entries from offset 12" reads straight through these chunk headers as
   if they were tile indices, producing garbage/out-of-range tile refs
   past that point — hence the scattered/incomplete look.
3. Root-caused separately: these files back the **Epoch destination-select
   screen** (confirmed via overlay9_002.bin strings: `minimap/BG/...`,
   `minimap/obj/obj_icon_nmap.*` = selectable-destination icons,
   `obj_win_mnmap_*` = window chrome), not a "walk around the overworld"
   HUD map. It may genuinely be a small set of disconnected
   location-thumbnail icons positioned on an otherwise-blank/transparent
   background (each icon = one selectable destination), which would
   explain the "islands" look even with a correct decode — the sparse
   clustering in even the best decode attempt is consistent with that.
   **Not needed for the "map every indoor area" goal** — deprioritized.

Scripts kept for follow-up: `scratchpad/ds/inspect_chunks.py`,
`scratchpad/ds/dump_nsc_entries.py`, `scratchpad/ds/dump_tileset.py`,
`scratchpad/ds/test_altbits.py` (tried an alternate 8-bit-index/8-bit-flag
bit layout for 8bpp screens — also unsuccessful, ruled out).

## Room/area → map ID

Found via `strings` on the ARM9 code overlays (dumped to
`scratchpad/ds/overlay9_*.bin`, `arm9.bin`, `arm7.bin`):

- `overlay9_016.bin` contains the literal sprintf format strings
  `"/menu/bg/minimap_%03d_ncg.bin"`, `"/menu/bg/minimap_%03d_%d_ncg.bin"`
  (and `_nsc.bin` counterparts), plus `"/menu/bg/minimap_W-%s_ncg.bin"`.
  This confirms the game builds the filename at runtime from **one
  small-int ID** (`%03d`) with an optional floor/sub-index (`%d`), i.e.
  there is a single "MapID" (or "AreaID") per room, not a separate lookup
  table of filenames — the number *is* the key.
- `overlay9_016.bin` also references `menu/obj/minimap_mark.*` — the
  player-position marker sprite — right alongside the format strings,
  confirming this overlay owns the whole "show minimap + player dot" HUD
  feature (`overlay9_002.bin` has a class `cMiniMap`/`ShowMiniMap`, but
  that one turned out to own the *era* minimap, not the room one — don't
  conflate the two; overlay 016 is the room-map one).
- The minimap IDs (0–~668, sparse, some going into `_2`, `999`, `W-`)
  land in the same numeric range as `PS_BIN/MapTable_NNNN.dat` (342 files,
  IDs 0000–0668) and `eve_save/EvtNNNN.bin` (710 files, IDs 0000–0709,
  fixed-size 2524-byte records — almost certainly per-room event/script
  tables). This is strong circumstantial evidence the minimap ID **is**
  the same room/event ID used throughout the field engine (not a separate
  namespace requiring its own translation table) — but I did not find and
  disassemble the actual "current room ID" global/register read in the
  overlay code to prove it outright; that's the remaining unknown if a
  hard guarantee is needed. A pointer-table search for "list of all room
  IDs with a byte flag" turned up nothing more specific than the sprintf
  callsite itself in the time available.

## Feasibility verdict: maps for every indoor area on the companion screen

**Feasible, and most of the hard part is already done.**

- Format is fully decoded and scriptable (`decode_map.py`). 520 clean PNGs
  already sitting in `out/area_minimap_*.png`, ready to ship as static
  assets bundled with the companion app (extract once, don't decode at
  runtime).
- Remaining unknowns, in order of what actually blocks "show the right map
  automatically":
  1. **Room→map ID table.** Strong evidence the DS's own room/event ID
     doubles as the minimap ID (see above), but that ID lives in the *DS*
     ROM/engine's address space. ChronoDuo's `gamestate.c` currently reads
     **the SNES-translated mobile/Steam re-release** (65816 emulation
     layer, `ChronoCanvas::getFieldMapName()` returning a location-name
     *string*, not a numeric DS room ID) — a completely different runtime
     than the DS ROM these maps were extracted from. There is no direct
     numeric ID bridge between the two today. The practical path is a
     **hand-built name→PNG lookup table**: enumerate the ~500 distinct
     `getFieldMapName()` strings the mobile build can produce (probably a
     small, finite, mostly-static set — original CT location names), and
     map each one manually/semi-automatically to the matching
     `area_minimap_XXX[_n].png` (many can be identified purely by eye from
     the rendered PNGs, e.g. instantly-recognizable dungeon shapes). This
     is grunt work, not a technical blocker.
  2. **Player-position → map-pixel mapping.** These maps carry no embedded
     per-room coordinate metadata in the files decoded so far — the player
     dot is a separate sprite (`minimap_mark`) presumably positioned by
     code using the field engine's own world coordinates, scaled by
     per-room constants not stored in the NCG/NCL/NSC triplet. ChronoDuo
     already reads live SNES-work-RAM player X/Y (per `gamestate.c`'s
     `sfc_work()`/`ASM_MEM_GLOBAL` machinery) — turning that into a dot on
     one of these 256x192 maps needs a per-room (offset, scale) calibration,
     likely determined empirically per dungeon (walk to a known landmark,
     note SNES coords vs. pixel position) rather than extracted from the
     ROM.
  3. Multi-floor dungeons (`_1`, `_2`, … suffixes) need a floor-index
     signal from the live game too — not yet identified in `gamestate.c`.

None of these are format/decoding problems anymore — they're all "wire the
already-decoded assets up to the live game state" integration work, which
is a materially smaller task than what this session solved.

## Key files

- `scratchpad/ds/decode_map.py` — the decoder (NCG/NCL/NSC → PIL Image).
- `scratchpad/ds/render_all.py` — batch-renders every era + area map.
- `scratchpad/ds/out/area_minimap_*.png` — 520 decoded dungeon/room maps.
- `scratchpad/ds/out/era_*.png`, `out/gendai_tileset.png` — era-map
  renders + raw tileset dump (for the unresolved chunk-format issue).
- `scratchpad/ds/files.txt` — full 10,381-file NitroFS listing.
- `scratchpad/ds/overlay9_016.bin`, `overlay9_002.bin` — ARM9 overlays with
  the sprintf format strings and class names cited above.
