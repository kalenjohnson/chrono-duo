package com.kalenjohnson.chronoduo.saveimport;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stream model of the Chrono Trigger (2018 Steam/mobile port) save payload,
 * ported byte-for-byte from tools/saves/ctsave.py. See REPORT.md #3 for the
 * format. The payload is a serialized *stream*, not a fixed struct: names
 * are length-prefixed, so every field after the name table shifts if a
 * name's length changes -- {@link #parse} and {@link #serialize} walk the
 * stream sequentially in both directions, same as the Python.
 */
public final class CtSave {
    public static final int CHAR_SIZE = 0x58;
    public static final int NUM_CHARS = 7;
    public static final int NUM_NAMES = 10;
    public static final int REGION_A_SIZE = 0x200;
    public static final int FLAGS_SIZE = 0x200;
    public static final int BLOCK_400_SIZE = 0x30;
    public static final int TECH_BLOCK_SIZE = 45;

    /** (section name, capacity, category byte), in stream order. */
    public static final class InventorySection {
        public final String name;
        public final int capacity;
        public final int categoryByte;

        public InventorySection(String name, int capacity, int categoryByte) {
            this.name = name;
            this.capacity = capacity;
            this.categoryByte = categoryByte;
        }
    }

    public static final InventorySection[] INVENTORY_SECTIONS = {
            new InventorySection("weapons", 111, 0x00),
            new InventorySection("armour", 50, 0x10),
            new InventorySection("helmets", 39, 0x20),
            new InventorySection("accessories", 59, 0x30),
            new InventorySection("consumables", 43, 0x40),
            new InventorySection("key_items", 45, 0x50),
    };

    /** One (idx, count) inventory entry. */
    public static final class Entry {
        public int idx, count;

        public Entry(int idx, int count) {
            this.idx = idx;
            this.count = count;
        }
    }

    /**
     * One 0x58-byte character record (REPORT.md #4, port column). Backed by
     * a mutable byte[] (`raw`); every field is a get/set pair reading/
     * writing directly into `raw`, mirroring ctsave.py's property-based
     * CtChar so {@link #serialize} is always exact.
     */
    public static final class CtChar {
        public final byte[] raw;

        public CtChar(byte[] raw) {
            if (raw.length != CHAR_SIZE) throw new IllegalArgumentException("bad char record size");
            this.raw = raw.clone();
        }

        private int u8(int off) {
            return raw[off] & 0xFF;
        }

        private void u8(int off, int v) {
            raw[off] = (byte) (v & 0xFF);
        }

        private int u16(int off) {
            return (raw[off] & 0xFF) | ((raw[off + 1] & 0xFF) << 8);
        }

        private void u16(int off, int v) {
            raw[off] = (byte) (v & 0xFF);
            raw[off + 1] = (byte) ((v >>> 8) & 0xFF);
        }

        private long u32(int off) {
            return (raw[off] & 0xFFL) | ((raw[off + 1] & 0xFFL) << 8)
                    | ((raw[off + 2] & 0xFFL) << 16) | ((raw[off + 3] & 0xFFL) << 24);
        }

        private void u32(int off, long v) {
            raw[off] = (byte) (v & 0xFF);
            raw[off + 1] = (byte) ((v >>> 8) & 0xFF);
            raw[off + 2] = (byte) ((v >>> 16) & 0xFF);
            raw[off + 3] = (byte) ((v >>> 24) & 0xFF);
        }

        public int getId() { return u8(0x00); }
        public void setId(int v) { u8(0x00, v); }
        public int getConst01() { return u8(0x01); }
        public void setConst01(int v) { u8(0x01, v); }
        public int getConst02() { return u8(0x02); }
        public void setConst02(int v) { u8(0x02, v); }
        public int getConst03() { return u8(0x03); }
        public void setConst03(int v) { u8(0x03, v); }

        // HP/MP -- the port swaps max/current order vs. the SNES record.
        public int getMaxHp() { return u16(0x04); }
        public void setMaxHp(int v) { u16(0x04, v); }
        public int getCurHp() { return u16(0x06); }
        public void setCurHp(int v) { u16(0x06, v); }
        public int getMaxMp() { return u16(0x08); }
        public void setMaxMp(int v) { u16(0x08, v); }
        public int getCurMp() { return u16(0x0A); }
        public void setCurMp(int v) { u16(0x0A, v); }
        public int getBaseMaxHp() { return u16(0x0C); }
        public void setBaseMaxHp(int v) { u16(0x0C, v); }

