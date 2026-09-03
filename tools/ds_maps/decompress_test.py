import ndspy.rom, ndspy.lz10
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")

def getfile(path):
    fid = rom.filenames.idOf(path)
    return rom.files[fid]

for name in ["menu/bg/minimap_000_ncg.bin","menu/bg/minimap_001_ncg.bin","minimap/bg/gendai_ncg.bin"]:
    d = getfile(name)
    dec = ndspy.lz10.decompress(d)
    print(name, "compressed", len(d), "-> decompressed", len(dec))
    print(dec[:64].hex())
