# Save-game formats: SNES SRAM vs. the 2018 mobile/Steam port

*(2026-09-15 — derived purely from data: 21 SNES `.srm` files and 43 Steam chapter saves.
Nothing here has been checked against `libchrono.so` yet; see §6 for what still needs the binary.)*

Goal: import an SNES Chrono Trigger save into ChronoDuo, which hosts the official mobile
port. The port's save is a different serialization of (mostly) the same data, so this is a
conversion problem, not an emulation one.

## 1. Fixtures

| Dir | What | Source |
|---|---|---|
| `snes/fantasyanime/ctsaveNN-*/chrono_trigger.srm` | 16 SRMs × 3 slots, whole game in order | fantasyanime.com/squaresoft/ctsaves-srm.htm |
| `snes/retromaggedon/sN/…/Chrono Trigger (USA).srm` | 5 SRMs, slot 0 only | retromaggedon.com |
| `steam/NN_chapter.bin` | 31 story-chapter saves (chapter 2 is missing from the archive) + 12 NG+ (`X*`) saves + `meta.bin` + `common.bin` | Steam Community guide 2482326379 (Google Drive) |
| `steam/*.bin.dec` | decrypted plaintext (generated, git-ignored) | `python3 ctcrypto.py steam/*.bin` |

Story-matched pairs used for byte correlation (SNES file/slot ↔ Steam chapter):
01/1↔03, 01/2↔05, 02/1↔06, 03/1↔08, 03/2↔09, 04/1↔15, 06/0↔16, 07/0↔17, 08/0↔20, 10/0↔24, 15/2↔28.

## 2. SNES SRAM (`.srm`, 8192 bytes)

Three slots of 0xA00 at 0x0000 / 0x0A00 / 0x1400; `0x1FE0` = last-used slot; `0x1FF0`
= 3 × u16 LE slot checksums. An unused slot is 0xFF-filled.

**Checksum** = 16-bit add-with-carry over the slot's 1280 LE words **from 0x9FE down to
0x000** (a 65816 `ADC` loop counting down), final carry-out dropped. Verified 53/53 slots.
Summing upward matches only 43 (the carry chain differs); the `mcred` editor's variant is
also wrong on 10.

Slot layout (offsets within the slot):

| Off | Size | Field |
|---|---|---|
| 0x000 | 256 | inventory item ids (0 = empty) |
| 0x100 | 256 | inventory counts (parallel) |
| 0x200 | 7×0x50 | character records, Crono..Magus (see §4) |
| 0x430 | 7 | techs learned per character (count) |
| 0x437 | 7 | single-tech learned bitmask per character (bit7 = tech 1) |
| 0x43E | 31 | dual/triple-tech learned bitmasks |
| 0x45D | 6 | 0xFF |
| 0x580 | 3 | active party (char id, 0x80 = empty) |
| 0x583 | 6 | reserve list (char id; 0x80 bit set = also in active party) |
| 0x589 | 1 | ? |
| 0x590 | 12 | constant `84 a0 01 80 08 40 08 02 04 10 20 40` |
| 0x59C | 1 | save counter |
| 0x59F | 1 | constant 0x1D |
| 0x5AD | 2 | ? (u16, grows) |
| 0x5AF | 1 | recruited-characters bitmask (bit7 Crono … bit1 Magus) |
| 0x5B0 | 8×6 | names: Crono Marle Lucca Robo Frog Ayla Magus Epoch. Encoding 0xA0–0xB9 = `A`–`Z`, 0xBA–0xD3 = `a`–`z`, 0x00 or 0xFF = end/pad (Frog is stored `a5 cb c8 c0 ff 00`) |
| 0x5E0 | 3 | gold (u24) |
| 0x5E3 | 6 | play time as **digits**: frames (0–59), seconds (0–59), minutes ones, minutes tens, hours ones, hours tens. Verified monotonic over all 53 slots (e.g. `03 10 09 03 01 02` = 21h39m16s) |
| 0x5EB | 8 | constant `80 08 40 08 02 04 10 20` |
| 0x5F3 | 2 | location id (u16) |
| 0x5F5 | 2 | player x, y (tiles) |
| 0x5F7 | 9 | facing / previous location etc. |
| 0x600 | 2 | ? (`0d 0a` seen) |
| 0x602 | 512 | **event flags** (byte-identical block in the port, §3) |
| 0x802 | ~0x2E | misc; 0x830–0x9FF is 0xFF |

