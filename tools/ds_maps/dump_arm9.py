import ndspy.rom
rom = ndspy.rom.NintendoDSRom.fromFile("/home/kalenj/Work/chrono-trigger/Chrono Trigger (USA) (En,Fr).nds")
with open("arm9.bin","wb") as f:
    f.write(rom.arm9)
with open("arm7.bin","wb") as f:
    f.write(rom.arm7)
print(len(rom.arm9), len(rom.arm7))
print("overlay9 count", len(rom.loadArm9Overlays()))
