package com.edition.home;

import android.graphics.drawable.Drawable;
import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.List;

/**
 * Tela inicial: dock com até cinco aplicativos, alça para abrir todos os aplicativos
 * e o botão de status no canto superior direito.
 */
public class HomeActivity extends Activity {

    /** Posições X dos cinco slots da dock (desenho 1280x720). */
    private static final float[] SLOT_X = {168f, 360f, 552f, 744f, 936f};
    private static final float SLOT_Y = 454f;
    private static final float DOCK_X = 144f;
    private static final float DOCK_Y = 430f;
    private static final float DOCK_W = 992f;
    private static final float DOCK_H = 170f;
    private static final float HANDLE_W = 200f;
    private static final float HANDLE_H = 10f;
    private static final float HANDLE_Y = Design.SAFE_BOTTOM - HANDLE_H;

    private AppRepository repository;
    private FrameLayout root;
    private AppTileView[] slots;
    private StatusBar statusBar;
    private Screensaver screensaver;
    private AppMenu menu;
    private int moveIndex = -1;
    private String renderedLook;
    private long wallpaperStamp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.init(this);
        renderedLook = Prefs.lookKey(this);
        repository = new AppRepository(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Design.BACKGROUND);
        setContentView(root);
        Wallpaper.apply(this, root);
        wallpaperStamp = Wallpaper.stamp(this);
        Permissions.requestAll(this);

        View dock = new View(this);
        GradientDrawable dockBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x3AFFFFFF, 0x18FFFFFF});
        dockBg.setCornerRadius(Design.px(28));
        dockBg.setStroke(Math.max(1, Design.px(2)), Design.HOME_GLASS);
        dock.setBackground(dockBg);
        Design.place(root, dock, DOCK_X, DOCK_Y, DOCK_W, DOCK_H);

        slots = new AppTileView[SLOT_X.length];
        for (int i = 0; i < SLOT_X.length; i++) {
            final int index = i;
            AppTileView tile = new AppTileView(this, 24, true);
            tile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onSlotClick(index);
                }
            });
            tile.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return onSlotLongClick(index);
                }
            });
            Design.place(root, tile, SLOT_X[i], SLOT_Y, AppTileView.W, AppTileView.TOTAL_H);
            slots[i] = tile;
        }

        View handle = new View(this);
        final GradientDrawable handleNormal = Design.rect(0x99FFFFFF, 5);
        final Drawable handleFocus = Design.focusRing(0xFFFFFFFF, 5);
        handle.setBackground(handleNormal);
        handle.setFocusable(true);
        handle.setClickable(true);
        handle.setContentDescription(getString(R.string.open_drawer));
        handle.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(hasFocus ? handleFocus : handleNormal);
            }
        });
        handle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDrawer(false);
            }
        });
        Design.place(root, handle, (Design.WIDTH - HANDLE_W) / 2f, HANDLE_Y, HANDLE_W, HANDLE_H);

        statusBar = new StatusBar(this, root);
        screensaver = new Screensaver(this, root);
        menu = new AppMenu(this, root, repository, new AppMenu.Listener() {
            @Override
            public void onMoveRequested(int slot) {
                enterMoveMode(slot);
            }

            @Override
            public void onChanged() {
                refreshDock();
            }
        });

        slots[0].post(new Runnable() {
            @Override
            public void run() {
                slots[0].requestFocus();
            }
        });
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
        // Cor ou modo mudou nas Configurações: recria a tela com as novas cores.
        if (!Prefs.lookKey(this).equals(renderedLook)) {
            recreate();
            return;
        }
        refreshDock();
        long stamp = Wallpaper.stamp(this);
        if (stamp != wallpaperStamp || Wallpaper.needsRefresh(this)) {
            wallpaperStamp = stamp;
            Wallpaper.apply(this, root);
        }
        screensaver.schedule();
    }

    @Override
    protected void onPause() {
        exitMoveMode();
        screensaver.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (screensaver != null) {
            screensaver.release();
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            screensaver.userActivity();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            screensaver.userActivity();
        }
        if (moveIndex < 0 || event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveBy(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveBy(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                exitMoveMode();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (Wallpaper.handleResult(this, requestCode, resultCode, data)) {
            wallpaperStamp = Wallpaper.stamp(this);
            Wallpaper.apply(this, root);
        }
    }

    @Override
    public void onBackPressed() {
        if (moveIndex >= 0) {
            exitMoveMode();
            return;
        }
        if (menu.isShowing()) {
            menu.hide();
        }
        // Tela inicial: não sai do launcher.
    }

    private void refreshDock() {
        List<AppInfo> favorites = repository.getFavoriteApps();
        for (int i = 0; i < slots.length; i++) {
            if (i < favorites.size()) {
                AppInfo app = favorites.get(i);
                slots[i].bindApp(app, repository.loadBanner(app.packageName), repository.loadIcon(app.packageName));
            } else if (i == favorites.size() && favorites.size() < AppRepository.MAX_FAVORITES) {
                slots[i].bindEmpty();
            } else {
                slots[i].bindHidden();
            }
        }
    }

    private void onSlotClick(int index) {
        if (moveIndex >= 0) {
            exitMoveMode();
            return;
        }
        List<AppInfo> favorites = repository.getFavoriteApps();
        if (index < favorites.size()) {
            if (!repository.launch(favorites.get(index).packageName)) {
                toast(R.string.toast_cannot_open);
            }
        } else {
            openDrawer(true);
        }
    }

    private boolean onSlotLongClick(int index) {
        List<AppInfo> favorites = repository.getFavoriteApps();
        if (index >= favorites.size() || moveIndex >= 0) {
            return true;
        }
        menu.showForDock(favorites.get(index), index, slots[index]);
        return true;
    }

    private void enterMoveMode(int slot) {
        moveIndex = slot;
        slots[slot].requestFocus();
        toast(R.string.toast_move_hint);
    }

    private void moveBy(int delta) {
        int target = moveIndex + delta;
        int count = repository.getFavoriteApps().size();
        if (target < 0 || target >= count) {
            return;
        }
        repository.swapFavorites(moveIndex, target);
        moveIndex = target;
        refreshDock();
        slots[moveIndex].requestFocus();
    }

    private void exitMoveMode() {
        if (moveIndex >= 0) {
            moveIndex = -1;
        }
    }

    private void openDrawer(boolean pickForDock) {
        Intent intent = new Intent(this, AppDrawerActivity.class);
        intent.putExtra(AppDrawerActivity.EXTRA_PICK_FOR_DOCK, pickForDock);
        startActivity(intent);
        Prefs.applyOpenAnimation(this);
    }

    private void toast(int messageRes) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
    }
}
