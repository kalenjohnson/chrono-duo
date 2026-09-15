package com.kalenjohnson.chronoduo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * The bottom screen's map side: the overworld world-map panel with the
 * party/Epoch markers, the field-location panel with its DS-style area
 * minimap, dungeon fog-of-war and position marker, and the small
 * crossfades between location titles and between area-map bitmaps. Split
 * out of {@link PartyPanelView}, which still owns the canvas, the parchment,
 * the mode crossfade between map and battle content, and the shared paints
 * and text helpers this class reaches through same-named private delegates.
 */
final class MapContent {
    private final PartyPanelView view;
    // Shared with the view (same Paint objects): see PartyPanelView's fields.
    private final Paint fill, text;
    private static final int INK = PartyPanelView.INK;
    private static final long TITLE_FADE_NANOS = PartyPanelView.TITLE_FADE_NANOS;

    MapContent(PartyPanelView view) {
        this.view = view;
        this.fill = view.fill;
        this.text = view.text;
        mapPaint.setFilterBitmap(false);
        mapPaint.setDither(false);
        // sepia map is composited over the parchment at ~88% so the paper
        // ground still shows through, per the weathered-map look
        mapPaint.setAlpha(225);
        markerPaint.setFilterBitmap(false);
        markerPaint.setDither(false);
    }

    private int getWidth() { return view.getWidth(); }
    private int getHeight() { return view.getHeight(); }
    private void setText(float size, int color, boolean bold, Paint.Align align, boolean shadow) {
        view.setText(size, color, bold, align, shadow);
    }
    private void applyTypeface(Typeface fallback) { view.applyTypeface(fallback); }

    // ---- state (moved verbatim from PartyPanelView) ----------------------------

    private String fadingOutTitle;
    private long titleFadeStart = -1L;
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
    // Last snapshot taken with the WorldScene live; reused to keep drawing
    // the overworld panel while the menu covers the world map.
    private PartySnapshot lastOverworldSnap;

    // ---- host-facing API ------------------------------------------------------

    /**
     * Called from {@link PartyPanelView#update} when the panel stays in
     * FIELD mode across a snapshot: a changed location name starts the
     * title micro-fade; a changed area-map id starts the bitmap crossfade.
     * The two are independent (a name can change with the id unchanged and
     * vice versa), hence two separate checks rather than an else-if.
     */
    void onFieldToField(PartySnapshot snap, PartySnapshot s) {
        if (!snap.mapName.equals(s.mapName)) {
            // staying in FIELD mode but the location name itself changed:
            // the smaller title-only micro-fade, not the full mode crossfade.
            fadingOutTitle = snap.mapName;
            titleFadeStart = System.nanoTime();
        }
        if (snap.fieldMapId != s.fieldMapId) {
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
    }

    /** Drops in-flight fade state on view detach so a re-attach starts clean. */
    void reset() {
        titleFadeStart = -1L;
        fadingOutTitle = null;
        prevAreaMapBitmap = null;
        prevAreaMapId = -1;
        areaMapFadeStart = -1L;
    }

    /** True while a title or area-map crossfade is still running. */
    boolean isAnimating() {
        return titleFadeStart >= 0 || areaMapFadeStart >= 0;
    }

    /** Non-battle parchment content: the overworld map when the WorldScene is live (or the menu is open over it), else the field location panel. */
    void drawContent(Canvas c, RectF parchment, PartySnapshot s, boolean live) {
        // Overworld only when the game's WorldScene is actually live. A
        // nameless field scene (e.g. story cutscenes with no location) used
        // to be mistaken for the overworld and showed a map that didn't apply.
        boolean overworld = s.worldScenePresent;
        if (overworld) lastOverworldSnap = s;
        boolean nameless = s.mapName == null || s.mapName.isEmpty();
        String title = overworld ? "World Map" : (nameless ? "" : s.mapName);
        if (overworld) {
            drawOverworldContent(c, parchment, s, title);
        } else if (PartyPanelView.isWorldMapLocation(s.fieldMapId) && lastOverworldSnap != null) {
            // menu open on the overworld: keep the last live overworld view
            drawOverworldContent(c, parchment, lastOverworldSnap, "World Map");
        } else {
            drawFieldContent(c, parchment, s, title, live);
        }
    }

    // ---- drawing (moved verbatim) -------------------------------------------

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
        if (!view.settings.fogOn || s.fieldMapId < 0) return;
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
        if (raw == null || !view.settings.fogOn || roomId < 0) return raw;
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
}