        public int getBasePower() { return u8(0x0E); }
        public void setBasePower(int v) { u8(0x0E, v); }
        public int getBaseStamina() { return u8(0x0F); }
        public void setBaseStamina(int v) { u8(0x0F, v); }
        public int getBaseSpeed() { return u8(0x10); }
        public void setBaseSpeed(int v) { u8(0x10, v); }
        public int getBaseMagic() { return u8(0x11); }
        public void setBaseMagic(int v) { u8(0x11, v); }
        public int getBaseHit() { return u8(0x12); }
        public void setBaseHit(int v) { u8(0x12, v); }
        public int getBaseEvade() { return u8(0x13); }
        public void setBaseEvade(int v) { u8(0x13, v); }
        public int getBaseMdef() { return u8(0x14); }
        public void setBaseMdef(int v) { u8(0x14, v); }
        public int getLevel() { return u8(0x15); }
        public void setLevel(int v) { u8(0x15, v); }

        public long getExp() { return u32(0x16); }
        public void setExp(long v) { u32(0x16, v); }
        public long getTpNext() { return u32(0x1A); }
        public void setTpNext(long v) { u32(0x1A, v); }
        // +0x1E..+0x2B (14 bytes): zeros in every observed fixture.

        public int getEquipWeapon() { return u16(0x2C); }
        public void setEquipWeapon(int v) { u16(0x2C, v); }
        public int getEquipArmor() { return u16(0x2E); }
        public void setEquipArmor(int v) { u16(0x2E, v); }
        public int getEquipHelmet() { return u16(0x30); }
        public void setEquipHelmet(int v) { u16(0x30, v); }
        public int getEquipAccessory() { return u16(0x32); }
        public void setEquipAccessory(int v) { u16(0x32, v); }

        public int getExpNext() { return u16(0x34); }
        public void setExpNext(int v) { u16(0x34, v); }
        public int getTpRelated() { return u16(0x36); }
        public void setTpRelated(int v) { u16(0x36, v); }

        public byte[] getGrowth() {
            byte[] out = new byte[6];
            System.arraycopy(raw, 0x38, out, 0, 6);
            return out;
        }

        public void setGrowth(byte[] value) {
            if (value.length != 6) throw new IllegalArgumentException("growth must be 6 bytes");
            System.arraycopy(value, 0, raw, 0x38, 6);
        }

        /** 9 bytes: power, stamina, speed, magic, hit, evade, mdef, attack, defence. */
        public byte[] getCurStats() {
            byte[] out = new byte[9];
            System.arraycopy(raw, 0x3E, out, 0, 9);
            return out;
        }

        public void setCurStats(byte[] value) {
            if (value.length != 9) throw new IllegalArgumentException("curStats must be 9 bytes");
            System.arraycopy(value, 0, raw, 0x3E, 9);
        }

        public byte[] getPerCharConstants() {
            byte[] out = new byte[4];
            System.arraycopy(raw, 0x47, out, 0, 4);
            return out;
        }

        public void setPerCharConstants(byte[] value) {
            if (value.length != 4) throw new IllegalArgumentException("perCharConstants must be 4 bytes");
            System.arraycopy(value, 0, raw, 0x47, 4);
        }
        // +0x4B..+0x57 (13 bytes): zeros in every observed fixture.

        public byte[] serialize() {
            return raw.clone();
        }

        CtChar deepCopy() {
            return new CtChar(raw);
        }
    }

    public byte[] regionA = new byte[REGION_A_SIZE];
    public byte[] flags = new byte[FLAGS_SIZE];
    public byte[] block400 = new byte[BLOCK_400_SIZE];
    public CtChar[] chars = new CtChar[0]; // 7 CtChar once parsed
    // section name -> fixed-capacity list of entries, in INVENTORY_SECTIONS order
    public Map<String, List<Entry>> inventory = new LinkedHashMap<>();
    public byte[] techBlock = new byte[TECH_BLOCK_SIZE];
    public List<String> names = new ArrayList<>(); // 10 strings
    public byte[] party = {0, 0, 0};
    public byte[] reserve = new byte[6];
    public int recruitedMask;
    public int byte0x0A;
    public long gold;
    public int counter1;
    public long playTimeSeconds;
    public int counter2;
    public int zeroU16;
    public int locationNameId;
    public int eraMask;
    public byte[] tail = new byte[0]; // opaque: everything from here to the end of payload[N]
    public int checksum; // trailer u16; unknown algorithm, see load()/save() equivalents

