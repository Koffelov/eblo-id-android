package io.github.koffelov.ebloclient;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Own profile settings: avatar, badge, social links visibility, notification types, logout. */
public class EditProfileActivity extends BaseActivity {
    private static final int REQUEST_AVATAR = 71;
    private static final String[][] NOTIFY = {{"notify_likes", "Лайки на моих постах"},
            {"notify_comments", "Комментарии и ответы"}, {"notify_follows", "Новые подписчики"}};

    private LinearLayout form;
    private ImageView avatar;
    private LinearLayout badges;
    private LinearLayout notifyBox;
    private Switch social;
    private Models.Profile profile;

    static void start(Context c) {
        c.startActivity(new Intent(c, EditProfileActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addTopBar("Профиль и настройки", true);
        ScrollView scroll = new ScrollView(this);
        form = Ui.column(this);
        form.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(32));
        scroll.addView(form);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (!Session.loggedIn()) {
            form.addView(Ui.text(this, "Войдите, чтобы менять профиль.", 18, Ui.INK2));
            return;
        }
        LinearLayout top = Ui.row(this);
        avatar = Ui.avatar(this, 96);
        top.addView(avatar);
        LinearLayout buttons = Ui.column(this);
        buttons.setPadding(Ui.dp(16), 0, 0, 0);
        TextView change = Ui.button(this, "Сменить аватар", true);
        change.setOnClickListener(v -> pickAvatar());
        buttons.addView(change, new LinearLayout.LayoutParams(-1, -2));
        TextView remove = Ui.button(this, "Удалить аватар", false);
        remove.setOnClickListener(v -> deleteAvatar());
        buttons.addView(remove, Ui.margins(Ui.lp(-1, -2), 0, 8, 0, 0));
        top.addView(buttons, Ui.lpWeight());
        form.addView(top);
        form.addView(hint("Картинка до 5 МБ. Большие фото уменьшаются автоматически."));

        form.addView(section("Значок у ника"));
        HorizontalScrollView badgeScroll = new HorizontalScrollView(this);
        badgeScroll.setHorizontalScrollBarEnabled(false);
        badges = Ui.row(this);
        badgeScroll.addView(badges);
        form.addView(badgeScroll);

        form.addView(section("Соцсети в профиле"));
        LinearLayout socialRow = Ui.row(this);
        socialRow.addView(Ui.text(this, "Показывать Twitch и Telegram", 18, Ui.INK), Ui.lpWeight());
        social = toggle();
        socialRow.addView(social);
        form.addView(socialRow);

        form.addView(section("Уведомления"));
        notifyBox = Ui.column(this);
        form.addView(notifyBox);

        form.addView(section("Аккаунт"));
        TextView logout = Ui.button(this, "Выйти из аккаунта", false);
        logout.setTextColor(Ui.DANGER);
        logout.setOnClickListener(v -> confirmLogout());
        form.addView(logout, new LinearLayout.LayoutParams(-1, -2));

        form.addView(section("О приложении"));
        form.addView(hint(getString(R.string.notice_text)));

        load();
    }

    private TextView section(String text) {
        TextView t = Ui.text(this, text, 20, Ui.INK);
        t.setPadding(0, Ui.dp(26), 0, Ui.dp(10));
        return t;
    }

    private TextView hint(String text) {
        TextView t = Ui.text(this, text, 15, Ui.INK3);
        t.setPadding(0, Ui.dp(8), 0, 0);
        return t;
    }

    private Switch toggle() {
        Switch s = new Switch(this);
        s.setThumbTintList(ColorStateList.valueOf(Ui.BRAND_LIGHT));
        s.setTrackTintList(ColorStateList.valueOf(0x669AC43E));
        return s;
    }

    private void load() {
        String login = Session.login();
        Http.run(() -> Api.profile(login), (p, error) -> {
            if (!alive() || p == null) return;
            profile = p;
            Images.load(avatar, p.avatar, Ui.dp(96));
            social.setOnCheckedChangeListener(null);
            social.setEnabled(p.showSocial != null);
            social.setChecked(p.showSocial != null && p.showSocial);
            social.setOnCheckedChangeListener(this::onSocialChanged);
        });
        Http.run(Api::myBadges, (list, error) -> {
            if (alive() && list != null) renderBadges(list);
        });
        Http.run(Api::notificationPrefs, (prefs, error) -> {
            if (alive()) renderNotify(prefs);
        });
    }

    private void onSocialChanged(CompoundButton b, boolean checked) {
        b.setEnabled(false);
        Http.run(() -> Api.setSocialVisibility(checked), (value, error) -> {
            if (!alive()) return;
            b.setEnabled(true);
            if (error != null) {
                Ui.toast(this, Ui.errorText(this, error));
                b.setOnCheckedChangeListener(null);
                b.setChecked(!checked);
                b.setOnCheckedChangeListener(this::onSocialChanged);
            }
        });
    }

    private void renderBadges(List<Models.Badge> list) {
        badges.removeAllViews();
        if (list.isEmpty()) {
            badges.addView(Ui.text(this, "Значков пока нет", 16, Ui.INK3));
            return;
        }
        boolean anyActive = false;
        for (Models.Badge b : list) anyActive |= b.active;
        badges.addView(badgeCell(null, "Без значка", null, !anyActive, list));
        for (Models.Badge b : list) badges.addView(badgeCell(b.id, b.name, b.icon, b.active, list));
    }

    private View badgeCell(Long id, String name, String icon, boolean active, List<Models.Badge> all) {
        LinearLayout cell = Ui.column(this);
        cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        cell.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(8));
        cell.setBackground(Ui.ripple(active ? Ui.round(0x339AC43E, 12, Ui.BRAND_LIGHT) : Ui.round(Ui.SURFACE, 12, Ui.LINE_STRONG)));
        FrameLayout box = new FrameLayout(this);
        if (icon != null) {
            ImageView img = new ImageView(this);
            Images.load(img, icon, Ui.dp(36));
            box.addView(img, new FrameLayout.LayoutParams(Ui.dp(36), Ui.dp(36)));
        } else {
            box.addView(Ui.icon(this, R.drawable.ic_close, 36, Ui.INK3), new FrameLayout.LayoutParams(Ui.dp(36), Ui.dp(36)));
        }
        cell.addView(box);
        TextView label = Ui.text(this, name, 13, Ui.INK2);
        label.setMaxWidth(Ui.dp(84));
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(label, Ui.margins(Ui.lp(-2, -2), 0, 6, 0, 0));
        cell.setOnClickListener(v -> Http.run(() -> {
            Api.setBadge(id);
            return Boolean.TRUE;
        }, (ok, error) -> {
            if (!alive()) return;
            if (error != null) {
                Ui.toast(this, Ui.errorText(this, error));
                return;
            }
            for (Models.Badge b : all) b.active = id != null && id.equals(b.id);
            renderBadges(all);
            Ui.toast(this, "Значок обновлён");
        }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, Ui.dp(8), 0);
        cell.setLayoutParams(lp);
        return cell;
    }

    private void renderNotify(JSONObject prefs) {
        notifyBox.removeAllViews();
        if (prefs == null) {
            notifyBox.addView(hint("Не удалось загрузить настройки уведомлений."));
            return;
        }
        for (String[] item : NOTIFY) {
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(6), 0, Ui.dp(6));
            row.addView(Ui.text(this, item[1], 18, Ui.INK), Ui.lpWeight());
            Switch s = toggle();
            s.setChecked(prefs.optBoolean(item[0], true));
            s.setOnCheckedChangeListener((b, checked) -> {
                b.setEnabled(false);
                Http.run(() -> {
                    Api.setNotificationPref(item[0], checked);
                    return Boolean.TRUE;
                }, (ok, error) -> {
                    if (!alive()) return;
                    b.setEnabled(true);
                    if (error != null) Ui.toast(this, Ui.errorText(this, error));
                });
            });
            row.addView(s);
            notifyBox.addView(row);
        }
    }

