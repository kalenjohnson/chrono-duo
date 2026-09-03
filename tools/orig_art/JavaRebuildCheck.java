import com.kalenjohnson.chronoduo.origart.BmpIndexed;
import com.kalenjohnson.chronoduo.origart.SheetRebuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.file.Files;

/**
 * Desktop harness (no Android deps) for SheetRebuilder + BmpIndexed.
 *
 * Usage: java JavaRebuildCheck <png> <bmp> <reference_png> [out_png]
 *
 * Runs SheetRebuilder on <png>+<bmp> and compares pixel-for-pixel against
 * <reference_png> (expected: the Python rebuild_sheet.py output for the
 * same inputs). Prints the Result stats and the match percentage.
 */
public class JavaRebuildCheck {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: JavaRebuildCheck <png> <bmp> <reference_png> [out_png]");
            System.exit(2);
        }
        File pngFile = new File(args[0]);
        File bmpFile = new File(args[1]);
        File refFile = new File(args[2]);
        File outFile = args.length > 3 ? new File(args[3]) : null;

        BufferedImage pngImg = readArgbNonPremultiplied(pngFile);
        int pngW = pngImg.getWidth(), pngH = pngImg.getHeight();
        int[] pngArgb = ((DataBufferInt) pngImg.getRaster().getDataBuffer()).getData().clone();

        byte[] bmpBytes = Files.readAllBytes(bmpFile.toPath());
        BmpIndexed bmp = BmpIndexed.parse(bmpBytes);

        int[] outArgb = new int[pngW * pngH];
        long t0 = System.nanoTime();
        SheetRebuilder.Result result = SheetRebuilder.rebuild(
                pngArgb, pngW, pngH,
                bmp.indices, bmp.width, bmp.height,
                bmp.paletteArgb, outArgb);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("Result: " + result + "  (" + ms + " ms)");

        BufferedImage outImg = argbToImage(outArgb, pngW, pngH);
        if (outFile != null) {
            writeArgbNonPremultiplied(outImg, outFile);
            System.out.println("Wrote " + outFile);
        }

        BufferedImage refImg = readArgbNonPremultiplied(refFile);
        if (refImg.getWidth() != pngW || refImg.getHeight() != pngH) {
            System.out.println("MISMATCH: reference is " + refImg.getWidth() + "x" + refImg.getHeight()
                    + ", output is " + pngW + "x" + pngH);
            System.exit(1);
        }
        int[] refArgb = ((DataBufferInt) refImg.getRaster().getDataBuffer()).getData();

        long total = (long) pngW * pngH;
        long exact = 0;
        long rgbOnlyMismatchWhereAlphaZero = 0;
        for (int i = 0; i < total; i++) {
            if (outArgb[i] == refArgb[i]) {
                exact++;
            } else {
                int aOut = (outArgb[i] >>> 24) & 0xFF;
                int aRef = (refArgb[i] >>> 24) & 0xFF;
                if (aOut == 0 && aRef == 0) rgbOnlyMismatchWhereAlphaZero++;
            }
        }
        double pct = 100.0 * exact / total;
        System.out.printf("Pixel match: %d / %d = %.4f%%%n", exact, total, pct);
        if (rgbOnlyMismatchWhereAlphaZero > 0) {
            System.out.println("  (of which " + rgbOnlyMismatchWhereAlphaZero
                    + " mismatches are RGB-only differences under alpha=0 on both sides)");
        }
    }

    /** Reads a PNG into a TYPE_INT_ARGB (non-premultiplied) BufferedImage, preserving RGB under alpha=0. */
    private static BufferedImage readArgbNonPremultiplied(File f) throws Exception {
        BufferedImage raw = ImageIO.read(f);
        BufferedImage img = new BufferedImage(raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_INT_ARGB);
        // Draw with a no-op composite would still let Java premultiply internally for some paths;
        // instead copy raw ARGB samples directly via getRGB, which returns non-premultiplied ARGB
        // regardless of the source image's own storage, and does not zero RGB under alpha=0
        // (Java's default TYPE_INT_ARGB raster preserves whatever samples were decoded).
        int w = raw.getWidth(), h = raw.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            raw.getRGB(0, y, w, 1, row, 0, w);
            img.getRaster().setDataElements(0, y, w, 1, row);
        }
        return img;
    }

    private static void writeArgbNonPremultiplied(BufferedImage img, File out) throws Exception {
        ImageIO.write(img, "png", out);
    }

    private static BufferedImage argbToImage(int[] argb, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.getRaster().setDataElements(0, 0, w, h, argb);
        return img;
    }
}
