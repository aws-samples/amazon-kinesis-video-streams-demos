Welcome to Kinesis Video Streams CPP Producer SDK integration with AWS Cloudwatch Metrics!

## Requirements

1. `pkg-config`
2. `automake`
3. `cmake`
4. `make`


## Build Steps

1. `git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git -b new-canary-producer-c`
2. `mkdir -p amazon-kinesis-video-streams-demos/canary/producer-cpp/build`
3. `cd amazon-kinesis-video-streams-demos/canary/producer-cpp/build`
4. `cmake ..`


NOTE: This project requires setting up of AWS SDK CPP Libraries. The specific components being used are:
1. `events`
2. `monitoring`
3. `logs`