package com.edition.home;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Gaveta com todos os aplicativos que aparecem na lista. Cinco colunas; o painel
 * mostra até três linhas e rola o resto. Ocultos ficam atrás do botão "Mostrar ocultos".
 */
public class AppDrawerActivity extends Activity {

    public static final String EXTRA_PICK_FOR_DOCK = "pick_for_dock";
    private static final String TOGGLE_ID = "__toggle_hidden__";

    private static final int COLS = 5;
    private static final float PANEL_W = 1040f;
    private static final float PANEL_X = (Design.WIDTH - PANEL_W) / 2f;
    private static final float PANEL_BOTTOM = Design.SAFE_BOTTOM;
    private static final float PAD_X = 48f;
    private static final float PAD_Y = 24f;
    private static final float COL_STEP = 192f;
    private static final float GAP_ROW = 16f;
    private static final int MAX_ROWS = 3;
    private static final float COUNT_W = 360f;
    private static final float COUNT_H = 32f;

    /** Mostrar ocultos: fica entre as sessões da gaveta. */
    private static boolean showHidden;

    private AppRepository repository;
    private StatusBar statusBar;
    private AppMenu menu;
    private FrameLayout root;
    private String renderedLook;
    private long wallpaperStamp;
    private String loadedSignature;
    private boolean pickForDock;
    private List<AppInfo> visibleApps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.init(this);
        renderedLook = Prefs.lookKey(this);
        repository = new AppRepository(this);
        pickForDock = getIntent().getBooleanExtra(EXTRA_PICK_FOR_DOCK, false);

        root = new FrameLayout(this);
        root.setBackgroundColor(Design.BACKGROUND);
        setContentView(root);
        Wallpaper.apply(this, root);
        wallpaperStamp = Wallpaper.stamp(this);

        statusBar = new StatusBar(this, root);
        menu = new AppMenu(this, root, repository, new AppMenu.Listener() {
            @Override
            public void onMoveRequested(int slot) {
                // Não se aplica à gaveta.
            }

            @Override
            public void onChanged() {
                recreate();
            }
        });

        build();
        if (pickForDock) {
            toast(R.string.toast_pick_hint);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        statusBar.start();
    }

    @Override
    protected void onStop() {
        statusBar.stop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Prefs.lookKey(this).equals(renderedLook)) {
            recreate();
            return;
        }
        long stamp = Wallpaper.stamp(this);
        if (stamp != wallpaperStamp) {
            wallpaperStamp = stamp;
            Wallpaper.apply(this, root);
        }
        // Só recria a grade se a lista de aplicativos mudou (instalar ou remover).
        if (!loadedSignature.equals(AppRepository.signature(repository.loadLaunchableApps()))) {
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        // Ao recriar a tela (mudou algo), mantém "mostrar ocultos"; só zera ao fechar a gaveta.
        if (isFinishing()) {
            showHidden = false;
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (menu.isShowing() && event.getAction() == KeyEvent.ACTION_UP
                && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            menu.hide();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (menu.isShowing()) {
            menu.hide();
            return;
        }
        super.onBackPressed();
        Prefs.applyOpenAnimation(this);
    }

    private void build() {
        List<AppInfo> all = repository.loadLaunchableApps();
        loadedSignature = AppRepository.signature(all);

        visibleApps = new ArrayList<>();
        for (AppInfo app : all) {
            if (showHidden || !repository.isHidden(app.packageName)) {
                visibleApps.add(app);
            }
        }
        boolean anyHidden = false;
        for (AppInfo app : all) {
            if (repository.isHidden(app.packageName)) {
                anyHidden = true;
                break;
            }
        }

        List<AppInfo> entries = new ArrayList<>(visibleApps);
        if (anyHidden) {
            entries.add(new AppInfo(TOGGLE_ID, getString(showHidden ? R.string.menu_hide_list : R.string.menu_show_hidden)));
        }

        int rows = Math.max(1, (entries.size() + COLS - 1) / COLS);
        int visibleRows = Math.min(rows, MAX_ROWS);
        float panelH = panelHeight(visibleRows);
        float panelY = PANEL_BOTTOM - panelH;

        View panel = new View(this);
        panel.setBackground(Design.rect(Design.PANEL, 24));
        Design.place(root, panel, PANEL_X, panelY, PANEL_W, panelH);

        TextView count = new TextView(this);
        count.setText(getResources().getQuantityString(R.plurals.apps_count, visibleApps.size(), visibleApps.size()));
        count.setTextColor(Design.TEXT_DIM);
        count.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(20));
        count.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        count.setSingleLine(true);
        Design.setFont(count, Design.FONT_INTER_MEDIUM);
        Design.place(root, count, PANEL_X + PANEL_W - COUNT_W - 24f, panelY - COUNT_H - 10f, COUNT_W, COUNT_H);

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.drawer_empty);
            empty.setTextColor(Design.TEXT_DIM);
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Design.px(22));
            empty.setGravity(Gravity.CENTER);
            Design.place(root, empty, PANEL_X, panelY, PANEL_W, panelH);
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        FrameLayout content = new FrameLayout(this);
        content.setClipChildren(false);
        float contentH = PAD_Y * 2 + rows * AppTileView.TOTAL_H + (rows - 1) * GAP_ROW;
        scroll.addView(content, new FrameLayout.LayoutParams(Design.px(PANEL_W), Design.px(contentH)));
        Design.place(root, scroll, PANEL_X, panelY, PANEL_W, panelH);

