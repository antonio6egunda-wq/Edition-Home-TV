package com.edition.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Barra de uso com segmentos coloridos a partir da esquerda. O fundo é branco,
 * e é o espaço restante.
 */
public final class UsageBar extends View {

    private float[] fractions = new float[0];
    private int[] colors = new int[0];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public UsageBar(Context context) {
        super(context);
    }

    /** Frações (0 a 1) de cada segmento e a cor de cada um, na mesma ordem. */
    public void setSegments(float[] fractions, int[] colors) {
        this.fractions = fractions;
        this.colors = colors;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = h / 2f;
        RectF rect = new RectF(0, 0, w, h);
        Path clip = new Path();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);

        paint.setColor(0xFFFFFFFF);
        canvas.drawRoundRect(rect, r, r, paint);

        canvas.save();
        canvas.clipPath(clip);
        float x = 0;
        for (int i = 0; i < fractions.length && i < colors.length; i++) {
            float seg = w * Math.max(0f, Math.min(1f, fractions[i]));
            paint.setColor(colors[i]);
            canvas.drawRect(x, 0, x + seg, h, paint);
            x += seg;
        }
        canvas.restore();
    }
}
