import ndspy.rom, ndspy.lz10, struct
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
def getfile(path):
    fid = rom.filenames.idOf(path)
    return rom.files[fid]

for name in ["menu/bg/minimap_000_ncl.bin","minimap/bg/gendai_ncl.bin","menu/bg/minimap_W_ncl.bin"]:
    d = getfile(name)
    print(name, len(d), d[:16].hex())

print("---nsc---")
for name in ["menu/bg/minimap_000_nsc.bin","menu/bg/minimap_002_nsc.bin","minimap/bg/gendai_nsc.bin"]:
    d = getfile(name)
    # nsc not compressed (no lz magic at start since begins with 'N')
    print(name, len(d), d[:24].hex())