        AppTileView[] tiles = new AppTileView[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            final AppInfo app = entries.get(i);
            final int col = i % COLS;
            final int row = i / COLS;
            AppTileView tile = new AppTileView(this, 20, false);
            tile.setId(View.generateViewId());
            if (TOGGLE_ID.equals(app.packageName)) {
                tile.bindApp(app, null, getDrawable(showHidden ? R.drawable.ic_visibility_off : R.drawable.ic_visibility));
                tile.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showHidden = !showHidden;
                        recreate();
                    }
                });
            } else {
                Drawable icon = repository.loadIcon(app.packageName);
                tile.bindApp(app, repository.loadBanner(app.packageName), icon);
                tile.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onAppClick(app);
                    }
                });
                tile.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        menu.showForDrawer(app, v);
                        return true;
                    }
                });
            }
            Design.placeLocal(content, tile, PAD_X + col * COL_STEP, PAD_Y + row * (AppTileView.TOTAL_H + GAP_ROW),
                    AppTileView.W, AppTileView.TOTAL_H);
            tiles[i] = tile;
        }
        linkFocus(tiles, rows);
        tiles[0].post(new Runnable() {
            @Override
            public void run() {
                tiles[0].requestFocus();
            }
        });
    }

    private void onAppClick(AppInfo app) {
        if (pickForDock) {
            if (repository.isFavorite(app.packageName)) {
                toast(R.string.toast_already_in_dock);
                return;
            }
            if (!repository.addFavorite(app.packageName)) {
                toast(R.string.toast_full);
                return;
            }
            toast(R.string.toast_added);
            finish();
            Prefs.applyOpenAnimation(this);
            return;
        }
        if (!repository.launch(app.packageName)) {
            toast(R.string.toast_cannot_open);
        }
    }

    private void linkFocus(AppTileView[] tiles, int rows) {
        for (int i = 0; i < tiles.length; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int left = col > 0 ? i - 1 : -1;
            int right = i + 1 < tiles.length && (i + 1) % COLS != 0 ? i + 1 : -1;
            int up = row > 0 ? i - COLS : -1;
            int down = i + COLS < tiles.length ? i + COLS : -1;
            // Mantém o foco dentro da grade; sem vizinho, o foco não sai do painel.
            if (left >= 0) {
                tiles[i].setNextFocusLeftId(tiles[left].getId());
            }
            if (right >= 0) {
                tiles[i].setNextFocusRightId(tiles[right].getId());
            }
            if (up >= 0) {
                tiles[i].setNextFocusUpId(tiles[up].getId());
            }
            if (down >= 0) {
                tiles[i].setNextFocusDownId(tiles[down].getId());
            }
        }
    }

    private static float panelHeight(int rows) {
        return PAD_Y * 2 + rows * AppTileView.TOTAL_H + (rows - 1) * GAP_ROW;
    }

    private void toast(int messageRes) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
    }
}
