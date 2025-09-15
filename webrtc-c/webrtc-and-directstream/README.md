# KVS WebRTC and Direct Stream Simultaneously
## Overview

Added a gstreamer app sample (`kvsWebRTCAndDirectStream.c`) to support streaming video to KVS with WebRTC SDK (for real-time use) and Stream Producer SDK (for near-realtime video ingestion), simultaneously from 1 camera source.

This sample is only tested on Raspberry Pi equipped with USB Camera. 

## Prerequisites

- AWS Account with configured:
  - Kinesis Video Streams
  - IAM role with appropriate permissions

## Installation and Configuration

1. Clone this repository to your Raspberry Pi with submodules:
   ```
   git clone --recurse-submodules <repository-url>
   ```
   
   Or if already cloned, initialize submodules:
   ```
   git submodule update --init --recursive
   ```

2. Copy the sample file to the WebRTC SDK samples directory:
   ```
   cp kvsWebRTCAndDirectStream.c amazon-kinesis-video-streams-webrtc-sdk-c/samples/
   ```

3. Configure your AWS credentials on the Raspberry Pi

### Building the AWS SDKs

#### Building the KVS Producer SDK

```
cd amazon-kinesis-video-streams-producer-sdk-cpp
mkdir -p build
cd build
cmake ..
make
```

#### Building the KVS WebRTC SDK

```
cd amazon-kinesis-video-streams-webrtc-sdk-c
mkdir -p build
cd build
cmake ..
make
```

## Usage

1. Configure your AWS credentials on the Raspberry Pi

2. Go to `amazon-kinesis-video-streams-webrtc-sdk-c/build/` and run `./samples/kvsWebRTCAndDirectStream <signaling channel name> <kvs stream name>`

## License

This project uses components from AWS Kinesis Video Streams SDKs which are licensed under the Apache License 2.0.