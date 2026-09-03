package com.kalenjohnson.chronoduo;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.cocos2dx.lib.GameControllerAdapter;
import org.cocos2dx.lib.GameControllerDelegate;

/**
 * Forwards Android gamepad input to the engine's controller path
 * (GameControllerAdapter -> nativeControllerButtonEvent/AxisEvent).
 *
 * The game binds cocos2d Controller::Key codes (ct_nx keymap): A=confirm,
 * B=cancel/dash, X=warp/map, Y=menu, L/R=page-shift, Start=pause. The Java
 * GameControllerDelegate constants are numerically identical to the native
 * Controller::Key enum (1000+), so they pass straight through.
 */
public final class GameControllerInput {
    private static final String VENDOR = "ChronoDuo Controller";
    private static final int CONTROLLER = 0;
    private static final float HAT_THRESHOLD = 0.5f;

    /**
     * Lets the panel's own command-row navigation (see
     * {@code PartyPanelView#onControllerLeft/Right/Confirm}) intercept a
     * physical d-pad-left/-right press before it reaches the game, for the
     * hat-axis d-pad path (see {@link #handleMotionEvent}). Set (or cleared,
     * with null) by {@link org.cocos2dx.cpp.AppActivity}; left null-safe here
     * so a controller connected before the panel exists still works.
     */
    public interface CommandNavSink {
        boolean left();
        boolean right();
        boolean confirm();
    }

    private CommandNavSink navSink;

    /** Installs (or clears, with null) the command-nav interception target -- see {@link CommandNavSink}. */
    public void setCommandNavSink(CommandNavSink sink) {
        navSink = sink;
    }

    private boolean connected;
    private boolean hatLeft, hatRight, hatUp, hatDown;
    // Set when a hat-axis left/right press was consumed by navSink, so the
    // matching release is swallowed too instead of reaching the game as a
    // half-press it never saw the down half of.
    private boolean hatLeftConsumed, hatRightConsumed;

    public void ensureConnected() {
        if (!connected) {
            connected = true;
            GameControllerAdapter.onConnected(VENDOR, CONTROLLER);
        }
    }

    /** @return true if the event was consumed (it mapped to a game button). */
    public boolean handleKeyEvent(KeyEvent event) {
        int cc = mapKeyCode(event.getKeyCode());
        if (cc == 0) return false;
        if (event.getRepeatCount() > 0) return true; // swallow auto-repeat
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
        ensureConnected();
        GameControllerAdapter.onButtonEvent(VENDOR, CONTROLLER, cc, pressed, pressed ? 1f : 0f, false);
        return true;
    }

    /** @return true if the event was consumed (joystick move). */
    public boolean handleMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == 0
                || event.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        ensureConnected();
        GameControllerAdapter.onAxisEvent(VENDOR, CONTROLLER,
                GameControllerDelegate.THUMBSTICK_LEFT_X, event.getAxisValue(MotionEvent.AXIS_X), true);
        GameControllerAdapter.onAxisEvent(VENDOR, CONTROLLER,
                GameControllerDelegate.THUMBSTICK_LEFT_Y, event.getAxisValue(MotionEvent.AXIS_Y), true);
        GameControllerAdapter.onAxisEvent(VENDOR, CONTROLLER,
                GameControllerDelegate.THUMBSTICK_RIGHT_X, event.getAxisValue(MotionEvent.AXIS_Z), true);
        GameControllerAdapter.onAxisEvent(VENDOR, CONTROLLER,
                GameControllerDelegate.THUMBSTICK_RIGHT_Y, event.getAxisValue(MotionEvent.AXIS_RZ), true);

        // d-pads that report as hat axes -> synthesize dpad button events.
        // Left/right first offer the press (on the pressed-edge transition
        // only) to the panel's CommandNavSink, if one is installed -- when it
        // consumes, neither the synthetic press nor its later release is
        // emitted to the game (see hatLeftConsumed/hatRightConsumed). Up/down
        // have no panel meaning and keep the plain hatButton path.
        float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);

        boolean isLeft = hx < -HAT_THRESHOLD;
        if (!hatLeft && isLeft) {
            if (navSink != null && navSink.left()) {
                hatLeftConsumed = true;
            } else {
                GameControllerAdapter.onButtonEvent(VENDOR, CONTROLLER,
                        GameControllerDelegate.BUTTON_DPAD_LEFT, true, 1f, false);
            }
        } else if (hatLeft && !isLeft) {
            if (hatLeftConsumed) {
                hatLeftConsumed = false;
            } else {
                GameControllerAdapter.onButtonEvent(VENDOR, CONTROLLER,
                        GameControllerDelegate.BUTTON_DPAD_LEFT, false, 0f, false);
            }
        }
        hatLeft = isLeft;

        boolean isRight = hx > HAT_THRESHOLD;
        if (!hatRight && isRight) {
            if (navSink != null && navSink.right()) {
                hatRightConsumed = true;
            } else {
                GameControllerAdapter.onButtonEvent(VENDOR, CONTROLLER,
                        GameControllerDelegate.BUTTON_DPAD_RIGHT, true, 1f, false);
            }
        } else if (hatRight && !isRight) {
            if (hatRightConsumed) {
                hatRightConsumed = false;
            } else {
                GameControllerAdapter.onButtonEvent(VENDOR, CONTROLLER,
                        GameControllerDelegate.BUTTON_DPAD_RIGHT, false, 0f, false);
            }
        }
        hatRight = isRight;

        hatUp = hatButton(hatUp, hy < -HAT_THRESHOLD, GameControllerDelegate.BUTTON_DPAD_UP);
        hatDown = hatButton(hatDown, hy > HAT_THRESHOLD, GameControllerDelegate.BUTTON_DPAD_DOWN);
        return true;
    }

    private boolean hatButton(boolean was, boolean is, int cc) {
        if (was != is) {
            GameControllerAdapter.onButtonEvent(VENDOR, CONTROLLER, cc, is, is ? 1f : 0f, false);
        }
        return is;
    }

    private static int mapKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return GameControllerDelegate.BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B: return GameControllerDelegate.BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_X: return GameControllerDelegate.BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return GameControllerDelegate.BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return GameControllerDelegate.BUTTON_LEFT_SHOULDER;
            case KeyEvent.KEYCODE_BUTTON_R1: return GameControllerDelegate.BUTTON_RIGHT_SHOULDER;
            case KeyEvent.KEYCODE_BUTTON_L2: return GameControllerDelegate.BUTTON_LEFT_TRIGGER;
            case KeyEvent.KEYCODE_BUTTON_R2: return GameControllerDelegate.BUTTON_RIGHT_TRIGGER;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return GameControllerDelegate.BUTTON_LEFT_THUMBSTICK;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return GameControllerDelegate.BUTTON_RIGHT_THUMBSTICK;
            case KeyEvent.KEYCODE_BUTTON_START: return GameControllerDelegate.BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return GameControllerDelegate.BUTTON_SELECT;
            case KeyEvent.KEYCODE_DPAD_UP: return GameControllerDelegate.BUTTON_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return GameControllerDelegate.BUTTON_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return GameControllerDelegate.BUTTON_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return GameControllerDelegate.BUTTON_DPAD_RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER: return GameControllerDelegate.BUTTON_A;
            default: return 0;
        }
    }
}
