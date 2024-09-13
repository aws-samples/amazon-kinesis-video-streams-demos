package sigv4signer

import (
	"encoding/hex"
	"net/url"
	"strings"
	"testing"
)

func TestSignMasterURLWithTemporaryCredentials(t *testing.T) {
	masterURIToSignProtocolAndHost := "wss://m-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com"
	uriToSign, _ := url.Parse(masterURIToSignProtocolAndHost + "?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123")
	accessKeyId := "AKIAIOSFODNN7EXAMPLE"
	secretKeyId := "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
	sessionToken := "AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT+FvwqnKwRcOIfrRh3c/LTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE/IvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb/AXlzBBko7b15fjrBs2+cTQtpZ3CYWFXG8C5zqx37wnOE49mRl/+OtkIKGO7fAE"
	region := "us-west-2"
	dateMilli := int64(1690186022951)

	signer := AwsV4Signer{}
	actual, _ := signer.Sign(uriToSign, accessKeyId, secretKeyId, sessionToken, region, dateMilli)

	expected, _ := url.Parse(masterURIToSignProtocolAndHost + "/?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20230724%2Fus-west-2%2Fkinesisvideo%2Faws4_request&X-Amz-Date=20230724T080702Z&X-Amz-Expires=299&X-Amz-Security-Token=AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT%2BFvwqnKwRcOIfrRh3c%2FLTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE%2FIvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb%2FAXlzBBko7b15fjrBs2%2BcTQtpZ3CYWFXG8C5zqx37wnOE49mRl%2F%2BOtkIKGO7fAE&X-Amz-SignedHeaders=host&X-Amz-Signature=f8fed632bbe38ac920c7ed2eeaba1a4ba5e2b1bd7aada9f852708112eab76baa")

	if actual.String() != expected.String() {
		t.Errorf("Expected %s, but got %s", expected.String(), actual.String())
	}
}

func TestSignViewerURLWithTemporaryCredentials(t *testing.T) {
	viewerURIToSignProtocolAndHost := "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com"
	uriToSign, _ := url.Parse(viewerURIToSignProtocolAndHost + "?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557")
	accessKeyId := "AKIAIOSFODNN7EXAMPLE"
	secretKeyId := "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
	sessionToken := "AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT+FvwqnKwRcOIfrRh3c/LTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE/IvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb/AXlzBBko7b15fjrBs2+cTQtpZ3CYWFXG8C5zqx37wnOE49mRl/+OtkIKGO7fAE"
	region := "us-west-2"
	dateMilli := int64(1690186022958)

	signer := AwsV4Signer{}
	actual, _ := signer.Sign(uriToSign, accessKeyId, secretKeyId, sessionToken, region, dateMilli)

	expected, _ := url.Parse(viewerURIToSignProtocolAndHost + "/?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20230724%2Fus-west-2%2Fkinesisvideo%2Faws4_request&X-Amz-Date=20230724T080702Z&X-Amz-Expires=299&X-Amz-Security-Token=AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT%2BFvwqnKwRcOIfrRh3c%2FLTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE%2FIvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb%2FAXlzBBko7b15fjrBs2%2BcTQtpZ3CYWFXG8C5zqx37wnOE49mRl%2F%2BOtkIKGO7fAE&X-Amz-SignedHeaders=host&X-Amz-Signature=77ea5ff8ede2e22aa268a3a068f1ad3a5d92f0fa8a427579f9e6376e97139761")

	if actual.String() != expected.String() {
		t.Errorf("Expected %s, but got %s", expected.String(), actual.String())
	}
}

func TestSignMasterURLWithLongTermCredentials(t *testing.T) {
	masterURIToSignProtocolAndHost := "wss://m-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com"
	uriToSign, _ := url.Parse(masterURIToSignProtocolAndHost + "?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123")
	accessKeyId := "AKIAIOSFODJJ7EXAMPLE"
	secretKeyId := "wJalrXUtnFEMI/K7MDENG/bPxQQiCYEXAMPLEKEY"
	sessionToken := ""
	region := "us-west-2"
	dateMilli := int64(1690186022101)

	signer := AwsV4Signer{}
	actual, _ := signer.Sign(uriToSign, accessKeyId, secretKeyId, sessionToken, region, dateMilli)

	expected, _ := url.Parse(masterURIToSignProtocolAndHost + "/?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&X-Amz-Credential=AKIAIOSFODJJ7EXAMPLE%2F20230724%2Fus-west-2%2Fkinesisvideo%2Faws4_request&X-Amz-Date=20230724T080702Z&X-Amz-Expires=299&X-Amz-SignedHeaders=host&X-Amz-Signature=0bbef329f0d9d3e68635f7b844ac684c7764a0c228ca013232d935c111b9a370")

	if actual.String() != expected.String() {
		t.Errorf("Expected %s, but got %s", expected.String(), actual.String())
	}
}

