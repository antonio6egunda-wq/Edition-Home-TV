package com.edition.home;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lista os apps instalados, nomes, ocultos, dock (favoritos) e abertura de apps. */
public final class AppRepository {

    public static final int MAX_FAVORITES = 5;

    private static final String PREFS = "home_tv_prefs";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_NAME_PREFIX = "name_";

    private final Context context;
    private final PackageManager pm;
    private final SharedPreferences prefs;

    public AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.pm = context.getPackageManager();
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Todos os apps abríveis (inclui ocultos), ordenados pelo nome exibido. */
    public List<AppInfo> loadLaunchableApps() {
        List<AppInfo> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addFromCategory(Intent.CATEGORY_LEANBACK_LAUNCHER, apps, seen);
        addFromCategory(Intent.CATEGORY_LAUNCHER, apps, seen);
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    private void addFromCategory(String category, List<AppInfo> apps, Set<String> seen) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            apps.add(new AppInfo(pkg, displayName(pkg, String.valueOf(info.loadLabel(pm)))));
        }
    }

    /** Retorna null se o pacote não estiver instalado. */
    public AppInfo getAppInfo(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return new AppInfo(pkg, displayName(pkg, String.valueOf(pm.getApplicationLabel(ai))));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Nome personalizado, se houver; senão o nome original do app. */
    public String displayName(String pkg, String original) {
        String custom = prefs.getString(KEY_NAME_PREFIX + pkg, null);
        return (custom == null || custom.isEmpty()) ? original : custom;
    }

    /** Nome vazio restaura o nome original. */
    public void setCustomName(String pkg, String name) {
        if (name == null || name.isEmpty()) {
            prefs.edit().remove(KEY_NAME_PREFIX + pkg).apply();
        } else {
            prefs.edit().putString(KEY_NAME_PREFIX + pkg, name).apply();
        }
    }

    // ---------- Ocultos ----------

    public boolean isHidden(String pkg) {
        return readList(KEY_HIDDEN).contains(pkg);
    }

    /** Ocultar também tira o app da dock. */
    public void setHidden(String pkg, boolean hidden) {
        List<String> list = readList(KEY_HIDDEN);
        if (hidden) {
            if (!list.contains(pkg)) {
                list.add(pkg);
            }
            removeFavorite(pkg);
        } else {
            list.remove(pkg);
        }
        writeList(KEY_HIDDEN, list);
    }

    // ---------- Sistema ----------

    public boolean isSystemApp(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    /** Banner do app (imagem 16:9 que o app define para a TV). Retorna null se não tiver. */
    public Drawable loadBanner(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationBanner(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public Drawable loadIcon(String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Assinatura da lista de apps, para saber se algo mudou (ex.: desinstalação). */
    public static String signature(List<AppInfo> apps) {
        List<String> pkgs = new ArrayList<>();
        for (AppInfo app : apps) {
            pkgs.add(app.packageName);
        }
        return TextUtils.join(",", pkgs);
    }

    // ---------- Dock (favoritos) ----------

    public List<AppInfo> getFavoriteApps() {
        List<AppInfo> result = new ArrayList<>();
        for (String pkg : readList(KEY_FAVORITES)) {
            AppInfo app = getAppInfo(pkg);
            if (app != null) {
                result.add(app);
            }
        }
        return result;
    }

    public boolean isFavorite(String pkg) {
        return readList(KEY_FAVORITES).contains(pkg);
    }

    /** Retorna false se a dock já estiver cheia. */
    public boolean addFavorite(String pkg) {
        List<String> list = readList(KEY_FAVORITES);
        if (list.contains(pkg)) {
            return true;
        }
        if (list.size() >= MAX_FAVORITES) {
            return false;
        }
        list.add(pkg);
        writeList(KEY_FAVORITES, list);
        return true;
    }

    public void removeFavorite(String pkg) {
        List<String> list = readList(KEY_FAVORITES);
        if (list.remove(pkg)) {
            writeList(KEY_FAVORITES, list);
        }
    }

    /** Troca a posição de dois favoritos da dock. */
    public void swapFavorites(int a, int b) {
        List<String> list = readList(KEY_FAVORITES);
        if (a < 0 || b < 0 || a >= list.size() || b >= list.size()) {
            return;
        }
        Collections.swap(list, a, b);
        writeList(KEY_FAVORITES, list);
    }

    // ---------- Listas salvas ----------

    /** Lista salva, sem duplicados e só com apps instalados. */
    private List<String> readList(String key) {
        List<String> list = new ArrayList<>();
        String raw = prefs.getString(key, "");
        if (raw == null || raw.isEmpty()) {
            return list;
        }
        for (String pkg : raw.split(",")) {
            if (!pkg.isEmpty() && !list.contains(pkg)
                    && (KEY_HIDDEN.equals(key) || isInstalled(pkg))) {
                list.add(pkg);
            }
        }
        return list;
    }

    private void writeList(String key, List<String> list) {
        prefs.edit().putString(key, TextUtils.join(",", list)).apply();
    }

    private boolean isInstalled(String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---------- Abertura ----------

    /** Abre o app. Retorna false se não foi possível. */
    public boolean launch(String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(pkg);
        }
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
