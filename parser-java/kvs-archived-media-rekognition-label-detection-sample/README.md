# KVS Archived Media Retrieval with Image Rekognition Integration



### kvs-archived-media-rekognition-label-detection-sample

The archived media retrieval APIs can be useful for media analysis of previously ingested video. This sample integrates the retrieval of archived media from a Kinesis Video Stream with the image label detection APIs from AWS Rekognition. Users specify the stream name, time range, and a sample rate to retrieve the archived media and display it locally in JFrame with bounding boxes rendered around detected instances. Other detected labels are logged to stdout as well. The sample rate determines every n frames to send to Rekognition. A sample rate of 0 indicates the application to only send key frames to Rekognition.
   
**Set up**

1. Clone this repository 

```
git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git
```

2. Navigate to the sample

```
cd amazon-kinesis-video-streams-demos/parser-java/kvs-archived-media-rekognition-label-detection-sample
```

3. Build the jar
```
mvn package
```

```
java -jar target/kvs-archived-media-rekognition-label-detection-sample-1.0-SNAPSHOT.jar -s [stream] -st [start time] -et [end time] -sr [sample rate]

java -jar target/kvs-archived-media-rekognition-label-detection-sample-1.0-SNAPSHOT.jar -stream [stream] -startTime [start time] -endTime [end time] -sampleRate [sample rate]

Example:
   
java -jar target/kvs-archived-media-rekognition-label-detection-sample-1.0-SNAPSHOT.jar -s archived_stream -st "20/07/2020 14:19:15" -et "20/07/2020 14:19:20" -sr 0
```