#!/usr/bin/env python3
"""Rebuild a 2x smoothed PNG sprite atlas using original 1x indexed BMP art,
pixel-doubled, matched frame-by-frame. Generalises to any (png, bmp, out) pair
of the port's chara sheets.

Usage: python3 rebuild_sheet.py <png> <bmp> <out_png> [--report report.md]
"""
import sys, argparse
import numpy as np
from PIL import Image

MAX_FRAME_2X = 96  # per spec: components bigger than this in 2x space are "implausible"


def dilate(mask, iters=1):
    m = mask.copy()
    for _ in range(iters):
        p = np.pad(m, 1)
        m = p[1:-1, 1:-1] | p[:-2, 1:-1] | p[2:, 1:-1] | p[1:-1, :-2] | p[1:-1, 2:]
    return m


def label_components(mask):
    """4-connectivity flood fill labeling, pure numpy/BFS (no scipy)."""
    h, w = mask.shape
    labels = np.zeros((h, w), dtype=np.int32)
    cur = 0
    ys, xs = np.nonzero(mask)
    coords = set(zip(ys.tolist(), xs.tolist()))
    visited = np.zeros((h, w), dtype=bool)
    comps = []
    for y0, x0 in zip(ys.tolist(), xs.tolist()):
        if visited[y0, x0]:
            continue
        cur += 1
        stack = [(y0, x0)]
        visited[y0, x0] = True
        pix = []
        while stack:
            y, x = stack.pop()
            pix.append((y, x))
            labels[y, x] = cur
            for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                ny, nx = y + dy, x + dx
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not visited[ny, nx]:
                    visited[ny, nx] = True
                    stack.append((ny, nx))
        pix = np.array(pix)
        y_min, y_max = pix[:, 0].min(), pix[:, 0].max()
        x_min, x_max = pix[:, 1].min(), pix[:, 1].max()
        comps.append(dict(label=cur, bbox=(int(x_min), int(y_min), int(x_max) + 1, int(y_max) + 1)))
    return labels, comps


def restrict_bbox_to_mask(mask, dilated_labels, label_id, orig_mask):
    """bbox of the ORIGINAL (undilated) mask pixels sharing this dilated label."""
    sel = (dilated_labels == label_id) & orig_mask
    ys, xs = np.nonzero(sel)
    if len(ys) == 0:
        return None
    return (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)


def segment_png(png_rgba, dilate_px=0):
    a = png_rgba[:, :, 3] > 0
    dil = dilate(a, dilate_px) if dilate_px else a
    labels, comps = label_components(dil)
    frames = []
    oversized = []
    for c in comps:
        bb = restrict_bbox_to_mask(a, labels, c["label"], a)
        if bb is None:
            continue
        x0, y0, x1, y1 = bb
        w, h = x1 - x0, y1 - y0
        if w > MAX_FRAME_2X or h > MAX_FRAME_2X:
            oversized.append(bb)
        frames.append(bb)
    return frames, oversized


def guess_bg_index(bmp_idx):
    """Most common index at the four corners + border, used as background."""
    h, w = bmp_idx.shape
    border = np.concatenate([
        bmp_idx[0, :], bmp_idx[-1, :], bmp_idx[:, 0], bmp_idx[:, -1]
    ])
    vals, counts = np.unique(border, return_counts=True)
    return int(vals[np.argmax(counts)])


def segment_bmp(bmp_idx, bg_index, dilate_px=0):
    a = bmp_idx != bg_index
    dil = dilate(a, dilate_px) if dilate_px else a
    labels, comps = label_components(dil)
    frames = []
    for c in comps:
        bb = restrict_bbox_to_mask(a, labels, c["label"], a)
        if bb is None:
            continue
        frames.append(bb)
    return frames


def masked_sad(cand_rgb, cand_mask, tgt_rgb, tgt_mask):
    """Sum of abs diff over pixels where either mask is set; mismatched
    mask pixels penalised. Returns (score, overlap_pixels)."""
    both = cand_mask | tgt_mask
    n = both.sum()
    if n == 0:
        return 1e18, 0
    diff = np.abs(cand_rgb.astype(np.int32) - tgt_rgb.astype(np.int32)).sum(axis=2)
    diff = np.where(cand_mask & tgt_mask, diff, 255)  # penalty where masks disagree
    score = diff[both].sum() / n
    return score, int(n)


def fit_to_shape(rgb, mask, h, w):
    """Center-crop or pad rgb/mask (H0,W0,[3]) to exactly (h,w)."""
    h0, w0 = mask.shape
    out_rgb = np.zeros((h, w, 3), dtype=np.uint8)
    out_mask = np.zeros((h, w), dtype=bool)
    y_off = (h - h0) // 2
    x_off = (w - w0) // 2
    sy0, sy1 = max(0, -y_off), min(h0, h - y_off)
    sx0, sx1 = max(0, -x_off), min(w0, w - x_off)
    dy0, dx0 = y_off + sy0, x_off + sx0
    dy1, dx1 = y_off + sy1, x_off + sx1
    if sy1 > sy0 and sx1 > sx0:
        out_rgb[dy0:dy1, dx0:dx1] = rgb[sy0:sy1, sx0:sx1]
        out_mask[dy0:dy1, dx0:dx1] = mask[sy0:sy1, sx0:sx1]
    return out_rgb, out_mask


