package com.kalenjohnson.chronoduo.dsimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Minimal PNG encoder: 8-bit RGBA, no filtering (filter type 0 per scanline),
 * zlib via java.util.zip.Deflater. No external dependencies.
 */
public final class PngWriter {

    private PngWriter() {}

    private static final byte[] SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    /** width/height taken from the array shape; argb is row-major 0xAARRGGBB. */
    public static void write(OutputStream out, int width, int height, int[] argb) throws IOException {
        out.write(SIGNATURE);

        // IHDR
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, width);
        writeInt(ihdr, height);
        ihdr.write(8);  // bit depth
        ihdr.write(6);  // color type: RGBA
        ihdr.write(0);  // compression method
        ihdr.write(0);  // filter method
        ihdr.write(0);  // interlace method
        writeChunk(out, "IHDR", ihdr.toByteArray());

        // IDAT: filter byte 0 + RGBA bytes per scanline, deflate-compressed.
        ByteArrayOutputStream raw = new ByteArrayOutputStream(height * (1 + width * 4));
        for (int y = 0; y < height; y++) {
            raw.write(0); // filter type: None
            for (int x = 0; x < width; x++) {
                int px = argb[y * width + x];
                int a = (px >>> 24) & 0xFF;
                int r = (px >>> 16) & 0xFF;
                int g = (px >>> 8) & 0xFF;
                int b = px & 0xFF;
                raw.write(r);
                raw.write(g);
                raw.write(b);
                raw.write(a);
            }
        }

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed, deflater)) {
            dos.write(raw.toByteArray());
        } finally {
            deflater.end();
        }
        writeChunk(out, "IDAT", compressed.toByteArray());

        // IEND
        writeChunk(out, "IEND", new byte[0]);
    }

    private static void writeChunk(OutputStream out, String type, byte[] data) throws IOException {
        writeInt(out, data.length);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(data);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
