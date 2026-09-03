package com.kalenjohnson.chronoduo.dsimport;

/**
 * Standard Nintendo GBA/NDS "LZ10" (type 0x10) decompression, used for the
 * NCG tile-graphics files in the DS minimap format. Direct port of
 * ndspy.lz10.decompress (tools/ds_maps venv), which is itself a port of
 * NSMBe's decompressor.
 */
public final class Lz10 {

    private Lz10() {}

    public static byte[] decompress(byte[] data) {
        if ((data[0] & 0xFF) != 0x10) {
            throw new IllegalArgumentException("Not an LZ10-compressed blob (bad type byte)");
        }

        int dataLen = ((data[1] & 0xFF) | ((data[2] & 0xFF) << 8) | ((data[3] & 0xFF) << 16));

        byte[] out = new byte[dataLen];
        int inPos = 4, outPos = 0;

        while (dataLen > 0) {
            int d = data[inPos++] & 0xFF;

            if (d != 0) {
                for (int i = 0; i < 8; i++) {
                    if ((d & 0x80) != 0) {
                        int hi = data[inPos] & 0xFF;
                        int lo = data[inPos + 1] & 0xFF;
                        inPos += 2;
                        int thing = (hi << 8) | lo;

                        int length = (thing >>> 12) + 3;
                        int offset = thing & 0xFFF;
                        int windowOffset = outPos - offset - 1;

                        for (int j = 0; j < length; j++) {
                            out[outPos] = out[windowOffset];
                            outPos++;
                            windowOffset++;
                            dataLen--;
                            if (dataLen == 0) return out;
                        }
                    } else {
                        out[outPos] = data[inPos];
                        outPos++;
                        inPos++;
                        dataLen--;
                        if (dataLen == 0) return out;
                    }
                    d = (d << 1) & 0xFF;
                }
            } else {
                for (int i = 0; i < 8; i++) {
                    out[outPos] = data[inPos];
                    outPos++;
                    inPos++;
                    dataLen--;
                    if (dataLen == 0) return out;
                }
            }
        }

        return out;
    }
}
