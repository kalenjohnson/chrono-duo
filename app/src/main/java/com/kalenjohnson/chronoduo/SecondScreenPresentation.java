package com.kalenjohnson.chronoduo;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Companion screen: live party status panel polled from game memory. */
public final class SecondScreenPresentation extends Presentation {
    private static final long POLL_MS = 500;
    // Battle needs snappier command-button/targeting tracking than the field/
    // overworld case -- reschedule at this faster cadence whenever the last
    // read snapshot was mid-battle, and fall back to POLL_MS otherwise.
    private static final long POLL_MS_BATTLE = 150;

    // The old post-scene-change "hide burst" (a denser 150ms re-hide pass for
    // a few seconds after a mapName change) was removed: GameState's per-
    // frame enforcer (see AppActivity.startFrameEnforcer /
    // nativeEnforceUiTick) now re-parks FieldMenu/WorldMenu every rendered
    // frame, which closes the same post-transition flash window faster than
    // any polling burst could. A mapName change no longer needs a special
    // reaction here at all.

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
            // Live overworld map: arms a capture of the game's own pre-
            // rendered world RenderTextures when the overworld appears or the
            // era changes, and publishes any capture the GL thread finished.
            // Cheap (two JNI scalar reads) when there's nothing to do.
            WorldMapLive.tick(snap);
            if (last == null || !snap.sameAs(last)) {
                last = snap;
                panel.update(snap);
            }
            handler.postDelayed(this, last != null && last.inBattle ? POLL_MS_BATTLE : POLL_MS);
        }
    };

    // --- mod-page in-app WebView overlay (see PartyPanelView.SettingsHost#requestModGet /
    // AppActivity#openModWebView) ------------------------------------------------------
    // Lazily created on first use, torn down (destroy()'d) in onStop so a
    // system-initiated Presentation recreate (screen churn, sleep/wake --
    // see SecondScreenManager's class doc) never leaves a stale WebView
    // holding a Surface. Lives in `root`, a FrameLayout wrapping `panel` --
    // getPanel() still returns the bare PartyPanelView (physical-controller
    // routing in AppActivity expects that), the overlay just sits on top.
    private FrameLayout root;
    private LinearLayout webOverlay;
    private WebView webView;
    private TextView webTitle;
    private ModWebViewHost webHost;

    /** Callback for {@link #openModWebView}: {@code onDownloadStart} fires once per WebView-initiated download (see {@link android.webkit.DownloadListener}); {@code onClosed} fires when the overlay is dismissed (Close button or {@link #closeModWebView}), whichever comes first. */
    public interface ModWebViewHost {
        void onDownloadStart(String url, String userAgent, String contentDisposition,
                              String mimeType, long contentLength, String cookie);
        void onClosed();
    }

    public SecondScreenPresentation(Context outerContext, Display display) {
        super(outerContext, display);
        // Never take focus by default: controller/touch input must stay with
        // the game. Cleared only while the mod WebView overlay is open (a
        // WebView cannot accept typed input -- e.g. a Nexus login -- in a
        // non-focusable window) and restored the moment it closes or the
        // Presentation stops; see openModWebView/closeModWebView/onStop.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        panel = new PartyPanelView(getContext());
        root = new FrameLayout(getContext());
        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemUi();
    }

    /**
     * The live panel view, or null before {@link #onCreate} has run. Lets
     * physical-controller routing (see {@code AppActivity#dispatchKeyEvent},
     * {@link GameControllerInput}) offer d-pad-left/-right/A to the panel's
     * own command-row navigation before the game sees it.
     */
    public PartyPanelView getPanel() {
        return panel;
    }

    /**
     * Opens {@code url} full-screen over the panel, in a WebView with
     * JavaScript + DOM storage enabled and cookies persisted across launches
     * (the {@link CookieManager} default) -- needed for a Nexus Mods login.
     * A top bar shows the page title and a Close button. The window is made
     * focusable for the duration (see the constructor's note) so the page
     * can actually receive typed input; {@code host} is told about every
     * download the page starts (see {@link ModWebViewHost}) and about the
     * close. No-op if {@code root} isn't ready yet (before {@link #onCreate}).
     */
    public void openModWebView(String url, ModWebViewHost host) {
        if (root == null) return;
        webHost = host;
        if (webOverlay == null) buildWebOverlay();
        webOverlay.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        webView.requestFocus();
        webView.loadUrl(url);
    }

    /** Updates the overlay's top-bar title/status text (e.g. "Downloading…") without navigating the page -- see {@code AppActivity#downloadAndImportMod}. No-op if the overlay isn't open. */
    public void setWebViewStatus(String text) {
        if (webTitle != null) webTitle.setText(text);
    }

    /** Closes the mod WebView overlay (if open), restores the non-focusable flag, and fires {@link ModWebViewHost#onClosed} exactly once. Safe to call when already closed. */
    public void closeModWebView() {
        boolean wasOpen = webOverlay != null && webOverlay.getVisibility() == View.VISIBLE;
        if (webOverlay != null) webOverlay.setVisibility(View.GONE);
        if (webView != null) webView.loadUrl("about:blank");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        ModWebViewHost h = webHost;
        webHost = null;
        if (wasOpen && h != null) h.onClosed();
    }

    private void buildWebOverlay() {
        Context ctx = getContext();
        webView = new WebView(ctx);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient()); // keep navigation inside this WebView, not a system browser
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView view, String title) {
                if (webTitle != null && title != null) webTitle.setText(title);
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (webHost != null) {
                webHost.onDownloadStart(url, userAgent, contentDisposition, mimeType, contentLength, cookie);
            }
        });

        Button close = new Button(ctx);
        close.setText("Close");
        close.setOnClickListener(v -> closeModWebView());

        webTitle = new TextView(ctx);
        webTitle.setTextColor(Color.WHITE);
        webTitle.setGravity(Gravity.CENTER_VERTICAL);
        webTitle.setSingleLine(true);
        int pad = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        webTitle.setPadding(pad, 0, pad, 0);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.BLACK);
        bar.setPadding(pad, pad, pad, pad);
        bar.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(webTitle, titleLp);

        webOverlay = new LinearLayout(ctx);
        webOverlay.setOrientation(LinearLayout.VERTICAL);
        webOverlay.setBackgroundColor(Color.BLACK);
        webOverlay.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        webOverlay.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        webOverlay.setVisibility(View.GONE);
        root.addView(webOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(poll);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(poll);
        // Defensive: never leave the window focusable (and stealing
        // controller/touch focus from the game) once this Presentation stops,
        // regardless of whether the WebView overlay was cleanly closed first.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        if (webOverlay != null && root != null) {
            root.removeView(webOverlay);
        }
        webOverlay = null;
        webTitle = null;
        webHost = null;
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
