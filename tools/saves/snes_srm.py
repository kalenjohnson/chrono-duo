"""Parser for SNES Chrono Trigger .srm save files.

An .srm is 8192 bytes: three 0xA00-byte slots at 0x0000/0x0A00/0x1400, a
last-used-slot marker at 0x1FE0, and three little-endian u16 slot checksums
at 0x1FF0. An unused slot is entirely 0xFF.

See REPORT.md #2 for the full slot layout this module implements.
"""
import struct
import sys
from dataclasses import dataclass, field

SLOT_SIZE = 0xA00
NUM_SLOTS = 3
CHAR_SIZE = 0x50
NUM_CHARS = 7
CHECKSUM_TABLE_OFFSET = 0x1FF0
LAST_USED_OFFSET = 0x1FE0

CHAR_ORDER = ["Crono", "Marle", "Lucca", "Robo", "Frog", "Ayla", "Magus"]
NAME_SLOT_ORDER = ["Crono", "Marle", "Lucca", "Robo", "Frog", "Ayla", "Magus", "Epoch"]


def slot_checksum(slot: bytes) -> int:
    """16-bit add-with-carry (65816 ADC loop) over the slot's 1280 LE words,
    summed from the LAST word (0x9FE) down to the first, final carry-out
    dropped. Word order matters because the carry propagates; forward order
    matches only 43/53 fixture slots, descending order matches 53/53.
    """
    acc = 0
    carry = 0
    for i in range(SLOT_SIZE - 2, -1, -2):
        w = slot[i] | (slot[i + 1] << 8)
        total = acc + w + carry
        acc = total & 0xFFFF
        carry = 1 if total > 0xFFFF else 0
    return acc


def decode_name(raw: bytes) -> str:
    """0xA0-0xB9 = A-Z, 0xBA-0xD3 = a-z, 0x00 or 0xFF = end/pad."""
    out = []
    for b in raw:
        if b in (0, 0xFF):
            break
        if 0xA0 <= b <= 0xB9:
            out.append(chr(ord('A') + (b - 0xA0)))
        elif 0xBA <= b <= 0xD3:
            out.append(chr(ord('a') + (b - 0xBA)))
        else:
            out.append('?')
    return ''.join(out)


@dataclass
class SnesChar:
    """One 0x50-byte character record (REPORT.md #4, SNES column)."""
    raw: bytes
    id: int
    cur_hp: int
    max_hp: int
    cur_mp: int
    max_mp: int
    power: int
    stamina: int
    speed: int
    magic: int
    hit: int
    evade: int
    mdef: int
    level: int
    exp: int
    tp_next: int
    helmet_id: int
    armor_id: int
    weapon_id: int
    accessory_id: int
    exp_next: int
    field_2d: int

    @staticmethod
    def parse(raw: bytes) -> "SnesChar":
        assert len(raw) == CHAR_SIZE
        power, stamina, speed, magic, hit, evade, mdef, level = raw[0x0B:0x13]
        exp = raw[0x13] | (raw[0x14] << 8) | (raw[0x15] << 16)
        return SnesChar(
            raw=bytes(raw),
            id=raw[0x00],
            cur_hp=struct.unpack_from('<H', raw, 0x03)[0],
            max_hp=struct.unpack_from('<H', raw, 0x05)[0],
            cur_mp=struct.unpack_from('<H', raw, 0x07)[0],
            max_mp=struct.unpack_from('<H', raw, 0x09)[0],
            power=power, stamina=stamina, speed=speed, magic=magic,
            hit=hit, evade=evade, mdef=mdef, level=level,
            exp=exp,
            tp_next=struct.unpack_from('<H', raw, 0x16)[0],
            helmet_id=raw[0x27],
            armor_id=raw[0x28],
            weapon_id=raw[0x29],
            accessory_id=raw[0x2A],
            exp_next=struct.unpack_from('<H', raw, 0x2B)[0],
            field_2d=raw[0x2D],
        )


