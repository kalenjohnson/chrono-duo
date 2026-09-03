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
  +0x00 char id, +0x04/+0x08 ? (xp-ish), +0x10 curHP, +0x14 maxHP, +0x18 curMP,
  +0x1c maxMP, +0x20 baseMaxHP, +0x24 Pow, +0x28 Sta, +0x2c Spd, +0x30 Mag,
  +0x34 Hit, +0x38 Evd, +0x3c MDef, +0x40 level.
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
- **Still open:** current map/area id, battle state, character portraits
  (need resources.bin decryption à la ChronoMod).
  For gold: diff before/after buying something. For play time: two dumps with
  everything else idle. Battle info: `SfcBattleWork` / `SceneBattle::getwork8`
  (reads a buffer pointer at SceneBattle+0x8) is the entry point.

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
