package com.kalenjohnson.chronoduo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders all 8 Chrono Trigger overworld maps on device, from the game's own
 * asset files, replacing the old PixelCopy screenshot-of-the-map-screen
 * capture. Pixel math lives in {@link WorldMapCompositor} (no android.*
 * imports, shared with the desktop check harness
 * {@code tools/world_map/JavaRenderCheck.java}); this class is just the
 * Android-side file/Bitmap plumbing -- see {@code tools/world_map/REPORT.md}
 * for the disassembly this is ported from.
 */
public final class WorldMapRenderer {
    private static final String TAG = "ChronoDuo";

    /** Per-world/overall progress sink; called from whatever thread {@link #renderAll} runs on. */
    public interface Logger {
        void log(String message);
    }

    private WorldMapRenderer() {}

    // world id -> (chipTableId, paletteId, mapFileId). Read out of
    // WorldMapInfo::G_WORLDMAPINFO in libchrono.so -- see REPORT.md section 5.
    // world id here is exactly what GameState.nativeGetWorldEra() (via
    // PartySnapshot#worldEra) reports, so the output filenames
    // (worldmap_era<N>.png) line up with the existing ChronoAssets lookup.
    private static final int[][] WORLD_INFO = {
            {0, 4, 0},   // world 0: 1000 AD (Present)
            {0, 5, 1},   // world 1: 600 AD (Middle Ages)
            {2, 7, 3},   // world 2: 2300 AD (Future)
            {3, 8, 4},   // world 3: 65,000,000 BC (Prehistory)
            {4, 9, 5},   // world 4: 12,000 BC (Dark Ages, ground)
            {5, 10, 7},  // world 5: 12,000 BC (Zeal, sky)
            {4, 9, 6},   // world 6: 12,000 BC (Dark Ages, post-Zeal)
            {1, 6, 2},   // world 7: 1000 AD variant (Lavos-fallen / ending), see note below
    };

    /** World 5 (Zeal, the sky): layers are swapped and the layer-0 overlay is not baked on top -- see REPORT.md section 4. */
    private static final int NO_OVERLAY_WORLD = 5;

    /** Output filenames this class writes, {@code worldmap_era<N>.png} for N in {@link #WORLD_INFO}'s index range -- matches {@link ChronoAssets#resolveWorldMapFile}. */
    public static int worldCount() { return WORLD_INFO.length; }

    /** The flat (no subdirectory) resources.bin entry basenames {@link #renderAll} needs present in {@code extractedDir}: 8 {@code Map_%04d.dat} + 14 {@code worldchip_<chip>_<plt>_{0,1}.png}. */
    public static List<String> requiredAssetNames() {
        List<String> names = new ArrayList<>();
        boolean[][] chipPltSeen = new boolean[16][16];
        for (int[] info : WORLD_INFO) {
            names.add(String.format(Locale.US, "Map_%04d.dat", info[2]));
        }
        for (int[] info : WORLD_INFO) {
            int chip = info[0], plt = info[1];
            if (chipPltSeen[chip][plt]) continue;
            chipPltSeen[chip][plt] = true;
            names.add(String.format(Locale.US, "worldchip_%d_%d_0.png", chip, plt));
            names.add(String.format(Locale.US, "worldchip_%d_%d_1.png", chip, plt));
        }
        return names;
    }

