package com.kalenjohnson.chronoduo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static Bitmap minimapMark;
    // Cell 2 of Game/common/minimap_mark.png. WorldMap::markMiniMap uses it
    // for the Epoch ("silverd" -- シルバード), the time machine, drawn on the
    // world map only while the Epoch is parked in the era being shown. It is
    // NOT the generic "point of interest" pin an earlier reading assumed.
    private static Bitmap epochMark;
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
    // 0-based line index into Localize/en/msg/battle.txt == battle message
    // id (line 37 "Earned <NUMBER> EXP.", 38 TP, 39 G, 40 "Obtained
    // <NAME_ITM>.", 41 level-up, 42-44 tech-learned -- see
    // battle_results_report.md). Placeholders are literal tokens
    // ("<NUMBER>", "<NAME_ITM>", ...), not printf-style. Null until
    // extraction finishes; getBattleMessage falls back to a literal string
    // when this table or the requested id is missing.
    private static String[] battleMessages;
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
     * Default uniform corner/edge inset, in the window-texture bitmap's own
     * pixels, used by PartyPanelView's manual 9-slice draw. Matches the
     * beveled border thickness AppActivity crops Extension/menu_win.png down
     * to at its ORIGINAL 512x512 sheet size (the panel's border resolves
     * into flat fill texture by ~16px in from each edge, verified by
     * sampling the source PNG). A mod that ships a differently-sized
     * menu_win.png gets a proportionally scaled inset instead -- see
     * {@link #setWindowTex(Bitmap, int)} and AppActivity's
     * {@code cropWindowTexture}.
     */
    public static final int WINDOW_TEX_INSET_DEFAULT = 16;

    /** Current inset for {@link #getWindowTex()} -- see {@link #setWindowTex(Bitmap, int)}. */
    private static int windowTexInset = WINDOW_TEX_INSET_DEFAULT;

    // App's external files dir, set once from AppActivity (mirrors the
    // pattern used to find the rendered world maps -- see AppActivity#renderWorldMaps),
    // so getAreaMap() below can locate <externalFilesDir>/ds_maps/*.png.
    private static File externalFilesDir;
    // App's private files dir (context.getFilesDir()), set once from
    // AppActivity. DS-derived maps decoded on-device by DsMapImporter land
    // here (never shipped, never pushed externally) -- checked before
    // externalFilesDir in getAreaMap() below, which stays as a dev-push
    // fallback.
    private static File filesDir;

    /**
     * DS-style per-map minimap PNGs (rendered on-device from a user-provided
     * ROM, or dev-pushed via adb -- never shipped): {@code <filesDir or
     * externalFilesDir>/ds_maps/area_minimap_%03d.png} (or, for a suffixed
     * floor variant, {@code area_minimap_%03d_%d.png}), 256x192.
     */
    private static final String AREA_MAP_SUBDIR = "ds_maps";
    private static final String AREA_MAP_FILENAME = "area_minimap_%03d.png";
    private static final String AREA_MAP_FILENAME_SUFFIXED = "area_minimap_%03d_%d.png";
    private static final int AREA_MAP_CACHE_CAP = 12;
    // Multiplier used to fold (fileId, suffix) into one cache key int --
    // see getAreaMap(int, float, float). Suffixes seen in the ROM top out
    // in the single digits; 1000 leaves plenty of headroom.
    private static final int AREA_MAP_CACHE_KEY_SUFFIX_MULT = 1000;

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
    public static Bitmap getMinimapMark() { return minimapMark; }
    /** Cell 2 of minimap_mark.png -- the Epoch marker; see {@link #epochMark}. */
    public static Bitmap getEpochMark() { return epochMark; }
    public static Bitmap getWindowTex() { return windowTex; }
    public static String[] getMonsterNames() { return monsterNames; }
    public static byte[] getMonsterFlags() { return monsterFlags; }
    public static String[] getTechNames() { return techNames; }
    public static String[] getItemNames() { return itemNames; }
    public static String[] getBattleMessages() { return battleMessages; }

    // Literal fallback strings (mirroring battle.txt lines 37-40) used by
    // getBattleMessage when battleMessages isn't loaded yet -- keeps
    // PartyPanelView's results window readable even before extraction
    // finishes or if the table is missing from this ROM dump.
    private static final String[] BATTLE_MESSAGE_FALLBACK = {
            "Earned <NUMBER> EXP.",   // 37
            "Earned <NUMBER> TP.",    // 38
            "Found <NUMBER> G.",      // 39
            "Obtained <NAME_ITM>.",   // 40
            "<NAME_CHR> leveled up!", // 41
            "<NAME_CHR> learned <NAME_TEC>!", // 42
            "Learned <NAME_TEC>!",    // 43 (dual tech)
            "Learned <NAME_TEC>!",    // 44 (triple tech)
    };
    public static final int BATTLE_MSG_EXP = 37, BATTLE_MSG_TP = 38, BATTLE_MSG_GOLD = 39,
            BATTLE_MSG_ITEM = 40, BATTLE_MSG_LEVEL_UP = 41, BATTLE_MSG_TECH = 42,
            BATTLE_MSG_DUAL_TECH = 43, BATTLE_MSG_TRIPLE_TECH = 44;
    private static final java.util.regex.Pattern MSG_TAG = java.util.regex.Pattern.compile("<[A-Z_0-9]+>");
    private static final int BATTLE_MESSAGE_FALLBACK_BASE = 37;

    /**
     * Resolves battle.txt line {@code id}, substituting the first literal
     * {@code <NUMBER>} token (if any) with {@code value} formatted as a
     * plain decimal. Falls back to {@link #BATTLE_MESSAGE_FALLBACK} when the
     * real table isn't loaded or doesn't cover {@code id}; if even that
     * fails, returns {@code null} so the caller can keep its last message.
     */
    /**
     * Like {@link #getBattleMessage(int, int)} but substitutes the
     * template's {@code <TAG>} placeholders in order with {@code args}
     * (any left over are dropped). Null when no template resolves.
     */
    public static String getBattleMessageText(int id, String... args) {
        String template = null;
        if (battleMessages != null && id >= 0 && id < battleMessages.length) {
            template = battleMessages[id];
        }
        if (template == null || template.trim().isEmpty()) {
            int fi = id - BATTLE_MESSAGE_FALLBACK_BASE;
            if (fi >= 0 && fi < BATTLE_MESSAGE_FALLBACK.length) template = BATTLE_MESSAGE_FALLBACK[fi];
        }
        if (template == null) return null;
        java.util.regex.Matcher m = MSG_TAG.matcher(template);
        StringBuffer out = new StringBuffer();
        int n = 0;
        while (m.find()) {
            String rep = n < args.length && args[n] != null ? args[n] : "";
            n++;
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString();
    }

    // First item.txt line of each drop category (cSfcWork::TopIndex @0xbe0348
    // in libchrono.so, first six entries: weapon, armor, helmet, accessory,
    // consumable, key). Battle drop ids (exp_get) are (category << 12) |
    // index; the name is the flat item.txt line TopIndex[category] + index.
    private static final int[] DROP_TOP_INDEX = {0, 111, 161, 200, 259, 302};

    /** Display name for a battle-results drop id (see {@link #DROP_TOP_INDEX}); "#id" when the table can't resolve it. */
    public static String getDropItemName(int dropId) {
        // A consumable drop (category 4 << 12 | n) is bit-identical to the
        // battle item list's USEITEM encoding (1 << 14 | n), and that
        // sfc_item.txt lookup is already verified live (0x4001 = Potion), so
        // prefer it; the TopIndex line math below covers the other
        // categories (equipment drops).
        int cat = dropId >> 12, idx = dropId & 0xfff;
        if (cat == 4) {
            String viaList = getItemName(dropId);
            if (viaList != null && !viaList.trim().isEmpty() && !viaList.startsWith("#")) return viaList;
        }
        String[] names = itemNames;
        if (cat >= 0 && cat < DROP_TOP_INDEX.length && names != null) {
            int line = DROP_TOP_INDEX[cat] + idx;
            if (line >= 0 && line < names.length && !names[line].trim().isEmpty()) return names[line];
        }
        return "#" + dropId;
    }

    public static String getBattleMessage(int id, int value) {
        String template = null;
        if (battleMessages != null && id >= 0 && id < battleMessages.length) {
            template = battleMessages[id];
        }
        if (template == null || template.trim().isEmpty()) {
            int fi = id - BATTLE_MESSAGE_FALLBACK_BASE;
            if (fi >= 0 && fi < BATTLE_MESSAGE_FALLBACK.length) template = BATTLE_MESSAGE_FALLBACK[fi];
        }
        if (template == null) return null;
        return template.replace("<NUMBER>", String.valueOf(value));
    }

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

    // --- per-era world map cache --------------------------------------------
    // Directory holding the per-era rendered world map PNGs (see
    // AppActivity#renderWorldMaps / WorldMapRenderer): <dir>/worldmap_era<N>.png,
    // N = the overworld/era id from GameState.nativeGetWorldEra() (see
    // PartySnapshot#worldEra). Set once from AppActivity, mirroring
    // setExternalFilesDir/setFilesDir above.
    private static File worldMapDir;
    // Highest overworld id GameState.nativeGetWorldEra() can return (raw
    // 0x1FA - 0x1F0); WorldMapRenderer only renders 0..7, the rest can only
    // ever come from a live capture.
    private static final int WORLD_ERA_MAX = 10;
    private static final int WORLD_MAP_CACHE_CAP = 3;
    private static final Map<Integer, Bitmap> worldMapCache =
            new LinkedHashMap<Integer, Bitmap>(WORLD_MAP_CACHE_CAP, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap> eldest) {
                    return size() > WORLD_MAP_CACHE_CAP;
                }
            };
    // Eras with no PNG on disk (the common case before capture runs), so
    // repeated getWorldMap(era) calls -- once or more per frame from
    // PartyPanelView -- don't re-hit the filesystem every time.
    private static final Set<Integer> worldMapMisses = new HashSet<>();
    // wb_mini.png, cropped and sepia-tinted once (see setMiniMapFallback):
    // shown for any era with no rendered PNG yet. Always non-natural-aspect
    // (see PartyPanelView's letterboxing comment on the old single-map field).
    /** Last bitmap served by {@link #getWorldMap(int)}; reused while the era is momentarily unreadable. */
    private static Bitmap lastWorldMap;

    // Live re-composites of the game's own metatile grid (see WorldMapLive /
    // GameState.nativeGetWorldMapData), keyed by the same panel-space era id
    // as the on-disk PNGs. Preferred over the decode cache
    // and the offline render by getWorldMap(), because it reflects story
    // changes (bridges, craters, the Ocean Palace) the offline render can't.
    // Not LRU-capped: at most a handful of eras are ever visited in one
    // session, and evicting a live capture would silently fall back to a stale
    // picture. The PNG is written to disk too, so a restart keeps the change.
    private static final Map<Integer, Bitmap> worldMapLive = new HashMap<>();

    /** Records the directory {@link #getWorldMap(int)} looks in for {@code worldmap_era<N>.png}. Set once from AppActivity. */
    public static void setWorldMapDir(File dir) { worldMapDir = dir; }

    /**
     * Installs a live re-composite of the game's own world map for {@code era}
     * as the bitmap {@link #getWorldMap(int)} returns from now on, tinted
     * through the same {@link #sepiaTint} as the offline render so the two
     * look identical on the panel. Also drops any cached decode of the
     * (now superseded) on-disk PNG for that era. Main thread only, like the
     * rest of ChronoAssets; notifies listeners so a panel already showing the
     * old image repaints immediately instead of waiting for the next poll.
     */
    public static void setLiveWorldMap(int era, Bitmap capture) {
        if (era < 0 || capture == null) return;
        worldMapLive.put(era, sepiaTint(capture));
        worldMapCache.remove(era);
        worldMapMisses.remove(era);
        notifyListeners();
    }

    /** True when a live capture (not the offline render) is currently backing {@code era}. */
    public static boolean hasLiveWorldMap(int era) {
        return era >= 0 && worldMapLive.containsKey(era);
    }

    /**
     * Lazily decodes and caches (LRU, cap {@value #WORLD_MAP_CACHE_CAP}) the
     * rendered world map PNG for overworld/era id {@code era} --
     * {@code <worldMapDir>/worldmap_era<era>.png}. Falls back to the
     * last served bitmap for a momentarily unknown era, else null
     * finishes) when {@code era} is negative (unknown), {@link #worldMapDir}
     * isn't set yet, or no PNG exists for this era -- i.e. on-device
     * rendering hasn't finished or failed for it (remembered as a miss so
     * repeated calls don't re-hit the filesystem -- see {@link
     * #invalidateWorldMap(int)} to clear a miss once a render lands). Main
     * thread only, like the rest of ChronoAssets.
     */
    public static Bitmap getWorldMap(int era) {
        // A momentarily unknown era (world id not readable this tick) keeps
        // showing whatever was on screen rather than flashing to nothing.
        if (era < 0) return lastWorldMap;
        Bitmap live = worldMapLive.get(era);
        if (live != null) return lastWorldMap = live;
        Bitmap cached = worldMapCache.get(era);
        if (cached != null) return lastWorldMap = cached;
        if (worldMapMisses.contains(era)) return null;
        File f = resolveWorldMapFile(era);
        Bitmap decoded = f != null ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        if (decoded == null) {
            worldMapMisses.add(era);
            return null;
        }
        Bitmap tinted = sepiaTint(decoded);
        worldMapCache.put(era, tinted);
        return lastWorldMap = tinted;
    }

    /** Resolves the on-disk rendered PNG for {@code era} under {@link #worldMapDir} -- see {@link #getWorldMap(int)}. */
    private static File resolveWorldMapFile(int era) {
        if (worldMapDir == null) return null;
        File f = new File(worldMapDir, "worldmap_era" + era + ".png");
        return f.isFile() ? f : null;
    }

    /** Every bitmap {@link #getWorldMap(int)} serves (live 1536x1024 or rendered 3072x2048) is already at the world's true 1.5:1 aspect. */
    public static boolean isWorldMapNaturalAspect(int era) {
        return true;
    }

    /**
     * Drops the cached bitmap and known-missing flag for {@code era}, so the
     * next {@link #getWorldMap(int)} call re-hits the filesystem instead of
     * returning a stale miss/hit. Called after a new capture is saved for
     * this era. Notifies listeners so a panel already showing the fallback
     * (or a previous capture) for this era repaints.
     */
    public static void invalidateWorldMap(int era) {
        // Deliberately does NOT drop a live capture: the offline render this
        // is announcing is strictly older information than a readback of the
        // game's own RenderTextures.
        worldMapCache.remove(era);
        worldMapMisses.remove(era);
        notifyListeners();
    }

    /**
     * True when a rendered PNG for {@code era} exists on disk under
     * {@link #worldMapDir} -- the single source of truth for "does this era
     * already have an image", shared by {@link #getWorldMap(int)},
     * {@link #capturedWorldMapEras()}, and AppActivity#renderWorldMaps
     * ({@link #resolveWorldMapFile} is the single place that decides
     * whether a world's render is present).
     */
    public static boolean hasWorldMap(int era) {
        return resolveWorldMapFile(era) != null;
    }

    /** Eras (0..7 -- WorldMapRenderer.worldCount()) with a rendered PNG currently on disk under {@link #worldMapDir} -- for the settings screen's one-line status row. Does not consult the decode cache (so it reflects disk state even for an era not yet queried by {@link #getWorldMap(int)}). */
    public static List<Integer> capturedWorldMapEras() {
        List<Integer> eras = new ArrayList<>();
        if (worldMapDir == null) return eras;
        // Up to WORLD_ERA_MAX, not WorldMapRenderer.worldCount(): the offline
        // renderer covers worlds 0..7, but a live capture is keyed by
        // GameState.nativeGetWorldEra(), which reaches 10 for the special
        // maps -- worldmap_era8..10.png can exist on disk.
        for (int era = 0; era <= WORLD_ERA_MAX; era++) {
            if (worldMapLive.containsKey(era) || resolveWorldMapFile(era) != null) eras.add(era);
        }
        return eras;
    }

    public static void setMinimapMark(Bitmap b) { minimapMark = b; notifyListeners(); }
    public static void setEpochMark(Bitmap b) { epochMark = b; notifyListeners(); }

    /** Returns the corner/edge inset (in {@link #getWindowTex()}'s own pixels) for the manual 9-slice draw -- see {@link #setWindowTex(Bitmap, int)}. */
    public static int getWindowTexInset() { return windowTexInset; }

    /**
     * Stores the window-chrome bitmap and the inset (in that bitmap's own
     * pixels) PartyPanelView's manual 9-slice draw should use for it --
     * scaled by AppActivity's {@code cropWindowTexture} to match whatever
     * size {@code Extension/menu_win.png} actually decoded at (a mod can
     * ship a higher- or lower-resolution sheet than the shipped 512x512
     * original).
     */
    public static void setWindowTex(Bitmap b, int inset) {
        windowTex = b;
        windowTexInset = inset > 0 ? inset : WINDOW_TEX_INSET_DEFAULT;
        notifyListeners();
    }

    /** Stores the monster name table (line index == monster id) and notifies listeners, so a battle panel already open when extraction finishes repaints with real names. */
    public static void setMonsterNames(String[] names) { monsterNames = names; notifyListeners(); }

    /** Stores the monster flag table (byte index == monster id; 255 = hide info) and notifies listeners. */
    public static void setMonsterFlags(byte[] flags) { monsterFlags = flags; notifyListeners(); }

    /** Stores the tech name table (line index == tech id) and notifies listeners, mirroring {@link #setMonsterNames}. */
    public static void setTechNames(String[] names) { techNames = names; notifyListeners(); }

    /** Stores the item name table (line index == item id) and notifies listeners, mirroring {@link #setMonsterNames}. */
    public static void setItemNames(String[] names) { itemNames = names; notifyListeners(); }

    /** Stores the battle-message table (line index == message id, see {@link #getBattleMessage}) and notifies listeners, mirroring {@link #setMonsterNames}. */
    public static void setBattleMessages(String[] messages) { battleMessages = messages; notifyListeners(); }

    /** Stores the sfc_item.txt-derived category name map (key "<CATEGORY>_<NNN>") used by {@link #getItemName(int)}, and notifies listeners. */
    public static void setItemCategoryNames(Map<String, String> names) { itemCategoryNames = names; notifyListeners(); }

    /** Stores the tech MP-cost table (index == tech id) used by {@link #getTechMp(int)}, and notifies listeners. */
    public static void setTechMpTable(int[] table) { techMp = table; notifyListeners(); }

    /**
     * Records the app's external files dir, so {@link #getAreaMap(int)} can
     * find {@code <dir>/ds_maps/area_minimap_%03d.png}. Set once from
     * AppActivity, the same place that resolves the rendered world maps.
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
     * map (room id) {@code roomId}, resolving the actual minimap PNG file id
     * via {@link AreaMapCalib#fileIdFor(int)} -- the DS maps room ids to
     * minimap file ids through a ROM table that is not the identity (e.g.
     * the Cathedral, room id 129, has no {@code area_minimap_129.png}).
     * Equivalent to {@link #getAreaMap(int, float, float)} with NaN tile
     * coordinates, so a multi-floor room resolves to its first floor's map.
     */
    public static Bitmap getAreaMap(int roomId) {
        return getAreaMap(roomId, Float.NaN, Float.NaN);
    }

    /**
     * Lazily decodes and caches the rendered DS-style area minimap for field
     * map (room id) {@code roomId}, at the given live field-tile position
     * ({@code tileX}, {@code tileY} -- see {@link PartySnapshot#fieldX}/
     * {@code fieldY}). For a multi-floor room this picks the floor whose
     * calibration rect contains the position (see {@link
     * AreaMapCalib#fileIdFor(int, float, float)}); pass NaN for either
     * coordinate (or use {@link #getAreaMap(int)}) when no position is
     * known yet, which resolves to the first floor.
     *
     * <p>The PNG is looked up at {@code area_minimap_%03d.png} (or, for a
     * suffixed floor variant, {@code area_minimap_%03d_%d.png}) under
     * either {@link #filesDir}/ds_maps -- the on-device DsMapImporter output
     * -- or, as a dev-push fallback, {@link #externalFilesDir}/ds_maps;
     * whichever has the file wins, filesDir checked first. Returns null when
     * neither dir is set yet, {@code roomId} is negative, or no PNG exists
     * for the resolved file in either location (a known-missing file is
     * remembered so repeated calls -- e.g. once per frame from
     * PartyPanelView -- don't re-hit the filesystem). Cache keys are by
     * resolved file id (folding in the suffix, since two floors of the same
     * room can resolve to different files), not by room id. Main thread
     * only, like the rest of ChronoAssets.
     */
    public static Bitmap getAreaMap(int roomId, float tileX, float tileY) {
        if (roomId < 0) return null;
        int fileId = AreaMapCalib.fileIdFor(roomId, tileX, tileY);
        int suffix = AreaMapCalib.suffixFor(roomId, tileX, tileY);
        int cacheKey = fileId * AREA_MAP_CACHE_KEY_SUFFIX_MULT + suffix;

        Bitmap cached = areaMapCache.get(cacheKey);
        if (cached != null) return cached;
        if (areaMapMisses.contains(cacheKey)) return null;
        File f = resolveAreaMapFile(fileId, suffix);
        Bitmap b = f != null ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        if (b == null) {
            areaMapMisses.add(cacheKey);
            return null;
        }
        areaMapCache.put(cacheKey, b);
        return b;
    }

    /** Resolves the on-disk file for minimap file id {@code fileId} (with optional filename {@code suffix}, 0 = none), filesDir first, externalFilesDir as fallback -- see {@link #getAreaMap(int, float, float)}. */
    private static File resolveAreaMapFile(int fileId, int suffix) {
        String name = suffix != 0
                ? String.format(Locale.US, AREA_MAP_FILENAME_SUFFIXED, fileId, suffix)
                : String.format(Locale.US, AREA_MAP_FILENAME, fileId);
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
        if (facePng != null || !worldMapCache.isEmpty()
                || !worldMapLive.isEmpty()
                || minimapMark != null || windowTex != null
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
