"""Convert an SNES Chrono Trigger .srm slot into a port save (CtSave), by
overlaying SNES data onto a "template" chapter save picked by nearest event
flags. See REPORT.md #5 for the overall design.

Usage:
    python3 convert.py <srm> <slot 0-2> <out.bin> [--template steam/NN.bin]
"""
import argparse
import glob
import os
import struct
import sys

import ctcrypto
import ctsave
import ds_sav
import snes_srm

STEAM_DIR_DEFAULT = "steam"


# --- item id -> (section, idx) mapping (REPORT.md #3.2) -------------------
# idx is the item's position within its category array; each range is
# linear (idx = id - base) including the key-item range 0xD0-0xE7 (idx =
# id - 0xCF for every id in that range; a coordinator correction clarified
# this is NOT a special case for any particular id, contrary to an earlier
# draft of REPORT.md).
_ITEM_RANGES = [
    ("weapons", 0x01, 0x5A, 0x00),
    ("armour", 0x5B, 0x7B, 0x5A),
    ("helmets", 0x7C, 0x94, 0x7B),
    ("accessories", 0x95, 0xBC, 0x94),
    ("consumables", 0xBD, 0xCF, 0xBC),
    ("key_items", 0xD0, 0xE7, 0xCF),
]

# Equipment category nibble used in the port's (category<<12)|idx encoding
# (REPORT.md #4, port +0x2C..+0x33).
_EQUIP_CATEGORY = {"weapons": 0x0, "armour": 0x1, "helmets": 0x2, "accessories": 0x3}


def classify_item(item_id: int):
    """Return (section_name, idx) for an SNES item id, or None if id==0 or
    outside every known range (in which case the caller should skip it)."""
    if item_id == 0:
        return None
    for section, lo, hi, base in _ITEM_RANGES:
        if lo <= item_id <= hi:
            return section, item_id - base
    return None


def equip_field(item_id: int) -> int:
    """SNES equipment item id -> port u16 (category<<12)|idx, or 0 if empty."""
    classified = classify_item(item_id)
    if classified is None:
        return 0
    section, idx = classified
    category = _EQUIP_CATEGORY.get(section)
    if category is None:
        # A key item or consumable id in an equipment slot shouldn't happen.
        return 0
    return (category << 12) | idx


def hamming_distance(a: bytes, b: bytes) -> int:
    assert len(a) == len(b)
    dist = 0
    for x, y in zip(a, b):
        dist += bin(x ^ y).count('1')
    return dist


def pick_template(flags: bytes, steam_dir: str = STEAM_DIR_DEFAULT):
    """Pick the steam/*.bin chapter save whose flags block (payload 0x200..
    0x400) is closest (Hamming distance) to `flags`. Excludes NG+ (X*.bin),
    common.bin and meta.bin. Returns (path, distance)."""
    candidates = sorted(glob.glob(f"{steam_dir}/[0-9]*.bin"))
    best = None
    for path in candidates:
        with open(path, 'rb') as f:
            data = f.read()
        plain = ctcrypto.decrypt(data)
        n = struct.unpack_from('<I', plain, len(plain) - 4)[0]
        payload = plain[:n]
        template_flags = payload[0x200:0x400]
        dist = hamming_distance(flags, template_flags)
        if best is None or dist < best[1]:
            best = (path, dist)
    return best


