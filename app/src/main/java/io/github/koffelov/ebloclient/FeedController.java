package io.github.koffelov.ebloclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The feed: "Лента / Подписки", filters, pull-to-refresh and endless scrolling. */
final class FeedController {
    private static final int LIMIT = 15;
    private static final String[][] SORTS = {{"best", "Лучшее"}, {"new", "Новое"},
            {"popular", "По просмотрам"}, {"top_liked", "По оценкам"}, {"top_commented", "По комментариям"}};
    private static final String[][] TIMES = {{"today", "Сегодня"}, {"week", "Неделя"},
            {"month", "Месяц"}, {"year", "Год"}, {"all", "Всё время"}};
    private static final String[][] TYPES = {{"all", "Всё"}, {"image", "Картинки"},
            {"video", "Видео"}, {"audio", "Аудио"}, {"albums", "Альбомы"}};

    private final Activity activity;
    private final Api.FeedQuery query;
    private final FrameLayout container;
    private final PullRefreshLayout pull;
    private final ListView list;
    private final StateView state;
    private final TextView footer;
    private final List<Models.Post> posts = new ArrayList<>();
    private final Set<String> codes = new HashSet<>();
    private final Adapter adapter = new Adapter();
    private TextView tabAll;
    private TextView tabFollowing;
    private View indicatorAll;
    private View indicatorFollowing;
    private TextView filterLabel;
    private int offset;
    private String cursor;
    private boolean hasMore = true;
    private boolean loading;
    private int generation;

