# Sigv4 Signer Samples - Swift

## Setup

This sample application uses CocoaPods as its dependency manager.

1. Install Cocoapods on your Mac if it isn't yet already installed.
   
```shell
brew install cocoapods
pod setup
```

2. Clone this repository and change directories to the directory containing this [`Podfile`](./Podfile):

```shell
git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git
cd amazon-kinesis-video-streams-demos/sigv4-signing/swift/sigv4
```

3. Install the dependencies

```shell
pod install
```

4. Open the project using one of these two methods:

   1. Open XCode, select "Open a project or file", and choose [sigv4.**xcworkspace**](sigv4.xcworkspace), **OR**
  
   2. Run the following command from the [sigv4](.) (this) folder.
    ```bash
    xed .
    ```


## Project Structure

This project comes in 3 different parts:

### Sigv4 Signer Framework

The [signer framework](./Sigv4Signer) contains the logic for signing the Secure WebSocket (WSS) URL with the provided AWS Credentials using the SigV4 algorithm to connect to Amazon Kinesis Video Streams WebRTC Signaling.

### Sigv4 CLI Application

This is the sample application demonstrating how to use the SigV4 Signer framework. It contains placeholders for where and what to input so that the WebSocket URL is generated correctly. Refer to the [main README](../../README.md) for more details on the variables. You can use either short-term or long-term credentials in this sample. To run the sample, press the Run button at the top-left of the XCode UI.

### Unit Tests

This project features unit tests which may help when trying to debug other custom signer implementations. To run the unit tests, long-press the run button and choose "Test".
