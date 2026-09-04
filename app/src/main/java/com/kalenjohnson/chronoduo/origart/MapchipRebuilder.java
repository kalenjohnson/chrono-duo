package com.kalenjohnson.chronoduo.origart;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import com.kalenjohnson.chronoduo.ChronoResources;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * Batch-rebuilds every shipped FIELD chip sheet
 * {@code Game/field/mapchip/mapchip_<a>_<b>_<page>.png} from the game's own
 * original 1x tile data, using {@link MapchipCore}, and writes one
 * {@code <outDir>/mapchip_<a>_<b>_<page>.png} per page.
 *
 * <p>The output directory is the same one {@link OrigArtRebuilder} writes into
 * and AppActivity#scanOrigArtReplacements scans, so a rebuilt sheet is picked
 * up as a texture replacement by exactly the same machinery as the character
 * sheets -- matched by asset basename against {@code g_pending_tex_path},
 * which {@code hooked_createTexture} parks for the duration of
 * {@code MapTable::LoadTexture}'s
 * {@code ctr::ResourceManager::createTexture("Game/field/mapchip/...")} calls.
 * See tools/field_art/REPORT.md section 8b.</p>
 *
 * <h3>Which sheets exist</h3>
 * The shipped sheets are keyed {@code (a, b) = (mapinfo[2], mapinfo[4])} --
 * the ChipTable id and the palette id -- so the set of sheets is enumerated
 * from the 669 {@code Game/field/Mapinfo/mapinfo_<n>.dat} entries exactly as
 * {@code tools/field_art/rebuild_mapchip.py} does, never by globbing the
 * shipped PNG names (which include leftover authoring assets like
 * {@code mapchip_23_33_ev.png} that {@code LoadTexture}'s {@code %d_%d_%d}
 * format string can never name).
 *
 * <p>Only <b>pages 0 and 1</b> are rebuilt: a {@code ChipTable_%04d.dat} is
 * exactly 6144 bytes = two pages, and there is no source for the page 2/3
 * sheets a handful of chip sets ship. Those 14 sheet-slots keep the shipped
 * smoothed art.</p>
 *
 * <p>A pair whose {@code bgsettable}/{@code ChipTable}/{@code plt} source is
 * missing from resources.bin is skipped (the three known dead entries --
 * (255,75), (588,21), (590,21) -- have no ChipTable or bgsettable at all).</p>
 *
 * <p>Memory: one 512x512 page at a time (1 MB of ARGB ints plus the Bitmap),
 * with the cg banks cached across pairs -- all 244 shipped banks together are
 * about 1 MB, so caching them costs less than re-reading them ~250 times.</p>
 *
 * <p>Entirely off-main-thread work; same threading contract as
 * {@link OrigArtRebuilder#rebuildAll} (callback invoked synchronously, inline,
 * from the calling thread; cancellation polled at pair boundaries).</p>
 */
public final class MapchipRebuilder {
    private static final String TAG = "ChronoDuo";

    private static final String MAPINFO_PREFIX = "Game/field/Mapinfo/mapinfo_";
    private static final String MAPINFO_SUFFIX = ".dat";
    private static final String BGSET_FMT = "Game/field/BGSetTable/bgsettable_%d.dat";
    private static final String CHIPTABLE_FMT = "Game/field/ChipTable/ChipTable_%04d.dat";
    private static final String PALETTE_FMT = "Game/field/palette_bin/plt%d.bin";
    private static final String CG_FMT = "Game/field/map_bin/cg%d.bin";
    /** The name the game asks {@code ResourceManager::createTexture} for, minus the directory. */
    private static final String SHEET_FMT = "mapchip_%d_%d_%d.png";

    private MapchipRebuilder() {}

    public static final class Stats {
        public int mapinfos;        // mapinfo entries found in resources.bin
        public int pairs;           // distinct (chipTable, palette) pairs
        public int rebuilt;         // pages written
        public int skippedExisting; // pages whose output file already existed
        public int skippedNoSource; // pairs skipped for a missing bgsettable/ChipTable/plt
        public int failed;          // pages that threw
        public int cancelledAt = -1;
        public final List<String> failedNames = new ArrayList<>();

        @Override
        public String toString() {
            return "MapchipRebuilder.Stats{mapinfos=" + mapinfos + ", pairs=" + pairs
                    + ", rebuilt=" + rebuilt + ", skippedExisting=" + skippedExisting
                    + ", skippedNoSource=" + skippedNoSource + ", failed=" + failed
                    + ", cancelledAt=" + cancelledAt + "}";
        }
    }

    /** One (chipTable, palette) sheet group and the bgset id its cg banks come from. */
    private static final class Pair {
        final int a, b, bgset;
        Pair(int a, int b, int bgset) { this.a = a; this.b = b; this.bgset = bgset; }
    }

