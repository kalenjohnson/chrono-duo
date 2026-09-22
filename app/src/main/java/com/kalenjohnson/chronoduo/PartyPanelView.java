package com.kalenjohnson.chronoduo;

import android.content.Context;
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

import java.util.List;
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
 *
 * <p>This class is the host: it owns the canvas, the live {@link PartySnapshot}
 * hand-off, the parchment chrome, the status boxes, the mode crossfade
 * between map and battle content, and the shared paints/text helpers. The
 * three content areas live in their own package-private classes and borrow
 * those helpers through same-named delegates:
 * <ul>
 * <li>{@link SettingsScreen} -- gear chip, full-screen settings sheet, Mods page;</li>
 * <li>{@link BattleChrome} -- enemy bars, command/targeting/submenu/results
 *     bands with tap injection, top-strip chips, battle d-pad nav;</li>
 * <li>{@link MapContent} -- overworld map + markers, field area minimap,
 *     fog-of-war, title/area-map crossfades.</li>
 * </ul>
 * Touch and d-pad input is routed settings-first, then battle; drawing is
 * settings (whole screen) or parchment + map/battle content + battle overlay.
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
    static final long MODE_FADE_NANOS = 250_000_000L;
    private PartySnapshot fadeSnap;
    private long modeFadeStart = -1L;

    // Short crossfade shared by the field-mode title/area-map fades (MapContent)
    // and the battle results-message fade (BattleChrome).
    static final long TITLE_FADE_NANOS = 180_000_000L;




    // DS status box palette (navy window, light double border)
    static final int BOX_BG = Color.rgb(32, 40, 96);
    static final int BOX_BG_DARK = Color.rgb(16, 20, 56);
    static final int BOX_BORDER_OUT = Color.rgb(222, 222, 230);
    static final int BOX_BORDER_IN = Color.rgb(90, 96, 150);
    // parchment palette
    static final int PAPER = Color.rgb(214, 197, 158);
    static final int PAPER_DARK = Color.rgb(150, 128, 88);
    static final int PAPER_EDGE = Color.rgb(94, 74, 44);
    static final int INK = Color.rgb(96, 72, 40);

    // Shared prefs file for the panel's own settings (enemy hidden-HP mode in
    // BattleChrome, fog/pixel-graphics in SettingsScreen).
    static final String PREFS_NAME = "chronoduo_prefs";

    // The battle side (enemy bars, command/targeting/submenu/results bands,
    // top-strip chips, tap injection, battle d-pad nav) lives in BattleChrome;
    // this view routes touch/d-pad/draw/update to it.
    final BattleChrome battle;
    // Party status box rects (one per drawn member, in party order) for
    // tap-to-target on allies -- see onTouchEvent / BattleChrome.selectTargetSlot.
    private final RectF[] memberBoxes = {new RectF(), new RectF(), new RectF()};
    private int memberBoxCount;
    // The map side (overworld map + marker, field area minimap + fog + marker,
    // title / area-map crossfades) lives in MapContent.
    final MapContent map;

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
        /** A mod row's ▲/▼ reorder button tapped: {@code name} is the mod's directory name (or any member of its group's move-as-one unit -- see {@link com.kalenjohnson.chronoduo.mods.ModManager#deriveUnits}), {@code up} true for ▲. */
        void onModMoved(String name, boolean up);
    }
    /** Sets (or clears, with null) the host that handles requests from the settings screen -- see {@link SettingsHost}. Delegates to {@link SettingsScreen}. */
    public void setSettingsHost(SettingsHost host) {
        settings.setHost(host);
    }

    // The settings screen (gear chip, full-screen settings sheet, Mods page)
    // lives in its own class; this view only routes touch/d-pad/draw to it
    // and exposes the AppActivity-facing setters below as delegates.
    final SettingsScreen settings;


    /** Delegates to {@link SettingsScreen#setSaveImportStatus}. */
    public void setSaveImportStatus(boolean importing, String message, boolean error) {
        settings.setSaveImportStatus(importing, message, error);
    }

    /** Delegates to {@link SettingsScreen#setModsStatus}. */
    public void setModsStatus(boolean importing, String message, String error) {
        settings.setModsStatus(importing, message, error);
    }

    /** Delegates to {@link SettingsScreen#setModsList}. */
    public void setModsList(java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.ModInfo> mods) {
        settings.setModsList(mods);
    }

    /** Delegates to {@link SettingsScreen#setModCatalog}. */
    public void setModCatalog(java.util.List<com.kalenjohnson.chronoduo.mods.ModCatalog.Entry> entries) {
        settings.setModCatalog(entries);
    }

    /** Delegates to {@link SettingsScreen#setModGroups}. */
    public void setModGroups(java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.ModGroup> groups) {
        settings.setModGroups(groups);
    }

    /** Delegates to {@link SettingsScreen#setImportStatus}. */
    public void setImportStatus(boolean importing, int done, int total, String stage, String error) {
        settings.setImportStatus(importing, done, total, stage, error);
    }


    /** Delegates to {@link SettingsScreen#setOrigArtStatus}. */
    public void setOrigArtStatus(boolean building, int done, int total, String phase, String error) {
        settings.setOrigArtStatus(building, done, total, phase, error);
    }

    private static final int[] PORTRAIT_COLORS = {
            Color.rgb(196, 84, 40), Color.rgb(120, 180, 230), Color.rgb(120, 200, 120),
            Color.rgb(190, 160, 70), Color.rgb(80, 160, 90), Color.rgb(230, 200, 140),
            Color.rgb(110, 80, 180),
    };

    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint portraitPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    // nearest-neighbor, opaque: the settings gear sprite (see drawGearChip)
    final Paint spritePaint = new Paint();
    private final Path speckles = new Path();
    private int speckleW, speckleH;
    // torn-paper outline: dark-edge path is the full parchment rect walked
    // and jittered; paper path is the same walk on the inset paper rect, so
    // the "deckled" fill sits a few px inside the ripped dark edge, same as
    // the old rounded-rect version's 7px inset.
    private final Path tornEdge = new Path();
    final Path tornPaper = new Path();
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
        map = new MapContent(this);
        spritePaint.setFilterBitmap(false);
        spritePaint.setDither(false);
        battle = new BattleChrome(this, context);
        settings = new SettingsScreen(this, context);
        FogOfWar.init(context.getFilesDir());
        // Repaints the badge (see drawFastForwardBadge) whenever the R2
        // trigger/analog binding or the Speed settings page changes
        // GameSpeed's state from outside this view.
        GameSpeed.setListener(this::invalidate);
    }

    /** Toggles and persists the enemy hidden-HP display setting; called from the eye-glyph tap handler in {@link #onTouchEvent}. */

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
        if (settings.isOpen()) return settings.onTouchEvent(event);
        if (action != MotionEvent.ACTION_DOWN) return false;
        // The gear is also drawn on the pre-load wordmark screen (no party
        // yet, so inBattle is necessarily false there) -- imports and art
        // builds don't need a loaded game, so settings must be reachable
        // from the title screen.
        if (!snap.inBattle && settings.gearHit(event.getX(), event.getY())) {
            settings.open();
            return true;
        }
        if (snap.inBattle && snap.targetingActive) {
            for (int i = 0; i < memberBoxCount; i++) {
                if (memberBoxes[i].contains(event.getX(), event.getY())) {
                    return battle.selectTargetSlot(i);
                }
            }
        }
        return battle.onTouchDown(event.getX(), event.getY());
    }


    /**
     * Physical-controller routing (see {@link org.cocos2dx.cpp.AppActivity#dispatchKeyEvent}
     * and {@link GameControllerInput}): the settings sheet takes every
     * d-pad/A press while open, otherwise the battle chrome decides whether
     * the panel-owned command/list selection consumes it.
     *
     * @return true iff this consumed the input -- callers must forward the
     * event to the game unchanged when false.
     */
    public boolean onControllerLeft() {
        if (settings.isOpen()) return settings.settingsNavLeft();
        return battle.onControllerLeft();
    }

    public boolean onControllerRight() {
        if (settings.isOpen()) return settings.settingsNavRight();
        return battle.onControllerRight();
    }

    public boolean onControllerConfirm() {
        if (settings.isOpen()) return settings.settingsNavConfirm();
        return battle.onControllerConfirm();
    }

    public boolean onControllerUp() {
        if (settings.isOpen()) return settings.settingsNavUpInternal();
        return battle.onControllerUp();
    }

    public boolean onControllerDown() {
        if (settings.isOpen()) return settings.settingsNavDownInternal();
        return battle.onControllerDown();
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


    public void update(PartySnapshot s) {
        if (s.inBattle) settings.closeForBattle();
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
        } else if (hadContent && oldMode == ContentMode.FIELD && newMode == ContentMode.FIELD) {
            // staying in FIELD mode: the map side decides whether the
            // location name and/or the area-map bitmap need their own
            // (smaller) crossfades.
            map.onFieldToField(snap, s);
        }
        battle.update(s);
        snap = s;
        invalidate();
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
        map.reset();
        battle.reset();
        settings.close();
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
    float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    void setText(float size, int color, boolean bold, Paint.Align align, boolean shadow) {
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

    void applyTypeface(Typeface fallback) {
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
        int charIdx = (m.id >= 0 && m.id < PartySnapshot.DEFAULT_NAMES.length) ? m.id : -1; // by id, not name: renamed characters keep their face
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
    void drawNinePatch(Canvas c, Bitmap bmp, int srcInset, RectF dst, float destInset) {
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
    void drawParchmentBase(Canvas c, RectF r) {
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
    void drawParchmentOverlay(Canvas c, RectF r) {
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


    // Warm-gold accent used for the default-selected button highlight (see
    // drawCommandButton's highlighted param) -- distinct from the neutral
    // white pressed-feedback tint so the two read as different things even
    // if they ever briefly overlapped.
    static final int DEFAULT_HIGHLIGHT_COLOR = Color.rgb(255, 224, 130);

    /** One command button: 9-sliced window texture (fallback: hand-drawn navy box), centered label, optional pressed-state overlay. */
    void drawCommandButton(Canvas c, RectF box, String label, Bitmap winTex, boolean pressed) {
        drawCommandButton(c, box, label, winTex, pressed, false);
    }

    /**
     * Same as the 4-arg overload, plus {@code highlighted}: a subtle
     * brighter border + faint tint applied when true (and {@code pressed} is
     * false, so it never fights the pressed flash) -- purely cosmetic, carries
     * no targeting/injection meaning. Highlight source is the panel's own
     * {@link #commandSel}, not the game's cursor.
     */
    void drawCommandButton(Canvas c, RectF box, String label, Bitmap winTex, boolean pressed, boolean highlighted) {
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
        settings.drawGearChip(c, w * 0.05f, h * 0.06f);
    }

    /** Dispatches to whichever parchment content (battle vs map vs field-title) {@code s} calls for. */
    private void drawContent(Canvas c, PartySnapshot s, RectF parchment, boolean live) {
        if (s.inBattle) {
            battle.drawBattleContent(c, parchment, s, live);
            return;
        }
        map.drawContent(c, parchment, s, live);
    }

    /** Overworld: title, world map bitmap (or nothing, if not extracted yet), and the live position marker. */

    @Override
    protected void onDraw(Canvas c) {
        // Settings first: it draws its own parchment and needs no party, so
        // it works from the pre-load wordmark screen as well.
        if (settings.isOpen()) {
            settings.drawSettingsScreen(c);
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

        boolean animating = battle.advanceEnemyBarFractions();

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

        if (map.isAnimating()) animating = true; // title / area-map crossfades
        if (FogOfWar.isAnimating()) animating = true; // newly revealed fog cells fading in
        if (battle.isAnimating()) animating = true; // results fade, victory-pose wait, press flashes

        // aged-paper vignette/speckles/frame ON TOP of the map so it reads
        // as ink on old parchment rather than a clean printed minimap
        drawParchmentOverlay(c, parchment);

        // Everything interactive -- top-strip chips (fast-forward badge, gear/
        // AUTO/eye crossfade) and the battle bands (command / targeting /
        // submenu list / results) -- is drawn here, on top of the aged-paper
        // overlay, so the vignette and ink frame never strike through it.
        if (battle.drawOverlay(c, parchment)) animating = true;

        // DS-style status boxes along the top, one per party member (n > 0
        // here — onDraw returns early via drawWordmark() otherwise)
        int n = snap.members.size();
        float boxH = h * 0.145f;
        float boxW = Math.min(w * 0.31f, (w - pad * (n + 1)) / n);
        float x = pad;
        memberBoxCount = 0;
        for (int i = 0; i < n; i++) {
            PartySnapshot.Member m = snap.members.get(i);
            drawStatusBox(c, m, x, pad, boxW, boxH);
            // Party member i is battle actor slot i (see PartySnapshot.read),
            // so an ally-targeting tech/item highlights its box here.
            if (snap.inBattle && BattleChrome.isTargetSlot(snap, i)) {
                RectF box = new RectF(x - 3f, pad - 3f, x + boxW + 3f, pad + boxH + 3f);
                battle.drawTargetHighlight(c, box);
            }
            if (i < memberBoxes.length) {
                memberBoxes[i].set(x, pad, x + boxW, pad + boxH);
                memberBoxCount = i + 1;
            }
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

}
