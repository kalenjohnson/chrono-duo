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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

        com.kalenjohnson.chronoduo.GameState.attach();
        // periodic full-memory dumps for offline layout analysis (dev only)
        File dumpDir = getExternalFilesDir(null);
        if (dumpDir != null) {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            String dir = dumpDir.getAbsolutePath();
            Runnable dump = new Runnable() {
                @Override public void run() {
                    com.kalenjohnson.chronoduo.GameState.dumpToFiles(dir);
                    h.postDelayed(this, 8000);
                }
            };
            h.postDelayed(dump, 8000);

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
