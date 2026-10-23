package io.github.koffelov.ebloclient;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** "Видосы": videos suggested for Bratishkin's streams, by category. Videos open in YouTube. */
final class VideosController {
    private static final int PER_PAGE = 12;

    private final Activity activity;
    private final FrameLayout container;
    private final PullRefreshLayout pull;
    private final ListView list;
    private final StateView state;
    private final LinearLayout chips;
    private final TextView footer;
    private final List<Models.Video> videos = new ArrayList<>();
    private final List<Models.Category> categories = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private long category = -1;
    private int page = 1;
    private boolean hasMore = true;
    private boolean loading;
    private boolean loaded;
    private int generation;

    VideosController(Activity activity) {
        this.activity = activity;
        container = new FrameLayout(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setBackgroundColor(Ui.BG);

        LinearLayout head = Ui.column(activity);
        head.setPadding(0, Ui.dp(8), 0, Ui.dp(6));
        LinearLayout titleRow = Ui.row(activity);
        titleRow.setPadding(Ui.dp(16), 0, Ui.dp(12), 0);
        LinearLayout titles = Ui.column(activity);
        titles.addView(Ui.text(activity, "Видосы", 26, Ui.INK));
        titles.addView(Ui.text(activity, "Предложка видео на стримы Братишкина", 15, Ui.INK3));
        titleRow.addView(titles, Ui.lpWeight());
        TextView suggest = Ui.button(activity, "Предложить", true);
        suggest.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0);
        suggest.setCompoundDrawablePadding(Ui.dp(4));
        suggest.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(0xFF111111));
        suggest.setContentDescription("Предложить видео");
        suggest.setOnClickListener(v -> SuggestVideoActivity.start(activity));
        titleRow.addView(suggest);
        head.addView(titleRow);
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        chips = Ui.row(activity);
        chips.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(4));
        scroll.addView(chips);
        head.addView(scroll);
        list.addHeaderView(head, null, false);
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
                if (!loading && hasMore && !videos.isEmpty() && first + visible >= total - 2) loadMore();
            }
        });
        pull = new PullRefreshLayout(activity);
        pull.setContent(list);
        pull.setOnRefresh(() -> reload(false));
        container.addView(pull, new FrameLayout.LayoutParams(-1, -1));
        state = new StateView(activity);
        state.hide();
        container.addView(state, new FrameLayout.LayoutParams(-1, -1));
        renderChips();
    }

    View view() {
        return container;
    }

    void ensureLoaded() {
        if (!loaded) reload(true);
    }

    void reload(boolean spinner) {
        loaded = true;
        generation++;
        page = 1;
        hasMore = true;
        if (spinner && videos.isEmpty()) state.loading();
        if (categories.isEmpty()) {
            Http.run(Api::videoCategories, (loadedCategories, error) -> {
                if (activity.isDestroyed() || loadedCategories == null) return;
                categories.clear();
                categories.addAll(loadedCategories);
                renderChips();
            });
        }
        load(true);
    }

    private void loadMore() {
        if (loading || !hasMore) return;
        load(false);
    }

    private void load(boolean fresh) {
        loading = true;
        final int gen = generation;
        final int pageNo = page;
        final long cat = category;
        footer.setText("Загрузка…");
        Http.run(() -> Api.videos(cat, pageNo, PER_PAGE), (result, error) -> {
            if (gen != generation || activity.isDestroyed()) return;
            loading = false;
            pull.setRefreshing(false);
            if (error != null) {
                if (fresh && videos.isEmpty()) {
                    state.error(Ui.errorText(activity, error), () -> reload(true),
                            () -> WebActivity.open(activity, Http.BASE + "/videos", "Видосы"));
                } else {
                    footer.setText(Ui.errorText(activity, error) + "\nНажмите, чтобы повторить");
                }
                return;
            }
            if (fresh) videos.clear();
            videos.addAll(result.items);
            page = pageNo + 1;
            hasMore = result.hasMore && !result.items.isEmpty();
            adapter.notifyDataSetChanged();
            footer.setText(videos.isEmpty() ? "В этом разделе пока нет видео" : "");
            state.hide();
        });
    }

    private void renderChips() {
        chips.removeAllViews();
        addChip("Рекомендуем", -1);
        addChip("Все", 0);
        for (Models.Category c : categories) addChip(c.title, c.id);
    }

    private void addChip(String title, long id) {
        TextView chip = Ui.chip(activity, title, id == category);
        chip.setOnClickListener(v -> {
            if (category == id) return;
            category = id;
            renderChips();
            videos.clear();
            adapter.notifyDataSetChanged();
            reload(true);
        });
        chips.addView(chip, Ui.margins(Ui.lp(-2, -2), 0, 0, 8, 0));
    }

    private void vote(Models.Video v) {
        if (!Session.loggedIn()) {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("Нужен вход")
                    .setMessage("Войдите через Twitch, чтобы голосовать за видео")
                    .setPositiveButton("Войти", (d, w) -> WebActivity.login(activity))
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        final boolean remove = v.voted;
        v.voted = !remove;
        v.likes += remove ? -1 : 1;
        adapter.notifyDataSetChanged();
        Http.run(() -> Api.voteVideo(v.id, remove), (updated, error) -> {
            if (activity.isDestroyed()) return;
            if (error != null) {
                v.voted = remove;
                v.likes += remove ? 1 : -1;
                Ui.toast(activity, Ui.errorText(activity, error));
            } else {
                v.voted = updated.voted;
                v.likes = updated.likes;
            }
            adapter.notifyDataSetChanged();
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return videos.size();
        }

        @Override
        public Object getItem(int position) {
            return videos.get(position);
        }

        @Override
        public long getItemId(int position) {
            return videos.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout outer;
            if (convertView instanceof FrameLayout) {
                outer = (FrameLayout) convertView;
            } else {
                outer = new FrameLayout(activity);
                outer.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
                LinearLayout card = Ui.column(activity);
                card.setBackground(Ui.ripple(Ui.round(Ui.SURFACE, 12, Ui.LINE_STRONG)));
                Ui.clipRound(card, 12);
                RatioFrame frame = new RatioFrame(activity, 0.5625f);
                frame.setBackgroundColor(Color.BLACK);
                ImageView thumb = new ImageView(activity);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                frame.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
                TextView duration = Ui.text(activity, 15, Color.WHITE);
                duration.setBackground(Ui.round(0xCC000000, 6, 0));
                duration.setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(6), Ui.dp(2));
                FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
                dl.setMargins(0, 0, Ui.dp(8), Ui.dp(8));
                frame.addView(duration, dl);
                card.addView(frame);
                LinearLayout body = Ui.column(activity);
                body.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(12));
                TextView title = Ui.text(activity, 20, Ui.INK);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                body.addView(title);
                LinearLayout metaRow = Ui.row(activity);
                TextView meta = Ui.text(activity, 15, Ui.INK3);
                metaRow.addView(meta, Ui.lpWeight());
                TextView like = Ui.text(activity, 17, Ui.INK2);
                like.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_heart, 0, 0, 0);
                like.setCompoundDrawablePadding(Ui.dp(6));
                like.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
                metaRow.addView(like);
                body.addView(metaRow, Ui.margins(Ui.lp(-1, -2), 0, 4, 0, 0));
                card.addView(body);
                outer.addView(card);
            }
            Models.Video v = videos.get(position);
            LinearLayout card = (LinearLayout) outer.getChildAt(0);
            RatioFrame frame = (RatioFrame) card.getChildAt(0);
            Images.load((ImageView) frame.getChildAt(0), v.thumb, activity.getResources().getDisplayMetrics().widthPixels);
            TextView duration = (TextView) frame.getChildAt(1);
            duration.setText(Ui.duration(v.duration));
            duration.setVisibility(v.duration > 0 ? View.VISIBLE : View.GONE);
            LinearLayout body = (LinearLayout) card.getChildAt(1);
            ((TextView) body.getChildAt(0)).setText(v.title);
            StringBuilder meta = new StringBuilder();
            if (!v.channel.isEmpty()) meta.append(v.channel);
            if (!v.authorName.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append("предложил ").append(v.authorName);
            LinearLayout metaRow = (LinearLayout) body.getChildAt(1);
            ((TextView) metaRow.getChildAt(0)).setText(meta);
            TextView like = (TextView) metaRow.getChildAt(1);
            like.setText(Ui.count(v.likes));
            like.setTextColor(v.voted ? Ui.DANGER : Ui.INK2);
            like.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(v.voted ? Ui.DANGER : Ui.INK3));
            like.setBackground(Ui.ripple(Ui.round(v.voted ? 0x26EF9282 : Ui.SURFACE_RAISED, 16, 0)));
            like.setContentDescription(v.voted ? "Убрать голос" : "Голосовать за видео");
            like.setOnClickListener(x -> vote(v));
            card.setOnClickListener(x -> {
                if (v.url != null && !v.url.isEmpty()) Router.openExternal(activity, Uri.parse(v.url));
            });
            return outer;
        }
    }
}
