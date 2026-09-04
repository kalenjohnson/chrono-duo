#!/usr/bin/env python3
"""
rebuild_worldchara.py -- attempt (and disprove) an original-1x rebuild of
`Game/common/worldChara.png`, the overworld party sprite sheet.

VERDICT: there is no 1x source for this sheet anywhere in resources.bin.
This script is the evidence, kept runnable so the negative can be
re-derived rather than trusted.  It exits 1 when no row matches, which is
what it does against the shipped archive.

Background
----------
Every other sheet in the original-art pipeline is a 2x bake of art that
also ships at 1x:

  * `Game/chara/png/<n>.png`      <- `Game/chara/bmp/<n>.bmp`   (SheetRebuilder)
  * `Game/world/gif/<n>.png`      <- `Game/world/gif/<n>.bmp`   (SheetRebuilder)
  * `Game/world/worldchip_*.png`  <- cg banks + Chip + plt      (MapchipCore)

`worldChara.png` (384x384 RGBA, 8 rows x 8 columns of 48x48 cells: rows
0..6 are the seven party members in character-id order, each row 8 walk /
stand frames; row 7 is one extra pose per character, 7 cells) has neither
a `.bmp` sibling nor a cg/chip-table path -- see REPORT.md section 6.  The
hypothesis this script tests is the remaining one: that the overworld
frames are the *field* frames, i.e. that they can be found inside
`Game/chara/bmp/c00N_*.bmp` (N = character id) or, failing that, anywhere
in the BMP corpus.

They cannot.  See REPORT.md section 6.1 for the numbers this prints.

What it measures
----------------
1. **Segmentation.**  `rebuild_sheet.segment_png` masks on `alpha > 0`.
   That is wrong for this sheet: its alpha channel carries a large halo of
   1..46-valued pixels (a lossy-pipeline artefact), and `alpha > 0` yields
   **2914** components.  At any threshold in 64..192 it yields **71**, of
   which **63** are larger than 8x8 -- exactly the 7*8 + 7 sprites the
   layout predicts.  ALPHA_THRESHOLD below is that threshold.

2. **Downsampling.**  The sheet *is* a 2x upscale: 2x2 blocks aligned to
   phase (0,0) have a mean intra-block channel range of 66 vs 141 at phase
   (1,1).  But the blocks are not flat (only 4.6% are within +-8), and the
   sheet holds 31681 distinct opaque colours, so `rebuild_sheet`'s
   "topleft" 2x decimation samples noise.  An alpha-weighted 2x2 box mean
   (`box1x` below) recovers a clean 1x instead, and is what the matching
   uses -- this is deliberately *more* generous to the hypothesis than the
   production path would be.

3. **Matching.**  `rebuild_sheet`'s masked SAD with horizontal flips and
   the same +-2 size gate, but against several BMP sources at once:
   rows 0..6 against that character's own bitmaps, row 7 against all seven
   (the extra pose could belong to any of them), and -- with `--corpus` --
   against every BMP given, size gate widened to +-5.

Usage
-----
    python3 rebuild_worldchara.py <worldChara.png> <chara-bmp-dir> <out-dir> \
        [--corpus <dir-of-bmps> ...] [--size-slack N]

`<chara-bmp-dir>` holds `c000_0.bmp` .. `c006_1.bmp` extracted with
`tools/ctres.py`; each `--corpus` directory is a flat directory of `.bmp`
files extracted the same way (the archive's 708 BMPs live under
`Game/chara/bmp/` (629), `Game/battle/oef/` (54), `Game/world/gif/` (21) and
`Game/common/` (4)).  Writes `<out-dir>/worldchara_sbs.png` (shipped left,
best match right, per sprite) and `<out-dir>/worldchara_1x.png` (the box
downsample, for inspection only -- it is NOT original art and is not what
the pipeline ships; its colours are recovered averages, not palette
entries).

Dependencies: Pillow, numpy.
"""
import argparse
import glob
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "orig_art"))
from rebuild_sheet import label_components, guess_bg_index, fit_to_shape, masked_sad  # noqa: E402

