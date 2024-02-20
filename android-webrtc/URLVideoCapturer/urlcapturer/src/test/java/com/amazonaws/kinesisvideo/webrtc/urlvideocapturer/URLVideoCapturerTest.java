package com.amazonaws.kinesisvideo.webrtc.urlvideocapturer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Basic unit testing for URLVideoCapturer.
 */
public class URLVideoCapturerTest {
    @Test
    public void instantiation() {
        final String url = "rtsp://user:password@host/path";
        final String[] options = new String[]{"Option1", "Option2"};
        final String aspectRatio = "4:3";

        URLVideoCapturer urlVideoCapturer = new URLVideoCapturer(url, options, aspectRatio);
        assertNotNull(urlVideoCapturer);
    }

    @Test
    public void notScreenCast() {
        final String url = "rtsp://user:password@host/path";
        final String[] options = new String[]{"Option1", "Option2"};
        final String aspectRatio = "4:3";

        URLVideoCapturer urlVideoCapturer = new URLVideoCapturer(url, options, aspectRatio);
        assertFalse(urlVideoCapturer.isScreencast());
    }
}