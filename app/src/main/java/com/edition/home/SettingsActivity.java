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
    private static final int PAGE_THEME = 6;
    private static final int PAGE_LANGUAGE = 7;
    private static final int PAGE_DATETIME = 8;
    private static final int PAGE_STORAGE = 9;

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
            case PAGE_THEME:
                return R.string.settings_theme;
            case PAGE_LANGUAGE:
                return R.string.settings_language;
            case PAGE_DATETIME:
                return R.string.settings_datetime;
            case PAGE_STORAGE:
                return R.string.settings_storage;
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
            case PAGE_THEME:
                buildTheme();
                break;
            case PAGE_LANGUAGE:
                buildLanguage();
                break;
            case PAGE_DATETIME:
                buildDateTime();
                break;
            case PAGE_STORAGE:
                buildStorage();
                break;
            default:
                break;
        }
    }

    // ---------------- Página principal ----------------

    private void buildRoot() {
        menuRow(R.drawable.ic_wifi_lock, R.string.settings_network, R.string.settings_network, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_NETWORK); } });
        menuRow(R.drawable.ic_palette, R.string.settings_theme, R.string.settings_theme, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_THEME); } });
        menuRow(R.drawable.ic_wallpaper, R.string.settings_look, R.string.settings_look, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_LOOK); } });
        menuRow(R.drawable.ic_app, R.string.settings_apps, R.string.settings_apps, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_APPS); } });
        menuRow(R.drawable.ic_language, R.string.settings_language, R.string.settings_language, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_LANGUAGE); } });
        menuRow(R.drawable.ic_schedule, R.string.settings_datetime, R.string.settings_datetime, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_DATETIME); } });
        menuRow(R.drawable.ic_info, R.string.settings_device, R.string.settings_device, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_DEVICE); } });
        menuRow(R.drawable.ic_storage, R.string.settings_storage, R.string.settings_storage, 0,
                new Runnable() { @Override public void run() { showPage(PAGE_STORAGE); } });
        menuRow(R.drawable.ic_settings, R.string.settings_more, R.string.settings_more, 0,
                new Runnable() { @Override public void run() { openSystem(Settings.ACTION_SETTINGS); } });
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
    /** Idioma do app e entrada de teclado. */
    private void buildLanguage() {
        String current = java.util.Locale.getDefault().getLanguage();
        content.addView(label(R.string.settings_language), lp(8));
        content.addView(languageRow("pt", R.string.lang_pt, current), lp(ROW_GAP));
        content.addView(languageRow("en", R.string.lang_en, current), lp(ROW_GAP));

        content.addView(label(R.string.settings_keyboard), lp(8));
        content.addView(row(null, getString(R.string.kb_setup), null, 0, new Runnable() {
            @Override
            public void run() {
                openSystem(Settings.ACTION_INPUT_METHOD_SETTINGS);
            }
        }, 0), lp(ROW_GAP));
    }

    private View languageRow(final String tag, int titleRes, String current) {
        return row(null, getString(titleRes), null, tag.equals(current) ? R.drawable.ic_verified : 0,
                new Runnable() {
                    @Override
                    public void run() {
                        if (applySystemLanguage(tag)) {
                            restorePage = PAGE_LANGUAGE;
                            recreate();
                        } else {
                            toast(R.string.sys_no_permission);
                        }
                    }
                }, 0);
    }

    /** Troca o idioma do sistema (Configuration persistente). Precisa de CHANGE_CONFIGURATION. */
    private boolean applySystemLanguage(String tag) {
        try {
            Object am = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            Class<?> iam = Class.forName("android.app.IActivityManager");
            android.content.res.Configuration cfg =
                    new android.content.res.Configuration(getResources().getConfiguration());
            cfg.locale = new java.util.Locale(tag);
            try {
                iam.getMethod("updatePersistentConfiguration", android.content.res.Configuration.class)
                        .invoke(am, cfg);
            } catch (NoSuchMethodException e) {
                iam.getMethod("updateConfiguration", android.content.res.Configuration.class).invoke(am, cfg);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Data e hora: mostra o que está no aparelho e o formato de 12 ou 24 horas. */
    private void buildDateTime() {
        java.util.Locale loc = java.util.Locale.getDefault();
        java.util.Date now = new java.util.Date();
        boolean h24 = Prefs.clock24(this);

        content.addView(label(R.string.dt_current), lp(8));
        content.addView(row(null, getString(R.string.dt_date),
                new java.text.SimpleDateFormat("dd/MM/yyyy", loc).format(now), 0,
                new Runnable() { @Override public void run() { pickDate(); } }, 0), lp(ROW_GAP));
        content.addView(row(null, getString(R.string.dt_time),
                new java.text.SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", loc).format(now), 0,
                new Runnable() { @Override public void run() { pickTime(); } }, 0), lp(ROW_GAP));
        content.addView(row(null, getString(R.string.dt_timezone),
                java.util.TimeZone.getDefault().getDisplayName(false, java.util.TimeZone.LONG, loc), 0,
                new Runnable() { @Override public void run() { pickZone(); } }, 0), lp(ROW_GAP));

        content.addView(label(R.string.dt_format), lp(8));
        content.addView(row(h24 ? R.drawable.ic_toggle_on : R.drawable.ic_toggle_off, getString(R.string.dt_24h),
                null, 0, new Runnable() {
                    @Override
                    public void run() {
                        Prefs.setClock24(SettingsActivity.this, !Prefs.clock24(SettingsActivity.this));
                        rebuildSamePage();
                    }
                }, 0), lp(ROW_GAP));
        content.addView(note(R.string.dt_note), lp(12));
    }

    /** Armazenamento e RAM lidos do kernel (RealStorage), sem valores do firmware. */
    private void buildStorage() {
        long[] mem = RealStorage.memory();
        content.addView(label(R.string.storage_ram), lp(8));
        addRaw(getString(R.string.storage_total), valueOr(RealStorage.formatBinary(mem[0])));
        addRaw(getString(R.string.storage_avail), valueOr(RealStorage.formatBinary(mem[1])));

        content.addView(label(R.string.storage_disks), lp(8));
        java.util.List<RealStorage.Disk> disks = RealStorage.disks();
        if (disks.isEmpty()) {
            addRaw(getString(R.string.storage_disks), getString(R.string.dev_unavailable));
        }
        for (RealStorage.Disk d : disks) {
            addRaw(d.name, valueOr(RealStorage.format(d.bytes)));
        }

        content.addView(label(R.string.storage_parts), lp(8));
        for (RealStorage.Partition part : RealStorage.partitions()) {
            addRaw(part.mount + "  " + getString(R.string.storage_total), valueOr(RealStorage.format(part.total)));
            addRaw(part.mount + "  " + getString(R.string.storage_free), valueOr(RealStorage.format(part.free)));
        }
        content.addView(note(R.string.storage_note), lp(12));
    }

    private void addRaw(String title, String value) {
        content.addView(row(null, title, value, 0, null, 0), lp(ROW_GAP));
    }

    private String valueOr(String value) {
        return value != null ? value : getString(R.string.dev_unavailable);
    }

    private void pickDate() {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(this, new android.app.DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(android.widget.DatePicker view, int y, int m, int d) {
                java.util.Calendar n = java.util.Calendar.getInstance();
                n.set(y, m, d, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), 0);
                setSystemTime(n.getTimeInMillis());
            }
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH),
                c.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime() {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        new android.app.TimePickerDialog(this, new android.app.TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(android.widget.TimePicker view, int hour, int minute) {
                java.util.Calendar n = java.util.Calendar.getInstance();
                n.set(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH),
                        c.get(java.util.Calendar.DAY_OF_MONTH), hour, minute, 0);
                setSystemTime(n.getTimeInMillis());
            }
        }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
                Prefs.clock24(this)).show();
    }

    private void setSystemTime(long millis) {
        boolean ok;
        try {
            ok = android.os.SystemClock.setCurrentTimeMillis(millis);
        } catch (SecurityException e) {
            ok = false;
        }
        if (!ok) {
            toast(R.string.sys_no_permission);
        }
        rebuildSamePage();
    }

    private void pickZone() {
        final String[] ids = {
                "America/Sao_Paulo", "America/Manaus", "America/Fortaleza", "America/Rio_Branco",
                "Africa/Luanda", "Europe/Lisbon", "Europe/London", "America/New_York", "UTC"
        };
        java.util.Locale loc = java.util.Locale.getDefault();
        String[] names = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            names[i] = java.util.TimeZone.getTimeZone(ids[i]).getDisplayName(false, java.util.TimeZone.LONG, loc);
        }
        Design.dialog(this)
                .setTitle(R.string.dt_zone_pick)
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        setSystemTimeZone(ids[which]);
                    }
                })
                .show();
    }

    private void setSystemTimeZone(String id) {
        try {
            ((android.app.AlarmManager) getSystemService(ALARM_SERVICE)).setTimeZone(id);
        } catch (SecurityException e) {
            toast(R.string.sys_no_permission);
        }
        rebuildSamePage();
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
        LinearLayout modes = pairRow();
        modes.addView(modeCard(R.drawable.ic_dark_mode, R.string.mode_dark, dark, true), cellLp(true, -1));
        modes.addView(modeCard(R.drawable.ic_light_mode, R.string.mode_light, !dark, false), cellLp(false, -1));
        content.addView(modes, lp(ROW_GAP));

        content.addView(label(R.string.theme_accent), lp(8));
        String[] names = getResources().getStringArray(R.array.accent_names);
        int current = Prefs.themeIndex(this);
        LinearLayout grid = null;
        for (int i = 0; i < names.length; i++) {
            if (i % 4 == 0) {
                grid = pairRow();
                content.addView(grid, lp(ROW_GAP));
            }
            grid.addView(accentCircle(Prefs.ACCENTS[i], names[i], i == current, i), cellLp(i % 4 == 0, -1));
        }
    }

    /** Cartão de modo (Escuro ou Claro) com check no selecionado. */
    private View modeCard(int iconRes, int titleRes, boolean selected, final boolean dark) {
        return row(iconRes, getString(titleRes), null, selected ? R.drawable.ic_verified : 0, new Runnable() {
            @Override
            public void run() {
                Prefs.setDark(SettingsActivity.this, dark);
                restorePage = PAGE_THEME;
                recreate();
            }
        }, 0);
    }

    /** Círculo de cor de destaque com nome embaixo. Escolher reinicia a tela com a nova cor. */
    private View accentCircle(int color, String name, boolean selected, final int index) {
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, Design.px(6), 0, Design.px(6));
        col.setFocusable(true);
        col.setClickable(true);

        FrameLayout circle = new FrameLayout(this);
        View fill = new View(this);
        fill.setBackground(Design.oval(color));
        circle.addView(fill, new FrameLayout.LayoutParams(Design.px(68), Design.px(68), Gravity.CENTER));
        final View ring = new View(this);
        GradientDrawable ringBg = Design.oval(0x00000000);
        ringBg.setStroke(Math.max(1, Design.px(3)), Design.ACCENT);
        ring.setBackground(ringBg);
        ring.setVisibility(View.GONE);
        circle.addView(ring, new FrameLayout.LayoutParams(Design.px(84), Design.px(84), Gravity.CENTER));
        if (selected) {
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_verified);
            check.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
            circle.addView(check, new FrameLayout.LayoutParams(Design.px(30), Design.px(30), Gravity.CENTER));
        }
        col.addView(circle, new LinearLayout.LayoutParams(Design.px(84), Design.px(84)));

        TextView caption = text(name, Design.TEXT, 18, Design.FONT_INTER_MEDIUM);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine(true);
        col.addView(caption, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        col.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                ring.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
            }
        });
        col.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setThemeIndex(SettingsActivity.this, index);
                restorePage = PAGE_THEME;
                recreate();
            }
        });
        return col;
    }

    private LinearLayout pairRow() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    /** Célula de uma linha de cards; h em px de desenho, -1 = altura do conteúdo. */
    private LinearLayout.LayoutParams cellLp(boolean first, float designH) {
        int h = designH < 0 ? LinearLayout.LayoutParams.WRAP_CONTENT : Design.px(designH);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, 1f);
        if (!first) {
            p.leftMargin = Design.px(16);
        }
        return p;
    }

    /**
     * Card com imagem (ou fundo) e legenda embaixo. Foco mostra o contorno de destaque;
     * selecionado mostra um check no canto. showAdd mostra o sinal de + (sem foto).
     */
    private FrameLayout tile(android.graphics.Bitmap bmp, Drawable bg, String name, float textSize,
                             boolean selected, boolean showAdd, final Runnable action) {
        final FrameLayout card = new FrameLayout(this);
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Design.px(18));
            }
        });
        if (bmp != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(bmp);
            card.addView(image, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else if (bg != null) {
            card.setBackground(bg);
        } else {
            card.setBackgroundColor(Design.GLASS);
        }
        if (showAdd) {
            ImageView add = new ImageView(this);
            add.setImageResource(R.drawable.ic_menu_plus);
            add.setColorFilter(Design.TEXT_DIM, android.graphics.PorterDuff.Mode.SRC_IN);
            card.addView(add, new FrameLayout.LayoutParams(Design.px(44), Design.px(44), Gravity.CENTER));
        }

        TextView caption = text(name, 0xFFFFFFFF, textSize, Design.FONT_INTER_SEMIBOLD);
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setPadding(Design.px(8), Design.px(3), Design.px(8), Design.px(3));
        caption.setBackground(Design.rect(0xA6000000, 10));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        clp.setMargins(Design.px(8), 0, Design.px(8), Design.px(8));
        card.addView(caption, clp);

        if (selected) {
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_verified);
            check.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
            check.setPadding(Design.px(5), Design.px(5), Design.px(5), Design.px(5));
            check.setBackground(Design.oval(0xCC000000));
            FrameLayout.LayoutParams chk = new FrameLayout.LayoutParams(
                    Design.px(32), Design.px(32), Gravity.TOP | Gravity.END);
            chk.setMargins(0, Design.px(8), Design.px(8), 0);
            card.addView(check, chk);
        }

        final View ring = new View(this);
        GradientDrawable ringBg = Design.rect(0x00000000, 18);
        ringBg.setStroke(Math.max(1, Design.px(3)), Design.ACCENT);
        ring.setBackground(ringBg);
        ring.setVisibility(View.GONE);
        card.addView(ring, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                ring.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return card;
    }

    private void buildWallpaper() {
        String mode = Wallpaper.mode(this);
        int index = Wallpaper.index(this);
        String[] palettes = getResources().getStringArray(R.array.palette_names);

        content.addView(label(R.string.wp_padrao), lp(8));
        LinearLayout grid = null;
        for (int i = 0; i < palettes.length; i++) {
            if (i % 4 == 0) {
                grid = pairRow();
                content.addView(grid, lp(ROW_GAP));
            }
            final int p = i;
            boolean selected = Wallpaper.MODE_PALETTE.equals(mode) && index == i;
            grid.addView(tile(null, Wallpaper.paletteDrawable(i), palettes[i], 16, selected, false,
                    new Runnable() {
                        @Override
                        public void run() {
                            Wallpaper.setPalette(SettingsActivity.this, p);
                            rebuildSamePage();
                        }
                    }), cellLp(i % 4 == 0, 72));
        }

        content.addView(label(R.string.wp_images), lp(8));
        LinearLayout photos = pairRow();
        android.net.Uri daily = Wallpaper.dailyUri(this);
        android.graphics.Bitmap dailyBmp = daily == null ? null : Wallpaper.decode(this, daily, 320, 180);
        photos.addView(tile(dailyBmp, dailyBmp == null ? Wallpaper.paletteDrawable(0) : null,
                getString(R.string.wp_daily), 18, Wallpaper.MODE_DAILY.equals(mode), false,
                new Runnable() {
                    @Override
                    public void run() {
                        Wallpaper.setDaily(SettingsActivity.this);
                        rebuildSamePage();
                    }
                }), cellLp(true, 124));

        android.net.Uri custom = Wallpaper.customUri(this);
        android.graphics.Bitmap customBmp = custom == null ? null : Wallpaper.decode(this, custom, 320, 180);
        photos.addView(tile(customBmp, null, getString(R.string.wp_custom), 18,
                Wallpaper.MODE_CUSTOM.equals(mode), customBmp == null,
                new Runnable() {
                    @Override
                    public void run() {
                        Wallpaper.pickCustom(SettingsActivity.this);
                    }
                }), cellLp(false, 124));
        content.addView(photos, lp(ROW_GAP));
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
