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

        public ScanResult(List<ModInfo> mods, String[] archivePaths, String[] diskPaths) {
            this.mods = mods;
            this.archivePaths = archivePaths;
            this.diskPaths = diskPaths;
        }
    }

    private final File root;
    private volatile ScanResult lastResult;

    /** {@code externalFilesDir} is the app's external files directory (see AppActivity's {@code getExternalFilesDir(null)}); the mod root is {@code <externalFilesDir>/mods}. */
    public ModManager(File externalFilesDir) {
        this.root = new File(externalFilesDir, MODS_DIR_NAME);
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
                if (atRoot) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("readme") || lower.endsWith(".txt")) continue;
                }
                out.add(new String[]{rel, entry.getAbsolutePath()});
            }
        }
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
        return registered;
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
     * <p>Pure Java I/O -- no Android dependency -- so this can run on a
     * plain JVM.
     *
     * @return the mod's final directory ({@code <modsRoot>/<name>})
     */
    public static File extractZip(InputStream in, File modsRoot, String name) throws IOException {
        File finalDir = new File(modsRoot, name);
        File stagingDir = new File(modsRoot, name + ".tmp");
        deleteRecursive(stagingDir);
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory()) {
            throw new IOException("cannot create staging directory " + stagingDir);
        }
        boolean ok = false;
        try {
            try (ZipInputStream zis = new ZipInputStream(in)) {
                unzipInto(zis, stagingDir);
            }
            expandNestedArchives(stagingDir, 0);
            stripSingleTopFolder(stagingDir);
            deleteRecursive(finalDir);
            if (!stagingDir.renameTo(finalDir)) {
                throw new IOException("could not move staged mod into place: " + finalDir);
            }
            ok = true;
            return finalDir;
        } finally {
            if (!ok) deleteRecursive(stagingDir);
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
     * deleted, up to two levels deep. Several {@code .ctp} variants in one
     * download (e.g. "with Consistent Magus" / "without") all get expanded;
     * later ones overwrite earlier ones file-by-file, which is the best a
     * blind importer can do -- the status line reports the file count.
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
    public static File importArchive(InputStream in, String displayName, File modsRoot) throws IOException {
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
     * @return the mod's final directory
     */
    public File importArchive(Uri uri, ContentResolver resolver) throws IOException {
        String displayName = queryDisplayName(resolver, uri);
        if (displayName == null || displayName.isEmpty()) {
            String seg = uri.getLastPathSegment();
            displayName = seg != null ? seg : "mod";
        }
        File modDir;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("could not open " + uri);
            modDir = importArchive(in, displayName, root);
        }
        scan();
        return modDir;
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
    public static File importArchiveNamed(InputStream in, File modsRoot, String forcedName) throws IOException {
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
     * (best-effort; a failure to write it doesn't fail the import -- {@code
     * scan()}'s dotfile skip at the mod root already ignores it either way),
     * then rescans.
     *
     * @return the mod's final directory
     */
    public File importCatalogMod(InputStream in, String id, String sourceUrl) throws IOException {
        File modDir = importArchiveNamed(in, root, id);
        if (sourceUrl != null) {
            try (OutputStream out = new FileOutputStream(new File(modDir, ".source"))) {
                out.write(sourceUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "mods: could not write .source for " + id, e);
            }
        }
        scan();
        return modDir;
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
