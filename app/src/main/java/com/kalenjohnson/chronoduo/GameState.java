package com.kalenjohnson.chronoduo;

import android.util.Log;

/**
 * Live game state read out of libchrono.so via the chronoduo native hook.
 * Field offsets inside the 0x154-byte character block are calibrated
 * empirically (see NOTES.md).
 */
public final class GameState {
    private static final String TAG = "ChronoDuo";
    private static boolean attached;

    static {
        System.loadLibrary("chronoduo");
    }

    public static native boolean nativeAttach();
    public static native byte[] nativeReadChara(int idx);
    public static native byte[] nativeProbeWork(int slotOff, int memOff, int len);
    public static native byte[] nativeReadSfc(int off, int len);
    public static native byte[] nativeReadAsmMem(int off, int len);
    public static native void nativeScan();
    public static native void nativeDumpToFiles(String dir);
    public static native void nativeSceneDump(int maxDepth);      // GL thread only
    public static native int nativeSetVisibleByPattern(String pattern, boolean visible); // GL thread only

    public static native void nativeUpdateMapName();              // GL thread only
    public static native String nativeGetMapName();

    public static native void nativeUpdateBattleFlag();           // GL thread only
    public static native boolean nativeGetBattleFlag();
    public static native void nativeDumpBattleBuffers(String dir); // any thread; uses cached node ptr
    // Live battle actor array (10 * 0x80-byte slots), or null if not in battle
    // or any pointer in the chase is bad. Any thread; uses cached node ptr.
    public static native byte[] nativeReadBattleActors();
    // Battle command button (MenuItemToggle) positions/visibility/selection,
    // cached by nativeUpdateBattleFlag: flat [x0,y0,vis0,sel0,selIdx0, ...]
    // quintuples in worldspace pixels; vis/sel are 0.0/1.0, selIdx is the
    // toggle's raw _selectedIndex cast to float. Empty array when not in
    // battle. Any thread; uses the cached array populated on the GL thread.
    public static native float[] nativeGetBattleToggles();

    // --- frame-perfect UI enforcer -----------------------------------------
    // Idempotent start gate (native side) for the per-rendered-frame GL tick
    // below -- see startFrameEnforcer(). Returns true only the first time.
    public static native boolean nativeStartFrameEnforcer();
    // Cheap per-frame GL-thread tick: depth<=2 park sweep for FieldMenu/
    // WorldMenu (kills the field-MENU flash the 700ms tick can still miss
    // between ticks), plus -- when in battle and nativeSetHideBattleUi(true)
    // (the default) -- an opacity-only sweep of the battle node's command
    // menus. Must run on the GL thread; queued every frame via
    // FRAME_ENFORCER_TICK below.
    public static native void nativeEnforceUiTick();
    // Toggles the battle top-UI opacity hiding done by nativeEnforceUiTick.
    // Default true; expose an off switch in case it ever misbehaves. Any
    // thread (plain flag write).
    public static native void nativeSetHideBattleUi(boolean hide);

    private static boolean frameEnforcerStarted;
    private static final android.os.Handler sEnforcerHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    // Per-frame tick. CRITICAL: the repost must bounce through the MAIN
    // thread with a frame's delay — a Runnable that re-queues itself from
    // inside the GL thread's event drain starves rendering completely
    // (GLSurfaceView drains its event queue before drawing; a self-reposting
    // event means the queue never empties and the first frame never renders —
    // this hung the app at the splash screen when done the "obvious" way).
    private static final Runnable FRAME_ENFORCER_TICK = new Runnable() {
        @Override public void run() {
            nativeEnforceUiTick();
            sEnforcerHandler.postDelayed(GameState::queueEnforcerTick, 16);
        }
    };

    private static void queueEnforcerTick() {
        org.cocos2dx.lib.Cocos2dxGLSurfaceView view =
                org.cocos2dx.lib.Cocos2dxGLSurfaceView.getInstance();
        if (view != null) view.queueEvent(FRAME_ENFORCER_TICK);
        else sEnforcerHandler.postDelayed(GameState::queueEnforcerTick, 100);
    }

    /**
     * Starts the per-frame UI enforcer loop. Idempotent (a second call is a
     * no-op, guarded natively so even a call racing the Java-side flag can't
     * stack a duplicate repost loop) and safe to call before the GL surface
     * exists -- queueEvent() just buffers the Runnable until the render
     * thread is ready to run it. No stop path: the tick is a no-op whenever
     * nothing currently matches its patterns, so leaving it running forever
     * costs nothing extra once idle.
     */
    public static void startFrameEnforcer() {
        if (frameEnforcerStarted || !isAttached()) return;
        if (!nativeStartFrameEnforcer()) return;
        frameEnforcerStarted = true;
        queueEnforcerTick();
    }

    /** Scene-graph node type/name patterns hidden by the "clean UI" tick. */
    public static final String[] HIDDEN_UI_PATTERNS = {"FieldMenu", "WorldMenu"};

    /**
     * Hides every node matching HIDDEN_UI_PATTERNS. Must run on the GL thread
     * (scene-graph access) -- shared by AppActivity's periodic "clean UI" tick
     * and SecondScreenPresentation's scene-change fast path so both apply the
     * exact same call.
     */
    public static void applyHiddenUiPatterns() {
        for (String pat : HIDDEN_UI_PATTERNS) {
            nativeSetVisibleByPattern(pat, false);
        }
    }

    /**
     * Queues one applyHiddenUiPatterns() run on the GL thread. Safe to call
     * from any thread; no-op if the native hook isn't attached yet.
     */
    public static void queueHiddenUiPatterns() {
        if (isAttached()) {
            org.cocos2dx.lib.Cocos2dxHelper.runOnGLThread(GameState::applyHiddenUiPatterns);
        }
    }

    public static boolean attach() {
        if (!attached) {
            attached = nativeAttach();
            Log.i(TAG, "GameState.attach: " + attached);
        }
        return attached;
    }

    public static boolean isAttached() {
        return attached;
    }

    public static String hexDump(byte[] data, int base) {
        if (data == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 16) {
            sb.append(String.format("%04x:", base + i));
            for (int j = i; j < Math.min(i + 16, data.length); j++) {
                sb.append(String.format(" %02x", data[j]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** cSfcWork::Setup stores Asm::getBuffer() at this+0xfdf8 — the virtual SNES memory. */
    public static final int WORK_PTR_SLOT = 0xfdf8;
    /** SNES bank $7E lives at index 0x20000 in the Asm buffer (GetWorkBank7E). */
    public static final int BANK_7E = 0x20000;

    /** Full-region dumps for offline analysis (adb pull the .bin files). */
    public static void dumpToFiles(String dir) {
        if (attach()) nativeDumpToFiles(dir);
    }
}
