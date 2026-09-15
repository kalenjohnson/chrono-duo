"""Parser for Nintendo DS Chrono Trigger `.sav` (64 KB EEPROM) saves.

See REPORT.md #7 for the full format spec this module implements. One
correction versus that section, found while validating against the fixture
(`ds/chrono-trigger.22851.dst`): the table lists the character-record block
as starting at DS slot offset 0x480, but in the fixture the 7 records
actually start at **0x484** -- the `u32 id` field (0, 1, .. 6) and the
`80 86 90 xx` per-character constant land at DS-record-relative +0x00/+0x04
exactly as documented, they're just 4 bytes further into the slot than the
table's "DS slot" column says. Everything downstream (inventory at 0x724
onward, tech block at 0xC90, names at 0xCC0, party/gold/time/etc.) already
lines up with base 0x484 + 7*0x60 = 0x724, so only that one start offset was
off; nothing else in #7 needed correcting against this fixture.

Wrappers stripped before parsing (REPORT.md #7):
  - Action Replay DS export: 500-byte `ARDS` header, file size 262644 (or
    starts with b"ARDS"); the header is followed by a 256 KB image of which
    only the first 64 KB is real (the rest is 0xFF padding) -- REPORT.md
    only calls this out explicitly for the 524288-byte case, but the same
    "take the first 65536 bytes" rule applies here too (256 KB != 512 KB,
    a case REPORT.md's wrapper list doesn't enumerate by size).
  - raw 65536 bytes: used as-is.
  - 524288 bytes: first 65536 bytes (same 0xFF-padding pattern).
  - DeSmuME .dsv: 122-byte footer carrying the ASCII signature
    "|-DESMUME SAVE-|"; stripped only when that signature is actually
    present in the tail (never assumed from size alone).
"""
import struct
import sys
import unicodedata
from dataclasses import dataclass, field

SLOT_SIZE = 0x2800
NUM_SLOTS = 3
IMAGE_SIZE = 65536

ARDS_HEADER_SIZE = 500
ARDS_TOTAL_SIZE = 262644
DESMUME_FOOTER_SIZE = 122
DESMUME_SIGNATURE = b"|-DESMUME SAVE-|"

SLOT_MAGIC = 0xFEDCBA98  # the last-saved slot's u32 at offset 0 (little-endian on disk: 98 BA DC FE)

# Corrected vs. REPORT.md #7's table (see module docstring): the 7 character
# records actually start here, not at slot offset 0x480.
CHAR_BLOCK_OFF = 0x484
CHAR_SIZE = 0x60
NUM_CHARS = 7

FLAGS_OFF = 0x250
FLAGS_SIZE = 0x200
SCENE_OFF = 0x450
SCENE_SIZE = 0x30
REGION_A_OFF = 0x050
REGION_A_SIZE = 0x200

TECH_BLOCK_OFF = 0xC90
TECH_BLOCK_SIZE = 45

NAMES_OFF = 0xCC0
NAME_STRIDE = 16
NUM_NAMES = 10

PARTY_OFF = 0xD60  # party(3) + reserve(6) + recruited(1) = 10 bytes
GOLD_OFF = 0xD6C
SAVE_COUNT_OFF = 0xD70
PLAY_TIME_OFF = 0xD74
LOCATION_NAME_ID_OFF = 0xD80
ERA_MASK_OFF = 0xD82

# Six inventory sections, in stream order, immediately after the character
# block (0x724). idx = id & 0xFFF; category = id >> 12, checked against the
# section's expected nibble (same category numbering as the port's
# (category<<12)|idx equipment encoding: weapons=0, armour=1, helmets=2,
# accessories=3, plus consumables=4 and key_items=5 for the two sections
# that never appear as equipment).
INVENTORY_SECTIONS = [
    ("weapons", 111, 0x0),
    ("armour", 50, 0x1),
    ("helmets", 39, 0x2),
    ("accessories", 59, 0x3),
    ("consumables", 43, 0x4),
    ("key_items", 45, 0x5),
]


def _decode_ds_name(raw: bytes) -> str:
    """16-byte fixed Shift-JIS string, NUL-padded; full-width chars (e.g.
    'Ｃｒｏｎｏ') normalized to ASCII via NFKC before returning."""
    raw = raw.split(b"\x00", 1)[0]
    s = raw.decode("shift_jis", errors="replace")
    return unicodedata.normalize("NFKC", s)


