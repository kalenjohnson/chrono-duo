package com.kalenjohnson.chronoduo.origart;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.kalenjohnson.chronoduo.ChronoResources;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Batch-rebuilds the OVERWORLD's original-1x art into {@code outDir}, the same
 * directory {@link OrigArtRebuilder} and {@link MapchipRebuilder} write into
 * and {@code AppActivity#scanOrigArtReplacements} scans. Two families, both
 * picked up by {@code OrigArtCache}'s FILE-level substitution path (the
 * {@code ctr::ResourceManager::getData} hook in gamestate.c), because both are
 * consumed by the game's own CPU-side compositing rather than only by a
 * texture upload:
 *
 * <ol>
 *   <li><b>Chip sheets</b> -- the 14 shipped
 *       {@code Game/world/worldchip_<chip>_<palette>_<page>.png} (7 pairs,
 *       pages 0 and 1), rebuilt from {@code Game/world/map_bin/cg<n>.bin} +
 *       {@code Game/world/Chip/Chip_%04d.dat} +
 *       {@code Game/world/plt_bin/plt<n>.bin} via {@link MapchipCore}'s
 *       overworld path ({@code worldRenderSheet2x}).</li>
 *   <li><b>Overworld object/backdrop sheets</b> -- the 14 shipped
 *       {@code Game/world/gif/<name>.png} that have a 1x indexed
 *       {@code <name>.bmp} sibling ({@code <n>_wobj0}, {@code <n>_wobj1},
 *       {@code 0_wboa}, {@code 4_kodai_break}), rebuilt frame-by-frame with
 *       {@link SheetRebuilder} exactly like the chara sheets -- the 2x PNG is
 *       NOT a pixel-double of the BMP, the frames are re-packed.</li>
 * </ol>
 *
 * <h3>Which chip sheets exist</h3>
 * There is no {@code bgsettable} equivalent for the overworld: the cg bank ids
 * live in {@code WorldMapInfo::G_WORLDMAPINFO} @ 0xbe2508 inside
 * libchrono.so, so {@link MapchipCore#WORLDMAPINFO} is a hardcoded copy of
 * that table and {@link MapchipCore#worldSheetPairs()} folds its 8 worlds into
 * the 7 distinct {@code (chip, palette)} pairs -- exactly the 7 shipped sheet
 * pairs. Worlds 4 and 6 share (4, 9) <em>and</em> have identical cg slots, so
 * the sheet-to-bank mapping is unambiguous (unlike the field's (62,21) bgset
 * ambiguity); {@code worldSheetPairs()} throws if that ever stops holding.
 * The table is verified against the shipped ELF by
 * {@code tools/world_art/rebuild_worldchip.py --libchrono}, and this class's
 * output is verified pixel-identical to that script's by
 * {@code tools/world_art/JavaWorldchipCheck.java}.
 *
 * <p>Deliberately NOT rebuilt (no 1x source ships; see
 * tools/world_art/REPORT.md section 6):
 * {@code Game/world/worldchipScr3_<n>_{2,3}.png} (10 sheets -- authored
 * weather-layer textures that {@code WorldMap::initWeatherMap} @ 0x60802c
 * hands straight to {@code createTexture}, with no chip table or cg bank
 * anywhere in the path), {@code Game/common/worldChara.png} and
 * {@code Game/common/silbird.png} (no {@code .bmp} sibling; for
 * {@code worldChara} the "the overworld frames are just the field frames"
 * hypothesis is disproved by measurement too -- 0 of its 63 sprites match at
 * the 60.0 threshold against all 16194 components of every one of the 708
 * BMPs in the archive,
 * see REPORT.md section 6.1 and {@code tools/world_art/rebuild_worldchara.py}),
 * and the seven {@code <n>_wboa.bmp} that ship without a PNG.</p>
 *
 * <p>Threading contract is identical to {@link OrigArtRebuilder#rebuildAll}:
 * background thread only, callback invoked inline, cancellation polled at item
 * boundaries.</p>
 */
public final class WorldchipRebuilder {
    private static final String TAG = "ChronoDuo";

    private static final String CHIP_FMT = "Game/world/Chip/Chip_%04d.dat";
    private static final String CG_FMT = "Game/world/map_bin/cg%d.bin";
    private static final String PALETTE_FMT = "Game/world/plt_bin/plt%d.bin";
    /** The name the game asks {@code ResourceManager::createTexture} for, minus the directory. */
    private static final String SHEET_FMT = "worldchip_%d_%d_%d.png";

    private static final String GIF_PREFIX = "Game/world/gif/";
    private static final String PNG_SUFFIX = ".png";
    private static final String BMP_SUFFIX = ".bmp";

    private WorldchipRebuilder() {}

    public static final class Stats {
        public int pairs;             // distinct (chip, palette) sheet pairs found
        public int rebuilt;           // chip sheet pages written
        public int skippedExisting;   // outputs that already existed
        public int skippedNoSource;   // pairs skipped for a missing Chip/plt
        public int failed;            // items that threw
        public int gifPairs;          // gif png/bmp pairs found
        public int gifRebuilt;        // gif sheets written
        public int gifUnpaired;       // gif pngs or bmps with no sibling
        public long gifMatchedFrames;
        public long gifUnmatchedFrames;
        public int cancelledAt = -1;
        public final List<String> failedNames = new ArrayList<>();

        @Override
        public String toString() {
            return "WorldchipRebuilder.Stats{pairs=" + pairs + ", rebuilt=" + rebuilt
                    + ", skippedExisting=" + skippedExisting
                    + ", skippedNoSource=" + skippedNoSource + ", failed=" + failed
                    + ", gifPairs=" + gifPairs + ", gifRebuilt=" + gifRebuilt
                    + ", gifUnpaired=" + gifUnpaired
                    + ", gifMatchedFrames=" + gifMatchedFrames
                    + ", gifUnmatchedFrames=" + gifUnmatchedFrames
                    + ", cancelledAt=" + cancelledAt + "}";
        }
    }

    /**
     * Rebuilds every overworld chip sheet page and every paired gif sheet into
     * {@code outDir}. Best effort per item: a failure is logged and counted and
     * the batch continues.
     *
     * @param ctx        app context (ChronoResources' version-keyed extraction cache)
     * @param gameAssets the <em>game's</em> AssetManager, holding resources.bin
     * @param outDir     created if missing
     * @param progress   optional, called once per item (a chip pair, or a gif sheet)
     * @param cancelled  optional, polled at item boundaries
     */
    public static Stats rebuildAll(Context ctx, AssetManager gameAssets, File outDir,
                                    OrigArtRebuilder.ProgressCallback progress,
                                    BooleanSupplier cancelled) {
        Stats stats = new Stats();
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            Log.w(TAG, "worldchip rebuild: cannot create output dir " + outDir);
            return stats;
        }

        List<String> entries;
        try {
            entries = ArcTable.listEntries(gameAssets);
        } catch (IOException e) {
            Log.w(TAG, "worldchip rebuild: failed to read resources.bin table", e);
            return stats;
        }

        int[][] pairs = MapchipCore.worldSheetPairs();
        stats.pairs = pairs.length;

        List<String> gifNames = pairedGifNames(entries, stats);
        stats.gifPairs = gifNames.size();

        int total = pairs.length + gifNames.size();
        int done = 0;

        // ---- chip sheets -------------------------------------------------
        // All the sources at once: 7 pairs reference at most ~24 distinct cg
        // banks (4 KB each), 6 Chip tables and 7 palettes -- well under 200 KB
        // held for the whole batch.
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (int[] row : pairs) {
            for (int s = 0; s < MapchipCore.WORLD_CG_SLOTS; s++) {
                if (row[s] != MapchipCore.WORLD_CG_NONE) {
                    names.add(String.format(Locale.US, CG_FMT, row[s]));
                }
            }
            names.add(String.format(Locale.US, CHIP_FMT, row[MapchipCore.WMI_CHIP]));
            names.add(String.format(Locale.US, PALETTE_FMT, row[MapchipCore.WMI_PALETTE]));
        }
        Map<String, File> srcFiles = ChronoResources.extractAll(
                ctx, gameAssets, names.toArray(new String[0]));
        Map<String, byte[]> src = new HashMap<>();
        for (Map.Entry<String, File> e : srcFiles.entrySet()) {
            try {
                src.put(e.getKey(), readAllBytes(e.getValue()));
            } catch (Exception ex) {
                Log.w(TAG, "worldchip rebuild: cannot read " + e.getKey(), ex);
            }
        }

        for (int[] row : pairs) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                stats.cancelledAt = done;
                Log.i(TAG, "worldchip rebuild (cancelled): " + stats);
                return stats;
            }
            processPair(row, src, outDir, stats);
            done++;
            if (progress != null) {
                progress.onProgress(done, total, "worldchip_" + row[MapchipCore.WMI_CHIP]
                        + "_" + row[MapchipCore.WMI_PALETTE]);
            }
        }

        // ---- gif object / backdrop sheets --------------------------------
        for (String name : gifNames) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                stats.cancelledAt = done;
                Log.i(TAG, "worldchip rebuild (cancelled): " + stats);
                return stats;
            }
            processGif(ctx, gameAssets, outDir, name, stats);
            done++;
            if (progress != null) progress.onProgress(done, total, name);
        }

        Log.i(TAG, "worldchip rebuild: " + stats);
        return stats;
    }

    /** The {@code Game/world/gif/} basenames that ship both a {@code .png} and a {@code .bmp}. */
    private static List<String> pairedGifNames(List<String> entries, Stats stats) {
        LinkedHashSet<String> pngs = new LinkedHashSet<>();
        LinkedHashSet<String> bmps = new LinkedHashSet<>();
        for (String entry : entries) {
            if (!entry.startsWith(GIF_PREFIX)) continue;
            String base = entry.substring(GIF_PREFIX.length());
            if (base.indexOf('/') >= 0) continue;
            if (base.endsWith(PNG_SUFFIX)) {
                pngs.add(base.substring(0, base.length() - PNG_SUFFIX.length()));
            } else if (base.endsWith(BMP_SUFFIX)) {
                bmps.add(base.substring(0, base.length() - BMP_SUFFIX.length()));
            }
        }
        List<String> paired = new ArrayList<>();
        for (String n : pngs) {
            if (bmps.contains(n)) paired.add(n); else stats.gifUnpaired++;
        }
        for (String n : bmps) {
            if (!pngs.contains(n)) stats.gifUnpaired++;
        }
        Collections.sort(paired);
        return paired;
    }

    private static void processPair(int[] row, Map<String, byte[]> src, File outDir, Stats stats) {
        int chip = row[MapchipCore.WMI_CHIP];
        int plt = row[MapchipCore.WMI_PALETTE];
        byte[] chipTable = src.get(String.format(Locale.US, CHIP_FMT, chip));
        byte[] palette = src.get(String.format(Locale.US, PALETTE_FMT, plt));
        if (chipTable == null || palette == null) {
            Log.w(TAG, "worldchip rebuild: missing Chip_" + chip + " or plt" + plt + " -- skipped");
            stats.skippedNoSource++;
            return;
        }

        byte[][] banks = new byte[MapchipCore.WORLD_CG_SLOTS][];
        for (int s = 0; s < MapchipCore.WORLD_CG_SLOTS; s++) {
            if (row[s] == MapchipCore.WORLD_CG_NONE) continue;   // 128 = "no bank", and only 128
            banks[s] = src.get(String.format(Locale.US, CG_FMT, row[s]));
        }

        byte[] chipData = MapchipCore.worldBuildChipData(banks);
        int[] pal = MapchipCore.loadPalette(palette);
        for (int page = 0; page < MapchipCore.WORLD_CHIPTABLE_PAGES; page++) {
            File outFile = new File(outDir, String.format(Locale.US, SHEET_FMT, chip, plt, page));
            if (outFile.isFile()) {
                stats.skippedExisting++;
                continue;
            }
            try {
                byte[] idx = MapchipCore.worldExpandPage(
                        chipData, MapchipCore.worldLoadChipTablePage(chipTable, page));
                writePng(MapchipCore.colorize2x(idx, pal),
                        MapchipCore.SHEET_PX, MapchipCore.SHEET_PX, outFile);
                stats.rebuilt++;
            } catch (Exception e) {
                Log.w(TAG, "worldchip rebuild: failed for " + outFile.getName(), e);
                stats.failed++;
                stats.failedNames.add(outFile.getName());
            }
        }
    }

    /**
     * Rebuilds one {@code Game/world/gif/<name>.png} from its 1x
     * {@code <name>.bmp} sibling. Byte-for-byte the same work
     * {@link OrigArtRebuilder} does for a chara sheet -- see that class for why
     * the frames have to be segmented and matched rather than pixel-doubled --
     * with the world's own entry paths and the shipped basename as the output
     * name (which is what the {@code getData} substitution matches on).
     */
    private static void processGif(Context ctx, AssetManager gameAssets, File outDir,
                                    String name, Stats stats) {
        File outFile = new File(outDir, name + PNG_SUFFIX);
        if (outFile.isFile()) {
            stats.skippedExisting++;
            return;
        }
        Bitmap pngBitmap = null;
        try {
            File pngFile = ChronoResources.extract(ctx, gameAssets, GIF_PREFIX + name + PNG_SUFFIX);
            File bmpFile = ChronoResources.extract(ctx, gameAssets, GIF_PREFIX + name + BMP_SUFFIX);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inPremultiplied = false;
            opts.inScaled = false;
            pngBitmap = BitmapFactory.decodeFile(pngFile.getAbsolutePath(), opts);
            if (pngBitmap == null) {
                throw new IOException("BitmapFactory.decodeFile returned null for " + pngFile);
            }
            int w = pngBitmap.getWidth(), h = pngBitmap.getHeight();
            int[] pngArgb = new int[w * h];
            pngBitmap.getPixels(pngArgb, 0, w, 0, 0, w, h);
            pngBitmap.recycle();
            pngBitmap = null;

            BmpIndexed bmp = BmpIndexed.parse(readAllBytes(bmpFile));
            int[] outArgb = new int[w * h];
            SheetRebuilder.Result r = SheetRebuilder.rebuild(
                    pngArgb, w, h, bmp.indices, bmp.width, bmp.height, bmp.paletteArgb, outArgb);

            writePng(outArgb, w, h, outFile);
            stats.gifRebuilt++;
            stats.gifMatchedFrames += r.matched;
            stats.gifUnmatchedFrames += r.unmatched;
        } catch (Exception e) {
            Log.w(TAG, "worldchip rebuild: gif failed for " + name, e);
            stats.failed++;
            stats.failedNames.add(name + PNG_SUFFIX);
        } finally {
            if (pngBitmap != null) pngBitmap.recycle();
        }
    }

    /**
     * Writes non-premultiplied ARGB as a PNG, atomically (temp file + rename),
     * matching {@link MapchipRebuilder}'s output shape so OrigArtCache picks it
     * up identically. File substitution is UPSTREAM of cocos2d-x's premultiply,
     * so these bytes must stay exactly as rendered.
     */
    private static void writePng(int[] argb, int w, int h, File outFile) throws IOException {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPremultiplied(false);
        bmp.setPixels(argb, 0, w, 0, 0, w, h);
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
