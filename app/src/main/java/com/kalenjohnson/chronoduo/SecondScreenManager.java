package com.kalenjohnson.chronoduo;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * Finds a secondary presentation display (e.g. the Ayn Thor's bottom panel)
 * and keeps a SecondScreenPresentation alive on it.
 *
 * Pattern follows zelda3-android / tmc-android / balatro-dualscreen:
 *  - discover by capability (DISPLAY_CATEGORY_PRESENTATION), never by model or
 *    cached display id
 *  - the presentation window must stay non-focusable so controller input keeps
 *    going to the game on the default display
 *  - the system can dismiss a Presentation behind our back (rotation, display
 *    config churn); recover in onDismiss and on display events
 */
public final class SecondScreenManager {
    private static final String TAG = "ChronoDuoSS";

    private final Activity activity;
    private final DisplayManager displayManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SecondScreenPresentation presentation;
    private boolean resumed;

    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { update(); }
        @Override public void onDisplayRemoved(int displayId) { update(); }
        @Override public void onDisplayChanged(int displayId) { update(); }
    };

    public SecondScreenManager(Activity activity) {
        this.activity = activity;
        this.displayManager = (DisplayManager) activity.getSystemService(Activity.DISPLAY_SERVICE);
    }

    private final android.content.BroadcastReceiver screenOnReceiver =
            new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context ctx, android.content.Intent i) {
                    // After device sleep the panel powers back on without any
                    // display event; the old Presentation surface stays black.
                    // Recreate it from scratch.
                    dismiss();
                    handler.postDelayed(SecondScreenManager.this::update, 400);
                }
            };

    public void onResume() {
        resumed = true;
        displayManager.registerDisplayListener(listener, handler);
        activity.registerReceiver(screenOnReceiver,
                new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON));
        update();
        // displays are sometimes not ready the instant we resume — retry briefly
        handler.postDelayed(this::update, 600);
        handler.postDelayed(this::update, 2500);
    }

    public void onPause() {
        resumed = false;
        displayManager.unregisterDisplayListener(listener);
        try {
            activity.unregisterReceiver(screenOnReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        dismiss();
    }

    private void update() {
        if (!resumed) return;
        Display target = findSecondaryDisplay();
        if (target == null) {
            dismiss();
            return;
        }
        if (presentation != null) {
            if (presentation.getDisplay().getDisplayId() == target.getDisplayId()
                    && presentation.isShowing()) {
                return;
            }
            dismiss();
        }
        show(target);
    }

    private Display findSecondaryDisplay() {
        int gameDisplayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        for (Display d : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getDisplayId() != gameDisplayId && d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                return d;
            }
        }
        return null;
    }

    private void show(Display display) {
        SecondScreenPresentation p = new SecondScreenPresentation(activity, display);
        p.setOnDismissListener(dialog -> {
            if (presentation == dialog) {
                presentation = null;
                // system-initiated dismiss; try to come back
                handler.postDelayed(this::update, 250);
            }
        });
        try {
            p.show();
            presentation = p;
            Log.i(TAG, "presentation shown on display " + display.getDisplayId());
        } catch (WindowManager.InvalidDisplayException | SecurityException e) {
            Log.w(TAG, "cannot show presentation on display " + display.getDisplayId(), e);
        }
    }

    private void dismiss() {
        if (presentation != null) {
            SecondScreenPresentation p = presentation;
            presentation = null;
            p.setOnDismissListener(null);
            p.dismiss();
        }
    }
}