@dataclass
class DsChar:
    """One 0x60-byte DS character record (REPORT.md #7)."""
    raw: bytes
    id: int
    cur_hp: int
    max_hp: int
    cur_mp: int
    max_mp: int
    base_max_hp: int
    power: int
    stamina: int
    speed: int
    magic: int
    hit: int
    evade: int
    mdef: int
    level: int
    extra_u16: int  # +0x1A -- not present in the port (REPORT.md #7)
    exp: int
    tp_next: int  # +0x20 u32 -> port +0x1A
    equip_weapon: int
    equip_armor: int
    equip_helmet: int
    equip_accessory: int
    exp_next: int
    tp_related: int
    growth: bytes  # 6 bytes -> port +0x38
    cur_stats: bytes  # 9 bytes -> port +0x3E
    per_char_constants: bytes  # 4 bytes -> port +0x47

    @staticmethod
    def parse(raw: bytes) -> "DsChar":
        assert len(raw) == CHAR_SIZE
        max_hp, cur_hp, max_mp, cur_mp, base_max_hp = struct.unpack_from("<HHHHH", raw, 0x08)
        power, stamina, speed, magic, hit, evade, mdef, level = raw[0x12:0x1A]
        extra_u16 = struct.unpack_from("<H", raw, 0x1A)[0]
        exp = struct.unpack_from("<I", raw, 0x1C)[0]
        tp_next = struct.unpack_from("<I", raw, 0x20)[0]
        eq_w, eq_a, eq_h, eq_acc = struct.unpack_from("<HHHH", raw, 0x36)
        exp_next = struct.unpack_from("<H", raw, 0x3E)[0]
        tp_related = struct.unpack_from("<H", raw, 0x40)[0]
        return DsChar(
            raw=bytes(raw),
            id=struct.unpack_from("<I", raw, 0x00)[0],
            cur_hp=cur_hp, max_hp=max_hp, cur_mp=cur_mp, max_mp=max_mp,
            base_max_hp=base_max_hp,
            power=power, stamina=stamina, speed=speed, magic=magic,
            hit=hit, evade=evade, mdef=mdef, level=level,
            extra_u16=extra_u16,
            exp=exp, tp_next=tp_next,
            equip_weapon=eq_w, equip_armor=eq_a, equip_helmet=eq_h, equip_accessory=eq_acc,
            exp_next=exp_next, tp_related=tp_related,
            growth=bytes(raw[0x42:0x48]),
            cur_stats=bytes(raw[0x48:0x51]),
            per_char_constants=bytes(raw[0x51:0x55]),
        )


@dataclass
class DsSlot:
    raw: bytes  # full 0x2800-byte slot
    used: bool
    is_last_saved: bool
    region_a: bytes  # 0x200 bytes at 0x050
    flags: bytes  # 0x200 bytes at 0x250 (event flags)
    scene_block: bytes  # 0x30 bytes at 0x450
    chars: list  # 7 DsChar, Crono..Magus
    inventory: dict  # section name -> list[(idx, count)]
    tech_block: bytes  # 45 bytes at 0xC90
    names: list  # 10 decoded strings
    party: bytes  # 3 bytes
    reserve: bytes  # 6 bytes
    recruited_mask: int
    gold: int
    save_count: int  # u32 at 0xD70
    play_time_seconds: int
    location_name_id: int
    era_mask: int

    @staticmethod
    def parse(raw: bytes) -> "DsSlot":
        assert len(raw) == SLOT_SIZE
        magic = struct.unpack_from("<I", raw, 0)[0]
        is_last_saved = magic == SLOT_MAGIC

        region_a = raw[REGION_A_OFF:REGION_A_OFF + REGION_A_SIZE]
        flags = raw[FLAGS_OFF:FLAGS_OFF + FLAGS_SIZE]
        scene_block = raw[SCENE_OFF:SCENE_OFF + SCENE_SIZE]

        chars = [
            DsChar.parse(raw[CHAR_BLOCK_OFF + i * CHAR_SIZE: CHAR_BLOCK_OFF + (i + 1) * CHAR_SIZE])
            for i in range(NUM_CHARS)
        ]

        # Unused slots are 0xFF-filled (not zero-filled); e.g. an all-0xFF
        # max HP or a 0xFF party byte 0. The FEDCBA98 magic only marks the
        # *last-saved* slot, not "used" (the other used slots are 0xFFFFFFFF
        # there too) -- REPORT.md #7 / fixture chrono-trigger.18311.duc.
        used = chars[0].max_hp not in (0, 0xFFFF) and raw[PARTY_OFF] != 0xFF

        off = CHAR_BLOCK_OFF + NUM_CHARS * CHAR_SIZE
        assert off == 0x724, f"unexpected inventory offset {off:#x}"
        inventory = {}
        for name, capacity, expected_cat in INVENTORY_SECTIONS:
            entries = []
            for i in range(capacity):
                packed, count = struct.unpack_from("<HH", raw, off)
                off += 4
                idx = packed & 0xFFF
                cat = packed >> 12
                # 0xFFFF (both fields 0xFF) shows up throughout an unused,
                # 0xFF-filled slot (REPORT.md #7 / chrono-trigger.18311.duc
                # slots 1-2) -- not a real category byte, don't validate it.
                if packed not in (0, 0xFFFF) and cat != expected_cat:
                    raise ValueError(
                        f"unexpected category {cat:#x} in DS section {name} "
                        f"(expected {expected_cat:#x}) at slot offset {off - 4:#x}")
                entries.append((idx, count))
            inventory[name] = entries
        assert off == TECH_BLOCK_OFF, f"unexpected tech block offset {off:#x}"

        tech_block = raw[TECH_BLOCK_OFF:TECH_BLOCK_OFF + TECH_BLOCK_SIZE]

        names = [
            _decode_ds_name(raw[NAMES_OFF + i * NAME_STRIDE: NAMES_OFF + (i + 1) * NAME_STRIDE])
            for i in range(NUM_NAMES)
        ]

        party = raw[PARTY_OFF:PARTY_OFF + 3]
        reserve = raw[PARTY_OFF + 3:PARTY_OFF + 9]
        recruited_mask = raw[PARTY_OFF + 9]

        gold = struct.unpack_from("<I", raw, GOLD_OFF)[0]
        save_count = struct.unpack_from("<I", raw, SAVE_COUNT_OFF)[0]
        play_time_seconds = struct.unpack_from("<I", raw, PLAY_TIME_OFF)[0]
        location_name_id = struct.unpack_from("<H", raw, LOCATION_NAME_ID_OFF)[0]
        era_mask = struct.unpack_from("<H", raw, ERA_MASK_OFF)[0]

        return DsSlot(
            raw=bytes(raw), used=used, is_last_saved=is_last_saved,
            region_a=bytes(region_a), flags=bytes(flags), scene_block=bytes(scene_block),
            chars=chars, inventory=inventory, tech_block=bytes(tech_block), names=names,
            party=bytes(party), reserve=bytes(reserve), recruited_mask=recruited_mask,
            gold=gold, save_count=save_count, play_time_seconds=play_time_seconds,
            location_name_id=location_name_id, era_mask=era_mask,
        )


