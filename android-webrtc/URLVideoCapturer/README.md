# URL VideoCapturer for WebRTC

This solution is an Android library project that shows how to enable streaming media from IP cameras using any Android hardware to WebRTC viewer(s) using AWS Kinesis Video Streams Android WebRTC SDK. This Android library implements a WebRTC VideoCapturer for video playback from a media URL. You can use it just like any other VideoCapturer in WebRTC. For example, you can use this implementation along with [Amazon Kinesis Video Streams Android WebRTC SDK](https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-android) in order to add video streams from an RTSP camera into the WebRTC session. Such streaming from an IP camera will be through the Real time Streaming Protocol (RTSP) URL provided by the camera (The media source). 

Here is an illustration of how the different components work together.

![](illustration.png)

## License

This project is licensed under the Apache-2.0 License.

This project has a dependency on [libvlc](https://mvnrepository.com/artifact/org.videolan.android/libvlc-all).