    FeedController(Activity activity, Api.FeedQuery query, boolean scopeTabs) {
        this.activity = activity;
        this.query = query;
        container = new FrameLayout(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(4), 0, Ui.dp(12));
        list.setBackgroundColor(Ui.BG);
        list.addHeaderView(header(scopeTabs), null, false);
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
                if (!loading && hasMore && !posts.isEmpty() && first + visible >= total - 3) loadMore();
            }
        });
        pull = new PullRefreshLayout(activity);
        pull.setContent(list);
        pull.setOnRefresh(() -> reload(false));
        container.addView(pull, new FrameLayout.LayoutParams(-1, -1));
        state = new StateView(activity);
        container.addView(state, new FrameLayout.LayoutParams(-1, -1));
        state.hide();
    }

    View view() {
        return container;
    }

    void scrollToTop() {
        if (list.getFirstVisiblePosition() > 8) list.setSelection(0);
        else list.smoothScrollToPosition(0);
    }

    void showFollowing(boolean following) {
        String scope = following ? "following" : "public";
        if (scope.equals(query.scope)) return;
        query.scope = scope;
        if (following) {
            query.sort = "new";
            query.time = "all";
        } else {
            query.sort = "best";
            query.time = "today";
        }
        updateHeader();
        reload(true);
    }

    void onSessionChanged() {
        if ("following".equals(query.scope)) reload(true);
    }

    void reload(boolean spinner) {
        generation++;
        loading = false;
        offset = 0;
        cursor = null;
        hasMore = true;
        if ("following".equals(query.scope) && !Session.loggedIn()) {
            pull.setRefreshing(false);
            posts.clear();
            codes.clear();
            adapter.notifyDataSetChanged();
            footer.setText("");
            state.loginPrompt("Подписки", "Войдите, чтобы видеть посты тех, на кого вы подписаны.",
                    () -> WebActivity.login(activity));
            stateBelowHeader();
            return;
        }
        if (spinner && posts.isEmpty()) state.loading();
        load(true);
    }

    private void stateBelowHeader() {
        // The login prompt sits below the scope tabs so they stay usable.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) state.getLayoutParams();
        lp.topMargin = list.getChildCount() > 0 ? list.getChildAt(0).getBottom() : Ui.dp(110);
        state.setLayoutParams(lp);
    }

    private void loadMore() {
        if (loading || !hasMore) return;
        load(false);
    }

    private void load(boolean fresh) {
        loading = true;
        final int gen = generation;
        footer.setText("Загрузка…");
        final int fromOffset = offset;
        final String fromCursor = cursor;
        Http.run(() -> Api.feed(query, fromOffset, fromCursor, LIMIT), (page, error) -> {
            if (gen != generation || activity.isDestroyed()) return;
            loading = false;
            pull.setRefreshing(false);
            if (error != null) {
                if (fresh && posts.isEmpty()) {
                    state.error(Ui.errorText(activity, error), () -> reload(true),
                            () -> WebActivity.open(activity, siteUrl(), null));
                    resetStateMargin();
                } else {
                    footer.setText(Ui.errorText(activity, error) + "\nНажмите, чтобы повторить");
                }
                return;
            }
            if (fresh) {
                posts.clear();
                codes.clear();
            }
            for (Models.Post p : page.items) {
                if (codes.add(p.code)) posts.add(p);
            }
            offset = page.nextOffset;
            cursor = page.cursor;
            hasMore = page.hasMore && !page.items.isEmpty();
            adapter.notifyDataSetChanged();
            if (fresh) list.setSelection(0);
            footer.setText(hasMore ? "" : posts.isEmpty() ? "" : "Больше постов нет");
            if (posts.isEmpty()) {
                state.show(R.drawable.ic_album, "Здесь пока пусто",
                        "following".equals(query.scope) ? "Подпишитесь на авторов, и их посты появятся здесь."
                                : "Попробуйте другой период или фильтр.",
                        null, null, null, null);
                stateBelowHeader();
            } else {
                state.hide();
            }
        });
    }

    private void resetStateMargin() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) state.getLayoutParams();
        lp.topMargin = 0;
        state.setLayoutParams(lp);
    }

    private String siteUrl() {
        if (query.tag != null) return Http.BASE + "/?tag=" + android.net.Uri.encode(query.tag);
        if (query.query != null) return Http.BASE + "/search?q=" + android.net.Uri.encode(query.query);
        return Http.BASE + "/";
    }

    // ---- header: scope tabs + filters ----

    private View header(boolean scopeTabs) {
        LinearLayout h = Ui.column(activity);
        h.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(6));
        if (scopeTabs) {
            LinearLayout tabs = Ui.row(activity);
            LinearLayout all = tab("Лента", true);
            LinearLayout following = tab("Подписки", false);
            tabAll = (TextView) all.getChildAt(0);
            indicatorAll = all.getChildAt(1);
            tabFollowing = (TextView) following.getChildAt(0);
            indicatorFollowing = following.getChildAt(1);
            all.setOnClickListener(v -> showFollowing(false));
            following.setOnClickListener(v -> showFollowing(true));
            tabs.addView(all, Ui.lpWeight());
            tabs.addView(following, Ui.lpWeight());
            h.addView(tabs);
        }
        filterLabel = Ui.text(activity, 17, Ui.INK);
        filterLabel.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        filterLabel.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_filter, 0, 0, 0);
        filterLabel.setCompoundDrawablePadding(Ui.dp(10));
        filterLabel.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.INK2));
        filterLabel.setBackground(Ui.ripple(Ui.round(Ui.SURFACE, 10, Ui.LINE_STRONG)));
        filterLabel.setOnClickListener(v -> showFilters());
        h.addView(filterLabel, Ui.margins(Ui.lp(-1, -2), 0, 10, 0, 4));
        updateHeader();
        return h;
    }

    private LinearLayout tab(String label, boolean active) {
        LinearLayout l = Ui.column(activity);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setBackground(Ui.ripple(null));
        TextView t = Ui.text(activity, label, 22, active ? Ui.INK : Ui.INK3);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(12), 0, Ui.dp(10));
        l.addView(t, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(activity);
        line.setBackgroundColor(active ? Ui.BRAND_LIGHT : Ui.LINE);
        l.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(2)));
        return l;
    }

    private void updateHeader() {
        boolean following = "following".equals(query.scope);
        if (tabAll != null) {
            tabAll.setTextColor(following ? Ui.INK3 : Ui.INK);
            tabFollowing.setTextColor(following ? Ui.INK : Ui.INK3);
            indicatorAll.setBackgroundColor(following ? Ui.LINE : Ui.BRAND_LIGHT);
            indicatorFollowing.setBackgroundColor(following ? Ui.BRAND_LIGHT : Ui.LINE);
        }
        String text = label(SORTS, query.sort);
        if (!"new".equals(query.sort)) text += " · " + label(TIMES, query.time);
        text += " · " + label(TYPES, query.type);
        filterLabel.setText(text);
    }

    private static String label(String[][] options, String value) {
        for (String[] o : options) if (o[0].equals(value)) return o[1];
        return "relevance".equals(value) ? "По релевантности" : value;
    }

    private void showFilters() {
        final String[] sort = {query.sort};
        final String[] time = {query.time};
        final String[] type = {query.type};
        LinearLayout box = Ui.column(activity);
        box.setPadding(Ui.dp(20), Ui.dp(16), Ui.dp(20), Ui.dp(8));
        box.setBackgroundColor(Ui.SURFACE);
        final LinearLayout timeSection = section(box, "Период", TIMES, time, null);
        section(box, "Сортировка", SORTS, sort,
                () -> timeSection.setVisibility("new".equals(sort[0]) ? View.GONE : View.VISIBLE));
        box.removeView(timeSection);
        box.addView(timeSection);
        timeSection.setVisibility("new".equals(sort[0]) ? View.GONE : View.VISIBLE);
        section(box, "Тип публикаций", TYPES, type, null);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(box)
                .setPositiveButton("Показать", (d, w) -> {
                    query.sort = sort[0];
                    query.time = time[0];
                    query.type = type[0];
                    updateHeader();
                    reload(true);
                })
                .setNegativeButton("Отмена", null)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(Ui.round(Ui.SURFACE, 16, 0));
    }

    private LinearLayout section(LinearLayout box, String title, String[][] options, String[] selected,
                                 Runnable onChange) {
        LinearLayout section = Ui.column(activity);
        TextView t = Ui.text(activity, title, 18, Ui.INK2);
        section.addView(t, Ui.margins(Ui.lp(-2, -2), 0, 8, 0, 8));
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(activity);
        scroll.addView(chips);
        List<TextView> views = new ArrayList<>();
        for (String[] o : options) {
            TextView chip = Ui.chip(activity, o[1], o[0].equals(selected[0]));
            chip.setOnClickListener(v -> {
                selected[0] = o[0];
                for (int i = 0; i < views.size(); i++) {
                    boolean on = options[i][0].equals(selected[0]);
                    views.get(i).setTextColor(on ? 0xFF111111 : Ui.INK);
                    views.get(i).setBackground(Ui.ripple(on ? Ui.round(Ui.BRAND_LIGHT, 18, 0)
                            : Ui.round(Ui.SURFACE, 18, Ui.LINE_STRONG)));
                }
                if (onChange != null) onChange.run();
            });
            views.add(chip);
            chips.addView(chip, Ui.margins(Ui.lp(-2, -2), 0, 0, 8, 0));
        }
        section.addView(scroll);
        box.addView(section, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, 12));
        return section;
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return posts.size();
        }

        @Override
        public Object getItem(int position) {
            return posts.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = PostCards.card(activity, convertView);
            PostCards.bind(activity, v, posts.get(position));
            return v;
        }
    }
}
