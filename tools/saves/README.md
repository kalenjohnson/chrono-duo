# Chrono Trigger save conversion tools

Convert an SNES Chrono Trigger `.srm` save into the format used by the
2018 Steam/mobile port, for import into ChronoDuo. See `REPORT.md` for the
full reverse-engineered format spec; the code here implements it.

## Fixtures

| Dir | What | Source |
|---|---|---|
| `snes/fantasyanime/ctsaveNN-*/chrono_trigger.srm` | 16 SRMs x 3 slots, whole game in order | fantasyanime.com/squaresoft/ctsaves-srm.htm |
| `snes/retromaggedon/sN/.../Chrono Trigger (USA).srm` | 5 SRMs, slot 0 only | retromaggedon.com |
| `steam/NN_chapter.bin` | 31 chapter saves + 12 NG+ (`X*`) saves + `meta.bin` + `common.bin` | Steam Community guide 2482326379 (Google Drive) |
| `steam/*.bin.dec` | decrypted plaintext (generated, git-ignored) | `python3 ctcrypto.py steam/*.bin` |

## Modules

- `ctcrypto.py` -- Blowfish-CBC container encrypt/decrypt (pre-existing, unmodified).
- `snes_srm.py` -- parses an 8 KB `.srm` into `SnesSlot`/`SnesChar` records and
  verifies the per-slot checksum.
- `ctsave.py` -- `CtSave`/`CtChar` stream model of the port's save payload;
  `load()`/`save()` handle the encrypted container.
- `convert.py` -- `snes_to_ct()`: picks the closest-matching chapter template
  and overlays SNES data onto it.
- `test_saves.py` -- parsing, round-trip, and conversion checks over every fixture.
- `JavaSaveCheck.java` -- desktop parity check for the Java port of these
  modules (`app/src/main/java/com/kalenjohnson/chronoduo/saveimport/`, used
  by ChronoDuo's in-app "Import SNES save" feature). Compares every used
  SRM slot's converted payload byte-for-byte against a Python-generated
  reference, and every `steam/*.bin` template's decrypt-parse-serialize-
  re-encrypt round trip against the original file bytes. Build and run from
  this directory:
  ```
  javac -d /tmp/svclasses ../../app/src/main/java/com/kalenjohnson/chronoduo/saveimport/*.java JavaSaveCheck.java
  java -cp /tmp/svclasses JavaSaveCheck <manifest.tsv> <refDir>
  ```
  `<manifest.tsv>`/`<refDir>` come from a one-off Python helper (not
  checked in) that imports `ctsave`/`snes_srm`/`convert` directly and dumps
  each used slot's `snes_to_ct(...).serialize()` bytes plus a manifest line
  `<srm>\t<slot>\t<template basename>\t<hamming distance>\t<ref file>`.

## Running

```
# Summarize every slot of one or more .srm files:
python3 snes_srm.py snes/fantasyanime/ctsave01-kidnapping/chrono_trigger.srm

# Convert one SNES save slot to a port save:
python3 convert.py <path.srm> <slot 0-2> out.bin [--template steam/16_thefiendlordskeep.bin]

# Run the full test suite:
python3 test_saves.py
```

## Known issues / open items

- **SNES slot checksum**: add-with-carry over the 1280 words summed from
  0x9FE *down* to 0x000, final carry dropped -- 53/53 fixture slots verify.
  Forward order only matches 43 (the carry chain differs).
- **Port save name encoding**: REPORT.md #3.2 calls the length-prefixed names
  ASCII. One fixture (`steam/X12_dreamsepilogue.bin`) has a custom Chinese
  name for Epoch stored as UTF-8 (length byte 12 for 4 three-byte
  characters), so `ctsave.py` decodes/encodes names as UTF-8 (a superset of
  ASCII) rather than pure ASCII.
- **Party byte "empty" marker**: REPORT.md #2 documents `0x80` as the empty
  marker in the active-party bytes. `ctsave01-kidnapping` slot 2 has party
  bytes `0x81` in the same role; code treats any party byte `>= 7` as empty
  rather than only exactly `0x80`.
- **Trailer checksum** (REPORT.md #3.1): still unknown. `CtSave.checksum`
  holds whatever was loaded (or 0 for a fresh save); `ctsave.save()` writes
  that value back verbatim. Coordinate any future work on this against
  `libchrono.so`, per REPORT.md #6.
- **Character `+0x1A` TP-to-next-tech / `+0x36` fields** (REPORT.md #4, #6.4):
  semantics are still unclear (values like 131072 show up for active
  characters in early saves that don't look like meaningful TP counts).
  `convert.py` copies the SNES value across per the documented field mapping
  without trying to reinterpret it.
