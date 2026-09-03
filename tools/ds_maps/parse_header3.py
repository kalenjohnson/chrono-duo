import ndspy.rom, ndspy.lz10, struct
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
def getfile(path):
    fid = rom.filenames.idOf(path)
    return rom.files[fid]

names = ["menu/bg/minimap_000_ncg.bin","menu/bg/minimap_001_ncg.bin","menu/bg/minimap_002_ncg.bin",
         "minimap/bg/gendai_ncg.bin","minimap/bg/mirai_ncg.bin","minimap/bg/kodai_ncg.bin",
         "minimap/bg/genshi_ncg.bin","minimap/bg/tyusei_ncg.bin"]
for name in names:
    d = getfile(name)
    dec = ndspy.lz10.decompress(d)
    count, flag = struct.unpack_from("<HH", dec, 4)
    bpp = 8 if flag else 4
    bytesPerTile = 64 if flag else 32
    expected = 8 + count*bytesPerTile
    print(f"{name}: len={len(dec)} count={count} flag={flag} bpp={bpp} expected={expected} match={expected==len(dec)}")
