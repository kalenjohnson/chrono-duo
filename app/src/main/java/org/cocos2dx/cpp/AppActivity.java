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
import com.kalenjohnson.chronoduo.SecondScreenManager;

import org.cocos2dx.lib.Cocos2dxActivity;
import org.cocos2dx.lib.Cocos2dxHelper;

import java.io.File;

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

        secondScreen = new SecondScreenManager(this);
        controllerInput.ensureConnected();
        extractCompanionAssets();

        com.kalenjohnson.chronoduo.GameState.attach();
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
            // controller covers them (Y = menu). Re-applied every 2s because
            // scene transitions rebuild the UI nodes.
            Runnable cleanUi = new Runnable() {
                @Override public void run() {
                    Cocos2dxHelper.runOnGLThread(() -> {
                        for (String pat : com.kalenjohnson.chronoduo.GameState.HIDDEN_UI_PATTERNS) {
                            com.kalenjohnson.chronoduo.GameState.nativeSetVisibleByPattern(pat, false);
                        }
                    });
                    h.postDelayed(this, 2000);
                }
            };
            h.postDelayed(cleanUi, 2000);
        }

        // dev trigger: adb shell am broadcast -a com.kalenjohnson.chronoduo.SCENE_DUMP
        android.content.IntentFilter filter =
                new android.content.IntentFilter("com.kalenjohnson.chronoduo.SCENE_DUMP");
        android.content.BroadcastReceiver devReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
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
                        "Game/battle/tblb/MonsterNameData.dat",
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
                final String[] monsterNames = readMonsterNames(files.get("Localize/en/msg/monster.txt"));
                final byte[] monsterFlags = readRawBytes(files.get("Game/battle/tblb/MonsterNameData.dat"));

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
                    if (monsterFlags != null) com.kalenjohnson.chronoduo.ChronoAssets.setMonsterFlags(monsterFlags);
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
     * Reads Localize/en/msg/monster.txt as UTF-8, CRLF-delimited (a lone LF is
     * tolerated too); line index (0-based) == monster id, e.g. line 146 =
     * "Gato". Best-effort: any failure is logged and returns null, leaving
     * PartyPanelView's "Enemy N" fallback in place.
     */
    private static String[] readMonsterNames(File f) {
        if (f == null) return null;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            return text.split("\r\n|\n");
        } catch (Exception e) {
            Log.w(TAG, "failed to read monster name table: " + f, e);
            return null;
        }
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
