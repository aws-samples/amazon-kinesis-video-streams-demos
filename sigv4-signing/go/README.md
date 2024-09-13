# SigV4 Signer Samples - Golang

## Setup

1. Follow the official instructions to install golang for your device: https://go.dev/doc/install

2. Clone this repository and change directories to the directory containing the [`go.mod`](./go.mod) file:

   ```shell
   git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git
   cd amazon-kinesis-video-streams-demos/sigv4-signing/go/
   ```

3. Install the dependencies

   There is a `go.mod` file located in this folder. Download the necessary dependencies:
   
   ```shell
   go mod download
   ```

## Using the samples

Included in the [cmd](./cmd) folder are two sample applications:
1. [Simple connect to socket example](./cmd/1_simple_connect_to_socket_sample) - This sample application creates the Pre-Signed URL, connects and disconnects from it using the Gorilla WebSocket module.
2. [Signer simulated consumer example](./cmd/1_simple_connect_to_socket_sample) - This sample application demonstrates an integration with the AWS SDK for Go v2 to fetch the signaling channel ARN, and signaling channel endpoint. It connects and disconnects to the WebSocket endpoint a configurable number of times to ensure the signer implementation is solid. Each attempt, it fetches a new set of temporary credentials from STS, verifying that the signer is compatible with different sets of AWS credentials across various signing timestamps.

To run the samples, edit the placeholder variables at the top of the `main()` function. Then, run `go run path/to/main.go`. For example:

```shell
go run cmd/1_simple_connect_to_socket_sample/main.go
```

## Project Structure

This sample is designed in a way that the customer application can be integrated using the SigV4 signer logic in a golang application as a reference.

### Sigv4 Signer Framework

The [signer framework](./pkg/sigv4signer/sigv4signer.go) contains the logic for signing the Secure WebSocket (WSS) URL with the provided AWS Credentials using the SigV4 algorithm to connect to Amazon Kinesis Video Streams WebRTC Signaling [as Master](https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-2.html) or [as Viewer](https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-1.html).

### Unit Tests

This project features unit tests which may help when trying to debug other custom signer implementations. To run the unit tests, run the following command from the folder containing the [go.mod](./go.mod) file:
```shell
go test github.com/aws-samples/amazon-kinesis-video-streams-demos/sigv4-signing/go/pkg/sigv4signer
```

Or, change directories to: [pkg/sigv4signer](./pkg/sigv4signer) and run:
```shell
go test
```

## Troubleshooting

If you're having trouble downloading the Gorilla WebSocket module:
```log
go: module github.com/gorilla/websocket: Get "https://proxy.golang.org/github.com/gorilla/websocket/@v/list": dial tcp: lookup proxy.golang.org: i/o timeout
```

Try setting the following environment variable and try again.
```shell
export GOPROXY=direct
```
