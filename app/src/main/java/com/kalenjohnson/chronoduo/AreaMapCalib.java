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
 * Transforms are derived on-device by DsMapImporter from the user's own DS
 * ROM and written to {@code area_calib.json} (one JSON object keyed by
 * decimal map id, each value {@code {"sx":.., "sy":.., "ox":.., "oy":..}});
 * {@link #load(File)} parses that file into {@link #TABLE}. Maps without
 * an entry (about 94 rooms carry a no-rect flag in the ROM table) get no
 * marker: {@link #toMapPixel} returns false and there is no built-in
 * fallback transform.
 */
public final class AreaMapCalib {
    private static final String TAG = "AreaMapCalib";

    private AreaMapCalib() {}

    // Per-map {sx, sy, ox, oy}, keyed by map id; filled by load().
    private static final SparseArray<float[]> TABLE = new SparseArray<>();

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
        float[] xf = TABLE.get(mapId);
        // No calibration for this map (e.g. the ~94 rooms whose ROM table
        // entry carries the no-rect flag): draw no marker rather than a
        // wrong one.
        if (xf == null) return false;
        out[0] = xf[2] + tileX * xf[0];
        out[1] = xf[3] + tileY * xf[1];
        return true;
    }

    /**
     * Loads {@code jsonFile} (an {@code area_calib.json} written by
     * DsMapImporter: a JSON object keyed by decimal map id string, each value
     * {@code {"sx":.., "sy":.., "ox":.., "oy":..}}) and merges its entries
     * into {@link #TABLE}, overwriting any existing entry for the same
     * id (including the hardcoded map-5 fallback above). Called from
     * AppActivity at startup (with whichever of filesDir/ds_maps or
     * externalFilesDir/ds_maps has the file, filesDir preferred) and again
     * after a successful ROM import completes. Best-effort: a missing file,
     * unreadable file, or malformed JSON is logged and leaves {@link
     * #TABLE} exactly as it was (a bad per-entry value is skipped, not
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
                TABLE.put(id, new float[]{sx, sy, ox, oy});
                loaded++;
            } catch (JSONException e) {
                Log.w(TAG, "skipping malformed calib entry for map " + id, e);
            }
        }
        Log.i(TAG, "loaded " + loaded + " area map calibration entries from " + jsonFile);
    }
}
