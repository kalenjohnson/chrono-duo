package com.kalenjohnson.chronoduo.dsimport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal Nintendo DS ROM (.nds) reader: header, filename table (FNT),
 * file allocation table (FAT), ARM9 main-code metadata, and the ARM9
 * overlay table. Reads files on demand through a {@link SeekableSource}
 * rather than slurping the whole ROM into memory. Android-free.
 *
 * Field layout / semantics ported from ndspy.rom.NintendoDSRom and
 * ndspy.fnt (tools/ds_maps venv).
 */
public final class NitroRom {

    /** Hard cap on any single {@link #readRange} request -- guards against a corrupt/garbage
     * header (or a file that isn't an NDS ROM at all) driving an allocation big enough to OOM
     * the process, as happened when a picked .zip was fed straight in without validation. */
    private static final int MAX_READ_BYTES = 16 * 1024 * 1024; // 16 MB

    /** Sanity cap for the FNT/FAT/ARM9 table sizes read out of the header. */
    private static final int MAX_TABLE_BYTES = 4 * 1024 * 1024; // 4 MB

    /** CRC16 of the 156-byte Nintendo logo bitmap, stored at header offset 0x15C on every
     * legitimate NDS/DSi cartridge (checked by the console's own boot ROM). */
    private static final int LOGO_CRC16 = 0xCF56;

    /** Chrono Trigger DS game codes: USA, EUR, JPN. "YQUE" is the actual
     * header code on the "Chrono Trigger (USA) (En,Fr).nds" dump used
     * throughout this project (verified directly from the ROM header at
     * offset 0x0C) -- "YCTE" does not appear on any real dump checked. */
    private static final String[] VALID_GAME_CODES = {"YQUE", "YQUP", "YQUJ"};

    private final SeekableSource src;

    public int arm9RamAddress;
    public int arm9CodeSettingsPointerAddress;
    public byte[] arm9; // raw (possibly BLZ-compressed-at-tail) ARM9 code
    public byte[] arm9OverlayTable; // 32 bytes per entry

    private long[] fatStart;
    private long[] fatEnd;

    public Folder filenames;

    /** One folder in the FNT tree: an ordered list of subfolders and file names. */
    public static final class Folder {
        public final List<String> fileNames = new ArrayList<>();
        public final List<String> subfolderNames = new ArrayList<>();
        public final List<Folder> subfolders = new ArrayList<>();
        public int firstID;

        /** Look up a subfolder by "/"-separated path, relative to this folder. */
        public Folder subfolder(String path) {
            List<String> parts = splitPath(path);
            Folder cur = this;
            outer:
            for (String part : parts) {
                for (int i = 0; i < cur.subfolderNames.size(); i++) {
                    if (cur.subfolderNames.get(i).equals(part)) {
                        cur = cur.subfolders.get(i);
                        continue outer;
                    }
                }
                return null;
            }
            return cur;
        }

        /** Find the file ID for a "/"-separated path relative to this folder, or -1. */
        public int idOf(String path) {
            List<String> parts = splitPath(path);
            if (parts.isEmpty()) return -1;
            Folder cur = this;
            for (int i = 0; i < parts.size() - 1; i++) {
                Folder next = null;
                for (int j = 0; j < cur.subfolderNames.size(); j++) {
                    if (cur.subfolderNames.get(j).equals(parts.get(i))) {
                        next = cur.subfolders.get(j);
                        break;
                    }
                }
                if (next == null) return -1;
                cur = next;
            }
            String last = parts.get(parts.size() - 1);
            int idx = cur.fileNames.indexOf(last);
            if (idx < 0) return -1;
            return cur.firstID + idx;
        }

        private static List<String> splitPath(String path) {
            List<String> parts = new ArrayList<>();
            for (String p : path.split("/")) {
                if (!p.isEmpty()) parts.add(p);
            }
            return parts;
        }
    }

    public NitroRom(SeekableSource src) throws IOException {
        this.src = src;
        parseHeader();
    }

