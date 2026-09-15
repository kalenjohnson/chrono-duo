package com.kalenjohnson.chronoduo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DS-style dungeon fog of war for the room minimap ({@link PartyPanelView}'s
 * area-map bitmap, 256x192 -- see {@code AREA_MAP_SRC_*}/{@code
 * drawAreaMapBitmap} there). Reveal state is a per-room bitset at DS tile
 * granularity: the 256x192 minimap is a 32x24 grid of 8x8px cells, stored as
 * a 96-byte bitset (one bit per cell, row-major). Rooms are keyed by
 * {@code roomId + "_" + suffix} (see {@link #keyFor}) so two floors of one
 * multi-floor room (see {@link AreaMapCalib#suffixFor}) get independent
 * masks, matching how the area-map bitmap itself is resolved per floor.
 *
 * <p>Persistence is lazy and best-effort: a mask is loaded from
 * {@code <filesDir>/fog/<key>.bin} the first time it's touched (via {@link
 * #reveal}/{@link #isRevealed}/{@link #applyMask}) and cached in memory for
 * the rest of the process; writes are debounced to at most once every
 * {@link #WRITE_DEBOUNCE_NANOS} per key and happen on a background single
 * thread so a fast-walking player never blocks the UI thread on disk I/O.
 * {@link #flush} forces any still-dirty masks to write immediately --
 * {@link PartyPanelView#onDetachedFromWindow()} calls it so progress isn't
 * lost between the debounce window and, say, the presentation being torn
 * down.
 *
 * <p>{@link #applyMask} hands back a masked ARGB_8888 copy of a source
 * bitmap with every unrevealed cell's pixels made fully transparent (via a
 * {@code PorterDuff.Mode.CLEAR} rect per unrevealed cell -- cheap and does
 * not touch already-transparent source pixels differently than opaque
 * ones). The masked copy is cached per key and only regenerated when the
 * mask has actually changed (tracked by a per-key version counter, bumped
 * only when {@link #reveal} flips a bit) or the caller passes a different
 * source {@link Bitmap} *instance* -- so a steady-state frame in an
 * unrevealed-but-already-seen room does zero allocation.
 */
public final class FogOfWar {
    private static final String TAG = "FogOfWar";

    private FogOfWar() {}

    public static final int GRID_W = 32;
    public static final int GRID_H = 24;
    public static final int CELL_PX = 8;
    public static final int IMAGE_W = GRID_W * CELL_PX; // 256
    public static final int IMAGE_H = GRID_H * CELL_PX; // 192
    private static final int MASK_BYTES = (GRID_W * GRID_H + 7) / 8; // 96

    /** Default reveal radius, in minimap-pixel space (the 256x192 image). */
    public static final float DEFAULT_REVEAL_RADIUS_PX = 12f;

    private static final long WRITE_DEBOUNCE_NANOS = 2_000_000_000L;
    private static final String FOG_SUBDIR = "fog";

    private static File fogDir;

    // Every field below is guarded by LOCK: reveal/applyMask/isRevealed run
    // on the UI thread, while the writer thread updates dirty/lastWriteAt
    // and reads masks for trailing writes. Holding LOCK never touches disk
    // (writeToDisk snapshots under the lock, then releases it before I/O).
    private static final Object LOCK = new Object();
    private static final Map<String, byte[]> masks = new HashMap<>();
    private static final Map<String, Integer> maskVersion = new HashMap<>();
    // Keys with changes not yet on disk.
    private static final java.util.Set<String> dirty = new java.util.HashSet<>();
    private static final Map<String, Long> lastWriteAt = new HashMap<>();
    // Keys that already have a trailing (post-debounce) write queued, so a
    // burst of reveals inside one window queues exactly one follow-up.
    private static final java.util.Set<String> trailingQueued = new java.util.HashSet<>();
    // Bumped by clearAll(); a queued write captured under an older
    // generation is dropped instead of recreating a file we just deleted.
    private static int generation = 0;

    // Masked-bitmap cache: at most MASKED_CACHE_MAX keys (the current room
    // and the fading-out previous one is all the panel ever draws), each
    // remembering the source Bitmap instance and mask version it was built
    // from -- see applyMask's class-doc paragraph.
    private static final int MASKED_CACHE_MAX = 2;
    private static final java.util.LinkedHashMap<String, Bitmap> maskedCache = new java.util.LinkedHashMap<>(4, 0.75f, true);
    private static final Map<String, Bitmap> maskedCacheSource = new HashMap<>();
    private static final Map<String, Integer> maskedCacheVersion = new HashMap<>();

    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FogOfWar-writer");
        t.setDaemon(true);
        return t;
    });

    /** Sets (or updates) the directory fog masks persist under: {@code <filesDir>/fog}. Safe to call repeatedly (e.g. once per view construction). */
    public static void init(File filesDir) {
        if (filesDir == null) return;
        synchronized (LOCK) {
            fogDir = new File(filesDir, FOG_SUBDIR);
        }
    }

    /** The mask key for {@code roomId} at floor {@code suffix} (0 = no suffix / single-floor) -- matches {@link AreaMapCalib#suffixFor}'s return and the on-disk filename stem. */
    public static String keyFor(int roomId, int suffix) {
        return roomId + "_" + suffix;
    }

    private static File fileFor(String key) {
        return fogDir != null ? new File(fogDir, key + ".bin") : null;
    }

    /** Caller must hold LOCK. The one-time 96-byte disk read per room is negligible. */
    private static byte[] getMask(String key) {
        byte[] m = masks.get(key);
        if (m != null) return m;
        m = loadFromDisk(key);
        if (m == null) m = new byte[MASK_BYTES];
        masks.put(key, m);
        return m;
    }

    private static byte[] loadFromDisk(String key) {
        File f = fileFor(key);
        if (f == null || !f.isFile()) return null;
        byte[] buf = new byte[MASK_BYTES];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = in.read(buf);
            if (n != MASK_BYTES) {
                Log.w(TAG, "fog mask " + f + " is " + n + " bytes, expected " + MASK_BYTES + " -- ignoring");
                return null;
            }
            return buf;
        } catch (IOException e) {
            Log.w(TAG, "failed to load fog mask " + f, e);
            return null;
        }
    }

    private static boolean getBit(byte[] mask, int cellX, int cellY) {
        int idx = cellY * GRID_W + cellX;
        return (mask[idx >> 3] & (1 << (idx & 7))) != 0;
    }

    private static void setBit(byte[] mask, int cellX, int cellY) {
        int idx = cellY * GRID_W + cellX;
        mask[idx >> 3] |= (1 << (idx & 7));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** True iff cell ({@code cellX}, {@code cellY}) of {@code key}'s mask is revealed. Out-of-range cells are always false. Lazily loads the mask (see {@link #getMask}) if not already resident. */
    public static boolean isRevealed(String key, int cellX, int cellY) {
        if (cellX < 0 || cellX >= GRID_W || cellY < 0 || cellY >= GRID_H) return false;
        synchronized (LOCK) {
            return getBit(getMask(key), cellX, cellY);
        }
    }

    /**
     * Reveals every cell of {@code key}'s mask whose centre is within
     * {@code radiusPx} of ({@code mapPx}, {@code mapPy}) -- both in the
     * 256x192 minimap's own pixel space -- always including the cell the
     * point itself falls in. NaN or out-of-image coordinates reveal nothing
     * and never throw. Returns true iff at least one cell newly flipped to
     * revealed, in which case the mask's version is bumped (see {@link
     * #applyMask}) and a debounced background write is scheduled (see
     * {@link #WRITE_DEBOUNCE_NANOS}).
     */
    public static boolean reveal(String key, float mapPx, float mapPy, float radiusPx) {
        if (key == null) return false;
        if (Float.isNaN(mapPx) || Float.isNaN(mapPy)) return false;
        if (mapPx < 0 || mapPy < 0 || mapPx >= IMAGE_W || mapPy >= IMAGE_H) return false;

        synchronized (LOCK) {
            byte[] mask = getMask(key);
            int leaderCx = clamp((int) (mapPx / CELL_PX), 0, GRID_W - 1);
            int leaderCy = clamp((int) (mapPy / CELL_PX), 0, GRID_H - 1);

            boolean changed = false;
            if (!getBit(mask, leaderCx, leaderCy)) {
                setBit(mask, leaderCx, leaderCy);
                changed = true;
            }

            int cellRadius = (int) Math.ceil(radiusPx / CELL_PX) + 1;
            int minCx = clamp(leaderCx - cellRadius, 0, GRID_W - 1);
            int maxCx = clamp(leaderCx + cellRadius, 0, GRID_W - 1);
            int minCy = clamp(leaderCy - cellRadius, 0, GRID_H - 1);
            int maxCy = clamp(leaderCy + cellRadius, 0, GRID_H - 1);
            float r2 = radiusPx * radiusPx;
            for (int cy = minCy; cy <= maxCy; cy++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    if (getBit(mask, cx, cy)) continue;
                    float ccx = cx * CELL_PX + CELL_PX / 2f;
                    float ccy = cy * CELL_PX + CELL_PX / 2f;
                    float dx = ccx - mapPx, dy = ccy - mapPy;
                    if (dx * dx + dy * dy <= r2) {
                        setBit(mask, cx, cy);
                        changed = true;
                    }
                }
            }

            if (changed) {
                maskVersion.put(key, maskVersion.getOrDefault(key, 0) + 1);
                scheduleWrite(key, mask);
            }
            return changed;
        }
    }

    /** Caller must hold LOCK. Writes now if the key is outside its debounce window, else queues one trailing write for when the window ends. */
    private static void scheduleWrite(String key, byte[] mask) {
        dirty.add(key);
        long now = System.nanoTime();
        Long last = lastWriteAt.get(key);
        long wait = last == null ? 0L : WRITE_DEBOUNCE_NANOS - (now - last);
        if (wait <= 0) {
            lastWriteAt.put(key, now);
            final byte[] snapshot = mask.clone();
            final int gen = generation;
            writer.execute(() -> writeToDisk(key, snapshot, gen));
        } else if (trailingQueued.add(key)) {
            final long sleepMs = Math.max(1L, wait / 1_000_000L);
            writer.execute(() -> {
                try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) { }
                byte[] snapshot; int gen;
                synchronized (LOCK) {
                    trailingQueued.remove(key);
                    byte[] m = masks.get(key);
                    if (m == null || !dirty.contains(key)) return;
                    lastWriteAt.put(key, System.nanoTime());
                    snapshot = m.clone();
                    gen = generation;
                }
                writeToDisk(key, snapshot, gen);
            });
        }
    }

    /** Writer thread only. Marks the key clean only if no newer reveal or clearAll() happened while writing. */
    private static void writeToDisk(String key, byte[] snapshot, int gen) {
        File f;
        synchronized (LOCK) {
            if (gen != generation) return; // cleared since this write was queued
            f = fileFor(key);
        }
        if (f == null) return;
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                Log.w(TAG, "could not create fog dir " + dir);
                return;
            }
            File tmp = new File(dir, f.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(snapshot);
            }
            if (!tmp.renameTo(f)) {
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(snapshot);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            synchronized (LOCK) {
                if (gen != generation) return;
                byte[] m = masks.get(key);
                if (m != null && java.util.Arrays.equals(m, snapshot)) dirty.remove(key);
            }
        } catch (IOException e) {
            Log.w(TAG, "failed to write fog mask " + f, e);
        }
    }

    /**
     * Forces any masks with a pending (debounced) write to write out now, on
     * the same background thread {@link #reveal} uses. Called from {@link
     * PartyPanelView#onDetachedFromWindow()}.
     */
    public static void flush() {
        synchronized (LOCK) {
            for (String key : dirty) {
                byte[] mask = masks.get(key);
                if (mask == null) continue;
                final byte[] snapshot = mask.clone();
                final int gen = generation;
                lastWriteAt.put(key, System.nanoTime());
                writer.execute(() -> writeToDisk(key, snapshot, gen));
            }
        }
    }

    /**
     * Returns a masked ARGB_8888 copy of {@code source} with every
     * unrevealed 8x8 cell of {@code key}'s mask fully transparent, or
     * {@code source} itself if it's null. The result is cached per key and
     * reused across calls until either the mask changes (see {@link
     * #reveal}) or {@code source} is a different {@link Bitmap} instance
     * than the one the cached copy was built from -- see the class doc.
     */
    public static Bitmap applyMask(String key, Bitmap source) {
        if (source == null) return null;
        byte[] maskCopy;
        int curVersion;
        synchronized (LOCK) {
            curVersion = maskVersion.getOrDefault(key, 0);
            Bitmap cachedMasked = maskedCache.get(key); // access-ordered: marks key most recent
            Bitmap cachedSource = maskedCacheSource.get(key);
            Integer cachedVersion = maskedCacheVersion.get(key);
            if (cachedMasked != null && cachedSource == source
                    && cachedVersion != null && cachedVersion == curVersion) {
                return cachedMasked;
            }
            maskCopy = getMask(key).clone();
        }

        Bitmap masked = source.copy(Bitmap.Config.ARGB_8888, true);
        if (masked == null) return source; // copy failed (e.g. recycled source) -- fall back to unmasked rather than crash

        Canvas canvas = new Canvas(masked);
        Paint clear = new Paint();
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        int w = masked.getWidth(), h = masked.getHeight();
        for (int cy = 0; cy < GRID_H; cy++) {
            int py0 = cy * CELL_PX;
            if (py0 >= h) break;
            int py1 = Math.min(py0 + CELL_PX, h);
            for (int cx = 0; cx < GRID_W; cx++) {
                if (getBit(maskCopy, cx, cy)) continue;
                int px0 = cx * CELL_PX;
                if (px0 >= w) break;
                int px1 = Math.min(px0 + CELL_PX, w);
                canvas.drawRect(px0, py0, px1, py1, clear);
            }
        }

        synchronized (LOCK) {
            maskedCache.put(key, masked);
            maskedCacheSource.put(key, source);
            maskedCacheVersion.put(key, curVersion);
            while (maskedCache.size() > MASKED_CACHE_MAX) {
                // Evict the least recently drawn key. Not recycled: the
                // panel may still hold it as the fading-out prevAreaMapBitmap.
                String eldest = maskedCache.keySet().iterator().next();
                maskedCache.remove(eldest);
                maskedCacheSource.remove(eldest);
                maskedCacheVersion.remove(eldest);
            }
        }
        return masked;
    }

    /**
     * Clears every in-memory mask/cache and deletes the on-disk fog
     * directory, so every dungeon starts fully fogged again. Called from
     * the settings screen's "Reset explored maps" button.
     */
    public static void clearAll() {
        File dir;
        synchronized (LOCK) {
            generation++;
            masks.clear();
            maskVersion.clear();
            dirty.clear();
            lastWriteAt.clear();
            trailingQueued.clear();
            maskedCache.clear();
            maskedCacheSource.clear();
            maskedCacheVersion.clear();
            dir = fogDir;
        }
        if (dir != null) deleteRecursive(dir);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
