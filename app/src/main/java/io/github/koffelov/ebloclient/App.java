package io.github.koffelov.ebloclient;

import android.app.Application;
import android.net.http.HttpResponseCache;
import android.webkit.CookieManager;

import java.io.File;
import java.io.IOException;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // Disk cache for images and media thumbnails (API JSON requests bypass it).
            HttpResponseCache.install(new File(getCacheDir(), "http"), 64L * 1024 * 1024);
        } catch (IOException ignored) {
            // Works without a disk cache, just slower.
        }
        // Native requests and the embedded web screens share one cookie jar, so the session
        // created by the Twitch login in a web screen is used by the native screens too.
        CookieManager.getInstance().setAcceptCookie(true);
        Http.init(this);
        Ui.init(this);
    }
}
