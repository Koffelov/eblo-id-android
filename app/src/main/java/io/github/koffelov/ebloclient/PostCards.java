package io.github.koffelov.ebloclient;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Feed cards and profile grid cells, styled after eblo.id's feed. */
final class PostCards {
    private PostCards() {
    }

    static final class Holder {
        View root;
        RatioFrame media;
        ImageView thumb;
        LinearLayout typeBadge;
        ImageView typeIcon;
        TextView typeText;
        TextView hidden;
        ImageView avatar;
        TextView name;
        ImageView badge;
        TextView time;
        TextView title;
        TextView description;
        TextView views;
        TextView score;
        TextView comments;
        Models.Post post;
    }

    static View card(Activity a, View convertView) {
        if (convertView != null && convertView.getTag() instanceof Holder) return convertView;
        Holder h = new Holder();
        LinearLayout card = Ui.column(a);
        card.setBackground(Ui.ripple(Ui.round(Ui.SURFACE, 12, Ui.LINE_STRONG)));
        Ui.clipRound(card, 12);
        FrameLayout outer = new FrameLayout(a);
        outer.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        outer.addView(card);

        h.media = new RatioFrame(a, 0.75f);
        h.media.setBackgroundColor(Ui.SURFACE_RAISED);
        h.thumb = new ImageView(a);
        h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        h.media.addView(h.thumb, new FrameLayout.LayoutParams(-1, -1));
        h.typeBadge = Ui.row(a);
        h.typeBadge.setBackground(Ui.round(0xB3000000, 14, 0));
        h.typeBadge.setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(10), Ui.dp(4));
        h.typeIcon = Ui.icon(a, R.drawable.ic_play, 18, Color.WHITE);
        h.typeBadge.addView(h.typeIcon);
        h.typeText = Ui.text(a, 15, Color.WHITE);
        h.typeText.setPadding(Ui.dp(4), 0, 0, 0);
        h.typeBadge.addView(h.typeText);
        FrameLayout.LayoutParams tb = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tb.setMargins(0, Ui.dp(10), Ui.dp(10), 0);
        h.media.addView(h.typeBadge, tb);
        h.hidden = Ui.text(a, "Скрытый контент\nнажмите, чтобы открыть", 18, Ui.INK);
        h.hidden.setGravity(Gravity.CENTER);
        h.hidden.setBackgroundColor(0xF0151515);
        h.media.addView(h.hidden, new FrameLayout.LayoutParams(-1, -1));
        card.addView(h.media, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = Ui.column(a);
        body.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        LinearLayout author = Ui.row(a);
        h.avatar = Ui.avatar(a, 26);
        author.addView(h.avatar);
        h.name = Ui.text(a, 17, Ui.INK2);
        h.name.setSingleLine(true);
        h.name.setEllipsize(TextUtils.TruncateAt.END);
        h.name.setPadding(Ui.dp(8), 0, Ui.dp(4), 0);
        author.addView(h.name, new LinearLayout.LayoutParams(-2, -2, 0f));
        h.badge = Ui.icon(a, R.drawable.ic_person, 18, 0);
        h.badge.setImageTintList(null);
        author.addView(h.badge);
        View spacer = new View(a);
        author.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        h.time = Ui.text(a, 15, Ui.INK3);
        author.addView(h.time);
        body.addView(author);

        h.title = Ui.text(a, 21, Ui.INK);
        h.title.setMaxLines(2);
        h.title.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(h.title, Ui.margins(Ui.lp(-1, -2), 0, 10, 0, 0));
        h.description = Ui.text(a, 16, Ui.INK2);
        h.description.setMaxLines(2);
        h.description.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(h.description, Ui.margins(Ui.lp(-1, -2), 0, 4, 0, 0));

        LinearLayout stats = Ui.row(a);
        LinearLayout v = Ui.stat(a, R.drawable.ic_eye, "");
        LinearLayout s = Ui.stat(a, R.drawable.ic_heart, "");
        LinearLayout c = Ui.stat(a, R.drawable.ic_comment, "");
        h.views = (TextView) v.getChildAt(1);
        h.score = (TextView) s.getChildAt(1);
        h.comments = (TextView) c.getChildAt(1);
        stats.addView(v);
        stats.addView(s);
        stats.addView(c);
        body.addView(stats, Ui.margins(Ui.lp(-1, -2), 0, 12, 0, 0));
        card.addView(body);

        h.root = outer;
        outer.setTag(h);
        return outer;
    }

