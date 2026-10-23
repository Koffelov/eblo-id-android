package io.github.koffelov.ebloclient;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/** Pull-to-refresh for a single scrolling child, without AndroidX. */
final class PullRefreshLayout extends FrameLayout {
    private final ProgressBar spinner;
    private final int touchSlop;
    private final int trigger = Ui.dp(72);
    private View target;
    private Runnable onRefresh;
    private boolean refreshing;
    private boolean dragging;
    private float startY;

    PullRefreshLayout(Context c) {
        super(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        spinner = new ProgressBar(c);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.BRAND_LIGHT));
        spinner.setBackground(Ui.round(Ui.SURFACE, 20, Ui.LINE_STRONG));
        int pad = Ui.dp(8);
        spinner.setPadding(pad, pad, pad, pad);
        spinner.setElevation(Ui.dp(4));
        spinner.setAlpha(0f);
        LayoutParams lp = new LayoutParams(Ui.dp(40), Ui.dp(40), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lp.topMargin = -Ui.dp(48);
        addView(spinner, lp);
    }

    void setContent(View content) {
        target = content;
        addView(content, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void setOnRefresh(Runnable r) {
        onRefresh = r;
    }

    void setRefreshing(boolean value) {
        refreshing = value;
        if (value) {
            spinner.animate().translationY(Ui.dp(64)).alpha(1f).setDuration(150).start();
        } else {
            spinner.animate().translationY(0).alpha(0f).setDuration(200).start();
        }
    }

    private boolean canPull() {
        return target != null && !refreshing && !target.canScrollVertically(-1) && isEnabled();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = ev.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (canPull() && ev.getY() - startY > touchSlop) {
                    dragging = true;
                    startY = ev.getY();
                }
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return super.onTouchEvent(ev);
        float pulled = Math.max(0, ev.getY() - startY) * 0.5f;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                spinner.setTranslationY(Math.min(pulled, trigger * 1.5f));
                spinner.setAlpha(Math.min(1f, pulled / trigger));
                spinner.setRotation(pulled * 2);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (pulled >= trigger && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    setRefreshing(true);
                    if (onRefresh != null) onRefresh.run();
                } else {
                    setRefreshing(false);
                }
                return true;
            default:
                return true;
        }
    }
}
