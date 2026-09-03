import ndspy.rom
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")

def getfile(path):
    fid = rom.filenames.idOf(path)
    return rom.files[fid]

for name in ["menu/bg/minimap_000_ncg.bin","menu/bg/minimap_000_ncl.bin","menu/bg/minimap_000_nsc.bin",
             "menu/bg/minimap_001_ncg.bin","menu/bg/minimap_002_ncg.bin","menu/bg/minimap_002_nsc.bin",
             "minimap/bg/gendai_ncg.bin","minimap/bg/gendai_ncl.bin","minimap/bg/gendai_nsc.bin"]:
    d = getfile(name)
    print(name, len(d), d[:32].hex())
