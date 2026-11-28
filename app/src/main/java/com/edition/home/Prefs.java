package com.edition.home;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/** Preferências do launcher. Um único arquivo para tema, animação e proteção de tela. */
public final class Prefs {

    private static final String NAME = "home_tv_prefs";
    private static final String KEY_ACCENT = "theme_index";
    private static final String KEY_DARK = "ui_dark";
    private static final String KEY_OPEN_ANIM = "open_anim";
    private static final String KEY_SS_ENABLED = "ss_enabled";
    private static final String KEY_SS_IDLE = "ss_idle_min";
    private static final String KEY_SS_SOURCE = "ss_source";
    private static final String KEY_CLOCK24 = "clock_24h";

    /** Cores de destaque (mesma ordem de R.array.accent_names). */
    public static final int[] ACCENTS = {
            0xFFB39DFF, // roxo
            0xFF3B82F6, // azul
            0xFF22D3EE, // ciano
            0xFF34D399, // verde
            0xFFFACC15, // amarelo
            0xFFFB923C, // laranja
            0xFFF472B6, // rosa
            0xFFFF6B6B  // vermelho
    };

    public static final int[] IDLE_MINUTES = {5, 10, 15, 30};

    public static final int ANIM_FADE = 0;
    public static final int ANIM_ZOOM = 1;
    public static final int ANIM_SLIDE = 2;
    public static final int ANIM_NONE = 3;

    public static final int SOURCE_PHOTOS = 0;
    public static final int SOURCE_VIDEO = 1;

    private Prefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int themeIndex(Context c) {
        return prefs(c).getInt(KEY_ACCENT, 0) % ACCENTS.length;
    }

    public static void setThemeIndex(Context c, int index) {
        prefs(c).edit().putInt(KEY_ACCENT, index).apply();
    }

    public static int accentColor(Context c) {
        return ACCENTS[themeIndex(c)];
    }

    public static boolean isDark(Context c) {
        return prefs(c).getBoolean(KEY_DARK, true);
    }

    public static void setDark(Context c, boolean dark) {
        prefs(c).edit().putBoolean(KEY_DARK, dark).apply();
    }

    /** Identifica a aparência atual; quando muda, as telas são recriadas. */
    public static String lookKey(Context c) {
        return (isDark(c) ? "d" : "l") + themeIndex(c) + java.util.Locale.getDefault().getLanguage();
    }

    /** Relógio da barra de status: true = 24 horas. */
    public static boolean clock24(Context c) {
        try {
            String v = Settings.System.getString(c.getContentResolver(), Settings.System.TIME_12_24);
            if (v != null) {
                return "24".equals(v);
            }
        } catch (RuntimeException ignored) {
            // Sem leitura: usa a preferência do próprio app.
        }
        return prefs(c).getBoolean(KEY_CLOCK24, true);
    }

    /** Grava o formato no sistema (precisa de WRITE_SETTINGS) e no app. */
    public static void setClock24(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_CLOCK24, on).apply();
        try {
            Settings.System.putString(c.getContentResolver(), Settings.System.TIME_12_24, on ? "24" : "12");
        } catch (SecurityException ignored) {
            // Sem permissão de sistema: vale só para o relógio do app.
        }
    }

    public static int openAnimation(Context c) {
        return prefs(c).getInt(KEY_OPEN_ANIM, ANIM_FADE);
    }

    public static void setOpenAnimation(Context c, int anim) {
        prefs(c).edit().putInt(KEY_OPEN_ANIM, anim).apply();
    }

    public static boolean screensaverEnabled(Context c) {
        return prefs(c).getBoolean(KEY_SS_ENABLED, false);
    }

    public static void setScreensaverEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_SS_ENABLED, on).apply();
    }

    public static int idleMinutes(Context c) {
        return prefs(c).getInt(KEY_SS_IDLE, 5);
    }

    public static void setIdleMinutes(Context c, int minutes) {
        prefs(c).edit().putInt(KEY_SS_IDLE, minutes).apply();
    }

    public static int screensaverSource(Context c) {
        return prefs(c).getInt(KEY_SS_SOURCE, SOURCE_PHOTOS);
    }

    public static void setScreensaverSource(Context c, int source) {
        prefs(c).edit().putInt(KEY_SS_SOURCE, source).apply();
    }

    public static void applyOpenAnimation(Activity activity) {
        switch (openAnimation(activity)) {
            case ANIM_ZOOM:
                activity.overridePendingTransition(R.anim.zoom_in, android.R.anim.fade_out);
                break;
            case ANIM_SLIDE:
                activity.overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right);
                break;
            case ANIM_NONE:
                activity.overridePendingTransition(0, 0);
                break;
            default:
                activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                break;
        }
    }
}
