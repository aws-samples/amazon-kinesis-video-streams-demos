Welcome to Kinesis Video Streams CPP Producer SDK integration with AWS Cloudwatch Metrics!

## Introduction

The integration is developed keeping in mind the need to monitor various performance parameters of the SDK itself. The app can be used to run long term canaries on your devices with maybe, few modifications to the build system. This demo provides an introduction on how the SDK can be integrated with the Cloudwatch APIs to get some metrics out periodically.

Note 2: Currently, this demo is tested on Linux.

## Requirements

1. `pkg-config`
2. `automake`
3. `cmake`
4. `make`


## Build

To download the repository, run the following command:

`git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git`

Create a build directory in the newly checked out repository, and execute CMake from it.

1. `git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git -b new-canary-producer-c`
2. `mkdir -p amazon-kinesis-video-streams-demos/canary/producer-cpp/build`
3. `cd amazon-kinesis-video-streams-demos/canary/producer-cpp/build`
4. `cmake ..`

NOTE: This project requires setting up of AWS SDK CPP Libraries. The specific components being used are:
1. `events`
2. `monitoring`
3. `logs`

## Running the application

The demo comprises of a simple sample that uses GStreamer-generated frames to capture certain metrics and performance parameters of the CPP Producer SDK and its PIC. To run the sample:

`./kvsProducerSampleCloudwatch`	

## Configurable Parameters
The following environment variables can be set to configure the Canary.
* `CANARY_STREAM_NAME` -- Name of the destination Kinesis video stream
* `CANARY_RUN_SCENARIO` -- Realtime/Offline
* `CANARY_STREAM_TYPE` --  Continuous/Intermittent
* `CANARY_LABEL` -- CloudWatch dimension for aggregate metrics to be grouped to
* `CANARY_CP_URL` -- Specified cpUrl
* `CANARY_FRAGMENT_SIZE` --  Size of fragments sent in milliseconds
* `CANARY_DURATION` -- Duration in seconds
* `CANARY_STORAGE_SIZE` -- Size in bytes
* `CANARY_FPS` -- Frames per second of generated test video

On running the application, the metrics are generated and posted in the `KinesisVideoSDKCanary` namespace with stream name format:  `<stream-name-prefix>-<Realtime/Offline>-<canary-type>`, where `canary-type` signifies the type of run of the application, for example, `periodic`, `longrun`, etc.

## Cloudwatch Metrics

Every metric is available in two dimensions:
1. Per stream: This is available under `KinesisVideoSDKCanary->ProducerSDKCanaryStreamName` in the cloudwatch console
2. Aggregated over all streams based on `canary-type`. `canary-type` is set by running `export CANARY_LABEL=value`. This is available under `KinesisVideoSDKCanary->ProducerSDKCanaryType` in the cloudwatch console
