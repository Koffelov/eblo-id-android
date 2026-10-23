package io.github.koffelov.ebloclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** A user's profile: header (from the profile page) and a grid of their posts. */
final class ProfileController {
    private static final int LIMIT = 21;

    private final Activity activity;
    private final String login;
    private final FrameLayout container;
    private final PullRefreshLayout pull;
    private final ListView list;
    private final StateView state;
    private final LinearLayout header;
    private final TextView footer;
    private final List<Models.Post> posts = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private Models.Profile profile;
    private int page = 1;
    private boolean hasMore = true;
    private boolean loading;
    private int generation;
    private Runnable onLoggedOut;

    ProfileController(Activity activity, String login) {
        this.activity = activity;
        this.login = login;
        container = new FrameLayout(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setBackgroundColor(Ui.BG);
        header = Ui.column(activity);
        list.addHeaderView(header, null, false);
        footer = Ui.text(activity, 17, Ui.INK3);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, Ui.dp(18), 0, Ui.dp(24));
        footer.setOnClickListener(v -> loadMore());
        list.addFooterView(footer, null, false);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (!loading && hasMore && !posts.isEmpty() && first + visible >= total - 2) loadMore();
            }
        });
        pull = new PullRefreshLayout(activity);
        pull.setContent(list);
        pull.setOnRefresh(() -> load(false));
        container.addView(pull, new FrameLayout.LayoutParams(-1, -1));
        state = new StateView(activity);
        container.addView(state, new FrameLayout.LayoutParams(-1, -1));
    }

    View view() {
        return container;
    }

    void setOnLoggedOut(Runnable r) {
        onLoggedOut = r;
    }

    void scrollToTop() {
        list.smoothScrollToPosition(0);
    }

    void load(boolean spinner) {
        final int gen = ++generation;
        loading = true;
        if (spinner) state.loading();
        Http.run(() -> Api.profile(login), (p, error) -> {
            if (gen != generation || activity.isDestroyed()) return;
            if (error != null) {
                loading = false;
                pull.setRefreshing(false);
                state.error(Ui.errorText(activity, error), () -> load(true),
                        () -> WebActivity.open(activity, Http.BASE + "/@" + login, null));
                return;
            }
            profile = p;
            buildHeader();
            state.hide();
            page = 1;
            hasMore = true;
            posts.clear();
            adapter.notifyDataSetChanged();
            loading = false;
            loadPosts(gen);
        });
    }

    private void loadMore() {
        if (loading || !hasMore) return;
        loadPosts(generation);
    }

    private void loadPosts(int gen) {
        loading = true;
        footer.setText("Загрузка…");
        final int pageNo = page;
        Http.run(() -> Api.uploads(login, pageNo, LIMIT), (result, error) -> {
            if (gen != generation || activity.isDestroyed()) return;
            loading = false;
            pull.setRefreshing(false);
            if (error != null) {
                footer.setText(Ui.errorText(activity, error) + "\nНажмите, чтобы повторить");
                return;
            }
            posts.addAll(result.items);
            page = pageNo + 1;
            hasMore = result.hasMore && !result.items.isEmpty();
            adapter.notifyDataSetChanged();
            footer.setText(posts.isEmpty() ? "Публикаций пока нет" : hasMore ? "" : "");
        });
    }

    private void buildHeader() {
        header.removeAllViews();
        LinearLayout top = Ui.row(activity);
        top.setPadding(Ui.dp(16), Ui.dp(18), Ui.dp(16), Ui.dp(8));
        ImageView avatar = Ui.avatar(activity, 88);
        Images.load(avatar, profile.avatar, Ui.dp(88));
        if (profile.avatar != null) {
            avatar.setOnClickListener(v -> {
                List<String> one = new ArrayList<>();
                one.add(profile.avatar);
                MediaActivity.images(activity, one, 0);
            });
        }
        top.addView(avatar);
        LinearLayout info = Ui.column(activity);
        info.setPadding(Ui.dp(16), 0, 0, 0);
        TextView name = Ui.text(activity, profile.name, 28, Ui.INK);
        info.addView(name);
        TextView handle = Ui.text(activity, "@" + profile.login, 17, Ui.INK3);
        info.addView(handle);
        if (!profile.badges.isEmpty() || profile.twitch != null || profile.telegram != null) {
            LinearLayout extras = Ui.row(activity);
            for (int i = 0; i < Math.min(5, profile.badges.size()); i++) {
                ImageView b = Ui.icon(activity, R.drawable.ic_person, 24, 0);
                b.setImageTintList(null);
                Images.load(b, profile.badges.get(i), Ui.dp(24));
                extras.addView(b, Ui.margins(Ui.lp(Ui.dp(24), Ui.dp(24)), 0, 0, 6, 0));
            }
            if (profile.twitch != null) extras.addView(link(R.drawable.ic_twitch, Ui.TWITCH, profile.twitch, "Twitch"));
            if (profile.telegram != null) extras.addView(link(R.drawable.ic_telegram, 0xFF2AABEE, profile.telegram, "Telegram"));
            info.addView(extras, Ui.margins(Ui.lp(-2, -2), 0, 6, 0, 0));
        }
        top.addView(info, Ui.lpWeight());
        header.addView(top);

        LinearLayout stats = Ui.row(activity);
        stats.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(10));
        if (profile.posts >= 0) stats.addView(stat(profile.posts, "публикаций"));
        if (profile.followers >= 0) stats.addView(stat(profile.followers, "подписчиков"));
        if (profile.following >= 0) stats.addView(stat(profile.following, "подписок"));
        header.addView(stats);

        LinearLayout buttons = Ui.row(activity);
        buttons.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(14));
        if (profile.owner) {
            TextView edit = Ui.button(activity, "Редактировать профиль", false);
            edit.setOnClickListener(v -> WebActivity.open(activity, Http.BASE + "/@" + login, "Мой профиль"));
            buttons.addView(edit, Ui.lpWeight());
            TextView logout = Ui.button(activity, "Выйти", false);
            logout.setTextColor(Ui.DANGER);
            logout.setOnClickListener(v -> confirmLogout());
            buttons.addView(logout, Ui.margins(Ui.lp(-2, -2), 10, 0, 0, 0));
        } else if (profile.canFollow) {
            TextView follow = Ui.button(activity, "", !profile.isFollowing);
            styleFollow(follow);
            follow.setOnClickListener(v -> toggleFollow(follow));
            buttons.addView(follow, Ui.lpWeight());
        }
        if (buttons.getChildCount() > 0) header.addView(buttons);
        header.addView(Ui.divider(activity));
        View gap = new View(activity);
        header.addView(gap, new LinearLayout.LayoutParams(-1, Ui.dp(2)));
    }

    private View stat(int value, String label) {
        LinearLayout l = Ui.column(activity);
        l.addView(Ui.text(activity, Ui.count(value), 22, Ui.INK));
        l.addView(Ui.text(activity, label, 15, Ui.INK3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        l.setLayoutParams(lp);
        return l;
    }

    private View link(int icon, int tint, String url, String description) {
        ImageView v = Ui.iconButton(activity, icon, tint, description);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)));
        v.setOnClickListener(x -> Router.openExternal(activity, Uri.parse(url)));
        return v;
    }

    private void styleFollow(TextView b) {
        b.setText(profile.isFollowing ? "Вы подписаны" : "Подписаться");
        b.setTextColor(profile.isFollowing ? Ui.INK : 0xFF111111);
        b.setBackground(Ui.ripple(profile.isFollowing ? Ui.round(Ui.SURFACE, 8, Ui.LINE_STRONG)
                : Ui.round(Ui.BRAND_LIGHT, 8, 0)));
    }

    private void toggleFollow(TextView b) {
        if (!Session.loggedIn()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Нужен вход")
                    .setMessage("Войдите, чтобы подписываться на авторов")
                    .setPositiveButton("Войти через Twitch", (d, w) -> WebActivity.login(activity))
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        final boolean target = !profile.isFollowing;
        b.setEnabled(false);
        Http.run(() -> Api.follow(login, target), (following, error) -> {
            if (activity.isDestroyed()) return;
            b.setEnabled(true);
            if (error != null) {
                Ui.toast(activity, Ui.errorText(activity, error));
                return;
            }
            profile.isFollowing = following;
            if (profile.followers >= 0) profile.followers += following ? 1 : -1;
            styleFollow(b);
        });
    }

    private void confirmLogout() {
        new AlertDialog.Builder(activity)
                .setTitle("Выйти из аккаунта?")
                .setPositiveButton("Выйти", (d, w) -> Http.run(() -> {
                    Api.logout();
                    return Boolean.TRUE;
                }, (ok, error) -> {
                    if (activity.isDestroyed()) return;
                    if (error != null) {
                        Ui.toast(activity, Ui.errorText(activity, error));
                        return;
                    }
                    Ui.toast(activity, "Вы вышли из аккаунта");
                    if (onLoggedOut != null) onLoggedOut.run();
                }))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return (posts.size() + 2) / 3;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = PostCards.gridRow(activity, convertView);
            PostCards.bindGridRow(activity, row, posts, position * 3);
            return row;
        }
    }
}
