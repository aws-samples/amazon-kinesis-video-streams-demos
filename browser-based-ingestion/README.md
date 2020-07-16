### KVS Browser based Ingestion is used to stream video from the system's webcam to the KVS backend directly without any proxies. ###

Currently, it is only available via Chrome. 
There are four important tasks that this repository helps perform
1. Obtaining the video from the webcam/ uploading video file
2. Converting it to containers and codecs expected by the playback
3. Sending to the putMedia API via POST calls
4. Viewing the stream on the browser


### Setup ###
To set-up the project and run the example index.html and index.js files, follow the steps below:
1. Create a webpack project referring to https://webpack.js.org/guides/getting-started/
    ```
    mkdir webpack-demo
    cd webpack-demo
    npm init -y
    npm install webpack webpack-cli --save-dev
    ```

2. Create dist and src folders in webpack-demo
    ```
    mkdir dist
    mkdir src
    ```

3. Add webpack.config.js with the following content to the src folder
    ```javascript
    const HtmlWebPackPlugin = require('html-webpack-plugin')
    module.exports = {
    target: 'node',
    entry: './dist/index.js',
    output: {
        filename: 'main.js'
    },
    module: {
        rules: [
        {
            test: /.html$/,
            use: [
            {
                loader: 'html-loader',
                options: { minimize: true }
            }
            ]
        }
        ]
    },
    plugins: [
        new HtmlWebPackPlugin({
        template: './src/index.html',
        filename: './index.html'
        })
    ]
    }
    ```

4. Install aws-sdk and aws4
    ```
    npm install aws-sdk -save--dev
    npm install aws4 --save-dev
    ```

5. Now you can either create your own HTML page or use the one in the dist folder of this project.  
    If you create your own HTML page, then in order to use the functions from this project add scripts (i) and (ii) from below to your index.html in the dist folder. To use your own index.js from src folder, add script (iii) to your index.html in the dist folder 
    i. `<script src="https://unpkg.com/@ffmpeg/ffmpeg@0.8.3/dist/ffmpeg.min.js"></script>` 
    ii. `<script src="https://www.WebRTC-Experiment.com/RecordRTC.js"></script>` 
    iii. `<script src="main.js"></script>`

6. Now, add AWS-SDKCalls.js, createStream.js, getDataEndpoint.js, getProcessedVideo.js from this project's dist folder to yours, so that you can use the functions from the same in your index.js. (You can add index.js from this project's dist folder to yours to run the example)

7. Once you have imported all the scripts as per directions above and have index.html in dist and index.js in src, run `npx webpack` from webapck-demo folder

8. You can run the project on locahost using http-server
    ```
    brew install http-server
    http-server -p 8080 -o
    ```



### Functions in the project ###
1. `getBlobFromWebcam` is an asynchronous function that records video from the webcam, transforms it and sends it to the putMedia API. It can be imported as 
    `import { getBlobFromWebcam } from './getProcessedVideo.js'` 
    It accepts the following arguments:
```javascript
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


2. `getBlobFromFile` is an asynchronous function that accepts a file, transforms it to the expected format and sends it to the putMedia API. It can be imported as 
    `import { getBlobFromFile } from './getProcessedVideo.js'` 
    It accepts the following arguments:
```javascript
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


3. `transformVideo` is an asynchronous function that is used to transform a video to expected container(MKV), audio(AAC) and video(H.264 AvCC) codecs as expected by KVS Playback for streaming. 
    The transformation of videos to expected formats in getBlobFromFile and getBlobFromWebcam is done using this function. It can be imported as 
    `import { transformVideo } from './getProcessedVideo.js'` 
    It accepts the following arguments:
```javascript
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


4. `stopRecording` is a function that is used to stop the ongoing recording started using getBlobFromWebCam. It can be imported as 
    `import { stopRecording } from './getProcessedVideo.js'`
        

5. `putMedia` is a function that makes API Calls to PutMedia API on the ingestion side of KVS. It can be imported as 
    `import { putMedia } from './AWS-SDKCalls.js'` 
    It accepts the following arguments:
```javascript
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



6. `kinesisVideoObject` is a function that is used to create a service interface object with the arguments as parameters and return the same. It can be imported as 
    `import { kinesisVideoObject } from './AWS-SDKCalls.js'` 
    It accepts the following arguments:
```javascript
        endpoint: The endpoint URI to send requests to. The default endpoint is built from the configured region. The endpoint should be a string like 'https://{service}.{region}.amazonaws.com' 
        region: the region to send service requests to. See AWS.KinesisVideo.region for more information
                Example: us-west-2 
        accessKeyID: your AWS access key ID, 
        secretAccessKey: your AWS secret access key, 
        sessionToken: the optional AWS session token to sign requests with
```


7. `createStream` is used to create a new stream to which videos can be sent. It returns a promise which can be resolved to get a response containing the name of the stream. It can be imported as 
    `import { createStream } from './createStream.js'` 
    It accepts the following arguments:
```javascript
    kinesisvideo: It is a kinseis video object which is a service interface object. It can be obtained from the kinesisVideoObject function or refer (https://docs.aws.amazon.com/AWSJavaScriptSDK/latest/AWS/KinesisVideo.html#constructor-property) to create it with parameters other than the ones mentioned above
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
                    

8. `getDataEndpoint` is used to obtain the endpoint needed to make the POST request while making putMedia API calls. It can be imported as 
    `import { getDataEndpoint } from './getDataEndpoint.js'` 
    It accepts the following parameters:
```javascript
        kinesisvideo: It is a kinseis video object which is a service interface object. It can be obtained from the kinesisVideoObject function or refer (https://docs.aws.amazon.com/AWSJavaScriptSDK/latest/AWS/KinesisVideo.html#constructor-property) to create it with parameters other than the ones mentioned above
        streamName:  The name of the stream to which putMedia API calls are to be made 
```