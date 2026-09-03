package com.kalenjohnson.chronoduo;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed party state. Character records live at cSfcWork+0x10 with stride
 * 0x120: +0x00 char id, +0x10 curHP, +0x14 maxHP, +0x18 curMP, +0x1c maxMP,
 * +0x40 level (u32 LE each). Active names: libc++ std::strings at +0x19a8,
 * stride 0x18 (always SSO-short for 6-char names).
 */
public final class PartySnapshot {
    public static final String[] DEFAULT_NAMES =
            {"Crono", "Marle", "Lucca", "Robo", "Frog", "Ayla", "Magus"};

    private static final int CHARA_BASE = 0x10;
    private static final int CHARA_STRIDE = 0x120;
    private static final int NAMES_BASE = 0x19a8;
    private static final int NAME_STRIDE = 0x18;

    public static final class Member {
        public String name;
        public int level, curHp, maxHp, curMp, maxMp;
        public int slot; // 1-based party slot (record +0x11c); -1 = not in party
    }

    public final List<Member> members = new ArrayList<>();
    public int gold;        // cSfcWork+0x1a04 (u32), found by differential dump
    public int playSeconds; // cSfcWork+0x1a10 (u32), monotonically rising
    public String mapName = ""; // cached from ChronoCanvas::getFieldMapName()

    private static int u32(byte[] b, int off) {
        if (b == null || off + 4 > b.length) return 0;
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8
                | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    public static PartySnapshot read() {
        PartySnapshot snap = new PartySnapshot();
        if (!GameState.isAttached()) return snap;
        byte[] misc = GameState.nativeReadSfc(0x1a00, 0x20);
        snap.gold = u32(misc, 0x04);
        snap.playSeconds = u32(misc, 0x10);
        String mn = GameState.nativeGetMapName();
        snap.mapName = mn != null ? mn : "";
        for (int i = 0; i < 7; i++) {
            byte[] b = GameState.nativeReadSfc(CHARA_BASE + i * CHARA_STRIDE, 0x120);
            if (b == null) break;
            int slot = u32(b, 0x11c);
            if (slot < 1 || slot > 3) continue; // not in the active party
            int level = u32(b, 0x40);
            int maxHp = u32(b, 0x14);
            if (level <= 0 || level > 99 || maxHp <= 0 || maxHp > 999) continue;
            Member m = new Member();
            m.slot = slot;
            m.level = level;
            m.curHp = u32(b, 0x10);
            m.maxHp = maxHp;
            m.curMp = u32(b, 0x18);
            m.maxMp = u32(b, 0x1c);
            m.name = readName(i);
            snap.members.add(m);
        }
        snap.members.sort((a, b2) -> a.slot - b2.slot);
        return snap;
    }

    private static String readName(int id) {
        byte[] s = GameState.nativeReadSfc(NAMES_BASE + id * NAME_STRIDE, NAME_STRIDE);
        if (s != null && (s[0] & 1) == 0) {
            int len = (s[0] & 0xff) >> 1;
            if (len > 0 && len <= 22 && len < s.length) {
                return new String(s, 1, len, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return id < DEFAULT_NAMES.length ? DEFAULT_NAMES[id] : "?";
    }

    public boolean sameAs(PartySnapshot o) {
        if (o == null || o.members.size() != members.size()) return false;
        if (gold != o.gold || playSeconds != o.playSeconds
                || !mapName.equals(o.mapName)) return false;
        for (int i = 0; i < members.size(); i++) {
            Member a = members.get(i), b = o.members.get(i);
            if (!a.name.equals(b.name) || a.level != b.level
                    || a.curHp != b.curHp || a.maxHp != b.maxHp
                    || a.curMp != b.curMp || a.maxMp != b.maxMp) return false;
        }
        return true;
    }
}
