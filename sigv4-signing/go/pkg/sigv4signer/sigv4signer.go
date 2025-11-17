package sigv4signer

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/url"
	"sort"
	"strings"
	"time"
)

type AwsV4Signer struct{}

// SigV4 Signer sample reference for the Kinesis Video Streams WebRTC WebSocket connections
func (signer AwsV4Signer) Sign(uri *url.URL, accessKey string, secretKey string, sessionToken string, region string, dateMilli int64) (*url.URL, error) {

	amzDate := getTimeStamp(dateMilli)
	datestamp := getDateStamp(dateMilli)

	queryParamsMap := buildQueryParamsMap(uri, accessKey, sessionToken, region, amzDate, datestamp)
	canonicalQuerystring := getCanonicalizedQueryString(queryParamsMap)
	canonicalRequest := getCanonicalRequest(uri, canonicalQuerystring)
	stringToSign := signString(amzDate, createCredentialScope(region, datestamp), canonicalRequest)

	signatureKey := getSignatureKey(secretKey, datestamp, region, SERVICE)
	signature := hex.EncodeToString(hmacSha256(stringToSign, signatureKey))

	signedCanonicalQueryString := canonicalQuerystring + "&" + X_AMZ_SIGNATURE + "=" + signature

	signedURI, err := url.Parse(uri.Scheme + "://" + uri.Host + getCanonicalURI(uri) + "?" + signedCanonicalQueryString)
	if err != nil {
		return nil, err
	}

	return signedURI, nil
}

func buildQueryParamsMap(uri *url.URL, accessKey string, sessionToken string, region string, amzDate string, datestamp string) map[string]string {

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

	return queryParamsMap
}

func createCredentialScope(region string, datestamp string) string {
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

func signString(amzDate string, credentialScope string, canonicalRequest string) string {
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

func getSignatureKey(key string, dateStamp string, regionName string, serviceName string) []byte {
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
