package com.kalenjohnson.chronoduo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Party status panel styled after Chrono Trigger's own menu windows
 * (DS bottom-screen look): navy gradient panels with a silver beveled frame,
 * white drop-shadowed text, numeric HP/MP like the in-game menu.
 */
public final class PartyPanelView extends View {
    private PartySnapshot snap = new PartySnapshot();

    // classic CT window palette
    private static final int FRAME_LIGHT = Color.rgb(232, 232, 240);
    private static final int FRAME_MID = Color.rgb(140, 140, 156);
    private static final int FRAME_DARK = Color.rgb(40, 40, 48);
    private static final int WINDOW_TOP = Color.rgb(66, 70, 160);
    private static final int WINDOW_BOTTOM = Color.rgb(14, 14, 56);
    private static final int TEXT_MAIN = Color.WHITE;
    private static final int TEXT_LABEL = Color.rgb(200, 205, 230);

    private static final int[] PORTRAIT_COLORS = {
            Color.rgb(196, 84, 40),   // Crono — red hair
            Color.rgb(120, 180, 230), // Marle — light blue
            Color.rgb(120, 200, 120), // Lucca — green tunic
            Color.rgb(190, 160, 70),  // Robo — gold
            Color.rgb(80, 160, 90),   // Frog — green
            Color.rgb(230, 200, 140), // Ayla — sand
            Color.rgb(110, 80, 180),  // Magus — purple
    };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PartyPanelView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
    }

    public void update(PartySnapshot s) {
        snap = s;
        invalidate();
    }

    private void drawCtWindow(Canvas c, RectF r) {
        float rad = 14;
        // silver bevel: light outer frame, mid line, dark inner line
        fill.setShader(null);
        fill.setColor(FRAME_LIGHT);
        c.drawRoundRect(r, rad, rad, fill);
        RectF inner = new RectF(r.left + 4, r.top + 4, r.right - 4, r.bottom - 4);
        fill.setColor(FRAME_DARK);
        c.drawRoundRect(inner, rad - 3, rad - 3, fill);
        inner.inset(2, 2);
        fill.setShader(new LinearGradient(0, inner.top, 0, inner.bottom,
                WINDOW_TOP, WINDOW_BOTTOM, Shader.TileMode.CLAMP));
        c.drawRoundRect(inner, rad - 4, rad - 4, fill);
        fill.setShader(null);
        // subtle mid-tone edge between light frame and dark line
        stroke.setStrokeWidth(1.5f);
        stroke.setColor(FRAME_MID);
        c.drawRoundRect(new RectF(r.left + 3, r.top + 3, r.right - 3, r.bottom - 3),
                rad - 2, rad - 2, stroke);
    }

    private void setText(float size, int color, boolean bold, Paint.Align align) {
        text.setTextSize(size);
        text.setColor(color);
        text.setTypeface(bold ? Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                : Typeface.DEFAULT);
        text.setTextAlign(align);
        text.setShadowLayer(3, 2, 2, Color.argb(200, 0, 0, 0));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        int n = snap.members.size();
        float pad = w * 0.025f;

        if (n == 0) {
            RectF box = new RectF(w * 0.2f, h * 0.4f, w * 0.8f, h * 0.6f);
            drawCtWindow(c, box);
            setText(h * 0.04f, TEXT_MAIN, true, Paint.Align.CENTER);
            c.drawText("CHRONO DUO", w / 2f, box.centerY() - h * 0.005f, text);
            setText(h * 0.025f, TEXT_LABEL, false, Paint.Align.CENTER);
            c.drawText("waiting for party data…", w / 2f, box.centerY() + h * 0.045f, text);
            return;
        }

        float footerH = h * 0.085f;
        float cardH = (h - footerH - pad * (n + 2)) / n;
        if (cardH > h * 0.3f) cardH = h * 0.3f;
        float top = pad;

        for (PartySnapshot.Member m : snap.members) {
            RectF card = new RectF(pad, top, w - pad, top + cardH);
            drawCtWindow(c, card);

            float ih = cardH; // card height drives all metrics
            // portrait placeholder: inset box with character initial
            float ppad = ih * 0.12f;
            RectF portrait = new RectF(card.left + ppad, card.top + ppad,
                    card.left + ppad + (ih - 2 * ppad), card.bottom - ppad);
            fill.setColor(FRAME_DARK);
            c.drawRoundRect(portrait, 8, 8, fill);
            RectF pin = new RectF(portrait);
            pin.inset(4, 4);
            int charIdx = indexOfName(m.name);
            fill.setColor(charIdx >= 0 ? PORTRAIT_COLORS[charIdx] : Color.DKGRAY);
            c.drawRoundRect(pin, 6, 6, fill);
            setText(ih * 0.42f, Color.argb(230, 0, 0, 0), true, Paint.Align.CENTER);
            text.clearShadowLayer();
            c.drawText(m.name.substring(0, 1), portrait.centerX(),
                    portrait.centerY() + ih * 0.15f, text);

            float tx = portrait.right + ih * 0.18f;
            // name + level line
            setText(ih * 0.3f, TEXT_MAIN, true, Paint.Align.LEFT);
            c.drawText(m.name, tx, card.top + ih * 0.38f, text);
            setText(ih * 0.2f, TEXT_LABEL, false, Paint.Align.LEFT);
            c.drawText("Lv." + m.level, tx + w * 0.28f, card.top + ih * 0.38f, text);

            // HP / MP numeric rows, right-aligned values like the in-game menu
            float rowY1 = card.top + ih * 0.66f;
            float rowY2 = card.top + ih * 0.9f;
            float valRight = card.right - ih * 0.3f;
            setText(ih * 0.2f, TEXT_LABEL, false, Paint.Align.LEFT);
            c.drawText("HP", tx, rowY1, text);
            c.drawText("MP", tx, rowY2, text);
            setText(ih * 0.22f, TEXT_MAIN, false, Paint.Align.RIGHT);
            text.setTypeface(Typeface.MONOSPACE);
            c.drawText(String.format("%3d/%3d", m.curHp, m.maxHp), valRight, rowY1, text);
            c.drawText(String.format("%3d/%3d", m.curMp, m.maxMp), valRight, rowY2, text);

            // thin HP pips under the numbers, quiet nod to at-a-glance health
            float frac = m.maxHp > 0 ? Math.min(1f, (float) m.curHp / m.maxHp) : 0;
            float barL = tx, barR = valRight, barY = rowY1 + ih * 0.045f;
            stroke.setStrokeWidth(ih * 0.03f);
            stroke.setColor(Color.argb(90, 0, 0, 0));
            c.drawLine(barL, barY, barR, barY, stroke);
            stroke.setColor(frac > 0.5f ? Color.rgb(120, 220, 130)
                    : frac > 0.2f ? Color.rgb(240, 210, 80) : Color.rgb(240, 90, 70));
            c.drawLine(barL, barY, barL + (barR - barL) * frac, barY, stroke);

            top += cardH + pad;
        }

        // footer: gold (left window) and play time (right window)
        float fy = h - footerH - pad * 0.5f;
        RectF goldBox = new RectF(pad, fy, w * 0.42f, h - pad * 0.5f);
        RectF timeBox = new RectF(w * 0.58f, fy, w - pad, h - pad * 0.5f);
        drawCtWindow(c, goldBox);
        drawCtWindow(c, timeBox);
        int s = snap.playSeconds;
        String time = String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        setText(footerH * 0.42f, TEXT_MAIN, false, Paint.Align.RIGHT);
        text.setTypeface(Typeface.MONOSPACE);
        c.drawText(snap.gold + " G", goldBox.right - footerH * 0.3f,
                goldBox.centerY() + footerH * 0.15f, text);
        c.drawText(time, timeBox.right - footerH * 0.3f,
                timeBox.centerY() + footerH * 0.15f, text);
    }

    private static int indexOfName(String name) {
        for (int i = 0; i < PartySnapshot.DEFAULT_NAMES.length; i++) {
            if (PartySnapshot.DEFAULT_NAMES[i].equals(name)) return i;
        }
        return -1;
    }
}
