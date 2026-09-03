package com.kalenjohnson.chronoduo.dsimport;

import java.util.Arrays;

/**
 * "Code LZ" / BLZ decompression, as used for the ARM9 main-code tail and
 * (optionally) ARM9 code overlays. Direct port of ndspy.codeCompression
 * .decompress (tools/ds_maps venv), itself ported from DSDecmp.
 *
 * Format summary (see the original Python docstring for the full story):
 * the data is basically LZ-0x10 compressed, but read back-to-front from the
 * end of the file, with a small footer (compressedLen/headerLen/extraSize)
 * living at the very end, and +2 added to the DISP value.
 */
public final class Blz {

    private Blz() {}

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    /**
     * Mirrors ndspy's _detectAppendedData. Returns null if the data doesn't
     * look compressed at all; otherwise the number of trailing bytes (a
     * multiple of 4, 0..0x1C) that are appended raw data sitting after the
     * compressed payload.
     *
     * NOTE: the inner "padding must be 0xFF" loop in the original Python is
     * a no-op (its `continue` only continues the inner loop), so it never
     * actually rejects anything. That quirk is intentionally preserved here
     * for byte-for-byte behavioral parity.
     */
    private static Integer detectAppendedData(byte[] data) {
        for (int possibleAmt = 0; possibleAmt < 0x20; possibleAmt += 4) {
            int idx = data.length - 5 - possibleAmt;
            if (idx < 0) return null;
            // headerLen read here is immediately overwritten below, as in
            // the Python (it's dead code upstream too -- kept for parity).

            int off = data.length - possibleAmt - 8;
            if (off < 0) return null;
            int compLenHeaderLen = u32(data, off);
            // extraSize at off+4 is read but unused here, same as upstream.

            int headerLen = (compLenHeaderLen >>> 24) & 0xFF;
            int compressedLen = compLenHeaderLen & 0xFFFFFF;

            if (headerLen < 8) continue;
            if (compressedLen > data.length) continue;

            // (upstream no-op padding-check loop intentionally omitted --
            // it never affects control flow; see note above)

            return possibleAmt;
        }
        return null;
    }

    public static byte[] decompress(byte[] input) {
        Integer appendedDataAmount = detectAppendedData(input);

        if (appendedDataAmount == null) {
            // Probably not compressed.
            return input;
        }

        byte[] data = input;
        byte[] appendedData = new byte[0];
        if (appendedDataAmount > 0) {
            appendedData = Arrays.copyOfRange(data, data.length - appendedDataAmount, data.length);
            data = Arrays.copyOfRange(data, 0, data.length - appendedDataAmount);
        }

        // If extraSize (the last value in the header) is 0, the data is not
        // actually compressed.
        if (data.length >= 4
                && data[data.length - 4] == 0 && data[data.length - 3] == 0
                && data[data.length - 2] == 0 && data[data.length - 1] == 0) {
            return data;
        }

        int compLenHeaderLen = u32(data, data.length - 8);
        int extraSize = u32(data, data.length - 4);
        int headerLen = (compLenHeaderLen >>> 24) & 0xFF;
        int compressedLen = compLenHeaderLen & 0xFFFFFF;

        if (data.length < headerLen) {
            throw new IllegalArgumentException("File is too small for header (" + data.length + " < " + headerLen + ")");
        }
        if (compressedLen > data.length) {
            throw new IllegalArgumentException("Compressed length doesn't fit in the input file");
        }

        for (int i = data.length - headerLen; i < data.length - 8; i++) {
            if ((data[i] & 0xFF) != 0xFF) {
                throw new IllegalArgumentException("Header padding isn't entirely 0xFF");
            }
        }

        if (compressedLen >= data.length) {
            compressedLen = data.length;
        }

        int passthroughLen = data.length - compressedLen;
        byte[] passthroughData = Arrays.copyOfRange(data, 0, passthroughLen);

        byte[] compData = Arrays.copyOfRange(data, passthroughLen, passthroughLen + compressedLen - headerLen);
        int decompLen = data.length + extraSize - passthroughLen;
        byte[] decompData = new byte[decompLen];

        int currentOutSize = 0;
        int readBytes = 0;
        int flags = 0;
        int mask = 1;

        while (currentOutSize < decompLen) {
            if (mask == 1) {
                if (readBytes >= compressedLen) {
                    throw new IllegalStateException("Not enough data to decompress");
                }
                flags = compData[compData.length - 1 - readBytes] & 0xFF;
                readBytes++;
                mask = 0x80;
            } else {
                mask >>>= 1;
            }

            if ((flags & mask) != 0) {
                if (readBytes + 1 >= data.length) {
                    throw new IllegalStateException("Not enough data to decompress");
                }
                int byte1 = compData[compData.length - 1 - readBytes] & 0xFF;
                readBytes++;
                int byte2 = compData[compData.length - 1 - readBytes] & 0xFF;
                readBytes++;

                int length = (byte1 >>> 4) + 3;
                int disp = (((byte1 & 0x0F) << 8) | byte2) + 3;

                if (disp > currentOutSize) {
                    if (currentOutSize < 2) {
                        throw new IllegalStateException(
                                "Cannot go back more than already written; attempted to go back "
                                        + disp + " bytes when only " + currentOutSize + " bytes have been written");
                    }
                    // HACK (kept for parity with ndspy/DSDecmp): treat as disp=2.
                    disp = 2;
                }

                int bufIdx = currentOutSize - disp;
                for (int i = 0; i < length; i++) {
                    byte next = decompData[decompData.length - 1 - bufIdx];
                    bufIdx++;
                    decompData[decompData.length - 1 - currentOutSize] = next;
                    currentOutSize++;
                    if (currentOutSize == decompLen) break;
                }
            } else {
                if (readBytes > data.length) {
                    throw new IllegalStateException("Not enough data to decompress");
                }
                byte next = compData[compData.length - 1 - readBytes];
                readBytes++;
                decompData[decompData.length - 1 - currentOutSize] = next;
                currentOutSize++;
            }
        }

        byte[] result = new byte[passthroughData.length + decompData.length + appendedData.length];
        System.arraycopy(passthroughData, 0, result, 0, passthroughData.length);
        System.arraycopy(decompData, 0, result, passthroughData.length, decompData.length);
        System.arraycopy(appendedData, 0, result, passthroughData.length + decompData.length, appendedData.length);
        return result;
    }

