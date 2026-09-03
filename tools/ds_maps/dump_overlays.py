import ndspy.rom
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
ovs = rom.loadArm9Overlays()
for oid, ov in ovs.items():
    with open(f"overlay9_{oid:03d}.bin","wb") as f:
        f.write(ov.data)
    print(oid, len(ov.data))