@dataclass
class SnesSlot:
    raw: bytes
    items: list  # list of (id, count), 256 entries (0 id = empty)
    chars: list  # 7 SnesChar, Crono..Magus
    tech_block: bytes  # 45 bytes at 0x430
    party: bytes  # 3 bytes
    reserve: bytes  # 6 bytes
    recruited_mask: int
    names: list  # 8 decoded strings
    gold: int
    play_hours: int
    play_minutes: int
    play_seconds: int
    play_time_seconds: int
    chapter_byte: int  # 0x5E7 -- see caveat below
    location_id: int
    x: int
    y: int
    flags: bytes  # 512 bytes at 0x602

    @staticmethod
    def parse(raw: bytes) -> "SnesSlot":
        assert len(raw) == SLOT_SIZE
        ids = raw[0x000:0x100]
        counts = raw[0x100:0x200]
        items = [(ids[i], counts[i]) for i in range(256) if ids[i] != 0]

        chars = [
            SnesChar.parse(raw[0x200 + i * CHAR_SIZE: 0x200 + (i + 1) * CHAR_SIZE])
            for i in range(NUM_CHARS)
        ]

        tech_block = raw[0x430:0x430 + 45]
        party = raw[0x580:0x583]
        reserve = raw[0x583:0x589]
        recruited_mask = raw[0x5AF]
        names = [decode_name(raw[0x5B0 + i * 6: 0x5B0 + i * 6 + 6]) for i in range(8)]
        gold = raw[0x5E0] | (raw[0x5E1] << 8) | (raw[0x5E2] << 16)

        # Play time per coordinator correction: 0x5E3..0x5E8 are six DIGIT
        # bytes -- frames, seconds, minutes-ones, minutes-tens, hours-ones,
        # hours-tens -- not a packed frames/seconds/minutes/hours quad as
        # REPORT.md #2 originally described. 0x5E7 is the hours-ones digit,
        # not a "story chapter counter".
        frames = raw[0x5E3]
        sec = raw[0x5E4]
        min_ones = raw[0x5E5]
        min_tens = raw[0x5E6]
        hr_ones = raw[0x5E7]
        hr_tens = raw[0x5E8]
        minutes = min_tens * 10 + min_ones
        hours = hr_tens * 10 + hr_ones
        play_time_seconds = hours * 3600 + minutes * 60 + sec

        location_id = struct.unpack_from('<H', raw, 0x5F3)[0]
        x = struct.unpack_from('<H', raw, 0x5F5)[0]
        y = struct.unpack_from('<H', raw, 0x5F7)[0]
        flags = raw[0x602:0x602 + 512]

        return SnesSlot(
            raw=bytes(raw), items=items, chars=chars, tech_block=bytes(tech_block),
            party=bytes(party), reserve=bytes(reserve), recruited_mask=recruited_mask,
            names=names, gold=gold,
            play_hours=hours, play_minutes=minutes, play_seconds=sec,
            play_time_seconds=play_time_seconds,
            chapter_byte=hr_ones, location_id=location_id, x=x, y=y,
            flags=bytes(flags),
        )


def parse_srm(data: bytes):
    """Return (slots, last_used, checksums). slots[i] is None for unused slots."""
    assert len(data) == 8192
    checksums = struct.unpack_from('<3H', data, CHECKSUM_TABLE_OFFSET)
    last_used = data[LAST_USED_OFFSET]
    slots = []
    for i in range(NUM_SLOTS):
        raw = data[i * SLOT_SIZE:(i + 1) * SLOT_SIZE]
        if raw[:16] == b'\xff' * 16:
            slots.append(None)
        else:
            slots.append(SnesSlot.parse(raw))
    return slots, last_used, checksums


def load_srm(path: str):
    with open(path, 'rb') as f:
        data = f.read()
    return parse_srm(data)


if __name__ == '__main__':
    for path in sys.argv[1:]:
        print(f"=== {path} ===")
        slots, last_used, checksums = load_srm(path)
        print(f"  last used slot marker: {last_used}")
        for i, slot in enumerate(slots):
            if slot is None:
                print(f"  slot {i}: unused")
                continue
            stored = checksums[i]
            computed = slot_checksum(slot.raw)
            ok = "OK" if computed == stored else f"MISMATCH (computed {computed:#06x})"
            party_names = [slot.names[cid] for cid in slot.party if cid < 7]
            print(f"  slot {i}: checksum stored={stored:#06x} {ok}")
            print(f"    party: {', '.join(party_names)}")
            print(f"    gold: {slot.gold}  play time: "
                  f"{slot.play_hours:02d}:{slot.play_minutes:02d}:{slot.play_seconds:02d} "
                  f"({slot.play_time_seconds}s)")
            print(f"    location: {slot.location_id}  x,y: {slot.x},{slot.y}")
            print(f"    recruited mask: {slot.recruited_mask:#04x}")
            for c in slot.chars:
                name = slot.names[c.id] if c.id < 8 else f"id{c.id}"
                print(f"    {name}: lvl {c.level} hp {c.cur_hp}/{c.max_hp} "
                      f"mp {c.cur_mp}/{c.max_mp} exp {c.exp}")
