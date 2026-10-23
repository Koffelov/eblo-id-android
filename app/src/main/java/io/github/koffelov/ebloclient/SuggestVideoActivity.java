package io.github.koffelov.ebloclient;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** "Предложить видео": suggest a YouTube video for Bratishkin's stream, natively. */
public class SuggestVideoActivity extends BaseActivity {
    private static final Pattern YOUTUBE = Pattern.compile(
            "^(?:https?://)?(?:[a-z0-9-]+\\.)*(?:youtube\\.com|youtu\\.be)/\\S+$", Pattern.CASE_INSENSITIVE);

    private final List<Long> selected = new ArrayList<>();
    private StateView state;
    private EditText url;
    private EditText comment;
    private LinearLayout categoriesBox;
    private TextView submit;
    private boolean sending;

    static void start(Context c) {
        c.startActivity(new Intent(c, SuggestVideoActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addTopBar("Предложить видео", true);
        FrameLayout content = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = Ui.column(this);
        form.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(32));
        scroll.addView(form);
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        state = new StateView(this);
        state.hide();
        content.addView(state, new FrameLayout.LayoutParams(-1, -1));
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView about = Ui.text(this, "Видео для просмотра на стриме Братишкина. Каждое проходит модерацию: "
                + "если его отклонили, значит оно не подошло под настроение и актуальный контент.", 16, Ui.INK2);
        about.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        about.setBackground(Ui.round(Ui.SURFACE, 12, Ui.LINE_STRONG));
        form.addView(about);

        form.addView(label("Ссылка на YouTube"));
        url = field("https://youtu.be/…", false);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(url);

        form.addView(label("Категории"));
        categoriesBox = Ui.column(this);
        categoriesBox.addView(Ui.text(this, "Загрузка…", 16, Ui.INK3));
        form.addView(categoriesBox);

        form.addView(label("Комментарий"));
        comment = field("Чем видео интересно (необязательно)", true);
        form.addView(comment);

        submit = Ui.button(this, "Отправить", true);
        submit.setOnClickListener(v -> send());
        form.addView(submit, Ui.margins(Ui.lp(-1, -2), 0, 24, 0, 0));

        TextView paid = Ui.text(this, "Платное размещение без очереди доступно на сайте.", 14, Ui.INK3);
        paid.setGravity(Gravity.CENTER);
        form.addView(paid, Ui.margins(Ui.lp(-1, -2), 0, 12, 0, 0));

        loadCategories();
        if (!Session.known()) Session.refresh(this::checkLogin);
        else checkLogin();
    }

    private void checkLogin() {
        if (!alive()) return;
        if (Session.loggedIn()) {
            state.hide();
        } else {
            state.loginPrompt("Предложить видео", "Войдите через Twitch, чтобы предлагать видео на стрим.",
                    () -> WebActivity.login(this));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WebActivity.REQUEST_LOGIN) checkLogin();
    }

    private TextView label(String text) {
        TextView t = Ui.text(this, text, 18, Ui.INK2);
        t.setPadding(0, Ui.dp(18), 0, Ui.dp(6));
        return t;
    }

    private EditText field(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setTypeface(Ui.font);
        e.setTextSize(19);
        e.setTextColor(Ui.INK);
        e.setHintTextColor(Ui.INK3);
        e.setHint(hint);
        e.setBackground(Ui.round(Ui.INPUT, 10, Ui.LINE_STRONG));
        e.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(3);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    private void loadCategories() {
        Http.run(Api::videoCategories, (list, error) -> {
            if (!alive()) return;
            categoriesBox.removeAllViews();
            if (error != null || list == null) {
                TextView retry = Ui.text(this, Ui.errorText(this, error) + " · повторить", 16, Ui.DANGER);
                retry.setOnClickListener(v -> loadCategories());
                categoriesBox.addView(retry);
                return;
            }
            LinearLayout row = null;
            for (int i = 0; i < list.size(); i++) {
                Models.Category c = list.get(i);
                if (i % 3 == 0) {
                    row = Ui.row(this);
                    categoriesBox.addView(row, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, 8));
                }
                TextView chip = Ui.chip(this, c.title, selected.contains(c.id));
                chip.setOnClickListener(v -> {
                    if (selected.contains(c.id)) selected.remove(c.id);
                    else selected.add(c.id);
                    boolean on = selected.contains(c.id);
                    chip.setTextColor(on ? 0xFF111111 : Ui.INK);
                    chip.setBackground(Ui.ripple(on ? Ui.round(Ui.BRAND_LIGHT, 18, 0) : Ui.round(Ui.SURFACE, 18, Ui.LINE_STRONG)));
                });
                row.addView(chip, Ui.margins(new LinearLayout.LayoutParams(0, -2, 1f), 0, 0, 8, 0));
            }
        });
    }

    private void send() {
        if (sending) return;
        String link = url.getText().toString().trim();
        if (!YOUTUBE.matcher(link).matches()) {
            url.setError("Вставьте ссылку на видео YouTube");
            url.requestFocus();
            return;
        }
        if (selected.isEmpty()) {
            Ui.toast(this, "Выберите хотя бы одну категорию");
            return;
        }
        if (!link.startsWith("http")) link = "https://" + link;
        final String videoUrl = link;
        final String text = comment.getText().toString().trim();
        final List<Long> cats = new ArrayList<>(selected);
        sending = true;
        submit.setEnabled(false);
        submit.setText("Отправляем…");
        Http.run(() -> Api.offerVideo(videoUrl, cats, text), (message, error) -> {
            if (!alive()) return;
            sending = false;
            submit.setEnabled(true);
            submit.setText("Отправить");
            if (error != null) {
                if (error instanceof Api.ApiException && ((Api.ApiException) error).captcha) {
                    Turnstile.show(this, ok -> {
                        if (ok) send();
                    });
                    return;
                }
                new AlertDialog.Builder(this).setTitle("Не отправлено")
                        .setMessage(Ui.errorText(this, error)).setPositiveButton("Понятно", null).show();
                return;
            }
            new AlertDialog.Builder(this).setTitle("Готово")
                    .setMessage(message + "\n\nСтатус предложенных видео виден в разделе «Видосы».")
                    .setCancelable(false)
                    .setPositiveButton("Отлично", (d, w) -> finish())
                    .show();
        });
    }
}
