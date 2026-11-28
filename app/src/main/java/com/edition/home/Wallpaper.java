package com.edition.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Papel de parede da tela inicial.
 *
 * Modos: padrão (degradês), cor de destaque, imagem do dia (Bing) e foto própria.
 * Imagens são decodificadas em segundo plano em RGB_565 para economizar memória,
 * que é curta nas boxes.
 */
public final class Wallpaper {

    public static final int REQUEST_PICK = 4101;

    private static final String PREFS = "home_tv_look";
    private static final String KEY_MODE = "wallpaper_mode";
    private static final String KEY_INDEX = "wallpaper_index";
    private static final String KEY_URI = "wallpaper_uri";
    private static final String KEY_SHOWN_DAY = "daily_shown_day";
    private static final String KEY_LAST_ATTEMPT = "daily_last_attempt";
    private static final String KEY_STAMP = "wallpaper_stamp";

    public static final String MODE_PALETTE = "palette";
    public static final String MODE_COLOR = "color";
    public static final String MODE_DAILY = "daily";
    public static final String MODE_CUSTOM = "custom";

    /** Degradês dos padrões (topo-esquerda para base-direita). */
    private static final int[][] PALETTES = {
            {0xFF1C2140, 0xFF0B0E1F}, // noite
            {0xFF0E4D64, 0xFF062131}, // oceano
            {0xFF1E3A8A, 0xFF0A1433}, // azul profundo
            {0xFF5C1A2E, 0xFF1A0810}, // vinho
            {0xFF1F5C3F, 0xFF0A2116}, // floresta
            {0xFF5A2D6E, 0xFF1C0C24}, // ameixa
            {0xFF3A4150, 0xFF121519}, // ardósia
            {0xFF8A5A1E, 0xFF2B1A07}  // âmbar
    };

    private static final String TAG = "wallpaper_layer";
    private static final long RETRY_MS = 30L * 60L * 1000L;
    private static final String BING_API =
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=pt-BR";
    private static final String BING_HOST = "https://www.bing.com";

    private static final AtomicBoolean fetching = new AtomicBoolean(false);
    private static final AtomicInteger generation = new AtomicInteger(0);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Wallpaper() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String mode(Context c) {
        return prefs(c).getString(KEY_MODE, MODE_PALETTE);
    }

    public static int index(Context c) {
        return prefs(c).getInt(KEY_INDEX, 0);
    }

    public static long stamp(Context c) {
        return prefs(c).getLong(KEY_STAMP, 0L);
    }

    public static Uri customUri(Context c) {
        String s = prefs(c).getString(KEY_URI, null);
        return s == null ? null : Uri.parse(s);
    }

    /** Uri da imagem do dia mais recente baixada, para mostrar o cartão. Null se não houver. */
    public static Uri dailyUri(Context c) {
        File f = newestDailyFile(c);
        return f == null ? null : Uri.fromFile(f);
    }

    /** Verdadeiro quando a imagem do dia ainda não foi baixada hoje. */
    public static boolean needsRefresh(Context c) {
        return MODE_DAILY.equals(mode(c)) && !today().equals(prefs(c).getString(KEY_SHOWN_DAY, ""));
    }

    private static void save(Context c, String mode, int index) {
        prefs(c).edit()
                .putString(KEY_MODE, mode)
                .putInt(KEY_INDEX, index)
                .putLong(KEY_STAMP, System.currentTimeMillis())
                .apply();
    }

    public static void setPalette(Context c, int index) {
        save(c, MODE_PALETTE, index % PALETTES.length);
    }

    public static void setColor(Context c, int accentIndex) {
        save(c, MODE_COLOR, accentIndex % Prefs.ACCENTS.length);
    }

    public static void setDaily(Context c) {
        prefs(c).edit().putLong(KEY_LAST_ATTEMPT, 0L).apply();
        save(c, MODE_DAILY, 0);
    }

    /** Degradê de um padrão. */
    public static Drawable paletteDrawable(int index) {
        int[] colors = PALETTES[index % PALETTES.length];
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
    }

