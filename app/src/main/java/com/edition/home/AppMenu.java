package com.edition.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Menu de opções de um aplicativo (OK longo). Usa as cores do modo claro/escuro
 * e a cor de destaque escolhida.
 */
public final class AppMenu {

    public interface Listener {
        /** Usuário escolheu "Mover" na dock: a tela inicial entra no modo de mover. */
        void onMoveRequested(int slot);

        /** Algo mudou (nome, ocultar, remover da dock): a tela deve se atualizar. */
        void onChanged();
    }

    private static final float CARD_W = 300f;
    private static final float ITEM_H = 56f;

    private final Activity activity;
    private final AppRepository repository;
    private final Listener listener;
    private final FrameLayout overlay;
    private final LinearLayout card;
    private View anchor;

    public AppMenu(Activity activity, FrameLayout root, AppRepository repository, Listener listener) {
        this.activity = activity;
        this.repository = repository;
        this.listener = listener;

        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(Design.SCRIM);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });

        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Design.px(10), Design.px(12), Design.px(10), Design.px(12));
        card.setBackground(Design.rect(Design.CARD, 16));
        card.setClickable(true);
        overlay.addView(card, new FrameLayout.LayoutParams(
                Design.px(CARD_W), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public boolean isShowing() {
        return overlay.getVisibility() == View.VISIBLE;
    }

    /** Menu de um aplicativo da dock (slot é a posição na dock). */
    public void showForDock(AppInfo app, int slot, View anchorView) {
        show(app, anchorView, slot);
    }

    /** Menu de um aplicativo da gaveta. */
    public void showForDrawer(AppInfo app, View anchorView) {
        show(app, anchorView, -1);
    }

    public void hide() {
        if (!isShowing()) {
            return;
        }
        overlay.setVisibility(View.GONE);
        if (anchor != null) {
            anchor.requestFocus();
        }
    }

    private void show(final AppInfo app, View anchorView, final int slot) {
        anchor = anchorView;
        card.removeAllViews();

        TextView title = new TextView(activity);
        title.setText(repository.displayName(app.packageName, app.label));
        title.setTextColor(Design.TEXT_DIM);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(20));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(Design.px(14), Design.px(6), Design.px(14), Design.px(10));
        Design.setFont(title, Design.FONT_INTER_SEMIBOLD);
        card.addView(title);

        card.addView(divider());

        if (slot >= 0) {
            addItem(R.string.menu_edit, R.drawable.ic_menu_edit, Design.TEXT, new Runnable() {
                @Override
                public void run() {
                    rename(app);
                }
            });
            addItem(R.string.menu_move, R.drawable.ic_menu_move, Design.TEXT, new Runnable() {
                @Override
                public void run() {
                    hide();
                    listener.onMoveRequested(slot);
                }
            });
            addItem(R.string.menu_remove, R.drawable.ic_menu_trash, Design.TEXT, new Runnable() {
                @Override
                public void run() {
                    repository.removeFavorite(app.packageName);
                    overlay.setVisibility(View.GONE);
                    listener.onChanged();
                }
            });
        } else {
            addItem(R.string.menu_edit, R.drawable.ic_menu_edit, Design.TEXT, new Runnable() {
                @Override
                public void run() {
                    rename(app);
                }
            });
            final boolean hidden = repository.isHidden(app.packageName);
            addItem(hidden ? R.string.menu_show : R.string.menu_hide,
                    hidden ? R.drawable.ic_visibility : R.drawable.ic_visibility_off, Design.TEXT, new Runnable() {
                        @Override
                        public void run() {
                            repository.setHidden(app.packageName, !hidden);
                            overlay.setVisibility(View.GONE);
                            listener.onChanged();
                        }
                    });
        }

        if (!repository.isSystemApp(app.packageName)) {
            addItem(R.string.menu_uninstall, R.drawable.ic_menu_trash, Design.DANGER, new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.packageName));
                    launch(intent);
                }
            });
        }
        addItem(R.string.menu_info, R.drawable.ic_menu_info, Design.TEXT, new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.packageName));
                launch(intent);
            }
        });

        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        View first = card.getChildAt(2);
        if (first != null) {
            first.requestFocus();
        }
    }

    private void launch(Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.toast_cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    private View divider() {
        View v = new View(activity);
        v.setBackgroundColor(Design.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Design.px(1));
        lp.setMargins(Design.px(8), Design.px(6), Design.px(8), Design.px(6));
        v.setLayoutParams(lp);
        return v;
    }

    private void addItem(int labelRes, int iconRes, final int textColor, final Runnable action) {
        final boolean danger = textColor == Design.DANGER;
        final int focusFill = Design.withAlpha(danger ? Design.DANGER : Design.ACCENT, 0x40);

        final LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Design.px(14), 0, Design.px(14), 0);
        row.setFocusable(true);
        row.setClickable(true);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(textColor, PorterDuff.Mode.SRC_IN);
        row.addView(icon, new LinearLayout.LayoutParams(Design.px(26), Design.px(26)));

        TextView text = new TextView(activity);
        text.setText(labelRes);
        text.setTextColor(textColor);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(22));
        text.setSingleLine(true);
        Design.setFont(text, Design.FONT_INTER_MEDIUM);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Design.px(16);
        row.addView(text, tlp);

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                row.setBackground(hasFocus ? Design.rect(focusFill, 10) : null);
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        card.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Design.px(ITEM_H)));
    }

    private void rename(final AppInfo app) {
        overlay.setVisibility(View.GONE);
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(repository.displayName(app.packageName, app.label));
        input.setTextColor(Design.TEXT);
        input.setHintTextColor(Design.TEXT_DIM);
        input.setSelectAllOnFocus(true);
        int pad = Design.px(16);
        input.setPadding(pad, pad, pad, pad);

        AlertDialog dialog = Design.dialog(activity)
                .setTitle(R.string.menu_rename_title)
                .setView(input)
                .setPositiveButton(R.string.action_save, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        repository.setCustomName(app.packageName, input.getText().toString().trim());
                        listener.onChanged();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.show();
        Design.tintButtons(dialog);
    }
}
