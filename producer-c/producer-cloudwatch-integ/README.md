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

The file installs the C Producer SDK libraries, PIC libraries for you and CPP SDK components for you.

NOTE: This project requires setting up of AWS SDK CPP Libraries. The specific components being used are:
1. `events`
2. `monitoring`
3. `logs`

## Running the application

The demo comprises of a simple sample that uses custom constructed frames to capture certain metrics and performance parameters of the C Producer SDK and PIC. To run the sample:

`./kvsProducerSampleCloudwatch <path-to-config-file>`, or
`./kvsProducerSampleCloudwatch`	

Note that if config file is provided and environment variables are exported, JSON file is used to configure the canary app

The application uses a JSON file or environment variables to parse some parameters that can be controlled by the application. It is necessary to provide the absolute path to the JSON file to run the application. A sample config file is provided [here](https://github.com/aws-samples/amazon-kinesis-video-streams-demos/tree/master/producer-c/producer-cloudwatch-integ). If using environment variables, take a look at the [sample shell script](https://github.com/aws-samples/amazon-kinesis-video-streams-demos/tree/master/producer-c/producer-cloudwatch-integ).

On running the application, the metrics are geenrated and posted in the `KinesisVideoSDKCanary` namespace with stream name format:  `<stream-name-prefix>-<Realtime/Offline>-<canary-type>`, where `canary-type` is signifies the type of run of the application, for example, `periodic`, `longrun`, etc.

## Metrics being collected currently

Currently, the following metrics are being collected on a per fragment basis:

| Metric	                 | Frequency	    | Unit         | Description	           
|--------------------|:-------------:|:-------------:|:-------------|
| Outgoing frame rate  | Every key frame	      | Count_Second | Measures the rate at which frames are sent out from the producer. The value is computed in the PIC and the application just emits the metric when requested	
| CurrentViewDuration  | Every key frame	      | Milliseconds | Measures the number of frames in the buffer that have not been sent out in timescale. For example, a current view duration of 2 seconds would indicate that 2 seconds worth of frames are yet to be sent out.
| PutFrameErrorRate	   | 60 seconds	              | Count_Second | Indicates the number of put Frame errors in a fixed duration.	
| ErrorAckRate		   | 60 seconds	              | Count_Second | Rate at which error acks are received
| StorageSizeAvailable | Every key frame	      | Bytes        | Measures the storage size available out of the overall allocated content store. A decrease in this would indicate frames being produced that are not being sent out.
| Persisted Ack Latency| Every callback invocation| Milliseconds | Measures the time between when the frame is sent out to when the ACK is received after persisting
| Received Ack Latency | Every callback invocation| Milliseconds | Measures the time between when the frame is sent out to when the ACK is received after receiving the frame
| Stream error		   | Every callback invocation| None         | This metric emits a 1.0 when the streamErrorReportHandler is invoked. Note that this metric would not show up on Cloudwatch console if no error is encountered
| Total error count    | 60 seconds               | None         | This includes the put frame error count, error ack count and stream error handler invocation count
 
## Jenkins

### Prerequisites

Required Jenkins plugins:
* [Job DSL](https://plugins.jenkins.io/job-dsl/)
* [Blue Ocean](https://plugins.jenkins.io/blueocean/)
* [CloudBees AWS Credentials](https://plugins.jenkins.io/aws-credentials/)
* [Throttle Concurrents](https://plugins.jenkins.io/throttle-concurrents/)

Required Credentials:
* AWS Credentials access key and secret key

Required Script Signature Approvals:
* method hudson.model.ItemGroup getAllItems java.lang.Class
* method hudson.model.Job getBuilds
* method hudson.model.Job getLastBuild
* method hudson.model.Job isBuilding
* method hudson.model.Run getTimeInMillis
* method hudson.model.Run isBuilding
* method jenkins.model.Jenkins getItemByFullName java.lang.String
* method jenkins.model.ParameterizedJobMixIn$ParameterizedJob isDisabled
* method jenkins.model.ParameterizedJobMixIn$ParameterizedJob setDisabled boolean
* method org.jenkinsci.plugins.workflow.job.WorkflowRun doKill
* staticMethod jenkins.model.Jenkins getInstance
* staticField java.lang.Long MAX_VALUE

### Architecture

#### Seeding

Seeding is a meta job that its sole job is to bootstrap other jobs, orchestrator and runners. 
When there's a new change to the seed or the other jobs that were created from the seed, the change will automatically propagate to the other jobs. 

The concept is very similar to [AWS CloudFormation](https://aws.amazon.com/cloudformation/)

![seeding](./docs/seeding.png)
Seed script can be found [here](https://github.com/aws-samples/amazon-kinesis-video-streams-demos/blob/master/producer-c/producer-cloudwatch-integ/jobs/canary_seed.groovy)

#### Orchestration

Orchestration is a process of permuting a set of the canary configuration and delegate the works to the runner. The permutation can be ranging from streaming duration, bitrate, device types, regions, etc.

![orchestrator](./docs/orchestrator.png)

Note that here, the jobs run an end to end scenario from producer SDK to [java parser based consumer SDK](https://github.com/aws-samples/amazon-kinesis-video-streams-demos/tree/master/consumer-java/aws-kinesis-video-producer-sdk-canary-consumer). 
Orchestrator script can be found [here](https://github.com/aws-samples/amazon-kinesis-video-streams-demos/blob/master/producer-c/producer-cloudwatch-integ/jobs/orchestrator.groovy)

#### Update Flow

Finally, our canary is up and running. But, now, we want to make changes to the canary or update the SDK version without shutting down the whole canary.

To achieve this, the update process uses the rolling update technique:

![updater](./docs/update-flow.png)
Rolling update and runner script can be found [here](https://github.com/aws-samples/amazon-kinesis-video-streams-demos/blob/master/producer-c/producer-cloudwatch-integ/jobs/runner.groovy)


## Logging

Cloudwatch logging capability is added in the samples! A call to putLogEventsAsync is made every
minute to push the set of logs accumulated in the duration to cloudwatch. To get more information
about Cloudwatch logging, please refer to: 
https://sdk.amazonaws.com/cpp/api/LATEST/namespace_aws_1_1_cloud_watch_logs.html

If you would like to use file logger instead, you could run `export ENABLE_FILE_LOGGER=TRUE`
This will enable file logging and disable cloudwatch logging.

Cloudwatch log files are generated with the following name: `<stream-name-prefix>-<Realtime/Offline>-<canary-type>-<timestamp>`

## Cloudwatch Metrics

Every metric is available in two dimensions:
1. Per stream: This will be available under `KinesisVideoSDKCanary->ProducerSDKCanaryStreamName` in cloudwatch console
2. Aggregated over all streams based on `canary-type`. `canary-type` is set by running `export CANARY_LABEL=value`. This will be available under `KinesisVideoSDKCanary->ProducerSDKCanaryType` in cloudwatch console


## References
1. http://sdk.amazonaws.com/cpp/api/LATEST/index.html
2. https://docs.aws.amazon.com/sdk-for-cpp/v1/developer-guide/examples-cloudwatch.html
3. https://github.com/awslabs/amazon-kinesis-video-streams-producer-c

