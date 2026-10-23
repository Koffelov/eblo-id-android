package io.github.koffelov.ebloclient;

import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** Edge-to-edge window with insets as padding, a top bar, and Back handling for all versions. */
abstract class BaseActivity extends Activity {
    protected FrameLayout root;
    protected LinearLayout page;
    protected TextView topTitle;
    protected LinearLayout topActions;
    protected boolean fullBleed;
    private Object backCallback;
    private boolean backRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        page = Ui.column(this);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Edge-to-edge is enforced on Android 15+, so bars and keyboard are applied as padding.
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                if (fullBleed) {
                    v.setPadding(0, 0, 0, 0);
                } else {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                }
                return WindowInsets.CONSUMED;
            });
        }
    }

    /** Top bar with an optional back arrow; returns it so screens can add actions. */
    protected LinearLayout addTopBar(String title, boolean back) {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
        bar.setMinimumHeight(Ui.dp(56));
        bar.setBackgroundColor(Ui.BG);
        if (back) {
            ImageView b = Ui.iconButton(this, R.drawable.ic_back, Ui.INK, "Назад");
            b.setOnClickListener(v -> onBackRequested());
            bar.addView(b);
        }
        topTitle = Ui.text(this, title, 24, Ui.INK);
        topTitle.setSingleLine(true);
        topTitle.setEllipsize(TextUtils.TruncateAt.END);
        topTitle.setPadding(Ui.dp(back ? 4 : 12), 0, Ui.dp(8), 0);
        topTitle.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(topTitle, new LinearLayout.LayoutParams(0, Ui.dp(56), 1f));
        topActions = Ui.row(this);
        bar.addView(topActions);
        page.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        page.addView(Ui.divider(this));
        return bar;
    }

    protected ImageView addAction(int icon, String description, View.OnClickListener click) {
        ImageView b = Ui.iconButton(this, icon, Ui.INK, description);
        b.setOnClickListener(click);
        topActions.addView(b);
        return b;
    }

    // ---- Back ----

    /** Screens return true when they handled Back themselves (closing a viewer, going back in a page). */
    protected boolean handleBack() {
        return false;
    }

    /** Whether Back should currently be intercepted (Android 13+ predictive back). */
    protected boolean wantsBack() {
        return false;
    }

    protected void onBackRequested() {
        if (!handleBack()) finish();
    }

    protected void updateBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        boolean needed = wantsBack();
        if (needed == backRegistered) return;
        updateBackCallbackApi33(needed);
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private void updateBackCallbackApi33(boolean needed) {
        if (backCallback == null) {
            backCallback = (OnBackInvokedCallback) () -> {
                if (!handleBack()) finish();
                updateBackCallback();
            };
        }
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        if (needed) {
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) backCallback);
        } else {
            d.unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
        }
        backRegistered = needed;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!handleBack()) super.onBackPressed();
    }

    protected boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    protected void setFullBleed(boolean value) {
        fullBleed = value;
        root.requestApplyInsets();
    }
}