    /**
     * Renders {@code worldmap_era0.png} .. {@code worldmap_era7.png} into
     * {@code outDir}, reading {@code Map_%04d.dat} / {@code
     * worldchip_<chip>_<plt>_{0,1}.png} (flat filenames, no subdirectory)
     * out of {@code extractedDir}. A world whose output file already exists
     * is skipped unless {@code force} is true. Best-effort across worlds: a
     * single world's failure (missing/short source file, bad PNG) is logged
     * and does not stop the rest. Off the UI thread is the caller's
     * responsibility -- this does synchronous file IO and PNG decode/encode.
     *
     * @return true iff every world rendered successfully (or was skipped
     *         because it already had output)
     */
    public static boolean renderAll(File extractedDir, File outDir, boolean force, Logger log) {
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            logBoth(log, "WorldMapRenderer: cannot create output dir " + outDir);
            return false;
        }
        boolean allOk = true;
        long batchStart = System.nanoTime();
        for (int world = 0; world < WORLD_INFO.length; world++) {
            File out = new File(outDir, "worldmap_era" + world + ".png");
            if (!force && out.isFile() && out.length() > 0) {
                continue;
            }
            long t0 = System.nanoTime();
            try {
                renderOne(extractedDir, out, world);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                logBoth(log, "WorldMapRenderer: world " + world + " rendered in " + ms + "ms -> " + out.getName());
            } catch (Exception e) {
                allOk = false;
                Log.w(TAG, "WorldMapRenderer: world " + world + " failed", e);
                logBoth(log, "WorldMapRenderer: world " + world + " FAILED: " + e);
            }
        }
        long totalMs = (System.nanoTime() - batchStart) / 1_000_000L;
        logBoth(log, "WorldMapRenderer: batch done in " + totalMs + "ms, ok=" + allOk);
        return allOk;
    }

    private static void renderOne(File dir, File out, int world) throws IOException {
        int[] info = WORLD_INFO[world];
        int chip = info[0], plt = info[1], mapId = info[2];

        byte[] map = readAll(new File(dir, String.format(Locale.US, "Map_%04d.dat", mapId)));
        if (map.length != WorldMapCompositor.MAP_FILE_BYTES) {
            throw new IOException(String.format(Locale.US, "Map_%04d.dat: expected %d bytes, got %d",
                    mapId, WorldMapCompositor.MAP_FILE_BYTES, map.length));
        }

        Bitmap page0Bmp = decodePage(new File(dir, "worldchip_" + chip + "_" + plt + "_0.png"));
        Bitmap page1Bmp = decodePage(new File(dir, "worldchip_" + chip + "_" + plt + "_1.png"));
        Bitmap result = null;
        try {
            int dim = WorldMapCompositor.PAGE_DIM;
            int[] page0 = new int[dim * dim];
            page0Bmp.getPixels(page0, 0, dim, 0, 0, dim, dim);
            page0Bmp.recycle();
            page0Bmp = null;

            int[] page1 = new int[dim * dim];
            page1Bmp.getPixels(page1, 0, dim, 0, 0, dim, dim);
            page1Bmp.recycle();
            page1Bmp = null;

            int[] canvas = WorldMapCompositor.composite(map, page0, page1, world != NO_OVERLAY_WORLD);

            result = Bitmap.createBitmap(WorldMapCompositor.OUT_W, WorldMapCompositor.OUT_H, Bitmap.Config.ARGB_8888);
            result.setPixels(canvas, 0, WorldMapCompositor.OUT_W, 0, 0, WorldMapCompositor.OUT_W, WorldMapCompositor.OUT_H);
            writePng(result, out);
        } finally {
            if (page0Bmp != null) page0Bmp.recycle();
            if (page1Bmp != null) page1Bmp.recycle();
            if (result != null) result.recycle();
        }
    }

    private static Bitmap decodePage(File f) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inPremultiplied = false; // "nonzero alpha wins" is a pixel replace, not a blend -- keep raw components
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        if (b == null) throw new IOException("failed to decode " + f);
        int dim = WorldMapCompositor.PAGE_DIM;
        if (b.getWidth() != dim || b.getHeight() != dim) {
            b.recycle();
            throw new IOException(f + ": expected " + dim + "x" + dim + ", got " + b.getWidth() + "x" + b.getHeight());
        }
        return b;
    }

    private static void writePng(Bitmap bmp, File out) throws IOException {
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        boolean ok = false;
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        if (!ok || !tmp.renameTo(out)) {
            throw new IOException("write/rename failed for " + out);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        if (!f.isFile()) throw new IOException("missing: " + f);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) >= 0) off += n;
            if (off != buf.length) throw new IOException("short read: " + f + " (" + off + "/" + buf.length + ")");
            return buf;
        }
    }

    private static void logBoth(Logger log, String message) {
        Log.i(TAG, message);
        if (log != null) log.log(message);
    }
}
