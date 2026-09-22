package org.cocos2dx.cpp;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import android.view.KeyEvent;
import android.view.MotionEvent;

import com.kalenjohnson.chronoduo.ChronoRuntime;
import com.kalenjohnson.chronoduo.GameControllerInput;
import com.kalenjohnson.chronoduo.PartyPanelView;
import com.kalenjohnson.chronoduo.SecondScreenManager;

import org.cocos2dx.lib.Cocos2dxActivity;
import org.cocos2dx.lib.Cocos2dxHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Host activity for libchrono.so (Chrono Trigger "Upgrade Ver.", cocos2d-x 3.14.1).
 *
 * The class lives in org.cocos2dx.cpp because the game binary exports two custom
 * natives bound to this exact class name:
 *   Java_org_cocos2dx_cpp_AppActivity_setAssetManager
 *   Java_org_cocos2dx_cpp_AppActivity_setExternalStorageInfo
 *
 * Boot order (mirrors ct_nx and the original app):
 *   System.load(libc++_shared, libchrono) -> JNI_OnLoad
 *   Cocos2dxHelper.init: nativeSetApkPath(game apk), nativeSetContext(ctx, game assets)
 *   setAssetManager(ctx, game assets)
 *   setExternalStorageInfo(extPath, extPath, game package)
 *   GL surface created -> nativeInit(w, h) on the GL thread
 */
public class AppActivity extends Cocos2dxActivity {
    private static final String TAG = "ChronoDuo";

    private ChronoRuntime runtime;
    private SecondScreenManager secondScreen;
    private final GameControllerInput controllerInput = new GameControllerInput();
    // Keycodes whose ACTION_DOWN was consumed by the panel's own command-row
    // navigation (see offerToPanel/dispatchKeyEvent) -- the matching
    // ACTION_UP must be swallowed too, or the game sees a bare release with
    // no press it knows about (a "stuck key" style half-press).
    private final Set<Integer> swallowedKeys = new HashSet<>();
    // Request code for the SAF document picker used by requestRomImport/
    // launchRomPicker below. No androidx.activity dependency in this project
    // (see app/build.gradle) -- Cocos2dxActivity already forwards
    // onActivityResult through Cocos2dxHelper's OnActivityResultListener set
    // and into super, so overriding it here (classic startActivityForResult
    // style) is the natural fit rather than adding a new dependency for an
    // ActivityResultLauncher.
    private static final int REQUEST_ROM_IMPORT = 4242;
    // Request code for the SAF document picker used by requestSaveImport/
    // launchSavePicker (SNES .srm import) -- same startActivityForResult
    // pattern as REQUEST_ROM_IMPORT above, distinct code so onActivityResult
    // can tell the two pickers apart.
    private static final int REQUEST_SAVE_IMPORT = 4243;
    // Request code for the SAF document picker used by requestModImport/
    // launchModPicker (mod .ctp/.zip import) -- same startActivityForResult
    // pattern as REQUEST_ROM_IMPORT/REQUEST_SAVE_IMPORT above, distinct code
    // so onActivityResult can tell the three pickers apart.
    private static final int REQUEST_MOD_IMPORT = 4244;
    // User mod loader (see com.kalenjohnson.chronoduo.mods.ModManager) --
    // instantiated once ext (the external files dir) is known, in onCreate.
    private com.kalenjohnson.chronoduo.mods.ModManager modManager;
    // Curated mod catalog (see com.kalenjohnson.chronoduo.mods.ModCatalog) --
    // loaded in the background in onCreate (loadModCatalog) and refreshed
    // into this field + the panel every time it (re)loads. Volatile: read
    // from the main thread (findCatalogEntry, for a "Get" tap) and from
    // background import threads (handleViewIntent's fileHint match).
    private volatile java.util.List<com.kalenjohnson.chronoduo.mods.ModCatalog.Entry> modCatalog =
            java.util.Collections.emptyList();
    // Cancel signal for a background OrigArtRebuilder.rebuildAll pass (see
    // requestOrigArtBuild) -- polled between sheets by that call's own
    // BooleanSupplier param, set true in onDestroy so a build in flight when
    // the activity is torn down stops at the next pair boundary instead of
    // racing a dead panel/context. Also doubles as an in-flight guard: a
    // build only starts if one isn't already running (the settings button's
    // own hit box is cleared while building, but that's UI-thread state and
    // this flag is checked/set from the background thread itself).
    private volatile boolean origArtBuildCancelled;
    private final java.util.concurrent.atomic.AtomicBoolean origArtBuilding =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static native void setAssetManager(Context context, AssetManager assetManager);
    public static native void setExternalStorageInfo(String path1, String path2, String packageName);

    @Override
    public AssetManager getAssets() {
        // Cocos2dxHelper.init() picks this up and hands it to nativeSetContext,
        // pointing every native AAssetManager read at the game's own APK.
        if (runtime != null && runtime.getChronoAssets() != null) {
            return runtime.getChronoAssets();
        }
        return super.getAssets();
    }

    // Only one engine instance may exist: the Thor's secondary-display
    // launcher can start a SECOND copy of this activity on the bottom screen
    // (observed live: two games running, one invisible, audio bleeding).
    private static AppActivity sLiveInstance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (sLiveInstance != null && sLiveInstance != this && !sLiveInstance.isFinishing()) {
            Log.w(TAG, "second instance launch blocked (existing instance alive)");
            Cocos2dxActivity.sSkipEngineInit = true;
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        sLiveInstance = this;
        controllerInput.setContext(this);
        try {
            runtime = ChronoRuntime.bootstrap(this);
        } catch (Exception e) {
            Log.e(TAG, "bootstrap failed", e);
            Cocos2dxActivity.sSkipEngineInit = true;
            super.onCreate(savedInstanceState);
            showBootstrapError(e);
            return;
        }

        Cocos2dxHelper.sAssetsPathOverride = runtime.getApkPath();

        // Saves used to live in getFilesDir(); they now live in the external files
        // dir (see Cocos2dxHelper.init). Move any existing ones across once, before
        // the engine looks for them.
        migrateSaves(getFilesDir(), getExternalFilesDir(null));

        super.onCreate(savedInstanceState);

        setAssetManager(this, runtime.getChronoAssets());
        File ext = getExternalFilesDir(null);
        String extPath = (ext != null ? ext : getFilesDir()).getAbsolutePath();
        setExternalStorageInfo(extPath, extPath, ChronoRuntime.CHRONO_PACKAGE);
        Log.i(TAG, "boot calls done, external storage: " + extPath);
        // So ChronoAssets.getAreaMap() can find <externalFilesDir>/ds_maps/*.png
        // (user-pushed DS-style area minimaps -- same "external files dir"
        // used below for the rendered world maps).
        com.kalenjohnson.chronoduo.ChronoAssets.setExternalFilesDir(ext != null ? ext : getFilesDir());
        // Per-era rendered world map PNGs (worldmap_era<N>.png, see
        // WorldMapRenderer/renderWorldMaps below) live in the same external
        // files dir -- see ChronoAssets.getWorldMap.
        com.kalenjohnson.chronoduo.ChronoAssets.setWorldMapDir(ext != null ? ext : getFilesDir());
        // Live world maps: re-composited from the game's own metatile grid
        // (see WorldMapLive) using the chip pages renderWorldMaps stages under
        // filesDir/world_src, and written over the same worldmap_era<N>.png
        // files, so story changes persist across launches.
        com.kalenjohnson.chronoduo.WorldMapLive.setDirs(
                ext != null ? ext : getFilesDir(), new File(getFilesDir(), "world_src"));
        // Private files dir: where DsMapImporter writes DS-derived maps
        // decoded on-device from a user-supplied ROM (see requestRomImport/
        // importRomFromUri below) -- checked before externalFilesDir by
        // ChronoAssets.getAreaMap().
        com.kalenjohnson.chronoduo.ChronoAssets.setFilesDir(getFilesDir());
        // area_calib.json: filesDir/ds_maps first (the on-device import
        // output), externalFilesDir/ds_maps as a dev-push fallback -- first
        // one found wins (AreaMapCalib.load is a no-op on a missing file).
        File calibFile = new File(new File(getFilesDir(), "ds_maps"), "area_calib.json");
        if (!calibFile.isFile() && ext != null) {
            calibFile = new File(new File(ext, "ds_maps"), "area_calib.json");
        }
        com.kalenjohnson.chronoduo.AreaMapCalib.load(calibFile);

        secondScreen = new SecondScreenManager(this);
        secondScreen.setSettingsHost(new PartyPanelView.SettingsHost() {
            @Override public void onPixelGraphicsChanged(boolean enabled) {
                if (enabled) scanOrigArtReplacements();
                else com.kalenjohnson.chronoduo.GameState.nativeClearTextureReplacements();
            }
            @Override public void requestRomImport() {
                launchRomPicker();
            }
            @Override public void requestOrigArtBuild() {
                AppActivity.this.requestOrigArtBuild();
            }
            @Override public void requestSaveImport() {
                launchSavePicker();
            }
            @Override public void requestModImport() {
                launchModPicker();
            }
            @Override public void onModToggled(String name, boolean enabled) {
                setModEnabled(name, enabled);
            }
            @Override public void requestModGet(String id) {
                AppActivity.this.requestModGet(id);
            }
            @Override public void onModOptionSelected(String group, String optionTitle, String dirOrNull) {
                setModOption(group, optionTitle, dirOrNull);
            }
            @Override public void onModMoved(String name, boolean up) {
                moveMod(name, up);
            }
        });
        // A Presentation the system tears down and recreates behind our
        // back (sleep/wake being the common trigger -- see
        // SecondScreenManager's class doc) gets a brand-new PartyPanelView,
        // which starts from field defaults: the Mods page's installed-mod
        // list and catalog (pushed only at scan/catalog-load time, never
        // re-pushed) would silently go blank until the next mod action.
        // Re-push them here. The other push-once state (DS-ROM import,
        // save-import, and original-art rebuild progress) is intentionally
        // NOT replayed: each already treats a freshly-defaulted idle state
        // as correct by design (see PartyPanelView#setImportStatus/
        // #setOrigArtStatus's doc -- idle falls back to counting files
        // already on disk; #setSaveImportStatus's idle is just "no message
        // yet", which is already true of a fresh panel).
        secondScreen.setPanelListener(this::onSecondScreenPanelAttached);
        controllerInput.ensureConnected();
        // Physical hat-axis d-pad left/right (see GameControllerInput.
        // handleMotionEvent) offers to the panel's command-row navigation
        // through this sink, same target as dispatchKeyEvent's key-event
        // path below (offerToPanel).
        controllerInput.setCommandNavSink(new GameControllerInput.CommandNavSink() {
            @Override public boolean left() {
                PartyPanelView panel = secondScreen.getPanel();
                return panel != null && panel.onControllerLeft();
            }
            @Override public boolean right() {
                PartyPanelView panel = secondScreen.getPanel();
                return panel != null && panel.onControllerRight();
            }
            @Override public boolean up() {
                PartyPanelView panel = secondScreen.getPanel();
                return panel != null && panel.onControllerUp();
            }
            @Override public boolean down() {
                PartyPanelView panel = secondScreen.getPanel();
                return panel != null && panel.onControllerDown();
            }
            @Override public boolean confirm() {
                PartyPanelView panel = secondScreen.getPanel();
                return panel != null && panel.onControllerConfirm();
            }
        });
        renderWorldMaps();
        // Original-sprite replacements ride on the pixel-graphics preference.
        if (com.kalenjohnson.chronoduo.GameState.getPixelGraphicsPref(this)) scanOrigArtReplacements();
        // Mods are unconditional -- unlike orig_art above, they don't ride on
        // any preference. The boot scan runs synchronously: the game's first
        // asset reads happen on the GL thread once the surface exists (after
        // onCreate returns), so registering here guarantees even the very
        // first getData() sees the mod table. Walking a few thousand files
        // takes tens of ms. Later rescans (import/toggle) go through the
        // background-thread scanMods().
        //
        // This MUST run before extractCompanionAssets() below: ModManager's
        // constructor publishes the static holder ChronoResources#
        // extractModAware reads (via ModManager#resolveStatic), and scan()
        // populates the winner map that backs it -- extracting the
        // companion-UI art first would race that (or simply miss it) and the
        // second screen would show unmodded art until the next import/toggle.
        modManager = new com.kalenjohnson.chronoduo.mods.ModManager(ext != null ? ext : getFilesDir());
        try {
            modManager.scan();
        } catch (Throwable t) {
            Log.e(TAG, "boot mod scan failed", t);
        }
        updateModsStatus(false, null);
        extractCompanionAssets();
        loadModCatalog();
        // A .ctp opened from outside (browser download, file manager, "Open
        // with" on a shared file) before the app was running arrives as the
        // launch intent rather than onNewIntent -- see handleViewIntent.
        handleViewIntent(getIntent());

        com.kalenjohnson.chronoduo.GameState.attach();
        // Frame-perfect menu parking: a per-rendered-frame GL tick that kills
        // the residual field-MENU flash the 700ms backstop tick (below) can
        // still miss between its own runs, plus opacity-hides the battle
        // top-UI. Started once; safe before the GL surface exists.
        com.kalenjohnson.chronoduo.GameState.startFrameEnforcer();
        // Blanks the top-screen Tech/Item submenu lists too (the bottom-
        // screen panel mirrors them instead -- see PartyPanelView's
        // submenu-list band). Gated behind PartyPanelView.HIDE_SUBMENUS so
        // this can be flipped off in one place if it ever misbehaves; must
        // run on the GL thread, like the rest of the native UI-hiding calls.
        if (PartyPanelView.HIDE_SUBMENUS) {
            Cocos2dxHelper.runOnGLThread(
                    () -> com.kalenjohnson.chronoduo.GameState.nativeSetHideBattleSubmenus(true));
        }
        // periodic full-memory dumps for offline layout analysis (dev only)
        File dumpDir = getExternalFilesDir(null);
        if (dumpDir != null) {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            String dir = dumpDir.getAbsolutePath();
            final long FIELD_INTERVAL_MS = 8000;
            final long BATTLE_INTERVAL_MS = 4000;
            final String[] DUMP_NAMES = {"sfcwork.bin", "asmmem.bin", "asmmem2.bin", "btldata.bin",
                    "btlwork.bin", "btlchara.bin", "btlobj.bin"};
            final int MAX_GENERATIONS = 8; // seq wraps at 8 -> max 8*4 = 32 battle-tagged files
            Runnable dump = new Runnable() {
                int battleSeq = 0;
                @Override public void run() {
                    com.kalenjohnson.chronoduo.GameState.dumpToFiles(dir);
                    Cocos2dxHelper.runOnGLThread(com.kalenjohnson.chronoduo.GameState::nativeUpdateBattleFlag);
                    boolean inBattle = com.kalenjohnson.chronoduo.GameState.nativeGetBattleFlag();
                    if (inBattle) {
                        com.kalenjohnson.chronoduo.GameState.nativeDumpBattleBuffers(dir);
                        // seq cycles 0..MAX_GENERATIONS-1; each copy overwrites the file
                        // from MAX_GENERATIONS ticks ago, so at most 8 generations
                        // (32 files) of battle-tagged dumps ever exist on disk.
                        int seq = battleSeq;
                        battleSeq = (battleSeq + 1) % MAX_GENERATIONS;
                        for (String name : DUMP_NAMES) {
                            File src = new File(dir, name);
                            if (!src.exists()) continue;
                            File dst = new File(dir, "battle_" + seq + "_" + name);
                            copyFile(src, dst);
                        }
                    }
                    h.postDelayed(this, inBattle ? BATTLE_INTERVAL_MS : FIELD_INTERVAL_MS);
                }
            };
            h.postDelayed(dump, FIELD_INTERVAL_MS);

            // "clean UI": keep the game's on-screen touch buttons hidden — the
            // controller covers them (Y = menu). Re-applied every 700ms because
            // scene transitions rebuild the UI nodes (FieldMenu) and the
            // overworld re-asserts its own button visibility every frame
            // (WorldMenu). Now purely a steady-state backstop for whatever the
            // per-frame enforcer (GameState.startFrameEnforcer(), started
            // above) might miss -- the enforcer's own depth<=2 park sweep runs
            // every rendered frame and is what actually kills the flash, so
            // SecondScreenPresentation's old post-scene-change "hide burst" was
            // removed as redundant (a once-per-frame sweep already beats its
            // 150ms cadence by roughly an order of magnitude).
            Runnable cleanUi = new Runnable() {
                @Override public void run() {
                    com.kalenjohnson.chronoduo.GameState.queueHiddenUiPatterns();
                    h.postDelayed(this, 700);
                }
            };
            h.postDelayed(cleanUi, 700);
        }

        // dev trigger: adb shell am broadcast -a com.kalenjohnson.chronoduo.WORLD_MAP_CAPTURE
        // -- forces a re-composite of the world from the game's live metatile
        // grid and logs the live-vs-Map_%04d.dat diff count, the grid hash,
        // the WorldScene/dirty state and the raw/derived marker values, then
        // writes <externalFilesDir>/worldmap_capture_debug.png.
        // dev trigger: adb shell am broadcast -a com.kalenjohnson.chronoduo.SCENE_DUMP
        // dev trigger: adb shell am broadcast -a com.kalenjohnson.chronoduo.BATTLE_HIDE_MASK
        // --ei mask N -- blanks direct children of the battle node by index
        // bitmask, for seeing live which child draws what.
        android.content.IntentFilter filter =
                new android.content.IntentFilter("com.kalenjohnson.chronoduo.SCENE_DUMP");
        filter.addAction("com.kalenjohnson.chronoduo.BATTLE_HIDE_MASK");
        // BATTLE_UI_SHOW --ez show true|false: un-blanks (or re-blanks) the
        // game's own top-screen battle UI, submenus included, so it can be
        // compared against the bottom-screen mirror live.
        filter.addAction("com.kalenjohnson.chronoduo.BATTLE_UI_SHOW");
        filter.addAction("com.kalenjohnson.chronoduo.WORLD_MAP_CAPTURE");
        android.content.BroadcastReceiver devReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                String action = i.getAction();
                if ("com.kalenjohnson.chronoduo.WORLD_MAP_CAPTURE".equals(action)) {
                    File dir = getExternalFilesDir(null);
                    com.kalenjohnson.chronoduo.WorldMapLive.requestDebugCapture(
                            dir != null ? new File(dir, "worldmap_capture_debug.png") : null);
                    return;
                }
                if ("com.kalenjohnson.chronoduo.BATTLE_UI_SHOW".equals(action)) {
                    boolean show = i.getBooleanExtra("show", true);
                    Cocos2dxHelper.runOnGLThread(() -> {
                        com.kalenjohnson.chronoduo.GameState.nativeSetHideBattleUi(!show);
                        com.kalenjohnson.chronoduo.GameState.nativeSetHideBattleSubmenus(
                                !show && PartyPanelView.HIDE_SUBMENUS);
                    });
                    return;
                }
                if ("com.kalenjohnson.chronoduo.BATTLE_HIDE_MASK".equals(action)) {
                    int mask = i.getIntExtra("mask", 0);
                    Cocos2dxHelper.runOnGLThread(
                            () -> com.kalenjohnson.chronoduo.GameState.nativeSetBattleHideMask(mask));
                    return;
                }
                int depth = i.getIntExtra("depth", 4);
                Cocos2dxHelper.runOnGLThread(
                        () -> com.kalenjohnson.chronoduo.GameState.nativeSceneDump(depth));
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(devReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(devReceiver, filter);
        }
    }

