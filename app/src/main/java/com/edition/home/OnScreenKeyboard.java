package com.edition.home;

import android.graphics.drawable.Drawable;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Teclado virtual para digitar a senha só com o controle remoto (sem teclado nem IME).
 * Alterna entre letras (com maiúsculas) e números/símbolos. Insere no campo informado.
 */
public final class OnScreenKeyboard extends LinearLayout {

    private static final String[] LETTERS_1 = {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"};
    private static final String[] LETTERS_2 = {"a", "s", "d", "f", "g", "h", "j", "k", "l"};
    private static final String[] LETTERS_3 = {"z", "x", "c", "v", "b", "n", "m"};
    private static final String[] SYMBOLS_1 = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
    private static final String[] SYMBOLS_2 = {"@", "#", "$", "_", "&", "-", "+", "(", ")", "/"};
    private static final String[] SYMBOLS_3 = {"*", "!", "?", ",", ".", ":", ";", "'", "\""};

    private static final float KEY_H = 50f;
    private static final float KEY_MARGIN = 4f;

    private final EditText target;
    private final List<TextView> letterKeys = new ArrayList<>();
    private boolean upper;
    private boolean numeric;

    public OnScreenKeyboard(Context context, EditText target) {
        super(context);
        this.target = target;
        setOrientation(VERTICAL);
        build();
    }

    private void build() {
        removeAllViews();
        letterKeys.clear();
        if (numeric) {
            addLetterRow(SYMBOLS_1);
            addLetterRow(SYMBOLS_2);
            addLetterRow(SYMBOLS_3);
        } else {
            addLetterRow(LETTERS_1);
            addLetterRow(LETTERS_2);
            LinearLayout row = newRow();
            row.addView(key(getContext().getString(upper ? R.string.kb_lower : R.string.kb_upper), 1.6f, new Runnable() {
                @Override
                public void run() {
                    upper = !upper;
                    build();
                }
            }));
            for (String s : LETTERS_3) {
                row.addView(letter(s));
            }
            row.addView(key(getContext().getString(R.string.kb_delete), 1.6f, new Runnable() {
                @Override
                public void run() {
                    backspace();
                }
            }));
            addView(row);
        }

        LinearLayout bottom = newRow();
        bottom.addView(key(getContext().getString(numeric ? R.string.kb_letters : R.string.kb_numbers), 1.6f, new Runnable() {
            @Override
            public void run() {
                numeric = !numeric;
                build();
            }
        }));
        bottom.addView(key(getContext().getString(R.string.kb_space), 5f, new Runnable() {
            @Override
            public void run() {
                insert(" ");
            }
        }));
        bottom.addView(key(getContext().getString(R.string.kb_delete), 1.6f, new Runnable() {
            @Override
            public void run() {
                backspace();
            }
        }));
        addView(bottom);
    }

    private void addLetterRow(String[] keys) {
        LinearLayout row = newRow();
        for (String s : keys) {
            row.addView(letter(s));
        }
        addView(row);
    }

    private TextView letter(final String value) {
        final String shown = (!numeric && upper) ? value.toUpperCase() : value;
        TextView k = key(shown, 1f, new Runnable() {
            @Override
            public void run() {
                insert(shown);
            }
        });
        letterKeys.add(k);
        return k;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LayoutParamsHolder.apply(row, this);
        return row;
    }

    private TextView key(String label, float weight, final Runnable action) {
        TextView k = new TextView(getContext());
        k.setText(label);
        k.setGravity(Gravity.CENTER);
        k.setTextColor(Design.TEXT);
        k.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(24));
        k.setSingleLine(true);
        Design.setFont(k, Design.FONT_INTER_SEMIBOLD);
        k.setFocusable(true);
        k.setClickable(true);

        final GradientDrawable normal = Design.rect(weight > 1f ? Design.GLASS_FOCUS : Design.GLASS, 10);
        final Drawable focused = Design.focusRing(Design.GLASS_FOCUS, 10);
        k.setBackground(normal);
        k.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(hasFocus ? focused : normal);
            }
        });
        k.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Design.px(KEY_H), weight);
        int m = Design.px(KEY_MARGIN);
        lp.setMargins(m, m, m, m);
        k.setLayoutParams(lp);
        return k;
    }

    private void insert(String text) {
        Editable e = target.getText();
        int pos = Math.max(0, target.getSelectionEnd());
        e.insert(pos, text);
    }

    private void backspace() {
        Editable e = target.getText();
        int pos = target.getSelectionEnd();
        if (pos > 0) {
            e.delete(pos - 1, pos);
        }
    }

    /** Ajusta a largura das linhas para o painel (mantém o layout vertical do teclado). */
    private static final class LayoutParamsHolder {
        static void apply(LinearLayout row, LinearLayout parent) {
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }
}
