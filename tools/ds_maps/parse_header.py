import ndspy.rom, ndspy.lz10, struct
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")

def getfile(path):
    fid = rom.filenames.idOf(path)
    return rom.files[fid]

for name in ["menu/bg/minimap_000_ncg.bin","menu/bg/minimap_001_ncg.bin","menu/bg/minimap_002_ncg.bin","minimap/bg/gendai_ncg.bin","minimap/bg/mirai_ncg.bin"]:
    d = getfile(name)
    dec = ndspy.lz10.decompress(d)
    magic = dec[0:4]
    f1, f2, f3, f4 = struct.unpack_from("<IIII", dec, 4)
    print(name, "len", len(dec), "magic", magic, "f1..4", f1,f2,f3,f4)
    payload = len(dec) - 20
    print("  payload", payload, "payload/32", payload/32, "payload/64", payload/64)