# See docstring point 1: `alpha > 0` gives 2914 components on this sheet,
# any threshold in 64..192 gives 71 (63 of them sprite-sized).
ALPHA_THRESHOLD = 128
CELL = 48
ROWS = 8
# Components smaller than this in either axis (2x space) are halo specks --
# on this sheet exactly 8 of them, all sparkle/dust pixels beside a sprite.
MIN_COMPONENT_2X = 9
# rebuild_sheet.rebuild's own accept threshold; a real frame match scores
# well under it (the gif sheets' pixel-doubled `7_wobj0` scores 0.0).
SCORE_THRESHOLD = 60.0
# Character id -> its field bitmaps, in a FIXED order.
CHARA_BMPS = [
    ["c000_0", "c000_1"],           # 0 Crono
    ["c001_0", "c001_1"],           # 1 Marle
    ["c002_0", "c002_1", "c002_2"],  # 2 Lucca
    ["c003_0", "c003_1"],           # 3 Robo
    ["c004_0", "c004_1"],           # 4 Frog
    ["c005_0", "c005_1"],           # 5 Ayla
    ["c006_0", "c006_1"],           # 6 Magus
]
CHARA_NAMES = ["Crono", "Marle", "Lucca", "Robo", "Frog", "Ayla", "Magus"]


def bmp_crops(path):
    """Every connected non-background component of an indexed BMP, as
    (name, bbox, rgb, mask) -- the same segmentation rebuild_sheet does,
    with the background index guessed PER FILE (each BMP has its own
    palette and its own background index)."""
    name = os.path.splitext(os.path.basename(path))[0]
    im = Image.open(path)
    idx = np.array(im)
    pal = np.array(im.getpalette(), dtype=np.uint8).reshape(-1, 3)
    bg = guess_bg_index(idx)
    mask = idx != bg
    rgb = pal[idx]
    _, comps = label_components(mask)
    out = []
    for c in comps:
        x0, y0, x1, y1 = c["bbox"]
        if (x1 - x0) < 4 or (y1 - y0) < 4:
            continue
        out.append((name, (x0, y0, x1, y1),
                    rgb[y0:y1, x0:x1].copy(), mask[y0:y1, x0:x1].copy()))
    return out


