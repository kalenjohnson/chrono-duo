package com.kalenjohnson.chronoduo;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * Disk-backed cache of user-supplied "original 1x pixel art" texture
 * replacements (see AppActivity#scanOrigArtReplacements). Rather than
 * holding every replacement decoded in native RAM (512x512x4 bytes each,
 * >600MB across hundreds of sheets), each sheet's decoded pixels live on
 * disk as a zlib-compressed ".rgbz" file, and the native side (gamestate.c)
 * freads and inflates one lazily on a texture-upload match instead of
 * keeping any of them resident.
 *
 * On-disk format, under {@code <filesDir>/orig_art_cache/}:
 *   - "&lt;name&gt;.rgbz": zlib-compressed premultiplied RGBA8888 bytes for
 *     that sheet -- a 16-byte header {@code "RGBZ"} (4 bytes) + width (u32
 *     LE) + height (u32 LE) + rawLen (u32 LE, the uncompressed size, always
 *     {@code w*h*4}), followed by a standard zlib stream ({@link
 *     java.util.zip.Deflater}'s default RFC-1950 wrapper) of the row-major,
 *     tightly packed (R,G,B,A per texel) premultiplied RGBA8888 bytes -- the
 *     exact bytes {@link com.kalenjohnson.chronoduo.GameState
 *     #nativeLoadTextureReplacementIndex}'s registry inflates and uploads
 *     via glTexImage2D on a match. Sprite sheets are mostly transparent and
 *     deflate well, so this is substantially smaller than the raw RGBA on
 *     disk at the cost of a native zlib inflate (a few ms/sheet) on each
 *     match; any leftover "&lt;name&gt;.rgba" from a pre-compression build
 *     of this cache is stale and deleted by {@link #refresh}.
 *   - "index.txt": one line per cached sheet, "&lt;name&gt; &lt;w&gt;
 *     &lt;h&gt; &lt;alphaFp hex16&gt; &lt;redFp hex16&gt; &lt;pngMtime&gt;"
 *     -- name is the replacement's basename (e.g. "c000_0.png", including
 *     the ".png"), w/h its pixel size, alphaFp/redFp the FNV-1a content
 *     fingerprints of the ORIGINAL (unmodified) game asset at that name (see
 *     {@link #fingerprint}) -- or BOTH ZERO for a path-keyed entry, the
 *     sentinel that tells gamestate.c to match this name only against
 *     the asset path the game asked for and never by content (see
 *     {@link #isPathKeyed}) -- formatted lowercase, zero-padded to 16 hex
 *     digits ("%016x") so the native side's fixed-width sscanf can parse
 *     them without a delimiter, and pngMtime the source PNG's
 *     lastModified() at build time -- this trailing field is read only by
 *     {@link #refresh}'s own reuse check; gamestate.c's index parser reads
 *     exactly the 5 fields before it and ignores the rest of the line.
 *
 * {@link #refresh} is the only entry point: it rebuilds only what changed
 * since the last run (a missing/stale ".rgbz", or a source PNG with no
 * cache entry at all -- everything else is reused, so a 629-sheet boot scan
 * with nothing changed costs one directory listing and one index parse
 * instead of 629 PNG decodes plus 629 resources.bin extractions), writes
 * the merged index atomically, and hands the result to
 * {@code nativeLoadTextureReplacementIndex}.
 */
public final class OrigArtCache {
    private static final String TAG = "ChronoDuo";
    private static final String CACHE_DIR_NAME = "orig_art_cache";
    private static final String INDEX_NAME = "index.txt";

    private OrigArtCache() {}

    /**
     * One parsed/built index line, kept together with its already-formatted
     * text. {@code pngMtime} is the source PNG's {@code lastModified()} at
     * the time this entry was built -- carried as a 6th, space-separated
     * field on the line purely for Java's own reuse decision in {@link
     * #refresh}; the native index parser (gamestate.c) reads exactly 5
     * fields via sscanf and ignores anything after, so this field never
     * needs to round-trip through the native side.
     */
    private static final class Entry {
        final String name;
        final long pngMtime;
        final String line;
        Entry(String name, long pngMtime, String line) {
            this.name = name;
            this.pngMtime = pngMtime;
            this.line = line;
        }
    }

    /**
     * Rebuilds (incrementally) the disk cache from every {@code *.png} found
     * in {@code sourceDirs} (scanned in order; a name found in a later
     * directory overrides one found earlier -- callers pass
     * {externalFilesDir/orig_art, filesDir/orig_art} so filesDir wins,
     * matching ChronoAssets's own convention), then loads the result into
     * the native registry. Synchronized (like {@link ChronoResources#extract})
     * since this can run from both the boot thread and the settings-toggle
     * thread, and both write index.txt/*.rgbz. Safe to call on a background
     * thread only (file IO, PNG decode, resources.bin extraction). Returns
     * the number of entries loaded natively (0 on a hard failure, e.g. the
     * cache directory can't be created).
     */
    public static synchronized int refresh(Context ctx, AssetManager gameAssets, File[] sourceDirs) {
        long t0 = System.currentTimeMillis();
        File cacheDir = new File(ctx.getFilesDir(), CACHE_DIR_NAME);
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            Log.w(TAG, "orig_art_cache: failed to create " + cacheDir);
            return 0;
        }
        File indexFile = new File(cacheDir, INDEX_NAME);

        Map<String, Entry> oldIndex = parseIndex(indexFile);

        // name -> source PNG file; later dirs in sourceDirs win.
        Map<String, File> sources = new LinkedHashMap<>();
        for (File dir : sourceDirs) {
            File[] files = (dir != null && dir.isDirectory()) ? dir.listFiles() : null;
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName();
                if (n.toLowerCase(Locale.ROOT).endsWith(".png")) sources.put(n, f);
            }
        }

        Map<String, Entry> newIndex = new LinkedHashMap<>();
        int processed = 0, rebuilt = 0, total = sources.size();
        for (Map.Entry<String, File> se : sources.entrySet()) {
            String name = se.getKey();
            File pngFile = se.getValue();
            File rgbzFile = new File(cacheDir, name + ".rgbz");
            Entry old = oldIndex.get(name);
            // Reuse only when the cached entry was built from this exact
            // PNG content-timestamp: comparing rgbzFile's own write time
            // against the PNG's current mtime (the naive approach) breaks
            // whenever a PNG's mtime moves backward or is preserved by the
            // tool that wrote it (cp -p, unzip, rsync) -- restoring an
            // older PNG over a newer cached one would then silently keep
            // serving the stale cache forever, since the .rgbz write time
            // stays newer than the restored (older) PNG's mtime.
            boolean reuse = old != null && rgbzFile.isFile()
                    && old.pngMtime == pngFile.lastModified();
            if (reuse) {
                newIndex.put(name, old);
            } else {
                Entry built = buildEntry(ctx, gameAssets, name, pngFile, rgbzFile);
                if (built != null) {
                    newIndex.put(name, built);
                    rebuilt++;
                }
            }
            processed++;
            if (processed % 50 == 0) {
                Log.i(TAG, "orig_art_cache: processed " + processed + "/" + total
                        + " (" + rebuilt + " rebuilt so far)");
            }
        }

        // Stale-cache deletion: skipped entirely when this scan found no
        // sources at all but a previous run did -- a transient unlistable/
        // empty source dir must never be read as "everything was removed",
        // which would delete a good 629-sheet cache and force a full
        // rebuild next boot.
        if (sources.isEmpty() && !oldIndex.isEmpty()) {
            Log.w(TAG, "orig_art_cache: no source PNGs found this scan -- keeping existing cache as-is");
            newIndex.putAll(oldIndex);
        } else {
            for (String staleName : oldIndex.keySet()) {
                if (!newIndex.containsKey(staleName)) {
                    File stale = new File(cacheDir, staleName + ".rgbz");
                    if (stale.delete()) {
                        Log.i(TAG, "orig_art_cache: removed stale " + stale.getName());
                    }
                }
            }
        }

        // Migration: delete any leftover ".rgba" files from a pre-
        // compression build of this cache -- every entry now lives in the
        // matching ".rgbz" (built above, either freshly or reused), so a
        // ".rgba" with no live purpose left on disk is always safe to
        // remove. Cheap (one directory listing) and a no-op once migrated.
        File[] leftovers = cacheDir.listFiles();
        if (leftovers != null) {
            int removedRgba = 0;
            for (File f : leftovers) {
                if (f.getName().toLowerCase(Locale.ROOT).endsWith(".rgba") && f.delete()) {
                    removedRgba++;
                }
            }
            if (removedRgba > 0) {
                Log.i(TAG, "orig_art_cache: migration -- removed " + removedRgba
                        + " leftover .rgba file(s)");
            }
        }

        writeIndexAtomic(indexFile, newIndex);

        int count = GameState.nativeLoadTextureReplacementIndex(
                indexFile.getAbsolutePath(), cacheDir.getAbsolutePath());
        long ms = System.currentTimeMillis() - t0;
        Log.i(TAG, "orig_art_cache: refresh done, " + newIndex.size() + " entries (" + rebuilt
                + " rebuilt, " + (newIndex.size() - rebuilt) + " reused), " + count
                + " loaded natively, " + ms + " ms");
        return count;
    }

    /**
     * Maps an orig_art replacement filename to the resources.bin entry it
     * should be fingerprinted against. Only handles character sheet names
     * ("c000_0.png", "c123_1.png", ...) under Game/chara/png/; returns null
     * for anything else -- including the PATH-KEYED names {@link
     * #isPathKeyed} covers, which are matched by asset basename and need no
     * fingerprint at all. A small standalone function so other directories
     * (items, monsters, ...) can be added later without touching {@link
     * #refresh}.
     */
    private static String origArtResourceEntry(String name) {
        if (name.matches("c\\d\\d\\d_\\d\\.png")) {
            return "Game/chara/png/" + name;
        }
        return null;
    }

    /**
     * True for replacement names that are matched purely by the asset path the
     * game asks for, never by content fingerprint -- currently the field chip
     * sheets, "mapchip_&lt;chipTable&gt;_&lt;palette&gt;_&lt;page&gt;.png"
     * (see {@link com.kalenjohnson.chronoduo.origart.MapchipRebuilder}).
     *
     * <p>Those load through {@code MapTable::LoadTexture} ->
     * {@code ctr::ResourceManager::createTexture("Game/field/mapchip/...")},
     * which gamestate.c's mechanism-5 hook already parks in
     * {@code g_pending_tex_path}, so the path is available, unique and free.
     * The alpha fingerprint would be a <em>bad</em> key here: a chip sheet's
     * alpha channel is just its index-0 mask, several sheets are entirely
     * transparent or entirely opaque, and collisions across 500-odd
     * same-sized sheets are near certain. Entries built for these names
     * therefore carry {@code alphaFp == redFp == 0}, the sentinel gamestate.c
     * reads as "path-only -- never consider this entry for a fingerprint
     * match"; building them also skips extracting the original asset
     * entirely, which is what makes a ~500-sheet field pass cheap.</p>
     */
    private static boolean isPathKeyed(String name) {
        return name.matches("mapchip_\\d+_\\d+_\\d+\\.png");
    }

    // ---- FNV-1a content fingerprint (must stay bit-identical to
    // gamestate.c's fnv1a_byte/fnv1a_u32/tex_fingerprint) ----
    private static final long FNV64_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV64_PRIME = 0x100000001b3L;

    private static long fnv1aByte(long h, int b) {
        h ^= (b & 0xffL);
        h *= FNV64_PRIME;
        return h;
    }

    private static long fnv1aU32(long h, int v) {
        h = fnv1aByte(h, (v >>> 24) & 0xff);
        h = fnv1aByte(h, (v >>> 16) & 0xff);
        h = fnv1aByte(h, (v >>> 8) & 0xff);
        h = fnv1aByte(h, v & 0xff);
        return h;
    }

    /**
     * Copies a Bitmap's raw pixel bytes out via {@link Bitmap
     * #copyPixelsToBuffer}. For {@code Config.ARGB_8888}, despite the name,
     * Android stores each texel as four bytes in memory in R,G,B,A order
     * (confirmed: android.graphics.Bitmap's native pixel format for
     * ARGB_8888 is kRGBA_8888_SkColorType) -- i.e. this returns exactly the
     * R,G,B,A byte layout the game uploads via glTexImage2D(..., GL_RGBA,
     * GL_UNSIGNED_BYTE, ...) and exactly what the ".rgbz" cache file stores
     * (compressed), so no channel reordering is needed here or in
     * {@link #buildEntry}.
     * Deliberately NOT {@code getPixels}/{@code getPixel}: those
     * un-premultiply on read, which would change what the red-channel
     * fingerprint (and the cached bytes themselves) mean.
     */
    private static byte[] bitmapRgbaBytes(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        byte[] bytes = new byte[w * h * 4];
        bmp.copyPixelsToBuffer(ByteBuffer.wrap(bytes));
        return bytes;
    }

    private static final byte[] RGBZ_MAGIC = {'R', 'G', 'B', 'Z'};

    /**
     * Builds the on-disk ".rgbz" payload for one sheet: a 16-byte header
     * ({@code "RGBZ"} + width/height/rawLen, each u32 little-endian) followed
     * by a zlib (RFC 1950, default-wrapper) deflate of {@code rawRgba} at
     * level 6 -- must stay byte-compatible with what gamestate.c's
     * {@code uncompress()} expects (see nativeLoadTextureReplacementIndex /
     * hooked_glTexImage2D). {@code rawRgba.length} must equal {@code w*h*4}.
     */
    private static byte[] compressRgbz(int w, int h, byte[] rawRgba) {
        Deflater deflater = new Deflater(6); // level 6, default zlib (RFC 1950) wrapper
        deflater.setInput(rawRgba);
        deflater.finish();
        // Deflate output is never larger than input + a small fixed overhead
        // in the worst (incompressible) case; size the buffer generously and
        // rely on Deflater to report the actual bytes produced.
        byte[] tmp = new byte[rawRgba.length + 64];
        int total = 0;
        while (!deflater.finished()) {
            if (total == tmp.length) {
                byte[] grown = new byte[tmp.length * 2];
                System.arraycopy(tmp, 0, grown, 0, total);
                tmp = grown;
            }
            int n = deflater.deflate(tmp, total, tmp.length - total);
            total += n;
        }
        deflater.end();

        byte[] out = new byte[16 + total];
        System.arraycopy(RGBZ_MAGIC, 0, out, 0, 4);
        writeU32LE(out, 4, w);
        writeU32LE(out, 8, h);
        writeU32LE(out, 12, rawRgba.length);
        System.arraycopy(tmp, 0, out, 16, total);
        return out;
    }

    private static void writeU32LE(byte[] buf, int off, int v) {
        buf[off] = (byte) (v & 0xff);
        buf[off + 1] = (byte) ((v >>> 8) & 0xff);
        buf[off + 2] = (byte) ((v >>> 16) & 0xff);
        buf[off + 3] = (byte) ((v >>> 24) & 0xff);
    }

    /**
     * Computes {alphaFp, redFp} over a 64x64 grid of samples (4096 total),
     * identically to gamestate.c's tex_fingerprint: for i,j in 0..63,
     * x = (i*w)/64, y = (j*h)/64 (integer division), sampling pixel (x,y).
     * {@code alphaBytes}/{@code redBytes} are tightly packed R,G,B,A buffers
     * (see {@link #bitmapRgbaBytes}) of size w*h*4 each, both w x h; they may
     * be the same buffer or two different decodes of the same image (this
     * class passes two different decodes -- see {@link #buildEntry} -- since
     * alpha is unaffected by premultiplication but red is not, and the
     * fingerprint must reflect what the game's premultiplied upload looks
     * like).
     */
    private static long[] fingerprint(byte[] alphaBytes, byte[] redBytes, int w, int h) {
        long ah = FNV64_OFFSET, rh = FNV64_OFFSET;
        ah = fnv1aU32(ah, w);
        ah = fnv1aU32(ah, h);
        rh = fnv1aU32(rh, w);
        rh = fnv1aU32(rh, h);
        for (int i = 0; i < 64; i++) {
            int x = (i * w) / 64;
            for (int j = 0; j < 64; j++) {
                int y = (j * h) / 64;
                int idx = (y * w + x) * 4;
                ah = fnv1aByte(ah, alphaBytes[idx + 3] & 0xff);
                rh = fnv1aByte(rh, redBytes[idx] & 0xff);
            }
        }
        return new long[]{ah, rh};
    }

    /**
     * Builds (or rebuilds) one cache entry: resolves the matching
     * resources.bin entry via {@link #origArtResourceEntry}, extracts the
     * ORIGINAL asset (via {@link ChronoResources#extract}, itself cached
     * under filesDir) and decodes it twice -- once non-premultiplied (for
     * the alpha fingerprint, unaffected by premultiplication) and once
     * premultiplied (for the red fingerprint, matching what the game's own
     * premultiply step produces at upload) -- to compute alphaFp/redFp;
     * then decodes the replacement itself PREMULTIPLIED (matching the
     * game's upload order), zlib-deflates its raw R,G,B,A bytes and writes
     * the RGBZ header + compressed stream to {@code rgbzFile} (atomically:
     * temp file + rename), and returns the index line. w/h come from the
     * ORIGINAL; a replacement whose decoded size doesn't match is skipped
     * (returns null) with a log rather than cached mismatched.
     */
    private static Entry buildEntry(Context ctx, AssetManager gameAssets, String name,
                                     File pngFile, File rgbzFile) {
        // Captured before any decode work so the stored mtime reflects the
        // exact PNG content this entry was built from, for #refresh's reuse
        // check.
        long pngMtime = pngFile.lastModified();

        if (isPathKeyed(name)) return buildPathKeyedEntry(name, pngFile, rgbzFile, pngMtime);

        String resEntry = origArtResourceEntry(name);
        if (resEntry == null) {
            Log.w(TAG, "orig_art_cache: no resources.bin mapping for " + name + " -- skipped");
            return null;
        }

        File origFile;
        try {
            origFile = ChronoResources.extract(ctx, gameAssets, resEntry);
        } catch (Exception e) {
            Log.w(TAG, "orig_art_cache: failed to extract original " + resEntry + " for " + name, e);
            return null;
        }

        Bitmap origNonPremul = null, origPremul = null, replacement = null;
        try {
            BitmapFactory.Options optsNP = new BitmapFactory.Options();
            optsNP.inPreferredConfig = Bitmap.Config.ARGB_8888;
            optsNP.inPremultiplied = false;
            optsNP.inScaled = false;
            origNonPremul = BitmapFactory.decodeFile(origFile.getAbsolutePath(), optsNP);
            if (origNonPremul == null) {
                Log.w(TAG, "orig_art_cache: decode returned null for original " + origFile);
                return null;
            }
            int w = origNonPremul.getWidth(), h = origNonPremul.getHeight();

            // Second decode of the ORIGINAL, premultiplied this time, purely
            // to get the red channel as the game's own premultiply step
            // would produce it (alpha itself is identical either way, so the
            // non-premultiplied decode above is used for that channel).
            BitmapFactory.Options optsP = new BitmapFactory.Options();
            optsP.inPreferredConfig = Bitmap.Config.ARGB_8888;
            optsP.inPremultiplied = true;
            optsP.inScaled = false;
            origPremul = BitmapFactory.decodeFile(origFile.getAbsolutePath(), optsP);
            if (origPremul == null) {
                Log.w(TAG, "orig_art_cache: premultiplied decode returned null for original " + origFile);
                return null;
            }

            long[] fp = fingerprint(bitmapRgbaBytes(origNonPremul), bitmapRgbaBytes(origPremul), w, h);
            long alphaFp = fp[0], redFp = fp[1];

            BitmapFactory.Options replOpts = new BitmapFactory.Options();
            replOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            replOpts.inPremultiplied = true; // matches the game's own upload order (premultiplied RGBA)
            replOpts.inScaled = false;
            replacement = BitmapFactory.decodeFile(pngFile.getAbsolutePath(), replOpts);
            if (replacement == null) {
                Log.w(TAG, "orig_art_cache: decode returned null for " + pngFile);
                return null;
            }
            if (replacement.getWidth() != w || replacement.getHeight() != h) {
                Log.w(TAG, "orig_art_cache: " + name + " is " + replacement.getWidth() + "x"
                        + replacement.getHeight() + ", original " + resEntry + " is " + w + "x" + h
                        + " -- size mismatch, skipped");
                return null;
            }

            byte[] rgba = bitmapRgbaBytes(replacement);
            byte[] rgbz = compressRgbz(w, h, rgba);
            if (!writeAtomic(rgbzFile, rgbz)) {
                Log.w(TAG, "orig_art_cache: failed to write " + rgbzFile);
                return null;
            }

            // The trailing pngMtime field is Java-only (see Entry's
            // javadoc) -- gamestate.c's sscanf reads exactly 5 fields and
            // ignores the rest of the line.
            String line = name + " " + w + " " + h + " "
                    + String.format(Locale.ROOT, "%016x", alphaFp) + " "
                    + String.format(Locale.ROOT, "%016x", redFp) + " "
                    + pngMtime;
            return new Entry(name, pngMtime, line);
        } catch (Exception e) {
            Log.w(TAG, "orig_art_cache: failed to process " + pngFile, e);
            return null;
        } finally {
            if (origNonPremul != null) origNonPremul.recycle();
            if (origPremul != null) origPremul.recycle();
            if (replacement != null) replacement.recycle();
        }
    }

    /**
     * Builds one PATH-KEYED cache entry (see {@link #isPathKeyed}): decodes
     * the replacement premultiplied (matching the game's own upload order),
     * takes w/h from the replacement itself -- there is no original to
     * measure against, and none is extracted -- deflates its raw R,G,B,A
     * bytes into the same RGBZ container every other entry uses, and emits an
     * index line whose two fingerprint fields are both zero.
     *
     * <p>That all-zero pair is the sentinel gamestate.c reads as "path-only":
     * such an entry is only ever matched against {@code g_pending_tex_path}
     * and is skipped by the content-fingerprint fallback (and by its
     * size pre-scan, so a 512x512 upload with no registered fingerprint entry
     * of that size doesn't pay for a 4096-sample hash). The index line format
     * is otherwise unchanged, so the native parser needs no new field.</p>
     */
    private static Entry buildPathKeyedEntry(String name, File pngFile, File rgbzFile,
                                              long pngMtime) {
        Bitmap replacement = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inPremultiplied = true; // matches the game's own upload order
            opts.inScaled = false;
            replacement = BitmapFactory.decodeFile(pngFile.getAbsolutePath(), opts);
            if (replacement == null) {
                Log.w(TAG, "orig_art_cache: decode returned null for " + pngFile);
                return null;
            }
            int w = replacement.getWidth(), h = replacement.getHeight();

            byte[] rgbz = compressRgbz(w, h, bitmapRgbaBytes(replacement));
            if (!writeAtomic(rgbzFile, rgbz)) {
                Log.w(TAG, "orig_art_cache: failed to write " + rgbzFile);
                return null;
            }
            String line = name + " " + w + " " + h + " "
                    + String.format(Locale.ROOT, "%016x", 0L) + " "
                    + String.format(Locale.ROOT, "%016x", 0L) + " "
                    + pngMtime;
            return new Entry(name, pngMtime, line);
        } catch (Exception e) {
            Log.w(TAG, "orig_art_cache: failed to process " + pngFile, e);
            return null;
        } finally {
            if (replacement != null) replacement.recycle();
        }
    }

    /**
     * Parses an existing index.txt into name -> Entry; returns an empty map
     * if it doesn't exist or is unreadable. Requires the 6-field format
     * (5 native fields plus the trailing pngMtime -- see Entry's javadoc);
     * a 5-field line from a pre-mtime-tracking build of this cache has no
     * mtime to compare against and is dropped here, which simply forces a
     * one-time rebuild of that sheet in the caller's reuse check.
     */
    private static Map<String, Entry> parseIndex(File indexFile) {
        Map<String, Entry> map = new LinkedHashMap<>();
        if (!indexFile.isFile()) return map;
        try (BufferedReader r = new BufferedReader(new FileReader(indexFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String[] parts = trimmed.split(" ");
                if (parts.length != 6) {
                    Log.w(TAG, "orig_art_cache: bad/stale index line, skipping: " + trimmed);
                    continue;
                }
                try {
                    // Validate the numeric fields parse, but keep the
                    // original text verbatim as the carried-forward line.
                    Integer.parseInt(parts[1]);
                    Integer.parseInt(parts[2]);
                    Long.parseUnsignedLong(parts[3], 16);
                    Long.parseUnsignedLong(parts[4], 16);
                    long pngMtime = Long.parseLong(parts[5]);
                    map.put(parts[0], new Entry(parts[0], pngMtime, trimmed));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "orig_art_cache: bad index line, skipping: " + trimmed);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "orig_art_cache: failed to read " + indexFile, e);
        }
        return map;
    }

    /** Writes index.txt atomically (temp file + rename), one Entry line per row. */
    private static void writeIndexAtomic(File indexFile, Map<String, Entry> index) {
        File tmp = new File(indexFile.getParentFile(), indexFile.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (Entry e : index.values()) {
                w.write(e.line);
                w.write('\n');
            }
        } catch (IOException e) {
            Log.w(TAG, "orig_art_cache: failed to write index to " + tmp, e);
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(indexFile)) {
            Log.w(TAG, "orig_art_cache: failed to rename " + tmp + " -> " + indexFile);
            tmp.delete();
        }
    }

    /** Writes {@code data} to {@code dest} atomically (temp file + rename). */
    private static boolean writeAtomic(File dest, byte[] data) {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
            fos.getFD().sync();
        } catch (IOException e) {
            Log.w(TAG, "orig_art_cache: write failed for " + tmp, e);
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(dest)) {
            Log.w(TAG, "orig_art_cache: rename failed " + tmp + " -> " + dest);
            tmp.delete();
            return false;
        }
        return true;
    }
}
