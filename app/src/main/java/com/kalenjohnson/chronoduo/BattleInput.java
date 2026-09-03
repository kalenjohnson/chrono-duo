package com.kalenjohnson.chronoduo;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.cocos2dx.lib.Cocos2dxGLSurfaceView;

/**
 * Injects a synthetic tap into the game's cocos2d-x GL surface on the
 * primary display, simulating a real touch on a live battle command menu
 * toggle. Public-API only (no reflection, no shell commands): fetch the
 * singleton {@link Cocos2dxGLSurfaceView}, feed it an ACTION_DOWN
 * {@link MotionEvent} at the target's game-screen coordinates, then ~80ms
 * later a matching ACTION_UP at the same point. Both events are dispatched
 * on the main thread's Handler -- the Presentation (where the caller runs)
 * and the game's GL surface view share the same main Looper, so
 * {@code onTouchEvent} here runs exactly as it would for a real screen tap.
 */
final class BattleInput {
    private static final long UP_DELAY_MS = 80;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private BattleInput() {
    }

    /**
     * Injects a DOWN immediately, then a matching UP ~{@value #UP_DELAY_MS}ms
     * later, both at ({@code sx}, {@code sy}) in game-view screen pixels (the
     * 1920x1080 space the caller has already transformed into -- see
     * {@link PartySnapshot.CommandTarget}). No-op, safely, if the cocos GL
     * view isn't available (e.g. game not yet attached/foregrounded).
     */
    static void tap(float sxIn, float syIn) {
        Cocos2dxGLSurfaceView glView = Cocos2dxGLSurfaceView.getInstance();
        if (glView == null) return;
        // affine is calibrated for a 1920x1080 view; scale if the surface differs
        float sx = sxIn, sy = syIn;
        int vw = glView.getWidth(), vh = glView.getHeight();
        if (vw > 0 && vh > 0 && (vw != 1920 || vh != 1080)) {
            sx = sxIn * vw / 1920f;
            sy = syIn * vh / 1080f;
        }
        final float fsx = sx, fsy = sy;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, fsx, fsy, 0);
        try {
            glView.onTouchEvent(down);
        } finally {
            down.recycle();
        }
        handler.postDelayed(() -> {
            Cocos2dxGLSurfaceView gl2 = Cocos2dxGLSurfaceView.getInstance();
            if (gl2 == null) return;
            long t2 = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(t2, t2, MotionEvent.ACTION_UP, fsx, fsy, 0);
            try {
                gl2.onTouchEvent(up);
            } finally {
                up.recycle();
            }
        }, UP_DELAY_MS);
    }
}
