package com.edition.home;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Cartão de aplicativo: ícone ou banner dentro do cartão e o nome sempre embaixo dele.
 *
 * O cartão cresce um pouco no foco (ESCALA_FOCO); o contorno de destaque fica dentro
 * do próprio cartão, então nada é cortado. O nome não muda no foco.
 */
public final class AppTileView extends FrameLayout {

    public static final float W = 176f;
    public static final float H = 100f;
    public static final float GAP = 8f;
    public static final float LABEL_H = 26f;
    public static final float TOTAL_H = H + GAP + LABEL_H;

    /** Escala única de foco para todo o app. */
    public static final float FOCUS_SCALE = 1.05f;

    private static final float ICON = 52f;
    private static final float ADD_ICON = 40f;

    private final boolean home;
    private final float radius;
    private final FrameLayout card;
    private final ImageView banner;
    private final ImageView icon;
    private final ImageView addIcon;
    private final TextView label;
    private final GradientDrawable normal;
    private final Drawable focused;

    public AppTileView(Context context, float radius, boolean home) {
        super(context);
        this.home = home;
        this.radius = radius;
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        setClickable(true);

        int fill = home ? Design.HOME_GLASS : Design.GLASS;
        int fillFocus = home ? Design.HOME_GLASS_FOCUS : Design.GLASS_FOCUS;
        normal = Design.rect(fill, radius);
        focused = Design.focusRing(fillFocus, radius);

        card = new FrameLayout(context);
        card.setBackground(normal);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Design.px(radius));
            }
        });
        addView(card, new FrameLayout.LayoutParams(Design.px(W), Design.px(H)));

        banner = new ImageView(context);
        banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.setVisibility(GONE);
        card.addView(banner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setVisibility(GONE);
        card.addView(icon, new FrameLayout.LayoutParams(Design.px(ICON), Design.px(ICON), Gravity.CENTER));

        addIcon = new ImageView(context);
        addIcon.setImageResource(R.drawable.ic_menu_plus);
        addIcon.setColorFilter(home ? Design.HOME_TEXT_DIM : Design.MUTED, PorterDuff.Mode.SRC_IN);
        addIcon.setVisibility(GONE);
        card.addView(addIcon, new FrameLayout.LayoutParams(Design.px(ADD_ICON), Design.px(ADD_ICON), Gravity.CENTER));

        label = new TextView(context);
        label.setTextColor(home ? Design.HOME_TEXT : Design.TEXT);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(20));
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        Design.setFont(label, Design.FONT_INTER_MEDIUM);
        addView(label, new FrameLayout.LayoutParams(Design.px(W), Design.px(LABEL_H), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams) label.getLayoutParams()).topMargin = Design.px(H + GAP);

        setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                card.setBackground(hasFocus ? focused : normal);
                float s = hasFocus ? FOCUS_SCALE : 1f;
                card.animate().scaleX(s).scaleY(s).setDuration(120).start();
                if (hasFocus) {
                    bringToFront();
                }
            }
        });
    }

    /** Mostra um aplicativo: banner (se houver) e ícone centralizados, nome embaixo. */
    public void bindApp(AppInfo app, Drawable bannerDrawable, Drawable iconDrawable) {
        setVisibility(VISIBLE);
        addIcon.setVisibility(GONE);
        label.setVisibility(VISIBLE);
        label.setTextColor(home ? Design.HOME_TEXT : Design.TEXT);
        label.setText(app.label);
        setContentDescription(app.label);

        if (bannerDrawable != null) {
            banner.setImageDrawable(bannerDrawable);
            banner.setVisibility(VISIBLE);
        } else {
            banner.setImageDrawable(null);
            banner.setVisibility(GONE);
        }
        // Com banner, o banner é a imagem do app: não mostra o ícone (ic_launcher) por cima.
        if (iconDrawable != null && bannerDrawable == null) {
            icon.setImageDrawable(iconDrawable);
            icon.setVisibility(VISIBLE);
        } else {
            icon.setVisibility(GONE);
        }
    }

    /** Slot vazio: sinal de + e a palavra "Adicionar" embaixo. */
    public void bindEmpty() {
        setVisibility(VISIBLE);
        banner.setVisibility(GONE);
        icon.setVisibility(GONE);
        addIcon.setVisibility(VISIBLE);
        label.setVisibility(VISIBLE);
        label.setText(R.string.add_favorite);
        label.setTextColor(home ? Design.HOME_TEXT_DIM : Design.MUTED);
        setContentDescription(getContext().getString(R.string.add_favorite));
    }

    public void bindHidden() {
        setVisibility(GONE);
    }
}
