"""Blowfish-CBC-ish cipher used by the Chrono Trigger (2018 Steam / mobile) port.
Ported from reference/ChronoMod/ChronoCrypto.cpp. Standard Blowfish P/S init
(hex digits of pi); 8-byte key BA EC B7 8A EA 25 CA E4 (from the Steam binary)."""
import struct, sys

KEY = bytes.fromhex("BAECB78AEA25CAE4")
HDR_MAGIC = bytes.fromhex("75FA2995054D415F")

def _pi_hex(nwords):
    # generate hex digits of pi via Bailey–Borwein–Plouffe? Simpler: use mpmath if present, else hardcoded via decimal.
    from decimal import Decimal, getcontext
    getcontext().prec = nwords*8*2 + 50  # plenty
    # Machin formula
    def arctan_inv(x):
        x = Decimal(x); total = Decimal(0); term = 1/x; n = 1; sign = 1; x2 = x*x
        while True:
            t = term / n
            if t < Decimal(10) ** (-(getcontext().prec-10)): break
            total += sign*t; term /= x2; n += 2; sign = -sign
        return total
    pi = 16*arctan_inv(5) - 4*arctan_inv(239)
    frac = pi - 3
    words = []
    for _ in range(nwords):
        frac *= 16**8
        w = int(frac); words.append(w); frac -= w
    return words

_PI = None
def _init_tables():
    global _PI
    if _PI is None:
        _PI = _pi_hex(18 + 1024)
    return _PI

class Blowfish:
    def __init__(self, key=KEY):
        pi = _init_tables()
        P = pi[:18]; S = pi[18:18+1024]
        self.P = [P[i] ^ struct.unpack('>I', (key*3)[(i*4)%8:(i*4)%8+4])[0] for i in range(18)]
        self.S = list(S)
        l = r = 0
        for i in range(0, 18, 2):
            l, r = self.encrypt_block(l, r); self.P[i], self.P[i+1] = l, r
        for i in range(0, 1024, 2):
            l, r = self.encrypt_block(l, r); self.S[i], self.S[i+1] = l, r
    def F(self, x):
        S = self.S
        return ((S[(x>>24)] + S[0x100+((x>>16)&0xff)]) ^ S[0x200+((x>>8)&0xff)]) + S[0x300+(x&0xff)] & 0xffffffff
    def encrypt_block(self, l, r):
        for i in range(16):
            l ^= self.P[i]; r ^= self.F(l); l, r = r, l
        l, r = r, l
        return l ^ self.P[17], r ^ self.P[16]
    def decrypt_block(self, l, r):
        for i in range(17, 1, -1):
            l ^= self.P[i]; r ^= self.F(l); l, r = r, l
        l, r = r, l
        return l ^ self.P[0], r ^ self.P[1]

_BF = None
def bf():
    global _BF
    if _BF is None: _BF = Blowfish()
    return _BF

def decrypt(data: bytes) -> bytes:
    """data = 8-byte header (IV xor magic) + ciphertext. Returns plaintext."""
    b = bf()
    prev = bytes(x ^ y for x, y in zip(data[:8], HDR_MAGIC))
    out = bytearray()
    for i in range(8, len(data) - 7, 8):
        blk = data[i:i+8]
        l, r = struct.unpack('>II', blk)
        l, r = b.decrypt_block(l, r)
        p = struct.pack('>II', l, r)
        out += bytes(x ^ y for x, y in zip(p, prev))
        prev = blk
    return bytes(out)

def encrypt(plain: bytes, iv: bytes = bytes(8)) -> bytes:
    assert len(plain) % 8 == 0
    b = bf()
    prev = iv
    out = bytearray(bytes(x ^ y for x, y in zip(iv, HDR_MAGIC)))
    for i in range(0, len(plain), 8):
        x = bytes(p ^ q for p, q in zip(plain[i:i+8], prev))
        l, r = struct.unpack('>II', x)
        l, r = b.encrypt_block(l, r)
        c = struct.pack('>II', l, r)
        out += c; prev = c
    return bytes(out)

if __name__ == '__main__':
    for f in sys.argv[1:]:
        d = open(f, 'rb').read()
        p = decrypt(d)
        open(f + '.dec', 'wb').write(p)
        print(f, len(d), '->', len(p))
