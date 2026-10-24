package io.github.koffelov.ebloclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Profile header parsing from the /@login page, cut down to the parts the app reads. */
public class ApiProfileParseTest {
    /** The site's support modal comes before the profile and links eblo.id's own channel. */
    private static final String SITE_CHROME = """
            <div id="support-modal"><a href="https://t.me/ebloid_upd">Канал в Telegram</a>
            <a href="https://t.me/ebloid_upd?direct">Чат</a></div>
            """;

    private static String page(String links, String extra) {
        return "<html><head><meta property=\"og:image\" content=\"https://cdn.example/a.png\"></head>"
                + "<body data-is-owner=\"false\">" + SITE_CHROME
                + "<div class=\"desktop-only\"><div class=\"profile-card\">"
                + "<img class=\"user-badge-icon user-badge-icon-profile\" src=\"/uploads/badges/admin.webp\">"
                + "<img class=\"user-badge-icon user-badge-icon-profile badge-desktop-extra\" src=\"/uploads/badges/streamer.webp\">"
                + "<div class=\"profile-name\">Ishak &amp; Co</div>"
                + "<div class=\"profile-links\">" + links + "</div>"
                + "<button class=\"profile-social-stat\"><strong data-following-count>3</strong> подписок</button>"
                + "<button class=\"profile-social-stat\"><strong data-followers-count>301</strong> подписчиков</button>"
                + "<span class=\"profile-social-stat\"><strong>5</strong> публикаций</span>"
                + extra + "</div></div>"
                + "<div class=\"mobile-only\"><img class=\"user-badge-icon user-badge-icon-profile\" src=\"/uploads/badges/mobile-only.webp\"></div>"
                + "</body></html>";
    }

    @Test
    public void noLinkedTelegram_doesNotPickTheSiteChannel() {
        String html = page("<a href=\"https://twitch.tv/ishak_kodolov\"><img alt=\"Twitch\"></a>", "");
        Models.Profile p = Api.parseProfile("ishak_kodolov", html);
        assertNull("eblo.id's channel from the page chrome must not become the user's Telegram", p.telegram);
        assertEquals("https://twitch.tv/ishak_kodolov", p.twitch);
    }

    @Test
    public void linkedTelegram_isRead() {
        String html = page("<a href=\"https://twitch.tv/bratishkinoff\"></a><a href=\"https://t.me/nebudetgg\"></a>", "");
        Models.Profile p = Api.parseProfile("bratishkinoff", html);
        assertEquals("https://t.me/nebudetgg", p.telegram);
    }

    @Test
    public void readsNameCountersBadgesAndAvatar() {
        Models.Profile p = Api.parseProfile("ishak_kodolov", page("", ""));
        assertEquals("Ishak & Co", p.name);
        assertEquals(3, p.following);
        assertEquals(301, p.followers);
        assertEquals(5, p.posts);
        assertEquals("https://cdn.example/a.png", p.avatar);
        // Only the desktop block's badges, made absolute, without the mobile duplicate.
        assertEquals(2, p.badges.size());
        assertEquals("https://eblo.id/uploads/badges/admin.webp", p.badges.get(0));
        assertFalse(p.owner);
        assertTrue(p.canFollow);
    }

    @Test
    public void followButtonText_meansFollowing() {
        String html = page("", "<a class=\"profile-edit-btn profile-follow-btn\" href=\"#\">Вы отслеживаете</a>");
        assertTrue(Api.parseProfile("x", html).isFollowing);
        String notFollowing = page("", "<a class=\"profile-edit-btn profile-follow-btn\" href=\"#\">Подписаться</a>");
        assertFalse(Api.parseProfile("x", notFollowing).isFollowing);
    }

    @Test
    public void ownerPage_readsSocialToggle() {
        String html = page("", "<input type=\"checkbox\" id=\"social-visibility-toggle\" checked>")
                .replace("data-is-owner=\"false\"", "data-is-owner=\"true\"");
        Models.Profile p = Api.parseProfile("me", html);
        assertTrue(p.owner);
        assertFalse(p.canFollow);
        assertEquals(Boolean.TRUE, p.showSocial);
        assertNull("not an owner page: no toggle", Api.parseProfile("x", page("", "")).showSocial);
    }

    @Test
    public void missingCounters_areUnknown() {
        Models.Profile p = Api.parseProfile("ghost", "<html><body>404</body></html>");
        assertEquals("ghost", p.name);
        assertEquals(-1, p.followers);
        assertEquals(-1, p.posts);
        assertNull(p.twitch);
    }
}
