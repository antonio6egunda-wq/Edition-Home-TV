package com.edition.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Configurações: painel à direita com Wi-Fi e LAN no topo. Ao focar Wi-Fi, mostra as redes;
 * ao focar LAN, mostra o cabo. Embaixo ficam as seções do sistema.
 *
 * O cabeçalho mostra sempre "Configurações" e, embaixo, o nome da seção em foco.
 */
public class SettingsActivity extends Activity {

    private static final int REQ_LOCATION = 7001;
    private static final long SCAN_INTERVAL_MS = 10_000L;
    private static final long REFRESH_DELAY_MS = 2_500L;

    private static final float PANEL_X = 696f;
    private static final float PANEL_Y = Design.SAFE_Y;
    private static final float PANEL_W = 520f;
    private static final float PANEL_H = Design.SAFE_BOTTOM - Design.SAFE_Y;
    private static final float PAD = 32f;
    private static final float ROW_GAP = 8f;

    private static final int PAGE_ROOT = 0;
    private static final int PAGE_DEVICE = 1;
    private static final int PAGE_NETWORK = 2;
    private static final int PAGE_APPS = 3;
    private static final int PAGE_LOOK = 4;
    private static final int PAGE_ANDROID = 5;
    private static final int PAGE_THEME = 6;

    private static final int IND_WIFI = 0;
    private static final int IND_LAN = 1;

    private static final int DETAIL_NONE = 0;
    private static final int DETAIL_WIFI = 1;
    private static final int DETAIL_LAN = 2;

    private static final int FOCUS_NONE = 0;
    private static final int FOCUS_TOGGLE = 1;
    private static final int FOCUS_REFRESH = 2;
    private static final int FOCUS_NETWORK = 3;

    private static final int[] WIFI_BARS = {
            R.drawable.ic_wifi_bar_1, R.drawable.ic_wifi_bar_2,
            R.drawable.ic_wifi_bar_3, R.drawable.ic_wifi_bar_4
    };

    /** Página a abrir quando a tela é recriada (troca de tema). */
    private static int restorePage = PAGE_ROOT;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiManager wifi;
    private ConnectivityManager connectivity;
    private AppRepository repository;

    private FrameLayout root;
    private LinearLayout panel;
    private TextView subtitle;
    private ScrollView scroll;
    private LinearLayout content;

    private int page = PAGE_ROOT;
    private final ImageView[] indIcons = new ImageView[2];
    private final TextView[] indStates = new TextView[2];
    private final FrameLayout[] indCircles = new FrameLayout[2];
    private final LinearLayout[] indCards = new LinearLayout[2];
    private LinearLayout detail;
    private View backRow;
    private View toggleRow;
    private View refreshButton;
    private int detailKind = DETAIL_NONE;

