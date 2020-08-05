# KVS Archived Media Retreival with Parallel Processing Rekognition Integration 

### kvs-archived-media-parallel-processing-image-rekognition-sample</h3>

This application retrieves archived media and sends specified image frames to Rekognition to log detected labels to stdout and allows for new threads and tasks commandline options to allow for parallel processing. Users can specify how many tasks to split up the time range into and how many threads to perform the label detection for those partitioned time ranges. Processing speeds are increased by increasing the number of tasks and threads and allows for longer lengths of archived media to be consumed and analyzed in a shorter amount of time. Utilizing a high number of threads like 12 and up can cause issues with too many requests to GetMediaForFragmentList. Number of tasks must be set such that each partition is at least approximately <= 20 seconds so GetMediaForFragmentList works correctly.
    
**Set up**

1. Clone this repository 

```
git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git
```

2. Navigate to the sample

```
cd amazon-kinesis-video-streams-demos/parser-java/kvs-archived-media-parallel-processing-image-rekognition-sample
```

3. Build the jar
```
mvn package
```
```
java -jar target/kvs-archived-media-parallel-processing-image-rekognition-sample-1.0-SNAPSHOT.jar -s [stream] -st [start time] -et [end time] -sr [sample rate]  -t [tasks]  -th [threads]

java -jar target/kvs-archived-media-parallel-processing-image-rekognition-sample-1.0-SNAPSHOT.jar -stream [stream] -startTime [start time] -endTime [end time] -sampleRate [sample rate] -tasks [tasks] -threads [threads]

Example:

java -jar target/kvs-archived-media-parallel-processing-image-rekognition-sample-1.0-SNAPSHOT.jar -s archived_stream -st "04/08/2020 14:36:50" -et "04/08/2020 14:37:16" -sr 0 -t 12 -th 8
```
   
