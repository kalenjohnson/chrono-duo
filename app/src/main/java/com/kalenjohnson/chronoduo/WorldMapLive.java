package com.kalenjohnson.chronoduo;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Keeps the panel's overworld picture in sync with the game's live map data.
 *
 * <p>The world is 96x64 metatiles in two u8 layers -- 12KB -- and {@code
 * WorldMap} keeps that grid live and patched as the story advances (the Zenan
 * bridge, the Lavos crater, the Ocean Palace). The native GL tick snapshots
 * those bytes off {@code WorldMap+0x237e0} (see gamestate.c) in exactly the
 * on-disk {@code Map_%04d.dat} layout; this class watches the snapshot's hash
 * and, when it moves, re-runs the very same {@link WorldMapCompositor} the
 * first-launch offline renderer uses. So a live map costs one 12KB read plus
 * an occasional 3072x2048 composite -- and no GL work at all.
 *
 * <p><b>Not the game's RenderTextures.</b> An earlier version read back the
 * twelve {@code cocos2d::RenderTexture}s at {@code WorldMap+0x26868}/{@code
 * +0x268c8}. That works mechanically but the content is wrong: during gameplay
 * they hold a 2x-magnified scrolling window around the party, tiled across the
 * six cells. They only become the whole 1x world inside the game's own map
 * screen. See NOTES.md.
 *
 * <p>A finished composite is published two ways:
 * <ul>
 *   <li>into {@link ChronoAssets#setLiveWorldMap} as the in-memory image
 *       {@link ChronoAssets#getWorldMap} returns from then on, so the panel
 *       switches over on the next frame; and
 *   <li>over {@code <externalFilesDir>/worldmap_era<N>.png} (with a {@code
 *       .hash} sidecar so an unchanged map is never re-encoded), so story
 *       changes survive a restart even before the player revisits that era.
 * </ul>
 *
 * <p>{@link #tick} is main-thread and cheap (two JNI scalar reads); the
 * composite and the PNG encode run on a background thread.
 */
public final class WorldMapLive {
    private static final String TAG = "ChronoDuoWorldMap";

    /** Sentinel for "no snapshot yet" -- the native hash is 0 before the first one. */
    private static final int NO_HASH = 0;

    private static File mapDir;   // where worldmap_era<N>.png lives (external files dir)
    private static File srcDir;   // staged Map_*.dat + worldchip_*.png (filesDir/world_src)

    // Last (world, hash) actually composited, so a re-entry into the same
    // unchanged world costs nothing.
    private static int lastWorld = -1;
    private static int lastHash = NO_HASH;
    // Guards the single background composite. Must be an atomic CAS, not a
    // plain boolean: it is set on the poll (main) thread and cleared on the
    // worker, so a non-volatile flag could leave the poller seeing "busy"
    // forever after the first composite -- and it is also what makes the
    // worker-thread-only state below (pages/pagesWorld/diffed) safe by
    // guaranteeing at most one worker at a time.
    private static final java.util.concurrent.atomic.AtomicBoolean composing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // Chip pages decoded for lastPagesWorld only -- two 512x512 int[] = 2MB,
    // and the player is in exactly one world at a time.
    private static int[][] pages;
    private static int pagesWorld = -1;
    // Worlds whose live grid has already been diffed against the shipped
    // Map_%04d.dat (logged once per world per session, not per composite).
    private static final java.util.Set<Integer> diffed = new java.util.HashSet<>();

    private static byte[] mapBytes;

    private WorldMapLive() {}

    /**
     * Records where the composited PNGs go ({@code mapDir}, the external files
     * dir -- same place {@link ChronoAssets#setWorldMapDir} reads) and where
     * the staged chip pages live ({@code srcDir}, {@code filesDir/world_src},
     * re-populated on every launch by AppActivity#renderWorldMaps). Set once
     * from AppActivity.
     */
    public static void setDirs(File mapDir, File srcDir) {
        WorldMapLive.mapDir = mapDir;
        WorldMapLive.srcDir = srcDir;
    }

    /**
     * One poll step, called from the panel's 500ms snapshot poll with the
     * snapshot just read. Recomposites only when the live grid's hash differs
     * from the last one composited for this world.
     */
    public static void tick(PartySnapshot snap) {
        if (!GameState.isAttached() || snap == null) return;
        if (!snap.worldScenePresent) return;          // snapshot survives, but nothing new can arrive
        maybeRecomposite(snap.worldEra, null);
    }

    /**
     * Recomposites {@code world} if the live map data has changed since the
     * last composite for it. {@code debugPng} (dev broadcast) forces a
     * composite even when the hash is unchanged and writes an extra copy
     * there.
     *
     * @return a one-line reason string when nothing was started, else null
     */
    private static String maybeRecomposite(final int world, final File debugPng) {
        final boolean forced = debugPng != null;
        if (composing.get()) return "a composite is already running";
        if (world < 0) return "world id unknown (nativeGetWorldEra returned -1)";
        if (!WorldMapRenderer.isKnownWorld(world)) {
            return "world " + world + " has no chip/palette mapping (special map)";
        }
        if (srcDir == null || !srcDir.isDirectory()) {
            return "staged sources missing (" + srcDir + ") -- extraction not finished?";
        }
        final int hash = GameState.nativeGetWorldMapHash();
        if (hash == NO_HASH) return "no live map snapshot yet (never been on the overworld)";
        if (!forced && world == lastWorld && hash == lastHash) return null; // up to date, the common case

        int size = GameState.nativeGetWorldMapDataSize();
        if (size != WorldMapCompositor.MAP_FILE_BYTES) {
            return "native map size " + size + " != expected " + WorldMapCompositor.MAP_FILE_BYTES;
        }
        if (mapBytes == null || mapBytes.length < size) mapBytes = new byte[size];
        if (!GameState.nativeGetWorldMapData(mapBytes)) return "nativeGetWorldMapData failed";

        // Copy: the poller reuses mapBytes on the next tick, and the composite
        // runs on another thread.
        final byte[] map = mapBytes.clone();
        // CAS rather than a bare store, so two ticks that both pass the .get()
        // check above cannot both launch a worker.
        if (!composing.compareAndSet(false, true)) return "a composite is already running";
        Log.i(TAG, "recomposite start: world=" + world + " hash=0x" + Integer.toHexString(hash)
                + " prevHash=0x" + Integer.toHexString(lastHash)
                + " wmIndex=" + GameState.nativeGetWorldMapIndex()
                + " dirty=" + GameState.nativeGetWorldMapDirty()
                + (forced ? " (forced)" : "") + markerLog());

        new Thread(() -> {
            try {
                composite(world, hash, map, debugPng);
            } catch (Throwable t) {
                Log.w(TAG, "recomposite world " + world + " failed", t);
            } finally {
                composing.set(false);
            }
        }, "WorldMapLiveComposite").start();
        return null;
    }

    /** Background: diff-log, composite, publish on the main thread, persist. */
    private static void composite(int world, int hash, byte[] map, File debugPng) throws IOException {
        logFileDiffOnce(world, map);

        if (pages == null || pagesWorld != world) {
            long tp = android.os.SystemClock.uptimeMillis();
            pages = WorldMapRenderer.loadChipPages(srcDir, world);
            pagesWorld = world;
            Log.i(TAG, "chip pages decoded for world " + world + " in "
                    + (android.os.SystemClock.uptimeMillis() - tp) + "ms");
        }

        long t0 = android.os.SystemClock.uptimeMillis();
        int[] argb = WorldMapCompositor.composite(map, pages[0], pages[1],
                WorldMapRenderer.overlayLayer0OnTop(world));
        int w = WorldMapCompositor.OUT_W, h = WorldMapCompositor.OUT_H;
        final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, w, 0, 0, w, h);
        // Drop the 3072x2048 int[] (25MB) NOW: ChronoAssets.setLiveWorldMap
        // sepia-tints into a second bitmap of the same size while this worker
        // still holds the first for the PNG encode, so without this the peak
        // would be three 25MB buffers instead of the two the offline render
        // path already peaks at.
        argb = null;
        long ms = android.os.SystemClock.uptimeMillis() - t0;

        Log.i(TAG, "recomposite done: world=" + world + " hash=0x" + Integer.toHexString(hash)
                + " size=" + w + "x" + h + " compositeMs=" + ms);

        new Handler(Looper.getMainLooper()).post(() -> {
            ChronoAssets.setLiveWorldMap(world, bmp);
            lastWorld = world;
            lastHash = hash;
        });

        persist(world, hash, bmp, debugPng);
    }

    /**
     * Logs, once per world per session, how far the live grid has drifted from
     * the {@code Map_%04d.dat} the offline renderer used -- i.e. whether the
     * game had already patched story state into it. 0 means the shipped render
     * was already correct for this save.
     */
    private static void logFileDiffOnce(int world, byte[] live) {
        if (!diffed.add(world)) return;
        try {
            byte[] file = WorldMapRenderer.readMapFile(srcDir, world);
            if (file.length != live.length) {
                Log.i(TAG, "world " + world + " live-vs-file: size differs ("
                        + live.length + " vs " + file.length + ")");
                return;
            }
            int diff = 0, firstAt = -1;
            for (int i = 0; i < file.length; i++) {
                if (file[i] != live[i]) {
                    if (firstAt < 0) firstAt = i;
                    diff++;
                }
            }
            Log.i(TAG, "world " + world + " live-vs-file (" + WorldMapRenderer.mapFileName(world)
                    + "): " + diff + "/" + file.length + " bytes differ"
                    + (firstAt >= 0 ? " (first at 0x" + Integer.toHexString(firstAt)
                        + ", layer " + (firstAt / WorldMapCompositor.LAYER_BYTES) + ")" : ""));
        } catch (Exception e) {
            Log.w(TAG, "world " + world + " live-vs-file diff failed", e);
        }
    }

    /**
     * Overwrites {@code worldmap_era<N>.png} with the live composite, skipping
     * the (slow) PNG encode when the {@code .hash} sidecar says the file on
     * disk was already made from this exact grid. The debug copy, when asked
     * for, is always written.
     */
    private static void persist(int world, int hash, Bitmap bmp, File debugPng) {
        if (debugPng != null) writePng(bmp, debugPng);
        if (mapDir == null) return;
        File png = new File(mapDir, "worldmap_era" + world + ".png");
        File sidecar = new File(mapDir, "worldmap_era" + world + ".hash");
        String want = Integer.toHexString(hash);
        if (png.isFile() && png.length() > 0 && want.equals(readText(sidecar))) {
            Log.i(TAG, "world " + world + " on-disk render already matches hash 0x" + want
                    + " -- not re-encoding");
            return;
        }
        if (!writePng(bmp, png)) return;
        writeText(sidecar, want);
        Log.i(TAG, "world " + world + " saved: " + png + " (" + png.length()
                + " bytes, hash 0x" + want + ")");
    }

    /** Raw + derived marker values, so a marker regression is visible in the same log line. */
    private static String markerLog() {
        int[] wp = GameState.nativeGetWorldPixelPos();
        if (wp == null || wp.length < 9) return " marker=unavailable";
        String s = " partyRaw=(" + wp[5] + "," + wp[6] + ") partyImg=(" + wp[0] + "," + wp[1] + ")";
        s += wp[4] == 1
                ? " epochRaw=(" + wp[7] + "," + wp[8] + ") epochImg=(" + wp[2] + "," + wp[3] + ")"
                : " epoch=hidden";
        return s;
    }

    // --- small file helpers ---------------------------------------------------

    /** Writes via a temp file + rename, so a kill mid-encode can't leave a truncated PNG that ChronoAssets would decode to null and remember as a permanent miss. */
    private static boolean writePng(Bitmap bmp, File out) {
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                Log.w(TAG, "PNG compress failed for " + out);
                tmp.delete();
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "save failed: " + out, e);
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(out)) {
            Log.w(TAG, "rename failed: " + tmp + " -> " + out);
            tmp.delete();
            return false;
        }
        return true;
    }

    private static String readText(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[64];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8").trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(File f, String text) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "sidecar write failed: " + f, e);
        }
    }

    /**
     * Dev entry point for the {@code WORLD_MAP_CAPTURE} broadcast: logs the
     * live-vs-file diff count, the grid hash, the raw/derived marker values,
     * and forces a recomposite (even if the hash is unchanged) that is also
     * written to {@code <externalFilesDir>/worldmap_capture_debug.png}.
     *
     * <p>Unlike the previous RenderTexture version this cannot be starved by
     * the regular path consuming a one-shot: there is no one-shot -- a forced
     * composite always runs from the current snapshot.
     */
    public static void requestDebugCapture(File debugPng) {
        if (!GameState.isAttached()) {
            Log.w(TAG, "debug capture: native hook not attached");
            return;
        }
        int era = GameState.nativeGetWorldEra();
        Log.i(TAG, "debug capture: scenePresent=" + GameState.nativeGetWorldScenePresent()
                + " era=" + era
                + " wmIndex=" + GameState.nativeGetWorldMapIndex()
                + " dirty=" + GameState.nativeGetWorldMapDirty()
                + " hash=0x" + Integer.toHexString(GameState.nativeGetWorldMapHash())
                + " lastComposited=(world=" + lastWorld
                + ", hash=0x" + Integer.toHexString(lastHash) + ")"
                + markerLog());
        String why = maybeRecomposite(era, debugPng);
        if (why != null) Log.w(TAG, "debug capture: nothing done -- " + why);
    }
}
