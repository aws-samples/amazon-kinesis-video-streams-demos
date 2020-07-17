## KVS Browser-based Ingestion ##


There has been customer asks for sending video streams from browser to KVS. Browser environment is attractive to some customers because of its ubiquity and ease of deployment. The current solution that we recommend to customers is sending MKV files to S3 and using a lambda to do putMedia there. However, orchestrating this solution is not simple for customers as they need to do some heavy processing to get the audio and video data in browser, package it into MKV, and write lambdas for uploading to S3 and KVS. This solution is also slow - writing in S3 and having to use Java Producer SDK in lambda being long poles. 

The aim of this project is to send audio and video stream from camera and microphone from your browser into a KVS stream without a proxy server or lambda. While doing so, ensuring that the video is in MKV format, uses H.264 codec for video and AAC for audio. 

Currently available via Chrome, there four important tasks that this repository helps perform
1. Obtaining the video from the webcam/ uploading video file
2. Converting it to containers and codecs expected by the playback
3. Sending to the putMedia API via POST calls
4. Viewing the stream on the browser


### Setup ###

1. Clone the repository as
```
    git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git
```

2. Go to `browser-based-ingestion` folder. Add the `lib` and `src` folders from that folder to your project to use this repository. Include the code from `lib` into your scripts in your HTML page with the following 
```
    <script src="../lib/aws-sdk-2.714.2.min.js"></script>
    <script src="../lib/ffmpeg.min.js"></script>
    <script src="../lib/RecordRTC.js"></script>
```
2. You can import functions from this repository as 
``` 
    import { kinesisVideo } from '../src/kinesisVideo.js'
    import { createStream } from '../src/createStream.js'
    import { getDataEndpoint } from '../src/getDataEndpoint.js'
    import { getBlobFromWebcam, getBlobFromFile, stopRecording } from '../src/getProcessedVideo.js'
    import { putMedia } from '../src/putMediaCall.js'
```

3. While adding the javascript file which uses functions from this project to script tag, please ensure that you add it with `type="module"` since `import` and `export` have been used in the underlying code for this project
```
    <script type="module" src="index.js"></script>
```

### Example ###

1. To run the example, go to `browser-based-ingestion` folder of your cloned repository and start an http-server using the following steps
```
    brew install http-server
    http-server -p 8080 -o
```
2. To view the stream, go to aws-samples.github.io/amazon-kinesis-video-streams-media-viewer/ and enter the your credentials and details of the endpoint and stream in use.

### Code Documentation ###

1. `getBlobFromWebcam(service, region, accessKeyID, secretAccessKey, sessionToken, dataEndpoint, streamName, audio, height, width, frameRate, latency, webcam = null)` is an asynchronous function that records video from the webcam, transforms it and sends it to the putMedia API. It can be imported as 
    `import { getBlobFromWebcam } from './src/getProcessedVideo.js'` 
    It accepts the following arguments:
```
        service: the service to send requests to
                Example: kinesisvideo
        region: the region to send service requests to. See AWS.KinesisVideo.region for more information
                Example: us-west-2 
        accessKeyID: your AWS access key ID, 
        secretAccessKey: your AWS secret access key, 
        sessionToken: the optional AWS session token to sign requests with,
        dataEndpoint: The endpoint that a service will talk to, for example, 'https://ec2.ap-southeast-1.amazonaws.com',
                    In this case, this is the result of the GetDataEndpoint request which returns a JSON as 
                    data = {
                        "DataEndpoint": "string"
                    } 
                    So, here the value to be sent to dataEndpoint is data.DataEndpoint or data["DataEndpoint"]
        streamName: The name of the stream to which putMedia API calls are to be made
        audio: A boolean that is set to true if you want to send audio, else false,
        height: height of the video being recorded from webcam in pixels
                Example: 480, 
        width: width of the video being recorded from webcam in pixels
                Example: 640, 
        frameRate: frame-rate of the video being recorded in frames-per-second
                Example: 30, 
        latency: frequency of putMedia API calls in milliseconds. This is also the latency of the video, since a recording of 'latency' milliseconds will be recorded before being   sent.
                Example: 2000 (for 2 seconds)
        webcam: (optional) A video element that can be used to view the stream that is being sent
                Example: <video id="webcam" width="640px" height="480px"></video>
                        const webcam = document.getElementById('webcam')
```


<<<<<<< HEAD
2. `getBlobFromFile(service, region, accessKeyID, secretAccessKey, sessionToken, dataEndpoint, streamName, inputFile, h264)` is an asynchronous function that accepts a file, transforms it to the expected format and sends it to the putMedia API. It can be imported as 
    `import { getBlobFromFile } from './src/getProcessedVideo.js'` 
    It accepts the following arguments:
```
=======
2. `getBlobFromFile` is an asynchronous function that accepts a file, transforms it to the expected format and sends it to the putMedia API. It can be imported as 
    `import { getBlobFromFile } from './getProcessedVideo.js'` 
    It accepts the following arguments:
```javascript
>>>>>>> 963a0d6b6d15d9dd90964d8c36f77fbad695c255
        service: the service to send requests to
                Example: kinesisvideo
        region: the region to send service requests to. See AWS.KinesisVideo.region for more information
                Example: us-west-2 
        accessKeyID: your AWS access key ID, 
        secretAccessKey: your AWS secret access key, 
        sessionToken: the optional AWS session token to sign requests with,
        dataEndpoint: The endpoint that a service will talk to, for example, 'https://ec2.ap-southeast-1.amazonaws.com',
                    In this case, this is the result of the GetDataEndpoint request which returns a JSON as 
                    data = {
                        "DataEndpoint": "string"
                    } 
                    So, here the value to be sent to dataEndpoint is data.DataEndpoint or data["DataEndpoint"]
        streamName: A name for the stream that you are creating. (The stream name is an identifier for the stream, and must be unique for each account and region),
        inputFile: The file that is to be streamed by sending it to putMedia API
                    Example: <input type="file" name="inputFile" id = "inputFile">
                            const inputFile = document.getElementById('inputFile').files[0] 
        inputFiletype: A boolean that is set to true if the video uploaded contains H.264 frames, else set to false
                        Please note that if the video is not MKV or WebM with H.264 frames then the transformation will require transcoding of the frames and may take considerable amount of time.
