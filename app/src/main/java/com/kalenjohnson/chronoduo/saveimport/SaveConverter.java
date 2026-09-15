package com.kalenjohnson.chronoduo.saveimport;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts an SNES Chrono Trigger .srm slot into a port save (CtSave), by
 * overlaying SNES data onto a "template" chapter save picked by nearest
 * event flags. Ported byte-for-byte from tools/saves/convert.py. See
 * REPORT.md #5 for the overall design.
 */
public final class SaveConverter {
    private SaveConverter() {
    }

    /** One (section index into CtSave.INVENTORY_SECTIONS, idx-within-section) range, id-base linear mapping. */
    private static final class ItemRange {
        final String section;
        final int lo, hi, base;

        ItemRange(String section, int lo, int hi, int base) {
            this.section = section;
            this.lo = lo;
            this.hi = hi;
            this.base = base;
        }
    }

    // idx is the item's position within its category array; each range is linear
    // (idx = id - base) including the key-item range 0xD0-0xE7 (REPORT.md #3.2, convert.py).
    private static final ItemRange[] ITEM_RANGES = {
            new ItemRange("weapons", 0x01, 0x5A, 0x00),
            new ItemRange("armour", 0x5B, 0x7B, 0x5A),
            new ItemRange("helmets", 0x7C, 0x94, 0x7B),
            new ItemRange("accessories", 0x95, 0xBC, 0x94),
            new ItemRange("consumables", 0xBD, 0xCF, 0xBC),
            new ItemRange("key_items", 0xD0, 0xE7, 0xCF),
    };

    /** Result of {@link #classifyItem}: section name + idx within that section. */
    public static final class Classified {
        public final String section;
        public final int idx;

        Classified(String section, int idx) {
            this.section = section;
            this.idx = idx;
        }
    }

    /** Returns (section, idx) for an SNES item id, or null if id==0 or outside every known range. */
    public static Classified classifyItem(int itemId) {
        if (itemId == 0) return null;
        for (ItemRange r : ITEM_RANGES) {
            if (itemId >= r.lo && itemId <= r.hi) {
                return new Classified(r.section, itemId - r.base);
            }
        }
        return null;
    }

    // Equipment category nibble used in the port's (category<<12)|idx encoding
    // (REPORT.md #4, port +0x2C..+0x33).
    private static int equipCategory(String section) {
        switch (section) {
            case "weapons": return 0x0;
            case "armour": return 0x1;
            case "helmets": return 0x2;
            case "accessories": return 0x3;
            default: return -1;
        }
    }

    /** SNES equipment item id -> port u16 (category<<12)|idx, or 0 if empty. */
    public static int equipField(int itemId) {
        Classified c = classifyItem(itemId);
        if (c == null) return 0;
        int category = equipCategory(c.section);
        if (category < 0) return 0; // a key item/consumable id in an equipment slot shouldn't happen
        return (category << 12) | c.idx;
    }

