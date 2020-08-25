# KVS Label Detection Web Application Sample


By using parallel processing, ML scene and object detection of images in archived Kinesis Video Streams can be performed in a shorter amount of time. This web application sample demonstrates Kinesis Video Streams integration with AWS Rekognition image detection APIs whilst utilizing concurrency for performance boosts. 

In this sample, users input a stream with archived media and the start and end timestamps for the time range that they want to perform image recognition on. Furthermore, users can specify an option for the number of threads and a sample rate (sample rate of N indicates that every N frames will be sent to AWS Rekognition while a sample rate of 0 indicates key frames only).

After submitting the form users can see all the different archived video stream segments stored in the database. By selecting a specified archived video stream segment, the ML processing of the frames will begin. Once finished, the frames are displayed in chronological order with timestamps to the user in an image carousel. In addition, at the top of the page there will be a dropdown button for both labels and timestamps for the duration of the media. By selecting a label, the dropdown for timestamps will then display all the corresponding timestamps at which that label was detected. By selecting any timestamp, the frame viewer will immediately display the frame at that moment.

<Strong>Set up</Strong>

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
cd amazon-kinesis-video-streams-demos/parser-java/label-detection-web-app
```

4. Build the jar
```
mvn package
```



To run the application, users can either run the main function in the LabelDetectionWebApplication file or run this command from the commandline to start the backend.

```
./mvnw spring-boot:run
```

In the src/main/frontend folder, run 

```
npm start
```

Then, users can access the application at localhost:3000 in the web browser. There, users will be prompted to enter stream information.


[![Stream-Form.png](https://i.postimg.cc/hG4ZS4Q7/Stream-Form.png)](https://postimg.cc/pm7JCHZ2)

Once submitted, users are then redirected to a page containing all the previously submitted streams. 


[![Streams.png](https://i.postimg.cc/mZ9jW1p5/Streams.png)](https://postimg.cc/YjpY10H6)


By selecting an archived stream segment via the View Rekognized Images button, users will then be redirected to the frame viewer page. The media is then fetched and processed and finally displayed to the user.


[![Stream.png](https://i.postimg.cc/fT4HVf0s/Stream.png)](https://postimg.cc/z3jwMWj2)

In the frame viewer, there's a dropdown for all the detected labels in the video and the corresponding timestamps for that label. Clicking on a timestamp redirects the image carousel directly to that frame.


