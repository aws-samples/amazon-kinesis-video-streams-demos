### Sample Docker container build and run instructions for Kinesis Video Streams RTSP demo application
#### 1. Install Docker

Follow instructions to download and start Docker

* [ Docker download instructions ]( https://www.docker.com/community-edition#/download )
* [Getting started with Docker](https://docs.docker.com/get-started/)

#### 2. Build Docker image
Download the `Dockerfile` into a folder.  Once the Docker is installed and running, you can then build the docker image by using the following command.

```
  $ docker build -t rtspdockertest .
```
* Get the image id from the previous step (once the build is complete) by running the command `docker images` which will display the Docker images built in your system.

```
  $ docker images
```

* Use the **IMAGE_ID** from the output of the previous command (e.g `f97f1a633597` ) :

```
    REPOSITORY          TAG                 IMAGE ID            CREATED                  SIZE
  rtspdockertest      latest              54f0d65f69b2        Less than a second ago   2.82GB

```
#### Start the Docker container
---

*  Start the Kinesis Video Streams Docker container using the following command:
`docker run -it <IMAGE_ID> /bin/bash`

You can also run this with the label and latest tag:
`sudo docker run -it --device=/dev/video0 --device=/dev/vchiq -v /opt/vc:/opt/vc rtspdockertest:latest /bin/bash`

#### Run the gstreamer sample app with the requisite arguments
`<AWS_ACCESS_KEY_ID> <AWS_SECRET_ACCESS_KEY> ./kinesis_video_gstreamer_sample_app <STREAM_NAME> <RTSP_URL>` 