    public static int hammingDistance(byte[] a, byte[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("length mismatch");
        int dist = 0;
        for (int i = 0; i < a.length; i++) {
            dist += Integer.bitCount((a[i] ^ b[i]) & 0xFF);
        }
        return dist;
    }

    /** One candidate chapter template, as handed to {@link #pickTemplate}. */
    public static final class TemplateCandidate {
        public final String name;
        public final byte[] payload; // full decrypted payload (region A .. tail)

        public TemplateCandidate(String name, byte[] payload) {
            this.name = name;
            this.payload = payload;
        }
    }

    public static final class PickResult {
        public final TemplateCandidate candidate;
        public final int distance;

        PickResult(TemplateCandidate candidate, int distance) {
            this.candidate = candidate;
            this.distance = distance;
        }
    }

    /**
     * Picks the candidate whose flags block (payload 0x200..0x400) is
     * closest (Hamming distance) to {@code flags}. Mirrors convert.py's
     * {@code pick_template} (candidates are supplied by the caller here
     * instead of globbed from disk, since Android reads them from assets).
     */
    public static PickResult pickTemplate(byte[] flags, List<TemplateCandidate> candidates) {
        PickResult best = null;
        for (TemplateCandidate cand : candidates) {
            byte[] templateFlags = new byte[CtSave.FLAGS_SIZE];
            System.arraycopy(cand.payload, 0x200, templateFlags, 0, CtSave.FLAGS_SIZE);
            int dist = hammingDistance(flags, templateFlags);
            if (best == null || dist < best.distance) {
                best = new PickResult(cand, dist);
            }
        }
        return best;
    }

    /** Overlays SNES slot data onto a copy of {@code template} (REPORT.md #5, steps 2-3). */
    public static CtSave snesToCt(SnesSrm.SnesSlot slot, CtSave template) {
        CtSave out = template.deepCopy();

        // Flags: verbatim.
        out.flags = slot.flags.clone();

        // Characters.
        for (int i = 0; i < 7; i++) {
            SnesSrm.SnesChar sc = slot.chars[i];
            CtSave.CtChar cc = out.chars[i];
            cc.setMaxHp(sc.maxHp);
            cc.setCurHp(sc.curHp);
            cc.setMaxMp(sc.maxMp);
            cc.setCurMp(sc.curMp);
            cc.setBaseMaxHp(u16(sc.raw, 0x3F));
            cc.setBasePower(sc.power);
            cc.setBaseStamina(sc.stamina);
            cc.setBaseSpeed(sc.speed);
            cc.setBaseMagic(sc.magic);
            cc.setBaseHit(sc.hit);
            cc.setBaseEvade(sc.evade);
            cc.setBaseMdef(sc.mdef);
            cc.setLevel(sc.level);
            cc.setExp(sc.exp);
            cc.setTpNext(sc.tpNext); // semantics unclear past ~chapter 7, REPORT.md #6.4
            cc.setEquipWeapon(equipField(sc.weaponId));
            cc.setEquipArmor(equipField(sc.armorId));
            cc.setEquipHelmet(equipField(sc.helmetId));
            cc.setEquipAccessory(equipField(sc.accessoryId));
            cc.setExpNext(sc.expNext);
            cc.setTpRelated(sc.field2d);
            cc.setGrowth(new byte[]{
                    sc.raw[0x2F], sc.raw[0x30], sc.raw[0x32], sc.raw[0x33], sc.raw[0x34], sc.raw[0x35],
            });
            byte[] curStats = new byte[9];
            System.arraycopy(sc.raw, 0x36, curStats, 0, 9);
            cc.setCurStats(curStats);
            // +01/+02/+03 and +0x47..+0x4A are per-character constants; keep the
            // template's (same character id => same constants).
        }

        // Inventory: re-bin by category.
        java.util.Map<String, List<CtSave.Entry>> buckets = new java.util.LinkedHashMap<>();
        for (CtSave.InventorySection sec : CtSave.INVENTORY_SECTIONS) buckets.put(sec.name, new ArrayList<>());
        for (SnesSrm.ItemStack item : slot.items) {
            Classified c = classifyItem(item.id);
            if (c == null) continue; // unknown/out-of-range id; drop rather than guess
            buckets.get(c.section).add(new CtSave.Entry(c.idx, item.count));
        }

        java.util.Map<String, List<CtSave.Entry>> newInventory = new java.util.LinkedHashMap<>();
        for (CtSave.InventorySection sec : CtSave.INVENTORY_SECTIONS) {
            List<CtSave.Entry> bucket = buckets.get(sec.name);
            List<CtSave.Entry> entries = new ArrayList<>(sec.capacity);
            for (int i = 0; i < sec.capacity; i++) {
                entries.add(i < bucket.size() ? bucket.get(i) : new CtSave.Entry(0, 0));
            }
            newInventory.put(sec.name, entries);
        }
        out.inventory = newInventory;

        // Tech block: verbatim 45 bytes.
        out.techBlock = slot.techBlock.clone();

        // Names: decoded SNES names for the 8 characters; keep template's extra
        // two trailing name-table entries (purpose unknown, REPORT.md #3.2).
        List<String> names = new ArrayList<>();
        for (String n : slot.names) names.add(n);
        names.add(template.names.get(8));
        names.add(template.names.get(9));
        out.names = names;

        // Party / reserve / recruited: verbatim.
        out.party = slot.party.clone();
        out.reserve = slot.reserve.clone();
        out.recruitedMask = slot.recruitedMask;

        out.gold = slot.gold;
        out.playTimeSeconds = slot.playTimeSeconds;

        return out;
    }

    private static int u16(byte[] a, int off) {
        return (a[off] & 0xFF) | ((a[off + 1] & 0xFF) << 8);
    }
}
