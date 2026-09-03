package com.kalenjohnson.chronoduo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.Random;

/**
 * DS-style bottom screen: compact navy HP/MP boxes in the top-left (portrait,
 * HP xx/ xx, MP xx/ xx) and a parchment map panel as the centerpiece, showing
 * the live location name until real map art is extracted from resources.bin.
 */
public final class PartyPanelView extends View {
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
    private final Path speckles = new Path();
    private int speckleW, speckleH;

    public PartyPanelView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
    }

    public void update(PartySnapshot s) {
        snap = s;
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

    /** DS status box: double border, navy interior, portrait, HP/MP rows. */
    private void drawStatusBox(Canvas c, PartySnapshot.Member m, float l, float t, float w, float h) {
        RectF box = new RectF(l, t, l + w, t + h);
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

        // portrait square (placeholder color + initial until real art)
        float ppad = h * 0.1f;
        RectF portrait = new RectF(box.left + ppad, box.top + ppad,
                box.left + ppad + (h - 2 * ppad), box.bottom - ppad);
        fill.setColor(Color.BLACK);
        c.drawRect(portrait, fill);
        RectF pin = new RectF(portrait);
        pin.inset(2, 2);
        int charIdx = indexOfName(m.name);
        fill.setColor(charIdx >= 0 ? PORTRAIT_COLORS[charIdx] : Color.DKGRAY);
        c.drawRect(pin, fill);
        setText(h * 0.5f, Color.argb(210, 0, 0, 0), true, Paint.Align.CENTER, false);
        c.drawText(m.name.substring(0, 1), portrait.centerX(), portrait.centerY() + h * 0.18f, text);

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

    /** Parchment map panel with rough dark edge and aged-paper speckling. */
    private void drawParchment(Canvas c, RectF r) {
        fill.setShader(null);
        fill.setColor(PAPER_EDGE);
        c.drawRoundRect(r, 26, 26, fill);
        RectF paper = new RectF(r);
        paper.inset(7, 7);
        fill.setColor(PAPER);
        c.drawRoundRect(paper, 20, 20, fill);
        // aged vignette toward the edges
        fill.setShader(new RadialGradient(paper.centerX(), paper.centerY(),
                Math.max(paper.width(), paper.height()) * 0.62f,
                new int[]{0x00000000, 0x33000000 & (PAPER_DARK | 0xff000000)},
                null, Shader.TileMode.CLAMP));
        fill.setShader(new RadialGradient(paper.centerX(), paper.centerY(),
                Math.max(paper.width(), paper.height()) * 0.62f,
                new int[]{Color.TRANSPARENT, Color.argb(70, 60, 40, 10)},
                null, Shader.TileMode.CLAMP));
        c.drawRoundRect(paper, 20, 20, fill);
        fill.setShader(null);
        // speckles
        buildSpeckles(getWidth(), getHeight());
        fill.setColor(Color.argb(26, 80, 55, 20));
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
        drawParchment(c, parchment);

        // location name inked on the parchment
        String title = snap.mapName == null || snap.mapName.isEmpty()
                ? (snap.members.isEmpty() ? "— uncharted —" : "World Map")
                : snap.mapName;
        setText(h * 0.045f, INK, true, Paint.Align.CENTER, false);
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        c.drawText(title, parchment.centerX(), parchment.top + h * 0.085f, text);
        // red position marker (waiting for real coordinates)
        float mx = parchment.centerX(), my = parchment.centerY() + h * 0.03f;
        fill.setColor(Color.rgb(210, 50, 70));
        Path marker = new Path();
        float ms = h * 0.016f;
        marker.moveTo(mx, my - ms);
        marker.lineTo(mx + ms, my);
        marker.lineTo(mx, my + ms);
        marker.lineTo(mx - ms, my);
        marker.close();
        c.drawPath(marker, fill);

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

    private static int indexOfName(String name) {
        for (int i = 0; i < PartySnapshot.DEFAULT_NAMES.length; i++) {
            if (PartySnapshot.DEFAULT_NAMES[i].equals(name)) return i;
        }
        return -1;
    }
}
