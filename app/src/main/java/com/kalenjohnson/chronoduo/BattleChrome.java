package com.kalenjohnson.chronoduo;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The bottom screen's battle side: enemy HP bars, the command / targeting /
 * submenu-list / results bands with their tap-to-inject behaviour, the
 * top-strip chips (fast-forward badge, gear-vs-AUTO crossfade, eye toggle)
 * and the battle d-pad navigation. Split out of {@link PartyPanelView},
 * which still owns the canvas, the live snapshot hand-off ({@link #update}),
 * shared paints and chrome helpers (window-texture buttons, nine-patch,
 * text setup), reached through same-named private delegates below. Battle
 * state -- panel-owned selections, injection cooldowns, fades -- lives here.
 */
final class BattleChrome {
    private final PartyPanelView view;
    // Shared with the view (same Paint objects): see PartyPanelView's fields.
    private final Paint fill, stroke, text;
    private static final int INK = PartyPanelView.INK;
    private static final int BOX_BG = PartyPanelView.BOX_BG;
    private static final int BOX_BORDER_OUT = PartyPanelView.BOX_BORDER_OUT;
    private static final int BOX_BORDER_IN = PartyPanelView.BOX_BORDER_IN;
    private static final int DEFAULT_HIGHLIGHT_COLOR = PartyPanelView.DEFAULT_HIGHLIGHT_COLOR;
    private static final long MODE_FADE_NANOS = PartyPanelView.MODE_FADE_NANOS;
    private static final long TITLE_FADE_NANOS = PartyPanelView.TITLE_FADE_NANOS;

    // The live snapshot as of the last update(); the previous one during
    // update() itself (see that method).
    private PartySnapshot snap = new PartySnapshot();

    BattleChrome(PartyPanelView view, Context context) {
        this.view = view;
        this.fill = view.fill;
        this.stroke = view.stroke;
        this.text = view.text;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        honorHiddenHp = !MODE_FULL.equals(prefs.getString(KEY_ENEMY_HP_MODE, MODE_HONOR));
    }

    // ---- host delegates: same names as PartyPanelView's, so the code moved
    // here from that class reads unchanged ------------------------------------

    private Context getContext() { return view.getContext(); }
    private int getWidth() { return view.getWidth(); }
    private int getHeight() { return view.getHeight(); }
    private void invalidate() { view.invalidate(); }
    private float dp(float v) { return view.dp(v); }
    private void setText(float size, int color, boolean bold, Paint.Align align, boolean shadow) {
        view.setText(size, color, bold, align, shadow);
    }
    private void applyTypeface(Typeface fallback) { view.applyTypeface(fallback); }
    private void drawCommandButton(Canvas c, RectF box, String label, Bitmap winTex, boolean pressed) {
        view.drawCommandButton(c, box, label, winTex, pressed);
    }
    private void drawCommandButton(Canvas c, RectF box, String label, Bitmap winTex, boolean pressed, boolean highlighted) {
        view.drawCommandButton(c, box, label, winTex, pressed, highlighted);
    }
    private void drawNinePatch(Canvas c, Bitmap bmp, int srcInset, RectF dst, float destInset) {
        view.drawNinePatch(c, bmp, srcInset, dst, destInset);
    }

    // ---- state (moved verbatim from PartyPanelView) ----------------------------

    // Battle-results single-message crossfade (see drawResultsWindow):
    // resultsMessage is the current big line ("Earned N EXP." / "Found N
    // G." / "<Name> leveled up!" / etc), chosen from snap.resultsStep by
    // computeResultsMessage. resultsFadingMessage/resultsMessageFadeStart
    // mirror fadingOutTitle/titleFadeStart above (reuses TITLE_FADE_NANOS)
    // -- when the step machine advances to a new message, the old one fades
    // out while the new one fades in, drawing the eye down toward the panel.
    // A step with no message of its own (the per-actor bookkeeping steps
    // between the documented ones) leaves resultsMessage unchanged, per
    // computeResultsMessage returning null. resultsBaseLevels snapshots
    // each party member's level (by members-list index, matching slot
    // order) at the moment resultsActive first turns true, so a later
    // level-up step can tell which member's level rose -- see
    // leveledMemberName. All four are cleared whenever resultsActive drops
    // (results screen closed or the battle itself ended) -- see update().
    private String resultsMessage;
    private String resultsFadingMessage;
    private long resultsMessageFadeStart = -1L;
    // The game holds its victory pose for a beat before the first Earned
    // window; mirror that so the bottom message doesn't pop the instant the
    // last enemy drops.
    private static final long RESULTS_DELAY_NANOS = 0L; // no artificial delay: resultsActive already keys off the game posting its first window (step 1)
    private long resultsActiveSince = -1L;

    private boolean resultsVisible(PartySnapshot s) {
        return s.resultsActive && resultsActiveSince >= 0
                && System.nanoTime() - resultsActiveSince >= RESULTS_DELAY_NANOS;
    }

    private int[] resultsBaseLevels;

    // Per-slot eased HP-bar fractions (enemy battle bars). Keyed by enemy
    // index -- there's no persistent enemy identity to key on, and slot
    // order is stable within one fight, matching the "enemy slot" ask.
    private final Map<Integer, Float> enemyBarFrac = new HashMap<>();

    // Enemy hidden-HP display setting: HONOR (default) hides cur/max numbers
    // and shows a neutral full-width bar for any enemy the game itself
    // marks as hidden-info (MonsterNameData.dat flag byte == 255, bosses/
    // event enemies); FULL always shows real numbers, as today. Persisted
    // across sessions and toggled by tapping the eye glyph during battle.
    private static final String PREFS_NAME = PartyPanelView.PREFS_NAME;
    private static final String KEY_ENEMY_HP_MODE = "enemy_hp_mode";
    private static final String MODE_HONOR = "HONOR";
    private static final String MODE_FULL = "FULL";
    private boolean honorHiddenHp = true;
    // Hit box (screen px) for the battle-mode "eye" toggle glyph, updated
    // each frame it's drawn; empty (and therefore never touch-hit) outside
    // battle mode -- see drawEyeToggle / onTouchEvent.
    private final RectF eyeHitBox = new RectF();
    private static final float EYE_GLYPH_RADIUS = 12f; // ~24px diameter
    private static final float EYE_HIT_HALF = 24f;     // ~48px hit box

    /**
     * Wall-clock 0..1 visibility tween for one top-strip chip, so the gear
     * (outside battle) and the eye/AUTO chips (battle) fade in and out
     * across the battle boundary instead of popping -- the same
     * self-scheduled onDraw timing as the mode crossfade ({@link
     * #MODE_FADE_NANOS}), no ValueAnimator. {@link #setVisible} retargets
     * from wherever the value currently is, so a quick in-and-out (an
     * ambush that ends in one hit) reverses smoothly. Hit boxes are NOT
     * driven by this -- they follow the target state directly (see the
     * {@link #onDraw} chip block), so a chip becomes tappable the moment
     * it starts fading in and stops being tappable the moment it starts
     * fading out.
     */
    private static final class ChipFade {
        private boolean target;
        private float from;
        private long start = -1L;
        ChipFade(boolean visible) { target = visible; from = visible ? 1f : 0f; }
        void setVisible(boolean visible) {
            if (visible == target) return;
            from = value();
            target = visible;
            start = System.nanoTime();
        }
        /** Current 0..1 opacity. */
        float value() {
            float to = target ? 1f : 0f;
            if (start < 0) return to;
            float t = Math.min(1f, (System.nanoTime() - start) / (float) MODE_FADE_NANOS);
            if (t >= 1f) { start = -1L; return to; }
            return from + (to - from) * t;
        }
        boolean animating() { return start >= 0; }
    }
    private final ChipFade gearFade = new ChipFade(true);
    private final ChipFade eyeFade = new ChipFade(false);
    private final ChipFade autoFade = new ChipFade(false);

    // Hit box (screen px) for the battle-mode "AUTO" chip, updated each
    // frame it's drawn; cleared (and therefore never touch-hit) whenever
    // snap.autoBattleAvailable is false -- see drawAutoToggle / onTouchEvent.
    private final RectF autoHitBox = new RectF();
    // Tap injects BattleInput.tap at the live toggle's game-screen position
    // (snap.autoBattleX/Y); the displayed ON/OFF state always comes from the
    // next snapshot poll (snap.autoBattleOn), never guessed locally.

    // Battle command buttons (Attack/Tech/Item) -- on-panel hit rects (panel
    // px, not game-screen px) for the up to 3 currently visible command
    // targets in snap.commandTargets, same order. commandCount is how many
    // of commandHitBoxes[0..2] are live this frame; touches outside that
    // range never hit-test. Updated only while drawing the LIVE snapshot
    // (never the fading-out one) so a stale rect can't outlive its target.
    private static final String[] COMMAND_LABELS = {"Attack", "Tech", "Item"};
    private final RectF[] commandHitBoxes = {new RectF(), new RectF(), new RectF()};
    // Tap-to-target: one hit rect per drawn enemy row (live snapshot only),
    // plus the row's actor slot; only consulted while targeting is active.
    // Tapping a row that isn't the current target moves the game's cursor
    // there (GameState.nativeSetBattleTargetSlot); tapping the row that
    // already is the target confirms it, like the Confirm button.
    private static final int ENEMY_ROWS_MAX = 8;
    private final RectF[] enemyHitBoxes = new RectF[ENEMY_ROWS_MAX];
    private final int[] enemyHitSlots = new int[ENEMY_ROWS_MAX];
    private int enemyHitCount;
    { for (int i = 0; i < ENEMY_ROWS_MAX; i++) enemyHitBoxes[i] = new RectF(); }
    private int commandCount;
    // Panel-owned command-row selection (0..commandCount-1), independent of
    // the game's own cursor (see PartySnapshot.CommandTarget.selected, which
    // is no longer used for the highlight). Persists across snapshots and is
    // reset to 0 only when the command menu newly opens -- see update()'s
    // menuOpen-transition check. Driven by onControllerLeft/Right/Confirm and
    // by a direct tap (onTouchEvent sets it to the tapped index).
    private int commandSel;
    // Brief pressed-state visual feedback on the tapped button.
    private int pressedCommand = -1;
    private long pressedAt = -1L;
    private static final long PRESS_FEEDBACK_NANOS = 150_000_000L;
    // Safety: at most one in-flight injected tap -- a DOWN inside a command
    // button's rect within this cooldown of the last injection is ignored.
    private long lastInjectAt = -1L;
    private static final long INJECT_COOLDOWN_NANOS = 250_000_000L;
    // Brief pressed-state visual feedback on the AUTO chip, mirroring
    // pressedCommand/pressedAt above -- the actual ON/OFF label always comes
    // from the next snap.autoBattleOn poll (see injectAutoBattleToggle).
    private boolean pressedAuto;
    private long pressedAutoAt = -1L;

    // Target-selection phase: after a successful command injection the game
    // enters cursor-based target selection (top-screen cursor, dpad cycles,
    // A confirms). targetingUntil is a nanoTime deadline (-1 when inactive);
    // targeting mode itself is computed live each frame in
    // isTargetingActive() rather than cached, so it always reflects the
    // current snapshot. See injectCommand (arms it) and update() (clears it
    // early on the two exit conditions: the next command menu opening, or
    // battle ending).
    // Since 2026-09-16 the band is driven by the game's own target-selection
    // phase counter (snap.targetingActive, see GameState.
    // nativeReadBattleTargeting); this timer is now only a short grace so
    // the band appears the instant a confirm is injected, before the next
    // 150ms poll catches the game up -- it used to be an 8s deadline that
    // made the band vanish under a slow player and linger after a cancel.
    private static final long TARGETING_DURATION_NANOS = 1_500_000_000L;
    private long targetingUntil = -1L;
    // Third targeting exit: B during target selection for a tech/item sends
    // the game back to the submenu list, not the command menu, so menuOpen
    // never flips and the band used to sit there until the 8s timer ran
    // out (read as lag). The list closes when the row is decided, so
    // "list observed closed since arming, now open again" is the reopen.
    // Armed from a command confirm (Attack), the list was never open, so any
    // list open at all also ends targeting.
    private boolean targetingSawListClosed;
    // What the targeting band is choosing a target FOR ("Attack", or the
    // committed tech/item's display name) -- drawn as the confirm button's
    // label so the band reads as "use X on the highlighted target". Set at
    // arm time from the same name tables the list rows use; null falls back
    // to the plain "Confirm" label.
    private String targetingLabel;

    // Double-A fix: after a successful command or list-row confirm,
    // snap.menuOpen/listOpen can stay true for up to one poll interval
    // (the snapshot is one read behind the game), which would otherwise let
    // a fast second A press get consumed again by this panel's own nav and
    // dropped -- instead of reaching the game as the native A press that
    // confirms the just-armed target. While pendingMenuClose is true,
    // onControllerConfirm() refuses to consume A at all (returns false), so
    // that second press passes straight through. pendingCloseIsCommand says
    // which snapshot field to watch for the close: true after a command
    // confirm (clear on menuOpen == false), false after a list-row confirm
    // (clear on listOpen == false) -- see update(). pendingMenuCloseAt is a
    // safety-timeout deadline in case the expected close is never observed.
    private boolean pendingMenuClose;
    private boolean pendingCloseIsCommand;
    private long pendingMenuCloseAt = -1L;
    private static final long PENDING_CLOSE_TIMEOUT_NANOS = 1_500_000_000L;
    // Left-/right-triangle glyphs written as unicode escapes rather than raw
    // UTF-8 bytes -- this file has had no non-ASCII characters until now, so
    // there's no evidence javac's source encoding is set to UTF-8 for this
    // module; escapes sidestep the question entirely. (And a literal
    // backslash-u may not appear even in comments: javac decodes it anywhere.)
    private static final String[] TARGET_LABELS = {"\u25C0", "Confirm", "\u25B6"};
    // Hit rects for the three targeting buttons: 0=left, 1=confirm, 2=right.
    private final RectF[] targetHitBoxes = {new RectF(), new RectF(), new RectF()};
    private int targetCount;
    private long lastArrowInjectAt = -1L;
    private long lastConfirmInjectAt = -1L;
    private static final long ARROW_COOLDOWN_NANOS = 120_000_000L;
    private static final long CONFIRM_COOLDOWN_NANOS = 250_000_000L;

    // Submenu-list phase: tapping Tech or Item (injectCommand idx 1/2) opens
    // the game's own scrollable tech/item list; the panel mirrors it
    // directly from the live snapshot (snap.listOpen/listRows -- see
    // PartySnapshot), rather than a timer-based band like targeting's, since
    // the game itself reports exactly when the submenu is open and what it
    // holds. listSel is the panel-owned selected row (independent of the
    // game's own cursor, same spirit as commandSel), reset to 0 whenever the
    // submenu newly opens or its kind changes -- see update().
    private int listSel;
    // Row height (fraction of view height) and the max number of rows shown
    // at once before the list starts scrolling -- see computeListVisibleRows/
    // listBandRect.
    private static final float LIST_ROW_H_FRAC = 0.058f;
    private static final int LIST_MAX_ROWS = 6;
    // Hit rects for the currently-drawn window of rows (0..listVisibleCount-1,
    // mapped to absolute row index via listWindowStart), updated only while
    // drawing the live snapshot -- same pattern as commandHitBoxes.
    private final RectF[] listRowHitBoxes = {
            new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF(),
    };
    private int listVisibleCount;
    private int listWindowStart;
    // Small "back" chip (top strip, right of the AUTO chip and eye glyph
    // slots -- see drawListBackToggle), shown only while the submenu-list band is up --
    // taps it like the game's own B/cancel would. Not part of the row list
    // spec itself, but without it a touch-only player has no way out of the
    // submenu (the game's real B button is deliberately left unconsumed --
    // see GameControllerInput -- but touch has no B button to fall back on).
    private final RectF listBackHitBox = new RectF();
    private static final float BACK_GLYPH_RADIUS = 12f;
    private static final float BACK_HIT_HALF = 24f;

    // On-panel fast-forward badge (both in and out of battle) -- see
    // drawFastForwardBadge. Tapping it toggles GameSpeed regardless of
    // ffMode.
    final RectF ffHitBox = new RectF(); // SettingsScreen clears it while the sheet is up (view.battle.ffHitBox)

    // ---- touch / d-pad / snapshot hand-off ------------------------------------

    private void toggleHiddenHpMode() {
        honorHiddenHp = !honorHiddenHp;
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_ENEMY_HP_MODE, honorHiddenHp ? MODE_HONOR : MODE_FULL)
                .apply();
        invalidate();
    }

    /**
     * DOWN-only tap dispatch for everything this class draws, called by
     * {@link PartyPanelView#onTouchEvent} after the settings sheet and gear
     * chip have had their chance: fast-forward badge, eye/AUTO chips,
     * command buttons, targeting band, submenu back chip and rows. A tap
     * on a battle control injects the matching input into the game (see
     * {@link #injectCommand} et al.). Returns false when nothing was hit.
     */
    boolean onTouchDown(float x, float y) {
        // Fast-forward badge (see drawFastForwardBadge): drawn/hit-testable
        // both in and out of battle, toggle semantics regardless of ffMode.
        if (!ffHitBox.isEmpty() && ffHitBox.contains(x, y)) {
            GameSpeed.toggle(getContext());
            invalidate();
            return true;
        }
        if (snap.inBattle && !eyeHitBox.isEmpty()
                && eyeHitBox.contains(x, y)) {
            toggleHiddenHpMode();
            return true;
        }
        if (snap.inBattle && snap.autoBattleAvailable && !autoHitBox.isEmpty()
                && autoHitBox.contains(x, y)) {
            injectAutoBattleToggle();
            return true;
        }
        if (snap.inBattle && snap.menuOpen && commandCount > 0) {
            for (int i = 0; i < commandCount; i++) {
                if (commandHitBoxes[i].contains(x, y)) {
                    commandSel = i;
                    injectCommand(i);
                    return true;
                }
            }
        }
        if (isTargetingActive(System.nanoTime()) && targetCount > 0) {
            for (int i = 0; i < targetCount; i++) {
                if (targetHitBoxes[i].contains(x, y)) {
                    injectTarget(i);
                    return true;
                }
            }
            for (int i = 0; i < enemyHitCount; i++) {
                if (enemyHitBoxes[i].contains(x, y)) return selectTargetSlot(enemyHitSlots[i]);
            }
        }
        if (snap.inBattle && snap.listOpen && !listBackHitBox.isEmpty()
                && listBackHitBox.contains(x, y)) {
            backList();
            return true;
        }
        if (snap.inBattle && snap.listOpen && listVisibleCount > 0) {
            for (int i = 0; i < listVisibleCount; i++) {
                if (listRowHitBoxes[i].contains(x, y)) {
                    int idx = listWindowStart + i;
                    listSel = idx;
                    confirmListRow(idx);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True while the target-selection band should be shown/hit-testable:
     * in battle, the command menu is not open (it closed when a command was
     * chosen), and we're still inside the {@link #TARGETING_DURATION_NANOS}
     * window armed by the last successful {@link #injectCommand}.
     */
    private boolean isTargetingActive(long now) {
        if (!snap.inBattle || snap.menuOpen) return false;
        return snap.targetingActive || (targetingUntil > 0 && now < targetingUntil);
    }

    /**
     * True exactly when the panel-owned submenu-list row selection
     * ({@link #listSel}) is the thing that should react to up/down/confirm
     * navigation -- in battle, with a live Tech/Item submenu open, and at
     * least one row to select. Mirrors {@link #commandNavActive}. Also
     * excludes {@link #isTargetingActive}: a row confirm arms targeting
     * immediately (see {@link #confirmListRow}) while {@code snap.listOpen}
     * is still true for one more poll (it's snapshot-driven, one read behind
     * the game), so without this check a controller A press meant for the
     * just-opened targeting band would be swallowed here instead -- same
     * reasoning as {@link #drawTargetingButtons}/{@link #drawSubmenuList}'s
     * mutual exclusion in {@link #onDraw}.
     */
    private boolean listNavActive() {
        return snap.inBattle && snap.listOpen && !snap.listRows.isEmpty()
                && !isTargetingActive(System.nanoTime());
    }

    /**
     * Injects target-cycle/confirm input for targeting button {@code idx}
     * (0=left, 1=confirm, 2=right) via {@link TargetingInput}, cooldown-
     * guarded like {@link #injectCommand} (arrows: {@link
     * #ARROW_COOLDOWN_NANOS}; confirm: {@link #CONFIRM_COOLDOWN_NANOS}, same
     * duration as the command-button cooldown). A confirm tap also clears
     * {@link #targetingUntil} immediately -- the action has been chosen, so
     * targeting mode shouldn't linger until the timeout.
     */
    private void injectTarget(int idx) {
        long now = System.nanoTime();
        if (idx == 1) {
            if (lastConfirmInjectAt >= 0 && now - lastConfirmInjectAt < CONFIRM_COOLDOWN_NANOS) return;
            lastConfirmInjectAt = now;
            TargetingInput.confirm();
            targetingUntil = -1L;
        } else {
            if (lastArrowInjectAt >= 0 && now - lastArrowInjectAt < ARROW_COOLDOWN_NANOS) return;
            lastArrowInjectAt = now;
            if (idx == 0) TargetingInput.left(); else TargetingInput.right();
        }
        invalidate();
    }

    /**
     * Confirms submenu-list row {@code idx} -- either the panel-owned
     * {@link #listSel} (controller/keyboard confirm) or a directly-tapped
     * row (see {@link #onTouchEvent}, which also sets {@link #listSel} to
     * match first). Taps the row's live game-screen coordinates via
     * {@link BattleInput}, cooldown-guarded like {@link #injectCommand}'s
     * confirm (shares {@link #lastConfirmInjectAt}/{@link
     * #CONFIRM_COOLDOWN_NANOS}). A no-op when the row's position isn't known
     * yet ({@link PartySnapshot.ListRow#x}/{@code y} NaN) so a bad tap never
     * fires blind. Also a no-op when the row itself is marked unusable ({@link
     * PartySnapshot.ListRow#usable} false) -- refused with no tap and no
     * targeting arm, from either a controller confirm or a direct row tap
     * (see {@link #onTouchEvent}, which reaches this the same way). Selecting
     * a usable tech/item always leads to target selection next, so this arms
     * {@link #targetingUntil} exactly like a successful Attack tap through
     * {@link #injectCommand} would.
     */
    private void confirmListRow(int idx) {
        if (!snap.inBattle || !snap.listOpen) return;
        if (idx < 0 || idx >= snap.listRows.size()) return;
        PartySnapshot.ListRow row = snap.listRows.get(idx);
        if (!row.usable) return;
        long now = System.nanoTime();
        if (lastConfirmInjectAt >= 0 && now - lastConfirmInjectAt < CONFIRM_COOLDOWN_NANOS) return;
        lastConfirmInjectAt = now;
        // Preferred path: drive the game's input manager directly so a row
        // that isn't the currently focused one commits in one step (a tap
        // on a non-focused row only moves focus -- see GameState.
        // nativeCommitBattleListRow). Falls back to the tap when the native
        // side can't resolve the open menu; the tap is posted from the GL
        // thread back to the main thread, where BattleInput expects to run.
        final float tx = row.x, ty = row.y;
        org.cocos2dx.lib.Cocos2dxHelper.runOnGLThread(() -> {
            if (GameState.nativeCommitBattleListRow(idx)) return;
            if (Float.isNaN(tx) || Float.isNaN(ty)) return;
            view.post(() -> BattleInput.tap(tx, ty));
        });
        targetingUntil = now + TARGETING_DURATION_NANOS;
        targetingSawListClosed = false;
        targetingLabel = listRowName(row, snap.listKind);
        pendingMenuClose = true;
        pendingCloseIsCommand = false;
        pendingMenuCloseAt = now;
        invalidate();
    }

    /**
     * Backs out of the submenu list via {@link TargetingInput#back()} --
     * the game closes it on its own, and {@code snap.listOpen} simply flips
     * false on the next snapshot. Cooldown-guarded like {@link
     * #confirmListRow} (shares the same clock/duration -- the two never fire
     * in the same gesture). Only reachable from the touch-only {@link
     * #listBackHitBox} chip; the physical controller's B button is
     * deliberately left unconsumed so the game's own cancel handling applies
     * (see GameControllerInput).
     */
    private void backList() {
        long now = System.nanoTime();
        if (lastConfirmInjectAt >= 0 && now - lastConfirmInjectAt < CONFIRM_COOLDOWN_NANOS) return;
        lastConfirmInjectAt = now;
        TargetingInput.back();
        invalidate();
    }

    /**
     * Injects a tap for command button {@code idx} (0=Attack, 1=Tech,
     * 2=Item) at its live game-screen coordinates via {@link BattleInput},
     * guarded so an injection never fires outside battle/menuOpen, never
     * fires when the underlying command list is stale/shorter than idx, and
     * never fires more than once per {@link #INJECT_COOLDOWN_NANOS} (one
     * in-flight tap at a time). Also arms the brief pressed-button visual
     * feedback. Which follow-up mode gets armed depends on what was tapped:
     * Attack (idx 0) goes straight to target selection; Tech/Item (idx 1/2)
     * open the game's own submenu list instead (mirrored by the panel's
     * submenu-list band -- see {@link #drawSubmenuList}, driven directly by
     * {@code snap.listOpen} rather than anything armed here).
     */
    private void injectCommand(int idx) {
        if (!snap.inBattle || !snap.menuOpen) return;
        if (idx < 0 || idx >= snap.commandTargets.size()) return;
        long now = System.nanoTime();
        if (lastInjectAt >= 0 && now - lastInjectAt < INJECT_COOLDOWN_NANOS) return;
        lastInjectAt = now;
        pressedCommand = idx;
        pressedAt = now;
        PartySnapshot.CommandTarget t = snap.commandTargets.get(idx);
        BattleInput.tap(t.x, t.y);
        targetingUntil = idx == 0 ? now + TARGETING_DURATION_NANOS : -1L;
        targetingSawListClosed = !snap.listOpen;
        targetingLabel = idx == 0 ? "Attack" : null;
        pendingMenuClose = true;
        pendingCloseIsCommand = true;
        pendingMenuCloseAt = now;
        invalidate();
    }

    /**
     * Injects a tap on the live Auto Battle toggle at its game-screen
     * coordinates (snap.autoBattleX/Y), cooldown-guarded like {@link
     * #injectCommand} (shares {@link #lastInjectAt}/{@link
     * #INJECT_COOLDOWN_NANOS} -- one in-flight tap at a time across both).
     * Unlike injectCommand, this never opens/closes the command menu or
     * targeting; it only flips the toggle. The displayed ON/OFF state is
     * never guessed here -- it comes from the next {@code snap.autoBattleOn}
     * poll, exactly like every other live-state chip in this file.
     */
    private void injectAutoBattleToggle() {
        if (!snap.inBattle || !snap.autoBattleAvailable) return;
        long now = System.nanoTime();
        if (lastInjectAt >= 0 && now - lastInjectAt < INJECT_COOLDOWN_NANOS) return;
        lastInjectAt = now;
        pressedAuto = true;
        pressedAutoAt = now;
        BattleInput.tap(snap.autoBattleX, snap.autoBattleY);
        invalidate();
    }

    /**
     * True exactly when the panel-owned command row is the thing that should
     * react to left/right/confirm navigation -- in battle, with the command
     * menu open, and at least one command button currently drawn. Shared by
     * {@link #onControllerLeft}, {@link #onControllerRight} and {@link
     * #onControllerConfirm} so all three agree on when input is theirs to
     * consume versus the game's.
     */
    private boolean commandNavActive() {
        return snap.inBattle && snap.menuOpen && commandCount > 0;
    }

    /**
     * Moves the panel-owned command selection one slot left, clamped at 0
     * (no wrap). Called from the main thread by physical-controller routing
     * (see {@link org.cocos2dx.cpp.AppActivity#dispatchKeyEvent} and {@link
     * GameControllerInput}) before the event would otherwise reach the game.
     *
     * @return true iff this consumed the input (see {@link #commandNavActive}) --
     * callers must forward the event to the game unchanged when false.
     */
    boolean onControllerLeft() {
        if (!commandNavActive()) return false;
        if (commandSel > 0) {
            commandSel--;
            invalidate();
        }
        return true;
    }

    /** Same as {@link #onControllerLeft}, moving right and clamping at {@code commandCount - 1}. */
    boolean onControllerRight() {
        if (!commandNavActive()) return false;
        if (commandSel < commandCount - 1) {
            commandSel++;
            invalidate();
        }
        return true;
    }


    /**
     * Confirms the panel-owned command selection, injecting a tap for it via
     * {@link #injectCommand} exactly as a direct button tap would; when the
     * submenu-list band is active instead (see {@link #listNavActive}),
     * confirms {@link #listSel} via {@link #confirmListRow} instead.
     * Consumption is reported from {@link #commandNavActive}/{@link
     * #listNavActive} rather than either injector's own return value -- both
     * are cooldown-guarded and can silently no-op, and a false return here
     * would make the caller forward the same press on to the game as a real
     * button. While {@link #pendingMenuClose} is set (a confirm just fired
     * and the game hasn't yet reported its menu/list as closed), this
     * refuses to consume A at all -- see that field's javadoc for why.
     */
    boolean onControllerConfirm() {
        if (pendingMenuClose) return false;
        if (commandNavActive()) {
            injectCommand(commandSel);
            return true;
        }
        if (listNavActive()) {
            confirmListRow(listSel);
            return true;
        }
        return false;
    }

    /**
     * Moves the panel-owned submenu-list row selection ({@link #listSel}) up
     * one row, clamped at 0 (no wrap) -- same shape as {@link
     * #onControllerLeft}, gated on {@link #listNavActive} instead of {@link
     * #commandNavActive}. Only meaningful while {@code snap.listOpen}; d-pad
     * left/right are left untouched in that mode (the game uses L/R for
     * combo tabs), so there are no matching onControllerLeft/Right list
     * cases.
     */
    boolean onControllerUp() {
        if (!listNavActive()) return false;
        if (listSel > 0) {
            listSel--;
            invalidate();
        }
        return true;
    }

    /** Same as {@link #onControllerUp}, moving down and clamping at {@code snap.listRows.size() - 1}. */
    boolean onControllerDown() {
        if (!listNavActive()) return false;
        if (listSel < snap.listRows.size() - 1) {
            listSel++;
            invalidate();
        }
        return true;
    }

    /**
     * Snapshot-driven state transitions, called from {@link PartyPanelView#update}
     * with the incoming snapshot while {@link #snap} still holds the previous
     * one: targeting/pending-close bookkeeping, command/list selection
     * resets, and the results-window message machine.
     */
    void update(PartySnapshot s) {
        if (targetingUntil > 0 && !s.listOpen) targetingSawListClosed = true;
        if (targetingUntil > 0 && snap.targetingActive && !s.targetingActive) {
            // the game left target selection (confirmed or cancelled): drop
            // the grace immediately so the band doesn't outlive the phase.
            targetingUntil = -1L;
        }
        if (targetingUntil > 0
                && (!s.inBattle || s.menuOpen || (s.listOpen && targetingSawListClosed))) {
            // exit targeting early: the next command menu has opened (a new
            // command was issued through some other path), the submenu list
            // came back (B out of target selection -- see
            // targetingSawListClosed), or battle itself ended -- don't wait
            // out the timeout in any of these cases.
            targetingUntil = -1L;
        }
        if (pendingMenuClose) {
            // see pendingMenuClose's javadoc: clear once the menu/list the
            // triggering confirm was waiting on has actually closed in a
            // fresh snapshot, or after the safety timeout either way.
            boolean closed = pendingCloseIsCommand ? !s.menuOpen : !s.listOpen;
            boolean timedOut = pendingMenuCloseAt >= 0
                    && System.nanoTime() - pendingMenuCloseAt > PENDING_CLOSE_TIMEOUT_NANOS;
            if (closed || timedOut) pendingMenuClose = false;
        }
        if (!snap.menuOpen && s.menuOpen) {
            // command menu just opened: start the panel-owned selection back
            // at Attack, matching the game's own default highlight.
            commandSel = 0;
        }
        if ((!snap.listOpen && s.listOpen)
                || (snap.listOpen && s.listOpen && snap.listKind != s.listKind)) {
            // submenu list just opened, or stayed open but switched kind
            // (Tech <-> Item): start the panel-owned row selection back at 0.
            listSel = 0;
        }
        if (s.resultsActive) {
            if (!snap.resultsActive) {
                // results screen just started: baseline levels for
                // leveledMemberName, and start clean (no message from a
                // previous fight can carry over).
                resultsActiveSince = System.nanoTime();
                resultsBaseLevels = levelsOf(snap);
                resultsMessage = null;
                resultsFadingMessage = null;
                resultsMessageFadeStart = -1L;
            }
            String newMsg = computeResultsMessage(s);
            if (newMsg != null && !newMsg.equals(resultsMessage)) {
                if (resultsMessage != null) {
                    resultsFadingMessage = resultsMessage;
                    resultsMessageFadeStart = System.nanoTime();
                }
                resultsMessage = newMsg;
            }
        } else if (snap.resultsActive || snap.inBattle != s.inBattle) {
            // results screen just ended, or the battle itself ended --
            // clear all results-message state (item 3: resultsActive
            // already gates the window's visibility; this clears the
            // crossfade/level-baseline state behind it).
            resultsMessage = null;
            resultsFadingMessage = null;
            resultsMessageFadeStart = -1L;
            resultsBaseLevels = null;
        }
        snap = s;
    }

    /** Drops in-flight battle UI state on view detach so a re-attach starts clean. */
    void reset() {
        resultsMessage = null;
        resultsFadingMessage = null;
        resultsMessageFadeStart = -1L;
        resultsBaseLevels = null;
        targetingUntil = -1L;
        pendingMenuClose = false;
        pendingMenuCloseAt = -1L;
        listSel = 0;
    }

    /** True while something battle-side is still animating frame to frame (results-message crossfade, waiting out the victory pose, button press flashes). */
    boolean isAnimating() {
        if (resultsMessageFadeStart >= 0) return true;
        if (snap.resultsActive && !resultsVisible(snap)) return true; // waiting out the victory pose
        if (pressedCommand >= 0 && pressedAt >= 0
                && System.nanoTime() - pressedAt < PRESS_FEEDBACK_NANOS) {
            return true;
        }
        if (pressedAuto && pressedAutoAt >= 0
                && System.nanoTime() - pressedAutoAt < PRESS_FEEDBACK_NANOS) {
            return true;
        }
        return false;
    }

    private static int[] levelsOf(PartySnapshot s) {
        int[] a = new int[s.members.size()];
        for (int i = 0; i < a.length; i++) a[i] = s.members.get(i).level;
        return a;
    }

    /**
     * Which message {@link #drawResultsWindow} should show for {@code
     * s.resultsStep}, per the comment_out2 decode in gamestate.c: 1 EXP, 3 TP, 7 gold or
     * item drop (sub-counter tells them apart), 9/17/25 level-up, 11/19/27
     * tech learned, 13/21/29 dual tech, 15/23/31 triple tech. Any even step
     * (one-tick setup passes, or -1/32+ idle)
     * returns null so the caller keeps showing the last message. Numeric
     * placeholders come from {@link ChronoAssets#getBattleMessage}, which
     * falls back to a literal template when the battle.txt table isn't
     * loaded.
     */
    private String computeResultsMessage(PartySnapshot s) {
        // comment_out2 step machine (see gamestate.c nativeGetBattleResults
        // for the decode): only odd steps show a window; even steps are
        // one-tick setup passes and return null so the last message holds.
        int step = s.resultsStep;
        switch (step) {
            case 1: return ChronoAssets.getBattleMessage(ChronoAssets.BATTLE_MSG_EXP, Math.max(0, s.resultsExp));
            case 3: return ChronoAssets.getBattleMessage(ChronoAssets.BATTLE_MSG_TP, Math.max(0, s.resultsTp));
            case 7: {
                if (s.resultsSub >= 6 || s.resultsSub < 0) {
                    return ChronoAssets.getBattleMessage(ChronoAssets.BATTLE_MSG_GOLD, Math.max(0, s.resultsGold));
                }
                int slot = 5 - s.resultsSub; // setup step 6 decremented sub after picking slot
                int id = slot >= 0 && slot < s.resultsItems.length ? s.resultsItems[slot] : 0;
                if (id <= 0) return null;
                return ChronoAssets.getBattleMessageText(ChronoAssets.BATTLE_MSG_ITEM, ChronoAssets.getDropItemName(id));
            }
            case 9: case 17: case 25:
                return ChronoAssets.getBattleMessageText(ChronoAssets.BATTLE_MSG_LEVEL_UP, resultsMemberName(s));
            case 11: case 19: case 27:
                return ChronoAssets.getBattleMessageText(ChronoAssets.BATTLE_MSG_TECH,
                        resultsMemberName(s), techName(s.resultsTechId));
            case 13: case 21: case 29:
                return ChronoAssets.getBattleMessageText(ChronoAssets.BATTLE_MSG_DUAL_TECH,
                        techName(s.resultsDualTechId), resultsMemberName(s));
            case 15: case 23: case 31:
                return ChronoAssets.getBattleMessageText(ChronoAssets.BATTLE_MSG_TRIPLE_TECH,
                        techName(s.resultsTripleTechId), resultsMemberName(s));
            default: return null;
        }
    }

    /** Name of the party member the per-character results block is on (slot cursor == party order), else the level-diff heuristic, else a generic. */
    private String resultsMemberName(PartySnapshot s) {
        if (s.resultsSlot >= 0 && s.resultsSlot < s.members.size()) return s.members.get(s.resultsSlot).name;
        String m = leveledMemberName(s);
        return m != null ? m : "Party member";
    }

    private static String techName(int id) {
        String[] names = ChronoAssets.getTechNames();
        if (id >= 0 && names != null && id < names.length && !names[id].trim().isEmpty()) return names[id];
        return id >= 0 ? "#" + id : "a tech";
    }

    /** Which party member's level rose since {@link #resultsBaseLevels} was captured (results-screen start), or null if none/unknown (baseline missing, or a leveled member's slot changed). */
    private String leveledMemberName(PartySnapshot s) {
        if (resultsBaseLevels == null) return null;
        for (int i = 0; i < s.members.size() && i < resultsBaseLevels.length; i++) {
            if (s.members.get(i).level > resultsBaseLevels[i]) return s.members.get(i).name;
        }
        return null;
    }

    // ---- drawing (moved verbatim) -------------------------------------------

    /**
     * Battle-mode centerpiece: replaces the map/location content with one
     * HP bar per enemy, CT-style (green &gt; yellow &gt; red by remaining
     * fraction). Drawn inside the same torn-parchment clip as the map, so it
     * inherits the aged-paper overlay drawn after this returns.
     */
    /**
     * @param s    snapshot to draw enemies from (the live {@link #snap}, or a
     *             stale {@link #fadeSnap} while fading out).
     * @param live true when {@code s} is the live snapshot, in which case bar
     *             fractions come from the eased {@link #enemyBarFrac} map;
     *             false draws the raw (un-eased) fraction for the fading-out
     *             side, which is about to disappear anyway.
     */
    void drawBattleContent(Canvas c, RectF parchment, PartySnapshot s, boolean live) {
        int w = getWidth(), h = getHeight();
        setText(h * 0.045f, INK, true, Paint.Align.CENTER, false);
        applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        c.drawText("Battle", parchment.centerX(), parchment.top + h * 0.085f, text);

        int n = s.enemies.size();
        if (n == 0) return;
        float areaTop = parchment.top + h * 0.13f;
        // The AUTO chip (see drawAutoToggle) is taller than the top strip
        // the eye glyph lives in, so when it's showing push the enemy rows
        // down to clear it. areaTop is the first row's text BASELINE (see
        // drawEnemyBar: the name/HP labels are drawn at t and rise above
        // it by roughly rowH*0.62, up to ~5% of the view height), so the
        // clearance must cover that ascent plus a small gap, not just the
        // row's nominal top.
        // The AUTO chip shares the fast-forward badge's row (see
        // autoChipRect / ffBadgeRect) and the badge is drawn in every
        // battle unconditionally, so one clearance covers both.
        areaTop = Math.max(areaTop, ffBadgeRect(parchment).bottom + h * 0.065f);
        // Leave room for the command-button band (see drawCommandButtons)
        // when it's showing, so a long enemy list compresses instead of
        // drawing through the buttons.
        // Reserve the command-button band's space whenever the command menu
        // or the target-selection band is showing (only ever true for the
        // live snapshot -- see isTargetingActive), so a long enemy list
        // compresses instead of drawing through either. The submenu-list
        // band gets its own reservation below since it can grow taller than
        // the single-row command band (see listBandRect).
        long now = System.nanoTime();
        boolean listShowing = live && s.listOpen && !s.listRows.isEmpty();
        boolean resultsShowing = live && resultsVisible(s);
        boolean reserveBand = s.menuOpen || (live && isTargetingActive(now));
        float areaBottom;
        if (resultsShowing) {
            int visRows = resultsVisibleRowCount(s);
            areaBottom = listBandRect(parchment, visRows).top;
        } else if (listShowing) {
            int visRows = computeListVisibleRows(parchment, s.listRows.size());
            areaBottom = listBandRect(parchment, visRows).top;
        } else if (reserveBand) {
            areaBottom = parchment.bottom - h * 0.26f;
        } else {
            areaBottom = parchment.bottom - h * 0.09f;
        }
        float rowH = Math.min(h * 0.075f, (areaBottom - areaTop) / n);
        float barLeft = parchment.left + w * 0.09f;
        float barRight = parchment.right - w * 0.09f;
        if (live) enemyHitCount = 0;
        boolean targetingLive = live && isTargetingActive(now);
        for (int i = 0; i < n; i++) {
            float rowTop = areaTop + i * rowH;
            PartySnapshot.Enemy e = s.enemies.get(i);
            float rawFrac = e.maxHp > 0 ? clamp01(e.curHp / (float) e.maxHp) : 0f;
            Float eased = live ? enemyBarFrac.get(i) : null;
            float barH = rowH * 0.62f;
            // Row extent as drawn by drawEnemyBar: name/HP text sits on
            // baseline rowTop (ascent above it), the bar below it.
            RectF row = new RectF(barLeft - w * 0.02f, rowTop - barH * 0.7f,
                    barRight + w * 0.02f, rowTop + barH * 0.28f + barH * 0.6f + rowH * 0.06f);
            if (targetingLive && isTargetSlot(s, e.slot)) drawTargetHighlight(c, row);
            drawEnemyBar(c, e, i, barLeft, rowTop, barRight - barLeft, barH,
                    eased != null ? eased : rawFrac);
            if (live && i < ENEMY_ROWS_MAX) {
                enemyHitBoxes[i].set(row);
                enemyHitSlots[i] = e.slot;
                enemyHitCount = i + 1;
            }
        }
    }

    /**
     * When the live battle command menu is open ({@code s.menuOpen}), draws
     * up to 3 CT-style buttons (Attack/Tech/Item, horizontally spread) in a
     * band near the bottom of the parchment, below the enemy bars and above
     * the gold/time corner text. Called from {@link #onDraw} <em>after</em>
     * {@link #drawParchmentOverlay} (like {@link #drawEyeToggle}) so the
     * vignette/ink-frame chrome never darkens or strikes through the
     * buttons; {@code live} is always {@code true} at that call site since
     * only the current snapshot's buttons are ever interactive. Only updates
     * {@link #commandHitBoxes} / {@link #commandCount} when {@code live} --
     * kept as a parameter (mirroring the rest of this file's draw*(..., live)
     * methods) so a stale hit rect can never outlive its target.
     */
    private void drawCommandButtons(Canvas c, RectF parchment, PartySnapshot s, boolean live) {
        if (live) commandCount = 0;
        if (!s.menuOpen) return;
        int w = getWidth(), h = getHeight();
        int count = Math.min(commandHitBoxes.length, s.commandTargets.size());
        if (count == 0) return;

        // Panel-owned selection (commandSel), independent of the game's own
        // cursor -- see the field's javadoc. Clamped defensively in case
        // commandCount shrank since the last left/right/tap.
        int highlightIdx = Math.max(0, Math.min(commandSel, count - 1));

        float bandTop = parchment.bottom - h * 0.235f;
        float bandBottom = parchment.bottom - h * 0.115f;
        float gap = w * 0.02f;
        float totalW = parchment.width() - w * 0.09f * 2f;
        float btnW = (totalW - gap * (count - 1)) / count;
        float x = parchment.left + w * 0.09f;

        Bitmap winTex = ChronoAssets.getWindowTex();
        for (int i = 0; i < count; i++) {
            RectF box = new RectF(x, bandTop, x + btnW, bandBottom);
            boolean pressed = live && pressedCommand == i
                    && pressedAt >= 0 && System.nanoTime() - pressedAt < PRESS_FEEDBACK_NANOS;
            // The panel-owned selection (commandSel) gets a subtle highlight
            // treatment -- purely cosmetic, suppressed while the
            // pressed-feedback flash is showing so the two don't visually
            // compete.
            drawCommandButton(c, box, COMMAND_LABELS[i], winTex, pressed, i == highlightIdx);
            if (live) commandHitBoxes[i].set(box);
            x += btnW + gap;
        }
        if (live) commandCount = count;
    }

    /**
     * Target-selection band: three buttons (left-triangle / "Confirm" /
     * right-triangle, see {@link #TARGET_LABELS}) in the
     * same band position/chrome as {@link #drawCommandButtons} (reusing
     * {@link #drawCommandButton}), shown instead of the command buttons once
     * {@link #isTargetingActive} is true. Confirm is wider than the two
     * arrows, which are kept close to square. Only ever called for the live
     * snapshot (targeting has no meaning for a fading-out one), so always
     * updates {@link #targetHitBoxes} unconditionally, mirroring the
     * {@code live}-only hit-rect update in {@link #drawCommandButtons}.
     */
    private void drawTargetingButtons(Canvas c, RectF parchment) {
        int w = getWidth(), h = getHeight();
        float bandTop = parchment.bottom - h * 0.235f;
        float bandBottom = parchment.bottom - h * 0.115f;
        float bandH = bandBottom - bandTop;
        float gap = w * 0.02f;
        float totalW = parchment.width() - w * 0.09f * 2f;
        float arrowW = Math.min(bandH, totalW * 0.22f); // square-ish
        float confirmW = totalW - arrowW * 2f - gap * 2f;
        float x = parchment.left + w * 0.09f;
        Bitmap winTex = ChronoAssets.getWindowTex();

        RectF left = new RectF(x, bandTop, x + arrowW, bandBottom);
        drawCommandButton(c, left, TARGET_LABELS[0], winTex, false);
        targetHitBoxes[0].set(left);
        x += arrowW + gap;

        RectF confirm = new RectF(x, bandTop, x + confirmW, bandBottom);
        drawCommandButton(c, confirm, targetingLabel != null ? targetingLabel : TARGET_LABELS[1],
                winTex, false);
        targetHitBoxes[1].set(confirm);
        x += confirmW + gap;

        RectF right = new RectF(x, bandTop, x + arrowW, bandBottom);
        drawCommandButton(c, right, TARGET_LABELS[2], winTex, false);
        targetHitBoxes[2].set(right);

        targetCount = 3;
    }

    /**
     * How many submenu-list rows fit on screen at once: capped at {@link
     * #LIST_MAX_ROWS}, {@code totalRows} itself, and however many actually
     * fit between the standard band's bottom edge and the space reserved
     * near the parchment top for the "Battle" title -- see {@link
     * #listBandRect}, which uses the same two edges.
     */
    private int computeListVisibleRows(RectF parchment, int totalRows) {
        int h = getHeight();
        float bandBottom = parchment.bottom - h * 0.115f;
        float areaTopLimit = parchment.top + h * 0.13f;
        float rowH = h * LIST_ROW_H_FRAC;
        int maxFit = Math.max(1, (int) ((bandBottom - areaTopLimit) / rowH));
        return Math.max(1, Math.min(totalRows, Math.min(LIST_MAX_ROWS, maxFit)));
    }

    /**
     * Submenu-list band rect: same bottom edge as the command/targeting band
     * ({@link #drawCommandButtons}/{@link #drawTargetingButtons}), but grows
     * upward past that band's single-row height when {@code visibleRows}
     * calls for more room (i.e. more than one row is visible) -- see {@link
     * #computeListVisibleRows}. Shared by {@link #drawSubmenuList} (draw)
     * and {@link #drawBattleContent} (space reservation for the enemy bars
     * above it) so the two always agree on where the band sits.
     */
    private RectF listBandRect(RectF parchment, int visibleRows) {
        int h = getHeight();
        float bandBottom = parchment.bottom - h * 0.115f;
        float areaTopLimit = parchment.top + h * 0.13f;
        float rowH = h * LIST_ROW_H_FRAC;
        float bandTop = Math.max(areaTopLimit, bandBottom - Math.max(1, visibleRows) * rowH);
        float inset = parchment.width() * 0.09f;
        return new RectF(parchment.left + inset, bandTop, parchment.right - inset, bandBottom);
    }

    /**
     * Submenu-list band: mirrors the live Tech/Item list the game itself has
     * open (see {@code snap.listRows}), one row per entry -- name (from
     * {@link ChronoAssets#getTechNames()}/{@link ChronoAssets#getItemNames()},
     * falling back to "#id"), right-aligned extra readout (tech: "MP n" only
     * when the param is &gt; 0; item: "x n"), unusable rows dimmed, and the
     * panel-owned {@link #listSel} row highlighted with the same gold accent
     * as the command highlight (see {@link #DEFAULT_HIGHLIGHT_COLOR}). Drawn
     * inside one 9-sliced window panel spanning {@link #listBandRect}, which
     * grows upward and windows/scrolls the row list to keep {@link #listSel}
     * visible when there are more rows than {@link #computeListVisibleRows}
     * allows on screen at once. Only ever called for the live snapshot (list
     * state, like targeting, has no meaning to redraw for a fading-out one),
     * so always updates {@link #listRowHitBoxes}/{@link #listVisibleCount}/
     * {@link #listWindowStart} unconditionally, mirroring {@link
     * #drawTargetingButtons}. Tapping a row (see {@link #onTouchEvent}) sets
     * {@link #listSel} and confirms it via {@link #confirmListRow}.
     */
    private void drawSubmenuList(Canvas c, RectF parchment) {
        List<PartySnapshot.ListRow> rows = snap.listRows;
        int total = rows.size();
        if (total == 0) {
            listVisibleCount = 0;
            return;
        }
        listSel = Math.max(0, Math.min(listSel, total - 1));

        int visCount = computeListVisibleRows(parchment, total);
        int windowStart = total > visCount
                ? Math.max(0, Math.min(listSel - visCount / 2, total - visCount))
                : 0;
        RectF band = listBandRect(parchment, visCount);

        Bitmap winTex = ChronoAssets.getWindowTex();
        if (winTex != null) {
            float destInset = Math.min(band.width(), band.height()) * 0.08f;
            drawNinePatch(c, winTex, ChronoAssets.getWindowTexInset(), band, destInset);
        } else {
            fill.setShader(null);
            fill.setColor(BOX_BORDER_OUT);
            c.drawRect(band, fill);
            RectF inner = new RectF(band);
            inner.inset(3, 3);
            fill.setColor(BOX_BORDER_IN);
            c.drawRect(inner, fill);
            inner.inset(2, 2);
            fill.setColor(BOX_BG);
            c.drawRect(inner, fill);
        }

        float rowH = band.height() / visCount;
        String[] names = snap.listKind == 0 ? ChronoAssets.getTechNames() : ChronoAssets.getItemNames();
        for (int i = 0; i < visCount; i++) {
            int idx = windowStart + i;
            PartySnapshot.ListRow row = rows.get(idx);
            RectF rowBox = new RectF(band.left + 6, band.top + i * rowH,
                    band.right - 6, band.top + (i + 1) * rowH);
            drawListRow(c, rowBox, row, names, idx == listSel);
            listRowHitBoxes[i].set(rowBox);
        }
        listVisibleCount = visCount;
        listWindowStart = windowStart;
    }

    /** One submenu-list row: name, right-aligned extra readout, optional selected-row highlight -- see {@link #drawSubmenuList}. */
    /**
     * Display name for a submenu-list row: item rows go through {@link
     * ChronoAssets#getItemName} (encoded category/index ids), tech rows are
     * a plain line-index lookup into the tech name table, "#id" when unknown.
     */
    private static String listRowName(PartySnapshot.ListRow row, int kind) {
        if (kind == 1) return ChronoAssets.getItemName(row.id);
        String[] names = ChronoAssets.getTechNames();
        return (names != null && row.id >= 0 && row.id < names.length && !names[row.id].trim().isEmpty())
                ? names[row.id] : ("#" + row.id);
    }

    private void drawListRow(Canvas c, RectF box, PartySnapshot.ListRow row, String[] names, boolean selected) {
        if (selected) {
            fill.setShader(null);
            fill.setColor(Color.argb(50, Color.red(DEFAULT_HIGHLIGHT_COLOR),
                    Color.green(DEFAULT_HIGHLIGHT_COLOR), Color.blue(DEFAULT_HIGHLIGHT_COLOR)));
            c.drawRect(box, fill);
            stroke.setStrokeWidth(2f);
            stroke.setColor(Color.argb(200, Color.red(DEFAULT_HIGHLIGHT_COLOR),
                    Color.green(DEFAULT_HIGHLIGHT_COLOR), Color.blue(DEFAULT_HIGHLIGHT_COLOR)));
            RectF hb = new RectF(box);
            hb.inset(1f, 1f);
            c.drawRect(hb, stroke);
        }
        // Item rows use the encoded (category << 14) | index id -- see
        // ChronoAssets.getItemName -- since item.txt's flat line-index table
        // doesn't cover it (Potion arrived as 16385 = 0x4001). Tech rows keep
        // the plain line-index lookup into the tech name table.
        String name = listRowName(row, snap.listKind);
        int color = row.usable ? Color.WHITE : Color.argb(140, 170, 170, 170);
        setText(box.height() * 0.42f, color, false, Paint.Align.LEFT, true);
        c.drawText(name, box.left + box.width() * 0.03f, box.centerY() + box.height() * 0.16f, text);

        // row.extra was found NOT to be the MP cost live (it only carried a
        // value for a dual tech) -- MP now comes from ChronoAssets.getTechMp,
        // a separately-loaded table, and is only drawn once that's known.
        String extra = null;
        if (snap.listKind == 0) { // tech: MP cost, only when known
            int mp = ChronoAssets.getTechMp(row.id);
            if (mp >= 0) extra = "MP " + mp;
        } else { // item: held count
            extra = "x " + row.extra;
        }
        if (extra != null) {
            setText(box.height() * 0.38f, color, false, Paint.Align.RIGHT, true);
            c.drawText(extra, box.right - box.width() * 0.03f, box.centerY() + box.height() * 0.14f, text);
        }
    }

    // Fixed band height for the results window, in submenu-list "rows" (see
    // listBandRect) -- no longer driven by item count now that only one
    // message shows at a time; three rows' worth of height gives the big
    // message room to wrap to two lines without the band feeling cramped.
    private static final int RESULTS_BAND_ROWS = 3;

    private int resultsVisibleRowCount(PartySnapshot s) {
        return RESULTS_BAND_ROWS;
    }

    /**
     * Battle-results window: replaces the command/targeting/list bands once
     * {@code snap.resultsActive} holds (every enemy dead and the native
     * comment_out2 step machine -- {@code snap.resultsStep} -- still mid-
     * sequence). Shows ONE big message at a time -- "Earned N EXP.",
     * "Earned N TP.", "Found N G.", "Obtained <item>.", or "<Name> leveled
     * up!" -- chosen from the live step by {@link #computeResultsMessage}
     * and cached in {@link #resultsMessage} (a step with no message of its
     * own leaves the last one showing). When the step machine advances to a
     * new message, {@link #resultsMessageFadeStart} drives a crossfade
     * (reusing {@link #TITLE_FADE_NANOS}, same timing as the field-mode
     * location-title fade) between {@link #resultsFadingMessage} (out) and
     * {@link #resultsMessage} (in), drawing the eye down toward the panel.
     * A small dim "A: continue" hint sits below the window. Drawn in the
     * same 9-sliced window style and band position as the old list (see
     * {@link #listBandRect}). Never consumes controller A -- see {@link
     * #onControllerConfirm}, which only reacts to {@link #commandNavActive}/
     * {@link #listNavActive}, both false here (menuOpen/listOpen are false
     * during results), so the confirm press reaches the game unchanged.
     */
    private void drawResultsWindow(Canvas c, RectF parchment) {
        RectF band = listBandRect(parchment, resultsVisibleRowCount(snap));

        Bitmap winTex = ChronoAssets.getWindowTex();
        if (winTex != null) {
            float destInset = Math.min(band.width(), band.height()) * 0.08f;
            drawNinePatch(c, winTex, ChronoAssets.getWindowTexInset(), band, destInset);
        } else {
            fill.setShader(null);
            fill.setColor(BOX_BORDER_OUT);
            c.drawRect(band, fill);
            RectF inner = new RectF(band);
            inner.inset(3, 3);
            fill.setColor(BOX_BORDER_IN);
            c.drawRect(inner, fill);
            inner.inset(2, 2);
            fill.setColor(BOX_BG);
            c.drawRect(inner, fill);
        }

        float msgY = band.centerY() - band.height() * 0.08f;
        if (resultsMessageFadeStart >= 0) {
            long elapsed = System.nanoTime() - resultsMessageFadeStart;
            float t = Math.min(1f, elapsed / (float) TITLE_FADE_NANOS);
            if (t < 1f) {
                drawResultsMessage(c, band, resultsFadingMessage, msgY, 1f - t);
                drawResultsMessage(c, band, resultsMessage, msgY, t);
            } else {
                // fade finished this frame -- settle and fall through to a plain draw
                resultsMessageFadeStart = -1L;
                resultsFadingMessage = null;
                drawResultsMessage(c, band, resultsMessage, msgY, 1f);
            }
        } else {
            drawResultsMessage(c, band, resultsMessage, msgY, 1f);
        }

        setText(band.height() * 0.09f, Color.argb(200, 230, 230, 210), false, Paint.Align.CENTER, true);
        c.drawText("A: continue", band.centerX(), band.bottom + band.height() * 0.12f, text);
    }

    /**
     * Draws one results message, word-wrapped and centred within {@code
     * band} around {@code baselineY} (mirrors {@link #drawTitleWrapped}'s
     * centred-block layout), at about the location title's size ({@code h *
     * 0.075} at full band height, scaled down slightly to leave headroom
     * for two-line wraps) and {@code alpha} opacity -- used by {@link
     * #drawResultsWindow} to crossfade the outgoing/incoming message. A
     * null/empty message or zero alpha draws nothing (covers the brief
     * window before the first message is computed).
     */
    private void drawResultsMessage(Canvas c, RectF band, String msg, float baselineY, float alpha) {
        if (msg == null || msg.isEmpty() || alpha <= 0f) return;
        int h = getHeight();
        setText(Math.min(band.height() * 0.34f, h * 0.06f), Color.WHITE, true, Paint.Align.CENTER, true);
        applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        // Fade via a canvas layer so the shadow layer fades with the glyphs
        // (Paint alpha alone left a black shadow ghost during crossfades).
        float a = clamp01(alpha);
        int saved = -1;
        if (a < 1f) {
            saved = c.saveLayerAlpha(band.left, band.top - h * 0.1f, band.right, band.bottom + h * 0.1f,
                    (int) (255 * a));
        }

        float maxW = band.width() * 0.88f;
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : msg.split(" ")) {
            if (word.isEmpty()) continue;
            String candidate = cur.length() == 0 ? word : cur + " " + word;
            if (text.measureText(candidate) <= maxW || cur.length() == 0) {
                cur.setLength(0);
                cur.append(candidate);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        float lineH = text.getTextSize() * 1.15f;
        float y = baselineY - lineH * (lines.size() - 1) * 0.5f;
        for (String line : lines) {
            c.drawText(line, band.centerX(), y, text);
            y += lineH;
        }
        if (saved >= 0) c.restoreToCount(saved);
    }

    /**
     * Small "back" chip (rounded square + left-triangle glyph), drawn in the
     * parchment's top strip right of the AUTO chip and eye glyph slots
     * ({@link #eyeHitRect}) only while the submenu-list band is showing.
     * Tapping within its ~48px hit box ({@link #listBackHitBox}, see {@link
     * #onTouchEvent}) calls {@link #backList}. Updates {@link
     * #listBackHitBox} every call so the hit-test always matches the glyph's
     * current on-screen position.
     */
    private void drawListBackToggle(Canvas c, RectF parchment) {
        // Right of the eye glyph's slot (see eyeHitRect, itself right of
        // the AUTO chip; both slots are reserved whether or not shown, so
        // this never shifts), centred on the chip row -- the corner itself
        // belongs to the gear/AUTO crossfade.
        RectF eye = eyeHitRect(parchment);
        float cx = eye.right + dp(6f) + BACK_HIT_HALF;
        float cy = eye.centerY();
        listBackHitBox.set(cx - BACK_HIT_HALF, cy - BACK_HIT_HALF, cx + BACK_HIT_HALF, cy + BACK_HIT_HALF);

        int inkA = Color.argb(210, Color.red(INK), Color.green(INK), Color.blue(INK));
        stroke.setStrokeWidth(2f);
        stroke.setColor(inkA);
        RectF glyphBox = new RectF(cx - BACK_GLYPH_RADIUS, cy - BACK_GLYPH_RADIUS,
                cx + BACK_GLYPH_RADIUS, cy + BACK_GLYPH_RADIUS);
        c.drawRoundRect(glyphBox, 4f, 4f, stroke);
        setText(BACK_GLYPH_RADIUS * 1.3f, inkA, true, Paint.Align.CENTER, false);
        // reuses TARGET_LABELS[0]'s glyph (a unicode escape, not a raw byte
        // -- see its own field comment) rather than introducing a second
        // literal non-ASCII character into this file.
        c.drawText(TARGET_LABELS[0], cx, cy + BACK_GLYPH_RADIUS * 0.4f, text);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * Advances {@link #enemyBarFrac} one animation step toward the live
     * snapshot's fractions. Called once per drawn frame (not per fade
     * layer) so easing speed doesn't depend on whether a mode crossfade is
     * also in progress. Returns true while any bar is still off-target, so
     * the caller knows whether another frame is needed.
     */
    boolean advanceEnemyBarFractions() {
        boolean animating = false;
        Iterator<Map.Entry<Integer, Float>> it = enemyBarFrac.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey() >= snap.enemies.size()) it.remove();
        }
        for (int i = 0; i < snap.enemies.size(); i++) {
            PartySnapshot.Enemy e = snap.enemies.get(i);
            float target = e.maxHp > 0 ? clamp01(e.curHp / (float) e.maxHp) : 0f;
            Float cur = enemyBarFrac.get(i);
            if (cur == null) {
                enemyBarFrac.put(i, target);
                continue;
            }
            float delta = target - cur;
            if (Math.abs(delta) < 0.005f) {
                if (cur != target) enemyBarFrac.put(i, target);
                continue;
            }
            enemyBarFrac.put(i, cur + delta * 0.25f);
            animating = true;
        }
        return animating;
    }

    /**
     * Resolves the display label for an enemy row: the real monster name from
     * ChronoAssets' Localize/en/msg/monster.txt table (line index == monster
     * id) when it's loaded and covers this id, else the "Enemy N" fallback
     * used before extraction finishes or for an out-of-range/blank entry.
     */
    private static String enemyLabel(PartySnapshot.Enemy e, int index) {
        String[] names = ChronoAssets.getMonsterNames();
        if (names != null && e.id >= 0 && e.id < names.length) {
            String name = names[e.id].trim();
            if (!name.isEmpty()) return name;
        }
        return "Enemy " + (index + 1);
    }

    // Neutral/muted bar color for a hidden-info enemy in HONOR mode -- drawn
    // at full width regardless of actual HP, but visibly distinct from the
    // green "healthy" bar color so it reads as "unknown," not "full health."
    private static final int HIDDEN_HP_BAR_COLOR = Color.rgb(140, 130, 110);

    /**
     * True when this enemy's info should be hidden per the game's own
     * MonsterNameData.dat flag table (byte 255 == hidden; bosses/event
     * enemies) and the current display setting honors that (HONOR mode).
     * FULL mode, or a missing/out-of-range flag table, always returns false.
     */
    private boolean isHiddenInfo(PartySnapshot.Enemy e) {
        return honorHiddenHp && isFlaggedHidden(e);
    }

    /** The game's own hidden-info flag for this enemy (see {@link #isHiddenInfo}), regardless of the current display setting. */
    private static boolean isFlaggedHidden(PartySnapshot.Enemy e) {
        byte[] flags = ChronoAssets.getMonsterFlags();
        if (flags == null || e.id < 0 || e.id >= flags.length) return false;
        return (flags[e.id] & 0xff) == 255;
    }

    /**
     * True when any enemy in {@code s} is flagged hidden-info by the game
     * (a boss/event enemy). Gates the eye toggle: outside such fights the
     * setting has nothing to act on, so the glyph stays hidden rather than
     * offering a switch that visibly does nothing. Deliberately ignores
     * {@link #honorHiddenHp} -- the toggle must stay reachable in FULL
     * mode too, or there'd be no way back to HONOR.
     */
    private static boolean anyFlaggedHidden(PartySnapshot s) {
        for (PartySnapshot.Enemy e : s.enemies) if (isFlaggedHidden(e)) return true;
        return false;
    }

    /**
     * One CT-style enemy HP bar: label above, colored fill bar with numeric
     * readout. Numbers are instant; only the fill bar (frac) eases. When the
     * enemy's info is hidden (see {@link #isHiddenInfo}) and HONOR mode is
     * active, the numeric readout is replaced with "???" and the bar is
     * drawn full-width in a muted neutral color instead of the real
     * fraction -- the name is still shown, matching the game's own bosses.
     */
    private void drawEnemyBar(Canvas c, PartySnapshot.Enemy e, int index, float l, float t, float w, float h, float frac) {
        boolean hidden = isHiddenInfo(e);
        setText(h * 0.62f, INK, true, Paint.Align.LEFT, false);
        applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        c.drawText(enemyLabel(e, index), l, t, text);
        setText(h * 0.62f, INK, false, Paint.Align.RIGHT, false);
        applyTypeface(Typeface.MONOSPACE);
        c.drawText(hidden ? "???" : (e.curHp + "/" + e.maxHp), l + w, t, text);

        float barTop = t + h * 0.28f;
        float barH = h * 0.6f;
        RectF track = new RectF(l, barTop, l + w, barTop + barH);
        fill.setShader(null);
        fill.setColor(Color.argb(160, 40, 30, 15));
        c.drawRoundRect(track, barH * 0.4f, barH * 0.4f, fill);

        if (hidden) {
            fill.setColor(HIDDEN_HP_BAR_COLOR);
            c.drawRoundRect(track, barH * 0.4f, barH * 0.4f, fill);
        } else {
            frac = clamp01(frac);
            if (frac > 0f) {
                RectF fillRect = new RectF(track);
                fillRect.right = track.left + track.width() * frac;
                int barColor = frac > 0.5f ? Color.rgb(70, 190, 90)
                        : frac > 0.2f ? Color.rgb(230, 200, 60)
                        : Color.rgb(210, 60, 60);
                fill.setColor(barColor);
                c.drawRoundRect(fillRect, barH * 0.4f, barH * 0.4f, fill);
            }
        }
        stroke.setStrokeWidth(1.5f);
        stroke.setColor(Color.argb(150, 96, 72, 40));
        c.drawRoundRect(track, barH * 0.4f, barH * 0.4f, stroke);
    }

    /** True when actor slot {@code slot} is under the game's target cursor in snapshot {@code s}. */
    static boolean isTargetSlot(PartySnapshot s, int slot) {
        if (!s.targetingActive) return false;
        for (int t : s.targetSlots) if (t == slot) return true;
        return false;
    }

    /**
     * Gold selection wash + outline behind a row/box under the target
     * cursor -- the same treatment {@link #drawListRow} gives the selected
     * submenu row, so "selected" reads the same everywhere on the panel.
     */
    void drawTargetHighlight(Canvas c, RectF box) {
        fill.setShader(null);
        fill.setColor(Color.argb(60, Color.red(DEFAULT_HIGHLIGHT_COLOR),
                Color.green(DEFAULT_HIGHLIGHT_COLOR), Color.blue(DEFAULT_HIGHLIGHT_COLOR)));
        c.drawRoundRect(box, 6f, 6f, fill);
        stroke.setStrokeWidth(2.5f);
        stroke.setColor(Color.argb(220, Color.red(DEFAULT_HIGHLIGHT_COLOR),
                Color.green(DEFAULT_HIGHLIGHT_COLOR), Color.blue(DEFAULT_HIGHLIGHT_COLOR)));
        c.drawRoundRect(box, 6f, 6f, stroke);
    }

    /**
     * Tap-to-target for actor slot {@code slot} (enemy row or party status
     * box): moves the game's cursor there when it isn't the target yet,
     * confirms when it already is. Refused outside targeting or in all-
     * target mode (nothing to pick). The cursor write runs on the GL
     * thread; the highlight follows on the next snapshot.
     */
    boolean selectTargetSlot(int slot) {
        if (!isTargetingActive(System.nanoTime()) || snap.targetAll) return false;
        if (isTargetSlot(snap, slot)) {
            injectTarget(1);
            return true;
        }
        org.cocos2dx.lib.Cocos2dxHelper.runOnGLThread(() -> GameState.nativeSetBattleTargetSlot(slot));
        return true;
    }

    /**
     * Small "eye" toggle glyph (circle + dot, INK color, ~24px) in the
     * parchment's top strip, immediately RIGHT of the AUTO chip's slot
     * (see {@link #eyeHitRect}; the slot is reserved whether or not AUTO
     * is showing, so the glyph never shifts), drawn only in battle and
     * only while some enemy is flagged hidden-info (see {@link
     * #anyFlaggedHidden} -- a boss or event enemy; in an ordinary fight
     * the toggle would do nothing visible), faded via {@link #eyeFade}. Tapping
     * within its ~48px hit box (see {@link #onTouchEvent}) toggles the
     * hidden-HP display setting. Updates {@link #eyeHitBox} every call so
     * the hit-test always matches the glyph's current on-screen position.
     */
    private void drawEyeToggle(Canvas c, RectF parchment) {
        RectF hit = eyeHitRect(parchment);
        float cx = hit.centerX(), cy = hit.centerY();
        eyeHitBox.set(hit);

        // An actual eye, not a ring: almond outline (two quadratic arcs
        // meeting at the corners), iris ring, pupil dot. Wider than tall
        // (~1.7:1) so it reads as an eye at 24px. When hidden HP is being
        // overridden (real numbers forced on) a diagonal slash crosses it,
        // the usual "eye-off" idiom, so the toggle shows its own state.
        float rx = EYE_GLYPH_RADIUS * 1.35f, ry = EYE_GLYPH_RADIUS * 0.8f;
        int inkA = Color.argb(210, Color.red(INK), Color.green(INK), Color.blue(INK));
        stroke.setStrokeWidth(2f);
        stroke.setColor(inkA);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        Path eye = new Path();
        eye.moveTo(cx - rx, cy);
        eye.quadTo(cx, cy - ry * 2.1f, cx + rx, cy);
        eye.quadTo(cx, cy + ry * 2.1f, cx - rx, cy);
        eye.close();
        c.drawPath(eye, stroke);
        c.drawCircle(cx, cy, ry * 0.62f, stroke);
        fill.setShader(null);
        fill.setColor(inkA);
        c.drawCircle(cx, cy, ry * 0.28f, fill);
        if (!honorHiddenHp) {
            stroke.setStrokeWidth(2.5f);
            c.drawLine(cx - rx * 0.9f, cy + ry * 1.25f, cx + rx * 0.9f, cy - ry * 1.25f, stroke);
        }
        stroke.setStrokeJoin(Paint.Join.MITER); // shared paint: back to its default
    }

    /**
     * "AUTO" chip toggling the game's own Auto Battle mode (repeated Attack
     * + 2x battle speed) by tapping the real on-screen toggle's game-screen
     * position -- see {@link #injectAutoBattleToggle}. Drawn only in battle
     * and only while {@code snap.autoBattleAvailable}, faded via {@link
     * #autoFade} (see the {@link #onDraw} chip block, which also owns
     * clearing {@link #autoHitBox}). Reuses {@link #drawCommandButton}'s
     * exact chrome so it matches the Attack/Tech/Item row, with {@code
     * highlighted} doing double duty as the ON/OFF indicator: filled/tinted
     * with a brighter border when Auto Battle is on, plain when off --
     * state comes straight from {@code snap.autoBattleOn} (the next poll),
     * never guessed locally except for the brief {@link #pressedAuto} press
     * flash. Sits in the gear chip's top-left slot (see {@link
     * #autoChipRect}) -- the gear is hidden in battle, so the two crossfade
     * in place; the fast-forward badge keeps the top-right. Not below the
     * strip, which would run into the enemy HP rows that start at {@link
     * #drawBattleContent}'s areaTop. Sized to a
     * {@link SettingsScreen#MOD_ROW_BUTTON_H_DP}-tall (>=48dp) touch target. Updates
     * {@link #autoHitBox} every call so the hit-test always matches the
     * chip's current on-screen position.
     */
    private void drawAutoToggle(Canvas c, RectF parchment) {
        RectF box = autoChipRect(parchment);
        autoHitBox.set(box);

        boolean pressed = pressedAuto && pressedAutoAt >= 0
                && System.nanoTime() - pressedAutoAt < PRESS_FEEDBACK_NANOS;
        Bitmap winTex = ChronoAssets.getWindowTex();
        drawCommandButton(c, box, "AUTO", winTex, pressed, snap.autoBattleOn);
    }

    /**
     * The AUTO chip's rect for this parchment: {@link SettingsScreen#MOD_ROW_BUTTON_H_DP}
     * tall, 88dp wide, in the parchment's top-LEFT corner at the same 24px
     * inset and on the same row as the fast-forward badge ({@link
     * #ffBadgeRect}) -- i.e. exactly where the gear chip sits outside
     * battle, so the two crossfade in place across the battle boundary
     * (gear out, AUTO in) rather than a chip appearing somewhere new.
     * Shared by {@link #drawAutoToggle}, {@link #eyeHitRect} (the eye
     * glyph sits right of it, and the submenu back chip right of that)
     * and {@link #drawBattleContent}, which pushes the enemy HP rows
     * below it.
     */
    private RectF autoChipRect(RectF parchment) {
        RectF ff = ffBadgeRect(parchment);
        float chipW = dp(88f);
        float left = parchment.left + 24f;
        return new RectF(left, ff.top, left + chipW, ff.bottom);
    }

    /**
     * The eye glyph's ~48px hit square: immediately RIGHT of the AUTO
     * chip's slot ({@link #autoChipRect}) with a small gap, centred on its
     * row -- the two are battle-only and fade in together, so they sit
     * together on the left; the fast-forward badge has the right to
     * itself. Shared by {@link #drawEyeToggle}, {@link
     * #drawListBackToggle} (which sits right of it) and the {@link #onDraw}
     * chip block (as the fade layer's bounds).
     */
    private RectF eyeHitRect(RectF parchment) {
        RectF auto = autoChipRect(parchment);
        float cx = auto.right + dp(6f) + EYE_HIT_HALF;
        float cy = auto.centerY();
        return new RectF(cx - EYE_HIT_HALF, cy - EYE_HIT_HALF, cx + EYE_HIT_HALF, cy + EYE_HIT_HALF);
    }

    /**
     * On-panel fast-forward badge, drawn both in and out of battle (see the
     * {@link #onDraw} chip block) -- reuses {@link #drawCommandButton}'s
     * chrome like {@link #drawAutoToggle} does. Shows ">> Nx" (highlighted)
     * while {@link GameSpeed#isActive} is true; otherwise a dimmed/hollow
     * ">>" so it stays discoverable and tappable to turn fast-forward on.
     * Tapping it always toggles, regardless of {@link GameSpeed.Mode}.
     * Owns the parchment's top-RIGHT corner in every mode (see {@link
     * #ffBadgeRect}) and never moves or fades: it's the one chip that is
     * always present, so it anchors the strip -- the battle-only AUTO chip
     * and eye glyph line up to its left, and the gear chip has the
     * top-left to itself outside battle. Sized to a {@link
     * #MOD_ROW_BUTTON_H_DP}-tall (>=48dp) touch target, like the AUTO
     * chip. Sets {@link #ffHitBox}.
     */
    private void drawFastForwardBadge(Canvas c, RectF parchment) {
        RectF box = ffBadgeRect(parchment);
        ffHitBox.set(box);

        boolean active = GameSpeed.isActive();
        Bitmap winTex = ChronoAssets.getWindowTex();
        String label = active ? (">> " + (int) GameSpeed.getSpeed(getContext()) + "x") : ">>";
        drawCommandButton(c, box, label, winTex, false, active);
        if (!active) {
            // Dim/hollow when inactive -- still tappable (toggles on), just
            // visually secondary to the active state.
            fill.setShader(null);
            fill.setColor(Color.argb(140, 0, 0, 0));
            c.drawRect(box, fill);
        }
    }

    /**
     * The fast-forward badge's rect for this parchment, mode-independent:
     * {@link SettingsScreen#MOD_ROW_BUTTON_H_DP} tall, 84dp wide, flush with the
     * parchment's top-right corner at the same 24px inset the gear chip
     * uses top-left, and vertically centred on the gear chip's slot so the
     * two read as one row outside battle. Shared by {@link
     * #drawFastForwardBadge}, {@link #autoChipRect} (which hangs off its
     * left edge) and {@link #drawBattleContent} (which pushes the enemy HP
     * rows below it).
     */
    private RectF ffBadgeRect(RectF parchment) {
        float chipH = dp(SettingsScreen.MOD_ROW_BUTTON_H_DP);
        float chipW = dp(84f);
        float right = parchment.right - 24f;
        float top = parchment.top + 24f + (SettingsScreen.GEAR_CHIP_SIZE - chipH) / 2f;
        return new RectF(right - chipW, top, right, top + chipH);
    }

    /**
     * Draws one fading top-strip chip: {@code fade}'s current opacity is
     * applied via a layer over {@code bounds} (a little padded so the
     * window chrome's edge isn't clipped), {@code draw} paints the chip at
     * full opacity inside it. Skipped entirely at 0. Returns true while
     * the fade is still running so {@link #onDraw} keeps scheduling frames.
     */
    private boolean drawFadedChip(Canvas c, ChipFade fade, RectF bounds, Runnable draw) {
        float a = fade.value();
        if (a <= 0f) return fade.animating();
        if (a >= 1f) {
            draw.run();
        } else {
            RectF r = new RectF(bounds);
            r.inset(-4f, -4f);
            int layer = c.saveLayerAlpha(r, (int) (255 * a));
            draw.run();
            c.restoreToCount(layer);
        }
        return fade.animating();
    }

    /**
     * Draws everything interactive, on top of the aged-paper overlay: the
     * top-strip chips (fast-forward badge always; gear outside battle, AUTO
     * and eye in battle, crossfading via {@link ChipFade}) and, in battle,
     * whichever band applies (results / targeting / submenu list + back chip
     * / command buttons). Owns every hit box it draws. Returns true while a
     * chip fade is still running so {@link PartyPanelView#onDraw} keeps
     * scheduling frames.
     */
    boolean drawOverlay(Canvas c, RectF parchment) {
        boolean animating = false;
        // battle-only hidden-HP mode toggle glyph; drawn on the current
        // (possibly still-fading-in) mode only, never during the crossfade
        // itself, and its hit box is cleared outside battle mode so a stray
        // touch never toggles anything.
        // battle-only command buttons, drawn (like the eye toggle) on top of
        // the aged-paper overlay -- inside drawContent()/drawBattleContent()
        // they'd sit under the vignette and the inner ink-frame stroke,
        // which is why the eye toggle is placed here too. Only ever drawn/
        // hit-testable against the live snapshot, never the fading-out one.
        long bandNow = System.nanoTime();
        boolean resultsMode = snap.inBattle && resultsVisible(snap);
        boolean targeting = !resultsMode && snap.inBattle && isTargetingActive(bandNow);
        boolean listMode = !resultsMode && snap.inBattle && snap.listOpen && !snap.listRows.isEmpty();
        // Top-strip chips. The fast-forward badge owns the top-right corner
        // in every mode and never fades; the gear (outside battle) and the
        // eye/AUTO chips (battle) crossfade across the battle boundary --
        // see ChipFade. Drawn here, on top of the aged-paper overlay, for
        // the same reason as the command buttons above. Hit boxes follow
        // the TARGET state, not the fade: cleared below for whichever mode
        // we're not in, so a chip that's still fading out can't be tapped.
        boolean showGear = !snap.inBattle;
        boolean showAuto = snap.inBattle && snap.autoBattleAvailable;
        // eye only when there's a hidden-info enemy for it to act on
        boolean showEye = snap.inBattle && anyFlaggedHidden(snap);
        gearFade.setVisible(showGear);
        eyeFade.setVisible(showEye);
        autoFade.setVisible(showAuto);
        drawFastForwardBadge(c, parchment);
        RectF gearRect = new RectF(parchment.left + 24f, parchment.top + 24f,
                parchment.left + 24f + SettingsScreen.GEAR_CHIP_SIZE, parchment.top + 24f + SettingsScreen.GEAR_CHIP_SIZE);
        if (drawFadedChip(c, gearFade, gearRect, () -> view.settings.drawGearChip(c, parchment))) animating = true;
        if (drawFadedChip(c, eyeFade, eyeHitRect(parchment), () -> drawEyeToggle(c, parchment))) animating = true;
        if (drawFadedChip(c, autoFade, autoChipRect(parchment), () -> drawAutoToggle(c, parchment))) animating = true;
        if (!showGear) view.settings.gearHitBox.setEmpty();
        if (!showEye) eyeHitBox.setEmpty();
        if (!showAuto) autoHitBox.setEmpty();
        if (snap.inBattle) {
            if (resultsMode) {
                drawResultsWindow(c, parchment);
                commandCount = 0;
                targetCount = 0;
                listVisibleCount = 0;
                listBackHitBox.setEmpty();
            } else if (targeting) {
                drawTargetingButtons(c, parchment);
                commandCount = 0;
                listVisibleCount = 0;
                listBackHitBox.setEmpty();
            } else if (listMode) {
                drawSubmenuList(c, parchment);
                drawListBackToggle(c, parchment);
                commandCount = 0;
                targetCount = 0;
            } else {
                drawCommandButtons(c, parchment, snap, true);
                targetCount = 0;
                listVisibleCount = 0;
                listBackHitBox.setEmpty();
            }
        } else {
            commandCount = 0;
            targetCount = 0;
            listVisibleCount = 0;
            listBackHitBox.setEmpty();
        }
        // targeting mode has its own wall-clock timeout (see
        // isTargetingActive) that isn't tied to a snapshot change, so the
        // band needs exactly one more repaint right at the deadline or it
        // would only clear itself whenever the next update() happens to
        // land. This is a single delayed callback, not the
        // postInvalidateOnAnimation loop the other animators below use --
        // that would redraw at display refresh rate (full parchment/
        // speckle/ninepatch repaint) for up to 8s straight after every
        // command, which breaks this view's "fully idle once settled"
        // invariant for no visible benefit (nothing here is actually
        // animating frame to frame). The submenu-list band needs no such
        // callback -- snap.listOpen is snapshot-driven, not a timer, so the
        // next real snapshot update already repaints it.
        if (targeting) {
            view.postInvalidateDelayed(Math.max(1L, (targetingUntil - System.nanoTime()) / 1_000_000L + 16L));
        }
        return animating;
    }

}
