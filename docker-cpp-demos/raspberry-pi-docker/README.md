# Using Docker images for Producer SDK (CPP and GStreamer plugin):

Please follow the four steps below to get the Docker image for Producer SDK (includes CPP and GStreamer plugin) and start streaming to Kinesis Video.

#### Pre-requisite:

This requires docker is installed in your system.

Follow instructions to download and start Docker

* [Docker download instructions](https://www.docker.com/community-edition#/download)
* [Getting started with Docker](https://docs.docker.com/get-started/)

#### Step 1: Build the docker image

Run the following command: 
`docker build -t kinesis-video-producer-sdk-cpp-raspberry-pi .`

This takes some time as it pulls all the dependencies in.

#### Step 2: Find the docker image
Run the following command to find the image id for `kinesis-video-producer-sdk-cpp-raspberry-pi`:
`docker images`


#### Step 3: Start the container
Run the following command to start the kinesis video sdk container.

`sudo docker run -it --device=/dev/video0 --device=/dev/vchiq -v /opt/vc:/opt/vc <image-id> /bin/bash`

You can also run this with the label and latest tag:
`sudo docker run -it --device=/dev/video0 --device=/dev/vchiq -v /opt/vc:/opt/vc kinesis-video-producer-sdk-cpp-raspberry-pi:latest /bin/bash`

#### Step 4: Run the gstreamer sample

Set these environment variables: 
`export GST_PLUGIN_PATH=/opt/amazon-kinesis-video-streams-producer-sdk-cpp/build`
`export LD_LIBRARY_PATH=/opt/amazon-kinesis-video-streams-producer-sdk-cpp/open-source/local/lib`

Start the streaming with the `gst-launch-1.0` command:

`gst-launch-1.0 v4l2src do-timestamp=TRUE device=/dev/video0 ! videoconvert ! video/x-raw,format=I420,width=640,height=480,framerate=30/1 ! omxh264enc control-rate=2 target-bitrate=512000 inline-header=FALSE periodicty-idr=20 ! h264parse ! video/x-h264,stream-format=avc,alignment=au,width=640,height=480,framerate=30/1,profile=baseline ! kvssink stream-name="YOURSTREAMNAME" access-key=YOURACCESSKEY secret-key=YOURSECRETKEY`

