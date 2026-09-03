#!/usr/bin/env python3
"""Minimal ARC1 (resources.bin) reader, ported from ChronoResources.java."""
import gzip, io, struct, sys

ARCHIVE = "/tmp/claude-1000/-home-kalenj-Work-chrono-trigger/d8c69a5b-a368-4c5e-afee-e08320e6e724/scratchpad/res/assets/resources.bin"

def xor_decode(buf, region_start_offset):
    tmp = (0x19000000 + region_start_offset) & 0xFFFFFFFF
    out = bytearray(buf)
    for i in range(len(out)):
        tmp = (tmp * 0x41c64e6d + 0x3039) & 0xFFFFFFFF
        out[i] ^= (tmp >> 24) & 0xFF
    return bytes(out)

def read_region(f, offset, length):
    f.seek(offset)
    buf = f.read(length)
    assert len(buf) == length, f"short read at {offset} wanted {length} got {len(buf)}"
    return xor_decode(buf, offset)

def gunzip(data, expected_len):
    out = gzip.GzipFile(fileobj=io.BytesIO(data)).read()
    if expected_len >= 0:
        assert len(out) == expected_len, f"gunzip len mismatch {len(out)} != {expected_len}"
    return out

def parse_table(f):
    hdr = read_region(f, 0, 16)
    magic = hdr[0:4]
    assert magic == b"ARC1", f"bad magic {magic!r}"
    header_offset = struct.unpack_from("<I", hdr, 8)[0]
    header_len = struct.unpack_from("<I", hdr, 12)[0]

    table_region = read_region(f, header_offset, header_len)
    table_uncomp_len = struct.unpack_from(">I", table_region, 0)[0]
    inflated = gunzip(table_region[4:], table_uncomp_len)

    entry_count = struct.unpack_from("<I", inflated, 0)[0]
    p = 4
    entries = []
    for i in range(entry_count):
        name_off, entry_off, entry_len = struct.unpack_from("<III", inflated, p)
        p += 12
        entries.append((name_off, entry_off, entry_len))

    table = {}
    order = []
    for name_off, entry_off, entry_len in entries:
        end = inflated.index(b"\x00", name_off)
        name = inflated[name_off:end].decode("utf-8")
        table[name] = (entry_off, entry_len)
        order.append(name)
    return table, order

def decode_entry(f, entry_off, entry_len):
    region = read_region(f, entry_off, entry_len)
    uncomp_len = struct.unpack_from(">I", region, 0)[0]
    if uncomp_len == 0:
        return b""
    return gunzip(region[4:], uncomp_len)

def extract(f, table, name):
    entry_off, entry_len = table[name]
    return decode_entry(f, entry_off, entry_len)

if __name__ == "__main__":
    with open(ARCHIVE, "rb") as f:
        table, order = parse_table(f)
        print(f"entries: {len(order)}", file=sys.stderr)
        if len(sys.argv) > 1:
            if sys.argv[1] == "--list":
                for n in order:
                    print(n)
            else:
                data = extract(f, table, sys.argv[1])
                sys.stdout.buffer.write(data)
