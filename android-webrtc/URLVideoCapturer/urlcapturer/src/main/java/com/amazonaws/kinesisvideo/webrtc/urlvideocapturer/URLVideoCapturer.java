package com.amazonaws.kinesisvideo.webrtc.urlvideocapturer;

import android.content.Context;
import android.net.Uri;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;
import org.webrtc.CapturerObserver;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * This class implements a WebRTC VideoCapturer for media streams. Similar to how camera capturers work, this
 * class also leverages the SurfaceTextureHelper to do the heavy lifting of grabbing and encoding video
 * frames using an intermediate OpenGL texture. It also uses libVLC(User must add) to playback streams on the texture.
 */
public class URLVideoCapturer implements VideoCapturer, VideoSink {
    private String url;
    private String[] options;
    private String aspectRatio;
    private SurfaceTextureHelper surfaceTextureHelper;
    private Context context;
    private CapturerObserver capturerObserver;

    /**
     * Public constructor accepting the stream url and libVLC configuration.
     *
     * @param url         Media stream url.
     * @param options     List of options to pass on to libVLC.
     * @param aspectRatio Aspect ratio to configure for libVLC.
     */
    public URLVideoCapturer(String url, String[] options, String aspectRatio) {
        this.url = url;
        this.options = options;
        this.aspectRatio = aspectRatio;
    }

    /**
     * Initializes this object by setting up the SurfaceTextureHelper.
     *
     * @param surfaceTextureHelper The SurfaceTextureHelper instance to use.
     * @param context              Android Context object to be used by libVLC.
     * @param capturerObserver     An instance of CapturerObserver to be notified about video frames.
     */
    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context context, CapturerObserver capturerObserver) {
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.context = context;
        this.capturerObserver = capturerObserver;

        surfaceTextureHelper.startListening(this);
    }

    /**
     * Callback to be used by the SurfaceTextureHelper when a video frame is ready.
     *
     * @param videoFrame VideoFrame from the SurfaceTextureHelper.
     */
    @Override
    public void onFrame(VideoFrame videoFrame) {
        capturerObserver.onFrameCaptured(videoFrame);
    }

    /**
     * Begins the stream capture at specified resolution.
     *
     * @param width     Width of the frame.
     * @param height    Height of the frame.
     * @param framerate This parameter is ignored.
     */
    @Override
    public void startCapture(int width, int height, int framerate) {
        capturerObserver.onCapturerStarted(true);
        surfaceTextureHelper.setTextureSize(width, height);

        // Use libVLC to play the stream onto the texture.
        LibVLC libVlc = new LibVLC(context, new ArrayList<>(Arrays.asList(options)));

        MediaPlayer mediaPlayer = new MediaPlayer(libVlc);
        IVLCVout vOut = mediaPlayer.getVLCVout();
        vOut.setWindowSize(width, height);
        vOut.setVideoSurface(surfaceTextureHelper.getSurfaceTexture());
        vOut.attachViews();

        Media videoMedia = new Media(libVlc, Uri.parse(url));
        mediaPlayer.setMedia(videoMedia);
        mediaPlayer.setAspectRatio(aspectRatio);
        mediaPlayer.play();
    }

    /**
     * Stops the video capture, but does not tear down the setup.
     */
    @Override
    public void stopCapture() {
        capturerObserver.onCapturerStopped();
    }

    /**
     * Changes the capture format. Stream will be stopped and started again with new configuration.
     *
     * @param width     Width of the frame.
     * @param height    Height of the frame.
     * @param framerate This parameter is ignored.
     */
    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        stopCapture();
        startCapture(width, height, framerate);
    }

    /**
     * Disposes off this video capturer and the related SurfaceTextureHelper.
     */
    @Override
    public void dispose() {
        surfaceTextureHelper.dispose();

        surfaceTextureHelper = null;
        context = null;
        capturerObserver = null;
    }

    /**
     * Always returns false since this is not a screen casting.
     *
     * @return false.
     */
    @Override
    public boolean isScreencast() {
        return false;
    }
}
