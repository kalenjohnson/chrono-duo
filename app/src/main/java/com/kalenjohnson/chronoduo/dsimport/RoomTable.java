package com.kalenjohnson.chronoduo.dsimport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the per-room minimap crop-rect tables and turns them into
 * pixel-space calibration entries, matching gen_calib.py's main() loop
 * exactly (v4 transform model, see Calib.java; v5 schema, keyed by ROOM
 * id, not minimap file id): one entry per ROOM id (0..668, always unique
 * -- no file-id-collision handling needed, unlike the old file-id-keyed
 * schema), with multi-floor rooms carrying a nested floors[] list instead
 * of a top-level transform.
 *
 * Table 1 -- per room, 8 bytes/entry, ARM9 overlay 16 @ 0x0219efb4, N=0..668:
 *   +2 (u16) minimap file ID for single-floor rooms ("%03d" in the
 *          filename). Sentinel (0) for multi-floor rooms -- Table 2 has
 *          the real per-floor file id there instead.
 *   +4 (u8)  cumulative start index into Table 2 for this room's floors
 *   +5 (u8)  floor-variant count (0 = single floor, no _1/_2 suffix)
 *   +6 (u16) FOG-OF-WAR flag/size: 0 = the minimap is shown in full
 *          (towns, houses, hubs, cutscene rooms); nonzero = explorable
 *          dungeon whose map reveals as the player walks it. 999 is a
 *          sentinel meaning "per floor, see Table 2 +0xA". Verified
 *          2026-09-15 against all 669 names: nonzero is exactly the
 *          dungeon set (Guardia Forest, Prison, Heckran, Denadoro,
 *          Magus's Castle, Labs, Factory, Sewers, Death Peak, Reptite
 *          Lair, Tyrano Lair, Black Omen, Blackbird, Mt. Woe, Ocean
 *          Palace, DS-only Lost Sanctum/Vortex...); zero for every town,
 *          house, Zeal Palace, End of Time, Arris/Keeper's Dome hubs.
 *          The magnitude (30..344) is NOT yet understood (not the NSC
 *          tile count, which is ~468 everywhere) -- treat as a bool.
 *
 * Table 2 -- per floor-variant, 12 bytes/entry, overlay 16 @ 0x0219e8c4:
 *   +0..+3 (4xu8) tile rect X0,Y0,X1,Y1 (unverified for this table, by
 *          analogy with Table 3)
 *   +6 (u16) minimap FILE id for this floor variant (confirmed against
 *          real rendered PNGs -- e.g. room 47's four floors read file id
 *          47 with suffixes 1..4, matching area_minimap_047_1..4.png;
 *          rooms 482-485 each cycle through ten distinct file ids)
 *   +8 (u16) filename suffix ("_N"; 0 = no suffix -- confirmed against
 *          room 28, whose floors all read suffix 0 and render as a
 *          single unsuffixed area_minimap_028.png)
 *   +0xA (u16) per-floor fog-of-war value (same meaning as Table1 +6);
 *          only honoured when Table1 +6 == 999. Room 90 (Prison Catwalks
 *          ending variant) carries nonzero floor values under a Table1
 *          zero and is NOT fogged, so Table1 +6 is the gate.
 *
 * Table 3 -- per room, 20 bytes/entry, ARM9 main code @ 0x02059e04:
 *   +0x10 (u32 LE, read as 4 bytes b0..b3) tile rect X0,Y0,X1,Y1, used for
 *          rooms with Table1.floorCount == 0. If bit 0x80 of b0 is set, the
 *          room has no crop rect (renders 1:1 at 8px/tile instead) -- this
 *          still gets a transform (native scale, ox=oy=0), not an omitted
 *          one, so the ~94 rooms on this path keep getting a marker.
 */
public final class RoomTable {

    private RoomTable() {}

    public static final int T1_BASE = 0x0219efb4;
    public static final int T1_COUNT = 669;
    public static final int T2_BASE = 0x0219e8c4;
    public static final int T3_BASE = 0x02059e04;
    public static final int OVERLAY_ID = 16;

    public static final class FloorEntry {
        public int file;
        public int suffix;
        public double sx, sy, ox, oy;
        public int x0, y0, x1, y1;
        public boolean fog;
    }

    public static final class CalibEntry {
        public String key; // decimal room id, e.g. "129"
        public int roomId;
        public int file;
        // Present only for single-floor rooms (floors == null).
        public boolean hasTransform;
        public double sx, sy, ox, oy;
        public int x0, y0, x1, y1;
        public List<FloorEntry> floors; // null for single-floor rooms
        // Fog-of-war flag; meaningful only for single-floor rooms (floors
        // == null) -- multi-floor rooms carry fog per-floor on FloorEntry
        // instead. See Table1 +6 / Table2 +0xA in the class doc.
        public boolean fog;
    }

    private static int u8(byte[] data, int base, int addr) {
        int off = addr - base;
        if (off < 0 || off >= data.length) {
            throw new IndexOutOfBoundsException("u8 read at 0x" + Integer.toHexString(addr) + " out of range");
        }
        return data[off] & 0xFF;
    }

