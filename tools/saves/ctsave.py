"""Stream model of the Chrono Trigger (2018 Steam/mobile port) save payload.

See REPORT.md #3 for the format. The payload is a serialized *stream*, not a
fixed C struct: names are length-prefixed ASCII, so every field after the
name table shifts if a name's length changes. CtSave.parse()/.serialize()
therefore walk the stream sequentially in both directions.

Container framing (REPORT.md #3.1): on disk a save is
    8-byte header (IV xor magic) + Blowfish-CBC ciphertext
which decrypts (via ctcrypto.py) to
    payload[N] + u16 checksum + pad-to-8-byte-boundary + u32 LE N
`load()`/`save()` handle that trailer; CtSave itself only models payload[N].
"""
import struct

import ctcrypto

CHAR_SIZE = 0x58
NUM_CHARS = 7
NUM_NAMES = 10
REGION_A_SIZE = 0x200
FLAGS_SIZE = 0x200
BLOCK_400_SIZE = 0x30

# (capacity, category byte) for the six inventory sections, in stream order.
INVENTORY_SECTIONS = [
    ("weapons", 111, 0x00),
    ("armour", 50, 0x10),
    ("helmets", 39, 0x20),
    ("accessories", 59, 0x30),
    ("consumables", 43, 0x40),
    ("key_items", 45, 0x50),
]
TECH_BLOCK_SIZE = 45


class CtChar:
    """One 0x58-byte character record (REPORT.md #4, port column).

    Backed by a mutable bytearray (`raw`); every named field is a
    property that reads/writes directly into `raw`, so `bytes(self.raw)`
    is always an exact, up-to-date serialization -- there is no separate
    field state to keep in sync.
    """

    def __init__(self, raw: bytes):
        assert len(raw) == CHAR_SIZE
        self.raw = bytearray(raw)

    def _u8(self, off, value=None):
        if value is None:
            return self.raw[off]
        self.raw[off] = value & 0xFF

    def _u16(self, off, value=None):
        if value is None:
            return struct.unpack_from('<H', self.raw, off)[0]
        struct.pack_into('<H', self.raw, off, value & 0xFFFF)

    def _u32(self, off, value=None):
        if value is None:
            return struct.unpack_from('<I', self.raw, off)[0]
        struct.pack_into('<I', self.raw, off, value & 0xFFFFFFFF)

    # +00..+03
    id = property(lambda s: s._u8(0x00), lambda s, v: s._u8(0x00, v))
    const_01 = property(lambda s: s._u8(0x01), lambda s, v: s._u8(0x01, v))
    const_02 = property(lambda s: s._u8(0x02), lambda s, v: s._u8(0x02, v))
    const_03 = property(lambda s: s._u8(0x03), lambda s, v: s._u8(0x03, v))

    # HP/MP -- note the port swaps max/current order vs. the SNES record.
    max_hp = property(lambda s: s._u16(0x04), lambda s, v: s._u16(0x04, v))
    cur_hp = property(lambda s: s._u16(0x06), lambda s, v: s._u16(0x06, v))
    max_mp = property(lambda s: s._u16(0x08), lambda s, v: s._u16(0x08, v))
    cur_mp = property(lambda s: s._u16(0x0A), lambda s, v: s._u16(0x0A, v))
    base_max_hp = property(lambda s: s._u16(0x0C), lambda s, v: s._u16(0x0C, v))

    base_power = property(lambda s: s._u8(0x0E), lambda s, v: s._u8(0x0E, v))
    base_stamina = property(lambda s: s._u8(0x0F), lambda s, v: s._u8(0x0F, v))
    base_speed = property(lambda s: s._u8(0x10), lambda s, v: s._u8(0x10, v))
    base_magic = property(lambda s: s._u8(0x11), lambda s, v: s._u8(0x11, v))
    base_hit = property(lambda s: s._u8(0x12), lambda s, v: s._u8(0x12, v))
    base_evade = property(lambda s: s._u8(0x13), lambda s, v: s._u8(0x13, v))
    base_mdef = property(lambda s: s._u8(0x14), lambda s, v: s._u8(0x14, v))
    level = property(lambda s: s._u8(0x15), lambda s, v: s._u8(0x15, v))

    exp = property(lambda s: s._u32(0x16), lambda s, v: s._u32(0x16, v))
    tp_next = property(lambda s: s._u32(0x1A), lambda s, v: s._u32(0x1A, v))
    # +0x1E..+0x2B (14 bytes): zeros in every observed fixture.

    equip_weapon = property(lambda s: s._u16(0x2C), lambda s, v: s._u16(0x2C, v))
    equip_armor = property(lambda s: s._u16(0x2E), lambda s, v: s._u16(0x2E, v))
    equip_helmet = property(lambda s: s._u16(0x30), lambda s, v: s._u16(0x30, v))
    equip_accessory = property(lambda s: s._u16(0x32), lambda s, v: s._u16(0x32, v))

    exp_next = property(lambda s: s._u16(0x34), lambda s, v: s._u16(0x34, v))
    tp_related = property(lambda s: s._u16(0x36), lambda s, v: s._u16(0x36, v))

    @property
    def growth(self):
        return bytes(self.raw[0x38:0x3E])

    @growth.setter
    def growth(self, value):
        assert len(value) == 6
        self.raw[0x38:0x3E] = value

    @property
    def cur_stats(self):
        """9 bytes: power, stamina, speed, magic, hit, evade, mdef, attack, defence."""
        return bytes(self.raw[0x3E:0x47])

    @cur_stats.setter
    def cur_stats(self, value):
        assert len(value) == 9
        self.raw[0x3E:0x47] = value

    @property
    def per_char_constants(self):
        return bytes(self.raw[0x47:0x4B])

    @per_char_constants.setter
    def per_char_constants(self, value):
        assert len(value) == 4
        self.raw[0x47:0x4B] = value

    # +0x4B..+0x57 (13 bytes): zeros in every observed fixture.

    def serialize(self) -> bytes:
        return bytes(self.raw)


