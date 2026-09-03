package com.kalenjohnson.chronoduo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/**
 * Static holder for companion-UI art decoded from resources.bin. Populated
 * once, on the main thread, by AppActivity after a background extraction
 * pass; PartyPanelView reads it lazily and falls back to placeholders when a
 * bitmap isn't set (game assets missing, extraction failed, or not finished
 * yet). All access must happen on the main thread.
 */
public final class ChronoAssets {
    /** Notified on the main thread whenever a new bitmap becomes available. */
    public interface Listener {
        void onChronoAssetsChanged();
    }

    private static Bitmap facePng;
    private static Bitmap worldMap;
    private static Bitmap minimapMark;
    private static Bitmap windowTex;
    private static final List<Listener> listeners = new ArrayList<>();

    /**
     * Uniform corner/edge inset, in the window-texture bitmap's own pixels,
     * used by PartyPanelView's manual 9-slice draw. Matches the beveled
     * border thickness AppActivity crops Extension/menu_win.png down to
     * (the panel's border resolves into flat fill texture by ~16px in from
     * each edge, verified by sampling the source PNG).
     */
    public static final int WINDOW_TEX_INSET = 16;

    private ChronoAssets() {}

    public static Bitmap getFace() { return facePng; }
    public static Bitmap getWorldMap() { return worldMap; }
    public static Bitmap getMinimapMark() { return minimapMark; }
    public static Bitmap getWindowTex() { return windowTex; }

    // Public (not package-private): populated from AppActivity, which lives
    // in org.cocos2dx.cpp — a different package — because the game binary
    // requires that exact class name for its JNI bindings.
    public static void setFace(Bitmap b) { facePng = b; notifyListeners(); }

    /** Stores the world-map bitmap after tinting it once to a weathered sepia parchment look (see {@link #sepiaTint}). */
    public static void setWorldMap(Bitmap b) { worldMap = b != null ? sepiaTint(b) : null; notifyListeners(); }
    public static void setMinimapMark(Bitmap b) { minimapMark = b; notifyListeners(); }
    public static void setWindowTex(Bitmap b) { windowTex = b; notifyListeners(); }

    /** Registers a listener; if any asset is already loaded, fires immediately so late attachers (e.g. a Presentation created after the background load finished) don't miss it. */
    public static void addListener(Listener l) {
        listeners.add(l);
        if (facePng != null || worldMap != null || minimapMark != null || windowTex != null) {
            l.onChronoAssetsChanged();
        }
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onChronoAssetsChanged();
    }

    /**
     * Desaturates and warms a bitmap toward aged sepia parchment: low
     * saturation, then a per-channel scale+offset that pushes cool blues
     * (ocean) down to dark brown and greens (land) to mid-brown ink instead
     * of the minimap's clean colorful look. Computed once per bitmap (called
     * from {@link #setWorldMap}) rather than every frame; the caller
     * composites the result at reduced opacity so the parchment ground still
     * shows through.
     */
    private static Bitmap sepiaTint(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0.15f);
        ColorMatrix tone = new ColorMatrix(new float[]{
                1.1f, 0f,    0f,   0f, 20f,
                0f,   0.95f, 0f,   0f, 10f,
                0f,   0f,    0.7f, 0f, -10f,
                0f,   0f,    0f,   1f, 0f,
        });
        cm.postConcat(tone);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(src, 0, 0, p);
        return out;
    }
}
