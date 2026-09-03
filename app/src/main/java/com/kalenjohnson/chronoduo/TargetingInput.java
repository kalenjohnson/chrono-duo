package com.kalenjohnson.chronoduo;

import android.os.Handler;
import android.os.Looper;

import org.cocos2dx.lib.GameControllerAdapter;
import org.cocos2dx.lib.GameControllerDelegate;

/**
 * Injects synthetic gamepad button presses for the battle target-selection
 * phase (cursor cycling / confirm), as opposed to {@link BattleInput}'s
 * synthetic touches for the command menu. The game reads target-cursor input
 * from the controller path, not touch, so this goes straight through
 * {@link GameControllerAdapter#onButtonEvent} -- which already self-queues
 * onto the GL thread, same as {@link org.cocos2dx.lib.GameControllerAdapter#onConnected}
 * below.
 *
 * Uses the same vendor string / controller id convention as
 * {@link GameControllerInput} so the native side sees one consistent virtual
 * pad rather than a second phantom controller; {@link #ensureConnected}
 * mirrors that class's connect-once guard (as a static here, since this is
 * used from {@link PartyPanelView}, a separate object from the
 * {@code GameControllerInput} instance that owns real hardware forwarding).
 */
final class TargetingInput {
    private static final String VENDOR = "ChronoDuo Controller";
    private static final int CONTROLLER = 0;

    private static final long ARROW_UP_DELAY_MS = 120;
    private static final long CONFIRM_UP_DELAY_MS = 70;
    private static final long BACK_UP_DELAY_MS = 70;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static boolean connected;

    private TargetingInput() {
    }

    private static void ensureConnected() {
        if (!connected) {
            connected = true;
            GameControllerAdapter.onConnected(VENDOR, CONTROLLER);
        }
    }

    /** Cycles the target cursor left (dpad-left down, then up ~120ms later). */
    static void left() {
        press(GameControllerDelegate.BUTTON_DPAD_LEFT, ARROW_UP_DELAY_MS);
    }

    /** Cycles the target cursor right (dpad-right down, then up ~120ms later). */
    static void right() {
        press(GameControllerDelegate.BUTTON_DPAD_RIGHT, ARROW_UP_DELAY_MS);
    }

    /** Confirms the current target (A down, then up ~70ms later). */
    static void confirm() {
        press(GameControllerDelegate.BUTTON_A, CONFIRM_UP_DELAY_MS);
    }

    /** Moves the submenu list cursor up (dpad-up down, then up ~120ms later). */
    static void up() {
        press(GameControllerDelegate.BUTTON_DPAD_UP, ARROW_UP_DELAY_MS);
    }

    /** Moves the submenu list cursor down (dpad-down down, then up ~120ms later). */
    static void down() {
        press(GameControllerDelegate.BUTTON_DPAD_DOWN, ARROW_UP_DELAY_MS);
    }

    /** Backs out of the submenu list (B down, then up ~70ms later). */
    static void back() {
        press(GameControllerDelegate.BUTTON_B, BACK_UP_DELAY_MS);
    }

    private static void press(int button, long upDelayMs) {
        ensureConnected();
        GameControllerAdapter.onButtonEvent(VENDOR, CONTROLLER, button, true, 1f, false);
        handler.postDelayed(() ->
                GameControllerAdapter.onButtonEvent(VENDOR, CONTROLLER, button, false, 0f, false),
                upDelayMs);
    }
}