    /**
     * Offers keycode {@code kc} to the second screen's panel via the
     * matching {@code PartyPanelView.onController*} method, returning false
     * (never touching the game) when no presentation/panel is currently
     * showing. Shared by {@link #dispatchKeyEvent} and the
     * {@link GameControllerInput.CommandNavSink} registered in
     * {@link #onCreate} so both physical-input paths (raw key events and
     * hat-axis-synthesized dpad events) agree on where left/right/up/down/
     * confirm go.
     */
    private boolean offerToPanel(int kc) {
        if (secondScreen == null) return false;
        PartyPanelView panel = secondScreen.getPanel();
        if (panel == null) return false;
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return panel.onControllerLeft();
            case KeyEvent.KEYCODE_DPAD_RIGHT: return panel.onControllerRight();
            case KeyEvent.KEYCODE_DPAD_UP: return panel.onControllerUp();
            case KeyEvent.KEYCODE_DPAD_DOWN: return panel.onControllerDown();
            case KeyEvent.KEYCODE_BUTTON_A: return panel.onControllerConfirm();
            default: return false;
        }
    }

    /**
     * Before forwarding to the game (via {@link #controllerInput}), gives the
     * second screen's own command-row/list-row navigation first crack at a
     * fresh (repeatCount == 0) DOWN of d-pad-left/-right/-up/-down or the A
     * button -- see {@link #offerToPanel}. When the panel consumes it, the matching UP is
     * also swallowed here (via {@link #swallowedKeys}) so the game never sees
     * a release with no press it knows about. A DOWN the panel does NOT
     * consume clears any stale entry for that keycode first, bounding the
     * damage if some earlier consumed DOWN's UP never arrived (window/focus
     * churn) -- otherwise the next unrelated UP for that keycode would be
     * swallowed while its own DOWN went to the game.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        // Right trigger (R2) is fast-forward, not a game input (the game
        // uses L1/R1 for page-shift, not the triggers) -- always swallowed,
        // never reaches controllerInput/the game. See GameSpeed for the
        // HOLD/TOGGLE semantics; the analog-trigger path lives in
        // GameControllerInput.handleMotionEvent (AXIS_RTRIGGER/AXIS_GAS).
        if (kc == KeyEvent.KEYCODE_BUTTON_R2) {
            if (event.getRepeatCount() == 0) {
                // GameSpeed merges this KEY edge with the analog AXIS edge
                // (an analog trigger delivers both for one pull) and
                // branches HOLD (activate) vs TOGGLE (flip) itself.
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    com.kalenjohnson.chronoduo.GameSpeed.press(this, com.kalenjohnson.chronoduo.GameSpeed.Source.KEY);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    com.kalenjohnson.chronoduo.GameSpeed.release(this, com.kalenjohnson.chronoduo.GameSpeed.Source.KEY);
                }
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && swallowedKeys.remove((Integer) kc)) {
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT
                        || kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN
                        || kc == KeyEvent.KEYCODE_BUTTON_A)) {
            if (offerToPanel(kc)) {
                swallowedKeys.add(kc);
                return true;
            }
            swallowedKeys.remove((Integer) kc);
        }
        if (runtime != null && controllerInput.handleKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (runtime != null && controllerInput.handleMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onLoadNativeLibraries() {
        if (runtime == null) return;
        System.load(new File(runtime.getLibDir(), "libc++_shared.so").getAbsolutePath());
        System.load(new File(runtime.getLibDir(), "libchrono.so").getAbsolutePath());
        Log.i(TAG, "libchrono.so loaded from " + runtime.getLibDir());
        // Must run right here, before this method returns: this is called
        // from Cocos2dxActivity.onCreate() well before the GL surface is
        // created (nativeInit runs later, asynchronously, on the GL thread
        // once the surface view is attached), so every texture the game
        // loads gets the current pixel-graphics preference from its very
        // first bind. Referencing GameState here also triggers its own
        // System.loadLibrary("chronoduo") if that hasn't happened yet.
        boolean pixelGraphics = com.kalenjohnson.chronoduo.GameState.applyPixelGraphicsPref(this);
        Log.i(TAG, "pixel graphics: " + (pixelGraphics ? "on" : "off (or native patch failed)"));
        // True widescreen (per-scene design canvas): must be installed here too,
        // before nativeInit's GL surface triggers AppDelegate::
        // applicationDidFinishLaunching's one-time setDesignResolutionSize call.
        // See GameState.applyDesignZoomPref/nativeSetDesignZoom.
        float designZoom = com.kalenjohnson.chronoduo.GameState.applyDesignZoomPref(this);
        Log.i(TAG, "design zoom: " + designZoom);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (secondScreen != null) secondScreen.onResume();
    }

    @Override
    protected void onPause() {
        if (secondScreen != null) secondScreen.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (sLiveInstance == this) sLiveInstance = null;
        origArtBuildCancelled = true;
        if (secondScreen != null) secondScreen.onDestroy();
        super.onDestroy();
    }

    // --- world map rendering -------------------------------------------------
    // Renders all 8 overworld maps on device from the game's own asset files
    // (Game/world/Map/Map_%04d.dat + Game/world/worldchip_<chip>_<plt>_{0,1}.png,
    // pulled out of resources.bin) into <externalFilesDir>/worldmap_era<N>.png,
    // N = the overworld/era id (GameState.nativeGetWorldEra() via
    // PartySnapshot#worldEra) -- see WorldMapRenderer/WorldMapCompositor and
    // tools/world_map/REPORT.md for the format this is ported from. Replaces
    // the old PixelCopy-of-the-map-screen auto-capture, which needed the
    // player to open the in-game map at least once per era and depended on a
    // guessed crop rect.

    /** Set true to force a re-render of every world even if worldmap_era<N>.png already exists (dev only). */
    private static final boolean WORLD_MAP_FORCE_RERENDER = false;

    /**
     * Extracts the map/chip source files this render needs from resources.bin,
     * stages them under a flat filesDir subdirectory (WorldMapRenderer expects
     * plain "Map_0000.dat"/"worldchip_0_4_0.png" names, not resources.bin's
     * full "Game/world/..." paths), then renders every world into
     * getExternalFilesDir(null) -- the same directory ChronoAssets.
     * setWorldMapDir was pointed at in onCreate. Entirely off the main
     * thread; best-effort like the rest of the extraction pipeline.
     */
    private void renderWorldMaps() {
        final File mapDir = getExternalFilesDir(null);
        if (mapDir == null) return;
        final Context appCtx = getApplicationContext();
        final android.content.res.AssetManager gameAssets = runtime.getChronoAssets();
        new Thread(() -> {
            try {
                java.util.List<String> flatNames = com.kalenjohnson.chronoduo.WorldMapRenderer.requiredAssetNames();
                String[] entryNames = new String[flatNames.size()];
                for (int i = 0; i < flatNames.size(); i++) {
                    String flat = flatNames.get(i);
                    entryNames[i] = flat.startsWith("Map_")
                            ? "Game/world/Map/" + flat
                            : "Game/world/" + flat;
                }
                java.util.Map<String, File> extracted =
                        com.kalenjohnson.chronoduo.ChronoResources.extractAll(appCtx, gameAssets, entryNames);
                if (extracted.size() != entryNames.length) {
                    Log.w(TAG, "world map render: only extracted " + extracted.size() + "/" + entryNames.length
                            + " source files from resources.bin");
                }
                File srcDir = new File(getFilesDir(), "world_src");
                if (!srcDir.isDirectory() && !srcDir.mkdirs()) {
                    Log.w(TAG, "world map render: cannot create " + srcDir);
                    return;
                }
                for (java.util.Map.Entry<String, File> e : extracted.entrySet()) {
                    String flat = e.getKey().substring(e.getKey().lastIndexOf('/') + 1);
                    copyFile(e.getValue(), new File(srcDir, flat));
                }
                boolean ok = com.kalenjohnson.chronoduo.WorldMapRenderer.renderAll(
                        srcDir, mapDir, WORLD_MAP_FORCE_RERENDER, msg -> Log.i(TAG, msg));
                Log.i(TAG, "world map render: batch complete, ok=" + ok);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    for (int era = 0; era < com.kalenjohnson.chronoduo.WorldMapRenderer.worldCount(); era++) {
                        com.kalenjohnson.chronoduo.ChronoAssets.invalidateWorldMap(era);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "world map render failed", e);
            }
        }, "WorldMapRender").start();
    }

    /**
     * Extracts the companion-UI art (portraits, world map sheet) from the
     * game's resources.bin off the main thread, then decodes and hands the
     * bitmaps to ChronoAssets on the main thread. Best-effort: any failure
     * (game assets missing, archive format surprise, OOM on a 420MB stream)
     * is logged and simply leaves PartyPanelView's placeholders in place.
     */
    private void extractCompanionAssets() {
        final Context appCtx = getApplicationContext();
        final android.content.res.AssetManager gameAssets = runtime.getChronoAssets();
        new Thread(() -> {
            try {
                // Locale hardcoded to "en"; other locales exist under
                // Localize/<lang>/msg/monster.txt but aren't wired up.
                String[] names = {
                        "Extension/face.png",
                        "Game/common/minimap_mark.png",
                        "Extension/menu_win.png",
                        "Localize/en/msg/monster.txt",
                        "Localize/en/msg/tech.txt",
                        "Localize/en/msg/item.txt",
                        "Localize/en/msg/sfc_item.txt",
                        "Localize/en/msg/battle.txt",
                        "Game/battle/tblb/MonsterNameData.dat",
                        "Game/common/TechnicMpTable.dat",
                        "Game/common/TechnicBaseDataTable.dat",
                };
                // Mod-aware: a mod that replaces any of these paths (portraits,
                // window chrome, marker tiles, name/message tables, ...) wins
                // here, unlike renderWorldMaps()'s extractAll -- see
                // ChronoResources#extractModAware and #extract's doc.
                java.util.Map<String, File> files =
                        com.kalenjohnson.chronoduo.ChronoResources.extractAllModAware(appCtx, gameAssets, names);

                final android.graphics.Bitmap face = decodeBitmap(files.get("Extension/face.png"));
                final android.graphics.Bitmap mark = cropMarkerTile(files.get("Game/common/minimap_mark.png"));
                final android.graphics.Bitmap epochMark = cropEpochTile(files.get("Game/common/minimap_mark.png"));
                final WindowTexResult windowTex = cropWindowTexture(files.get("Extension/menu_win.png"));
                final String[] monsterNames = readNameTable(files.get("Localize/en/msg/monster.txt"));
                final String[] techNames = readNameTable(files.get("Localize/en/msg/tech.txt"));
                final String[] itemNames = readNameTable(files.get("Localize/en/msg/item.txt"));
                final java.util.Map<String, String> itemCategoryNames =
                        readSfcItemTable(files.get("Localize/en/msg/sfc_item.txt"));
                final String[] battleMessages = readNameTable(files.get("Localize/en/msg/battle.txt"));
                final byte[] monsterFlags = readRawBytes(files.get("Game/battle/tblb/MonsterNameData.dat"));
                final int[] techMp = readTechMpTable(
                        files.get("Game/common/TechnicMpTable.dat"),
                        files.get("Game/common/TechnicBaseDataTable.dat"));

                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (face != null) com.kalenjohnson.chronoduo.ChronoAssets.setFace(face);
                    if (mark != null) com.kalenjohnson.chronoduo.ChronoAssets.setMinimapMark(mark);
                    if (epochMark != null) com.kalenjohnson.chronoduo.ChronoAssets.setEpochMark(epochMark);
                    if (windowTex != null) com.kalenjohnson.chronoduo.ChronoAssets.setWindowTex(windowTex.bitmap, windowTex.inset);
                    if (monsterNames != null) com.kalenjohnson.chronoduo.ChronoAssets.setMonsterNames(monsterNames);
                    if (techNames != null) com.kalenjohnson.chronoduo.ChronoAssets.setTechNames(techNames);
                    if (itemNames != null) com.kalenjohnson.chronoduo.ChronoAssets.setItemNames(itemNames);
                    if (itemCategoryNames != null) com.kalenjohnson.chronoduo.ChronoAssets.setItemCategoryNames(itemCategoryNames);
                    if (battleMessages != null) com.kalenjohnson.chronoduo.ChronoAssets.setBattleMessages(battleMessages);
                    if (monsterFlags != null) com.kalenjohnson.chronoduo.ChronoAssets.setMonsterFlags(monsterFlags);
                    if (techMp != null) com.kalenjohnson.chronoduo.ChronoAssets.setTechMpTable(techMp);
                });
            } catch (Exception e) {
                Log.w(TAG, "companion asset extraction failed", e);
            }
        }, "ChronoResExtract").start();
    }

    /**
     * Refreshes the disk-backed texture-replacement cache from
     * {@code <externalFilesDir>/orig_art/*.png} and
     * {@code <filesDir>/orig_art/*.png} (original 1x pixel art, pixel-doubled
     * back up to the game's shipped texture size) and loads the result into
     * the native glTexImage2D hook -- see {@link
     * com.kalenjohnson.chronoduo.OrigArtCache#refresh}. Runs off the main
     * thread (file IO, PNG decode, resources.bin extraction for anything
     * that changed since the last refresh); {@code OrigArtCache.refresh}
     * itself is synchronized so overlapping calls from boot and the
     * settings toggle serialize rather than race. Both directories are
     * scanned so files can be dropped in either without a rebuild (adb push
     * to externalFilesDir; on-device import tooling could use filesDir);
     * when both hold a same-named file, filesDir's (scanned second) wins,
     * matching ChronoAssets.getAreaMap's own filesDir-first-else-external
     * convention for user-local overrides elsewhere in this app.
     */
    private void scanOrigArtReplacements() {
        File ext = getExternalFilesDir(null);
        final File extDir = new File(ext != null ? ext : getFilesDir(), "orig_art");
        final File filesDir = new File(getFilesDir(), "orig_art");
        final Context appCtx = getApplicationContext();
        final android.content.res.AssetManager gameAssets = runtime.getChronoAssets();
        new Thread(() -> {
            com.kalenjohnson.chronoduo.OrigArtCache.refresh(
                    appCtx, gameAssets, new File[]{extDir, filesDir});
        }, "ChronoOrigArtScan").start();
    }

    /**
     * Runs the three original-art rebuild passes on one background thread,
     * writing into {@code <filesDir>/orig_art} -- exactly the directory
     * {@link #scanOrigArtReplacements} already scans -- and posting progress
     * to the bottom-screen panel's settings view throughout (see {@link
     * #updateOrigArtStatus}):
     * <ol>
     *   <li>{@link com.kalenjohnson.chronoduo.origart.OrigArtRebuilder#rebuildAll}
     *       -- the ~629 character sheets, rebuilt from their paired 1x
     *       {@code Game/chara/bmp} art;</li>
     *   <li>{@link com.kalenjohnson.chronoduo.origart.MapchipRebuilder#rebuildAll}
     *       -- the ~252 field chip sheet pairs (pages 0 and 1 each), rebuilt
     *       from the 4bpp cg banks + ChipTable + palette;</li>
     *   <li>{@link com.kalenjohnson.chronoduo.origart.WorldchipRebuilder#rebuildAll}
     *       -- the overworld: 7 {@code worldchip} sheet pairs (14 pages) from
     *       the world's own cg banks + Chip table + palette, plus the 14
     *       {@code Game/world/gif} object/backdrop sheets from their 1x
     *       {@code .bmp} siblings.</li>
     * </ol>
     * All three write into the same directory and are picked up by the same
     * replacement machinery, so one button covers them all; the phase label
     * ("sprites" / "field chips" / "overworld") is what tells the three
     * done/total counters apart on screen.
     *
     * <p>Called from the {@link PartyPanelView.SettingsHost} wired onto
     * SecondScreenManager in {@link #onCreate}, i.e. from a tap on the
     * panel's "Build original art" button. A no-op if a build is already in
     * flight ({@link #origArtBuilding}). On completion (success or failure)
     * re-runs {@link #scanOrigArtReplacements} so any newly written sheets
     * are picked up as texture replacements immediately, then posts the final
     * status. Catches Throwable, not just Exception -- like {@link
     * #importRomFromUri}, a background-thread Error would otherwise kill the
     * whole process instead of just failing this build.</p>
     */
    private void requestOrigArtBuild() {
        if (!origArtBuilding.compareAndSet(false, true)) return;
        updateOrigArtStatus(true, 0, 0, "sprites", null);
        final Context appCtx = getApplicationContext();
        final android.content.res.AssetManager gameAssets = runtime.getChronoAssets();
        final File outDir = new File(getFilesDir(), "orig_art");
        new Thread(() -> {
            try {
                com.kalenjohnson.chronoduo.origart.OrigArtRebuilder.rebuildAll(
                        appCtx, gameAssets, outDir,
                        (done, total, name) -> updateOrigArtStatus(true, done, total, "sprites", null),
                        () -> origArtBuildCancelled);
                if (!origArtBuildCancelled) {
                    updateOrigArtStatus(true, 0, 0, "field chips", null);
                    com.kalenjohnson.chronoduo.origart.MapchipRebuilder.rebuildAll(
                            appCtx, gameAssets, outDir,
                            (done, total, name) ->
                                    updateOrigArtStatus(true, done, total, "field chips", null),
                            () -> origArtBuildCancelled);
                }
                if (!origArtBuildCancelled) {
                    updateOrigArtStatus(true, 0, 0, "overworld", null);
                    com.kalenjohnson.chronoduo.origart.WorldchipRebuilder.rebuildAll(
                            appCtx, gameAssets, outDir,
                            (done, total, name) ->
                                    updateOrigArtStatus(true, done, total, "overworld", null),
                            () -> origArtBuildCancelled);
                }
                scanOrigArtReplacements();
                updateOrigArtStatus(false, 0, 0, null, null);
            } catch (Throwable t) {
                Log.e(TAG, "original-art rebuild failed", t);
                scanOrigArtReplacements();
                updateOrigArtStatus(false, 0, 0, null,
                        t.getMessage() != null ? t.getMessage() : t.toString());
            } finally {
                origArtBuilding.set(false);
            }
        }, "OrigArtRebuild").start();
    }

    /** Posts original-art rebuild progress/result to the bottom-screen panel's settings view, if one is currently showing -- a no-op otherwise. Safe from any thread. Mirrors {@link #updateImportStatus}. */
    private void updateOrigArtStatus(boolean building, int done, int total, String phase, String error) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            PartyPanelView panel = secondScreen != null ? secondScreen.getPanel() : null;
            if (panel != null) panel.setOrigArtStatus(building, done, total, phase, error);
        });
    }

    // --- mod loader (see com.kalenjohnson.chronoduo.mods.ModManager) --------

    /**
     * Runs {@link com.kalenjohnson.chronoduo.mods.ModManager#scan} on a
     * background thread (file IO) and posts the result to the bottom-screen
     * panel's settings view. Called at boot (see {@link #onCreate}) and
     * after every import/toggle.
     */
    private void scanMods() {
        if (modManager == null) return;
        final com.kalenjohnson.chronoduo.mods.ModManager mm = modManager;
        new Thread(() -> {
            mm.scan();
            updateModsStatus(false, null);
            refreshCompanionAssetsForModChange();
        }, "ChronoModScan").start();
    }

    /**
     * Re-runs {@link #extractCompanionAssets()} after any mod scan that
     * follows the boot scan (import, enable/disable toggle, catalog "Get") --
     * see {@link com.kalenjohnson.chronoduo.ChronoResources#extractModAware}.
     * The boot scan itself doesn't need this: it runs before
     * {@link #extractCompanionAssets()}'s first call in {@link #onCreate},
     * so that first decode already sees whatever mods were enabled at
     * launch. {@link com.kalenjohnson.chronoduo.ChronoAssets}'s setters all
     * call their listeners, so PartyPanelView repaints as soon as the
     * re-decoded bitmaps land -- no separate cache-invalidation step is
     * needed on the panel side.
     */
    private void refreshCompanionAssetsForModChange() {
        extractCompanionAssets();
    }

    /** Launches the SAF document picker so the user can pick a mod archive (.ctp/.zip). Mirrors {@link #launchSavePicker()}. */
    private void launchModPicker() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_MOD_IMPORT);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "no document picker available", e);
            updateModsStatus(false, "no file picker available on this device");
        }
    }

    /**
     * Imports {@code uri} (a user-picked mod archive from {@link
     * #launchModPicker()}) on a background thread, then pushes the
     * resulting mod list/status to the panel. Mirrors {@link
     * #importSaveFromUri}.
     *
     * <p>Like {@link #handleViewIntent}, if the picked file's display name
     * matches a catalog entry's {@code fileHint} it's imported under that
     * entry's id via {@link
     * com.kalenjohnson.chronoduo.mods.ModManager#importCatalogMod} instead
     * of the plain sanitized-display-name {@link
     * com.kalenjohnson.chronoduo.mods.ModManager#importArchive(android.net.Uri, android.content.ContentResolver)}
     * path -- so manually importing (or re-importing) a mod the catalog
     * already knows about lands exactly where a catalog "Get" would have
     * put it (dedup'd against any earlier import of the same download under
     * a different name -- see {@code ModManager#removeConflictingMods})
     * rather than showing as a second, uncatalogued "Imported mod" row.
     */
    private void importModFromUri(android.net.Uri uri) {
        if (modManager == null) return;
        updateModsStatus(true, null);
        final com.kalenjohnson.chronoduo.mods.ModManager mm = modManager;
        String displayName = com.kalenjohnson.chronoduo.mods.ModManager.queryDisplayName(getContentResolver(), uri);
        com.kalenjohnson.chronoduo.mods.ModCatalog.Entry match = null;
        if (displayName != null) {
            String lower = displayName.toLowerCase(java.util.Locale.ROOT);
            for (com.kalenjohnson.chronoduo.mods.ModCatalog.Entry e : modCatalog) {
                if (e.fileHint != null && lower.contains(e.fileHint.toLowerCase(java.util.Locale.ROOT))) {
                    match = e;
                    break;
                }
            }
        }
        final com.kalenjohnson.chronoduo.mods.ModCatalog.Entry finalMatch = match;
        new Thread(() -> {
            try {
                com.kalenjohnson.chronoduo.mods.ModManager.setGameArchiveSource(gameArchiveSourceFactory());
                com.kalenjohnson.chronoduo.mods.ModManager.ImportResult result;
                if (finalMatch != null) {
                    try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new java.io.IOException("could not open " + uri);
                        result = mm.importCatalogMod(in, finalMatch.id, finalMatch.fileHint, "local:" + uri,
                                this::updateModsProgress);
                    }
                } else {
                    result = mm.importArchive(uri, getContentResolver(), this::updateModsProgress);
                }
                logImportResult(result);
                updateModsStatus(false, describeImportResult(result), null);
                refreshCompanionAssetsForModChange();
            } catch (Throwable t) {
                Log.e(TAG, "mod import failed", t);
                updateModsStatus(false, t.getMessage() != null ? t.getMessage() : t.toString());
            }
        }, "ChronoModImport").start();
    }

    /**
     * Logs a one-line summary of a completed import (e.g. "Imported 33 mods
     * from Chrono Trigger Pixel Demaster (1 enabled)" for a split
     * multi-archive download -- see {@link
     * com.kalenjohnson.chronoduo.mods.ModManager#extractZip} -- or nothing
     * for the common single-mod case). {@link PartyPanelView}'s Mods page
     * already reflects the result via its mod list, so this is
     * informational only, not surfaced as a status/error message.
     */
    private static void logImportResult(com.kalenjohnson.chronoduo.mods.ModManager.ImportResult result) {
        if (result == null || result.modNames.size() <= 1) return;
        Log.i(TAG, "mods: imported " + result.modNames.size() + " mods from " + result.downloadName
                + " (" + result.enabledCount + " enabled)");
    }

    /**
     * One-line human summary of a completed import (e.g. "Imported 2 mods (1
     * enabled)"), for the settings panel's status line -- unlike {@link
     * #logImportResult}, which only logs the multi-archive case, this always
     * returns a line so the common single-mod import stops finishing
     * silently on the Mods page. Passed through {@link #updateModsStatus}'s
     * neutral {@code message} parameter (never {@code error}), so it renders
     * in the panel's normal body color with no "error:" prefix -- see {@link
     * PartyPanelView#setModsStatus}.
     */
    private static String describeImportResult(com.kalenjohnson.chronoduo.mods.ModManager.ImportResult result) {
        if (result == null) return null;
        int n = result.modNames.size();
        return "Imported " + n + (n == 1 ? " mod" : " mods") + " (" + result.enabledCount + " enabled)";
    }

    /** Flips a mod's enabled state via {@link com.kalenjohnson.chronoduo.mods.ModManager#setEnabled} on a background thread, then pushes the result. Called from the settings screen's per-row toggle button. */
    private void setModEnabled(String name, boolean enabled) {
        if (modManager == null) return;
        final com.kalenjohnson.chronoduo.mods.ModManager mm = modManager;
        new Thread(() -> {
            mm.setEnabled(name, enabled);
            updateModsStatus(false, null);
            refreshCompanionAssetsForModChange();
        }, "ChronoModToggle").start();
    }

    /** Applies a single-choice option selection within a multi-.ctp download's option group via {@link com.kalenjohnson.chronoduo.mods.ModManager#selectOption} on a background thread, then pushes the result. Called from the settings screen's Mods page option-row cycle button (see {@link PartyPanelView.SettingsHost#onModOptionSelected}). */
    private void setModOption(String group, String optionTitle, String dirOrNull) {
        if (modManager == null) return;
        final com.kalenjohnson.chronoduo.mods.ModManager mm = modManager;
        new Thread(() -> {
            mm.selectOption(group, optionTitle, dirOrNull);
            updateModsStatus(false, null);
            refreshCompanionAssetsForModChange();
        }, "ChronoModOption").start();
    }

    /** Reorders a mod's priority via {@link com.kalenjohnson.chronoduo.mods.ModManager#moveMod} on a background thread, then pushes the result. Called from the settings screen's Mods page ▲/▼ buttons. */
    private void moveMod(String name, boolean up) {
        if (modManager == null) return;
        final com.kalenjohnson.chronoduo.mods.ModManager mm = modManager;
        new Thread(() -> {
            mm.moveMod(name, up);
            updateModsStatus(false, null);
            refreshCompanionAssetsForModChange();
        }, "ChronoModMove").start();
    }

    /** Same as the 3-arg overload with no success message -- the common idle/importing/error case. */
    private void updateModsStatus(boolean importing, String error) {
        updateModsStatus(importing, null, error);
    }

    /**
     * Forwards a {@link com.kalenjohnson.chronoduo.mods.ModManager.ProgressCallback}
     * tick (see {@link com.kalenjohnson.chronoduo.mods.ModManager#extractZip}
     * et al -- a multi-GB 7z/zip/RAR mod, e.g. a 4K FMV pack, can take a
     * while) to the settings panel's status line via {@link
     * #updateModsStatus}, throttled upstream to ~2/s by {@code ModManager}
     * already. {@code totalBytes <= 0} means the extractor doesn't know a
     * total ahead of time (a plain {@link java.util.zip.ZipInputStream}
     * can't without a first pass) -- shown as an entry count instead of a
     * percentage.
     */
    private void updateModsProgress(long processed, long totalBytes) {
        String msg = totalBytes > 0
                ? "Extracting... " + Math.min(100, (int) (processed * 100 / totalBytes)) + "%"
                : "Extracting... " + processed + (processed == 1 ? " file" : " files");
        updateModsStatus(true, msg, null);
    }

    /**
     * Builds a {@link com.kalenjohnson.chronoduo.mods.ModManager.GameArchiveSourceFactory}
     * over the game's own {@code resources.bin} asset, for {@link
     * com.kalenjohnson.chronoduo.mods.ModManager}'s resources.bin-overlay
     * import step (see {@code ArchiveOverlayImporter} -- a mod that ships a
     * full ARC1 repack, e.g. "Orchestral Wonders", needs to diff against the
     * real archive). Registered fresh before every mod-import call site
     * (cheap -- just wraps {@code runtime}) rather than once at boot, since
     * {@link #onCreate} isn't among the methods this class may edit. Mirrors
     * {@code ChronoResources#readRegion}'s open-the-whole-asset-and-skip
     * approach exactly (simple and always correct, at the cost of not being
     * true random access) -- reusing {@code ChronoResources} itself isn't an
     * option since its table/cache are keyed to the always-unmodded archive.
     */
    private com.kalenjohnson.chronoduo.mods.ModManager.GameArchiveSourceFactory gameArchiveSourceFactory() {
        final android.content.res.AssetManager ga = runtime != null ? runtime.getChronoAssets() : null;
        if (ga == null) return () -> null;
        return () -> new com.kalenjohnson.chronoduo.mods.ArchiveOverlayImporter.RegionSource() {
            @Override
            public byte[] readRaw(long offset, int length) throws java.io.IOException {
                try (java.io.InputStream in = ga.open("resources.bin")) {
                    long toSkip = offset;
                    while (toSkip > 0) {
                        long skipped = in.skip(toSkip);
                        if (skipped <= 0) {
                            if (in.read() < 0) throw new java.io.IOException("unexpected EOF while skipping");
                            toSkip--;
                        } else {
                            toSkip -= skipped;
                        }
                    }
                    byte[] buf = new byte[length];
                    int off = 0;
                    while (off < buf.length) {
                        int n = in.read(buf, off, buf.length - off);
                        if (n < 0) throw new java.io.IOException("unexpected EOF");
                        off += n;
                    }
                    return buf;
                }
            }
        };
    }

    /** Posts the current mod list and an optional neutral status/error message to the bottom-screen panel's settings view, if one is currently showing -- a no-op otherwise. Safe from any thread. Mirrors {@link #updateOrigArtStatus}. {@code message} is shown in the panel's normal body color (no prefix); {@code error} is shown red with an "error: " prefix -- see {@link PartyPanelView#setModsStatus}. */
    private void updateModsStatus(boolean importing, String message, String error) {
        final java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.ModInfo> mods =
                modManager != null ? modManager.lastMods() : java.util.Collections.emptyList();
        final java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.ModGroup> groups =
                modManager != null ? modManager.groups().groups : java.util.Collections.emptyList();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            PartyPanelView panel = secondScreen != null ? secondScreen.getPanel() : null;
            if (panel == null) return;
            panel.setModsList(mods);
            panel.setModGroups(groups);
            panel.setModsStatus(importing, message, error);
        });
    }

    /**
     * {@link SecondScreenManager.PanelListener} callback: runs whenever
     * {@link SecondScreenManager} builds a fresh {@link PartyPanelView} (the
     * initial one, and any later one a system-initiated Presentation
     * teardown/recreate produces -- see that class's doc). Re-pushes the
     * Mods page's list/catalog state, which is otherwise only pushed at
     * scan/import/toggle or catalog-load time and would sit at its
     * construction-time empty default (and clears any stale importing/error
     * flag from before the recreate, which can't still be meaningful for a
     * panel that didn't exist while it was set) -- see the {@code
     * setPanelListener} call site in {@link #onCreate} for what's
     * deliberately NOT replayed. Always runs on the main thread (called
     * synchronously from {@link SecondScreenManager#show}, itself only ever
     * invoked from {@code onResume}/its own main-thread handler posts).
     */
    private void onSecondScreenPanelAttached(PartyPanelView panel) {
        panel.setModCatalog(modCatalog);
        panel.setModsList(modManager != null ? modManager.lastMods() : java.util.Collections.emptyList());
        panel.setModGroups(modManager != null ? modManager.groups().groups : java.util.Collections.emptyList());
        panel.setModsStatus(false, null, null);
    }

    // --- curated mod catalog (see com.kalenjohnson.chronoduo.mods.ModCatalog) ----------

    /** Loads the mod catalog in the background (bundled/cached/freshly-fetched -- see {@link com.kalenjohnson.chronoduo.mods.ModCatalog#loadAsync}) and pushes it to {@link #modCatalog} plus the settings panel once it's ready. Called once from {@link #onCreate}. */
    private void loadModCatalog() {
        com.kalenjohnson.chronoduo.mods.ModCatalog.loadAsync(this, entries -> {
            modCatalog = entries;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                PartyPanelView panel = secondScreen != null ? secondScreen.getPanel() : null;
                if (panel != null) panel.setModCatalog(entries);
            });
        });
    }

    private com.kalenjohnson.chronoduo.mods.ModCatalog.Entry findCatalogEntry(String id) {
        for (com.kalenjohnson.chronoduo.mods.ModCatalog.Entry e : modCatalog) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }

    /**
     * A catalog row's "Get" button was tapped (see {@code
     * PartyPanelView.SettingsHost#requestModGet}). If the entry has a direct
     * {@code download} URL, downloads and imports it straight away (flow
     * 3a); otherwise (the common Nexus case) opens its {@code page} in the
     * bottom screen's in-app WebView so the user can log in / hit the
     * in-page Download button, and waits for that WebView's {@code
     * DownloadListener} to fire (flow 3b -- see {@link #openModWebView}).
     */
    private void requestModGet(String id) {
        com.kalenjohnson.chronoduo.mods.ModCatalog.Entry entry = findCatalogEntry(id);
        if (entry == null) {
            Log.w(TAG, "mods: requestModGet for unknown catalog id " + id);
            return;
        }
        if (entry.download != null && !entry.download.isEmpty()) {
            downloadAndImportMod(entry, entry.download, null, null, null, null);
        } else {
            openModWebView(entry);
        }
    }

    /** Opens {@code entry.page} in the bottom screen's mod WebView overlay (see {@link com.kalenjohnson.chronoduo.SecondScreenPresentation#openModWebView}) and wires its download callback into {@link #downloadAndImportMod}. No-op (with a status message) if no second-screen Presentation is currently showing. */
    private void openModWebView(com.kalenjohnson.chronoduo.mods.ModCatalog.Entry entry) {
        com.kalenjohnson.chronoduo.SecondScreenPresentation presentation =
                secondScreen != null ? secondScreen.getPresentation() : null;
        if (presentation == null) {
            updateModsStatus(false, "no second screen available to open " + entry.name);
            return;
        }
        presentation.openModWebView(entry.page, new com.kalenjohnson.chronoduo.SecondScreenPresentation.ModWebViewHost() {
            @Override public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                                   String mimeType, long contentLength, String cookie) {
                com.kalenjohnson.chronoduo.SecondScreenPresentation p =
                        secondScreen != null ? secondScreen.getPresentation() : null;
                if (p != null) p.setWebViewStatus("Downloading " + entry.name + "...");
                String suggested = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                Runnable closeWebView = () -> {
                    com.kalenjohnson.chronoduo.SecondScreenPresentation p2 =
                            secondScreen != null ? secondScreen.getPresentation() : null;
                    if (p2 != null) p2.closeModWebView();
                };
                downloadAndImportMod(entry, url, cookie, userAgent, suggested, closeWebView);
            }
            @Override public void onClosed() {
                // Nothing to clean up here -- the mod-get itself (if a
                // download did start) runs independently of the overlay's
                // lifetime; its own status lands via updateModsStatus.
            }
        });
    }

    /**
     * Downloads {@code url} (an entry's direct {@code download}, or a URL
     * handed to us by the mod WebView's {@code DownloadListener}) to a temp
     * file under {@link #getCacheDir()} on a background thread, then imports
     * it under {@code entry.id} and enables it (see {@link
     * com.kalenjohnson.chronoduo.mods.ModManager#importCatalogMod}), posting
     * progress/result through {@link #updateModsStatus} the same way every
     * other mod action here does. {@code cookie}/{@code userAgent} are
     * forwarded as request headers when non-null/non-empty (a WebView
     * download's cookie header only matters for same-origin URLs -- a
     * pre-signed CDN download URL won't have one, which is fine). {@code
     * onDownloadComplete}, if non-null, runs on the main thread once the
     * bytes are fully down (success or failure) -- used to close the mod
     * WebView overlay per the spec ("on completion close the WebView,
     * import...").
     */
    private void downloadAndImportMod(com.kalenjohnson.chronoduo.mods.ModCatalog.Entry entry, String url,
                                       String cookie, String userAgent, String suggestedName,
                                       Runnable onDownloadComplete) {
        if (modManager == null) return;
        updateModsStatus(true, null);
        final com.kalenjohnson.chronoduo.mods.ModManager mm = modManager;
        new Thread(() -> {
            File tmp = null;
            Throwable failure = null;
            try {
                tmp = File.createTempFile("mod_", ".dl", getCacheDir());
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
                if (userAgent != null && !userAgent.isEmpty()) conn.setRequestProperty("User-Agent", userAgent);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code + " downloading " + entry.name);
                }
                try (java.io.InputStream in = conn.getInputStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            } catch (Throwable t) {
                failure = t;
            }
            if (onDownloadComplete != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(onDownloadComplete);
            }
            if (failure == null) {
                try {
                    String name = suggestedName != null ? suggestedName : url;
                    if (!looksLikeModArchive(name)) {
                        // No longer a hard requirement -- extractArchiveFileInto
                        // sniffs magic bytes now (zip/7z/RAR4), not the
                        // filename/extension a Nexus download happens to have
                        // (e.g. a .7z served with a misleading .bin name) --
                        // still worth a log line for anything genuinely odd.
                        Log.i(TAG, "mods: '" + name + "' has no .ctp/.zip/.7z extension -- importing by content anyway");
                    }
                    com.kalenjohnson.chronoduo.mods.ModManager.setGameArchiveSource(gameArchiveSourceFactory());
                    com.kalenjohnson.chronoduo.mods.ModManager.ImportResult result = mm.importCatalogMod(
                            tmp, entry.id, entry.fileHint, entry.page != null ? entry.page : url,
                            this::updateModsProgress);
                    // extractZip already picks the right default-enabled
                    // sub-mod(s) fresh on every import (see its class doc) --
                    // no need to force-enable entry.id, which for a
                    // multi-archive download (e.g. Pixel Demaster) isn't
                    // even a real mod directory any more, just the shared
                    // "<id> - <option>" name prefix.
                    logImportResult(result);
                    updateModsStatus(false, describeImportResult(result), null);
                    refreshCompanionAssetsForModChange();
                } catch (Throwable t) {
                    failure = t;
                }
            }
            if (failure != null) {
                Log.e(TAG, "mod get failed for " + entry.id, failure);
                updateModsStatus(false, failure.getMessage() != null ? failure.getMessage() : failure.toString());
            }
            if (tmp != null) tmp.delete();
        }, "ChronoModGet").start();
    }

    private static boolean looksLikeModArchive(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".ctp") || lower.endsWith(".zip");
    }

    /**
     * Handles a {@code .ctp} VIEW intent (see the manifest's intent-filters,
     * {@link #onCreate}'s {@code handleViewIntent(getIntent())}, and {@link
     * #onNewIntent}): imports the file under a catalog id if its display
     * name matches a catalog entry's {@code fileHint} (so it lands exactly
     * where a catalog "Get" would have put it, and shows as installed on the
     * Mods page), otherwise falls back to the normal sanitized-display-name
     * import (same as the "Import file..." picker). No-op for any other
     * intent (action/data), or before {@link #modManager} exists.
     */
    private static final String MODS_PREFS = "chronoduo_mods";
    private static final String PREF_LAST_CTP_URI = "last_ctp_uri";

    private void handleViewIntent(android.content.Intent intent) {
        if (intent == null || !android.content.Intent.ACTION_VIEW.equals(intent.getAction())) return;
        final android.net.Uri uri = intent.getData();
        if (uri == null || modManager == null) return;
        // singleTask means a cold relaunch (process killed, then reopened
        // from Recents) calls onCreate again with the SAME launching Intent
        // -- ActivityManager persists it independent of this process, so an
        // in-memory "consume the intent" flag wouldn't survive the restart.
        // Without this check, every such relaunch would silently re-import
        // and re-enable the mod, undoing a manual "Off" toggle. A real
        // repeat request (the user re-opens the same .ctp on purpose) is
        // rare enough that "already imported, toggle it back on in Mods if
        // you meant to re-run it" is an acceptable trade-off for v1.
        android.content.SharedPreferences prefs = getSharedPreferences(MODS_PREFS, MODE_PRIVATE);
        String uriStr = uri.toString();
        if (uriStr.equals(prefs.getString(PREF_LAST_CTP_URI, null))) {
            Log.i(TAG, "mods: ignoring already-handled .ctp intent for " + uri);
            return;
        }
        prefs.edit().putString(PREF_LAST_CTP_URI, uriStr).apply();
        final com.kalenjohnson.chronoduo.mods.ModManager mm = modManager;
        String displayName = com.kalenjohnson.chronoduo.mods.ModManager.queryDisplayName(getContentResolver(), uri);
        com.kalenjohnson.chronoduo.mods.ModCatalog.Entry match = null;
        if (displayName != null) {
            String lower = displayName.toLowerCase(java.util.Locale.ROOT);
            for (com.kalenjohnson.chronoduo.mods.ModCatalog.Entry e : modCatalog) {
                if (e.fileHint != null && lower.contains(e.fileHint.toLowerCase(java.util.Locale.ROOT))) {
                    match = e;
                    break;
                }
            }
        }
        final com.kalenjohnson.chronoduo.mods.ModCatalog.Entry finalMatch = match;
        updateModsStatus(true, null);
        new Thread(() -> {
            try {
                com.kalenjohnson.chronoduo.mods.ModManager.setGameArchiveSource(gameArchiveSourceFactory());
                com.kalenjohnson.chronoduo.mods.ModManager.ImportResult result;
                if (finalMatch != null) {
                    try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new java.io.IOException("could not open " + uri);
                        result = mm.importCatalogMod(in, finalMatch.id, finalMatch.fileHint, "local:" + uri,
                                this::updateModsProgress);
                    }
                    // See downloadAndImportMod's matching comment: extractZip
                    // already establishes the right enabled state per import.
                } else {
                    result = mm.importArchive(uri, getContentResolver(), this::updateModsProgress);
                }
                logImportResult(result);
                updateModsStatus(false, describeImportResult(result), null);
                refreshCompanionAssetsForModChange();
            } catch (Throwable t) {
                Log.e(TAG, ".ctp import failed for " + uri, t);
                updateModsStatus(false, t.getMessage() != null ? t.getMessage() : t.toString());
            }
        }, "ChronoModCtpImport").start();
    }

    // Extension/menu_win.png is a 512x512 sheet of pre-baked DS-style window
    // panels at various fixed sizes (packed, not tiled). The largest one —
    // a beveled steel/navy panel with a black outline and a light bevel
    // highlight line that resolves into flat noise fill by ~16px in from
    // each edge — sits at (198,134)-(500,304) in sheet pixels; that region
    // is cropped out here so ChronoAssets/PartyPanelView can 9-slice it
    // directly (bitmap origin becomes the panel's own top-left).
    //
    // All of the crop rects below (this one and minimap_mark.png's tiles)
    // are pixel coordinates measured against the ORIGINAL sheet size. A mod
    // can ship a differently-sized replacement (e.g. a higher-res face.png
    // or menu_win.png) that keeps the same relative layout, so every rect is
    // scaled by (actual sheet size / original sheet size) before use rather
    // than applied as literal pixels -- see PartyPanelView#faceTileRect for
    // the same treatment of the portrait grid.
    private static final int WIN_TEX_SHEET_W = 512, WIN_TEX_SHEET_H = 512;
    private static final int WIN_TEX_L = 198, WIN_TEX_T = 134, WIN_TEX_R = 500, WIN_TEX_B = 304;

    /** {@link #cropWindowTexture}'s result: the cropped 9-slice source plus the corner/edge inset scaled to match (see {@link com.kalenjohnson.chronoduo.ChronoAssets#WINDOW_TEX_INSET_DEFAULT}). */
    private static final class WindowTexResult {
        final android.graphics.Bitmap bitmap;
        final int inset;
        WindowTexResult(android.graphics.Bitmap bitmap, int inset) {
            this.bitmap = bitmap;
            this.inset = inset;
        }
    }

    private static WindowTexResult cropWindowTexture(File f) {
        android.graphics.Bitmap sheet = decodeBitmap(f);
        if (sheet == null) return null;
        float scaleX = sheet.getWidth() / (float) WIN_TEX_SHEET_W;
        float scaleY = sheet.getHeight() / (float) WIN_TEX_SHEET_H;
        int l = Math.round(WIN_TEX_L * scaleX);
        int t = Math.round(WIN_TEX_T * scaleY);
        int r = Math.min(Math.round(WIN_TEX_R * scaleX), sheet.getWidth());
        int b = Math.min(Math.round(WIN_TEX_B * scaleY), sheet.getHeight());
        if (r <= l || b <= t) {
            Log.w(TAG, "menu_win.png smaller than expected, skipping window texture crop");
            return null;
        }
        try {
            android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(sheet, l, t, r - l, b - t);
            // Same beveled-border thickness as the un-scaled original (16px
            // of a 302x170 crop), scaled by the same factor as the crop
            // itself so a higher-res mod's border reads at the same relative
            // thickness instead of looking thin (or, upscaled the other way,
            // blown out).
            int inset = Math.max(1, Math.round(
                    com.kalenjohnson.chronoduo.ChronoAssets.WINDOW_TEX_INSET_DEFAULT * ((scaleX + scaleY) / 2f)));
            return new WindowTexResult(cropped, inset);
        } catch (Exception e) {
            Log.w(TAG, "window texture crop failed", e);
            return null;
        }
    }

    // Game/common/minimap_mark.png is a 48x16 strip of three 16x16 tiles:
    // tile 0 is fully transparent (unused spacer), tile 1 is a green ring
    // with a pale-blue fill, tile 2 is a yellow ring with a pale-yellow
    // fill. Read as a two-color map-pin legend (e.g. visited/current vs.
    // other point of interest), tile 1's cooler blue center reads as the
    // "you are here" marker, so that's the one tile we draw — the old code
    // drew the whole 48x16 sheet in one rect, which showed up on-screen as
    // three stray circles side by side.
    private static final int MARK_SHEET_W = 48, MARK_SHEET_H = 16;

    private static android.graphics.Bitmap cropMarkerTile(File f) {
        android.graphics.Bitmap sheet = decodeBitmap(f);
        if (sheet == null) return null;
        float scaleX = sheet.getWidth() / (float) MARK_SHEET_W;
        float scaleY = sheet.getHeight() / (float) MARK_SHEET_H;
        int x = Math.round(16 * scaleX), y = Math.round(0 * scaleY);
        int w = Math.round(16 * scaleX), h = Math.round(16 * scaleY);
        if (sheet.getWidth() < x + w || sheet.getHeight() < y + h) {
            Log.w(TAG, "minimap_mark.png smaller than expected, skipping marker tile crop");
            return null;
        }
        try {
            return android.graphics.Bitmap.createBitmap(sheet, x, y, w, h);
        } catch (Exception e) {
            Log.w(TAG, "marker tile crop failed", e);
            return null;
        }
    }

    // Tile 2 of the same 48x16 strip: the yellow ring the game draws for the
    // Epoch (WorldMap::markMiniMap's "silverd" child, rect (16,0,8,8) at 2x
    // content scale = the 16x16 cell at x=32). Drawn on the overworld panel
    // only while the Epoch is parked in the era being shown.
    private static android.graphics.Bitmap cropEpochTile(File f) {
        android.graphics.Bitmap sheet = decodeBitmap(f);
        if (sheet == null) return null;
        float scaleX = sheet.getWidth() / (float) MARK_SHEET_W;
        float scaleY = sheet.getHeight() / (float) MARK_SHEET_H;
        int x = Math.round(32 * scaleX), y = Math.round(0 * scaleY);
        int w = Math.round(16 * scaleX), h = Math.round(16 * scaleY);
        if (sheet.getWidth() < x + w || sheet.getHeight() < y + h) {
            Log.w(TAG, "minimap_mark.png smaller than expected, skipping Epoch tile crop");
            return null;
        }
        try {
            return android.graphics.Bitmap.createBitmap(sheet, x, y, w, h);
        } catch (Exception e) {
            Log.w(TAG, "Epoch tile crop failed", e);
            return null;
        }
    }

    // Plain Java byte copy used by the battle-snapshot archiver above.
    private static void copyFile(File src, File dst) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (java.io.IOException e) {
            Log.w(TAG, "battle snapshot copy failed: " + src + " -> " + dst, e);
        }
    }

    private static android.graphics.Bitmap decodeBitmap(File f) {
        if (f == null) return null;
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b == null) Log.w(TAG, "failed to decode bitmap: " + f);
        return b;
    }

    /**
     * Reads a Localize/en/msg/*.txt name table as UTF-8, CRLF-delimited (a
     * lone LF is tolerated too); line index (0-based) == the table's own id
     * space -- monster.txt line 146 = "Gato", tech.txt/item.txt line index ==
     * tech/item id (same ids as PartySnapshot.ListRow.id). Best-effort: any
     * failure is logged and returns null, leaving the caller's numeric-id
     * fallback ("Enemy N", "#id") in place.
     */
    private static String[] readNameTable(File f) {
        if (f == null) return null;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            return text.split("\r\n|\n");
        } catch (Exception e) {
            Log.w(TAG, "failed to read name table: " + f, e);
            return null;
        }
    }

    /**
     * Reads Localize/en/msg/sfc_item.txt: a CSV of
     * {@code MSG_SFC_ITEM_<CATEGORY>_<NNN>,<name>} lines (category names in
     * file order: WEAPON, ARMOR, HELMET, ACCSESARY, USEITEM, IMPORTANT --
     * sic on ACCSESARY), one line per item, and returns it as a map keyed
     * {@code "<CATEGORY>_<NNN>"} -> name. Used by
     * {@link com.kalenjohnson.chronoduo.ChronoAssets#getItemName(int)} to
     * resolve the battle item-list's encoded row id
     * ({@code (category << 14) | indexWithinCategory}), which item.txt's
     * flat line-index table doesn't cover. Best-effort, like
     * {@link #readNameTable}: any failure is logged and returns null.
     */
    private static java.util.Map<String, String> readSfcItemTable(File f) {
        if (f == null) return null;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = text.split("\r\n|\n");
            final String prefix = "MSG_SFC_ITEM_";
            java.util.Map<String, String> map = new java.util.HashMap<>();
            for (String line : lines) {
                if (line.isEmpty()) continue;
                int comma = line.indexOf(',');
                if (comma < 0) continue;
                String key = line.substring(0, comma);
                if (!key.startsWith(prefix)) continue;
                String name = line.substring(comma + 1);
                map.put(key.substring(prefix.length()), name);
            }
            return map;
        } catch (Exception e) {
            Log.w(TAG, "failed to read sfc_item table: " + f, e);
            return null;
        }
    }

    /**
     * Builds the tech MP-cost table (index == tech id, same id space as
     * tech.txt / PartySnapshot.ListRow.id for tech rows) from two data
     * files, per the calibrated format writeup for this pair:
     * <p>
     * {@code Game/common/TechnicMpTable.dat}: u32 LE {@code count} (72)
     * header, then {@code count} single-byte solo MP values, index == tech
     * id -- but only meaningful for a tech's own "solo" MP; combo (dual/
     * triple) tech ids beyond this table's range have no slot here.
     * <p>
     * {@code Game/common/TechnicBaseDataTable.dat}: u32 LE {@code count}
     * (124) header, then {@code count} fixed 15-byte records (record index
     * == tech id); bytes 11/12/13 of each record hold up to 3 "component"
     * tech ids (0xFF = unused slot). A tech's real MP cost is the sum of
     * the solo table's value for each of its non-0xFF components -- this
     * one rule covers solo (one component), dual (two), and triple (three)
     * techs uniformly (verified: Delta Force id 102, comps [8,8,8] -> 24;
     * Aura Whirl id 57, comps [Aura 1, Cyclone 2] -> 3).
     * <p>
     * When a record has no valid (non-0xFF, in-range) component -- e.g. the
     * id-0 dummy row, or reserved padding past id 116 -- falls back to the
     * solo table entry at that same id when it exists (id &lt; 72), else -1
     * (unknown; see {@link com.kalenjohnson.chronoduo.ChronoAssets#getTechMp}).
     * Best-effort like {@link #readSfcItemTable}: any header/size surprise
     * (files missing, count/record-length arithmetic not exact) logs and
     * returns null rather than a partial/garbage table.
     */
    private static int[] readTechMpTable(File mpFile, File baseFile) {
        if (mpFile == null || baseFile == null) return null;
        try {
            byte[] mpRaw = java.nio.file.Files.readAllBytes(mpFile.toPath());
            byte[] baseRaw = java.nio.file.Files.readAllBytes(baseFile.toPath());
            if (mpRaw.length < 4 || baseRaw.length < 4) return null;

            int mpCount = u32le(mpRaw, 0);
            if (mpCount <= 0 || 4 + mpCount > mpRaw.length) return null;
            int[] soloMp = new int[mpCount];
            for (int i = 0; i < mpCount; i++) soloMp[i] = mpRaw[4 + i] & 0xFF;

            int recCount = u32le(baseRaw, 0);
            int recBytes = baseRaw.length - 4;
            if (recCount <= 0 || recBytes % recCount != 0) return null;
            int recLen = recBytes / recCount;
            if (recLen < 14) return null; // need at least bytes 0..13 of each record

            int[] techMp = new int[recCount];
            for (int id = 0; id < recCount; id++) {
                int off = 4 + id * recLen;
                int sum = 0;
                boolean any = false;
                for (int k = 11; k <= 13; k++) {
                    int c = baseRaw[off + k] & 0xff;
                    if (c == 0xFF) continue;
                    if (c >= soloMp.length) continue; // bounds-check: component id must be < 72
                    sum += soloMp[c];
                    any = true;
                }
                if (any) {
                    techMp[id] = sum;
                } else if (id < soloMp.length) {
                    techMp[id] = soloMp[id];
                } else {
                    techMp[id] = -1;
                }
            }
            return techMp;
        } catch (Exception e) {
            Log.w(TAG, "failed to read tech MP tables: " + mpFile + ", " + baseFile, e);
            return null;
        }
    }

    private static int u32le(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8
                | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    /**
     * Reads Game/battle/tblb/MonsterNameData.dat as a raw byte table: index
     * (0-based) == monster id, same ids as the actor block's +0x00 field and
     * monster.txt's line index. 0 = normal enemy; 255 = the game hides this
     * enemy's info in its own UI (bosses/event enemies -- Gato id 146 is
     * 255; all common early enemies are 0). Best-effort: any failure is
     * logged and returns null, leaving the hidden-HP feature disabled.
     */
    private static byte[] readRawBytes(File f) {
        if (f == null) return null;
        try {
            return java.nio.file.Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            Log.w(TAG, "failed to read monster flag table: " + f, e);
            return null;
        }
    }

    /**
     * Launches the Storage Access Framework document picker so the user can
     * pick their own Chrono Trigger DS ROM file (never bundled or fetched by
     * this app -- see PartyPanelView's settings-screen explanatory text).
     * Called from the {@link PartyPanelView.SettingsHost} wired onto
     * SecondScreenManager in {@link #onCreate}, i.e. from a tap on the
     * bottom-screen panel's "Import DS ROM..." button. The game keeps running
     * on the top screen while the picker activity is up (it simply covers
     * this activity, same as any other launched activity) -- nothing in
     * onPause/onResume below is picker-specific, so that path is unaffected.
     */
    private void launchRomPicker() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                new String[]{"application/octet-stream", "*/*"});
        try {
            startActivityForResult(intent, REQUEST_ROM_IMPORT);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "no document picker available", e);
            postImportError("no file picker available on this device");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ROM_IMPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            importRomFromUri(data.getData());
        } else if (requestCode == REQUEST_SAVE_IMPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            importSaveFromUri(data.getData());
        } else if (requestCode == REQUEST_MOD_IMPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            importModFromUri(data.getData());
        }
    }

    /**
     * A {@code .ctp} opened while the app is already running (singleTask, so
     * this fires instead of a second {@link #onCreate}) -- see the manifest's
     * VIEW intent-filters and {@link #handleViewIntent}. The launch-time case
     * (app not yet running) is handled once in {@link #onCreate} via {@code
     * handleViewIntent(getIntent())} instead, since onNewIntent doesn't fire
     * for a cold start.
     */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    /**
     * Opens {@code uri} (a user-picked ROM file from {@link #launchRomPicker})
     * read-only via a ParcelFileDescriptor, wraps it in a {@link
     * com.kalenjohnson.chronoduo.dsimport.SeekableSource} backed by
     * positional {@link java.nio.channels.FileChannel} reads, and runs {@link
     * com.kalenjohnson.chronoduo.dsimport.DsMapImporter#importRom} on a
     * background thread. Output is written to a temp directory first and
     * only renamed over {@code <filesDir>/ds_maps} on success, so a failed or
     * interrupted import leaves whatever maps were already there intact.
     * Progress and the final result are posted back to the bottom-screen
     * panel (if one is currently showing -- see {@link #updateImportStatus})
     * on the main thread throughout. The picked file descriptor is kept open
     * for the whole import and closed in a finally block.
     */
    private void importRomFromUri(android.net.Uri uri) {
        updateImportStatus(true, 0, 0, "opening", null);
        new Thread(() -> {
            android.os.ParcelFileDescriptor pfd = null;
            File unzippedTemp = null;
            java.io.RandomAccessFile unzippedRaf = null;
            File tempOut = null;
            try {
                pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) {
                    postImportError("could not open the selected file");
                    return;
                }
                final java.nio.channels.FileChannel channel =
                        new java.io.FileInputStream(pfd.getFileDescriptor()).getChannel();
                final long pfdLength = channel.size();

                com.kalenjohnson.chronoduo.dsimport.SeekableSource pfdSource = channelSource(channel, pfdLength);

                // Peek the first few bytes to tell a raw .nds apart from a zip (or an
                // unsupported archive format) before doing anything else with it -- this is
                // what a user picking a .zip full of a ROM used to skip, letting NitroRom read
                // garbage header offsets/sizes straight out of whatever bytes came first.
                byte[] sig = new byte[8];
                int sigGot = 0;
                while (sigGot < sig.length) {
                    int n = pfdSource.read(sigGot, sig, sigGot, sig.length - sigGot);
                    if (n <= 0) break;
                    sigGot += n;
                }

                com.kalenjohnson.chronoduo.dsimport.SeekableSource source;
                if (startsWith(sig, sigGot, ZIP_SIGNATURE)) {
                    updateImportStatus(true, 0, 0, "unzipping", null);
                    String entryName = findRomEntryInZip(uri);
                    if (entryName == null) {
                        postImportError("no .nds file found inside the zip");
                        return;
                    }
                    unzippedTemp = extractZipEntry(uri, entryName, getCacheDir());
                    unzippedRaf = new java.io.RandomAccessFile(unzippedTemp, "r");
                    source = randomAccessFileSource(unzippedRaf);
                } else if (startsWith(sig, sigGot, SEVENZ_SIGNATURE) || startsWith(sig, sigGot, RAR_SIGNATURE)) {
                    postImportError("only .nds or .zip is supported");
                    return;
                } else {
                    source = pfdSource;
                }

                long romLength = source.length();
                if (romLength < MIN_ROM_BYTES || romLength > MAX_ROM_BYTES) {
                    postImportError("file is " + (romLength / (1024 * 1024))
                            + " MB -- expected a Chrono Trigger DS ROM between 16 and 512 MB");
                    return;
                }

                File filesDir = getFilesDir();
                tempOut = new File(filesDir, "ds_maps_tmp");
                deleteRecursive(tempOut);
                if (!tempOut.mkdirs()) {
                    postImportError("could not create a working directory");
                    return;
                }

                com.kalenjohnson.chronoduo.dsimport.DsMapImporter.Progress progress =
                        new com.kalenjohnson.chronoduo.dsimport.DsMapImporter.Progress() {
                            @Override public void onMinimap(String base, int index, int total, boolean ok) {
                                updateImportStatus(true, index + 1, total, "maps", null);
                            }
                            @Override public void onCalibStatus(String message) {
                                updateImportStatus(true, 0, 0, message, null);
                            }
                        };

                com.kalenjohnson.chronoduo.dsimport.DsMapImporter.Result result =
                        com.kalenjohnson.chronoduo.dsimport.DsMapImporter.importRom(source, tempOut, progress);

                // importRom throws on hard failures; a result with no maps means
                // the file wasn't a usable Chrono Trigger DS ROM.
                if (result == null || result.minimapOk == 0) {
                    deleteRecursive(tempOut);
                    postImportError("no maps found -- is this the Chrono Trigger DS ROM?");
                    return;
                }

                File finalOut = new File(filesDir, "ds_maps");
                File backupOut = new File(filesDir, "ds_maps_prev");
                deleteRecursive(backupOut);
                boolean hadExisting = finalOut.exists();
                if (hadExisting && !finalOut.renameTo(backupOut)) {
                    deleteRecursive(tempOut);
                    postImportError("could not replace the existing maps");
                    return;
                }
                if (!tempOut.renameTo(finalOut)) {
                    if (hadExisting) backupOut.renameTo(finalOut); // best-effort restore
                    deleteRecursive(tempOut);
                    postImportError("could not install the new maps");
                    return;
                }
                deleteRecursive(backupOut);

                final com.kalenjohnson.chronoduo.dsimport.DsMapImporter.Result finalResult = result;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    com.kalenjohnson.chronoduo.AreaMapCalib.load(new File(finalOut, "area_calib.json"));
                    com.kalenjohnson.chronoduo.ChronoAssets.clearAreaMapCache();
                    updateImportStatus(false, finalResult.minimapOk, finalResult.minimapOk, null, null);
                });
            } catch (Throwable t) {
                // Catches Throwable, not just Exception: a corrupt/garbage-header ROM (e.g. a
                // zip that slipped past the checks above) can otherwise drive an allocation
                // request big enough to throw OutOfMemoryError, which an Exception-only catch
                // lets straight through -- and an uncaught Error on this background thread
                // kills the whole process instead of just failing the import.
                Log.e(TAG, "ROM import failed", t);
                postImportError(t.getMessage() != null ? t.getMessage() : t.toString());
            } finally {
                // No-op if importRom already renamed tempOut to finalOut (deleteRecursive
                // returns immediately when the path doesn't exist) -- but on any exception
                // path (including the new header-validation IOExceptions and the OOM catch
                // above) tempOut can still hold partial output, and this is the only place
                // that's guaranteed to run for all of them.
                if (tempOut != null) {
                    deleteRecursive(tempOut);
                }
                if (unzippedRaf != null) {
                    try {
                        unzippedRaf.close();
                    } catch (java.io.IOException e) {
                        Log.w(TAG, "failed to close unzipped ROM temp file", e);
                    }
                }
                if (unzippedTemp != null && !unzippedTemp.delete()) {
                    Log.w(TAG, "failed to delete unzipped ROM temp file: " + unzippedTemp);
                }
                if (pfd != null) {
                    try {
                        pfd.close();
                    } catch (java.io.IOException e) {
                        Log.w(TAG, "failed to close ROM file descriptor", e);
                    }
                }
            }
        }, "DsMapImport").start();
    }

    // --- SNES/DS save import (see com.kalenjohnson.chronoduo.saveimport) --

    /**
     * Launches the SAF document picker so the user can pick an SNES, DS or
     * Steam/PC Chrono Trigger save file. Mirrors {@link #launchRomPicker()}.
     */
    private void launchSavePicker() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                new String[]{"application/octet-stream", "*/*"});
        try {
            startActivityForResult(intent, REQUEST_SAVE_IMPORT);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "no document picker available", e);
            postSaveImportError("no file picker available on this device");
        }
    }

    /**
     * Reads {@code uri} (a user-picked SNES/DS save from {@link
     * #launchSavePicker()}) off the main thread, validates its size, and
     * parses it as either format with {@link
     * com.kalenjohnson.chronoduo.saveimport.SaveImporter#parseSaveFile}
     * (8192 bytes -&gt; SNES .srm; any other recognized DS wrapper size ->
     * DS .sav, REPORT.md #7). On success, hands off to {@link
     * #showSaveSlotPicker} on the main thread to run the rest of the flow
     * (slot pick -> destination pick -> optional overwrite confirm ->
     * background import), all via {@link android.app.AlertDialog} on the
     * top screen (no dual-screen handling needed for dialogs).
     */
    private void importSaveFromUri(android.net.Uri uri) {
        updateSaveImportStatus(true, null, false);
        new Thread(() -> {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    postSaveImportError("could not open the selected file");
                    return;
                }
                byte[] data = com.kalenjohnson.chronoduo.saveimport.SaveImporter.readAll(in);
                // Size gates SNES/DS; anything else is tried as a Steam/PC
                // save_NN.bin container inside parseSaveFile.
                com.kalenjohnson.chronoduo.saveimport.SaveImporter.ParsedSaveFile parsed;
                try {
                    parsed = com.kalenjohnson.chronoduo.saveimport.SaveImporter.parseSaveFile(data);
                } catch (RuntimeException e) {
                    postSaveImportError("could not parse save file: " + e.getMessage());
                    return;
                }
                boolean anyUsed = false;
                for (int i = 0; i < parsed.slotCount(); i++) {
                    if (parsed.slotUsedInFile(i)) { anyUsed = true; break; }
                }
                if (!anyUsed) {
                    postSaveImportError("no used save slots found in this file");
                    return;
                }
                final com.kalenjohnson.chronoduo.saveimport.SaveImporter.ParsedSaveFile finalParsed = parsed;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    updateSaveImportStatus(false, null, false);
                    showSaveSlotPicker(finalParsed);
                });
            } catch (Throwable t) {
                Log.e(TAG, "SNES/DS/Steam save import (read) failed", t);
                postSaveImportError(t.getMessage() != null ? t.getMessage() : t.toString());
            }
        }, "SaveRead").start();
    }

    /** First dialog: pick which used save slot to import (main thread). */
    private void showSaveSlotPicker(com.kalenjohnson.chronoduo.saveimport.SaveImporter.ParsedSaveFile parsed) {
        List<Integer> usedIndices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < parsed.slotCount(); i++) {
            if (!parsed.slotUsedInFile(i)) continue;
            usedIndices.add(i);
            labels.add("Slot " + (i + 1) + ": " + parsed.describe(i));
        }
        if (parsed.kind == com.kalenjohnson.chronoduo.saveimport.SaveImporter.ParsedSaveFile.Kind.PORT) {
            // A Steam/PC file is a single save: skip the slot question.
            showDestinationSlotPicker(parsed, 0);
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Import which save slot?")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    int slotIndex = usedIndices.get(which);
                    showDestinationSlotPicker(parsed, slotIndex);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Second dialog: pick a ChronoDuo destination menu slot 1..20 (main thread). */
    private void showDestinationSlotPicker(com.kalenjohnson.chronoduo.saveimport.SaveImporter.ParsedSaveFile parsed,
                                            int slotIndex) {
        File saveDir = getExternalFilesDir(null);
        if (saveDir == null) saveDir = getFilesDir();
        final File finalSaveDir = saveDir;
        String[] labels = new String[com.kalenjohnson.chronoduo.saveimport.SaveImporter.NUM_MENU_SLOTS];
        for (int i = 0; i < labels.length; i++) {
            boolean used = com.kalenjohnson.chronoduo.saveimport.SaveImporter.slotUsed(finalSaveDir, i);
            labels[i] = "Slot " + (i + 1) + " (" + (used ? "used" : "empty") + ")";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Import into which ChronoDuo slot?")
                .setItems(labels, (dialog, which) -> {
                    if (com.kalenjohnson.chronoduo.saveimport.SaveImporter.slotUsed(finalSaveDir, which)) {
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("Overwrite slot " + (which + 1) + "?")
                                .setMessage("Slot " + (which + 1) + " already has a save. Overwrite it with the imported save?")
                                .setPositiveButton("Overwrite", (d2, w2) -> runSaveImport(parsed, slotIndex, which, finalSaveDir))
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        runSaveImport(parsed, slotIndex, which, finalSaveDir);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Runs the actual conversion+write on a background thread, then posts the result. */
    private void runSaveImport(com.kalenjohnson.chronoduo.saveimport.SaveImporter.ParsedSaveFile parsed,
                                int slotIndex, int destSlot, File saveDir) {
        updateSaveImportStatus(true, null, false);
        new Thread(() -> {
            try {
                List<com.kalenjohnson.chronoduo.saveimport.SaveConverter.TemplateCandidate> templates =
                        loadSaveTemplates();
                String template = com.kalenjohnson.chronoduo.saveimport.SaveImporter.importSave(
                        parsed, slotIndex, destSlot, saveDir, templates, new java.security.SecureRandom());
                Log.i(TAG, "save imported into slot " + (destSlot + 1) + " using template " + template);
                updateSaveImportStatus(false,
                        "Imported into slot " + (destSlot + 1) + " -- return to the title screen and choose Load",
                        false);
            } catch (Throwable t) {
                Log.e(TAG, "save import failed", t);
                updateSaveImportStatus(false, t.getMessage() != null ? t.getMessage() : t.toString(), true);
            }
        }, "SaveImport").start();
    }

    /**
     * Loads every bundled {@code save_templates/*.bin} chapter template from
     * assets, decrypted, sorted by filename (matches the Python reference's
     * {@code sorted(glob.glob("steam/[0-9]*.bin"))} tie-breaking order in
     * {@code pick_template}).
     */
    private List<com.kalenjohnson.chronoduo.saveimport.SaveConverter.TemplateCandidate> loadSaveTemplates() throws java.io.IOException {
        // getAssets() is overridden to serve the GAME's APK (see above); the
        // templates live in our own APK, so go through the unoverridden path.
        AssetManager ownAssets = super.getAssets();
        String[] names = ownAssets.list("save_templates");
        if (names == null || names.length == 0) {
            throw new java.io.IOException("no save templates bundled with the app");
        }
        java.util.Arrays.sort(names);
        List<com.kalenjohnson.chronoduo.saveimport.SaveConverter.TemplateCandidate> out = new ArrayList<>();
        for (String name : names) {
            if (!name.endsWith(".bin")) continue;
            try (java.io.InputStream in = ownAssets.open("save_templates/" + name)) {
                byte[] data = com.kalenjohnson.chronoduo.saveimport.SaveImporter.readAll(in);
                byte[] payload = com.kalenjohnson.chronoduo.saveimport.CtContainer.decrypt(data);
                out.add(new com.kalenjohnson.chronoduo.saveimport.SaveConverter.TemplateCandidate(name, payload));
            }
        }
        return out;
    }

    private void postSaveImportError(String message) {
        updateSaveImportStatus(false, message != null ? message : "unknown error", true);
    }

    /** Posts SNES-save-import progress/result to the bottom-screen panel's settings view, if one is currently showing -- a no-op otherwise. Safe from any thread. Mirrors {@link #updateImportStatus}. */
    private void updateSaveImportStatus(boolean importing, String message, boolean error) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            PartyPanelView panel = secondScreen != null ? secondScreen.getPanel() : null;
            if (panel != null) panel.setSaveImportStatus(importing, message, error);
            // The settings screen may already have closed by the time the picker
            // returns, so the final result also goes up as a toast on the game screen.
            if (!importing && message != null) {
                android.widget.Toast.makeText(this, (error ? "Save import failed: " : "") + message,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }

    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] SEVENZ_SIGNATURE = {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
    private static final byte[] RAR_SIGNATURE = {'R', 'a', 'r', '!'};
    private static final long MIN_ROM_BYTES = 16L * 1024 * 1024;
    private static final long MAX_ROM_BYTES = 512L * 1024 * 1024;
    private static final long UNZIP_PROGRESS_STEP_BYTES = 4L * 1024 * 1024;

    private static boolean startsWith(byte[] buf, int bufLen, byte[] sig) {
        if (bufLen < sig.length) return false;
        for (int i = 0; i < sig.length; i++) {
            if (buf[i] != sig[i]) return false;
        }
        return true;
    }

    /** Wraps a {@link java.nio.channels.FileChannel} (backing the picked file's own
     * ParcelFileDescriptor) as a {@link com.kalenjohnson.chronoduo.dsimport.SeekableSource}
     * doing positional reads -- shared by the raw-.nds path and the zip signature peek. */
    private static com.kalenjohnson.chronoduo.dsimport.SeekableSource channelSource(
            java.nio.channels.FileChannel channel, long length) {
        return new com.kalenjohnson.chronoduo.dsimport.SeekableSource() {
            @Override
            public int read(long pos, byte[] dst, int off, int len) {
                try {
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(dst, off, len);
                    int total = 0;
                    while (buf.hasRemaining()) {
                        int n = channel.read(buf, pos + total);
                        if (n < 0) break;
                        total += n;
                    }
                    return total > 0 ? total : -1;
                } catch (java.io.IOException e) {
                    return -1;
                }
            }

            @Override
            public long length() {
                return length;
            }
        };
    }

    /** Wraps an already-open {@link java.io.RandomAccessFile} (over the unzipped temp file) as
     * a {@link com.kalenjohnson.chronoduo.dsimport.SeekableSource}. Synchronized because
     * RandomAccessFile's seek+read pair isn't atomic and NitroRom otherwise only ever reads
     * from a single thread, but this keeps the wrapper safe regardless. */
    private static com.kalenjohnson.chronoduo.dsimport.SeekableSource randomAccessFileSource(
            java.io.RandomAccessFile raf) throws java.io.IOException {
        final long length = raf.length();
        return new com.kalenjohnson.chronoduo.dsimport.SeekableSource() {
            @Override
            public synchronized int read(long pos, byte[] dst, int off, int len) {
                try {
                    raf.seek(pos);
                    return raf.read(dst, off, len);
                } catch (java.io.IOException e) {
                    return -1;
                }
            }

            @Override
            public long length() {
                return length;
            }
        };
    }

    /**
     * First streaming pass over the zip at {@code uri}: returns the name of the first entry
     * whose name ends with ".nds" (case-insensitive), or -- if there isn't one -- the first
     * non-directory entry over 8 MB, or null if neither is found. Doesn't extract anything;
     * {@link #extractZipEntry} does a second pass to copy out whichever name this returns.
     */
    private String findRomEntryInZip(android.net.Uri uri) throws java.io.IOException {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(in)) {
            String fallbackName = null;
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name != null && name.toLowerCase(java.util.Locale.US).endsWith(".nds")) {
                    return name;
                }
                if (fallbackName == null && entry.getSize() > 8L * 1024 * 1024) {
                    fallbackName = name;
                }
            }
            return fallbackName;
        }
    }

    /**
     * Second streaming pass over the zip at {@code uri}: copies the entry named
     * {@code entryName} (found by {@link #findRomEntryInZip}) out to a fresh temp file under
     * {@code destDir}, reporting progress via {@code updateImportStatus} roughly every 4 MB.
     * Caller owns the returned file (and must delete it once done with it).
     */
    private File extractZipEntry(android.net.Uri uri, String entryName, File destDir) throws java.io.IOException {
        File out = File.createTempFile("rom_import_", ".nds", destDir);
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(in);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            java.util.zip.ZipEntry entry;
            boolean found = false;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entryName.equals(entry.getName())) continue;
                found = true;

                long totalBytes = entry.getSize(); // -1 if unknown (streamed entry)
                int totalMB = totalBytes > 0 ? (int) ((totalBytes + 1024 * 1024 - 1) / (1024 * 1024)) : 0;

                byte[] buf = new byte[64 * 1024];
                long copied = 0;
                long nextReportAt = UNZIP_PROGRESS_STEP_BYTES;
                int n;
                while ((n = zis.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    copied += n;
                    if (copied >= nextReportAt) {
                        updateImportStatus(true, (int) (copied / (1024 * 1024)), totalMB, "unzipping", null);
                        nextReportAt += UNZIP_PROGRESS_STEP_BYTES;
                    }
                }
                updateImportStatus(true, (int) (copied / (1024 * 1024)),
                        totalMB > 0 ? totalMB : (int) (copied / (1024 * 1024)), "unzipping", null);
                break;
            }
            if (!found) {
                throw new java.io.IOException("could not find " + entryName + " in the zip on the second pass");
            }
        }
        return out;
    }

    private void postImportError(String message) {
        updateImportStatus(false, 0, 0, null, message != null ? message : "unknown error");
    }

    /** Posts import progress/result to the bottom-screen panel's settings view, if one is currently showing -- a no-op otherwise. Safe from any thread. */
    private void updateImportStatus(boolean importing, int done, int total, String stage, String error) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            PartyPanelView panel = secondScreen != null ? secondScreen.getPanel() : null;
            if (panel != null) panel.setImportStatus(importing, done, total, stage, error);
        });
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        f.delete();
    }

    private void showBootstrapError(Exception e) {
        TextView tv = new TextView(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextSize(16);
        tv.setText("ChronoDuo could not start.\n\n"
                + "It needs the official CHRONO TRIGGER (Upgrade Ver.) app from Google Play "
                + "installed on this device — ChronoDuo runs that copy's engine and assets, "
                + "it ships none of its own.\n\nDetail: " + e);
        setContentView(tv);
    }

    /** One-time move of the game's save files from the old internal writable dir
     *  to the external files dir. Existing files in the destination are never
     *  overwritten. */
    static void migrateSaves(File from, File to) {
        if (from == null || to == null || from.equals(to)) return;
        File[] files = from.listFiles();
        if (files == null) return;
        int moved = 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName();
            boolean isSave = n.startsWith("Chrono_sp_") || n.equals("meta.bin")
                    || n.equals("common.bin") || n.equals("UserDefault.xml");
            if (!isSave) continue;
            File dst = new File(to, n);
            if (dst.exists()) continue;
            if (f.renameTo(dst)) { moved++; continue; }
            try (java.io.FileInputStream in = new java.io.FileInputStream(f);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                moved++;
            } catch (java.io.IOException e) {
                Log.w(TAG, "save migration failed for " + n, e);
                dst.delete();
                continue;
            }
            f.delete();
        }
        if (moved > 0) Log.i(TAG, "migrated " + moved + " save file(s) to " + to);
    }
}
