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
 * <p>Source of truth, in preference order: (1) a fresh fetch of {@link
 * #REMOTE_URL}, cached to {@code <filesDir>/mods_catalog.json} on success;
 * (2) that cache file, if the fetch failed (offline, timeout, non-200); (3)
 * the bundled {@code assets/mods/catalog.json}; (4) {@link #defaultEntries()}
 * as an absolute last resort if even the bundled asset can't be read/parsed
 * (should never happen -- it ships in the APK -- but a corrupt asset must not
 * crash the Mods page). See {@link #loadAsync}.
 *
 * <p>{@link #parse} and {@link #findInstalledDirName} are the two pieces
 * exercised by the JVM sanity check this class was built against: {@code
 * parse} because it's the only method that touches org.json (whose android.jar
 * copy is stub-only off-device), and {@code findInstalledDirName} because
 * it's pure and easy to get subtly wrong (id-vs-fileHint precedence,
 * case-sensitivity).
 */
public final class ModCatalog {
    private static final String TAG = "ChronoDuo";
    private static final String REMOTE_URL =
            "https://raw.githubusercontent.com/kalenjohnson/chrono-duo/main/app/src/main/assets/mods/catalog.json";
    private static final String BUNDLED_ASSET_PATH = "mods/catalog.json";
    private static final String CACHE_FILE_NAME = "mods_catalog.json";
    private static final int TIMEOUT_MS = 5000;

    private ModCatalog() {}

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

    /** Hand-rolled fallback list mirroring the bundled {@code assets/mods/catalog.json}, used only if that asset itself can't be read or parsed. */
    public static List<Entry> defaultEntries() {
        List<Entry> l = new ArrayList<>();
        l.add(new Entry("snes-overworld-sprites", "SNES Overworld Sprites Restoration", null,
                "Original SNES overworld walking sprites",
                "https://www.nexusmods.com/chronotrigger/mods/9", null, "SNESOverworld", null));
        l.add(new Entry("pixel-demaster", "Chrono Trigger Pixel Demaster", null,
                "SNES-style UI, icons, sprites, and the ChronoType SNES font",
                "https://www.nexusmods.com/chronotrigger/mods/8", null, "Pixel Demaster",
                "Installs as many sub-mods: enable Main plus the Font, UI colour, icon, and button-prompt "
                        + "variants you want. Text button prompt (.xdelta) patches are Steam-only and ignored."));
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
                "Built for the PC CTExt loader; UI parts may not apply on Android"));
        l.add(new Entry("orchestral-wonders", "Chrono Trigger - Orchestral Wonders", null,
                "Orchestral re-recordings of the soundtrack",
                "https://www.nexusmods.com/chronotrigger/mods/23", null, "Orchestral",
                "Large download; audio is Sound/BGM/*.sab -- untested whether the game reads BGM through the hooked path"));
        l.add(new Entry("fmv-remastered", "Chrono Trigger FMV's Remastered", null,
                "4K AI-upscaled anime cutscenes",
                "https://www.nexusmods.com/chronotrigger/mods/28", null, "FMV",
                "Very large; FMVs are loose APK assets (001-008.dat), not archive entries -- needs a Java-side "
                        + "override in the video path and FMV display in ChronoDuo is still an open issue"));
        return Collections.unmodifiableList(l);
    }

    /**
     * Parses a catalog JSON document (a top-level array of entry objects,
     * matching {@code assets/mods/catalog.json}'s schema) into a list of
     * {@link Entry}. {@code author}/{@code download}/{@code fileHint}/{@code
     * notes} are optional (missing, or explicit JSON {@code null}, both read
     * back as a Java {@code null}). Pure -- no I/O, no Android dependency
     * beyond org.json -- so it's the one method here exercised on a plain
     * JVM (where org.json itself has to come from a real Android runtime;
     * see the class doc).
     *
     * @throws JSONException on malformed JSON or a missing required field --
     *         callers should catch this (and any other Exception; a
     *         corrupt/short download is not always a clean JSONException)
     *         and fall back per the class doc's preference order.
     */
    public static List<Entry> parse(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
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
        return out;
    }

    /** Reads and parses the bundled {@code assets/mods/catalog.json}, falling back to {@link #defaultEntries()} on any error (missing asset, bad JSON) -- never throws. */
    public static List<Entry> loadBundled(AssetManager assets) {
        try (InputStream in = assets.open(BUNDLED_ASSET_PATH)) {
            return parse(readAll(in));
        } catch (Exception e) {
            Log.w(TAG, "mods: could not read/parse bundled catalog, using built-in defaults", e);
            return defaultEntries();
        }
    }

    /** Result callback for {@link #loadAsync} -- invoked on the same background thread {@code loadAsync} spawned; callers on Android must hop back to the main thread themselves before touching any View. */
    public interface Callback {
        void onLoaded(List<Entry> entries);
    }

    /**
     * Loads the catalog for use, in the preference order documented on the
     * class: fresh remote fetch (cached on success) > last-good cache >
     * bundled asset > hard-coded defaults. Always calls {@code cb} exactly
     * once, on a new background thread -- never on the calling thread, and
     * never throws synchronously.
     */
    public static void loadAsync(Context context, Callback cb) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> cb.onLoaded(load(appContext)), "ChronoModCatalogLoad").start();
    }

    /** Synchronous version of {@link #loadAsync} -- runs network I/O, so callers must already be off the main thread. */
    public static List<Entry> load(Context context) {
        File cacheFile = new File(context.getFilesDir(), CACHE_FILE_NAME);
        List<Entry> remote = fetchRemote(cacheFile);
        if (remote != null) return remote;

        if (cacheFile.isFile()) {
            try {
                return parse(readFile(cacheFile));
            } catch (Exception e) {
                Log.w(TAG, "mods: cached catalog is corrupt, ignoring", e);
            }
        }
        return loadBundled(context.getAssets());
    }

    /** Fetches+parses+caches {@link #REMOTE_URL}; returns null (leaving {@code cacheFile} untouched) on any failure -- timeout, non-200, malformed JSON, I/O error. Never throws. */
    private static List<Entry> fetchRemote(File cacheFile) {
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
            List<Entry> parsed = parse(json); // validate before caching
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
     * Finds the installed mod directory matching {@code entry} among {@code
     * modDirNames} (typically every {@link ModManager.ModInfo#name} from
     * {@link ModManager#lastMods}, enabled or not): an exact match on {@code
     * entry.id} (how every mod installed through the catalog flows in {@code
     * AppActivity} is named -- see {@code ModManager#importCatalogMod}) wins
     * first; failing that, a case-insensitive substring match against {@code
     * entry.fileHint} (for a mod that was manually imported under its
     * original archive name before a catalog existed, or dropped in by hand).
     * Returns null if neither matches (not installed).
     */
    public static String findInstalledDirName(Entry entry, List<String> modDirNames) {
        if (modDirNames == null) return null;
        for (String dir : modDirNames) {
            if (dir.equals(entry.id)) return dir;
        }
        if (entry.fileHint != null && !entry.fileHint.isEmpty()) {
            String hint = entry.fileHint.toLowerCase(Locale.ROOT);
            for (String dir : modDirNames) {
                if (dir.toLowerCase(Locale.ROOT).contains(hint)) return dir;
            }
        }
        return null;
    }
}
