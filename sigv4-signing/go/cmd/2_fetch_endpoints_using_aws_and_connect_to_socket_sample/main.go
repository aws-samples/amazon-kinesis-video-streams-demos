package main

import (
	"context"
	"fmt"
	"github.com/aws-samples/amazon-kinesis-video-streams-demos/sigv4-signing/go/pkg/sigv4signer"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/kinesisvideo"
	kinesisvideotypes "github.com/aws/aws-sdk-go-v2/service/kinesisvideo/types"
	"github.com/aws/aws-sdk-go-v2/service/sts"
	ststypes "github.com/aws/aws-sdk-go-v2/service/sts/types"
	"github.com/gorilla/websocket"
	"io"
	"log"
	"math/rand"
	"net/http/httputil"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"time"
)

func main() {
	////////////////////////////////////////////
	// ***Repeated connection test*** - fill in the variables
	// You can use this application to manually verify the signer implementation using different
	// sets of credentials and timestamps. Each attempt, a new set of temporary credentials
	// from AWS STS is used to sign the request and connect to the endpoint.

	accessKey := "YourAccessKey"
	secretKey := "YourSecretKey"
	sessionToken := "YourSessionToken" // Empty string if using non-temporary credentials

	signalingChannelName := "YourSignalingChannelName"
	region := "YourRegion"                      // e.g. "us-west-2"
	role := kinesisvideotypes.ChannelRoleMaster // or kinesisvideotypes.ChannelRoleViewer

	numIterations := 50
	sleepDurationMs := 5000 // Wait between iterations = sleepDurationMs + rand(0, sleepJitterMs)
	sleepJitterMs := 5000

	// ***Repeated connection test*** - end configuration area

	if accessKey == "YourAccessKey" || secretKey == "YourSecretKey" ||
		sessionToken == "YourSessionToken" || region == "YourRegion" ||
		signalingChannelName == "YourSignalingChannelName" {
		log.Fatal("you need to configure the application with your credentials, channel name, and region")
		return
	}

	// Refer to https://aws.github.io/aws-sdk-go-v2/docs/configuring-sdk/#specifying-credentials for other authentication methods
	cfg := aws.Config{
		Credentials: credentials.NewStaticCredentialsProvider(accessKey, secretKey, sessionToken),
		Region:      region,
	}

	ctx := context.Background()

	channelARN, err := fetchSignalingChannelARN(ctx, cfg, signalingChannelName)
	if err != nil {
		log.Fatalf("failed to fetch signaling channel ARN: %v", err)
	}

	endpoint, err := fetchWebSocketEndpoint(ctx, cfg, channelARN)
	if err != nil {
		log.Fatalf("failed to fetch Secure WebSocket (WSS) endpoint: %v", err)
	}

	uri, _ := url.Parse(endpoint + "?X-Amz-ChannelARN=" + channelARN)

	// If connecting to the viewer endpoint, randomly generate a Client ID
	if role == kinesisvideotypes.ChannelRoleViewer {
		uri, _ = url.Parse(uri.String() + "&X-Amz-ClientId=" + strconv.FormatUint(rand.Uint64(), 16))
	}

	for i := 1; i <= numIterations; i++ {
		log.Printf("Iteration: %d", i)

		tempCredentials, err := fetchNewTempCredentials(ctx, cfg)
		if err != nil {
			log.Printf("failed to fetch temporary credentials: %v", err)
			continue
		}

		signedURI, err := sigv4signer.AwsV4Signer{}.Sign(uri,
			*tempCredentials.AccessKeyId,
			*tempCredentials.SecretAccessKey,
			*tempCredentials.SessionToken,
			region,
			time.Now().UnixMilli())
		if err != nil {
			log.Fatalf("there was an error signing the request: %v", err)
			return
		}

		// Connect to the SigV4 signed Endpoint
		interrupt := make(chan os.Signal, 1)
		signal.Notify(interrupt, os.Interrupt)

		conn, resp, err := websocket.DefaultDialer.Dial(signedURI.String(), nil)
		if err != nil {
			log.Printf("error in connection: %v", err)
			return
		}

		// Print the responses from the connection
		log.Printf("Response code: %d", resp.StatusCode)

		body, err := io.ReadAll(resp.Body)
		if err != nil {
			log.Printf("error in reading response body: %v", err)
			return
		}

		log.Printf("Response body: %s", string(body))

		respBody, err := httputil.DumpResponse(resp, true)
		if err != nil {
			log.Printf("error printing response body: %v", err)
			return
		}

		log.Println(string(respBody))

		defer conn.Close()

		// Wait between iterations = sleepDurationMs + rand(0, sleepJitterMs)
		time.Sleep(time.Duration(sleepDurationMs+rand.Intn(sleepJitterMs)) * time.Millisecond)
	}
}

// fetchNewTempCredentials fetches new temporary credentials from AWS STS (Security Token Service)
func fetchNewTempCredentials(ctx context.Context, cfg aws.Config) (*ststypes.Credentials, error) {
	stsClient := sts.NewFromConfig(cfg)

	input := &sts.GetSessionTokenInput{
		DurationSeconds: aws.Int32(900),
	}

	result, err := stsClient.GetSessionToken(ctx, input)
	if err != nil {
		return nil, fmt.Errorf("unable to get temporary credentials: %v", err)
	}

	return result.Credentials, nil
}

// fetchSignalingChannelARN fetches the Amazon Resource Name (ARN) of the specified Kinesis Video Signaling Channel
func fetchSignalingChannelARN(ctx context.Context, cfg aws.Config, signalingChannelName string) (string, error) {
	client := kinesisvideo.NewFromConfig(cfg)

	input := &kinesisvideo.DescribeSignalingChannelInput{
		ChannelName: &signalingChannelName,
	}

	output, err := client.DescribeSignalingChannel(ctx, input)
	if err != nil {
		return "", fmt.Errorf("failed to describe signaling channel: %v", err)
	}

	return *output.ChannelInfo.ChannelARN, nil
}

// fetchWebSocketEndpoint fetches the Secure WebSocket (WSS) endpoint for the specified Kinesis Video Signaling Channel
func fetchWebSocketEndpoint(ctx context.Context, cfg aws.Config, signalingChannelARN string) (string, error) {
	client := kinesisvideo.NewFromConfig(cfg)

	input := &kinesisvideo.GetSignalingChannelEndpointInput{
		ChannelARN: &signalingChannelARN,
		SingleMasterChannelEndpointConfiguration: &kinesisvideotypes.SingleMasterChannelEndpointConfiguration{
			Role:      kinesisvideotypes.ChannelRoleMaster,
			Protocols: []kinesisvideotypes.ChannelProtocol{kinesisvideotypes.ChannelProtocolWss},
		},
	}

	output, err := client.GetSignalingChannelEndpoint(ctx, input)
	if err != nil {
		return "", fmt.Errorf("failed to get signaling channel endpoint: %v", err)
	}

	return *output.ResourceEndpointList[0].ResourceEndpoint, nil
}
