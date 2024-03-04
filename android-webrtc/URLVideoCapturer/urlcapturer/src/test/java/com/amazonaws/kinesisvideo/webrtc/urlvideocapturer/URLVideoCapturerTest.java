package com.amazonaws.kinesisvideo.webrtc.urlvideocapturer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static org.junit.Assert.*;

import android.net.Uri;

/**
 * Basic unit testing for URLVideoCapturer.
 */
@RunWith(PowerMockRunner.class)
@PowerMockIgnore("jdk.internal.reflect.*")
@PrepareForTest({Uri.class})
public class URLVideoCapturerTest {
    private Uri getMockUri(String url) {
        PowerMockito.mockStatic(Uri.class);
        Uri mockUri = PowerMockito.mock(Uri.class);
        PowerMockito.when(Uri.parse(url)).thenReturn(mockUri);
        return mockUri;
    }

    @Test
    public void instantiation() {
        final Uri uri = getMockUri("rtsp://user:password@host/path");
        final String[] options = new String[]{"Option1", "Option2"};
        final String aspectRatio = "4:3";

        try {
            new URLVideoCapturer(uri, options, aspectRatio);
        } catch (Exception e) {
            fail("Failed to create URLVideoCapturer");
        }
    }

    @Test
    public void instantiation_with_null_config() {
        final Uri uri = getMockUri("rtsp://user:password@host/path");
        final String[] options = null;
        final String aspectRatio = null;

        try {
            new URLVideoCapturer(uri, options, aspectRatio);
        } catch (Exception e) {
            fail("Failed to create URLVideoCapturer");
        }
    }

    @Test
    public void instantiation_with_null_url() {
        assertThrows(IllegalArgumentException.class, () -> {
            final Uri uri = null;
            final String[] options = new String[]{"Option1", "Option2"};
            final String aspectRatio = "4:3";

            new URLVideoCapturer(uri, options, aspectRatio);
        });
    }

    @Test
    public void notScreenCast() {
        final Uri uri = getMockUri("rtsp://user:password@host/path");
        final String[] options = new String[]{"Option1", "Option2"};
        final String aspectRatio = "4:3";

        URLVideoCapturer urlVideoCapturer = new URLVideoCapturer(uri, options, aspectRatio);
        assertFalse(urlVideoCapturer.isScreencast());
    }
}