def snes_to_ct(slot: snes_srm.SnesSlot, template: ctsave.CtSave) -> ctsave.CtSave:
    """Overlay SNES slot data onto a copy of `template` (REPORT.md #5, steps 2-3)."""
    out = ctsave.CtSave.parse(template.serialize())  # deep copy via round-trip

    # Flags: verbatim.
    out.flags = bytearray(slot.flags)

    # Characters.
    for i in range(7):
        sc = slot.chars[i]
        cc = out.chars[i]
        cc.max_hp = sc.max_hp
        cc.cur_hp = sc.cur_hp
        cc.max_mp = sc.max_mp
        cc.cur_mp = sc.cur_mp
        cc.base_max_hp = struct.unpack_from('<H', sc.raw, 0x3F)[0]
        cc.base_power = sc.power
        cc.base_stamina = sc.stamina
        cc.base_speed = sc.speed
        cc.base_magic = sc.magic
        cc.base_hit = sc.hit
        cc.base_evade = sc.evade
        cc.base_mdef = sc.mdef
        cc.level = sc.level
        cc.exp = sc.exp
        cc.tp_next = sc.tp_next  # semantics unclear past ~chapter 7, REPORT.md #6.4
        cc.equip_weapon = equip_field(sc.weapon_id)
        cc.equip_armor = equip_field(sc.armor_id)
        cc.equip_helmet = equip_field(sc.helmet_id)
        cc.equip_accessory = equip_field(sc.accessory_id)
        cc.exp_next = sc.exp_next
        cc.tp_related = sc.field_2d
        cc.growth = bytes([sc.raw[0x2F], sc.raw[0x30], sc.raw[0x32],
                            sc.raw[0x33], sc.raw[0x34], sc.raw[0x35]])
        cc.cur_stats = sc.raw[0x36:0x3F]
        # +01/+02/+03 and +0x47..+0x4A are per-character constants; keep the
        # template's (same character id => same constants).

    # Inventory: re-bin by category.
    buckets = {name: [] for name, _cap, _cat in ctsave.INVENTORY_SECTIONS}
    for item_id, count in slot.items:
        classified = classify_item(item_id)
        if classified is None:
            continue  # unknown/out-of-range id; drop rather than guess
        section, idx = classified
        buckets[section].append((idx, count))

    new_inventory = {}
    for name, capacity, _cat in ctsave.INVENTORY_SECTIONS:
        entries = buckets[name][:capacity]
        entries += [(0, 0)] * (capacity - len(entries))
        new_inventory[name] = entries
    out.inventory = new_inventory

    # Tech block: verbatim 45 bytes.
    out.tech_block = bytearray(slot.tech_block)

    # Names: decoded SNES names for the 8 characters; keep template's extra
    # two trailing name-table entries (purpose unknown, REPORT.md #3.2).
    out.names = list(slot.names) + list(template.names[8:10])

    # Party / reserve / recruited: verbatim.
    out.party = slot.party
    out.reserve = slot.reserve
    out.recruited_mask = slot.recruited_mask

    out.gold = slot.gold
    out.play_time_seconds = slot.play_time_seconds

    return out


def ds_to_ct(slot: "ds_sav.DsSlot", template: ctsave.CtSave) -> ctsave.CtSave:
    """Overlay a DS slot onto a copy of `template` (REPORT.md #7).

    Unlike `snes_to_ct`, the DS struct is positionally the same as the
    port's, so equipment/inventory ids are already in the port's
    `(category<<12)|idx` encoding (no `equip_field`/`classify_item`
    re-mapping needed), HP/MP are already max-first (no swap), and all 10
    names -- plus location-name id and era mask -- come straight from the
    DS slot rather than being left to the template.
    """
    out = ctsave.CtSave.parse(template.serialize())  # deep copy via round-trip

    # Flags: verbatim.
    out.flags = bytearray(slot.flags)

    # Characters.
    for i in range(7):
        dc = slot.chars[i]
        cc = out.chars[i]
        cc.max_hp = dc.max_hp
        cc.cur_hp = dc.cur_hp
        cc.max_mp = dc.max_mp
        cc.cur_mp = dc.cur_mp
        cc.base_max_hp = dc.base_max_hp
        cc.base_power = dc.power
        cc.base_stamina = dc.stamina
        cc.base_speed = dc.speed
        cc.base_magic = dc.magic
        cc.base_hit = dc.hit
        cc.base_evade = dc.evade
        cc.base_mdef = dc.mdef
        cc.level = dc.level
        cc.exp = dc.exp
        cc.tp_next = dc.tp_next
        cc.equip_weapon = dc.equip_weapon
        cc.equip_armor = dc.equip_armor
        cc.equip_helmet = dc.equip_helmet
        cc.equip_accessory = dc.equip_accessory
        cc.exp_next = dc.exp_next
        cc.tp_related = dc.tp_related
        cc.growth = dc.growth
        cc.cur_stats = dc.cur_stats
        cc.per_char_constants = dc.per_char_constants
        # +01/+02/+03 (the "80 86 90 xx" DS constant / port's per-character
        # id bytes) aren't carried across -- same as snes_to_ct, the template's
        # own constants for that character id are kept.

    # Inventory: DS sections already have the same capacity/order/idx
    # numbering as the port, so entries carry straight across.
    new_inventory = {}
    for name, capacity, _cat in ctsave.INVENTORY_SECTIONS:
        entries = list(slot.inventory[name])
        assert len(entries) == capacity, f"{name}: DS capacity {len(entries)} != port capacity {capacity}"
        new_inventory[name] = entries
    out.inventory = new_inventory

    # Tech block: verbatim 45 bytes.
    out.tech_block = bytearray(slot.tech_block)

    # Names: all 10, straight from the DS slot (already NFKC-normalized).
    out.names = list(slot.names)

    # Party / reserve / recruited: verbatim.
    out.party = slot.party
    out.reserve = slot.reserve
    out.recruited_mask = slot.recruited_mask

    out.gold = slot.gold
    out.play_time_seconds = slot.play_time_seconds
    out.location_name_id = slot.location_name_id
    out.era_mask = slot.era_mask

    return out


