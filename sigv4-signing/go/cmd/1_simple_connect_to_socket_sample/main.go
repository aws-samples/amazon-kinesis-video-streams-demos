package main

import (
	"github.com/aws-samples/amazon-kinesis-video-streams-demos/sigv4-signing/go/pkg/sigv4signer"
	"github.com/gorilla/websocket"
	"io"
	"log"
	"net/http/httputil"
	"net/url"
	"os"
	"os/signal"
	"time"
)

func main() {
	////////////////////////////////////////////
	// ***Sample connection test:*** - fill in the variables:

	// URI to sign.
	//
	// Connect as Master URL - GetSignalingChannelEndpoint (master role) + Query Parameters: Channel ARN as X-Amz-ChannelARN
	//   Ref: https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-2.html
	// Connect as Viewer URL - GetSignalingChannelEndpoint (viewer role) + Query Parameters: Channel ARN as X-Amz-ChannelARN & Client Id as X-Amz-ClientId
	//   Ref: https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-1.html
	//
	// Viewer URL example: wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557
	//
	// **Note**: The Signaling Channel Endpoints are different, depending on the role (master/viewer) specified in GetSignalingChannelEndpoint API call.
	//   Ref: https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_SingleMasterChannelEndpointConfiguration.html#KinesisVideo-Type-SingleMasterChannelEndpointConfiguration-Role
	uriToSign, _ := url.Parse("wss://<Your GetSignalingEndpoint response hostname>?X-Amz-ChannelARN=<YourChannelARN>&X-Amz-ClientId=<YourClientId>")

	// Test Credentials for sample connection verification. SessionToken can be empty string if using non-temporary credentials.
	accessKey := "YourAccessKey"
	secretKey := "YourSecretKey"
	sessionToken := "YourSessionToken"

	// AWS Region (e.g. us-west-2)
	region := "YourChannelRegion"

	////////////////////////////////////////////

	// Compute SigV4 signing
	// Ref: https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html
	// https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-android/blob/master/src/main/java/com/amazonaws/kinesisvideo/utils/AwsV4Signer.java

	signedURI, err := sigv4signer.AwsV4Signer{}.Sign(uriToSign, accessKey, secretKey, sessionToken, region, time.Now().UnixMilli())
	if err != nil {
		log.Fatalf("Failed to sign the URI: %v", err)
	}
	log.Printf("Signed URI for verification (e.g. using wscat) = %s", signedURI.String())

	// Connect to the SigV4 signed Endpoint
	interrupt := make(chan os.Signal, 1)
	signal.Notify(interrupt, os.Interrupt)

	conn, resp, err := websocket.DefaultDialer.Dial(signedURI.String(), nil)
	if err != nil {
		log.Printf("Error in connection: %v", err)
		return
	}

	// Print the responses from the connection
	log.Printf("Response code: %d", resp.StatusCode)

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		log.Printf("Error in reading response body: %v", err)
		return
	}

	log.Printf("Response body: %s", string(body))

	respBody, err := httputil.DumpResponse(resp, true)
	if err != nil {
		log.Printf("Error printing response body: %v", err)
		return
	}

	log.Println(string(respBody))

	defer conn.Close()
}