    /**
     * Rebuilds pages 0 and 1 of every shipped chip sheet into {@code outDir}.
     * Best effort per page: a failure is logged and counted, and the batch
     * continues.
     *
     * @param ctx        app context (ChronoResources' version-keyed extraction cache)
     * @param gameAssets the <em>game's</em> AssetManager, holding resources.bin
     * @param outDir     created if missing
     * @param progress   optional, called once per pair (both its pages done)
     * @param cancelled  optional, polled at pair boundaries
     */
    public static Stats rebuildAll(Context ctx, AssetManager gameAssets, File outDir,
                                    OrigArtRebuilder.ProgressCallback progress,
                                    BooleanSupplier cancelled) {
        Stats stats = new Stats();
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            Log.w(TAG, "mapchip rebuild: cannot create output dir " + outDir);
            return stats;
        }

        List<String> entries;
        try {
            entries = ArcTable.listEntries(gameAssets);
        } catch (IOException e) {
            Log.w(TAG, "mapchip rebuild: failed to read resources.bin table", e);
            return stats;
        }

        List<String> mapinfoNames = new ArrayList<>();
        for (String entry : entries) {
            if (entry.startsWith(MAPINFO_PREFIX) && entry.endsWith(MAPINFO_SUFFIX)) {
                mapinfoNames.add(entry);
            }
        }
        stats.mapinfos = mapinfoNames.size();
        if (mapinfoNames.isEmpty()) {
            Log.w(TAG, "mapchip rebuild: no " + MAPINFO_PREFIX + "* entries in resources.bin");
            return stats;
        }

        // Extract every mapinfo in one batch (24 bytes each, 669 of them) and
        // fold them into the distinct (chipTable, palette) pairs the shipped
        // sheets are keyed by. Where a pair's maps disagree on the bgset id --
        // (62,21) is the only such pair in the shipped data, with bgset 14 and
        // 70 differing in cg slot 5 -- the LOWEST id wins, deterministically,
        // so a device rebuild and a desktop rebuild agree.
        Map<String, File> mapinfoFiles = ChronoResources.extractAll(
                ctx, gameAssets, mapinfoNames.toArray(new String[0]));
        Map<Long, Pair> pairs = new TreeMap<>();
        for (Map.Entry<String, File> e : mapinfoFiles.entrySet()) {
            try {
                int[] mi = MapchipCore.readMapinfo(readAllBytes(e.getValue()));
                int a = mi[MapchipCore.MI_CHIPTABLE];
                int b = mi[MapchipCore.MI_PALETTE];
                int bgset = mi[MapchipCore.MI_BGSET];
                long key = ((long) a << 32) | (b & 0xFFFFFFFFL);
                Pair prev = pairs.get(key);
                if (prev == null || bgset < prev.bgset) pairs.put(key, new Pair(a, b, bgset));
            } catch (Exception ex) {
                Log.w(TAG, "mapchip rebuild: bad mapinfo " + e.getKey(), ex);
            }
        }
        stats.pairs = pairs.size();

        // Pre-extract every source the pairs reference, in three batches: the
        // bgsettables first (they name the cg banks), then the banks, then the
        // ChipTables and palettes.
        LinkedHashSet<String> bgsetNames = new LinkedHashSet<>();
        for (Pair p : pairs.values()) bgsetNames.add(String.format(Locale.US, BGSET_FMT, p.bgset));
        Map<String, File> bgsetFiles = ChronoResources.extractAll(
                ctx, gameAssets, bgsetNames.toArray(new String[0]));

        Map<Integer, int[]> slotsByBgset = new HashMap<>();
        LinkedHashSet<String> cgNames = new LinkedHashSet<>();
        for (Pair p : pairs.values()) {
            if (slotsByBgset.containsKey(p.bgset)) continue;
            File f = bgsetFiles.get(String.format(Locale.US, BGSET_FMT, p.bgset));
            if (f == null) continue;
            try {
                int[] slots = MapchipCore.bgsetSlots(readAllBytes(f));
                slotsByBgset.put(p.bgset, slots);
                for (int cg : slots) {
                    if (cg >= 0) cgNames.add(String.format(Locale.US, CG_FMT, cg));
                }
            } catch (Exception ex) {
                Log.w(TAG, "mapchip rebuild: bad bgsettable_" + p.bgset, ex);
            }
        }

        // ~244 banks x 4 KB = about 1 MB held for the whole batch -- cheap next
        // to re-reading them once per pair.
        Map<String, File> cgFiles = ChronoResources.extractAll(
                ctx, gameAssets, cgNames.toArray(new String[0]));
        Map<Integer, byte[]> cgBanks = new HashMap<>();
        for (Map.Entry<String, File> e : cgFiles.entrySet()) {
            try {
                int cg = Integer.parseInt(e.getKey().substring(
                        e.getKey().lastIndexOf("/cg") + 3, e.getKey().length() - 4));
                cgBanks.put(cg, readAllBytes(e.getValue()));
            } catch (Exception ex) {
                Log.w(TAG, "mapchip rebuild: cannot read " + e.getKey(), ex);
            }
        }

