# Brief: true widescreen (overworld centring, field maps, battles) in ChronoDuo

You are working in `/home/kalenj/Work/chrono-trigger` (ChronoDuo), an Android
host app that runs the official Chrono Trigger Android game's `libchrono.so`
(cocos2d-x 3.14.1, arm64, v2.1.5, unstripped dynamic symbol table) inside its
own process and hooks the engine from `app/src/main/cpp/gamestate.c` via
`dlsym`, GOT/PLT slot patching (`pixel_find_jump_slot` / `pixel_patch_slot`),
vtable slot patching, and a per-frame GL-thread tick (`nativeEnforceUiTick`).
Read `NOTES.md` first: the sections "Phase 3 memory layout", "Field render
path", "RESOLVED ... drawFrontChip", "Dead end: the RenderTextures are a 2x
scrolling window", and "Design zoom / true widescreen (2026-09-16)".

## The goal

The port draws the game world 2x into a 568x320-point design canvas
(`Director::setContentScaleFactor(2.0)`, `NO_BORDER`), so the visible field
is ~284x160 SNES pixels: wider than the SNES's 256, but 64 rows shorter than
its 224. We want a "true widescreen" option that shows the full 224 rows
(and the extra width that comes with 16:9). The overworld renders but is
off-centre; fields and battles are the open problems.

## What exists and works

- Pref `design_zoom` (float, default 1.0; debug override file
  `<externalFilesDir>/design_zoom.txt`) read at boot by
  `GameState.applyDesignZoomPref` → `nativeSetDesignZoom`.
- `gamestate.c`, section "Design zoom": patches `GLViewImpl` vtable slot 0xb8
  (`GLView::setDesignResolutionSize(float,float,ResolutionPolicy)` @0x8b77b4;
  `this+0x2c` holds the stored design size) so the canvas can be set to
  568*z x 320*z at runtime. Boot is stock. A GOT hook on
  `Director::setNextScene` (@0x8d8904) switches the canvas as a scene comes
  in: `WorldScene` present → zoomed, else stock. Rule: never switch a scene
  that is already laid out (the title slid to the top-right when we did).
- Field zoom is opt-in (`g_design_zoom_field`, `nativeSetDesignZoomField`),
  default off, because it is broken (below). Battle handling flag
  `g_design_zoom_battle`.
- GOT hook on `RenderTexture::create(int,int)` (PLT 0xb3fb10, GOT 0xbcb800)
  keyed on 19 return addresses inside `FieldMap::makeField`
  (0x57520c–0x576668): the five literal 432x224 windows scale both dims,
  the 640/768/512x256 strips scale height only, map-size RTs untouched.
- Overworld at zoom 1.4: the map renders correctly everywhere (its window
  follows the viewport) and the location label's Y is already computed from
  `Director::getVisibleSize()` (`WorldImpl::drawMsg` @0x63dcbc, margins
  141/234 from the top). The era chip ("600 A.D.") position and the label's
  X were not located (not in `WorldScene::init` @0x782f04, `WorldMenu::init`
  @0x782608, `WorldMap::init` @0x60688c; try callers of
  `nsSpriteUtils::createOutlineLabel` @0x7433ac / `createLabel`).

## Overworld: what is still wrong, and what we learned the hard way

Camera: `WorldMap::setScroll(float x, float y)` @0x6098cc (PLT 0xb447f0,
GOT JUMP_SLOT 0xbcde70) stores scrollX = 128 − x, scrollY = y + 96 at
WorldMap+0x26858/+0x2685c (WorldMap = WorldScene+0x320 → WorldImpl,
+0x1e78 → WorldMap). Static callers: `WorldImpl::InitScreen` @0x60f88c
(x = tileX*8, y = tileY*8) and `WorldImpl::kazumi_Nmi` @0x612b44. **On
device it fires every frame** (the hook log counted 680+ calls with the
same args while standing still), so it is the per-frame camera, not a
one-time origin. `WorldMap::Scroll(Vec2 const&)` @0x6098f0 clamps
±1536/±1280 (map edges, leave alone).

Result of hooking it with (x − dx, y + dy), dx = ((W−568)/2)/2 = 56.8 px,
dy = ((H−320)/2)/2 = 32 px at zoom 1.4 (code still present in
`gamestate.c`, `hooked_WorldMap_setScroll`, tunable via
`<externalFilesDir>/design_zoom_center.txt` = "kx ky ox oy", currently
"0 0 0 0" on the device): the MAP moved to centre the party's world
position on the true centre, but the party sprite (and NPC/vehicle
sprites) stayed at the old screen position — they are placed at a fixed
screen point derived from the stock canvas, independently of the map
scroll. So without the hook: map and sprites agree, both anchored at the
stock centre (284,160 pt), party low-left of the true centre (397,224).
With the hook: map centred, sprites wrong.

