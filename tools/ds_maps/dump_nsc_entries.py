import ndspy.rom
from decode_map import decode_nsc
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
def getfile(p):
    return rom.files[rom.filenames.idOf(p)]
nsc = getfile("minimap/bg/gendai_nsc.bin")
w,h,flag,entries = decode_nsc(nsc)
print("w,h,flag,count", w,h,flag,len(entries))
for y in range(h):
    row = entries[y*w:(y+1)*w]
    print(' '.join(f'{v:04x}' for v in row))
