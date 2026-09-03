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
    // Battle MP: u8 curMP at +0x07, u8 maxMP at +0x09 (+0x08 is always 0 --
    // reading it as maxMP showed "x/0" in battle). Verified against 8
    // in-fight snapshots: Crono 0x00/0x0e = 0/14, Marle 0x07/0x12 = 7/18.
    private static final int BTL_MP_OFF = 0x07;
    private static final int BTL_MAXMP_OFF = 0x09;
    private static final int BTL_MAX_PLAUSIBLE_HP = 9999;

    public static final class Member {
        public String name;
        public int level, curHp, maxHp, curMp, maxMp;
        public int slot; // 1-based party slot (record +0x11c); -1 = not in party
        // Live battle HP, when inBattle -- overrides curHp/maxHp for display
        // purposes while a fight is active (see PartyPanelView).
        public int battleCurHp, battleMaxHp;
        public int battleCurMp, battleMaxMp;
    }

    public static final class Enemy {
        public int curHp, maxHp;
        public int id; // monster id, actor block +0x00 (u8); index into monster.txt name table
    }

    /**
     * One live battle command menu toggle (Attack/Tech/Item), already
     * transformed from cocos worldspace into game-view screen pixels (see
     * {@link GameState#nativeGetBattleToggles()} and the affine in
     * {@link #read()}). Order within {@link #commandTargets} is always
     * Attack, Tech, Item (sorted by screenY ascending, per the calibrated
     * column layout).
     */
    public static final class CommandTarget {
        public final float x, y;
        // Mirrors the game's own cursor: true when this toggle's live
        // _selected flag (see GameState.nativeGetBattleToggles) was set at
        // read time. At most one CommandTarget in a snapshot is selected;
        // PartyPanelView falls back to highlighting index 0 when none is.
        public final boolean selected;
        public CommandTarget(float x, float y, boolean selected) {
            this.x = x;
            this.y = y;
            this.selected = selected;
        }
    }

    // Command-menu affine (worldspace -> 1920x1080 game-view screen pixels),
    // calibrated +-2px against the real menu.
    private static final float CMD_SX_A = 3714.0f, CMD_SX_B = 3.20f;
    private static final float CMD_SY_A = -1090.0f, CMD_SY_B = -3.39f;
    // Command-toggle column band: right-side x, and y >= this cutoff (the
    // two character-tab toggles sit above this at similar x and must be
    // excluded).
    private static final float CMD_MIN_X = 1200f;
    private static final float CMD_MIN_Y = 700f;
    private static final float CMD_MAX_Y = 1080f;
    private static final int CMD_MAX_TARGETS = 3;

    public final List<Member> members = new ArrayList<>();
    public boolean inBattle;
    public final List<Enemy> enemies = new ArrayList<>();
    // Up to 3 live command-menu targets (Attack, Tech, Item), in that order;
    // empty when the command menu isn't currently open (ATB not ready, or a
    // sub-menu is showing instead). See CommandTarget.
    public final List<CommandTarget> commandTargets = new ArrayList<>();
    public boolean menuOpen; // == !commandTargets.isEmpty()
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
                m.battleCurMp = btl[base + BTL_MP_OFF] & 0xff;
                m.battleMaxMp = btl[base + BTL_MAXMP_OFF] & 0xff;
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

            float[] toggles = GameState.nativeGetBattleToggles();
            // Quintuples: [x, y, visible, selected, selectedIndex] per toggle
            // -- widened from triples to carry the live selection flag (see
            // CommandTarget.selected / GameState.nativeGetBattleToggles).
            if (toggles != null && toggles.length >= 5) {
                List<float[]> candidates = new ArrayList<>();
                for (int i = 0; i + 4 < toggles.length; i += 5) {
                    float vis = toggles[i + 2];
                    if (vis < 0.5f) continue;
                    float sx = CMD_SX_A + CMD_SX_B * toggles[i];
                    float sy = CMD_SY_A + CMD_SY_B * toggles[i + 1];
                    if (sx > CMD_MIN_X && sy >= CMD_MIN_Y && sy <= CMD_MAX_Y) {
                        // Selection signal: the game's visible highlight is the
                        // toggle's IMAGE SWAP (selectedIndex >= 1), not the
                        // transient _selected press flag (which only pulses for
                        // ~150ms during a tap — observed live). Treat either as
                        // selected so a mid-press still highlights.
                        float sel = (toggles[i + 4] >= 1f || toggles[i + 3] >= 0.5f) ? 1f : 0f;
                        candidates.add(new float[]{sx, sy, sel});
                    }
                }
                // Only trust the band when it holds exactly the 3 command
                // toggles the calibration promises (Attack/Tech/Item); a
                // partial read (sub-menu open, mid-transition, 1-2 visible)
                // would otherwise get labeled from index 0 and mis-inject
                // (e.g. "Attack" firing Tech). Treat that as menu-closed.
                candidates.sort((a, b) -> Float.compare(a[1], b[1]));
                if (candidates.size() == CMD_MAX_TARGETS) {
                    for (float[] p : candidates) {
                        snap.commandTargets.add(new CommandTarget(p[0], p[1], p[2] >= 0.5f));
                    }
                }
            }
            snap.menuOpen = snap.commandTargets.size() == CMD_MAX_TARGETS;
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
        if (menuOpen != o.menuOpen || commandTargets.size() != o.commandTargets.size()) return false;
        for (int i = 0; i < members.size(); i++) {
            Member a = members.get(i), b = o.members.get(i);
            if (!a.name.equals(b.name) || a.level != b.level
                    || a.curHp != b.curHp || a.maxHp != b.maxHp
                    || a.curMp != b.curMp || a.maxMp != b.maxMp
                    || a.battleCurHp != b.battleCurHp || a.battleMaxHp != b.battleMaxHp
                    || a.battleCurMp != b.battleCurMp || a.battleMaxMp != b.battleMaxMp) return false;
        }
        for (int i = 0; i < enemies.size(); i++) {
            Enemy a = enemies.get(i), b = o.enemies.get(i);
            if (a.curHp != b.curHp || a.maxHp != b.maxHp || a.id != b.id) return false;
        }
        for (int i = 0; i < commandTargets.size(); i++) {
            CommandTarget a = commandTargets.get(i), b = o.commandTargets.get(i);
            if (a.x != b.x || a.y != b.y || a.selected != b.selected) return false;
        }
        return true;
    }
}
