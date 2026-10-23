package io.github.koffelov.ebloclient;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Full-area loading / error / empty state with a retry and an "open on the site" fallback. */
final class StateView extends FrameLayout {
    private final ProgressBar spinner;
    private final LinearLayout message;
    private final android.widget.ImageView icon;
    private final TextView title;
    private final TextView detail;
    private final TextView primary;
    private final TextView secondary;

    StateView(Context c) {
        super(c);
        setBackgroundColor(Ui.BG);
        spinner = new ProgressBar(c);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BRAND_LIGHT));
        addView(spinner, new LayoutParams(Ui.dp(40), Ui.dp(40), Gravity.CENTER));

        message = Ui.column(c);
        message.setGravity(Gravity.CENTER_HORIZONTAL);
        message.setPadding(Ui.dp(32), Ui.dp(24), Ui.dp(32), Ui.dp(24));
        icon = Ui.icon(c, R.drawable.ic_wifi_off, 48, Ui.INK3);
        message.addView(icon);
        title = Ui.text(c, 22, Ui.INK);
        title.setGravity(Gravity.CENTER);
        message.addView(title, Ui.margins(Ui.lp(-2, -2), 0, 14, 0, 0));
        detail = Ui.text(c, 17, Ui.INK2);
        detail.setGravity(Gravity.CENTER);
        message.addView(detail, Ui.margins(Ui.lp(-2, -2), 0, 8, 0, 0));
        primary = Ui.button(c, "Повторить", true);
        message.addView(primary, Ui.margins(Ui.lp(Ui.dp(200), -2), 0, 22, 0, 0));
        secondary = Ui.button(c, "Открыть на сайте", false);
        message.addView(secondary, Ui.margins(Ui.lp(Ui.dp(200), -2), 0, 10, 0, 0));
        addView(message, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setClickable(true);
    }

    void loading() {
        setVisibility(VISIBLE);
        spinner.setVisibility(VISIBLE);
        message.setVisibility(GONE);
    }

    void hide() {
        setVisibility(GONE);
    }

    void error(String text, Runnable retry, Runnable openSite) {
        show(R.drawable.ic_wifi_off, text, null, "Повторить", retry, openSite == null ? null : "Открыть на сайте", openSite);
    }

    void show(int iconRes, String titleText, String detailText, String primaryLabel, Runnable primaryAction,
              String secondaryLabel, Runnable secondaryAction) {
        setVisibility(VISIBLE);
        spinner.setVisibility(GONE);
        message.setVisibility(VISIBLE);
        icon.setVisibility(iconRes == 0 ? GONE : VISIBLE);
        if (iconRes != 0) icon.setImageResource(iconRes);
        title.setText(titleText);
        detail.setVisibility(detailText == null ? GONE : VISIBLE);
        detail.setText(detailText);
        bind(primary, primaryLabel, primaryAction);
        bind(secondary, secondaryLabel, secondaryAction);
    }

    /** Swaps the primary button for the Twitch-styled login button. */
    void loginPrompt(String titleText, String detailText, Runnable login) {
        show(R.drawable.ic_person, titleText, detailText, null, null, null, null);
        TextView twitch = Ui.twitchButton(getContext(), "Войти через Twitch");
        twitch.setOnClickListener(v -> login.run());
        twitch.setTag("login");
        View old = message.findViewWithTag("login");
        if (old != null) message.removeView(old);
        message.addView(twitch, Ui.margins(Ui.lp(Ui.dp(240), -2), 0, 22, 0, 0));
    }

    private void bind(TextView button, String label, Runnable action) {
        View login = message.findViewWithTag("login");
        if (login != null && button == primary) message.removeView(login);
        if (label == null || action == null) {
            button.setVisibility(GONE);
            return;
        }
        button.setVisibility(VISIBLE);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
    }
}
