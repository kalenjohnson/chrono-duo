package com.kalenjohnson.chronoduo.saveimport;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Container framing for a Chrono Trigger (2018 Steam/mobile) port save file,
 * ported byte-for-byte from tools/saves/ctcrypto.py plus the container-level
 * facts in tools/saves/ctsave.py's {@code load()}/{@code save()}.
 *
 * <p>On disk: 8-byte header (random IV XORed with a fixed magic) followed by
 * Blowfish-CBC ciphertext. Decrypting yields
 * {@code payload[N] ‖ random bytes ‖ u32 LE N} -- there is no checksum
 * (confirmed against libchrono.so): {@code nsCrypt::Manager::encrypt}
 * computes {@code size = (len+11) & ~7}, fills {@code [len, size-4)} with
 * {@code rand()%256}, and stores the u32 length at {@code [size-4, size)}.
 */
public final class CtContainer {
    private static final byte[] HDR_MAGIC = {
            0x75, (byte) 0xFA, 0x29, (byte) 0x95, 0x05, 0x4D, 0x41, 0x5F,
    };

    private CtContainer() {
    }

    private static Blowfish bf;

    private static synchronized Blowfish blowfish() {
        if (bf == null) bf = new Blowfish();
        return bf;
    }

    /**
     * Decrypts a full container file to its raw plaintext (payload + random
     * trailer, exactly as it comes out of Blowfish-CBC -- nothing stripped).
     * Mirrors {@code ctcrypto.decrypt}.
     */
    public static byte[] decryptToPlaintext(byte[] data) {
        if (data.length < 8) {
            throw new IllegalArgumentException("container too short: " + data.length + " bytes");
        }
        Blowfish b = blowfish();
        byte[] prev = new byte[8];
        for (int i = 0; i < 8; i++) prev[i] = (byte) (data[i] ^ HDR_MAGIC[i]);

        int cipherLen = data.length - 8;
        int blocks = cipherLen / 8; // matches Python's range(8, len(data)-7, 8): trailing partial block dropped
        byte[] out = new byte[blocks * 8];
        for (int blk = 0; blk < blocks; blk++) {
            int off = 8 + blk * 8;
            long l = readU32BE(data, off);
            long r = readU32BE(data, off + 4);
            long[] lr = b.decryptBlock(l, r);
            byte[] p = new byte[8];
            writeU32BE(p, 0, lr[0]);
            writeU32BE(p, 4, lr[1]);
            for (int i = 0; i < 8; i++) {
                out[blk * 8 + i] = (byte) (p[i] ^ prev[i]);
            }
            System.arraycopy(data, off, prev, 0, 8);
        }
        return out;
    }

    /**
     * Encrypts raw plaintext (payload + trailer, already built by the
     * caller) with the given 8-byte IV. Mirrors {@code ctcrypto.encrypt}.
     */
    public static byte[] encryptFromPlaintext(byte[] plaintext, byte[] iv) {
        if (plaintext.length % 8 != 0) {
            throw new IllegalArgumentException("plaintext length must be a multiple of 8, got " + plaintext.length);
        }
        if (iv.length != 8) {
            throw new IllegalArgumentException("iv must be 8 bytes");
        }
        Blowfish b = blowfish();
        byte[] out = new byte[8 + plaintext.length];
        for (int i = 0; i < 8; i++) out[i] = (byte) (iv[i] ^ HDR_MAGIC[i]);
        byte[] prev = iv;
        for (int off = 0; off < plaintext.length; off += 8) {
            byte[] x = new byte[8];
            for (int i = 0; i < 8; i++) x[i] = (byte) (plaintext[off + i] ^ prev[i]);
            long l = readU32BE(x, 0);
            long r = readU32BE(x, 4);
            long[] lr = b.encryptBlock(l, r);
            byte[] c = new byte[8];
            writeU32BE(c, 0, lr[0]);
            writeU32BE(c, 4, lr[1]);
            System.arraycopy(c, 0, out, 8 + off, 8);
            prev = c;
        }
        return out;
    }

    /**
     * Decrypts a full container file and strips the trailer, returning just
     * {@code payload[N]} (the u32 LE length stored at the end of the
     * plaintext names N). Mirrors {@code ctsave.load}'s payload extraction.
     */
    public static byte[] decrypt(byte[] data) {
        byte[] plain = decryptToPlaintext(data);
        if (plain.length < 4) {
            throw new IllegalArgumentException("decrypted container too short: " + plain.length + " bytes");
        }
        long n = readU32LE(plain, plain.length - 4);
        if (n < 0 || n > plain.length - 4) {
            throw new IllegalArgumentException("bad payload length " + n + " in decrypted container of "
                    + plain.length + " bytes");
        }
        return Arrays.copyOfRange(plain, 0, (int) n);
    }

    /**
     * Builds a fresh container file around {@code payload}: random padding
     * to {@code size = (len+11) & ~7}, u32 LE length trailer, encrypted with
     * a freshly random 8-byte IV. Matches the on-device encoder
     * (nsCrypt::Manager::encrypt, REPORT.md #3.1) -- there is no checksum,
     * only random fill.
     */
    public static byte[] encrypt(byte[] payload, SecureRandom random) {
        int len = payload.length;
        int size = (len + 11) & ~7;
        byte[] plain = new byte[size];
        System.arraycopy(payload, 0, plain, 0, len);
        byte[] randomFill = new byte[size - 4 - len];
        random.nextBytes(randomFill);
        System.arraycopy(randomFill, 0, plain, len, randomFill.length);
        writeU32LE(plain, size - 4, len);

        byte[] iv = new byte[8];
        random.nextBytes(iv);
        return encryptFromPlaintext(plain, iv);
    }

    private static long readU32BE(byte[] a, int off) {
        return ((a[off] & 0xFFL) << 24) | ((a[off + 1] & 0xFFL) << 16)
                | ((a[off + 2] & 0xFFL) << 8) | (a[off + 3] & 0xFFL);
    }

    private static void writeU32BE(byte[] a, int off, long v) {
        a[off] = (byte) ((v >>> 24) & 0xFF);
        a[off + 1] = (byte) ((v >>> 16) & 0xFF);
        a[off + 2] = (byte) ((v >>> 8) & 0xFF);
        a[off + 3] = (byte) (v & 0xFF);
    }

    static long readU32LE(byte[] a, int off) {
        return (a[off] & 0xFFL) | ((a[off + 1] & 0xFFL) << 8)
                | ((a[off + 2] & 0xFFL) << 16) | ((a[off + 3] & 0xFFL) << 24);
    }

    static void writeU32LE(byte[] a, int off, long v) {
        a[off] = (byte) (v & 0xFF);
        a[off + 1] = (byte) ((v >>> 8) & 0xFF);
        a[off + 2] = (byte) ((v >>> 16) & 0xFF);
        a[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }
}
