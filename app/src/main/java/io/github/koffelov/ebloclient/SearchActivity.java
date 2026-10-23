package io.github.koffelov.ebloclient;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Search for users and posts (the site's /api/search/all), with "all posts" as a feed. */
public class SearchActivity extends BaseActivity {
    private static final String EXTRA_QUERY = "q";
    private EditText input;
    private LinearLayout results;
    private StateView state;
    private int generation;

    static void start(Context c, String query) {
        c.startActivity(new Intent(c, SearchActivity.class).putExtra(EXTRA_QUERY, query));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        ImageView back = Ui.iconButton(this, R.drawable.ic_back, Ui.INK, "Назад");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        input = new EditText(this);
        input.setTypeface(Ui.font);
        input.setTextSize(19);
        input.setTextColor(Ui.INK);
        input.setHintTextColor(Ui.INK3);
        input.setHint("Поиск по eblo.id");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackground(Ui.round(Ui.INPUT, 22, Ui.LINE_STRONG));
        input.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(10));
        input.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        input.setCompoundDrawablePadding(Ui.dp(10));
        input.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.INK3));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                search();
                return true;
            }
            return false;
        });
        bar.addView(input, Ui.lpWeight());
        page.addView(bar);
        page.addView(Ui.divider(this));

        FrameLayout content = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        results = Ui.column(this);
        results.setPadding(0, Ui.dp(6), 0, Ui.dp(24));
        scroll.addView(results);
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        state = new StateView(this);
        state.hide();
        content.addView(state, new FrameLayout.LayoutParams(-1, -1));
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        String q = getIntent().getStringExtra(EXTRA_QUERY);
        if (!TextUtils.isEmpty(q)) {
            input.setText(q);
            search();
        } else {
            input.requestFocus();
            input.postDelayed(() -> {
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) imm.showSoftInput(input, 0);
            }, 250);
        }
    }

    private void search() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        final int gen = ++generation;
        state.loading();
        Http.run(() -> Api.search(q), (r, error) -> {
            if (!alive() || gen != generation) return;
            if (error != null) {
                state.error(Ui.errorText(this, error), this::search,
                        () -> WebActivity.open(this, Http.BASE + "/search?q=" + android.net.Uri.encode(q), null));
                return;
            }
            state.hide();
            show(q, r);
        });
    }

    private void show(String q, Api.SearchResult r) {
        results.removeAllViews();
        if (!r.users.isEmpty()) {
            results.addView(section("Пользователи"));
            for (Models.User u : r.users) {
                LinearLayout row = Ui.row(this);
                row.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(10));
                row.setBackground(Ui.ripple(null));
                ImageView avatar = Ui.avatar(this, 44);
                Images.load(avatar, u.avatar, Ui.dp(44));
                row.addView(avatar);
                LinearLayout text = Ui.column(this);
                text.setPadding(Ui.dp(12), 0, 0, 0);
                text.addView(Ui.text(this, u.name, 20, Ui.INK));
                text.addView(Ui.text(this, "@" + u.login, 15, Ui.INK3));
                row.addView(text, Ui.lpWeight());
                row.setOnClickListener(v -> ProfileActivity.start(this, u.login));
                results.addView(row);
            }
        }
        if (!r.posts.isEmpty()) {
            results.addView(section("Публикации"));
            for (Models.Post p : r.posts) {
                LinearLayout row = Ui.row(this);
                row.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
                row.setBackground(Ui.ripple(null));
                ImageView thumb = new ImageView(this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(Ui.round(Ui.SURFACE_RAISED, 8, 0));
                Ui.clipRound(thumb, 8);
                Images.load(thumb, p.thumb, Ui.dp(64));
                row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(64)));
                LinearLayout text = Ui.column(this);
                text.setPadding(Ui.dp(12), 0, 0, 0);
                TextView title = Ui.text(this, p.title.isEmpty() ? "Без названия" : p.title, 19, Ui.INK);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                text.addView(title);
                if (!p.authorName.isEmpty()) text.addView(Ui.text(this, p.authorName, 15, Ui.INK3));
                row.addView(text, Ui.lpWeight());
                row.setOnClickListener(v -> PostActivity.start(this, p.code));
                results.addView(row);
            }
        }
        TextView all = Ui.button(this, "Все публикации по запросу", false);
        all.setOnClickListener(v -> FeedActivity.startQuery(this, q));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(Ui.dp(16), Ui.dp(16), Ui.dp(16), 0);
        results.addView(all, lp);
        if (r.users.isEmpty() && r.posts.isEmpty()) {
            state.show(R.drawable.ic_search, "Ничего не найдено", "Попробуйте другой запрос.",
                    "Искать среди всех постов", () -> FeedActivity.startQuery(this, q), null, null);
        }
    }

    private View section(String title) {
        TextView t = Ui.text(this, title, 18, Ui.INK3);
        t.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(6));
        return t;
    }
}
