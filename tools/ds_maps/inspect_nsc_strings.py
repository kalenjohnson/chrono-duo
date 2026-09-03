import ndspy.rom
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
def getfile(p):
    return rom.files[rom.filenames.idOf(p)]
d = getfile("minimap/bg/gendai_nsc.bin")
print(len(d))
# find ascii runs
import re
for m in re.finditer(rb'[ -~]{4,}', d):
    print(m.start(), m.group())