    private static int u16(byte[] data, int base, int addr) {
        int off = addr - base;
        if (off < 0 || off + 2 > data.length) {
            throw new IndexOutOfBoundsException("u16 read at 0x" + Integer.toHexString(addr) + " out of range");
        }
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] data, int base, int addr) {
        int off = addr - base;
        if (off < 0 || off + 4 > data.length) {
            throw new IndexOutOfBoundsException("u32 read at 0x" + Integer.toHexString(addr) + " out of range");
        }
        return (data[off] & 0xFFL) | ((data[off + 1] & 0xFFL) << 8)
                | ((data[off + 2] & 0xFFL) << 16) | ((data[off + 3] & 0xFFL) << 24);
    }

    /**
     * Builds the calib table exactly as gen_calib.py's main() does: a map
     * from decimal room-id key to one CalibEntry (with a nested floors[]
     * list for multi-floor rooms instead of a top-level transform),
     * iterating room IDs 0..668 in order.
     *
     * Rooms whose table entries fall outside the given ARM9/overlay
     * buffers are skipped (reported via skippedRoomIds, if non-null)
     * rather than thrown -- a skip here against a correctly
     * BLZ-decompressed ARM9/overlay 16 signals a bug upstream, not a
     * legitimately short table.
     */
    public static List<CalibEntry> buildCalibEntries(
            byte[] arm9Section0, int arm9Base,
            byte[] overlay16Data, int overlay16Base,
            List<Integer> skippedRoomIds) {

        Map<String, CalibEntry> result = new LinkedHashMap<>();

        for (int roomId = 0; roomId < T1_COUNT; roomId++) {
            int a = T1_BASE + roomId * 8;
            try {
                int mapFileId = u16(overlay16Data, overlay16Base, a + 2);
                int floorOff = u8(overlay16Data, overlay16Base, a + 4);
                int floorCnt = u8(overlay16Data, overlay16Base, a + 5);
                int t1Fog = u16(overlay16Data, overlay16Base, a + 6);
                String key = Integer.toString(roomId);

                CalibEntry e = new CalibEntry();
                e.key = key;
                e.roomId = roomId;

                if (floorCnt == 0) {
                    int t3 = T3_BASE + roomId * 20;
                    long f10 = u32(arm9Section0, arm9Base, t3 + 0x10);
                    int b0 = (int) (f10 & 0xFF);
                    int b1 = (int) ((f10 >>> 8) & 0xFF);
                    int b2 = (int) ((f10 >>> 16) & 0xFF);
                    int b3 = (int) ((f10 >>> 24) & 0xFF);

                    if ((b0 & 0x80) != 0) {
                        e.x0 = 0; e.y0 = 0; e.x1 = 31; e.y1 = 23;
                        e.sx = e.sy = Calib.NATIVE_SCALE;
                        e.ox = e.oy = 0.0;
                    } else {
                        e.x0 = b0; e.y0 = b1; e.x1 = b2; e.y1 = b3;
                        Calib.Result c = Calib.fromRect(b0, b1, b2, b3);
                        e.sx = c.sx; e.sy = c.sy; e.ox = c.ox; e.oy = c.oy;
                    }
                    e.file = mapFileId;
                    e.hasTransform = true;
                    e.fog = t1Fog != 0;
                    result.put(key, e);
                } else {
                    // Multi-floor fog rule (see class doc Table1 +6 / Table2
                    // +0xA): t1Fog==0 -> every floor unfogged; t1Fog==999 ->
                    // per-floor value at Table2 +0xA gates each floor;
                    // any other nonzero (not observed in the ROM) -> every
                    // floor fogged, defensively.
                    List<FloorEntry> floors = new ArrayList<>(floorCnt);
                    for (int local = 0; local < floorCnt; local++) {
                        int t2 = T2_BASE + (floorOff + local) * 12;
                        int x0 = u8(overlay16Data, overlay16Base, t2);
                        int y0 = u8(overlay16Data, overlay16Base, t2 + 1);
                        int x1 = u8(overlay16Data, overlay16Base, t2 + 2);
                        int y1 = u8(overlay16Data, overlay16Base, t2 + 3);
                        int floorFileId = u16(overlay16Data, overlay16Base, t2 + 6);
                        int suffix = u16(overlay16Data, overlay16Base, t2 + 8);
                        int t2Fog = u16(overlay16Data, overlay16Base, t2 + 0xA);

                        FloorEntry fe = new FloorEntry();
                        fe.x0 = x0; fe.y0 = y0; fe.x1 = x1; fe.y1 = y1;
                        Calib.Result c = Calib.fromRect(x0, y0, x1, y1);
                        fe.sx = c.sx; fe.sy = c.sy; fe.ox = c.ox; fe.oy = c.oy;
                        fe.file = floorFileId;
                        fe.suffix = suffix;
                        if (t1Fog == 0) {
                            fe.fog = false;
                        } else if (t1Fog == 999) {
                            fe.fog = t2Fog != 0;
                        } else {
                            fe.fog = true;
                        }
                        floors.add(fe);
                    }

                    // Top-level "file" = the first floor's file id (a
                    // reasonable default when no live position is known
                    // yet). No top-level transform -- floors[] is the
                    // only source of truth for multi-floor rooms.
                    e.file = floors.get(0).file;
                    e.hasTransform = false;
                    e.floors = floors;
                    result.put(key, e);
                }
            } catch (IndexOutOfBoundsException ex) {
                if (skippedRoomIds != null) skippedRoomIds.add(roomId);
            }
        }

        return new ArrayList<>(result.values());
    }
}
