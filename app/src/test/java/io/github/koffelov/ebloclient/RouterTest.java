package io.github.koffelov.ebloclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/** Link routing with mocked Android components: Uri, Context, Intent and Toast. */
public class RouterTest {
    private static Uri uri(String scheme, String host, String path) {
        Uri u = mock(Uri.class);
        when(u.getScheme()).thenReturn(scheme);
        when(u.getHost()).thenReturn(host);
        when(u.getPath()).thenReturn(path);
        return u;
    }

    /** Intents built by the code under test become mocks whose builder calls return themselves. */
    private static MockedConstruction<Intent> mockIntents() {
        return mockConstruction(Intent.class, (intent, context) -> {
            when(intent.putExtra(anyString(), anyString())).thenReturn(intent);
            when(intent.putExtra(anyString(), anyBoolean())).thenReturn(intent);
            when(intent.addCategory(anyString())).thenReturn(intent);
            when(intent.addFlags(anyInt())).thenReturn(intent);
            when(intent.setFlags(anyInt())).thenReturn(intent);
        });
    }

    @Test
    public void postCode_onlyForSevenCharacterPostPaths() {
        assertEquals("LINgGdu", Router.postCode(uri("https", "eblo.id", "/LINgGdu")));
        assertEquals("LINgGdu", Router.postCode(uri("https", "www.eblo.id", "/LINgGdu/")));
        assertNull("site pages are not posts", Router.postCode(uri("https", "eblo.id", "/privacy")));
        assertNull(Router.postCode(uri("https", "eblo.id", "/consent")));
        assertNull(Router.postCode(uri("https", "eblo.id", "/@bratishkinoff")));
        assertNull("other sites are never posts", Router.postCode(uri("https", "example.com", "/LINgGdu")));
        assertNull(Router.postCode(uri("https", "eblo.id", null)));
    }

    @Test
    public void isSite_ignoresCase_andOtherHosts() {
        assertTrue(Router.isSite(uri("https", "EBLO.ID", "/")));
        assertFalse(Router.isSite(uri("https", "eblo.id.evil.com", "/")));
        assertFalse(Router.isSite(uri("https", null, "/")));
    }

    @Test
    public void postLink_opensTheNativePostScreen() {
        Context context = mock(Context.class);
        try (MockedConstruction<Intent> intents = mockIntents()) {
            Router.open(context, uri("https", "eblo.id", "/LINgGdu"));
            Intent created = intents.constructed().get(0);
            verify(created).putExtra("code", "LINgGdu");
            verify(context).startActivity(created);
        }
    }

    @Test
    public void foreignLink_goesToAnotherApp_withNewTaskOutsideActivities() {
        Context context = mock(Context.class);
        try (MockedConstruction<Intent> intents = mockIntents()) {
            Router.open(context, uri("https", "t.me", "/ebloid_upd"));
            Intent created = intents.constructed().get(0);
            verify(created).addCategory(Intent.CATEGORY_BROWSABLE);
            verify(created).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            verify(context).startActivity(created);
        }
    }

    @Test
    public void noAppForLink_showsAToastInsteadOfCrashing() {
        Context context = mock(Context.class);
        doThrow(new ActivityNotFoundException()).when(context).startActivity(any());
        Toast toast = mock(Toast.class);
        try (MockedConstruction<Intent> ignored = mockIntents();
             MockedStatic<Toast> toasts = mockStatic(Toast.class)) {
            toasts.when(() -> Toast.makeText(any(Context.class), anyString(), anyInt())).thenReturn(toast);
            Router.openExternal(context, uri("tg", "resolve", null));
            toasts.verify(() -> Toast.makeText(context, "Нет приложения, чтобы открыть эту ссылку", Toast.LENGTH_SHORT));
            verify(toast).show();
        }
    }

    @Test
    public void share_buildsATextIntent() {
        Context context = mock(Context.class);
        Intent chooser = mock(Intent.class);
        try (MockedConstruction<Intent> intents = mockIntents();
             MockedStatic<Intent> statics = mockStatic(Intent.class)) {
            statics.when(() -> Intent.createChooser(any(Intent.class), any())).thenReturn(chooser);
            Router.share(context, "Рекорд", "https://eblo.id/LINgGdu");
            Intent send = intents.constructed().get(0);
            verify(send).setType("text/plain");
            verify(send).putExtra(Intent.EXTRA_TEXT, "Рекорд\nhttps://eblo.id/LINgGdu");
            verify(context).startActivity(chooser);
            verify(context, never()).startActivity(send);
        }
    }
}
