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
 * Desktop test harness: runs the pure-Java {@link MapchipCore} (the exact
 * pixel core {@code MapchipRebuilder} uses on device) against the extracted
 * {@code Game/field} assets and compares its output pixel-for-pixel against
 * {@code tools/field_art/rebuild_mapchip.py}'s reference renders.
 *
 * Usage:
 * <pre>
 *   python3 tools/field_art/rebuild_mapchip.py &lt;field-dir&gt; &lt;ref-dir&gt; \
 *           --map 107 --map 6 --map 112 --map 219
 *   javac -d &lt;scratch&gt;/classes \
 *         app/src/main/java/com/kalenjohnson/chronoduo/origart/MapchipCore.java \
 *         tools/field_art/JavaMapchipCheck.java
 *   java -cp &lt;scratch&gt;/classes JavaMapchipCheck &lt;field-dir&gt; &lt;out-dir&gt; [ref-dir] [mapId ...]
 * </pre>
 *
 * {@code <field-dir>} is a directory laid out like the archive:
 * {@code Mapinfo/}, {@code BGSetTable/}, {@code ChipTable/}, {@code map_bin/},
 * {@code palette_bin/}. {@code <ref-dir>} defaults to {@code <out-dir>} (so a
 * single directory can hold both the Python's {@code *_rebuilt.png} and this
 * tool's {@code *_java.png}). With no map ids given it checks the four the
 * REPORT tested: 6, 107, 112, 219 -- i.e. sheets (0,0), (23,33), (20,27) and
 * (43,57), both pages each.
 *
 * Only ChipTable pages 0 and 1 are checkable (and rebuildable at all): a
 * {@code ChipTable_%04d.dat} is exactly 6144 bytes = two pages, while a
 * handful of chip sets ship a shipped-sheet page 2 or 3 from a source this
 * pipeline does not have.
 */
public class JavaMapchipCheck {

    private static final int[] DEFAULT_MAPS = {6, 107, 112, 219};

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: JavaMapchipCheck <field-dir> <out-dir> [ref-dir] [mapId ...]");
            System.exit(2);
        }
        File fieldDir = new File(args[0]);
        File outDir = new File(args[1]);
        File refDir = (args.length > 2) ? new File(args[2]) : outDir;
        int[] maps = DEFAULT_MAPS;
        if (args.length > 3) {
            maps = new int[args.length - 3];
            for (int i = 3; i < args.length; i++) maps[i - 3] = Integer.parseInt(args[i]);
        }
        outDir.mkdirs();

        System.out.println("Field: " + fieldDir.getAbsolutePath());
        System.out.println("Out:   " + outDir.getAbsolutePath());
        System.out.println("Ref:   " + refDir.getAbsolutePath());
        System.out.println();

        int checked = 0, matched = 0;
        List<String> mismatches = new ArrayList<>();

        for (int mapId : maps) {
            int[] mi = MapchipCore.readMapinfo(
                    readAll(new File(fieldDir, "Mapinfo/mapinfo_" + mapId + ".dat")));
            int a = mi[MapchipCore.MI_CHIPTABLE];
            int b = mi[MapchipCore.MI_PALETTE];
            int bgset = mi[MapchipCore.MI_BGSET];

            int[] slots = MapchipCore.bgsetSlots(
                    readAll(new File(fieldDir, "BGSetTable/bgsettable_" + bgset + ".dat")));
            byte[][] banks = new byte[MapchipCore.CG_SLOTS][];
            StringBuilder bankList = new StringBuilder();
            for (int s = 0; s < MapchipCore.CG_SLOTS; s++) {
                if (slots[s] < 0) continue;
                banks[s] = readAll(new File(fieldDir, "map_bin/cg" + slots[s] + ".bin"));
                if (bankList.length() > 0) bankList.append(',');
                bankList.append(slots[s]);
            }
            byte[] chipTable = readAll(new File(fieldDir,
                    String.format(Locale.US, "ChipTable/ChipTable_%04d.dat", a)));
            byte[] plt = readAll(new File(fieldDir, "palette_bin/plt" + b + ".bin"));

            for (int page = 0; page < MapchipCore.CHIPTABLE_PAGES; page++) {
                long t0 = System.nanoTime();
                int[] argb = MapchipCore.renderSheet2x(banks, chipTable, plt, page);
                long ms = (System.nanoTime() - t0) / 1_000_000L;

                String stem = "mapchip_" + a + "_" + b + "_" + page;
                BufferedImage img = new BufferedImage(MapchipCore.SHEET_PX, MapchipCore.SHEET_PX,
                        BufferedImage.TYPE_INT_ARGB);
                img.setRGB(0, 0, MapchipCore.SHEET_PX, MapchipCore.SHEET_PX, argb, 0, MapchipCore.SHEET_PX);
                File outFile = new File(outDir, stem + "_java.png");
                ImageIO.write(img, "png", outFile);

                System.out.printf(Locale.US, "%s  map=%d bgset=%d cg=[%s]  rendered in %dms -> %s%n",
                        stem, mapId, bgset, bankList, ms, outFile.getName());

                File refFile = new File(refDir, stem + "_rebuilt.png");
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
