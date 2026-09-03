package com.kalenjohnson.chronoduo;

import android.util.Log;
import android.util.SparseArray;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;

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
 * something to draw while real per-map entries are filled in. Real
 * transforms are derived on-device by DsMapImporter from the user's own DS
 * ROM and written to {@code area_calib.json} (one JSON object keyed by
 * decimal map id, each value {@code {"sx":.., "sy":.., "ox":.., "oy":..}});
 * {@link #load(File)} parses that file into {@link #OVERRIDES}, which take
 * priority over both {@link #DEFAULT} and the hardcoded map-5 entry below.
 */
public final class AreaMapCalib {
    private static final String TAG = "AreaMapCalib";

    private AreaMapCalib() {}

    // {sx, sy, ox, oy} -- see class doc. UNCALIBRATED placeholder: assumes an
    // 8px-per-tile DS map scaled to a 4px/tile minimap render, with the
    // minimap's own ~16px border (see PartyPanelView.AREA_MAP_SRC_L/T) as the
    // origin. Replace once real per-map transforms are known.
    private static final float[] DEFAULT = {4f, 4f, 16f, 16f};

    // Per-map overrides, keyed by mapId. A JSON entry loaded via load() for a
    // given id always wins over a hardcoded entry for that same id (load()
    // simply overwrites the SparseArray slot) -- see class doc.
    private static final SparseArray<float[]> OVERRIDES = new SparseArray<>();
    static {
        // Leene Square: calibrated live 2026-09-04 from three landmarks
        // (north stairs (24.5,23.0)->(128,40), fountain (24.2,34.9)->(128,97),
        // south exit (24.5,46.6)->(123,150)); ~4.66 px/tile, the minimap is
        // a crop of the room. X scale assumed equal to Y (unverified).
        // Kept only as a fallback for map 5 until/unless area_calib.json
        // (see load()) supplies its own entry for id 5.
        OVERRIDES.put(5, new float[] {4.04f, 4.66f, 29f, -67f}); // X from west wall: tile 2.49 -> px 39
    }

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

    /**
     * Loads {@code jsonFile} (an {@code area_calib.json} written by
     * DsMapImporter: a JSON object keyed by decimal map id string, each value
     * {@code {"sx":.., "sy":.., "ox":.., "oy":..}}) and merges its entries
     * into {@link #OVERRIDES}, overwriting any existing entry for the same
     * id (including the hardcoded map-5 fallback above). Called from
     * AppActivity at startup (with whichever of filesDir/ds_maps or
     * externalFilesDir/ds_maps has the file, filesDir preferred) and again
     * after a successful ROM import completes. Best-effort: a missing file,
     * unreadable file, or malformed JSON is logged and leaves {@link
     * #OVERRIDES} exactly as it was (a bad per-entry value is skipped, not
     * fatal to the rest of the file).
     */
    public static void load(File jsonFile) {
        if (jsonFile == null || !jsonFile.isFile()) return;
        String text;
        try {
            text = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "failed to read " + jsonFile, e);
            return;
        }
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException e) {
            Log.w(TAG, "failed to parse " + jsonFile, e);
            return;
        }
        Iterator<String> keys = root.keys();
        int loaded = 0;
        while (keys.hasNext()) {
            String key = keys.next();
            int id;
            try {
                id = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                Log.w(TAG, "skipping non-numeric map id key: " + key);
                continue;
            }
            try {
                JSONObject entry = root.getJSONObject(key);
                float sx = (float) entry.getDouble("sx");
                float sy = (float) entry.getDouble("sy");
                float ox = (float) entry.getDouble("ox");
                float oy = (float) entry.getDouble("oy");
                OVERRIDES.put(id, new float[]{sx, sy, ox, oy});
                loaded++;
            } catch (JSONException e) {
                Log.w(TAG, "skipping malformed calib entry for map " + id, e);
            }
        }
        Log.i(TAG, "loaded " + loaded + " area map calibration entries from " + jsonFile);
    }
}