## 3. Port save (`save_NN.bin` on Steam, `Chrono_sp_N_0.dat` on Android/Switch)

### 3.1 Container

`8-byte header + Blowfish-CBC ciphertext`, ported from `reference/ChronoMod/ChronoCrypto.cpp`
(`ctcrypto.py`, verified against Blowfish test vectors and decrypting all 45 files):

- Blowfish, standard π P/S init, **8-byte key `BA EC B7 8A EA 25 CA E4`** (from the Steam exe;
  assumed identical in `libchrono.so` — unverified), big-endian 64-bit blocks.
- IV = header XOR `75 FA 29 95 05 4D 41 5F`. CBC decrypt: `P_i = D(C_i) ^ C_{i-1}`.
- Plaintext = `payload[N] ‖ u16 checksum ‖ pad to 8 ‖ u32 LE N`. The pad bytes are
  uninitialised garbage. N = 7882 for every normal save; 7889 for the one save with a
  7-char-longer name; 447 for `meta.bin` (a JSON `{"slotInfos":[{"savedTime":…}×23]}`).
- **There is no checksum.** `nsCrypt::Manager::encrypt` (libchrono.so @0x6e69b0) computes
  `size = (len+11) & ~7`, copies the payload, fills `[len, size-4)` with `rand()%256`, stores
  the u32 length, picks 8 random IV bytes and runs Blowfish-CBC. The "u16" is random padding
  (which is why no CRC/sum/hash ever matched). Nothing is verified on load beyond the length.
- **Payload byte 0 is a format version: the Android reader (`readSaveDataFromBuffer`
  @0x5bf624) accepts only 1.** Steam files carry 3 with an otherwise identical layout; set
  the byte to 1 and they load on Android (verified on the Thor, 2026-09-15).
- `common.bin` (42 bytes) does not decrypt to anything structured with this key.

### 3.2 Payload — a serialized stream, not a fixed struct

Names are length-prefixed, so everything after them shifts (the renamed-character save
proves it: identical content at +7 from 0xB0F on). Offsets below are for default names.

