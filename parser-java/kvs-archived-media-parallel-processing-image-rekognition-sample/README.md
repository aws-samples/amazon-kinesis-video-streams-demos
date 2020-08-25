# KVS Archived Media Retreival with Parallel Processing Rekognition Integration 

### kvs-archived-media-parallel-processing-image-rekognition-sample

This application retrieves archived media and sends specified image frames to Rekognition to log detected labels to stdout. It also allows users to input a selected amount of threads to increase performance via parallel processing. The input timerange is partitioned into a set amount of tasks which are submitted to a thread pool. Processing speeds are significantly increased by increasing number of threads and allow for longer lengths of archived media to be consumed and analyzed in a shorter amount of time. Utilizing a higher number of threads than 10 can lead to too many requests to GetMediaForFragmentList and may result in some errors.    
**Set up**

1. Clone this repository 

```
git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git
```

2. Clone the parser library

```
git clone https://github.com/aws/amazon-kinesis-video-streams-parser-library.git
```

3. Navigate to the sample

```
cd amazon-kinesis-video-streams-demos/parser-java/kvs-archived-media-parallel-processing-image-rekognition-sample
```

4. Build the jar
```
mvn package
```
```
java -jar target/kvs-archived-media-parallel-processing-image-rekognition-sample-1.0-SNAPSHOT.jar -s [stream] -st [start time] -et [end time] -sr [sample rate]  -th [threads]

java -jar target/kvs-archived-media-parallel-processing-image-rekognition-sample-1.0-SNAPSHOT.jar -stream [stream] -startTime [start time] -endTime [end time] -sampleRate [sample rate]  -threads [threads]

Example:

java -jar target/kvs-archived-media-parallel-processing-image-rekognition-sample-1.0-SNAPSHOT.jar -s archived_stream -st "04/08/2020 14:36:50" -et "04/08/2020 14:37:16" -sr 0 -th 8
```
   
