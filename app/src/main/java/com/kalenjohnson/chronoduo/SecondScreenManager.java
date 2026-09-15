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
 *
 * Sleep/wake note: onResume() fires reliably after every device wake (verified
 * via adb: Cocos2dxActivity logs onPause/onResume around KEYCODE_SLEEP/WAKEUP),
 * so a screen-on BroadcastReceiver is unnecessary for that path -- and was
 * actually harmful here: it was registered in onResume/unregistered in onPause,
 * which guarantees it misses ACTION_SCREEN_ON (that broadcast fires the instant
 * the display powers on, before onResume has run and re-registered it).
 * The real failure mode is different and more subtle: a Presentation created
 * immediately (synchronously) on the first onResume() after wake can end up
 * with isShowing()==true but a permanently black surface -- the display
 * reports ready (DisplayPowerController "Unblocked screen on") before its
 * compositor layer is actually live again. A View.invalidate() cannot recover
 * a dead surface (verified: the panel keeps calling update()/invalidate()
 * every 500ms via its poll loop, yet the screen stays black), only tearing
 * down and recreating the Presentation (a fresh Surface) does. So update()'s
 * post-resume retries must force a real recreate, not just skip out because
 * isShowing() already (falsely) reports true.
 */
public final class SecondScreenManager {
    private static final String TAG = "ChronoDuoSS";

    private final Activity activity;
    private final DisplayManager displayManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SecondScreenPresentation presentation;
    private boolean resumed;
    // Set once from AppActivity (a different package -- PartyPanelView.
    // SettingsHost is public for exactly that reason) and re-applied to every
    // panel this manager creates, since a fresh SecondScreenPresentation (and
    // therefore a fresh PartyPanelView) can be built at any time -- system-
    // initiated dismiss/recreate, sleep/wake, display churn -- see show()
    // below and the class doc's recovery notes.
    private PartyPanelView.SettingsHost settingsHost;

    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { update(); }
        @Override public void onDisplayRemoved(int displayId) { update(); }
        @Override public void onDisplayChanged(int displayId) { update(); }
    };

    // Set when the screen actually turned off since our last resume; gates the
    // forced recreate so healthy resumes (app switches, overlays) don't flash.
    private boolean screenWasOff;
    private final android.content.BroadcastReceiver screenOffReceiver =
            new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    screenWasOff = true;
                }
            };

    public SecondScreenManager(Activity activity) {
        this.activity = activity;
        this.displayManager = (DisplayManager) activity.getSystemService(Activity.DISPLAY_SERVICE);
        // Registered for the manager's whole life (not resume/pause) so it
        // can't miss the broadcast — SCREEN_ON/OFF fire before onResume runs.
        activity.registerReceiver(screenOffReceiver,
                new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
    }

    /**
     * The current presentation's live panel view, or null when no
     * presentation is showing (or its own view isn't created yet) -- see
     * {@link SecondScreenPresentation#getPanel()}. Null-safe at both hops so
     * callers never need to check {@code isShowing()} themselves.
     */
    public PartyPanelView getPanel() {
        return presentation != null ? presentation.getPanel() : null;
    }

    /**
     * The current presentation itself, or null when none is showing -- lets
     * {@code AppActivity} reach {@link SecondScreenPresentation#openModWebView}/
     * {@code closeModWebView} for the Nexus-page mod-get flow (see {@code
     * PartyPanelView.SettingsHost#requestModGet}), which needs the
     * Presentation (for its window/focus), not just its panel view.
     */
    public SecondScreenPresentation getPresentation() {
        return presentation;
    }

    /**
     * Sets (or clears, with null) the host wired onto every panel this
     * manager creates -- see {@link PartyPanelView.SettingsHost} and the
     * {@link #settingsHost} field doc. Applied immediately to the current
     * panel too, if one is showing.
     */
    public void setSettingsHost(PartyPanelView.SettingsHost host) {
        settingsHost = host;
        PartyPanelView panel = getPanel();
        if (panel != null) panel.setSettingsHost(host);
    }

    public void onDestroy() {
        try {
            activity.unregisterReceiver(screenOffReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    public void onResume() {
        resumed = true;
        displayManager.registerDisplayListener(listener, handler);
        update();
        if (screenWasOff) {
            screenWasOff = false;
            // After a genuine wake, the presentation created just above can sit
            // on a dead compositor surface while claiming isShowing() (see
            // class doc); one forced recreate shortly after revives it.
            handler.postDelayed(this::forceUpdate, 700);
        }
    }

    public void onPause() {
        resumed = false;
        displayManager.unregisterDisplayListener(listener);
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

    /** Unconditionally tears down and recreates the presentation, if a target
     *  display is available. Unlike update(), this ignores isShowing() -- see
     *  class doc for why that check alone can't detect a dead-but-"showing"
     *  surface after a sleep/wake cycle. */
    private void forceUpdate() {
        if (!resumed) return;
        dismiss();
        update();
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
            if (settingsHost != null) {
                PartyPanelView panel = p.getPanel();
                if (panel != null) panel.setSettingsHost(settingsHost);
            }
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
