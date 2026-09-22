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
 * JVM coverage for {@link ModOrder} (the persisted {@code .order} file
 * behind the Mods page's ▲/▼ reorder buttons) and {@link
 * ModManager#deriveUnits}/{@link ModManager#moveMod}, which build on it.
 * Fixture style matches {@link ModManagerGroupingTest}: real temp
 * directories, no Android/native calls -- {@link ModManager#collect} and
 * {@link ModManager#groups} are exercised directly rather than through
 * {@link ModManager#scan}.
 */
public class ModOrderTest {
    private File tmpRoot;
    private File modsRoot;

    @Before
    public void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("modorder-test").toFile();
        modsRoot = new File(tmpRoot, "mods");
        assertTrue(modsRoot.mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursive(tmpRoot);
    }

    private static File makeModDir(File modsRoot, String name) throws IOException {
        File dir = new File(modsRoot, name);
        assertTrue(dir.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(dir, "asset.dat"))) {
            out.write(new byte[]{1});
        }
        return dir;
    }

    private static void writeGroupMarker(File modDir, String downloadName) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(modDir, ".group"))) {
            out.write(downloadName.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<String> names(List<File> dirs) {
        List<String> out = new ArrayList<>();
        for (File f : dirs) out.add(f.getName());
        return out;
    }

    // --- orderedModDirs -------------------------------------------------

    @Test
    public void noOrderFileSortsAlphabetically() throws IOException {
        makeModDir(modsRoot, "Zebra");
        makeModDir(modsRoot, "apple");
        makeModDir(modsRoot, "Mango");

        List<File> ordered = ModOrder.orderedModDirs(modsRoot);
        assertEquals(List.of("apple", "Mango", "Zebra"), names(ordered));
    }

    @Test
    public void partialOrderFileListedFirstThenAlphabeticalRest() throws IOException {
        makeModDir(modsRoot, "Zebra");
        makeModDir(modsRoot, "apple");
        makeModDir(modsRoot, "Mango");
        makeModDir(modsRoot, "banana");

        // Only "Zebra" has an explicit priority -- everything else falls
        // back to alphabetical among themselves, appended after it.
        writeOrderFile("Zebra");

        List<File> ordered = ModOrder.orderedModDirs(modsRoot);
        assertEquals(List.of("Zebra", "apple", "banana", "Mango"), names(ordered));
    }

    @Test
    public void staleNamesInOrderFileAreIgnored() throws IOException {
        makeModDir(modsRoot, "apple");
        makeModDir(modsRoot, "Mango");
        // "Zebra" and "DeletedMod" are in .order but no longer exist on disk.
        writeOrderFile("Zebra", "Mango", "DeletedMod", "apple");

        List<File> ordered = ModOrder.orderedModDirs(modsRoot);
        // Mango (still listed, still exists) keeps its priority; apple
        // (also listed) follows in listed order; the stale names are
        // simply absent from the result -- no crash, nothing invented.
        assertEquals(List.of("Mango", "apple"), names(ordered));
    }

    // --- move -------------------------------------------------------------

    @Test
    public void moveUpAndDownSwapsAndRewritesFullList() throws IOException {
        List<List<String>> units = List.of(
                List.of("A"), List.of("B"), List.of("C"));

        // No dirs exist on disk in this test -- orderedModDirs would see
        // nothing; verify via the .order file's own written content
        // instead, which is what ModOrder.move actually contracts to write.
        assertTrue(ModOrder.move(modsRoot, units, 1, true)); // B up, swaps with A
        assertEquals(List.of("B", "A", "C"), readOrderFile());

        // Move C up (swaps with A, now at index 1).
        List<List<String>> units2 = List.of(
                List.of("B"), List.of("A"), List.of("C"));
        assertTrue(ModOrder.move(modsRoot, units2, 2, true));
        assertEquals(List.of("B", "C", "A"), readOrderFile());

        // Move B (index 0) down, swaps with C (index 1).
        List<List<String>> units3 = List.of(
                List.of("B"), List.of("C"), List.of("A"));
        assertTrue(ModOrder.move(modsRoot, units3, 0, false));
        assertEquals(List.of("C", "B", "A"), readOrderFile());
    }

    @Test
    public void moveAtBoundaryReturnsFalseAndWritesNothing() throws IOException {
        List<List<String>> units = List.of(List.of("A"), List.of("B"), List.of("C"));

        File orderFile = new File(modsRoot, ".order");
        assertFalse(orderFile.exists());

        assertFalse(ModOrder.move(modsRoot, units, 0, true));   // already first
        assertFalse(orderFile.exists());

        assertFalse(ModOrder.move(modsRoot, units, 2, false));  // already last
        assertFalse(orderFile.exists());
    }

    @Test
    public void groupedUnitMovesAsABlockAndStaysContiguous() throws IOException {
        // "Group" is two dirs sharing a .group marker; "Solo" is a plain mod.
        makeModDir(modsRoot, "Solo");
        File main = makeModDir(modsRoot, "Group - Main File");
        File option = makeModDir(modsRoot, "Group - Font - SNES Font");
        writeGroupMarker(main, "Group");
        writeGroupMarker(option, "Group");

        // Alphabetical default: "Group - Font..." < "Group - Main..." < "Solo".
        ModManager.ScanResult scan = ModManager.collect(modsRoot);
        ModManager.GroupResult gr = ModManager.groups(modsRoot, scan);
        List<File> ordered = ModOrder.orderedModDirs(modsRoot);
        List<List<String>> units = ModManager.deriveUnits(ordered, gr);

        // Exactly 2 units: the group (2 members, positioned at the first
        // member's index) and Solo -- never 3 flat entries.
        assertEquals(2, units.size());
        List<String> groupUnit = units.get(0);
        assertEquals(2, groupUnit.size());
        assertTrue(groupUnit.contains("Group - Main File"));
        assertTrue(groupUnit.contains("Group - Font - SNES Font"));
        assertEquals(List.of("Solo"), units.get(1));

        // Move the group unit down past Solo -- both members travel
        // together and stay adjacent in the rewritten .order file.
        assertTrue(ModOrder.move(modsRoot, units, 0, false));
        List<String> written = readOrderFile();
        assertEquals(3, written.size());
        assertEquals("Solo", written.get(0));
        // The two group members are adjacent (in their original relative
        // order) at indices 1-2, wherever that lands.
        int giMain = written.indexOf("Group - Main File");
        int giFont = written.indexOf("Group - Font - SNES Font");
        assertTrue(Math.abs(giMain - giFont) == 1);
    }

    // --- collect() conflict attribution follows the persisted order ------

    @Test
    public void collectConflictAttributionFollowsPersistedOrder() throws IOException {
        // Built by hand (not makeModDir, which also drops an "asset.dat" in
        // every dir -- that would collide too and inflate conflictCount
        // beyond the one path this test cares about).
        File modA = new File(modsRoot, "AAA");
        File modB = new File(modsRoot, "BBB");
        assertTrue(modA.mkdirs());
        assertTrue(modB.mkdirs());
        // Both mods claim the same archive path with different bytes.
        try (FileOutputStream out = new FileOutputStream(new File(modA, "shared.dat"))) {
            out.write(new byte[]{1});
        }
        try (FileOutputStream out = new FileOutputStream(new File(modB, "shared.dat"))) {
            out.write(new byte[]{2});
        }

        // Default (no .order): alphabetical -- AAA wins.
        ModManager.ScanResult defaultScan = ModManager.collect(modsRoot);
        assertEquals(new File(modA, "shared.dat").getAbsolutePath(),
                defaultScan.archiveToDiskPath.get("shared.dat"));
        assertEquals(1, findMod(defaultScan, "BBB").conflictCount);
        assertEquals(0, findMod(defaultScan, "AAA").conflictCount);

        // Reorder so BBB outranks AAA -- BBB must now win the conflict, and
        // the registered disk path must be BBB's copy of the file.
        writeOrderFile("BBB", "AAA");
        ModManager.ScanResult reordered = ModManager.collect(modsRoot);
        assertEquals(new File(modB, "shared.dat").getAbsolutePath(),
                reordered.archiveToDiskPath.get("shared.dat"));
        assertEquals(1, findMod(reordered, "AAA").conflictCount);
        assertEquals(0, findMod(reordered, "BBB").conflictCount);
    }

    private static ModManager.ModInfo findMod(ModManager.ScanResult scan, String name) {
        for (ModManager.ModInfo m : scan.mods) {
            if (m.name.equals(name)) return m;
        }
        throw new AssertionError("no mod named " + name);
    }

    // --- helpers -----------------------------------------------------------

    private void writeOrderFile(String... names) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(n).append('\n');
        try (FileOutputStream out = new FileOutputStream(new File(modsRoot, ".order"))) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private List<String> readOrderFile() throws IOException {
        File f = new File(modsRoot, ".order");
        List<String> out = new ArrayList<>();
        if (!f.isFile()) return out;
        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
