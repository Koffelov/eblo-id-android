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
        final JSONObject body;

        ApiException(int code, String message, boolean captcha) {
            this(code, message, captcha, null);
        }

        ApiException(int code, String message, boolean captcha, JSONObject body) {
            super(message == null || message.isEmpty() ? "HTTP " + code : message);
            this.code = code;
            this.captcha = captcha;
            this.body = body;
        }

        /** Worth retrying: server hiccup, rate limit, conflict while chunks settle. */
        boolean transientError() {
            return code >= 500 && code != 507 || code == 429 || code == 409 || code == 499 || code == 0;
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

    static JSONObject parse(Http.Response r) throws IOException, JSONException {
        // The uploader asks for "status tunnelling": the real status comes in X-Origin-Status.
        int code = r.code;
        String tunnelled = r.header("X-Origin-Status");
        if (tunnelled != null) {
            try {
                code = Integer.parseInt(tunnelled.trim());
            } catch (NumberFormatException ignored) {
                // Keep the HTTP status.
            }
        }
        String text = r.body == null ? "" : r.body.trim();
        JSONObject json = null;
        if (text.startsWith("{")) {
            try {
                json = new JSONObject(text);
            } catch (JSONException ignored) {
                // Not JSON after all.
            }
        }
        if (code >= 400 || json == null) {
            String message = json == null ? null : json.optString("error", json.optString("message", null));
            boolean captcha = json != null && json.optBoolean("captcha_required");
            if (code == 401) message = "Нужно войти в аккаунт";
            if (code == 413) message = "Файл слишком большой для сайта";
            throw new ApiException(code, message, captcha, json);
        }
        if (json.has("success") && !json.optBoolean("success")) {
            throw new ApiException(code, json.optString("error", "Ошибка сайта"),
                    json.optBoolean("captcha_required"), json);
        }
        return json;
    }

    private static final java.util.Map<String, String> TUNNEL =
            java.util.Collections.singletonMap("X-Status-Tunnel", "1");

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
    // Only the profile's own link block: the page footer also links to eblo.id's Telegram channel.
    private static final Pattern LINKS_BLOCK =
            Pattern.compile("class=\"(?:profile-links|platform-badges)\">(.*?)</div>", Pattern.DOTALL);
    private static final Pattern SOCIAL_TOGGLE = Pattern.compile("<input[^>]*id=\"social-visibility-toggle\"[^>]*>");

    /** The profile header exists only as HTML on /@login. */
    static Models.Profile profile(String login) throws IOException {
        Http.Response r = Http.get("/@" + enc(login));
        if (r.code == 404) throw new ApiException(404, "Пользователь не найден", false);
        if (r.code >= 400) throw new ApiException(r.code, null, false);
        Session.update(r.body);
        return parseProfile(login, r.body);
    }

    /** Reads the profile header out of the /@login page HTML. */
    static Models.Profile parseProfile(String login, String html) {
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
        Matcher links = LINKS_BLOCK.matcher(html);
        String block = links.find() ? links.group(1) : "";
        p.twitch = group(TWITCH, block, null);
        p.telegram = group(TELEGRAM, block, null);
        Matcher social = SOCIAL_TOGGLE.matcher(html);
        if (social.find()) p.showSocial = social.group().contains(" checked");
        if (p.owner && Session.avatarVersion > 0 && p.avatar != null) {
            p.avatar += (p.avatar.contains("?") ? "&" : "?") + "v=" + Session.avatarVersion;
        }
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

    static Models.Video voteVideo(long id, boolean remove) throws IOException, JSONException {
        JSONObject json = postJson(remove ? "/api/videos/unvote" : "/api/videos/vote", new JSONObject().put("id", id));
        JSONObject data = json.optJSONObject("data");
        if (data == null) throw new ApiException(200, "Неожиданный ответ сайта", false);
        return Models.Video.from(data);
    }

    /** Suggests a video for the stream; returns the site's message. */
    static String offerVideo(String url, java.util.List<Long> categories, String comment)
            throws IOException, JSONException {
        JSONArray ids = new JSONArray();
        for (Long id : categories) ids.put(id.longValue());
        JSONObject body = new JSONObject().put("url", url).put("category_ids", ids).put("comment", comment);
        if (!categories.isEmpty()) body.put("category_id", categories.get(0).longValue());
        JSONObject json = postJson("/api/videos/offer", body);
        JSONObject notify = json.optJSONObject("notify");
        String msg = notify == null ? "" : notify.optString("msg", "");
        return msg.isEmpty() ? "Видео предложено" : msg;
    }

    // ---- new post: the site's chunked uploader (/api/uploads) ----

    static final class UploadSession {
        String id;
        int chunks;
        long chunkSize;
    }

    static Models.UploadLimits uploadLimits() throws IOException, JSONException {
        JSONObject json = getJson("/api/public/upload-limits");
        Models.UploadLimits l = new Models.UploadLimits();
        l.maxBytes = json.optLong("max_bytes", l.maxBytes);
        JSONArray ext = json.optJSONArray("extensions");
        if (ext != null) for (int i = 0; i < ext.length(); i++) l.extensions.add(ext.optString(i).toLowerCase(java.util.Locale.ROOT));
        return l;
    }

    static UploadSession createUpload(String name, long size, String mime) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("name", name).put("size", size).put("mime", mime);
        JSONObject json = parse(Http.request("POST", "/api/uploads", "application/json",
                body.toString().getBytes(StandardCharsets.UTF_8), TUNNEL));
        UploadSession s = new UploadSession();
        s.id = json.optString("id", "");
        s.chunks = json.optInt("chunks", 1);
        s.chunkSize = json.optLong("chunk_size", size);
        if (s.id.isEmpty() || s.chunks <= 0 || s.chunkSize <= 0) {
            throw new ApiException(200, "Сайт не начал загрузку", false, json);
        }
        return s;
    }

    static void putChunk(String id, int index, byte[] data) throws IOException, JSONException {
        Http.Response r = Http.request("PUT", "/api/uploads/" + enc(id) + "/chunks/" + index,
                "application/octet-stream", data, TUNNEL);
        if (r.body == null || r.body.trim().isEmpty()) r.body = "{}";
        parse(r);
    }

    static JSONObject completeUpload(java.util.List<String> ids, String title, String description,
                                     boolean linkOnly, boolean blur, String uploadId)
            throws IOException, JSONException {
        JSONArray uploads = new JSONArray();
        for (String id : ids) uploads.put(id);
        JSONObject body = new JSONObject().put("uploads", uploads).put("title", title)
                .put("description", description).put("private", linkOnly ? "1" : "0")
                .put("blur", blur ? "1" : "0").put("upload_id", uploadId);
        return parse(Http.request("POST", "/api/uploads/complete", "application/json",
                body.toString().getBytes(StandardCharsets.UTF_8), TUNNEL));
    }

    static void cancelUpload(String id) {
        try {
            Http.request("DELETE", "/api/uploads/" + enc(id), null, null, TUNNEL);
        } catch (IOException ignored) {
            // The site expires unfinished uploads by itself.
        }
    }

    static boolean verifyTurnstile(String token) throws IOException, JSONException {
        JSONObject json = parse(Http.request("POST", "/api/security/turnstile/verify", "application/json",
                new JSONObject().put("token", token).toString().getBytes(StandardCharsets.UTF_8)));
        return json.optBoolean("success", true);
    }

    // ---- own profile and settings ----

    static String uploadAvatar(byte[] jpeg) throws IOException, JSONException {
        String boundary = "----eblo" + System.nanoTime();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(jpeg);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        JSONObject json = parse(Http.request("POST", "/api/profile/upload-avatar",
                "multipart/form-data; boundary=" + boundary, out.toByteArray()));
        Session.avatarVersion = System.currentTimeMillis();
        return Http.absolute(json.optString("avatar_url", ""));
    }

    static void deleteAvatar() throws IOException, JSONException {
        postJson("/api/profile/delete-avatar", new JSONObject());
        Session.avatarVersion = System.currentTimeMillis();
    }

    static java.util.List<Models.Badge> myBadges() throws IOException, JSONException {
        JSONObject json = getJson("/settings/my_badges");
        java.util.List<Models.Badge> list = new java.util.ArrayList<>();
        JSONArray arr = json.optJSONArray("badges");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Models.Badge b = new Models.Badge();
                b.id = o.optLong("id");
                b.name = Models.str(o, "name");
                b.icon = Http.absolute(Models.str(o, "icon"));
                b.active = o.optBoolean("is_active");
                list.add(b);
            }
        }
        return list;
    }

    static void setBadge(Long id) throws IOException, JSONException {
        postJson("/settings/set_active_badge", new JSONObject().put("badge_id", id == null ? JSONObject.NULL : id));
    }

    static boolean setSocialVisibility(boolean show) throws IOException, JSONException {
        return postJson("/settings/social_visibility", new JSONObject().put("show_social_connections", show))
                .optBoolean("show_social_connections", show);
    }

    static JSONObject notificationPrefs() throws IOException, JSONException {
        return getJson("/settings/notification_prefs");
    }

    static void setNotificationPref(String key, boolean value) throws IOException, JSONException {
        postJson("/settings/notification_prefs", new JSONObject().put(key, value));
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
