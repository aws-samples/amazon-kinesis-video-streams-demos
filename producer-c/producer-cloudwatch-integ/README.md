Welcome to Kinesis Video Streams C Producer SDK integration with AWS Cloudwatch Metrics!


## Introduction

The integration is developed keeping in mind the need to monitor various performance parameters of the SDK itself. The app can be used to run long term canaries on your devices with maybe, few modifications to the build system. This demo provides an introduction on how the SDK can be integrated with the Cloudwatch APIs to get some metrics out periodically.

Note 1: This is intended to be a minimal set up requirement to get started. Any other libraries you might need can be added in the `CMake/Dependencies` directory as a new `CMakeLists.txt` file.

Note 2: Currently, this demo is tested on Linux and MacOS.

## Requirements

1. `pkg-config`
2. `automake`
3. `cmake`
4. `make`

## Build
To download run the following command:

`git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git`

Configure
Create a build directory in the newly checked out repository, and execute CMake from it.

1. `mkdir -p amazon-kinesis-video-streams-demos/build` 
2. `cd amazon-kinesis-video-streams-demos/build`
3. `cmake ..`

The file installs the C Producer SDK libraries and PIC libraries for you.

NOTE: This project requires setting up of AWS SDK CPP Libraries. The specific components being used are:
1. `events`
2. `monitoring`
3. `logs`

You can refer to the build steps here: `https://github.com/aws/aws-sdk-cpp` . The synopsis of the build steps utlized to get this project working is summarized below:

1. Create a directory that will store all the AWS SDK required libraries:
`mkdir aws-libs`

2. Install AWS Common Library
```
git clone https://github.com/awslabs/aws-c-common
cd aws-c-common
mkdir build && cd build
cmake .. -DBUILD_SHARED_LIBS=ON -DCMAKE_INSTALL_PREFIX=<path/to/aws-libs> 
make 
sudo make install
cmake .. -DBUILD_SHARED_LIBS=OFF -DCMAKE_INSTALL_PREFIX=<path/to/aws-libs> 
make 
sudo make install
```

3. Install AWS Checksums Library
```
git clone https://github.com/awslabs/aws-checksums
cd aws-checksums
mkdir build && cd build
cmake .. -DBUILD_SHARED_LIBS=ON -DCMAKE_INSTALL_PREFIX=<path/to/aws-libs> 
make 
sudo make install
cmake .. -DBUILD_SHARED_LIBS=OFF -DCMAKE_INSTALL_PREFIX=<path/to/aws-libs> 
make 
sudo make install
```

4. Install AWS Event Streams Library
```
git clone https://github.com/awslabs/aws-c-event-stream
cd aws-c-event-stream
mkdir build && cd build
cmake .. -DBUILD_SHARED_LIBS=ON  -DCMAKE_MODULE_PATH=<path/to/aws-libs>/lib/cmake -DCMAKE_INSTALL_PREFIX=<path/to/aws-libs> 
make 
sudo make install
cmake .. -DBUILD_SHARED_LIBS=OFF -DCMAKE_INSTALL_PREFIX=<path/to/aws-libs> 
make 
sudo make install
```

5. Finally, install the AWS CPP SDK library. This step takes some time. 
```
git clone https://github.com/aws/aws-sdk-cpp.git
cd aws-sdk-cpp
mkdir build && cd build
export LD_LIBRARY_PATH=<path/to/aws-libs>/lib/
cmake .. -DBUILD_ONLY="monitoring;events;logs" -DBUILD_DEPS=OFF -DCMAKE_MODULE_PATH=<path/to/aws-libs>/lib/cmake/ -DCMAKE_PREFIX_PATH="<path/to/aws-libs>/lib/aws-c-event-stream/cmake/;<path/to/aws-libs>/lib/aws-c-common/cmake/;<path/to/aws-libs>/lib/aws-checksums/cmake/;<path/to/aws-libs>/lib/cmake/"
make 
sudo make install
```

The cloudwatch library will now be ready to use!


## Running the application

The demo comprises of a simple sample that uses custom constructed frames to capture certain metrics and performance parameters of the C Producer SDK and PIC. To run the sample:

`./kinesis_video_cproducer_video_only_sample_cw <stream-name> <streaming-type> <fragment-size-in-bytes>`
	where, `streaming-type` can be 0 (real-time mode) or 1 (offline mode)
Since we use custom constructed frames and not the ones directly from the video source, there is provision to control the fragment size through `fragment-size-in-bytes`.

The application generates a stream name of the format: `<stream-name>-<realtime/offline>-<fragment-size-in-bytes>`

On running the application, the metrics are geenrated and posted in the `KinesisVideoSDKCanary` namespace. For every new stream name/parameters the application is run with, a new dimension with the metrics are generated. If you would like to modify your namespace, you can do so here:
`https://github.com/aws-samples/amazon-kinesis-video-streams-demos/blob/c525dc65ea543866dff6cf954617d077b1cb58d0/producer-c/producer-cloudwatch-integ/CanaryStreamCallbacks.cpp#L171`


## Metrics being collected currently

Currently, the following metrics are being collected on a per fragment basis:
* ReceivedAckLatency
* PersistedAckLatency
* FrameRate
* CurrentViewDuration

## Debugging

1. If you encounter the following error on MacOS while building libopenssl:
```
/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/clang/11.0.0/include/inttypes.h:30:15: fatal error: 'inttypes.h' file not found
#include_next <inttypes.h>
```

Please run the following command:
`export MACOSX_DEPLOYMENT_TARGET=<version>`

For example, for MacOS version 10.14.x, run this command and re-run `cmake`:
`export MACOSX_DEPLOYMENT_TARGET=10.14`

If the above does not work, update xcode and retry

2. For any issues related to setting up the AWS CPP SDK, please refer to the repository `https://github.com/aws/aws-sdk-cpp`


## References
1. http://sdk.amazonaws.com/cpp/api/LATEST/index.html
2. https://docs.aws.amazon.com/sdk-for-cpp/v1/developer-guide/examples-cloudwatch.html
3. https://github.com/awslabs/amazon-kinesis-video-streams-producer-c

