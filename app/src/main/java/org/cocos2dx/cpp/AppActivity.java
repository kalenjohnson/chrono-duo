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
        // used below for worldmap_hd.png).
        com.kalenjohnson.chronoduo.ChronoAssets.setExternalFilesDir(ext != null ? ext : getFilesDir());

        secondScreen = new SecondScreenManager(this);
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

        // dev trigger: adb shell am broadcast -a com.kalenjohnson.chronoduo.SCENE_DUMP
        // dev trigger: adb shell am broadcast -a com.kalenjohnson.chronoduo.BATTLE_HIDE_MASK
        // --ei mask N -- blanks direct children of the battle node by index
        // bitmask, for seeing live which child draws what.
        android.content.IntentFilter filter =
                new android.content.IntentFilter("com.kalenjohnson.chronoduo.SCENE_DUMP");
        filter.addAction("com.kalenjohnson.chronoduo.BATTLE_HIDE_MASK");
        android.content.BroadcastReceiver devReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                String action = i.getAction();
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
        if (secondScreen != null) secondScreen.onDestroy();
        super.onDestroy();
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
                        "Game/battle/tblb/MonsterNameData.dat",
                        "Game/common/TechnicMpTable.dat",
                        "Game/common/TechnicBaseDataTable.dat",
                };
                java.util.Map<String, File> files =
                        com.kalenjohnson.chronoduo.ChronoResources.extractAll(appCtx, gameAssets, names);

                final android.graphics.Bitmap face = decodeBitmap(files.get("Extension/face.png"));
                // an HD map render pushed to the external files dir wins over
                // the low-res wb_mini texture (user-local file, never shipped)
                File hd = new File(getExternalFilesDir(null), "worldmap_hd.png");
                final android.graphics.Bitmap hdMap =
                        hd.isFile() ? android.graphics.BitmapFactory.decodeFile(hd.getAbsolutePath()) : null;
                final android.graphics.Bitmap map =
                        hdMap != null ? null : cropWorldMap(files.get("Game/common/wb_mini.png"));
                final android.graphics.Bitmap mark = cropMarkerTile(files.get("Game/common/minimap_mark.png"));
                final android.graphics.Bitmap windowTex = cropWindowTexture(files.get("Extension/menu_win.png"));
                final String[] monsterNames = readNameTable(files.get("Localize/en/msg/monster.txt"));
                final String[] techNames = readNameTable(files.get("Localize/en/msg/tech.txt"));
                final String[] itemNames = readNameTable(files.get("Localize/en/msg/item.txt"));
                final java.util.Map<String, String> itemCategoryNames =
                        readSfcItemTable(files.get("Localize/en/msg/sfc_item.txt"));
                final byte[] monsterFlags = readRawBytes(files.get("Game/battle/tblb/MonsterNameData.dat"));
                final int[] techMp = readTechMpTable(
                        files.get("Game/common/TechnicMpTable.dat"),
                        files.get("Game/common/TechnicBaseDataTable.dat"));

                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (face != null) com.kalenjohnson.chronoduo.ChronoAssets.setFace(face);
                    if (hdMap != null) {
                        com.kalenjohnson.chronoduo.ChronoAssets.setWorldMap(hdMap, true);
                    } else if (map != null) {
                        com.kalenjohnson.chronoduo.ChronoAssets.setWorldMap(map);
                    }
                    if (mark != null) com.kalenjohnson.chronoduo.ChronoAssets.setMinimapMark(mark);
                    if (windowTex != null) com.kalenjohnson.chronoduo.ChronoAssets.setWindowTex(windowTex);
                    if (monsterNames != null) com.kalenjohnson.chronoduo.ChronoAssets.setMonsterNames(monsterNames);
                    if (techNames != null) com.kalenjohnson.chronoduo.ChronoAssets.setTechNames(techNames);
                    if (itemNames != null) com.kalenjohnson.chronoduo.ChronoAssets.setItemNames(itemNames);
                    if (itemCategoryNames != null) com.kalenjohnson.chronoduo.ChronoAssets.setItemCategoryNames(itemCategoryNames);
                    if (monsterFlags != null) com.kalenjohnson.chronoduo.ChronoAssets.setMonsterFlags(monsterFlags);
                    if (techMp != null) com.kalenjohnson.chronoduo.ChronoAssets.setTechMpTable(techMp);
                });
            } catch (Exception e) {
                Log.w(TAG, "companion asset extraction failed", e);
            }
        }, "ChronoResExtract").start();
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
