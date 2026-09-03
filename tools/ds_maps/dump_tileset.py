import ndspy.rom, ndspy.lz10
from decode_map import decode_ncg, decode_ncl
from PIL import Image

rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
def getfile(p):
    return rom.files[rom.filenames.idOf(p)]

ncg = ndspy.lz10.decompress(getfile("minimap/bg/gendai_ncg.bin"))
ncl = getfile("minimap/bg/gendai_ncl.bin")
tiles, bpp = decode_ncg(ncg)
colors = decode_ncl(ncl)
cols = 16
rows = (len(tiles)+cols-1)//cols
img = Image.new("RGB",(cols*8,rows*8),(255,0,255))
px = img.load()
for i,t in enumerate(tiles):
    tx = (i%cols)*8
    ty = (i//cols)*8
    for y in range(8):
        for x in range(8):
            ci = t[y][x]
            if ci < len(colors):
                px[tx+x,ty+y] = colors[ci]
img.save("out/gendai_tileset.png")
print(len(tiles), bpp)
