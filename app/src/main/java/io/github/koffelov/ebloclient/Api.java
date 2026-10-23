package io.github.koffelov.ebloclient;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * eblo.id endpoints. Only /api/public/* is documented; the rest are the JSON endpoints the site's
 * own pages call. If they change, screens show an error with "Open on the site" instead.
 */
final class Api {
    private Api() {
    }

    static final class ApiException extends IOException {
        final int code;
        final boolean captcha;

        ApiException(int code, String message, boolean captcha) {
            super(message == null || message.isEmpty() ? "HTTP " + code : message);
            this.code = code;
            this.captcha = captcha;
        }
    }

    static final class VoteResult {
        int score;
        String userVote;
    }

    static final class FeedQuery {
        String scope = "public";
        String sort = "best";
        String type = "all";
        String time = "today";
        String tag;
        String query;
    }

    // ---- plumbing ----

    static JSONObject getJson(String path) throws IOException, JSONException {
        return parse(Http.get(path));
    }

    static JSONObject postJson(String path, JSONObject body) throws IOException, JSONException {
        byte[] bytes = (body == null ? "{}" : body.toString()).getBytes(StandardCharsets.UTF_8);
        return parse(Http.request("POST", path, "application/json", bytes));
    }

    private static JSONObject parse(Http.Response r) throws IOException, JSONException {
        String text = r.body == null ? "" : r.body.trim();
        JSONObject json = text.startsWith("{") ? new JSONObject(text) : null;
        if (r.code >= 400 || json == null) {
            String message = json == null ? null : json.optString("error", null);
            boolean captcha = json != null && json.optBoolean("captcha_required");
            if (r.code == 401) message = "Нужно войти в аккаунт";
            throw new ApiException(r.code, message, captcha);
        }
        if (json.has("success") && !json.optBoolean("success")) {
            throw new ApiException(r.code, json.optString("error", "Ошибка сайта"),
                    json.optBoolean("captcha_required"));
        }
        return json;
    }

    private static String enc(String s) {
        return Uri.encode(s == null ? "" : s);
    }

    // ---- feed and posts ----

