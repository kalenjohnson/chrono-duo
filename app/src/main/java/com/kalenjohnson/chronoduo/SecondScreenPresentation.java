package com.kalenjohnson.chronoduo;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/** Companion screen: live party status panel polled from game memory. */
public final class SecondScreenPresentation extends Presentation {
    private static final long POLL_MS = 500;
    // Battle needs snappier command-button/targeting tracking than the field/
    // overworld case -- reschedule at this faster cadence whenever the last
    // read snapshot was mid-battle, and fall back to POLL_MS otherwise.
    private static final long POLL_MS_BATTLE = 150;

    // "Flash burst": a mapName change re-hides the UI immediately (see below),
    // but the game keeps re-asserting FieldMenu/WorldMenu visibility for a
    // short window right after the scene rebuild, so one immediate call can
    // still lose to a re-show that lands before AppActivity's 700ms backstop
    // tick fires -- that's the on-screen flash this burst closes. Once
    // started, it re-fires queueHiddenUiPatterns() every BURST_INTERVAL_MS
    // until BURST_DURATION_MS has elapsed; a new mapName change cancels
    // whatever burst is in flight and starts a fresh one, so at most one
    // burst is ever pending. The steady-state 700ms tick in AppActivity is
    // untouched -- this burst is purely an extra, denser pass right after a
    // scene change.
    private static final long BURST_INTERVAL_MS = 150;
    private static final long BURST_DURATION_MS = 3000;
    private long burstEndAtMs = -1L;
    private final Runnable burstTick = new Runnable() {
        @Override public void run() {
            GameState.queueHiddenUiPatterns();
            if (System.currentTimeMillis() < burstEndAtMs) {
                handler.postDelayed(this, BURST_INTERVAL_MS);
            }
        }
    };

    private void startHideBurst() {
        // Cancel any burst already in flight so only the newest one keeps
        // ticking -- "stop early if another burst starts."
        handler.removeCallbacks(burstTick);
        burstEndAtMs = System.currentTimeMillis() + BURST_DURATION_MS;
        burstTick.run(); // fires immediately, then reschedules itself
    }

    private PartyPanelView panel;
    private PartySnapshot last;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (GameState.isAttached()) {
                org.cocos2dx.lib.Cocos2dxHelper.runOnGLThread(GameState::nativeUpdateMapName);
                // refresh battle flag + toggle positions at panel cadence, not
                // just the 4s dump tick — command buttons must track the menu
                org.cocos2dx.lib.Cocos2dxHelper.runOnGLThread(GameState::nativeUpdateBattleFlag);
            }
            PartySnapshot snap = PartySnapshot.read();
            // A mapName change means a scene switch just happened (field<->
            // field, or field<->overworld) -- the game rebuilds its menu UI
            // nodes on that transition, so re-hide them immediately instead
            // of waiting for AppActivity's next 700ms tick, and keep re-
            // hiding at a denser cadence for a few seconds (see
            // startHideBurst) since the rebuild can re-show the UI again
            // right after this poll's single immediate call.
            if (last == null || !snap.mapName.equals(last.mapName)) {
                startHideBurst();
            }
            if (last == null || !snap.sameAs(last)) {
                last = snap;
                panel.update(snap);
            }
            handler.postDelayed(this, last != null && last.inBattle ? POLL_MS_BATTLE : POLL_MS);
        }
    };

    public SecondScreenPresentation(Context outerContext, Display display) {
        super(outerContext, display);
        // Never take focus: controller/touch input must stay with the game.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        panel = new PartyPanelView(getContext());
        setContentView(panel);
        hideSystemUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(poll);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(burstTick);
        super.onStop();
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
