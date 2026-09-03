package com.kalenjohnson.chronoduo;

import android.util.Log;
import android.util.SparseArray;

import org.json.JSONArray;
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
 * ROM and written to {@code area_calib.json}, one JSON object keyed by
 * decimal ROOM id (NOT the minimap PNG's file id -- the DS maps room ids to
 * minimap file ids through a ROM table that is not the identity: e.g. the
 * Cathedral, room id 129, has no {@code area_minimap_129.png} -- its content
 * lives under a different file id, resolved via {@link #fileIdFor(int)}).
 * Each value is {@code {"file": <int>, "sx":.., "sy":.., "ox":.., "oy":..,
 * "rect_tiles":[x0,y0,x1,y1]}} for a single-floor room, or {@code {"file":
 * <int>, "floors": [ {"file":.., "suffix":.., "sx":.., ...}, ... ]}} for a
 * multi-floor room (no top-level transform in that case -- each floor
 * carries its own). {@link #load(File)} parses that file into {@link
 * #TABLE}/{@link #FLOORS}/{@link #FILES}.
 */
public final class AreaMapCalib {
    private static final String TAG = "AreaMapCalib";

    private AreaMapCalib() {}

    /** One floor variant's own file id, filename suffix, and transform. */
    private static final class Floor {
        int file;
        int suffix;
        float sx, sy, ox, oy;
        float x0, y0, x1, y1;
    }

    // Per-room single-floor transform {sx, sy, ox, oy}, keyed by room id.
    // Absent for multi-floor rooms (see FLOORS below) and for rooms with
    // no calibration entry at all.
    private static final SparseArray<float[]> TABLE = new SparseArray<>();
    // Per-room minimap file id (the "%03d" component of area_minimap_%03d.png),
    // present for every loaded room -- single- and multi-floor alike.
    private static final SparseArray<Integer> FILES = new SparseArray<>();
    // Per-room floor list, present only for multi-floor rooms.
    private static final SparseArray<Floor[]> FLOORS = new SparseArray<>();

    /**
     * Resolves the minimap PNG file id for {@code roomId} (the "%03d" in
     * {@code area_minimap_%03d.png}), ignoring live position -- for
     * multi-floor rooms this is the first floor variant's file id, a
     * reasonable default when no position is known yet. Returns {@code
     * roomId} itself (the pre-fix dev fallback: file id == room id) when
     * the calibration table isn't loaded or carries no entry for this
     * room, so unconfigured/dev setups keep working.
     */
    public static int fileIdFor(int roomId) {
        Integer f = FILES.get(roomId);
        return f != null ? f : roomId;
    }

    /**
     * Position-aware variant of {@link #fileIdFor(int)}: for a multi-floor
     * room, picks the floor whose {@code rect_tiles} contains ({@code
     * tileX}, {@code tileY}) (first match wins), falling back to the first
     * floor when none contains it or either coordinate is NaN. Single-floor
     * rooms and unknown rooms behave exactly like {@link #fileIdFor(int)}.
     */
    public static int fileIdFor(int roomId, float tileX, float tileY) {
        Floor f = pickFloor(roomId, tileX, tileY);
        if (f != null) return f.file;
        return fileIdFor(roomId);
    }

    /**
     * Filename suffix (the "_N" in {@code area_minimap_%03d_N.png}; 0 means
     * no suffix) matching whichever floor {@link #fileIdFor(int, float,
     * float)} would pick for the same arguments. Single-floor rooms always
     * return 0 (their PNGs are never suffixed).
     */
    public static int suffixFor(int roomId, float tileX, float tileY) {
        Floor f = pickFloor(roomId, tileX, tileY);
        return f != null ? f.suffix : 0;
    }

    private static Floor pickFloor(int roomId, float tileX, float tileY) {
        Floor[] floors = FLOORS.get(roomId);
        if (floors == null || floors.length == 0) return null;
        if (!Float.isNaN(tileX) && !Float.isNaN(tileY)) {
            for (Floor f : floors) {
                if (tileX >= f.x0 && tileX <= f.x1 && tileY >= f.y0 && tileY <= f.y1) {
                    return f;
                }
            }
        }
        return floors[0];
    }

    /**
     * Maps a live field-tile position ({@code tileX}, {@code tileY} -- see
     * {@link PartySnapshot#fieldX}/{@code fieldY}) for field map {@code
     * roomId} to a pixel coordinate in the original 256x192 DS image space,
     * written into {@code out[0]}/{@code out[1]}. For a multi-floor room,
     * uses the floor whose {@code rect_tiles} contains the tile position
     * (falling back to the first floor). Returns false (leaving {@code out}
     * untouched) when {@code roomId} is negative, either tile coordinate is
     * NaN, or there is no calibration entry for this room.
     */
    static boolean toMapPixel(int roomId, float tileX, float tileY, float[] out) {
        if (roomId < 0 || Float.isNaN(tileX) || Float.isNaN(tileY)) return false;

        Floor[] floors = FLOORS.get(roomId);
        if (floors != null) {
            Floor f = pickFloor(roomId, tileX, tileY);
            if (f == null) return false;
            out[0] = f.ox + tileX * f.sx;
            out[1] = f.oy + tileY * f.sy;
            return true;
        }

        float[] xf = TABLE.get(roomId);
        if (xf == null) return false;
        out[0] = xf[2] + tileX * xf[0];
        out[1] = xf[3] + tileY * xf[1];
        return true;
    }

    /**
     * Loads {@code jsonFile} (an {@code area_calib.json} written by
     * DsMapImporter: a JSON object keyed by decimal ROOM id string -- see
     * class doc for the v5 schema) and merges its entries into {@link
     * #TABLE}/{@link #FILES}/{@link #FLOORS}, overwriting any existing
     * entry for the same id. Called from AppActivity at startup (with
     * whichever of filesDir/ds_maps or externalFilesDir/ds_maps has the
     * file, filesDir preferred) and again after a successful ROM import
     * completes. Best-effort: a missing file, unreadable file, or malformed
     * JSON is logged and leaves the tables exactly as they were (a bad
     * per-entry value is skipped, not fatal to the rest of the file).
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
                Log.w(TAG, "skipping non-numeric room id key: " + key);
                continue;
            }
            try {
                JSONObject entry = root.getJSONObject(key);
                int file = entry.getInt("file");
                FILES.put(id, file);

                JSONArray floorsArr = entry.optJSONArray("floors");
                if (floorsArr != null) {
                    Floor[] floors = new Floor[floorsArr.length()];
                    for (int i = 0; i < floorsArr.length(); i++) {
                        JSONObject fj = floorsArr.getJSONObject(i);
                        Floor f = new Floor();
                        f.file = fj.getInt("file");
                        f.suffix = fj.optInt("suffix", 0);
                        f.sx = (float) fj.getDouble("sx");
                        f.sy = (float) fj.getDouble("sy");
                        f.ox = (float) fj.getDouble("ox");
                        f.oy = (float) fj.getDouble("oy");
                        JSONArray rect = fj.getJSONArray("rect_tiles");
                        f.x0 = (float) rect.getDouble(0);
                        f.y0 = (float) rect.getDouble(1);
                        f.x1 = (float) rect.getDouble(2);
                        f.y1 = (float) rect.getDouble(3);
                        floors[i] = f;
                    }
                    FLOORS.put(id, floors);
                    TABLE.remove(id);
                } else {
                    float sx = (float) entry.getDouble("sx");
                    float sy = (float) entry.getDouble("sy");
                    float ox = (float) entry.getDouble("ox");
                    float oy = (float) entry.getDouble("oy");
                    TABLE.put(id, new float[]{sx, sy, ox, oy});
                    FLOORS.remove(id);
                }
                loaded++;
            } catch (JSONException e) {
                Log.w(TAG, "skipping malformed calib entry for room " + id, e);
            }
        }
        Log.i(TAG, "loaded " + loaded + " area map calibration entries from " + jsonFile);
    }
}
