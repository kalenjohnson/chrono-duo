package com.kalenjohnson.chronoduo.saveimport;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for SNES Chrono Trigger .srm save files, ported byte-for-byte from
 * tools/saves/snes_srm.py. See REPORT.md #2 for the full slot layout.
 */
public final class SnesSrm {
    public static final int SLOT_SIZE = 0xA00;
    public static final int NUM_SLOTS = 3;
    public static final int CHAR_SIZE = 0x50;
    public static final int NUM_CHARS = 7;
    public static final int CHECKSUM_TABLE_OFFSET = 0x1FF0;
    public static final int LAST_USED_OFFSET = 0x1FE0;

    public static final String[] CHAR_ORDER = {
            "Crono", "Marle", "Lucca", "Robo", "Frog", "Ayla", "Magus",
    };
    public static final String[] NAME_SLOT_ORDER = {
            "Crono", "Marle", "Lucca", "Robo", "Frog", "Ayla", "Magus", "Epoch",
    };

    private SnesSrm() {
    }

    /**
     * 16-bit add-with-carry (65816 ADC loop) over the slot's 1280 LE words,
     * summed from the LAST word (0x9FE) down to the first, final carry-out
     * dropped -- descending order matches 53/53 fixture slots (REPORT.md #2).
     */
    public static int slotChecksum(byte[] slot) {
        int acc = 0;
        int carry = 0;
        for (int i = SLOT_SIZE - 2; i >= 0; i -= 2) {
            int w = (slot[i] & 0xFF) | ((slot[i + 1] & 0xFF) << 8);
            int total = acc + w + carry;
            acc = total & 0xFFFF;
            carry = (total > 0xFFFF) ? 1 : 0;
        }
        return acc;
    }