    static void bind(Activity a, View view, Models.Post p) {
        Holder h = (Holder) view.getTag();
        h.post = p;
        int width = a.getResources().getDisplayMetrics().widthPixels;
        boolean hasPreview = p.thumb != null;
        h.media.setVisibility(hasPreview || "audio".equals(p.type) ? View.VISIBLE : View.GONE);
        h.media.setRatio(hasPreview ? 0.75f : 0.35f);
        Images.load(h.thumb, p.thumb, width);
        h.hidden.setVisibility(p.blur ? View.VISIBLE : View.GONE);
        bindTypeBadge(h.typeBadge, h.typeIcon, h.typeText, p);

        Images.load(h.avatar, p.authorAvatar, Ui.dp(26));
        h.name.setText(p.authorName);
        h.badge.setVisibility(p.badgeIcon == null ? View.GONE : View.VISIBLE);
        Images.load(h.badge, p.badgeIcon, Ui.dp(18));
        h.time.setText(Ui.time(p.created));
        h.title.setText(p.title);
        h.title.setVisibility(p.title.isEmpty() ? View.GONE : View.VISIBLE);
        h.description.setText(p.description);
        h.description.setVisibility(p.description.isEmpty() ? View.GONE : View.VISIBLE);
        h.views.setText(Ui.count(p.views));
        h.score.setText(Ui.count(p.score));
        h.comments.setText(Ui.count(p.comments));

        View card = ((ViewGroup) h.root).getChildAt(0);
        card.setOnClickListener(x -> PostActivity.start(a, p));
        View.OnClickListener toProfile = x -> ProfileActivity.start(a, p.authorLogin);
        h.avatar.setOnClickListener(toProfile);
        h.name.setOnClickListener(toProfile);
    }

    static void bindTypeBadge(LinearLayout badge, ImageView icon, TextView text, Models.Post p) {
        int res = 0;
        String label = "";
        if (p.album || "album".equals(p.type)) {
            res = R.drawable.ic_album;
            label = p.filesCount > 0 ? String.valueOf(p.filesCount) : "";
        } else if ("video".equals(p.type)) {
            res = R.drawable.ic_play;
        } else if ("audio".equals(p.type)) {
            res = R.drawable.ic_music;
        } else if ("file".equals(p.type) || "document".equals(p.type) || "archive".equals(p.type)) {
            res = R.drawable.ic_file;
        }
        badge.setVisibility(res == 0 ? View.GONE : View.VISIBLE);
        if (res != 0) icon.setImageResource(res);
        text.setText(label);
        text.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- profile grid: rows of three square cells ----

    static View gridRow(Activity a, View convertView) {
        if (convertView instanceof LinearLayout && "grid".equals(convertView.getTag())) return convertView;
        LinearLayout row = Ui.row(a);
        row.setTag("grid");
        row.setPadding(Ui.dp(2), 0, Ui.dp(2), 0);
        for (int i = 0; i < 3; i++) {
            RatioFrame cell = new RatioFrame(a, 1f);
            cell.setBackgroundColor(Ui.SURFACE_RAISED);
            ImageView img = new ImageView(a);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(img, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout badge = Ui.row(a);
            badge.setBackground(Ui.round(0xB3000000, 12, 0));
            badge.setPadding(Ui.dp(5), Ui.dp(3), Ui.dp(7), Ui.dp(3));
            badge.addView(Ui.icon(a, R.drawable.ic_play, 15, Color.WHITE));
            TextView t = Ui.text(a, 13, Color.WHITE);
            t.setPadding(Ui.dp(3), 0, 0, 0);
            badge.addView(t);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.setMargins(0, Ui.dp(6), Ui.dp(6), 0);
            cell.addView(badge, lp);
            TextView title = Ui.text(a, 15, Ui.INK);
            title.setMaxLines(3);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
            title.setGravity(Gravity.CENTER);
            cell.addView(title, new FrameLayout.LayoutParams(-1, -1));
            cell.setForeground(Ui.ripple(null));
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1f);
            lp2.setMargins(Ui.dp(2), Ui.dp(2), Ui.dp(2), Ui.dp(2));
            row.addView(cell, lp2);
        }
        return row;
    }

    static void bindGridRow(Activity a, View view, List<Models.Post> posts, int start) {
        LinearLayout row = (LinearLayout) view;
        int cellWidth = a.getResources().getDisplayMetrics().widthPixels / 3;
        for (int i = 0; i < 3; i++) {
            RatioFrame cell = (RatioFrame) row.getChildAt(i);
            ImageView img = (ImageView) cell.getChildAt(0);
            LinearLayout badge = (LinearLayout) cell.getChildAt(1);
            TextView title = (TextView) cell.getChildAt(2);
            int index = start + i;
            if (index >= posts.size()) {
                cell.setVisibility(View.INVISIBLE);
                cell.setOnClickListener(null);
                continue;
            }
            Models.Post p = posts.get(index);
            cell.setVisibility(View.VISIBLE);
            Images.load(img, p.blur ? null : p.thumb, cellWidth);
            title.setText(p.thumb == null || p.blur ? (p.title.isEmpty() ? "Без названия" : p.title) : "");
            title.setVisibility(p.thumb == null || p.blur ? View.VISIBLE : View.GONE);
            bindTypeBadge(badge, (ImageView) badge.getChildAt(0), (TextView) badge.getChildAt(1), p);
            cell.setOnClickListener(x -> PostActivity.start(a, p));
        }
    }

    static GradientDrawable dot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }
}
