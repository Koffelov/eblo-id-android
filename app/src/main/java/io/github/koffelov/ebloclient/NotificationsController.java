package io.github.koffelov.ebloclient;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The notifications tab (login required on the site). */
final class NotificationsController {
    interface UnreadListener {
        void unread(int count);
    }

    private final Activity activity;
    private final FrameLayout container;
    private final PullRefreshLayout pull;
    private final ListView list;
    private final StateView state;
    private final List<Models.Notif> items = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private final UnreadListener unreadListener;
    private int generation;

    NotificationsController(Activity activity, UnreadListener unreadListener) {
        this.activity = activity;
        this.unreadListener = unreadListener;
        container = new FrameLayout(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setBackgroundColor(Ui.BG);
        LinearLayout head = Ui.row(activity);
        head.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(8), Ui.dp(6));
        head.addView(Ui.text(activity, "Уведомления", 26, Ui.INK), Ui.lpWeight());
        TextView clear = Ui.text(activity, "Очистить", 17, Ui.INK3);
        clear.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        clear.setBackground(Ui.ripple(null));
        clear.setOnClickListener(v -> clearAll());
        head.addView(clear);
        list.addHeaderView(head, null, false);
        list.setAdapter(adapter);
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

    void load(boolean spinner) {
        final int gen = ++generation;
        if (!Session.loggedIn()) {
            pull.setRefreshing(false);
            items.clear();
            adapter.notifyDataSetChanged();
            if (unreadListener != null) unreadListener.unread(0);
            state.loginPrompt("Уведомления", "Войдите, чтобы видеть лайки, комментарии и новых подписчиков.",
                    () -> WebActivity.login(activity));
            return;
        }
        if (spinner && items.isEmpty()) state.loading();
        Http.run(Api::notifications, (n, error) -> {
            if (gen != generation || activity.isDestroyed()) return;
            pull.setRefreshing(false);
            if (error != null) {
                if (items.isEmpty()) {
                    state.error(Ui.errorText(activity, error), () -> load(true), null);
                } else {
                    Ui.toast(activity, Ui.errorText(activity, error));
                }
                return;
            }
            items.clear();
            items.addAll(n.items);
            adapter.notifyDataSetChanged();
            if (unreadListener != null) unreadListener.unread(n.unread);
            if (items.isEmpty()) {
                state.show(R.drawable.ic_bell, "Уведомлений нет", "Здесь появятся лайки, комментарии и подписки.",
                        null, null, null, null);
            } else {
                state.hide();
            }
        });
    }

    /** Called when the tab is opened: the site marks notifications as seen the same way. */
    void markSeen() {
        if (!Session.loggedIn()) return;
        Http.run(() -> {
            Api.markNotificationsOpened();
            return Boolean.TRUE;
        }, (ok, error) -> {
            if (error == null && unreadListener != null) unreadListener.unread(0);
        });
    }

    private void clearAll() {
        if (!Session.loggedIn() || items.isEmpty()) return;
        Http.run(() -> {
            Api.clearNotifications();
            return Boolean.TRUE;
        }, (ok, error) -> {
            if (activity.isDestroyed()) return;
            if (error != null) {
                Ui.toast(activity, Ui.errorText(activity, error));
                return;
            }
            load(false);
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = Ui.row(activity);
                row.setGravity(Gravity.TOP);
                row.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
                row.setBackground(Ui.ripple(null));
                View dot = new View(activity);
                dot.setBackground(PostCards.dot(Ui.BRAND_LIGHT));
                row.addView(dot, Ui.margins(Ui.lp(Ui.dp(8), Ui.dp(8)), 0, 8, 12, 0));
                LinearLayout col = Ui.column(activity);
                col.addView(Ui.text(activity, 19, Ui.INK));
                TextView message = Ui.text(activity, 17, Ui.INK2);
                message.setMaxLines(4);
                col.addView(message, Ui.margins(Ui.lp(-1, -2), 0, 3, 0, 0));
                col.addView(Ui.text(activity, 14, Ui.INK3), Ui.margins(Ui.lp(-2, -2), 0, 4, 0, 0));
                row.addView(col, Ui.lpWeight());
            }
            Models.Notif n = items.get(position);
            row.getChildAt(0).setVisibility(n.read ? View.INVISIBLE : View.VISIBLE);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            ((TextView) col.getChildAt(0)).setText(n.title);
            col.getChildAt(0).setVisibility(n.title.isEmpty() ? View.GONE : View.VISIBLE);
            ((TextView) col.getChildAt(1)).setText(n.message);
            col.getChildAt(1).setVisibility(n.message.isEmpty() ? View.GONE : View.VISIBLE);
            ((TextView) col.getChildAt(2)).setText(Ui.time(n.created));
            row.setOnClickListener(v -> {
                n.read = true;
                notifyDataSetChanged();
                if (n.url != null && !n.url.isEmpty()) Router.open(activity, n.url);
            });
            return row;
        }
    }
}
