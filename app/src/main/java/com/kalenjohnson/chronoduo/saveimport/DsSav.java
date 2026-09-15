package com.kalenjohnson.chronoduo.saveimport;

import java.io.UnsupportedEncodingException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for Nintendo DS Chrono Trigger {@code .sav} (64 KB EEPROM) saves,
 * ported byte-for-byte from tools/saves/ds_sav.py. See REPORT.md #7 for the
 * format -- with one correction found validating against the fixture: the
 * 7 character records actually start at DS slot offset 0x484, not the 0x480
 * the table names (everything downstream, from the inventory at 0x724 on,
 * already lines up with 0x484 + 7*0x60 = 0x724).
 */
public final class DsSav {
    public static final int SLOT_SIZE = 0x2800;
    public static final int NUM_SLOTS = 3;
    public static final int IMAGE_SIZE = 65536;

    public static final int ARDS_HEADER_SIZE = 500;
    public static final int ARDS_TOTAL_SIZE = 262644;
    public static final int DESMUME_FOOTER_SIZE = 122;
    private static final byte[] DESMUME_SIGNATURE;

    static {
        try {
            DESMUME_SIGNATURE = "|-DESMUME SAVE-|".getBytes("US-ASCII");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static final long SLOT_MAGIC = 0xFEDCBA98L;

    // Corrected vs. REPORT.md #7's table (see class doc): records actually
    // start here.
    private static final int CHAR_BLOCK_OFF = 0x484;
    private static final int CHAR_SIZE = 0x60;
    private static final int NUM_CHARS = 7;

    private static final int FLAGS_OFF = 0x250;
    private static final int FLAGS_SIZE = 0x200;
    private static final int SCENE_OFF = 0x450;
    private static final int SCENE_SIZE = 0x30;
    private static final int REGION_A_OFF = 0x050;
    private static final int REGION_A_SIZE = 0x200;

    private static final int TECH_BLOCK_OFF = 0xC90;
    private static final int TECH_BLOCK_SIZE = 45;

    private static final int NAMES_OFF = 0xCC0;
    private static final int NAME_STRIDE = 16;
    private static final int NUM_NAMES = 10;

    private static final int PARTY_OFF = 0xD60;
    private static final int GOLD_OFF = 0xD6C;
    private static final int SAVE_COUNT_OFF = 0xD70;
    private static final int PLAY_TIME_OFF = 0xD74;
    private static final int LOCATION_NAME_ID_OFF = 0xD80;
    private static final int ERA_MASK_OFF = 0xD82;

    /** (section name, capacity, expected category nibble), in stream order. */
    public static final class DsInventorySection {
        public final String name;
        public final int capacity;
        public final int category;

        DsInventorySection(String name, int capacity, int category) {
            this.name = name;
            this.capacity = capacity;
            this.category = category;
        }
    }

    public static final DsInventorySection[] INVENTORY_SECTIONS = {
            new DsInventorySection("weapons", 111, 0x0),
            new DsInventorySection("armour", 50, 0x1),
            new DsInventorySection("helmets", 39, 0x2),
            new DsInventorySection("accessories", 59, 0x3),
            new DsInventorySection("consumables", 43, 0x4),
            new DsInventorySection("key_items", 45, 0x5),
    };

    private DsSav() {
    }

    /** 16-byte fixed Shift-JIS string, NUL-padded; full-width -> ASCII via NFKC. */
    static String decodeDsName(byte[] raw, int off, int len) {
        int n = 0;
        while (n < len && raw[off + n] != 0) n++;
        String s;
        try {
            s = new String(raw, off, n, "Shift_JIS");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
        return Normalizer.normalize(s, Normalizer.Form.NFKC);
    }

    public static final class DsChar {
        public final byte[] raw;
        public final long id;
        public final int curHp, maxHp, curMp, maxMp, baseMaxHp;
        public final int power, stamina, speed, magic, hit, evade, mdef, level;
        public final int extraU16;
        public final long exp;
        public final long tpNext;
        public final int equipWeapon, equipArmor, equipHelmet, equipAccessory;
        public final int expNext, tpRelated;
        public final byte[] growth; // 6 bytes
        public final byte[] curStats; // 9 bytes
        public final byte[] perCharConstants; // 4 bytes

        private DsChar(byte[] raw, long id, int curHp, int maxHp, int curMp, int maxMp, int baseMaxHp,
                       int power, int stamina, int speed, int magic, int hit, int evade, int mdef, int level,
                       int extraU16, long exp, long tpNext,
                       int equipWeapon, int equipArmor, int equipHelmet, int equipAccessory,
                       int expNext, int tpRelated, byte[] growth, byte[] curStats, byte[] perCharConstants) {
            this.raw = raw;
            this.id = id;
            this.curHp = curHp; this.maxHp = maxHp; this.curMp = curMp; this.maxMp = maxMp;
            this.baseMaxHp = baseMaxHp;
            this.power = power; this.stamina = stamina; this.speed = speed; this.magic = magic;
            this.hit = hit; this.evade = evade; this.mdef = mdef; this.level = level;
            this.extraU16 = extraU16;
            this.exp = exp; this.tpNext = tpNext;
            this.equipWeapon = equipWeapon; this.equipArmor = equipArmor;
            this.equipHelmet = equipHelmet; this.equipAccessory = equipAccessory;
            this.expNext = expNext; this.tpRelated = tpRelated;
            this.growth = growth; this.curStats = curStats; this.perCharConstants = perCharConstants;
        }

        static DsChar parse(byte[] raw) {
            if (raw.length != CHAR_SIZE) throw new IllegalArgumentException("bad DS char record size");
            int maxHp = u16(raw, 0x08), curHp = u16(raw, 0x0A), maxMp = u16(raw, 0x0C), curMp = u16(raw, 0x0E);
            int baseMaxHp = u16(raw, 0x10);
            int power = raw[0x12] & 0xFF, stamina = raw[0x13] & 0xFF, speed = raw[0x14] & 0xFF, magic = raw[0x15] & 0xFF;
            int hit = raw[0x16] & 0xFF, evade = raw[0x17] & 0xFF, mdef = raw[0x18] & 0xFF, level = raw[0x19] & 0xFF;
            int extraU16 = u16(raw, 0x1A);
            long exp = u32(raw, 0x1C);
            long tpNext = u32(raw, 0x20);
            int eqW = u16(raw, 0x36), eqA = u16(raw, 0x38), eqH = u16(raw, 0x3A), eqAcc = u16(raw, 0x3C);
            int expNext = u16(raw, 0x3E);
            int tpRelated = u16(raw, 0x40);
            byte[] growth = slice(raw, 0x42, 6);
            byte[] curStats = slice(raw, 0x48, 9);
            byte[] perChar = slice(raw, 0x51, 4);
            return new DsChar(raw.clone(), u32(raw, 0x00),
                    curHp, maxHp, curMp, maxMp, baseMaxHp,
                    power, stamina, speed, magic, hit, evade, mdef, level,
                    extraU16, exp, tpNext, eqW, eqA, eqH, eqAcc, expNext, tpRelated,
                    growth, curStats, perChar);
        }
    }

    public static final class DsSlot {
        public final byte[] raw;
        public final boolean used;
        public final boolean lastSaved;
        public final byte[] regionA;
        public final byte[] flags;
        public final byte[] sceneBlock;
        public final DsChar[] chars;
        public final Map<String, List<CtSave.Entry>> inventory;
        public final byte[] techBlock;
        public final String[] names;
        public final byte[] party;
        public final byte[] reserve;
        public final int recruitedMask;
        public final long gold;
        public final long saveCount;
        public final long playTimeSeconds;
        public final int locationNameId;
        public final int eraMask;

        private DsSlot(byte[] raw, boolean used, boolean lastSaved, byte[] regionA, byte[] flags, byte[] sceneBlock,
                       DsChar[] chars, Map<String, List<CtSave.Entry>> inventory, byte[] techBlock, String[] names,
                       byte[] party, byte[] reserve, int recruitedMask, long gold, long saveCount,
                       long playTimeSeconds, int locationNameId, int eraMask) {
            this.raw = raw;
            this.used = used;
            this.lastSaved = lastSaved;
            this.regionA = regionA;
            this.flags = flags;
            this.sceneBlock = sceneBlock;
            this.chars = chars;
            this.inventory = inventory;
            this.techBlock = techBlock;
            this.names = names;
            this.party = party;
            this.reserve = reserve;
            this.recruitedMask = recruitedMask;
            this.gold = gold;
            this.saveCount = saveCount;
            this.playTimeSeconds = playTimeSeconds;
            this.locationNameId = locationNameId;
            this.eraMask = eraMask;
        }

        static DsSlot parse(byte[] raw) {
            if (raw.length != SLOT_SIZE) throw new IllegalArgumentException("bad DS slot size");
            long magic = u32(raw, 0);
            boolean lastSaved = magic == SLOT_MAGIC;

            byte[] regionA = slice(raw, REGION_A_OFF, REGION_A_SIZE);
            byte[] flags = slice(raw, FLAGS_OFF, FLAGS_SIZE);
            byte[] sceneBlock = slice(raw, SCENE_OFF, SCENE_SIZE);

            DsChar[] chars = new DsChar[NUM_CHARS];
            for (int i = 0; i < NUM_CHARS; i++) {
                chars[i] = DsChar.parse(slice(raw, CHAR_BLOCK_OFF + i * CHAR_SIZE, CHAR_SIZE));
            }
            // Unused slots are 0xFF-filled (not zero-filled); the FEDCBA98
            // magic only marks the *last-saved* slot, not "used" (other used
            // slots are 0xFFFFFFFF there too) -- REPORT.md #7 /
            // chrono-trigger.18311.duc.
            boolean used = chars[0].maxHp != 0 && chars[0].maxHp != 0xFFFF && (raw[PARTY_OFF] & 0xFF) != 0xFF;

            int off = CHAR_BLOCK_OFF + NUM_CHARS * CHAR_SIZE;
            if (off != 0x724) throw new IllegalStateException("unexpected inventory offset " + Integer.toHexString(off));
            Map<String, List<CtSave.Entry>> inventory = new LinkedHashMap<>();
            for (DsInventorySection sec : INVENTORY_SECTIONS) {
                List<CtSave.Entry> entries = new ArrayList<>(sec.capacity);
                for (int i = 0; i < sec.capacity; i++) {
                    int packed = u16(raw, off);
                    int count = u16(raw, off + 2);
                    off += 4;
                    int idx = packed & 0xFFF;
                    int cat = packed >>> 12;
                    // 0xFFFF shows up throughout an unused, 0xFF-filled slot
                    // -- not a real category byte, don't validate it.
                    if (packed != 0 && packed != 0xFFFF && cat != sec.category) {
                        throw new IllegalArgumentException(String.format(
                                "unexpected category 0x%X in DS section %s (expected 0x%X)",
                                cat, sec.name, sec.category));
                    }
                    entries.add(new CtSave.Entry(idx, count));
                }
                inventory.put(sec.name, entries);
            }
            if (off != TECH_BLOCK_OFF) throw new IllegalStateException("unexpected tech block offset " + Integer.toHexString(off));

            byte[] techBlock = slice(raw, TECH_BLOCK_OFF, TECH_BLOCK_SIZE);

            String[] names = new String[NUM_NAMES];
            for (int i = 0; i < NUM_NAMES; i++) {
                names[i] = decodeDsName(raw, NAMES_OFF + i * NAME_STRIDE, NAME_STRIDE);
            }

            byte[] party = slice(raw, PARTY_OFF, 3);
            byte[] reserve = slice(raw, PARTY_OFF + 3, 6);
            int recruitedMask = raw[PARTY_OFF + 9] & 0xFF;

            long gold = u32(raw, GOLD_OFF);
            long saveCount = u32(raw, SAVE_COUNT_OFF);
            long playTimeSeconds = u32(raw, PLAY_TIME_OFF);
            int locationNameId = u16(raw, LOCATION_NAME_ID_OFF);
            int eraMask = u16(raw, ERA_MASK_OFF);

            return new DsSlot(raw.clone(), used, lastSaved, regionA, flags, sceneBlock, chars, inventory,
                    techBlock, names, party, reserve, recruitedMask, gold, saveCount, playTimeSeconds,
                    locationNameId, eraMask);
        }
    }

    /**
     * Detects and strips the wrapper around a DS save image, returning the
     * raw 65536-byte EEPROM image. Throws IllegalArgumentException if the
     * data doesn't match any known wrapper (REPORT.md #7).
     */
    public static byte[] stripWrapper(byte[] data) {
        if (data.length == ARDS_TOTAL_SIZE || startsWith(data, "ARDS")) {
            if (data.length < ARDS_HEADER_SIZE) {
                throw new IllegalArgumentException("file looks like an ARDS export but is only " + data.length + " bytes");
            }
            int imageLen = data.length - ARDS_HEADER_SIZE;
            if (imageLen < IMAGE_SIZE) {
                throw new IllegalArgumentException("ARDS export image is " + imageLen + " bytes, expected >= " + IMAGE_SIZE);
            }
            byte[] image = new byte[IMAGE_SIZE];
            System.arraycopy(data, ARDS_HEADER_SIZE, image, 0, IMAGE_SIZE);
            return image;
        }

        if (data.length >= DESMUME_FOOTER_SIZE && containsSignature(data, data.length - DESMUME_FOOTER_SIZE, DESMUME_FOOTER_SIZE)) {
            int imageLen = data.length - DESMUME_FOOTER_SIZE;
            if (imageLen != IMAGE_SIZE) {
                throw new IllegalArgumentException("DeSmuME .dsv image (after footer strip) is " + imageLen
                        + " bytes, expected " + IMAGE_SIZE);
            }
            byte[] image = new byte[IMAGE_SIZE];
            System.arraycopy(data, 0, image, 0, IMAGE_SIZE);
            return image;
        }

        if (data.length == IMAGE_SIZE) {
            return data.clone();
        }

        if (data.length == 524288) {
            byte[] image = new byte[IMAGE_SIZE];
            System.arraycopy(data, 0, image, 0, IMAGE_SIZE);
            return image;
        }

        throw new IllegalArgumentException("unrecognized DS save wrapper: " + data.length + " bytes");
    }

    /** Returns 3 DsSlot, never null (unused slots still parse, with used=false). */
    public static DsSlot[] parseImage(byte[] image) {
        if (image.length != IMAGE_SIZE) throw new IllegalArgumentException("expected 65536-byte DS image");
        DsSlot[] slots = new DsSlot[NUM_SLOTS];
        for (int i = 0; i < NUM_SLOTS; i++) {
            slots[i] = DsSlot.parse(slice(image, i * SLOT_SIZE, SLOT_SIZE));
        }
        return slots;
    }

    public static DsSlot[] parseFile(byte[] data) {
        return parseImage(stripWrapper(data));
    }

    private static boolean startsWith(byte[] data, String prefix) {
        byte[] p;
        try {
            p = prefix.getBytes("US-ASCII");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
        if (data.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (data[i] != p[i]) return false;
        return true;
    }

    private static boolean containsSignature(byte[] data, int from, int len) {
        int end = from + len - DESMUME_SIGNATURE.length;
        outer:
        for (int i = from; i <= end; i++) {
            for (int j = 0; j < DESMUME_SIGNATURE.length; j++) {
                if (data[i + j] != DESMUME_SIGNATURE[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] slice(byte[] a, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(a, off, out, 0, len);
        return out;
    }

    private static int u16(byte[] a, int off) {
        return (a[off] & 0xFF) | ((a[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] a, int off) {
        return (a[off] & 0xFFL) | ((a[off + 1] & 0xFFL) << 8)
                | ((a[off + 2] & 0xFFL) << 16) | ((a[off + 3] & 0xFFL) << 24);
    }
}
