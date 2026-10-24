package io.github.koffelov.ebloclient;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A post: media, votes and comments, all native. Comments can be written when logged in. */
public class PostActivity extends BaseActivity {
    private static final String EXTRA_CODE = "code";
    private static final String EXTRA_BADGE = "badge";

    private String code;
    private String badgeIcon;
    private Models.Post post;
    private String userVote;
    private String sort = "best";
    private long pinnedId = -1;
    private final List<Models.Comment> flat = new ArrayList<>();
    private final CommentsAdapter adapter = new CommentsAdapter();

    private ListView list;
    private StateView state;
    private LinearLayout header;
    private TextView commentsFooter;
    private TextView scoreView;
    private ImageView upButton;
    private ImageView downButton;
    private TextView commentsTitle;
    private TextView sortBest;
    private TextView sortNew;

    private LinearLayout composer;
    private LinearLayout replyBar;
    private TextView replyLabel;
    private EditText input;
    private ImageView sendButton;
    private TextView loginBar;
    private Long replyTo;
    private boolean sending;

    private MediaPlayer player;
    private ImageView playerButton;
    private final List<VideoPlayer> players = new ArrayList<>();
    private VideoPlayer fullscreenPlayer;

    static void start(Context c, String code) {
        c.startActivity(new Intent(c, PostActivity.class).putExtra(EXTRA_CODE, code));
    }

    static void start(Context c, Models.Post p) {
        c.startActivity(new Intent(c, PostActivity.class).putExtra(EXTRA_CODE, p.code)
                .putExtra(EXTRA_BADGE, p.badgeIcon));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        code = getIntent().getStringExtra(EXTRA_CODE);
        badgeIcon = getIntent().getStringExtra(EXTRA_BADGE);
        addTopBar("Пост", true);
        addAction(R.drawable.ic_share, "Поделиться", v -> Router.share(this, post == null ? null : post.title, url()));
        addAction(R.drawable.ic_more, "Ещё", this::showMenu);

        FrameLayout content = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setBackgroundColor(Ui.BG);
        header = Ui.column(this);
        list.addHeaderView(header, null, false);
        commentsFooter = Ui.text(this, 17, Ui.INK3);
        commentsFooter.setGravity(Gravity.CENTER);
        commentsFooter.setPadding(0, Ui.dp(20), 0, Ui.dp(28));
        list.addFooterView(commentsFooter, null, false);
        list.setAdapter(adapter);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        state = new StateView(this);
        content.addView(state, new FrameLayout.LayoutParams(-1, -1));
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        buildComposer();
        load();
    }

