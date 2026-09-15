package com.kalenjohnson.chronoduo.mods;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The curated mod catalog shown on the Mods settings page (see {@code
 * PartyPanelView#buildModRows}) -- a small, hand-picked list of known-good
 * Chrono Trigger mods (mostly hosted on Nexus Mods) that the user can tap to
 * get and enable, on top of the freeform {@link ModManager#importArchive}
 * path that still exists for anything not in the list.
 *
 * <p>Source of truth is whichever of (a fresh fetch of {@link #REMOTE_URL},
 * cached to {@code <filesDir>/mods_catalog.json} on success) or (that cache
 * file, if the fetch failed -- offline, timeout, non-200) carries the higher
 * {@code "version"} field versus the bundled {@code assets/mods/catalog.json}
 * -- see {@link #load}/{@link #candidateBeatsBundled}. This guards against a
 * stale remote/cache catalog (e.g. the committed {@code catalog.json} on
 * GitHub hasn't caught up with a newer bundled copy shipping in this build)
 * permanently shadowing a newer bundled catalog just because a fetch once
 * succeeded: a candidate only wins with a STRICTLY higher version number, so
 * an equal (or legacy, unversioned -- treated as version 0) version prefers
 * the bundled copy, which is guaranteed to match this build.
 * {@link #defaultEntries()} is an absolute last resort if even the bundled
 * asset can't be read/parsed (should never happen -- it ships in the APK --
 * but a corrupt asset must not crash the Mods page); it mirrors {@code
 * catalog.json} exactly, including its version. See {@link #loadAsync}.
 *
 * <p>The catalog document is a top-level JSON object {@code {"version": N,
 * "entries": [...]}}; a bare top-level array (no version wrapper) is also
 * accepted for backward compatibility and treated as version 0 -- see
 * {@link #parseDocument}.
 *
 * <p>{@link #parseDocument} and {@link #findInstalledDirName} are the two
 * pure-logic pieces this class was built against on a plain JVM -- {@code
 * parseDocument}/{@code parse} because they're the only methods that touch
 * org.json (whose android.jar copy is stub-only off-device, so they can't
 * actually be exercised by a JVM unit test -- see {@link
 * #candidateBeatsBundled} for the version-comparison logic split out
 * specifically so IT can be), and {@code findInstalledDirName} because it's
 * pure and easy to get subtly wrong (id-vs-fileHint precedence,
 * case-sensitivity).
 */
public final class ModCatalog {
    private static final String TAG = "ChronoDuo";
    private static final String REMOTE_URL =
            "https://raw.githubusercontent.com/kalenjohnson/chrono-duo/main/app/src/main/assets/mods/catalog.json";
    private static final String BUNDLED_ASSET_PATH = "mods/catalog.json";
    private static final String CACHE_FILE_NAME = "mods_catalog.json";
    private static final int TIMEOUT_MS = 5000;
    // Mirrors catalog.json's "version" field -- bumped here too whenever
    // catalog.json's version is bumped (see defaultEntries()'s doc) so the
    // hardcoded last-resort fallback never looks "older" than the asset it
    // mirrors.
    private static final int DEFAULT_ENTRIES_VERSION = 2;

    private ModCatalog() {}

    /** A parsed catalog document: its {@code "version"} (0 for the legacy bare-array format -- see {@link #parseDocument}) plus its entries. */
    public static final class Parsed {
        public final int version;
        public final List<Entry> entries;

        public Parsed(int version, List<Entry> entries) {
            this.version = version;
            this.entries = entries;
        }
    }

    /** One catalog entry -- see the field docs; all but {@code id}/{@code name}/{@code summary}/{@code page} may be null. */
    public static final class Entry {
        public final String id;
        public final String name;
        public final String author;   // may be null
        public final String summary;  // one line, <=80 chars
        public final String page;     // the mod's web page (e.g. a Nexus mod page)
        public final String download; // direct download URL, or null if only reachable via `page` (e.g. Nexus)
        public final String fileHint; // substring expected in a downloaded/imported archive's filename, or null
        public final String notes;    // compatibility caveat etc., or null

        public Entry(String id, String name, String author, String summary, String page,
                     String download, String fileHint, String notes) {
            this.id = id;
            this.name = name;
            this.author = author;
            this.summary = summary;
            this.page = page;
            this.download = download;
            this.fileHint = fileHint;
            this.notes = notes;
        }
    }

    /** Hand-rolled fallback list mirroring the bundled {@code assets/mods/catalog.json} (including its {@code "version"} -- see {@link #DEFAULT_ENTRIES_VERSION}), used only if that asset itself can't be read or parsed. */
    public static List<Entry> defaultEntries() {
        List<Entry> l = new ArrayList<>();
        l.add(new Entry("snes-overworld-sprites", "SNES Overworld Sprites Restoration", null,
                "Original SNES overworld walking sprites",
                "https://www.nexusmods.com/chronotrigger/mods/9", null, "SNESOverworld", null));
        l.add(new Entry("pixel-demaster", "Chrono Trigger Pixel Demaster", null,
                "SNES-style UI, icons, sprites, and the ChronoType SNES font",
                "https://www.nexusmods.com/chronotrigger/mods/8", null, "Demaster",
                "Enable Main plus any Font, icon, UI or button variants."));
        l.add(new Entry("snes-wood-menu", "SNES Wood Menu", null,
                "Wooden SNES-style menu windows",
                "https://www.nexusmods.com/chronotrigger/mods/26", null, "Wood", null));
        l.add(new Entry("crono-snes-palette", "Crono SNES Sprite Replacement", null,
                "Crono's SNES palette (removes the yellow skin tone)",
                "https://www.nexusmods.com/chronotrigger/mods/3", null, null, null));
        l.add(new Entry("consistent-magus", "Consistent Magus Sprite", null,
                "Red-cloak Magus matching his portrait",
                "https://www.nexusmods.com/chronotrigger/mods/5", null, null, null));
        l.add(new Entry("transparent-portraits-icons", "Transparent Portraits and Improved Icons", null,
                "Cleaner portraits and menu icons",
                "https://www.nexusmods.com/chronotrigger/mods/12", null, null, null));
        l.add(new Entry("portraits-redone", "Character Portraits Redone", null,
                "Redrawn character portraits",
                "https://www.nexusmods.com/chronotrigger/mods/10", null, null, null));
        l.add(new Entry("rework", "-ReWork-", null,
                "Reworked title, menus, shop UI and font",
                "https://www.nexusmods.com/chronotrigger/mods/43", null, null,
                "Made for the PC CTExt loader; some UI parts may not apply"));
        l.add(new Entry("orchestral-wonders", "Chrono Trigger - Orchestral Wonders", null,
                "Orchestral re-recordings of the soundtrack",
                "https://www.nexusmods.com/chronotrigger/mods/23", null, "Orchestral",
                "Large download"));
        l.add(new Entry("fmv-remastered", "Chrono Trigger FMV's Remastered", null,
                "4K AI-upscaled anime cutscenes",
                "https://www.nexusmods.com/chronotrigger/mods/28", null, "FMV",
                "Very large download (~1 GB)"));
        return Collections.unmodifiableList(l);
    }

    /**
     * Parses a catalog JSON document into a {@link Parsed} (version +
     * entries): either the current wrapper format ({@code {"version": N,
     * "entries": [...]}}) or, for backward compatibility with an
     * already-cached/committed legacy document, a bare top-level array of
     * entry objects (treated as version 0 -- see the class doc). Per-entry,
     * {@code author}/{@code download}/{@code fileHint}/{@code notes} are
     * optional (missing, or explicit JSON {@code null}, both read back as a
     * Java {@code null}). Pure -- no I/O, no Android dependency beyond
     * org.json -- but org.json itself only works on a real Android runtime
     * (see the class doc), so this can't actually be exercised by a JVM unit
     * test; {@link #candidateBeatsBundled} carries the testable part of the
     * logic that consumes {@code version}.
     *
     * @throws JSONException on malformed JSON or a missing required field --
     *         callers should catch this (and any other Exception; a
     *         corrupt/short download is not always a clean JSONException)
     *         and fall back per the class doc's preference order.
     */
    public static Parsed parseDocument(String json) throws JSONException {
        String trimmed = json == null ? "" : json.trim();
        JSONArray arr;
        int version;
        if (trimmed.startsWith("[")) {
            arr = new JSONArray(trimmed);
            version = 0;
        } else {
            JSONObject doc = new JSONObject(trimmed);
            version = doc.optInt("version", 0);
            arr = doc.getJSONArray("entries");
        }
        List<Entry> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            out.add(new Entry(
                    o.getString("id"),
                    o.getString("name"),
                    o.isNull("author") ? null : o.optString("author", null),
                    o.getString("summary"),
                    o.getString("page"),
                    o.isNull("download") ? null : o.optString("download", null),
                    o.isNull("fileHint") ? null : o.optString("fileHint", null),
                    o.isNull("notes") ? null : o.optString("notes", null)));
        }
        return new Parsed(version, out);
    }

    /** Like {@link #parseDocument}, discarding the version -- kept for any caller that only wants the entries. */
    public static List<Entry> parse(String json) throws JSONException {
        return parseDocument(json).entries;
    }

    /** Reads and parses the bundled {@code assets/mods/catalog.json}, falling back to {@code (}{@link #DEFAULT_ENTRIES_VERSION}{@code , }{@link #defaultEntries()}{@code )} on any error (missing asset, bad JSON) -- never throws. */
    private static Parsed loadBundledParsed(AssetManager assets) {
        try (InputStream in = assets.open(BUNDLED_ASSET_PATH)) {
            return parseDocument(readAll(in));
        } catch (Exception e) {
            Log.w(TAG, "mods: could not read/parse bundled catalog, using built-in defaults", e);
            return new Parsed(DEFAULT_ENTRIES_VERSION, defaultEntries());
        }
    }

    /** Like {@link #loadBundledParsed}, discarding the version -- kept for any caller that only wants the entries. */
    public static List<Entry> loadBundled(AssetManager assets) {
        return loadBundledParsed(assets).entries;
    }

    /** Result callback for {@link #loadAsync} -- invoked on the same background thread {@code loadAsync} spawned; callers on Android must hop back to the main thread themselves before touching any View. */
    public interface Callback {
        void onLoaded(List<Entry> entries);
    }

    /**
     * Loads the catalog for use: whichever of (a fresh remote fetch, cached
     * on success) or (the last-good cache, if the fetch failed) has a
     * STRICTLY higher {@code version} than the bundled asset wins; otherwise
     * (no usable remote/cache, or its version doesn't beat the bundled
     * one's) the bundled asset (or, failing that, {@link #defaultEntries()})
     * is used -- see the class doc and {@link #candidateBeatsBundled}.
     * Always calls {@code cb} exactly once, on a new background thread --
     * never on the calling thread, and never throws synchronously.
     */
    public static void loadAsync(Context context, Callback cb) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> cb.onLoaded(load(appContext)), "ChronoModCatalogLoad").start();
    }

    /** Synchronous version of {@link #loadAsync} -- runs network I/O, so callers must already be off the main thread. */
    public static List<Entry> load(Context context) {
        File cacheFile = new File(context.getFilesDir(), CACHE_FILE_NAME);
        Parsed bundled = loadBundledParsed(context.getAssets());

        Parsed candidate = fetchRemote(cacheFile);
        if (candidate == null && cacheFile.isFile()) {
            try {
                candidate = parseDocument(readFile(cacheFile));
            } catch (Exception e) {
                Log.w(TAG, "mods: cached catalog is corrupt, ignoring", e);
            }
        }
        if (candidate != null && candidateBeatsBundled(candidate.version, bundled.version)) {
            return candidate.entries;
        }
        return bundled.entries;
    }

    /**
     * Pure version-comparison rule behind {@link #load}: a remote/cached
     * candidate is only preferred over the bundled catalog when its version
     * is STRICTLY higher -- an equal version (including the common "both
     * unversioned/legacy" case, both 0) prefers the bundled copy, since
     * that's guaranteed to match this build, whereas a same-numbered remote
     * copy might just be stale (e.g. the committed {@code catalog.json}
     * hasn't caught up with a bundled-only change yet). Split out from
     * {@link #load} specifically so it's testable on a plain JVM (see the
     * class doc's org.json caveat).
     */
    static boolean candidateBeatsBundled(int candidateVersion, int bundledVersion) {
        return candidateVersion > bundledVersion;
    }

    /** Fetches+parses+caches {@link #REMOTE_URL}; returns null (leaving {@code cacheFile} untouched) on any failure -- timeout, non-200, malformed JSON, I/O error. Never throws. */
    private static Parsed fetchRemote(File cacheFile) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(REMOTE_URL).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "mods: catalog refresh got HTTP " + code + ", using local copy");
                return null;
            }
            String json = readAll(conn.getInputStream());
            Parsed parsed = parseDocument(json); // validate before caching
            try (FileOutputStream out = new FileOutputStream(cacheFile)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "mods: could not cache refreshed catalog (using it anyway)", e);
            }
            return parsed;
        } catch (Exception e) {
            Log.i(TAG, "mods: catalog refresh failed (" + e + "), using local copy");
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    // --- installed-state matching (pure; see class doc) ----------------------

    /**
     * Like {@link #findInstalledDirName(Entry, List, List)} with no group
     * info -- kept for callers (and any pre-existing test) that don't have
     * a {@link ModManager#groups} result handy. Never prefers a group's
     * main dir over a plain substring match, so a multi-.ctp download's
     * catalog row can land on an option sub-dir here; prefer the 3-arg
     * overload whenever a {@link ModManager.GroupResult} is available (see
     * its doc).
     */
    public static String findInstalledDirName(Entry entry, List<String> modDirNames) {
        return findInstalledDirName(entry, modDirNames, null);
    }

    /**
     * Finds the installed mod directory matching {@code entry} among {@code
     * modDirNames} (typically every {@link ModManager.ModInfo#name} from
     * {@link ModManager#lastMods}, enabled or not): an exact match on {@code
     * entry.id} (how every mod installed through the catalog flows in {@code
     * AppActivity} is named -- see {@code ModManager#importCatalogMod}) wins
     * first; then, if {@code groups} is given, any {@link ModManager.ModGroup}
     * whose {@code downloadName} equals {@code entry.id} (case-insensitive --
     * how {@code ModManager#importCatalogMod} names every sub-mod's {@code
     * .group} marker, see that method's doc) OR contains {@code
     * entry.fileHint} (case-insensitive), OR whose {@code mainDir} contains
     * {@code entry.fileHint} or starts with {@code entry.id} (case-
     * insensitive) -- its {@code mainDir} is returned, NEVER an option
     * sub-dir, since a multi-.ctp download's alphabetically-first folder is
     * usually a Font/Interface variant rather than the actual "Main File"
     * mod (see the Mods-page grouping feature's design note); finally a
     * plain case-insensitive substring match against
     * {@code entry.fileHint} over every dir in {@code modDirNames} EXCEPT
     * any dir that's an option choice of some group in {@code groups} (an
     * option sub-dir must never be mistaken for "the installed mod" -- only
     * a group's main dir, or an ungrouped dir, may match here). Returns
     * null if nothing matches (not installed).
     */
    public static String findInstalledDirName(Entry entry, List<String> modDirNames,
                                               List<ModManager.ModGroup> groups) {
        if (modDirNames == null) return null;
        for (String dir : modDirNames) {
            if (dir.equals(entry.id)) return dir;
        }
        String idLower = entry.id.toLowerCase(Locale.ROOT);
        String hint = entry.fileHint != null && !entry.fileHint.isEmpty()
                ? entry.fileHint.toLowerCase(Locale.ROOT) : null;

        // modDirNames is the source of truth for "actually installed" --
        // groups may be paired with a different (e.g. slightly stale, see
        // ModManager#groups' caching doc) scan than modDirNames came from,
        // so a group's mainDir is only ever returned here if it's ALSO in
        // modDirNames; never hand back a dir the caller doesn't know about.
        java.util.Set<String> dirSet = new java.util.HashSet<>(modDirNames);
        java.util.Set<String> optionDirs = new java.util.HashSet<>();
        if (groups != null) {
            for (ModManager.ModGroup g : groups) {
                for (ModManager.OptionGroup og : g.options) {
                    for (ModManager.Choice c : og.choices) optionDirs.add(c.dir);
                }
            }
            for (ModManager.ModGroup g : groups) {
                if (g.mainDir == null || !dirSet.contains(g.mainDir)) continue;
                String downloadLower = g.downloadName.toLowerCase(Locale.ROOT);
                String mainDirLower = g.mainDir.toLowerCase(Locale.ROOT);
                boolean matches = downloadLower.equals(idLower)
                        || (hint != null && downloadLower.contains(hint))
                        || (hint != null && mainDirLower.contains(hint))
                        || mainDirLower.startsWith(idLower);
                if (matches) return g.mainDir;
            }
        }

        if (hint != null) {
            for (String dir : modDirNames) {
                if (optionDirs.contains(dir)) continue;
                if (dir.toLowerCase(Locale.ROOT).contains(hint)) return dir;
            }
        }
        return null;
    }
}
