package com.kalenjohnson.chronoduo;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Emulator-style fast-forward: prefs-backed speed/mode, runtime active state,
 * and the HOLD/TOGGLE press semantics shared by the R2 trigger binding
 * (AppActivity/GameControllerInput) and the panel's Speed settings page /
 * on-panel badge (PartyPanelView). The actual engine lever is
 * cocos2d::Scheduler::_timeScale, applied natively -- see GameState's
 * nativeSetGameSpeed/nativeApplyGameSpeed and NOTES.md "Fast-forward
 * research (2026-09-15)".
 */
public final class GameSpeed {
    private static final String PREFS_NAME = "chronoduo_prefs"; // same file PartyPanelView uses
    private static final String KEY_FF_SPEED = "ff_speed";
    private static final String KEY_FF_MODE = "ff_mode";

    public static final float[] SPEED_CHOICES = {2f, 3f, 5f};
    public static final float DEFAULT_SPEED = 3f;

    public enum Mode { HOLD, TOGGLE }

    private static float ffSpeed = DEFAULT_SPEED;
    private static Mode ffMode = Mode.HOLD;
    private static boolean active; // runtime only, not persisted
    private static boolean loaded;

    /** Repaint hook for whoever draws the on-panel badge (PartyPanelView). */
    public interface Listener {
        void onGameSpeedChanged();
    }

    private static Listener listener;

    private GameSpeed() {}

    public static void setListener(Listener l) {
        listener = l;
    }

    private static void ensureLoaded(Context context) {
        if (loaded) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ffSpeed = prefs.getFloat(KEY_FF_SPEED, DEFAULT_SPEED);
        ffMode = "TOGGLE".equals(prefs.getString(KEY_FF_MODE, "HOLD")) ? Mode.TOGGLE : Mode.HOLD;
        loaded = true;
    }

    public static float getSpeed(Context context) {
        ensureLoaded(context);
        return ffSpeed;
    }

    public static Mode getMode(Context context) {
        ensureLoaded(context);
        return ffMode;
    }

    public static boolean isActive() {
        return active;
    }

    /** Cycles 2x -> 3x -> 5x -> 2x, persists, and re-applies if currently active. */
    public static void cycleSpeed(Context context) {
        ensureLoaded(context);
        int idx = 0;
        for (int i = 0; i < SPEED_CHOICES.length; i++) {
            if (SPEED_CHOICES[i] == ffSpeed) { idx = i; break; }
        }
        ffSpeed = SPEED_CHOICES[(idx + 1) % SPEED_CHOICES.length];
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_FF_SPEED, ffSpeed).apply();
        if (active) applyNative();
        notifyListener();
    }

    /** Cycles HOLD -> TOGGLE -> HOLD, persists. Releases any held R2 state (deactivates). */
    public static void cycleMode(Context context) {
        ensureLoaded(context);
        ffMode = (ffMode == Mode.HOLD) ? Mode.TOGGLE : Mode.HOLD;
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_FF_MODE, ffMode.name()).apply();
        setActive(context, false);
        notifyListener();
    }

    /**
     * Sets the runtime active state, pushes it to the native Scheduler hook
     * and applies it on the GL thread. Safe to call before the game runtime
     * exists -- GameState.isAttached()/queueing guards mirror the pattern
     * used elsewhere in this codebase (see GameState.queueHiddenUiPatterns).
     */
    public static void setActive(Context context, boolean isActive) {
        ensureLoaded(context);
        active = isActive;
        applyNative();
        notifyListener();
    }

    private static void applyNative() {
        GameState.nativeSetGameSpeed(active ? ffSpeed : 1f);
        if (GameState.isAttached()) {
            org.cocos2dx.lib.Cocos2dxHelper.runOnGLThread(GameState::nativeApplyGameSpeed);
        }
    }

    private static void notifyListener() {
        if (listener != null) listener.onGameSpeedChanged();
    }

    public static void toggle(Context context) {
        setActive(context, !active);
    }

    // The right trigger reaches us twice on an analog-trigger pad (the Ayn
    // Thor's): Android delivers the AXIS_RTRIGGER motion AND a synthetic
    // KEYCODE_BUTTON_R2 key event for the same physical press, and the two
    // arrive at different points of the pull (the key fires near full
    // travel, the axis crosses its threshold much earlier). Without merging
    // them, a full pull in TOGGLE mode toggled twice (on, then straight off)
    // and only a feather-light pull -- crossing the axis threshold but never
    // the key's -- toggled once. So both paths feed ONE logical trigger
    // state here: the first source to press wins the edge, the last source
    // to release ends it, and press()/release() act only on those edges.
    private static boolean keyHeld;   // KEYCODE_BUTTON_R2 down (AppActivity)
    private static boolean axisHeld;  // AXIS_RTRIGGER/AXIS_GAS past threshold (GameControllerInput)

    /** Source of a trigger edge -- see the merge note above. */
    public enum Source { KEY, AXIS }

    private static boolean anyHeld() {
        return keyHeld || axisHeld;
    }

    /**
     * Trigger pressed edge from {@code source}. Acts (HOLD: activate;
     * TOGGLE: flip) only when the merged trigger state goes from released
     * to held, so a key+axis pair for one physical pull acts once.
     */
    public static void press(Context context, Source source) {
        ensureLoaded(context);
        boolean wasHeld = anyHeld();
        if (source == Source.KEY) keyHeld = true; else axisHeld = true;
        if (wasHeld) return;
        if (ffMode == Mode.HOLD) {
            setActive(context, true);
        } else {
            toggle(context);
        }
    }

    /**
     * Trigger released edge from {@code source}. HOLD mode deactivates only
     * when the LAST held source releases; TOGGLE mode ignores releases.
     */
    public static void release(Context context, Source source) {
        ensureLoaded(context);
        if (source == Source.KEY) keyHeld = false; else axisHeld = false;
        if (anyHeld()) return;
        if (ffMode == Mode.HOLD) {
            setActive(context, false);
        }
    }
}