    private String url() {
        return Http.BASE + "/" + code;
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Открыть на сайте");
        menu.getMenu().add(0, 2, 1, "Скопировать ссылку");
        menu.getMenu().add(0, 3, 2, "Обновить");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) WebActivity.open(this, url(), post == null ? null : post.title);
            if (item.getItemId() == 2) {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("eblo.id", url()));
                Ui.toast(this, "Ссылка скопирована");
            }
            if (item.getItemId() == 3) load();
            return true;
        });
        menu.show();
    }

    // ---- loading ----

    private static final class Loaded {
        Models.Post post;
        String vote;
    }

    private void load() {
        state.loading();
        Http.run(() -> {
            Loaded l = new Loaded();
            l.post = Api.post(code);
            try {
                l.vote = Api.userVote(code);
            } catch (IOException ignored) {
                // The vote highlight is optional.
            }
            return l;
        }, (loaded, error) -> {
            if (!alive()) return;
            if (error != null) {
                boolean missing = error instanceof Api.ApiException && ((Api.ApiException) error).code == 404;
                state.error(missing ? "Пост не найден или доступен только на сайте" : Ui.errorText(this, error),
                        this::load, () -> WebActivity.open(this, url(), null));
                return;
            }
            post = loaded.post;
            userVote = loaded.vote;
            state.hide();
            buildHeader();
            updateComposer();
            loadComments();
        });
    }

    private void loadComments() {
        commentsFooter.setText("Загрузка комментариев…");
        Http.run(() -> Api.comments(code, sort), (result, error) -> {
            if (!alive()) return;
            if (error != null) {
                commentsFooter.setText(Ui.errorText(this, error) + "\nНажмите, чтобы повторить");
                commentsFooter.setOnClickListener(v -> loadComments());
                return;
            }
            commentsFooter.setOnClickListener(null);
            flat.clear();
            pinnedId = result.pinnedId;
            for (Models.Comment c : result.items) flatten(c, 0);
            adapter.notifyDataSetChanged();
            commentsTitle.setText("Комментарии · " + result.total);
            commentsFooter.setText(flat.isEmpty() ? "Комментариев пока нет" : "");
        });
    }

    private void flatten(Models.Comment c, int depth) {
        c.depth = Math.min(depth, 4);
        flat.add(c);
        for (Models.Comment r : c.replies) flatten(r, depth + 1);
    }

    // ---- post header ----

    private void buildHeader() {
        for (VideoPlayer p : players) p.release();
        players.clear();
        header.removeAllViews();
        int width = getResources().getDisplayMetrics().widthPixels;

        LinearLayout author = Ui.row(this);
        author.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(10));
        author.setBackground(Ui.ripple(null));
        ImageView avatar = Ui.avatar(this, 42);
        Images.load(avatar, post.authorAvatar, Ui.dp(42));
        author.addView(avatar);
        LinearLayout who = Ui.column(this);
        who.setPadding(Ui.dp(12), 0, 0, 0);
        LinearLayout nameRow = Ui.row(this);
        nameRow.addView(Ui.text(this, post.authorName, 20, Ui.INK));
        if (badgeIcon != null) {
            ImageView badge = Ui.icon(this, R.drawable.ic_person, 20, 0);
            badge.setImageTintList(null);
            Images.load(badge, badgeIcon, Ui.dp(20));
            nameRow.addView(badge, Ui.margins(Ui.lp(Ui.dp(20), Ui.dp(20)), 6, 0, 0, 0));
        }
        who.addView(nameRow);
        who.addView(Ui.text(this, Ui.time(post.created), 15, Ui.INK3));
        author.addView(who, Ui.lpWeight());
        author.setOnClickListener(v -> ProfileActivity.start(this, post.authorLogin));
        header.addView(author);

        if (!post.title.isEmpty()) {
            TextView title = Ui.text(this, post.title, 26, Ui.INK);
            title.setTextIsSelectable(true);
            title.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(10));
            header.addView(title);
        }

        LinearLayout mediaBox = Ui.column(this);
        List<String> imageUrls = new ArrayList<>();
        for (Models.Media m : post.media) if (m.isImage()) imageUrls.add(m.url);
        for (Models.Media m : post.media) mediaBox.addView(mediaView(m, imageUrls, width));
        FrameLayout mediaFrame = new FrameLayout(this);
        mediaFrame.addView(mediaBox);
        if (post.blur && !post.media.isEmpty()) {
            TextView cover = Ui.text(this, "Скрытый контент\nнажмите, чтобы показать", 20, Ui.INK);
            cover.setGravity(Gravity.CENTER);
            cover.setBackgroundColor(0xFA151515);
            cover.setMinHeight(Ui.dp(220));
            cover.setOnClickListener(v -> mediaFrame.removeView(v));
            mediaFrame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        }
        header.addView(mediaFrame);

        if (!post.description.isEmpty()) {
            TextView desc = Ui.text(this, 18, Ui.INK);
            Ui.setRichText(desc, post.description, this);
            desc.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), 0);
            header.addView(desc);
        }

        LinearLayout actions = Ui.row(this);
        actions.setPadding(Ui.dp(8), Ui.dp(10), Ui.dp(16), Ui.dp(6));
        LinearLayout vote = Ui.row(this);
        vote.setBackground(Ui.round(Ui.SURFACE, 22, Ui.LINE_STRONG));
        upButton = Ui.iconButton(this, R.drawable.ic_up, Ui.INK2, "Нравится");
        upButton.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        downButton = Ui.iconButton(this, R.drawable.ic_down, Ui.INK2, "Не нравится");
        downButton.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        scoreView = Ui.text(this, 20, Ui.INK);
        scoreView.setMinWidth(Ui.dp(28));
        scoreView.setGravity(Gravity.CENTER);
        vote.addView(upButton);
        vote.addView(scoreView);
        vote.addView(downButton);
        upButton.setOnClickListener(v -> votePost("up"));
        downButton.setOnClickListener(v -> votePost("down"));
        actions.addView(vote, Ui.margins(Ui.lp(-2, -2), 8, 0, 12, 0));
        actions.addView(Ui.stat(this, R.drawable.ic_eye, Ui.count(post.views)));
        actions.addView(Ui.stat(this, R.drawable.ic_comment, Ui.count(post.comments)));
        header.addView(actions);
        updateVote(post.score);

        header.addView(Ui.divider(this), Ui.margins(Ui.lp(-1, Math.max(1, Ui.dp(1))), 0, 10, 0, 0));
        LinearLayout commentsHead = Ui.row(this);
        commentsHead.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(12), Ui.dp(6));
        commentsTitle = Ui.text(this, "Комментарии", 22, Ui.INK);
        commentsHead.addView(commentsTitle, Ui.lpWeight());
        sortBest = Ui.chip(this, "Лучшие", true);
        sortNew = Ui.chip(this, "Новые", false);
        sortBest.setOnClickListener(v -> setSort("best"));
        sortNew.setOnClickListener(v -> setSort("new"));
        commentsHead.addView(sortBest, Ui.margins(Ui.lp(-2, -2), 0, 0, 6, 0));
        commentsHead.addView(sortNew);
        header.addView(commentsHead);
        updateSortChips();
    }

    private View mediaView(Models.Media m, List<String> imageUrls, int width) {
        if (m.isImage()) {
            ImageView img = new ImageView(this);
            img.setAdjustViewBounds(true);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setBackgroundColor(Ui.SURFACE_RAISED);
            img.setMinimumHeight(Ui.dp(220));
            img.setContentDescription(m.name);
            Images.load(img, m.url, width, bitmap -> {
                img.setMinimumHeight(0);
                img.setBackgroundColor(0);
            });
            int index = imageUrls.indexOf(m.url);
            img.setOnClickListener(v -> MediaActivity.images(this, imageUrls, Math.max(0, index)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(4);
            img.setLayoutParams(lp);
            return img;
        }
        if (m.isVideo()) {
            RatioFrame frame = new RatioFrame(this, 0.5625f);
            frame.setBackgroundColor(Color.BLACK);
            final String poster = m.thumb != null ? m.thumb : post.thumb;
            final VideoPlayer[] self = new VideoPlayer[1];
            self[0] = new VideoPlayer(this, m.url, poster, false, new VideoPlayer.Listener() {
                @Override
                public void onAspect(float heightToWidth) {
                    frame.setRatio(Math.max(0.42f, Math.min(1.25f, heightToWidth)));
                }

                @Override
                public void onFullscreen(int positionMs) {
                    fullscreenPlayer = self[0];
                    self[0].unload();
                    MediaActivity.video(PostActivity.this, m.url, poster, post.title, positionMs);
                }

                @Override
                public void onPlay(VideoPlayer p) {
                    releasePlayer();
                    for (VideoPlayer other : players) if (other != p) other.pause();
                }
            });
            players.add(self[0]);
            frame.addView(self[0], new FrameLayout.LayoutParams(-1, -1));
            frame.setContentDescription("Видео " + m.name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(4);
            frame.setLayoutParams(lp);
            return frame;
        }
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        row.setBackground(Ui.ripple(Ui.round(Ui.SURFACE, 12, Ui.LINE_STRONG)));
        ImageView icon = Ui.icon(this, m.isAudio() ? R.drawable.ic_play_circle : R.drawable.ic_download, 40, Ui.BRAND_LIGHT);
        row.addView(icon);
        LinearLayout text = Ui.column(this);
        text.setPadding(Ui.dp(12), 0, 0, 0);
        TextView name = Ui.text(this, m.name.isEmpty() ? "Файл" : m.name, 18, Ui.INK);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        text.addView(name);
        text.addView(Ui.text(this, (m.isAudio() ? "Аудио · " : "Скачать · ") + Ui.size(m.size), 15, Ui.INK3));
        row.addView(text, Ui.lpWeight());
        if (m.isAudio()) {
            row.setOnClickListener(v -> toggleAudio(m.url, icon));
        } else {
            row.setOnClickListener(v -> WebActivity.download(this, m.url, null, m.mime, null));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(4));
        row.setLayoutParams(lp);
        return row;
    }

    private void toggleAudio(String url, ImageView button) {
        if (player != null && playerButton == button) {
            if (player.isPlaying()) {
                player.pause();
                button.setImageResource(R.drawable.ic_play_circle);
            } else {
                player.start();
                button.setImageResource(R.drawable.ic_pause);
            }
            return;
        }
        releasePlayer();
        playerButton = button;
        button.setImageResource(R.drawable.ic_pause);
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        try {
            player.setDataSource(url);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> button.setImageResource(R.drawable.ic_play_circle));
            player.setOnErrorListener((mp, what, extra) -> {
                Ui.toast(this, "Не удалось воспроизвести аудио");
                releasePlayer();
                return true;
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException e) {
            Ui.toast(this, "Не удалось воспроизвести аудио");
            releasePlayer();
        }
    }

    private void releasePlayer() {
        if (playerButton != null) playerButton.setImageResource(R.drawable.ic_play_circle);
        if (player != null) {
            player.release();
            player = null;
        }
        playerButton = null;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null && player.isPlaying()) {
            player.pause();
            if (playerButton != null) playerButton.setImageResource(R.drawable.ic_play_circle);
        }
        for (VideoPlayer p : players) p.pause();
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        for (VideoPlayer p : players) p.release();
        super.onDestroy();
    }

    // ---- votes ----

    private void updateVote(int score) {
        scoreView.setText(Ui.count(score));
        upButton.setImageTintList(android.content.res.ColorStateList.valueOf("up".equals(userVote) ? Ui.BRAND_LIGHT : Ui.INK2));
        downButton.setImageTintList(android.content.res.ColorStateList.valueOf("down".equals(userVote) ? Ui.DANGER : Ui.INK2));
        scoreView.setTextColor("up".equals(userVote) ? Ui.BRAND_LIGHT : "down".equals(userVote) ? Ui.DANGER : Ui.INK);
    }

    private void votePost(String vote) {
        if (!requireLogin("Войдите, чтобы оценивать посты")) return;
        upButton.setEnabled(false);
        downButton.setEnabled(false);
        Http.run(() -> Api.votePost(code, vote), (r, error) -> {
            if (!alive()) return;
            upButton.setEnabled(true);
            downButton.setEnabled(true);
            if (error != null) {
                showActionError(error);
                return;
            }
            userVote = r.userVote;
            post.score = r.score;
            updateVote(r.score);
        });
    }

    private void voteComment(Models.Comment c, String vote) {
        if (!requireLogin("Войдите, чтобы оценивать комментарии")) return;
        Http.run(() -> Api.voteComment(c.id, vote), (r, error) -> {
            if (!alive()) return;
            if (error != null) {
                showActionError(error);
                return;
            }
            c.score = r.score;
            c.userVote = r.userVote;
            adapter.notifyDataSetChanged();
        });
    }

    private void showActionError(Exception error) {
        if (error instanceof Api.ApiException && ((Api.ApiException) error).captcha) {
            new AlertDialog.Builder(this)
                    .setTitle("Проверка «я не робот»")
                    .setMessage("Сайт просит подтвердить, что вы не бот. Откройте пост на сайте, пройдите проверку и повторите действие.")
                    .setPositiveButton("Открыть", (d, w) -> WebActivity.open(this, url(), null))
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        Ui.toast(this, Ui.errorText(this, error));
    }

    private boolean requireLogin(String why) {
        if (Session.loggedIn()) return true;
        new AlertDialog.Builder(this)
                .setTitle("Нужен вход")
                .setMessage(why)
                .setPositiveButton("Войти через Twitch", (d, w) -> WebActivity.login(this))
                .setNegativeButton("Отмена", null)
                .show();
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WebActivity.REQUEST_LOGIN) {
            updateComposer();
            if (resultCode == RESULT_OK) load();
        }
        if (requestCode == MediaActivity.REQUEST_VIDEO && fullscreenPlayer != null && data != null) {
            fullscreenPlayer.startAt(data.getIntExtra(MediaActivity.EXTRA_POSITION, 0));
        }
    }

    // ---- comments ----

    private void setSort(String value) {
        if (value.equals(sort)) return;
        sort = value;
        updateSortChips();
        loadComments();
    }

    private void updateSortChips() {
        styleChip(sortBest, "best".equals(sort));
        styleChip(sortNew, "new".equals(sort));
    }

    private static void styleChip(TextView chip, boolean on) {
        chip.setTextColor(on ? 0xFF111111 : Ui.INK);
        chip.setBackground(Ui.ripple(on ? Ui.round(Ui.BRAND_LIGHT, 18, 0) : Ui.round(Ui.SURFACE, 18, Ui.LINE_STRONG)));
    }

    private void buildComposer() {
        LinearLayout bottom = Ui.column(this);
        bottom.setBackgroundColor(Ui.SURFACE_RAISED);
        bottom.addView(Ui.divider(this));

        replyBar = Ui.row(this);
        replyBar.setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(4), 0);
        replyLabel = Ui.text(this, 16, Ui.BRAND_LIGHT);
        replyBar.addView(replyLabel, Ui.lpWeight());
        ImageView cancel = Ui.iconButton(this, R.drawable.ic_close, Ui.INK2, "Отменить ответ");
        cancel.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)));
        cancel.setOnClickListener(v -> setReply(null));
        replyBar.addView(cancel);
        replyBar.setVisibility(View.GONE);
        bottom.addView(replyBar);

        composer = Ui.row(this);
        composer.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(4), Ui.dp(8));
        input = new EditText(this);
        input.setTypeface(Ui.font);
        input.setTextSize(18);
        input.setTextColor(Ui.INK);
        input.setHintTextColor(Ui.INK3);
        input.setHint("Написать комментарий…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(5);
        input.setBackground(Ui.round(Ui.INPUT, 20, Ui.LINE_STRONG));
        input.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        composer.addView(input, Ui.lpWeight());
        sendButton = Ui.iconButton(this, R.drawable.ic_send, Ui.BRAND_LIGHT, "Отправить");
        sendButton.setOnClickListener(v -> sendComment());
        composer.addView(sendButton);
        bottom.addView(composer);

        loginBar = Ui.text(this, "Войдите, чтобы комментировать и оценивать", 18, Ui.INK);
        loginBar.setGravity(Gravity.CENTER);
        loginBar.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        loginBar.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_twitch, 0, 0, 0);
        loginBar.setCompoundDrawablePadding(Ui.dp(10));
        loginBar.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.TWITCH));
        loginBar.setBackground(Ui.ripple(null));
        loginBar.setOnClickListener(v -> WebActivity.login(this));
        bottom.addView(loginBar);
        page.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        updateComposer();
    }

    private void updateComposer() {
        boolean in = Session.loggedIn();
        composer.setVisibility(in ? View.VISIBLE : View.GONE);
        loginBar.setVisibility(in || !Session.known() ? View.GONE : View.VISIBLE);
        if (!in) replyBar.setVisibility(View.GONE);
    }

    private void setReply(Models.Comment c) {
        replyTo = c == null ? null : c.id;
        replyBar.setVisibility(c == null ? View.GONE : View.VISIBLE);
        if (c != null) {
            replyLabel.setText("Ответ " + c.authorName);
            input.requestFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(input, 0);
        }
    }

    private void sendComment() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || sending) return;
        if (!requireLogin("Войдите, чтобы комментировать")) return;
        sending = true;
        sendButton.setEnabled(false);
        final Long parent = replyTo;
        Http.run(() -> Api.addComment(code, text, parent), (comment, error) -> {
            if (!alive()) return;
            sending = false;
            sendButton.setEnabled(true);
            if (error != null) {
                showActionError(error);
                return;
            }
            input.setText("");
            setReply(null);
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            Ui.toast(this, "Комментарий опубликован");
            loadComments();
        });
    }

    private final class CommentsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return flat.size();
        }

        @Override
        public Object getItem(int position) {
            return flat.get(position);
        }

        @Override
        public long getItemId(int position) {
            return flat.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CommentHolder h = convertView != null && convertView.getTag() instanceof CommentHolder
                    ? (CommentHolder) convertView.getTag() : new CommentHolder();
            Models.Comment c = flat.get(position);
            h.bind(c);
            return h.root;
        }
    }

    private final class CommentHolder {
        final LinearLayout root;
        final ImageView avatar;
        final TextView name;
        final ImageView badge;
        final TextView meta;
        final TextView content;
        final TextView score;
        final ImageView up;
        final ImageView down;
        final TextView reply;

        CommentHolder() {
            root = Ui.row(PostActivity.this);
            root.setGravity(Gravity.TOP);
            avatar = Ui.avatar(PostActivity.this, 32);
            root.addView(avatar);
            LinearLayout col = Ui.column(PostActivity.this);
            col.setPadding(Ui.dp(10), 0, 0, 0);
            LinearLayout top = Ui.row(PostActivity.this);
            name = Ui.text(PostActivity.this, 17, Ui.INK2);
            top.addView(name);
            badge = Ui.icon(PostActivity.this, R.drawable.ic_person, 16, 0);
            badge.setImageTintList(null);
            top.addView(badge, Ui.margins(Ui.lp(Ui.dp(16), Ui.dp(16)), 5, 0, 0, 0));
            meta = Ui.text(PostActivity.this, 14, Ui.INK3);
            meta.setPadding(Ui.dp(8), 0, 0, 0);
            top.addView(meta);
            col.addView(top);
            content = Ui.text(PostActivity.this, 18, Ui.INK);
            col.addView(content, Ui.margins(Ui.lp(-1, -2), 0, 4, 0, 0));
            LinearLayout actions = Ui.row(PostActivity.this);
            up = Ui.iconButton(PostActivity.this, R.drawable.ic_up, Ui.INK3, "Нравится");
            up.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)));
            score = Ui.text(PostActivity.this, 16, Ui.INK2);
            score.setMinWidth(Ui.dp(18));
            score.setGravity(Gravity.CENTER);
            down = Ui.iconButton(PostActivity.this, R.drawable.ic_down, Ui.INK3, "Не нравится");
            down.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)));
            reply = Ui.text(PostActivity.this, "Ответить", 16, Ui.INK3);
            reply.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
            reply.setBackground(Ui.ripple(null));
            actions.addView(up);
            actions.addView(score);
            actions.addView(down);
            actions.addView(reply);
            col.addView(actions);
            root.addView(col, Ui.lpWeight());
            root.setTag(this);
        }

        void bind(Models.Comment c) {
            root.setPadding(Ui.dp(16 + c.depth * 22), Ui.dp(10), Ui.dp(12), 0);
            Images.load(avatar, c.authorAvatar, Ui.dp(32));
            name.setText(c.authorName.isEmpty() ? "Аноним" : c.authorName);
            badge.setVisibility(c.badgeIcon == null ? View.GONE : View.VISIBLE);
            Images.load(badge, c.badgeIcon, Ui.dp(16));
            meta.setText((c.id == pinnedId ? "закреплён · " : "") + Ui.time(c.created));
            if (c.deleted) {
                content.setText("Комментарий удалён");
                content.setTextColor(Ui.INK3);
                content.setTypeface(Ui.font, Typeface.ITALIC);
                content.setMovementMethod(null);
            } else {
                content.setTextColor(Ui.INK);
                content.setTypeface(Ui.font);
                Ui.setRichText(content, c.content, PostActivity.this);
            }
            score.setText(Ui.count(c.score));
            up.setImageTintList(android.content.res.ColorStateList.valueOf("up".equals(c.userVote) ? Ui.BRAND_LIGHT : Ui.INK3));
            down.setImageTintList(android.content.res.ColorStateList.valueOf("down".equals(c.userVote) ? Ui.DANGER : Ui.INK3));
            up.setOnClickListener(v -> voteComment(c, "up"));
            down.setOnClickListener(v -> voteComment(c, "down"));
            reply.setVisibility(c.deleted ? View.GONE : View.VISIBLE);
            reply.setOnClickListener(v -> {
                if (requireLogin("Войдите, чтобы отвечать на комментарии")) setReply(c);
            });
            View.OnClickListener profile = v -> ProfileActivity.start(PostActivity.this, c.authorLogin);
            avatar.setOnClickListener(profile);
            name.setOnClickListener(profile);
        }
    }
}
