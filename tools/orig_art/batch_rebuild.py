#!/usr/bin/env python3
"""Batch-rebuild all Game/chara/{png,bmp}/<name> sheet pairs.

Extracts each pair from resources.bin via ctres.py, runs rebuild_sheet.rebuild(),
writes out/<name>.png, and records per-sheet stats to stats.json (append-friendly,
one JSON object per line -> jsonl). Skips names whose out/<name>.png already exists.
"""
import json, os, subprocess, sys, time, traceback
from multiprocessing import Pool

BASE = "/tmp/claude-1000/-home-kalenj-Work-chrono-trigger/d8c69a5b-a368-4c5e-afee-e08320e6e724/scratchpad/orig_art_all"
RES_DIR = "/tmp/claude-1000/-home-kalenj-Work-chrono-trigger/d8c69a5b-a368-4c5e-afee-e08320e6e724/scratchpad/res"
CTRES = os.path.abspath(os.path.join(RES_DIR, "..", "ctres.py"))
TOOLS = "/home/kalenj/Work/chrono-trigger/tools/orig_art"

sys.path.insert(0, TOOLS)

WORK_PNG = os.path.join(BASE, "work", "png")
WORK_BMP = os.path.join(BASE, "work", "bmp")
OUT_DIR = os.path.join(BASE, "out")
STATS_PATH = os.path.join(BASE, "stats.jsonl")
ERRORS_PATH = os.path.join(BASE, "errors.jsonl")

os.makedirs(WORK_PNG, exist_ok=True)
os.makedirs(WORK_BMP, exist_ok=True)
os.makedirs(OUT_DIR, exist_ok=True)


def extract(entry, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return
    with open(dest, "wb") as f:
        r = subprocess.run(
            ["python3", "../ctres.py", entry],
            cwd=RES_DIR,
            stdout=f,
            stderr=subprocess.PIPE,
        )
    if r.returncode != 0 or os.path.getsize(dest) == 0:
        raise RuntimeError(f"extract failed for {entry}: {r.stderr.decode(errors='replace')[:500]}")


def process_one(name):
    from rebuild_sheet import rebuild  # imported per-worker

    out_path = os.path.join(OUT_DIR, f"{name}.png")
    result = {"name": name}
    if os.path.exists(out_path) and os.path.getsize(out_path) > 0:
        result["skipped"] = True
        return result

    t0 = time.time()
    try:
        png_dest = os.path.join(WORK_PNG, f"{name}.png")
        bmp_dest = os.path.join(WORK_BMP, f"{name}.bmp")
        extract(f"Game/chara/png/{name}.png", png_dest)
        extract(f"Game/chara/bmp/{name}.bmp", bmp_dest)

        stats = rebuild(png_dest, bmp_dest, out_path)
        elapsed = time.time() - t0
        result.update(
            png_frames=stats["png_frames"],
            bmp_frames=stats["bmp_frames"],
            matched=stats["matched"],
            unmatched=stats["unmatched"],
            oversized=len(stats["oversized"]),
            max_score=float(max(stats["scores"])) if stats["scores"] else None,
            mean_score=float(sum(stats["scores"]) / len(stats["scores"])) if stats["scores"] else None,
            bg_index=stats["bg_index"],
            elapsed=elapsed,
            ok=True,
        )
    except Exception as e:
        elapsed = time.time() - t0
        result.update(ok=False, error=str(e), traceback=traceback.format_exc(), elapsed=elapsed)
    return result


def main():
    with open(os.path.join(BASE, "pairs.txt")) as f:
        names = [l.strip() for l in f if l.strip()]

    print(f"{len(names)} pairs to process", file=sys.stderr)
    t_start = time.time()

    results = []
    with Pool(processes=os.cpu_count()) as pool:
        for i, res in enumerate(pool.imap_unordered(process_one, names, chunksize=1)):
            results.append(res)
            tag = "SKIP" if res.get("skipped") else ("OK" if res.get("ok") else "ERR")
            print(f"[{i+1}/{len(names)}] {res['name']}: {tag}", file=sys.stderr)

    total_elapsed = time.time() - t_start

    with open(STATS_PATH, "w") as f:
        for r in results:
            if not r.get("skipped"):
                f.write(json.dumps(r) + "\n")

    errors = [r for r in results if r.get("ok") is False]
    with open(ERRORS_PATH, "w") as f:
        for r in errors:
            f.write(json.dumps(r) + "\n")

    print(f"done: {len(results)} total, {len(errors)} errors, {total_elapsed:.1f}s wall", file=sys.stderr)
    with open(os.path.join(BASE, "runtime.txt"), "w") as f:
        f.write(f"{total_elapsed:.2f}\n")


if __name__ == "__main__":
    main()
