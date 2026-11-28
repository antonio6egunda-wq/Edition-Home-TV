package com.edition.home;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Medidas e cores do layout.
 *
 * O desenho é feito em 1280x720 (HD, o padrão das boxes e projetores) e escalado
 * para a tela real, centralizado. Tudo fica dentro da área segura de 5%.
 * Cores de superfície (gaveta, ajustes, menus) mudam com o modo claro/escuro;
 * cores da tela inicial são fixas porque ficam sobre o papel de parede.
 */
public final class Design {

    public static final float WIDTH = 1280f;
    public static final float HEIGHT = 720f;

    /** Área segura: 5% das bordas, para TVs e projetores que cortam a imagem. */
    public static final float SAFE_X = 64f;
    public static final float SAFE_Y = 36f;
    public static final float SAFE_RIGHT = WIDTH - SAFE_X;
    public static final float SAFE_BOTTOM = HEIGHT - SAFE_Y;

    public static final int BACKGROUND = 0xFF0E1020;
    public static final int DANGER = 0xFFFF6B6B;

    // Tela inicial (sempre sobre o papel de parede)
    public static final int HOME_TEXT = 0xFFF4F5FA;
    public static final int HOME_TEXT_DIM = 0xB3F4F5FA;
    public static final int HOME_GLASS = 0x2EFFFFFF;
    public static final int HOME_GLASS_FOCUS = 0x55FFFFFF;
    public static final int HOME_DOCK = 0x26FFFFFF;

    // Superfícies: preenchidas por init() conforme o modo escolhido
    public static boolean dark = true;
    public static int ACCENT = 0xFFB39DFF;
    public static int TEXT;
    public static int TEXT_DIM;
    public static int MUTED;
    public static int PANEL;
    public static int CARD;
    public static int GLASS;
    public static int GLASS_FOCUS;
    public static int DIVIDER;
    public static int SCRIM;

    public static final String FONT_SERIF_SEMIBOLD = "fonts/SourceSerif4-SemiBold.ttf";
    public static final String FONT_INTER_LIGHT = "fonts/Inter-Light.ttf";
    public static final String FONT_INTER_MEDIUM = "fonts/Inter-Medium.ttf";
    public static final String FONT_INTER_SEMIBOLD = "fonts/Inter-SemiBold.ttf";

    private static final Map<String, Typeface> FONTS = new HashMap<>();
    private static AssetManager assets;
    private static float scale = 1f;
    private static float offsetX;
    private static float offsetY;

    private Design() {
    }

    public static void init(Context context) {
        assets = context.getAssets();
        dark = Prefs.isDark(context);
        ACCENT = Prefs.accentColor(context);
        applyPalette();

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        scale = Math.min(dm.widthPixels / WIDTH, dm.heightPixels / HEIGHT);
        offsetX = (dm.widthPixels - WIDTH * scale) / 2f;
        offsetY = (dm.heightPixels - HEIGHT * scale) / 2f;
    }

    private static void applyPalette() {
        if (dark) {
            TEXT = 0xFFF4F5FA;
            TEXT_DIM = 0xB3F4F5FA;
            MUTED = 0xFF9AA0AC;
            PANEL = 0xF2121420;
            CARD = 0xFF242832;
            GLASS = 0x2EFFFFFF;
            GLASS_FOCUS = 0x55FFFFFF;
            DIVIDER = 0x33FFFFFF;
            SCRIM = 0x99000000;
        } else {
            TEXT = 0xFF15171F;
            TEXT_DIM = 0xB315171F;
            MUTED = 0xFF5B6070;
            PANEL = 0xF5F6F8FC;
            CARD = 0xFFFFFFFF;
            GLASS = 0x1F000000;
            GLASS_FOCUS = 0x33000000;
            DIVIDER = 0x22000000;
            SCRIM = 0x66000000;
        }
    }

    /** Converte uma medida do desenho (1280x720) para pixels reais. */
    public static int px(float designPx) {
        return Math.round(designPx * scale);
    }

    /** Posiciona uma view no desenho (coordenadas absolutas, 1280x720). */
    public static void place(FrameLayout parent, View view, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(w), px(h));
        lp.leftMargin = Math.round(offsetX + x * scale);
        lp.topMargin = Math.round(offsetY + y * scale);
        parent.addView(view, lp);
    }

    /** Posiciona uma view dentro de um FrameLayout que já é o desenho (sem offset de tela). */
    public static void placeLocal(FrameLayout parent, View view, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(w), px(h));
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        parent.addView(view, lp);
    }

    public static Typeface font(String path) {
        Typeface cached = FONTS.get(path);
        if (cached != null) {
            return cached;
        }
        Typeface typeface;
        try {
            typeface = Typeface.createFromAsset(assets, path);
        } catch (RuntimeException e) {
            typeface = Typeface.DEFAULT;
        }
        FONTS.put(path, typeface);
        return typeface;
    }

    public static void setFont(TextView view, String path) {
        view.setTypeface(font(path));
    }

    public static GradientDrawable rect(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radius));
        return d;
    }

    /** Retângulo com cantos diferentes: tl, tr, br, bl. */
    public static GradientDrawable rect(int color, float tl, float tr, float br, float bl) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{px(tl), px(tl), px(tr), px(tr), px(br), px(br), px(bl), px(bl)});
        return d;
    }

    /**
     * Fundo de foco: o preenchimento normal mais um traço de destaque desenhado por dentro
     * da borda. Assim o zoom no foco não corta o contorno (o clip do cartão corta a metade
     * de fora de um traço centrado na borda).
     */
    public static Drawable focusRing(int fill, float radius) {
        int w = Math.max(1, px(3));
        GradientDrawable base = rect(fill, radius);
        GradientDrawable ring = rect(0x00000000, radius);
        ring.setStroke(w, ACCENT);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{base, ring});
        int inset = (w + 1) / 2;
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    public static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /** Caixa de diálogo no modo claro ou escuro. */
    public static AlertDialog.Builder dialog(Context context) {
        return new AlertDialog.Builder(context, dark ? R.style.Dialog_Dark : R.style.Dialog_Light);
    }

    /** Pinta os botões do diálogo com a cor de destaque (depois de show()). */
    public static void tintButtons(AlertDialog dialog) {
        int[] which = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE};
        for (int w : which) {
            TextView b = (TextView) dialog.getButton(w);
            if (b != null) {
                b.setTextColor(ACCENT);
            }
        }
    }
}
