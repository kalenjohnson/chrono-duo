package com.kalenjohnson.chronoduo.mods;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the user's mod priority order for the Mods settings page: a
 * plain-text file at {@code <modsRoot>/.order}, one mod directory name per
 * line, top line = highest priority (first to claim a conflicting archive
 * path -- see {@link ModManager#collect}/{@link ModManager#findFont}, both
 * of which call {@link #orderedModDirs} instead of each keeping their own
 * sort). A directory not listed in the file (a fresh install, or a stale/
 * renamed entry left behind in it) sorts AFTER every listed directory,
 * case-insensitive alphabetically among the unlisted ones -- so a mods
 * folder with no {@code .order} file yet behaves exactly like the old
 * fixed alphabetical rule, and the file only needs to grow to cover
 * whatever the user has actually reordered.
 *
 * <p>Pure {@code java.io} -- no Android dependency -- so this is
 * JVM-testable like the rest of the mods package.
 */
public final class ModOrder {
    private static final String ORDER_FILE_NAME = ".order";

    private ModOrder() {
    }

    /**
     * The immediate subdirectories of {@code modsRoot} in effective
     * priority order -- see the class doc. {@code modsRoot} not existing
     * or not a directory yields an empty list.
     */
    public static List<File> orderedModDirs(File modsRoot) {
        List<File> dirs = new ArrayList<>();
        File[] children = modsRoot.isDirectory() ? modsRoot.listFiles() : null;
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) dirs.add(f);
            }
        }
        List<String> order = readOrder(modsRoot);
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rank.put(order.get(i), i);
        dirs.sort((a, b) -> {
            Integer ra = rank.get(a.getName());
            Integer rb = rank.get(b.getName());
            if (ra != null || rb != null) {
                if (ra == null) return 1;
                if (rb == null) return -1;
                return ra - rb;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return dirs;
    }

    /**
     * Moves the unit at {@code unitIndex} within {@code unitsInOrder}
     * (each inner list is one "move as one" group of mod directory names
     * -- see {@link ModManager#deriveUnits}) one slot up or down, swapping
     * it with its neighbour, then rewrites {@code .order} as the FULL
     * flattened list of every unit's members in the new order -- so after
     * the first move the file becomes a complete, authoritative ordering
     * rather than a partial diff against the old alphabetical default.
     * Group members stay contiguous and in their existing relative order,
     * since they move as a single list element here.
     *
     * @return {@code false} (no write, {@code .order} left untouched) if
     * {@code unitIndex} is already at the end in the requested direction,
     * or out of range.
     */
    public static boolean move(File modsRoot, List<List<String>> unitsInOrder, int unitIndex, boolean up) {
        int target = up ? unitIndex - 1 : unitIndex + 1;
        if (unitIndex < 0 || unitIndex >= unitsInOrder.size() || target < 0 || target >= unitsInOrder.size()) {
            return false;
        }
        List<List<String>> reordered = new ArrayList<>(unitsInOrder);
        List<String> a = reordered.get(unitIndex);
        List<String> b = reordered.get(target);
        reordered.set(unitIndex, b);
        reordered.set(target, a);

        List<String> flat = new ArrayList<>();
        for (List<String> unit : reordered) flat.addAll(unit);
        try {
            writeOrder(modsRoot, flat);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /** Reads {@code <modsRoot>/.order}, one trimmed non-empty name per line, in file order; empty (not an error) if the file doesn't exist or can't be read. */
    private static List<String> readOrder(File modsRoot) {
        File f = new File(modsRoot, ORDER_FILE_NAME);
        List<String> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String line : content.split("\n", -1)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) out.add(trimmed);
            }
        } catch (IOException ignored) {
            // Treat an unreadable .order the same as a missing one -- the
            // alphabetical-among-unlisted fallback in orderedModDirs still
            // applies.
        }
        return out;
    }

    /** Overwrites {@code <modsRoot>/.order} with {@code names}, one per line, top = highest priority. */
    private static void writeOrder(File modsRoot, List<String> names) throws IOException {
        File f = new File(modsRoot, ORDER_FILE_NAME);
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(n).append('\n');
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