def _read_pascal_str(data: bytes, off: int):
    """One length-prefixed name: u8 byte-length, then that many bytes.

    REPORT.md #3.2 describes these as ASCII, and they are for every fixture
    except one: X12_dreamsepilogue.bin has a custom Chinese name for Epoch,
    stored as UTF-8 (length byte 0x0c = 12 bytes for 4 characters). Decode as
    UTF-8 (a superset of ASCII) rather than pure ASCII to handle both.
    """
    n = data[off]
    s = data[off + 1: off + 1 + n].decode('utf-8')
    return s, off + 1 + n


def _write_pascal_str(s: str) -> bytes:
    b = s.encode('utf-8')
    assert len(b) <= 255
    return bytes([len(b)]) + b


class CtSave:
    def __init__(self):
        self.region_a = bytearray(REGION_A_SIZE)
        self.flags = bytearray(FLAGS_SIZE)
        self.block_400 = bytearray(BLOCK_400_SIZE)
        self.chars = []  # 7 CtChar
        self.inventory = {}  # section name -> list[(idx, count)], fixed capacity
        self.tech_block = bytearray(TECH_BLOCK_SIZE)
        self.names = []  # 10 strings
        self.party = b'\x00\x00\x00'
        self.reserve = b'\x00' * 6
        self.recruited_mask = 0
        self.byte_0x0A = 0
        self.gold = 0
        self.counter1 = 0
        self.play_time_seconds = 0
        self.counter2 = 0
        self.zero_u16 = 0
        self.location_name_id = 0
        self.era_mask = 0
        self.tail = b''  # opaque: everything from here to the end of payload[N]
        self.checksum = 0  # trailer u16; unknown algorithm, see load()/save()

    @staticmethod
    def parse(payload: bytes) -> "CtSave":
        s = CtSave()
        off = 0
        s.region_a = bytearray(payload[off:off + REGION_A_SIZE]); off += REGION_A_SIZE
        s.flags = bytearray(payload[off:off + FLAGS_SIZE]); off += FLAGS_SIZE
        s.block_400 = bytearray(payload[off:off + BLOCK_400_SIZE]); off += BLOCK_400_SIZE

        s.chars = []
        for _ in range(NUM_CHARS):
            s.chars.append(CtChar(payload[off:off + CHAR_SIZE]))
            off += CHAR_SIZE

        s.inventory = {}
        for name, capacity, cat_byte in INVENTORY_SECTIONS:
            entries = []
            for _ in range(capacity):
                idx, cat, count = payload[off], payload[off + 1], payload[off + 2]
                off += 3
                if cat not in (0, cat_byte):
                    raise ValueError(
                        f"unexpected category byte {cat:#04x} in section {name} "
                        f"(expected 0x00 or {cat_byte:#04x})")
                entries.append((idx, count))
            s.inventory[name] = entries

        s.tech_block = bytearray(payload[off:off + TECH_BLOCK_SIZE]); off += TECH_BLOCK_SIZE

        s.names = []
        for _ in range(NUM_NAMES):
            name, off = _read_pascal_str(payload, off)
            s.names.append(name)

        s.party = payload[off:off + 3]; off += 3
        s.reserve = payload[off:off + 6]; off += 6
        s.recruited_mask = payload[off]; off += 1
        s.byte_0x0A = payload[off]; off += 1
        s.gold = payload[off] | (payload[off + 1] << 8) | (payload[off + 2] << 16); off += 3
        s.counter1 = payload[off]; off += 1
        s.play_time_seconds = (payload[off] | (payload[off + 1] << 8)
                                | (payload[off + 2] << 16)); off += 3
        s.counter2 = payload[off]; off += 1
        s.zero_u16 = struct.unpack_from('<H', payload, off)[0]; off += 2
        s.location_name_id = struct.unpack_from('<H', payload, off)[0]; off += 2
        s.era_mask = struct.unpack_from('<H', payload, off)[0]; off += 2

        s.tail = payload[off:]
        return s

    def serialize(self) -> bytes:
        out = bytearray()
        out += self.region_a
        out += self.flags
        out += self.block_400
        for c in self.chars:
            out += c.serialize()

        for name, capacity, cat_byte in INVENTORY_SECTIONS:
            entries = self.inventory[name]
            assert len(entries) == capacity, f"{name}: expected {capacity} entries, got {len(entries)}"
            for idx, count in entries:
                cat = cat_byte if (idx or count) else 0
                out += bytes([idx & 0xFF, cat, count & 0xFF])

        out += self.tech_block

        for name in self.names:
            out += _write_pascal_str(name)

        out += self.party
        out += self.reserve
        out.append(self.recruited_mask)
        out.append(self.byte_0x0A)
        out += bytes([self.gold & 0xFF, (self.gold >> 8) & 0xFF, (self.gold >> 16) & 0xFF])
        out.append(self.counter1)
        t = self.play_time_seconds
        out += bytes([t & 0xFF, (t >> 8) & 0xFF, (t >> 16) & 0xFF])
        out.append(self.counter2)
        out += struct.pack('<H', self.zero_u16)
        out += struct.pack('<H', self.location_name_id)
        out += struct.pack('<H', self.era_mask)
        out += self.tail
        return bytes(out)


