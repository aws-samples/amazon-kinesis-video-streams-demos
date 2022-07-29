# KVS GStreamer Plugin

Kinesis Video GStreamer Plugin allows an easy integration with GStreamer pipeline and run both the KVS Producer and KVS WebRTC client. By Default both of the clients are run with the default settings. The frames that are produced into the KVS stream will  be sent over the RTP Transceiver to the WebRTC peers, allowing to use a single encoder pipeline. As the streaming packets are separate from each other, the effective bandwidth is multiplied based on how many WebRTC peers are connected.

## Build

### Download
To download run the following command:

`git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git`

You will also need to install `pkg-config` and `CMake` and a build enviroment

The codebase has a build and runtime dependency on GStreamer and GStreamer Development Libraries.

Refer to platform specific instructions for help with GStreamer installation, build and runtime at [Windows](https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp/blob/master/docs/windows.md), [MacOS](https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp/blob/master/docs/macos.md) and [Linux](https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp/blob/master/docs/linux.md) 

### Configure
Create a build directory in the newly checked out repository, and execute CMake from it.

`mkdir -p amazon-kinesis-video-streams-demos/gst/gst-kvs-plugin/build; cd amazon-kinesis-video-streams-demos/gst/gst-kvs-plugin/build; cmake .. `

### Build
To build the library and the provided samples run make in the build directory you executed CMake.

`make`

### Run

A very basic example of a GStreamer pipeline to run on Mac


```sh
export GST_PLUGIN_PATH=`pwd`/build
gst-launch-1.0 autovideosrc !  vtenc_h264_hw max-keyframe-interval=30 bitrate=500 ! kvsplugin stream-name=ScaryTestStream channel-name="ScaryTestChannel" log-level=3
```

This will launch the default camera video stream only with hardware-accelerated encoder with 1sec fragments and up-to 500Kbps stream. Use or create a stream by the given name and a channel by its name. Will use INFO level logging.


### Prerequisites

The plugin itself does not require any extra prerequisites other than the ones defined in

[KVS C Producer](https://github.com/awslabs/amazon-kinesis-video-streams-producer-c)

[KVS WebRTC](https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-c)

### Auth integration
KVS GStreamer Plugin can be integrated with 3 types of credential providers
* Static credential provider using AWS Access Key and Secret Key using either environment variables or specifying in the GStreamer pipeline [properties](#Properties)
* IoT credential provider using IoT credential provider specific pipeline [properties](#Properties)
* File-based credential provider using File-based credential provider specific pipeline [properties](#Properties)

### Turning on and off
KVS GStreamer Plugin allows the applications to control which component they need to use and when. The initial selection can be done by supplying parameters controlling whether to enable WebRTC connection and KVS streaming. However, the plugin also listens to upstream custom events and enable/disable the appropriate client. This is very useful in cases where the application needs to take a control when to stream or not. As an example a GStreamer pipeline element could run inference to detect certain features and only then start/stop streaming. 

## Properties
Many of the aspects of KVS Producer and WebRTC can be controlled by the properties of the initial parameters that can be passed into the KVS GStreamer plugin - either via specifying in the gst-launch command line or specifying in the integrated application parameters list. These applications are listed below. Most up-to-date information can be retrieved by executing 

```sh
gst-inspect-1.0 kvsplugin
```

## Architecture
