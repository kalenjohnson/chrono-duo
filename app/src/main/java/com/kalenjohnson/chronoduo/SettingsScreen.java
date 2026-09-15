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
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.io.File;

/**
 * The bottom screen's settings side: the gear chip that opens it, the
 * full-screen settings sheet (tab rail + Imports / Graphics / Maps / Speed /
 * Mods pages), its touch and d-pad handling, the Mods page's row model, and
 * the AppActivity-pushed import/build/mod status it displays. Split out of
 * {@link PartyPanelView} so the settings UI can evolve without touching the
 * battle/map panel code; the view still owns the canvas, shared paints and
 * chrome helpers (parchment, window-texture buttons, text setup), which this
 * class reaches through same-named private delegates below. Drawn instead of
 * everything else while {@link #isOpen()} -- see {@link PartyPanelView#onDraw}.
 */
final class SettingsScreen {
    private final PartyPanelView view;
    // Shared with the view (same Paint objects): see PartyPanelView's fields.
    private final Paint fill, stroke, text, spritePaint;
    private final Path tornPaper;
    private static final int INK = PartyPanelView.INK;
    private static final int BOX_BG = PartyPanelView.BOX_BG;
    private static final int BOX_BG_DARK = PartyPanelView.BOX_BG_DARK;
    private static final int BOX_BORDER_OUT = PartyPanelView.BOX_BORDER_OUT;
    private static final int BOX_BORDER_IN = PartyPanelView.BOX_BORDER_IN;
    private static final String PREFS_NAME = PartyPanelView.PREFS_NAME;

    SettingsScreen(PartyPanelView view, Context context) {
        this.view = view;
        this.fill = view.fill;
        this.stroke = view.stroke;
        this.text = view.text;
        this.spritePaint = view.spritePaint;
        this.tornPaper = view.tornPaper;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        pixelGraphicsOn = GameState.getPixelGraphicsPref(context);
        trueWidescreenOn = GameState.getTrueWidescreenPref(context);
        fogOn = prefs.getBoolean(KEY_FOG_ON, true);
    }

    private PartyPanelView.SettingsHost settingsHost;

    // ---- state (moved verbatim from PartyPanelView) ----------------------------