def segment_sheet(png_rgba):
    """The 63 sprite components of the 2x sheet, in (row, x) order, plus
    the count of sub-sprite specks dropped."""
    mask = png_rgba[:, :, 3] >= ALPHA_THRESHOLD
    _, comps = label_components(mask)
    frames, specks = [], 0
    for c in comps:
        x0, y0, x1, y1 = c["bbox"]
        if (x1 - x0) < MIN_COMPONENT_2X or (y1 - y0) < MIN_COMPONENT_2X:
            specks += 1
            continue
        frames.append((x0, y0, x1, y1))
    frames.sort(key=lambda b: (((b[1] + b[3]) // 2) // CELL, b[0]))
    return frames, specks, mask


def box1x(png_rgba_f, bbox):
    """Alpha-weighted 2x2 box mean -- the 1x the sheet is a 2x upscale of.

    NOT rebuild_sheet's "topleft" decimation: this sheet's 2x2 blocks are
    not flat (4.6% within +-8) so a single sample per block reads noise.
    """
    x0, y0, x1, y1 = bbox
    w, h = x1 - x0, y1 - y0
    w -= w % 2
    h -= h % 2
    sub = png_rgba_f[y0:y0 + h, x0:x0 + w]
    wgt = sub[:, :, 3:4]
    num = (sub[:, :, :3] * wgt).reshape(h // 2, 2, w // 2, 2, 3).sum(axis=(1, 3))
    den = wgt.reshape(h // 2, 2, w // 2, 2, 1).sum(axis=(1, 3))
    rgb = np.where(den > 0, num / np.maximum(den, 1e-9), 0).astype(np.uint8)
    a = sub[:, :, 3].reshape(h // 2, 2, w // 2, 2).mean(axis=(1, 3))
    return rgb, a >= ALPHA_THRESHOLD


def best_match(cand_rgb, cand_mask, crops, slack):
    """rebuild_sheet's masked SAD with flips, over an ORDERED crop list
    (ties resolved by first encountered, as in rebuild_sheet/SheetRebuilder).
    Returns (score, iou, name, bbox, flip, rgb, mask) or None."""
    ch, cw = cand_mask.shape
    best = None
    for (name, bb, rgb, mask) in crops:
        bh, bw = mask.shape
        if abs(bw - cw) > slack or abs(bh - ch) > slack:
            continue
        for flip in (0, 1):
            m = mask[:, ::-1] if flip else mask
            r = rgb[:, ::-1] if flip else rgb
            fh, fw = max(bh, ch), max(bw, cw)
            r2, m2 = fit_to_shape(r, m, fh, fw)
            c2, cm2 = fit_to_shape(cand_rgb, cand_mask, fh, fw)
            score, _ = masked_sad(c2, cm2, r2, m2)
            if best is None or score < best[0]:
                union = (m2 | cm2).sum()
                iou = float((m2 & cm2).sum()) / union if union else 0.0
                best = (score, iou, name, bb, flip,
                        np.ascontiguousarray(r), np.ascontiguousarray(m))
    return best


def side_by_side(png_rgba, frames, results, out_path, zoom=2):
    """Shipped sheet left, best match per sprite right (pixel-doubled back
    into the sprite's own cell), on a grey ground."""
    h, w = png_rgba.shape[:2]
    right = np.zeros((h, w, 4), dtype=np.uint8)
    for bbox, res in zip(frames, results):
        if res is None:
            continue
        _, _, _, _, _, r, m = res
        dh, dw = m.shape[0] * 2, m.shape[1] * 2
        dr = np.repeat(np.repeat(r, 2, axis=0), 2, axis=1)
        dm = np.repeat(np.repeat(m, 2, axis=0), 2, axis=1)
        x0, y0, x1, y1 = bbox
        yo = y0 + (y1 - y0 - dh) // 2
        xo = x0 + (x1 - x0 - dw) // 2
        dy0, dx0 = max(0, yo), max(0, xo)
        dy1, dx1 = min(h, yo + dh), min(w, xo + dw)
        if dy1 <= dy0 or dx1 <= dx0:
            continue
        sr = dr[dy0 - yo:dy1 - yo, dx0 - xo:dx1 - xo]
        sm = dm[dy0 - yo:dy1 - yo, dx0 - xo:dx1 - xo]
        right[dy0:dy1, dx0:dx1, :3] = np.where(sm[..., None], sr, right[dy0:dy1, dx0:dx1, :3])
        right[dy0:dy1, dx0:dx1, 3] = np.where(sm, 255, right[dy0:dy1, dx0:dx1, 3])

    grey = Image.new("RGBA", (w, h), (40, 40, 50, 255))
    left_im = Image.alpha_composite(grey, Image.fromarray(png_rgba, "RGBA"))
    right_im = Image.alpha_composite(grey, Image.fromarray(right, "RGBA"))
    im = Image.new("RGB", (w * 2 + 8, h), (255, 0, 255))
    im.paste(left_im.convert("RGB"), (0, 0))
    im.paste(right_im.convert("RGB"), (w + 8, 0))
    if zoom != 1:
        im = im.resize((im.width * zoom, im.height * zoom), Image.NEAREST)
    im.save(out_path)
    return out_path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sheet", help="extracted Game/common/worldChara.png")
    ap.add_argument("bmp_dir", help="directory holding c000_0.bmp .. c006_1.bmp")
    ap.add_argument("out_dir")
    ap.add_argument("--corpus", action="append", default=[],
                    help="extra directory of .bmp files for a whole-corpus fallback "
                         "(repeatable); searched when the per-character match fails")
    ap.add_argument("--size-slack", type=int, default=2,
                    help="size gate for the per-character search (rebuild_sheet uses 2)")
    ap.add_argument("--corpus-slack", type=int, default=5,
                    help="size gate for the whole-corpus fallback (wider on purpose)")
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)

    png = np.asarray(Image.open(args.sheet).convert("RGBA"))
    png_f = png.astype(np.float64)
    frames, specks, _ = segment_sheet(png)
    n_all = len(label_components(png[:, :, 3] > 0)[1])
    print("sheet %s  %dx%d" % (args.sheet, png.shape[1], png.shape[0]))
    print("  components: %d at alpha>0, %d at alpha>=%d (%d sprite-sized, %d specks)"
          % (n_all, len(frames) + specks, ALPHA_THRESHOLD, len(frames), specks))
    print("  distinct opaque colours: %d"
          % len(np.unique(png[:, :, :3][png[:, :, 3] >= ALPHA_THRESHOLD], axis=0)))

    per_char = []
    for names in CHARA_BMPS:
        crops = []
        for n in names:                      # fixed order: ties break on the first source
            p = os.path.join(args.bmp_dir, n + ".bmp")
            if not os.path.exists(p):
                print("  warning: %s missing" % p, file=sys.stderr)
                continue
            crops.extend(bmp_crops(p))
        per_char.append(crops)

    corpus = []
    for d in args.corpus:
        for p in sorted(glob.glob(os.path.join(d, "*.bmp"))):
            corpus.extend(bmp_crops(p))
    if args.corpus:
        print("  corpus fallback: %d components from %d file(s)"
              % (len(corpus), sum(len(glob.glob(os.path.join(d, '*.bmp'))) for d in args.corpus)))
    print()

    results = []
    rows = {}
    for bbox in frames:
        row = ((bbox[1] + bbox[3]) // 2) // CELL
        cand_rgb, cand_mask = box1x(png_f, bbox)
        if row < 7:
            best = best_match(cand_rgb, cand_mask, per_char[row], args.size_slack)
            scope = CHARA_NAMES[row]
        else:
            best, scope = None, "any-of-seven"
            for crops in per_char:           # row 7: the extra pose, character unknown
                b = best_match(cand_rgb, cand_mask, crops, args.size_slack)
                if b is not None and (best is None or b[0] < best[0]):
                    best = b
        if (best is None or best[0] > SCORE_THRESHOLD) and corpus:
            b = best_match(cand_rgb, cand_mask, corpus, args.corpus_slack)
            if b is not None and (best is None or b[0] < best[0]):
                best, scope = b, "corpus"
        results.append(best)
        rows.setdefault(row, []).append((bbox, scope, best))

    total_matched = 0
    for row in sorted(rows):
        items = rows[row]
        matched = [i for i in items if i[2] is not None and i[2][0] <= SCORE_THRESHOLD]
        total_matched += len(matched)
        scored = [i[2][0] for i in items if i[2] is not None]
        label = CHARA_NAMES[row] if row < 7 else "extra pose"
        print("row %d %-11s %d/%d matched   best %s  worst %s"
              % (row, label, len(matched), len(items),
                 ("%7.1f" % min(scored)) if scored else "   n/a",
                 ("%7.1f" % max(scored)) if scored else "   n/a"))
        for bbox, scope, b in items:
            if b is None:
                print("    %-22s no size-plausible candidate" % (str(bbox),))
            else:
                print("    %-22s sad %6.1f  iou %.3f  %-9s %-22s flip%d  [%s]"
                      % (str(bbox), b[0], b[1], b[2], str(b[3]), b[4], scope))

    sbs = side_by_side(png, frames, results, os.path.join(args.out_dir, "worldchara_sbs.png"))
    print("\nside-by-side (shipped left, best match right): %s" % sbs)

    # Inspection only: the 2x sheet's own pixels averaged back down to 1x.
    # NOT original art -- these colours are recovered averages of a lossy
    # 2x asset, not palette entries -- so nothing in the pipeline ships it.
    one = np.zeros((png.shape[0] // 2, png.shape[1] // 2, 4), dtype=np.uint8)
    rgb, m = box1x(png_f, (0, 0, png.shape[1], png.shape[0]))
    one[:, :, :3] = rgb
    one[:, :, 3] = np.where(m, 255, 0)
    one_path = os.path.join(args.out_dir, "worldchara_1x.png")
    Image.fromarray(one, "RGBA").save(one_path)
    print("box-downsampled 1x (inspection only, NOT original art): %s" % one_path)

    print("\n%d/%d sprites matched at the %.1f threshold."
          % (total_matched, len(frames), SCORE_THRESHOLD))
    if total_matched == 0:
        print("VERDICT: NO 1X SOURCE. The overworld party sprites are not the field\n"
              "frames and are not in the BMP corpus; worldChara.png cannot be rebuilt\n"
              "from original art. See REPORT.md section 6.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
