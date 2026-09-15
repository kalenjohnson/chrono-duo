package com.kalenjohnson.chronoduo;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * DS-style bottom screen. Before any party member has been read (no game
 * attached yet, or nothing has passed the sanity check), the whole panel is
 * skipped in favor of a minimal black-screen "CHRONO DUO" wordmark — see
 * {@link #drawWordmark}. Once a party exists: compact navy HP/MP boxes in the
 * top-left (portrait, HP xx/ xx, MP xx/ xx) and a torn-edge parchment map
 * panel as the centerpiece, showing the live location name and — once
 * ChronoAssets finishes its background extraction from resources.bin — the
 * game's own portrait and world-map art. Colored-initial portraits and a
 * hand-drawn marker remain as fallbacks when that art isn't available yet
 * (or at all).
 */
public final class PartyPanelView extends View implements ChronoAssets.Listener {
    // Blanks the top-screen Tech/Item submenu lists (see
    // GameState.nativeSetHideBattleSubmenus) so this panel's own submenu-list
    // band (drawSubmenuList) is the only place they're shown -- flip off in
    // one place if the native-side hiding ever misbehaves. Read by
    // AppActivity at startup.
    public static boolean HIDE_SUBMENUS = true;

    // face.png layout: 4x2 grid of 96x88 tiles (384x176 sheet), char-id order
    // (Crono..Magus, Epoch); char ids 0..6 line up with
    // PartySnapshot.DEFAULT_NAMES. A mod can ship a differently-sized
    // face.png that keeps the same 4x2 layout (e.g. higher-res portraits),
    // so faceTileRect scales these by (actual sheet size / original sheet
    // size) rather than using them as literal pixels against whatever
    // ChronoAssets.getFace() currently returns.
    private static final int FACE_TILE_W = 96;
    private static final int FACE_TILE_H = 88;
    private static final int FACE_SHEET_W = FACE_TILE_W * 4;
    private static final int FACE_SHEET_H = FACE_TILE_H * 2;
    private static final int FACE_COLS = 4;

    private PartySnapshot snap = new PartySnapshot();

    // --- lightweight animation state -------------------------------------
    // No ValueAnimator: onDraw self-schedules via postInvalidateOnAnimation
    // while (and only while) something below is still off-target, so the
    // view is fully idle — no timers, no battery burn — once settled.
    private boolean attached;

    // Mode crossfade (map/field content <-> battle content), driven by
    // wall-clock elapsed time. fadeSnap holds the snapshot that was current
    // right before snap.inBattle flipped, so the fading-out side can keep
    // drawing its own (slightly stale) content for MODE_FADE_NANOS.
    private static final long MODE_FADE_NANOS = 250_000_000L;
    private PartySnapshot fadeSnap;
    private long modeFadeStart = -1L;

    // Field-mode location title fade (map/battle titles don't use this).
    private static final long TITLE_FADE_NANOS = 180_000_000L;
    private String fadingOutTitle;
    private long titleFadeStart = -1L;

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

    // Field-mode area-map bitmap crossfade: kept separate from the title
    // fade above since the two triggers don't always coincide (fieldMapId
    // can change while the location name stays the same -- distinct
    // sub-areas sharing a name -- or a rendered minimap can appear/disappear
    // beneath unchanged text once ChronoAssets finishes/loses it). Reuses
    // TITLE_FADE_NANOS as its duration. prevAreaMapBitmap/prevAreaMapId
    // snapshot what was showing right before the id changed, so the
    // fading-out side keeps drawing its own bitmap while the incoming one
    // fades in.
    private int prevAreaMapId = -1;
    private Bitmap prevAreaMapBitmap;
    private long areaMapFadeStart = -1L;

    // Per-slot eased HP-bar fractions (enemy battle bars). Keyed by enemy
    // index -- there's no persistent enemy identity to key on, and slot
    // order is stable within one fight, matching the "enemy slot" ask.
    private final Map<Integer, Float> enemyBarFrac = new HashMap<>();

    // DS status box palette (navy window, light double border)
    private static final int BOX_BG = Color.rgb(32, 40, 96);
    private static final int BOX_BG_DARK = Color.rgb(16, 20, 56);
    private static final int BOX_BORDER_OUT = Color.rgb(222, 222, 230);
    private static final int BOX_BORDER_IN = Color.rgb(90, 96, 150);
    // parchment palette
    // Dev aid: draws the numeric field-map id next to the location name on
    // the indoor/area-map panel, for lining up rendered area_minimap_%03d.png
    // files with live ids while building out the ds_maps/ set. Never shown
    // once that set is complete -- flip off then.
    private static final boolean SHOW_MAP_ID = false; // debug aid: map id after the location name
    // Dev calibration readout: appends the live field-tile position (one
    // decimal place) to the field-mode location title, so a player can
    // report on-screen positions to calibrate AreaMapCalib's per-map
    // transforms. Cheap (a single formatted string per draw) -- see
    // drawFieldContent.
    private static final boolean SHOW_FIELD_POS = false; // calibration readout, off now
    private static final int PAPER = Color.rgb(214, 197, 158);
    private static final int PAPER_DARK = Color.rgb(150, 128, 88);
    private static final int PAPER_EDGE = Color.rgb(94, 74, 44);
    private static final int INK = Color.rgb(96, 72, 40);

    // Enemy hidden-HP display setting: HONOR (default) hides cur/max numbers
    // and shows a neutral full-width bar for any enemy the game itself
    // marks as hidden-info (MonsterNameData.dat flag byte == 255, bosses/
    // event enemies); FULL always shows real numbers, as today. Persisted
    // across sessions and toggled by tapping the eye glyph during battle.
    private static final String PREFS_NAME = "chronoduo_prefs";
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
    private static final long TARGETING_DURATION_NANOS = 8_000_000_000L;
    private long targetingUntil = -1L;

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

    // --- settings screen (DS ROM import) -----------------------------------
    // Implemented by AppActivity (a different package, hence public) and
    // wired onto this view via SecondScreenManager -- see that class and
    // AppActivity#onCreate. Called when the user taps "Import DS ROM...".
    public interface SettingsHost {
        void requestRomImport();
        /** Pixel-graphics toggle changed: (un)register the original-sprite replacements. */
        void onPixelGraphicsChanged(boolean enabled);
        /** "Build original sprites" tapped: kick off the background OrigArtRebuilder pass. */
        void requestOrigArtBuild();
        /** "Import SNES/DS save..." tapped: launch the SAF picker for a save file. */
        void requestSaveImport();
        /** "Import mod..." tapped: launch the SAF picker for a mod archive (.ctp/.zip). */
        void requestModImport();
        /** A mod row's enable/disable button tapped: {@code name} is the mod's directory name. */
        void onModToggled(String name, boolean enabled);
        /** A catalog row's "Get" button tapped: {@code id} is the {@link com.kalenjohnson.chronoduo.mods.ModCatalog.Entry#id}. */
        void requestModGet(String id);
        /** A grouped mod's option-row cycle button tapped (see {@link com.kalenjohnson.chronoduo.mods.ModManager.ModGroup}): {@code group} is the download name, {@code optionTitle} the {@link com.kalenjohnson.chronoduo.mods.ModManager.OptionGroup#title}, and {@code dirOrNull} the choice directory to select ({@code null} to select none). */
        void onModOptionSelected(String group, String optionTitle, String dirOrNull);
    }
    private SettingsHost settingsHost;

    /** Sets (or clears, with null) the host that handles ROM-import requests from the settings screen -- see {@link SettingsHost}. */
    public void setSettingsHost(SettingsHost host) {
        settingsHost = host;
    }

    // Settings "gear" chip, top-left corner: a small CT menu window (the
    // game's own window texture via drawNinePatch, same chrome as the battle
    // command buttons) holding a hand-drawn 17x17 pixel-art gear sprite,
    // upscaled nearest-neighbor at an integer factor so it reads as game
    // pixels rather than a vector icon. Shown in field/overworld modes and on
    // the pre-load wordmark screen (never battle; the eye toggle owns that
    // corner there, and mid-battle isn't a sane time to open settings).
    // Tapping it enters settingsMode. See drawGearChip.
    private final RectF gearHitBox = new RectF();
    private static final float GEAR_CHIP_SIZE = 96f;   // window chip side, px
    private static final float GEAR_HIT_PAD = 10f;     // extra tap slop around the chip
    // Gear sprite, '.' = transparent, 'B' = body. Shading (lit top-left
    // edge, shaded bottom-right edge) and a 1px navy drop shadow are derived
    // from this mask when the bitmap is first built -- see buildGearSprite.
    private static final String[] GEAR_SPRITE = {
            ".......BBB.......",
            ".......BBB.......",
            "..BB...BBB...BB..",
            "..BBB..BBB..BBB..",
            "...BBBBBBBBBBB...",
            "....BBBBBBBBB....",
            "....BBB...BBB....",
            "BBBBBB.....BBBBBB",
            "BBBBBB.....BBBBBB",
            "BBBBBB.....BBBBBB",
            "....BBB...BBB....",
            "....BBBBBBBBB....",
            "...BBBBBBBBBBB...",
            "..BBB..BBB..BBB..",
            "..BB...BBB...BB..",
            ".......BBB.......",
            ".......BBB.......",
    };
    private static final int GEAR_LIT = Color.rgb(255, 255, 255);
    private static final int GEAR_BODY = Color.rgb(226, 230, 244);
    private static final int GEAR_SHADE = Color.rgb(140, 150, 200);
    private Bitmap gearSprite; // built lazily, 18x18 (17 + 1px drop shadow)
    private boolean settingsMode;
    // Settings is paginated (see SETTINGS_PAGES / drawSettingsScreen);
    // settingsPage is the 0-based page in view, kept across open/close so
    // reopening lands where the user left off. The prev/next hit boxes are
    // set only while drawSettingsScreen draws the corresponding arrow.
    private static final String[] SETTINGS_PAGES = {"Imports", "Graphics", "Maps", "Speed", "Mods"};
    // Mods is always the last page -- derived so nothing else needs updating
    // if another page is ever inserted before it.
    private static final int MODS_PAGE = SETTINGS_PAGES.length - 1;
    private static final int SPEED_PAGE = MODS_PAGE - 1;
    private int settingsPage;
    // Left tab rail hit boxes, one per SETTINGS_PAGES entry -- replaces the
    // old prev/next arrow buttons; tapping a tab jumps settingsPage directly
    // (see drawSettingsScreen). settingsBackHitBox (declared with the other
    // settings hit boxes below) is the rail's own Back button now, not a
    // bottom-row button.
    private final RectF[] settingsTabHitBoxes = new RectF[SETTINGS_PAGES.length];
    {
        for (int i = 0; i < SETTINGS_PAGES.length; i++) settingsTabHitBoxes[i] = new RectF();
    }

    // --- Settings d-pad focus (nice-to-have controller nav in settingsMode) -
    // Two focus areas: the tab rail (cursor over the tabs plus a trailing
    // Back "slot") and the content area (cursor over whatever the current
    // page draws -- rebuilt fresh every drawSettingsScreen pass into
    // settingsFocusRects/settingsFocusActivate/settingsFocusStepLeft/Right,
    // parallel lists indexed by settingsContentFocus). LEFT/RIGHT switch
    // between the two areas (RIGHT from the rail enters content, LEFT from
    // content returns to the rail) EXCEPT when the focused content item is a
    // Mods option-cycle picker, in which case LEFT/RIGHT step its choice
    // instead (settingsFocusStepLeft/Right non-null only for those entries).
    // Only ever consulted while settingsMode is true -- see
    // onControllerLeft/Right/Up/Down/Confirm -- so this can never steal
    // gameplay d-pad/A input while settings is closed.
    private static final int SETTINGS_FOCUS_RAIL = 0, SETTINGS_FOCUS_CONTENT = 1;
    private int settingsFocusArea = SETTINGS_FOCUS_RAIL;
    private int settingsRailFocus; // 0..SETTINGS_PAGES.length-1 = tabs, SETTINGS_PAGES.length = Back
    private int settingsContentFocus;
    private final java.util.List<RectF> settingsFocusRects = new java.util.ArrayList<>();
    private final java.util.List<Runnable> settingsFocusActivate = new java.util.ArrayList<>();
    private final java.util.List<Runnable> settingsFocusStepLeft = new java.util.ArrayList<>();
    private final java.util.List<Runnable> settingsFocusStepRight = new java.util.ArrayList<>();

    // Import status, pushed from AppActivity via setImportStatus as the
    // background ROM import (see SettingsHost) progresses. importError is
    // non-null only after a failed import; cleared by the next attempt.
    private boolean importing;
    private int importDone, importTotal;
    private String importStage = "";
    private String importError;
    // Hit boxes for the settings screen's two buttons, updated only while
    // drawSettingsScreen actually draws them (import button hit box is left
    // empty while importing, so a tap can't double-fire a second import).
    private final RectF importButtonHitBox = new RectF();
    private final RectF settingsBackHitBox = new RectF();

    // SNES-save-import status, pushed from AppActivity via setSaveImportStatus
    // as the (quick, foreground-triggered) background import runs -- same
    // idle/importing/error shape as importing/importError above, but no
    // done/total/stage progress since the conversion is effectively
    // instantaneous once the user has picked a slot in AppActivity's
    // AlertDialog flow. saveImportMessage holds either the success line
    // ("Imported into slot N -- ...") or the failure message.
    private boolean savingImport;
    private String saveImportMessage;
    private boolean saveImportError;
    private final RectF saveImportButtonHitBox = new RectF();

    /**
     * Pushes SNES-save-import progress/result to the settings screen; called
     * from AppActivity on the main thread. {@code importing} true means a
     * background import is in flight (button disabled meanwhile); false with
     * {@code error} true means {@code message} is a failure to show; false
     * with {@code error} false and a non-null {@code message} means success
     * (shown once, same as the DS-ROM row's error case, until the next
     * attempt or a screen re-entry clears it).
     */
    public void setSaveImportStatus(boolean importing, String message, boolean error) {
        this.savingImport = importing;
        this.saveImportMessage = message;
        this.saveImportError = error;
        invalidate();
    }

    // --- Mods page (see com.kalenjohnson.chronoduo.mods.ModManager) --------
    // Same idle/importing/error shape as the SNES-save row above, plus a
    // list of installed mods pushed separately (setModsList) since it can
    // change independently of an import (a toggle rescans too).
    private boolean modsImporting;
    private String modsError;
    // Neutral (non-error) one-line status, e.g. "Imported 2 mods (1
    // enabled)" -- drawn in the normal body color with no prefix, unlike
    // modsError's red "error: " styling. Mirrors saveImportMessage/
    // saveImportError's message+flag split above. Kept (not cleared) while a
    // new import/get is in flight, so a caller can push a live progress line
    // (e.g. "Downloading... 2/5") through the same field while modsImporting
    // is true -- see setModsStatus and drawSettingsScreen's Mods case, which
    // draws modsMessage in place of the previous flow's fixed "working..."
    // whenever it's non-null.
    private String modsMessage;
    private java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.ModInfo> modsList =
            java.util.Collections.emptyList();
    // Curated catalog (see com.kalenjohnson.chronoduo.mods.ModCatalog), pushed
    // once AppActivity's background load/refresh finishes -- empty until
    // then, which just means the page shows only already-installed mods
    // (none, normally) until it arrives.
    private java.util.List<com.kalenjohnson.chronoduo.mods.ModCatalog.Entry> modCatalog =
            java.util.Collections.emptyList();
    // Multi-.ctp download groupings (see com.kalenjohnson.chronoduo.mods.ModManager.ModGroup),
    // pushed alongside modsList/modCatalog -- see buildModRows/buildDisplayRows.
    private java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.ModGroup> modGroupList =
            java.util.Collections.emptyList();
    private final RectF modImportButtonHitBox = new RectF();
    // One row per catalog entry (in catalog order) plus any installed mod not
    // in the catalog -- see buildModRows(). Capped at MAX_MOD_ROWS; rows that
    // don't fit the viewport are reachable by scrolling the list (see
    // modScrollY below) rather than by a dead "+N more..." label. One hit
    // box per drawn row, cleared/rebuilt every pass alongside the parallel
    // per-row arrays below (only the first modRowCount entries of each are
    // valid on a given frame; a row scrolled fully or partially out of the
    // viewport gets an empty hit box even though its array slot is valid --
    // see drawSettingsScreen's Mods case). A row is either a not-yet-
    // installed catalog entry (modRowCatalogId set, tapping it calls
    // requestModGet) or an installed mod's On/Off toggle (modRowDirName set
    // instead, tapping it calls onModToggled) -- never both.
    // Bumped from the original 10: a grouped multi-.ctp download (e.g. the
    // ~30-sub-mod Pixel Demaster) now collapses to one parent row, but
    // expanding it to see/change its option groups adds one flattened row
    // per option group (see ModDisplayRow/buildDisplayRows) -- MAX_MOD_ROWS
    // is the cap on the FLATTENED row count (parents + any expanded
    // options), not on catalog/installed entries.
    private static final int MAX_MOD_ROWS = 48;
    // Real minimum touch-target height for a row's Get/On/Off/cycle button
    // (dp, converted to px via dp() at draw time) -- see drawSettingsScreen's
    // Mods case, which centers the button vertically in its (correspondingly
    // taller) row.
    private static final float MOD_ROW_BUTTON_H_DP = 48f;
    private final RectF[] modRowHitBoxes = new RectF[MAX_MOD_ROWS];
    private final String[] modRowDirName = new String[MAX_MOD_ROWS];
    private final String[] modRowCatalogId = new String[MAX_MOD_ROWS];
    private final boolean[] modRowEnabled = new boolean[MAX_MOD_ROWS];
    // True when row i is a grouped mod's option-cycle row (see
    // ModDisplayRow) rather than a parent Get/On/Off row -- in that case
    // modRowHitBoxes[i] is the cycle button and modRowDirName/CatalogId are
    // unused; modRowOptionGroupKey/Title/NextChoice below carry what tapping
    // it should do instead (see onTouchEvent and SettingsHost#onModOptionSelected).
    private final boolean[] modRowIsOptionCycle = new boolean[MAX_MOD_ROWS];
    private final String[] modRowOptionGroupKey = new String[MAX_MOD_ROWS];
    private final String[] modRowOptionTitle = new String[MAX_MOD_ROWS];
    private final String[] modRowOptionNextChoice = new String[MAX_MOD_ROWS]; // null == "select none"
    // Back-step choice (mirrors modRowOptionNextChoice) -- tapping the left
    // ~30% of a picker button (see drawSettingsScreen's Mods case, the "◂"
    // end) steps back through the cycle instead of forward.
    private final String[] modRowOptionPrevChoice = new String[MAX_MOD_ROWS];
    // A parent row's "Options ▾/▴" expander tap target -- separate from
    // modRowHitBoxes[i] (that stays the row's own Get/On/Off button) so both
    // can be live at once. Non-empty only for rows whose ModRow has a
    // non-empty ModGroup#options list (see buildDisplayRows).
    private final RectF[] modExpanderHitBoxes = new RectF[MAX_MOD_ROWS];
    private final String[] modExpanderKey = new String[MAX_MOD_ROWS]; // the group's downloadName
    private int modRowCount;
    {
        for (int i = 0; i < MAX_MOD_ROWS; i++) {
            modRowHitBoxes[i] = new RectF();
            modExpanderHitBoxes[i] = new RectF();
        }
    }
    // Which groups (keyed by ModGroup#downloadName) are expanded on the Mods
    // page, showing their option-group rows -- session-only UI state, never
    // persisted, reset implicitly whenever the app restarts. Not cleared on
    // page navigation, so leaving and returning to the Mods page keeps a
    // group open.
    private final java.util.Set<String> modExpandedGroups = new java.util.HashSet<>();

    // --- Mods list scrolling ------------------------------------------------
    // The row list is the only part of the Mods page that scrolls (heading,
    // Import button, status line and restart note stay fixed -- see
    // drawSettingsScreen). modScrollY is the vertical offset in px, clamped
    // every draw to [0, modContentHeight - modViewportHeight] since either
    // can change frame to frame (catalog/list push, row count change) without
    // any touch event to clamp it first. modListViewport/modContentHeight/
    // modViewportHeight are recomputed every draw pass (page 3 only; left
    // empty/zero on other pages so the touch handler below can't act on
    // stale geometry from the last time Mods was shown) and read back by
    // onTouchEvent for drag clamping and the chevron page-up/down amount.
    private float modScrollY;
    private final RectF modListViewport = new RectF();
    private float modContentHeight;
    private float modViewportHeight;
    // Drag state for the list-scroll gesture -- see onTouchEvent. Armed on an
    // ACTION_DOWN inside modListViewport; modDragging flips true only once
    // the finger has moved past the touch slop, at which point the gesture
    // is committed to scrolling (never a tap) for the rest of its life, even
    // if the finger returns near the down point.
    private boolean modListTouchActive;
    private boolean modDragging;
    private float modTouchDownX, modTouchDownY;
    private float modScrollAtDown;
    // Chevron hit boxes (page up/down by one viewport height) and the
    // scrollbar thumb rect -- all recomputed every draw pass, non-empty only
    // when the corresponding direction/overflow actually applies.
    private final RectF modScrollUpHitBox = new RectF();
    private final RectF modScrollDownHitBox = new RectF();
    private final RectF modScrollbarThumb = new RectF();

    /**
     * Pushes mod import progress/result to the settings screen; called from
     * AppActivity on the main thread. Mirrors {@link #setSaveImportStatus}'s
     * message+error split: {@code importing} true means a background
     * import/get is in flight -- {@code message}, if non-null, is drawn as a
     * live progress line in place of the generic "working..." fallback (see
     * drawSettingsScreen's Mods case); false with a non-null {@code error}
     * means the last attempt failed (shown red, "error: " prefixed); false
     * with a non-null {@code message} and null {@code error} means it
     * succeeded and has something worth saying (e.g. "Imported 2 mods (1
     * enabled)" -- shown in the normal body color, no prefix; the mod list
     * itself, via {@link #setModsList}, already shows a plain single-mod
     * success, so {@code message} is typically null for that common case);
     * both null means idle with nothing to report.
     */
    public void setModsStatus(boolean importing, String message, String error) {
        this.modsImporting = importing;
        this.modsMessage = message;
        this.modsError = error;
        invalidate();
    }

    /** Pushes the current installed-mod list (see {@link com.kalenjohnson.chronoduo.mods.ModManager#lastMods}) to the settings screen; called from AppActivity on the main thread after every scan/import/toggle. */
    public void setModsList(java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.ModInfo> mods) {
        this.modsList = mods != null ? mods : java.util.Collections.emptyList();
        invalidate();
    }

    /** Pushes the curated mod catalog (see {@link com.kalenjohnson.chronoduo.mods.ModCatalog}) to the settings screen; called from AppActivity on the main thread once the background load/refresh (bundled, cached, or freshly fetched) finishes. */
    public void setModCatalog(java.util.List<com.kalenjohnson.chronoduo.mods.ModCatalog.Entry> entries) {
        this.modCatalog = entries != null ? entries : java.util.Collections.emptyList();
        invalidate();
    }

    /** Pushes the current multi-.ctp download groupings (see {@link com.kalenjohnson.chronoduo.mods.ModManager#groups}) to the settings screen; called from AppActivity alongside {@link #setModsList} after every scan/import/toggle/option-select. */
    public void setModGroups(java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.ModGroup> groups) {
        this.modGroupList = groups != null ? groups : java.util.Collections.emptyList();
        invalidate();
    }

    /** One row of the Mods page's combined catalog+installed list -- see {@link #buildModRows}. */
    private static final class ModRow {
        final String title, summary, notes; // notes may be null
        final boolean installed;
        final String dirName;   // installed mods only: the actual directory name (for onModToggled)
        final String catalogId; // not-yet-installed catalog entries only: id (for requestModGet)
        final boolean enabled;
        final String suffix;    // "N files"/"N files, conflicts: N" -- installed rows only, else null
        // Non-null when this row is the PARENT of a multi-.ctp download
        // (dirName == group.mainDir, or this is an uncatalogued group's own
        // "Imported mod" row) -- see buildModRows/buildDisplayRows. The
        // On/Off button above still binds to dirName/enabled (the main mod);
        // group is only consulted for the Options ▾/▴ expander.
        final com.kalenjohnson.chronoduo.mods.ModManager.ModGroup group;

        ModRow(String title, String summary, String notes, boolean installed, String dirName,
               String catalogId, boolean enabled, String suffix,
               com.kalenjohnson.chronoduo.mods.ModManager.ModGroup group) {
            this.title = title;
            this.summary = summary;
            this.notes = notes;
            this.installed = installed;
            this.dirName = dirName;
            this.catalogId = catalogId;
            this.enabled = enabled;
            this.suffix = suffix;
            this.group = group;
        }
    }

    /**
     * Builds the Mods page's row list: every {@link #modCatalog} entry, in
     * catalog order (a "Get" row if {@link
     * com.kalenjohnson.chronoduo.mods.ModCatalog#findInstalledDirName} finds
     * no matching installed directory, an On/Off row if it does), followed
     * by any installed mod directory (from {@link #modsList}) that matches
     * no catalog entry at all -- a mod imported before the catalog existed,
     * or dropped in by hand.
     *
     * <p>A multi-.ctp download (see {@link #modGroupList}) collapses to a
     * SINGLE row bound to its {@link com.kalenjohnson.chronoduo.mods.ModManager.ModGroup#mainDir}:
     * a catalog entry matching that group's main dir (via {@link
     * com.kalenjohnson.chronoduo.mods.ModCatalog#findInstalledDirName}'s
     * group-aware overload) gets the row as usual, but with {@code
     * ModRow#group} set so drawSettingsScreen draws the Options ▾/▴
     * expander; an uncatalogued group's main dir gets an "Imported mod" row
     * titled by the group's OWN download name (not the raw directory name)
     * instead. Every other directory belonging to a group -- the main dir
     * of a group already emitted, and every option-choice dir -- is
     * excluded entirely from the flat list; see {@link #buildDisplayRows}
     * for how an expanded group's option rows get drawn instead. Pure
     * w.r.t. this view's own fields.
     */
    private java.util.List<ModRow> buildModRows() {
        java.util.List<ModRow> rows = new java.util.ArrayList<>();
        java.util.List<String> dirNames = new java.util.ArrayList<>();
        java.util.Map<String, com.kalenjohnson.chronoduo.mods.ModManager.ModInfo> byDir = new java.util.HashMap<>();
        for (com.kalenjohnson.chronoduo.mods.ModManager.ModInfo m : modsList) {
            dirNames.add(m.name);
            byDir.put(m.name, m);
        }

        java.util.Map<String, com.kalenjohnson.chronoduo.mods.ModManager.ModGroup> groupByMainDir =
                new java.util.HashMap<>();
        // Only a group WITH a main dir gets collapsed to one parent row --
        // hiding its option choices' flat rows depends on that parent row
        // existing to show them via its expander. A group with no main dir
        // (ModGroup#mainDir's doc: "null if the download had no such
        // single-segment sub-mod" -- an all-options Nexus download) has
        // nowhere to put an expander, so its sub-mods are left OUT of
        // optionDirs/groupByMainDir entirely and fall through to today's
        // flat "Imported mod" rows below instead of vanishing.
        java.util.Set<String> optionDirs = new java.util.HashSet<>();
        for (com.kalenjohnson.chronoduo.mods.ModManager.ModGroup g : modGroupList) {
            if (g.mainDir == null) continue;
            groupByMainDir.put(g.mainDir, g);
            for (com.kalenjohnson.chronoduo.mods.ModManager.OptionGroup og : g.options) {
                for (com.kalenjohnson.chronoduo.mods.ModManager.Choice c : og.choices) optionDirs.add(c.dir);
            }
        }

        java.util.Set<String> consumedDirs = new java.util.HashSet<>();
        for (com.kalenjohnson.chronoduo.mods.ModCatalog.Entry e : modCatalog) {
            String dir = com.kalenjohnson.chronoduo.mods.ModCatalog.findInstalledDirName(e, dirNames, modGroupList);
            if (dir != null) {
                consumedDirs.add(dir);
                com.kalenjohnson.chronoduo.mods.ModManager.ModInfo m = byDir.get(dir);
                com.kalenjohnson.chronoduo.mods.ModManager.ModGroup g = groupByMainDir.get(dir);
                rows.add(new ModRow(e.name, e.summary, e.notes, true, dir, null, m.enabled, modSuffix(m), g));
            } else {
                rows.add(new ModRow(e.name, e.summary, e.notes, false, null, e.id, false, null, null));
            }
        }
        for (com.kalenjohnson.chronoduo.mods.ModManager.ModInfo m : modsList) {
            if (consumedDirs.contains(m.name)) continue;
            if (optionDirs.contains(m.name)) continue; // grouped option sub-dir -- shown only via its expanded parent
            com.kalenjohnson.chronoduo.mods.ModManager.ModGroup g = groupByMainDir.get(m.name);
            if (g != null) {
                consumedDirs.add(m.name);
                rows.add(new ModRow(g.downloadName, "Imported mod", null, true, m.name, null, m.enabled, modSuffix(m), g));
            } else {
                rows.add(new ModRow(m.name, "Imported mod", null, true, m.name, null, m.enabled, modSuffix(m), null));
            }
        }
        return rows;
    }

    /**
     * One row of the Mods page's FLATTENED display list -- either a {@link
     * #parent} row (a {@link ModRow}, exactly as {@link #buildModRows}
     * built it) or, immediately following an expanded parent, one {@link
     * #option} row per {@link com.kalenjohnson.chronoduo.mods.ModManager.ModGroup#options}
     * entry. Each option row contributes its own entry to the draw loop's
     * {@code rowHeights[]}/hit-box arrays exactly like a parent row does --
     * it's never folded into the parent's own row height.
     */
    private static final class ModDisplayRow {
        final ModRow parent; // non-null for a parent row
        final boolean expandable;
        final String expandKey; // parent rows only: group.downloadName
        final com.kalenjohnson.chronoduo.mods.ModManager.OptionGroup option; // non-null for an option row
        final String optionGroupKey; // option rows only: the owning group's downloadName
        // Option rows only: og.title with any shared leading segment
        // stripped for display (see #sharedLeadingSegment) -- the RAW
        // og.title is still what's sent to onModOptionSelected, so this is
        // purely cosmetic.
        final String optionDisplayTitle;

        ModDisplayRow(ModRow parent, boolean expandable, String expandKey) {
            this.parent = parent;
            this.expandable = expandable;
            this.expandKey = expandKey;
            this.option = null;
            this.optionGroupKey = null;
            this.optionDisplayTitle = null;
        }

        ModDisplayRow(com.kalenjohnson.chronoduo.mods.ModManager.OptionGroup option, String optionGroupKey,
                      String optionDisplayTitle) {
            this.parent = null;
            this.expandable = false;
            this.expandKey = null;
            this.option = option;
            this.optionGroupKey = optionGroupKey;
            this.optionDisplayTitle = optionDisplayTitle;
        }
    }

    /**
     * The " - "-joined leading segment (e.g. "Interface") shared by every
     * option-group title in {@code options} that HAS more than one segment
     * -- a single-segment title (e.g. "Font") is excluded from the check
     * entirely and never stripped, so a mod whose option groups are a mix
     * of top-level and nested titles ("Font" alongside "Interface - Art
     * Icons") still gets the "Interface - " prefix stripped off the nested
     * ones even though "Font" itself has nothing to strip. Returns null if
     * no multi-segment title exists, or the multi-segment titles disagree
     * on their leading segment.
     */
    private static String sharedLeadingSegment(java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.OptionGroup> options) {
        String shared = null;
        for (com.kalenjohnson.chronoduo.mods.ModManager.OptionGroup og : options) {
            String[] segs = og.title.split(" - ", 2);
            if (segs.length < 2) continue; // single-segment title -- not part of the check
            if (shared == null) shared = segs[0];
            else if (!shared.equals(segs[0])) return null;
        }
        return shared;
    }

    /** Strips a leading {@code "<prefix> - "} from {@code title} when present; returns {@code title} unchanged when it isn't, or when stripping would leave nothing (the title WAS exactly the shared segment). */
    private static String stripLeadingSegment(String title, String prefix) {
        if (prefix == null) return title;
        String lead = prefix + " - ";
        if (title.startsWith(lead) && title.length() > lead.length()) return title.substring(lead.length());
        return title;
    }

    /** Flattens {@code rows} into the Mods page's actual draw order: every parent row, followed by its {@link com.kalenjohnson.chronoduo.mods.ModManager.ModGroup#options} rows (one per option group) whenever that parent's group is in {@link #modExpandedGroups}. */
    private java.util.List<ModDisplayRow> buildDisplayRows(java.util.List<ModRow> rows) {
        java.util.List<ModDisplayRow> out = new java.util.ArrayList<>();
        for (ModRow row : rows) {
            boolean expandable = row.group != null && !row.group.options.isEmpty();
            String key = row.group != null ? row.group.downloadName : null;
            out.add(new ModDisplayRow(row, expandable, key));
            if (expandable && modExpandedGroups.contains(key)) {
                String sharedPrefix = sharedLeadingSegment(row.group.options);
                for (com.kalenjohnson.chronoduo.mods.ModManager.OptionGroup og : row.group.options) {
                    out.add(new ModDisplayRow(og, key, stripLeadingSegment(og.title, sharedPrefix)));
                }
            }
        }
        return out;
    }

    private static String modSuffix(com.kalenjohnson.chronoduo.mods.ModManager.ModInfo m) {
        return m.fileCount + (m.fileCount == 1 ? " file" : " files")
                + (m.conflictCount > 0 ? ", conflicts: " + m.conflictCount : "");
    }

    /** Truncates {@code s} with a trailing "…" so it fits {@code maxW} under the {@link #text} paint's CURRENT size/typeface (caller must {@link #setText} first). */
    private String ellipsize(String s, float maxW) {
        if (s == null) return "";
        if (text.measureText(s) <= maxW) return s;
        String shown = s;
        while (shown.length() > 1 && text.measureText(shown + "…") > maxW) {
            shown = shown.substring(0, shown.length() - 1);
        }
        return shown + "…";
    }

    /**
     * Greedy word-wraps {@code s} onto up to {@code maxLines} lines that
     * each fit {@code maxW} under the {@link #text} paint's CURRENT size/
     * typeface (caller must {@link #setText} first, exactly like {@link
     * #ellipsize}) -- used by the Mods page's row title/summary text (see
     * the Mods case in {@link #drawSettingsScreen}). Breaks on spaces via
     * {@link Paint#breakText}/{@link Paint#measureText} so words are never
     * split mid-word; a lone word wider than {@code maxW} is hard-broken
     * (breakText's char count) since there's no space to back up to. If the
     * text still doesn't fit in {@code maxLines} lines, the last line is
     * {@link #ellipsize}d against the remaining (unwrapped) text so nothing
     * drawn is silently dropped without a "…". Returns an empty array for
     * null/blank input (the caller reserves no row space for it), otherwise
     * 1..{@code maxLines} lines.
     */
    private String[] wrapLines(String s, float maxW, int maxLines) {
        if (s == null) return new String[0];
        String remaining = s.trim();
        if (remaining.isEmpty()) return new String[0];
        java.util.List<String> lines = new java.util.ArrayList<>();
        while (lines.size() < maxLines && !remaining.isEmpty()) {
            if (text.measureText(remaining) <= maxW) {
                lines.add(remaining);
                remaining = "";
                break;
            }
            int fitCount = (int) text.breakText(remaining, true, maxW, null);
            int breakAt = fitCount;
            if (fitCount < remaining.length()) {
                int lastSpace = remaining.lastIndexOf(' ', Math.max(0, fitCount - 1));
                if (lastSpace > 0) breakAt = lastSpace;
            }
            if (breakAt <= 0) breakAt = Math.max(1, fitCount);
            lines.add(remaining.substring(0, breakAt).trim());
            remaining = remaining.substring(Math.min(breakAt, remaining.length())).trim();
        }
        if (!remaining.isEmpty()) {
            // Ran out of lines with text left over -- fold it back onto the
            // last line and let ellipsize crop it to fit, so truncation is
            // always visibly marked with "…" rather than silently cut.
            int last = lines.size() - 1;
            String combined = lines.isEmpty() ? remaining : lines.get(last) + " " + remaining;
            String ellipsized = ellipsize(combined, maxW);
            if (lines.isEmpty()) lines.add(ellipsized); else lines.set(last, ellipsized);
        }
        return lines.toArray(new String[0]);
    }

    // Pixel-graphics toggle row (GOT-patches libchrono.so's Texture2D default
    // filter -- see GameState.nativeSetPixelGraphics). Persisted via
    // GameState's own pref helpers (shared "chronoduo_prefs" file, different
    // key than KEY_ENEMY_HP_MODE above), initialized from the current pref in
    // the constructor and re-applied to the running game immediately on tap.
    private boolean pixelGraphicsOn;
    private final RectF pixelGraphicsHitBox = new RectF();

    // Dungeon fog-of-war toggle row (see FogOfWar) -- same persistence/hit-
    // test shape as pixelGraphicsOn above, different key, default On (the DS
    // reveals dungeon minimaps as you walk; towns/houses are unaffected --
    // see AreaMapCalib.isFogged). "Reset explored maps" wipes all saved
    // reveal state via FogOfWar.clearAll().
    private static final String KEY_FOG_ON = "fog_on";
    private boolean fogOn;
    private final RectF fogToggleHitBox = new RectF();
    private final RectF fogResetHitBox = new RectF();

    /**
     * Pushes live DS-ROM-import progress/result to the settings screen (see
     * {@link #drawSettingsScreen}); called from AppActivity on the main
     * thread as the background import advances. {@code importing} true means
     * an import is in flight ({@code done}/{@code total}/{@code stage}
     * reflect {@link SettingsHost}'s progress callback); {@code importing}
     * false with a non-null {@code error} means the last attempt failed;
     * {@code importing} false with a null {@code error} means idle (either
     * never attempted, or the last attempt succeeded -- either way the
     * status row falls back to counting files on disk, see {@link
     * #countDsMaps}).
     */
    public void setImportStatus(boolean importing, int done, int total, String stage, String error) {
        this.importing = importing;
        this.importDone = done;
        this.importTotal = total;
        this.importStage = stage != null ? stage : "";
        this.importError = error;
        invalidate();
    }

    // Original-art row, pushed from AppActivity via setOrigArtStatus as the
    // background rebuild (see SettingsHost#requestOrigArtBuild) advances --
    // same idle/building/error shape as the DS-import row above. The build
    // runs in two phases (character sprites, then field chip sheets), each
    // with its own done/total count, so origArtPhase names the phase in
    // flight ("sprites" / "field chips") and is null when idle.
    private boolean origArtBuilding;
    private int origArtDone, origArtTotal;
    private String origArtPhase;
    private String origArtError;
    // Hit box for the "Build original art" button -- left empty while
    // building, same double-fire guard as importButtonHitBox.
    private final RectF origArtButtonHitBox = new RectF();
    // Speed settings page (see GameSpeed) -- "Speed: Nx" and "Right trigger:
    // Hold/Toggle" buttons, same drawSettingsButton style/hit-box handling
    // as the Graphics page's pixelGraphicsHitBox/origArtButtonHitBox above.
    private final RectF ffSpeedHitBox = new RectF();
    private final RectF ffModeHitBox = new RectF();
    // On-panel fast-forward badge (both in and out of battle) -- see
    // drawFastForwardBadge. Tapping it toggles GameSpeed regardless of
    // ffMode.
    private final RectF ffHitBox = new RectF();

    /**
     * Pushes live original-art rebuild progress/result to the settings screen
     * (see {@link #drawSettingsScreen}); called from AppActivity on the main
     * thread as the background {@code OrigArtRebuilder.rebuildAll} /
     * {@code MapchipRebuilder.rebuildAll} passes advance. Mirrors {@link
     * #setImportStatus}'s building/error/idle shape -- idle falls back to
     * counting {@code *.png} files under {@code <filesDir>/orig_art}, see
     * {@link #countOrigArtSheets}. {@code phase} labels which of the two
     * rebuild passes the done/total belongs to, and may be null.
     */
    public void setOrigArtStatus(boolean building, int done, int total, String phase, String error) {
        this.origArtBuilding = building;
        this.origArtDone = done;
        this.origArtTotal = total;
        this.origArtPhase = phase;
        this.origArtError = error;
        invalidate();
    }

    private static final int[] PORTRAIT_COLORS = {
            Color.rgb(196, 84, 40), Color.rgb(120, 180, 230), Color.rgb(120, 200, 120),
            Color.rgb(190, 160, 70), Color.rgb(80, 160, 90), Color.rgb(230, 200, 140),
            Color.rgb(110, 80, 180),
    };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint portraitPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    // nearest-neighbor for the world map so upscaled pixels stay crisp
    private final Paint mapPaint = new Paint();
    // marker tile: same nearest-neighbor upscale as the map, but kept fully
    // opaque (unlike mapPaint) so it stays crisp on top of the sepia map
    private final Paint markerPaint = new Paint();
    // nearest-neighbor, opaque: the settings gear sprite (see drawGearChip)
    private final Paint spritePaint = new Paint();
    // Scratch paints for the area-map crossfade: copied from mapPaint/
    // markerPaint each frame (via Paint.set) and given a fade-specific
    // alpha, so the shared mapPaint/markerPaint alpha is never mutated
    // persistently.
    private final Paint areaMapFadePaint = new Paint();
    private final Paint markerFadePaint = new Paint();
    // Src/dst rects computed by the most recent drawAreaMapBitmap() call --
    // exposed so the field-position marker (drawFieldContent) can map a
    // point through the same crop/scale without recomputing it. areaMapSrc
    // is in the original 256x192 DS image's pixel space; areaMapDst is the
    // on-screen rect it was drawn into.
    private final Rect areaMapSrc = new Rect();
    private final RectF areaMapDst = new RectF();
    private final Path speckles = new Path();
    private int speckleW, speckleH;
    // torn-paper outline: dark-edge path is the full parchment rect walked
    // and jittered; paper path is the same walk on the inset paper rect, so
    // the "deckled" fill sits a few px inside the ripped dark edge, same as
    // the old rounded-rect version's 7px inset.
    private final Path tornEdge = new Path();
    private final Path tornPaper = new Path();
    private int tornW, tornH;
    // The rect buildTornPaths last built tornEdge/tornPaper for -- settings
    // mode's near-full-screen parchment and the normal mode's smaller,
    // lower map-panel rect can both be requested at the SAME view width/
    // height (a mode switch never resizes the view), so caching on w/h
    // alone reused whichever rect happened to be cached first for every
    // later draw of the OTHER mode: e.g. a Presentation recreated behind
    // our back (sleep/wake, or switching away and back -- see
    // AppActivity#onSecondScreenPanelAttached's doc) re-entering settings
    // mode at the same view size the old instance already had normal-mode
    // paths cached for drew the settings screen's rail/content against the
    // correct full rect, but its background parchment fill/clip stayed the
    // OLD, smaller normal-mode shape -- a black band above it with the
    // title/first row clipped away. NaN initially so the very first call
    // always rebuilds regardless of r.
    private float tornRectL = Float.NaN, tornRectT = Float.NaN, tornRectR = Float.NaN, tornRectB = Float.NaN;

    public PartyPanelView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        mapPaint.setFilterBitmap(false);
        mapPaint.setDither(false);
        // sepia map is composited over the parchment at ~88% so the paper
        // ground still shows through, per the weathered-map look
        mapPaint.setAlpha(225);
        markerPaint.setFilterBitmap(false);
        markerPaint.setDither(false);
        spritePaint.setFilterBitmap(false);
        spritePaint.setDither(false);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        honorHiddenHp = !MODE_FULL.equals(prefs.getString(KEY_ENEMY_HP_MODE, MODE_HONOR));
        pixelGraphicsOn = GameState.getPixelGraphicsPref(context);
        fogOn = prefs.getBoolean(KEY_FOG_ON, true);
        FogOfWar.init(context.getFilesDir());
        // Repaints the badge (see drawFastForwardBadge) whenever the R2
        // trigger/analog binding or the Speed settings page changes
        // GameSpeed's state from outside this view.
        GameSpeed.setListener(this::invalidate);
    }

    /** Toggles and persists the enemy hidden-HP display setting; called from the eye-glyph tap handler in {@link #onTouchEvent}. */
    private void toggleHiddenHpMode() {
        honorHiddenHp = !honorHiddenHp;
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_ENEMY_HP_MODE, honorHiddenHp ? MODE_HONOR : MODE_FULL)
                .apply();
        invalidate();
    }

    /**
     * Touch surface is otherwise fully inert (controller/touch input stays
     * with the game -- see {@link SecondScreenPresentation}'s
     * FLAG_NOT_FOCUSABLE window). Two interactive elements exist, both
     * battle-only: the eye glyph (toggles hidden-HP display) and, when the
     * live command menu is open, the Attack/Tech/Item buttons -- a tap
     * inside one injects a touch into the real game at that command's
     * stored game-screen coordinates (see {@link #injectCommand}).
     * Everything else returns false so no other touch behavior is ever
     * implied.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        boolean modsPageActive = settingsMode
                && Math.floorMod(settingsPage, SETTINGS_PAGES.length) == MODS_PAGE;

        // Mods list drag-to-scroll: a small state machine ahead of the
        // generic "DOWN-only" dispatch below since it needs MOVE and UP too.
        // ACTION_DOWN inside the list viewport arms it (recording the start
        // point/scroll offset) without yet deciding drag vs. tap. On MOVE,
        // once the finger has passed the touch slop the gesture commits to
        // dragging (modScrollY tracks the finger for the rest of the
        // gesture, clamped to content bounds) and is never treated as a tap.
        // On UP, a drag that never passed the slop falls through to the
        // ordinary tap dispatch below using the UP event's own coordinates
        // (down and up are close together for a real tap, so this is
        // equivalent to dispatching on DOWN); an actual drag or a CANCEL
        // just ends the gesture and consumes the event.
        if (modsPageActive && action == MotionEvent.ACTION_DOWN
                && !modListViewport.isEmpty() && modListViewport.contains(event.getX(), event.getY())) {
            modListTouchActive = true;
            modDragging = false;
            modTouchDownX = event.getX();
            modTouchDownY = event.getY();
            modScrollAtDown = modScrollY;
            return true;
        }
        if (modsPageActive && modListTouchActive && action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - modTouchDownX;
            float dy = event.getY() - modTouchDownY;
            if (!modDragging) {
                int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (Math.abs(dy) > slop || Math.abs(dx) > slop) modDragging = true;
            }
            if (modDragging) {
                float maxScroll = Math.max(0f, modContentHeight - modViewportHeight);
                modScrollY = Math.max(0f, Math.min(maxScroll, modScrollAtDown - dy));
                invalidate();
            }
            return true;
        }
        if (modsPageActive && modListTouchActive
                && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            modListTouchActive = false;
            boolean wasDragging = modDragging;
            modDragging = false;
            if (wasDragging || action == MotionEvent.ACTION_CANCEL) {
                return true; // a real drag (or a cancelled gesture) is never a tap
            }
            // Not a drag: fall through to the ordinary tap dispatch below,
            // using this UP event's coordinates.
        } else if (action != MotionEvent.ACTION_DOWN) {
            return false;
        }
        if (settingsMode) {
            if (!modsPageActive) {
                // stale drag state from a page we've since left/re-entered
                modListTouchActive = false;
                modDragging = false;
            }
            if (!modScrollUpHitBox.isEmpty() && modScrollUpHitBox.contains(event.getX(), event.getY())) {
                modScrollY = Math.max(0f, modScrollY - modViewportHeight);
                invalidate();
                return true;
            }
            if (!modScrollDownHitBox.isEmpty() && modScrollDownHitBox.contains(event.getX(), event.getY())) {
                float maxScroll = Math.max(0f, modContentHeight - modViewportHeight);
                modScrollY = Math.min(maxScroll, modScrollY + modViewportHeight);
                invalidate();
                return true;
            }
            if (!importing && !importButtonHitBox.isEmpty()
                    && importButtonHitBox.contains(event.getX(), event.getY())) {
                if (settingsHost != null) settingsHost.requestRomImport();
                return true;
            }
            if (!savingImport && !saveImportButtonHitBox.isEmpty()
                    && saveImportButtonHitBox.contains(event.getX(), event.getY())) {
                if (settingsHost != null) settingsHost.requestSaveImport();
                return true;
            }
            if (!modsImporting && !modImportButtonHitBox.isEmpty()
                    && modImportButtonHitBox.contains(event.getX(), event.getY())) {
                if (settingsHost != null) settingsHost.requestModImport();
                return true;
            }
            for (int i = 0; i < modRowCount; i++) {
                if (!modExpanderHitBoxes[i].isEmpty()
                        && modExpanderHitBoxes[i].contains(event.getX(), event.getY())) {
                    // "Options ▾/▴" -- purely local UI state, no SettingsHost
                    // call: just flips this group's membership in
                    // modExpandedGroups (see buildDisplayRows).
                    String key = modExpanderKey[i];
                    if (key != null) {
                        if (!modExpandedGroups.remove(key)) modExpandedGroups.add(key);
                        invalidate();
                    }
                    return true;
                }
            }
            for (int i = 0; i < modRowCount; i++) {
                if (!modRowHitBoxes[i].isEmpty()
                        && modRowHitBoxes[i].contains(event.getX(), event.getY())) {
                    if (settingsHost != null) {
                        if (modRowIsOptionCycle[i]) {
                            // Left ~30% of the wide picker ("◂" end) steps
                            // back one choice; the rest steps forward.
                            boolean stepBack = event.getX() < modRowHitBoxes[i].left + modRowHitBoxes[i].width() * 0.3f;
                            settingsHost.onModOptionSelected(modRowOptionGroupKey[i], modRowOptionTitle[i],
                                    stepBack ? modRowOptionPrevChoice[i] : modRowOptionNextChoice[i]);
                        } else if (modRowCatalogId[i] != null) {
                            settingsHost.requestModGet(modRowCatalogId[i]);
                        } else {
                            settingsHost.onModToggled(modRowDirName[i], !modRowEnabled[i]);
                        }
                    }
                    return true;
                }
            }
            if (!pixelGraphicsHitBox.isEmpty()
                    && pixelGraphicsHitBox.contains(event.getX(), event.getY())) {
                pixelGraphicsOn = !pixelGraphicsOn;
                GameState.setPixelGraphicsPref(getContext(), pixelGraphicsOn);
                if (settingsHost != null) settingsHost.onPixelGraphicsChanged(pixelGraphicsOn);
                invalidate();
                return true;
            }
            if (!fogToggleHitBox.isEmpty()
                    && fogToggleHitBox.contains(event.getX(), event.getY())) {
                fogOn = !fogOn;
                getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_FOG_ON, fogOn).apply();
                invalidate();
                return true;
            }
            if (!fogResetHitBox.isEmpty()
                    && fogResetHitBox.contains(event.getX(), event.getY())) {
                FogOfWar.clearAll();
                invalidate();
                return true;
            }
            if (!origArtBuilding && !origArtButtonHitBox.isEmpty()
                    && origArtButtonHitBox.contains(event.getX(), event.getY())) {
                if (settingsHost != null) settingsHost.requestOrigArtBuild();
                return true;
            }
            if (!ffSpeedHitBox.isEmpty()
                    && ffSpeedHitBox.contains(event.getX(), event.getY())) {
                GameSpeed.cycleSpeed(getContext());
                invalidate();
                return true;
            }
            if (!ffModeHitBox.isEmpty()
                    && ffModeHitBox.contains(event.getX(), event.getY())) {
                GameSpeed.cycleMode(getContext());
                invalidate();
                return true;
            }
            for (int i = 0; i < SETTINGS_PAGES.length; i++) {
                if (!settingsTabHitBoxes[i].isEmpty()
                        && settingsTabHitBoxes[i].contains(event.getX(), event.getY())) {
                    if (modsPageActive && i != settingsPage) modScrollY = 0f; // leaving the Mods page
                    settingsPage = i;
                    settingsFocusArea = SETTINGS_FOCUS_RAIL;
                    settingsRailFocus = i;
                    settingsContentFocus = 0;
                    invalidate();
                    return true;
                }
            }
            if (!settingsBackHitBox.isEmpty()
                    && settingsBackHitBox.contains(event.getX(), event.getY())) {
                if (modsPageActive) modScrollY = 0f; // leaving the Mods page
                settingsMode = false;
                invalidate();
                return true;
            }
            return true; // swallow every touch while the settings modal is up
        }
        // The gear is also drawn on the pre-load wordmark screen (no party
        // yet, so inBattle is necessarily false there) -- imports and art
        // builds don't need a loaded game, so settings must be reachable
        // from the title screen.
        if (!snap.inBattle && !gearHitBox.isEmpty()
                && gearHitBox.contains(event.getX(), event.getY())) {
            settingsMode = true;
            settingsFocusArea = SETTINGS_FOCUS_RAIL;
            settingsRailFocus = Math.floorMod(settingsPage, SETTINGS_PAGES.length);
            settingsContentFocus = 0;
            invalidate();
            return true;
        }
        // Fast-forward badge (see drawFastForwardBadge): drawn/hit-testable
        // both in and out of battle, toggle semantics regardless of ffMode.
        if (!ffHitBox.isEmpty() && ffHitBox.contains(event.getX(), event.getY())) {
            GameSpeed.toggle(getContext());
            invalidate();
            return true;
        }
        if (snap.inBattle && !eyeHitBox.isEmpty()
                && eyeHitBox.contains(event.getX(), event.getY())) {
            toggleHiddenHpMode();
            return true;
        }
        if (snap.inBattle && snap.autoBattleAvailable && !autoHitBox.isEmpty()
                && autoHitBox.contains(event.getX(), event.getY())) {
            injectAutoBattleToggle();
            return true;
        }
        if (snap.inBattle && snap.menuOpen && commandCount > 0) {
            for (int i = 0; i < commandCount; i++) {
                if (commandHitBoxes[i].contains(event.getX(), event.getY())) {
                    commandSel = i;
                    injectCommand(i);
                    return true;
                }
            }
        }
        if (isTargetingActive(System.nanoTime()) && targetCount > 0) {
            for (int i = 0; i < targetCount; i++) {
                if (targetHitBoxes[i].contains(event.getX(), event.getY())) {
                    injectTarget(i);
                    return true;
                }
            }
        }
        if (snap.inBattle && snap.listOpen && !listBackHitBox.isEmpty()
                && listBackHitBox.contains(event.getX(), event.getY())) {
            backList();
            return true;
        }
        if (snap.inBattle && snap.listOpen && listVisibleCount > 0) {
            for (int i = 0; i < listVisibleCount; i++) {
                if (listRowHitBoxes[i].contains(event.getX(), event.getY())) {
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
        return snap.inBattle && !snap.menuOpen && targetingUntil > 0 && now < targetingUntil;
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
        if (Float.isNaN(row.x) || Float.isNaN(row.y)) return;
        long now = System.nanoTime();
        if (lastConfirmInjectAt >= 0 && now - lastConfirmInjectAt < CONFIRM_COOLDOWN_NANOS) return;
        lastConfirmInjectAt = now;
        BattleInput.tap(row.x, row.y);
        targetingUntil = now + TARGETING_DURATION_NANOS;
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
    public boolean onControllerLeft() {
        if (settingsMode) return settingsNavLeft();
        if (!commandNavActive()) return false;
        if (commandSel > 0) {
            commandSel--;
            invalidate();
        }
        return true;
    }

    /** Same as {@link #onControllerLeft}, moving right and clamping at {@code commandCount - 1}. */
    public boolean onControllerRight() {
        if (settingsMode) return settingsNavRight();
        if (!commandNavActive()) return false;
        if (commandSel < commandCount - 1) {
            commandSel++;
            invalidate();
        }
        return true;
    }

    /**
     * Settings-mode d-pad focus (see the settingsFocus* fields' javadoc
     * above): LEFT returns from the content area to the rail, unless the
     * focused content item is a Mods option-cycle picker, in which case it
     * steps that picker's choice back instead.
     */
    private boolean settingsNavLeft() {
        if (settingsFocusArea == SETTINGS_FOCUS_CONTENT) {
            Runnable step = settingsContentFocus >= 0 && settingsContentFocus < settingsFocusStepLeft.size()
                    ? settingsFocusStepLeft.get(settingsContentFocus) : null;
            if (step != null) {
                step.run();
            } else {
                settingsFocusArea = SETTINGS_FOCUS_RAIL;
            }
            invalidate();
        }
        return true;
    }

    /** Same as {@link #settingsNavLeft}, entering the content area from the rail, or stepping a focused picker's choice forward. */
    private boolean settingsNavRight() {
        if (settingsFocusArea == SETTINGS_FOCUS_RAIL) {
            if (!settingsFocusRects.isEmpty()) {
                settingsFocusArea = SETTINGS_FOCUS_CONTENT;
                settingsContentFocus = Math.max(0, Math.min(settingsContentFocus, settingsFocusRects.size() - 1));
                invalidate();
            }
        } else {
            Runnable step = settingsContentFocus >= 0 && settingsContentFocus < settingsFocusStepRight.size()
                    ? settingsFocusStepRight.get(settingsContentFocus) : null;
            if (step != null) {
                step.run();
                invalidate();
            }
        }
        return true;
    }

    /** Moves the rail cursor (tabs + trailing Back slot) or the content cursor up one, clamped at 0 -- no wrap either way. */
    private boolean settingsNavUpInternal() {
        if (settingsFocusArea == SETTINGS_FOCUS_RAIL) {
            if (settingsRailFocus > 0) {
                settingsRailFocus--;
                invalidate();
            }
        } else if (settingsContentFocus > 0) {
            settingsContentFocus--;
            invalidate();
        }
        return true;
    }

    /** Same as {@link #settingsNavUpInternal}, moving down and clamping at the last tab/Back slot or the last content item. */
    private boolean settingsNavDownInternal() {
        if (settingsFocusArea == SETTINGS_FOCUS_RAIL) {
            if (settingsRailFocus < SETTINGS_PAGES.length) { // SETTINGS_PAGES.length itself == Back
                settingsRailFocus++;
                invalidate();
            }
        } else if (settingsContentFocus < settingsFocusRects.size() - 1) {
            settingsContentFocus++;
            invalidate();
        }
        return true;
    }

    /**
     * Activates whatever the focus cursor is currently on: a rail tab jumps
     * {@link #settingsPage} to it, the rail's trailing Back slot exits
     * settings, and a content item runs its {@link #settingsFocusActivate}
     * entry (the same action a tap on it would perform).
     */
    private boolean settingsNavConfirm() {
        if (settingsFocusArea == SETTINGS_FOCUS_RAIL) {
            if (settingsRailFocus == SETTINGS_PAGES.length) {
                if (Math.floorMod(settingsPage, SETTINGS_PAGES.length) == MODS_PAGE) modScrollY = 0f;
                settingsMode = false;
            } else {
                if (settingsRailFocus != settingsPage
                        && Math.floorMod(settingsPage, SETTINGS_PAGES.length) == MODS_PAGE) {
                    modScrollY = 0f;
                }
                settingsPage = settingsRailFocus;
                settingsContentFocus = 0;
            }
        } else if (settingsContentFocus >= 0 && settingsContentFocus < settingsFocusActivate.size()) {
            Runnable act = settingsFocusActivate.get(settingsContentFocus);
            if (act != null) act.run();
        }
        invalidate();
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
    public boolean onControllerConfirm() {
        if (settingsMode) return settingsNavConfirm();
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
    public boolean onControllerUp() {
        if (settingsMode) return settingsNavUpInternal();
        if (!listNavActive()) return false;
        if (listSel > 0) {
            listSel--;
            invalidate();
        }
        return true;
    }

    /** Same as {@link #onControllerUp}, moving down and clamping at {@code snap.listRows.size() - 1}. */
    public boolean onControllerDown() {
        if (settingsMode) return settingsNavDownInternal();
        if (!listNavActive()) return false;
        if (listSel < snap.listRows.size() - 1) {
            listSel++;
            invalidate();
        }
        return true;
    }

    // The three parchment content modes drawContent() dispatches on: live
    // battle, the overworld map, or a field location title. Any change
    // between these three -- not just a BATTLE flip -- is a hard content
    // cut and should use the mode crossfade, not a jump.
    private enum ContentMode {BATTLE, OVERWORLD, FIELD}

    private static ContentMode modeOf(PartySnapshot s) {
        if (s.inBattle) return ContentMode.BATTLE;
        if (isWorldMapLocation(s.fieldMapId)) return ContentMode.OVERWORLD;
        return (s.mapName == null || s.mapName.isEmpty()) ? ContentMode.OVERWORLD : ContentMode.FIELD;
    }

    /**
     * Location ids 0x1F0..0x1F7 are the eight overworlds themselves
     * ("Present", "Middle Ages", ... "Apocalypse"). The game reports one of
     * them as the field map while the menu is open on the overworld (the
     * WorldScene is gone, so worldScenePresent is false) -- there is no area
     * map for them, so the panel keeps showing the overworld instead of a
     * "#497" placeholder.
     */
    static boolean isWorldMapLocation(int id) {
        return id >= 0x1F0 && id <= 0x1F7;
    }

    // Last snapshot taken with the WorldScene live; reused to keep drawing
    // the overworld panel while the menu covers the world map.
    private PartySnapshot lastOverworldSnap;

    public void update(PartySnapshot s) {
        if (settingsMode && s.inBattle) {
            // the gear chip is never shown in battle -- if battle starts
            // while settings happens to be open (e.g. an ambush), get out of
            // the way rather than block the battle UI on the bottom screen.
            settingsMode = false;
            modScrollY = 0f;
        }
        boolean hadContent = !snap.members.isEmpty();
        ContentMode oldMode = modeOf(snap);
        ContentMode newMode = modeOf(s);
        if (hadContent && newMode != oldMode) {
            // parchment content mode is changing (battle <-> overworld <->
            // field, in any direction): keep the outgoing snapshot around
            // so it can fade out with its own data while the incoming one
            // fades in.
            fadeSnap = snap;
            modeFadeStart = System.nanoTime();
        } else if (hadContent && oldMode == ContentMode.FIELD && newMode == ContentMode.FIELD
                && !snap.mapName.equals(s.mapName)) {
            // staying in FIELD mode but the location name itself changed:
            // the smaller title-only micro-fade, not the full mode crossfade.
            fadingOutTitle = snap.mapName;
            titleFadeStart = System.nanoTime();
        }
        if (hadContent && oldMode == ContentMode.FIELD && newMode == ContentMode.FIELD
                && snap.fieldMapId != s.fieldMapId) {
            // area-map identity changed while staying in FIELD mode (a new
            // area's bitmap, or the placeholder text <-> a rendered minimap
            // becoming available) -- crossfade the bitmap independently of
            // the title micro-fade above; deliberately not an "else if" off
            // that block since fieldMapId can change without mapName
            // changing (or vice versa). An ordinary position update or a
            // re-read with the same id leaves fieldMapId untouched, so it
            // never lands here.
            // Snapshot the MASKED bitmap (not the raw one) so a fogged room
            // doesn't suddenly "pop" fully visible for the duration of the
            // outgoing side's fade -- see maskedAreaMapFor.
            Bitmap prevRawAreaMap = ChronoAssets.getAreaMap(snap.fieldMapId, snap.fieldX, snap.fieldY);
            prevAreaMapBitmap = maskedAreaMapFor(snap.fieldMapId, snap.fieldX, snap.fieldY, prevRawAreaMap);
            prevAreaMapId = snap.fieldMapId;
            areaMapFadeStart = System.nanoTime();
        }
        if (targetingUntil > 0 && (!s.inBattle || s.menuOpen)) {
            // exit targeting early: either the next command menu has opened
            // (a new command was issued through some other path) or battle
            // itself ended -- don't wait out the timeout in either case.
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
        invalidate();
    }

    private static int[] levelsOf(PartySnapshot s) {
        int[] a = new int[s.members.size()];
        for (int i = 0; i < a.length; i++) a[i] = s.members.get(i).level;
        return a;
    }

    /**
     * Which message {@link #drawResultsWindow} should show for {@code
     * s.resultsStep}, per the disassembly report's step ranges: 0-1 EXP,
     * 2-3 TP, 4-7 Gold, 8-9/16-17/24-25 item drop (index = step/8 - 1 into
     * {@code s.resultsItems}), 10-15/18-23/26-31 level-up. Any other step
     * (the per-actor bookkeeping steps between those ranges, or -1/32+ idle)
     * returns null so the caller keeps showing the last message. Numeric
     * placeholders come from {@link ChronoAssets#getBattleMessage}, which
     * falls back to a literal template when the battle.txt table isn't
     * loaded.
     */
    private String computeResultsMessage(PartySnapshot s) {
        int step = s.resultsStep;
        if (step == 0 || step == 1) {
            return ChronoAssets.getBattleMessage(37, Math.max(0, s.resultsExp));
        } else if (step == 2 || step == 3) {
            return ChronoAssets.getBattleMessage(38, Math.max(0, s.resultsTp));
        } else if (step >= 4 && step <= 7) {
            return ChronoAssets.getBattleMessage(39, Math.max(0, s.resultsGold));
        } else if (step == 8 || step == 9 || step == 16 || step == 17 || step == 24 || step == 25) {
            int idx = step / 8 - 1;
            String name = (idx >= 0 && idx < s.resultsItems.length)
                    ? resultsItemName(s.resultsItems[idx]) : null;
            if (name == null) return null;
            return "Obtained " + name + ".";
        } else if ((step >= 10 && step <= 15) || (step >= 18 && step <= 23) || (step >= 26 && step <= 31)) {
            String member = leveledMemberName(s);
            return member != null ? (member + " leveled up!") : "Level up!";
        }
        return null;
    }

    /** Item name for a battle-results drop id: flat item.txt line index first (these ids come from the flat drop list), then the encoded-id lookup, then "#id" -- mirrors the old drawResultsWindow's per-row lookup. */
    private String resultsItemName(int id) {
        String[] itemNames = ChronoAssets.getItemNames();
        if (itemNames != null && id >= 0 && id < itemNames.length && !itemNames[id].trim().isEmpty()) {
            return itemNames[id];
        }
        String name = ChronoAssets.getItemName(id);
        if (name == null || name.trim().isEmpty()) name = "#" + id;
        return name;
    }

    /** Which party member's level rose since {@link #resultsBaseLevels} was captured (results-screen start), or null if none/unknown (baseline missing, or a leveled member's slot changed). */
    private String leveledMemberName(PartySnapshot s) {
        if (resultsBaseLevels == null) return null;
        for (int i = 0; i < s.members.size() && i < resultsBaseLevels.length; i++) {
            if (s.members.get(i).level > resultsBaseLevels[i]) return s.members.get(i).name;
        }
        return null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        ChronoAssets.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        // drop any in-flight animation state so a re-attach starts clean
        // rather than resuming a stale fade with nonsense elapsed time.
        fadeSnap = null;
        modeFadeStart = -1L;
        titleFadeStart = -1L;
        fadingOutTitle = null;
        prevAreaMapBitmap = null;
        prevAreaMapId = -1;
        areaMapFadeStart = -1L;
        resultsMessage = null;
        resultsFadingMessage = null;
        resultsMessageFadeStart = -1L;
        resultsBaseLevels = null;
        targetingUntil = -1L;
        pendingMenuClose = false;
        pendingMenuCloseAt = -1L;
        listSel = 0;
        settingsMode = false;
        FogOfWar.flush();
        ChronoAssets.removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onChronoAssetsChanged() {
        invalidate();
    }

    /**
     * Converts a dp value to px using this view's current display density.
     * The rest of this file sizes everything as a fraction of {@code
     * getWidth()}/{@code getHeight()} (the panel is one fixed-aspect canvas,
     * not a normal dp-laid-out layout), but the Mods page's row buttons are
     * specified in absolute dp (a real minimum touch-target size, independent
     * of how tall the panel happens to render) -- see drawSettingsScreen's
     * Mods case.
     */
    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void setText(float size, int color, boolean bold, Paint.Align align, boolean shadow) {
        text.setTextSize(size);
        text.setColor(color);
        applyTypeface(bold ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                : Typeface.MONOSPACE);
        text.setTextAlign(align);
        if (shadow) text.setShadowLayer(2.5f, 1.5f, 1.5f, Color.argb(200, 0, 0, 0));
        else text.clearShadowLayer();
    }

    /**
     * Routes every glyph the panel draws through the active mod font (see
     * {@link com.kalenjohnson.chronoduo.mods.ModManager#activeTypeface}) when
     * one is loaded, falling back to {@code fallback} (the typeface a call
     * site would otherwise have passed straight to {@link Paint#setTypeface})
     * otherwise. Bold/serif/monospace distinctions are ignored for the mod
     * font -- ChronoType is a single-weight, fixed-advance pixel font, so it
     * suits the MONOSPACE-typeface call sites (aligned numbers) as well as
     * the SERIF/DEFAULT_BOLD ones.
     *
     * <p>Must be called after {@link Paint#setTextSize} (via {@link
     * #setText} or directly) since a bitmap-strike mod font snaps the
     * already-set size to a multiple of its native ppem -- several call
     * sites call {@code setText} then this, in that order, for exactly that
     * reason.
     */
    // ChronoType's glyphs sit small in the em box compared to the serif it
    // replaces, so a size that fit the serif reads a little small once
    // swapped to the mod font -- scale up modestly to compensate.
    private static final float MOD_FONT_SIZE_BOOST = 1.15f;
    // Snap to the font's native pixel grid (crisp, antialiasing off) only
    // when the size is already within this fraction of a grid step; the
    // panel is drawn at device resolution and never upscaled, so a
    // fractional size with antialiasing looks better than jumping a whole
    // step (10 -> 20 px) to stay on the grid.
    private static final float MOD_FONT_SNAP_TOLERANCE = 0.12f;

    private void applyTypeface(Typeface fallback) {
        Typeface mod = com.kalenjohnson.chronoduo.mods.ModManager.activeTypeface();
        if (mod != null) {
            text.setTypeface(mod);
            int ppem = com.kalenjohnson.chronoduo.mods.ModManager.activeTypefacePpem();
            float size = text.getTextSize() * MOD_FONT_SIZE_BOOST;
            boolean crisp = false;
            if (ppem > 0) {
                int nearest = Math.max(ppem, Math.round(size / ppem) * ppem);
                if (Math.abs(size - nearest) <= ppem * MOD_FONT_SNAP_TOLERANCE) {
                    size = nearest;
                    crisp = true;
                }
            }
            text.setTextSize(size);
            text.setAntiAlias(!crisp);
            text.setSubpixelText(!crisp);
        } else {
            text.setTypeface(fallback);
            text.setAntiAlias(true);
            text.setSubpixelText(true);
        }
    }

    /** DS status box: real window texture (9-sliced) when loaded, else a hand-drawn navy double border; portrait, HP/MP rows on top either way. */
    private void drawStatusBox(Canvas c, PartySnapshot.Member m, float l, float t, float w, float h) {
        RectF box = new RectF(l, t, l + w, t + h);
        Bitmap winTex = ChronoAssets.getWindowTex();
        if (winTex != null) {
            float destInset = Math.min(w, h) * 0.09f;
            drawNinePatch(c, winTex, ChronoAssets.getWindowTexInset(), box, destInset);
            box.inset(5, 5); // match the hand-drawn path's interior padding so content layout is identical
        } else {
            fill.setShader(null);
            fill.setColor(BOX_BORDER_OUT);
            c.drawRect(box, fill);
            box.inset(3, 3);
            fill.setColor(BOX_BORDER_IN);
            c.drawRect(box, fill);
            box.inset(2, 2);
            fill.setColor(BOX_BG);
            c.drawRect(box, fill);
            // subtle bottom shade
            fill.setColor(BOX_BG_DARK);
            c.drawRect(new RectF(box.left, box.bottom - h * 0.18f, box.right, box.bottom), fill);
        }

        // portrait square: real art from face.png when available, else a
        // colored-initial placeholder. Side length normally fills the box
        // height (minus ppad on top/bottom); findStatusLayout may shrink it
        // one step (see STATUS_PORTRAIT_SHRINK) when even the font floor
        // can't fit CT's worst-case "HP 999/999"/"MP 99/99" readouts at full
        // portrait size -- see that method's doc.
        float ppad = h * 0.1f;
        float valRight = box.right - w * 0.03f;
        StatusFontFit fit = findStatusFontFit(box, h, w, ppad, valRight);
        float ps = fit.portraitSide;
        RectF portrait = new RectF(box.left + ppad, box.top + (h - ps) / 2f,
                box.left + ppad + ps, box.top + (h - ps) / 2f + ps);
        fill.setColor(Color.BLACK);
        c.drawRect(portrait, fill);
        RectF pin = new RectF(portrait);
        pin.inset(2, 2);
        int charIdx = indexOfName(m.name);
        Bitmap face = ChronoAssets.getFace();
        if (face != null && charIdx >= 0) {
            Rect src = faceTileRect(face, charIdx);
            c.drawBitmap(face, src, pin, portraitPaint);
        } else {
            fill.setColor(charIdx >= 0 ? PORTRAIT_COLORS[charIdx] : Color.DKGRAY);
            c.drawRect(pin, fill);
            setText(h * 0.5f, Color.argb(210, 0, 0, 0), true, Paint.Align.CENTER, false);
            c.drawText(m.name.substring(0, 1), portrait.centerX(), portrait.centerY() + h * 0.18f, text);
        }

        float tx = portrait.right + w * 0.035f;
        float row1 = box.top + h * 0.42f;
        float row2 = box.top + h * 0.82f;
        float fs = fit.fontSize;
        setText(fs, Color.rgb(190, 200, 255), true, Paint.Align.LEFT, true);
        c.drawText("HP", tx, row1, text);
        int hpCur = snap.inBattle ? m.battleCurHp : m.curHp;
        int hpMax = snap.inBattle ? m.battleMaxHp : m.maxHp;
        setText(fs, Color.WHITE, false, Paint.Align.RIGHT, true);
        c.drawText(hpCur + "/" + hpMax, valRight, row1, text);
        // battle MP calibrated live (u8 pair at actor +0x07/+0x08) — the MP
        // row now shows in both modes, tracking combat spending in battle
        int mpCur = snap.inBattle ? m.battleCurMp : m.curMp;
        int mpMax = snap.inBattle ? m.battleMaxMp : m.maxMp;
        setText(fs, Color.rgb(190, 200, 255), true, Paint.Align.LEFT, true);
        c.drawText("MP", tx, row2, text);
        setText(fs, Color.WHITE, false, Paint.Align.RIGHT, true);
        c.drawText(mpCur + "/" + mpMax, valRight, row2, text);
    }

    /** Result of {@link #findStatusFontFit}: the font size and portrait side length to use for one status box. */
    private static final class StatusFontFit {
        final float fontSize, portraitSide;
        StatusFontFit(float fontSize, float portraitSide) {
            this.fontSize = fontSize;
            this.portraitSide = portraitSide;
        }
    }

    // Worst-case label+value strings CT can actually produce: HP tops out at
    // 999 (3 digits, e.g. Frog's late-game max), MP at 99. "HP 999/999" is
    // the longer of the two and drives the fit; "MP 99/99" is checked too
    // since a narrow font family could in principle make it wider (it never
    // is with the monospace face this panel uses, but the check is cheap).
    private static final String STATUS_WORST_HP = "HP 999/999";
    private static final String STATUS_WORST_MP = "MP 99/99";
    private static final float STATUS_FONT_MAX_FRAC = 0.26f; // current/legacy default size, as a ceiling
    private static final float STATUS_FONT_MIN_FRAC = 0.15f; // floor below which digits stop being readable
    private static final float STATUS_FONT_STEP_FRAC = 0.01f;
    private static final float STATUS_PORTRAIT_SHRINK = 0.8f; // one-step fallback when the font floor still overflows

    /**
     * Picks the largest font size (as a fraction of box height, between
     * {@link #STATUS_FONT_MIN_FRAC} and {@link #STATUS_FONT_MAX_FRAC}) whose
     * measured width for CT's worst-case HP/MP readouts ({@link
     * #STATUS_WORST_HP}/{@link #STATUS_WORST_MP}, i.e. "HP 999/999") still
     * fits between the portrait's right edge and the box's right inner
     * margin -- measured with {@link #text} directly (not hardcoded), using
     * the same bold monospace face {@link #setText} draws the HP/MP labels
     * with, so the fit matches what's actually painted. If even the font
     * floor doesn't fit at the normal portrait size, retries once with the
     * portrait shrunk by {@link #STATUS_PORTRAIT_SHRINK} (freeing horizontal
     * room) before falling back to the floor size regardless -- see the
     * class-level follow-up note in the caller for why (never shrink below
     * readable text; the portrait is the one allowed to give ground first).
     */
    private StatusFontFit findStatusFontFit(RectF box, float h, float w, float ppad, float valRight) {
        float fullSide = h - 2 * ppad;
        for (int attempt = 0; attempt < 2; attempt++) {
            float ps = attempt == 0 ? fullSide : fullSide * STATUS_PORTRAIT_SHRINK;
            float tx = box.left + ppad + ps + w * 0.035f;
            float avail = valRight - tx;
            float maxFs = h * STATUS_FONT_MAX_FRAC;
            float minFs = h * STATUS_FONT_MIN_FRAC;
            float fs = maxFs;
            while (fs > minFs) {
                text.setTextSize(fs);
                applyTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                float wgt = Math.max(text.measureText(STATUS_WORST_HP), text.measureText(STATUS_WORST_MP));
                if (wgt <= avail) break;
                fs -= h * STATUS_FONT_STEP_FRAC;
            }
            fs = Math.max(fs, minFs);
            // Accept immediately once it fits at max-or-shrunk size, or once
            // we're out of fallback attempts (last attempt always accepted,
            // clamped to the floor above).
            text.setTextSize(fs);
            applyTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            float wgt = Math.max(text.measureText(STATUS_WORST_HP), text.measureText(STATUS_WORST_MP));
            if (wgt <= avail || attempt == 1) {
                return new StatusFontFit(fs, ps);
            }
        }
        return new StatusFontFit(h * STATUS_FONT_MIN_FRAC, fullSide * STATUS_PORTRAIT_SHRINK);
    }

    /**
     * Manual 9-slice draw: {@code srcInset} pixels of {@code bmp} on every
     * side are treated as fixed corners, the strips between them stretch
     * along one axis, and the center stretches both ways. Android's
     * NinePatch class needs its own .9.png chunk format, which a plain PNG
     * extracted at runtime doesn't have, so this does the src/dst rect math
     * by hand instead.
     */
    private void drawNinePatch(Canvas c, Bitmap bmp, int srcInset, RectF dst, float destInset) {
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        int si = Math.max(1, Math.min(srcInset, Math.min(bw, bh) / 2 - 1));
        float di = Math.max(1f, Math.min(destInset, Math.min(dst.width(), dst.height()) / 2f - 1f));

        int[] sx = {0, si, bw - si, bw};
        int[] sy = {0, si, bh - si, bh};
        float[] dx = {dst.left, dst.left + di, dst.right - di, dst.right};
        float[] dy = {dst.top, dst.top + di, dst.bottom - di, dst.bottom};

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                Rect src = new Rect(sx[col], sy[row], sx[col + 1], sy[row + 1]);
                RectF d = new RectF(dx[col], dy[row], dx[col + 1], dy[row + 1]);
                if (src.width() <= 0 || src.height() <= 0 || d.width() <= 0 || d.height() <= 0) continue;
                c.drawBitmap(bmp, src, d, portraitPaint);
            }
        }
    }

    private void buildSpeckles(int w, int h) {
        if (w == speckleW && h == speckleH) return;
        speckleW = w;
        speckleH = h;
        speckles.reset();
        Random rnd = new Random(1000); // deterministic texture
        for (int i = 0; i < 260; i++) {
            float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h;
            float r = 1f + rnd.nextFloat() * 2.2f;
            speckles.addCircle(x, y, r, Path.Direction.CW);
        }
    }

    /**
     * Walks a rect's perimeter in ~16px steps, nudging each point inward or
     * outward by a few px of seeded noise along the local edge normal, and
     * closes the result into a jagged "torn/deckled paper" outline. Same
     * deterministic-cache spirit as {@link #buildSpeckles}: called with a
     * fixed seed so the tear pattern doesn't crawl frame to frame.
     */
    private static void buildTornPath(Path out, RectF r, long seed, float amplitude, float step) {
        Random rnd = new Random(seed);
        float[][] edges = {
                {r.left, r.top, r.right, r.top},
                {r.right, r.top, r.right, r.bottom},
                {r.right, r.bottom, r.left, r.bottom},
                {r.left, r.bottom, r.left, r.top},
        };
        boolean first = true;
        for (float[] e : edges) {
            float x0 = e[0], y0 = e[1], x1 = e[2], y1 = e[3];
            float dx = x1 - x0, dy = y1 - y0;
            float len = (float) Math.hypot(dx, dy);
            int n = Math.max(1, Math.round(len / step));
            float ux = dx / len, uy = dy / len;
            float nx = -uy, ny = ux; // edge normal, for inward/outward jitter
            for (int i = 0; i < n; i++) {
                float t = i / (float) n;
                float px = x0 + dx * t, py = y0 + dy * t;
                float noise = (rnd.nextFloat() * 2f - 1f) * amplitude;
                px += nx * noise;
                py += ny * noise;
                if (first) {
                    out.moveTo(px, py);
                    first = false;
                } else {
                    out.lineTo(px, py);
                }
            }
        }
        out.close();
    }

    /**
     * The index reached by stepping one position FORWARD (the picker's "▸"
     * end) through a {@code totalChoices}-long cycle from {@code curIdx},
     * wrapping around -- see the Mods-page option picker (the "◂ label ▸"
     * row) in {@link #drawSettingsScreen}'s Mods case. Pure/no Android
     * dependency, so it's JVM-testable; package-visible for that. Plain
     * modulo, NOT a "curIdx + 1 < total" bounds check -- the latter breaks
     * when {@code curIdx == totalChoices - 1} is the trailing "None" slot
     * (nothing selected), where {@code curIdx + 1} is never {@code <
     * totalChoices} so stepping forward from "None" silently did nothing.
     */
    static int cycleStepForward(int curIdx, int totalChoices) {
        return (curIdx + 1) % totalChoices;
    }

    /** Like {@link #cycleStepForward}, stepping one position BACK (the picker's "◂" end) instead. */
    static int cycleStepBack(int curIdx, int totalChoices) {
        return (curIdx - 1 + totalChoices) % totalChoices;
    }

    private void buildTornPaths(RectF r) {
        int w = getWidth(), h = getHeight();
        if (w == tornW && h == tornH && r.left == tornRectL && r.top == tornRectT
                && r.right == tornRectR && r.bottom == tornRectB) {
            return;
        }
        tornW = w;
        tornH = h;
        tornRectL = r.left;
        tornRectT = r.top;
        tornRectR = r.right;
        tornRectB = r.bottom;
        tornEdge.reset();
        buildTornPath(tornEdge, r, 4242L, 3.5f, 16f);
        RectF paper = new RectF(r);
        paper.inset(7, 7);
        tornPaper.reset();
        // smaller amplitude than tornEdge, and independently seeded — kept
        // low so the two outlines (edge amplitude 3.5, 7px apart at rest)
        // can't wander close enough to pinch the dark border to ~0px
        buildTornPath(tornPaper, paper, 4243L, 1.5f, 16f);
    }

    /** Base parchment sheet (dark edge + paper fill) only, drawn before the map so the aging overlay below can sit on top of it. */
    private void drawParchmentBase(Canvas c, RectF r) {
        buildTornPaths(r);
        fill.setShader(null);
        fill.setColor(PAPER_EDGE);
        c.drawPath(tornEdge, fill);
        fill.setColor(PAPER);
        c.drawPath(tornPaper, fill);
    }

    /**
     * Aged-paper overlay (edge vignette, ink speckles, inner frame line),
     * drawn ON TOP of the map/title/marker so the whole parchment — map
     * included — reads as ink on aged paper rather than a clean printout.
     */
    private void drawParchmentOverlay(Canvas c, RectF r) {
        RectF paper = new RectF(r);
        paper.inset(7, 7);
        buildTornPaths(r);
        // aged vignette toward the edges (strengthened so it reads through the map)
        fill.setShader(new RadialGradient(paper.centerX(), paper.centerY(),
                Math.max(paper.width(), paper.height()) * 0.62f,
                new int[]{Color.TRANSPARENT, Color.argb(90, 60, 40, 10)},
                null, Shader.TileMode.CLAMP));
        c.drawPath(tornPaper, fill);
        fill.setShader(null);
        // speckles
        buildSpeckles(getWidth(), getHeight());
        fill.setColor(Color.argb(34, 80, 55, 20));
        c.save();
        c.clipPath(tornPaper);
        c.drawPath(speckles, fill);
        c.restore();
        // inner ink frame line, hand-drawn-map vibe
        stroke.setStrokeWidth(2.5f);
        stroke.setColor(Color.argb(90, 96, 72, 40));
        RectF frame = new RectF(paper);
        frame.inset(14, 14);
        c.drawRoundRect(frame, 12, 12, stroke);
    }

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
    private void drawBattleContent(Canvas c, RectF parchment, PartySnapshot s, boolean live) {
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
        for (int i = 0; i < n; i++) {
            float rowTop = areaTop + i * rowH;
            PartySnapshot.Enemy e = s.enemies.get(i);
            float rawFrac = e.maxHp > 0 ? clamp01(e.curHp / (float) e.maxHp) : 0f;
            Float eased = live ? enemyBarFrac.get(i) : null;
            drawEnemyBar(c, e, i, barLeft, rowTop, barRight - barLeft, rowH * 0.62f,
                    eased != null ? eased : rawFrac);
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

    // Warm-gold accent used for the default-selected button highlight (see
    // drawCommandButton's highlighted param) -- distinct from the neutral
    // white pressed-feedback tint so the two read as different things even
    // if they ever briefly overlapped.
    private static final int DEFAULT_HIGHLIGHT_COLOR = Color.rgb(255, 224, 130);

    /** One command button: 9-sliced window texture (fallback: hand-drawn navy box), centered label, optional pressed-state overlay. */
    private void drawCommandButton(Canvas c, RectF box, String label, Bitmap winTex, boolean pressed) {
        drawCommandButton(c, box, label, winTex, pressed, false);
    }

    /**
     * Same as the 4-arg overload, plus {@code highlighted}: a subtle
     * brighter border + faint tint applied when true (and {@code pressed} is
     * false, so it never fights the pressed flash) -- purely cosmetic, carries
     * no targeting/injection meaning. Highlight source is the panel's own
     * {@link #commandSel}, not the game's cursor.
     */
    private void drawCommandButton(Canvas c, RectF box, String label, Bitmap winTex, boolean pressed, boolean highlighted) {
        if (winTex != null) {
            float destInset = Math.min(box.width(), box.height()) * 0.16f;
            drawNinePatch(c, winTex, ChronoAssets.getWindowTexInset(), box, destInset);
        } else {
            fill.setShader(null);
            fill.setColor(BOX_BORDER_OUT);
            c.drawRect(box, fill);
            RectF inner = new RectF(box);
            inner.inset(3, 3);
            fill.setColor(BOX_BORDER_IN);
            c.drawRect(inner, fill);
            inner.inset(2, 2);
            fill.setColor(BOX_BG);
            c.drawRect(inner, fill);
        }
        if (highlighted && !pressed) {
            fill.setShader(null);
            fill.setColor(Color.argb(50, Color.red(DEFAULT_HIGHLIGHT_COLOR),
                    Color.green(DEFAULT_HIGHLIGHT_COLOR), Color.blue(DEFAULT_HIGHLIGHT_COLOR)));
            c.drawRect(box, fill);
            stroke.setStrokeWidth(2.5f);
            stroke.setColor(Color.argb(210, Color.red(DEFAULT_HIGHLIGHT_COLOR),
                    Color.green(DEFAULT_HIGHLIGHT_COLOR), Color.blue(DEFAULT_HIGHLIGHT_COLOR)));
            RectF hb = new RectF(box);
            hb.inset(1.5f, 1.5f);
            c.drawRect(hb, stroke);
        }
        if (pressed) {
            fill.setShader(null);
            fill.setColor(Color.argb(100, 255, 255, 255));
            c.drawRect(box, fill);
        }
        setText(box.height() * 0.42f, Color.WHITE, true, Paint.Align.CENTER, true);
        c.drawText(label, box.centerX(), box.centerY() + box.height() * 0.15f, text);
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
        drawCommandButton(c, confirm, TARGET_LABELS[1], winTex, false);
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
        String name;
        if (snap.listKind == 1) {
            name = ChronoAssets.getItemName(row.id);
        } else {
            name = (names != null && row.id >= 0 && row.id < names.length && !names[row.id].trim().isEmpty())
                    ? names[row.id] : ("#" + row.id);
        }
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
    private boolean advanceEnemyBarFractions() {
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
     * {@link #MOD_ROW_BUTTON_H_DP}-tall (>=48dp) touch target. Updates
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
     * The AUTO chip's rect for this parchment: {@link #MOD_ROW_BUTTON_H_DP}
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
     * {@link #MOD_ROW_BUTTON_H_DP} tall, 84dp wide, flush with the
     * parchment's top-right corner at the same 24px inset the gear chip
     * uses top-left, and vertically centred on the gear chip's slot so the
     * two read as one row outside battle. Shared by {@link
     * #drawFastForwardBadge}, {@link #autoChipRect} (which hangs off its
     * left edge) and {@link #drawBattleContent} (which pushes the enemy HP
     * rows below it).
     */
    private RectF ffBadgeRect(RectF parchment) {
        float chipH = dp(MOD_ROW_BUTTON_H_DP);
        float chipW = dp(84f);
        float right = parchment.right - 24f;
        float top = parchment.top + 24f + (GEAR_CHIP_SIZE - chipH) / 2f;
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
     * Settings gear chip in the parchment's top-left corner, inset just
     * inside the aged-paper overlay's ink frame line (see {@link
     * #drawParchmentOverlay}). Drawn only outside battle (see {@link
     * #onDraw}); tapping it (see {@link #onTouchEvent}) enters {@link
     * #settingsMode}.
     */
    private void drawGearChip(Canvas c, RectF parchment) {
        drawGearChip(c, parchment.left + 24f, parchment.top + 24f);
    }

    /**
     * CT-style menu window chip ({@link #GEAR_CHIP_SIZE} square, top-left
     * corner at ({@code left}, {@code top})) holding the pixel-art gear
     * sprite, centered and upscaled by the largest integer factor that fits
     * inside the window border. Uses the game's own window texture when
     * loaded ({@link ChronoAssets#getWindowTex}), else the same navy
     * double-border fallback as {@link #drawCommandButton}, so the chip
     * matches the battle command buttons exactly. Sets {@link #gearHitBox}
     * to the chip plus {@link #GEAR_HIT_PAD} slop. Shared by the parchment
     * corner (above) and the pre-load wordmark screen ({@link #drawWordmark}).
     */
    private void drawGearChip(Canvas c, float left, float top) {
        RectF box = new RectF(left, top, left + GEAR_CHIP_SIZE, top + GEAR_CHIP_SIZE);
        gearHitBox.set(box);
        gearHitBox.inset(-GEAR_HIT_PAD, -GEAR_HIT_PAD);

        Bitmap winTex = ChronoAssets.getWindowTex();
        float destInset = GEAR_CHIP_SIZE * 0.16f;
        if (winTex != null) {
            drawNinePatch(c, winTex, ChronoAssets.getWindowTexInset(), box, destInset);
        } else {
            fill.setShader(null);
            fill.setColor(BOX_BORDER_OUT);
            c.drawRect(box, fill);
            RectF inner = new RectF(box);
            inner.inset(3, 3);
            fill.setColor(BOX_BORDER_IN);
            c.drawRect(inner, fill);
            inner.inset(2, 2);
            fill.setColor(BOX_BG);
            c.drawRect(inner, fill);
        }

        Bitmap sprite = gearSprite();
        int cells = GEAR_SPRITE.length; // the 1px shadow column/row hangs off the sprite's own grid
        int scale = Math.max(1, (int) Math.floor((GEAR_CHIP_SIZE - 2f * destInset - 4f) / cells));
        // snap to whole device pixels so every sprite pixel lands on an exact
        // scale x scale block -- a fractional origin would smear the edges
        float sx = Math.round(box.centerX() - cells * scale / 2f);
        float sy = Math.round(box.centerY() - cells * scale / 2f);
        RectF dst = new RectF(sx, sy, sx + sprite.getWidth() * scale, sy + sprite.getHeight() * scale);
        c.drawBitmap(sprite, null, dst, spritePaint);
    }

    /**
     * Returns (building on first use) the gear sprite bitmap from {@link
     * #GEAR_SPRITE}: body pixels in {@link #GEAR_BODY}, edge pixels whose
     * top or left neighbour is empty lit to {@link #GEAR_LIT}, edge pixels
     * whose bottom or right neighbour is empty shaded to {@link
     * #GEAR_SHADE} (pixels that are both stay body), plus a 1px {@link
     * #BOX_BG_DARK} drop shadow offset (+1, +1) -- hence the bitmap is one
     * pixel larger than the mask in each direction.
     */
    private Bitmap gearSprite() {
        if (gearSprite != null) return gearSprite;
        int n = GEAR_SPRITE.length;
        Bitmap bmp = Bitmap.createBitmap(n + 1, n + 1, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (gearOn(x, y)) bmp.setPixel(x + 1, y + 1, BOX_BG_DARK);
            }
        }
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!gearOn(x, y)) continue;
                boolean lit = !gearOn(x, y - 1) || !gearOn(x - 1, y);
                boolean dark = !gearOn(x, y + 1) || !gearOn(x + 1, y);
                int color = lit && !dark ? GEAR_LIT : (dark && !lit ? GEAR_SHADE : GEAR_BODY);
                bmp.setPixel(x, y, color);
            }
        }
        gearSprite = bmp;
        return bmp;
    }

    /** True when ({@code x}, {@code y}) is a body pixel of {@link #GEAR_SPRITE}; out-of-range coordinates are empty. */
    private static boolean gearOn(int x, int y) {
        if (y < 0 || y >= GEAR_SPRITE.length) return false;
        String row = GEAR_SPRITE[y];
        return x >= 0 && x < row.length() && row.charAt(x) == 'B';
    }

    /** Counts {@code area_minimap_*.png} files under {@code <filesDir>/ds_maps} for the settings screen's status row -- see {@link #drawSettingsScreen}. */
    private int countDsMaps() {
        File dir = new File(getContext().getFilesDir(), "ds_maps");
        File[] files = dir.listFiles((d, name) -> name.startsWith("area_minimap_") && name.endsWith(".png"));
        return files != null ? files.length : 0;
    }

    /** Builds the "DS maps: ..." status line's value half -- see {@link #setImportStatus} for the states this reflects. */
    private String importStatusText() {
        if (importing) {
            if ("unzipping".equals(importStage)) {
                // done/total here are MB copied so far / total MB (from setImportStatus's
                // "unzipping" stage), not the maps-processed counts the default line below
                // uses -- shown as its own "unzipping... N/M MB" line instead. Total can be 0
                // if the zip entry's size wasn't known up front; fall back to showing bytes
                // copied twice rather than a misleading "/0".
                int total = importTotal > 0 ? importTotal : importDone;
                return "unzipping... " + importDone + "/" + total + " MB";
            }
            StringBuilder sb = new StringBuilder("importing... ").append(importDone).append('/').append(importTotal);
            if (importStage != null && !importStage.isEmpty()) sb.append(' ').append(importStage);
            return sb.toString();
        }
        if (importError != null) return "error: " + importError;
        int n = countDsMaps();
        return n > 0 ? (n + " maps") : "not imported";
    }

    /** Status text for the "SNES save: ..." row -- see {@link #setSaveImportStatus}. */
    private String saveImportStatusText() {
        if (savingImport) return "importing...";
        if (saveImportMessage != null) {
            return (saveImportError ? "error: " : "") + saveImportMessage;
        }
        return "not imported";
    }

    /** Counts {@code *.png} files directly under {@code <filesDir>/orig_art} for the settings screen's "Original art" status row -- see {@link #drawSettingsScreen}. Mirrors {@link #countDsMaps}; this is the same directory {@link org.cocos2dx.cpp.AppActivity}'s OrigArtRebuilder and MapchipRebuilder write into and scanOrigArtReplacements() scans, so the count covers character sheets and field chip sheets together. */
    private int countOrigArtSheets() {
        File dir = new File(getContext().getFilesDir(), "orig_art");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".png"));
        return files != null ? files.length : 0;
    }

    /** Builds the "Original art: ..." status line's value half -- mirrors {@link #importStatusText}'s building/error/idle shape (see {@link #setOrigArtStatus}). */
    private String origArtStatusText() {
        if (origArtBuilding) {
            String what = (origArtPhase != null && !origArtPhase.isEmpty()) ? (origArtPhase + " ") : "";
            return "building " + what + origArtDone + "/" + origArtTotal;
        }
        if (origArtError != null) return "error: " + origArtError;
        int n = countOrigArtSheets();
        return n > 0 ? (n + " sheets") : "not built";
    }

    /** Builds the settings screen's one-line "World maps: N/8 rendered" status row -- how many of the game's overworld/era ids (see {@link PartySnapshot#worldEra}, {@link WorldMapRenderer#worldCount()}) have an on-device-rendered PNG on disk, per {@link ChronoAssets#capturedWorldMapEras()}. */
    private String worldMapStatusText() {
        int rendered = ChronoAssets.capturedWorldMapEras().size();
        return "World maps: " + rendered + "/" + WorldMapRenderer.worldCount() + " rendered";
    }

    /**
     * The settings screen: a full-screen parchment sheet (nearly the whole
     * view, small torn-paper border) split into a left tab rail -- one entry
     * per {@link #SETTINGS_PAGES}, plus a trailing Back button -- and a
     * content area to its right holding that page's title/subtitle/body.
     * Replaces the old prev/next-arrow pagination entirely; tapping (or
     * d-pad-confirming) a tab jumps {@link #settingsPage} directly. Buttons
     * mid-operation (importing / building) are dimmed and get no hit box.
     * Every settings hit box -- and the d-pad focus lists -- is cleared/
     * rebuilt up front so a control on another page can never catch a tap or
     * a stale focus index. Drawn instead of everything else whenever
     * {@link #settingsMode} is true -- see {@link #onDraw} -- including
     * before any party exists.
     */
    private void drawSettingsScreen(Canvas c) {
        int w = getWidth(), h = getHeight();
        // Full-screen sheet: fills the view minus a small torn-paper border,
        // rather than the map-panel-sized rect the normal panel uses.
        float pad = w * 0.012f;
        RectF parchment = new RectF(pad, pad, w - pad, h - pad);
        drawParchmentBase(c, parchment);

        importButtonHitBox.setEmpty();
        saveImportButtonHitBox.setEmpty();
        pixelGraphicsHitBox.setEmpty();
        fogToggleHitBox.setEmpty();
        fogResetHitBox.setEmpty();
        origArtButtonHitBox.setEmpty();
        ffSpeedHitBox.setEmpty();
        ffModeHitBox.setEmpty();
        ffHitBox.setEmpty();
        modImportButtonHitBox.setEmpty();
        for (int i = 0; i < MAX_MOD_ROWS; i++) {
            modRowHitBoxes[i].setEmpty();
            modExpanderHitBoxes[i].setEmpty();
        }
        modRowCount = 0;
        modListViewport.setEmpty();
        modScrollUpHitBox.setEmpty();
        modScrollDownHitBox.setEmpty();
        modScrollbarThumb.setEmpty();
        modContentHeight = 0f;
        modViewportHeight = 0f;
        for (int i = 0; i < SETTINGS_PAGES.length; i++) settingsTabHitBoxes[i].setEmpty();
        settingsBackHitBox.setEmpty();
        settingsFocusRects.clear();
        settingsFocusActivate.clear();
        settingsFocusStepLeft.clear();
        settingsFocusStepRight.clear();

        int page = Math.floorMod(settingsPage, SETTINGS_PAGES.length);
        int inkDim = Color.argb(200, Color.red(INK), Color.green(INK), Color.blue(INK));
        Bitmap winTex = ChronoAssets.getWindowTex();

        // --- Left tab rail -----------------------------------------------
        // ~260px wide at the 1240px-wide reference resolution -- scaled by
        // view width so it holds its proportion on any secondary display.
        // The rail rect is inset inside the parchment's inner border line
        // (drawParchmentOverlay's "frame" -- 7px paper inset + 14px frame
        // inset = 21px total, both literal device px like the rest of that
        // border) by a further ~24px on all sides, so neither the rail's
        // tint nor the "Settings" title/tab text ever touches that line or
        // the torn edge beyond it. Tab labels and the Back button get their
        // own ~32px left padding inside the rail (tabLeftPad below).
        float innerBorderInset = 21f;
        float railPadding = 24f;
        float tabLeftPad = 32f;
        float railW = parchment.width() * (260f / 1240f);
        RectF rail = new RectF(parchment.left + innerBorderInset + railPadding,
                parchment.top + innerBorderInset + railPadding,
                parchment.left + innerBorderInset + railPadding + railW,
                parchment.bottom - innerBorderInset - railPadding);

        float contentLeft = rail.right + w * 0.03f;
        float contentRight = parchment.right - w * 0.03f;
        float left = contentLeft;
        float textW = contentRight - contentLeft;

        c.save();
        c.clipPath(tornPaper);

        // Rail background: a faint tint distinguishing it from the content
        // area, same paper underneath.
        fill.setShader(null);
        fill.setColor(Color.argb(28, 0, 0, 0));
        c.drawRect(rail, fill);

        // "Settings" title must fit inside the rail -- shrink the text size
        // until it does rather than let it overflow the rail's right edge.
        // setText resets the typeface to plain MONOSPACE every call (see its
        // doc), so applyTypeface(SERIF/BOLD) must come AFTER each setText,
        // both while measuring here and for the final draw below.
        float titleAvailW = rail.width() - 2 * tabLeftPad;
        float settingsTitleSize = h * 0.05f;
        setText(settingsTitleSize, INK, true, Paint.Align.LEFT, false);
        applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        while (settingsTitleSize > h * 0.02f && text.measureText("Settings") > titleAvailW) {
            settingsTitleSize -= h * 0.002f;
            setText(settingsTitleSize, INK, true, Paint.Align.LEFT, false);
            applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        }
        c.drawText("Settings", rail.left + tabLeftPad, rail.top + h * 0.09f, text);

        float tabTop = rail.top + h * 0.15f;
        float tabH = h * 0.075f;
        for (int i = 0; i < SETTINGS_PAGES.length; i++) {
            RectF tabBox = new RectF(rail.left, tabTop, rail.right, tabTop + tabH);
            boolean selected = i == page;
            if (selected) {
                // Alpha bumped up from a first pass (60) since drawParchmentOverlay's
                // vignette/speckle pass, drawn on top of the whole sheet afterwards,
                // otherwise washes out the selected tint -- the navy marker bar
                // (opaque) still reads clearly either way.
                fill.setColor(Color.argb(95, 255, 255, 250));
                c.drawRect(tabBox, fill);
                fill.setColor(BOX_BG); // navy marker bar, left edge
                c.drawRect(tabBox.left, tabBox.top, tabBox.left + w * 0.006f, tabBox.bottom, fill);
            }
            setText(h * 0.032f, selected ? INK : inkDim, selected, Paint.Align.LEFT, false);
            applyTypeface(Typeface.create(Typeface.SERIF, selected ? Typeface.BOLD : Typeface.NORMAL));
            c.drawText(SETTINGS_PAGES[i], rail.left + tabLeftPad, tabBox.centerY() + h * 0.011f, text);
            // Tab hit box (and its d-pad focus ring, drawn around this same
            // rect at the bottom of this method) stays inside the rail
            // rect itself -- both share rail.left/rail.right exactly.
            settingsTabHitBoxes[i].set(tabBox);
            tabTop += tabH;
        }

        // Back button (rail-owned; drawn as a real button, deferred past the
        // clip release below like every other settings button). Anchored to
        // rail.bottom (not parchment.bottom) so it stays inside the now-
        // inset rail rect.
        RectF backBtn = new RectF(rail.left + tabLeftPad, rail.bottom - h * 0.07f,
                rail.right - tabLeftPad, rail.bottom - h * 0.005f);

        // Page title + one dim subtitle line, then the page body.
        setText(h * 0.048f, INK, true, Paint.Align.LEFT, false);
        applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        float y = parchment.top + h * 0.075f;
        c.drawText(SETTINGS_PAGES[page], left, y, text);
        y += h * 0.032f;
        String subtitle;
        switch (page) {
            case 0: subtitle = "Chrono Trigger DS room maps and SNES/DS save import."; break;
            case 1: subtitle = "Pixel-perfect rendering and original sprite/chip art."; break;
            case 2: subtitle = "Dungeon fog of war and overworld map rendering."; break;
            case 3: subtitle = "Speed up the whole game, held or toggled."; break;
            default: subtitle = "Alphabetical order, first mod wins a conflict."; break;
        }
        setText(h * 0.022f, inkDim, false, Paint.Align.LEFT, false);
        c.drawText(subtitle, left, y, text);
        y += h * 0.045f;

        // Buttons are drawn after the clip is released (they sit on top of
        // the parchment overlay); the text pass below only records where
        // each one goes.
        RectF btnA = null, btnB = null;
        String labelA = null, labelB = null;
        float btnW = Math.min(textW, w * 0.34f);
        float btnH = h * 0.07f;

        switch (page) {
            case 0: { // Imports
                y = drawSettingsHeading(c, "DS maps: " + importStatusText(), left, y, h);
                y = drawSettingsBody(c, "Room maps are available if you can provide the "
                        + "Chrono Trigger DS ROM (.nds or .zip).", left, y, textW, h, inkDim);
                btnA = new RectF(left, y + h * 0.01f, left + btnW, y + h * 0.01f + btnH);
                labelA = "Import DS ROM...";
                final boolean importBusyA = importing;
                settingsFocusRects.add(btnA);
                settingsFocusActivate.add(() -> {
                    if (!importBusyA && settingsHost != null) settingsHost.requestRomImport();
                });
                settingsFocusStepLeft.add(null);
                settingsFocusStepRight.add(null);
                y = btnA.bottom + h * 0.06f;

                y = drawSettingsHeading(c, "SNES/DS save:", left, y, h);
                y = drawSettingsBody(c, saveImportStatusText(), left, y, textW, h, INK);
                y = drawSettingsBody(c, "Copies a save from a SNES (.srm) or DS (.sav/.dst/"
                        + ".duc/.dsv) file into one of this game's save slots.",
                        left, y, textW, h, inkDim);
                btnB = new RectF(left, y + h * 0.01f, left + btnW, y + h * 0.01f + btnH);
                labelB = "Import SNES/DS save...";
                final boolean saveBusyB = savingImport;
                settingsFocusRects.add(btnB);
                settingsFocusActivate.add(() -> {
                    if (!saveBusyB && settingsHost != null) settingsHost.requestSaveImport();
                });
                settingsFocusStepLeft.add(null);
                settingsFocusStepRight.add(null);
                break;
            }
            case 1: { // Graphics
                y = drawSettingsHeading(c, "Pixel graphics", left, y, h);
                y = drawSettingsBody(c, "Nearest-neighbour texture filtering for crisp, "
                        + "unsmoothed sprites. Full effect after a game restart.",
                        left, y, textW, h, inkDim);
                btnA = new RectF(left, y + h * 0.01f, left + btnW, y + h * 0.01f + btnH);
                labelA = "Pixel graphics: " + (pixelGraphicsOn ? "On" : "Off");
                settingsFocusRects.add(btnA);
                settingsFocusActivate.add(() -> {
                    pixelGraphicsOn = !pixelGraphicsOn;
                    GameState.setPixelGraphicsPref(getContext(), pixelGraphicsOn);
                    if (settingsHost != null) settingsHost.onPixelGraphicsChanged(pixelGraphicsOn);
                });
                settingsFocusStepLeft.add(null);
                settingsFocusStepRight.add(null);
                y = btnA.bottom + h * 0.06f;

                y = drawSettingsHeading(c, "Original art: " + origArtStatusText(), left, y, h);
                y = drawSettingsBody(c, "Rebuilds character sprites and field chips from the "
                        + "game's own 1x art. Only applies when Pixel graphics is On.",
                        left, y, textW, h, inkDim);
                btnB = new RectF(left, y + h * 0.01f, left + btnW, y + h * 0.01f + btnH);
                labelB = "Build original art";
                final boolean origArtBusyB = origArtBuilding;
                settingsFocusRects.add(btnB);
                settingsFocusActivate.add(() -> {
                    if (!origArtBusyB && settingsHost != null) settingsHost.requestOrigArtBuild();
                });
                settingsFocusStepLeft.add(null);
                settingsFocusStepRight.add(null);
                break;
            }
            case 2: { // Maps
                y = drawSettingsHeading(c, "Dungeon fog", left, y, h);
                y = drawSettingsBody(c, "Dungeon minimaps are revealed as you explore them, "
                        + "like on the DS. Towns and houses are always fully shown.",
                        left, y, textW, h, inkDim);
                btnA = new RectF(left, y + h * 0.01f, left + btnW, y + h * 0.01f + btnH);
                // An import made before the fog flag was exported has no fog
                // data: say so on the button instead of silently never fogging.
                labelA = fogOn && countDsMaps() > 0 && !AreaMapCalib.hasFogData()
                        ? "Dungeon fog: re-import ROM" : "Dungeon fog: " + (fogOn ? "On" : "Off");
                settingsFocusRects.add(btnA);
                settingsFocusActivate.add(() -> {
                    fogOn = !fogOn;
                    getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putBoolean(KEY_FOG_ON, fogOn).apply();
                });
                settingsFocusStepLeft.add(null);
                settingsFocusStepRight.add(null);
                y = btnA.bottom + h * 0.075f;

                y = drawSettingsHeading(c, worldMapStatusText(), left, y, h);
                drawSettingsBody(c, "Overworld maps are rendered from the game's own data "
                        + "the first time it runs.", left, y, textW, h, inkDim);
                break;
            }
            case 3: { // Speed (SPEED_PAGE)
                y = drawSettingsHeading(c, "Fast-forward", left, y, h);
                y = drawSettingsBody(c, "Speeds up the whole game -- field, world map and "
                        + "battle alike -- while held or toggled on. Sound effects and music "
                        + "tempo are unaffected.", left, y, textW, h, inkDim);
                btnA = new RectF(left, y + h * 0.01f, left + btnW, y + h * 0.01f + btnH);
                labelA = "Speed: " + (int) GameSpeed.getSpeed(getContext()) + "x";
                settingsFocusRects.add(btnA);
                settingsFocusActivate.add(() -> GameSpeed.cycleSpeed(getContext()));
                settingsFocusStepLeft.add(null);
                settingsFocusStepRight.add(null);
                y = btnA.bottom + h * 0.03f;

                btnB = new RectF(left, y + h * 0.01f, left + btnW, y + h * 0.01f + btnH);
                labelB = "Right trigger: "
                        + (GameSpeed.getMode(getContext()) == GameSpeed.Mode.HOLD ? "Hold" : "Toggle");
                settingsFocusRects.add(btnB);
                settingsFocusActivate.add(() -> GameSpeed.cycleMode(getContext()));
                settingsFocusStepLeft.add(null);
                settingsFocusStepRight.add(null);
                break;
            }
            default: { // Mods -- a curated catalog (tap to get) plus any installed/imported mods.
                java.util.List<ModRow> rows = buildModRows();
                java.util.List<ModDisplayRow> displayRows = buildDisplayRows(rows);
                // Row title/description text and the row Get/On/Off button
                // sizes below match the redesigned mock: a fixed ~150x64px
                // (at the 1240x1080 reference resolution) button column for
                // parent rows, and a much wider ~520x64px "picker" button for
                // an expanded option row (title + centered choice label +
                // ◂/▸ step glyphs) -- see pickerW below. Both still respect a
                // real minimum touch height via dp(). Each row's height is
                // the taller of that button's height and the actual wrapped
                // text stack (textStackH, from the real number of title/
                // description lines) -- see buildDisplayRows for how a
                // grouped mod's expanded option rows flatten into this same
                // per-row loop.
                float boxH = Math.max(dp(MOD_ROW_BUTTON_H_DP), h * (64f / 1080f));
                float minRowH = h * 0.02f + boxH;
                // Parent rows: fixed ~150px On/Off/Get button. Option rows:
                // much wider ~520px picker (title + ◂ current-choice ▸).
                float toggleW = w * (150f / 1240f);
                float pickerW = w * (520f / 1240f);
                float modsBtnH = h * 0.055f;
                // Leave room to the right of the button/picker column for the
                // scrollbar track (trackX0..trackX1 below, contentRight-0.022w
                // .. -0.006w) so a wide On/Off/Get button can never sit under it.
                float btnRight = contentRight - w * 0.03f;
                // The list viewport is a FIXED rect (listTop..maxY) regardless
                // of row count, so the footer (Import button + status text)
                // below it never moves -- only the row list inside scrolls
                // (see modScrollY).
                float listTop = y;
                // Footer text budget is 2 lines (message + restart note, or
                // just the restart note -- see below), which can be taller
                // than the Import button itself.
                float footerStatusLineH = h * STATUS_LINE_SIZE_FRAC * BODY_LINE_H_RATIO;
                float footerH = Math.max(modsBtnH, footerStatusLineH * 2f + footerStatusLineH * 0.8f);
                float footerReserve = footerH + h * 0.025f /* gap above footer */;
                float maxY = parchment.bottom - h * 0.03f - footerReserve;
                float viewportH = Math.max(0f, maxY - listTop);

                // Row title/description column width -- the wrap width for
                // both. Option rows are indented under their parent's title
                // and sit next to the much wider picker button, so they get
                // their own (narrower) max width.
                float rowTextMaxW = textW - toggleW - w * 0.02f;
                float optionIndent = w * 0.04f;
                float optionTextMaxW = Math.max(0f, textW - pickerW - optionIndent - w * 0.02f);

                // Text sizes -- title ~34px, description ~24px, option title
                // ~28px at the 1240x1080 reference resolution. Set once here
                // so titleLineH/summaryLineH (Paint.ascent()/descent() at
                // THESE sizes) match what the draw loop below re-applies per
                // row via setText.
                float titleSize = h * (34f / 1080f);
                float summarySize = h * (24f / 1080f);
                // Notes render at the SAME size as the description (item 5)
                // and wrap onto up to 2 lines instead of a single ellipsized
                // line -- see notesLines below and wrapLines' doc.
                float expanderSize = h * 0.019f;
                // Option row title (left label, e.g. "Font"/"Art Icons") --
                // single fixed size, wrapped onto up to 2 lines (see the
                // option-row branch below). The PICKER label (the current
                // choice, drawn inside the ◂/▸ button) uses its own 3-size
                // fit instead -- see pickerLabelSizes below.
                float optionTitleSize = h * (28f / 1080f);
                float[] pickerLabelSizes = { h * (28f / 1080f), h * (24f / 1080f), h * (21f / 1080f) };
                // File-count/conflicts suffix, drawn UNDER the row's button
                // instead of above/beside the description.
                float suffixSize = h * 0.014f;
                float suffixGap = h * 0.004f;
                setText(titleSize, INK, true, Paint.Align.LEFT, false);
                applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                float titleLineH = text.descent() - text.ascent();
                setText(summarySize, inkDim, false, Paint.Align.LEFT, false);
                float summaryLineH = text.descent() - text.ascent();
                float notesLineH = summaryLineH;
                setText(suffixSize, inkDim, false, Paint.Align.CENTER, false);
                float suffixLineH = text.descent() - text.ascent();
                setText(expanderSize, INK, true, Paint.Align.LEFT, false);
                float expanderLineH = text.descent() - text.ascent();
                float rowTopPad = h * 0.006f;
                float titleGap = h * 0.006f;   // title block -> description block
                float summaryGap = h * 0.006f; // description block -> notes line
                float optionsGap = h * 0.006f; // notes/description/title block -> Options ▾/▴ line
                float rowBottomPad = h * 0.006f;

                // Word-wrap title (up to 2 lines) and description (up to 2
                // lines, ellipsized after that -- see wrapLines) against
                // rowTextMaxW now, up front, so both the row-height pass
                // below and the draw loop further down use the exact same
                // wrapped text. displayRows is already flattened (see
                // buildDisplayRows), so every parent AND every expanded
                // option-group row gets its own entry here.
                int rowCount = Math.min(displayRows.size(), MAX_MOD_ROWS);
                String[][] titleLines = new String[rowCount][];
                String[][] summaryLines = new String[rowCount][];
                String[][] notesLines = new String[rowCount][];
                String[][] optionTitleLines = new String[rowCount][];
                float[] rowHeights = new float[rowCount];
                // Maps each mods-page focus-list index (before the trailing
                // Import button, added after this switch) to the display-row
                // index it belongs to -- a plain row/picker contributes one
                // focus entry (its button/picker), an expandable parent row
                // contributes two (the "Options ▾/▴" line, then its button)
                // -- see the draw loop below, which appends
                // settingsFocusRects/Activate/StepLeft/Right in this SAME
                // order so the two stay in lockstep.
                java.util.List<Integer> focusRowIndex = new java.util.ArrayList<>();
                float contentH = 0f;
                for (int i = 0; i < rowCount; i++) {
                    ModDisplayRow dr = displayRows.get(i);
                    if (dr.parent != null) {
                        ModRow row = dr.parent;
                        setText(titleSize, INK, true, Paint.Align.LEFT, false);
                        applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                        titleLines[i] = wrapLines(row.title, rowTextMaxW, 2);
                        setText(summarySize, inkDim, false, Paint.Align.LEFT, false);
                        summaryLines[i] = wrapLines(row.summary, rowTextMaxW, 2);
                        boolean hasNotes = row.notes != null && !row.notes.isEmpty();
                        // Notes render at summarySize (same as the
                        // description) and wrap onto up to 2 lines -- see
                        // item 5: never a single ellipsized line.
                        notesLines[i] = hasNotes ? wrapLines(row.notes, rowTextMaxW, 2) : new String[0];

                        float textStackH = rowTopPad
                                + titleLines[i].length * titleLineH
                                + (summaryLines[i].length > 0 ? titleGap + summaryLines[i].length * summaryLineH : 0f)
                                + (notesLines[i].length > 0 ? summaryGap + notesLines[i].length * notesLineH : 0f)
                                + (dr.expandable ? optionsGap + expanderLineH : 0f)
                                + rowBottomPad;
                        // The button+suffix block is centered as one unit
                        // (see the draw loop below) -- its own minimum height
                        // must include the suffix line when present so it's
                        // never drawn past the row's bottom edge.
                        float buttonBlockH = boxH + (row.suffix != null ? suffixGap + suffixLineH : 0f);
                        rowHeights[i] = Math.max(textStackH, h * 0.02f + buttonBlockH);
                        if (dr.expandable) focusRowIndex.add(i); // "Options ▾/▴" line
                        focusRowIndex.add(i); // the row's own button
                    } else {
                        // Option row title (left label): shared-prefix
                        // stripped already (dr.optionDisplayTitle -- see
                        // buildDisplayRows), wrapped onto up to 2 lines
                        // (ellipsized only if it still overflows -- see
                        // wrapLines) instead of a single ellipsized line.
                        setText(optionTitleSize, INK, false, Paint.Align.LEFT, false);
                        optionTitleLines[i] = wrapLines(dr.optionDisplayTitle, optionTextMaxW, 2);
                        float optionTitleLineH = text.descent() - text.ascent();
                        float optionTextStackH = rowTopPad
                                + Math.max(1, optionTitleLines[i].length) * optionTitleLineH
                                + rowBottomPad;
                        rowHeights[i] = Math.max(minRowH, optionTextStackH);
                        focusRowIndex.add(i); // the picker
                    }
                    contentH += rowHeights[i];
                }

                // D-pad focus follow: when the content cursor is on a mod row
                // (index < focusRowIndex.size() -- see the trailing
                // Import-button entry added after this switch), scroll its
                // OWN row (via focusRowIndex, not the raw index) fully into
                // view BEFORE clamping/drawing below, so the per-row loop
                // always finds it rowVisible and the eventual focus ring
                // never lands on a partially-clipped row.
                int rowsFocusable = focusRowIndex.size();
                int modsFocusableCount = rowsFocusable + 1; // rows/expanders + the Import button
                if (settingsFocusArea == SETTINGS_FOCUS_CONTENT) {
                    if (settingsContentFocus >= modsFocusableCount) {
                        settingsContentFocus = Math.max(0, modsFocusableCount - 1);
                    }
                    if (settingsContentFocus < rowsFocusable) {
                        int rowIdx = focusRowIndex.get(settingsContentFocus);
                        float acc = 0f;
                        for (int k = 0; k < rowIdx; k++) acc += rowHeights[k];
                        float focusTop = acc, focusBottom = acc + rowHeights[rowIdx];
                        if (focusTop < modScrollY) modScrollY = focusTop;
                        else if (focusBottom > modScrollY + viewportH) modScrollY = focusBottom - viewportH;
                    }
                }

                float maxScroll = Math.max(0f, contentH - viewportH);
                // Clamp here (not only in onTouchEvent) since content height
                // can change out from under a held offset with no touch event
                // at all -- a catalog refresh, an import finishing, a toggle
                // rescan -- see setModsList/setModCatalog.
                modScrollY = Math.max(0f, Math.min(maxScroll, modScrollY));
                modContentHeight = contentH;
                modViewportHeight = viewportH;
                modListViewport.set(contentLeft, listTop, contentRight, maxY);

                c.save();
                c.clipRect(modListViewport);
                float rowY = listTop - modScrollY;
                for (int i = 0; i < rowCount; i++) {
                    float rowH = rowHeights[i];
                    float rowTop = rowY;
                    float rowBottom = rowY + rowH;
                    rowY += rowH;
                    if (rowBottom <= listTop || rowTop >= maxY) continue; // fully scrolled out
                    ModDisplayRow dr = displayRows.get(i);
                    // A row only gets a live hit box when it's FULLY inside
                    // the viewport -- a partially-clipped row at the top/
                    // bottom edge is visible but not tappable, so a drag that
                    // stops mid-scroll can never fire a half-seen button.
                    boolean rowVisible = rowTop >= listTop && rowBottom <= maxY;

                    // Thin divider above every top-level mod row (not before
                    // an option row, and never above the very first row).
                    if (dr.parent != null && i > 0) {
                        stroke.setStrokeWidth(1.5f);
                        stroke.setColor(Color.argb(90, 96, 72, 40));
                        c.drawLine(contentLeft, rowTop, contentRight, rowTop, stroke);
                    }

                    if (dr.parent != null) {
                        ModRow row = dr.parent;
                        float blockTop = rowTop + rowTopPad;

                        setText(titleSize, row.installed && !row.enabled ? inkDim : INK, true, Paint.Align.LEFT, false);
                        applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                        float baseline = blockTop - text.ascent();
                        for (String line : titleLines[i]) {
                            c.drawText(line, left, baseline, text);
                            baseline += titleLineH;
                        }
                        blockTop += titleLines[i].length * titleLineH;

                        if (summaryLines[i].length > 0) {
                            blockTop += titleGap;
                            setText(summarySize, inkDim, false, Paint.Align.LEFT, false);
                            baseline = blockTop - text.ascent();
                            for (String line : summaryLines[i]) {
                                c.drawText(line, left, baseline, text);
                                baseline += summaryLineH;
                            }
                            blockTop += summaryLines[i].length * summaryLineH;
                        }

                        if (notesLines[i].length > 0) {
                            blockTop += summaryGap;
                            setText(summarySize, inkDim, false, Paint.Align.LEFT, false);
                            baseline = blockTop - text.ascent();
                            for (String line : notesLines[i]) {
                                c.drawText(line, left, baseline, text);
                                baseline += notesLineH;
                            }
                            blockTop += notesLines[i].length * notesLineH;
                        }

                        // "Options ▾/▴" expander -- own tap target
                        // (modExpanderHitBoxes[i], separate from the row's
                        // own On/Off button below) that just flips this
                        // group's membership in modExpandedGroups; see
                        // onTouchEvent. Only drawn for a row whose group has
                        // at least one option group (dr.expandable). Also its
                        // own d-pad focus entry (added first, ahead of the
                        // row's button -- see focusRowIndex above) so a
                        // controller-only user can reach it at all. The hit
                        // box itself is full-row-width and at least
                        // MOD_ROW_BUTTON_H_DP tall (centered on the text's
                        // own line, clamped inside this row) rather than a
                        // tight box around the short label -- device
                        // feedback was that the label-sized box was hard to
                        // land a tap on.
                        RectF expBox = modExpanderHitBoxes[i];
                        if (dr.expandable) {
                            blockTop += optionsGap;
                            boolean expanded = modExpandedGroups.contains(dr.expandKey);
                            String label = "Options " + (expanded ? "▴" : "▾");
                            setText(expanderSize, INK, true, Paint.Align.LEFT, false);
                            c.drawText(label, left, blockTop - text.ascent(), text);
                            float expMinH = dp(MOD_ROW_BUTTON_H_DP);
                            float expCenterY = blockTop + expanderLineH / 2f;
                            float expBottom = Math.min(rowBottom, Math.max(blockTop + expanderLineH,
                                    expCenterY + expMinH / 2f));
                            float expTop = Math.max(rowTop, expBottom - expMinH);
                            // Right edge stops short of the row's own On/Off
                            // button column (checked AFTER this hit box in
                            // onTouchEvent) so widening this box can never
                            // swallow a tap meant for that button.
                            expBox.set(contentLeft, expTop, btnRight - toggleW - w * 0.01f, expBottom);
                            modExpanderKey[i] = dr.expandKey;
                            if (modsImporting || !rowVisible) expBox.setEmpty();
                            final String expKey = dr.expandKey;
                            settingsFocusRects.add(new RectF(expBox));
                            settingsFocusActivate.add(() -> {
                                if (expKey == null) return;
                                if (!modExpandedGroups.remove(expKey)) modExpandedGroups.add(expKey);
                                invalidate();
                            });
                            settingsFocusStepLeft.add(null);
                            settingsFocusStepRight.add(null);
                        } else {
                            expBox.setEmpty();
                        }

                        // Button column: fixed ~150x64px at the right edge.
                        // The button+suffix block (see buttonBlockH above) is
                        // centered as one unit so a row with a suffix line
                        // always has real clearance below the button.
                        float buttonBlockH = boxH + (row.suffix != null ? suffixGap + suffixLineH : 0f);
                        RectF box = modRowHitBoxes[i];
                        float boxTop = rowTop + (rowH - buttonBlockH) / 2f;
                        box.set(btnRight - toggleW, boxTop, btnRight, boxTop + boxH);

                        String rowLabel = row.catalogId != null ? "Get" : (row.enabled ? "On" : "Off");
                        drawCommandButton(c, box, rowLabel, winTex, false);

                        // File count / conflicts, in small dim text UNDER the
                        // button (moved off the description line's baseline).
                        if (row.suffix != null) {
                            setText(suffixSize, inkDim, false, Paint.Align.CENTER, false);
                            c.drawText(row.suffix, box.centerX(), box.bottom + suffixGap - text.ascent(), text);
                        }

                        boolean rowActionable = !modsImporting;
                        if (modsImporting) {
                            fill.setShader(null);
                            fill.setColor(Color.argb(150, 0, 0, 0));
                            c.drawRect(box, fill);
                            box.setEmpty();
                        } else if (!rowVisible) {
                            box.setEmpty();
                        }
                        modRowDirName[i] = row.dirName;
                        modRowCatalogId[i] = row.catalogId;
                        modRowEnabled[i] = row.enabled;
                        modRowIsOptionCycle[i] = false;

                        final String actDir = row.dirName;
                        final String actCatalogId = row.catalogId;
                        final boolean actEnabled = row.enabled;
                        final boolean actOk = rowActionable;
                        settingsFocusRects.add(new RectF(box));
                        settingsFocusActivate.add(() -> {
                            if (!actOk || settingsHost == null) return;
                            if (actCatalogId != null) settingsHost.requestModGet(actCatalogId);
                            else settingsHost.onModToggled(actDir, !actEnabled);
                        });
                        settingsFocusStepLeft.add(null);
                        settingsFocusStepRight.add(null);
                    } else {
                        // Indented option-group row (only present when its
                        // parent is expanded -- see buildDisplayRows): title
                        // on the left, a wide ~520x64px picker on the right
                        // with ◂/▸ glyphs at the ends and the centered choice
                        // label. Tapping the left ~22% steps back one choice
                        // (through choices + "None", wrapping); the rest of
                        // the button steps forward -- same cycle d-pad
                        // left/right drive via settingsFocusStepLeft/Right.
                        com.kalenjohnson.chronoduo.mods.ModManager.OptionGroup og = dr.option;

                        // Title block (1-2 lines, see optionTitleLines above)
                        // centered as one unit within the row, exactly like
                        // the parent row's title/description stack.
                        setText(optionTitleSize, INK, false, Paint.Align.LEFT, false);
                        float optionTitleLineH = text.descent() - text.ascent();
                        String[] otLines = optionTitleLines[i];
                        float titleBlockH = otLines.length * optionTitleLineH;
                        float titleBaseline = rowTop + (rowH - titleBlockH) / 2f - text.ascent();
                        for (String line : otLines) {
                            c.drawText(line, left + optionIndent, titleBaseline, text);
                            titleBaseline += optionTitleLineH;
                        }

                        String selectedDir = og.selected();
                        String selectedLabel = "None";
                        int selectedIdx = -1;
                        java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.Choice> choices = og.choices;
                        for (int ci = 0; ci < choices.size(); ci++) {
                            if (choices.get(ci).dir.equals(selectedDir)) {
                                selectedIdx = ci;
                                selectedLabel = choices.get(ci).label;
                                break;
                            }
                        }
                        int totalChoices = choices.size() + 1; // + the trailing "None" slot
                        int curIdx = selectedIdx >= 0 ? selectedIdx : choices.size();

                        RectF box = modRowHitBoxes[i];
                        float boxTop = rowTop + (rowH - boxH) / 2f;
                        box.set(btnRight - pickerW, boxTop, btnRight, boxTop + boxH);
                        drawCommandButton(c, box, "", winTex, false);
                        float arrowInset = box.width() * 0.06f;
                        setText(boxH * 0.4f, Color.WHITE, true, Paint.Align.LEFT, true);
                        c.drawText("◂", box.left + arrowInset, box.centerY() + boxH * 0.14f, text);
                        setText(boxH * 0.4f, Color.WHITE, true, Paint.Align.RIGHT, true);
                        c.drawText("▸", box.right - arrowInset, box.centerY() + boxH * 0.14f, text);
                        // Pick the largest of the 3 picker-label sizes that
                        // fits between the arrows; falling through to the
                        // smallest and ellipsizing there is correct by
                        // construction regardless of the real device
                        // font metrics for labels like "Original Font
                        // (Playstation Buttons)", "Steam Controller",
                        // "Black & White Icons", or "No Battle Gauges".
                        float pickerLabelMaxW = box.width() * 0.72f;
                        float chosenPickerSize = pickerLabelSizes[pickerLabelSizes.length - 1];
                        for (float sz : pickerLabelSizes) {
                            setText(sz, Color.WHITE, true, Paint.Align.CENTER, true);
                            if (text.measureText(selectedLabel) <= pickerLabelMaxW) {
                                chosenPickerSize = sz;
                                break;
                            }
                        }
                        setText(chosenPickerSize, Color.WHITE, true, Paint.Align.CENTER, true);
                        c.drawText(ellipsize(selectedLabel, pickerLabelMaxW), box.centerX(),
                                box.centerY() - (text.ascent() + text.descent()) / 2f, text);
                        boolean rowActionable = !modsImporting;
                        if (modsImporting || !rowVisible) box.setEmpty();

                        modRowIsOptionCycle[i] = true;
                        modRowOptionGroupKey[i] = dr.optionGroupKey;
                        modRowOptionTitle[i] = og.title;
                        // Wrap with modulo (matching prevIdx below and the
                        // settingsFocusStepRight runnable further down) --
                        // NOT a plain "+1 < size" bounds check, which breaks
                        // specifically when nothing is selected yet (curIdx
                        // == choices.size(), the trailing "None" slot): +1
                        // would be choices.size()+1, never < choices.size(),
                        // so tapping the forward ("▸") side of the
                        // picker from a fresh, all-disabled import (every
                        // option starts disabled -- see extractZip's class
                        // doc) silently did nothing instead of selecting the
                        // first choice.
                        int nextIdx = cycleStepForward(curIdx, totalChoices);
                        modRowOptionNextChoice[i] = nextIdx < choices.size() ? choices.get(nextIdx).dir : null;
                        int prevIdx = cycleStepBack(curIdx, totalChoices);
                        modRowOptionPrevChoice[i] = prevIdx < choices.size() ? choices.get(prevIdx).dir : null;
                        modExpanderHitBoxes[i].setEmpty();

                        final String ogKey = dr.optionGroupKey;
                        final String ogTitle = og.title;
                        final java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.Choice> choicesFinal = choices;
                        final int curIdxFinal = curIdx, totalFinal = totalChoices;
                        final boolean actOk = rowActionable;
                        settingsFocusRects.add(new RectF(box));
                        if (totalFinal > 1) {
                            Runnable stepBack = () -> {
                                if (!actOk || settingsHost == null) return;
                                int ni = (curIdxFinal - 1 + totalFinal) % totalFinal;
                                settingsHost.onModOptionSelected(ogKey, ogTitle,
                                        ni < choicesFinal.size() ? choicesFinal.get(ni).dir : null);
                            };
                            Runnable stepForward = () -> {
                                if (!actOk || settingsHost == null) return;
                                int ni = (curIdxFinal + 1) % totalFinal;
                                settingsHost.onModOptionSelected(ogKey, ogTitle,
                                        ni < choicesFinal.size() ? choicesFinal.get(ni).dir : null);
                            };
                            // Confirm is never a no-op: it steps forward, same as ▸/right.
                            settingsFocusActivate.add(stepForward);
                            settingsFocusStepLeft.add(stepBack);
                            settingsFocusStepRight.add(stepForward);
                        } else {
                            settingsFocusActivate.add(null);
                            settingsFocusStepLeft.add(null);
                            settingsFocusStepRight.add(null);
                        }
                    }
                }
                c.restore();
                modRowCount = rowCount;

                // Scrollbar track (with page-up/page-down chevrons at each
                // end) in the reserved margin right of the row toggles --
                // only shown/tappable while the list actually overflows.
                if (maxScroll > 0f) {
                    float trackX0 = contentRight - w * 0.022f;
                    float trackX1 = contentRight - w * 0.006f;
                    float chevronH = h * 0.025f;
                    modScrollUpHitBox.set(trackX0, listTop, trackX1, listTop + chevronH);
                    modScrollDownHitBox.set(trackX0, maxY - chevronH, trackX1, maxY);
                    setText(chevronH * 0.9f, modScrollY > 0f ? INK : inkDim, true, Paint.Align.CENTER, false);
                    c.drawText("▲", modScrollUpHitBox.centerX(),
                            modScrollUpHitBox.bottom - chevronH * 0.2f, text);
                    setText(chevronH * 0.9f, modScrollY < maxScroll ? INK : inkDim, true, Paint.Align.CENTER, false);
                    c.drawText("▼", modScrollDownHitBox.centerX(),
                            modScrollDownHitBox.bottom - chevronH * 0.2f, text);

                    float trackTop = listTop + chevronH + h * 0.006f;
                    float trackBottom = maxY - chevronH - h * 0.006f;
                    float trackH = Math.max(0f, trackBottom - trackTop);
                    float thumbH = Math.max(trackH * 0.12f, trackH * (viewportH / contentH));
                    float thumbY = trackTop + (trackH - thumbH) * (modScrollY / maxScroll);
                    modScrollbarThumb.set(trackX0 + w * 0.002f, thumbY, trackX1 - w * 0.002f, thumbY + thumbH);
                    fill.setShader(null);
                    fill.setColor(inkDim);
                    c.drawRoundRect(modScrollbarThumb, w * 0.005f, w * 0.005f, fill);
                } else {
                    modScrollY = 0f;
                }

                // Pinned footer: Import button on the left, status/message
                // text (two lines max) to its right -- the "restart for a
                // full refresh" note is always the second line, taking the
                // first line's place when there's no message to show.
                float footerY = maxY + h * 0.025f;
                btnA = new RectF(left, footerY, left + Math.min(textW * 0.42f, w * 0.34f), footerY + modsBtnH);
                labelA = "Import file…";
                final boolean modsBusyImport = modsImporting;
                settingsFocusRects.add(new RectF(btnA));
                settingsFocusActivate.add(() -> {
                    if (!modsBusyImport && settingsHost != null) settingsHost.requestModImport();
                });
                settingsFocusStepLeft.add(null);
                settingsFocusStepRight.add(null);

                float statusX = btnA.right + w * 0.025f;
                float statusW = Math.max(0f, contentRight - statusX);
                String statusLine1 = null;
                int statusColor1 = INK;
                if (modsImporting) {
                    statusLine1 = modsMessage != null ? modsMessage : "working...";
                } else if (modsError != null) {
                    statusLine1 = "error: " + modsError;
                    statusColor1 = Color.rgb(150, 30, 30);
                } else if (modsMessage != null) {
                    statusLine1 = modsMessage;
                }
                float statusLineH = h * STATUS_LINE_SIZE_FRAC * BODY_LINE_H_RATIO;
                float statusBaseline1 = btnA.top + statusLineH * 0.8f;
                if (statusLine1 != null) {
                    setText(h * STATUS_LINE_SIZE_FRAC, statusColor1, false, Paint.Align.LEFT, false);
                    c.drawText(ellipsize(statusLine1, statusW), statusX, statusBaseline1, text);
                }
                setText(h * STATUS_LINE_SIZE_FRAC, inkDim, false, Paint.Align.LEFT, false);
                c.drawText(ellipsize("Restart for a full refresh.", statusW), statusX,
                        statusBaseline1 + statusLineH, text);
                break;
            }
        }

        c.restore();
        drawParchmentOverlay(c, parchment);

        if (page == 0) {
            drawSettingsButton(c, btnA, labelA, winTex, importing, importButtonHitBox);
            drawSettingsButton(c, btnB, labelB, winTex, savingImport, saveImportButtonHitBox);
        } else if (page == 1) {
            drawSettingsButton(c, btnA, labelA, winTex, false, pixelGraphicsHitBox);
            drawSettingsButton(c, btnB, labelB, winTex, origArtBuilding, origArtButtonHitBox);
        } else if (page == 2) {
            drawSettingsButton(c, btnA, labelA, winTex, false, fogToggleHitBox);
            // Small text-style "Reset explored maps" action under the fog
            // toggle (its hit box is padded well beyond the glyphs).
            float captionY = btnA.bottom + h * 0.03f;
            setText(h * 0.02f, INK, true, Paint.Align.LEFT, false);
            String resetLabel = "Reset explored maps";
            c.drawText(resetLabel, btnA.left, captionY, text);
            float resetHalfW = text.measureText(resetLabel) / 2f + w * 0.01f;
            fogResetHitBox.set(btnA.left - w * 0.01f, btnA.bottom,
                    btnA.left + resetHalfW * 2f, captionY + h * 0.02f);
            settingsFocusRects.add(new RectF(fogResetHitBox));
            settingsFocusActivate.add(() -> FogOfWar.clearAll());
            settingsFocusStepLeft.add(null);
            settingsFocusStepRight.add(null);
        } else if (page == SPEED_PAGE) {
            drawSettingsButton(c, btnA, labelA, winTex, false, ffSpeedHitBox);
            drawSettingsButton(c, btnB, labelB, winTex, false, ffModeHitBox);
        } else if (page == MODS_PAGE) { // Mods
            // Per-row Get/On/Off/picker buttons are drawn earlier, inside the
            // row list's own clip/scroll pass above (see the Mods case in
            // the switch), so they scroll and clip with their row instead of
            // sitting on top of everything unclipped like this page's other
            // buttons. Only the fixed Import-file button belongs here.
            drawSettingsButton(c, btnA, labelA, winTex, modsImporting, modImportButtonHitBox);
        }

        // Rail's own Back button, bottom of the rail.
        drawCommandButton(c, backBtn, "Back", winTex, false);
        settingsBackHitBox.set(backBtn);

        // D-pad focus ring: 2px navy outline around whatever's focused --
        // a rail tab/Back, or the current content-area item.
        RectF ring = null;
        if (settingsFocusArea == SETTINGS_FOCUS_RAIL) {
            ring = settingsRailFocus < SETTINGS_PAGES.length ? settingsTabHitBoxes[settingsRailFocus] : backBtn;
        } else if (settingsContentFocus >= 0 && settingsContentFocus < settingsFocusRects.size()) {
            RectF r = settingsFocusRects.get(settingsContentFocus);
            if (r != null && !r.isEmpty()) ring = r;
        }
        if (settingsFocusArea == SETTINGS_FOCUS_CONTENT && settingsFocusRects.isEmpty()) {
            settingsFocusArea = SETTINGS_FOCUS_RAIL; // page has nothing focusable -- don't strand the cursor
        }
        if (ring != null && !ring.isEmpty()) {
            stroke.setStrokeWidth(2f);
            stroke.setColor(BOX_BG);
            RectF ringR = new RectF(ring);
            ringR.inset(-2f, -2f);
            c.drawRect(ringR, stroke);
        }
    }

    /** Settings section heading (monospace, INK) at {@code y}; returns the y for the body text that follows. */
    private float drawSettingsHeading(Canvas c, String s, float left, float y, int h) {
        setText(h * 0.03f, INK, false, Paint.Align.LEFT, false);
        applyTypeface(Typeface.MONOSPACE);
        c.drawText(s, left, y, text);
        return y + h * 0.032f;
    }

    /**
     * Settings body text, word-wrapped to {@code maxW} at a small size in
     * {@code color}, starting with a baseline at {@code y}; returns the y
     * just below the last line plus a small gap. Uses the default body text
     * size ({@link #BODY_TEXT_SIZE_FRAC}) -- see the sized overload below for
     * callers (e.g. the Mods page status line) that need something bigger.
     */
    private float drawSettingsBody(Canvas c, String s, float left, float y, float maxW, int h, int color) {
        return drawSettingsBody(c, s, left, y, maxW, h, color, BODY_TEXT_SIZE_FRAC);
    }

    // drawSettingsBody's original fixed text-size fraction (of view height).
    private static final float BODY_TEXT_SIZE_FRAC = 0.022f;
    // Mods page status line (working.../error/success) -- ~1.3x the normal
    // body size per the Mods-page text-size pass (row name/summary/notes and
    // this line all got the same bump; see the row-drawing loop below).
    private static final float STATUS_LINE_SIZE_FRAC = BODY_TEXT_SIZE_FRAC * 1.3f;
    // drawSettingsBody's line-height was always 0.027/0.022 ~= 1.227x its
    // text size; kept as a ratio so a bigger sizeFrac still gets proportional
    // line spacing.
    private static final float BODY_LINE_H_RATIO = 0.027f / BODY_TEXT_SIZE_FRAC;

    /**
     * Same as the 6-arg overload, with an explicit {@code sizeFrac} (of view
     * height) instead of the fixed default -- see {@link #STATUS_LINE_SIZE_FRAC}.
     */
    private float drawSettingsBody(Canvas c, String s, float left, float y, float maxW, int h, int color, float sizeFrac) {
        setText(h * sizeFrac, color, false, Paint.Align.LEFT, false);
        float lineH = h * sizeFrac * BODY_LINE_H_RATIO;
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && text.measureText(candidate) > maxW) {
                c.drawText(line.toString(), left, y, text);
                y += lineH;
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (line.length() > 0) {
            c.drawText(line.toString(), left, y, text);
            y += lineH;
        }
        return y + h * 0.012f;
    }

    /**
     * A settings action button: {@link #drawCommandButton} chrome, dimmed
     * with an empty hit box while {@code busy} (so a tap can't double-fire
     * an in-flight import/build), otherwise {@code hitBox} is set to it.
     */
    private void drawSettingsButton(Canvas c, RectF box, String label, Bitmap winTex, boolean busy, RectF hitBox) {
        drawCommandButton(c, box, label, winTex, false);
        if (busy) {
            fill.setShader(null);
            fill.setColor(Color.argb(150, 0, 0, 0));
            c.drawRect(box, fill);
            hitBox.setEmpty();
        } else {
            hitBox.set(box);
        }
    }

    /**
     * Before any party data has arrived (no members yet), skip the whole
     * parchment/DS-panel rendering and show a minimal black-screen wordmark
     * instead — no boxes, no subtitle, nothing else to imply readiness that
     * isn't there yet.
     */
    private void drawWordmark(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Color.BLACK);
        setText(h * 0.09f, Color.WHITE, true, Paint.Align.CENTER, true);
        applyTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        c.drawText("CHRONO DUO", w / 2f, h / 2f + h * 0.03f, text);
        // Settings are reachable before a save is loaded: the same gear chip
        // as the parchment corner, top-left of the black screen.
        drawGearChip(c, w * 0.05f, h * 0.06f);
    }

    /** Dispatches to whichever parchment content (battle vs map vs field-title) {@code s} calls for. */
    private void drawContent(Canvas c, PartySnapshot s, RectF parchment, boolean live) {
        if (s.inBattle) {
            drawBattleContent(c, parchment, s, live);
            return;
        }
        // Overworld only when the game's WorldScene is actually live. A
        // nameless field scene (e.g. story cutscenes with no location) used
        // to be mistaken for the overworld and showed a map that didn't apply.
        boolean overworld = s.worldScenePresent;
        if (overworld) lastOverworldSnap = s;
        boolean nameless = s.mapName == null || s.mapName.isEmpty();
        String title = overworld ? "World Map" : (nameless ? "" : s.mapName);
        if (overworld) {
            drawOverworldContent(c, parchment, s, title);
        } else if (isWorldMapLocation(s.fieldMapId) && lastOverworldSnap != null) {
            // menu open on the overworld: keep the last live overworld view
            drawOverworldContent(c, parchment, lastOverworldSnap, "World Map");
        } else {
            drawFieldContent(c, parchment, s, title, live);
        }
    }

    /** Overworld: title, world map bitmap (or nothing, if not extracted yet), and the live position marker. */
    private void drawOverworldContent(Canvas c, RectF parchment, PartySnapshot s, String title) {
        int w = getWidth(), h = getHeight();
        // small title above the map
        setText(h * 0.045f, INK, true, Paint.Align.CENTER, false);
        applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        c.drawText(title, parchment.centerX(), parchment.top + h * 0.085f, text);

        float mx = parchment.centerX(), my = parchment.centerY() + h * 0.03f;
        // Epoch marker position; NaN until a valid pixel position is known.
        float ex = Float.NaN, ey = Float.NaN;
        Bitmap map = ChronoAssets.getWorldMap(s.worldEra);
        if (map != null) {
            // area between the title and the gold/time corner text
            RectF area = new RectF(parchment.left + w * 0.06f, parchment.top + h * 0.13f,
                    parchment.right - w * 0.06f, parchment.bottom - h * 0.09f);
            // wb_mini.png's cropped map content is stored at half its
            // displayed width (the game's own map view is landscape
            // ~1.5:1, not the bitmap's raw 96:128 = 0.75:1), so the target
            // aspect used for letterboxing is 1.5, not map.getWidth()/
            // map.getHeight(). drawBitmap below maps the full (undoubled)
            // source into a dst rect built from the doubled width, which
            // is what stretches it 2x horizontally.
            float effW = ChronoAssets.isWorldMapNaturalAspect(s.worldEra)
                    ? map.getWidth() : map.getWidth() * 2f;
            float effH = map.getHeight();
            float scale = Math.min(area.width() / effW, area.height() / effH);
            float dw = effW * scale, dh = effH * scale;
            RectF dst = new RectF(area.centerX() - dw / 2f, area.centerY() - dh / 2f,
                    area.centerX() + dw / 2f, area.centerY() + dh / 2f);
            c.drawBitmap(map, null, dst, mapPaint);
            // Live position: worldX/worldY are 8-pixel units at the world's
            // native 1x scale (WorldImpl::GetPartyCharPos left-shifts the raw
            // SNES-RAM tile coordinate by 3 -- see tools/world_map/REPORT.md
            // section 6), so the full overworld spans X in 0..191 (1536px/8)
            // and Y in 0..127 (1024px/8) regardless of what's actually drawn
            // in dst (the rendered 3072x2048 map or the letterboxed wb_mini
            // fallback) -- map proportionally by those spans, not a flat
            // 256-unit range.
            // Preferred: the pixel-granular position the game's OWN map
            // screen uses (WorldMap::markMiniMap reads u16 pairs at Asm
            // 0x2E283/0x2E285), already converted to the world image's
            // 1536x1024 top-left-origin space by the native side. Both the
            // 1x live capture (1536x1024) and the 2x offline render
            // (3072x2048) cover that space exactly, so the proportional
            // mapping below is correct for either without a scale factor.
            if (s.worldPixelX >= 0 && s.worldPixelY >= 0) {
                mx = dst.left + dst.width() * (s.worldPixelX / 1536f);
                my = dst.top + dst.height() * (s.worldPixelY / 1024f);
                if (s.epochVisible && s.epochPixelX >= 0 && s.epochPixelY >= 0) {
                    ex = dst.left + dst.width() * (s.epochPixelX / 1536f);
                    ey = dst.top + dst.height() * (s.epochPixelY / 1024f);
                }
            } else if (s.worldX >= 0 && s.worldY >= 0) {
                // Fallback: the 8px-granular tile bytes at 0x2E102/0x2E103,
                // so the full overworld spans X in 0..191 (1536/8) and Y in
                // 0..127 (1024/8) -- see WorldImpl::GetPartyCharPos.
                mx = dst.left + dst.width() * (s.worldX / 192f);
                my = dst.top + dst.height() * (s.worldY / 128f);
            } else {
                mx = dst.centerX();
                my = dst.centerY();
            }
        }

        Bitmap mark = ChronoAssets.getMinimapMark();
        // The Epoch ("silverd") marker, drawn first so the party pin stays on
        // top when both sit on the same square -- cell 2 of minimap_mark.png,
        // which the game itself uses for the parked Epoch (NOT a generic POI).
        Bitmap epoch = ChronoAssets.getEpochMark();
        if (epoch != null && !Float.isNaN(ex)) {
            float es = h * 0.026f;
            RectF epochDst = new RectF(ex - es, ey - es * 1.4f, ex + es, ey + es * 0.6f);
            c.drawBitmap(epoch, null, epochDst, markerPaint);
        }
        if (mark != null) {
            float ms = h * 0.03f;
            RectF markDst = new RectF(mx - ms, my - ms * 1.4f, mx + ms, my + ms * 0.6f);
            c.drawBitmap(mark, null, markDst, markerPaint);
        } else {
            // hand-drawn diamond marker (waiting for real coordinates either way)
            fill.setColor(Color.rgb(210, 50, 70));
            Path marker = new Path();
            float ms = h * 0.016f;
            marker.moveTo(mx, my - ms);
            marker.lineTo(mx + ms, my);
            marker.lineTo(mx, my + ms);
            marker.lineTo(mx - ms, my);
            marker.close();
            c.drawPath(marker, fill);
        }
    }

    /**
     * Field location: the location name as the parchment's centerpiece, plus
     * — when a rendered DS-style area minimap exists for {@code s.fieldMapId}
     * (see {@link ChronoAssets#getAreaMap(int)}) — that bitmap scaled to fit
     * beneath it, mirroring {@link #drawOverworldContent}'s world-map layout.
     * No bitmap (not rendered/pushed yet) falls back to the original
     * text-only centerpiece. When {@code live} and a location-name change is
     * fading (see {@link #titleFadeStart}), crossfades the old title out and
     * the new one in over {@link #TITLE_FADE_NANOS}; otherwise just draws it.
     */
    private void drawFieldContent(Canvas c, RectF parchment, PartySnapshot s, String title, boolean live) {
        int w = getWidth(), h = getHeight();
        Bitmap areaMap = ChronoAssets.getAreaMap(s.fieldMapId, s.fieldX, s.fieldY);
        // Advance dungeon fog-of-war reveal (only for the live snapshot --
        // never the fading-out side of a mode crossfade), then swap in the
        // masked bitmap for drawing below. Both no-ops (raw areaMap
        // unchanged) when the room isn't fogged or the pref is off.
        if (live) updateFogOfWar(s);
        areaMap = maskedAreaMapFor(s.fieldMapId, s.fieldX, s.fieldY, areaMap);

        setText(h * (areaMap != null ? 0.045f : 0.075f), INK, true, Paint.Align.CENTER, false);
        applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        float ty = areaMap != null ? parchment.top + h * 0.085f : parchment.centerY() + h * 0.025f;

        String posSuffix = (SHOW_FIELD_POS && !Float.isNaN(s.fieldX) && !Float.isNaN(s.fieldY))
                ? String.format(java.util.Locale.US, "  (%.1f,%.1f)", s.fieldX, s.fieldY) : "";
        String label = ((SHOW_MAP_ID && s.fieldMapId >= 0) ? title + "  #" + s.fieldMapId : title) + posSuffix;
        String fadingLabel = ((SHOW_MAP_ID && s.fieldMapId >= 0)
                ? (fadingOutTitle != null ? fadingOutTitle + "  #" + s.fieldMapId : fadingOutTitle) : fadingOutTitle);
        if (fadingLabel != null) fadingLabel += posSuffix;

        // Area-map crossfade state for this draw -- resolved once up front
        // so both the title-fade branch and the plain-draw branch below can
        // share it. Only progressed/settled when live (never for the
        // fading-out fadeSnap side of a mode crossfade).
        boolean areaMapFading = live && areaMapFadeStart >= 0;
        float mapT = 1f;
        if (areaMapFading) {
            long elapsed = System.nanoTime() - areaMapFadeStart;
            mapT = Math.min(1f, elapsed / (float) TITLE_FADE_NANOS);
            if (mapT >= 1f) {
                // fade finished this frame -- settle, draw the plain way
                areaMapFading = false;
                areaMapFadeStart = -1L;
                prevAreaMapBitmap = null;
                prevAreaMapId = -1;
            }
        }

        if (live && titleFadeStart >= 0) {
            long elapsed = System.nanoTime() - titleFadeStart;
            float t = Math.min(1f, elapsed / (float) TITLE_FADE_NANOS);
            if (t < 1f) {
                text.setAlpha((int) (255 * (1f - t)));
                drawTitleWrapped(c, fadingLabel, parchment, ty, areaMap != null);
                text.setAlpha((int) (255 * t));
                drawTitleWrapped(c, label, parchment, ty, areaMap != null);
                text.setAlpha(255);
                drawAreaMapWithFade(c, parchment, s, w, h, areaMap, areaMapFading, mapT);
                return;
            }
            // fade finished this frame -- settle and fall through to a plain draw
            titleFadeStart = -1L;
            fadingOutTitle = null;
        }
        drawTitleWrapped(c, label, parchment, ty, areaMap != null);
        drawAreaMapWithFade(c, parchment, s, w, h, areaMap, areaMapFading, mapT);
    }

    /**
     * Draws a location title with {@link #text}'s current paint, word-wrapped
     * to the parchment's inner width (long names like "Manolia Cathedral"
     * used to run off both edges). Lines are centred horizontally; when the
     * title is the panel's centrepiece (no map), the block is centred on
     * {@code baselineY}, otherwise it grows downward from it.
     */
    private void drawTitleWrapped(Canvas c, String s, RectF parchment, float baselineY, boolean topAligned) {
        if (s == null || s.isEmpty()) return;
        float maxW = parchment.width() * 0.86f;
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
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
        float y = topAligned ? baselineY : baselineY - lineH * (lines.size() - 1) * 0.5f;
        for (String line : lines) {
            c.drawText(line, parchment.centerX(), y, text);
            y += lineH;
        }
    }

    /**
     * Draws the field panel's area-map bitmap, crossfading between
     * {@link #prevAreaMapBitmap} (fading out, alpha 255-&gt;0) and
     * {@code areaMap} (fading in, alpha 0-&gt;255) over
     * {@link #TITLE_FADE_NANOS} whenever {@code fading} is true -- covers a
     * fieldMapId change to a different rendered map, and either direction
     * between a rendered map and the text-only placeholder (a null
     * {@code areaMap} or null {@link #prevAreaMapBitmap} simply skips that
     * side's draw). The live position marker is drawn only on the incoming
     * ({@code areaMap}) side and fades in with it. Outside of a fade this is
     * just the original plain draw at full opacity.
     */
    private void drawAreaMapWithFade(Canvas c, RectF parchment, PartySnapshot s, int w, int h,
                                      Bitmap areaMap, boolean fading, float t) {
        if (!fading) {
            if (areaMap != null) {
                drawAreaMapBitmap(c, parchment, areaMap, w, h, mapPaint);
                drawFieldPosMarkerIfCalibrated(c, s, h, markerPaint);
            }
            return;
        }
        if (prevAreaMapBitmap != null) {
            areaMapFadePaint.set(mapPaint);
            areaMapFadePaint.setAlpha((int) (mapPaint.getAlpha() * (1f - t)));
            drawAreaMapBitmap(c, parchment, prevAreaMapBitmap, w, h, areaMapFadePaint);
        }
        if (areaMap != null) {
            areaMapFadePaint.set(mapPaint);
            areaMapFadePaint.setAlpha((int) (mapPaint.getAlpha() * t));
            // drawn last so areaMapSrc/areaMapDst (stashed by
            // drawAreaMapBitmap) reflect the incoming map, matching what
            // the position marker below needs to map through.
            drawAreaMapBitmap(c, parchment, areaMap, w, h, areaMapFadePaint);
            markerFadePaint.set(markerPaint);
            markerFadePaint.setAlpha((int) (255 * t));
            drawFieldPosMarkerIfCalibrated(c, s, h, markerFadePaint);
        }
    }

    // Scratch buffer for AreaMapCalib.toMapPixel's out param -- reused to
    // avoid an allocation every draw.
    private final float[] areaMapCalibOut = new float[2];

    /**
     * Advances {@link FogOfWar} reveal for {@code s}'s current room/floor:
     * a no-op unless the fog pref is on, {@link AreaMapCalib#isFogged} says
     * this room is a fogged dungeon at the live tile position, and {@link
     * AreaMapCalib#toMapPixel} resolves that position (both NaN-safe --
     * an invalid/unknown leader position simply reveals nothing). Cheap per
     * frame: {@link FogOfWar#reveal} only allocates (a small byte[] clone
     * handed to its background writer) on the frame a cell newly reveals,
     * never every frame.
     */
    private void updateFogOfWar(PartySnapshot s) {
        if (!fogOn || s.fieldMapId < 0) return;
        if (!AreaMapCalib.isFogged(s.fieldMapId, s.fieldX, s.fieldY)) return;
        if (!AreaMapCalib.toMapPixel(s.fieldMapId, s.fieldX, s.fieldY, areaMapCalibOut)) return;
        int suffix = AreaMapCalib.suffixFor(s.fieldMapId, s.fieldX, s.fieldY);
        FogOfWar.reveal(FogOfWar.keyFor(s.fieldMapId, suffix), areaMapCalibOut[0], areaMapCalibOut[1],
                FogOfWar.DEFAULT_REVEAL_RADIUS_PX);
    }

    /**
     * Returns {@code raw} unchanged unless the fog pref is on, {@code raw}
     * is non-null, and {@link AreaMapCalib#isFogged} says {@code roomId} is
     * a fogged dungeon at ({@code tileX}, {@code tileY}) -- in which case
     * returns {@link FogOfWar#applyMask}'s masked copy instead (cached per
     * room/floor; regenerated only when the mask changes or {@code raw} is
     * a different bitmap instance, never every frame). Towns/houses and
     * every draw with the pref off render {@code raw} exactly as before.
     */
    private Bitmap maskedAreaMapFor(int roomId, float tileX, float tileY, Bitmap raw) {
        if (raw == null || !fogOn || roomId < 0) return raw;
        if (!AreaMapCalib.isFogged(roomId, tileX, tileY)) return raw;
        int suffix = AreaMapCalib.suffixFor(roomId, tileX, tileY);
        Bitmap masked = FogOfWar.applyMask(FogOfWar.keyFor(roomId, suffix), raw);
        return masked != null ? masked : raw;
    }

    /** Looks up s's field position via AreaMapCalib and draws the marker (with {@code markPaint}'s alpha) if it maps to a point inside the drawn area map. */
    private void drawFieldPosMarkerIfCalibrated(Canvas c, PartySnapshot s, int h, Paint markPaint) {
        if (AreaMapCalib.toMapPixel(s.fieldMapId, s.fieldX, s.fieldY, areaMapCalibOut)) {
            drawFieldPosMarker(c, areaMapCalibOut[0], areaMapCalibOut[1], h, markPaint);
        }
    }

    // area_minimap_%03d.png (256x192) bakes its own parchment frame into the
    // art (a ~14px border with rounded corners, matching this panel's own
    // parchment chrome) -- (16,16)-(240,176) is the floor-plan content only,
    // with that frame cropped away, so the map can be scaled up to actually
    // fill the panel instead of being drawn small inside a second frame.
    private static final int AREA_MAP_SRC_L = 16, AREA_MAP_SRC_T = 16;
    private static final int AREA_MAP_SRC_R = 240, AREA_MAP_SRC_B = 176;

    /**
     * Draws the cropped floor-plan region of a rendered DS-style area
     * minimap (see {@link #AREA_MAP_SRC_L} et al.), scaled up to fill the
     * area between the title and the gold/time corner text as large as
     * possible (aspect preserved, centered, ~4% margin left around it) --
     * mirroring {@link #drawOverworldContent}'s world-map placement style
     * (nearest-neighbour via {@link #mapPaint}, parchment-blended alpha) but
     * fit to the crop's own aspect ratio rather than a fixed 4:3.
     */
    private void drawAreaMapBitmap(Canvas c, RectF parchment, Bitmap areaMap, int w, int h, Paint paint) {
        RectF area = new RectF(parchment.left + w * 0.06f, parchment.top + h * 0.13f,
                parchment.right - w * 0.06f, parchment.bottom - h * 0.09f);
        float margin = Math.min(area.width(), area.height()) * 0.04f;
        area.inset(margin, margin);

        Rect src = new Rect(AREA_MAP_SRC_L, AREA_MAP_SRC_T,
                Math.min(AREA_MAP_SRC_R, areaMap.getWidth()),
                Math.min(AREA_MAP_SRC_B, areaMap.getHeight()));
        if (src.width() <= 0 || src.height() <= 0) return; // unexpectedly small source, skip rather than draw garbage

        float scale = Math.min(area.width() / src.width(), area.height() / src.height());
        float dw = src.width() * scale, dh = src.height() * scale;
        RectF dst = new RectF(area.centerX() - dw / 2f, area.centerY() - dh / 2f,
                area.centerX() + dw / 2f, area.centerY() + dh / 2f);
        c.drawBitmap(areaMap, src, dst, paint);

        // Stash the src/dst rects this draw used, so drawFieldContent's
        // position-marker overlay can map a 256x192-image-space point (see
        // AreaMapCalib.toMapPixel) through the exact same crop/scale.
        areaMapSrc.set(src);
        areaMapDst.set(dst);
    }

    /**
     * Draws the live field-position marker (the same minimap_mark.png
     * "position" frame used by {@link #drawOverworldContent}) at the point
     * {@code (imgX, imgY)} in the original 256x192 DS area-minimap image
     * space, transformed through {@code areaMapSrc}/{@code areaMapDst} (set
     * by the {@link #drawAreaMapBitmap} call this frame). Skips drawing if
     * the point falls outside {@code areaMapDst} (off the visible crop).
     */
    private void drawFieldPosMarker(Canvas c, float imgX, float imgY, int h, Paint markPaint) {
        if (areaMapDst.width() <= 0 || areaMapDst.height() <= 0) return;
        if (areaMapSrc.width() <= 0 || areaMapSrc.height() <= 0) return;
        float u = (imgX - areaMapSrc.left) / (float) areaMapSrc.width();
        float v = (imgY - areaMapSrc.top) / (float) areaMapSrc.height();
        if (u < 0f || u > 1f || v < 0f || v > 1f) return;
        float mx = areaMapDst.left + areaMapDst.width() * u;
        float my = areaMapDst.top + areaMapDst.height() * v;

        Bitmap mark = ChronoAssets.getMinimapMark();
        if (mark != null) {
            float ms = h * 0.03f;
            RectF markDst = new RectF(mx - ms, my - ms * 1.4f, mx + ms, my + ms * 0.6f);
            c.drawBitmap(mark, null, markDst, markPaint);
        } else {
            // fallback diamond: no bitmap asset to hand markPaint's alpha
            // to, so fold it into the fill color's own alpha component
            // instead (fill is re-colored fresh on every use elsewhere, so
            // this doesn't leak a persistent alpha onto it).
            fill.setColor(Color.argb(markPaint.getAlpha(), 210, 50, 70));
            Path marker = new Path();
            float ms = h * 0.016f;
            marker.moveTo(mx, my - ms);
            marker.lineTo(mx + ms, my);
            marker.lineTo(mx, my + ms);
            marker.lineTo(mx - ms, my);
            marker.close();
            c.drawPath(marker, fill);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        // Settings first: it draws its own parchment and needs no party, so
        // it works from the pre-load wordmark screen as well.
        if (settingsMode) {
            drawSettingsScreen(c);
            return;
        }
        if (snap.members.isEmpty()) {
            drawWordmark(c);
            return;
        }

        int w = getWidth(), h = getHeight();
        float pad = w * 0.02f;

        // parchment fills most of the screen, like the DS map view
        RectF parchment = new RectF(pad * 3, h * 0.2f, w - pad * 3, h - pad * 2.2f);
        drawParchmentBase(c, parchment);

        boolean animating = advanceEnemyBarFractions();

        // clip the map/title content to the torn-paper path so nothing draws
        // past the ripped edge (drawParchmentBase() above already built it)
        c.save();
        c.clipPath(tornPaper);

        boolean modeFading = modeFadeStart >= 0 && fadeSnap != null;
        float modeT = 1f;
        if (modeFading) {
            long elapsed = System.nanoTime() - modeFadeStart;
            modeT = Math.min(1f, elapsed / (float) MODE_FADE_NANOS);
        }
        if (modeFading && modeT < 1f) {
            // crossfade ONLY the parchment content (map/marker/title vs
            // battle title/enemy bars) -- the parchment itself, status
            // boxes, and gold/time are drawn once, outside this block, and
            // never fade.
            int layer = c.saveLayerAlpha(0, 0, w, h, (int) (255 * (1f - modeT)));
            drawContent(c, fadeSnap, parchment, false);
            c.restoreToCount(layer);

            layer = c.saveLayerAlpha(0, 0, w, h, (int) (255 * modeT));
            drawContent(c, snap, parchment, true);
            c.restoreToCount(layer);
            animating = true;
        } else {
            if (modeFading) {
                // fade finished this frame -- settle
                modeFadeStart = -1L;
                fadeSnap = null;
            }
            drawContent(c, snap, parchment, true);
        }
        c.restore();

        if (titleFadeStart >= 0) animating = true;
        if (areaMapFadeStart >= 0) animating = true;
        if (FogOfWar.isAnimating()) animating = true; // newly revealed fog cells fading in
        if (resultsMessageFadeStart >= 0) animating = true;
        if (snap.resultsActive && !resultsVisible(snap)) animating = true; // waiting out the victory pose
        if (pressedCommand >= 0 && pressedAt >= 0
                && System.nanoTime() - pressedAt < PRESS_FEEDBACK_NANOS) {
            animating = true;
        }
        if (pressedAuto && pressedAutoAt >= 0
                && System.nanoTime() - pressedAutoAt < PRESS_FEEDBACK_NANOS) {
            animating = true;
        }

        // aged-paper vignette/speckles/frame ON TOP of the map so it reads
        // as ink on old parchment rather than a clean printed minimap
        drawParchmentOverlay(c, parchment);

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
                parchment.left + 24f + GEAR_CHIP_SIZE, parchment.top + 24f + GEAR_CHIP_SIZE);
        if (drawFadedChip(c, gearFade, gearRect, () -> drawGearChip(c, parchment))) animating = true;
        if (drawFadedChip(c, eyeFade, eyeHitRect(parchment), () -> drawEyeToggle(c, parchment))) animating = true;
        if (drawFadedChip(c, autoFade, autoChipRect(parchment), () -> drawAutoToggle(c, parchment))) animating = true;
        if (!showGear) gearHitBox.setEmpty();
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
            postInvalidateDelayed(Math.max(1L, (targetingUntil - System.nanoTime()) / 1_000_000L + 16L));
        }

        // DS-style status boxes along the top, one per party member (n > 0
        // here — onDraw returns early via drawWordmark() otherwise)
        int n = snap.members.size();
        float boxH = h * 0.145f;
        float boxW = Math.min(w * 0.31f, (w - pad * (n + 1)) / n);
        float x = pad;
        for (PartySnapshot.Member m : snap.members) {
            drawStatusBox(c, m, x, pad, boxW, boxH);
            x += boxW + pad;
        }

        // gold + time inked into the parchment's bottom corners
        setText(h * 0.032f, INK, true, Paint.Align.LEFT, false);
        applyTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        c.drawText(snap.gold + " G", parchment.left + w * 0.035f,
                parchment.bottom - h * 0.035f, text);
        int s = snap.playSeconds;
        String time = String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(time, parchment.right - w * 0.035f, parchment.bottom - h * 0.035f, text);

        // keep animating (mode crossfade, title fade, or bar easing) only
        // while something is actually still off-target, and only while
        // attached -- a detached view must never keep scheduling frames.
        if (animating && attached) {
            postInvalidateOnAnimation();
        }
    }

    /**
     * The source rect for portrait {@code charIdx} within {@code face}
     * (see {@link ChronoAssets#getFace()}), scaled by {@code face}'s actual
     * size relative to the original 384x176 sheet -- see the {@link
     * #FACE_TILE_W} field doc.
     */
    private static Rect faceTileRect(Bitmap face, int charIdx) {
        int col = charIdx % FACE_COLS, row = charIdx / FACE_COLS;
        float scaleX = face.getWidth() / (float) FACE_SHEET_W;
        float scaleY = face.getHeight() / (float) FACE_SHEET_H;
        int tileW = Math.round(FACE_TILE_W * scaleX);
        int tileH = Math.round(FACE_TILE_H * scaleY);
        int x = Math.round(col * FACE_TILE_W * scaleX);
        int y = Math.round(row * FACE_TILE_H * scaleY);
        return new Rect(x, y, x + tileW, y + tileH);
    }

    private static int indexOfName(String name) {
        for (int i = 0; i < PartySnapshot.DEFAULT_NAMES.length; i++) {
            if (PartySnapshot.DEFAULT_NAMES[i].equals(name)) return i;
        }
        return -1;
    }
}
