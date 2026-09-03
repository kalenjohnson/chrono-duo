package com.kalenjohnson.chronoduo;

import android.util.SparseArray;

/**
 * Per-field-map calibration for transforming a live field-tile position
 * (see {@link PartySnapshot#fieldX}/{@code fieldY}, from {@link
 * GameState#nativeGetFieldPos()}) into pixel coordinates in the ORIGINAL
 * 256x192 DS area-minimap image space (before {@link PartyPanelView}'s
 * crop/scale into the panel -- see {@code AREA_MAP_SRC_*} there).
 *
 * <p>Each map's transform is a plain per-axis affine: {@code px = ox + tileX
 * * sx}, {@code py = oy + tileY * sy}, stored as {@code {sx, sy, ox, oy}}.
 * {@link #DEFAULT} is a placeholder -- NOT calibrated against any real map,
 * just a plausible tile-to-pixel scale/origin -- kept only so callers have
 * something to draw while real per-map entries are filled in below. Another
 * agent is deriving the true transforms from the DS ROM's map data; this
 * table (or a formula, if one generalizes across maps) is where those values
 * drop in, keyed by {@code mapId} (== {@link PartySnapshot#fieldMapId}).
 */
final class AreaMapCalib {
    private AreaMapCalib() {}

    // {sx, sy, ox, oy} -- see class doc. UNCALIBRATED placeholder: assumes an
    // 8px-per-tile DS map scaled to a 4px/tile minimap render, with the
    // minimap's own ~16px border (see PartyPanelView.AREA_MAP_SRC_L/T) as the
    // origin. Replace once real per-map transforms are known.
    private static final float[] DEFAULT = {4f, 4f, 16f, 16f};

    // Per-map overrides, keyed by mapId. Empty until real transforms are
    // derived; entries here take priority over DEFAULT.
    private static final SparseArray<float[]> OVERRIDES = new SparseArray<>();

    /**
     * Maps a live field-tile position ({@code tileX}, {@code tileY} -- see
     * {@link PartySnapshot#fieldX}/{@code fieldY}) for field map {@code
     * mapId} to a pixel coordinate in the original 256x192 DS image space,
     * written into {@code out[0]}/{@code out[1]}. Returns false (leaving
     * {@code out} untouched) when {@code mapId} is negative or either tile
     * coordinate is NaN.
     */
    static boolean toMapPixel(int mapId, float tileX, float tileY, float[] out) {
        if (mapId < 0 || Float.isNaN(tileX) || Float.isNaN(tileY)) return false;
        float[] xf = OVERRIDES.get(mapId);
        if (xf == null) xf = DEFAULT;
        out[0] = xf[2] + tileX * xf[0];
        out[1] = xf[3] + tileY * xf[1];
        return true;
    }
}
