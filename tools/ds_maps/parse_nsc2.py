import ndspy.rom, struct
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
def getfile(path):
    fid = rom.filenames.idOf(path)
    return rom.files[fid]

for name in ["menu/bg/minimap_000_nsc.bin","menu/bg/minimap_002_nsc.bin","minimap/bg/gendai_nsc.bin","minimap/bg/mirai_nsc.bin"]:
    d = getfile(name)
    magic = d[:4]
    count = struct.unpack_from("<I", d, 4)[0]
    w = d[8]
    h = d[9]
    print(name, "len", len(d), "magic", magic, "count", count, "w", w, "h", h,
          "remaining_after_hdr10", len(d)-10, "count*2", count*2, "w*h*2", w*h*2)
    print(" first 20 bytes hex:", d[:20].hex())
