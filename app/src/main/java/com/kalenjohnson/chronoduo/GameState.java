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

    /**
     * Current field-map/location id -- a plain int32 read of
     * ChronoCanvas::getInstance()+0x12300 (== the embedded FIELD_MAp struct's
     * +0x1018), the exact field ChronoCanvas::getFieldMapName() itself loads
     * (via {@code ldrsw x11, [x9, #0x98c]} where x9 = this+0x11974) to pick
     * which localized name variant to show -- see NOTES.md /
     * fieldmap_id_report.md for the disassembly. Unlike nativeUpdateMapName
     * this is a plain safe_read (no scene-graph/std::string sret call), so it
     * is safe to call from any thread. Returns -1 if the canvas or the read
     * is unavailable.
     */
    public static native int nativeGetFieldMapId();

    /**
     * Party leader's in-field tile position: [x, y] as floats, read from the
     * CHARACTER_DATa record for party slot 1 (record index 1 -- cSfcWork +
     * 0x6924 + 1*0x154, the same base/stride nativeReadChara uses). Verified
     * live by differential dumps: int32 X tile at record+0x80, int32 X*256
     * sub-tile at +0x84, int32 Y tile at +0x8c, int32 Y*256 sub-tile at +0x90
     * (Y grows downward); the native side prefers the sub-tile values divided
     * by 256.0f and falls back to the plain tile ints only if that read
     * fails. Only meaningful in field maps, not on the overworld (use
     * worldX/worldY there instead -- see PartySnapshot). Plain safe_read, so
     * safe to call from any thread. Returns null if the record is
     * unreadable.
     */
    public static native float[] nativeGetFieldPos();

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
    // Cached open battle list submenu (Tech or Item), populated by
    // nativeUpdateBattleFlag right after the toggle scan above -- same
    // thread/cadence. Returns null when no submenu is open. Otherwise a
    // flat float array: [kind, count, id0, usable0, extra0, x0, y0, id1,
    // usable1, extra1, x1, y1, ...]. kind is 0 (Tech) or 1 (Item); usable is
    // 0.0/1.0; extra is the tech's param/cost or the item's count, cast to
    // float; x/y are worldspace pixels in the same space as
    // nativeGetBattleToggles, or NaN when the row's on-screen button node
    // couldn't be resolved (skip tapping that row). Any thread; uses the
    // cached array populated on the GL thread.
    public static native float[] nativeGetBattleList();
    // Opts the BattleTechMenu/BattleItemMenu submenu nodes into the same
    // opacity hiding nativeEnforceUiTick applies to the rest of the battle
    // chrome (see nativeSetHideBattleUi) -- only meaningful once the caller
    // is mirroring nativeGetBattleList's rows on the second screen. Default
    // false. Any thread (plain flag write).
    public static native void nativeSetHideBattleSubmenus(boolean hide);

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
    // Dev experiment hook: bit i blanks direct child index i of the battle
    // node's children (any type), so which child draws what can be seen
    // live. One-way -- clearing a bit does not restore opacity. Any thread
    // (plain flag write).
    public static native void nativeSetBattleHideMask(int mask);

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
