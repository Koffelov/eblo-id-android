package io.github.koffelov.ebloclient;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who is logged in. eblo.id has no session API, so this reads the values every page of the site
 * embeds for its own scripts: IS_AUTHENTICATED, CURRENT_USER_LOGIN and __CSRF_TOKEN.
 */
final class Session {
    private static final Pattern AUTH = Pattern.compile("window\\.IS_AUTHENTICATED=\\((true|false)\\)");
    private static final Pattern LOGIN =
            Pattern.compile("window\\.CURRENT_USER_LOGIN=\\((null|\"(?:[^\"\\\\]|\\\\.)*\")\\)");
    private static final Pattern CSRF =
            Pattern.compile("window\\.__CSRF_TOKEN=\\((\"(?:[^\"\\\\]|\\\\.)*\")\\)");
    private static final Pattern TURNSTILE_KEY = Pattern.compile("window\\.TURNSTILE_SITE_KEY=\\(\"([^\"]+)\"\\)");
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    /** Bumped after the user changes their avatar, so cached copies are not shown. */
    static volatile long avatarVersion;
    /** Cloudflare Turnstile site key the site embeds in every page (fallback: the current one). */
    static volatile String turnstileKey = "0x4AAAAAACcl-P8-SugE5N-9";

    private static volatile boolean known;
    private static volatile boolean authenticated;
    private static volatile String login;
    private static volatile String csrf = "";

    private Session() {
    }

    static boolean known() {
        return known;
    }

    static boolean loggedIn() {
        return authenticated && login != null;
    }

    static String login() {
        return login;
    }

    static String csrf() {
        return csrf == null ? "" : csrf;
    }

    static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** Fetches the home page and updates the session. Call off the main thread. */
    static void refreshBlocking() throws IOException {
        Http.Response r = Http.get("/");
        if (r.code >= 400) throw new IOException("HTTP " + r.code);
        update(r.body);
    }

    static void refresh(Runnable whenDone) {
        Http.run(() -> {
            refreshBlocking();
            return Boolean.TRUE;
        }, (ok, error) -> {
            if (whenDone != null) whenDone.run();
        });
    }

    /** Updates the session from any HTML page of the site. Safe to call from any thread. */
    static void update(String html) {
        if (html == null) return;
        Matcher auth = AUTH.matcher(html);
        if (!auth.find()) return;
        boolean wasLoggedIn = loggedIn();
        String oldLogin = login;
        authenticated = "true".equals(auth.group(1));
        Matcher l = LOGIN.matcher(html);
        login = l.find() ? jsonString(l.group(1)) : null;
        Matcher c = CSRF.matcher(html);
        if (c.find()) csrf = jsonString(c.group(1));
        Matcher t = TURNSTILE_KEY.matcher(html);
        if (t.find()) turnstileKey = t.group(1);
        boolean changed = !known || wasLoggedIn != loggedIn()
                || (oldLogin == null ? login != null : !oldLogin.equals(login));
        known = true;
        if (changed) Http.MAIN.post(() -> {
            for (Runnable r : new ArrayList<>(LISTENERS)) r.run();
        });
    }

    private static String jsonString(String literal) {
        if (literal == null || "null".equals(literal)) return null;
        try {
            String s = new JSONArray("[" + literal + "]").getString(0);
            return s.isEmpty() ? null : s;
        } catch (JSONException e) {
            return null;
        }
    }
}
