package com.kalenjohnson.chronoduo.origart;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java port of tools/orig_art/rebuild_sheet.py's rebuild() core.
 *
 * Rebuilds a 2x-smoothed sprite atlas by replacing each connected alpha
 * component with the pixel-doubled original 1x indexed-BMP art it best
 * matches (masked SAD in RGB, with horizontal flip considered), falling
 * back to the original (smoothed) pixels when no plausible-size candidate
 * scores within SCORE_THRESHOLD.
 *
 * Deliberately not ported from the Python (both are inert at the only call
 * site there, dilate_px=0):
 *  - dilate() / restrict_bbox_to_mask(): with no dilation the "restricted"
 *    bbox is just the component's own bbox, so segmentation here computes
 *    bboxes directly from the undilated mask.
 *  - MAX_FRAME_2X / "oversized": the Python computes and reports this but
 *    never filters frames by it -- oversized components still get matched
 *    normally. Not reproduced (nothing to reproduce: it's not a filter).
 *  - downsample2x's even-sized zero-padding: for mode="topleft" the padded
 *    rows/cols are provably never sampled, so the 2x downsample below reads
 *    straight from the source frame at even offsets, no padding needed.
 *
 * Everything else -- segmentation order, background-index tie-break,
 * masked SAD, fit-to-shape centering (with floor division matching
 * Python's // on both possibly-negative numerators), the 60.0 score
 * threshold, and the unmatched-frame fallback (which copies source pixels
 * verbatim, alpha included) -- is matched as closely as practical.
 *
 * All arrays are plain non-premultiplied ARGB int[] (row-major, y*w+x),
 * so this class has no Android dependency and is desktop-testable.
 */
public final class SheetRebuilder {
    private static final double SCORE_THRESHOLD = 60.0;

    private SheetRebuilder() {}

    public static final class Result {
        public final int pngFrameCount;
        public final int bmpFrameCount;
        public final int matched;
        public final int unmatched;
        /** Max SAD score among matched frames (0 if none matched). */
        public final double maxScore;

        Result(int pngFrameCount, int bmpFrameCount, int matched, int unmatched, double maxScore) {
            this.pngFrameCount = pngFrameCount;
            this.bmpFrameCount = bmpFrameCount;
            this.matched = matched;
            this.unmatched = unmatched;
            this.maxScore = maxScore;
        }

        @Override
        public String toString() {
            return "Result{pngFrames=" + pngFrameCount + ", bmpFrames=" + bmpFrameCount
                    + ", matched=" + matched + ", unmatched=" + unmatched + ", maxScore=" + maxScore + "}";
        }
    }

    /** Axis-aligned bbox, half-open: [x0,x1) x [y0,y1). */
    private static final class Bbox {
        final int x0, y0, x1, y1;
        Bbox(int x0, int y0, int x1, int y1) { this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; }
        int w() { return x1 - x0; }
        int h() { return y1 - y0; }
    }

    private static final class BmpCrop {
        final Bbox bbox;
        final int[] rgb;       // packed 0x00RRGGBB, row-major, size w*h
        final boolean[] mask;
        final int[] rgbFlip;
        final boolean[] maskFlip;
        final int w, h;
        BmpCrop(Bbox bbox, int[] rgb, boolean[] mask, int[] rgbFlip, boolean[] maskFlip, int w, int h) {
            this.bbox = bbox; this.rgb = rgb; this.mask = mask;
            this.rgbFlip = rgbFlip; this.maskFlip = maskFlip; this.w = w; this.h = h;
        }
    }

    /**
     * Rebuilds the atlas. outArgb must be pre-allocated to pngW*pngH; it is
     * zero-filled at the start (matching Python's np.zeros output), so any
     * region covered by no PNG-alpha frame is left transparent black -- it
     * is NOT copied from pngArgb.
     */
    public static Result rebuild(int[] pngArgb, int pngW, int pngH,
                                  byte[] bmpIndices, int bmpW, int bmpH,
                                  int[] bmpPaletteArgb, int[] outArgb) {
        if (pngArgb.length != pngW * pngH) throw new IllegalArgumentException("pngArgb size mismatch");
        if (bmpIndices.length != bmpW * bmpH) throw new IllegalArgumentException("bmpIndices size mismatch");
        if (outArgb.length != pngW * pngH) throw new IllegalArgumentException("outArgb size mismatch");
        java.util.Arrays.fill(outArgb, 0);

        boolean[] pngMask = new boolean[pngW * pngH];
        int[] pngRgb = new int[pngW * pngH];
        for (int i = 0; i < pngArgb.length; i++) {
            int a = (pngArgb[i] >>> 24) & 0xFF;
            pngMask[i] = a > 0;
            pngRgb[i] = pngArgb[i] & 0x00FFFFFF;
        }

        List<Bbox> pngFrames = segment(pngMask, pngW, pngH);

        int bgIndex = guessBgIndex(bmpIndices, bmpW, bmpH);
        boolean[] bmpMask = new boolean[bmpW * bmpH];
        int[] bmpRgb = new int[bmpW * bmpH];
        for (int i = 0; i < bmpIndices.length; i++) {
            int idx = bmpIndices[i] & 0xFF;
            bmpMask[i] = idx != bgIndex;
            bmpRgb[i] = bmpPaletteArgb[idx] & 0x00FFFFFF;
        }
        List<Bbox> bmpFrames = segment(bmpMask, bmpW, bmpH);

        List<BmpCrop> crops = new ArrayList<>(bmpFrames.size());
        for (Bbox bb : bmpFrames) {
            int w = bb.w(), h = bb.h();
            int[] rgb = new int[w * h];
            boolean[] mask = new boolean[w * h];
            for (int y = 0; y < h; y++) {
                int srcRow = (bb.y0 + y) * bmpW + bb.x0;
                int dstRow = y * w;
                for (int x = 0; x < w; x++) {
                    rgb[dstRow + x] = bmpRgb[srcRow + x];
                    mask[dstRow + x] = bmpMask[srcRow + x];
                }
            }
            int[] rgbFlip = new int[w * h];
            boolean[] maskFlip = new boolean[w * h];
            for (int y = 0; y < h; y++) {
                int rowBase = y * w;
                for (int x = 0; x < w; x++) {
                    rgbFlip[rowBase + x] = rgb[rowBase + (w - 1 - x)];
                    maskFlip[rowBase + x] = mask[rowBase + (w - 1 - x)];
                }
            }
            crops.add(new BmpCrop(bb, rgb, mask, rgbFlip, maskFlip, w, h));
        }

        int matched = 0, unmatched = 0;
        double maxScore = 0;

        for (Bbox bb : pngFrames) {
            int w = bb.w(), h = bb.h();
            int w1 = (w + 1) / 2, h1 = (h + 1) / 2;
            int[] candRgb = new int[w1 * h1];
            boolean[] candMask = new boolean[w1 * h1];
            for (int j = 0; j < h1; j++) {
                int sy = bb.y0 + 2 * j;
                int rowBase = j * w1;
                int srcRowBase = sy * pngW;
                for (int i = 0; i < w1; i++) {
                    int sx = bb.x0 + 2 * i;
                    candRgb[rowBase + i] = pngRgb[srcRowBase + sx];
                    candMask[rowBase + i] = pngMask[srcRowBase + sx];
                }
            }

            double bestScore = Double.POSITIVE_INFINITY;
            int[] bestRgb = null;
            boolean[] bestMask = null;
            int bestW = 0, bestH = 0;

            for (BmpCrop crop : crops) {
                if (Math.abs(crop.w - w1) > 2 || Math.abs(crop.h - h1) > 2) continue;
                for (int flip = 0; flip < 2; flip++) {
                    int[] r = flip == 0 ? crop.rgb : crop.rgbFlip;
                    boolean[] m = flip == 0 ? crop.mask : crop.maskFlip;
                    int fh = Math.max(crop.h, h1);
                    int fw = Math.max(crop.w, w1);
                    int[] r2 = new int[fh * fw];
                    boolean[] m2 = new boolean[fh * fw];
                    fitToShape(r, m, crop.h, crop.w, fh, fw, r2, m2);
                    int[] c2 = new int[fh * fw];
                    boolean[] cm2 = new boolean[fh * fw];
                    fitToShape(candRgb, candMask, h1, w1, fh, fw, c2, cm2);
                    double score = maskedSad(c2, cm2, r2, m2);
                    if (score < bestScore) {
                        bestScore = score;
                        bestRgb = r;
                        bestMask = m;
                        bestW = crop.w;
                        bestH = crop.h;
                    }
                }
            }

            if (bestRgb != null && bestScore <= SCORE_THRESHOLD) {
                int dh = bestH * 2, dw = bestW * 2;
                int yo = Math.floorDiv(h - dh, 2) + bb.y0;
                int xo = Math.floorDiv(w - dw, 2) + bb.x0;
                int dy0 = Math.max(0, yo), dx0 = Math.max(0, xo);
                int dy1 = Math.min(pngH, yo + dh), dx1 = Math.min(pngW, xo + dw);
                int sy0 = dy0 - yo, sx0 = dx0 - xo;
                for (int y = dy0; y < dy1; y++) {
                    int ry = sy0 + (y - dy0);
                    int outRowBase = y * pngW;
                    for (int x = dx0; x < dx1; x++) {
                        int rx = sx0 + (x - dx0);
                        // pixel-doubled source coordinate
                        int srcY = ry / 2, srcX = rx / 2;
                        int srcIdx = srcY * bestW + srcX;
                        if (bestMask[srcIdx]) {
                            outArgb[outRowBase + x] = 0xFF000000 | bestRgb[srcIdx];
                        }
                    }
                }
                matched++;
                if (bestScore > maxScore) maxScore = bestScore;
            } else {
                for (int y = bb.y0; y < bb.y1; y++) {
                    int rowBase = y * pngW;
                    for (int x = bb.x0; x < bb.x1; x++) {
                        outArgb[rowBase + x] = pngArgb[rowBase + x];
                    }
                }
                unmatched++;
            }
        }

        return new Result(pngFrames.size(), bmpFrames.size(), matched, unmatched, maxScore);
    }

    /** 4-connected component segmentation; components in raster (y outer, x inner) discovery order. */
    private static List<Bbox> segment(boolean[] mask, int w, int h) {
        List<Bbox> frames = new ArrayList<>();
        boolean[] visited = new boolean[mask.length];
        int[] stackX = new int[mask.length];
        int[] stackY = new int[mask.length];
        for (int y0 = 0; y0 < h; y0++) {
            int rowBase0 = y0 * w;
            for (int x0 = 0; x0 < w; x0++) {
                int idx0 = rowBase0 + x0;
                if (!mask[idx0] || visited[idx0]) continue;
                int sp = 0;
                stackX[sp] = x0; stackY[sp] = y0; sp++;
                visited[idx0] = true;
                int minX = x0, maxX = x0, minY = y0, maxY = y0;
                while (sp > 0) {
                    sp--;
                    int x = stackX[sp], y = stackY[sp];
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    // 4 neighbors
                    if (y - 1 >= 0) {
                        int ni = (y - 1) * w + x;
                        if (mask[ni] && !visited[ni]) { visited[ni] = true; stackX[sp] = x; stackY[sp] = y - 1; sp++; }
                    }
                    if (y + 1 < h) {
                        int ni = (y + 1) * w + x;
                        if (mask[ni] && !visited[ni]) { visited[ni] = true; stackX[sp] = x; stackY[sp] = y + 1; sp++; }
                    }
                    if (x - 1 >= 0) {
                        int ni = y * w + (x - 1);
                        if (mask[ni] && !visited[ni]) { visited[ni] = true; stackX[sp] = x - 1; stackY[sp] = y; sp++; }
                    }
                    if (x + 1 < w) {
                        int ni = y * w + (x + 1);
                        if (mask[ni] && !visited[ni]) { visited[ni] = true; stackX[sp] = x + 1; stackY[sp] = y; sp++; }
                    }
                }
                frames.add(new Bbox(minX, minY, maxX + 1, maxY + 1));
            }
        }
        return frames;
    }

    /** Most common index over the border (top/bottom rows, left/right cols); ties -> lowest index. */
    private static int guessBgIndex(byte[] indices, int w, int h) {
        int[] counts = new int[256];
        for (int x = 0; x < w; x++) {
            counts[indices[x] & 0xFF]++;                       // top row
            counts[indices[(h - 1) * w + x] & 0xFF]++;          // bottom row
        }
        for (int y = 0; y < h; y++) {
            counts[indices[y * w] & 0xFF]++;                    // left col
            counts[indices[y * w + (w - 1)] & 0xFF]++;          // right col
        }
        int best = 0, bestCount = -1;
        for (int i = 0; i < 256; i++) {
            if (counts[i] > bestCount) { bestCount = counts[i]; best = i; }
        }
        return best;
    }

    /**
     * Center-crops/pads (srcRgb,srcMask) of shape (h0,w0) into freshly zeroed
     * (outRgb,outMask) of shape (h,w), matching Python's fit_to_shape
     * (floor division for the centering offsets, which may be negative).
     */
    private static void fitToShape(int[] srcRgb, boolean[] srcMask, int h0, int w0,
                                    int h, int w, int[] outRgb, boolean[] outMask) {
        int yOff = Math.floorDiv(h - h0, 2);
        int xOff = Math.floorDiv(w - w0, 2);
        int sy0 = Math.max(0, -yOff), sy1 = Math.min(h0, h - yOff);
        int sx0 = Math.max(0, -xOff), sx1 = Math.min(w0, w - xOff);
        if (sy1 <= sy0 || sx1 <= sx0) return;
        int dy0 = yOff + sy0, dx0 = xOff + sx0;
        for (int sy = sy0; sy < sy1; sy++) {
            int srcRowBase = sy * w0;
            int dstRowBase = (dy0 + (sy - sy0)) * w + dx0;
            for (int sx = sx0; sx < sx1; sx++) {
                int dst = dstRowBase + (sx - sx0);
                outRgb[dst] = srcRgb[srcRowBase + sx];
                outMask[dst] = srcMask[srcRowBase + sx];
            }
        }
    }

    /** Masked SAD over the union of both masks; disagreeing mask pixels penalised at 255. */
    private static double maskedSad(int[] candRgb, boolean[] candMask, int[] tgtRgb, boolean[] tgtMask) {
        long sum = 0;
        long n = 0;
        for (int i = 0; i < candMask.length; i++) {
            boolean both = candMask[i] | tgtMask[i];
            if (!both) continue;
            n++;
            if (candMask[i] && tgtMask[i]) {
                int c = candRgb[i], t = tgtRgb[i];
                int dr = Math.abs(((c >> 16) & 0xFF) - ((t >> 16) & 0xFF));
                int dg = Math.abs(((c >> 8) & 0xFF) - ((t >> 8) & 0xFF));
                int db = Math.abs((c & 0xFF) - (t & 0xFF));
                sum += dr + dg + db;
            } else {
                sum += 255;
            }
        }
        if (n == 0) return 1e18;
        return (double) sum / (double) n;
    }
}
