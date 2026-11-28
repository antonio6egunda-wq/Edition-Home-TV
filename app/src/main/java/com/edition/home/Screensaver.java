package com.edition.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;

/**
 * Proteção de tela: depois de um tempo parado, mostra fotos da galeria (troca a cada 5 min)
 * ou um vídeo em loop. Qualquer tecla ou toque fecha e reinicia a contagem.
 */
public final class Screensaver {

    public static final int REQUEST_GALLERY = 4102;
    public static final int REQUEST_VIDEO = 4103;
    public static final long ROTATE_MS = 5L * 60L * 1000L;
    private static final long FADE_MS = 800L;

    private static final String PREFS = "home_tv_screensaver";
    private static final String KEY_GALLERY = "gallery";
    private static final String KEY_VIDEO = "video";
    private static final String SEPARATOR = "\n";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Uri> gallery(Context c) {
        List<Uri> list = new ArrayList<>();
        String raw = prefs(c).getString(KEY_GALLERY, "");
        if (raw == null || raw.isEmpty()) {
            return list;
        }
        for (String s : raw.split(SEPARATOR)) {
            if (!s.isEmpty()) {
                list.add(Uri.parse(s));
            }
        }
        return list;
    }

    public static void addToGallery(Context c, Uri uri) {
        List<Uri> list = gallery(c);
        if (!list.contains(uri)) {
            list.add(uri);
        }
        StringBuilder sb = new StringBuilder();
        for (Uri u : list) {
            if (sb.length() > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(u.toString());
        }
        prefs(c).edit().putString(KEY_GALLERY, sb.toString()).apply();
        persist(c, uri);
    }

    public static Uri video(Context c) {
        String s = prefs(c).getString(KEY_VIDEO, null);
        return s == null ? null : Uri.parse(s);
    }

    public static void setVideo(Context c, Uri uri) {
        prefs(c).edit().putString(KEY_VIDEO, uri.toString()).apply();
        persist(c, uri);
    }

    private static void persist(Context c, Uri uri) {
        try {
            c.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Sem permissão persistente a foto continua válida até o aparelho reiniciar.
        }
    }

    public static void pickGalleryPhoto(Activity activity) {
        launchPicker(activity, MediaPickerActivity.TYPE_IMAGE, REQUEST_GALLERY);
    }

    public static void pickVideo(Activity activity) {
        launchPicker(activity, MediaPickerActivity.TYPE_VIDEO, REQUEST_VIDEO);
    }

    private static void launchPicker(Activity activity, String type, int requestCode) {
        Intent intent = new Intent(activity, MediaPickerActivity.class);
        intent.putExtra(MediaPickerActivity.EXTRA_TYPE, type);
        activity.startActivityForResult(intent, requestCode);
    }

    /** Processa o retorno dos seletores de galeria e vídeo. Retorna true se algo foi salvo. */
    public static boolean handleResult(Context c, int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return false;
        }
        if (requestCode == REQUEST_GALLERY) {
            addToGallery(c, data.getData());
            return true;
        }
        if (requestCode == REQUEST_VIDEO) {
            setVideo(c, data.getData());
            return true;
        }
        return false;
    }

    // ---------------- Comportamento na tela ----------------

    private final Activity activity;
    private final FrameLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout overlay;
    private ImageView image;
    private VideoView video;
    private boolean showing;
    private List<Uri> photos = new ArrayList<>();
    private int index;

    private final Runnable startRunnable = new Runnable() {
        @Override
        public void run() {
            show();
        }
    };

    private final Runnable rotateRunnable = new Runnable() {
        @Override
        public void run() {
            if (showing) {
                next();
                handler.postDelayed(this, ROTATE_MS);
            }
        }
    };

    public Screensaver(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
    }

    /** Agenda a proteção de tela a partir de agora, se estiver ativada. */
    public void schedule() {
        handler.removeCallbacks(startRunnable);
        if (Prefs.screensaverEnabled(activity)) {
            handler.postDelayed(startRunnable, Prefs.idleMinutes(activity) * 60_000L);
        }
    }

    /** Qualquer atividade do usuário: fecha a proteção se estiver ativa e reinicia a contagem. */
    public void userActivity() {
        if (showing) {
            hide();
        }
        schedule();
    }

    /** Para tudo quando a tela inicial sai de foco. */
    public void stop() {
        handler.removeCallbacks(startRunnable);
        handler.removeCallbacks(rotateRunnable);
        if (showing) {
            hide();
        }
    }

    public void release() {
        stop();
        if (overlay != null) {
            root.removeView(overlay);
            overlay = null;
        }
    }

    private void show() {
        if (showing || !Prefs.screensaverEnabled(activity)) {
            return;
        }
        ensureOverlay();
        if (Prefs.screensaverSource(activity) == Prefs.SOURCE_VIDEO) {
            Uri uri = video(activity);
            if (uri == null) {
                return;
            }
            image.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            video.setVideoURI(uri);
            video.start();
        } else {
            photos = gallery(activity);
            if (photos.isEmpty()) {
                return;
            }
            video.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
            index = 0;
            showPhoto(0);
            handler.postDelayed(rotateRunnable, ROTATE_MS);
        }
        showing = true;
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
    }

    private void hide() {
        showing = false;
        handler.removeCallbacks(rotateRunnable);
        if (video != null) {
            video.stopPlayback();
        }
        if (overlay != null) {
            overlay.setVisibility(View.GONE);
        }
    }

    private void next() {
        if (photos.size() > 1) {
            index = (index + 1) % photos.size();
        }
        showPhoto(index);
    }

    private void showPhoto(int i) {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        Bitmap bmp = Wallpaper.decode(activity, photos.get(i), dm.widthPixels, dm.heightPixels);
        if (bmp == null) {
            return;
        }
        image.setImageBitmap(bmp);
        image.setAlpha(0f);
        image.animate().alpha(1f).setDuration(FADE_MS).start();
    }

    private void ensureOverlay() {
        if (overlay != null) {
            return;
        }
        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0xFF000000);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(false);
        overlay.setFocusable(false);

        image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        overlay.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        video = new VideoView(activity);
        video.setVisibility(View.GONE);
        video.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(android.media.MediaPlayer mp) {
                mp.setLooping(true);
            }
        });
        video.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                hide();
                return true;
            }
        });
        overlay.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }
}
