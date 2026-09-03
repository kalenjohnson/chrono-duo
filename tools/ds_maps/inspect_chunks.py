import ndspy.rom
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
def getfile(p):
    return rom.files[rom.filenames.idOf(p)]
d = getfile("minimap/bg/gendai_nsc.bin")
def hd(a,b):
    print(a, d[a:b].hex(' '))
hd(0,16)
hd(200,260)
hd(1060,1110)
hd(1260,1420)
