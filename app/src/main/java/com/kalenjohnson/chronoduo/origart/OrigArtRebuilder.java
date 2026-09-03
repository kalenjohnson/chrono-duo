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
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Batch-rebuilds every "Game/chara/png/&lt;name&gt;.png" sheet in the game's
 * resources.bin using its paired "Game/chara/bmp/&lt;name&gt;.bmp" original
 * 1x indexed art (see {@link SheetRebuilder}), writing one
 * "&lt;outDir&gt;/&lt;name&gt;.png" per pair.
 *
 * The output directory is exactly the shape
 * AppActivity#registerOrigArtDir / #scanOrigArtReplacements already scans
 * (externalFilesDir/orig_art or filesDir/orig_art, filenames like
 * "c000_0.png"), so pointing outDir at one of those and re-running
 * scanOrigArtReplacements() picks the results up as texture replacements.
 * This class does not do that wiring itself -- see the class doc below for
 * the intended call shape.
 *
 * Entirely off-main-thread work: resources.bin table read + per-entry
 * extraction (gzip inflate) + PNG decode + BMP parse + rebuild + PNG
 * encode, potentially for ~300 sheet pairs. Callers should invoke
 * {@link #rebuildAll} from a background thread and marshal
 * {@link ProgressCallback} calls back to the UI thread themselves if
 * needed (this class makes no threading assumptions and calls the
 * callback synchronously, inline, from whatever thread calls rebuildAll).
 */
public final class OrigArtRebuilder {
    private static final String TAG = "ChronoDuo";
    private static final String PNG_PREFIX = "Game/chara/png/";
    private static final String PNG_SUFFIX = ".png";
    private static final String BMP_PREFIX = "Game/chara/bmp/";
    private static final String BMP_SUFFIX = ".bmp";

    private OrigArtRebuilder() {}

    public interface ProgressCallback {
        /** Called synchronously after each name is processed (or skipped). done is 1-based. */
        void onProgress(int done, int total, String name);
    }

    public static final class Stats {
        public int total;               // paired (png+bmp) names found
        public int rebuilt;              // wrote a new output file
        public int skippedExisting;      // output file already existed
        public int failed;               // extraction/decode/write error
        public int unpairedPng;          // png entries with no matching bmp entry
        public int unpairedBmp;          // bmp entries with no matching png entry
        public int cancelledAt = -1;     // index (0-based) processing stopped at, or -1 if not cancelled
        public long totalMatchedFrames;  // sum of SheetRebuilder.Result.matched across all rebuilt sheets
        public long totalUnmatchedFrames;
        public final List<String> failedNames = new ArrayList<>();

        @Override
        public String toString() {
            return "OrigArtRebuilder.Stats{total=" + total + ", rebuilt=" + rebuilt
                    + ", skippedExisting=" + skippedExisting + ", failed=" + failed
                    + ", unpairedPng=" + unpairedPng + ", unpairedBmp=" + unpairedBmp
                    + ", cancelledAt=" + cancelledAt
                    + ", totalMatchedFrames=" + totalMatchedFrames
                    + ", totalUnmatchedFrames=" + totalUnmatchedFrames + "}";
        }
    }

    /**
     * Rebuilds every paired chara sheet into outDir. Best effort per pair:
     * a failure on one name is logged and counted in stats.failed, and
     * processing continues with the next name (mirroring
     * ChronoResources#extractAll's best-effort batch style).
     *
     * @param ctx         app context, used for ChronoResources' version-keyed
     *                    extraction cache.
     * @param gameAssets  the *game's* AssetManager (holding resources.bin),
     *                    not this app's -- same convention as
     *                    ChronoResources#extract's callers.
     * @param outDir      created if missing; one "&lt;name&gt;.png" written
     *                    per pair.
     * @param progress    optional, called after each name is processed.
     * @param cancelled   optional; polled between names, so cancellation is
     *                    only ever observed at a pair boundary (a rebuild
     *                    already in flight for one sheet always finishes).
     */
    public static Stats rebuildAll(Context ctx, AssetManager gameAssets, File outDir,
                                    ProgressCallback progress, BooleanSupplier cancelled) {
        Stats stats = new Stats();
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            Log.w(TAG, "orig_art rebuild: cannot create output dir " + outDir);
            return stats;
        }

        List<String> entries;
        try {
            entries = ArcTable.listEntries(gameAssets);
        } catch (IOException e) {
            Log.w(TAG, "orig_art rebuild: failed to read resources.bin table", e);
            return stats;
        }

        Map<String, String> pngEntryByName = new HashMap<>();
        Map<String, String> bmpEntryByName = new HashMap<>();
        for (String entry : entries) {
            if (entry.startsWith(PNG_PREFIX) && entry.endsWith(PNG_SUFFIX)) {
                String name = entry.substring(PNG_PREFIX.length(), entry.length() - PNG_SUFFIX.length());
                pngEntryByName.put(name, entry);
            } else if (entry.startsWith(BMP_PREFIX) && entry.endsWith(BMP_SUFFIX)) {
                String name = entry.substring(BMP_PREFIX.length(), entry.length() - BMP_SUFFIX.length());
                bmpEntryByName.put(name, entry);
            }
        }

        List<String> names = new ArrayList<>();
        for (String name : pngEntryByName.keySet()) {
            if (bmpEntryByName.containsKey(name)) {
                names.add(name);
            } else {
                stats.unpairedPng++;
            }
        }
        for (String name : bmpEntryByName.keySet()) {
            if (!pngEntryByName.containsKey(name)) {
                stats.unpairedBmp++;
            }
        }
        Collections.sort(names);
        stats.total = names.size();

        for (int i = 0; i < names.size(); i++) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                stats.cancelledAt = i;
                break;
            }
            String name = names.get(i);
            processOne(ctx, gameAssets, outDir, name, pngEntryByName.get(name), bmpEntryByName.get(name), stats);
            if (progress != null) progress.onProgress(i + 1, stats.total, name);
        }

        Log.i(TAG, "orig_art rebuild: " + stats);
        return stats;
    }

    private static void processOne(Context ctx, AssetManager gameAssets, File outDir, String name,
                                    String pngEntry, String bmpEntry, Stats stats) {
        File outFile = new File(outDir, name + PNG_SUFFIX);
        if (outFile.isFile()) {
            stats.skippedExisting++;
            return;
        }

        try {
            File pngFile = ChronoResources.extract(ctx, gameAssets, pngEntry);
            File bmpFile = ChronoResources.extract(ctx, gameAssets, bmpEntry);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inPremultiplied = false;
            opts.inScaled = false;
            Bitmap pngBitmap = BitmapFactory.decodeFile(pngFile.getAbsolutePath(), opts);
            if (pngBitmap == null) {
                throw new IOException("BitmapFactory.decodeFile returned null for " + pngFile);
            }
            int pngW = pngBitmap.getWidth(), pngH = pngBitmap.getHeight();
            int[] pngArgb = new int[pngW * pngH];
            pngBitmap.getPixels(pngArgb, 0, pngW, 0, 0, pngW, pngH);
            pngBitmap.recycle();

            byte[] bmpBytes = readAllBytes(bmpFile);
            BmpIndexed bmp = BmpIndexed.parse(bmpBytes);

            int[] outArgb = new int[pngW * pngH];
            SheetRebuilder.Result result = SheetRebuilder.rebuild(
                    pngArgb, pngW, pngH,
                    bmp.indices, bmp.width, bmp.height,
                    bmp.paletteArgb, outArgb);

            Bitmap outBitmap = Bitmap.createBitmap(pngW, pngH, Bitmap.Config.ARGB_8888);
            outBitmap.setPremultiplied(false);
            outBitmap.setPixels(outArgb, 0, pngW, 0, 0, pngW, pngH);

            File tmp = new File(outDir, name + PNG_SUFFIX + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                outBitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            } finally {
                outBitmap.recycle();
            }
            if (!tmp.renameTo(outFile)) {
                throw new IOException("rename failed for " + outFile);
            }

            stats.rebuilt++;
            stats.totalMatchedFrames += result.matched;
            stats.totalUnmatchedFrames += result.unmatched;
        } catch (Exception e) {
            Log.w(TAG, "orig_art rebuild: failed for " + name, e);
            stats.failed++;
            stats.failedNames.add(name);
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
