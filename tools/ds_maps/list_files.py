import ndspy.rom
import sys

rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")

def walk(folder, path, out):
    for name, sub in folder.folders:
        walk(sub, path + name + "/", out)
    for i, name in enumerate(folder.files):
        fid = folder.firstID + i
        data = rom.files[fid]
        out.append((path + name, fid, len(data)))

out = []
walk(rom.filenames, "", out)
out.sort()
with open("files.txt", "w") as f:
    for path, fid, size in out:
        f.write(f"{fid}\t{size}\t{path}\n")
print(f"Total files: {len(out)}")
