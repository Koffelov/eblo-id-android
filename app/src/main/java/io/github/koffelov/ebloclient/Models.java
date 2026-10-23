package io.github.koffelov.ebloclient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Data shapes of eblo.id's JSON, parsed leniently: missing fields fall back to defaults. */
final class Models {
    private Models() {
    }

    static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        return o.optString(key, "");
    }

    static final class Post {
        String code = "";
        String title = "";
        String description = "";
        String type = "";
        String thumb;
        String authorLogin = "";
        String authorName = "";
        String authorAvatar;
        String badgeIcon;
        String badgeName = "";
        long created;
        int views;
        int score;
        int comments;
        int filesCount;
        boolean blur;
        boolean album;
        String moderation = "";
        final List<Media> media = new ArrayList<>();

        /** An item of /api/feed or /api/profile/&lt;login&gt;/uploads. */
        static Post fromFeed(JSONObject o) {
            Post p = new Post();
            p.code = str(o, "short_code");
            p.title = str(o, "title");
            p.description = str(o, "description");
            p.type = str(o, "type");
            p.thumb = Http.absolute(str(o, "thumbnail_url"));
            p.authorLogin = str(o, "owner_login");
            p.authorName = str(o, "owner_display_name");
            if (p.authorName.isEmpty()) p.authorName = p.authorLogin;
            p.authorAvatar = Http.absolute(str(o, "owner_avatar"));
            p.badgeIcon = Http.absolute(str(o, "owner_badge_icon"));
            p.badgeName = str(o, "owner_badge_name");
            p.created = o.optLong("created");
            p.views = o.optInt("views");
            p.score = o.optInt("vote_score");
            p.comments = o.optInt("comments_count");
            p.filesCount = o.optInt("files_count");
            p.blur = "1".equals(str(o, "blur")) || o.optBoolean("blur", false);
            p.album = o.optBoolean("is_album") || "album".equals(p.type);
            p.moderation = str(o, "moderation_status");
            return p;
        }

        /** /api/public/post/&lt;code&gt; — the documented public API. */
        static Post fromPublic(JSONObject o) {
            Post p = new Post();
            p.code = str(o, "short_code");
            p.title = str(o, "title");
            p.description = str(o, "description");
            p.type = str(o, "type");
            p.thumb = Http.absolute(str(o, "thumb"));
            JSONObject author = o.optJSONObject("author");
            p.authorLogin = str(author, "login");
            p.authorName = str(author, "display_name");
            if (p.authorName.isEmpty()) p.authorName = p.authorLogin;
            p.authorAvatar = Http.absolute(str(author, "avatar_url"));
            p.created = o.optLong("created_at");
            JSONObject stats = o.optJSONObject("stats");
            if (stats != null) {
                p.views = stats.optInt("views");
                p.score = stats.optInt("score");
                p.comments = stats.optInt("comments");
            }
            p.blur = o.optBoolean("blur", false);
            p.album = "album".equals(p.type);
            JSONArray media = o.optJSONArray("media");
            if (media != null) {
                for (int i = 0; i < media.length(); i++) {
                    JSONObject m = media.optJSONObject(i);
                    if (m != null) p.media.add(Media.from(m));
                }
            }
            p.filesCount = o.optInt("media_count", p.media.size());
            return p;
        }
    }

    static final class Media {
        String kind = "";
        String mime = "";
        String name = "";
        long size;
        String url;
        String thumb;

        static Media from(JSONObject o) {
            Media m = new Media();
            m.kind = str(o, "kind");
            m.mime = str(o, "mime");
            m.name = str(o, "name");
            m.size = o.optLong("size");
            m.url = Http.absolute(str(o, "url"));
            m.thumb = Http.absolute(str(o, "thumb"));
            return m;
        }

        boolean isImage() {
            return "image".equals(kind) || mime.startsWith("image/");
        }

        boolean isVideo() {
            return "video".equals(kind) || mime.startsWith("video/");
        }

        boolean isAudio() {
            return "audio".equals(kind) || mime.startsWith("audio/");
        }
    }

    static final class Comment {
        long id;
        Long parentId;
        String content = "";
        long created;
        int score;
        String userVote;
        boolean deleted;
        String authorLogin = "";
        String authorName = "";
        String authorAvatar;
        String badgeIcon;
        int depth;
        final List<Comment> replies = new ArrayList<>();

        static Comment from(JSONObject o) {
            Comment c = new Comment();
            c.id = o.optLong("id");
            c.parentId = o.isNull("parent_id") ? null : o.optLong("parent_id");
            c.content = str(o, "content");
            c.created = o.optLong("created_at");
            c.score = o.optInt("score");
            c.userVote = o.isNull("user_vote") ? null : o.optString("user_vote", null);
            c.deleted = o.optBoolean("is_deleted");
            JSONObject a = o.optJSONObject("author");
            c.authorLogin = str(a, "login");
            c.authorName = str(a, "name");
            if (c.authorName.isEmpty()) c.authorName = c.authorLogin;
            c.authorAvatar = Http.absolute(str(a, "avatar"));
            c.badgeIcon = Http.absolute(str(a, "badge_icon"));
            JSONArray replies = o.optJSONArray("replies");
            if (replies != null) {
                for (int i = 0; i < replies.length(); i++) {
                    JSONObject r = replies.optJSONObject(i);
                    if (r != null) c.replies.add(from(r));
                }
            }
            return c;
        }
    }

    static final class Notif {
        long id;
        String title = "";
        String message = "";
        String url;
        long created;
        boolean read;

        static Notif from(JSONObject o) {
            Notif n = new Notif();
            n.id = o.optLong("id");
            n.title = str(o, "title");
            n.message = str(o, "message");
            n.url = str(o, "url");
            n.created = o.optLong("created_at");
            n.read = o.optBoolean("read");
            return n;
        }
    }

    static final class User {
        String login = "";
        String name = "";
        String avatar;

        static User from(JSONObject o) {
            User u = new User();
            u.login = str(o, "login");
            u.name = str(o, "display_name");
            if (u.name.isEmpty()) u.name = u.login;
            u.avatar = Http.absolute(str(o, "avatar"));
            return u;
        }
    }

    static final class Video {
        long id;
        String title = "";
        String url;
        String thumb;
        String channel = "";
        String authorName = "";
        int duration;
        int likes;
        long published;
        boolean voted;

        static Video from(JSONObject o) {
            Video v = new Video();
            v.id = o.optLong("id");
            v.voted = o.optBoolean("has_voted");
            v.title = str(o, "title");
            v.url = str(o, "url");
            String preview = str(o, "preview_url");
            v.thumb = preview.isEmpty() ? Http.absolute(str(o, "thumbnail_url")) : Http.absolute(preview);
            v.channel = str(o, "channel_title");
            JSONObject a = o.optJSONObject("author");
            v.authorName = str(a, "display_name");
            v.duration = o.optInt("duration");
            v.likes = o.optInt("likes");
            v.published = o.optLong("published_at", o.optLong("created_at"));
            return v;
        }
    }

    static final class Category {
        long id;
        String title = "";
    }

    static final class Profile {
        String login = "";
        String name = "";
        String avatar;
        int following = -1;
        int followers = -1;
        int posts = -1;
        boolean owner;
        boolean isFollowing;
        boolean canFollow;
        final List<String> badges = new ArrayList<>();
        String twitch;
        String telegram;
        Boolean showSocial;
    }

    static final class Badge {
        Long id;
        String name = "";
        String icon;
        boolean active;
    }

    static final class UploadLimits {
        long maxBytes = 200L * 1024 * 1024;
        final java.util.Set<String> extensions = new java.util.HashSet<>();
    }

    static final class Page<T> {
        final List<T> items = new ArrayList<>();
        boolean hasMore;
        int nextOffset;
        String cursor;
        int total = -1;
    }
}