func TestSignViewerURLWithLongTermCredentials(t *testing.T) {
	viewerURIToSignProtocolAndHost := "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com"
	uriToSign, _ := url.Parse(viewerURIToSignProtocolAndHost + "?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557")
	accessKeyId := "AKIAIOSFODJJ7EXAMPLE"
	secretKeyId := "wJalrXUtnFEMI/K7MDENG/bPxQQiCYEXAMPLEKEY"
	sessionToken := ""
	region := "us-west-2"
	dateMilli := int64(1690186022208)

	signer := AwsV4Signer{}
	actual, _ := signer.Sign(uriToSign, accessKeyId, secretKeyId, sessionToken, region, dateMilli)

	expected, _ := url.Parse(viewerURIToSignProtocolAndHost + "/?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557&X-Amz-Credential=AKIAIOSFODJJ7EXAMPLE%2F20230724%2Fus-west-2%2Fkinesisvideo%2Faws4_request&X-Amz-Date=20230724T080702Z&X-Amz-Expires=299&X-Amz-SignedHeaders=host&X-Amz-Signature=cea541f699dc51bc53a55590ce817e63cc06fac2bdef4696b63e0889eb448f0b")

	if actual.String() != expected.String() {
		t.Errorf("Expected %s, but got %s", expected.String(), actual.String())
	}
}

func TestGetCanonicalRequestWithQueryParameters(t *testing.T) {
	uri, _ := url.Parse("http://example.amazonaws.com")
	paramsMap := map[string]string{
		"Param2": "value2",
		"Param1": "value1",
	}
	canonicalQuerystring := getCanonicalizedQueryString(paramsMap)

	canonicalResultExpected := strings.Join([]string{
		METHOD,
		"/",
		"Param1=value1&Param2=value2",
		"host:example.amazonaws.com",
		"",
		SIGNED_HEADERS,
		"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
	}, "\n")

	actualCanonicalRequest := getCanonicalRequest(uri, canonicalQuerystring)

	if actualCanonicalRequest != canonicalResultExpected {
		t.Errorf("Expected %s, but got %s", canonicalResultExpected, actualCanonicalRequest)
	}
}

func TestGetCanonicalUriWithVariousResources(t *testing.T) {
	testCases := []struct {
		resourceStringToAppend string
		expectedResource       string
	}{
		{"", "/"},
		{"/", "/"},
		{"/hey", "/hey"},
	}

	for _, tc := range testCases {
		testUri, _ := url.Parse("wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com" + tc.resourceStringToAppend)
		actualResource := getCanonicalURI(testUri)

		if actualResource != tc.expectedResource {
			t.Errorf("For resource %s, expected %s, but got %s", tc.resourceStringToAppend, tc.expectedResource, actualResource)
		}
	}
}

func TestBuildQueryParamsMapWithLongTermCredentials(t *testing.T) {
	testUri, _ := url.Parse("wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123")
	testAccessKeyId := "AKIDEXAMPLE"
	testSessionToken := ""
	testRegion := "us-west-2"
	testTimestamp := "20230724T000000Z"
	testDatestamp := "20230724"

	expectedQueryParams := map[string]string{
		X_AMZ_ALGORITHM:      ALGORITHM_AWS4_HMAC_SHA_256,
		X_AMZ_CREDENTIAL:     "AKIDEXAMPLE%2F20230724%2Fus-west-2%2Fkinesisvideo%2Faws4_request",
		X_AMZ_DATE:           testTimestamp,
		X_AMZ_EXPIRES:        "299",
		X_AMZ_SIGNED_HEADERS: SIGNED_HEADERS,
		"X-Amz-ChannelARN":   "arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123",
	}

	actualQueryParams := buildQueryParamsMap(testUri, testAccessKeyId, testSessionToken, testRegion, testTimestamp, testDatestamp)

	if !areMapsSame(expectedQueryParams, actualQueryParams) {
		t.Errorf("Expected %v, but got %v", expectedQueryParams, actualQueryParams)
	}
}

func TestBuildQueryParamsMapWithTemporaryCredentials(t *testing.T) {
	testUri, _ := url.Parse("wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557")
	testAccessKeyId := "AKIDEXAMPLE"
	testSessionToken := "SSEXAMPLE"
	testRegion := "us-west-2"
	testTimestamp := "20230724T000000Z"
	testDatestamp := "20230724"

	expectedQueryParams := map[string]string{
		X_AMZ_ALGORITHM:      ALGORITHM_AWS4_HMAC_SHA_256,
		X_AMZ_CREDENTIAL:     "AKIDEXAMPLE%2F20230724%2Fus-west-2%2Fkinesisvideo%2Faws4_request",
		X_AMZ_DATE:           testTimestamp,
		X_AMZ_EXPIRES:        "299",
		X_AMZ_SIGNED_HEADERS: SIGNED_HEADERS,
		X_AMZ_SECURITY_TOKEN: "SSEXAMPLE",
		"X-Amz-ChannelARN":   "arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123",
		"X-Amz-ClientId":     "d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557",
	}

	actualQueryParams := buildQueryParamsMap(testUri, testAccessKeyId, testSessionToken, testRegion, testTimestamp, testDatestamp)

	if !areMapsSame(expectedQueryParams, actualQueryParams) {
		t.Errorf("Expected %v, but got %v", expectedQueryParams, actualQueryParams)
	}
}

