package io.github.koffelov.ebloclient;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Opens eblo.id links in the matching native screen; everything else goes to other apps. */
final class Router {
    private static final Pattern POST = Pattern.compile("^/([A-Za-z0-9]{7})/?$");
    private static final Set<String> NOT_POSTS = new HashSet<>(Arrays.asList(
            "privacy", "consent", "uploads", "sitemap", "uploader", "account"));

    private Router() {
    }

    static boolean isSite(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("eblo.id") || host.equals("www.eblo.id");
    }

    static String postCode(Uri uri) {
        if (!isSite(uri) || uri.getPath() == null) return null;
        java.util.regex.Matcher m = POST.matcher(uri.getPath());
        if (!m.find() || NOT_POSTS.contains(m.group(1).toLowerCase(Locale.ROOT))) return null;
        return m.group(1);
    }

    static void open(Context c, String url) {
        if (url == null || url.isEmpty()) return;
        open(c, Uri.parse(Http.absolute(url)));
    }

    static void open(Context c, Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (("https".equals(scheme) || "http".equals(scheme)) && isSite(uri)) {
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            String code = postCode(uri);
            if (code != null) {
                PostActivity.start(c, code);
            } else if (path.startsWith("/@") && path.length() > 2) {
                ProfileActivity.start(c, path.substring(2).replaceAll("/+$", ""));
            } else if ("/".equals(path) && uri.getQueryParameter("tag") != null) {
                FeedActivity.startTag(c, uri.getQueryParameter("tag"));
            } else if ("/".equals(path)) {
                Intent i = new Intent(c, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                if ("following".equals(uri.getQueryParameter("scope"))) i.putExtra(MainActivity.EXTRA_FOLLOWING, true);
                c.startActivity(i);
            } else if ("/search".equals(path)) {
                SearchActivity.start(c, uri.getQueryParameter("q"));
            } else if ("/new".equals(path)) {
                NewPostActivity.start(c);
            } else if ("/videos".equals(path)) {
                Intent i = new Intent(c, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                c.startActivity(i.putExtra(MainActivity.EXTRA_VIDEOS, true));
            } else {
                WebActivity.open(c, uri.buildUpon().scheme("https").build().toString(), null);
            }
            return;
        }
        openExternal(c, uri);
    }

    static void openExternal(Context c, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        if (!(c instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Ui.toast(c, "Нет приложения, чтобы открыть эту ссылку");
        }
    }

    static void share(Context c, String title, String url) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, title);
        send.putExtra(Intent.EXTRA_TEXT, title == null || title.isEmpty() ? url : title + "\n" + url);
        try {
            c.startActivity(Intent.createChooser(send, "Поделиться"));
        } catch (ActivityNotFoundException ignored) {
            // Nothing can share text; ignore.
        }
    }
}
