package io.github.koffelov.ebloclient;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTP to eblo.id with the WebView's cookies, so native screens and web screens share a session. */
final class Http {
    static final String BASE = "https://eblo.id";
    static final ExecutorService IO = Executors.newFixedThreadPool(4);
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static String userAgent = "Mozilla/5.0 (Linux; Android) eblo.id unofficial client";

    interface Callback<T> {
        void done(T result, Exception error);
    }

    static final class Response {
        int code;
        String body;
        String location;
        final Map<String, String> headers = new java.util.HashMap<>();

        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private Http() {
    }

    static void init(Context context) {
        try {
            userAgent = WebSettings.getDefaultUserAgent(context);
        } catch (RuntimeException ignored) {
            // Keep the fallback user agent.
        }
    }

    static String userAgent() {
        return userAgent;
    }

    static String absolute(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isEmpty()) return null;
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl;
        if (pathOrUrl.startsWith("//")) return "https:" + pathOrUrl;
        return BASE + (pathOrUrl.startsWith("/") ? "" : "/") + pathOrUrl;
    }

    /** Runs work on the IO pool and delivers the result on the main thread. */
    static <T> void run(Callable<T> work, Callback<T> callback) {
        IO.execute(() -> {
            T result = null;
            Exception error = null;
            try {
                result = work.call();
            } catch (Exception e) {
                error = e;
            }
            final T r = result;
            final Exception e = error;
            MAIN.post(() -> callback.done(r, e));
        });
    }

    static Response get(String path) throws IOException {
        return request("GET", path, null, null);
    }

    static Response request(String method, String path, String contentType, byte[] body)
            throws IOException {
        return request(method, path, contentType, body, null);
    }

    static Response request(String method, String path, String contentType, byte[] body,
                            Map<String, String> extraHeaders) throws IOException {
        String url = absolute(path);
        String currentMethod = method;
        byte[] currentBody = body;
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestMethod(currentMethod);
                c.setRequestProperty("User-Agent", userAgent);
                c.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8");
                c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7");
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
                if (!"GET".equals(currentMethod)) {
                    // Same headers the site's own scripts send with state-changing requests.
                    c.setRequestProperty("X-CSRF-Token", Session.csrf());
                    c.setRequestProperty("Origin", BASE);
                    c.setRequestProperty("Referer", BASE + "/");
                }
                if (extraHeaders != null) {
                    for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                        c.setRequestProperty(h.getKey(), h.getValue());
                    }
                }
                if (currentBody != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", contentType);
                    c.setFixedLengthStreamingMode(currentBody.length);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(currentBody);
                    }
                }
                int code = c.getResponseCode();
                storeCookies(url, c.getHeaderFields());
                String location = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && location != null && redirects < 5) {
                    url = new URL(new URL(url), location).toString();
                    if (code != 307 && code != 308) {
                        currentMethod = "GET";
                        currentBody = null;
                    }
                    continue;
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                Response r = new Response();
                r.code = code;
                r.location = url;
                for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                    if (h.getKey() != null && !h.getValue().isEmpty()) {
                        r.headers.put(h.getKey().toLowerCase(java.util.Locale.ROOT), h.getValue().get(0));
                    }
                }
                r.body = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
                return r;
            } finally {
                c.disconnect();
            }
        }
    }

    private static void storeCookies(String url, Map<String, List<String>> headers) {
        if (headers == null) return;
        CookieManager cm = CookieManager.getInstance();
        boolean changed = false;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() == null || !"set-cookie".equalsIgnoreCase(e.getKey())) continue;
            for (String value : e.getValue()) {
                cm.setCookie(url, value);
                changed = true;
            }
        }
        if (changed) cm.flush();
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = input.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
