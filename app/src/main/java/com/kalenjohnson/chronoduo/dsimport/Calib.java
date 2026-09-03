package com.kalenjohnson.chronoduo.dsimport;

/**
 * The tile-rect -> pixel-space (scale, offset) fitting model, isolated so it
 * can be revisited independently of everything else. Direct port of
 * gen_calib.py's rect_to_calib() / classify() ("v4" model: two fixed-size
 * boxes, capped scale ladders, non-ISO 8/7 vertical stretch, per-class
 * fixed image centre).
 *
 * IMPORTANT (carried over from gen_calib.py's own header comment): v3
 * (single ladder {1,2,4,8,16}, "pick whichever box fits", ISO sx=sy) was
 * disproved by a live test on a 16x16-tile room: the table said sx=8, but
 * the room is drawn ~64px wide (4px/tile) -- scale never actually reaches
 * 8 in practice. Two live-landmark calibrations (room 434, and room 5
 * refit) pinned down the real rule: sy = sx * 8/7 EXACTLY (a genuine,
 * global non-square-pixel vertical stretch, not a per-room quirk), and
 * scale is picked from a CAPPED ladder ({4,2} for a small 130x130 box,
 * {4,2,1} for a large 194x135 box) by taking WHICHEVER BOX GIVES THE
 * LARGER VALID SCALE -- not "try small first, fall back to large only if
 * small totally fails" (that was tried and left ~250/374 sampled rooms
 * wrong, since e.g. a 48-wide room always satisfies the small box at s=2
 * and never gets a chance to try s=4 in the large box).
 *
 * Placement is centred per-class on a FIXED point, fit directly from the
 * two live-verified rooms (not the raw 256x192 canvas centre, and NOT
 * derived from averaging measured PNG bbox centres across many rooms --
 * a room's drawn content can have asymmetric padding inside its own rect,
 * e.g. room 434's rect centres at y=98.2 but its own content bbox centres
 * at y=94.0, so bbox-averaging mixes real box-centre signal with
 * per-room content noise):
 *   SMALL_CENTER = (127.5, 98.2)        -- fit from room 434
 *   LARGE_CENTER = (126.0, 88.142857..) -- fit from room 5
 *
 * Verified against the ~375-room PNG-bbox sample (content bbox diffed
 * against the blank map, per room): predicted content-rect edges are an
 * upper bound on the measured bbox with median excess 0px, mean 0.69px,
 * and only 3/374 rooms exceeding by more than 6px (room 021, known
 * ARM-code special-cased; room 580, a tiny likely-anomalous room; room
 * 231, tw=51, just outside LARGE_BOX's width threshold).
 *
 * Room 5 is NO LONGER a hard-coded exception: since SMALL_CENTER and
 * LARGE_CENTER were fit directly from rooms 434 and 5, the general rule
 * reproduces both exactly (0px residual) -- there is nothing left for
 * the caller to override.
 *
 * CAVEAT: LARGE_CENTER rests on a single calibrated room (5);
 * SMALL_CENTER rests on a single calibrated room (434). Room 423 is a
 * known unexplained outlier: several other 32x32-tile rooms measure
 * scale 4 (matching this model), but 423 itself was observed drawn at
 * only ~2x, and its ARM9 Table 3 byte +14 (0x88) is uniquely different
 * from its same-size peers (all 0x00/0x04) -- a plausible per-room
 * override flag not conclusively decoded. This model does NOT
 * special-case 423.
 *
 * If a better calibration is ever derived, this is the one method that
 * needs to change.
 */
public final class Calib {

    private Calib() {}

    private static final int[] SMALL_LADDER = {4, 2};
    private static final double SMALL_BOX_W = 130.0;
    private static final double SMALL_BOX_H = 130.0;
    private static final double SMALL_CENTER_X = 127.5;
    private static final double SMALL_CENTER_Y = 98.2;

    private static final int[] LARGE_LADDER = {4, 2, 1};
    private static final double LARGE_BOX_W = 194.0;
    private static final double LARGE_BOX_H = 135.0;
    private static final double LARGE_CENTER_X = 126.0;
    private static final double LARGE_CENTER_Y = 88.142857142857;

    /** Global Y-axis stretch: sy = sx * KY. */
    private static final double KY = 8.0 / 7.0;

    /** Confirmed px/tile for the "no crop" (full 32x24 canvas) case. */
    public static final double NATIVE_SCALE = 8.0;

    public static final class Result {
        public final double sx, sy, ox, oy;

        public Result(double sx, double sy, double ox, double oy) {
            this.sx = sx;
            this.sy = sy;
            this.ox = ox;
            this.oy = oy;
        }
    }

    /** Largest ladder value that fits tw x th into boxW x boxH, or -1 if none does. */
    private static int bestFit(double tw, double th, int[] ladder, double boxW, double boxH) {
        for (int cand : ladder) {
            if (tw * cand <= boxW && th * cand <= boxH) return cand;
        }
        return -1;
    }

    /**
     * Picks whichever box (small vs. large) gives the LARGER valid scale.
     * Ties favour "small". Returns {isSmall(1/0), scale}.
     */
    private static int[] classify(double tw, double th) {
        int sSmall = bestFit(tw, th, SMALL_LADDER, SMALL_BOX_W, SMALL_BOX_H);
        int sLarge = bestFit(tw, th, LARGE_LADDER, LARGE_BOX_W, LARGE_BOX_H);
        if (sLarge < 0) sLarge = LARGE_LADDER[LARGE_LADDER.length - 1];
        if (sSmall >= 0 && sSmall >= sLarge) {
            return new int[]{1, sSmall};
        }
        return new int[]{0, sLarge};
    }

    /**
     * Fits a per-room tile-space crop rect (X0,Y0,X1,Y1) to the two-box,
     * capped-ladder, non-ISO model, matching gen_calib.py's
     * rect_to_calib() exactly -- including treating the tile span as
     * INCLUSIVE (X1-X0+1 tiles, not X1-X0).
     */
    public static Result fromRect(int x0, int y0, int x1, int y1) {
        double tw = Math.max(1, (x1 - x0) + 1);
        double th = Math.max(1, (y1 - y0) + 1);
        int[] cls = classify(tw, th);
        boolean small = cls[0] == 1;
        int s = cls[1];
        double sx = s;
        double sy = sx * KY;
        double centerX = small ? SMALL_CENTER_X : LARGE_CENTER_X;
        double centerY = small ? SMALL_CENTER_Y : LARGE_CENTER_Y;
        // Pixel-space midpoint of the inclusive tile range x0..x1 is
        // (x0 + x1 + 1) / 2 (tile x1 occupies [x1, x1+1), not just point x1).
        double cx = (x0 + x1 + 1) / 2.0;
        double cy = (y0 + y1 + 1) / 2.0;
        double ox = centerX - cx * sx;
        double oy = centerY - cy * sy;
        return new Result(sx, sy, ox, oy);
    }
}