        LinkedHashSet<String> tableNames = new LinkedHashSet<>();
        for (Pair p : pairs.values()) {
            tableNames.add(String.format(Locale.US, CHIPTABLE_FMT, p.a));
            tableNames.add(String.format(Locale.US, PALETTE_FMT, p.b));
        }
        Map<String, File> tableFiles = ChronoResources.extractAll(
                ctx, gameAssets, tableNames.toArray(new String[0]));

        List<Pair> ordered = new ArrayList<>(pairs.values());
        int total = ordered.size();
        for (int i = 0; i < total; i++) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                stats.cancelledAt = i;
                break;
            }
            Pair p = ordered.get(i);
            processPair(p, slotsByBgset.get(p.bgset), cgBanks, tableFiles, outDir, stats);
            if (progress != null) {
                progress.onProgress(i + 1, total,
                        "mapchip_" + p.a + "_" + p.b);
            }
        }

        Log.i(TAG, "mapchip rebuild: " + stats);
        return stats;
    }

    private static void processPair(Pair p, int[] slots, Map<Integer, byte[]> cgBanks,
                                     Map<String, File> tableFiles, File outDir, Stats stats) {
        File chipTableFile = tableFiles.get(String.format(Locale.US, CHIPTABLE_FMT, p.a));
        File pltFile = tableFiles.get(String.format(Locale.US, PALETTE_FMT, p.b));
        if (slots == null || chipTableFile == null || pltFile == null) {
            stats.skippedNoSource++;
            return;
        }

        // Skip the whole pair only if BOTH pages are already there -- a partial
        // previous run (cancelled mid-pair) must still finish page 1.
        File[] outFiles = new File[MapchipCore.CHIPTABLE_PAGES];
        int existing = 0;
        for (int page = 0; page < MapchipCore.CHIPTABLE_PAGES; page++) {
            outFiles[page] = new File(outDir, String.format(Locale.US, SHEET_FMT, p.a, p.b, page));
            if (outFiles[page].isFile()) existing++;
        }
        if (existing == MapchipCore.CHIPTABLE_PAGES) {
            stats.skippedExisting += existing;
            return;
        }

        byte[] chipTable, plt;
        byte[][] banks = new byte[MapchipCore.CG_SLOTS][];
        try {
            chipTable = readAllBytes(chipTableFile);
            plt = readAllBytes(pltFile);
            for (int s = 0; s < MapchipCore.CG_SLOTS; s++) {
                if (slots[s] >= 0) banks[s] = cgBanks.get(slots[s]);
            }
        } catch (Exception e) {
            Log.w(TAG, "mapchip rebuild: cannot read sources for " + p.a + "_" + p.b, e);
            stats.failed += MapchipCore.CHIPTABLE_PAGES - existing;
            stats.failedNames.add("mapchip_" + p.a + "_" + p.b);
            return;
        }

        byte[] chipData = MapchipCore.buildChipData(banks);
        int[] pal = MapchipCore.loadPalette(plt);
        for (int page = 0; page < MapchipCore.CHIPTABLE_PAGES; page++) {
            if (outFiles[page].isFile()) {
                stats.skippedExisting++;
                continue;
            }
            try {
                byte[] idx = MapchipCore.expandPage(chipData, MapchipCore.loadChipTablePage(chipTable, page));
                int[] argb = MapchipCore.colorize2x(idx, pal);
                writePng(argb, outFiles[page]);
                stats.rebuilt++;
            } catch (Exception e) {
                Log.w(TAG, "mapchip rebuild: failed for " + outFiles[page].getName(), e);
                stats.failed++;
                stats.failedNames.add(outFiles[page].getName());
            }
        }
    }

    /**
     * Writes one 512x512 non-premultiplied ARGB page as a PNG, atomically
     * (temp file + rename), matching {@link OrigArtRebuilder}'s output shape so
     * OrigArtCache picks it up identically.
     */
    private static void writePng(int[] argb, File outFile) throws IOException {
        int dim = MapchipCore.SHEET_PX;
        Bitmap bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
        bmp.setPremultiplied(false);
        bmp.setPixels(argb, 0, dim, 0, 0, dim, dim);
        File tmp = new File(outFile.getParentFile(), outFile.getName() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        } finally {
            bmp.recycle();
        }
        if (!tmp.renameTo(outFile)) {
            tmp.delete();
            throw new IOException("rename failed for " + outFile);
        }
    }

    private static byte[] readAllBytes(File f) throws IOException {
        long lenL = f.length();
        if (lenL <= 0 || lenL > Integer.MAX_VALUE) throw new IOException("bad file length for " + f);
        int len = (int) lenL;
        byte[] buf = new byte[len];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) throw new IOException("unexpected EOF reading " + f);
                off += n;
            }
        }
        return buf;
    }
}
