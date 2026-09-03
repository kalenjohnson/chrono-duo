package com.kalenjohnson.chronoduo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** Draws the party status panel on the second screen. */
public final class PartyPanelView extends View {
    private PartySnapshot snap = new PartySnapshot();

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PartyPanelView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(12, 12, 20));
        cardPaint.setColor(Color.rgb(28, 30, 52));
        cardEdge.setStyle(Paint.Style.STROKE);
        cardEdge.setStrokeWidth(3);
        cardEdge.setColor(Color.rgb(90, 96, 160));
        namePaint.setColor(Color.WHITE);
        namePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        labelPaint.setColor(Color.rgb(150, 150, 170));
        labelPaint.setTypeface(Typeface.MONOSPACE);
        valuePaint.setColor(Color.rgb(230, 230, 240));
        valuePaint.setTypeface(Typeface.MONOSPACE);
        barBg.setColor(Color.rgb(20, 20, 30));
        hintPaint.setColor(Color.rgb(110, 110, 130));
        hintPaint.setTypeface(Typeface.MONOSPACE);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void update(PartySnapshot s) {
        snap = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        int n = snap.members.size();
        if (n == 0) {
            hintPaint.setTextSize(h * 0.045f);
            c.drawText("CHRONO DUO", w / 2f, h * 0.46f, hintPaint);
            hintPaint.setTextSize(h * 0.028f);
            c.drawText("waiting for party data…", w / 2f, h * 0.54f, hintPaint);
            return;
        }
        float pad = w * 0.03f;
        float cardH = Math.min((h - pad) / n - pad, h * 0.28f);
        float top = pad;
        for (PartySnapshot.Member m : snap.members) {
            drawCard(c, m, pad, top, w - pad, top + cardH);
            top += cardH + pad;
        }
    }

    private void drawCard(Canvas c, PartySnapshot.Member m,
                          float l, float t, float r, float b) {
        float rad = (b - t) * 0.12f;
        RectF card = new RectF(l, t, r, b);
        c.drawRoundRect(card, rad, rad, cardPaint);
        c.drawRoundRect(card, rad, rad, cardEdge);

        float hgt = b - t;
        float textSize = hgt * 0.26f;
        namePaint.setTextSize(textSize);
        labelPaint.setTextSize(textSize * 0.72f);
        valuePaint.setTextSize(textSize * 0.72f);

        float x = l + hgt * 0.2f;
        float nameY = t + hgt * 0.34f;
        c.drawText(m.name, x, nameY, namePaint);
        c.drawText("Lv " + m.level, x + namePaint.measureText("MMMMMMM"), nameY, valuePaint);

        float barLeft = x + hgt * 0.9f;
        float barRight = r - hgt * 0.2f;
        drawBar(c, "HP", m.curHp, m.maxHp, barLeft, t + hgt * 0.46f, barRight, t + hgt * 0.64f,
                Color.rgb(90, 200, 120), Color.rgb(40, 90, 55));
        drawBar(c, "MP", m.curMp, m.maxMp, barLeft, t + hgt * 0.72f, barRight, t + hgt * 0.90f,
                Color.rgb(110, 150, 250), Color.rgb(45, 60, 110));
        // numeric values left of the bars
        String hp = m.curHp + "/" + m.maxHp;
        String mp = m.curMp + "/" + m.maxMp;
        c.drawText("HP " + hp, x, t + hgt * 0.62f, valuePaint);
        c.drawText("MP " + mp, x, t + hgt * 0.88f, valuePaint);
    }

    private void drawBar(Canvas c, String label, int cur, int max,
                         float l, float t, float r, float b, int color, int dim) {
        RectF bg = new RectF(l, t, r, b);
        float rad = (b - t) / 2f;
        c.drawRoundRect(bg, rad, rad, barBg);
        if (max > 0 && cur > 0) {
            float frac = Math.min(1f, (float) cur / max);
            RectF fill = new RectF(l, t, l + (r - l) * frac, b);
            barFill.setShader(new LinearGradient(l, t, l, b, color, dim, Shader.TileMode.CLAMP));
            c.drawRoundRect(fill, rad, rad, barFill);
            barFill.setShader(null);
        }
    }
}
