package com.kalenjohnson.chronoduo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

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
    // 0-based line index into Localize/en/msg/monster.txt == monster id
    // (line 146 = "Gato", verified live). Null until extraction finishes.
    private static String[] monsterNames;
    // 0-based line index into Localize/en/msg/tech.txt / item.txt == tech/
    // item id (same ids as PartySnapshot.ListRow.id / GameState.
    // nativeGetBattleList()'s row id). Tech names may start with a star
    // glyph; kept as-is. Null until extraction finishes.
    private static String[] techNames;
    private static String[] itemNames;
    // Battle item-list row id encoding: (category << 14) | indexWithinCategory
    // -- item.txt's flat line-index table (itemNames above) does NOT cover
    // these ids (Potion arrived as 16385 = 0x4001, category 1). Parsed from
    // Localize/en/msg/sfc_item.txt (a CSV of
    // "MSG_SFC_ITEM_<CATEGORY>_<NNN>,<name>" lines), keyed "<CATEGORY>_<NNN>".
    // Category 1 == USEITEM is the only category confirmed to appear in
    // battle; other category numbers aren't known yet. Null until extraction
    // finishes. See getItemName.
    private static Map<String, String> itemCategoryNames;
    // Tech MP-cost table, index == tech id (same id space as techNames /
    // PartySnapshot.ListRow.id for tech rows). ListRow.extra was found NOT to
    // be the MP cost live (it only showed a value for a dual tech), so this
    // separate table is the real source -- see PartyPanelView's drawListRow.
    // Built from Game/common/TechnicMpTable.dat + TechnicBaseDataTable.dat
    // (see AppActivity#readTechMpTable) and set via setTechMpTable. Null
    // until extraction finishes (getTechMp returns -1 until then).
    private static int[] techMp;
    // Game/battle/tblb/MonsterNameData.dat: per-monster byte, index == monster
    // id (same ids as monsterNames / the battle actor block's +0x00 field).
    // 0 = normal enemy; 255 = the game hides this enemy's info in its own UI
    // (bosses/event enemies -- Gato id 146 is 255; all common early enemies
    // are 0). Null until extraction finishes.
    private static byte[] monsterFlags;
    private static final List<Listener> listeners = new ArrayList<>();

    /**
     * Uniform corner/edge inset, in the window-texture bitmap's own pixels,
     * used by PartyPanelView's manual 9-slice draw. Matches the beveled
     * border thickness AppActivity crops Extension/menu_win.png down to
     * (the panel's border resolves into flat fill texture by ~16px in from
     * each edge, verified by sampling the source PNG).
     */
    public static final int WINDOW_TEX_INSET = 16;

    // App's external files dir, set once from AppActivity (mirrors the
    // pattern used to find worldmap_hd.png -- see AppActivity#extractCompanionAssets),
    // so getAreaMap() below can locate <externalFilesDir>/ds_maps/*.png.
    private static File externalFilesDir;
    // App's private files dir (context.getFilesDir()), set once from
    // AppActivity. DS-derived maps decoded on-device by DsMapImporter land
    // here (never shipped, never pushed externally) -- checked before
    // externalFilesDir in getAreaMap() below, which stays as a dev-push
    // fallback.
    private static File filesDir;

    /** DS-style per-map minimap PNGs (rendered on-device from a user-provided ROM, or dev-pushed via adb -- never shipped): {@code <filesDir or externalFilesDir>/ds_maps/area_minimap_%03d.png}, 256x192. */
    private static final String AREA_MAP_SUBDIR = "ds_maps";
    private static final String AREA_MAP_FILENAME = "area_minimap_%03d.png";
    private static final int AREA_MAP_CACHE_CAP = 12;

    // Small LRU decode cache, plus a separate miss set so a map id with no
    // PNG on disk (the common case -- most maps aren't rendered yet) doesn't
    // hit the filesystem every frame PartyPanelView asks for it.
    private static final Map<Integer, Bitmap> areaMapCache =
            new LinkedHashMap<Integer, Bitmap>(AREA_MAP_CACHE_CAP, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap> eldest) {
                    return size() > AREA_MAP_CACHE_CAP;
                }
            };
    private static final Set<Integer> areaMapMisses = new HashSet<>();

    private ChronoAssets() {}

    public static Bitmap getFace() { return facePng; }
    public static Bitmap getWorldMap() { return worldMap; }
    public static Bitmap getMinimapMark() { return minimapMark; }
    public static Bitmap getWindowTex() { return windowTex; }
    public static String[] getMonsterNames() { return monsterNames; }
    public static byte[] getMonsterFlags() { return monsterFlags; }
    public static String[] getTechNames() { return techNames; }
    public static String[] getItemNames() { return itemNames; }

    /**
     * Resolves a battle item-list row id (encoded as
     * {@code (category << 14) | indexWithinCategory} -- see {@link
     * PartySnapshot.ListRow#id} for item rows) to a display name. Category 1
     * (USEITEM, the only category confirmed to appear in battle) is looked
     * up in {@link #itemCategoryNames} as {@code "USEITEM_%03d"}; any other
     * category, or a miss in that table, falls back to the flat item.txt
     * line-index table ({@link #itemNames}) using the raw encoded id, then to
     * {@code "#id"} when nothing matches.
     */
    public static String getItemName(int encodedId) {
        int idx = encodedId & 0x3FFF;
        int cat = encodedId >> 14;
        if (cat == 1 && itemCategoryNames != null) {
            String key = String.format(Locale.US, "USEITEM_%03d", idx);
            String name = itemCategoryNames.get(key);
            if (name != null && !name.trim().isEmpty()) return name;
        }
        if (itemNames != null && encodedId >= 0 && encodedId < itemNames.length
                && !itemNames[encodedId].trim().isEmpty()) {
            return itemNames[encodedId];
        }
        return "#" + encodedId;
    }

    /** Returns tech {@code techId}'s MP cost, or -1 when the table isn't loaded yet or the id is out of range -- see {@link #techMp}. */
    public static int getTechMp(int techId) {
        if (techMp == null || techId < 0 || techId >= techMp.length) return -1;
        return techMp[techId];
    }

    // Public (not package-private): populated from AppActivity, which lives
    // in org.cocos2dx.cpp — a different package — because the game binary
    // requires that exact class name for its JNI bindings.
    public static void setFace(Bitmap b) { facePng = b; notifyListeners(); }

    /** Stores the world-map bitmap after tinting it once to a weathered sepia parchment look (see {@link #sepiaTint}). */
    // true when the map bitmap is already at display aspect (HD override);
    // false for wb_mini.png, which is stored at half its display width
    private static boolean worldMapNaturalAspect;

    public static void setWorldMap(Bitmap b) { setWorldMap(b, false); }

    public static void setWorldMap(Bitmap b, boolean naturalAspect) {
        worldMap = b != null ? sepiaTint(b) : null;
        worldMapNaturalAspect = naturalAspect;
        notifyListeners();
    }

    public static boolean isWorldMapNaturalAspect() { return worldMapNaturalAspect; }
    public static void setMinimapMark(Bitmap b) { minimapMark = b; notifyListeners(); }
    public static void setWindowTex(Bitmap b) { windowTex = b; notifyListeners(); }

    /** Stores the monster name table (line index == monster id) and notifies listeners, so a battle panel already open when extraction finishes repaints with real names. */
    public static void setMonsterNames(String[] names) { monsterNames = names; notifyListeners(); }

    /** Stores the monster flag table (byte index == monster id; 255 = hide info) and notifies listeners. */
    public static void setMonsterFlags(byte[] flags) { monsterFlags = flags; notifyListeners(); }

    /** Stores the tech name table (line index == tech id) and notifies listeners, mirroring {@link #setMonsterNames}. */
    public static void setTechNames(String[] names) { techNames = names; notifyListeners(); }

    /** Stores the item name table (line index == item id) and notifies listeners, mirroring {@link #setMonsterNames}. */
    public static void setItemNames(String[] names) { itemNames = names; notifyListeners(); }

    /** Stores the sfc_item.txt-derived category name map (key "<CATEGORY>_<NNN>") used by {@link #getItemName(int)}, and notifies listeners. */
    public static void setItemCategoryNames(Map<String, String> names) { itemCategoryNames = names; notifyListeners(); }

    /** Stores the tech MP-cost table (index == tech id) used by {@link #getTechMp(int)}, and notifies listeners. */
    public static void setTechMpTable(int[] table) { techMp = table; notifyListeners(); }

    /**
     * Records the app's external files dir, so {@link #getAreaMap(int)} can
     * find {@code <dir>/ds_maps/area_minimap_%03d.png}. Set once from
     * AppActivity, the same place that resolves worldmap_hd.png.
     */
    public static void setExternalFilesDir(File dir) { externalFilesDir = dir; }

    /**
     * Records the app's private files dir (context.getFilesDir()), so {@link
     * #getAreaMap(int)} can find {@code <dir>/ds_maps/area_minimap_%03d.png}
     * written there by DsMapImporter. Checked before {@link
     * #externalFilesDir}. Set once from AppActivity.
     */
    public static void setFilesDir(File dir) { filesDir = dir; }

    /**
     * Lazily decodes and caches the rendered DS-style area minimap for field
     * map {@code id} (256x192 PNG at {@code area_minimap_%03d.png} under
     * either {@link #filesDir}/ds_maps -- the on-device DsMapImporter output
     * -- or, as a dev-push fallback, {@link #externalFilesDir}/ds_maps;
     * whichever has the file wins, filesDir checked first). Returns null when
     * neither dir is set yet, {@code id} is negative, or no PNG exists for
     * this id in either location (a known-missing id is remembered so
     * repeated calls -- e.g. once per frame from PartyPanelView -- don't
     * re-hit the filesystem). Main thread only, like the rest of ChronoAssets.
     */
    public static Bitmap getAreaMap(int id) {
        if (id < 0) return null;
        Bitmap cached = areaMapCache.get(id);
        if (cached != null) return cached;
        if (areaMapMisses.contains(id)) return null;
        File f = resolveAreaMapFile(id);
        Bitmap b = f != null ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        if (b == null) {
            areaMapMisses.add(id);
            return null;
        }
        areaMapCache.put(id, b);
        return b;
    }

    /** Resolves the on-disk file for area map {@code id}, filesDir first, externalFilesDir as fallback -- see {@link #getAreaMap(int)}. */
    private static File resolveAreaMapFile(int id) {
        String name = String.format(Locale.US, AREA_MAP_FILENAME, id);
        if (filesDir != null) {
            File f = new File(new File(filesDir, AREA_MAP_SUBDIR), name);
            if (f.isFile()) return f;
        }
        if (externalFilesDir != null) {
            File f = new File(new File(externalFilesDir, AREA_MAP_SUBDIR), name);
            if (f.isFile()) return f;
        }
        return null;
    }

    /**
     * Drops the area-minimap decode cache and known-missing set, so the next
     * {@link #getAreaMap(int)} call re-hits the filesystem instead of
     * returning a stale miss/hit from before an import wrote new files.
     * Called after a successful DS ROM import. Notifies listeners so a panel
     * already showing a (now stale) map or its "no map yet" fallback repaints.
     */
    public static void clearAreaMapCache() {
        areaMapCache.clear();
        areaMapMisses.clear();
        notifyListeners();
    }

    /** Registers a listener; if any asset is already loaded, fires immediately so late attachers (e.g. a Presentation created after the background load finished) don't miss it. */
    public static void addListener(Listener l) {
        listeners.add(l);
        if (facePng != null || worldMap != null || minimapMark != null || windowTex != null
                || monsterNames != null || monsterFlags != null
                || techNames != null || itemNames != null
                || itemCategoryNames != null || techMp != null) {
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
