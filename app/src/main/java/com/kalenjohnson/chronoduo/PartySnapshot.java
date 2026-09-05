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
    // Active party list in the translated-65816 ("Asm") memory: 3 PC-id
    // bytes, 0x80 = empty slot (SNES CT's $7E2980 convention). Same offset
    // space as GameState.nativeReadAsmMem / the asmmem.bin dev dump.
    private static final int PARTY_LIST_OFFSET = 0x20980;
    private static final int PARTY_LIST_SLOTS = 3;
    private static final int PARTY_LIST_EMPTY = 0x80;
    // The C++ layer's OWN live active-party list: three i32 slots at
    // ChronoCanvas+0x124e8/+0x124ec/+0x124f0 (cSfcWork-relative 0x124a8, the
    // offset space nativeReadSfc uses). Each holds a GetCharaData index
    // DOUBLED (the field engine does `asr #1` before cSfcWork::GetCharaData;
    // see CHARA_DATA_BASE for the index->id mapping) or 0x80 for an empty
    // slot. This is what FieldImpl::atel_partyM (the script's
    // party-remove command), atel_partyMM and atel_split read and shift, so
    // it changes the instant a character leaves in a field cutscene -- the
    // Asm-memory copy at 0x20980 above is only re-synced on overworld entry,
    // which is why Frog's portrait used to linger through the whole castle.
    private static final int PARTY_SLOTS_OFFSET = 0x124a8;
    private static final int PARTY_SLOT_STRIDE = 4;
    // cSfcWork::GetCharaData(i) = cSfcWork+0x6924 + i*0x154; record +0x44 is
    // the PC id (atel_partyM compares it against the script's id byte).
    // The party-slot words above are GetCharaData indices x2, NOT PC ids x2:
    // a live Crono+Marle party read 00000004 00000006, i.e. indices 2 and 3,
    // so PCs sit at index id+2 and the id must be read back from +0x44.
    private static final int CHARA_DATA_BASE = 0x6924;
    private static final int CHARA_DATA_STRIDE = 0x154;
    private static final int CHARA_DATA_ID_OFF = 0x44;
    private static final int CHARA_DATA_COUNT = 16;

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
        public int slot; // 1-based active-party position (party order)
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

    /**
     * One row of the live battle Tech/Item submenu (see
     * {@link GameState#nativeGetBattleList()}). {@code x}/{@code y} are
     * already transformed into 1920x1080 game-view screen pixels via the
     * same {@link #CMD_SX_A}/{@link #CMD_SY_A} affine as {@link
     * CommandTarget}; either may be {@link Float#NaN} when the native side
     * doesn't know the row's on-screen position yet, in which case it must
     * not be tapped -- see PartyPanelView's confirm handling.
     */
    public static final class ListRow {
        public final int id;
        public final boolean usable;
        public final int extra; // tech: MP param; item: held count
        public final float x, y;

        public ListRow(int id, boolean usable, int extra, float x, float y) {
            this.id = id;
            this.usable = usable;
            this.extra = extra;
            this.x = x;
            this.y = y;
        }
    }

    // Live battle Tech/Item submenu, when open: kind 0 = tech, 1 = item; -1
    // = no submenu open (listRows then empty). Filled from
    // GameState.nativeGetBattleList() in read(), only while inBattle -- see
    // there. listOpen is always exactly (listKind >= 0).
    public int listKind = -1;
    public final List<ListRow> listRows = new ArrayList<>();
    public boolean listOpen;

    // Live battle-results accumulator (see GameState.nativeGetBattleResults),
    // filled in read() only while inBattle. resultsStep mirrors the native
    // comment_out2 step index (sb+0x22f4): -1 = unknown/not read, 0 = EXP
    // message, 2 = TP, 4 = Gold, 8/16/24 = item drops, 32 = idle. resultsExp/
    // Gold/Tp/Items come back as -1/-1/-1/empty when the battlework pointer
    // itself wasn't readable (see nativeGetBattleResults's fallback). resultsActive
    // mirrors the native battle_results_phase() predicate (every enemy in
    // {@link #enemies} dead) but is computed here from the already-parsed
    // enemy list, gated additionally on resultsStep being in [0, 30] (32 =
    // idle/done) so the panel stops showing the results window once the
    // native state machine itself has wound down.
    public int resultsStep = -1;
    public int resultsExp, resultsGold, resultsTp;
    public int[] resultsItems = new int[0];
    public boolean resultsActive;
    public int gold;        // cSfcWork+0x1a04 (u32), found by differential dump
    public int playSeconds; // cSfcWork+0x1a10 (u32), monotonically rising
    public String mapName = ""; // cached from ChronoCanvas::getFieldMapName()
    // Overworld tile position (Asm mem 0x2E102/0x2E103, u8 each; world is
    // 256x256 tiles). Valid only when on the overworld (mapName empty).
    public int worldX = -1, worldY = -1;
    // Current overworld/era id (GameState.nativeGetWorldEra(), -1 =
    // unknown/off-overworld) and the in-game MAP-overview mode byte
    // (GameState.nativeGetWorldMapMode(), -1 = unreadable; 6 = map screen
    // open per WorldScene::mapButton -- see world_era_report.md). Both are
    // plain safe_read, so cheap to fill every snapshot regardless of mode.
    public int worldEra = -1, worldMapMode = -1;
    // True while the running scene actually contains a WorldScene whose
    // WorldMap resolves (GameState.nativeGetWorldScenePresent, cached by the
    // per-frame GL tick) -- worldEra alone can stay valid from Asm memory
    // after the party goes indoors, and the live map capture must not be
    // requested when the RenderTextures no longer exist.
    public boolean worldScenePresent;
    // Pixel-granular overworld marker positions in the captured world image's
    // 1536x1024 top-left-origin space, from the same Asm addresses the game's
    // own map screen reads (GameState.nativeGetWorldPixelPos). -1 = unknown;
    // PartyPanelView falls back to the 8px-granular worldX/worldY above.
    public int worldPixelX = -1, worldPixelY = -1;
    // Epoch ("silverd") marker, drawn only when the Epoch is parked in this
    // era -- epochVisible mirrors markMiniMap's own gate.
    public int epochPixelX = -1, epochPixelY = -1;
    public boolean epochVisible;
    // Current field-map/location id, from GameState.nativeGetFieldMapId()
    // (ChronoCanvas+0x12300). -1 when unknown/unattached. Used by
    // PartyPanelView to look up a rendered DS-style area map bitmap for
    // indoor/dungeon maps (see ChronoAssets.getAreaMap).
    public int fieldMapId = -1;
    // Party leader's in-field tile position, from GameState.nativeGetFieldPos()
    // (CHARACTER_DATa record for party slot 1). NaN when unavailable/not read
    // (e.g. not attached, or the record wasn't readable) -- only meaningful
    // in field maps, not on the overworld (see worldX/worldY above). Cheap to
    // read (plain safe_read), so filled every snapshot regardless of mode.
    public float fieldX = Float.NaN, fieldY = Float.NaN;

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
        snap.fieldMapId = GameState.nativeGetFieldMapId();
        snap.worldEra = GameState.nativeGetWorldEra();
        snap.worldMapMode = GameState.nativeGetWorldMapMode();
        float[] fieldPos = GameState.nativeGetFieldPos();
        if (fieldPos != null && fieldPos.length >= 2) {
            snap.fieldX = fieldPos[0];
            snap.fieldY = fieldPos[1];
        }
        snap.worldScenePresent = GameState.nativeGetWorldScenePresent();
        if (snap.mapName.isEmpty()) {
            // Gated on "on the overworld" exactly like the tile read below:
            // these Asm bytes are not cleared when the party goes indoors, so
            // reading them unconditionally would both report stale overworld
            // coordinates off-overworld and, because sameAs() compares them,
            // make the panel repaint every poll if anything else happens to
            // write that memory during a field map or battle.
            int[] wp = GameState.nativeGetWorldPixelPos();
            if (wp != null && wp.length >= 5) {
                snap.worldPixelX = wp[0];
                snap.worldPixelY = wp[1];
                snap.epochPixelX = wp[2];
                snap.epochPixelY = wp[3];
                snap.epochVisible = wp[4] == 1;
            }
            byte[] pos = GameState.nativeReadAsmMem(0x2E102, 2);
            if (pos != null) {
                snap.worldX = pos[0] & 0xff;
                snap.worldY = pos[1] & 0xff;
            }
        }
        // Active party membership/order, most-live source first:
        //  1. the C++ layer's own slots at canvas+0x124e8 (PARTY_SLOTS_OFFSET)
        //     -- updated the instant a field cutscene adds/removes someone;
        //  2. the Asm-memory copy at 0x20980: three PC-id bytes, 0x80 =
        //     empty (verified live: Crono+Lucca 00 02 80, +Frog 00 02 04),
        //     but only re-synced when the party steps onto the overworld;
        //  3. the record field +0x11c, a JOIN COUNTER (Crono 1, Marle 2,
        //     Lucca 3, Frog 4 ... -1 = never joined / left), NOT a 1..3
        //     slot: filtering on 1..3 dropped Frog the moment he joined.
        int[] order = new int[7];
        java.util.Arrays.fill(order, -1);
        boolean haveList = readCanvasPartyList(order);
        if (!haveList) {
            // Fallback 1: the Asm-memory copy (stale inside field maps until
            // the next overworld entry, but right on the overworld itself).
            java.util.Arrays.fill(order, -1);
            byte[] plist = GameState.nativeReadAsmMem(PARTY_LIST_OFFSET, PARTY_LIST_SLOTS);
            if (plist != null && plist.length >= PARTY_LIST_SLOTS) {
                for (int k = 0; k < PARTY_LIST_SLOTS; k++) {
                    int id = plist[k] & 0xff;
                    if (id < 7 && order[id] < 0) { order[id] = k + 1; haveList = true; }
                    else if (id != PARTY_LIST_EMPTY) { haveList = false; break; } // garbage: fall back
                }
            }
        }
        for (int i = 0; i < 7; i++) {
            byte[] b = GameState.nativeReadSfc(CHARA_BASE + i * CHARA_STRIDE, 0x120);
            if (b == null) break;
            int joined = u32(b, 0x11c);
            // Fallback 2 (no readable list at all): +0x11c join-counter order.
            int slot = haveList ? order[i] : joined;
            if (slot < 1) continue; // empty / in the reserve, not the active party
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

            // Live Tech/Item submenu list, when one of those is open --
            // format: [kind, count, then per row: id, usable(0/1), extra,
            // x, y] in the same worldspace as the command toggles above, so
            // the same affine applies. Null (or too short to hold even the
            // 2-float header) means no submenu is currently open.
            float[] list = GameState.nativeGetBattleList();
            if (list != null && list.length >= 2) {
                int kind = (int) list[0];
                int count = (int) list[1];
                int idx = 2;
                for (int i = 0; i < count && idx + 5 <= list.length; i++, idx += 5) {
                    int id = (int) list[idx];
                    boolean usable = list[idx + 1] >= 0.5f;
                    int extra = (int) list[idx + 2];
                    float wx = list[idx + 3], wy = list[idx + 4];
                    float sx = Float.isNaN(wx) ? Float.NaN : CMD_SX_A + CMD_SX_B * wx;
                    float sy = Float.isNaN(wy) ? Float.NaN : CMD_SY_A + CMD_SY_B * wy;
                    snap.listRows.add(new ListRow(id, usable, extra, sx, sy));
                }
                snap.listKind = kind;
            }
            snap.listOpen = snap.listKind >= 0;

            int[] results = GameState.nativeGetBattleResults();
            if (results != null && results.length >= 6) {
                snap.resultsStep = results[0];
                snap.resultsExp = results[1];
                snap.resultsGold = results[2];
                snap.resultsTp = results[3];
                int itemCount = Math.max(0, Math.min(8, results[5]));
                int avail = results.length - 6;
                itemCount = Math.min(itemCount, avail);
                snap.resultsItems = new int[itemCount];
                System.arraycopy(results, 6, snap.resultsItems, 0, itemCount);
            }
            boolean allEnemiesDead = !snap.enemies.isEmpty();
            for (Enemy e : snap.enemies) {
                if (e.curHp != 0) { allEnemiesDead = false; break; }
            }
            snap.resultsActive = allEnemiesDead
                    && snap.resultsStep >= 1 && snap.resultsStep <= 30; // step 0 = engine still waiting out death animations; 1 = first window is up
        }
        return snap;
    }

    // Last raw canvas party-slot words, for a change-only logcat line
    // ("ChronoDuo party slots: ...") so a live run can confirm the decode.
    private static int lastSlotsA = Integer.MIN_VALUE, lastSlotsB, lastSlotsC;

    /**
     * Fills {@code order[id]} with the 1-based active-party position from
     * the C++ layer's live party list (see {@link #PARTY_SLOTS_OFFSET}).
     * Returns false, leaving {@code order} untouched, when the three words
     * don't decode as a well-formed list (unreadable, odd/out-of-range id,
     * duplicate) so the caller can fall back to the Asm-memory copy.
     */
    private static boolean readCanvasPartyList(int[] order) {
        byte[] w = GameState.nativeReadSfc(PARTY_SLOTS_OFFSET, PARTY_LIST_SLOTS * PARTY_SLOT_STRIDE);
        if (w == null || w.length < PARTY_LIST_SLOTS * PARTY_SLOT_STRIDE) return false;
        int a = u32(w, 0), b = u32(w, 4), c = u32(w, 8);
        if (a != lastSlotsA || b != lastSlotsB || c != lastSlotsC) {
            lastSlotsA = a; lastSlotsB = b; lastSlotsC = c;
            android.util.Log.i("ChronoDuo", String.format("party slots: %08x %08x %08x", a, b, c));
        }
        int[] ids = new int[PARTY_LIST_SLOTS];
        int count = 0;
        for (int k = 0; k < PARTY_LIST_SLOTS; k++) {
            int v = u32(w, k * PARTY_SLOT_STRIDE);
            if (v == PARTY_LIST_EMPTY) { ids[k] = -1; continue; }
            if (v < 0 || (v & 1) != 0 || (v >> 1) >= CHARA_DATA_COUNT) return false;
            ids[k] = charaDataId(v >> 1);
            if (ids[k] < 0 || ids[k] >= 7) return false;
            for (int j = 0; j < k; j++) if (ids[j] == ids[k]) return false; // duplicate
            count++;
        }
        if (count == 0) return false; // an empty party is never real; don't trust it
        for (int k = 0; k < PARTY_LIST_SLOTS; k++) if (ids[k] >= 0) order[ids[k]] = k + 1;
        return true;
    }

    /**
     * PC id stored in cSfcWork::GetCharaData(idx)+0x44, or -1 if unreadable.
     * Verified live 2026-09-05: Crono+Marle party slots 4/6 -> records 2/3
     * -> +0x44 = 0/1 (+0x40 = 0/1 = party position).
     */
    private static int charaDataId(int idx) {
        byte[] r = GameState.nativeReadSfc(CHARA_DATA_BASE + idx * CHARA_DATA_STRIDE, 0x48);
        if (r == null) return -1;
        return u32(r, CHARA_DATA_ID_OFF);
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
                || worldX != o.worldX || worldY != o.worldY
                || worldEra != o.worldEra || worldMapMode != o.worldMapMode
                || worldScenePresent != o.worldScenePresent
                || worldPixelX != o.worldPixelX || worldPixelY != o.worldPixelY
                || epochVisible != o.epochVisible
                || epochPixelX != o.epochPixelX || epochPixelY != o.epochPixelY
                || fieldMapId != o.fieldMapId
                || !feq(fieldX, o.fieldX) || !feq(fieldY, o.fieldY)) return false;
        if (inBattle != o.inBattle || enemies.size() != o.enemies.size()) return false;
        if (menuOpen != o.menuOpen || commandTargets.size() != o.commandTargets.size()) return false;
        if (listKind != o.listKind || listRows.size() != o.listRows.size()) return false;
        if (resultsActive != o.resultsActive || resultsStep != o.resultsStep
                || resultsExp != o.resultsExp || resultsGold != o.resultsGold
                || resultsTp != o.resultsTp
                || !java.util.Arrays.equals(resultsItems, o.resultsItems)) return false;
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
        for (int i = 0; i < listRows.size(); i++) {
            ListRow a = listRows.get(i), b = o.listRows.get(i);
            if (a.id != b.id || a.usable != b.usable || a.extra != b.extra
                    || !feq(a.x, b.x) || !feq(a.y, b.y)) return false;
        }
        return true;
    }

    // Plain != would treat NaN as "always different," even against another
    // NaN -- ListRow.x/y are legitimately NaN when the row's on-screen
    // position isn't known yet (see ListRow), and that state is often
    // stable frame to frame, so without this sameAs() would report a
    // difference (and PartyPanelView would redraw) every single frame while
    // such a row is visible.
    private static boolean feq(float a, float b) {
        return a == b || (Float.isNaN(a) && Float.isNaN(b));
    }
}
