import ndspy.rom, ndspy.lz10
from decode_map import decode_ncg, decode_ncl, decode_nsc
from PIL import Image
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
def getfile(p):
    return rom.files[rom.filenames.idOf(p)]

ncg = ndspy.lz10.decompress(getfile("minimap/bg/gendai_ncg.bin"))
ncl = getfile("minimap/bg/gendai_ncl.bin")
nsc = getfile("minimap/bg/gendai_nsc.bin")
tiles, bpp = decode_ncg(ncg)
colors = decode_ncl(ncl)
w,h,flag,entries = decode_nsc(nsc)
print("w,h",w,h,"ntiles",len(tiles))

img = Image.new("RGB",(w*8,h*8),(255,0,255))
px = img.load()
for ty in range(h):
    for tx in range(w):
        e = entries[ty*w+tx]
        tidx = e & 0xFF
        hflip = (e>>8)&1
        vflip = (e>>9)&1
        if tidx>=len(tiles):
            continue
        t = tiles[tidx]
        for py in range(8):
            sy = 7-py if vflip else py
            for pxo in range(8):
                sx = 7-pxo if hflip else pxo
                ci = t[sy][sx]
                if ci < len(colors):
                    px[tx*8+pxo,ty*8+py]=colors[ci]
img.save("out/era_gendai_altbits.png")
