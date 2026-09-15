package com.kalenjohnson.chronoduo.mods;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
 * JVM coverage for the Mods-page "one row per multi-.ctp download, with
 * toggleable option groups" feature: {@link ModManager#groups} splitting a
 * real Pixel Demaster-shaped set of sub-mod directories back into a main
 * mod plus option groups, {@link ModCatalog#findInstalledDirName}'s
 * group-aware overload preferring the main dir over an alphabetically-
 * earlier option sub-dir, and {@link ModManager#applySelectOption}'s radio-
 * button semantics. Everything exercised here is pure {@code java.io} (no
 * {@link ModManager#scan}/native calls), same as {@link
 * ModManagerMultiImportTest}.
 *
 * <p>The fixture directory names below are exactly the on-device layout
 * described for this feature (a subset of the real ~30-folder Nexus
 * "Chrono Trigger Pixel Demaster" download -- e.g. 3 of its 9 button-prompt
 * choices and 2 of its 5 UI colours, enough to exercise every parsing rule
 * without the full 30).
 */
public class ModManagerGroupingTest {
    private static final String DOWNLOAD_NAME = "Chrono Trigger Pixel Demaster";

    private File tmpRoot;
    private File modsRoot;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("modmanager-grouping-test").toFile();
        modsRoot = new File(tmpRoot, "mods");
        assertTrue(modsRoot.mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursive(tmpRoot);
    }

    // Every sub-mod of the fixture download, main first. Mirrors the real
    // on-device folder names given for this feature (a representative
    // subset of Button Prompts' 9 choices and UI's 5 colours).
    private static final String[] SUBMOD_DIRS = {
            "PixelDemaster - Main File",
            "PixelDemaster - Font - Original Font (Backup)",
            "PixelDemaster - Font - SNES Font",
            "PixelDemaster - Font - SNES Font (Playstation Buttons)",
            "PixelDemaster - Interface - Art Icons - Solid Color Background",
            "PixelDemaster - Interface - Art Icons - Transparent Background",
            "PixelDemaster - Interface - Button Prompts Textures - PS4 Button Prompts",
            "PixelDemaster - Interface - Button Prompts Textures - Switch Pro Button Prompts",
            "PixelDemaster - Interface - Button Prompts Textures - Xbox One Button Prompts",
            "PixelDemaster - Interface - Interaction Icons - Interaction Icons",
            "PixelDemaster - Interface - Interaction Icons - No Interaction Icons",
            "PixelDemaster - Interface - Inventory Icons - Black & White Icons",
            "PixelDemaster - Interface - Inventory Icons - Colored Icons",
            "PixelDemaster - Interface - UI - Black UI - Battle Gauges",
            "PixelDemaster - Interface - UI - Black UI - No Battle Gauges",
            "PixelDemaster - Interface - UI - Blue UI - Battle Gauges",
            "PixelDemaster - Interface - UI - Blue UI - No Battle Gauges",
    };

    /** Only the Main File and the "SNES Font" font choice start enabled -- mirrors a user who's already picked a font, matching the class doc's "fresh install starts every option disabled" default plus one deliberate choice. */
    private static final String ENABLED_FONT_CHOICE = "PixelDemaster - Font - SNES Font";

    private void createFixture() throws IOException {
        for (String name : SUBMOD_DIRS) {
            File dir = new File(modsRoot, name);
            assertTrue(dir.mkdirs());
            // A real (non-dotfile) asset, so collect() doesn't sweep the dir
            // away as import residue (see its "hasVisibleFile" doc).
            try (FileOutputStream out = new FileOutputStream(new File(dir, "asset.dat"))) {
                out.write(new byte[]{1});
            }
            writeFile(new File(dir, ".group"), DOWNLOAD_NAME);
            boolean enabled = name.equals("PixelDemaster - Main File") || name.equals(ENABLED_FONT_CHOICE);
            if (!enabled) {
                assertTrue(new File(dir, ".disabled").createNewFile());
            }
        }
    }

    private static void writeFile(File f, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    // --- name parsing into groups/options -----------------------------------

    @Test
    public void groupsSplitsMainAndOptionGroupsFromFolderNames() throws IOException {
        createFixture();
        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);

        assertEquals(1, gr.groups.size());
        assertTrue(gr.ungrouped.isEmpty());
        ModManager.ModGroup g = gr.groups.get(0);
        assertEquals(DOWNLOAD_NAME, g.downloadName);

        // Main detection: the single-segment "Main File" sub-mod.
        assertEquals("PixelDemaster - Main File", g.mainDir);
        assertTrue(g.mainEnabled);

        // Option group titles, sorted -- every segment but the last, joined
        // with " - "; same-shaped colour variants (UI Black/Blue) stay
        // separate groups rather than being merged.
        List<String> titles = new ArrayList<>();
        for (ModManager.OptionGroup og : g.options) titles.add(og.title);
        assertEquals(List.of(
                "Font",
                "Interface - Art Icons",
                "Interface - Button Prompts Textures",
                "Interface - Interaction Icons",
                "Interface - Inventory Icons",
                "Interface - UI - Black UI",
                "Interface - UI - Blue UI"
        ), titles);

        // Choices + labels (last folder segment) for a couple of
        // representative option groups.
        ModManager.OptionGroup font = findOption(g, "Font");
        assertEquals(3, font.choices.size());
        assertEquals("Original Font (Backup)", font.choices.get(0).label);
        assertEquals("PixelDemaster - Font - Original Font (Backup)", font.choices.get(0).dir);
        assertEquals("SNES Font", font.choices.get(1).label);
        assertEquals("SNES Font (Playstation Buttons)", font.choices.get(2).label);
        // Only the SNES Font choice starts enabled (see createFixture).
        assertFalse(font.choices.get(0).enabled);
        assertTrue(font.choices.get(1).enabled);
        assertFalse(font.choices.get(2).enabled);
        assertEquals("PixelDemaster - Font - SNES Font", font.selected());

        ModManager.OptionGroup buttonPrompts = findOption(g, "Interface - Button Prompts Textures");
        assertEquals(3, buttonPrompts.choices.size());
        assertEquals("PS4 Button Prompts", buttonPrompts.choices.get(0).label);
        assertEquals("Switch Pro Button Prompts", buttonPrompts.choices.get(1).label);
        assertEquals("Xbox One Button Prompts", buttonPrompts.choices.get(2).label);
        assertNull(buttonPrompts.selected()); // none enabled by default

        ModManager.OptionGroup uiBlack = findOption(g, "Interface - UI - Black UI");
        assertEquals(2, uiBlack.choices.size());
        assertEquals("Battle Gauges", uiBlack.choices.get(0).label);
        assertEquals("No Battle Gauges", uiBlack.choices.get(1).label);
    }

    private static ModManager.OptionGroup findOption(ModManager.ModGroup g, String title) {
        for (ModManager.OptionGroup og : g.options) {
            if (og.title.equals(title)) return og;
        }
        throw new AssertionError("no option group titled " + title);
    }

    @Test
    public void ungroupedModsHaveNoGroupMarker() throws IOException {
        createFixture();
        File plain = new File(modsRoot, "SNES Overworld Sprites Restoration");
        assertTrue(plain.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(plain, "asset.dat"))) {
            out.write(new byte[]{1});
        }

        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);
        assertEquals(1, gr.groups.size());
        assertEquals(1, gr.ungrouped.size());
        assertEquals("SNES Overworld Sprites Restoration", gr.ungrouped.get(0).name);
    }

    // --- catalog matching prefers the main dir ------------------------------

    @Test
    public void findInstalledDirNamePrefersGroupMainDirOverOptionSubDir() throws IOException {
        createFixture();
        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);

        List<String> dirNames = new ArrayList<>();
        for (ModManager.ModInfo m : scan.mods) dirNames.add(m.name);

        ModCatalog.Entry entry = new ModCatalog.Entry("pixel-demaster", "Chrono Trigger Pixel Demaster", null,
                "summary", "https://example.invalid/page", null, "Pixel Demaster", null);

        // Without group info, the old plain-substring match lands on the
        // alphabetically-first dir containing "Pixel Demaster"'s hint --
        // demonstrating the bug this feature fixes (a Font variant, never
        // the actual main mod).
        String legacyMatch = ModCatalog.findInstalledDirName(entry, dirNames);
        assertTrue(legacyMatch == null || legacyMatch.toLowerCase(java.util.Locale.ROOT).contains("font")
                || legacyMatch.equals("PixelDemaster - Main File"));

        // Group-aware lookup must land on the group's main dir, never an
        // option sub-dir, even though "Pixel Demaster" (the fileHint) only
        // literally appears in the .group-derived download name, not in any
        // folder name itself (folders use "PixelDemaster", no space).
        String dir = ModCatalog.findInstalledDirName(entry, dirNames, gr.groups);
        assertEquals("PixelDemaster - Main File", dir);
    }

    /**
     * Regression test for the actual on-device bug report: the real {@code
     * .group} marker written by {@link ModManager} holds the RAW download
     * name with no spaces added (e.g. {@code "PixelDemaster"}), unlike the
     * fixture above which uses a hand-picked {@code DOWNLOAD_NAME} of
     * "Chrono Trigger Pixel Demaster" (spaced) that happens to contain the
     * old {@code fileHint} of "Pixel Demaster" as a literal substring. With
     * a device-shaped, space-free download name, that old hint never
     * matches ("pixeldemaster" does not contain "pixel demaster"), so the
     * catalog lookup fell through and the Mods page showed a plain
     * "PixelDemaster / Imported mod" row instead of the catalog's "Chrono
     * Trigger Pixel Demaster" name/summary. catalog.json's {@code
     * pixel-demaster} entry now uses {@code fileHint: "Demaster"} instead,
     * which is a substring of "PixelDemaster" regardless of spacing.
     */
    @Test
    public void findInstalledDirNameMatchesDeviceShapedDownloadName() throws IOException {
        String deviceDownloadName = "PixelDemaster"; // no "Chrono Trigger" prefix, no space
        String[] dirs = {
                "PixelDemaster - Main File",
                "PixelDemaster - Font - SNES Font",
        };
        for (String name : dirs) {
            File dir = new File(modsRoot, name);
            assertTrue(dir.mkdirs());
            try (FileOutputStream out = new FileOutputStream(new File(dir, "asset.dat"))) {
                out.write(new byte[]{1});
            }
            writeFile(new File(dir, ".group"), deviceDownloadName);
        }

        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);
        List<String> dirNames = new ArrayList<>();
        for (ModManager.ModInfo m : scan.mods) dirNames.add(m.name);

        // The old hint never matches this device-shaped download name --
        // demonstrates the bug rather than just the fix.
        ModCatalog.Entry oldHintEntry = new ModCatalog.Entry("pixel-demaster", "Chrono Trigger Pixel Demaster",
                null, "summary", "https://example.invalid/page", null, "Pixel Demaster", null);
        assertNull(ModCatalog.findInstalledDirName(oldHintEntry, dirNames, gr.groups));

        // The shipped fileHint ("Demaster") matches regardless of spacing
        // and resolves to the group's main dir.
        ModCatalog.Entry entry = new ModCatalog.Entry("pixel-demaster", "Chrono Trigger Pixel Demaster",
                null, "summary", "https://example.invalid/page", null, "Demaster", null);
        assertEquals("PixelDemaster - Main File", ModCatalog.findInstalledDirName(entry, dirNames, gr.groups));
    }

    @Test
    public void findInstalledDirNameNeverMatchesAnOptionSubDirByPlainHint() throws IOException {
        createFixture();
        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);
        List<String> dirNames = new ArrayList<>();
        for (ModManager.ModInfo m : scan.mods) dirNames.add(m.name);

        // A hint that only matches an OPTION sub-dir (never the group's
        // downloadName or mainDir) must not resolve to that sub-dir --
        // an option choice is never "the installed mod" for a catalog row.
        ModCatalog.Entry entry = new ModCatalog.Entry("snes-font-only", "SNES Font Only", null,
                "summary", "https://example.invalid/page", null, "SNES Font", null);
        assertNull(ModCatalog.findInstalledDirName(entry, dirNames, gr.groups));
    }

    /**
     * A group whose {@code .group} marker (downloadName) is EXACTLY the
     * catalog entry's id -- how {@link ModManager#importCatalogMod} names
     * every sub-mod's group today -- must match even for an entry with NO
     * fileHint at all (the old code's group-aware branch skipped entirely
     * when {@code hint == null}, so a catalog entry like "consistent-magus"
     * that has no fileHint could never show as installed once it became a
     * multi-.ctp download).
     */
    @Test
    public void findInstalledDirNameMatchesExactIdDownloadNameWithNoFileHint() throws IOException {
        String[] dirs = {
                "consistent-magus - Main File",
                "consistent-magus - Variant - Alt",
        };
        for (String name : dirs) {
            File dir = new File(modsRoot, name);
            assertTrue(dir.mkdirs());
            try (FileOutputStream out = new FileOutputStream(new File(dir, "asset.dat"))) {
                out.write(new byte[]{1});
            }
            writeFile(new File(dir, ".group"), "consistent-magus");
        }

        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);
        List<String> dirNames = new ArrayList<>();
        for (ModManager.ModInfo m : scan.mods) dirNames.add(m.name);

        ModCatalog.Entry entry = new ModCatalog.Entry("consistent-magus", "Consistent Magus Sprite", null,
                "summary", "https://example.invalid/page", null, null, null);
        assertEquals("consistent-magus - Main File", ModCatalog.findInstalledDirName(entry, dirNames, gr.groups));
    }

    /**
     * The group's {@code mainDir} itself starting with the entry id also
     * matches, independent of the {@code .group} marker's exact text or any
     * fileHint -- covers a main dir named e.g. "pixel-demaster - Main File"
     * even if its {@code .group} marker somehow diverged.
     */
    @Test
    public void findInstalledDirNameMatchesMainDirStartingWithEntryId() throws IOException {
        String[] dirs = {
                "pixel-demaster - Main File",
                "pixel-demaster - Font - SNES Font",
        };
        for (String name : dirs) {
            File dir = new File(modsRoot, name);
            assertTrue(dir.mkdirs());
            try (FileOutputStream out = new FileOutputStream(new File(dir, "asset.dat"))) {
                out.write(new byte[]{1});
            }
            writeFile(new File(dir, ".group"), "some other download name");
        }

        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);
        List<String> dirNames = new ArrayList<>();
        for (ModManager.ModInfo m : scan.mods) dirNames.add(m.name);

        ModCatalog.Entry entry = new ModCatalog.Entry("pixel-demaster", "Chrono Trigger Pixel Demaster", null,
                "summary", "https://example.invalid/page", null, null, null);
        assertEquals("pixel-demaster - Main File", ModCatalog.findInstalledDirName(entry, dirNames, gr.groups));
    }

    // --- selectOption radio semantics ---------------------------------------

    @Test
    public void applySelectOptionEnablesChosenAndDisablesSiblings() throws IOException {
        createFixture();
        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);

        String chosen = "PixelDemaster - Font - SNES Font (Playstation Buttons)";
        ModManager.applySelectOption(modsRoot, gr, DOWNLOAD_NAME, "Font", chosen);

        ModManager.ScanResult after = ModManager.collect(modsRoot);
        ModManager.GroupResult grAfter = ModManager.groups(modsRoot, after);
        ModManager.OptionGroup font = findOption(grAfter.groups.get(0), "Font");
        assertEquals(chosen, font.selected());
        for (ModManager.Choice c : font.choices) {
            assertEquals(c.dir.equals(chosen), c.enabled);
        }
        // Untouched: a different option group's selection state is
        // unaffected by selecting within "Font".
        ModManager.OptionGroup buttonPrompts = findOption(grAfter.groups.get(0), "Interface - Button Prompts Textures");
        assertNull(buttonPrompts.selected());
    }

    @Test
    public void applySelectOptionWithNullSelectsNone() throws IOException {
        createFixture();
        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);

        // Font starts with "SNES Font" enabled (see createFixture) --
        // selecting null must disable every choice.
        ModManager.applySelectOption(modsRoot, gr, DOWNLOAD_NAME, "Font", null);

        ModManager.ScanResult after = ModManager.collect(modsRoot);
        ModManager.GroupResult grAfter = ModManager.groups(modsRoot, after);
        ModManager.OptionGroup font = findOption(grAfter.groups.get(0), "Font");
        assertNull(font.selected());
        for (ModManager.Choice c : font.choices) assertFalse(c.enabled);
    }

    @Test
    public void applySelectOptionIsANoOpForAnUnknownGroupOrTitle() throws IOException {
        createFixture();
        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);

        ModManager.applySelectOption(modsRoot, gr, "Some Other Download", "Font", "whatever");
        ModManager.applySelectOption(modsRoot, gr, DOWNLOAD_NAME, "Not A Real Option", "whatever");

        // Nothing changed: the originally-enabled SNES Font choice is still
        // the only enabled one.
        ModManager.ScanResult after = ModManager.collect(modsRoot);
        ModManager.GroupResult grAfter = ModManager.groups(modsRoot, after);
        ModManager.OptionGroup font = findOption(grAfter.groups.get(0), "Font");
        assertEquals(ENABLED_FONT_CHOICE, font.selected());
    }

    // --- main-mod toggle cascades onto option choices -----------------------

    @Test
    public void applyMainToggleOffCascadesToOptionChoices() throws IOException {
        createFixture();
        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);

        // Sanity: the fixture starts with Main File on and one Font choice on.
        assertTrue(gr.groups.get(0).mainEnabled);
        assertEquals(ENABLED_FONT_CHOICE, findOption(gr.groups.get(0), "Font").selected());

        ModManager.applyMainToggle(modsRoot, gr, "PixelDemaster - Main File", false);

        ModManager.ScanResult after = ModManager.collect(modsRoot);
        ModManager.GroupResult grAfter = ModManager.groups(modsRoot, after);
        ModManager.ModGroup g = grAfter.groups.get(0);
        assertFalse(g.mainEnabled);
        for (ModManager.OptionGroup og : g.options) {
            for (ModManager.Choice c : og.choices) {
                assertFalse("expected " + c.dir + " disabled after main toggled off", c.enabled);
            }
        }
    }

    @Test
    public void applyMainToggleOnDoesNotReEnableOptionChoices() throws IOException {
        createFixture();
        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);

        ModManager.applyMainToggle(modsRoot, gr, "PixelDemaster - Main File", false);
        ModManager.ScanResult mid = ModManager.collect(modsRoot);
        ModManager.GroupResult grMid = ModManager.groups(modsRoot, mid);

        ModManager.applyMainToggle(modsRoot, grMid, "PixelDemaster - Main File", true);
        ModManager.ScanResult after = ModManager.collect(modsRoot);
        ModManager.GroupResult grAfter = ModManager.groups(modsRoot, after);
        ModManager.ModGroup g = grAfter.groups.get(0);
        assertTrue(g.mainEnabled);
        // Turning the main mod back on does NOT resurrect the previously
        // chosen font -- every option stays disabled until the user picks
        // again (see applyMainToggle's doc).
        for (ModManager.OptionGroup og : g.options) {
            assertNull(og.selected());
        }
    }

    @Test
    public void applyMainToggleIsANoOpForAPlainModOrOptionSubDir() throws IOException {
        createFixture();
        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);

        // Toggling an OPTION sub-dir off must not cascade onto its siblings
        // (that's selectOption's job, not the main-toggle cascade).
        ModManager.applyMainToggle(modsRoot, gr, ENABLED_FONT_CHOICE, false);
        ModManager.ScanResult after = ModManager.collect(modsRoot);
        ModManager.GroupResult grAfter = ModManager.groups(modsRoot, after);
        assertTrue(grAfter.groups.get(0).mainEnabled); // untouched
        assertNull(findOption(grAfter.groups.get(0), "Font").selected()); // just this one, now off
    }

    // --- helpers -------------------------------------------------------------

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
