#!/usr/bin/env python3
"""
Generate a per-room pixel-calibration table for the Chrono Trigger DS
bottom-screen area minimaps (256x192 PNGs rendered from
menu/bg/minimap_<ID>_{ncg,ncl,nsc}.bin).

Formula used by consumers:  px = ox + sx*tileX ; py = oy + sy*tileY

===========================================================================
MODEL (v4 -- two fixed-size boxes, non-ISO 8/7 vertical stretch)
===========================================================================

v3 (single scale ladder {1,2,4,8,16}, one box picked per-room by "largest
value that fits either box", ISO sx=sy, one fixed image centre) was
disproved by a live test on a 16x16-tile room: the table said sx=8, but
the room is drawn ~64px wide (4px/tile). Every [0,0,15,15]-style room's
drawn bbox is 64px, 32-tile rooms draw ~61-64px (2px/tile), 37-tile
rooms draw ~67px (2px/tile) -- i.e. scale genuinely never exceeds 4 for
"small" rooms; v3's 8/16 ladder steps were never real (the two-box
"pick whichever fits, allowing 8" logic let inappropriately-large steps
through for anything <=16 tiles wide, since 16*8=128 <= v3's 130 box).

Two more live-landmark calibrations pinned the rest down:
  - Room 434 (rect [0,0,15,13], mobile-port landmarks: left/right walls
    at tile x=2.5/13.5 -> px 103/152; counter/exit at tile y=4.5/11.6 ->
    px 83/120): sx=4.0, ox=95.5, sy=4.571428.. (=4*8/7), oy=66.2.
  - Room 5 refit with the same vertical factor: sy=4*8/7=4.571428..,
    oy=-65, sx=4.0, ox=30 (north-stairs tile y=23 -> px 40 checks out:
    -65 + 23*32/7 = 40.1).

Both rooms fit sy = sx * 8/7 EXACTLY (not an empirical per-room ratio
like v2 assumed) -- a genuine, GLOBAL non-square pixel stretch applied
to every room's Y axis, on top of an otherwise ISO ladder scale `s`
(sx = s, sy = s*8/7). Re-deriving the ladder+box rule with this
correction (`KY = 8/7`, ladder-fit check done in plain tile units, KY
applied only when producing the final sy, not to the box-fit test)
against the same ~375-room PNG-bbox sample used for v3 gives a MUCH
better fit than any single-box or unconditional-ladder attempt:

    SMALL_BOX = 130 x 130 (square)          ladder {4, 2}
    LARGE_BOX = 194 x 135 (room 5's own box) ladder {4, 2, 1}
    KY = 8/7

    s_small(tw,th) = largest s in {4,2} with tw*s<=130 and th*s<=130 (or None)
    s_large(tw,th) = largest s in {4,2,1} with tw*s<=194 and th*s<=135
    s = s_small if (s_small is not None and s_small >= s_large) else s_large

The critical fix vs. v3: **pick whichever box gives the larger valid
scale** (not "try small first, only fall back to large if small totally
fails" -- that was tried and left ~250 violations, since e.g. a 48-wide
room always satisfies the small box at s=2 and never gets a chance to
try s=4 in the large box). With this "max across both boxes" rule and a
capped {4,2}/{4,2,1} ladder (never 8 or 16), predicted content-rect
edges (using KY on the Y axis) are an upper bound on the measured PNG
bbox for the entire sample: **median excess 0px, mean 0.69px, only 3/374
rooms exceed by more than 6px** -- room 021 (independently known to be
ARM-code special-cased, dynamic content dispatch around overlay 16's
0x2199930), room 580 (a tiny, likely-anomalous room already flagged in
v3), and room 231 (tw=51, just outside LARGE_BOX's width threshold --
the roughest remaining edge case).

Placement is centred, per class, on a FIXED point (not the raw 256x192
canvas centre -- see caveat below):

    SMALL_CENTER = (127.5, 98.2)   -- fit directly from room 434
    LARGE_CENTER = (126.0, 88.142857..)  -- fit directly from room 5

    ox = center_x - (X0+X1+1)/2 * sx    (note: +1, using INCLUSIVE
    oy = center_y - (Y0+Y1+1)/2 * sy     rect-tile-count centring, i.e.
                                          the true pixel-space midpoint
                                          of tiles X0..X1 inclusive)

IMPORTANT: the DS centres the RECT, not the drawn content. Room 434's
own rect-centre formula gives cy=98.2, but its PNG content bbox itself
is centred at only y=94.0 -- a real ~4px gap, because this room's drawn
walls have asymmetric empty space (more room above the counter than
below the exit) inside its own rect. Averaging many rooms' raw bbox
centres (as v3 did) is therefore the WRONG signal -- it mixes genuine
box-centre information with per-room content asymmetry noise. SMALL_
CENTER/LARGE_CENTER are fit directly from the two live-verified rooms
instead, once class+scale is otherwise pinned down by the bbox sample.

**Room 5 is no longer a hard-coded exception.** Because SMALL_CENTER
and LARGE_CENTER above were fit directly from rooms 434 and 5, the
general rule reproduces both exactly by construction (0px residual) --
there is nothing left for `rect_to_calib()`'s caller to override.

*** CONFIDENCE CAVEAT ***: the scale/box/KY rule is strongly verified
(median 0px excess over 374 rooms). LARGE_CENTER rests on a single
calibrated room (5); SMALL_CENTER rests on a single calibrated room
(434) -- a second live landmark in a different small room, and one in a
different large room, would be the next useful check. Room 423 remains
an unexplained outlier from earlier investigation: several other 32x32
-tile rooms measure scale 4 (matching this model's prediction), but 423
itself was observed drawn at only ~2x, and its ARM9 Table 3 byte offset
+14 (0x88) is uniquely different from its same-size peers (all 0x00 or
0x04) -- a plausible per-room override flag that was not conclusively
decoded this session. This model does NOT special-case 423; treat its
predicted entry as unverified.

===========================================================================
DATA SOURCES (read directly from the ROM at generation time)
===========================================================================

  Table 1 -- per room, 8 bytes/entry, overlay 16 @ 0x0219efb4, N=0..668:
      +2 (u16) minimap file ID ("%03d" in the filename)
      +4 (u8)  floor-variant start index into Table 2
      +5 (u8)  floor-variant count (0 = single floor, no _1/_2 suffix)
      +6 (u16) background-tile streaming budget (NOT a scale -- ruled
               out by cross-reference to the VRAM-upload progress loop)

  Table 2 -- per floor-variant, 12 bytes/entry, overlay 16 @ 0x0219e8c4,
             indexed by Table1.floorStartIndex + local floor index:
      +0..+3 (4xu8) tile rect, X0,Y0,X1,Y1 (by analogy with Table 3;
               UNVERIFIED for this table specifically -- no calibrated
               multi-floor room exists)
      +6,+8 (2xu16) floor-suffix numbers for the filename (_1, _2, ...)

  Table 3 -- per room, 20 bytes/entry, ARM9 main code @ 0x02059e04,
             looked up via a tiny helper (called from overlay 16) at
             0x0204ba88, which is just `return base + roomID*20`:
      +0x10 (u32, packed LE bytes b0..b3) tile rect X0,Y0,X1,Y1, used
               for rooms with Table1.floorCount == 0 (the majority).
               If bit 0x80 of the low byte (b0) is set, the room has NO
               crop rect (renders 1:1 at the native 8px/tile scale
               instead) -- modeled here as rect (0,0,31,23), the full
               32x24-tile canvas.

Run:
    python3 gen_calib.py [path/to/rom.nds] [out.json]
Defaults:
    ROM:  ../../Chrono Trigger (USA) (En,Fr).nds (relative to this file)
    out:  ../../ds_maps_out/area_calib.json
"""
import json
import os
import struct
import sys

