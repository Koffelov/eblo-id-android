package io.github.koffelov.ebloclient;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Full-screen viewer: zoomable images (animated GIF/WebP on Android 9+) or a video player. */
public class MediaActivity extends BaseActivity {
    private static final String EXTRA_IMAGES = "images";
    private static final String EXTRA_INDEX = "index";
    private static final String EXTRA_VIDEO = "video";
    private static final String EXTRA_TITLE = "title";

    private List<String> images;
    private int index;
    private ZoomImageView image;
    private ProgressBar spinner;
    private TextView counter;
    private LinearLayout bar;
    private VideoView video;
    private int loadGeneration;

    static void images(Context c, List<String> urls, int index) {
        c.startActivity(new Intent(c, MediaActivity.class)
                .putStringArrayListExtra(EXTRA_IMAGES, new ArrayList<>(urls))
                .putExtra(EXTRA_INDEX, index));
    }

    static void video(Context c, String url, String title) {
        c.startActivity(new Intent(c, MediaActivity.class).putExtra(EXTRA_VIDEO, url).putExtra(EXTRA_TITLE, title));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root.setBackgroundColor(Color.BLACK);
        page.setVisibility(View.GONE);
        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, 0, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.BRAND_LIGHT));

        bar = Ui.row(this);
        bar.setBackgroundColor(0x88000000);
        ImageView close = Ui.iconButton(this, R.drawable.ic_close, Color.WHITE, "Закрыть");
        close.setOnClickListener(v -> finish());
        bar.addView(close);
        counter = Ui.text(this, 20, Color.WHITE);
        bar.addView(counter, Ui.lpWeight());

        String videoUrl = getIntent().getStringExtra(EXTRA_VIDEO);
        if (videoUrl != null) {
            video = new VideoView(this);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(video);
            video.setMediaController(controller);
            video.setVideoURI(Uri.parse(videoUrl));
            video.setOnPreparedListener(mp -> {
                spinner.setVisibility(View.GONE);
                mp.setLooping(true);
                video.start();
                controller.show(2500);
            });
            video.setOnErrorListener((mp, what, extra) -> {
                spinner.setVisibility(View.GONE);
                Ui.toast(this, "Не удалось воспроизвести видео");
                return true;
            });
            stage.addView(video, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            counter.setText(getIntent().getStringExtra(EXTRA_TITLE));
            ImageView download = Ui.iconButton(this, R.drawable.ic_download, Color.WHITE, "Скачать");
            download.setOnClickListener(v -> WebActivity.download(this, videoUrl, null, "video/mp4", null));
            bar.addView(download);
        } else {
            images = getIntent().getStringArrayListExtra(EXTRA_IMAGES);
            if (images == null) images = new ArrayList<>();
            index = getIntent().getIntExtra(EXTRA_INDEX, 0);
            image = new ZoomImageView(this);
            image.setSwipeListener(direction -> show(index + direction));
            image.setTapListener(() -> bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            stage.addView(image, new FrameLayout.LayoutParams(-1, -1));
            ImageView download = Ui.iconButton(this, R.drawable.ic_download, Color.WHITE, "Скачать");
            download.setOnClickListener(v -> {
                if (index < images.size()) WebActivity.download(this, images.get(index), null, null, null);
            });
            bar.addView(download);
            show(index);
        }
        stage.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(48), Ui.dp(48), Gravity.CENTER));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(bar, barLp);
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
        if (video != null) video.pause();
    }
}