```


3. `transformVideo(videoUint8Array, streamName, h264)` is an asynchronous function that is used to transform a video to expected container(MKV), audio(AAC) and video(H.264 AvCC) codecs as expected by KVS Playback for streaming. 
    The transformation of videos to expected formats in getBlobFromFile and getBlobFromWebcam is done using this function. It can be imported as 
    `import { transformVideo } from './src/getProcessedVideo.js'` 
    It accepts the following arguments:
```
        videoUint8Array: A Uint8 Array of the video to be transformed
                        
                    Note:    
                    A blob can be converted to a uint8 array as 
                        const uint8Array = new Uint8Array(await blob.arrayBuffer())
                    A video input file can be converted to uint8 array as 
                        const reader = new FileReader()
                        reader.onload = async function (e) {
                            const uint8Array = new Uint8Array(e.target.result)
                        }
                        reader.readAsArrayBuffer(inputFile)

        streamName: The name of the stream to which putMedia API calls are to be made
```


4. `stopRecording()` is a function that is used to stop the ongoing recording started using getBlobFromWebCam. It can be imported as 
    `import { stopRecording } from './src/getProcessedVideo.js'`
        

5. `putMedia(videoFile, service, region, accessKeyID, secretAccessKey, sessionToken, dataEndpoint, streamName)` is a function that makes API Calls to PutMedia API on the ingestion side of KVS. It can be imported as 
    `import { putMedia } from './src/putMediaCall.js'` 
    It accepts the following arguments:
```
        videoFile: Uint8 Array of a video file with the following constraints:
                    Track 1 should be video with H.264 AvCC codec
                    Track 2 should be audio with AAC codec
                    The container should be MKV
        service: the service to send requests to
                Example: kinesisvideo
        region: the region to send service requests to. See AWS.KinesisVideo.region for more information
                Example: us-west-2 
        accessKeyID: your AWS access key ID, 
        secretAccessKey: your AWS secret access key, 
        sessionToken: the optional AWS session token to sign requests with,
        dataEndpoint: The endpoint that a service will talk to, for example, 'https://ec2.ap-southeast-1.amazonaws.com',
                    In this case, this is the result of the GetDataEndpoint request which returns a JSON as 
                    data = {
                        "DataEndpoint": "string"
                    } 
                    So, here the value to be sent to dataEndpoint is data.DataEndpoint or data["DataEndpoint"]
        streamName:  The name of the stream to which putMedia API calls are to be made
```



6. `kinesisVideo(accessKeyID, secretAccessKey, sessionToken, region, endpoint)` is a function that is used to create a service interface object with the arguments as parameters and return the same. It can be imported as 
    `import { kinesisVideo } from './src/kinesisVideo.js'` 
    It accepts the following arguments:
```
        accessKeyID: your AWS access key ID, 
        secretAccessKey: your AWS secret access key, 
        sessionToken: the optional AWS session token to sign requests with
        region: the region to send service requests to. See AWS.KinesisVideo.region for more information
                Example: us-west-2 
        endpoint: The endpoint URI to send requests to. The default endpoint is built from the configured region. The endpoint should be a string like 'https://{service}.{region}.amazonaws.com' 
```


7. `createStream(kinesisvideo, params)` is used to create a new stream to which videos can be sent. It returns a promise which can be resolved to get a response containing the name of the stream. It can be imported as 
    `import { createStream } from './src/createStream.js'` 
    It accepts the following arguments:
```
    kinesisvideo: It is a kinseis video object which is a service interface object. It can be obtained from the kinesisVideoObject function or refer (https://docs.aws.amazon.com/AWSJavaScriptSDK/latest/AWS/KinesisVideo.html
    params: 
    It is a JSON object of parameters below
        {"DeviceName": "foo",
        "StreamName": "foo",
        "MediaType": "foo",
        "KmsKeyId": "foo",
        "DataRetentionInHours": 1,
        "Tags": 
        {"key1": "foo",
        "key2": "foo"},
        "IngestionConfiguration": 
        {"Rtsp": 
            {"Uri": "foo",
            "Protocol": "RTSP",
            "Auth": 
            {"Type": "BASIC",
            "Username": "foo",
            "Password": "foo"},
            "VpcConfiguration": 
            {"SecurityGroupIds": 
                ["foo", "foo"],
            "SubnetIds": 
                ["foo", "foo"],
            "DeviceCidrBlocks": 
                ["foo", "foo"]
            }
            },
        "RoleARN": "foo"}
        }
```
                    

8. `getDataEndpoint(kinesisvideo, streamName)` is used to obtain the endpoint needed to make the POST request while making putMedia API calls. It can be imported as 
    `import { getDataEndpoint } from './src/getDataEndpoint.js'` 
    It accepts the following parameters:
```
        kinesisvideo: It is a kinseis video object which is a service interface object. It can be obtained from the kinesisVideoObject function or refer (https://docs.aws.amazon.com/AWSJavaScriptSDK/latest/AWS/KinesisVideo.html
        streamName:  The name of the stream to which putMedia API calls are to be made 
```