package com.kalenjohnson.chronoduo.mods;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * JVM coverage for {@link ModManager#removeConflictingMods} -- the dedupe
 * pass {@link ModManager#importCatalogMod} runs before extracting, so
 * re-installing a catalog mod under its current id replaces (rather than
 * duplicates) an earlier install of the SAME download that landed under a
 * different name -- e.g. a manual "Import file..." done before the catalog
 * existed (or under an older fileHint), followed by a catalog "Get" that
 * would otherwise install a second, differently-named copy alongside it.
 */
public class ModManagerDedupeTest {
    private File tmpRoot;
    private File modsRoot;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("modmanager-dedupe-test").toFile();
        modsRoot = new File(tmpRoot, "mods");
        assertTrue(modsRoot.mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursive(tmpRoot);
    }

    private static void makeModDir(File modsRoot, String name, String groupOrNull) throws IOException {
        File dir = new File(modsRoot, name);
        assertTrue(dir.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(dir, "asset.dat"))) {
            out.write(new byte[]{1});
        }
        if (groupOrNull != null) {
            try (FileOutputStream out = new FileOutputStream(new File(dir, ".group"))) {
                out.write(groupOrNull.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static List<String> listDirNames(File modsRoot) {
        List<String> out = new ArrayList<>();
        File[] children = modsRoot.listFiles();
        if (children != null) {
            for (File c : children) if (c.isDirectory()) out.add(c.getName());
        }
        return out;
    }

    /**
     * The actual on-device scenario: a manual import done before the
     * catalog existed split into sub-mods under the raw display name
     * "PixelDemaster" (its {@code .group} marker), and now a catalog Get of
     * {@code pixel-demaster} (fileHint "Demaster") is about to install
     * "pixel-demaster - ..." sub-mods -- the old group must be wiped first.
     */
    @Test
    public void removesEarlierManualImportOfSameDownloadByFileHint() throws IOException {
        makeModDir(modsRoot, "PixelDemaster - Main File", "PixelDemaster");
        makeModDir(modsRoot, "PixelDemaster - Font - SNES Font", "PixelDemaster");
        makeModDir(modsRoot, "SNES Wood Menu", null); // unrelated mod, must survive

        ModManager.removeConflictingMods(modsRoot, "pixel-demaster", "Demaster");

        List<String> remaining = listDirNames(modsRoot);
        assertEquals(List.of("SNES Wood Menu"), remaining);
    }

    /** A previous catalog install under the SAME id's sub-mod naming is removed too (the ordinary re-Get/overwrite case). */
    @Test
    public void removesEarlierCatalogInstallBySharedNamePrefix() throws IOException {
        makeModDir(modsRoot, "pixel-demaster - Main File", "pixel-demaster");
        makeModDir(modsRoot, "pixel-demaster - Font - SNES Font", "pixel-demaster");

        ModManager.removeConflictingMods(modsRoot, "pixel-demaster", "Demaster");

        assertTrue(listDirNames(modsRoot).isEmpty());
    }

    /** No {@code .group} marker at all (a single-archive manual import, sanitized name) still matches via the name-prefix rule when it happens to share the id's prefix, and via a matching un-hinted single dir is left alone if unrelated. */
    @Test
    public void leavesUnrelatedModsUntouched() throws IOException {
        makeModDir(modsRoot, "Consistent Magus Sprite", null);
        makeModDir(modsRoot, "SNES Wood Menu", "SNES Wood Menu");

        ModManager.removeConflictingMods(modsRoot, "pixel-demaster", "Demaster");

        List<String> remaining = listDirNames(modsRoot);
        assertEquals(2, remaining.size());
        assertTrue(remaining.contains("Consistent Magus Sprite"));
        assertTrue(remaining.contains("SNES Wood Menu"));
    }

    /** A null/empty fileHint (some catalog entries have none) still lets the id-prefix and exact-group-id rules work. */
    @Test
    public void worksWithNullFileHint() throws IOException {
        makeModDir(modsRoot, "consistent-magus - Main File", "consistent-magus");
        makeModDir(modsRoot, "unrelated-mod", null);

        ModManager.removeConflictingMods(modsRoot, "consistent-magus", null);

        assertEquals(List.of("unrelated-mod"), listDirNames(modsRoot));
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
