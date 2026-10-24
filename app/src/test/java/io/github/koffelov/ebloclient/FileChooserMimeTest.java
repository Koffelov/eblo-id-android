package io.github.koffelov.ebloclient;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.webkit.MimeTypeMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

/** accept="…" of a web file input → MIME types for the Android picker (static MimeTypeMap mocked). */
public class FileChooserMimeTest {
    private MockedStatic<MimeTypeMap> mimeMap;

    @Before
    public void fakeMimeMap() {
        MimeTypeMap fake = mock(MimeTypeMap.class);
        when(fake.getMimeTypeFromExtension("png")).thenReturn("image/png");
        when(fake.getMimeTypeFromExtension("mp4")).thenReturn("video/mp4");
        when(fake.getMimeTypeFromExtension("zip")).thenReturn("application/zip");
        mimeMap = mockStatic(MimeTypeMap.class);
        mimeMap.when(MimeTypeMap::getSingleton).thenReturn(fake);
    }

    @After
    public void restore() {
        mimeMap.close();
    }

    @Test
    public void mediaOnly_keepsTheTypes() {
        assertArrayEquals(new String[] {"image/png", "video/mp4", "image/*"},
                WebActivity.mimeTypesFor(new String[] {".png, .MP4", "image/*"}));
    }

    @Test
    public void anyDocument_opensAllFiles() {
        assertArrayEquals(new String[] {"*/*"}, WebActivity.mimeTypesFor(new String[] {".png", ".zip"}));
    }

    @Test
    public void unknownExtension_opensAllFiles() {
        assertArrayEquals(new String[] {"*/*"}, WebActivity.mimeTypesFor(new String[] {".heic2"}));
    }

    @Test
    public void noAccept_opensAllFiles() {
        assertArrayEquals(new String[] {"*/*"}, WebActivity.mimeTypesFor(null));
        assertArrayEquals(new String[] {"*/*"}, WebActivity.mimeTypesFor(new String[] {""}));
    }
}