def _looks_like_ds_file(path: str) -> bool:
    """DS saves are detected by content (an ARDS export's b"ARDS" prefix, or
    one of the known wrapper sizes), not by extension -- an ARDS export can
    show up as .dst/.dsv/.duc/anything (REPORT.md #7)."""
    try:
        with open(path, "rb") as f:
            head = f.read(4)
        size = os.path.getsize(path)
    except OSError:
        return False
    if head == b"ARDS":
        return True
    if size in (ds_sav.ARDS_TOTAL_SIZE, ds_sav.IMAGE_SIZE, 524288, ds_sav.IMAGE_SIZE + ds_sav.DESMUME_FOOTER_SIZE):
        return True
    return path.lower().endswith((".dst", ".dsv", ".duc"))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("save_file", help="SNES .srm (8192 bytes) or DS .sav/.dst/.dsv save")
    ap.add_argument("slot", type=int, choices=[0, 1, 2])
    ap.add_argument("out")
    ap.add_argument("--template", default=None)
    args = ap.parse_args()

    is_ds = _looks_like_ds_file(args.save_file)

    if is_ds:
        ds_slots = ds_sav.load_ds_sav(args.save_file)
        slot = ds_slots[args.slot]
        if not slot.used:
            print(f"slot {args.slot} is unused in {args.save_file}", file=sys.stderr)
            sys.exit(1)
        flags = slot.flags
    else:
        slots, _last_used, checksums = snes_srm.load_srm(args.save_file)
        slot = slots[args.slot]
        if slot is None:
            print(f"slot {args.slot} is unused in {args.save_file}", file=sys.stderr)
            sys.exit(1)
        computed = snes_srm.slot_checksum(slot.raw)
        if computed != checksums[args.slot]:
            print(f"warning: slot checksum mismatch (stored {checksums[args.slot]:#06x}, "
                  f"computed {computed:#06x}) -- see snes_srm.slot_checksum docstring",
                  file=sys.stderr)
        flags = slot.flags

    if args.template:
        template_path = args.template
        template_ct = ctsave.load(template_path)
        dist = hamming_distance(flags, template_ct.flags)
    else:
        template_path, dist = pick_template(flags)
        template_ct = ctsave.load(template_path)

    print(f"chosen template: {template_path} (Hamming distance {dist})")

    result = ds_to_ct(slot, template_ct) if is_ds else snes_to_ct(slot, template_ct)
    ctsave.save(args.out, result)
    print(f"wrote {args.out}")


if __name__ == '__main__':
    main()