    /** 0xA0-0xB9 = A-Z, 0xBA-0xD3 = a-z, 0x00 or 0xFF = end/pad. */
    public static String decodeName(byte[] raw, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int b = raw[off + i] & 0xFF;
            if (b == 0 || b == 0xFF) break;
            if (b >= 0xA0 && b <= 0xB9) {
                sb.append((char) ('A' + (b - 0xA0)));
            } else if (b >= 0xBA && b <= 0xD3) {
                sb.append((char) ('a' + (b - 0xBA)));
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    /** One 0x50-byte character record (REPORT.md #4, SNES column). */
    public static final class SnesChar {
        public final byte[] raw;
        public final int id;
        public final int curHp, maxHp, curMp, maxMp;
        public final int power, stamina, speed, magic, hit, evade, mdef, level;
        public final int exp;
        public final int tpNext;
        public final int helmetId, armorId, weaponId, accessoryId;
        public final int expNext;
        public final int field2d;

        private SnesChar(byte[] raw, int id, int curHp, int maxHp, int curMp, int maxMp,
                          int power, int stamina, int speed, int magic, int hit, int evade, int mdef, int level,
                          int exp, int tpNext, int helmetId, int armorId, int weaponId, int accessoryId,
                          int expNext, int field2d) {
            this.raw = raw;
            this.id = id;
            this.curHp = curHp;
            this.maxHp = maxHp;
            this.curMp = curMp;
            this.maxMp = maxMp;
            this.power = power;
            this.stamina = stamina;
            this.speed = speed;
            this.magic = magic;
            this.hit = hit;
            this.evade = evade;
            this.mdef = mdef;
            this.level = level;
            this.exp = exp;
            this.tpNext = tpNext;
            this.helmetId = helmetId;
            this.armorId = armorId;
            this.weaponId = weaponId;
            this.accessoryId = accessoryId;
            this.expNext = expNext;
            this.field2d = field2d;
        }

        static SnesChar parse(byte[] raw) {
            if (raw.length != CHAR_SIZE) throw new IllegalArgumentException("bad char record size");
            int power = raw[0x0B] & 0xFF;
            int stamina = raw[0x0C] & 0xFF;
            int speed = raw[0x0D] & 0xFF;
            int magic = raw[0x0E] & 0xFF;
            int hit = raw[0x0F] & 0xFF;
            int evade = raw[0x10] & 0xFF;
            int mdef = raw[0x11] & 0xFF;
            int level = raw[0x12] & 0xFF;
            int exp = (raw[0x13] & 0xFF) | ((raw[0x14] & 0xFF) << 8) | ((raw[0x15] & 0xFF) << 16);
            return new SnesChar(
                    raw.clone(),
                    raw[0x00] & 0xFF,
                    u16(raw, 0x03), u16(raw, 0x05), u16(raw, 0x07), u16(raw, 0x09),
                    power, stamina, speed, magic, hit, evade, mdef, level,
                    exp, u16(raw, 0x16),
                    raw[0x27] & 0xFF, raw[0x28] & 0xFF, raw[0x29] & 0xFF, raw[0x2A] & 0xFF,
                    u16(raw, 0x2B), raw[0x2D] & 0xFF
            );
        }
    }

    /** (id, count) inventory entry. */
    public static final class ItemStack {
        public final int id, count;

        public ItemStack(int id, int count) {
            this.id = id;
            this.count = count;
        }
    }

    public static final class SnesSlot {
        public final byte[] raw;
        public final List<ItemStack> items;
        public final SnesChar[] chars; // 7, Crono..Magus
        public final byte[] techBlock; // 45 bytes at 0x430
        public final byte[] party; // 3 bytes
        public final byte[] reserve; // 6 bytes
        public final int recruitedMask;
        public final String[] names; // 8 decoded strings
        public final int gold;
        public final int playHours, playMinutes, playSeconds, playTimeSeconds;
        public final int chapterByte;
        public final int locationId, x, y;
        public final byte[] flags; // 512 bytes at 0x602

        private SnesSlot(byte[] raw, List<ItemStack> items, SnesChar[] chars, byte[] techBlock,
                          byte[] party, byte[] reserve, int recruitedMask, String[] names, int gold,
                          int playHours, int playMinutes, int playSeconds, int playTimeSeconds,
                          int chapterByte, int locationId, int x, int y, byte[] flags) {
            this.raw = raw;
            this.items = items;
            this.chars = chars;
            this.techBlock = techBlock;
            this.party = party;
            this.reserve = reserve;
            this.recruitedMask = recruitedMask;
            this.names = names;
            this.gold = gold;
            this.playHours = playHours;
            this.playMinutes = playMinutes;
            this.playSeconds = playSeconds;
            this.playTimeSeconds = playTimeSeconds;
            this.chapterByte = chapterByte;
            this.locationId = locationId;
            this.x = x;
            this.y = y;
            this.flags = flags;
        }

        static SnesSlot parse(byte[] raw) {
            if (raw.length != SLOT_SIZE) throw new IllegalArgumentException("bad slot size");
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                int id = raw[i] & 0xFF;
                if (id != 0) items.add(new ItemStack(id, raw[0x100 + i] & 0xFF));
            }

            SnesChar[] chars = new SnesChar[NUM_CHARS];
            for (int i = 0; i < NUM_CHARS; i++) {
                byte[] rec = new byte[CHAR_SIZE];
                System.arraycopy(raw, 0x200 + i * CHAR_SIZE, rec, 0, CHAR_SIZE);
                chars[i] = SnesChar.parse(rec);
            }

            byte[] techBlock = new byte[45];
            System.arraycopy(raw, 0x430, techBlock, 0, 45);
            byte[] party = new byte[3];
            System.arraycopy(raw, 0x580, party, 0, 3);
            byte[] reserve = new byte[6];
            System.arraycopy(raw, 0x583, reserve, 0, 6);
            int recruitedMask = raw[0x5AF] & 0xFF;
            String[] names = new String[8];
            for (int i = 0; i < 8; i++) names[i] = decodeName(raw, 0x5B0 + i * 6, 6);
            int gold = (raw[0x5E0] & 0xFF) | ((raw[0x5E1] & 0xFF) << 8) | ((raw[0x5E2] & 0xFF) << 16);

            int sec = raw[0x5E4] & 0xFF;
            int minOnes = raw[0x5E5] & 0xFF;
            int minTens = raw[0x5E6] & 0xFF;
            int hrOnes = raw[0x5E7] & 0xFF;
            int hrTens = raw[0x5E8] & 0xFF;
            int minutes = minTens * 10 + minOnes;
            int hours = hrTens * 10 + hrOnes;
            int playTimeSeconds = hours * 3600 + minutes * 60 + sec;

            int locationId = u16(raw, 0x5F3);
            int x = u16(raw, 0x5F5);
            int y = u16(raw, 0x5F7);
            byte[] flags = new byte[512];
            System.arraycopy(raw, 0x602, flags, 0, 512);

            return new SnesSlot(raw.clone(), items, chars, techBlock, party, reserve, recruitedMask, names, gold,
                    hours, minutes, sec, playTimeSeconds, hrOnes, locationId, x, y, flags);
        }
    }

    /** Return value of {@link #parseSrm}: 3 slots (null = unused), last-used marker, stored checksums. */
    public static final class SrmFile {
        public final SnesSlot[] slots; // length 3, entries may be null
        public final int lastUsed;
        public final int[] checksums; // length 3

        SrmFile(SnesSlot[] slots, int lastUsed, int[] checksums) {
            this.slots = slots;
            this.lastUsed = lastUsed;
            this.checksums = checksums;
        }
    }

    public static SrmFile parseSrm(byte[] data) {
        if (data.length != 8192) {
            throw new IllegalArgumentException("expected 8192-byte .srm, got " + data.length);
        }
        int[] checksums = new int[3];
        for (int i = 0; i < 3; i++) checksums[i] = u16(data, CHECKSUM_TABLE_OFFSET + i * 2);
        int lastUsed = data[LAST_USED_OFFSET] & 0xFF;
        SnesSlot[] slots = new SnesSlot[NUM_SLOTS];
        for (int i = 0; i < NUM_SLOTS; i++) {
            byte[] raw = new byte[SLOT_SIZE];
            System.arraycopy(data, i * SLOT_SIZE, raw, 0, SLOT_SIZE);
            boolean unused = true;
            for (int b = 0; b < 16; b++) {
                if (raw[b] != (byte) 0xFF) {
                    unused = false;
                    break;
                }
            }
            slots[i] = unused ? null : SnesSlot.parse(raw);
        }
        return new SrmFile(slots, lastUsed, checksums);
    }

    private static int u16(byte[] a, int off) {
        return (a[off] & 0xFF) | ((a[off + 1] & 0xFF) << 8);
    }
}