def strip_wrapper(data: bytes) -> bytes:
    """Detect and strip the wrapper around a DS save image, returning the
    raw 65536-byte EEPROM image. Raises ValueError if the file doesn't match
    any known wrapper (REPORT.md #7)."""
    if len(data) == ARDS_TOTAL_SIZE or data[:4] == b"ARDS":
        if len(data) < ARDS_HEADER_SIZE:
            raise ValueError(f"file looks like an ARDS export but is only {len(data)} bytes")
        image = data[ARDS_HEADER_SIZE:]
        if len(image) > IMAGE_SIZE:
            image = image[:IMAGE_SIZE]
        elif len(image) < IMAGE_SIZE:
            raise ValueError(f"ARDS export image is {len(image)} bytes, expected >= {IMAGE_SIZE}")
        return image

    if len(data) >= DESMUME_FOOTER_SIZE and DESMUME_SIGNATURE in data[-DESMUME_FOOTER_SIZE:]:
        image = data[:-DESMUME_FOOTER_SIZE]
        if len(image) != IMAGE_SIZE:
            raise ValueError(f"DeSmuME .dsv image (after footer strip) is {len(image)} bytes, "
                              f"expected {IMAGE_SIZE}")
        return image

    if len(data) == IMAGE_SIZE:
        return data

    if len(data) == 524288:
        return data[:IMAGE_SIZE]

    raise ValueError(
        f"unrecognized DS save wrapper: {len(data)} bytes, "
        f"first 4 bytes {data[:4]!r}")


def parse_ds_image(image: bytes):
    """Return a list of 3 DsSlot (never None -- unused slots are still
    parsed, just with used=False; REPORT.md #7 notes the magic can't be used
    to determine which slots hold data)."""
    assert len(image) == IMAGE_SIZE
    slots = []
    for i in range(NUM_SLOTS):
        raw = image[i * SLOT_SIZE:(i + 1) * SLOT_SIZE]
        slots.append(DsSlot.parse(raw))
    return slots


def load_ds_sav(path: str):
    with open(path, "rb") as f:
        data = f.read()
    image = strip_wrapper(data)
    return parse_ds_image(image)


if __name__ == "__main__":
    for path in sys.argv[1:]:
        print(f"=== {path} ===")
        slots = load_ds_sav(path)
        for i, slot in enumerate(slots):
            if not slot.used:
                print(f"  slot {i}: unused")
                continue
            party_ids = [b for b in slot.party if b < 7]
            party_names = [slot.names[cid] for cid in party_ids]
            levels = [slot.chars[cid].level for cid in party_ids]
            hours = slot.play_time_seconds // 3600
            minutes = (slot.play_time_seconds % 3600) // 60
            seconds = slot.play_time_seconds % 60
            print(f"  slot {i}: last_saved={slot.is_last_saved}")
            print(f"    party: {', '.join(party_names)}  levels: {levels}")
            print(f"    gold: {slot.gold}  play time: {hours:02d}:{minutes:02d}:{seconds:02d} "
                  f"({slot.play_time_seconds}s)")
            print(f"    location name id: {slot.location_name_id:#04x}  era mask: {slot.era_mask:#06x}")
            print(f"    recruited mask: {slot.recruited_mask:#04x}  save count: {slot.save_count}")
            print(f"    names: {slot.names}")
            for c in slot.chars:
                name = slot.names[c.id] if c.id < 8 else f"id{c.id}"
                print(f"    {name}: lvl {c.level} hp {c.cur_hp}/{c.max_hp} mp {c.cur_mp}/{c.max_mp} exp {c.exp}")