    public static CtSave parse(byte[] payload) {
        CtSave s = new CtSave();
        int off = 0;
        s.regionA = slice(payload, off, REGION_A_SIZE); off += REGION_A_SIZE;
        s.flags = slice(payload, off, FLAGS_SIZE); off += FLAGS_SIZE;
        s.block400 = slice(payload, off, BLOCK_400_SIZE); off += BLOCK_400_SIZE;

        s.chars = new CtChar[NUM_CHARS];
        for (int i = 0; i < NUM_CHARS; i++) {
            s.chars[i] = new CtChar(slice(payload, off, CHAR_SIZE));
            off += CHAR_SIZE;
        }

        s.inventory = new LinkedHashMap<>();
        for (InventorySection sec : INVENTORY_SECTIONS) {
            List<Entry> entries = new ArrayList<>(sec.capacity);
            for (int i = 0; i < sec.capacity; i++) {
                int idx = payload[off] & 0xFF;
                int cat = payload[off + 1] & 0xFF;
                int count = payload[off + 2] & 0xFF;
                off += 3;
                if (cat != 0 && cat != sec.categoryByte) {
                    throw new IllegalArgumentException(String.format(
                            "unexpected category byte 0x%02X in section %s (expected 0x00 or 0x%02X)",
                            cat, sec.name, sec.categoryByte));
                }
                entries.add(new Entry(idx, count));
            }
            s.inventory.put(sec.name, entries);
        }

        s.techBlock = slice(payload, off, TECH_BLOCK_SIZE); off += TECH_BLOCK_SIZE;

        s.names = new ArrayList<>(NUM_NAMES);
        for (int i = 0; i < NUM_NAMES; i++) {
            int n = payload[off] & 0xFF;
            String name = new String(payload, off + 1, n, StandardCharsets.UTF_8);
            s.names.add(name);
            off += 1 + n;
        }

        s.party = slice(payload, off, 3); off += 3;
        s.reserve = slice(payload, off, 6); off += 6;
        s.recruitedMask = payload[off] & 0xFF; off += 1;
        s.byte0x0A = payload[off] & 0xFF; off += 1;
        s.gold = (payload[off] & 0xFFL) | ((payload[off + 1] & 0xFFL) << 8) | ((payload[off + 2] & 0xFFL) << 16);
        off += 3;
        s.counter1 = payload[off] & 0xFF; off += 1;
        s.playTimeSeconds = (payload[off] & 0xFFL) | ((payload[off + 1] & 0xFFL) << 8)
                | ((payload[off + 2] & 0xFFL) << 16);
        off += 3;
        s.counter2 = payload[off] & 0xFF; off += 1;
        s.zeroU16 = ((payload[off] & 0xFF) | ((payload[off + 1] & 0xFF) << 8)); off += 2;
        s.locationNameId = ((payload[off] & 0xFF) | ((payload[off + 1] & 0xFF) << 8)); off += 2;
        s.eraMask = ((payload[off] & 0xFF) | ((payload[off + 1] & 0xFF) << 8)); off += 2;

        s.tail = slice(payload, off, payload.length - off);
        return s;
    }

    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBytes(out, regionA);
        writeBytes(out, flags);
        writeBytes(out, block400);
        for (CtChar c : chars) writeBytes(out, c.serialize());

        for (InventorySection sec : INVENTORY_SECTIONS) {
            List<Entry> entries = inventory.get(sec.name);
            if (entries == null || entries.size() != sec.capacity) {
                throw new IllegalStateException(sec.name + ": expected " + sec.capacity + " entries, got "
                        + (entries == null ? 0 : entries.size()));
            }
            for (Entry e : entries) {
                int cat = (e.idx != 0 || e.count != 0) ? sec.categoryByte : 0;
                out.write(e.idx & 0xFF);
                out.write(cat);
                out.write(e.count & 0xFF);
            }
        }

        writeBytes(out, techBlock);

        for (String name : names) {
            byte[] b = utf8(name);
            if (b.length > 255) throw new IllegalStateException("name too long: " + name);
            out.write(b.length);
            writeBytes(out, b);
        }

        writeBytes(out, party);
        writeBytes(out, reserve);
        out.write(recruitedMask & 0xFF);
        out.write(byte0x0A & 0xFF);
        out.write((int) (gold & 0xFF));
        out.write((int) ((gold >>> 8) & 0xFF));
        out.write((int) ((gold >>> 16) & 0xFF));
        out.write(counter1 & 0xFF);
        out.write((int) (playTimeSeconds & 0xFF));
        out.write((int) ((playTimeSeconds >>> 8) & 0xFF));
        out.write((int) ((playTimeSeconds >>> 16) & 0xFF));
        out.write(counter2 & 0xFF);
        out.write(zeroU16 & 0xFF);
        out.write((zeroU16 >>> 8) & 0xFF);
        out.write(locationNameId & 0xFF);
        out.write((locationNameId >>> 8) & 0xFF);
        out.write(eraMask & 0xFF);
        out.write((eraMask >>> 8) & 0xFF);
        writeBytes(out, tail);

        return out.toByteArray();
    }

    /** Deep copy via round-trip, mirroring convert.snes_to_ct's `CtSave.parse(template.serialize())`. */
    public CtSave deepCopy() {
        return parse(serialize());
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] slice(byte[] a, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(a, off, out, 0, len);
        return out;
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }
}
