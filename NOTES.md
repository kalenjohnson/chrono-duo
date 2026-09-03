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
- **Party composition: SOLVED via differential dump** (before/after Marle
  joined): character record field **+0x11c (i32) = 1-based party slot**,
  -1 when not in the party. Crono=1, Marle=2 verified live.
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