    private String expandedSsid;
    private String focusSsid;
    private int restoreFocus = FOCUS_NONE;
    private String networkSignature = "";
    private boolean lastWifiOn;
    private boolean lanConnected;
    private boolean registered;

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                renderDetail(false);
            } else {
                refreshAll();
            }
        }
    };

    private final ConnectivityManager.NetworkCallback lanCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    lanConnected = hasEthernet();
                    updateStatusCards();
                    renderDetail(false);
                }
            });
        }

        @Override
        public void onLost(Network network) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    lanConnected = hasEthernet();
                    updateStatusCards();
                    renderDetail(false);
                }
            });
        }
    };

    private final Runnable scanTick = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && page == PAGE_NETWORK && detailKind == DETAIL_WIFI
                    && wifi.isWifiEnabled() && hasLocation()) {
                wifi.startScan();
            }
            handler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    // ---------------- Ciclo de vida ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.init(this);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        repository = new AppRepository(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Design.SCRIM);
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        setContentView(root);
        buildPanel();

        lanConnected = hasEthernet();
        connectivity.registerNetworkCallback(
                new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(),
                lanCallback);

        int target = restorePage;
        restorePage = PAGE_ROOT;
        showPage(target);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!registered) {
            IntentFilter f = new IntentFilter();
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(wifiReceiver, f);
            registered = true;
        }
        refreshAll();
        handler.postDelayed(scanTick, SCAN_INTERVAL_MS);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(scanTick);
        if (registered) {
            unregisterReceiver(wifiReceiver);
            registered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            connectivity.unregisterNetworkCallback(lanCallback);
        } catch (IllegalArgumentException ignored) {
            // Já removido.
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (page != PAGE_ROOT) {
            showPage(PAGE_ROOT);
        } else {
            finish();
            Prefs.applyOpenAnimation(this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (Wallpaper.handleResult(this, requestCode, resultCode, data)
                || Screensaver.handleResult(this, requestCode, resultCode, data)) {
            rebuildSamePage();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_LOCATION) {
            renderDetail(true);
        }
    }

    // ---------------- Estrutura do painel ----------------

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Design.rect(Design.PANEL, 28, 0, 0, 28));
        panel.setClickable(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(Design.px(PAD), Design.px(PAD), Design.px(PAD), Design.px(12));

        TextView title = text(getString(R.string.settings_title), Design.TEXT, 36, Design.FONT_SERIF_SEMIBOLD);
        header.addView(title);

        subtitle = text("", Design.TEXT_DIM, 20, Design.FONT_INTER_MEDIUM);
        header.addView(subtitle);
        panel.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Design.px(PAD), Design.px(8), Design.px(PAD), Design.px(PAD));
        scroll.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Design.place(root, panel, PANEL_X, PANEL_Y, PANEL_W, PANEL_H);
    }

    private void setSubtitle(String value) {
        subtitle.setText(value);
    }

    private void showPage(int target) {
        page = target;
        content.removeAllViews();
        backRow = null;
        toggleRow = null;
        refreshButton = null;
        detail = null;

        if (page == PAGE_ROOT) {
            setSubtitle(getString(R.string.settings_subtitle));
            buildRoot();
        } else {
            setSubtitle(getString(sectionOf(page)));
            buildBack();
            buildPage();
        }
        content.post(new Runnable() {
            @Override
            public void run() {
                focusFirst();
            }
        });
        refreshAll();
    }

    private void rebuildSamePage() {
        int index = indexOfFocus();
        content.removeAllViews();
        backRow = null;
        toggleRow = null;
        refreshButton = null;
        detail = null;
        if (page == PAGE_ROOT) {
            buildRoot();
        } else {
            buildBack();
            buildPage();
        }
        final int target = Math.min(index, content.getChildCount() - 1);
        content.post(new Runnable() {
            @Override
            public void run() {
                if (target >= 0 && content.getChildAt(target) != null) {
                    content.getChildAt(target).requestFocus();
                } else {
                    focusFirst();
                }
            }
        });
        refreshAll();
    }

    private int indexOfFocus() {
        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i).hasFocus()) {
                return i;
            }
        }
        return 0;
    }

    private void focusFirst() {
        if (page == PAGE_NETWORK && indCards[IND_WIFI] != null) {
            indCards[IND_WIFI].requestFocus();
        } else if (page == PAGE_ROOT) {
            View first = content.getChildAt(0);
            if (first != null) {
                first.requestFocus();
            }
        } else if (backRow != null) {
            backRow.requestFocus();
        }
    }

    /** Abre as Configurações; network=true vai direto para a aba Rede. */
    public static void openAt(Activity from, boolean network) {
        restorePage = network ? PAGE_NETWORK : PAGE_ROOT;
        from.startActivity(new Intent(from, SettingsActivity.class));
        Prefs.applyOpenAnimation(from);
    }

    /** Nome da categoria, mostrado no subtítulo. */
    private int sectionOf(int p) {
        switch (p) {
            case PAGE_DEVICE:
                return R.string.settings_device;
            case PAGE_APPS:
                return R.string.settings_apps;
            case PAGE_LOOK:
                return R.string.settings_look;
            case PAGE_ANDROID:
                return R.string.settings_android;
            case PAGE_THEME:
                return R.string.settings_theme;
            default:
                return R.string.settings_network;
        }
    }

    private void buildBack() {
        backRow = row(null, getString(R.string.back_label), null, 0, new Runnable() {
            @Override
            public void run() {
                showPage(PAGE_ROOT);
            }
        }, 0);
        content.addView(backRow, lp(12));
    }

    private void buildPage() {
        switch (page) {
            case PAGE_DEVICE:
                buildDevice();
                break;
            case PAGE_NETWORK:
                buildNetwork();
                break;
            case PAGE_APPS:
                buildApps();
                break;
            case PAGE_LOOK:
                buildLook();
                break;
            case PAGE_ANDROID:
                buildAndroid();
                break;
            case PAGE_THEME:
                buildTheme();
                break;
            default:
                break;
        }
    }

    // ---------------- Página principal ----------------

    private void buildRoot() {
        menuRow(R.drawable.ic_display_settings, R.string.settings_device, R.string.settings_device, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_DEVICE); } });
        menuRow(R.drawable.ic_wifi_lock, R.string.settings_network, R.string.settings_network, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_NETWORK); } });
        menuRow(R.drawable.ic_app, R.string.settings_apps, R.string.settings_apps, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_APPS); } });
        menuRow(R.drawable.ic_wallpaper, R.string.settings_look, R.string.settings_look, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_LOOK); } });
        menuRow(R.drawable.ic_settings, R.string.settings_theme, R.string.settings_theme, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_THEME); } });
        menuRow(R.drawable.ic_admin_panel_settings, R.string.settings_android, R.string.settings_android, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_ANDROID); } });
    }

    private void buildNetwork() {
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.addView(statusCard(IND_WIFI, R.string.indicator_wifi),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        gap.leftMargin = Design.px(16);
        cards.addView(statusCard(IND_LAN, R.string.indicator_lan), gap);
        content.addView(cards, lp(20));

        detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        content.addView(detail, lp(20));
        updateStatusCards();
    }

    private void buildLook() {
        buildWallpaper();
        content.addView(label(R.string.settings_screensaver), lp(8));
        buildScreensaver();
    }

    /** Configurações do Android: idioma, data e hora, mais definições e o tema. */
    private void buildAndroid() {
        content.addView(row(R.drawable.ic_language, getString(R.string.settings_language), null, 0,
                new Runnable() { @Override public void run() { openSystem(Settings.ACTION_LOCALE_SETTINGS); } }, 0), lp(ROW_GAP));
        content.addView(row(R.drawable.ic_schedule, getString(R.string.settings_datetime), null, 0,
                new Runnable() { @Override public void run() { openSystem(Settings.ACTION_DATE_SETTINGS); } }, 0), lp(ROW_GAP));
        content.addView(row(R.drawable.ic_admin_panel_settings, getString(R.string.settings_more), null, 0,
                new Runnable() { @Override public void run() { openSystem(Settings.ACTION_SETTINGS); } }, 0), lp(ROW_GAP));
    }

    private void menuRow(int iconRes, int titleRes, int sectionRes, int trailingRes, Runnable action) {
        View r = row(iconRes, getString(titleRes), null, trailingRes, action, sectionRes);
        content.addView(r, lp(ROW_GAP));
    }

    /** Cartão de Wi-Fi ou LAN: ícone num círculo, nome e estado. */
    private LinearLayout statusCard(final int idx, int nameRes) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Design.px(12), Design.px(20), Design.px(12), Design.px(18));
        card.setBackground(Design.rect(Design.GLASS, 22));
        card.setFocusable(true);
        card.setClickable(true);

        FrameLayout circle = new FrameLayout(this);
        ImageView icon = new ImageView(this);
        icon.setPadding(Design.px(14), Design.px(14), Design.px(14), Design.px(14));
        icon.setColorFilter(0xFFFFFFFF);
        circle.addView(icon, new FrameLayout.LayoutParams(Design.px(64), Design.px(64)));
        card.addView(circle, new LinearLayout.LayoutParams(Design.px(64), Design.px(64)));

        TextView name = text(getString(nameRes), Design.TEXT_DIM, 20, Design.FONT_INTER_MEDIUM);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Design.px(10);
        card.addView(name, nlp);

        TextView state = text("", Design.TEXT, 20, Design.FONT_INTER_SEMIBOLD);
        state.setGravity(Gravity.CENTER_HORIZONTAL);
        state.setSingleLine(true);
        state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(state, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        indCards[idx] = card;
        indCircles[idx] = circle;
        indIcons[idx] = icon;
        indStates[idx] = state;

        final GradientDrawable normal = Design.rect(Design.GLASS, 22);
        final Drawable focused = Design.focusRing(Design.GLASS_FOCUS, 22);
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                card.setBackground(hasFocus ? focused : normal);
                if (hasFocus) {
                    setSubtitle(getString(R.string.settings_network));
                    detailKind = idx == IND_WIFI ? DETAIL_WIFI : DETAIL_LAN;
                    renderDetail(true);
                }
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (idx == IND_WIFI) {
                    toggleWifi();
                }
            }
        });
        return card;
    }

    private void updateStatusCards() {
        if (page != PAGE_NETWORK || indCards[IND_WIFI] == null) {
            return;
        }
        boolean on = wifi.isWifiEnabled();
        String ssid = currentSsid();
        String wifiState = on
                ? (ssid != null ? ssid : getString(R.string.wifi_no_network))
                : getString(R.string.wifi_title_off);
        setCard(IND_WIFI, on, wifiIconRes(on), wifiState);
        setCard(IND_LAN, lanConnected, R.drawable.ic_lan,
                getString(lanConnected ? R.string.lan_title_on : R.string.lan_title_off));
    }

    private void setCard(int idx, boolean active, int iconRes, String state) {
        indCircles[idx].setBackground(Design.oval(active ? Design.ACCENT : Design.MUTED));
        indIcons[idx].setImageResource(iconRes);
        indStates[idx].setText(state);
    }

    private int wifiIconRes(boolean on) {
        if (!on) {
            return R.drawable.ic_wifi_off;
        }
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1) {
            return R.drawable.ic_wifi_question;
        }
        return WIFI_BARS[WifiManager.calculateSignalLevel(info.getRssi(), WIFI_BARS.length)];
    }

    // ---------------- Detalhe do Wi-Fi e da LAN ----------------

    /**
     * Redesenha o detalhe do cartão em foco. Sem force, não mexe enquanto o usuário
     * estiver numa rede expandida ou navegando dentro do detalhe, para não perder o foco.
     */
    private void renderDetail(boolean force) {
        if (page != PAGE_NETWORK || detail == null) {
            return;
        }
        if (detailKind == DETAIL_WIFI) {
            renderWifi(force);
        } else if (detailKind == DETAIL_LAN) {
            renderLan();
        } else {
            detail.removeAllViews();
        }
    }

    private void renderWifi(boolean force) {
        boolean on = wifi.isWifiEnabled();
        boolean location = on && hasLocation();
        List<ScanResult> networks = location ? uniqueNetworks() : new ArrayList<ScanResult>();
        String signature = signatureOf(on, location, networks);

        if (!force) {
            if (expandedSsid != null || detail.hasFocus() || signature.equals(networkSignature)) {
                return;
            }
        }
        networkSignature = signature;
        boolean hadFocus = detail.hasFocus();
        detail.removeAllViews();
        lastWifiOn = on;

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.wifi_networks), Design.TEXT_DIM, 20, Design.FONT_INTER_SEMIBOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        refreshButton = iconButton(R.drawable.ic_refresh, new Runnable() {
            @Override
            public void run() {
                refreshNetworks();
            }
        });
        head.addView(refreshButton, new LinearLayout.LayoutParams(Design.px(52), Design.px(52)));
        detail.addView(head, lp(10));

        toggleRow = null;

        if (!on) {
            detail.addView(note(R.string.wifi_need_on), lp(4));
        } else if (!hasLocation()) {
            detail.addView(note(R.string.wifi_need_location), lp(10));
            detail.addView(row(null, getString(R.string.wifi_allow), null, 0, new Runnable() {
                @Override
                public void run() {
                    requestLocation();
                }
            }, 0), lp(ROW_GAP));
            detail.addView(row(null, getString(R.string.net_location_settings), null, 0, new Runnable() {
                @Override
                public void run() {
                    openSystem(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                }
            }, 0), lp(ROW_GAP));
        } else if (networks.isEmpty()) {
            detail.addView(note(R.string.wifi_scanning), lp(4));
        } else {
            for (ScanResult r : networks) {
                detail.addView(networkBlock(r), lp(ROW_GAP));
            }
        }

        if (hadFocus) {
            View target = null;
            if (restoreFocus == FOCUS_TOGGLE) {
                target = refreshButton;
            } else if (restoreFocus == FOCUS_NETWORK && focusSsid != null) {
                target = findNetworkRow(focusSsid);
            } else if (restoreFocus == FOCUS_REFRESH || restoreFocus == FOCUS_NONE) {
                target = refreshButton;
            }
            if (target != null) {
                target.requestFocus();
            }
        }
        restoreFocus = FOCUS_NONE;
        focusSsid = null;
    }

    private String signatureOf(boolean on, boolean location, List<ScanResult> networks) {
        StringBuilder sb = new StringBuilder();
        sb.append(on).append('|').append(location).append('|').append(currentSsid()).append('|').append(expandedSsid);
        for (ScanResult r : networks) {
            sb.append('|').append(r.SSID).append(':').append(r.level / 10);
        }
        return sb.toString();
    }

    private View findNetworkRow(String ssid) {
        for (int i = 0; i < detail.getChildCount(); i++) {
            View child = detail.getChildAt(i);
            if (child instanceof LinearLayout) {
                View row = ((LinearLayout) child).getChildAt(0);
                if (row != null && ssid.equals(row.getTag())) {
                    return row;
                }
            }
        }
        return null;
    }

    private LinearLayout networkBlock(final ScanResult r) {
        final boolean connected = r.SSID.equals(currentSsid());
        final WifiConfiguration saved = findSaved(r.SSID);
        final boolean secured = isSecured(r);
        boolean expanded = r.SSID.equals(expandedSsid);

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Design.px(14), Design.px(12), Design.px(14), Design.px(12));
        row.setTag(r.SSID);

        ImageView bars = new ImageView(this);
        bars.setImageResource(WIFI_BARS[WifiManager.calculateSignalLevel(r.level, WIFI_BARS.length)]);
        bars.setColorFilter(connected ? Design.ACCENT : Design.TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
        row.addView(bars, new LinearLayout.LayoutParams(Design.px(30), Design.px(30)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(Design.px(14), 0, 0, 0);
        TextView name = text(r.SSID, Design.TEXT, 22, Design.FONT_INTER_MEDIUM);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(name);
        String sub;
        if (connected) {
            sub = getString(R.string.net_current);
        } else {
            sub = getString(secured ? R.string.wifi_secured : R.string.wifi_open);
        }
        names.addView(text(sub, Design.TEXT_DIM, 17, Design.FONT_INTER_MEDIUM));
        row.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (secured) {
            ImageView lock = new ImageView(this);
            lock.setImageResource(R.drawable.ic_wifi_lock);
            lock.setColorFilter(Design.TEXT_DIM, android.graphics.PorterDuff.Mode.SRC_IN);
            row.addView(lock, new LinearLayout.LayoutParams(Design.px(24), Design.px(24)));
        }

        styleFocus(row, 16, new Runnable() {
            @Override
            public void run() {
                toggleExpand(r.SSID);
            }
        }, 0);
        block.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (expanded) {
            block.addView(networkActions(r, connected, saved, secured), lp(8));
        }
        return block;
    }

    /**
     * Área de uma rede expandida, numa linha: campo de senha (redes protegidas sem cadastro),
     * olho, e os botões Conectar, Esquecer e Ligar. O teclado virtual fica abaixo do campo.
     */
    private LinearLayout networkActions(final ScanResult r, final boolean connected,
                                        final WifiConfiguration saved, boolean secured) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Design.px(8), 0, Design.px(8), 0);

        final boolean needsPassword = saved == null && secured;
        final EditText password = new EditText(this);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        if (needsPassword) {
            password.setSingleLine(true);
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.setHint(R.string.net_password_hint);
            password.setHintTextColor(Design.TEXT_DIM);
            password.setTextColor(Design.TEXT);
            password.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(22));
            Design.setFont(password, Design.FONT_INTER_MEDIUM);
            password.setBackground(Design.rect(Design.GLASS, 12));
            password.setPadding(Design.px(14), 0, Design.px(14), 0);
            password.setTransformationMethod(PasswordTransformationMethod.getInstance());
            // Usa só o teclado virtual do app (o controle não tem teclado físico).
            password.setShowSoftInputOnFocus(false);
            bar.addView(password, new LinearLayout.LayoutParams(0, Design.px(52), 2f));

            final boolean[] shown = {false};
            final ImageView eye = iconButton(R.drawable.ic_visibility, null);
            eye.setContentDescription(getString(R.string.net_show_password));
            eye.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    shown[0] = !shown[0];
                    password.setTransformationMethod(shown[0] ? null : PasswordTransformationMethod.getInstance());
                    eye.setImageResource(shown[0] ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
                    eye.setContentDescription(getString(shown[0] ? R.string.net_hide_password : R.string.net_show_password));
                    password.setSelection(password.getText().length());
                }
            });
            LinearLayout.LayoutParams eyeLp = new LinearLayout.LayoutParams(Design.px(52), Design.px(52));
            eyeLp.leftMargin = Design.px(8);
            bar.addView(eye, eyeLp);
        }

        final Runnable connect = new Runnable() {
            @Override
            public void run() {
                if (saved != null) {
                    connectSaved(saved.networkId);
                } else if (needsPassword) {
                    connectNew(r, password.getText().toString());
                } else {
                    connectNew(r, "");
                }
            }
        };
        // Um só botão de conexão: Desconectar quando conectada, Conectar caso contrário.
        if (connected) {
            bar.addView(actionButton(R.string.net_disconnect, new Runnable() {
                @Override
                public void run() {
                    wifi.disconnect();
                    expandedSsid = null;
                    scheduleRefresh();
                }
            }), actionParams());
        } else {
            bar.addView(actionButton(R.string.net_connect, connect), actionParams());
        }
        if (saved != null) {
            bar.addView(actionButton(R.string.net_forget, new Runnable() {
                @Override
                public void run() {
                    forget(saved.networkId);
                }
            }), actionParams());
        }
        box.addView(bar, lp(ROW_GAP));

        if (needsPassword) {
            box.addView(new OnScreenKeyboard(this, password), lp(ROW_GAP));
        }
        return box;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Design.px(52), 1f);
        p.leftMargin = Design.px(8);
        return p;
    }

    /** Botão da linha de rede: mesmo foco do resto do app, texto curto. */
    private TextView actionButton(int labelRes, Runnable action) {
        TextView b = new TextView(this);
        b.setText(labelRes);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setTextColor(Design.TEXT);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(18));
        Design.setFont(b, Design.FONT_INTER_SEMIBOLD);
        b.setPadding(Design.px(6), 0, Design.px(6), 0);
        styleFocusWith(b, Design.rect(Design.GLASS, 12), Design.focusRing(Design.GLASS_FOCUS, 12), action, 0);
        return b;
    }

    private void renderLan() {
        detail.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Design.px(14), Design.px(10), Design.px(14), Design.px(10));
        box.addView(text(getString(lanConnected ? R.string.lan_title_on : R.string.lan_title_off),
                Design.TEXT, 22, Design.FONT_INTER_SEMIBOLD));
        box.addView(text(getString(lanConnected ? R.string.lan_sub_on : R.string.lan_sub_off),
                Design.TEXT_DIM, 18, Design.FONT_INTER_MEDIUM));
        String ip = ipAddress();
        if (ip != null) {
            TextView ipView = text(getString(R.string.dev_ip) + ": " + ip, Design.TEXT_DIM, 18, Design.FONT_INTER_MEDIUM);
            LinearLayout.LayoutParams p = lp(0);
            ipView.setLayoutParams(p);
            box.addView(ipView);
        }
        detail.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void toggleExpand(String ssid) {
        expandedSsid = ssid.equals(expandedSsid) ? null : ssid;
        focusSsid = ssid;
        restoreFocus = FOCUS_NETWORK;
        renderWifi(true);
    }

    private void toggleWifi() {
        boolean target = !wifi.isWifiEnabled();
        restoreFocus = FOCUS_TOGGLE;
        if (!wifi.setWifiEnabled(target)) {
            toast(R.string.wifi_cannot_toggle);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshAll();
            }
        }, 1500L);
    }

    private void refreshNetworks() {
        restoreFocus = FOCUS_REFRESH;
        if (wifi.isWifiEnabled() && hasLocation()) {
            wifi.startScan();
            toast(R.string.wifi_refreshing);
        }
        renderWifi(true);
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                renderDetail(false);
            }
        }, REFRESH_DELAY_MS);
    }

    private void connectSaved(int networkId) {
        wifi.enableNetwork(networkId, true);
        wifi.reconnect();
        toast(R.string.net_connecting);
        expandedSsid = null;
        scheduleRefresh();
    }

    private void connectNew(ScanResult r, String password) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + r.SSID + "\"";
        if (!isSecured(r)) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else if (r.capabilities.contains("WEP")) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            config.wepKeys[0] = "\"" + password + "\"";
            config.wepTxKeyIndex = 0;
        } else {
            config.preSharedKey = "\"" + password + "\"";
        }
        int id = wifi.addNetwork(config);
        if (id == -1) {
            toast(R.string.net_error);
            return;
        }
        connectSaved(id);
    }

    private void forget(int networkId) {
        wifi.removeNetwork(networkId);
        wifi.saveConfiguration();
        toast(R.string.net_forgotten);
        expandedSsid = null;
        networkSignature = "";
        renderWifi(true);
    }

    // ---------------- Páginas das seções ----------------

    private void buildDevice() {
        addInfo(R.string.dev_name, deviceName());
        addInfo(R.string.dev_model, Build.MODEL);
        addInfo(R.string.dev_android, Build.VERSION.RELEASE);
        addInfo(R.string.dev_resolution, resolution());
        addExpandable(R.string.dev_ram, memoryTotal());
        addExpandable(R.string.dev_storage, storageTotal());
        addInfo(R.string.dev_build, Build.DISPLAY);
        addInfo(R.string.dev_security, securityPatch());
        addInfo(R.string.dev_ip, ipAddress() != null ? ipAddress() : getString(R.string.dev_no_ip));
    }

    private void addInfo(int titleRes, String value) {
        content.addView(row(null, getString(titleRes), value, 0, null, 0), lp(ROW_GAP));
    }

    /** Linha que mostra o total ao tocar (RAM e armazenamento). */
    private void addExpandable(int titleRes, final String total) {
        final TextView value = text("›", Design.TEXT_DIM, 22, Design.FONT_INTER_SEMIBOLD);
        final boolean[] open = {false};
        View r = row(null, getString(titleRes), null, 0, new Runnable() {
            @Override
            public void run() {
                open[0] = !open[0];
                value.setText(open[0] ? total : "›");
            }
        }, 0);
        LinearLayout ll = (LinearLayout) r;
        ll.addView(value, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(r, lp(ROW_GAP));
    }

    private void buildApps() {
        content.addView(label(R.string.settings_animation), lp(8));
        buildAnimation();
        content.addView(label(R.string.settings_apps), lp(8));
        content.addView(note(R.string.apps_hint), lp(12));
        List<AppInfo> apps = repository.loadLaunchableApps();
        for (final AppInfo app : apps) {
            final boolean hidden = repository.isHidden(app.packageName);
            Drawable icon = repository.loadIcon(app.packageName);
            View r = row(null, repository.displayName(app.packageName, app.label),
                    hidden ? getString(R.string.apps_hidden) : null,
                    hidden ? R.drawable.ic_visibility_off : R.drawable.ic_visibility,
                    new Runnable() {
                        @Override
                        public void run() {
                            repository.setHidden(app.packageName, !hidden);
                            rebuildSamePage();
                        }
                    }, 0);
            setLeadIcon(r, icon);
            content.addView(r, lp(ROW_GAP));
        }
    }

    private void buildAnimation() {
        int current = Prefs.openAnimation(this);
        choiceRow(R.string.anim_fade, current == Prefs.ANIM_FADE, Prefs.ANIM_FADE);
        choiceRow(R.string.anim_zoom, current == Prefs.ANIM_ZOOM, Prefs.ANIM_ZOOM);
        choiceRow(R.string.anim_slide, current == Prefs.ANIM_SLIDE, Prefs.ANIM_SLIDE);
        choiceRow(R.string.anim_none, current == Prefs.ANIM_NONE, Prefs.ANIM_NONE);
    }

    private void choiceRow(int titleRes, boolean selected, final int value) {
        content.addView(row(null, getString(titleRes), null,
                selected ? R.drawable.ic_verified : 0, new Runnable() {
                    @Override
                    public void run() {
                        Prefs.setOpenAnimation(SettingsActivity.this, value);
                        rebuildSamePage();
                    }
                }, 0), lp(ROW_GAP));
    }

    /** Categoria Tema: modo claro/escuro e cor de destaque (única lista de cores do app). */
    private void buildTheme() {
        content.addView(label(R.string.theme_mode), lp(8));
        boolean dark = Prefs.isDark(this);
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.addView(modeCard(R.string.mode_dark, dark, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        gap.leftMargin = Design.px(16);
        modes.addView(modeCard(R.string.mode_light, !dark, false), gap);
        content.addView(modes, lp(ROW_GAP));

        content.addView(label(R.string.theme_accent), lp(8));
        String[] names = getResources().getStringArray(R.array.accent_names);
        int current = Prefs.themeIndex(this);
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            content.addView(bannerRow(Wallpaper.colorDrawable(Prefs.ACCENTS[i]), names[i], i == current,
                    new Runnable() {
                        @Override
                        public void run() {
                            Prefs.setThemeIndex(SettingsActivity.this, index);
                            restorePage = PAGE_THEME;
                            recreate();
                        }
                    }), lp(ROW_GAP));
        }
    }

    /** Cartão de modo (Escuro ou Claro) com check no selecionado. */
    private View modeCard(int titleRes, boolean selected, final boolean dark) {
        return row(null, getString(titleRes), null, selected ? R.drawable.ic_verified : 0, new Runnable() {
            @Override
            public void run() {
                Prefs.setDark(SettingsActivity.this, dark);
                restorePage = PAGE_THEME;
                recreate();
            }
        }, 0);
    }

    private void buildWallpaper() {
        String mode = Wallpaper.mode(this);
        int index = Wallpaper.index(this);
        String[] palettes = getResources().getStringArray(R.array.palette_names);

        content.addView(label(R.string.wp_padrao), lp(8));
        for (int i = 0; i < palettes.length; i++) {
            final int p = i;
            boolean selected = Wallpaper.MODE_PALETTE.equals(mode) && index == i;
            content.addView(bannerRow(Wallpaper.paletteDrawable(i), palettes[i], selected, new Runnable() {
                @Override
                public void run() {
                    Wallpaper.setPalette(SettingsActivity.this, p);
                    rebuildSamePage();
                }
            }), lp(ROW_GAP));
        }

        // Cor sólida: usa a cor de destaque escolhida em Tema (não repete a lista de cores).
        content.addView(label(R.string.wp_colors), lp(8));
        content.addView(bannerRow(Wallpaper.colorDrawable(Prefs.accentColor(this)),
                getString(R.string.wp_solid), Wallpaper.MODE_COLOR.equals(mode), new Runnable() {
                    @Override
                    public void run() {
                        Wallpaper.setColor(SettingsActivity.this, Prefs.themeIndex(SettingsActivity.this));
                        rebuildSamePage();
                    }
                }), lp(ROW_GAP));

        content.addView(label(R.string.wp_images), lp(8));
        content.addView(row(null, getString(R.string.wp_daily), null,
                Wallpaper.MODE_DAILY.equals(mode) ? R.drawable.ic_verified : 0, new Runnable() {
                    @Override
                    public void run() {
                        Wallpaper.setDaily(SettingsActivity.this);
                        rebuildSamePage();
                    }
                }, 0), lp(ROW_GAP));
        content.addView(row(null, getString(R.string.wp_custom), null,
                Wallpaper.MODE_CUSTOM.equals(mode) ? R.drawable.ic_verified : 0, new Runnable() {
                    @Override
                    public void run() {
                        Wallpaper.pickCustom(SettingsActivity.this);
                    }
                }, 0), lp(ROW_GAP));
        content.addView(row(R.drawable.ic_menu_plus, getString(R.string.wp_add), null, 0, new Runnable() {
            @Override
            public void run() {
                Screensaver.pickGalleryPhoto(SettingsActivity.this);
            }
        }, 0), lp(ROW_GAP));
        content.addView(note(getString(R.string.wp_gallery_count, Screensaver.gallery(this).size())), lp(ROW_GAP));
    }

    private void buildScreensaver() {
        boolean on = Prefs.screensaverEnabled(this);
        content.addView(row(on ? R.drawable.ic_toggle_on : R.drawable.ic_toggle_off,
                getString(R.string.ss_enable), null, 0, new Runnable() {
                    @Override
                    public void run() {
                        Prefs.setScreensaverEnabled(SettingsActivity.this, !Prefs.screensaverEnabled(SettingsActivity.this));
                        rebuildSamePage();
                    }
                }, 0), lp(ROW_GAP));

        content.addView(label(R.string.ss_start_after), lp(8));
        int idle = Prefs.idleMinutes(this);
        for (final int minutes : Prefs.IDLE_MINUTES) {
            content.addView(row(null, getString(R.string.ss_minutes, minutes), null,
                    idle == minutes ? R.drawable.ic_verified : 0, new Runnable() {
                        @Override
                        public void run() {
                            Prefs.setIdleMinutes(SettingsActivity.this, minutes);
                            rebuildSamePage();
                        }
                    }, 0), lp(ROW_GAP));
        }

        content.addView(label(R.string.ss_content), lp(8));
        int source = Prefs.screensaverSource(this);
        content.addView(row(null, getString(R.string.ss_source_photos), null,
                source == Prefs.SOURCE_PHOTOS ? R.drawable.ic_verified : 0, new Runnable() {
                    @Override
                    public void run() {
                        Prefs.setScreensaverSource(SettingsActivity.this, Prefs.SOURCE_PHOTOS);
                        rebuildSamePage();
                    }
                }, 0), lp(ROW_GAP));
        content.addView(row(null, getString(R.string.ss_source_video), null,
                source == Prefs.SOURCE_VIDEO ? R.drawable.ic_verified : 0, new Runnable() {
                    @Override
                    public void run() {
                        Prefs.setScreensaverSource(SettingsActivity.this, Prefs.SOURCE_VIDEO);
                        rebuildSamePage();
                    }
                }, 0), lp(ROW_GAP));
        boolean hasVideo = Screensaver.video(this) != null;
        content.addView(row(R.drawable.ic_menu_plus, getString(R.string.ss_choose_video),
                hasVideo ? getString(R.string.ss_video_chosen) : getString(R.string.ss_video_none), 0,
                new Runnable() {
                    @Override
                    public void run() {
                        Screensaver.pickVideo(SettingsActivity.this);
                    }
                }, 0), lp(ROW_GAP));
        content.addView(row(R.drawable.ic_menu_plus, getString(R.string.wp_add), null, 0, new Runnable() {
            @Override
            public void run() {
                Screensaver.pickGalleryPhoto(SettingsActivity.this);
            }
        }, 0), lp(ROW_GAP));
        content.addView(note(R.string.ss_change_hint), lp(12));
    }

    // ---------------- Construtores de linhas ----------------

    /**
     * Linha padrão: ícone opcional, título, valor opcional e indicador opcional.
     * Toda linha é focável (para o painel rolar); só as com ação respondem a OK.
     */
    private View row(Integer iconRes, String title, String value, int trailingRes, Runnable action, int sectionRes) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Design.px(14), Design.px(12), Design.px(14), Design.px(12));
        r.setMinimumHeight(Design.px(56));

        if (iconRes != null && iconRes != 0) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(iconRes);
            icon.setColorFilter(Design.TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
            r.addView(icon, new LinearLayout.LayoutParams(Design.px(30), Design.px(30)));
        }

        TextView titleView = text(title, Design.TEXT, 22, Design.FONT_INTER_MEDIUM);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (iconRes != null && iconRes != 0) {
            tlp.leftMargin = Design.px(14);
        }
        r.addView(titleView, tlp);

        if (value != null) {
            TextView valueView = text(value, Design.TEXT_DIM, 20, Design.FONT_INTER_MEDIUM);
            valueView.setSingleLine(true);
            valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            valueView.setMaxWidth(Design.px(200));
            r.addView(valueView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        if (trailingRes != 0) {
            ImageView trailing = new ImageView(this);
            trailing.setImageResource(trailingRes);
            trailing.setColorFilter(Design.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Design.px(28), Design.px(28));
            plp.leftMargin = Design.px(12);
            r.addView(trailing, plp);
        }

        styleFocus(r, 16, action, sectionRes);
        return r;
    }

    /** Ícone de um aplicativo à esquerda (lista de aplicativos). */
    private void setLeadIcon(View row, Drawable icon) {
        LinearLayout r = (LinearLayout) row;
        if (icon == null) {
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        r.addView(iv, 0, new LinearLayout.LayoutParams(Design.px(40), Design.px(40)));
        ((LinearLayout.LayoutParams) iv.getLayoutParams()).rightMargin = Design.px(14);
    }

    /** Linha em forma de banner colorido (cores e padrões de fundo). */
    private View bannerRow(Drawable background, String name, boolean selected, Runnable action) {
        GradientDrawable normalBg = (GradientDrawable) background;
        normalBg.setCornerRadius(Design.px(16));
        GradientDrawable focusBg = (GradientDrawable) background.getConstantState().newDrawable().mutate();
        focusBg.setStroke(Math.max(1, Design.px(3)), Design.ACCENT);

        FrameLayout r = new FrameLayout(this);
        r.setPadding(Design.px(18), 0, Design.px(18), 0);
        r.setMinimumHeight(Design.px(68));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(name, 0xFFFFFFFF, 22, Design.FONT_INTER_SEMIBOLD);
        label.setSingleLine(true);
        inner.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) {
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_verified);
            check.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
            inner.addView(check, new LinearLayout.LayoutParams(Design.px(28), Design.px(28)));
        }
        r.addView(inner, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Design.px(68)));

        styleFocusWith(r, normalBg, focusBg, action, 0);
        return r;
    }

    private TextView label(int res) {
        TextView t = text(getString(res), Design.TEXT_DIM, 18, Design.FONT_INTER_SEMIBOLD);
        t.setPadding(Design.px(4), Design.px(8), 0, Design.px(4));
        return t;
    }

    private TextView note(int res) {
        return note(getString(res));
    }

    private TextView note(String value) {
        TextView t = text(value, Design.TEXT_DIM, 18, Design.FONT_INTER_MEDIUM);
        t.setPadding(Design.px(4), Design.px(4), Design.px(4), Design.px(4));
        return t;
    }

    private ImageView iconButton(int iconRes, Runnable action) {
        ImageView b = new ImageView(this);
        b.setImageResource(iconRes);
        b.setColorFilter(Design.TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
        b.setPadding(Design.px(12), Design.px(12), Design.px(12), Design.px(12));
        styleFocus(b, 26, action, 0);
        return b;
    }

    /**
     * Foco padrão: contorno de destaque dentro da própria linha (sem zoom,
     * para não cortar a borda). Opcionalmente atualiza o subtítulo com a seção.
     */
    private void styleFocus(View v, float radius, Runnable action, int sectionRes) {
        styleFocusWith(v, v.getBackground(), Design.focusRing(Design.GLASS, radius), action, sectionRes);
    }

    private void styleFocusWith(final View v, final Drawable normal, final Drawable focused,
                                final Runnable action, final int sectionRes) {
        v.setFocusable(true);
        if (normal != null) {
            v.setBackground(normal);
        }
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                view.setBackground(hasFocus ? focused : normal);
                if (hasFocus && sectionRes != 0) {
                    setSubtitle(getString(sectionRes));
                }
            }
        });
        if (action != null) {
            v.setClickable(true);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    action.run();
                }
            });
        }
    }

    private TextView text(String value, int color, float size, String font) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(size));
        Design.setFont(t, font);
        return t;
    }

    private LinearLayout.LayoutParams lp(float bottomDesign) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Design.px(bottomDesign);
        return p;
    }

    // ---------------- Estado do sistema ----------------

    /** Atualiza cartões, detalhe e ícone de Wi-Fi/LAN. */
    private void refreshAll() {
        if (isFinishing() || wifi == null) {
            return;
        }
        lanConnected = hasEthernet();
        boolean on = wifi.isWifiEnabled();
        boolean changed = on != lastWifiOn;
        updateStatusCards();
        if (page == PAGE_NETWORK) {
            renderDetail(changed);
        }
    }

    private boolean hasEthernet() {
        if (connectivity == null) {
            return false;
        }
        Network[] networks = connectivity.getAllNetworks();
        for (Network network : networks) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return true;
            }
        }
        return false;
    }

    private String currentSsid() {
        if (!wifi.isWifiEnabled()) {
            return null;
        }
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null || info.getSSID() == null) {
            return null;
        }
        String ssid = info.getSSID();
        if ("<unknown ssid>".equals(ssid)) {
            return null;
        }
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private WifiConfiguration findSaved(String ssid) {
        List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
        if (configs == null) {
            return null;
        }
        String quoted = "\"" + ssid + "\"";
        for (WifiConfiguration c : configs) {
            if (quoted.equals(c.SSID) || ssid.equals(c.SSID)) {
                return c;
            }
        }
        return null;
    }

    private boolean isSecured(ScanResult r) {
        String caps = r.capabilities;
        return caps.contains("WPA") || caps.contains("WEP") || caps.contains("PSK") || caps.contains("EAP");
    }

    /** Uma linha por rede (a mais forte), ordenadas pelo sinal. */
    private List<ScanResult> uniqueNetworks() {
        List<ScanResult> scanned = wifi.getScanResults();
        List<ScanResult> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (scanned != null) {
            List<ScanResult> all = new ArrayList<>(scanned);
            Collections.sort(all, new java.util.Comparator<ScanResult>() {
                @Override
                public int compare(ScanResult a, ScanResult b) {
                    return Integer.compare(b.level, a.level);
                }
            });
            for (ScanResult r : all) {
                if (r.SSID != null && !r.SSID.isEmpty() && seen.add(r.SSID)) {
                    result.add(r);
                }
            }
        }
        return result;
    }

    private boolean hasLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        }
    }

    private String deviceName() {
        try {
            String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (RuntimeException ignored) {
            // Chave ausente em algumas versões.
        }
        return Build.MODEL;
    }

    private String resolution() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels + " × " + dm.heightPixels;
    }

    private String memoryTotal() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return gigabytes(mi.totalMem);
    }

    private String storageTotal() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            return gigabytes(stat.getBlockCountLong() * stat.getBlockSizeLong());
        } catch (RuntimeException e) {
            return getString(R.string.dev_unavailable);
        }
    }

    private String gigabytes(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String securityPatch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return getString(R.string.dev_unavailable);
        }
        String patch = Build.VERSION.SECURITY_PATCH;
        return patch == null || patch.isEmpty() ? getString(R.string.dev_unavailable) : patch;
    }

    private String ipAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Sem rede.
        }
        return null;
    }

    private void openSystem(String action) {
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException e2) {
                toast(R.string.toast_cannot_open);
            }
        }
    }

    private void toast(int messageRes) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
    }
}
