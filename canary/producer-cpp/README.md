Welcome to Kinesis Video Streams CPP Producer SDK integration with AWS Cloudwatch Metrics!

## Requirements

1. `pkg-config`
2. `automake`
3. `cmake`
4. `make`


## Build Steps

`git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git -b new-canary-producer-c`
`mkdir -p amazon-kinesis-video-streams-demos/canary/producer-cpp/build`
`cd amazon-kinesis-video-streams-demos/canary/producer-cpp/build`
`cmake ..`


NOTE: This project requires setting up of AWS SDK CPP Libraries. The specific components being used are:
1. `events`
2. `monitoring`
3. `logs`