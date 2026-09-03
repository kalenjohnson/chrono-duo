package com.kalenjohnson.chronoduo;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed party state. Character records live at cSfcWork+0x10 with stride
 * 0x120: +0x00 char id, +0x10 maxHP, +0x14 curHP, +0x18 maxMP, +0x1c curMP,
 * +0x40 level (u32 LE each). HP order verified live (post-loss panel read
 * "70/1" — 70 is Crono's max, at +0x10). MP order is inferred to mirror HP's
 * (max-then-cur) and is NOT independently verified. Active names: libc++
 * std::strings at +0x19a8, stride 0x18 (always SSO-short for 6-char names).
 */
public final class PartySnapshot {
    public static final String[] DEFAULT_NAMES =
            {"Crono", "Marle", "Lucca", "Robo", "Frog", "Ayla", "Magus"};

    private static final int CHARA_BASE = 0x10;
    private static final int CHARA_STRIDE = 0x120;
    private static final int NAMES_BASE = 0x19a8;
    private static final int NAME_STRIDE = 0x18;

    // Battle actor array (see GameState.nativeReadBattleActors): stride 0x80
    // per actor, at least 10 slots. u16 LE +0x03 = current HP, +0x05 = max HP.
    // Slots 0-2 = party (party order), slots 3+ = enemies (present if maxHP >
    // 0). MP offsets within the block are NOT reliably known -- calibration
    // against fight6/battle_*_btlchara.bin (8 generations, party = Crono +
    // Marle only) found a candidate for Crono (cur=+0x30, max=+0x45, matching
    // the known 8/10 exactly in all 8 captures) but the equivalent search for
    // Marle's known 12/14 found no offset at all matching 14 anywhere in her
    // block, in any capture -- and Crono's "match" isn't adjacent the way HP's
    // +0x03/+0x05 pair is (21-byte gap), unlike a real stat pair. Given a real
    // MP field should generalize across party members at a consistent relative
    // offset and this one doesn't, it's treated as a coincidental match on
    // small integers rather than the real field. MP is therefore left out of
    // battle mode entirely; only HP is shown live during battle.
    private static final int BTL_STRIDE = 0x80;
    private static final int BTL_SLOTS = 10;
    private static final int BTL_PARTY_SLOTS = 3;
    private static final int BTL_HP_OFF = 0x03;
    private static final int BTL_MAXHP_OFF = 0x05;
    private static final int BTL_MAX_PLAUSIBLE_HP = 9999;

    public static final class Member {
        public String name;
        public int level, curHp, maxHp, curMp, maxMp;
        public int slot; // 1-based party slot (record +0x11c); -1 = not in party
        // Live battle HP, when inBattle -- overrides curHp/maxHp for display
        // purposes while a fight is active (see PartyPanelView).
        public int battleCurHp, battleMaxHp;
    }

    public static final class Enemy {
        public int curHp, maxHp;
        public int id; // monster id, actor block +0x00 (u8); index into monster.txt name table
    }

    public final List<Member> members = new ArrayList<>();
    public boolean inBattle;
    public final List<Enemy> enemies = new ArrayList<>();
    public int gold;        // cSfcWork+0x1a04 (u32), found by differential dump
    public int playSeconds; // cSfcWork+0x1a10 (u32), monotonically rising
    public String mapName = ""; // cached from ChronoCanvas::getFieldMapName()
    // Overworld tile position (Asm mem 0x2E102/0x2E103, u8 each; world is
    // 256x256 tiles). Valid only when on the overworld (mapName empty).
    public int worldX = -1, worldY = -1;

    private static int u32(byte[] b, int off) {
        if (b == null || off + 4 > b.length) return 0;
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8
                | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    private static int u16(byte[] b, int off) {
        if (b == null || off + 2 > b.length) return 0;
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8;
    }

    private static int u8(byte[] b, int off) {
        if (b == null || off >= b.length) return 0;
        return b[off] & 0xff;
    }

    public static PartySnapshot read() {
        PartySnapshot snap = new PartySnapshot();
        if (!GameState.isAttached()) return snap;
        byte[] misc = GameState.nativeReadSfc(0x1a00, 0x20);
        snap.gold = u32(misc, 0x04);
        snap.playSeconds = u32(misc, 0x10);
        String mn = GameState.nativeGetMapName();
        snap.mapName = mn != null ? mn : "";
        if (snap.mapName.isEmpty()) {
            byte[] pos = GameState.nativeReadAsmMem(0x2E102, 2);
            if (pos != null) {
                snap.worldX = pos[0] & 0xff;
                snap.worldY = pos[1] & 0xff;
            }
        }
        for (int i = 0; i < 7; i++) {
            byte[] b = GameState.nativeReadSfc(CHARA_BASE + i * CHARA_STRIDE, 0x120);
            if (b == null) break;
            int slot = u32(b, 0x11c);
            if (slot < 1 || slot > 3) continue; // not in the active party
            int level = u32(b, 0x40);
            int maxHp = u32(b, 0x10);
            if (level <= 0 || level > 99 || maxHp <= 0 || maxHp > 999) continue;
            Member m = new Member();
            m.slot = slot;
            m.level = level;
            m.maxHp = maxHp;
            m.curHp = u32(b, 0x14);
            m.maxMp = u32(b, 0x18);
            m.curMp = u32(b, 0x1c);
            m.name = readName(i);
            snap.members.add(m);
        }
        snap.members.sort((a, b2) -> a.slot - b2.slot);

        byte[] btl = GameState.nativeReadBattleActors();
        if (btl != null && btl.length >= BTL_SLOTS * BTL_STRIDE) {
            snap.inBattle = true;
            for (int i = 0; i < BTL_PARTY_SLOTS && i < snap.members.size(); i++) {
                int base = i * BTL_STRIDE;
                int curHp = u16(btl, base + BTL_HP_OFF);
                int maxHp = u16(btl, base + BTL_MAXHP_OFF);
                if (maxHp <= 0 || maxHp > BTL_MAX_PLAUSIBLE_HP || curHp > BTL_MAX_PLAUSIBLE_HP) continue;
                Member m = snap.members.get(i);
                m.battleCurHp = curHp;
                m.battleMaxHp = maxHp;
            }
            for (int i = BTL_PARTY_SLOTS; i < BTL_SLOTS; i++) {
                int base = i * BTL_STRIDE;
                int curHp = u16(btl, base + BTL_HP_OFF);
                int maxHp = u16(btl, base + BTL_MAXHP_OFF);
                if (maxHp <= 0 || maxHp > BTL_MAX_PLAUSIBLE_HP || curHp > BTL_MAX_PLAUSIBLE_HP) continue;
                Enemy e = new Enemy();
                e.curHp = curHp;
                e.maxHp = maxHp;
                e.id = u8(btl, base + 0x00);
                snap.enemies.add(e);
            }
        }
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
                || !mapName.equals(o.mapName)
                || worldX != o.worldX || worldY != o.worldY) return false;
        if (inBattle != o.inBattle || enemies.size() != o.enemies.size()) return false;
        for (int i = 0; i < members.size(); i++) {
            Member a = members.get(i), b = o.members.get(i);
            if (!a.name.equals(b.name) || a.level != b.level
                    || a.curHp != b.curHp || a.maxHp != b.maxHp
                    || a.curMp != b.curMp || a.maxMp != b.maxMp
                    || a.battleCurHp != b.battleCurHp || a.battleMaxHp != b.battleMaxHp) return false;
        }
        for (int i = 0; i < enemies.size(); i++) {
            Enemy a = enemies.get(i), b = o.enemies.get(i);
            if (a.curHp != b.curHp || a.maxHp != b.maxHp || a.id != b.id) return false;
        }
        return true;
    }
}
