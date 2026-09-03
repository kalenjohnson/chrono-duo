import ndspy.rom, ndspy.lz10
from decode_map import render_map
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
def getfile(p):
    return rom.files[rom.filenames.idOf(p)]
ncg = ndspy.lz10.decompress(getfile("minimap/bg/kodai_ncg.bin"))
ncl = getfile("minimap/bg/kodai_ncl.bin")
nsc = getfile("minimap/bg/kodai_nsc.bin")
img = render_map(ncg, ncl, nsc, transparent0=False)
img.convert("RGB").save("out/era_kodai_opaque.png")
