package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"github.com/gorilla/websocket"
	"io"
	"log"
	"net/http/httputil"
	"net/url"
	"os"
	"os/signal"
	"sort"
	"strings"
	"time"
)

const (
	ALGORITHM_AWS4_HMAC_SHA_256 = "AWS4-HMAC-SHA256"
	AWS4_REQUEST_TYPE           = "aws4_request"
	SERVICE                     = "kinesisvideo"
	X_AMZ_ALGORITHM             = "X-Amz-Algorithm"
	X_AMZ_CREDENTIAL            = "X-Amz-Credential"
	X_AMZ_DATE                  = "X-Amz-Date"
	X_AMZ_EXPIRES               = "X-Amz-Expires"
	X_AMZ_SECURITY_TOKEN        = "X-Amz-Security-Token"
	X_AMZ_SIGNATURE             = "X-Amz-Signature"
	X_AMZ_SIGNED_HEADERS        = "X-Amz-SignedHeaders"
	NEW_LINE_DELIMITER          = "\n"
	DATE_PATTERN                = "20060102"
	TIME_PATTERN                = "20060102T150405Z"
	METHOD                      = "GET"
	SIGNED_HEADERS              = "host"
)

type AwsV4Signer struct{}

// SigV4 Signer sample reference for the Kinesis Video Streams WebRTC WebSocket connections
// Uses Gorilla WebSockets Go Module

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
	uri, _ := url.Parse("wss://<Your GetSignalingEndpoint response hostname>?X-Amz-ChannelARN=<YourChannelARN>&X-Amz-ClientId=<YourClientId>")

	// Secure WebSocket method "wss" plus hostname obtained from GetSignalingChannelEndpoint.
	// Viewer URL example: wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com
	//
	// **Note**: The Signaling Channel Endpoints are different, depending on the role (master/viewer) specified in GetSignalingChannelEndpoint API call.
	//   Ref: https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_SingleMasterChannelEndpointConfiguration.html#KinesisVideo-Type-SingleMasterChannelEndpointConfiguration-Role
	wssUri, _ := url.Parse("wss://<Your GetSignalingEndpoint Response hostname>")

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

	signedUri := AwsV4Signer{}.sign(uri, accessKey, secretKey, sessionToken, wssUri, region)
	println("Go SignedURL log for verifications (e.g. using wscat ) =", signedUri.String())

	// Connect to the SigV4 signed Endpoint
	interrupt := make(chan os.Signal, 1)
	signal.Notify(interrupt, os.Interrupt)

	c, resp, err := websocket.DefaultDialer.Dial(signedUri.String(), nil)

	// Print the responses from the connection
	println("Response code=", resp.StatusCode)
	if err != nil {
		log.Fatalln("Error in connection: ", err)
	}

	b, errBodyResp := io.ReadAll(resp.Body)
	if errBodyResp != nil {
		log.Fatalln("Error in reading response body: ", errBodyResp)
	}

	fmt.Println("Response body", string(b))

	body, errResp := httputil.DumpResponse(resp, true)
	if errResp != nil {
		log.Fatal("Error printing response body:", err)
	}

	fmt.Println(string(body))

	defer c.Close()

}

func (signer AwsV4Signer) sign(uri *url.URL, accessKey, secretKey, sessionToken string, wssURI *url.URL, region string) *url.URL {

	dateMilli := time.Now().UnixMilli()
	amzDate := getTimeStamp(dateMilli)
	datestamp := getDateStamp(dateMilli)

	queryParamsMap := buildQueryParamsMap(uri, accessKey, sessionToken, region, amzDate, datestamp)
	canonicalQuerystring := getCanonicalizedQueryString(queryParamsMap)
	canonicalRequest := getCanonicalRequest(uri, canonicalQuerystring)
	stringToSign := signString(amzDate, createCredentialScope(region, datestamp), canonicalRequest)

	signatureKey := getSignatureKey(secretKey, datestamp, region, SERVICE)
	signature := hex.EncodeToString(hmacSha256(stringToSign, signatureKey))

	signedCanonicalQueryString := canonicalQuerystring + "&" + X_AMZ_SIGNATURE + "=" + signature

	signedURI, err := url.Parse(wssURI.Scheme + "://" + wssURI.Host + getCanonicalURI(uri) + "?" + signedCanonicalQueryString)
	if err != nil {
		fmt.Println(err)
	}

	return signedURI
}

