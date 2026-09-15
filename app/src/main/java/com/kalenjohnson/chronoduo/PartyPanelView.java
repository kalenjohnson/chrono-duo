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

    // face.png layout: 4x2 grid of 96x88 tiles, char-id order (Crono..Magus,
    // Epoch); char ids 0..6 line up with PartySnapshot.DEFAULT_NAMES.
    private static final int FACE_TILE_W = 96;
    private static final int FACE_TILE_H = 88;
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
    private static final boolean SHOW_MAP_ID = true; // debug aid: map id after the location name
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
    // Small "back" chip (top-left corner, mirroring the eye toggle's
    // top-right position), shown only while the submenu-list band is up --
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
    }
    private SettingsHost settingsHost;

    /** Sets (or clears, with null) the host that handles ROM-import requests from the settings screen -- see {@link SettingsHost}. */
    public void setSettingsHost(SettingsHost host) {
        settingsHost = host;
    }

    // Small "gear" chip, top-left corner, mirroring the eye toggle's
    // top-right placement/hit-box style (see drawEyeToggle) -- shown in
    // field/overworld modes (never battle; the eye toggle owns that corner
    // there, and mid-battle isn't a sane time to open settings). Tapping it
    // enters settingsMode.
    private final RectF gearHitBox = new RectF();
    private boolean settingsMode;

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
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        honorHiddenHp = !MODE_FULL.equals(prefs.getString(KEY_ENEMY_HP_MODE, MODE_HONOR));
        pixelGraphicsOn = GameState.getPixelGraphicsPref(context);
        fogOn = prefs.getBoolean(KEY_FOG_ON, true);
        FogOfWar.init(context.getFilesDir());
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
        if (event.getAction() != MotionEvent.ACTION_DOWN) return false;
        if (settingsMode) {
            if (!importing && !importButtonHitBox.isEmpty()
                    && importButtonHitBox.contains(event.getX(), event.getY())) {
                if (settingsHost != null) settingsHost.requestRomImport();
                return true;
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
            if (!settingsBackHitBox.isEmpty()
                    && settingsBackHitBox.contains(event.getX(), event.getY())) {
                settingsMode = false;
                invalidate();
                return true;
            }
            return true; // swallow every touch while the settings modal is up
        }
        if (!snap.inBattle && !gearHitBox.isEmpty()
                && gearHitBox.contains(event.getX(), event.getY())) {
            settingsMode = true;
            invalidate();
            return true;
        }
        if (snap.inBattle && !eyeHitBox.isEmpty()
                && eyeHitBox.contains(event.getX(), event.getY())) {
            toggleHiddenHpMode();
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
        if (!commandNavActive()) return false;
        if (commandSel > 0) {
            commandSel--;
            invalidate();
        }
        return true;
    }

    /** Same as {@link #onControllerLeft}, moving right and clamping at {@code commandCount - 1}. */
    public boolean onControllerRight() {
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
    public boolean onControllerConfirm() {
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
        if (!listNavActive()) return false;
        if (listSel > 0) {
            listSel--;
            invalidate();
        }
        return true;
    }

    /** Same as {@link #onControllerUp}, moving down and clamping at {@code snap.listRows.size() - 1}. */
    public boolean onControllerDown() {
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

    private void setText(float size, int color, boolean bold, Paint.Align align, boolean shadow) {
        text.setTextSize(size);
        text.setColor(color);
        text.setTypeface(bold ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                : Typeface.MONOSPACE);
        text.setTextAlign(align);
        if (shadow) text.setShadowLayer(2.5f, 1.5f, 1.5f, Color.argb(200, 0, 0, 0));
        else text.clearShadowLayer();
    }

    /** DS status box: real window texture (9-sliced) when loaded, else a hand-drawn navy double border; portrait, HP/MP rows on top either way. */
    private void drawStatusBox(Canvas c, PartySnapshot.Member m, float l, float t, float w, float h) {
        RectF box = new RectF(l, t, l + w, t + h);
        Bitmap winTex = ChronoAssets.getWindowTex();
        if (winTex != null) {
            float destInset = Math.min(w, h) * 0.09f;
            drawNinePatch(c, winTex, ChronoAssets.WINDOW_TEX_INSET, box, destInset);
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
            Rect src = faceTileRect(charIdx);
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
                text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                float wgt = Math.max(text.measureText(STATUS_WORST_HP), text.measureText(STATUS_WORST_MP));
                if (wgt <= avail) break;
                fs -= h * STATUS_FONT_STEP_FRAC;
            }
            fs = Math.max(fs, minFs);
            // Accept immediately once it fits at max-or-shrunk size, or once
            // we're out of fallback attempts (last attempt always accepted,
            // clamped to the floor above).
            text.setTextSize(fs);
            text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
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

    private void buildTornPaths(RectF r) {
        int w = getWidth(), h = getHeight();
        if (w == tornW && h == tornH) return;
        tornW = w;
        tornH = h;
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
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        c.drawText("Battle", parchment.centerX(), parchment.top + h * 0.085f, text);

        int n = s.enemies.size();
        if (n == 0) return;
        float areaTop = parchment.top + h * 0.13f;
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
            drawNinePatch(c, winTex, ChronoAssets.WINDOW_TEX_INSET, box, destInset);
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
            drawNinePatch(c, winTex, ChronoAssets.WINDOW_TEX_INSET, band, destInset);
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
            drawNinePatch(c, winTex, ChronoAssets.WINDOW_TEX_INSET, band, destInset);
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
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
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
     * parchment's top-left corner -- mirroring {@link #drawEyeToggle}'s
     * top-right placement -- only while the submenu-list band is showing.
     * Tapping within its ~48px hit box ({@link #listBackHitBox}, see {@link
     * #onTouchEvent}) calls {@link #backList}. Updates {@link
     * #listBackHitBox} every call so the hit-test always matches the glyph's
     * current on-screen position.
     */
    private void drawListBackToggle(Canvas c, RectF parchment) {
        float cx = parchment.left + BACK_GLYPH_RADIUS + 14f;
        float cy = parchment.top + BACK_GLYPH_RADIUS + 14f;
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
        if (!honorHiddenHp) return false;
        byte[] flags = ChronoAssets.getMonsterFlags();
        if (flags == null || e.id < 0 || e.id >= flags.length) return false;
        return (flags[e.id] & 0xff) == 255;
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
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        c.drawText(enemyLabel(e, index), l, t, text);
        setText(h * 0.62f, INK, false, Paint.Align.RIGHT, false);
        text.setTypeface(Typeface.MONOSPACE);
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
     * parchment's top-right corner, drawn only while in battle mode. Tapping
     * within its ~48px hit box (see {@link #onTouchEvent}) toggles the
     * hidden-HP display setting. Updates {@link #eyeHitBox} every call so
     * the hit-test always matches the glyph's current on-screen position.
     */
    private void drawEyeToggle(Canvas c, RectF parchment) {
        float cx = parchment.right - EYE_GLYPH_RADIUS - 14f;
        float cy = parchment.top + EYE_GLYPH_RADIUS + 14f;
        eyeHitBox.set(cx - EYE_HIT_HALF, cy - EYE_HIT_HALF, cx + EYE_HIT_HALF, cy + EYE_HIT_HALF);

        int inkA = Color.argb(210, Color.red(INK), Color.green(INK), Color.blue(INK));
        stroke.setStrokeWidth(2f);
        stroke.setColor(inkA);
        c.drawCircle(cx, cy, EYE_GLYPH_RADIUS, stroke);
        fill.setShader(null);
        fill.setColor(inkA);
        c.drawCircle(cx, cy, EYE_GLYPH_RADIUS * 0.35f, fill);
    }

    /**
     * Small "gear" toggle glyph (ring + teeth + center dot, INK color) in the
     * parchment's top-left corner -- same size/hit-box style as {@link
     * #drawEyeToggle}'s top-right eye, drawn only outside battle (see {@link
     * #onDraw}). Tapping within its hit box (see {@link #onTouchEvent})
     * enters {@link #settingsMode}. Updates {@link #gearHitBox} every call so
     * the hit-test always matches the glyph's current on-screen position.
     */
    private void drawGearToggle(Canvas c, RectF parchment) {
        // Drawn ~2.4x the eye glyph's size and inset well inside the ink
        // frame: at the eye's size it read as a speck on the frame line.
        float r = EYE_GLYPH_RADIUS * 2.4f;
        float cx = parchment.left + r + 30f;
        float cy = parchment.top + r + 30f;
        float half = Math.max(EYE_HIT_HALF, r * 1.6f);
        gearHitBox.set(cx - half, cy - half, cx + half, cy + half);

        int inkA = Color.argb(230, Color.red(INK), Color.green(INK), Color.blue(INK));
        stroke.setStrokeWidth(3.5f);
        stroke.setColor(inkA);
        c.drawCircle(cx, cy, r * 0.7f, stroke);
        for (int i = 0; i < 8; i++) {
            double ang = Math.toRadians(i * 45);
            float x0 = (float) (cx + Math.cos(ang) * r * 0.72f);
            float y0 = (float) (cy + Math.sin(ang) * r * 0.72f);
            float x1 = (float) (cx + Math.cos(ang) * r * 1.15f);
            float y1 = (float) (cy + Math.sin(ang) * r * 1.15f);
            c.drawLine(x0, y0, x1, y1, stroke);
        }
        fill.setShader(null);
        fill.setColor(inkA);
        c.drawCircle(cx, cy, r * 0.3f, fill);
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
     * The settings screen: same parchment chrome as the normal panel (title,
     * "DS maps: <status>" row + import button, "Pixel graphics: On/Off"
     * button, "Original art: <status>" row + "Build original art"
     * button -- disabled, no hit box, while {@link #origArtBuilding} -- and a
     * "Back" button that returns to the normal panel). Drawn instead of
     * {@link #drawContent}/the status boxes/gold-time corners whenever
     * {@link #settingsMode} is true -- see {@link #onDraw}.
     */
    private void drawSettingsScreen(Canvas c) {
        int w = getWidth(), h = getHeight();
        float pad = w * 0.02f;
        RectF parchment = new RectF(pad * 3, h * 0.2f, w - pad * 3, h - pad * 2.2f);
        drawParchmentBase(c, parchment);

        c.save();
        c.clipPath(tornPaper);

        setText(h * 0.055f, INK, true, Paint.Align.CENTER, false);
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        c.drawText("Settings", parchment.centerX(), parchment.top + h * 0.09f, text);

        setText(h * 0.032f, INK, false, Paint.Align.LEFT, false);
        text.setTypeface(Typeface.MONOSPACE);
        c.drawText("DS maps: " + importStatusText(), parchment.left + w * 0.06f,
                parchment.top + h * 0.155f, text);

        setText(h * 0.022f, Color.argb(200, Color.red(INK), Color.green(INK), Color.blue(INK)),
                false, Paint.Align.LEFT, false);
        c.drawText("Room maps are available if you can provide the",
                parchment.left + w * 0.06f, parchment.top + h * 0.185f, text);
        c.drawText("Chrono Trigger DS ROM (.nds or .zip).",
                parchment.left + w * 0.06f, parchment.top + h * 0.212f, text);

        setText(h * 0.032f, INK, false, Paint.Align.LEFT, false);
        text.setTypeface(Typeface.MONOSPACE);
        c.drawText("Original art: " + origArtStatusText(), parchment.left + w * 0.06f,
                parchment.top + h * 0.462f, text);

        setText(h * 0.022f, Color.argb(200, Color.red(INK), Color.green(INK), Color.blue(INK)),
                false, Paint.Align.LEFT, false);
        c.drawText("Rebuilds character sprites and field chips from the",
                parchment.left + w * 0.06f, parchment.top + h * 0.488f, text);
        c.drawText("game's own 1x art. Only applies when Pixel graphics is On.",
                parchment.left + w * 0.06f, parchment.top + h * 0.514f, text);

        setText(h * 0.022f, Color.argb(200, Color.red(INK), Color.green(INK), Color.blue(INK)),
                false, Paint.Align.LEFT, false);
        text.setTypeface(Typeface.MONOSPACE);
        c.drawText(worldMapStatusText(), parchment.left + w * 0.06f, parchment.top + h * 0.63f, text);

        c.restore();
        drawParchmentOverlay(c, parchment);

        Bitmap winTex = ChronoAssets.getWindowTex();

        float btnW = parchment.width() * 0.6f;
        float btnH = h * 0.075f;
        RectF importBtn = new RectF(parchment.centerX() - btnW / 2f, parchment.top + h * 0.245f,
                parchment.centerX() + btnW / 2f, parchment.top + h * 0.245f + btnH);
        drawCommandButton(c, importBtn, "Import DS ROM...", winTex, false);
        if (importing) {
            fill.setShader(null);
            fill.setColor(Color.argb(150, 0, 0, 0));
            c.drawRect(importBtn, fill);
            importButtonHitBox.setEmpty();
        } else {
            importButtonHitBox.set(importBtn);
        }

        // Pixel graphics and Dungeon fog share one row as two half-width
        // toggles (the 0.6-parchment-width column the other buttons use,
        // split with a small gap) so the rows below keep their positions.
        // Slightly shorter than the other buttons so the longest label
        // ("Pixel graphics: Off") still fits its half at the 0.42*height
        // label size drawCommandButton uses.
        float toggleRowW = parchment.width() * 0.6f;
        float toggleGap = parchment.width() * 0.02f;
        float toggleW = (toggleRowW - toggleGap) / 2f;
        float toggleH = h * 0.055f;
        float toggleTop = parchment.top + h * 0.35f;
        float toggleLeft = parchment.centerX() - toggleRowW / 2f;
        RectF pixelBtn = new RectF(toggleLeft, toggleTop, toggleLeft + toggleW, toggleTop + toggleH);
        drawCommandButton(c, pixelBtn, "Pixel graphics: " + (pixelGraphicsOn ? "On" : "Off"), winTex, false);
        pixelGraphicsHitBox.set(pixelBtn);

        RectF fogBtn = new RectF(pixelBtn.right + toggleGap, toggleTop,
                pixelBtn.right + toggleGap + toggleW, toggleTop + toggleH);
        // An import made before the fog flag was exported has no fog data:
        // say so on the button instead of silently never fogging.
        String fogLabel = fogOn && countDsMaps() > 0 && !AreaMapCalib.hasFogData()
                ? "Dungeon fog: re-import ROM" : "Dungeon fog: " + (fogOn ? "On" : "Off");
        drawCommandButton(c, fogBtn, fogLabel, winTex, false);
        fogToggleHitBox.set(fogBtn);

        // One caption line under each toggle: the restart note under Pixel
        // graphics, and a small text-style "Reset explored maps" action
        // under Dungeon fog (its hit box is padded well beyond the glyphs).
        float captionY = pixelBtn.bottom + h * 0.024f;
        setText(h * 0.018f, Color.argb(190, Color.red(INK), Color.green(INK), Color.blue(INK)),
                false, Paint.Align.CENTER, false);
        c.drawText("Full effect after game restart", pixelBtn.centerX(), captionY, text);

        setText(h * 0.018f, INK, true, Paint.Align.CENTER, false);
        String resetLabel = "Reset explored maps";
        c.drawText(resetLabel, fogBtn.centerX(), captionY, text);
        float resetHalfW = text.measureText(resetLabel) / 2f + w * 0.01f;
        fogResetHitBox.set(fogBtn.centerX() - resetHalfW, fogBtn.bottom,
                fogBtn.centerX() + resetHalfW, captionY + h * 0.02f);

        float origArtBtnW = parchment.width() * 0.6f;
        float origArtBtnH = h * 0.065f;
        RectF origArtBtn = new RectF(parchment.centerX() - origArtBtnW / 2f, parchment.top + h * 0.545f,
                parchment.centerX() + origArtBtnW / 2f, parchment.top + h * 0.545f + origArtBtnH);
        drawCommandButton(c, origArtBtn, "Build original art", winTex, false);
        if (origArtBuilding) {
            fill.setShader(null);
            fill.setColor(Color.argb(150, 0, 0, 0));
            c.drawRect(origArtBtn, fill);
            origArtButtonHitBox.setEmpty();
        } else {
            origArtButtonHitBox.set(origArtBtn);
        }

        float backW = parchment.width() * 0.4f;
        float backH = h * 0.065f;
        RectF backBtn = new RectF(parchment.centerX() - backW / 2f, parchment.bottom - h * 0.09f,
                parchment.centerX() + backW / 2f, parchment.bottom - h * 0.09f + backH);
        drawCommandButton(c, backBtn, "Back", winTex, false);
        settingsBackHitBox.set(backBtn);
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
        text.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        c.drawText("CHRONO DUO", w / 2f, h / 2f + h * 0.03f, text);
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
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
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
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
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
        if (snap.members.isEmpty()) {
            drawWordmark(c);
            return;
        }
        if (settingsMode) {
            drawSettingsScreen(c);
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
        if (snap.inBattle) {
            gearHitBox.setEmpty();
            drawEyeToggle(c, parchment);
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
            eyeHitBox.setEmpty();
            commandCount = 0;
            targetCount = 0;
            listVisibleCount = 0;
            listBackHitBox.setEmpty();
            drawGearToggle(c, parchment);
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
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
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

    private static Rect faceTileRect(int charIdx) {
        int col = charIdx % FACE_COLS, row = charIdx / FACE_COLS;
        int x = col * FACE_TILE_W, y = row * FACE_TILE_H;
        return new Rect(x, y, x + FACE_TILE_W, y + FACE_TILE_H);
    }

    private static int indexOfName(String name) {
        for (int i = 0; i < PartySnapshot.DEFAULT_NAMES.length; i++) {
            if (PartySnapshot.DEFAULT_NAMES[i].equals(name)) return i;
        }
        return -1;
    }
}