func areMapsSame(expected, actual map[string]string) bool {
	if len(expected) != len(actual) {
		return false
	}

	for key, value := range expected {
		if actual[key] != value {
			return false
		}
	}

	return true
}

func TestSignString(t *testing.T) {
	credentialScope := "AKIDEXAMPLE/20150830/us-east-1/service/aws4_request"
	requestDate := "20150830T123600Z"
	canonicalRequest := strings.Join([]string{
		METHOD,
		"/",
		"Param1=value1&Param2=value2",
		"host:example.amazonaws.com",
		"x-amz-date:20150830T123600Z",
		"",
		SIGNED_HEADERS + ";x-amz-date",
		"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
	}, "\n")

	expected := strings.Join([]string{
		ALGORITHM_AWS4_HMAC_SHA_256,
		"20150830T123600Z",
		"AKIDEXAMPLE/20150830/us-east-1/service/" + AWS4_REQUEST_TYPE,
		"816cd5b414d056048ba4f7c5386d6e0533120fb1fcfa93762cf0fc39e2cf19e0",
	}, "\n")

	actual := signString(requestDate, credentialScope, canonicalRequest)

	if actual != expected {
		t.Errorf("Expected %s, but got %s", expected, actual)
	}
}

func TestGetSignatureKeyAndHmacSha256(t *testing.T) {
	stringToSign := strings.Join([]string{
		ALGORITHM_AWS4_HMAC_SHA_256,
		"20150830T123600Z",
		"20150830/us-east-1/iam/" + AWS4_REQUEST_TYPE,
		"f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59",
	}, "\n")

	signatureKeyBytes := getSignatureKey("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20150830", "us-east-1", "iam")

	expectedSignature := "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9"
	actualSignature := hex.EncodeToString(signatureKeyBytes)

	if actualSignature != expectedSignature {
		t.Errorf("Expected signature %s, but got %s", expectedSignature, actualSignature)
	}

	expectedSignatureString := "5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7"
	actualSignatureString := hex.EncodeToString(hmacSha256(stringToSign, signatureKeyBytes))

	if actualSignatureString != expectedSignatureString {
		t.Errorf("Expected signature string %s, but got %s", expectedSignatureString, actualSignatureString)
	}
}

func TestUrlEncode(t *testing.T) {
	exampleArn := "arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123"
	expected := "arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123"

	actual := urlEncode(exampleArn)

	if actual != expected {
		t.Errorf("Expected %s, but got %s", expected, actual)
	}
}

func TestCreateCredentialScope(t *testing.T) {
	region := "us-west-2"
	datestamp := "20150930"
	expected := "20150930/us-west-2/" + SERVICE + "/" + AWS4_REQUEST_TYPE

	actual := createCredentialScope(region, datestamp)

	if actual != expected {
		t.Errorf("Expected %s, but got %s", expected, actual)
	}
}

func TestHmacSha256(t *testing.T) {
	testKey := []byte("testKey")
	testData := "testData123"
	expectedHex := "f8117085c5b8be75d01ce86d16d04e90fedfc4be4668fe75d39e72c92da45568"

	actualResult := hmacSha256(testData, testKey)
	actualHex := hex.EncodeToString(actualResult)

	if actualHex != expectedHex {
		t.Errorf("Expected %s, but got %s", expectedHex, actualHex)
	}
}

func TestGetTimeStamp(t *testing.T) {
	testCases := []struct {
		name           string
		dateMilli      int64
		expectedOutput string
	}{
		{
			name:           "Saturday, July 22, 2023 12:00:00.000 AM (UTC)",
			dateMilli:      1689984000000,
			expectedOutput: "20230722T000000Z",
		},
		{
			name:           "Friday, July 21, 2023 11:59:59.999 PM (UTC)",
			dateMilli:      1689983999999,
			expectedOutput: "20230721T235959Z",
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			actualOutput := getTimeStamp(tc.dateMilli)
			if actualOutput != tc.expectedOutput {
				t.Errorf("Expected %s, but got %s", tc.expectedOutput, actualOutput)
			}
		})
	}
}

func TestGetDateStamp(t *testing.T) {
	testCases := []struct {
		name           string
		dateMilli      int64
		expectedOutput string
	}{
		{
			name:           "Saturday, July 22, 2023 12:00:00.000 AM (UTC)",
			dateMilli:      1689984000000,
			expectedOutput: "20230722",
		},
		{
			name:           "Friday, July 21, 2023 11:59:59.999 PM (UTC)",
			dateMilli:      1689983999999,
			expectedOutput: "20230721",
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			actualOutput := getDateStamp(tc.dateMilli)
			if actualOutput != tc.expectedOutput {
				t.Errorf("Expected %s, but got %s", tc.expectedOutput, actualOutput)
			}
		})
	}
}