import ndspy.rom  # pip install ndspy

# ---- two fixed boxes + capped ladders + per-class fixed centre -------
# (see model writeup above)
SMALL_LADDER = [4, 2]
SMALL_BOX_W = 130.0
SMALL_BOX_H = 130.0
SMALL_CENTER_X = 127.5
SMALL_CENTER_Y = 98.2

LARGE_LADDER = [4, 2, 1]
LARGE_BOX_W = 194.0
LARGE_BOX_H = 135.0
LARGE_CENTER_X = 126.0
LARGE_CENTER_Y = 88.142857142857

KY = 8.0 / 7.0  # global Y-axis stretch: sy = sx * KY

NATIVE_SCALE = 8.0  # confirmed px/tile for the "no crop" (full canvas) case

T1_BASE = 0x0219efb4      # overlay 16, per-room, 8 bytes/entry
T1_COUNT = 669             # confirmed valid range 0..668
T2_BASE = 0x0219e8c4       # overlay 16, per-floor-variant, 12 bytes/entry
T3_BASE = 0x02059e04       # ARM9 main code, per-room, 20 bytes/entry
OVERLAY_ID = 16


def load_binaries(rom_path):
    rom = ndspy.rom.NintendoDSRom.fromFile(rom_path)
    ov = rom.loadArm9Overlays()[OVERLAY_ID]
    ov_data = ov.data
    ov_base = ov.ramAddress
    a9 = rom.loadArm9()
    main_section = a9.sections[0]
    main_data = main_section.data
    main_base = main_section.ramAddress
    return (ov_data, ov_base), (main_data, main_base)


