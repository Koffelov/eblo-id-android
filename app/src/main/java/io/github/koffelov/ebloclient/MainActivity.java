package io.github.koffelov.ebloclient;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Home: top bar, five tabs like the site's mobile tab bar (Лента, Видосы, +, Уведомления,
 * Профиль), and the entry point for eblo.id links opened from other apps.
 */
public class MainActivity extends BaseActivity {
    static final String EXTRA_FOLLOWING = "following";
    private static final int TAB_FEED = 0;
    private static final int TAB_VIDEOS = 1;
    private static final int TAB_NEW = 2;
    private static final int TAB_NOTIFICATIONS = 3;
    private static final int TAB_ME = 4;
    private static final String PREFS = "app";
    private static final String PREF_NOTICE_SHOWN = "notice_shown_v1";

    private FeedController feed;
    private VideosController videos;
    private NotificationsController notifications;
    private FrameLayout meContainer;
    private StateView meState;
    private ProfileController me;
    private String meLogin;
    private final View[] pages = new View[5];
    private final LinearLayout[] tabs = new LinearLayout[5];
    private TextView unreadBadge;
    private int currentTab = TAB_FEED;
    private long lastSessionRefresh;
    private final Runnable sessionListener = this::onSessionChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildTopBar();

        FrameLayout content = new FrameLayout(this);
        feed = new FeedController(this, new Api.FeedQuery(), true);
        videos = new VideosController(this);
        notifications = new NotificationsController(this, this::setUnread);
        meContainer = new FrameLayout(this);
        meState = new StateView(this);
        meContainer.addView(meState, new FrameLayout.LayoutParams(-1, -1));
        pages[TAB_FEED] = feed.view();
        pages[TAB_VIDEOS] = videos.view();
        pages[TAB_NOTIFICATIONS] = notifications.view();
        pages[TAB_ME] = meContainer;
        for (View p : pages) {
            if (p == null) continue;
            p.setVisibility(View.GONE);
            content.addView(p, new FrameLayout.LayoutParams(-1, -1));
        }
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        buildBottomBar();
        selectTab(TAB_FEED);

