package com.kalenjohnson.chronoduo.dsimport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the per-room minimap crop-rect tables and turns them into
 * pixel-space calibration entries, matching gen_calib.py's main() loop
 * exactly (v4 model, see Calib.java): one top-level entry per minimap
 * file ID (keyed
 * "%03d" from Table 1's map_file_id, NOT per floor-variant), with
 * multi-floor rooms carrying a nested floors[] list, and single-floor
 * file-ID collisions resolved by keeping the lowest room_id (later
 * collisions recorded, not overwritten).
 *
 * Table 1 -- per room, 8 bytes/entry, ARM9 overlay 16 @ 0x0219efb4, N=0..668:
 *   +2 (u16) minimap file ID ("%03d" in the filename)
 *   +4 (u8)  cumulative start index into Table 2 for this room's floors
 *   +5 (u8)  floor-variant count (0 = single floor, no _1/_2 suffix)
 *   +6 (u16) background-tile streaming budget (NOT a scale)
 *
 * Table 2 -- per floor-variant, 12 bytes/entry, overlay 16 @ 0x0219e8c4:
 *   +0..+3 (4xu8) tile rect X0,Y0,X1,Y1 (unverified for this table, by
 *          analogy with Table 3)
 *   +6,+8 (2xu16) floor-suffix numbers for the filename (_1, _2, ...)
 *
 * Table 3 -- per room, 20 bytes/entry, ARM9 main code @ 0x02059e04:
 *   +0x10 (u32 LE, read as 4 bytes b0..b3) tile rect X0,Y0,X1,Y1, used for
 *          rooms with Table1.floorCount == 0. If bit 0x80 of b0 is set, the
 *          room has no crop rect (renders 1:1 at 8px/tile instead).
 */
public final class RoomTable {

    private RoomTable() {}

    public static final int T1_BASE = 0x0219efb4;
    public static final int T1_COUNT = 669;
    public static final int T2_BASE = 0x0219e8c4;
    public static final int T3_BASE = 0x02059e04;
    public static final int OVERLAY_ID = 16;

    public static final class FloorEntry {
        public double sx, sy, ox, oy;
        public int x0, y0, x1, y1;
        public int suffix;
        public int floorLocalIndex;
    }

    public static final class CalibEntry {
        public String key;
        public double sx, sy, ox, oy;
        public int x0, y0, x1, y1;
        public String source;
        public int roomId;
        public List<FloorEntry> floors; // null for single-floor rooms
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
     * from "%03d" file-ID key to one CalibEntry (with a nested floors[]
     * list for multi-floor rooms), iterating room IDs 0..668 in order.
     *
     * Rooms whose table entries fall outside the given ARM9/overlay
     * buffers are skipped (reported via skippedRoomIds, if non-null)
     * rather than thrown -- per REPORT.md, a skip here against a correctly
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
                String key = String.format("%03d", mapFileId);

                if (floorCnt == 0) {
                    int t3 = T3_BASE + roomId * 20;
                    long f10 = u32(arm9Section0, arm9Base, t3 + 0x10);
                    int b0 = (int) (f10 & 0xFF);
                    int b1 = (int) ((f10 >>> 8) & 0xFF);
                    int b2 = (int) ((f10 >>> 16) & 0xFF);
                    int b3 = (int) ((f10 >>> 24) & 0xFF);

                    CalibEntry e = new CalibEntry();
                    e.key = key;
                    e.roomId = roomId;

                    if ((b0 & 0x80) != 0) {
                        e.x0 = 0; e.y0 = 0; e.x1 = 31; e.y1 = 23;
                        e.sx = e.sy = Calib.NATIVE_SCALE;
                        e.ox = e.oy = 0.0;
                        e.source = "table3_full_canvas";
                    } else {
                        e.x0 = b0; e.y0 = b1; e.x1 = b2; e.y1 = b3;
                        Calib.Result c = Calib.fromRect(b0, b1, b2, b3);
                        e.sx = c.sx; e.sy = c.sy; e.ox = c.ox; e.oy = c.oy;
                        e.source = "table3";
                    }

                    if (result.containsKey(key)) {
                        // Same minimap file ID reused by multiple distinct
                        // rooms with DIFFERENT crop rects. Deterministically
                        // keep the lowest room_id's transform (don't overwrite).
                        continue;
                    }
                    result.put(key, e);
                } else {
                    List<FloorEntry> floors = new ArrayList<>(floorCnt);
                    for (int local = 0; local < floorCnt; local++) {
                        int t2 = T2_BASE + (floorOff + local) * 12;
                        int x0 = u8(overlay16Data, overlay16Base, t2);
                        int y0 = u8(overlay16Data, overlay16Base, t2 + 1);
                        int x1 = u8(overlay16Data, overlay16Base, t2 + 2);
                        int y1 = u8(overlay16Data, overlay16Base, t2 + 3);
                        int suffix = u16(overlay16Data, overlay16Base, t2 + 6);

                        FloorEntry fe = new FloorEntry();
                        fe.x0 = x0; fe.y0 = y0; fe.x1 = x1; fe.y1 = y1;
                        Calib.Result c = Calib.fromRect(x0, y0, x1, y1);
                        fe.sx = c.sx; fe.sy = c.sy; fe.ox = c.ox; fe.oy = c.oy;
                        fe.suffix = suffix;
                        fe.floorLocalIndex = local;
                        floors.add(fe);
                    }

                    // Top-level entry = first floor variant's rect/transform,
                    // plus the full floors[] list. Unconditional overwrite,
                    // same as the Python (no conflict tracking on this path).
                    FloorEntry first = floors.get(0);
                    CalibEntry e = new CalibEntry();
                    e.key = key;
                    e.roomId = roomId;
                    e.x0 = first.x0; e.y0 = first.y0; e.x1 = first.x1; e.y1 = first.y1;
                    e.sx = first.sx; e.sy = first.sy; e.ox = first.ox; e.oy = first.oy;
                    e.source = "table2_unverified";
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