    static Models.Page<Models.Post> feed(FeedQuery q, int offset, String cursor, int limit)
            throws IOException, JSONException {
        Uri.Builder b = Uri.parse("/api/feed").buildUpon()
                .appendQueryParameter("scope", q.scope)
                .appendQueryParameter("sort", q.sort)
                .appendQueryParameter("type", q.type);
        if (!"new".equals(q.sort)) b.appendQueryParameter("time", q.time);
        if (cursor != null) b.appendQueryParameter("cursor", cursor);
        else b.appendQueryParameter("offset", String.valueOf(offset));
        b.appendQueryParameter("limit", String.valueOf(limit));
        if (q.tag != null) b.appendQueryParameter("tag", q.tag);
        if (q.query != null) b.appendQueryParameter("q", q.query);
        JSONObject json = getJson(b.build().toString());
        Models.Page<Models.Post> page = new Models.Page<>();
        JSONArray files = json.optJSONArray("files");
        if (files == null) throw new ApiException(200, "Неожиданный ответ сайта", false);
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f != null) page.items.add(Models.Post.fromFeed(f));
        }
        page.hasMore = json.optBoolean("has_more", files.length() >= limit);
        page.nextOffset = json.optInt("next_offset", offset + files.length());
        page.cursor = json.isNull("cursor") ? null : json.optString("cursor", null);
        return page;
    }

    static Models.Post post(String code) throws IOException, JSONException {
        return Models.Post.fromPublic(getJson("/api/public/post/" + enc(code)));
    }

    private static final Pattern USER_VOTE = Pattern.compile("data-user-vote=\"([a-zA-Z]*)\"");

    /** The viewer's vote is only in the post page; loading it also counts the view like the site. */
    static String userVote(String code) throws IOException {
        Http.Response r = Http.get("/" + enc(code));
        Session.update(r.body);
        Matcher m = USER_VOTE.matcher(r.body == null ? "" : r.body);
        if (!m.find()) return null;
        String v = m.group(1);
        return "up".equals(v) || "down".equals(v) ? v : null;
    }

    static VoteResult votePost(String code, String vote) throws IOException, JSONException {
        JSONObject json = postJson("/api/post/" + enc(code) + "/vote", new JSONObject().put("vote", vote));
        VoteResult r = new VoteResult();
        r.score = json.optInt("score");
        r.userVote = json.isNull("user_vote") ? null : json.optString("user_vote", null);
        return r;
    }

    static final class Comments {
        final java.util.List<Models.Comment> items = new java.util.ArrayList<>();
        long pinnedId = -1;
        int total;
    }

    static Comments comments(String code, String sort) throws IOException, JSONException {
        JSONObject json = getJson("/api/post/" + enc(code) + "/comments?sort=" + enc(sort));
        Comments c = new Comments();
        JSONArray arr = json.optJSONArray("comments");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) c.items.add(Models.Comment.from(o));
            }
        }
        c.pinnedId = json.isNull("pinned_comment_id") ? -1 : json.optLong("pinned_comment_id", -1);
        c.total = json.optInt("total", c.items.size());
        return c;
    }

    static Models.Comment addComment(String code, String content, Long parentId)
            throws IOException, JSONException {
        JSONObject body = new JSONObject().put("content", content);
        if (parentId != null) body.put("parent_id", parentId.longValue());
        JSONObject json = postJson("/api/post/" + enc(code) + "/comments", body);
        JSONObject comment = json.optJSONObject("comment");
        if (comment == null) throw new ApiException(200, "Комментарий не сохранён", false);
        return Models.Comment.from(comment);
    }

    static VoteResult voteComment(long id, String vote) throws IOException, JSONException {
        JSONObject json = postJson("/api/comment/" + id + "/vote", new JSONObject().put("vote", vote));
        VoteResult r = new VoteResult();
        r.score = json.optInt("score");
        r.userVote = json.isNull("user_vote") ? null : json.optString("user_vote", null);
        return r;
    }

    // ---- profiles ----

    private static final Pattern OG_IMAGE = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile("class=\"(?:profile-name|username)\">([^<]+)<");
    private static final Pattern FOLLOWING = Pattern.compile("data-following-count>(\\d+)<");
    private static final Pattern FOLLOWERS = Pattern.compile("data-followers-count>(\\d+)<");
    private static final Pattern POSTS = Pattern.compile("<span class=\"profile-social-stat\"><strong>(\\d+)</strong>");
    private static final Pattern BADGE = Pattern.compile("class=\"user-badge-icon user-badge-icon-profile[^\"]*\" src=\"([^\"]+)\"");
    private static final Pattern OWNER = Pattern.compile("data-is-owner=\"(true|false)\"");
    private static final Pattern FOLLOW_BTN = Pattern.compile("<(?:a|button)[^>]*profile-follow-btn[^>]*>([^<]*)<");
    private static final Pattern TWITCH = Pattern.compile("href=\"(https://(?:www\\.)?twitch\\.tv/[^\"]+)\"");
    private static final Pattern TELEGRAM = Pattern.compile("href=\"(https://t\\.me/[^\"]+)\"");

    /** The profile header exists only as HTML on /@login. */
    static Models.Profile profile(String login) throws IOException {
        Http.Response r = Http.get("/@" + enc(login));
        if (r.code == 404) throw new ApiException(404, "Пользователь не найден", false);
        if (r.code >= 400) throw new ApiException(r.code, null, false);
        String html = r.body;
        Session.update(html);
        Models.Profile p = new Models.Profile();
        p.login = login;
        p.name = group(NAME, html, login).trim();
        p.avatar = Http.absolute(htmlDecode(group(OG_IMAGE, html, null)));
        p.following = parseInt(group(FOLLOWING, html, "-1"));
        p.followers = parseInt(group(FOLLOWERS, html, "-1"));
        p.posts = parseInt(group(POSTS, html, "-1"));
        p.owner = "true".equals(group(OWNER, html, "false"));
        Matcher b = BADGE.matcher(html);
        int desktopBlockEnd = html.indexOf("class=\"mobile-only\"");
        while (b.find() && (desktopBlockEnd < 0 || b.start() < desktopBlockEnd)) {
            String url = Http.absolute(htmlDecode(b.group(1)));
            if (!p.badges.contains(url)) p.badges.add(url);
        }
        String follow = group(FOLLOW_BTN, html, "").trim().toLowerCase(java.util.Locale.ROOT);
        p.canFollow = !p.owner;
        p.isFollowing = follow.contains("отслеживаете") || follow.contains("отписаться");
        p.twitch = group(TWITCH, html, null);
        p.telegram = group(TELEGRAM, html, null);
        return p;
    }

    static Models.Page<Models.Post> uploads(String login, int page, int limit)
            throws IOException, JSONException {
        JSONObject json = getJson("/api/profile/" + enc(login) + "/uploads?page=" + page + "&limit="
                + limit + "&sort=new&time_filter=all&hidden=false");
        Models.Page<Models.Post> result = new Models.Page<>();
        JSONArray files = json.optJSONArray("files");
        if (files == null) throw new ApiException(200, "Неожиданный ответ сайта", false);
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f != null) result.items.add(Models.Post.fromFeed(f));
        }
        result.total = json.optInt("total", -1);
        result.hasMore = files.length() >= limit
                && (result.total < 0 || (long) page * limit < result.total);
        return result;
    }

    static boolean follow(String login, boolean following) throws IOException, JSONException {
        JSONObject json = postJson("/api/profile/" + enc(login) + "/follow",
                new JSONObject().put("following", following));
        return json.optBoolean("is_following", following);
    }

    // ---- notifications, search, videos ----

    static final class Notifications {
        final java.util.List<Models.Notif> items = new java.util.ArrayList<>();
        int unread;
    }

    static Notifications notifications() throws IOException, JSONException {
        JSONObject json = getJson("/api/notifications/list?limit=25");
        Notifications n = new Notifications();
        JSONArray items = json.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o != null) n.items.add(Models.Notif.from(o));
            }
        }
        n.unread = json.optInt("unread");
        return n;
    }

    static void markNotificationsOpened() throws IOException, JSONException {
        postJson("/api/notifications/opened", new JSONObject());
    }

    static void clearNotifications() throws IOException, JSONException {
        postJson("/api/notifications/clear", new JSONObject());
    }

    static final class SearchResult {
        final java.util.List<Models.User> users = new java.util.ArrayList<>();
        final java.util.List<Models.Post> posts = new java.util.ArrayList<>();
    }

    static SearchResult search(String q) throws IOException, JSONException {
        JSONObject json = getJson("/api/search/all?q=" + enc(q));
        SearchResult r = new SearchResult();
        JSONArray users = json.optJSONArray("users");
        if (users != null) {
            for (int i = 0; i < users.length(); i++) {
                JSONObject o = users.optJSONObject(i);
                if (o != null) r.users.add(Models.User.from(o));
            }
        }
        JSONArray files = json.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject o = files.optJSONObject(i);
                if (o == null) continue;
                Models.Post p = new Models.Post();
                p.code = Models.str(o, "short_code");
                String match = Models.str(o, "search_match_text");
                p.title = !Models.str(o, "title").isEmpty() ? Models.str(o, "title")
                        : !match.isEmpty() ? match : Models.str(o, "filename");
                p.thumb = Http.absolute(Models.str(o, "thumb_url"));
                p.authorLogin = Models.str(o, "owner_login");
                p.authorName = Models.str(o, "owner_display_name");
                if (!p.code.isEmpty()) r.posts.add(p);
            }
        }
        return r;
    }

    static java.util.List<Models.Category> videoCategories() throws IOException, JSONException {
        JSONObject json = getJson("/api/videos/categories/list?video_status=approved");
        java.util.List<Models.Category> list = new java.util.ArrayList<>();
        JSONArray data = json.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null || !"active".equals(o.optString("status", "active"))) continue;
                Models.Category c = new Models.Category();
                c.id = o.optLong("id");
                c.title = Models.str(o, "title");
                list.add(c);
            }
        }
        return list;
    }

    /** category: -1 = recommended (must-have), 0 = all approved, otherwise a category id. */
    static Models.Page<Models.Video> videos(long category, int page, int perPage)
            throws IOException, JSONException {
        Uri.Builder b = Uri.parse("/api/videos/list").buildUpon();
        if (category < 0) {
            b.appendQueryParameter("must_have", "true").appendQueryParameter("status", "approved");
        } else {
            if (category > 0) b.appendQueryParameter("category_id", String.valueOf(category));
            b.appendQueryParameter("status", "approved")
                    .appendQueryParameter("sort_by", category > 0 ? "custom" : "published_at")
                    .appendQueryParameter("sort_order", category > 0 ? "asc" : "desc");
        }
        b.appendQueryParameter("page", String.valueOf(page))
                .appendQueryParameter("per_page", String.valueOf(perPage));
        JSONObject json = getJson(b.build().toString());
        Models.Page<Models.Video> result = new Models.Page<>();
        JSONArray data = json.optJSONArray("data");
        if (data == null) throw new ApiException(200, "Неожиданный ответ сайта", false);
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o != null) result.items.add(Models.Video.from(o));
        }
        JSONObject meta = json.optJSONObject("meta");
        result.hasMore = meta != null ? meta.optBoolean("has_next_page") : data.length() >= perPage;
        return result;
    }

    // ---- account ----

    static void logout() throws IOException {
        String body = "csrf_token=" + Uri.encode(Session.csrf());
        Http.request("POST", "/logout", "application/x-www-form-urlencoded",
                body.getBytes(StandardCharsets.UTF_8));
        Session.refreshBlocking();
    }

    // ---- helpers ----

    private static String group(Pattern p, String html, String fallback) {
        if (html == null) return fallback;
        Matcher m = p.matcher(html);
        return m.find() ? htmlDecode(m.group(1)) : fallback;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static String htmlDecode(String s) {
        if (s == null) return null;
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
