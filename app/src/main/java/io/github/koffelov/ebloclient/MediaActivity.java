package io.github.koffelov.ebloclient;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Full-screen viewer: zoomable images (animated GIF/WebP on Android 9+) or the video player. */
public class MediaActivity extends BaseActivity {
    static final String EXTRA_POSITION = "position";
    static final int REQUEST_VIDEO = 52;
    private static final String EXTRA_IMAGES = "images";
    private static final String EXTRA_INDEX = "index";
    private static final String EXTRA_VIDEO = "video";
    private static final String EXTRA_POSTER = "poster";
    private static final String EXTRA_TITLE = "title";

    private List<String> images;
    private int index;
    private ZoomImageView image;
    private ProgressBar spinner;
    private TextView counter;
    private LinearLayout bar;
    private VideoPlayer player;
    private int loadGeneration;

    static void images(Context c, List<String> urls, int index) {
        c.startActivity(new Intent(c, MediaActivity.class)
                .putStringArrayListExtra(EXTRA_IMAGES, new ArrayList<>(urls))
                .putExtra(EXTRA_INDEX, index));
    }

    static void video(Activity a, String url, String poster, String title, int positionMs) {
        a.startActivityForResult(new Intent(a, MediaActivity.class).putExtra(EXTRA_VIDEO, url)
                .putExtra(EXTRA_POSTER, poster).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_POSITION, positionMs), REQUEST_VIDEO);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root.setBackgroundColor(Color.BLACK);
        page.setVisibility(View.GONE);
        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, 0, new FrameLayout.LayoutParams(-1, -1));

        bar = Ui.row(this);
        ImageView close = Ui.iconButton(this, R.drawable.ic_back, Color.WHITE, "Закрыть");
        close.setOnClickListener(v -> finishWithPosition());
        bar.addView(close);
        counter = Ui.text(this, 20, Color.WHITE);
        counter.setSingleLine(true);
        bar.addView(counter, Ui.lpWeight());

        String videoUrl = getIntent().getStringExtra(EXTRA_VIDEO);
        if (videoUrl != null) {
            hideSystemBars();
            counter.setText(getIntent().getStringExtra(EXTRA_TITLE));
            player = new VideoPlayer(this, videoUrl, getIntent().getStringExtra(EXTRA_POSTER), true,
                    new VideoPlayer.Listener() {
                        @Override
                        public void onAspect(float heightToWidth) {
                        }

                        @Override
                        public void onFullscreen(int positionMs) {
                            finishWithPosition();
                        }

                        @Override
                        public void onPlay(VideoPlayer p) {
                        }
                    });
            player.startAt(getIntent().getIntExtra(EXTRA_POSITION, 0));
            stage.addView(player, new FrameLayout.LayoutParams(-1, -1));
            ImageView download = Ui.iconButton(this, R.drawable.ic_download, Color.WHITE, "Скачать");
            download.setOnClickListener(v -> WebActivity.download(this, videoUrl, null, "video/mp4", null));
            bar.addView(download);
            player.play();
        } else {
            spinner = new ProgressBar(this);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.BRAND_LIGHT));
            images = getIntent().getStringArrayListExtra(EXTRA_IMAGES);
            if (images == null) images = new ArrayList<>();
            index = getIntent().getIntExtra(EXTRA_INDEX, 0);
            image = new ZoomImageView(this);
            image.setSwipeListener(direction -> show(index + direction));
            image.setTapListener(() -> bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            stage.addView(image, new FrameLayout.LayoutParams(-1, -1));
            stage.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(48), Ui.dp(48), Gravity.CENTER));
            bar.setBackgroundColor(0x88000000);
            ImageView download = Ui.iconButton(this, R.drawable.ic_download, Color.WHITE, "Скачать");
            download.setOnClickListener(v -> {
                if (index < images.size()) WebActivity.download(this, images.get(index), null, null, null);
            });
            bar.addView(download);
            show(index);
        }
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        setFullBleed(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void finishWithPosition() {
        if (player != null) setResult(RESULT_OK, new Intent().putExtra(EXTRA_POSITION, player.position()));
        finish();
    }

    @Override
    protected boolean handleBack() {
        finishWithPosition();
        return true;
    }

    @Override
    protected boolean wantsBack() {
        return true;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        updateBackCallback();
    }

    private void show(int i) {
        if (images.isEmpty() || i < 0 || i >= images.size()) return;
        index = i;
        counter.setText(images.size() > 1 ? (i + 1) + " / " + images.size() : "");
        image.setImageDrawable(null);
        spinner.setVisibility(View.VISIBLE);
        final int gen = ++loadGeneration;
        final String url = images.get(i);
        Http.run(() -> decode(url), (drawable, error) -> {
            if (!alive() || gen != loadGeneration) return;
            spinner.setVisibility(View.GONE);
            if (drawable == null) {
                Ui.toast(this, "Не удалось загрузить изображение");
                return;
            }
            image.setImageDrawable(drawable);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) startIfAnimated(drawable);
        });
    }

    private Drawable decode(String url) throws Exception {
        byte[] data = Images.download(url);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return decodeApi28(data);
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
        return b == null ? null : new android.graphics.drawable.BitmapDrawable(getResources(), b);
    }

    @TargetApi(Build.VERSION_CODES.P)
    private Drawable decodeApi28(byte[] data) throws Exception {
        ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(data));
        return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
            int max = 4096;
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            if (w > max || h > max) {
                float s = Math.min(max / (float) w, max / (float) h);
                decoder.setTargetSize(Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)));
            }
        });
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static void startIfAnimated(Drawable d) {
        if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        if (player != null) player.release();
        super.onDestroy();
    }
}
