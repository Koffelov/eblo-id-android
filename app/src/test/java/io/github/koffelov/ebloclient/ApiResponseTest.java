package io.github.koffelov.ebloclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

/** How the app reads eblo.id's JSON answers, including the uploader's status tunnelling. */
public class ApiResponseTest {
    private static Http.Response response(int code, String body, String originStatus) {
        Http.Response r = new Http.Response();
        r.code = code;
        r.body = body;
        if (originStatus != null) r.headers.put("x-origin-status", originStatus);
        return r;
    }

    @Test
    public void okJson_isReturned() throws Exception {
        JSONObject json = Api.parse(response(200, "{\"success\":true,\"id\":\"abc\"}", null));
        assertEquals("abc", json.getString("id"));
    }

    @Test
    public void tunnelledStatus_winsOverHttp200() throws Exception {
        try {
            Api.parse(response(200, "{\"error\":\"\"}", "413"));
            fail("expected ApiException");
        } catch (Api.ApiException e) {
            assertEquals(413, e.code);
            assertEquals("Файл слишком большой для сайта", e.getMessage());
            assertFalse(e.transientError());
        }
    }

    @Test
    public void captchaRequired_isFlagged() throws Exception {
        try {
            Api.parse(response(403, "{\"captcha_required\":true,\"error\":\"Требуется проверка\"}", null));
            fail("expected ApiException");
        } catch (Api.ApiException e) {
            assertTrue(e.captcha);
            assertEquals("Требуется проверка", e.getMessage());
            assertEquals("Требуется проверка", e.body.getString("error"));
        }
    }

    @Test
    public void successFalse_isAnError() throws Exception {
        try {
            Api.parse(response(200, "{\"success\":false,\"error\":\"Нельзя\"}", null));
            fail("expected ApiException");
        } catch (Api.ApiException e) {
            assertEquals("Нельзя", e.getMessage());
        }
    }

    @Test
    public void htmlInsteadOfJson_isAnError() throws Exception {
        try {
            Api.parse(response(502, "<html>Bad gateway</html>", null));
            fail("expected ApiException");
        } catch (Api.ApiException e) {
            assertEquals(502, e.code);
            assertTrue("5xx are retried by the uploader", e.transientError());
        }
    }

    @Test
    public void unauthorized_explainsLogin() throws Exception {
        try {
            Api.parse(response(401, "{}", null));
            fail("expected ApiException");
        } catch (Api.ApiException e) {
            assertEquals("Нужно войти в аккаунт", e.getMessage());
        }
    }

    @Test
    public void transientErrors_matchTheSitesUploader() {
        assertTrue(new Api.ApiException(429, null, false).transientError());
        assertTrue(new Api.ApiException(409, null, false).transientError());
        assertTrue(new Api.ApiException(503, null, false).transientError());
        assertFalse("507 = storage full, not worth retrying", new Api.ApiException(507, null, false).transientError());
        assertFalse(new Api.ApiException(400, null, false).transientError());
    }
}