    /** Fundo na cor de destaque: vai da cor até uma versão mais escura. */
    public static Drawable colorDrawable(int color) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color, shade(color, 0.35f)});
    }

    private static int shade(int color, float f) {
        return Color.argb(255,
                (int) (Color.red(color) * f),
                (int) (Color.green(color) * f),
                (int) (Color.blue(color) * f));
    }

    /** Abre o seletor próprio do app (pastas do aparelho e pendrive), sem depender de app de arquivos. */
    public static void pickCustom(Activity activity) {
        Intent intent = new Intent(activity, MediaPickerActivity.class);
        intent.putExtra(MediaPickerActivity.EXTRA_TYPE, MediaPickerActivity.TYPE_IMAGE);
        activity.startActivityForResult(intent, REQUEST_PICK);
    }

    /** Processa o retorno do seletor de foto. Retorna true se a foto foi salva. */
    public static boolean handleResult(Context c, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return false;
        }
        Uri uri = data.getData();
        try {
            c.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Alguns seletores não concedem permissão persistente; a foto ainda pode ser lida agora.
        }
        prefs(c).edit().putString(KEY_URI, uri.toString()).apply();
        save(c, MODE_CUSTOM, 0);
        return true;
    }

    /**
     * Aplica o papel de parede. Cores e degradês são imediatos; imagens são decodificadas
     * em segundo plano e entram quando prontas (se nada mais mudou no meio tempo).
     */
    public static void apply(final Context context, final FrameLayout root) {
        final int gen = generation.incrementAndGet();
        removeLayers(root);

        String mode = mode(context);
        int index = index(context);
        if (MODE_COLOR.equals(mode)) {
            root.setBackground(colorDrawable(Prefs.ACCENTS[index % Prefs.ACCENTS.length]));
            return;
        }
        if (!MODE_CUSTOM.equals(mode) && !MODE_DAILY.equals(mode)) {
            root.setBackground(paletteDrawable(index));
            return;
        }

        root.setBackground(paletteDrawable(0));
        final Context app = context.getApplicationContext();
        Uri source = MODE_CUSTOM.equals(mode) ? customUri(context) : dailySource(context, root);
        if (source == null) {
            return;
        }
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        final int w = dm.widthPixels;
        final int h = dm.heightPixels;
        final Uri src = source;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decode(app, src, w, h);
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bmp != null && gen == generation.get()) {
                            addLayers(app, root, bmp);
                        }
                    }
                });
            }
        }, "wallpaper").start();
    }

    private static void addLayers(Context context, FrameLayout root, Bitmap bmp) {
        removeLayers(root);
        ImageView image = new ImageView(context);
        image.setTag(TAG);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(bmp);
        root.addView(image, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View scrim = new View(context);
        scrim.setTag(TAG);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33000000, 0x66000000});
        scrim.setBackground(g);
        root.addView(scrim, 1, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private static void removeLayers(FrameLayout root) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            if (TAG.equals(root.getChildAt(i).getTag())) {
                root.removeViewAt(i);
            }
        }
    }

    /** Decodifica uma imagem reduzida para a tela, em RGB_565. Pode ser chamado fora da thread principal. */
    public static Bitmap decode(Context context, Uri uri, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight);
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
            in.close();
            return bmp;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int sampleSize(int width, int height, int reqWidth, int reqHeight) {
        int sample = 1;
        if (width > reqWidth || height > reqHeight) {
            while (width / (sample * 2) >= reqWidth && height / (sample * 2) >= reqHeight) {
                sample *= 2;
            }
        }
        return sample;
    }

    // ---------------- Imagem do dia (Bing) ----------------

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static File dailyFile(Context c, String day) {
        return new File(c.getFilesDir(), "daily_" + day + ".jpg");
    }

    private static File newestDailyFile(Context c) {
        File[] files = c.getFilesDir().listFiles();
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File f : files) {
            if (f.getName().startsWith("daily_") && (newest == null || f.getName().compareTo(newest.getName()) > 0)) {
                newest = f;
            }
        }
        return newest;
    }

    /** Devolve a imagem do dia se já existir; caso contrário, baixa em segundo plano e usa a mais recente. */
    private static Uri dailySource(Context c, FrameLayout root) {
        String day = today();
        File todayFile = dailyFile(c, day);
        if (todayFile.exists()) {
            prefs(c).edit().putString(KEY_SHOWN_DAY, day).apply();
            return Uri.fromFile(todayFile);
        }
        startDailyFetch(c, root, day);
        File newest = newestDailyFile(c);
        return newest == null ? fallbackUri(c) : Uri.fromFile(newest);
    }

    /** Imagem embutida em assets/fallback, usada sem internet e sem imagem do dia salva. */
    private static Uri fallbackUri(Context c) {
        File out = new File(c.getFilesDir(), "fallback_wallpaper.jpg");
        if (out.exists() && out.length() > 0) {
            return Uri.fromFile(out);
        }
        try {
            String chosen = null;
            String[] names = c.getAssets().list("fallback");
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String n : names) {
                    String lower = n.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
                        chosen = n;
                        break;
                    }
                }
            }
            if (chosen == null) {
                return null;
            }
            InputStream in = c.getAssets().open("fallback/" + chosen);
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.close();
            in.close();
            return Uri.fromFile(out);
        } catch (IOException e) {
            return null;
        }
    }

    private static void startDailyFetch(final Context c, final FrameLayout root, final String day) {
        long last = prefs(c).getLong(KEY_LAST_ATTEMPT, 0L);
        if (System.currentTimeMillis() - last < RETRY_MS) {
            return;
        }
        if (!fetching.compareAndSet(false, true)) {
            return;
        }
        prefs(c).edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply();
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ok = fetchDaily(app, day);
                } finally {
                    fetching.set(false);
                }
                if (ok) {
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            apply(app, root);
                        }
                    });
                }
            }
        }, "daily-wallpaper").start();
    }

    private static boolean fetchDaily(Context c, String day) {
        try {
            String json = httpGet(BING_API);
            JSONObject image = new JSONObject(json).getJSONArray("images").getJSONObject(0);
            String url = BING_HOST + image.getString("url");
            File tmp = new File(c.getFilesDir(), "daily_tmp.jpg");
            if (!download(url, tmp)) {
                return false;
            }
            File target = dailyFile(c, day);
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return false;
            }
            // Remove imagens de dias anteriores.
            File[] files = c.getFilesDir().listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("daily_") && !f.equals(target)) {
                        f.delete();
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String httpGet(String address) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) HomeTV");
        try {
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static boolean download(String address, File target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        try {
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(target);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
            return target.length() > 0;
        } finally {
            conn.disconnect();
        }
    }
}