def downsample2x(png_rgb, png_mask, bbox, mode="topleft"):
    x0, y0, x1, y1 = bbox
    w, h = x1 - x0, y1 - y0
    w1, h1 = (w + 1) // 2, (h + 1) // 2
    sub_rgb = png_rgb[y0:y1, x0:x1]
    sub_mask = png_mask[y0:y1, x0:x1]
    # pad to even
    ph, pw = h1 * 2, w1 * 2
    pr = np.zeros((ph, pw, 3), dtype=np.uint8)
    pm = np.zeros((ph, pw), dtype=bool)
    pr[:h, :w] = sub_rgb
    pm[:h, :w] = sub_mask
    if mode == "topleft":
        d_rgb = pr[0::2, 0::2]
        d_mask = pm[0::2, 0::2]
    else:  # majority/median over 2x2
        blocks_rgb = pr.reshape(h1, 2, w1, 2, 3)
        blocks_mask = pm.reshape(h1, 2, w1, 2)
        d_mask = blocks_mask.sum(axis=(1, 3)) >= 2
        d_rgb = np.median(blocks_rgb.reshape(h1, 2, w1, 2, 3), axis=(1, 3)).astype(np.uint8)
    return d_rgb, d_mask


def rebuild(png_path, bmp_path, out_path, report_lines=None, tag=""):
    png = Image.open(png_path).convert("RGBA")
    png_arr = np.array(png)
    png_rgb, png_a = png_arr[:, :, :3], png_arr[:, :, 3]
    png_mask = png_a > 0

    bmp = Image.open(bmp_path)
    bmp_idx = np.array(bmp)
    pal = np.array(bmp.getpalette(), dtype=np.uint8).reshape(-1, 3)
    bg_index = guess_bg_index(bmp_idx)
    bmp_rgb_full = pal[bmp_idx]

    png_frames, oversized = segment_png(png_arr)
    bmp_frames = segment_bmp(bmp_idx, bg_index)

    # precompute bmp component rgb/mask crops (+flipped versions)
    bmp_crops = []
    for bb in bmp_frames:
        x0, y0, x1, y1 = bb
        rgb = bmp_rgb_full[y0:y1, x0:x1]
        mask = bmp_idx[y0:y1, x0:x1] != bg_index
        bmp_crops.append((bb, rgb, mask, np.fliplr(rgb), np.fliplr(mask)))

    out_rgba = np.zeros((png_arr.shape[0], png_arr.shape[1], 4), dtype=np.uint8)
    matched, unmatched = 0, 0
    scores = []
    SCORE_THRESHOLD = 60.0  # mean per-pixel channel-sum SAD; tuned empirically

    for bb in png_frames:
        x0, y0, x1, y1 = bb
        w, h = x1 - x0, y1 - y0
        cand_rgb, cand_mask = downsample2x(png_rgb, png_mask, bb, "topleft")
        best = None
        for (bbb, rgb, mask, rgb_f, mask_f) in bmp_crops:
            bh, bw = mask.shape
            if abs(bw - cand_mask.shape[1]) > 2 or abs(bh - cand_mask.shape[0]) > 2:
                continue
            for flip, r, m in ((False, rgb, mask), (True, rgb_f, mask_f)):
                fh, fw = max(bh, cand_mask.shape[0]), max(bw, cand_mask.shape[1])
                r2, m2 = fit_to_shape(r, m, fh, fw)
                c2, cm2 = fit_to_shape(cand_rgb, cand_mask, fh, fw)
                score, n = masked_sad(c2, cm2, r2, m2)
                if best is None or score < best[0]:
                    best = (score, flip, r, m)
        if best is not None and best[0] <= SCORE_THRESHOLD:
            score, flip, r, m = best
            bh, bw = m.shape
            doubled_rgb = np.repeat(np.repeat(r, 2, axis=0), 2, axis=1)
            doubled_mask = np.repeat(np.repeat(m, 2, axis=0), 2, axis=1)
            dh, dw = doubled_mask.shape
            yo = y0 + (h - dh) // 2
            xo = x0 + (w - dw) // 2
            dy0, dx0 = max(0, yo), max(0, xo)
            dy1, dx1 = min(out_rgba.shape[0], yo + dh), min(out_rgba.shape[1], xo + dw)
            sy0, sx0 = dy0 - yo, dx0 - xo
            sy1, sx1 = sy0 + (dy1 - dy0), sx0 + (dx1 - dx0)
            region_rgb = doubled_rgb[sy0:sy1, sx0:sx1]
            region_mask = doubled_mask[sy0:sy1, sx0:sx1]
            out_rgba[dy0:dy1, dx0:dx1, :3] = np.where(region_mask[..., None], region_rgb, out_rgba[dy0:dy1, dx0:dx1, :3])
            out_rgba[dy0:dy1, dx0:dx1, 3] = np.where(region_mask, 255, out_rgba[dy0:dy1, dx0:dx1, 3])
            matched += 1
            scores.append(score)
        else:
            # fallback: paste original smoothed png pixels for this frame
            out_rgba[y0:y1, x0:x1] = png_arr[y0:y1, x0:x1]
            unmatched += 1
            if report_lines is not None:
                report_lines.append(f"  - UNMATCHED {tag} png frame {bb} (best score {best[0]:.1f} if any)" if best else f"  - UNMATCHED {tag} png frame {bb} (no size-plausible bmp candidate)")

    Image.fromarray(out_rgba, "RGBA").save(out_path)
    return dict(
        png_frames=len(png_frames), bmp_frames=len(bmp_frames), oversized=oversized,
        matched=matched, unmatched=unmatched, scores=scores, bg_index=bg_index,
    )


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("png")
    ap.add_argument("bmp")
    ap.add_argument("out")
    args = ap.parse_args()
    stats = rebuild(args.png, args.bmp, args.out)
    print(stats)
