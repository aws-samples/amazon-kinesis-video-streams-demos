# KVS Archived Media Retrieval

### kvs-archived-media-retrieval-sample
Analysis of archived media previously ingested by Kinesis Video Streams can be useful for media play back and analysis of retained stream data. The AWS Kinesis Video Streams Parser library contains a ListFragments API that retrieves fragments for a video stream over a requested time period and a GetMediaForListFragments API that retrieves media given a list of fragment numbers. 

Using these APIs, this sample allows users to retrieve archived media from a specified Kinesis Video Stream and then displays the media locally via JFrame. Commandline arguments are included to allow for a specified stream name and time range of media to be played back. Long options are also supported for command line arguments.

**Set up**

1. Clone this repository 

```
git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git
```

2. Navigate to the sample

```
cd amazon-kinesis-video-streams-demos/parser-java/kvs-archived-media-retrieval-sample
```

3. Build the jar
```
mvn package
```

<strong>Usage</strong>
```
java -jar target/kvs-archived-media-retrieval-sample-1.0-SNAPSHOT.jar -s [stream] -st [start time] -et [end time]

java -jar target/kvs-archived-media-retrieval-sample-1.0-SNAPSHOT.jar -stream [stream] -startTime [start time] -endTime [end time]

Example:

java -jar target/kvs-archived-media-retrieval-sample-1.0-SNAPSHOT.jar -s archived_stream -st "20/07/2020 14:19:15" -et "20/07/2020 14:19:20"
   ```
   
    


    
    
    
    
    

	
    