package io.github.koffelov.ebloclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Session state comes from the values every eblo.id page embeds for its own scripts. */
public class SessionTest {
    private static String page(String auth, String login, String csrf) {
        return "<script>window.__CSRF_TOKEN=(" + csrf + "),window.CURRENT_USER_LOGIN=(" + login
                + "),window.IS_AUTHENTICATED=(" + auth + "),window.TURNSTILE_SITE_KEY=(\"0xKEY-1\");</script>";
    }

    @Test
    public void loggedInPage() {
        Session.update(page("true", "\"ishak_kodolov\"", "\"tok\\\"en\""));
        assertTrue(Session.known());
        assertTrue(Session.loggedIn());
        assertEquals("ishak_kodolov", Session.login());
        assertEquals("tok\"en", Session.csrf());
        assertEquals("0xKEY-1", Session.turnstileKey);
    }

    @Test
    public void guestPage() {
        Session.update(page("false", "null", "\"\""));
        assertFalse(Session.loggedIn());
        assertNull(Session.login());
        assertEquals("", Session.csrf());
    }

    @Test
    public void pageWithoutSessionMarkers_changesNothing() {
        Session.update(page("true", "\"someone\"", "\"x\""));
        Session.update("<html>just an error page</html>");
        assertTrue(Session.loggedIn());
        assertEquals("someone", Session.login());
    }
}