    // ---- avatar ----

    private void pickAvatar() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQUEST_AVATAR);
        } catch (android.content.ActivityNotFoundException e) {
            Ui.toast(this, "Не найдено приложение для выбора картинок");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_AVATAR || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Ui.toast(this, "Загружаем аватар…");
        Http.run(() -> Api.uploadAvatar(prepareAvatar(uri)), (url, error) -> {
            if (!alive()) return;
            if (error != null) {
                Ui.toast(this, Ui.errorText(this, error));
                return;
            }
            Images.load(avatar, url + "?v=" + Session.avatarVersion, Ui.dp(96));
            Ui.toast(this, "Аватар обновлён");
        });
    }

    /** Square-ish JPEG under 5 MB, at most 1024 px. */
    private byte[] prepareAvatar(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2;
        opts.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bitmap == null) throw new IOException("Это не похоже на картинку");
        float scale = Math.min(1f, 1024f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (scale < 1f) {
            bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale),
                    Math.round(bitmap.getHeight() * scale), true);
        }
        int quality = 92;
        byte[] out;
        do {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes);
            out = bytes.toByteArray();
            quality -= 12;
        } while (out.length > 5 * 1024 * 1024 && quality > 30);
        return out;
    }

    private void deleteAvatar() {
        new AlertDialog.Builder(this)
                .setTitle("Удалить аватар?")
                .setMessage("Вернётся аватар из Twitch.")
                .setPositiveButton("Удалить", (d, w) -> Http.run(() -> {
                    Api.deleteAvatar();
                    return Boolean.TRUE;
                }, (ok, error) -> {
                    if (!alive()) return;
                    if (error != null) Ui.toast(this, Ui.errorText(this, error));
                    else {
                        Ui.toast(this, "Аватар удалён");
                        load();
                    }
                }))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Выйти из аккаунта?")
                .setPositiveButton("Выйти", (d, w) -> Http.run(() -> {
                    Api.logout();
                    return Boolean.TRUE;
                }, (ok, error) -> {
                    if (!alive()) return;
                    if (error != null) {
                        Ui.toast(this, Ui.errorText(this, error));
                        return;
                    }
                    Ui.toast(this, "Вы вышли из аккаунта");
                    finish();
                }))
                .setNegativeButton("Отмена", null)
                .show();
    }
}
