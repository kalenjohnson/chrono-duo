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
    // Battle command button (MenuItemToggle) positions/visibility, cached by
    // nativeUpdateBattleFlag: flat [x0,y0,vis0, x1,y1,vis1, ...] triples in
    // worldspace pixels, vis 0.0/1.0. Empty array when not in battle. Any
    // thread; uses the cached array populated on the GL thread.
    public static native float[] nativeGetBattleToggles();

    /** Scene-graph node type/name patterns hidden by the "clean UI" tick. */
    public static final String[] HIDDEN_UI_PATTERNS = {"FieldMenu", "WorldMenu"};

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
