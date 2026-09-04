import com.kalenjohnson.chronoduo.origart.MapchipCore;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Desktop test harness: runs the pure-Java overworld path of {@link
 * MapchipCore} (the exact pixel core {@code WorldchipRebuilder} uses on
 * device) against the extracted {@code Game/world} assets and compares its
 * output pixel-for-pixel against {@code tools/world_art/rebuild_worldchip.py}'s
 * reference renders.
 *
 * <p>This also checks the one constant that is hardcoded rather than parsed:
 * {@code MapchipCore.WORLDMAPINFO}, the in-binary {@code
 * WorldMapInfo::G_WORLDMAPINFO} table @ 0xbe2508 that names the cg banks per
 * world. {@code rebuild_worldchip.py --libchrono} re-reads it out of the ELF;
 * a pixel match here means the Java copy renders the same 14 sheets.</p>
 *
 * Usage:
 * <pre>
 *   python3 tools/world_art/rebuild_worldchip.py &lt;world-dir&gt; &lt;ref-dir&gt;
 *   javac -d &lt;scratch&gt;/classes \
 *         app/src/main/java/com/kalenjohnson/chronoduo/origart/MapchipCore.java \
 *         tools/world_art/JavaWorldchipCheck.java
 *   java -cp &lt;scratch&gt;/classes JavaWorldchipCheck &lt;world-dir&gt; &lt;out-dir&gt; [ref-dir]
 * </pre>
 *
 * {@code <world-dir>} is a directory laid out like the archive:
 * {@code Chip/Chip_%04d.dat}, {@code map_bin/cg<n>.bin},
 * {@code plt_bin/plt<n>.bin}. {@code <ref-dir>} defaults to {@code <out-dir>}.
 */
public class JavaWorldchipCheck {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: JavaWorldchipCheck <world-dir> <out-dir> [ref-dir]");
            System.exit(2);
        }
        File worldDir = new File(args[0]);
        File outDir = new File(args[1]);
        File refDir = (args.length > 2) ? new File(args[2]) : outDir;
        outDir.mkdirs();

        System.out.println("World: " + worldDir.getAbsolutePath());
        System.out.println("Out:   " + outDir.getAbsolutePath());
        System.out.println("Ref:   " + refDir.getAbsolutePath());
        System.out.println();

        int checked = 0, matched = 0;
        List<String> mismatches = new ArrayList<>();

        for (int[] row : MapchipCore.worldSheetPairs()) {
            int a = row[MapchipCore.WMI_CHIP];
            int b = row[MapchipCore.WMI_PALETTE];

            byte[][] banks = new byte[MapchipCore.WORLD_CG_SLOTS][];
            StringBuilder bankList = new StringBuilder();
            for (int s = 0; s < MapchipCore.WORLD_CG_SLOTS; s++) {
                if (bankList.length() > 0) bankList.append(',');
                if (row[s] == MapchipCore.WORLD_CG_NONE) {
                    bankList.append('-');
                    continue;
                }
                banks[s] = readAll(new File(worldDir, "map_bin/cg" + row[s] + ".bin"));
                bankList.append(row[s]);
            }
            byte[] chipTable = readAll(new File(worldDir,
                    String.format(Locale.US, "Chip/Chip_%04d.dat", a)));
            byte[] plt = readAll(new File(worldDir, "plt_bin/plt" + b + ".bin"));

            for (int page = 0; page < MapchipCore.WORLD_CHIPTABLE_PAGES; page++) {
                long t0 = System.nanoTime();
                int[] argb = MapchipCore.worldRenderSheet2x(banks, chipTable, plt, page);
                long ms = (System.nanoTime() - t0) / 1_000_000L;

                String stem = "worldchip_" + a + "_" + b + "_" + page;
                BufferedImage img = new BufferedImage(MapchipCore.SHEET_PX, MapchipCore.SHEET_PX,
                        BufferedImage.TYPE_INT_ARGB);
                img.setRGB(0, 0, MapchipCore.SHEET_PX, MapchipCore.SHEET_PX, argb, 0, MapchipCore.SHEET_PX);
                File outFile = new File(outDir, stem + "_java.png");
                ImageIO.write(img, "png", outFile);

                System.out.printf(Locale.US, "%s  cg=[%s]  rendered in %dms -> %s%n",
                        stem, bankList, ms, outFile.getName());

                File refFile = new File(refDir, stem + ".png");
                if (!refFile.isFile()) {
                    System.out.println("  no reference at " + refFile + " -- skipping compare");
                    continue;
                }
                checked++;
                BufferedImage ref = ImageIO.read(refFile);
                int diff = compare(ref, img);
                if (diff == 0) {
                    matched++;
                    System.out.println("  pixel match: true");
                } else {
                    mismatches.add(stem + " (" + diff + " px)");
                    System.out.println("  pixel match: FALSE -- " + diff + " differing pixels");
                }
            }
        }

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("Matched: " + matched + "/" + checked);
        if (!mismatches.isEmpty()) {
            System.out.println("Mismatched: " + mismatches);
            System.exit(1);
        }
    }

    /** Counts differing pixels; fully-transparent pixels compare equal regardless of RGB. */
    private static int compare(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return Integer.MAX_VALUE;
        int w = a.getWidth(), h = a.getHeight(), diff = 0;
        int[] rowA = new int[w], rowB = new int[w];
        for (int y = 0; y < h; y++) {
            a.getRGB(0, y, w, 1, rowA, 0, w);
            b.getRGB(0, y, w, 1, rowB, 0, w);
            for (int x = 0; x < w; x++) {
                if (normalizeTransparent(rowA[x]) != normalizeTransparent(rowB[x])) diff++;
            }
        }
        return diff;
    }

    private static int normalizeTransparent(int argb) {
        return ((argb >>> 24) & 0xFF) == 0 ? 0 : argb;
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