def reader(blob, base):
    def rd8(addr):
        return blob[addr - base]

    def rd16(addr):
        return struct.unpack_from("<H", blob, addr - base)[0]

    def rd32(addr):
        return struct.unpack_from("<I", blob, addr - base)[0]

    return rd8, rd16, rd32


def _best_fit(tw, th, ladder, box_w, box_h):
    """Largest ladder value that fits tw x th into box_w x box_h, or None."""
    for cand in ladder:
        if tw * cand <= box_w and th * cand <= box_h:
            return cand
    return None


def classify(tw, th):
    """Picks whichever box (small vs. large) gives the LARGER valid scale
    -- not "try small first, fall back to large only if small totally
    fails" (that was tried and left ~250/374 violations, since a 48-wide
    room always satisfies the small box at s=2 and never gets a chance
    to try s=4 in the large box). Ties favour "small". Returns
    (box_name, scale)."""
    s_small = _best_fit(tw, th, SMALL_LADDER, SMALL_BOX_W, SMALL_BOX_H)
    s_large = _best_fit(tw, th, LARGE_LADDER, LARGE_BOX_W, LARGE_BOX_H)
    if s_large is None:
        s_large = min(LARGE_LADDER)
    if s_small is not None and s_small >= s_large:
        return "small", s_small
    return "large", s_large


def rect_to_calib(x0, y0, x1, y1):
    """Two-box, capped-ladder, non-ISO model: px = ox + sx*tileX ;
    py = oy + sy*tileY, with sy = sx*KY (global 8/7 vertical stretch) and
    placement centred per-class on a fixed image point."""
    tw = max(1, (x1 - x0) + 1)   # inclusive tile span
    th = max(1, (y1 - y0) + 1)
    box, s = classify(tw, th)
    sx = float(s)
    sy = sx * KY
    if box == "small":
        center_x, center_y = SMALL_CENTER_X, SMALL_CENTER_Y
    else:
        center_x, center_y = LARGE_CENTER_X, LARGE_CENTER_Y
    # Pixel-space midpoint of the inclusive tile range x0..x1 is
    # (x0 + x1 + 1) / 2 (tile x1 occupies [x1, x1+1), not just point x1).
    cx = (x0 + x1 + 1) / 2.0
    cy = (y0 + y1 + 1) / 2.0
    ox = center_x - cx * sx
    oy = center_y - cy * sy
    return sx, sy, ox, oy


def calib_dict(x0, y0, x1, y1, sx, sy, ox, oy):
    return {
        "sx": round(sx, 4), "sy": round(sy, 4),
        "ox": round(ox, 4), "oy": round(oy, 4),
        "rect_tiles": [x0, y0, x1, y1],
    }


