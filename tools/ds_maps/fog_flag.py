"""Dump the per-room fog-of-war gate (Table 1 +6 / Table 2 +0xA) joined to SNES location names.
Usage: python3 fog_flag.py [snes_locations.txt]   (needs ndspy; ROM path as in gen_calib.py)
See NOTES.md "Dungeon fog-of-war flag".
"""
import ndspy.rom, re, sys, os
rom = ndspy.rom.NintendoDSRom.fromFile(os.path.join(os.path.dirname(__file__), "..", "..", "Chrono Trigger (USA) (En,Fr).nds"))
ov = rom.loadArm9Overlays()[16]; ovd, ovb = ov.data, ov.ramAddress
T1=0x0219efb4
def t1(i): o=T1-ovb+i*8; return ovd[o:o+8]
def u16(b,o): return b[o]|(b[o+1]<<8)
names={}
for line in open(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "snes_locations.txt")):
    m=re.match(r'([0-9A-F]{3}) (.*)',line.strip())
    if m: names[int(m.group(1),16)]=m.group(2)
def nm(i): return names.get(i,'(DS-only)')
# count non-empty 8x8 tiles in the room's NSC
def nsc_count(fid, suffix=0):
    name = 'menu/bg/minimap_%03d%s_nsc.bin' % (fid, ('_%d'%suffix) if suffix else '')
    try: d = rom.getFileByName(name)
    except Exception: return None
    w,h = d[8], d[9]; ents = [u16(d,12+2*k) for k in range(w*h)]
    return sum(1 for e in ents if (e & 0x3ff) != 0)
nz=[];z=[]
for i in range(669):
    a=t1(i); v=u16(a,6)
    (nz if v else z).append(i)
print("nonzero t1+6:", len(nz), " zero:", len(z))
print("\n--- NONZERO (candidate fog dungeons) ---")
for i in nz:
    a=t1(i); v=u16(a,6); fid=u16(a,2); fl=a[5]
    c = nsc_count(fid) if fid and not fl else None
    print("%3d %4d  file=%3d floors=%d tiles=%s  %s"%(i,v,fid,fl,c,nm(i)))
print("\n--- ZERO rooms whose name looks dungeon-ish ---")
kw=re.compile(r'Cave|Forest|Ruins|Lair|Dome|Lab|Peak|Omen|Palace|Fort|Castle Magus|Mt\.|Woe|Desert|Sewer|Factory|Blackbird|Nest|Claw|Canyon|Mtn|Maze|Prison|Denadoro|Manoria|Tyrano|Tunnel|Core',re.I)
for i in z:
    if kw.search(nm(i)): print("%3d  %s"%(i,nm(i)))
