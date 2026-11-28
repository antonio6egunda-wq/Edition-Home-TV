package com.edition.home;

import android.graphics.drawable.Drawable;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Seletor de arquivos próprio: navega pelo armazenamento interno e pelos pendrives/USB.
 * Não depende de app de arquivos do sistema, que muitas TVs e boxes não têm.
 */
public class MediaPickerActivity extends Activity {

    public static final String EXTRA_TYPE = "type";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_VIDEO = "video";

    private static final int REQ_STORAGE = 7201;
    private static final int MAX_ITEMS = 300;
    private static final String[] IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp"};
    private static final String[] VIDEO_EXT = {".mp4", ".m4v", ".mkv", ".mov", ".3gp", ".webm"};

    private boolean videoMode;
    private File current;
    private TextView title;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.init(this);
        videoMode = TYPE_VIDEO.equals(getIntent().getStringExtra(EXTRA_TYPE));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Design.BACKGROUND);
        setContentView(root);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Design.px(Design.SAFE_X), Design.px(Design.SAFE_Y), Design.px(Design.SAFE_X), Design.px(Design.SAFE_Y));
        root.addView(body, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        title = new TextView(this);
        title.setTextColor(Design.HOME_TEXT);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(34));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        Design.setFont(title, Design.FONT_SERIF_SEMIBOLD);
        body.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Design.px(16), 0, Design.px(16));
        scroll.addView(list, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        if (needsStoragePermission()) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        } else {
            browse(null);
        }
    }

    private boolean needsStoragePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE) {
            browse(null);
        }
    }

    @Override
    public void onBackPressed() {
        if (current == null) {
            finish();
            Prefs.applyOpenAnimation(this);
        } else {
            browse(parentOf(current));
        }
    }

    /** Pai da pasta atual; null volta à lista de armazenamentos. */
    private File parentOf(File dir) {
        for (File root : roots()) {
            if (root.getAbsolutePath().equals(dir.getAbsolutePath())) {
                return null;
            }
        }
        return dir.getParentFile();
    }

    private void browse(File dir) {
        current = dir;
        list.removeAllViews();
        if (dir == null) {
            title.setText(R.string.picker_title);
            List<File> roots = roots();
            if (roots.isEmpty()) {
                addNote(R.string.picker_no_storage);
            }
            for (final File root : roots) {
                addRow(label(root), new Runnable() {
                    @Override
                    public void run() {
                        browse(root);
                    }
                });
            }
            return;
        }

        title.setText(dir.getAbsolutePath());
        addRow(getString(R.string.back_label), new Runnable() {
            @Override
            public void run() {
                onBackPressed();
            }
        });

        File[] children = dir.listFiles();
        List<File> folders = new ArrayList<>();
        List<File> files = new ArrayList<>();
        String[] exts = videoMode ? VIDEO_EXT : IMAGE_EXT;
        if (children != null) {
            for (File f : children) {
                if (f.getName().startsWith(".")) {
                    continue;
                }
                if (f.isDirectory()) {
                    folders.add(f);
                } else if (hasExtension(f.getName(), exts)) {
                    files.add(f);
                }
            }
        }
        Comparator<File> byName = new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().toLowerCase(Locale.ROOT).compareTo(b.getName().toLowerCase(Locale.ROOT));
            }
        };
        java.util.Collections.sort(folders, byName);
        java.util.Collections.sort(files, byName);

        int shown = 0;
        for (final File f : folders) {
            if (shown++ >= MAX_ITEMS) break;
            addRow(f.getName() + "  ›", new Runnable() {
                @Override
                public void run() {
                    browse(f);
                }
            });
        }
        for (final File f : files) {
            if (shown++ >= MAX_ITEMS) break;
            addRow(f.getName(), new Runnable() {
                @Override
                public void run() {
                    choose(f);
                }
            });
        }
        if (folders.isEmpty() && files.isEmpty()) {
            addNote(R.string.picker_empty);
        }
    }

    private void choose(File file) {
        Uri uri = Uri.fromFile(file);
        if (videoMode && !MediaCheck.supportedVideo(this, uri)) {
            Toast.makeText(this, R.string.ss_video_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        Intent data = new Intent();
        data.setData(uri);
        setResult(RESULT_OK, data);
        finish();
        Prefs.applyOpenAnimation(this);
    }

    /** Raízes: armazenamento interno e pastas de pendrive/USB montadas. */
    private List<File> roots() {
        List<File> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        File internal = Environment.getExternalStorageDirectory();
        addRoot(result, seen, internal);
        String[] parents = {"/storage", "/mnt/media_rw", "/mnt/usb", "/mnt"};
        for (String p : parents) {
            File dir = new File(p);
            File[] children = dir.listFiles();
            if (children == null) {
                continue;
            }
            Arrays.sort(children);
            for (File c : children) {
                String name = c.getName();
                if ("self".equals(name) || "emulated".equals(name) || "sdcard0".equals(name)
                        || "sdcard".equals(name) && p.equals("/mnt")) {
                    continue;
                }
                addRoot(result, seen, c);
            }
        }
        return result;
    }

    private void addRoot(List<File> result, Set<String> seen, File dir) {
        if (dir == null || !dir.isDirectory() || !dir.canRead()) {
            return;
        }
        try {
            if (seen.add(dir.getCanonicalPath())) {
                result.add(dir);
            }
        } catch (java.io.IOException e) {
            // Ignora pastas que não podem ser resolvidas.
        }
    }

    private String label(File root) {
        if (root.equals(Environment.getExternalStorageDirectory())) {
            return getString(R.string.picker_internal) ;
        }
        return getString(R.string.picker_usb, root.getName());
    }

    private boolean hasExtension(String name, String[] exts) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String e : exts) {
            if (lower.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    private void addNote(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextColor(Design.HOME_TEXT_DIM);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(22));
        t.setPadding(Design.px(8), Design.px(8), Design.px(8), Design.px(8));
        list.addView(t);
    }

    private void addRow(String text, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Design.HOME_TEXT);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(24));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setPadding(Design.px(20), Design.px(16), Design.px(20), Design.px(16));
        Design.setFont(t, Design.FONT_INTER_MEDIUM);
        t.setFocusable(true);
        t.setClickable(true);
        final Drawable focused = Design.focusRing(Design.HOME_GLASS_FOCUS, 14);
        final GradientDrawable normal = Design.rect(Design.HOME_GLASS, 14);
        t.setBackground(normal);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(hasFocus ? focused : normal);
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Design.px(10);
        list.addView(t, lp);
    }
}
