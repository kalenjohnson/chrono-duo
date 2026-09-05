# ChronoDuo — design notes and research record

*(2026-09-03)*

## Why the official Android APK is the base

- The Steam/mobile Chrono Trigger (2018 Steam = 2011 mobile port lineage, both
  currently v2.1.x) is **cocos2d-x 3.14.1**, not a custom engine. Confirmed by
  the [ct_nx](https://github.com/NaGaa95/ct_nx) Switch port, which loads the
  Android build's `libchrono.so` unmodified.
- It's the **only version of CT with real widescreen**: the engine re-renders the
  original tilemaps at the device viewport, instead of emulating a fixed 256px
  PPU framebuffer. (SNES: bsnes-hd widescreen breaks sprites/HUD, no CT ROM hack
  exists. DS: pure 2D game, DS widescreen hacks only re-project 3D — CT is not
  on the TWiLight Menu++ widescreen list.)
- No zelda3-style C reimplementation of CT exists (checked snesrev + community).
- ct_nx verified: **no DRM/licensing/integrity checks** in the native lib; v2.1.5
  ships all assets in-APK (`assets/resources.bin`, `001.dat`–`008.dat`, `007-en.dat`,
  `Shaders/`); arm64-v8a; links a separate `libc++_shared.so`.
- The `.so` appears to keep its **symbol table** — ct_nx resolves mangled C++
  symbols like `_ZN7cocos2d8Director11getInstanceEv` and even a game variable
  `_ZN10DeviceInfo16mCurrentLanguageE`. Huge for phase 3: engine/game state may
  be reachable via `dlsym` on known cocos2d-x APIs, not blind offsets.

## Boot sequence (from ct_nx `main.c`, mirrored in our AppActivity)

1. `System.load(libc++_shared.so)`, `System.load(libchrono.so)` → `JNI_OnLoad`
2. `Cocos2dxHelper.nativeSetApkPath(<game apk path>)` (via `sAssetsPathOverride`)
3. `Cocos2dxHelper.nativeSetContext(ctx, <game AssetManager>)` (via our
   `getAssets()` override — `createPackageContext(CHRONO_PACKAGE, 0).getAssets()`)
4. `AppActivity.setAssetManager(ctx, assets)` — **SE custom native**
5. `AppActivity.setExternalStorageInfo(path, path, "com.square_enix.android_googleplay.chrono")`
   — SE custom native, `(String, String, String)`; ct_nx passes writable dir twice
6. GL surface created → `Cocos2dxRenderer.nativeInit(w, h)` on GL thread

The full JNI up-call surface the game uses (~35–40 methods, all standard
cocos2d-x `Cocos2dxHelper`/`Bitmap`/`VideoHelper`/`EditBoxHelper` glue) is
catalogued in ct_nx `source/jni_fake.c` — our vendored real classes cover it.

## What's vendored / modified

`app/src/main/java/org/cocos2dx/lib/` = cocos2d-x **3.14.1**
(`reference/cocos2d-x`, sparse clone of tag `cocos2d-x-3.14.1`,
`cocos/platform/android/java`). Local modifications:

- `Cocos2dxHelper`: stripped OBB (`ZipResourceFile`/`APKExpansionSupport`) and
  Samsung "Enhance" game-tuning service deps; added `sAssetsPathOverride`;
  `getObbAssetFileDescriptor` stubbed (v2.1.5 has no OBB — but note the engine
  still *calls* it, per ct_nx)
- `Cocos2dxActivity`: `getGLContextAttrs()` native wrapped with a fallback
  (`{8,8,8,8,24,8}`) in case libchrono.so doesn't export it
- `Cocos2dxMusic`/`Cocos2dxSound`: OBB branches removed (fall through to
  `mContext.getAssets()`, which our activity overrides → game assets)
- `Cocos2dxDownloader.java` deleted (needed `com.loopj` http lib; CT is offline)

## First-boot findings (Ayn Thor Lite, 2026-09-03) — IT WORKS

- Game boots, renders the intro at full 1920×1080 widescreen, plays audio.
- Play ships the game as **split APKs**: libs in `split_config.arm64_v8a.apk`
  (`extractNativeLibs=false`), assets in `split_assetPack.apk` (install-time
  asset pack; merged into the package AssetManager, so `createPackageContext`
  assets work unchanged). `ChronoRuntime` scans base + all splits for the libs.
- The engine `FindClass`es `Cocos2dxDownloader` during `nativeInit` — the class
  must exist even if unused, so it's vendored with its `com.loopj` dependency.
- `nativeSetAudioDeviceInfo` **is** exported by libchrono.so (crashed before the
  lib was loaded due to a bootstrap-failure path bug; fixed with
  `Cocos2dxActivity.sSkipEngineInit`).
- Thor Lite displays: top = display 0, 1920×1080 landscape (120Hz-capable);
  bottom = display 4, `DISPLAY_CATEGORY_PRESENTATION`, 1240×1080 logical.
  `screencap -d <physicalDisplayId>` (e.g. 4630946482288158082) captures it.
- Not yet verified: touch input in-game, physical controller, FMV playback,
  menus/battles, saves, onPause/onResume cycling.

## Device/testing gotchas (learned the hard way)

- After `adb shell am start`, the game window can come up with
  `mCurrentFocus=null`; Cocos2dxActivity gates `mGLSurfaceView.onResume()` on
  `onWindowFocusChanged(true)`, so the game sits alive-but-black until a TAP
  (`input tap 960 540`) focuses it — injected key events don't.
- KEYCODE_BACK at the title = the game's quit path (clean exit, no crash log).
- The intro/attract FMV fails in our host ("Can't play this video" —
  Cocos2dxVideoHelper/VideoView vs the game's .dat assets; on the fix list).
  It's dismissible; the game continues.
- A backgrounded lime3DS emulator can hold its own Presentation on display 4
  and fight ours for the panel.

## Known risks / first-run watch list (logcat)

- `UnsatisfiedLinkError` on a `nativeX` the .so doesn't export, or
  `NoSuchMethodError`/JNI lookup failure for an SE-added Java method our stock
  3.14.1 classes lack (e.g. `getVersion`, `getDeviceMaxAudioInstances` appear in
  ct_nx's fake surface; ct_nx hardcodes `getVersion` → `"2.1.5"`). Fix: add the
  method to the vendored class.
- The real app's Java layer may do setup we haven't replicated (e.g. an
  `Application` subclass). If boot dies early, decompile the *manifest + smali
  class list* of the user's own APK (jadx) to compare — inspection only, we
  redistribute nothing.
- `getAssets()` override returns the game's AssetManager for the whole activity;
  our own UI must never rely on activity assets (it doesn't — views are built
  programmatically).
- FMV playback goes through `Cocos2dxVideoHelper`/`VideoView` reading
  `001.dat`-style assets from the game AssetManager — verify when reached.
- Saves land in **our** app's dirs (`getFilesDir`/`getExternalFilesDir`), not the
  official app's — existing saves won't carry over (official app's data is
  private). Cloud save in-game may help if it works.
- Second screen: Presentation must stay `FLAG_NOT_FOCUSABLE`; discover displays
  by capability each time; recover from system dismiss (see zelda3-android,
  tmc-android, balatro-dualscreen, BanjoRecomp — all use this exact pattern).

## Phase 3 memory layout — RECOVERED (2026-09-03, v2.1.5 arm64)

Verified live on device (values matched Crono/Marle/Lucca/… canonical stats):

- `ChronoCanvas::getInstance()` (exported) → root singleton; **cSfcWork embedded
  at canvas+0x40** (from `ChronoCanvas::setupSfcWork` disasm).
- **Character records: cSfcWork+0x10, stride 0x120, 7 entries** (from
  `cSfcWork::GetEquipParam` disasm + live calibration). u32 LE fields:
  +0x00 char id, +0x04/+0x08 ? (xp-ish), +0x10 maxHP, +0x14 curHP, +0x18 maxMP,
  +0x1c curMP, +0x20 baseMaxHP, +0x24 Pow, +0x28 Sta, +0x2c Spd, +0x30 Mag,
  +0x34 Hit, +0x38 Evd, +0x3c MDef, +0x40 level.
  CORRECTED (2026-09-03): live testing after a lost fight showed the panel
  reading "70/1" (70 = Crono's max HP), proving HP order is max-then-cur, not
  cur-then-max as originally logged here. MP order (+0x18 max, +0x1c cur) is
  inferred to mirror HP's and is unverified.
  NOTE: all 7 records hold default join stats even before recruitment — need the
  party list to filter (open question below).
- **Names: two libc++ std::string tables** (SSO, stride 0x18, 8 entries:
  chars + Epoch): defaults at cSfcWork+0x18e8, active names at +0x19a8.
- `cSfcWork::GetCharaData(i)` = this+0x6924+i*0x154 — a *different* staging
  array, observed all-zero during field play. Not the live store.
- **Translated-65816 layer**: class `Asm` (`_ld8/_ld16/_adc8/...`), virtual
  SNES memory pointer in an unnamed static at **libchrono base + 0xbeeba8**
  (recovered from `Asm::GetAddrY8` disasm; `dladdr` for the base). Mapping:
  bank $7E → +0x20000, bank $7F → +0x10000 (`GetWorkBank7E`, `GetAddrY8`).
  Observed NEARLY EMPTY during field play (only script-var regions ~0x12000,
  0x2e100–0x2fe00 live) — the C++ layer owns most state; SNES RAM-map addresses
  do NOT hold field-mode party data. `WorldImpl::PartyMember()` does read
  0x22980 ($7E2980) via `Asm::_ld16`, so some contexts (world map?) sync it.
- **Party composition.** Character record field **+0x11c (i32) is a JOIN
  COUNTER, not a slot**: Crono=1, Marle=2, Lucca=3, Frog=4 ... and -1 when
  not (or no longer) in the party — a live dump after Frog joined (Marle
  gone) read `1, -1, 3, -1, 4, -1, -1`, so the old `1..3` filter silently
  dropped Frog (fixed 2026-09-05). The **active party list is in Asm memory
  at 0x20980: 3 PC-id bytes in party order, 0x80 = empty** (SNES CT's
  $7E2980 convention; Crono+Lucca dumps read `00 02 80`, Crono+Lucca+Frog
  `00 02 04`) -- **but that copy is only re-synced on overworld entry**: after
  Frog left in Guardia Castle his portrait stayed until the party walked out
  onto the world map. The **live list is the C++ layer's own: three i32 slots
  at ChronoCanvas+0x124e8/ec/f0 (cSfcWork-relative 0x124a8), value =
  `cSfcWork::GetCharaData` index × 2 (the engine does `asr #1`), 0x80 =
  empty**. That index is NOT the PC id: live Crono+Marle read `4, 6` (indices
  2, 3), so the id is read back from the GetCharaData record (cSfcWork+0x6924
  + i*0x154) at +0x44, which `atel_partyM` compares to the script's id byte.
  Verified live: records 2/3 read +0x44 = 0/1 (Crono/Marle) and +0x40 = 0/1
  (party position; `atel_partyM` writes 3 there on removal). So the earlier
  "GetCharaData observed all-zero during field play" note was wrong for the
  PC entries -- they are live. Portraits now update mid-cutscene. `FieldImpl::atel_partyM` (script party-remove) reads/shifts those
  slots directly, as do `atel_partyMM` and `atel_split`. `PartySnapshot.read()`
  uses that list first, then the Asm copy, then `+0x11c >= 1` ordering; a
  change-only `party slots: xxxxxxxx ...` logcat line shows the raw words.
  The reserve (SNES $7E2983+) reads as zeros in Asm memory, so it is not used.
- Differential-dump workflow (the way to find any remaining field):
  `GameState.dumpToFiles()` writes sfcwork.bin (64KB) + asmmem.bin (192KB) to
  the app's external files dir every 8s (dev only); adb pull before/after a
  known in-game change and diff with python.
- **Gold = cSfcWork+0x1a04 (u32), play time seconds = +0x1a10 (u32)** — found
  by differential dumps (Bronze Blade purchase / idle ticking). Both live.
- **SHELVED — hiding the on-screen MENU/Map buttons:** scene-graph walker works
  (safe reads via process_vm_readv after a SIGSEGV lesson; find_running_scene
  scans Director members for RTTI "*Scene"). Field screen button = `FieldMenu`
  node, world map = `WorldMenu` (3 MenuItemSprites). BUT the periodic
  `nativeSetVisibleByPattern("WorldMenu", false)` doesn't stick — dumps still
  show WorldMenu vis=1. Unclear whether the tick isn't reaching the GL thread,
  the game re-shows the node, or find_running_scene picks a stale Scene at
  hide-time. Debug later (log the per-tick hit count first). Dev trigger
  exists: `adb shell am broadcast -a com.kalenjohnson.chronoduo.SCENE_DUMP`.
- **resources.bin: SOLVED.** "ARC1" archive, no real key: XOR stream seeded by
  region start offset (tmp=0x19000000+off; tmp=tmp*0x41c64e6d+0x3039; ^=tmp>>24)
  + gzip; table gzip'd at header_offset (BE length prefixes). Offline tool:
  scratchpad/ctres.py + resources_listing.txt (9,494 entries). Runtime:
  ChronoResources.java extracts via the game AssetManager, cached by game
  versionCode. Key assets: Extension/face.png (4×2 grid of 96×88 portraits,
  char-id order + Epoch), Game/common/wb_mini.png (mini world map on a
  256×256 sheet; content measured at x 16–111, y 48–175 = 96×128, cropped
  exactly in AppActivity.cropWorldMap — stored half-width, drawn stretched 2x
  horizontally by PartyPanelView for the game's landscape ~1.5:1 map aspect),
  Game/common/minimap_mark.png (3×16×16: 0 empty, 1 green/blue =
  position, 2 yellow = POI), Extension/menu_win.png (window panel at
  198,134–500,304, 9-sliceable, inset 16).
- **Sleep/wake black screen: SOLVED.** Root cause: after wake the display
  reports ready ~1ms before its compositor surface is live; a Presentation
  created then claims isShowing() but is permanently black (invalidate can't
  fix a dead surface). Fix: forceUpdate() (dismiss+recreate) once ~700ms after
  resume, gated by an ACTION_SCREEN_OFF receiver registered for the manager's
  lifetime (registering in onResume misses the broadcast — it fires pre-resume).
  Ungated recreates caused visible panel flashes on every ordinary resume.
- **World position: SOLVED.** Overworld tile X/Y = u8 at Asm mem 0x2E102/0x2E103
  (from WorldImpl::GetPartyCharPos disasm + calibration walk); world = 256×256
  tiles, linear map onto the drawn map rect. Pixel-scale mirrors at ~0x2E00A.
- **Battle: SOLVED (the five-fight saga).** CT battles are field-layer — no
  battle Scene is pushed. The battle node's RTTI class is plain `Battle`
  (mangled `6Battle`), a DIRECT child of the root cocos Scene; its +0x320 is
  the `SceneBattle` engine object (update/isActive/setField all delegate).
  SceneBattle+0x8 = the Asm memory base (getwork8/16 read it); **+0x68 = the
  battle ACTOR ARRAY**: 0x80-byte actors, ≥10 slots; u8 +0x00 = monster/char
  id, u16 +0x03 curHP, +0x05 maxHP; party slots 0-2, enemies 3+ (maxHP>0 =
  present). Values live per-hit. Character records in cSfcWork FREEZE during
  battle (sync at end). Dead heuristics for the record: scene-type detection,
  cSfcWork+0x7651 byte (a first-fight coincidence), GetSendBtlDataa (a table).
- **Enemy names**: `Localize/en/msg/monster.txt` in resources.bin — CRLF
  lines, 0-based line index == monster id (146 = "Gato"). Loaded at runtime
  via ChronoResources; other locales under Localize/<lang>/.
- **Still open:** ATB gauge field (candidates +0x18/+0x2d u8, inconclusive —
  needs fast-sampled single-battle capture), status-effect flags, enemy HP
  for the panel when the game hides bars (we show them anyway — by design).
  For gold: diff before/after buying something. For play time: two dumps with
  everything else idle. Battle info: `SfcBattleWork` / `SceneBattle::getwork8`
  (reads a buffer pointer at SceneBattle+0x8) is the entry point.
  **SOLVED:** hiding the top-screen battle UI + panel-owned touch-forwarded
  commands — see "Battle UI hiding, battle MP, list submenus — RE record (2026-09-04)".

## Phase 3 leads (game state → second screen)

- `dlsym` cocos2d-x engine symbols (Director / Scene graph / UserDefault) — the
  binary seems unstripped; a tiny NDK hook lib loaded after libchrono can walk
  engine state on the GL thread.
- PC (Steam) build Cheat Engine tables (fearlessrevolution.com t=6134) document
  the game-state *structure*; same data layout likely on ARM, different addresses.
- ChronoMod (github.com/jimzrt/ChronoMod) documents `resources.bin` weak
  encryption + archive format → map/UI asset extraction for drawing the
  companion screen.
- DS version precedent for what the second screen should show: map + party
  status + items; battle menu moves to bottom screen during combat.

## Reference repos (gitignored, in `reference/`)

- `ct_nx` — Switch port of this exact binary; loader blueprint + JNI catalogue
- `cocos2d-x` — engine source at the game's exact version (sparse checkout)

## Battle UI hiding, battle MP, list submenus — RE record (2026-09-04)

**Battle node direct children (8 total).** Scene dump (tag at Node+0x1a0):
- Index 0: `N7cocos2d4MenuE` (Menu, 30 children) — command buttons (Attack/Tech/Item toggles).
- Index 1: `N7cocos2d13RenderTextureE` (1 child) — status window cell layer (frame, portraits, HP/MP bars, ATB).
- Index 2: `nsBattleListMenu14BattleTechMenuE` (1 child) — tech submenu, not hidden.
- Index 3: `nsBattleListMenu14BattleItemMenuE` (1 child) — item submenu, not hidden.
- Index 4: `N7cocos2d4NodeE` (7 children) — labels (HP/MP values, Attack/Tech/Item text).
- Indices 5–7: additional nodes (draw node, render textures).
**Hide set:** indices 0, 1, 4–7; indices 2, 3 stay visible (lists not yet mirrored to panel).
**Verified:** damage numbers and target cursor render on top screen; panel shows clean battle state.

**Status window architecture.** `BattleMenu` (the 2D UI host node) has no separate RTTI class — status info draws via internal methods `drawStatusWindow`, `chrTab`, `drawActiveBar` into the RenderTexture cell layer (index 1). Offsets into `SceneBattle::update` reveal it's a single composite render step, not a scene node, so reading/hiding requires hitting the RenderTexture child, not a separate node class.

**Battle actor MP offsets.** SceneBattle+0x68 = actor array (0x80-byte stride). Field order (all u8 unless noted):
- +0x07: **curMP** (live per tech cast; Crono=0, Marle=7 in Gato fight, frozen across snapshots).
- +0x09: **maxMP** (Crono=14, Marle=18; Gato fight confirmed via 8 sequential snapshots; byte +0x08 always 0x00, not shifted field).
**Confidence: high** — exact dual-actor match, all 8 seqs; corroborated by frozen sfcwork.bin ground truth.

**BattleListMenuBase member offsets** (`BattleTechMenu`/`BattleItemMenu` subclasses). Derived from disasm + live calls:

| Offset | Size | Field | Role |
|---|---|---|---|
| 0x320 | 8 | `_rootNode` | cocos2d::Node* (scene graph anchor). |
| 0x330 | 8 | `_scrollView` | cocos2d::ui::ScrollView* (holds row buttons). |
| 0x340 | 8 | `_inputManager` | nsMenu::nsInput::Manager* (state & current index). |
| 0x370 | 8 | `_eventCallback` | `std::function<void(int,EventType)>` (selection callback). |
| 0x380 | 1 | `_isOpen` | bool (set in `open()`, cleared in `close()`). |
| 0x388–0x390 | 8 ea | data vector | begin/end pointers; 12-byte row elements. |
| 0x398 | 8 | data capacity | vector cap. |
| 0x3a0–0x3ec | 80 | cursor cache | int32[20], per-category cursor, init'd -1 in `listupTechs`. |
| 0x3c8 | 4 | category idx | actor/category arg from `open(actor, category)`. |

Row element struct (12 bytes, **layout differs per subclass**):
- **TechRow:** id (4B) + param (4B) + usable (1B u8) + pad (3B).
- **ItemRow:** id (4B) + usable (1B u8) + pad (3B) + count (4B).
Usable byte read by `canSelect(i)` matches storage offset exactly.
**Confirmation:** `listupTechs`/`listupItems` store writes + `canSelect` reads, high confidence.

Entry point: `onButtonPressed(int rowIdx)` (BattleTechMenu 0x6e44b4, 520B). Reads current index from input manager, validates against 20-entry cursor cache, calls `vtable[0x658](newIndex)` to commit, fires callback if set.

**Dev hooks.** Broadcast intents:
- `SCENE_DUMP`: prints node geometry (pos, size, anchor, tag, child count, world-center per Node+0x1a0 tag offset). Node cap raised to 2000.
- `BATTLE_HIDE_MASK --ei mask N`: blanks Battle's direct children by bitmask (bit i hides child i). Live what-draws-what debug.

**Command selection model (shipped).** Panel owns d-pad left/right + A while command menu is open (`_isOpen` byte check at this+0x380). Left/right update panel index (no game cursor sync); A injects touch on highlighted command + swallows key-up so game sees no keypress. Outside menu, all controller input passes through. On-panel direct tap updates index and fires selection callback.

## DS area maps and the room marker (2026-09-04)

**DS ROM files and format.** 522 unique dungeon/room maps stored as triplets
`menu/bg/minimap_<ID>[_<n>]_{ncg,ncl,nsc}.bin` in the DS ROM. NCG (LZ10-compressed
tile graphics): magic "NCG\0", count u16, 4bpp/8bpp flag, then tile data (32 or 64 bytes
per 8×8 tile). NCL (palette, uncompressed): magic "NCL\0", count u32, then BGR555 colors
(2 bytes each). NSC (screen/tilemap, uncompressed): magic "NSC\0", count u16, width u8,
height u8 (always 32×24 = 256×192 px), then 2-byte entries (tile index bits 0-9, hflip
bit 10, vflip bit 11, palette index bits 12-15 for 4bpp). 520 of 521 decode correctly
as clean tan/brown floor-plan icons with transparent background. See `tools/ds_maps/REPORT.md`
for format details and `tools/ds_maps/decode_map.py` for the Python reference decoder.

**Field map ID match.** Mobile port's `ChronoCanvas::getFieldMapName()` reads an int32 from
**ChronoCanvas + 0x12300** (field map location id). This id matches the DS minimap file id
exactly: Leene Square = 5 (verified live). The id also doubles as the room/event ID throughout
the DS ROM (MapTable/EvtNNNN structures), reducing to a hand-built name→PNG lookup table once
the ~500 canonical CT location names are enumerated.

**Leader position in field maps.** CHARACTER_DATa records at `cSfcWork + 0x6924 + i*0x154`
(i = 0 for party leader). Within each record: X tile = int32 @ +0x80, X×256 (sub-tile) @ +0x84;
Y tile @ +0x8c, Y×256 @ +0x90. Y grows downward. Verified by differential dumps while walking.
Multi-floor dungeons need a separate floor index (not yet located in gamestate.c).

**Marker transform: model v4.** Per-room rect from ARM9 0x02059e04 (20 B/room) defines a tile
bounding box [X0,Y0,X1,Y1]. Scale ladder {4,2,1} per box class (both fit tx×sy ≤ box dims),
global 8/7 vertical stretch (sy = sx * 8/7), rect centred in a fixed pixel-space centre
(small box: (127.5, 98.2), large box: (126.0, 88.14...)). Formula: `px = ox + sx*tileX`,
`py = oy + sy*tileY` where `ox = centre_x - (X0+X1+1)/2*sx`, `oy = centre_y - (Y0+Y1+1)/2*sy`.
Calibration: Leene Square (room 5, stairs (24.5,23.0)→(128,40), fountain (24.2,34.9)→(128,97),
south exit (24.5,46.6)→(123,150), west wall x=2.49 → px 39); room 434 (walls x=2.5/13.5 →
px 103/152, counter/exit y=4.5/11.6 → px 83/120, sx=4, sy≈4.571). See `tools/ds_maps/gen_calib.py`
module docstring (lines 10-99) for the full v3→v4 derivation and confidence caveats.

**On-device importer.** Settings gear icon → SAF picker → .nds or .zip (NitroFS entry streamed
to temp cache). Validation: Nitro logo CRC, game codes {YQUE, YQUP, YQUJ}, ARM9 table bounds.
Imports to `<filesDir>/ds_maps/` with area_calib.json (per-room rects from the ARM9 table).
Maps and calib reload on completion. Desktop verification: `tools/ds_maps/JavaImportCheck.java`
matches the Python decoder pixel-for-pixel on all 520 maps; used for testing the
`app/src/main/java/com/kalenjohnson/chronoduo/dsimport/` Java port.

**Tech and item names.** `Localize/en/msg/tech.txt` (117 CRLF lines) and `Localize/en/msg/item.txt`
(347 lines), 0-based line index == id, extracted from resources.bin. Tech descriptions at
`tec_mes.txt` (same indexing). Battle item ids encode category<<14 | index; consumables are
category 1 (USEITEM_nnn). See `tech_item_names_report.md` for sample indices and file locations.

**MP formula.** TechnicMpTable.dat (header u32 count, then count single-byte values) holds solo
tech costs indexed by tech id. TechnicBaseDataTable.dat (header u32 count, then count 15-byte
records) has bytes 11-13 listing up to 3 component tech ids (0xFF = unused). **MP cost = sum of
TechnicMpTable.mp[c] for each non-0xFF component id c** — uniform for solo/dual/triple techs.
Verified: Cyclone 2, Aura 1, Aura Whirl (1+2) = 3, Luminaire 20, Delta Force (triple 8+8+8) = 24.
See `tech_mp_report.md` for the full calibration table and Java snippet.

## Graphics filter and original sprites (2026-09-04)

**No in-game option; GL filtering forced to NEAREST — and nothing in the field path was ever LINEAR.** The GOT-patched `Texture2D::setAntiAliasTexParameters`→`setAliasTexParameters` redirect (mechanism 1) and the `glTexParameteri` rewrite (mechanism 2) changed nothing on-screen because they were **no-ops on this path**, not because they missed it. Proven statically: `Texture2D::initWithMipmaps` @ 0x936b8c *always* sets MIN/MAG explicitly (so no texture ever falls back to the GLES defaults MIN `NEAREST_MIPMAP_LINEAR` / MAG `LINEAR`), cocos2d-x is statically linked into `libchrono.so` with `glTexParameteri` a real `JUMP_SLOT` import (so the GOT patch did see every call), and every texture in the field chain is already NEAREST — see below. Only `FontAtlas` calls `setAntiAliasTexParameters` at all.

**Field render path — ~~the ground is already original 1x art~~ RETRACTED, see "RESOLVED" below.** *(The paragraph that follows is kept for the filtering evidence, which stands. Its conclusion about the ground does not: `MapTable::Expansion` @ 0x568af0 is never called, and the ground is blitted from the mapchip sheets by `MapTable::drawFrontChip` @ 0x56bdac.)* `MapTable::Expansion` @ 0x568af0 / `ExpansionExt` @ 0x569c28 CPU-composite the background into a power-of-two-padded **1x** RGBA8888 buffer whose RED channel is an 8-bit palette index (`MapTable::writeChip` @ 0x56b5f0 copies one source byte per destination texel from the 1x expanded ChipTable page at `chipTable + 0x3200 + page*0x10000`; 16 px per metatile via `lsl w9, w11, #4` @ 0x568bf0 proves the 1x), hand it to `Texture2D::initWithData(..., PixelFormat=2, w, h, size)` @ 0x568e78, and draw it through `Shaders/ShaderDrawPalettedTexture.fsh` — one index tap plus one palette tap. **Immediately after that `initWithData`, 0x568e84-0x568f00 loads the 16-byte constant at 0x374f60 — `{0x2600, 0x2600, 0x812F, 0x812F}` = `{GL_NEAREST, GL_NEAREST, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE}` — into `Texture2D::setTexParameters`** (the nearby conditional `mov w8,#0x2901` = `GL_REPEAT` writes only the two WRAP fields). The palette texture (`ctr::ResourceManager::createPaletteTexture` @ 0x5be520) ends with `setAliasTexParameters` @ 0x5be7e8, and every `RenderTexture` (`initWithWidthAndHeight` @ 0x895f38 — 19 created by `FieldMap::makeField` @ 0x57520c, mostly 432×224 points) does the same. All NEAREST. No MSAA anywhere in the binary.

~~So the smoothed 512×512 `mapchip` sheets never touch the walkable ground:~~ **WRONG — they feed the ground too** (`drawFrontChip` with `b = false` draws every non-priority metatile from them; see "RESOLVED" below). They also feed `MapTable::drawFrontChip` @ 0x56bdac / `drawFrontChipExt` @ 0x56c88c → `drawChip` @ 0x56cf68 → `Sprite::createWithTexture`, i.e. the **animated / front / priority chips** and treasure chests. Tree roots, chests **and the ground** are all blurry, and it is all one cause: the sheets.

**The residual ground softness is GEOMETRIC, not filtering.** `AppDelegate::applicationDidFinishLaunching` @ 0x6417f0 picks a **568×320-point** design resolution (the `Size` constant written by the static ctor at 0x641c34) with `Director::setContentScaleFactor(2.0f)` @ 0x641afc and `NO_BORDER`. On a 1920×1080 panel one source texel therefore covers `1920/568 = 3.380` screen pixels; NEAREST at a non-integer 3.38× yields blocks alternating between 3 and 4 px wide, which reads as softness with no filter involved. Confirmed on a device screenshot: horizontal run lengths over the ground, the front chips **and** the character sprite share the same 3/4-px distribution (~0.9 of adjacent pixels identical) — a LINEAR-sampled region would show almost no equal neighbours. A real fix means changing the design resolution so the scale is an integer (e.g. 480×270 on 16:9 → exactly 2.0), which shrinks or grows the visible play area; that is a gameplay-visible trade and is deliberately **not** made.

**Mechanism 6: probe (never write) the filter state of generated textures.** `hooked_glTexImage2D`, for level-0 `GL_RGBA` uploads with **no asset path in flight** (`g_pending_tex_path` empty — the field/world index textures, palette LUTs, RenderTexture allocations), reads the live MIN/MAG back with `glGetTexParameteriv` after forwarding and logs, once per distinct texture shape, what it found. Deliberately **diagnostic only**: the static evidence says these are all already NEAREST, so writing the filter would be inert where it matters while still landing on RenderTextures nothing here has reasoned about (`FieldMap::makeField` / `RewriteBg` / `Scroll` / `drawGate`) — forcing NEAREST on a buffer sampled *below* 1:1 would trade correct minification for shimmer. If `nearest_was_linear` ever comes back non-zero, that names the size and is the moment to add a targeted write. Gated on the Pixel graphics pref. Grep `pixel-gfx: generated` (per-size lines, `<-- NOT NEAREST` if anything ever is) and `nearest_probed=` / `nearest_was_linear=` in the stats line.

**Decimation experiment kept behind `nativeSetPixelDecimate` (off).** Halving RGBA uploads on the fly would undo one layer of upscaling, but the field sampler runs in texel space — texel lookups break when texture dimensions change, tiles misalign and UV wraps fail.

**Original 1x pixel art ships with the game.** `Game/chara/bmp/*.bmp` (629 sheets, 4bpp indexed BITMAPV4, 1x original art, differently packed) alongside `Game/chara/png` (2x atlases). Battle/world art at `Game/battle/oef` and `Game/world/gif` bmps. **Field tiles have a 1x source too** — the earlier note here that they do not was wrong; see “Field chip sheets” below.

**Rebuild pipeline.** `tools/orig_art/rebuild_sheet.py` / `SheetRebuilder`: 4-connected segmentation of both sides, masked SAD match (RGB, flips considered), pixel-doubled paste of matched 1x frames into 2x rects, fallback to original. Desktop check (`JavaRebuildCheck.java`): 100% on Crono. Batch over 629 sheets: 97% matched (610 frames), 0 failures.

**Runtime swap mechanism.** Replacement sheets registered with FNV-1a fingerprint of ORIGINAL upload's alpha (64×64 sample grid, red premultiplied as tiebreaker). The glTexImage2D hook fingerprints uploads of registered size and substitutes the replacement's premultiplied RGBA bytes — sheets bypass `TextureCache::addImage` / `ResourceManager::createTexture` so path-matching alone is insufficient (mechanism 5b: fingerprint match as fallback). The lookup tries the **path first** (which is all the field chip sheets need — see below) and the fingerprint second. Disk cache `<filesDir>/orig_art_cache/`: one `.rgbz` per sheet (`"RGBZ"` + u32 w/h/rawLen, then a zlib stream of row-major premultiplied RGBA8888) + `index.txt` (name, w, h, alphaFp/redFp hex16, pngMtime; native freads and inflates one sheet at a time on match, nothing held resident). Character sheets only — the field chip sheets are no longer in this cache at all (see "Field chip sheets", below).

**Field chip sheets (`mapchip`) — rebuilt from 1x, swapped BY FILE.** `Game/field/map_bin/cg*.bin` are untouched 1x 4bpp SNES tile banks (linear, not planar; 0x1000 B = 128×64 px = 128 tiles), `ChipTable_%04d.dat` is 2 pages × 256 metatiles × 4 × (u16 tileref + u8 prio), `plt%d.bin` BGR555, `bgsettable_%d.dat` 8 bytes naming the cg banks per ChipData slot. The shipped 512×512 `mapchip_<chipTable>_<palette>_<page>.png` sheets are a **derived** 2x, smoothed, 256-colour-quantised bake of exactly that — they are why front/animated chips (tree roots, chests) look blurry. `tools/field_art/rebuild_mapchip.py` (desktop) and `origart/MapchipCore` + `MapchipRebuilder` (device) reconstruct any page from the 1x sources and pixel-double it; `tools/field_art/JavaMapchipCheck.java` verifies the Java core **pixel-identical to the Python on all 504 rebuildable pages**. Sheets are enumerated from the 669 `Mapinfo` entries keyed `(mapinfo[2], mapinfo[4])`, never by globbing (leftover `mapchip_23_33_ev*` / `_test` authoring assets can never be named by `LoadTexture`'s `%d_%d_%d`). Only **pages 0 and 1** are rebuildable — a ChipTable is exactly 6144 B = 2 pages, so the 14 shipped page-2/3 slots keep the smoothed art. `(62,21)` is the only pair whose maps disagree on the bgset id (14 vs 70, differing in slot 5); both tools take the lowest deterministically. Full format evidence in `tools/field_art/REPORT.md`.

**CORRECTION (2026-09-04): the chip-sheet GL swap was INERT, not wrong — and the cause was an ABI bug in our own hook.** All 504 rebuilt pages were registered as *path-keyed* `.rgbz` entries, matched inside `hooked_glTexImage2D` against `g_pending_tex_path`. On device, with every page registered, **no `replaced mapchip_` line ever appeared** and the diagnostic alarm fired instead (`512x512 upload with no asset path in flight while path-keyed replacement mapchip_0_117_0.png is registered`). The reason is not exotic: `cocos2d::TextureCache::addImage(const std::string&)` @ 0x93e518 is an ordinary member (`x0` = `this`, `x1` = the string), but **`ctr::ResourceManager::createTexture(const std::string&)` @ 0x5be3bc is STATIC** — `x0` *is* the string (0x5be3d4 `mov x19, x0`; 0x5be3ec `mov x0, x19` straight into the `(string, Image*)` overload @ 0x5be450). gamestate.c declared both with the member signature, so for `createTexture` it read the path out of `x1` — junk — and `g_pending_tex_path` stayed **empty for every `createTexture` load**. Forwarding still worked by accident (`x0`/`x1` pass through untouched), which is why nothing crashed and the miss was silent. `MapTable::LoadTexture` @ 0x56b874 *was* reaching the hooked call the whole time (PLT branches at 56b8c0/56b8f4/56b96c). The signature is fixed here.

**Why the chip sheets moved to a file-level swap anyway.** Not because the GL path can't work — with the ABI fixed it very likely would, since `drawFrontChip` @ 0x56bdac samples the uploaded `Texture2D` directly. It is because the file layer is strictly more general and cannot silently miss: it substitutes the PNG *before decode*, so every consumer of the decoded image sees it (GL upload, sprite draw, any CPU blit), it needs no fingerprint, no `.rgbz` and no size agreement, and the same hook is available for `.dat`/`.bin` assets later. Given one mechanism that had already failed invisibly once, the layer with the fewest ways to be inert is the right one.

**RESOLVED, and NOTES/REPORT §8a above were WRONG: the walkable ground IS the smoothed 2x mapchip art.** The earlier claim — ground CPU-composited at 1x from the cg banks into a paletted index texture, sheets feeding only the animated/front chips — rested on `MapTable::Expansion` @ 0x568af0 and `MapTable::writeChip` @ 0x56b5f0, and **`MapTable::Expansion` is never called**: no PLT stub, no `JUMP_SLOT` relocation, zero direct `bl`s, while its live sibling `ExpansionExt` @ 0x569c28 has a stub at 0xb3f860 taking 4 calls. (Careful: in this binary *every* intra-library call goes through a PLT stub, so `grep "bl 0x<body>"` returning 0 proves nothing on its own — it is 0 for `ExpansionExt` and `drawFrontChip` too. The missing stub *and* missing relocation is what settles it.) `writeChip` has no stub either and is reachable only from `draw`/`drawExt`/`drawZero`, which only `Expansion`/`ExpansionExt` reach.

What actually paints the field is **`MapTable::drawFrontChip` @ 0x56bdac**, verified instruction by instruction: `srcX = (chip & 0x0F)*16` (`ubfiz w22,w6,#4,#4` @ 0x56bddc), `srcY = chip & 0xF0` (@ 0x56bdec), priority mask `chipTable[0x3000 + page*0x100 + chip]` (@ 0x56be04), **`if (!b) mask ^= 0xF`** (`eor`/`tst w2,#1`/`csel` @ 0x56be3c-0x56be44), and when `mask == 0xF` (@ 0x56be48) it builds `Rect(srcX, srcY, 16.0f, 16.0f)` (`fmov s2/s3,#16.0` @ 0x56be5c/0x56be60) over the texture at **`this + 0x98 + page*8`** (`ldr x21,[x8,#0x98]` @ 0x56be68) — i.e. exactly the `Texture2D*` array `MapTable::LoadTexture` @ 0x56b874 fills with the mapchip sheets — and `Sprite::createWithTexture` @ 0x56be7c + `setPosition` + `visit`. Otherwise it does four 8x8 quadrant draws from the same texture. The `b` flag inverts the mask, so **`b = false` draws every non-priority metatile (the ground) and `b = true` the front chips**. `MapTable::CreateSprites` @ 0x56bbdc drives it over the whole visible window with **no filter at all** (loop 0x56bd2c-0x56bda4: one unconditional `bl drawFrontChip@plt` per cell), and `FieldMap::makeField` @ 0x57520c calls `CreateSprites`/`CreateSpritesExt` eight times (0x5754ec … 0x575708). The baked smoothing survives at 1:1: with contentScale 2.0, `Sprite::setTextureCoords` turns the 16x16-**point** `Rect` into **32x32 pixels** of the 512x512 sheet — one cell of its 16x16 grid — onto a footprint that is 32x32 device pixels. Nothing resamples, nothing recovers the 1x. That, not geometry, is why the 2026-09-04 screenshot shows smoothed ground; the earlier run-length reading was measuring the sheet's own 2x cells.

**The NPOT uploads were RenderTextures, not composited buffers.** `RenderTexture::initWithWidthAndHeight` scales w/h by `CC_CONTENT_SCALE_FACTOR()` (2.0), skips `ccNextPOT` under `supportsNPOT()`, then `malloc`s and `memset(0)`s before `initWithData` (`cocos/2d/CCRenderTexture.cpp:205-231`) — so **every RenderTexture uploads non-NULL data, at NPOT size, with no path in flight**. `FieldMap::makeField` matches the log exactly: `RenderTexture::create(640,256)` @ 0x57543c/0x575440 → **1280x512**; `create(432,224)` @ 0x57571c/0x575720 → **864x448**; `create([FieldMap+0x330],[FieldMap+0x334])` (map size in points) @ 0x5716a8 / 0x575b04 → 896x768 → **1792x1536** and 768x768 → **1536x1536**. `ExpansionExt`'s own ×16 geometry (`lsl w10,w11,#4` @ 0x569d50, `lsl w8,w8,#4` @ 0x56a0d4, POT round-up 0x56a0e8-0x56a114 — no `lsl #5` anywhere) was read correctly and is POT/1x, so it cannot be any of those; the 512x512 no-path upload is the likely candidate for it. The error was never the arithmetic, it was assuming that texture is what reaches the screen. Still unread line by line: `draw` @ 0x569008 / `drawExt` @ 0x56a8dc / `drawZero` @ 0x56b09c, and what (beyond `~MapTable` @ 0x56bb4c) consumes the index texture — moot, since the sprite blit above is positive evidence on its own.

**Mechanism 7: file-level asset substitution at `ctr::ResourceManager::getData`.** Every asset read in this game — archive entry *and* filesystem fallback — funnels through one static function, `ctr::ResourceManager::getData(const std::string& path, int* outLen)` @ **0x5be02c** (PLT stub **0xb42b80**, GOT `R_AARCH64_JUMP_SLOT` at **0xbcd038**; `x0` = `std::string*`, `x1` = `int*`, buffer returned in `x0` — no `this`, no sret, no `cocos2d::Data`). It calls `DetchmanResource::LoadFileEntry` @ 0x55e810 first (the ARC1 archive in `resources.bin`: XOR-descramble with `seed = (base + fileOffset) * 0x41c64e6d + 0x3039` taking `seed >> 24` per byte, a 4-byte **big-endian** raw size, then `ZipUtils::inflateMemory`) and on a miss falls back to `DeviceInfo::getMainBundlePath() + path` → `ResourceManager::readFile` @ 0x5be9ec → `FileUtils::getDataFromFile`. `ResourceData::ResourceData(const std::string&)` @ 0x5bdfc4 (149 call sites — every `.dat`/`.bin` read, `ChipData::Load` and `ChipTable::Load` included) is a thin shim over it, and `createTexture(path)` @ 0x5be3bc reaches it via `createTexture(path, Image*)` @ 0x5be450 → `getData` → `Image::initWithImageData(bytes, len)` → `free(bytes)`. There is **no direct-BL bypass** (`grep -cE "bl[[:space:]]+0x5be02c\b" full.asm` = 0) and exactly one JUMP_SLOT for the symbol, which is also exported (`T` at 0x5be02c) so `dlsym` finds the original — i.e. `pixel_find_jump_slot` patches it exactly like every other mechanism here. **Ownership is `malloc`/`free`**: the buffer comes from `ZipUtils::inflateMemory` (`malloc`/`realloc`) and callers free it plainly — `ResourceData::~ResourceData` @ 0x5be118 is `ldr x0,[x0]; b free@plt`, `createTexture` does `bl initWithImageData; mov x0,x20; bl free@plt` at 0x5be494 — so a substitute buffer must be a fresh `malloc()`, never `new[]` and never a shared scratch buffer.

On a **basename** match the hook returns the rebuilt PNG's own bytes from `<filesDir>/orig_art/mapchip_<a>_<b>_<page>.png`, so the game decodes *our* sheet and **every** consumer sees it — GL upload, sprite draw, CPU blit alike. Bytes are served **verbatim**: this is upstream of cocos2d-x's premultiply, unlike the deliberately premultiplied `.rgbz` payloads mechanism 5 uploads. Registration is `GameState.nativeRegisterFileSubstitutions(String[] names, String[] paths)` — two parallel arrays so replacements can live in either scanned `orig_art` directory — called by `OrigArtCache.refresh` **before** its PNG-decoding pass, since registration is free and a field load that beat it would show the shipped sheets. The substituted names are the anchored patterns in `OrigArtCache.FILE_SUBSTITUTED` (`mapchip_\d+_\d+_\d+\.png`, `worldchip_\d+_\d+_\d+\.png`, `\d+_wobj\d+\.png`, `\d+_wboa\.png`, `\d+_kodai_break\.png`) — anchored rather than prefix-matched so `worldchip_` can never catch `worldchipScr3_*`, which has no 1x source. The chip sheets therefore need no `.rgbz`, no fingerprint and no cache entry at all; the old path-keyed `alphaFp == redFp == 0` sentinel, `buildPathKeyedEntry` and the one-shot alarm are gone. Gated on the Pixel graphics pref (`g_file_subst_enabled`, set by `nativeSetPixelGraphics`); the GOT patch itself is installed unconditionally, so toggling is just a flag. Grep on device: `pixel-gfx: file-substitution hook installed`, `pixel-gfx: registered <n> file substitution(s)`, one `pixel-gfx: substituted <name>` per sheet (`mapchip_<a>_<b>_<p>.png`, `worldchip_<a>_<b>_<p>.png`, `<n>_wobj0.png` …), and `file_subst_registered=` / `file_subst_hits=` / `file_subst_misses=` in the periodic `pixel-gfx: stats` line — **`file_subst_hits=0` with a non-zero registered count is the signal that the hook point is wrong**, and is the one thing that distinguishes "installed" from "engaged".

**Overworld chip sheets (`worldchip`) + `Game/world/gif` — rebuilt from 1x, same file-level swap.** The overworld runs its own parallel implementation of the field's chip pipeline, and **its tile-ref bit layout is different**: `world::ChipTable::setChip_8_8` @ 0x604830 reads **bits 0-9 tile, 10-12 palette (only 3 bits), 13 priority, 14 hflip, 15 vflip** — where the field has 10 hflip / 11 vflip / 12-15 palette. `Chip/Chip_%04d.dat` is 4096 B = 2 pages × 256 metatiles × 4 **u16** (no per-tile priority byte), `world::ChipData::Load` @ 0x606d9c sizes a bank from the cg header's *second* u16 alone and treats **128, and only 128, as "no bank"** (cg0 is a live bank, so the field's 0/0xFF rule must not be reused). There is **no `bgsettable` for the world**: the 7 cg bank ids per world live in `WorldMapInfo::G_WORLDMAPINFO` @ 0xbe2508 (8 × 0x30, u16 0..6 = slots 0..6, u16 10 = plt, u16 16 = chip), hardcoded as `MapchipCore.WORLDMAPINFO` and re-checked against the ELF by `rebuild_worldchip.py --libchrono`. Its 8 worlds fold to exactly the 7 shipped `(chip, plt)` pairs = 14 sheets, with worlds 4/6 sharing both the pair *and* the banks (no ambiguity). `tools/world_art/rebuild_worldchip.py` + `origart/MapchipCore`'s `world*` methods + `WorldchipRebuilder` rebuild all 14; `tools/world_art/JavaWorldchipCheck.java` verifies Java **pixel-identical to Python on 14/14**. Against the shipped sheets: 0 invariant violations and **11 of 3584 metatile cells off (0.31%), all colour-only** (probable `Palette::ColAnim` phase). The `Game/world/gif/<n>_wobj*.png` / `0_wboa.png` / `4_kodai_break.png` (14 sheets) are re-packed 2x bakes of their 1x `.bmp` siblings, exactly like the chara sheets, and go through the existing `SheetRebuilder` unchanged (507/563 frames matched; `7_wobj0` is a plain pixel-double and rebuilds at score 0.0 on all 161 frames). **Not rebuildable, documented:** `worldchipScr3_<n>_{2,3}.png` (10 authored weather textures — `WorldMap::initWeatherMap` @ 0x60802c hands them straight to `createTexture`, no chip table or cg bank in the path, and no world Bg3 chip file ships) and `Game/common/worldChara.png` + `Game/common/silbird.png` (the overworld party sheet and the Epoch sprite — no `.bmp` sibling, and for `worldChara` the "they're just the field frames" hypothesis is now disproved by measurement: its 63 sprites (7×8 + 7, segmented at `alpha >= 128` — `alpha > 0` gives 2914 components on that sheet's halo) match **0/63** at the 60.0 threshold against all 16194 components of every one of the 708 BMPs in the archive, best whole-corpus SAD 126–229 and best IoU ~0.85 always on the wrong character; the sheet is a 2x upscale of art that ships nowhere, with 31681 distinct opaque colours from a lossy pipeline. `tools/world_art/rebuild_worldchara.py` reproduces the negative and exits 1). Full evidence in `tools/world_art/REPORT.md` (§6.1).

**Settings.** "Original art" row with a single Build action covering all three passes (sprites, then field chips, then overworld — the phase label distinguishes the three done/total counters). Refresh (~6 s on-device over 629 sheets) rebuilds only changed/missing `.rgbz` files (mtime-keyed cache reuse).

**Battle results window.** Keyed on `SceneBattle+0x22f4` step counter: step 0 = waiting for death animations, step 1 = first results window. Work struct at `*(SceneBattle+0x60)`: EXP @ +0x1640, gold @ +0x1694, TP @ +0x1758, items @ +0x16b0. Results phase unhides cell layer so game's windows re-appear; bottom-screen panel mirrors the results via live message crossfade.

## Overworld map rendering (2026-09-04)

**On-device render, no capture.** All 8 world maps are composited on first
launch from the game's own data (replaced the old PixelCopy screenshot-of-the-
map-screen capture, which needed the player to open the map once per era and
depended on a guessed crop rect). Full disassembly evidence:
`tools/world_map/REPORT.md`; reference implementation: `tools/world_map/render_world.py`.

**Format.** `Game/world/Map/Map_%04d.dat` = 12,288 bytes = two 96x64 u8 layers
(layer 0 at byte 0, layer 1 at 0x1800, stride 0x1800 each); byte 0 in either
layer means "no metatile", not metatile 0. `Game/world/worldchip_<chip>_<plt>_<page>.png`
is a 512x512 ARGB sheet of 256 metatiles (32x32px, 2x native), `row = b>>4, col = b&15`;
page 0 backs map layer 0, page 1 backs layer 1. Per-cell compositing is per pixel
("nonzero alpha wins", never blended): paint layer0/page0, then layer1/page1, then
(for every world except world 5, Zeal/the sky, whose layers are swapped and whose
overlay isn't baked) layer0/page0 again on top. Output 3072x2048 (2x).

**World -> asset mapping** (world id == `PartySnapshot#worldEra`):

| world | chip | plt | map file | era |
|---|---|---|---|---|
| 0 | 0 | 4 | Map_0000 | 1000 AD (Present) |
| 1 | 0 | 5 | Map_0001 | 600 AD (Middle Ages) |
| 2 | 2 | 7 | Map_0003 | 2300 AD (Future) |
| 3 | 3 | 8 | Map_0004 | 65,000,000 BC (Prehistory) |
| 4 | 4 | 9 | Map_0005 | 12,000 BC (Dark Ages, ground) |
| 5 | 5 | 10 | Map_0007 | 12,000 BC (Zeal, sky) |
| 6 | 4 | 9 | Map_0006 | 12,000 BC (Dark Ages, post-Zeal) |
| 7 | 1 | 6 | Map_0002 | 1000 AD variant (Lavos-fallen / ending, cutscene) |

World 7's map is normally overwritten at runtime from a baked table in
`libchrono.so` (a cutscene-only variant); ChronoDuo does not reproduce that
patch and just renders straight from `Map_0002.dat`, same as `render_world.py`
without `--libchrono`.

**Implementation.** `WorldMapCompositor` (pure Java, no android.* imports) is
the pixel core -- `composite(map, page0, page1, overlayLayer0OnTop)` -- shared
by `WorldMapRenderer` (Android, decodes via `BitmapFactory`/`Bitmap`) and the
desktop check harness `tools/world_map/JavaRenderCheck.java` (decodes via
`ImageIO`/`BufferedImage`). `AppActivity#renderWorldMaps` extracts the 8
`Map_*.dat` + 14 `worldchip_*.png` entries from `resources.bin`, stages them
flat under `filesDir/world_src`, and calls `WorldMapRenderer.renderAll` off
the UI thread into `worldmap_era<N>.png` under the external files dir --
same filename `ChronoAssets.getWorldMap(era)` already resolved, so no other
call site changed. Verified against `render_world.py`'s reference output:
`JavaRenderCheck` matches all 8 worlds pixel-for-pixel.

**Party marker.** `PartySnapshot#worldX/worldY` are 8-pixel units at the
world's native 1x scale (`WorldImpl::GetPartyCharPos` left-shifts the raw
tile coordinate by 3), so the full overworld spans X in 0..191, Y in 0..127
regardless of render scale -- `PartyPanelView.drawOverworldContent` maps
proportionally by those spans (`worldX/192`, `worldY/128`) through whatever
rect the map bitmap is drawn into, not a flat 256-unit range.

## Live overworld map from the game's own map data (2026-09-04)

The offline render above is now only the **first-launch fallback**. The panel
re-composites the world from the game's own **live metatile grid**, so story
changes (the Zenan bridge, the Lavos crater, the Ocean Palace) show up on the
second screen. All offsets below were verified instruction-by-instruction
against `llvm-objdump` of `libchrono.so` v2.1.5 arm64.

### Dead end: the RenderTextures are a 2x scrolling window, not the world

`WorldMap` holds twelve `cocos2d::RenderTexture`s — six terrain at
`WorldMap+0x26868`, six front-chip at `+0x268c8`, each 512x512 — and
`worldmap_screen_re.md` concluded they hold a complete 1536x1024 picture of the
whole world. **They do not, during gameplay.** Reading them back through their
own FBOs works perfectly (6/6 cells, ~20 ms, no GL error, on device), but the
content is a **2x-magnified scrolling window around the party**, tiled across
the six cells. The full-world 1x fill only happens on `enterMiniMap`, i.e. only
while the player is actually looking at the game's map screen — which is exactly
the state we did not want to force. The readback implementation (RT `_FBO` at
`+0x32c`, `_texture` at `+0x340`, both from `RenderTexture::initWithWidthAndHeight`
@0x895f38; `cocos2d::Image` layout `_data@0x28, _dataLen@0x30, _width@0x38,
_height@0x3c` from `initWithRawData` @0x8bb2a0) is in git history if it is ever
useful for capturing the map screen itself. **Do not retry it for a live map.**

### What we do instead: snapshot the metatile grid

The world is 96x64 metatiles in **two** u8 layers — 12 KB — and `WorldMap` keeps
that grid live and patched. `WorldMap::GetMapData(l,x,y)` @0x609b18 is:

```
mov w8,#0x1800 ; mov w9,#0x60
smaddl x8, l, 0x1800, this      ; layer stride 0x1800
smaddl x8, y, 0x60, x8          ; row stride 96
mov w9,#0x237e0 ; add x8,x8,x ; ldrb w0,[x8,x9]
```

i.e. `*(u8*)(this + 0x237e0 + l*0x1800 + y*96 + x)`. `PutMapData` @0x609b3c
writes through the identical expression, and `world::MapData::GetMapData`
@0x6066e0 is the same math **without** the `+0x237e0`, confirming that `WorldMap`
embeds a `world::MapData` at that offset. So layer 0 is at `+0x237e0` and layer 1
at `+0x24fe0`, **contiguous and 0x1800 apart** — the 0x3000-byte copy is
byte-for-byte the on-disk `Map_%04d.dat` layout (layer 0 at file offset 0,
layer 1 at 0x1800), so it drops straight into the existing
`WorldMapCompositor.composite`.

| Offset | Contents | Verified at |
|---|---|---|
| `WorldScene+0x320` | `WorldImpl*` (`+0x00` = Asm memory base) | `mapButton` @0x60bc64 |
| `WorldImpl+0x1e78` | `WorldMap*` | @0x60b00c `str x1,[x0,#0x1e78]` |
| `WorldMap+0x320` | era / world index 0..6 | `markMiniMap` @0x60a548 |
| `WorldMap+0x237e0` | `world::MapData`, 2 x 0x1800 metatile layers | `GetMapData` @0x609b18, `PutMapData` @0x609b3c, `world::MapData::GetMapData` @0x6066e0 |
| `WorldMap+0x26d88` | u8 map-dirty flag | set `PutMapData` @0x609ba4, cleared `update` @0x608518 |

**Capture path.** `world_map_tick` in `gamestate.c` runs on the GL thread,
piggybacked on the existing per-frame enforcer tick. It resolves
WorldScene→WorldImpl→WorldMap (all `safe_read`-guarded, never cached across
frames), and when the dirty byte is **0** (the game applies a scripted map edit
as a burst of `PutMapData` calls, each setting that byte, so sampling mid-burst
would hash a half-applied grid) it `safe_read`s the 12 KB into a static buffer
and FNV-1a hashes it. The Java accessors read only that static snapshot, so they
are callable from any thread and can never dereference a `WorldMap*` the scene
teardown has already freed. `nativeGetWorldMapHash()` is the cheap poll;
`nativeGetWorldMapData(byte[])` pulls the bytes only when it moves. No GL work.

**Java side.** `WorldMapLive.tick(snap)` runs from the panel's existing 500 ms
poll; on a hash change it re-runs `WorldMapCompositor.composite` on a background
thread with the chip pages for that world (decoded from the `filesDir/world_src`
PNGs that `AppActivity#renderWorldMaps` re-stages every launch, through
`WorldMapRenderer.loadChipPages` — the same non-premultiplied decode, since the
compositor does a pixel replace and not a blend), publishes the 3072x2048 result
through `ChronoAssets.setLiveWorldMap`, and writes it over
`worldmap_era<N>.png`. A `worldmap_era<N>.hash` sidecar holds the grid hash the
on-disk PNG was made from, so an unchanged map is never re-encoded. Chip pages
are cached for the current world only (2 MB). One log line per recomposite with
world id, hash, composite ms, and any skip reason; plus, once per world per
session, a diff count of the live grid against the shipped `Map_%04d.dat` —
which says directly whether the game had already patched story state in.

**Two id spaces — do not confuse them.** `WorldMap+0x320` (0..6) is the
`WorldMap` object's own world index (exposed as `nativeGetWorldMapIndex()`, for
logs only); `GameState.nativeGetWorldEra()` (Asm u16 @0x2E100 − 0x1F0, 0..10) is
what `PartySnapshot#worldEra`, `ChronoAssets.getWorldMap(era)` and
`worldmap_era<N>.png` are keyed by. Everything user-facing uses the latter.

**Party and Epoch markers — pixel-granular, CALIBRATED.** `WorldMap::markMiniMap`
@0x60a4f0 does not use the 8px tile bytes: party `Px = u16 @asmmem 0x2E283`
(@0x60a52c), `Py = u16 @0x2E285` (@0x60a544). The Epoch ("silverd" = シルバード,
**not** a generic POI) is drawn only when `(i8)asmmem[0x2E294] < 0` (@0x60a6f4)
*and* `u16@0x2E100 == u16@0x2E29F` (@0x60a708/@0x60a718), at
`Ex = asmmem[0x2E290] | asmmem[0x2E291]<<8` (@0x60a734), `Ey = u16 @0x2E292`
(@0x60a744). On-device logs settled the transform: `partyRaw=(280,280)` against
`tile=(35,35)` and `partyRaw=(280,296)` against `tile=(35,37)` — i.e.
`raw == tile*8` exactly on both axes, so **the raw values already are 1x
world-image pixels with a top-left origin**. The (−256, +256) shift and modulo
wrap derived statically from `markMiniMap`'s node-space transform described the
RenderTexture mosaic's own space and do **not** apply; the conversion is the
identity. `PartyPanelView` maps `x/1536`, `y/1024` proportionally into whatever
rect the (2x, 3072x2048) render is drawn into, and falls back to the 8px tile
bytes at 0x2E102/0x2E103 when the pixel values are unreadable.
`Game/common/minimap_mark.png` is 48x16 = three 16x16 cells: cell 1 = party,
**cell 2 = the Epoch** (`ChronoAssets.getEpochMark`).

**Era 5 (Zeal) comes out right for free** — `WorldMapCompositor` already has the
swapped-layer / no-overlay special case, and the live path reuses it via
`WorldMapRenderer.overlayLayer0OnTop(world)`.

**Scene lifetime.** `WorldScene` is a real scene swap; going indoors destroys the
`WorldMap`. The `WorldMap*` is re-resolved every tick and never cached, but the
12 KB snapshot deliberately *survives* the scene, so the panel keeps showing the
last known world after the party goes indoors.

**Dev trigger.** `adb shell am broadcast -a com.kalenjohnson.chronoduo.WORLD_MAP_CAPTURE`
forces a recomposite even when the hash is unchanged, logs the live-vs-file diff
count, the grid hash, WorldScene/dirty state and the raw/derived marker values,
and writes `<externalFilesDir>/worldmap_capture_debug.png`. Unlike the previous
RenderTexture version it cannot be starved by the normal path consuming a
one-shot — there is no one-shot; a forced composite always runs from the
current snapshot.

**Still unverified on device:** that a live grid actually diverges from the
shipped `Map_%04d.dat` after a story event (the diff-count log answers it in one
line), and the recomposite wall time for a 3072x2048 composite on the Thor.
