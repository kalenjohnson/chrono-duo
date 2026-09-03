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
import android.view.View;

import java.util.Random;

/**
 * DS-style bottom screen: compact navy HP/MP boxes in the top-left (portrait,
 * HP xx/ xx, MP xx/ xx) and a parchment map panel as the centerpiece, showing
 * the live location name and — once ChronoAssets finishes its background
 * extraction from resources.bin — the game's own portrait and world-map art.
 * Colored-initial portraits and a hand-drawn marker remain as fallbacks when
 * that art isn't available yet (or at all).
 */
public final class PartyPanelView extends View implements ChronoAssets.Listener {
    // face.png layout: 4x2 grid of 96x88 tiles, char-id order (Crono..Magus,
    // Epoch); char ids 0..6 line up with PartySnapshot.DEFAULT_NAMES.
    private static final int FACE_TILE_W = 96;
    private static final int FACE_TILE_H = 88;
    private static final int FACE_COLS = 4;

    private PartySnapshot snap = new PartySnapshot();

    // DS status box palette (navy window, light double border)
    private static final int BOX_BG = Color.rgb(32, 40, 96);
    private static final int BOX_BG_DARK = Color.rgb(16, 20, 56);
    private static final int BOX_BORDER_OUT = Color.rgb(222, 222, 230);
    private static final int BOX_BORDER_IN = Color.rgb(90, 96, 150);
    // parchment palette
    private static final int PAPER = Color.rgb(214, 197, 158);
    private static final int PAPER_DARK = Color.rgb(150, 128, 88);
    private static final int PAPER_EDGE = Color.rgb(94, 74, 44);
    private static final int INK = Color.rgb(96, 72, 40);

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
    private final Path speckles = new Path();
    private int speckleW, speckleH;

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
    }

    public void update(PartySnapshot s) {
        snap = s;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ChronoAssets.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
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
        // colored-initial placeholder
        float ppad = h * 0.1f;
        RectF portrait = new RectF(box.left + ppad, box.top + ppad,
                box.left + ppad + (h - 2 * ppad), box.bottom - ppad);
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
        float valRight = box.right - w * 0.03f;
        float row1 = box.top + h * 0.42f;
        float row2 = box.top + h * 0.82f;
        float fs = h * 0.26f;
        setText(fs, Color.rgb(190, 200, 255), true, Paint.Align.LEFT, true);
        c.drawText("HP", tx, row1, text);
        c.drawText("MP", tx, row2, text);
        setText(fs, Color.WHITE, false, Paint.Align.RIGHT, true);
        c.drawText(m.curHp + "/" + m.maxHp, valRight, row1, text);
        c.drawText(m.curMp + "/" + m.maxMp, valRight, row2, text);
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

    /** Base parchment sheet (dark edge + paper fill) only, drawn before the map so the aging overlay below can sit on top of it. */
    private void drawParchmentBase(Canvas c, RectF r) {
        fill.setShader(null);
        fill.setColor(PAPER_EDGE);
        c.drawRoundRect(r, 26, 26, fill);
        RectF paper = new RectF(r);
        paper.inset(7, 7);
        fill.setColor(PAPER);
        c.drawRoundRect(paper, 20, 20, fill);
    }

    /**
     * Aged-paper overlay (edge vignette, ink speckles, inner frame line),
     * drawn ON TOP of the map/title/marker so the whole parchment — map
     * included — reads as ink on aged paper rather than a clean printout.
     */
    private void drawParchmentOverlay(Canvas c, RectF r) {
        RectF paper = new RectF(r);
        paper.inset(7, 7);
        // aged vignette toward the edges (strengthened so it reads through the map)
        fill.setShader(new RadialGradient(paper.centerX(), paper.centerY(),
                Math.max(paper.width(), paper.height()) * 0.62f,
                new int[]{Color.TRANSPARENT, Color.argb(90, 60, 40, 10)},
                null, Shader.TileMode.CLAMP));
        c.drawRoundRect(paper, 20, 20, fill);
        fill.setShader(null);
        // speckles
        buildSpeckles(getWidth(), getHeight());
        fill.setColor(Color.argb(34, 80, 55, 20));
        c.save();
        c.clipRect(paper);
        c.drawPath(speckles, fill);
        c.restore();
        // inner ink frame line, hand-drawn-map vibe
        stroke.setStrokeWidth(2.5f);
        stroke.setColor(Color.argb(90, 96, 72, 40));
        RectF frame = new RectF(paper);
        frame.inset(14, 14);
        c.drawRoundRect(frame, 12, 12, stroke);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float pad = w * 0.02f;

        // parchment fills most of the screen, like the DS map view
        RectF parchment = new RectF(pad * 3, h * 0.2f, w - pad * 3, h - pad * 2.2f);
        drawParchmentBase(c, parchment);

        // PartySnapshot.mapName is empty on the overworld (the world map is
        // meaningful there) and non-empty inside a field location (house,
        // Leene Square, etc. — the DS game doesn't show the world map
        // there, and neither should this view).
        boolean overworld = snap.mapName == null || snap.mapName.isEmpty();
        String title = overworld
                ? (snap.members.isEmpty() ? "— uncharted —" : "World Map")
                : snap.mapName;

        if (overworld) {
            // small title above the map
            setText(h * 0.045f, INK, true, Paint.Align.CENTER, false);
            text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            c.drawText(title, parchment.centerX(), parchment.top + h * 0.085f, text);

            float mx = parchment.centerX(), my = parchment.centerY() + h * 0.03f;
            Bitmap map = ChronoAssets.getWorldMap();
            if (map != null) {
                // area between the title and the gold/time corner text
                RectF area = new RectF(parchment.left + w * 0.06f, parchment.top + h * 0.13f,
                        parchment.right - w * 0.06f, parchment.bottom - h * 0.09f);
                // single uniform scale factor applied to both dimensions —
                // this is what keeps the map's aspect ratio intact (letterboxed
                // inside `area`) instead of stretching it to fill the box.
                float scale = Math.min(area.width() / map.getWidth(), area.height() / map.getHeight());
                float dw = map.getWidth() * scale, dh = map.getHeight() * scale;
                RectF dst = new RectF(area.centerX() - dw / 2f, area.centerY() - dh / 2f,
                        area.centerX() + dw / 2f, area.centerY() + dh / 2f);
                c.drawBitmap(map, null, dst, mapPaint);
                mx = dst.centerX();
                my = dst.centerY();
            }

            // placeholder "current position" pin at the map's center — we
            // don't have real player coordinates yet, so this is a stand-in
            // rather than an actual fix, same spirit as the old hand-drawn
            // diamond it replaces when real art is loaded
            Bitmap mark = ChronoAssets.getMinimapMark();
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
        } else {
            // field location: no map, just the location name as the
            // parchment's centerpiece, large and vertically centered
            setText(h * 0.075f, INK, true, Paint.Align.CENTER, false);
            text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            c.drawText(title, parchment.centerX(), parchment.centerY() + h * 0.025f, text);
        }

        // aged-paper vignette/speckles/frame ON TOP of the map so it reads
        // as ink on old parchment rather than a clean printed minimap
        drawParchmentOverlay(c, parchment);

        // DS-style status boxes along the top, one per party member
        int n = snap.members.size();
        if (n > 0) {
            float boxH = h * 0.145f;
            float boxW = Math.min(w * 0.31f, (w - pad * (n + 1)) / n);
            float x = pad;
            for (PartySnapshot.Member m : snap.members) {
                drawStatusBox(c, m, x, pad, boxW, boxH);
                x += boxW + pad;
            }
        } else {
            setText(h * 0.03f, Color.WHITE, true, Paint.Align.LEFT, true);
            c.drawText("CHRONO DUO — waiting for party…", pad, pad + h * 0.045f, text);
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
