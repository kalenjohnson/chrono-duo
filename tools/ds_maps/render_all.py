import ndspy.rom, ndspy.lz10, os
from decode_map import render_map

ROM_PATH = "/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds"
OUT = "out"
os.makedirs(OUT, exist_ok=True)

rom = ndspy.rom.NintendoDSRom.fromFile(ROM_PATH)

def getfile(path):
    fid = rom.filenames.idOf(path)
    if fid is None:
        return None
    return rom.files[fid]

def try_render(base, ncg_path, ncl_path, nsc_path, out_name):
    ncg_raw = getfile(ncg_path)
    ncl_raw = getfile(ncl_path)
    nsc_raw = getfile(nsc_path)
    if ncg_raw is None or ncl_raw is None or nsc_raw is None:
        print(f"MISSING for {base}: ncg={ncg_path in [None]} ...")
        return False
    ncg_dec = ndspy.lz10.decompress(ncg_raw)
    try:
        img = render_map(ncg_dec, ncl_raw, nsc_raw)
        img.save(os.path.join(OUT, out_name))
        print(f"OK {out_name}  size={img.size}")
        return True
    except Exception as e:
        print(f"FAIL {out_name}: {e}")
        return False

# ---- Era overworld minimaps ----
eras = ["gendai", "genshi", "kodai", "mirai", "tyusei",
        "gendai_fiona", "genshi_mura", "genshi_thiran", "kodai-rakka",
        "kodai_houkai", "kodai_tenjou", "tyusei_Fiona", "tyusei_Hashi", "tyusei_magan"]
for era in eras:
    try_render(era,
               f"minimap/bg/{era}_ncg.bin",
               f"minimap/bg/{era}_ncl.bin",
               f"minimap/bg/{era}_nsc.bin",
               f"era_{era}.png")

# ---- Dungeon/area minimaps (menu/bg/minimap_XXX) ----
# Palette files only exist for 000, 001, W -- figure out which applies per index
# For now: try each area's own ncl if present, else fall back to 000's palette.
import re
names = set()
for path, folder in [(f, None) for f in []]:
    pass

# gather all minimap_* base names from folder listing
base_names = set()
def walk(folder, path):
    for name, sub in folder.folders:
        walk(sub, path + name + "/")
    for name in folder.files:
        if path == "menu/bg/" and name.startswith("minimap_") and name.endswith("_ncg.bin"):
            base_names.add(name[:-len("_ncg.bin")])

walk(rom.filenames, "")

fallback_ncl = getfile("menu/bg/minimap_000_ncl.bin")

count_ok = 0
count_fail = 0
for base in sorted(base_names):
    ncg_path = f"menu/bg/{base}_ncg.bin"
    ncl_path = f"menu/bg/{base}_ncl.bin"
    nsc_path = f"menu/bg/{base}_nsc.bin"
    ncg_raw = getfile(ncg_path)
    nsc_raw = getfile(nsc_path)
    ncl_raw = getfile(ncl_path)
    if ncl_raw is None:
        ncl_raw = fallback_ncl
    if ncg_raw is None or nsc_raw is None:
        count_fail += 1
        continue
    ncg_dec = ndspy.lz10.decompress(ncg_raw)
    try:
        img = render_map(ncg_dec, ncl_raw, nsc_raw)
        img.save(os.path.join(OUT, f"area_{base}.png"))
        count_ok += 1
    except Exception as e:
        print(f"FAIL area_{base}: {e}")
        count_fail += 1

print(f"Area maps: OK={count_ok} FAIL={count_fail} total={len(base_names)}")