def main():
    rom_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(__file__), "..", "..", "Chrono Trigger (USA) (En,Fr).nds"
    )
    out_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(__file__), "..", "..", "ds_maps_out", "area_calib.json"
    )

    (ov_data, ov_base), (m_data, m_base) = load_binaries(rom_path)
    ov_rd8, ov_rd16, _ = reader(ov_data, ov_base)
    m_rd8, m_rd16, m_rd32 = reader(m_data, m_base)

    result = {}
    multi_floor_rooms = []
    conflicts = {}

    for room_id in range(T1_COUNT):
        a = T1_BASE + room_id * 8
        map_file_id = ov_rd16(a + 2)
        floor_off = ov_rd8(a + 4)
        floor_cnt = ov_rd8(a + 5)
        key = f"{map_file_id:03d}"

        if floor_cnt == 0:
            # Table 3: single-floor room, crop rect from ARM9 main code
            t3 = T3_BASE + room_id * 20
            f10 = m_rd32(t3 + 0x10)
            b0 = f10 & 0xFF
            b1 = (f10 >> 8) & 0xFF
            b2 = (f10 >> 16) & 0xFF
            b3 = (f10 >> 24) & 0xFF
            if b0 & 0x80:
                # "no crop" flag -> full 32x24 tile canvas, native 8px/tile
                x0, y0, x1, y1 = 0, 0, 31, 23
                sx = sy = NATIVE_SCALE
                ox = oy = 0.0
                source = "table3_full_canvas"
            else:
                x0, y0, x1, y1 = b0, b1, b2, b3
                sx, sy, ox, oy = rect_to_calib(x0, y0, x1, y1)
                source = "table3"
            entry = calib_dict(x0, y0, x1, y1, sx, sy, ox, oy)
            entry["source"] = source
            entry["room_id"] = room_id
            if key in result:
                # Same minimap file ID reused by multiple distinct rooms
                # with DIFFERENT crop rects -- see CONFLICTING_FILE_IDS
                # in the printed summary. Deterministically keep the
                # lowest room_id's transform; this is a real ambiguity in
                # the ROM data, not a bug -- key by room_id instead of
                # file id if you need per-room correctness.
                conflicts.setdefault(key, [result[key]["room_id"]]).append(room_id)
                continue
            result[key] = entry
        else:
            multi_floor_rooms.append((room_id, floor_off, floor_cnt))
            floors = []
            for local in range(floor_cnt):
                t2 = T2_BASE + (floor_off + local) * 12
                raw = ov_data[t2 - ov_base: t2 - ov_base + 12]
                x0, y0, x1, y1 = raw[0], raw[1], raw[2], raw[3]
                suffix = struct.unpack_from("<H", raw, 6)[0]
                sx, sy, ox, oy = rect_to_calib(x0, y0, x1, y1)
                fentry = calib_dict(x0, y0, x1, y1, sx, sy, ox, oy)
                fentry["suffix"] = suffix
                fentry["floor_local_index"] = local
                floors.append(fentry)

            # Top-level entry = first floor variant's rect/transform, plus
            # the full floors[] list so the app can pick by live position.
            first = floors[0]
            entry = calib_dict(
                *first["rect_tiles"], first["sx"], first["sy"], first["ox"], first["oy"]
            )
            entry["source"] = "table2_unverified"
            entry["room_id"] = room_id
            entry["floors"] = floors
            result[key] = entry

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(result, f, indent=2, sort_keys=True)

    print(f"Wrote {len(result)} entries to {out_path}")
    print(f"{len(multi_floor_rooms)} multi-floor rooms (Table 2 path, unverified rect/scale):")
    for room_id, floor_off, floor_cnt in multi_floor_rooms:
        print(f"  room {room_id}: {floor_cnt} floors starting at table2[{floor_off}]")
    if conflicts:
        print(f"\n{len(conflicts)} minimap file IDs are reused by multiple distinct rooms")
        print("with DIFFERENT crop rects (kept the lowest room_id's transform; see")
        print("comment in the code -- key by room_id for full correctness):")
        for key, rooms in sorted(conflicts.items()):
            print(f"  file {key}: rooms {rooms}")


if __name__ == "__main__":
    main()