def load(path: str) -> CtSave:
    with open(path, 'rb') as f:
        data = f.read()
    plain = ctcrypto.decrypt(data)
    n = struct.unpack_from('<I', plain, len(plain) - 4)[0]
    payload = plain[:n]
    save = CtSave.parse(payload)
    save.checksum = struct.unpack_from('<H', plain, n)[0]
    return save


def save(path: str, ct_save: CtSave, iv: bytes = None):
    """Serialize and encrypt `ct_save` to `path`.

    The trailer checksum algorithm is unknown (REPORT.md #3.1) -- this writes
    whatever is currently in `ct_save.checksum` (default 0, or whatever was
    loaded from an existing file via `load()`). Pad bytes are zero-filled
    (the original format's pad bytes are documented as uninitialised
    garbage, so zero is as good a choice as any).
    """
    payload = ct_save.serialize()
    n = len(payload)
    trailer = bytearray()
    trailer += struct.pack('<H', ct_save.checksum & 0xFFFF)
    total = n + len(trailer) + 4
    pad = (-total) % 8
    trailer += bytes(pad)
    trailer += struct.pack('<I', n)
    plain = payload + bytes(trailer)
    if iv is None:
        iv = bytes(8)
    data = ctcrypto.encrypt(plain, iv=iv)
    with open(path, 'wb') as f:
        f.write(data)
