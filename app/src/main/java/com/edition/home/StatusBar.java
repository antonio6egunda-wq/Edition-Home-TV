package com.edition.home;

import android.graphics.drawable.Drawable;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * Relógio, saudação e o botão de status no canto superior direito.
 *
 * O botão tem três partes: engrenagem (abre Configurações), ícone de rede (Wi-Fi ou LAN)
 * e liga/desliga do Wi-Fi. Cada uma tem legenda embaixo. O liga/desliga é só indicador.
 */
public final class StatusBar {

    private static final float CLOCK_W = 760f;
    private static final float CLOCK_H = 72f;
    private static final float GREETING_Y = 112f;
    private static final float PILL_W = 160f;
    private static final float PILL_H = 96f;

    private static final int[] WIFI_BARS = {
            R.drawable.ic_wifi_bar_1, R.drawable.ic_wifi_bar_2,
            R.drawable.ic_wifi_bar_3, R.drawable.ic_wifi_bar_4
    };

    private final Activity activity;
    private final ConnectivityManager connectivity;
    private final WifiManager wifi;
    private final TextView clock;
    private final TextView greeting;
    private final ImageView netIcon;
    private final TextView netCaption;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    /**
     * Pílula: três partes independentes.
     * Um único botão com o ícone de rede. Clicar abre a aba Rede das Configurações.
     */
    public StatusBar(final Activity activity, FrameLayout root) {
        this.activity = activity;
        this.connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.wifi = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        clock = new TextView(activity);
        clock.setTextColor(Design.HOME_TEXT);
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(60));
        clock.setSingleLine(true);
        Design.place(root, clock, Design.SAFE_X, Design.SAFE_Y, CLOCK_W, CLOCK_H);

        greeting = new TextView(activity);
        greeting.setTextColor(Design.HOME_TEXT_DIM);
        greeting.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(24));
        greeting.setSingleLine(true);
        Design.setFont(greeting, Design.FONT_INTER_MEDIUM);
        Design.place(root, greeting, Design.SAFE_X, GREETING_Y, CLOCK_W, 36f);

        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(Design.px(8), 0, Design.px(8), 0);
        pill.setBackground(Design.rect(Design.HOME_GLASS, 48));

        // Um único botão: o ícone de rede (muda com o estado) por cima do indicador liga/desliga.
        // Clicar abre o menu de Configurações. Sem texto "Ligado" e sem engrenagem separada.
        LinearLayout wifiPart = new LinearLayout(activity);
        wifiPart.setOrientation(LinearLayout.VERTICAL);
        wifiPart.setGravity(Gravity.CENTER);

        // Só o ícone de rede (Wi-Fi, LAN ou sem rede), centralizado e legível a distância.
        FrameLayout stack = new FrameLayout(activity);
        netIcon = new ImageView(activity);
        stack.addView(netIcon, new FrameLayout.LayoutParams(Design.px(44), Design.px(44), Gravity.CENTER));
        netCaption = caption();
        wifiPart.addView(stack, new LinearLayout.LayoutParams(Design.px(72), Design.px(48)));
        wifiPart.addView(netCaption, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        wifiPart.setContentDescription(activity.getString(R.string.pill_toggle));
        wifiPart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsActivity.openAt(activity, true);
            }
        });
        pill.addView(part(wifiPart, 144), new LinearLayout.LayoutParams(Design.px(144), LinearLayout.LayoutParams.MATCH_PARENT));

        Design.place(root, pill, Design.SAFE_RIGHT - PILL_W, Design.SAFE_Y, PILL_W, PILL_H);
        updateClock();
        refreshStatus();
    }

    /** Cada parte recebe um contorno de foco próprio, dentro da pílula. */
    private View part(View v, float w) {
        final Drawable focused = Design.focusRing(Design.HOME_GLASS_FOCUS, 40);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                view.setBackground(hasFocus ? focused : null);
            }
        });
        return v;
    }

    private TextView caption() {
        TextView t = new TextView(activity);
        t.setTextColor(Design.HOME_TEXT_DIM);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(18));
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        Design.setFont(t, Design.FONT_INTER_MEDIUM);
        return t;
    }

    public void start() {
        if (!registered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            activity.registerReceiver(receiver, f);
            registered = true;
        }
        refresh();
    }

    public void stop() {
        if (registered) {
            activity.unregisterReceiver(receiver);
            registered = false;
        }
    }

    public void refresh() {
        updateClock();
        refreshStatus();
    }

    private void updateClock() {
        Calendar cal = Calendar.getInstance();
        String[] months = activity.getResources().getStringArray(R.array.months);
        String time;
        if (Prefs.clock24(activity)) {
            time = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        } else {
            int h = cal.get(Calendar.HOUR) == 0 ? 12 : cal.get(Calendar.HOUR);
            time = String.format(Locale.US, "%d:%02d %s", h, cal.get(Calendar.MINUTE),
                    cal.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM");
        }
        String date = activity.getString(R.string.clock_date, cal.get(Calendar.DAY_OF_MONTH),
                months[cal.get(Calendar.MONTH)], cal.get(Calendar.YEAR));
        String full = time + "   " + date;

        SpannableString text = new SpannableString(full);
        int t = time.length();
        text.setSpan(new FontSpan(Design.font(Design.FONT_SERIF_SEMIBOLD)), 0, t, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new FontSpan(Design.font(Design.FONT_INTER_LIGHT)), t, full.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.4f), t, full.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Design.HOME_TEXT_DIM), t, full.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        clock.setText(text);
        greeting.setText(greetingFor(cal.get(Calendar.HOUR_OF_DAY)));
    }

    private String greetingFor(int hour) {
        if (hour < 12) {
            return activity.getString(R.string.greeting_morning);
        }
        if (hour < 18) {
            return activity.getString(R.string.greeting_afternoon);
        }
        return activity.getString(R.string.greeting_evening);
    }

    /**
     * Ícone de rede conforme o estado:
     * cabo conectado: LAN; Wi-Fi desligado: ic_wifi_off; Wi-Fi ligado sem rede: ic_wifi_alert;
     * conectado: barras de sinal; sem informação de rede: ic_wifi_question.
     */
    private void refreshStatus() {
        boolean wifiOn = wifi != null && wifi.isWifiEnabled();
        NetworkInfo info = connectivity == null ? null : connectivity.getActiveNetworkInfo();
        boolean connected = info != null && info.isConnected();

        if (connected && info.getType() == ConnectivityManager.TYPE_ETHERNET) {
            netIcon.setImageResource(R.drawable.ic_lan);
            netCaption.setText(R.string.indicator_lan);
        } else if (!wifiOn) {
            netIcon.setImageResource(R.drawable.ic_wifi_off);
            netCaption.setText(R.string.wifi_title_off);
        } else if (connected && info.getType() == ConnectivityManager.TYPE_WIFI) {
            netIcon.setImageResource(wifiBarsRes());
            netCaption.setText(R.string.indicator_wifi);
        } else {
            netIcon.setImageResource(R.drawable.ic_wifi_alert);
            netCaption.setText(R.string.wifi_no_network);
        }
        netIcon.setColorFilter(Design.HOME_TEXT, PorterDuff.Mode.SRC_IN);
    }

    private int wifiBarsRes() {
        WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
        if (info == null) {
            return WIFI_BARS[0];
        }
        return WIFI_BARS[WifiManager.calculateSignalLevel(info.getRssi(), WIFI_BARS.length)];
    }
}