    // Settings "gear" chip, top-left corner: a small CT menu window (the
    // game's own window texture via drawNinePatch, same chrome as the battle
    // command buttons) holding a hand-drawn 17x17 pixel-art gear sprite,
    // upscaled nearest-neighbor at an integer factor so it reads as game
    // pixels rather than a vector icon. Shown in field/overworld modes and on
    // the pre-load wordmark screen (never battle; the eye toggle owns that
    // corner there, and mid-battle isn't a sane time to open settings).
    // Tapping it enters settingsMode. See drawGearChip.
    final RectF gearHitBox = new RectF(); // cleared by PartyPanelView.onDraw in battle
    static final float GEAR_CHIP_SIZE = 96f;   // window chip side, px
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
    private boolean settingsMode; // see isOpen()/open()/close()
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
    void setSaveImportStatus(boolean importing, String message, boolean error) {
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
    static final float MOD_ROW_BUTTON_H_DP = 48f;
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
    void setModsStatus(boolean importing, String message, String error) {
        this.modsImporting = importing;
        this.modsMessage = message;
        this.modsError = error;
        invalidate();
    }

    /** Pushes the current installed-mod list (see {@link com.kalenjohnson.chronoduo.mods.ModManager#lastMods}) to the settings screen; called from AppActivity on the main thread after every scan/import/toggle. */
    void setModsList(java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.ModInfo> mods) {
        this.modsList = mods != null ? mods : java.util.Collections.emptyList();
        invalidate();
    }

    /** Pushes the curated mod catalog (see {@link com.kalenjohnson.chronoduo.mods.ModCatalog}) to the settings screen; called from AppActivity on the main thread once the background load/refresh (bundled, cached, or freshly fetched) finishes. */
    void setModCatalog(java.util.List<com.kalenjohnson.chronoduo.mods.ModCatalog.Entry> entries) {
        this.modCatalog = entries != null ? entries : java.util.Collections.emptyList();
        invalidate();
    }

    /** Pushes the current multi-.ctp download groupings (see {@link com.kalenjohnson.chronoduo.mods.ModManager#groups}) to the settings screen; called from AppActivity alongside {@link #setModsList} after every scan/import/toggle/option-select. */
    void setModGroups(java.util.List<com.kalenjohnson.chronoduo.mods.ModManager.ModGroup> groups) {
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

    // True widescreen toggle row (per-scene design canvas: full 224-row SNES
    // frame in fields and on the overworld -- see GameState.
    // setTrueWidescreenPref / gamestate.c "Design zoom"). Applies on the
    // next app launch only, so the row just flips the pref.
    private boolean trueWidescreenOn;
    private final RectF trueWidescreenHitBox = new RectF();

    // Dungeon fog-of-war toggle row (see FogOfWar) -- same persistence/hit-
    // test shape as pixelGraphicsOn above, different key, default On (the DS
    // reveals dungeon minimaps as you walk; towns/houses are unaffected --
    // see AreaMapCalib.isFogged). "Reset explored maps" wipes all saved
    // reveal state via FogOfWar.clearAll().
    private static final String KEY_FOG_ON = "fog_on";
    boolean fogOn; // read by PartyPanelView's area-map fog code too
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
    void setImportStatus(boolean importing, int done, int total, String stage, String error) {
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
    void setOrigArtStatus(boolean building, int done, int total, String phase, String error) {
        this.origArtBuilding = building;
        this.origArtDone = done;
        this.origArtTotal = total;
        this.origArtPhase = phase;
        this.origArtError = error;
        invalidate();
    }


    // ---- host-facing API ------------------------------------------------------

    /** True while the settings sheet is up (drawn instead of the panel; swallows all touch/d-pad input). */
    boolean isOpen() {
        return settingsMode;
    }

    /** Opens the sheet with the d-pad cursor on the current page's rail tab -- the gear-chip tap. */
    void open() {
        settingsMode = true;
        settingsFocusArea = SETTINGS_FOCUS_RAIL;
        settingsRailFocus = Math.floorMod(settingsPage, SETTINGS_PAGES.length);
        settingsContentFocus = 0;
        invalidate();
    }

    /** Closes the sheet (Back, or the view detaching), resetting the Mods list scroll. */
    void close() {
        settingsMode = false;
        modScrollY = 0f;
    }

    /**
     * Called from {@link PartyPanelView#update} when a battle starts: the
     * gear chip is never shown in battle -- if settings happens to be open
     * (e.g. an ambush), get out of the way rather than block the battle UI
     * on the bottom screen.
     */
    void closeForBattle() {
        if (settingsMode) close();
    }

    /** True when ({@code x}, {@code y}) is inside the gear chip's hit box (empty whenever the chip isn't drawn). */
    boolean gearHit(float x, float y) {
        return !gearHitBox.isEmpty() && gearHitBox.contains(x, y);
    }

    void setHost(PartyPanelView.SettingsHost host) {
        settingsHost = host;
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
    private void drawParchmentBase(Canvas c, RectF r) { view.drawParchmentBase(c, r); }
    private void drawParchmentOverlay(Canvas c, RectF r) { view.drawParchmentOverlay(c, r); }

    // ---- touch / d-pad ------------------------------------------------------

    /**
     * Settings-mode touch handling, called by {@link PartyPanelView#onTouchEvent}
     * only while {@link #isOpen()}: the Mods list drag-to-scroll state
     * machine first (it needs MOVE/UP), then DOWN-only tap dispatch over
     * every settings hit box. Always consumes the event while the sheet
     * is up.
     */
    boolean onTouchEvent(MotionEvent event) {
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
        // DOWN-only tap dispatch (or a non-drag UP from the machine above).
        {
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
            if (!trueWidescreenHitBox.isEmpty()
                    && trueWidescreenHitBox.contains(event.getX(), event.getY())) {
                trueWidescreenOn = !trueWidescreenOn;
                GameState.setTrueWidescreenPref(getContext(), trueWidescreenOn);
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
    }

    /**
     * Settings-mode d-pad focus (see the settingsFocus* fields' javadoc
     * above): LEFT returns from the content area to the rail, unless the
     * focused content item is a Mods option-cycle picker, in which case it
     * steps that picker's choice back instead.
     */
    boolean settingsNavLeft() {
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
    boolean settingsNavRight() {
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
    boolean settingsNavUpInternal() {
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
    boolean settingsNavDownInternal() {
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
    boolean settingsNavConfirm() {
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

    // ---- gear chip, status text, drawing (moved verbatim) -----------------------

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

    /**
     * Settings gear chip in the parchment's top-left corner, inset just
     * inside the aged-paper overlay's ink frame line (see {@link
     * #drawParchmentOverlay}). Drawn only outside battle (see {@link
     * #onDraw}); tapping it (see {@link #onTouchEvent}) enters {@link
     * #settingsMode}.
     */
    void drawGearChip(Canvas c, RectF parchment) {
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
    void drawGearChip(Canvas c, float left, float top) {
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
    void drawSettingsScreen(Canvas c) {
        int w = getWidth(), h = getHeight();
        // Full-screen sheet: fills the view minus a small torn-paper border,
        // rather than the map-panel-sized rect the normal panel uses.
        float pad = w * 0.012f;
        RectF parchment = new RectF(pad, pad, w - pad, h - pad);
        drawParchmentBase(c, parchment);

        importButtonHitBox.setEmpty();
        saveImportButtonHitBox.setEmpty();
        pixelGraphicsHitBox.setEmpty();
        trueWidescreenHitBox.setEmpty();
        fogToggleHitBox.setEmpty();
        fogResetHitBox.setEmpty();
        origArtButtonHitBox.setEmpty();
        ffSpeedHitBox.setEmpty();
        ffModeHitBox.setEmpty();
        view.battle.ffHitBox.setEmpty(); // the badge is not tappable under the sheet
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
        RectF btnA = null, btnB = null, btnC = null;
        String labelA = null, labelB = null, labelC = null;
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
                y = btnB.bottom + h * 0.06f;

                y = drawSettingsHeading(c, "True widescreen", left, y, h);
                y = drawSettingsBody(c, "Shows the full SNES frame (all 224 rows) plus the "
                        + "extra 16:9 width in fields, battles and on the overworld. "
                        + "Takes effect after a game restart.",
                        left, y, textW, h, inkDim);
                btnC = new RectF(left, y + h * 0.01f, left + btnW, y + h * 0.01f + btnH);
                labelC = "True widescreen: " + (trueWidescreenOn ? "On" : "Off");
                settingsFocusRects.add(btnC);
                settingsFocusActivate.add(() -> {
                    trueWidescreenOn = !trueWidescreenOn;
                    GameState.setTrueWidescreenPref(getContext(), trueWidescreenOn);
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
            drawSettingsButton(c, btnC, labelC, winTex, false, trueWidescreenHitBox);
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
}
