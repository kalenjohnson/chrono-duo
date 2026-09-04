import com.kalenjohnson.chronoduo.WorldMapCompositor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Desktop test harness: runs the pure-Java WorldMapCompositor (the exact
 * pixel core WorldMapRenderer uses on device) against the extracted game
 * assets and compares its output pixel-for-pixel against
 * tools/world_map/render_world.py's reference renders.
 *
 * Usage:
 *   javac -d <scratch>/classes app/src/main/java/com/kalenjohnson/chronoduo/WorldMapCompositor.java tools/world_map/JavaRenderCheck.java
 *   java -cp <scratch>/classes JavaRenderCheck <assets-dir> <out-dir> [reference-dir]
 *
 * <assets-dir> must contain (flat, as produced by tools/ctres.py): Map_0000.dat
 * .. Map_0007.dat and worldchip_<chip>_<plt>_{0,1}.png for the 7 distinct
 * (chip, plt) pairs. <reference-dir> defaults to <assets-dir>/../world_out
 * (tools/world_map/render_world.py's own output directory convention).
 *
 * World 7's map file is Map_0002.dat (unpatched) -- the runtime bake from
 * libchrono.so is intentionally not reproduced here, mirroring
 * render_world.py without --libchrono.
 */
public class JavaRenderCheck {

    // world -> (chip, plt, mapId), mirrors WorldMapRenderer.WORLD_INFO / render_world.py's WORLD_INFO.
    private static final int[][] WORLD_INFO = {
            {0, 4, 0}, {0, 5, 1}, {2, 7, 3}, {3, 8, 4},
            {4, 9, 5}, {5, 10, 7}, {4, 9, 6}, {1, 6, 2},
    };
    private static final int NO_OVERLAY_WORLD = 5;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: JavaRenderCheck <assets-dir> <out-dir> [reference-dir]");
            System.exit(2);
        }
        File assetsDir = new File(args[0]);
        File outDir = new File(args[1]);
        File refDir = args.length > 2 ? new File(args[2]) : new File(assetsDir.getParentFile(), "world_out");
        outDir.mkdirs();

        System.out.println("Assets: " + assetsDir.getAbsolutePath());
        System.out.println("Out:    " + outDir.getAbsolutePath());
        System.out.println("Ref:    " + refDir.getAbsolutePath());
        System.out.println();

        int matchCount = 0;
        List<String> mismatches = new ArrayList<>();

        for (int world = 0; world < WORLD_INFO.length; world++) {
            int[] info = WORLD_INFO[world];
            int chip = info[0], plt = info[1], mapId = info[2];
            long t0 = System.nanoTime();

            byte[] map = readAll(new File(assetsDir, String.format(Locale.US, "Map_%04d.dat", mapId)));
            BufferedImage page0Img = ImageIO.read(new File(assetsDir, "worldchip_" + chip + "_" + plt + "_0.png"));
            BufferedImage page1Img = ImageIO.read(new File(assetsDir, "worldchip_" + chip + "_" + plt + "_1.png"));

            int dim = WorldMapCompositor.PAGE_DIM;
            int[] page0 = page0Img.getRGB(0, 0, dim, dim, null, 0, dim);
            int[] page1 = page1Img.getRGB(0, 0, dim, dim, null, 0, dim);

            int[] canvas = WorldMapCompositor.composite(map, page0, page1, world != NO_OVERLAY_WORLD);

            BufferedImage out = new BufferedImage(WorldMapCompositor.OUT_W, WorldMapCompositor.OUT_H, BufferedImage.TYPE_INT_ARGB);
            out.setRGB(0, 0, WorldMapCompositor.OUT_W, WorldMapCompositor.OUT_H, canvas, 0, WorldMapCompositor.OUT_W);
            File outFile = new File(outDir, "world_" + world + ".png");
            ImageIO.write(out, "png", outFile);

            long ms = (System.nanoTime() - t0) / 1_000_000L;
            System.out.printf(Locale.US, "world %d: rendered in %dms -> %s%n", world, ms, outFile.getName());

            File refFile = new File(refDir, "world_" + world + ".png");
            if (!refFile.isFile()) {
                System.out.println("  no reference at " + refFile + " -- skipping compare");
                continue;
            }
            BufferedImage ref = ImageIO.read(refFile);
            boolean match = compare(ref, out);
            System.out.println("  pixel match: " + match);
            if (match) matchCount++;
            else mismatches.add("world_" + world);
        }

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("Matched: " + matchCount + "/" + WORLD_INFO.length);
        if (!mismatches.isEmpty()) {
            System.out.println("Mismatched: " + mismatches);
            System.exit(1);
        }
    }

    private static boolean compare(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        int w = a.getWidth(), h = a.getHeight();
        int[] rowA = new int[w], rowB = new int[w];
        for (int y = 0; y < h; y++) {
            a.getRGB(0, y, w, 1, rowA, 0, w);
            b.getRGB(0, y, w, 1, rowB, 0, w);
            for (int x = 0; x < w; x++) {
                if (normalizeTransparent(rowA[x]) != normalizeTransparent(rowB[x])) return false;
            }
        }
        return true;
    }

    /** Fully-transparent pixels compare equal regardless of RGB. */
    private static int normalizeTransparent(int argb) {
        int a = (argb >>> 24) & 0xFF;
        return a == 0 ? 0 : argb;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) >= 0) off += n;
            if (off != buf.length) throw new IOException("short read: " + f);
            return buf;
        }
    }
}
