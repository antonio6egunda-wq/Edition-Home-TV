package com.edition.home;

import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

/** Aplica uma fonte embutida a um trecho de texto (o TypefaceSpan do sistema só aceita fontes do sistema). */
public final class FontSpan extends MetricAffectingSpan {

    private final Typeface typeface;

    public FontSpan(Typeface typeface) {
        this.typeface = typeface;
    }

    @Override
    public void updateMeasureState(TextPaint paint) {
        paint.setTypeface(typeface);
    }

    @Override
    public void updateDrawState(TextPaint paint) {
        paint.setTypeface(typeface);
    }
}