        Session.addListener(sessionListener);
        refreshSession();
        feed.reload(true);
        handleIntent(getIntent());
        showNoticeOnce();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_FOLLOWING, false)) {
            selectTab(TAB_FEED);
            feed.showFollowing(true);
        }
        Uri data = intent.getData();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && data != null) {
            intent.setData(null);
            String path = data.getPath() == null ? "/" : data.getPath();
            if ("/".equals(path) && data.getQueryParameter("tag") == null) {
                selectTab(TAB_FEED);
                if ("following".equals(data.getQueryParameter("scope"))) feed.showFollowing(true);
            } else if ("/videos".equals(path)) {
                selectTab(TAB_VIDEOS);
            } else {
                Router.open(this, data);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Picks up logins and logouts done in a web screen (for example on the "new post" page).
        if (SystemClock.elapsedRealtime() - lastSessionRefresh > 30_000) refreshSession();
    }

    @Override
    protected void onDestroy() {
        Session.removeListener(sessionListener);
        super.onDestroy();
    }

    private void refreshSession() {
        lastSessionRefresh = SystemClock.elapsedRealtime();
        Session.refresh(() -> {
            if (!alive()) return;
            updateMeTab();
            if (currentTab == TAB_ME) showMe();
            pollUnread();
        });
    }

    private void onSessionChanged() {
        if (!alive()) return;
        updateMeTab();
        feed.onSessionChanged();
        if (currentTab == TAB_NOTIFICATIONS) notifications.load(true);
        if (currentTab == TAB_ME) showMe();
        if (!Session.loggedIn()) setUnread(0);
    }

    private void pollUnread() {
        if (!Session.loggedIn()) return;
        Http.run(Api::notifications, (n, error) -> {
            if (alive() && n != null && currentTab != TAB_NOTIFICATIONS) setUnread(n.unread);
        });
    }

    // ---- chrome ----

    private void buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bar.addView(logo, new LinearLayout.LayoutParams(Ui.dp(54), Ui.dp(54)));
        TextView title = Ui.text(this, "eblo.id", 30, Ui.ACCENT);
        bar.addView(title, Ui.lpWeight());
        logo.setOnClickListener(v -> selectTab(TAB_FEED));
        title.setOnClickListener(v -> selectTab(TAB_FEED));
        ImageView search = Ui.iconButton(this, R.drawable.ic_search, Ui.INK, "Поиск");
        search.setOnClickListener(v -> SearchActivity.start(this, null));
        bar.addView(search);
        page.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(56)));
    }

    private void buildBottomBar() {
        page.addView(Ui.divider(this));
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.BG);
        bar.setPadding(0, Ui.dp(4), 0, Ui.dp(4));
        String[] labels = {"Лента", "Видосы", "", "Уведомления", "Войти"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_play, R.drawable.ic_add, R.drawable.ic_bell, R.drawable.ic_person};
        for (int i = 0; i < 5; i++) {
            final int index = i;
            LinearLayout tab = Ui.column(this);
            tab.setGravity(Gravity.CENTER);
            tab.setBackground(Ui.ripple(null));
            tab.setContentDescription(i == TAB_NEW ? "Новый пост" : labels[i]);
            if (i == TAB_NEW) {
                ImageView plus = Ui.icon(this, icons[i], 30, 0xFF111111);
                plus.setScaleType(ImageView.ScaleType.CENTER);
                plus.setBackground(Ui.round(Ui.BRAND_LIGHT, 12, 0));
                tab.addView(plus, new LinearLayout.LayoutParams(Ui.dp(54), Ui.dp(42)));
            } else {
                FrameLayout iconBox = new FrameLayout(this);
                ImageView icon = Ui.icon(this, icons[i], 26, Ui.INK3);
                iconBox.addView(icon, new FrameLayout.LayoutParams(Ui.dp(26), Ui.dp(26), Gravity.CENTER));
                if (i == TAB_NOTIFICATIONS) {
                    unreadBadge = Ui.text(this, 12, 0xFF111111);
                    unreadBadge.setGravity(Gravity.CENTER);
                    unreadBadge.setMinWidth(Ui.dp(18));
                    unreadBadge.setPadding(Ui.dp(4), Ui.dp(1), Ui.dp(4), Ui.dp(1));
                    unreadBadge.setBackground(Ui.round(Ui.BRAND_LIGHT, 9, 0));
                    unreadBadge.setVisibility(View.GONE);
                    iconBox.addView(unreadBadge, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
                }
                tab.addView(iconBox, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(28)));
                TextView label = Ui.text(this, labels[i], 13, Ui.INK3);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                tab.addView(label);
            }
            tab.setOnClickListener(v -> selectTab(index));
            tabs[i] = tab;
            bar.addView(tab, new LinearLayout.LayoutParams(0, Ui.dp(56), 1f));
        }
        page.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    private void updateMeTab() {
        TextView label = (TextView) tabs[TAB_ME].getChildAt(1);
        label.setText(Session.loggedIn() ? "Профиль" : "Войти");
    }

    private void setUnread(int count) {
        unreadBadge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        unreadBadge.setText(count > 99 ? "99+" : String.valueOf(count));
    }

    private void selectTab(int index) {
        if (index == TAB_NEW) {
            WebActivity.newPost(this);
            return;
        }
        if (index == currentTab && pages[index].getVisibility() == View.VISIBLE) {
            if (index == TAB_FEED) feed.scrollToTop();
            if (index == TAB_ME && me != null) me.scrollToTop();
            return;
        }
        currentTab = index;
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] != null) pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            if (i == TAB_NEW || tabs[i] == null) continue;
            int color = i == index ? Ui.BRAND_LIGHT : Ui.INK3;
            FrameLayout iconBox = (FrameLayout) tabs[i].getChildAt(0);
            ((ImageView) iconBox.getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
            ((TextView) tabs[i].getChildAt(1)).setTextColor(color);
        }
        if (index == TAB_VIDEOS) videos.ensureLoaded();
        if (index == TAB_NOTIFICATIONS) {
            notifications.load(true);
            notifications.markSeen();
        }
        if (index == TAB_ME) showMe();
        updateBackCallback();
    }

    private void showMe() {
        if (!Session.known()) {
            meState.loading();
            return;
        }
        if (!Session.loggedIn()) {
            if (me != null) {
                meContainer.removeView(me.view());
                me = null;
                meLogin = null;
            }
            meState.loginPrompt("Добро пожаловать",
                    "Войдите через Twitch, чтобы публиковать посты, комментировать, ставить оценки и подписываться.",
                    () -> WebActivity.login(this));
            return;
        }
        meState.hide();
        String login = Session.login();
        if (me == null || !login.equals(meLogin)) {
            if (me != null) meContainer.removeView(me.view());
            me = new ProfileController(this, login);
            me.setOnLoggedOut(() -> {
                updateMeTab();
                showMe();
            });
            meLogin = login;
            meContainer.addView(me.view(), 0, new FrameLayout.LayoutParams(-1, -1));
            me.load(true);
        }
    }

    // ---- Back: other tabs return to the feed first ----

    @Override
    protected boolean wantsBack() {
        return currentTab != TAB_FEED;
    }

    @Override
    protected boolean handleBack() {
        if (currentTab != TAB_FEED) {
            selectTab(TAB_FEED);
            return true;
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WebActivity.REQUEST_LOGIN) onSessionChanged();
    }

    private void showNoticeOnce() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_NOTICE_SHOWN, false)) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.notice_title)
                .setMessage(R.string.notice_text)
                .setCancelable(false)
                .setPositiveButton(R.string.notice_ok, (dialog, which) ->
                        prefs.edit().putBoolean(PREF_NOTICE_SHOWN, true).apply())
                .show();
    }
}