| Off | Size | Field | SNES source |
|---|---|---|---|
| 0x000 | 0x200 | **region A**: u16-ish sparse table, `[0]=3`, `[0x1FF]=3`; values recur in the 0x400 block. Map/scene runtime state. Not from the SRAM slot. | — (take from template) |
| 0x200 | 0x200 | **event flags** | slot `0x602..0x802`, byte for byte (delta confirmed on 11 pairs, ~370 matching non-zero bytes; `0x1FE..0x200` probably = slot `0x600..0x602`) |
| 0x400 | 0x30 | scene state (small ints, related to region A) | — (template) |
| 0x430 | 7×0x58 | character records (§4) | slot 0x200 + c×0x50 |
| 0x698 | 111×3 | inventory, weapons: `{u8 idx, u8 0x00, u8 count}` | items 0x01–0x5A → idx = id |
| 0x7E5 | 50×3 | armour `{idx, 0x10, count}` | 0x5B–0x7B → idx = id − 0x5A |
| 0x87B | 39×3 | helmets `{idx, 0x20, count}` | 0x7C–0x94 → idx = id − 0x7B |
| 0x8F0 | 59×3 | accessories `{idx, 0x30, count}` | 0x95–0xBC → idx = id − 0x94 (verified: Bandana 1, Ribbon 2, Defender 4, MagicScarf 5, Berserker 0x17, SpeedBelt 0x19; Lucca's SightScope 0xA4 → 0x10) |
| 0x9A1 | 43×3 | consumables `{idx, 0x40, count}` | 0xBD–0xCF → idx = id − 0xBC (verified Tonic..Shelter, Power/Magic/Speed Tab) |
| 0xA22 | 45×3 | key items `{idx, 0x50, count}` | 0xD0–0xE7 → idx = id − 0xCF, linear like the other categories. Verified by first-appearance chapter: Petal/Fang/Horn/Feather 1–4, Bike Key 6, Pendant 7, Gate Key 8, PrismShard 9 (ch28), C.Trigger 0x0A (ch27 Time Egg), DreamStone 0x0D (present ch14–15, gone after the Masamune is reforged), Sun Stone 0x10 and Ruby Knife 0x11 (ch24–25). 0x1D/0x23 seen late are DS/port-only items with no SNES id |
| 0xAA9 | 7 | techs learned count per char | slot 0x430 |
| 0xAB0 | 7 | single-tech bitmasks | slot 0x437 |
| 0xAB7 | 31 | dual/triple-tech bitmasks | slot 0x43E (same length, same order) |
| 0xAD6 | var | **10 length-prefixed ASCII names**: Crono Marle Lucca Robo Frog Ayla Magus Epoch, then "Crono", "Marle" again (purpose unknown; keep template's) | slot 0x5B0 decoded |
| +0x00 | 3 | active party | slot 0x580 |
| +0x03 | 6 | reserve list | slot 0x583 |
| +0x09 | 1 | recruited bitmask (0xC0 → 0xE8 when Frog joins ch3 → 0xF8 Robo → 0xFC Ayla → 0xFE Magus) | slot 0x5AF |
| +0x0A | 1 | ? (0 except one save = 0x10) | — |
| +0x0B | 3 | gold u24 | slot 0x5E0 |
| +0x0E | 1 | counter, = +0x12 (0 normally; NG+ saves 2..12) | — (template) |
| +0x0F | 3 | play time, u24 seconds (ch1 ≈ 21 min … ch29 ≈ 32.5 h) | (10·h10+h1)·3600 + (10·m10+m1)·60 + s from slot 0x5E3 |
| +0x12 | 1 | same counter as +0x0E | — |
| +0x13 | 2 | 0 | |
| +0x15 | 2 | save-menu location-name id (End of Time = 0x3E, Leene Square = 6, …) | — (template) |
| +0x17 | 2 | bitmask that only grows through the game (0x307 → 0x317 → 0x31F → 0x33F → 0x3BF → 0x3FF): eras/gates unlocked | — (template) |
| +0x19 | … | zeros to 0x1E00 | |
| 0x1E0C | 0xC4 | field state: `u8 n; …; 3×(u16 x, u16 y)` party sprite positions at 0x1E13, `70 02 a0 01` at 0x1E20, more map/camera bytes, `64 64 01 01` near the end | — (template) |

The 0x1E0C block and region A/0x400 block are the *where-am-I-and-what-is-on-screen* state.
Plan: never translate them — pick the chapter template whose story point matches the SNES
flags and keep its scene state, so the imported game resumes at that chapter's save point.

## 4. Character record: SNES 0x50 → port 0x58

The port widened every field to 16/32 bits and **swapped max/current order for HP and MP**.

| Port | SNES | Field |
|---|---|---|
| +00 u8 | +00 | character id |
| +01 u8 | — | per-character constant (80 20 10 00 20 00 40 for Crono..Magus) |
| +02 u8 | +01 | 0x86 0x2A 0x1A 0x44 0x24 0x1C 0x42 |
| +03 u8 | +02 | 0x80 |
| +04 u16 | +05 | **max HP** (effective, incl. accessory e.g. Silver Earring +25 %) |
| +06 u16 | +03 | **current HP** |
| +08 u16 | +09 | **max MP** |
| +0A u16 | +07 | **current MP** |
| +0C u16 | +3F | base max HP (no accessory) |
| +0E..+15 | +0B..+12 | base power, stamina, speed, magic, hit, evade, m.def, **level** |
| +16 u32 | +13 u24 | experience |
| +1A u32 | +16 u16 | TP to next tech — but the port writes the sentinel **9 999 999** for every character from about chapter 7 on (Crono) / once recruited later; early values match SNES exactly (0x75 at lvl 7). Semantics unclear; see §6 |
| +1E..+2B | +18..+26 | zeros |
| +2C 4×u16 | +27..+2A | equipment as `(category<<12)\|idx`: weapon 0x0xxx, armour 0x1xxx, helmet 0x2xxx, accessory 0x3xxx (same idx mapping as inventory). SNES order is helmet, armour, weapon, accessory; port order weapon, armour, helmet, accessory |
| +34 u16 | +2B | exp to next level |
| +36 u16 | +2D | TP-related (Robo 5, Ayla 0x0A, Frog 0x0F, Magus 0x190 as defaults — matches SNES +2D) |
| +38 6×u8 | +2F,+30,+32,+33,+34,+35 | growth / element bytes; SNES +31 is dropped |
| +3E 9×u8 | +36..+3E | current power, stamina, speed, magic, hit, evade, m.def, attack, defence |
| +47 4×u8 | — | per-character constants (`05 04 04 04`, `04 04 05 09`, `04 04 04 0A`, `04 05 04 04`, `04 04 05 04`, `04 04 04 0A`, `04 05 04 04`) |
| +4B..+57 | (+41..+4F not carried) | zeros |

Unrecruited characters carry default records in both formats (Magus lvl 37, Ayla lvl 18…).

## 5. Conversion design (template overlay)

1. Parse SNES slot; verify checksum.
2. Pick the Steam chapter template whose flag block is closest (Hamming distance over
   `0x200..0x400`) to the SNES flags — this also fixes location, scene state, region A,
   +0x15 location-name id and +0x17 era mask coherently.
3. Overlay from SNES: flags (verbatim), 7 character records (§4), inventory (re-binned by
   category), tech block (verbatim 45 bytes), names (decoded), party/reserve (9 bytes
   verbatim), recruited mask, gold, play time.
4. Serialize (names re-encoded → stream shifts are handled by the writer), append checksum
   (unknown → currently copy-nothing/zero), pad, length; encrypt with a fresh IV.
5. Install as `Chrono_sp_<slot>_0.dat` in the ChronoDuo files dir and update `meta.bin`'s
   `slotInfos[slot].savedTime` (Unix *seconds* as a decimal string with thousands separators, e.g. `"1,650,489,262"`; unused slots hold `"0"` or `"1"`).

Slot naming on Steam: slots 1–3, 5–6 → `save_00..02, 04, 05`; slot 4 → `save_20`;
quicksave → `save_03`. Android names `Chrono_sp_3_0.dat`, `_4_0`, `_5_0` were reported on
GameFAQs — the index↔slot mapping still needs confirming on device.

## 6. Device results (Ayn Thor, 2026-09-15)

Resolved with the library and a live test — the four pushed saves (three Steam chapters with
byte 0 := 1, one converted SNES slot) all appear in the load menu and load:

1. No checksum (§3.1). Key and IV magic are byte-identical in the Android lib.
2. File names: `Chrono_sp_%d_0.dat` with %d = menu slot + 3 for slots 0..19, **slot 3 → 23**,
   suspend slot 20 → 6 (`getSaveDataFileName` @0x5bf214). `meta.bin` = same container around
   the slotInfos JSON; the game re-saves `savedTime` as the parsed integer.
3. The engine's writable path is `FileUtils::getWritablePath()`; ChronoDuo now sets that to
   `getExternalFilesDir(null)` (was the internal files dir) and migrates old saves, so
   `/sdcard/Android/data/com.kalenjohnson.chronoduo/files/` is the save directory.

Still open: item names for an import UI (port item table), semantics of char +1A / +36, and
how far the template's scene state may diverge from the SNES flags before something breaks.
