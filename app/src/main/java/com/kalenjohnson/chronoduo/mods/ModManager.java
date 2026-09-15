package com.kalenjohnson.chronoduo.mods;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * User mod loader, layered onto gamestate.c's mechanism-7 asset-substitution
 * hook (see {@code ctr::ResourceManager::getData} in gamestate.c and
 * {@link com.kalenjohnson.chronoduo.GameState#nativeRegisterModSubstitutions}).
 *
 * <p>Root directory: {@code <externalFilesDir>/mods/}. Each immediate
 * subdirectory of that root is one mod; the files inside it mirror the
 * game's own archive layout exactly, e.g. {@code
 * mods/MyMod/Game/chara/png/c000_0.png} substitutes for the archive path
 * {@code Game/chara/png/c000_0.png}. A mod is disabled by the presence of a
 * file named {@code .disabled} in its directory -- no preference bookkeeping,
 * so the state survives an app reinstall along with the mod files
 * themselves.
 *
 * <p>{@link #scan()} is the normal entry point: it walks every enabled mod
 * in case-insensitive alphabetical order, resolves conflicts (first mod to
 * claim an archive path wins; later claims of the same path count as a
 * conflict against the LATER mod, i.e. the one that lost), and registers the
 * winning set with the native side. {@link #importArchive} unpacks a
 * user-picked {@code .ctp}/{@code .zip} mod archive into the root and
 * rescans; {@link #setEnabled} flips a mod's {@code .disabled} marker and
 * rescans.
 *
 * <p><b>Substitutions only apply to files the game loads AFTER
 * registration</b> -- a texture already uploaded to the GPU (or any other
 * asset already decoded) keeps showing the old bytes until the game reloads
 * it, which in practice usually means a full game restart. Callers should
 * surface that in the UI (see {@code PartyPanelView}'s Mods page body text)
 * rather than implying a live/instant effect.
 *
 * <p>This class is deliberately free of Android dependencies except {@link
 * Uri}/{@link ContentResolver} (and the small SAF display-name lookup that
 * goes with them) in the {@code importArchive(Uri, ContentResolver)}
 * overload -- {@link #collect}, {@link #extractZip}, and the plain-{@link
 * InputStream} {@link #importArchive(InputStream, String, File)} touch
 * nothing Android-specific and never reference {@link
 * com.kalenjohnson.chronoduo.GameState}, so they can be exercised on a plain
 * JVM (see the scratch sanity check this class was built against).
 */
public final class ModManager {
    private static final String TAG = "ChronoDuo";
    private static final String MODS_DIR_NAME = "mods";
    private static final String DISABLED_MARKER = ".disabled";

    // Recognized top-level archive directories -- used only to decide
    // whether a zip's single top-level folder is a wrapper to strip (see
    // stripSingleTopFolder) rather than the mod's actual name/content.
    private static final Set<String> ASSET_ROOTS = new HashSet<>(Arrays.asList(
            "game", "localize", "extension", "sound"));

    // A Steam font patch's payload file (e.g. string_2.bin), decrypted by
    // processFonts() into font.ttf/font_N.ttf -- see extractZip's class doc.
    private static final java.util.regex.Pattern STRING_BIN =
            java.util.regex.Pattern.compile("string_(\\d+)\\.bin", java.util.regex.Pattern.CASE_INSENSITIVE);

    // Loose files a Nexus download can contain that are never usable assets
    // on this platform -- .xdelta patches are Steam-exe-only (see
    // extractZip's class doc) -- deleted outright so they never get swept
    // into a mod's archive-substitution set by collect()/walk().
    private static final Set<String> NON_ASSET_EXTENSIONS = new HashSet<>(Collections.singletonList("xdelta"));

    /**
     * Result of {@link #extractZip}/{@link #importArchive}/{@link
     * #importArchiveNamed}/{@link #importCatalogMod}: a download can expand
     * into more than one mod directory now (see {@link #extractZip}'s class
     * doc paragraph on multi-archive downloads), so callers need the full
     * list, not just one {@link File}.
     */
    public static final class ImportResult {
        /** The first mod directory created (single-archive downloads: the only one; multi-archive: the earliest by {@link #modNames} order) -- kept for callers that only need "a" resulting directory. */
        public final File finalDir;
        /** Every mod directory name created by this import, in creation order (== {@link #modNames}'s iteration order over {@code nested archives} for a split download). */
        public final List<String> modNames;
        /** How many of {@link #modNames} started enabled (no {@code .disabled} marker written) -- always 1 for a single-archive download, 0 or more for a split one. */
        public final int enabledCount;
        /** The download's display name, as passed to {@link #extractZip} -- echoed back so a status message can name it (e.g. "Imported 33 mods from Pixel Demaster"). */
        public final String downloadName;

        ImportResult(File finalDir, List<String> modNames, int enabledCount, String downloadName) {
            this.finalDir = finalDir;
            this.modNames = modNames;
            this.enabledCount = enabledCount;
            this.downloadName = downloadName;
        }
    }

    /** One mod's summary, as returned by {@link #collect}/{@link #scan}. */
    public static final class ModInfo {
        public final String name;
        public final boolean enabled;
        public final int fileCount;
        public final int conflictCount;

        public ModInfo(String name, boolean enabled, int fileCount, int conflictCount) {
            this.name = name;
            this.enabled = enabled;
            this.fileCount = fileCount;
            this.conflictCount = conflictCount;
        }
    }

    /** Result of a {@link #collect} pass: per-mod summaries plus the flattened, conflict-resolved registration arrays. */
    public static final class ScanResult {
        public final List<ModInfo> mods;
        public final String[] archivePaths;
        public final String[] diskPaths;
        // Same winning (archivePath -> diskPath) pairs as archivePaths/
        // diskPaths above, keyed by the SAME normalized form gamestate.c's
        // normalize_archive_path uses (strip one leading "./" then any
        // leading "/"), for O(1) lookup by ChronoResources#extractModAware /
        // resolve() below instead of a linear scan. Case-sensitive, matching
        // the native side's strcmp.
        public final Map<String, String> archiveToDiskPath;

        public ScanResult(List<ModInfo> mods, String[] archivePaths, String[] diskPaths) {
            this.mods = mods;
            this.archivePaths = archivePaths;
            this.diskPaths = diskPaths;
            Map<String, String> map = new java.util.HashMap<>(archivePaths.length * 2);
            for (int i = 0; i < archivePaths.length; i++) {
                map.put(normalizeArchivePath(archivePaths[i]), diskPaths[i]);
            }
            this.archiveToDiskPath = map;
        }
    }

    /**
     * Strips a single leading "./" and then any number of leading "/" from an
     * archive path, exactly mirroring gamestate.c's {@code
     * normalize_archive_path} -- must be applied identically here and on the
     * native side or a mod entry silently misses. Case is left untouched:
     * the native side compares with {@code strcmp}.
     */
    public static String normalizeArchivePath(String p) {
        if (p == null) return "";
        int i = 0;
        if (p.startsWith("./")) i = 2;
        while (i < p.length() && p.charAt(i) == '/') i++;
        return p.substring(i);
    }

    // Set at the end of the constructor; read by the static resolveStatic()
    // helper ChronoResources#extractModAware calls. Volatile: constructed on
    // the main thread at boot, read from whatever thread runs companion asset
    // extraction. Null until AppActivity constructs its ModManager -- callers
    // must treat that as "no mods" (fall back to the archive), never as an
    // error; see ChronoResources#extractModAware.
    private static volatile ModManager instance;

    private final File root;
    private volatile ScanResult lastResult;

    /** {@code externalFilesDir} is the app's external files directory (see AppActivity's {@code getExternalFilesDir(null)}); the mod root is {@code <externalFilesDir>/mods}. */
    public ModManager(File externalFilesDir) {
        this.root = new File(externalFilesDir, MODS_DIR_NAME);
        instance = this;
    }

    /**
     * Resolves {@code archivePath} (e.g. {@code "Extension/face.png"}) to the
     * on-disk file of the mod currently winning that path, or {@code null} if
     * no enabled mod claims it or no scan has run yet. Backed by the same
     * winner map {@link #scan()}/{@link #collect} builds -- reflects
     * whatever the most recent scan registered natively, kept in memory here
     * so this doesn't need to re-walk the mods directory per lookup.
     */
    public File resolve(String archivePath) {
        return resolveFromResult(lastResult, archivePath);
    }

    /**
     * Pure helper backing {@link #resolve(String)} -- split out so the
     * winner-precedence/normalization/disabled-exclusion behavior can be
     * exercised on a plain JVM against a {@link ScanResult} from {@link
     * #collect}, without going through {@link #scan()} (which touches
     * {@link com.kalenjohnson.chronoduo.GameState}, and so requires the
     * native library). {@code result} may be {@code null} (no scan yet),
     * which resolves everything to {@code null}.
     */
    public static File resolveFromResult(ScanResult result, String archivePath) {
        if (result == null) return null;
        String disk = result.archiveToDiskPath.get(normalizeArchivePath(archivePath));
        return disk != null ? new File(disk) : null;
    }

    /**
     * Static convenience for call sites (like {@link
     * com.kalenjohnson.chronoduo.ChronoResources#extractModAware}) with no
     * direct reference to the app's single {@link ModManager}. Safe to call
     * before the instance exists (constructed once from AppActivity) -- an
     * uninitialized loader is treated as "no mods", returning {@code null} so
     * the caller falls back to the archive.
     */
    public static File resolveStatic(String archivePath) {
        ModManager m = instance;
        return m != null ? m.resolve(archivePath) : null;
    }

    public File getRoot() {
        return root;
    }

    /** The mods found by the most recent {@link #scan}, or an empty list before the first scan. */
    public List<ModInfo> lastMods() {
        ScanResult r = lastResult;
        return r != null ? r.mods : Collections.emptyList();
    }

    // --- pure scan/collect (no Android, no native calls) --------------------

    /**
     * Walks {@code modsRoot}'s immediate subdirectories in case-insensitive
     * alphabetical order; each is one mod, skipped entirely (fileCount=0,
     * conflictCount=0) if it contains a {@code .disabled} marker file.
     * Within an enabled mod, every regular file is visited depth-first
     * (dotfiles/dot-directories skipped everywhere; {@code readme*} and
     * {@code *.txt} skipped only directly at the mod's root, since real
     * assets like {@code Localize/en/msg/tech.txt} live under subdirs and
     * must not be swept up by that rule). The archive path is the file's
     * path relative to the mod directory with {@code '/'} separators.
     *
     * <p>The first mod (in the walk order above) to claim a given archive
     * path wins; every later mod that names the same path has that file
     * counted as a conflict against IT (the loser), and that file is
     * excluded from the returned registration arrays -- the winning mod's
     * claim is unaffected.
     *
     * <p>Pure and Android-free: touches only {@code java.io}, so it can run
     * on a plain JVM.
     */
    public static ScanResult collect(File modsRoot) {
        List<File> modDirs = new ArrayList<>();
        File[] children = modsRoot.isDirectory() ? modsRoot.listFiles() : null;
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) modDirs.add(f);
            }
        }
        modDirs.sort(Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));

        Set<String> claimed = new HashSet<>();
        List<ModInfo> infos = new ArrayList<>();
        List<String> archivePaths = new ArrayList<>();
        List<String> diskPaths = new ArrayList<>();

        for (File modDir : modDirs) {
            String name = modDir.getName();
            boolean enabled = !new File(modDir, DISABLED_MARKER).isFile();
            int fileCount = 0, conflicts = 0;
            if (enabled) {
                List<String[]> files = new ArrayList<>(); // {relArchivePath, absDiskPath}
                walk(modDir, "", files);
                for (String[] pair : files) {
                    String rel = pair[0];
                    fileCount++;
                    if (claimed.contains(rel)) {
                        conflicts++;
                    } else {
                        claimed.add(rel);
                        archivePaths.add(rel);
                        diskPaths.add(pair[1]);
                    }
                }
            }
            infos.add(new ModInfo(name, enabled, fileCount, conflicts));
        }
        return new ScanResult(infos, archivePaths.toArray(new String[0]), diskPaths.toArray(new String[0]));
    }

    /**
     * Depth-first walk of {@code dir} (a mod directory, or a subdirectory of
     * one when {@code relPrefix} is non-empty), appending {@code
     * {relativeArchivePath, absoluteDiskPath}} pairs to {@code out} for
     * every regular file that isn't skipped. Sorted by name at each level
     * purely for deterministic output (registration order doesn't matter to
     * the native side, which keys on path, but it makes results
     * reproducible for testing/debugging).
     */
    private static void walk(File dir, String relPrefix, List<String[]> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        boolean atRoot = relPrefix.isEmpty();
        for (File entry : entries) {
            String name = entry.getName();
            if (name.startsWith(".")) continue; // dotfiles/dot-dirs, including .disabled itself
            String rel = atRoot ? name : relPrefix + "/" + name;
            if (entry.isDirectory()) {
                walk(entry, rel, out);
            } else if (entry.isFile()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (atRoot && (lower.startsWith("readme") || lower.endsWith(".txt"))) continue;
                // Font assets (a raw .ttf/.otf, or a not-yet-decrypted
                // string_N.bin -- see extractZip/processFonts) are never
                // archive substitutions, at any depth.
                if (lower.endsWith(".ttf") || lower.endsWith(".otf") || STRING_BIN.matcher(name).matches()) continue;
                out.add(new String[]{rel, entry.getAbsolutePath()});
            }
        }
    }

    /**
     * Returns the font file of the first enabled mod (same alphabetical
     * order {@link #collect} walks) that has one, or {@code null} if none
     * do. A mod's font is {@code font.ttf} at its root if present (the
     * lowest-numbered {@code string_N.bin} decrypted by {@link
     * #processFonts} during import, or a raw {@code .ttf}/{@code .otf}
     * dropped in under that exact name); failing that, the alphabetically
     * first raw {@code .ttf}/{@code .otf} file at the mod's root. Pure --
     * touches only {@code java.io}, so it's JVM-testable; {@link #scan()}
     * calls this and publishes the result to {@code Cocos2dxBitmap}.
     */
    public static File findFont(File modsRoot) {
        File[] children = modsRoot.isDirectory() ? modsRoot.listFiles() : null;
        if (children == null) return null;
        List<File> modDirs = new ArrayList<>();
        for (File f : children) {
            if (f.isDirectory()) modDirs.add(f);
        }
        modDirs.sort(Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        for (File modDir : modDirs) {
            if (new File(modDir, DISABLED_MARKER).isFile()) continue;
            File primary = new File(modDir, "font.ttf");
            if (primary.isFile()) return primary;
            File[] entries = modDir.listFiles();
            if (entries == null) continue;
            Arrays.sort(entries, Comparator.comparing(File::getName));
            for (File e : entries) {
                if (!e.isFile()) continue;
                String lower = e.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".ttf") || lower.endsWith(".otf")) return e;
            }
        }
        return null;
    }

    /**
     * Parses {@code ttf}'s sfnt table directory looking for an {@code EBLC}
     * table (embedded bitmap location -- present on bitmap-strike fonts
     * like pixel-art fonts baked at a fixed size) and returns the first
     * strike's {@code ppemX}, or {@code 0} if the font has no embedded
     * bitmap strikes (a normal scalable font) or on any parse error.
     * Pure -- only {@code java.io}, so it's JVM-testable.
     *
     * <p>sfnt header: 4-byte version/magic ({@code 0x00010000}, {@code
     * 'true'}, or {@code 'OTTO'} -- {@code 'ttcf'} TrueType collections are
     * not handled and return 0), uint16 numTables, then 8 bytes of
     * search-range fields we skip, followed by {@code numTables} 16-byte
     * table records: 4-byte tag, uint32 checksum, uint32 offset, uint32
     * length.
     *
     * <p>EBLC table: uint32 version, uint32 numSizes, then {@code
     * numSizes} 48-byte {@code bitmapSizeTable} records; {@code ppemX} is
     * the uint8 at offset 44 within a record, {@code ppemY} at 45.
     */
    static int fontNativePpem(File ttf) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(ttf, "r")) {
            byte[] header = new byte[12];
            if (raf.read(header) != 12) return 0;
            int magic = readInt32(header, 0);
            if (magic != 0x00010000 && magic != 0x74727565 /* 'true' */
                    && magic != 0x4F54544F /* 'OTTO' */) {
                return 0;
            }
            int numTables = readUInt16(header, 4);
            if (numTables <= 0 || numTables > 4096) return 0;

            long eblcOffset = -1;
            long eblcLength = -1;
            byte[] record = new byte[16];
            for (int i = 0; i < numTables; i++) {
                if (raf.read(record) != 16) return 0;
                String tag = new String(record, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if ("EBLC".equals(tag)) {
                    eblcOffset = readUInt32(record, 8);
                    eblcLength = readUInt32(record, 12);
                    break;
                }
            }
            if (eblcOffset < 0) return 0;
            if (eblcLength < 8) return 0;

            raf.seek(eblcOffset);
            byte[] eblcHeader = new byte[8];
            if (raf.read(eblcHeader) != 8) return 0;
            long numSizes = readUInt32(eblcHeader, 4);
            if (numSizes <= 0) return 0;

            byte[] sizeRecord = new byte[48];
            if (raf.read(sizeRecord) != 48) return 0;
            return sizeRecord[44] & 0xFF;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int readInt32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int readUInt16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long readUInt32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    // --- Android-facing scan -------------------------------------------------

    /**
     * Runs {@link #collect} over this instance's mod root and registers the
     * result with {@link com.kalenjohnson.chronoduo.GameState#nativeRegisterModSubstitutions}.
     * Safe to call repeatedly (idempotent, wholesale replace on the native
     * side) -- called after {@link #importArchive} and {@link #setEnabled}
     * as well as at boot. Returns the number of file substitutions
     * registered natively.
     */
    public int scan() {
        ScanResult result = collect(root);
        lastResult = result;
        int registered = com.kalenjohnson.chronoduo.GameState.nativeRegisterModSubstitutions(
                result.archivePaths, result.diskPaths);
        int conflicts = 0;
        for (ModInfo m : result.mods) conflicts += m.conflictCount;
        Log.i(TAG, "mods: scan found " + result.mods.size() + " mod dir(s), " + registered
                + " file substitution(s) registered" + (conflicts > 0 ? ", " + conflicts + " conflict(s)" : ""));

        File font = findFont(root);
        if (font != null) {
            try {
                android.graphics.Typeface tf = android.graphics.Typeface.createFromFile(font);
                int ppem = fontNativePpem(font);
                org.cocos2dx.lib.Cocos2dxBitmap.setModTypeface(tf, ppem);
                modTypeface = tf;
                modTypefacePpem = ppem;
            } catch (Exception e) {
                Log.w(TAG, "mods: could not load mod font " + font, e);
                org.cocos2dx.lib.Cocos2dxBitmap.setModTypeface(null, 0);
                modTypeface = null;
                modTypefacePpem = 0;
            }
        } else {
            org.cocos2dx.lib.Cocos2dxBitmap.setModTypeface(null, 0);
            modTypeface = null;
            modTypefacePpem = 0;
        }
        return registered;
    }

    private static volatile android.graphics.Typeface modTypeface;
    private static volatile int modTypefacePpem;

    /** The active mod font, if one is currently loaded and registered via {@link #scan()}; {@code null} otherwise. Used by {@link com.kalenjohnson.chronoduo.PartyPanelView} so the second-screen panel's own text mirrors the field/menu font. */
    public static android.graphics.Typeface activeTypeface() {
        return modTypeface;
    }

    /** The active mod font's native bitmap-strike ppem (see {@link #fontNativePpem}), or {@code 0} if unknown/not a bitmap-strike font. */
    public static int activeTypefacePpem() {
        return modTypefacePpem;
    }

    // --- import ---------------------------------------------------------------

    /** Sanitizes a user-supplied display name into a safe mod directory name: strips the extension, keeps only {@code [A-Za-z0-9 _.-]}, falls back to "mod" if that leaves nothing. */
    public static String sanitizeName(String rawDisplayName) {
        String base = rawDisplayName == null ? "" : rawDisplayName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String cleaned = base.replaceAll("[^A-Za-z0-9 _.-]", "_").trim();
        return cleaned.isEmpty() ? "mod" : cleaned;
    }

    /**
     * Unpacks the zip data read from {@code in} into {@code
     * <modsRoot>/<name>/}, overwriting an existing mod of that name.
     * Extracts into a sibling {@code <name>.tmp} staging directory first and
     * only replaces the final directory once extraction succeeds, so a
     * failed/interrupted import never corrupts an existing mod.
     *
     * <p>Zip-slip protection: any entry whose resolved path would land
     * outside the staging directory is rejected (throws {@link IOException},
     * aborting the whole import). Directory entries and any entry under
     * {@code __MACOSX/} are skipped.
     *
     * <p>If, after extraction, the staging directory contains exactly one
     * child and that child is a directory whose own children include one of
     * the recognized archive roots ({@code Game}, {@code Localize}, {@code
     * Extension}, {@code Sound}, case-insensitive), that wrapper folder is
     * stripped -- its children are moved up a level -- so a zip built as
     * {@code MyMod/Game/...} behaves the same as one built as {@code
     * Game/...} directly.
     *
     * <p><b>Multi-archive downloads.</b> A Nexus "collection" download (e.g.
     * Pixel Demaster) unpacks into a tree of numbered option folders (
     * {@code 1 - Main File}, {@code 2 - Font/2.1 - SNES Font}, {@code 3 -
     * Interface/3.1 - UI/3.1.2 - Blue UI/3.1.2.1 - Battle Gauges}, ...),
     * each a mutually-exclusive variant packaged as its own nested {@code
     * .ctp}/{@code .zip}. Blindly expanding every one in place (as this
     * method used to) makes the variants overwrite each other file-by-file,
     * which is silently wrong -- so: if the tree under a single logical
     * root (peeling any chain of single-child wrapper folders, e.g. the
     * outer "Chrono Trigger Pixel Demaster/" folder) contains more than one
     * nested archive, each is expanded into its OWN mod directory instead,
     * named {@code "<name> - <option folder path>"} (each path segment's
     * leading numeric prefix, e.g. {@code "3.1.2.1 - "}, stripped; an
     * archive sitting directly at the logical root uses its own base name
     * instead of a folder path). Only the sub-mod whose name contains
     * "main" (case-insensitive) starts enabled; if none does, the
     * alphabetically first one does; the rest get a {@code .disabled}
     * marker. Every sub-mod also gets a {@code .group} file naming the
     * download, for future UI grouping. A single nested archive (the common
     * case -- one author, one {@code .ctp}) keeps today's one-mod-in-place
     * behavior exactly.
     *
     * <p>Loose files with no possible use on this platform ({@code
     * .xdelta} -- Steam-exe-only binary patches) are deleted from every mod
     * directory this produces, so they never get swept into a mod's
     * archive-substitution set by {@link #collect}. Any {@code
     * string_N.bin} sitting at a mod's root is decrypted to a font (see
     * {@link #processFonts}) rather than left as a loose file.
     *
     * <p>Pure Java I/O -- no Android dependency -- so this can run on a
     * plain JVM.
     *
     * @return the created mod director{y,ies}, see {@link ImportResult}
     */
    public static ImportResult extractZip(InputStream in, File modsRoot, String name) throws IOException {
        File stagingDir = new File(modsRoot, name + ".tmp");
        deleteRecursive(stagingDir);
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory()) {
            throw new IOException("cannot create staging directory " + stagingDir);
        }
        try {
            try (ZipInputStream zis = new ZipInputStream(in)) {
                unzipInto(zis, stagingDir);
            }
            stripSingleTopFolder(stagingDir);
            File logicalRoot = findLogicalRoot(stagingDir);
            List<File> nested = findNestedArchives(logicalRoot);

            List<File> createdDirs = new ArrayList<>();
            List<String> modNames = new ArrayList<>();
            int enabledCount = 0;

            if (nested.size() <= 1) {
                expandNestedArchives(stagingDir, 0);
                // Re-strip: expanding the sole nested archive can turn what
                // was a non-wrapper-looking folder (e.g. "SNESOverworld-9-1-1/
                // SNESOverworld.ctp", no asset root among its pre-expansion
                // children) into one that now directly wraps Game/Localize/
                // Extension/Sound -- exactly the case this strips.
                stripSingleTopFolder(stagingDir);
                deleteNonAssetLooseFiles(stagingDir);
                processFonts(stagingDir);
                File finalDir = new File(modsRoot, name);
                deleteRecursive(finalDir);
                if (!stagingDir.renameTo(finalDir)) {
                    throw new IOException("could not move staged mod into place: " + finalDir);
                }
                createdDirs.add(finalDir);
                modNames.add(finalDir.getName());
                enabledCount = 1;
            } else {
                List<String> subNames = new ArrayList<>(nested.size());
                for (File archive : nested) subNames.add(subModName(name, logicalRoot, archive));
                int enabledIdx = pickEnabledSubMod(subNames);

                for (int i = 0; i < nested.size(); i++) {
                    File archive = nested.get(i);
                    String subName = subNames.get(i);
                    File finalSub = extractSubMod(modsRoot, archive, subName);
                    writeGroup(finalSub, name);
                    if (i == enabledIdx) {
                        enabledCount++;
                    } else {
                        new File(finalSub, DISABLED_MARKER).createNewFile();
                    }
                    createdDirs.add(finalSub);
                    modNames.add(subName);
                }
            }
            return new ImportResult(createdDirs.get(0), modNames, enabledCount, name);
        } finally {
            deleteRecursive(stagingDir); // no-op if the single-mod branch already renamed it away
        }
    }

    /** Extracts one nested archive (a sub-mod's {@code .ctp}/{@code .zip}) into {@code <modsRoot>/<subName>}, applying the same wrapper-strip/loose-file/font handling a top-level import gets. */
    private static File extractSubMod(File modsRoot, File archive, String subName) throws IOException {
        File subStaging = new File(modsRoot, subName + ".tmp");
        deleteRecursive(subStaging);
        if (!subStaging.mkdirs() && !subStaging.isDirectory()) {
            throw new IOException("cannot create staging directory " + subStaging);
        }
        boolean ok = false;
        try {
            try (ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(archive)))) {
                unzipInto(zis, subStaging);
            }
            stripSingleTopFolder(subStaging);
            deleteNonAssetLooseFiles(subStaging);
            processFonts(subStaging);
            File finalSub = new File(modsRoot, subName);
            deleteRecursive(finalSub);
            if (!subStaging.renameTo(finalSub)) {
                throw new IOException("could not move staged mod into place: " + finalSub);
            }
            ok = true;
            return finalSub;
        } finally {
            if (!ok) deleteRecursive(subStaging);
        }
    }

    /** Peels a chain of single-child wrapper directories (e.g. the outer "Chrono Trigger Pixel Demaster/" folder around a multi-option Nexus download) to find the effective root against which nested-archive folder paths are computed. Does not move anything on disk -- purely for path computation. */
    private static File findLogicalRoot(File dir) {
        File root = dir;
        while (true) {
            File[] children = root.listFiles();
            if (children != null && children.length == 1 && children[0].isDirectory()) {
                root = children[0];
            } else {
                break;
            }
        }
        return root;
    }

    /** Collects every {@code .ctp}/{@code .zip} anywhere under {@code dir} (dotfiles/dirs and {@code __MACOSX} skipped), sorted by path for deterministic sub-mod ordering. */
    private static List<File> findNestedArchives(File dir) {
        List<File> out = new ArrayList<>();
        collectNestedArchives(dir, out);
        out.sort(Comparator.comparing(File::getAbsolutePath));
        return out;
    }

    private static void collectNestedArchives(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            String n = c.getName();
            if (n.startsWith(".") || n.equals("__MACOSX")) continue;
            if (c.isDirectory()) {
                collectNestedArchives(c, out);
                continue;
            }
            String lower = n.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".ctp") || lower.endsWith(".zip")) out.add(c);
        }
    }

    /**
     * Builds a sub-mod's directory name: {@code "<downloadName> - <folder
     * segment> - <folder segment> - ..."}, each segment being one of {@code
     * archive}'s parent folders relative to {@code logicalRoot} with its
     * leading numeric prefix (e.g. {@code "3.1.2.1 - "}) stripped; if {@code
     * archive} sits directly at {@code logicalRoot} (no parent folders),
     * its own base name (extension stripped) is used as the sole segment
     * instead. "/" is our conceptual separator but isn't legal in a
     * filename, so on disk (which is also the display name -- see the
     * class doc) every level, including the leading download name, is
     * joined with {@code " - "}.
     */
    private static String subModName(String downloadName, File logicalRoot, File archive) {
        List<String> segs = new ArrayList<>();
        File cur = archive.getParentFile();
        while (cur != null && !cur.equals(logicalRoot)) {
            segs.add(0, cur.getName());
            cur = cur.getParentFile();
        }
        List<String> parts = new ArrayList<>();
        parts.add(downloadName);
        if (segs.isEmpty()) {
            String base = archive.getName();
            int dot = base.lastIndexOf('.');
            parts.add(cleanSegment(dot > 0 ? base.substring(0, dot) : base));
        } else {
            for (String s : segs) parts.add(cleanSegment(s));
        }
        return sanitizeFileNamePart(String.join(" - ", parts));
    }

    // Strips a leading "N", "N.N", "N.N.N - " ... numeric outline prefix
    // (Nexus's FOMOD-style option-folder naming) before the sanitize pass.
    private static final java.util.regex.Pattern NUMERIC_PREFIX =
            java.util.regex.Pattern.compile("^\\d+(?:\\.\\d+)*\\s*-\\s*");

    private static String cleanSegment(String s) {
        String stripped = NUMERIC_PREFIX.matcher(s.trim()).replaceFirst("");
        return sanitizeFileNamePart(stripped.isEmpty() ? s : stripped);
    }

    /** Replaces characters illegal in an Android/Linux file name; unlike {@link #sanitizeName} this does NOT strip a trailing ".ext" -- callers here are already building one path segment, not passing through a raw file name. */
    private static String sanitizeFileNamePart(String s) {
        String cleaned = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "part" : cleaned;
    }

    /** Picks the sub-mod to start enabled: the first (in {@code subNames} order) whose name contains "main" case-insensitively, else the alphabetically first. */
    private static int pickEnabledSubMod(List<String> subNames) {
        for (int i = 0; i < subNames.size(); i++) {
            if (subNames.get(i).toLowerCase(Locale.ROOT).contains("main")) return i;
        }
        int best = 0;
        for (int i = 1; i < subNames.size(); i++) {
            if (subNames.get(i).compareToIgnoreCase(subNames.get(best)) < 0) best = i;
        }
        return best;
    }

    /** Best-effort {@code .group} write recording {@code downloadName}, mirroring how {@link #importCatalogMod} writes {@code .source} -- a failure doesn't fail the import. */
    private static void writeGroup(File modDir, String downloadName) {
        try (OutputStream out = new FileOutputStream(new File(modDir, ".group"))) {
            out.write(downloadName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "mods: could not write .group for " + modDir, e);
        }
    }

    /** Recursively deletes every file under {@code dir} whose extension is in {@link #NON_ASSET_EXTENSIONS} (currently just {@code .xdelta} -- Steam-exe-only patches; see the class doc). */
    private static void deleteNonAssetLooseFiles(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                deleteNonAssetLooseFiles(c);
                continue;
            }
            String n = c.getName();
            int dot = n.lastIndexOf('.');
            if (dot < 0) continue;
            if (NON_ASSET_EXTENSIONS.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT))) c.delete();
        }
    }

    /**
     * Decrypts every {@code string_N.bin} at {@code modDir}'s root (a Steam
     * font patch payload -- see {@link
     * com.kalenjohnson.chronoduo.saveimport.CtContainer}, which uses the
     * same container scheme as a save file) into a font file, then deletes
     * the {@code .bin}: the lowest-numbered one becomes {@code font.ttf}
     * (the mod's default font -- see {@link #findFont}), any others become
     * {@code font_N.ttf} (named after their own N, kept for a future "pick
     * a variant" UI but not otherwise used yet). A {@code .bin} that
     * doesn't decrypt to a recognizable TTF/OTF (bad key guess, corrupt
     * download, wrong file) is logged and dropped -- nothing is written for
     * it, per the class doc's "on failure, keep nothing".
     */
    private static void processFonts(File modDir) {
        File[] children = modDir.listFiles();
        if (children == null) return;
        List<File> binFiles = new ArrayList<>();
        for (File f : children) {
            if (f.isFile() && STRING_BIN.matcher(f.getName()).matches()) binFiles.add(f);
        }
        if (binFiles.isEmpty()) return;
        binFiles.sort(Comparator.comparingInt(ModManager::binIndex));

        boolean wroteDefault = false;
        for (File bin : binFiles) {
            try {
                byte[] data = readAllBytes(bin);
                // decryptToPlaintext, NOT decrypt(): the latter also strips
                // a trailing u32 LE length word that only save-container
                // payloads carry (nsCrypt::Manager's encrypt() trailer) --
                // a font patch's string_N.bin is just the raw TTF bytes
                // Blowfish-CBC'd with no such trailer (verified against
                // tools/saves/ctcrypto.py's decrypt(), which does the same
                // plain CBC-decrypt-and-return with no length unwrap).
                byte[] decrypted = com.kalenjohnson.chronoduo.saveimport.CtContainer.decryptToPlaintext(data);
                if (looksLikeFont(decrypted)) {
                    String outName = !wroteDefault ? "font.ttf" : "font_" + binIndex(bin) + ".ttf";
                    try (OutputStream out = new FileOutputStream(new File(modDir, outName))) {
                        out.write(decrypted);
                    }
                    wroteDefault = true;
                } else {
                    Log.w(TAG, "mods: " + bin.getName() + " did not decrypt to a recognizable font, skipping");
                }
            } catch (Exception e) {
                Log.w(TAG, "mods: could not decrypt " + bin.getName(), e);
            }
            bin.delete();
        }
    }

    private static int binIndex(File bin) {
        java.util.regex.Matcher m = STRING_BIN.matcher(bin.getName());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** TTF (0x00010000) / older-Mac-TTF ("true") / OTF ("OTTO") magic check on decrypted font bytes. */
    private static boolean looksLikeFont(byte[] data) {
        if (data.length < 4) return false;
        if (data[0] == 0x00 && data[1] == 0x01 && data[2] == 0x00 && data[3] == 0x00) return true;
        if (data[0] == 't' && data[1] == 'r' && data[2] == 'u' && data[3] == 'e') return true;
        return data[0] == 'O' && data[1] == 'T' && data[2] == 'T' && data[3] == 'O';
    }

    private static byte[] readAllBytes(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** Extracts every regular entry of {@code zis} under {@code dir} (zip-slip checked, {@code __MACOSX} skipped). */
    private static void unzipInto(ZipInputStream zis, File dir) throws IOException {
        ZipEntry entry;
        byte[] buf = new byte[8192];
        while ((entry = zis.getNextEntry()) != null) {
            String entryName = entry.getName();
            if (entryName == null || entryName.isEmpty()) continue;
            if (entryName.contains("__MACOSX/") || entryName.equals("__MACOSX")) continue;
            if (entry.isDirectory()) continue;
            File outFile = safeResolve(dir, entryName);
            if (outFile == null) {
                throw new IOException("zip entry escapes target directory: " + entryName);
            }
            File parent = outFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("cannot create directory " + parent);
            }
            try (OutputStream out = new FileOutputStream(outFile)) {
                int n;
                while ((n = zis.read(buf)) > 0) out.write(buf, 0, n);
            }
            zis.closeEntry();
        }
    }

    /**
     * Nexus downloads usually wrap the author's {@code .ctp} in an outer
     * {@code .zip} (e.g. "SNES Overworld Sprites Restoration-9-1-1.zip"
     * containing {@code SNESOverworld.ctp}). A {@code .ctp} is itself a zip
     * of archive-relative paths, so any {@code .ctp}/{@code .zip} left on
     * disk after extraction is expanded in place (next to where it sat) and
     * deleted, up to two levels deep. Only called by {@link #extractZip}
     * when there's a single nested archive to begin with -- a download with
     * several (e.g. Pixel Demaster's per-option {@code .ctp}s) is split
     * into one mod directory per archive instead; see that method's class
     * doc.
     */
    private static void expandNestedArchives(File dir, int depth) throws IOException {
        if (depth > 2) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                expandNestedArchives(c, depth);
                continue;
            }
            String lower = c.getName().toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".ctp") || lower.endsWith(".zip"))) continue;
            File parent = c.getParentFile() != null ? c.getParentFile() : dir;
            try (ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(c)))) {
                unzipInto(zis, parent);
            }
            if (!c.delete()) throw new IOException("could not remove nested archive " + c);
            expandNestedArchives(parent, depth + 1);
        }
    }

    /**
     * Resolves a zip entry name against {@code baseDir}, rejecting (returns
     * null) any entry whose canonical path would land outside {@code
     * baseDir} -- the zip-slip check (a {@code ../../evil} entry, or an
     * absolute path, must not escape the staging directory).
     */
    private static File safeResolve(File baseDir, String entryName) throws IOException {
        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isEmpty()) return null;
        File target = new File(baseDir, normalized);
        String basePath = baseDir.getCanonicalPath() + File.separator;
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(basePath)) return null;
        return target;
    }

    /** See {@link #extractZip}'s single-top-folder-stripping paragraph. */
    private static void stripSingleTopFolder(File dir) {
        File[] children = dir.listFiles();
        if (children == null || children.length != 1 || !children[0].isDirectory()) return;
        File sole = children[0];
        File[] grandchildren = sole.listFiles();
        if (grandchildren == null) return;
        boolean looksLikeWrapper = false;
        for (File gc : grandchildren) {
            if (gc.isDirectory() && ASSET_ROOTS.contains(gc.getName().toLowerCase(Locale.ROOT))) {
                looksLikeWrapper = true;
                break;
            }
        }
        if (!looksLikeWrapper) return;
        for (File gc : grandchildren) {
            gc.renameTo(new File(dir, gc.getName()));
        }
        sole.delete();
    }

    /** Recursively deletes {@code f} (file or directory); a no-op if it doesn't exist. Best-effort. */
    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    /**
     * Imports a mod archive already opened as {@code in} (a {@code .ctp} or
     * {@code .zip} zip stream), naming the mod from {@code displayName}
     * (sanitized -- see {@link #sanitizeName}) into {@code modsRoot}, and
     * rescanning if {@code afterImport} is non-null (pass this instance's
     * {@link #scan} via a lambda from the Android call site, or null to skip
     * rescanning -- e.g. from a JVM test). No Android dependency.
     */
    public static ImportResult importArchive(InputStream in, String displayName, File modsRoot) throws IOException {
        String name = sanitizeName(displayName);
        if (!modsRoot.isDirectory() && !modsRoot.mkdirs() && !modsRoot.isDirectory()) {
            throw new IOException("cannot create mods directory " + modsRoot);
        }
        return extractZip(in, modsRoot, name);
    }

    /**
     * Imports a mod archive picked via SAF: reads {@code uri} through {@code
     * resolver}, derives the mod name from its display name (falling back to
     * the URI's last path segment), extracts it under this instance's mod
     * root, then rescans (see {@link #scan}). Runs entirely synchronously --
     * callers should invoke this off the main thread, mirroring {@code
     * AppActivity#importRomFromUri}/{@code #importSaveFromUri}.
     *
     * @return the import result (mod directories created)
     */
    public ImportResult importArchive(Uri uri, ContentResolver resolver) throws IOException {
        String displayName = queryDisplayName(resolver, uri);
        if (displayName == null || displayName.isEmpty()) {
            String seg = uri.getLastPathSegment();
            displayName = seg != null ? seg : "mod";
        }
        ImportResult result;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("could not open " + uri);
            result = importArchive(in, displayName, root);
        }
        scan();
        return result;
    }

    /**
     * Like {@link #importArchive(InputStream, String, File)}, but under an
     * EXACT directory name -- no {@link #sanitizeName} pass -- since the
     * caller (the mod catalog flow in {@code AppActivity}, via {@link
     * #importCatalogMod}) already has a known-safe slug (a {@link
     * com.kalenjohnson.chronoduo.mods.ModCatalog.Entry#id}), not a
     * user-supplied display name. Same overwrite-existing/staging-directory
     * semantics as the sibling overload. Pure Java I/O.
     */
    public static ImportResult importArchiveNamed(InputStream in, File modsRoot, String forcedName) throws IOException {
        if (!modsRoot.isDirectory() && !modsRoot.mkdirs() && !modsRoot.isDirectory()) {
            throw new IOException("cannot create mods directory " + modsRoot);
        }
        return extractZip(in, modsRoot, forcedName);
    }

    /**
     * Imports a mod obtained through the curated catalog (see {@code
     * com.kalenjohnson.chronoduo.mods.ModCatalog} and {@code
     * AppActivity#downloadAndImportMod}/{@code #handleViewIntent}): unpacks
     * {@code in} under this instance's mod root using the exact directory
     * name {@code id} (a catalog slug -- see {@link #importArchiveNamed}),
     * writes a {@code .source} provenance file recording {@code sourceUrl}
     * into EVERY mod directory this produces (best-effort; a failure to
     * write it doesn't fail the import -- {@code scan()}'s dotfile skip at
     * the mod root already ignores it either way; a multi-archive download
     * -- see {@link #extractZip} -- can produce more than one), then
     * rescans.
     *
     * @return the import result (mod directories created)
     */
    public ImportResult importCatalogMod(InputStream in, String id, String sourceUrl) throws IOException {
        ImportResult result = importArchiveNamed(in, root, id);
        if (sourceUrl != null) {
            for (String modName : result.modNames) {
                try (OutputStream out = new FileOutputStream(new File(new File(root, modName), ".source"))) {
                    out.write(sourceUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (IOException e) {
                    Log.w(TAG, "mods: could not write .source for " + modName, e);
                }
            }
        }
        scan();
        return result;
    }

    /** Package-visible for {@code AppActivity}'s {@code .ctp} intent-filter handler, which needs the same display-name lookup this class already does for {@link #importArchive(Uri, ContentResolver)}. */
    public static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "mods: could not query display name for " + uri, e);
        }
        return null;
    }

    // --- enable/disable ---------------------------------------------------------

    /**
     * Enables or disables the mod named {@code name} by deleting/creating a
     * {@code .disabled} marker file in its directory, then rescans. A no-op
     * (still rescans) if the mod directory doesn't exist.
     */
    public void setEnabled(String name, boolean enabled) {
        File modDir = new File(root, name);
        File marker = new File(modDir, DISABLED_MARKER);
        if (enabled) {
            marker.delete();
        } else {
            try {
                if (!marker.exists()) marker.createNewFile();
            } catch (IOException e) {
                Log.w(TAG, "mods: could not disable " + name, e);
            }
        }
        scan();
    }
}
