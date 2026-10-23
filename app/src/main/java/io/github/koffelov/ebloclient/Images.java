package io.github.koffelov.ebloclient;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small image loader: memory LRU + the HTTP disk cache installed in {@link App}. */
final class Images {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> MEMORY =
            new LruCache<String, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 6, 96L << 20)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getAllocationByteCount();
                }
            };

    interface Listener {
        void loaded(Bitmap bitmap);
    }

    private Images() {
    }

    /** Loads url into view, decoded to about targetWidth pixels; safe with recycled list rows. */
    static void load(ImageView view, String url, int targetWidth) {
        load(view, url, targetWidth, null);
    }

    static void load(ImageView view, String url, int targetWidth, Listener listener) {
        view.setTag(R.id.image_url, url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        String key = url + "#" + targetWidth;
        Bitmap cached = MEMORY.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            if (listener != null) listener.loaded(cached);
            return;
        }
        view.setImageDrawable(null);
        POOL.execute(() -> {
            Bitmap bitmap = fetch(url, targetWidth);
            if (bitmap != null) MEMORY.put(key, bitmap);
            Http.MAIN.post(() -> {
                if (!url.equals(view.getTag(R.id.image_url))) return;
                if (bitmap == null) return;
                view.setAlpha(0f);
                view.setImageBitmap(bitmap);
                view.animate().alpha(1f).setDuration(150).start();
                if (listener != null) listener.loaded(bitmap);
            });
        });
    }

    static byte[] download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setUseCaches(true);
            c.setRequestProperty("User-Agent", Http.userAgent());
            if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
            return Http.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    static Bitmap fetch(String url, int targetWidth) {
        try {
            byte[] data = download(url);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            if (targetWidth > 0) {
                while (bounds.outWidth / (sample * 2) >= targetWidth
                        && bounds.outHeight / (sample * 2) >= targetWidth / 4) {
                    sample *= 2;
                }
            }
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
