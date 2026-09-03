import ndspy.rom, ndspy.lz10, struct
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
def getfile(path):
    fid = rom.filenames.idOf(path)
    return rom.files[fid]

d = getfile("minimap/bg/gendai_ncg.bin")
dec = ndspy.lz10.decompress(d)
print(len(dec))
print(dec[:48].hex())
# try count at offset 4 as uint16
c16 = struct.unpack_from("<H", dec, 4)[0]
print("count u16 @4:", c16, "-> *32+? =", c16*32)