    /** A single extracted "main code" section: raw bytes plus its RAM load address. */
    public static final class Section {
        public final byte[] data;
        public final int ramAddress;

        public Section(byte[] data, int ramAddress) {
            this.data = data;
            this.ramAddress = ramAddress;
        }
    }

    private static final byte[] CODE_SETTINGS_MAGIC = {
            (byte) 0x21, (byte) 0x06, (byte) 0xC0, (byte) 0xDE,
            (byte) 0xDE, (byte) 0xC0, (byte) 0x06, (byte) 0x21,
    };

    /**
     * Reproduces ndspy's MainCodeFile logic just far enough to extract
     * "sections[0]" (the implicit first section, spanning from the RAM base
     * up to wherever the code-settings copy-table's autoload data begins).
     * That's the section gen_calib.py reads the ARM9 room table (Table 3)
     * out of.
     *
     * codeSettingsPointerAddress is the ROM header field of the same name
     * (0 if unknown/unavailable, in which case we fall back to a magic-byte
     * scan for the code-settings struct, same as ndspy does for ARM7).
     */
    public static Section extractArm9Section0(byte[] arm9CompressedOrRaw, int ramAddress, int codeSettingsPointerAddress) {
        byte[] decompressed = decompress(arm9CompressedOrRaw);

        Integer codeSettingsOffs = null;

        if (codeSettingsPointerAddress != 0) {
            try {
                int ptrFileOffset = codeSettingsPointerAddress - ramAddress - 4;
                if (ptrFileOffset >= 0 && ptrFileOffset + 4 <= decompressed.length) {
                    int codeSettingsAddr = u32(decompressed, ptrFileOffset);
                    int offs = codeSettingsAddr - ramAddress;
                    if (offs >= 0 && offs < decompressed.length - 4) {
                        codeSettingsOffs = offs;
                    }
                }
            } catch (Exception ignored) {
                // fall through to magic scan
            }
        }

        if (codeSettingsOffs == null) {
            int limit = Math.min(0x8000, decompressed.length - 8);
            for (int i = 0; i <= limit; i += 4) {
                boolean match = true;
                for (int j = 0; j < 8; j++) {
                    if (decompressed[i + j] != CODE_SETTINGS_MAGIC[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    int offs = i - 0x1C;
                    if (offs >= 0) {
                        codeSettingsOffs = offs;
                    }
                    break;
                }
            }
        }

        int dataBegin;
        if (codeSettingsOffs != null && codeSettingsOffs + 12 <= decompressed.length) {
            dataBegin = u32(decompressed, codeSettingsOffs + 8) - ramAddress;
        } else {
            dataBegin = decompressed.length;
        }

        int end = Math.max(0, Math.min(dataBegin, decompressed.length));
        byte[] section0 = Arrays.copyOfRange(decompressed, 0, end);
        return new Section(section0, ramAddress);
    }
}
