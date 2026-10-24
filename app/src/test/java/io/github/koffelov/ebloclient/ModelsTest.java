package io.github.koffelov.ebloclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Lenient parsing of eblo.id JSON (shapes copied from real responses). */
public class ModelsTest {
    @Test
    public void feedItem() throws Exception {
        Models.Post p = Models.Post.fromFeed(new JSONObject("""
                {"blur":"1","comments_count":40,"created":1792686443,"description":"ВСЕМ СПАСИБО!",
                 "is_album":false,"owner_avatar":"https://static-cdn.jtvnw.net/a.png",
                 "owner_badge_icon":"/uploads/badges/ambassador.webp","owner_display_name":"",
                 "owner_login":"bratishkinoff","short_code":"LINgGdu",
                 "thumbnail_url":"/uploads/thumbs/LINgGdu_thumb.webp","title":"Рекорд Chatterino+",
                 "type":"image","views":524,"vote_score":87}
                """));
        assertEquals("LINgGdu", p.code);
        assertEquals("bratishkinoff", p.authorName); // empty display name falls back to login
        assertEquals("https://eblo.id/uploads/thumbs/LINgGdu_thumb.webp", p.thumb);
        assertEquals("https://eblo.id/uploads/badges/ambassador.webp", p.badgeIcon);
        assertTrue(p.blur);
        assertFalse(p.album);
        assertEquals(87, p.score);
        assertEquals(40, p.comments);
    }

    @Test
    public void albumInFeed() throws Exception {
        Models.Post p = Models.Post.fromFeed(new JSONObject("{\"type\":\"album\",\"files_count\":10,\"blur\":\"0\"}"));
        assertTrue(p.album);
        assertEquals(10, p.filesCount);
        assertFalse(p.blur);
        assertNull("no thumbnail_url → no preview", p.thumb);
    }

    @Test
    public void publicPostWithMedia() throws Exception {
        Models.Post p = Models.Post.fromPublic(new JSONObject("""
                {"author":{"avatar_url":"https://cdn/a.png","display_name":"twivoid","login":"twivoid"},
                 "blur":false,"created_at":1792706034,"description":"","media_count":2,"short_code":"AW9OoeC",
                 "stats":{"comments":3,"score":5,"views":9},"title":"топ","type":"album",
                 "media":[{"kind":"image","mime":"image/webp","name":"a.png","size":31726,
                           "url":"https://eblo.id/uploads/AU5lzwX/a.opt.webp"},
                          {"kind":"video","mime":"video/mp4","name":"b.mp4","size":1139686,
                           "url":"https://eblo.id/uploads/JJLhynG/b.opt.mp4"}]}
                """));
        assertEquals(2, p.media.size());
        assertTrue(p.media.get(0).isImage());
        assertTrue(p.media.get(1).isVideo());
        assertFalse(p.media.get(1).isAudio());
        assertEquals(9, p.views);
        assertEquals(2, p.filesCount);
        assertTrue(p.album);
    }

    @Test
    public void commentTree() throws Exception {
        Models.Comment c = Models.Comment.from(new JSONObject("""
                {"author":{"avatar":"/profile/pizdid/avatar.webp","login":"pizdid","name":"pizdid"},
                 "content":"Нет","created_at":1792686492,"id":31871,"is_deleted":false,"parent_id":null,
                 "score":8,"user_vote":null,
                 "replies":[{"author":{"login":"x","name":""},"content":"Да","id":31872,"parent_id":31871,
                             "user_vote":"up","replies":[]}]}
                """));
        assertNull(c.parentId);
        assertNull(c.userVote);
        assertEquals("https://eblo.id/profile/pizdid/avatar.webp", c.authorAvatar);
        assertEquals(1, c.replies.size());
        Models.Comment reply = c.replies.get(0);
        assertEquals(Long.valueOf(31871), reply.parentId);
        assertEquals("up", reply.userVote);
        assertEquals("x", reply.authorName);
    }

    @Test
    public void videoPrefersTheSitesPreview() throws Exception {
        Models.Video v = Models.Video.from(new JSONObject("""
                {"id":11757,"title":"Исполнила мечту","url":"https://www.youtube.com/watch?v=YkD4kKSDFnI",
                 "preview_url":"/uploads/previews/YkD4kKSDFnI.webp",
                 "thumbnail_url":"https://i.ytimg.com/vi/YkD4kKSDFnI/hqdefault.jpg",
                 "duration":1736,"likes":119,"has_voted":true,"author":{"display_name":"lorei01"}}
                """));
        assertEquals("https://eblo.id/uploads/previews/YkD4kKSDFnI.webp", v.thumb);
        assertTrue(v.voted);
        assertEquals("lorei01", v.authorName);
    }

    @Test
    public void absoluteUrls() {
        assertEquals("https://eblo.id/a", Http.absolute("/a"));
        assertEquals("https://eblo.id/a", Http.absolute("a"));
        assertEquals("https://cdn.x/a", Http.absolute("//cdn.x/a"));
        assertEquals("http://x/a", Http.absolute("http://x/a"));
        assertNull(Http.absolute(""));
        assertNull(Http.absolute(null));
    }
}