    private byte[] readRange(long pos, int len) throws IOException {
        if (len < 0 || len > MAX_READ_BYTES) {
            throw new IOException("Refusing to read " + len + " bytes at offset " + pos
                    + " (over the " + (MAX_READ_BYTES / (1024 * 1024)) + " MB cap) -- "
                    + "ROM header looks corrupt or this isn't an NDS ROM");
        }
        if (pos < 0 || pos > src.length() || len > src.length() - pos) {
            throw new IOException("ROM read of " + len + " bytes at offset " + pos
                    + " runs past the end of the file (length " + src.length() + ")");
        }
        byte[] buf = new byte[len];
        int got = 0;
        while (got < len) {
            int n = src.read(pos + got, buf, got, len - got);
            if (n < 0) throw new IOException("Unexpected EOF reading ROM at " + (pos + got));
            got += n;
        }
        return buf;
    }

    /** Verifies a header-declared (offset, length) pair -- used for the ARM9/FNT/FAT tables --
     * lies fully within the source and isn't implausibly large before anything reads it. */
    private void checkTable(String name, long offset, int len) throws IOException {
        long length = src.length();
        if (len < 0 || len > MAX_TABLE_BYTES) {
            throw new IOException("Not a valid NDS ROM (" + name + " size " + len
                    + " bytes is out of range)");
        }
        if (offset < 0 || offset > length || len > length - offset) {
            throw new IOException("Not a valid NDS ROM (" + name + " at offset " + offset
                    + " length " + len + " runs past the end of the file, length " + length + ")");
        }
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private void parseHeader() throws IOException {
        byte[] h = readRange(0, 0x200);

        // Game title (0x00-0x0B, 12 bytes): must be printable ASCII, zero-padded -- once a
        // zero byte appears, everything after it must also be zero.
        boolean sawZero = false;
        for (int i = 0x00; i < 0x0C; i++) {
            int b = h[i] & 0xFF;
            if (b == 0) {
                sawZero = true;
            } else if (sawZero || b < 0x20 || b > 0x7E) {
                throw new IOException("Not a valid NDS ROM (garbage game title bytes) -- "
                        + "did you pick the right file?");
            }
        }

        // Nintendo logo CRC16 at 0x15C: every real NDS/DSi cartridge carries this exact value
        // (the console's boot ROM refuses to run anything else), so it's a reliable cheap check
        // that this is actually an NDS ROM and not, say, a zip or some other file's bytes.
        int logoCrc = u16(h, 0x15C);
        if (logoCrc != LOGO_CRC16) {
            // Full expected/actual CRC values go to logcat (via AppActivity's catch-all
            // Log.e), not into this message -- it's rendered as one unwrapped line on the
            // settings screen's parchment, so keep it short.
            throw new IOException("not a valid NDS ROM (logo check failed)");
        }

        // Game code at 0x0C (4 bytes, ASCII): checked right after the logo CRC and before any
        // offset/size validation below, so a wrong-game NDS ROM (valid logo, but not Chrono
        // Trigger DS) reports "wrong game code" rather than tripping on that other game's FNT/
        // FAT/ARM9 layout and getting a more confusing "table out of range" error instead.
        String gameCode = new String(h, 0x0C, 4, java.nio.charset.StandardCharsets.US_ASCII);
        boolean validCode = false;
        for (String code : VALID_GAME_CODES) {
            if (code.equals(gameCode)) {
                validCode = true;
                break;
            }
        }
        if (!validCode) {
            throw new IOException("Not a Chrono Trigger DS ROM (game code " + gameCode + ")");
        }

        int arm9Offset = u32(h, 0x20);
        arm9RamAddress = u32(h, 0x28);
        int arm9Len = u32(h, 0x2C);

        int fntOffset = u32(h, 0x40);
        int fntLen = u32(h, 0x44);
        int fatOffset = u32(h, 0x48);
        int fatLen = u32(h, 0x4C);
        int arm9OvTOffset = u32(h, 0x50);
        int arm9OvTLen = u32(h, 0x54);

        arm9CodeSettingsPointerAddress = u32(h, 0x70);

        checkTable("ARM9", arm9Offset & 0xFFFFFFFFL, arm9Len);
        checkTable("FNT", fntOffset & 0xFFFFFFFFL, fntLen);
        checkTable("FAT", fatOffset & 0xFFFFFFFFL, fatLen);

        arm9 = readRange(arm9Offset & 0xFFFFFFFFL, arm9Len);
        arm9OverlayTable = arm9OvTLen > 0 ? readRange(arm9OvTOffset & 0xFFFFFFFFL, arm9OvTLen) : new byte[0];

        byte[] fnt = fntLen > 0 ? readRange(fntOffset & 0xFFFFFFFFL, fntLen) : new byte[0];
        byte[] fat = fatLen > 0 ? readRange(fatOffset & 0xFFFFFFFFL, fatLen) : new byte[0];

        int fileCount = fat.length / 8;
        fatStart = new long[fileCount];
        fatEnd = new long[fileCount];
        for (int i = 0; i < fileCount; i++) {
            fatStart[i] = u32(fat, 8 * i) & 0xFFFFFFFFL;
            fatEnd[i] = u32(fat, 8 * i + 4) & 0xFFFFFFFFL;
        }

        filenames = fnt.length > 0 ? loadFnt(fnt) : new Folder();
    }

    private static Folder loadFnt(byte[] fnt) {
        return loadFntFolder(fnt, 0xF000);
    }

    private static Folder loadFntFolder(byte[] fnt, int folderId) {
        Folder folder = new Folder();
        int off = 8 * (folderId & 0xFFF);
        int entriesTableOff = u32(fnt, off);
        int fileID = u16(fnt, off + 4);
        folder.firstID = fileID;

        off = entriesTableOff;
        while (true) {
            int control = fnt[off] & 0xFF;
            off += 1;
            if (control == 0) break;

            int len = control & 0x7F;
            boolean isFolder = (control & 0x80) != 0;

            String name = new String(fnt, off, len, java.nio.charset.StandardCharsets.ISO_8859_1);
            off += len;

            if (isFolder) {
                int subFolderID = u16(fnt, off);
                off += 2;
                Folder sub = loadFntFolder(fnt, subFolderID);
                folder.subfolderNames.add(name);
                folder.subfolders.add(sub);
            } else {
                folder.fileNames.add(name);
            }
        }
        return folder;
    }

    public int fileCount() {
        return fatStart.length;
    }

    public byte[] readFileById(int fileId) throws IOException {
        if (fileId < 0 || fileId >= fatStart.length) {
            throw new IndexOutOfBoundsException("No such file ID: " + fileId);
        }
        long start = fatStart[fileId];
        long end = fatEnd[fileId];
        return readRange(start, (int) (end - start));
    }

    /** Returns null if the path doesn't resolve to a file (mirrors Python's getfile() helper). */
    public byte[] readFileByPathOrNull(String path) throws IOException {
        int id = filenames.idOf(path);
        if (id < 0) return null;
        return readFileById(id);
    }

    public byte[] readFileByPath(String path) throws IOException {
        byte[] data = readFileByPathOrNull(path);
        if (data == null) throw new IllegalArgumentException("Cannot find file: " + path);
        return data;
    }

    /**
     * Finds and (if flagged) BLZ-decompresses the ARM9 overlay with the
     * given overlay ID (matched by the overlay table's ovID field, not by
     * table index). Returns null if not present.
     */
    public Blz.Section loadArm9Overlay(int overlayId) throws IOException {
        for (int off = 0; off + 32 <= arm9OverlayTable.length; off += 32) {
            int ovID = u32(arm9OverlayTable, off);
            if (ovID != overlayId) continue;

            int ramAddr = u32(arm9OverlayTable, off + 0x04);
            int fileID = u32(arm9OverlayTable, off + 0x18);
            int compressedSizeAndFlags = u32(arm9OverlayTable, off + 0x1C);
            int flags = (compressedSizeAndFlags >>> 24) & 0xFF;
            boolean compressed = (flags & 1) != 0;

            byte[] fileData = readFileById(fileID);
            byte[] data = compressed ? Blz.decompress(fileData) : fileData;
            return new Blz.Section(data, ramAddr);
        }
        return null;
    }
}