What the fix must do: move map AND sprite placement together. Candidates:
(a) find where the world sprites' screen positions are computed (the
party sprite sits at ≈(281 pt, 132 pt from the bottom) on the zoomed
canvas; look for the stock-centre constants 128/96/142/80/256/160 in
`WorldImpl::draw*`/`WorldImpl::update`/the chara-sprite update, or a
`setPosition` on the party node, and derive from the live design size
(`GLView this+0x2c`) — then keep the setScroll hook; or (b) translate the
whole world content by ((W−568)/2, (H−320)/2) points once (root/layer
`setPosition`), which moves map, sprites and labels consistently — but
check whether the map layer's coverage window is viewport-anchored (a
translated root may leave uncovered strips at the bottom/left; if it
does, enlarge the window or combine with (a)). Verify with screenshots
(`adb shell screencap -d 4630946441858561667 -p`); the user walks.

## What is broken for fields (measured on device at zoom 1.4)

With the RT hook active the field fills the full height, but the composited
map is displaced from the sprites by ≈ the RT width delta (608−432 = 176 pt;
party "on the ladder" draws ~400 device px right of the ladder), and a black
strip ≈ half the delta remains at the right edge. Sprites/NPCs are correct;
the ground layers are wrong. Static trace findings (addresses are file
offsets in `libchrono.so`; `llvm-objdump -d` output for the whole lib exists
or can be regenerated):

- `makeField` positions every RT with inline float immediates via
  `Node::setPosition(float,float)` (vtable+0xc8): 432x224 RTs at (128,112);
  640x256 at (256,160); 768x256 at (0,96); dynamic-width strips at
  (w/2−96, 160) and (192, mapH/2). No anchor/scale calls.
- `FieldMap::setScroll(float,float)` @0x576cd8 (**no JUMP_SLOT relocation:
  every caller is a direct `bl`, so it is not GOT-hookable; needs a .text
  patch or an inline trampoline**): x = 128 − sx;
  y = (sy + 96) − mapH (mapH = `[FieldMap+0x334]`); written to
  `FieldMap+0x350/0x358/0x360` and `+0x354/0x35c/0x364`; calls
  `FieldImpl::ResetScrollAddress(int,int)` @0x576d74.
- `FieldMap::Scroll(float)` @0x576ec8 (per frame): clamp/delta math using
  +216 and −216 (= 432/2) at 0x577ab8/0x577ac8, 256 at 0x577ac4, −96 at
  0x577ad0; re-reads `+0x350/+0x354`.
- `FieldMap::setScrollLimit(const MapInfo&)` @0x570c90: clamp bounds
  (`+0x378..0x384`) from `Director::getVisibleSize/getVisibleOrigin` plus
  literals 44, 256, 480, 192, 320 (320 = stock canvas height, used in an
  fdiv at 0x570d18); reads map width `[FieldMap+0x330]`.
- `MapTable::CreateSprites` @0x56bbdc culls tiles to
  `ChipTable+0x518/0x51c/0x520/0x524` where ChipTable =
  `ChronoCanvas::getInstance()+0x13000` (also `[MapTable+0x48]+0x13000`);
  the 27x14 metatile window. **The writer of those four fields was not
  found** (`ChipTable::Load/LoadExt` only copy resource bytes at +0x13200+).
  If this window is fixed, no position fix removes the right strip.
- One immediate (`movi v0.2s,#0x43,lsl#24` = 128.0 at 0x576d30) cannot be
  re-encoded in place with an arbitrary float; needs a stub or a hook.
- `RenderTexture::onBegin` @0x8972c4 derives viewport/ortho from the RT's
  own pixel size: enlarged RTs render correctly; the bug is placement.
- `FieldImpl::AdjustScroll` @0x57d828 does ±16 px stepping and map-edge
  clamping in `FieldImpl+0x868`; no window constants there.
- Battles are field-layer: node class `Battle` under the field Scene,
  `Battle+0x320` → `SceneBattle`; battle backgrounds use a separate render
  path (`FieldImpl::drawEclipse` and friends) that has not been examined.

Suggested discriminating experiment before patching: run at zoom 1.2 and
measure the map offset and strip width; a full-Δw model predicts ≈232 device
px, a Δw/2 model ≈116. Screenshots: `adb shell screencap -d
4630946441858561667 -p` is the top screen (1920x1080). Do not inject input;
the user plays and reports.

## Constraints

- Never `git push`; commit only after the user verifies on device.
- Build: `./gradlew assembleDebug -q -PversionCode=<higher than installed>`;
  install with `adb install -r` (never uninstall: saves live in the app's
  external files dir). adb is at `~/Android/Sdk/platform-tools/adb`.
- Zoom 1.0 must remain a strict no-op; all patches gated on the live mode.
- Prefer deriving corrected values from `Director::getVisibleSize()` at
  runtime over hardcoding 1.4; verify original bytes before any `.text`
  write; log each patch once.
- Another Claude session may be working in the repo; coordinate file
  ownership with `SendMessage`/`ListAgents` before editing
  `PartyPanelView.java`.

## Deliverables

0. Overworld centring: make map and sprites move together (see the
   overworld section); this is the smallest win and validates the method.
1. A verdict, with evidence, on whether field zoom is achievable by
   patching `setScroll`/`Scroll`/`setScrollLimit` constants plus the
   culling window, and what the culling window's writer is.
2. If achievable: the implementation in `gamestate.c`, gated on
   `g_design_zoom_field`, verified on device with the user.
3. The same analysis for battles (background, actor placement, the
   mirrored-command touch affine in `PartySnapshot.java` `CMD_SX/SY`).
4. Update `NOTES.md` with what you learn, including dead ends.