func buildQueryParamsMap(uri *url.URL, accessKey, sessionToken, region, amzDate, datestamp string) map[string]string {

	xAmzCredential := urlEncode(accessKey + "/" + createCredentialScope(region, datestamp))

	// The SigV4 signer has a maximum time limit of five minutes.
	// Once a connection is established, peers exchange signaling messages,
	// and the P2P connection is successful, the media P2P session
	// can continue for longer period of time.
	queryParamsMap := map[string]string{
		X_AMZ_ALGORITHM:  ALGORITHM_AWS4_HMAC_SHA_256,
		X_AMZ_CREDENTIAL: xAmzCredential,
		X_AMZ_DATE:       amzDate,
		X_AMZ_EXPIRES:    "299",
	}

	if sessionToken != "" {
		queryParamsMap[X_AMZ_SECURITY_TOKEN] = urlEncode(sessionToken)
	}

	queryParamsMap[X_AMZ_SIGNED_HEADERS] = SIGNED_HEADERS

	if uri.RawQuery != "" {
		params := strings.Split(uri.RawQuery, "&")
		for _, param := range params {
			index := strings.Index(param, "=")
			if index > 0 {
				queryParamsMap[param[:index]] = urlEncode(param[index+1:])
			}
		}
	}

	println("\n")

	return queryParamsMap
}

func createCredentialScope(region, datestamp string) string {
	ret := strings.Join([]string{datestamp, region, SERVICE, AWS4_REQUEST_TYPE}, "/")
	return ret
}

func getCanonicalRequest(uri *url.URL, canonicalQuerystring string) string {
	// https://pkg.go.dev/crypto/sha256
	payloadHash := sha256.New().Sum([]byte(""))
	canonicalUri := getCanonicalURI(uri)
	canonicalHeaders := "host:" + uri.Host + NEW_LINE_DELIMITER
	canonicalRequest := strings.Join([]string{METHOD, canonicalUri, canonicalQuerystring, canonicalHeaders, SIGNED_HEADERS, hex.EncodeToString(payloadHash)}, NEW_LINE_DELIMITER)

	return canonicalRequest
}

func getCanonicalURI(uri *url.URL) string {
	if uri.Path == "" {
		return "/"
	}
	return uri.Path
}

func signString(amzDate, credentialScope, canonicalRequest string) string {
	// https://pkg.go.dev/crypto/sha256#Sum256
	// Sum256 returns the SHA256 checksum of the data.
	result := sha256.Sum256([]byte(canonicalRequest))
	// Slice to make it to variable size for encoding
	result2 := hex.EncodeToString(result[:])

	stringToSign := strings.Join([]string{ALGORITHM_AWS4_HMAC_SHA_256, amzDate, credentialScope, result2},
		NEW_LINE_DELIMITER)
	return stringToSign
}

func urlEncode(str string) string {
	ret := url.QueryEscape(str)
	return ret
}

func hmacSha256(data string, key []byte) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(data))
	ret := mac.Sum(nil)
	return ret
}

func getSignatureKey(key, dateStamp, regionName, serviceName string) []byte {
	kSecret := []byte("AWS4" + key)
	kDate := hmacSha256(dateStamp, kSecret)
	kRegion := hmacSha256(regionName, kDate)
	kService := hmacSha256(serviceName, kRegion)
	ret := hmacSha256(AWS4_REQUEST_TYPE, kService)
	return ret
}

func getTimeStamp(dateMilli int64) string {
	ret := time.UnixMilli(dateMilli).UTC().Format(TIME_PATTERN)
	return ret
}

func getDateStamp(dateMilli int64) string {
	ret := time.UnixMilli(dateMilli).UTC().Format(DATE_PATTERN)
	return ret
}

func getCanonicalizedQueryString(queryParamsMap map[string]string) string {
	var queryKeys []string
	for key := range queryParamsMap {
		queryKeys = append(queryKeys, key)
	}
	sort.Strings(queryKeys)

	var builder strings.Builder
	for i, key := range queryKeys {
		builder.WriteString(key + "=" + queryParamsMap[key])
		if i < len(queryKeys)-1 {
			builder.WriteString("&")
		}
	}

	return builder.String()
}
