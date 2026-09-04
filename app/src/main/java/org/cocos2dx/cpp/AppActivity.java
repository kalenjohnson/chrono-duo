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
import java.util.HashSet;
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
        });
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
        extractCompanionAssets();
        renderWorldMaps();
        // Original-sprite replacements ride on the pixel-graphics preference.
        if (com.kalenjohnson.chronoduo.GameState.getPixelGraphicsPref(this)) scanOrigArtReplacements();

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
                        "Game/common/wb_mini.png",
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
                java.util.Map<String, File> files =
                        com.kalenjohnson.chronoduo.ChronoResources.extractAll(appCtx, gameAssets, names);

                final android.graphics.Bitmap face = decodeBitmap(files.get("Extension/face.png"));
                // wb_mini.png is the fallback shown by ChronoAssets.getWorldMap(era)
                // for any era whose rendered worldmap_era<N>.png isn't on disk yet
                // (rendering hasn't finished, or failed) -- see
                // ChronoAssets.setWorldMapDir/setMiniMapFallback and
                // AppActivity#renderWorldMaps.
                final android.graphics.Bitmap map = cropWorldMap(files.get("Game/common/wb_mini.png"));
                final android.graphics.Bitmap mark = cropMarkerTile(files.get("Game/common/minimap_mark.png"));
                final android.graphics.Bitmap epochMark = cropEpochTile(files.get("Game/common/minimap_mark.png"));
                final android.graphics.Bitmap windowTex = cropWindowTexture(files.get("Extension/menu_win.png"));
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
                    if (map != null) com.kalenjohnson.chronoduo.ChronoAssets.setMiniMapFallback(map);
                    if (mark != null) com.kalenjohnson.chronoduo.ChronoAssets.setMinimapMark(mark);
                    if (epochMark != null) com.kalenjohnson.chronoduo.ChronoAssets.setEpochMark(epochMark);
                    if (windowTex != null) com.kalenjohnson.chronoduo.ChronoAssets.setWindowTex(windowTex);
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
     * Runs the two original-art rebuild passes on one background thread,
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
     *       from the 4bpp cg banks + ChipTable + palette.</li>
     * </ol>
     * Both write into the same directory and are picked up by the same
     * replacement machinery, so one button covers both; the phase label
     * ("sprites" / "field chips") is what tells the two done/total counters
     * apart on screen.
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

    // Extension/menu_win.png is a 512x512 sheet of pre-baked DS-style window
    // panels at various fixed sizes (packed, not tiled). The largest one —
    // a beveled steel/navy panel with a black outline and a light bevel
    // highlight line that resolves into flat noise fill by ~16px in from
    // each edge — sits at (198,134)-(500,304) in sheet pixels; that region
    // is cropped out here so ChronoAssets/PartyPanelView can 9-slice it
    // directly (bitmap origin becomes the panel's own top-left).
    private static final int WIN_TEX_L = 198, WIN_TEX_T = 134, WIN_TEX_R = 500, WIN_TEX_B = 304;

    private static android.graphics.Bitmap cropWindowTexture(File f) {
        android.graphics.Bitmap sheet = decodeBitmap(f);
        if (sheet == null) return null;
        if (sheet.getWidth() < WIN_TEX_R || sheet.getHeight() < WIN_TEX_B) {
            Log.w(TAG, "menu_win.png smaller than expected, skipping window texture crop");
            return null;
        }
        try {
            return android.graphics.Bitmap.createBitmap(sheet, WIN_TEX_L, WIN_TEX_T,
                    WIN_TEX_R - WIN_TEX_L, WIN_TEX_B - WIN_TEX_T);
        } catch (Exception e) {
            Log.w(TAG, "window texture crop failed", e);
            return null;
        }
    }

    // Game/common/wb_mini.png is a 256x256 sheet; the actual mini world-map
    // content is a fixed 96x128 region at (16,48)-(112,176) in sheet pixels
    // (measured directly, not auto-detected — the old autoCropContent()
    // background-color heuristic isn't reliable against this art). The
    // texture is stored at half its displayed width: the game's own map view
    // is landscape ~1.5:1, so PartyPanelView stretches this crop 2x
    // horizontally when drawing it (see the aspect-1.5 letterbox there).
    private static final int WMAP_L = 16, WMAP_T = 48, WMAP_R = 112, WMAP_B = 176;

    private static android.graphics.Bitmap cropWorldMap(File f) {
        android.graphics.Bitmap sheet = decodeBitmap(f);
        if (sheet == null) return null;
        if (sheet.getWidth() < WMAP_R || sheet.getHeight() < WMAP_B) {
            Log.w(TAG, "wb_mini.png smaller than expected, skipping world map crop");
            return null;
        }
        try {
            return android.graphics.Bitmap.createBitmap(sheet, WMAP_L, WMAP_T,
                    WMAP_R - WMAP_L, WMAP_B - WMAP_T);
        } catch (Exception e) {
            Log.w(TAG, "world map crop failed", e);
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
    private static android.graphics.Bitmap cropMarkerTile(File f) {
        android.graphics.Bitmap sheet = decodeBitmap(f);
        if (sheet == null) return null;
        if (sheet.getWidth() < 32 || sheet.getHeight() < 16) {
            Log.w(TAG, "minimap_mark.png smaller than expected, skipping marker tile crop");
            return null;
        }
        try {
            return android.graphics.Bitmap.createBitmap(sheet, 16, 0, 16, 16);
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
        if (sheet.getWidth() < 48 || sheet.getHeight() < 16) {
            Log.w(TAG, "minimap_mark.png smaller than expected, skipping Epoch tile crop");
            return null;
        }
        try {
            return android.graphics.Bitmap.createBitmap(sheet, 32, 0, 16, 16);
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
        if (requestCode != REQUEST_ROM_IMPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importRomFromUri(data.getData());
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
}
