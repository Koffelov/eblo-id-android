package io.github.koffelov.ebloclient;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

/**
 * Video player with the app's own controls: poster, big play button, green seek bar, time,
 * buffering spinner, double-tap ±10 s and a fullscreen button. Used inline and fullscreen.
 */
final class VideoPlayer extends FrameLayout {
    interface Listener {
        void onAspect(float heightToWidth);

        void onFullscreen(int positionMs);

        void onPlay(VideoPlayer player);
    }

    private final String url;
    private final VideoView video;
    private final ImageView poster;
    private final ImageView bigButton;
    private final ProgressBar buffering;
    private final LinearLayout controls;
    private final ImageView playPause;
    private final TextView time;
    private final SeekBar seek;
    private final TextView skipHint;
    private final Listener listener;
    private MediaPlayer player;
    private boolean loaded;
    private boolean prepared;
    private boolean completed;
    private boolean dragging;
    private int startAt;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (video.isPlaying()) postDelayed(this, 250);
        }
    };
    private final Runnable hideControls = () -> {
        if (video.isPlaying() && !dragging) setControlsVisible(false);
    };

    VideoPlayer(Context c, String url, String posterUrl, boolean fullscreen, Listener listener) {
        super(c);
        this.url = url;
        this.listener = listener;
        setBackgroundColor(Color.BLACK);

        video = new VideoView(c);
        addView(video, new LayoutParams(-1, -1, Gravity.CENTER));
        poster = new ImageView(c);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Images.load(poster, posterUrl, getResources().getDisplayMetrics().widthPixels);
        addView(poster, new LayoutParams(-1, -1));

        buffering = new ProgressBar(c);
        buffering.setIndeterminateTintList(ColorStateList.valueOf(Ui.BRAND_LIGHT));
        buffering.setVisibility(GONE);
        addView(buffering, new LayoutParams(Ui.dp(48), Ui.dp(48), Gravity.CENTER));

        bigButton = new ImageView(c);
        bigButton.setImageResource(R.drawable.ic_play);
        bigButton.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        bigButton.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x99000000);
        circle.setStroke(Ui.dp(2), 0x55FFFFFF);
        bigButton.setBackground(circle);
        bigButton.setScaleX(1.6f);
        bigButton.setScaleY(1.6f);
        bigButton.setContentDescription("Воспроизвести");
        bigButton.setOnClickListener(v -> toggle());
        addView(bigButton, new LayoutParams(Ui.dp(46), Ui.dp(46), Gravity.CENTER));

        skipHint = Ui.text(c, 20, Color.WHITE);
        skipHint.setBackground(Ui.round(0x99000000, 18, 0));
        skipHint.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6));
        skipHint.setAlpha(0f);
        addView(skipHint, new LayoutParams(-2, -2, Gravity.CENTER));

        controls = Ui.row(c);
        controls.setPadding(Ui.dp(4), Ui.dp(18), Ui.dp(4), Ui.dp(fullscreen ? 10 : 2));
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xCC000000, 0x00000000});
        controls.setBackground(shade);
        playPause = Ui.iconButton(c, R.drawable.ic_play, Color.WHITE, "Воспроизвести");
        playPause.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(42), Ui.dp(42)));
        playPause.setOnClickListener(v -> toggle());
        controls.addView(playPause);
        seek = new SeekBar(c);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.BRAND_LIGHT));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.BRAND_LIGHT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && prepared) time.setText(format(progress * (long) video.getDuration() / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
                removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (prepared) video.seekTo((int) (bar.getProgress() * (long) video.getDuration() / 1000));
                scheduleHide();
            }
        });
        controls.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        time = Ui.text(c, "0:00", 15, Color.WHITE);
        time.setPadding(Ui.dp(2), 0, Ui.dp(6), 0);
        controls.addView(time);
        ImageView full = Ui.iconButton(c, fullscreen ? R.drawable.ic_close : R.drawable.ic_open, Color.WHITE,
                fullscreen ? "Свернуть" : "На весь экран");
        full.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(42), Ui.dp(42)));
        full.setOnClickListener(v -> {
            int pos = prepared ? video.getCurrentPosition() : startAt;
            pause();
            if (listener != null) listener.onFullscreen(pos);
        });
        controls.addView(full);
        controls.setVisibility(GONE);
        addView(controls, new LayoutParams(-1, -2, Gravity.BOTTOM));

        GestureDetector gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!loaded) toggle();
                else setControlsVisible(controls.getVisibility() != VISIBLE);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!prepared) return false;
                int delta = e.getX() < getWidth() / 2f ? -10000 : 10000;
                int target = Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + delta));
                video.seekTo(target);
                skipHint.setText(delta < 0 ? "« 10 с" : "10 с »");
                skipHint.animate().cancel();
                skipHint.setAlpha(1f);
                skipHint.animate().alpha(0f).setStartDelay(500).setDuration(300).start();
                updateProgress();
                return true;
            }
        });
        setOnTouchListener((v, ev) -> gestures.onTouchEvent(ev));

        video.setOnPreparedListener(mp -> {
            player = mp;
            prepared = true;
            buffering.setVisibility(GONE);
            if (mp.getVideoWidth() > 0 && listener != null) {
                listener.onAspect(mp.getVideoHeight() / (float) mp.getVideoWidth());
            }
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) buffering.setVisibility(VISIBLE);
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) buffering.setVisibility(GONE);
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) poster.setVisibility(GONE);
                return false;
            });
            if (startAt > 0) video.seekTo(startAt);
            play();
        });
        video.setOnCompletionListener(mp -> {
            completed = true;
            updateButtons();
            setControlsVisible(true);
        });
        video.setOnErrorListener((mp, what, extra) -> {
            buffering.setVisibility(GONE);
            Ui.toast(getContext(), "Не удалось воспроизвести видео");
            return true;
        });
    }

    /** Starts playing (loads the stream on first use). */
    void play() {
        if (!loaded) {
            loaded = true;
            buffering.setVisibility(VISIBLE);
            bigButton.setVisibility(GONE);
            video.setVideoURI(Uri.parse(url));
            return;
        }
        if (!prepared) return;
        if (completed) {
            completed = false;
            video.seekTo(0);
        }
        if (listener != null) listener.onPlay(this);
        video.start();
        poster.postDelayed(() -> poster.setVisibility(GONE), 300);
        updateButtons();
        removeCallbacks(ticker);
        post(ticker);
        setControlsVisible(true);
        scheduleHide();
    }

    void pause() {
        if (prepared && video.isPlaying()) video.pause();
        updateButtons();
        setControlsVisible(loaded);
    }

    void startAt(int positionMs) {
        startAt = positionMs;
        if (prepared) video.seekTo(positionMs);
    }

    int position() {
        return prepared ? video.getCurrentPosition() : startAt;
    }

    void release() {
        removeCallbacks(ticker);
        removeCallbacks(hideControls);
        video.stopPlayback();
    }

    private void toggle() {
        if (prepared && video.isPlaying()) pause();
        else play();
    }

    private void updateButtons() {
        boolean playing = prepared && video.isPlaying();
        playPause.setImageResource(playing ? R.drawable.ic_pause : completed ? R.drawable.ic_refresh : R.drawable.ic_play);
        playPause.setContentDescription(playing ? "Пауза" : "Воспроизвести");
        bigButton.setImageResource(completed ? R.drawable.ic_refresh : R.drawable.ic_play);
        bigButton.setVisibility(playing || (loaded && !prepared) ? GONE : VISIBLE);
    }

    private void setControlsVisible(boolean visible) {
        controls.setVisibility(visible && loaded ? VISIBLE : GONE);
        if (visible) updateProgress();
    }

    private void scheduleHide() {
        removeCallbacks(hideControls);
        postDelayed(hideControls, 2800);
    }

    private void updateProgress() {
        if (!prepared) return;
        int duration = video.getDuration();
        int position = video.getCurrentPosition();
        if (!dragging && duration > 0) seek.setProgress((int) (position * 1000L / duration));
        seek.setSecondaryProgress(video.getBufferPercentage() * 10);
        if (!dragging) time.setText(format(position) + " / " + format(duration));
    }

    private static String format(long ms) {
        return Ui.duration((int) (ms / 1000)).isEmpty() ? "0:00" : Ui.duration((int) (ms / 1000));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(ticker);
        removeCallbacks(hideControls);
    }
}
