#!/usr/bin/env python3
"""
Generate a per-room pixel-calibration table for the Chrono Trigger DS
bottom-screen area minimaps (256x192 PNGs rendered from
menu/bg/minimap_<ID>_{ncg,ncl,nsc}.bin, see render_all.py).

Formula used by consumers:  px = ox + sx*tileX ; py = oy + sy*tileY

===========================================================================
SCHEMA (v5 -- keyed by ROOM id, not minimap file id)
===========================================================================

Earlier versions of this script keyed the output JSON by the minimap
*file* id (the "%03d" in the rendered PNG's name). That silently broke
for any room whose Table 1 entry maps to a file id that isn't the room's
own id -- e.g. Cathedral (room id 129) has no `area_minimap_129.png`;
its content lives under a different file id, and the old file-id-keyed
JSON had no way to represent "room 129 -> file NNN" at all, plus it had
to arbitrarily drop ~76 rooms that shared a file id with another room
(the CONFLICTING_FILE_IDS problem from earlier sessions).

The output is now keyed by decimal ROOM id string (e.g. "129"), which is
always unique (no conflict-resolution logic needed at all -- that whole
code path from the file-id-keyed version is gone). Each entry:

    {
      "file": <minimap file id, int>,   # the "%03d" component of the
                                         # rendered PNG's name
      "sx": .., "sy": .., "ox": .., "oy": ..,   # present for every
                                         # single-floor room (including
                                         # the "no crop rect" case, which
                                         # still gets an identity-ish
                                         # native-scale transform -- see
                                         # caveat below); ABSENT for
                                         # multi-floor rooms (use
                                         # floors[0] as the fallback)
      "rect_tiles": [x0,y0,x1,y1],      # present alongside sx/sy/ox/oy
      "floors": [                       # present ONLY for multi-floor
        {                               # rooms (Table1.floorCount > 0)
          "file": <int>,                # each floor variant can have a
                                         # genuinely different minimap
                                         # file id -- see Table 2 below
          "suffix": <int>,              # 0 = filename has no _N suffix
          "rect_tiles": [x0,y0,x1,y1],
          "sx": .., "sy": .., "ox": .., "oy": ..
        }, ...
      ]
    }

CAVEAT on the "may be absent" wording some callers may expect: only
multi-floor rooms omit the top-level sx/sy/ox/oy (they carry per-floor
transforms instead). The "no crop rect" single-floor case (Table3 b0 bit
0x80) still EMITS a transform (native 8px/tile, ox=oy=0) rather than
omitting one -- dropping it would silently regress marker rendering for
the ~94 rooms that hit this path, which already worked under the old
schema. Only genuinely-multi-floor entries lack a top-level transform.

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

Placement is centred, per class, on a FIXED point (not the raw 256x192
canvas centre):

    SMALL_CENTER = (127.5, 98.2)   -- fit directly from room 434
    LARGE_CENTER = (126.0, 88.142857..)  -- fit directly from room 5

    ox = center_x - (X0+X1+1)/2 * sx    (note: +1, using INCLUSIVE
    oy = center_y - (Y0+Y1+1)/2 * sy     rect-tile-count centring)

*** CONFIDENCE CAVEAT ***: the scale/box/KY rule is strongly verified
(median 0px excess over 374 rooms). LARGE_CENTER/SMALL_CENTER each rest
on a single calibrated room (5 / 434). Room 423 remains an unexplained
outlier (see prior session's marker_transform_report.md) -- not
special-cased here.

===========================================================================
DATA SOURCES (read directly from the ROM at generation time)
===========================================================================

  Table 1 -- per room, 8 bytes/entry, overlay 16 @ 0x0219efb4, N=0..668:
      +2 (u16) minimap file ID for single-floor rooms ("%03d" in the
               filename). For multi-floor rooms this is a sentinel (0)
               -- Table 2 carries the real per-floor file id instead.
      +4 (u8)  floor-variant start index into Table 2
      +5 (u8)  floor-variant count (0 = single floor, no _1/_2 suffix)
      +6 (u16) background-tile streaming budget (NOT a scale -- ruled
               out by cross-reference to the VRAM-upload progress loop)

  Table 2 -- per floor-variant, 12 bytes/entry, overlay 16 @ 0x0219e8c4,
             indexed by Table1.floorStartIndex + local floor index:
      +0..+3 (4xu8) tile rect, X0,Y0,X1,Y1 (by analogy with Table 3;
               UNVERIFIED as a rect specifically for this table -- no
               calibrated multi-floor room exists)
      +6 (u16) minimap FILE id for this floor variant (confirmed against
               real rendered PNGs this session -- e.g. room 47's four
               floors read file id 47 with suffixes 1..4, matching
               area_minimap_047_1.png..area_minimap_047_4.png on disk;
               room 482-485 each cycle through TEN distinct file ids
               370-375/442-445, confirming this field is a real
               per-floor file id, not a constant)
      +8 (u16) filename suffix ("_N"; 0 = no suffix in the filename --
               confirmed against room 28, whose 4 floors all read
               suffix 0 and which renders as a single unsuffixed
               area_minimap_028.png)

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


def transform_dict(x0, y0, x1, y1, sx, sy, ox, oy):
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

    for room_id in range(T1_COUNT):
        a = T1_BASE + room_id * 8
        map_file_id = ov_rd16(a + 2)
        floor_off = ov_rd8(a + 4)
        floor_cnt = ov_rd8(a + 5)
        key = str(room_id)

        if floor_cnt == 0:
            # Table 3: single-floor room, crop rect from ARM9 main code
            t3 = T3_BASE + room_id * 20
            f10 = m_rd32(t3 + 0x10)
            b0 = f10 & 0xFF
            b1 = (f10 >> 8) & 0xFF
            b2 = (f10 >> 16) & 0xFF
            b3 = (f10 >> 24) & 0xFF
            if b0 & 0x80:
                # "no crop" flag -> full 32x24 tile canvas, native 8px/tile.
                # Still emits a transform (not omitted) so the ~94 rooms on
                # this path keep getting a marker, same as before.
                x0, y0, x1, y1 = 0, 0, 31, 23
                sx = sy = NATIVE_SCALE
                ox = oy = 0.0
            else:
                x0, y0, x1, y1 = b0, b1, b2, b3
                sx, sy, ox, oy = rect_to_calib(x0, y0, x1, y1)
            entry = {"file": map_file_id}
            entry.update(transform_dict(x0, y0, x1, y1, sx, sy, ox, oy))
            result[key] = entry
        else:
            multi_floor_rooms.append((room_id, floor_off, floor_cnt))
            floors = []
            for local in range(floor_cnt):
                t2 = T2_BASE + (floor_off + local) * 12
                raw = ov_data[t2 - ov_base: t2 - ov_base + 12]
                x0, y0, x1, y1 = raw[0], raw[1], raw[2], raw[3]
                floor_file_id = struct.unpack_from("<H", raw, 6)[0]
                suffix = struct.unpack_from("<H", raw, 8)[0]
                sx, sy, ox, oy = rect_to_calib(x0, y0, x1, y1)
                fentry = {"file": floor_file_id, "suffix": suffix}
                fentry.update(transform_dict(x0, y0, x1, y1, sx, sy, ox, oy))
                floors.append(fentry)

            # Top-level "file" = the first floor's file id, so a caller
            # that doesn't know the live position yet has a reasonable
            # default (no top-level transform -- floors[] is the only
            # source of truth for multi-floor rooms).
            entry = {"file": floors[0]["file"], "floors": floors}
            result[key] = entry

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(result, f, indent=2, sort_keys=True)

    print(f"Wrote {len(result)} entries to {out_path}")
    print(f"{len(multi_floor_rooms)} multi-floor rooms (Table 2 path, unverified rect):")
    for room_id, floor_off, floor_cnt in multi_floor_rooms:
        print(f"  room {room_id}: {floor_cnt} floors starting at table2[{floor_off}]")


if __name__ == "__main__":
    main()
