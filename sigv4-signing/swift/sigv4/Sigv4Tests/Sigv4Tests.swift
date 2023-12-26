import XCTest
@testable import Sigv4

final class Sigv4Tests: XCTestCase {
    
    func test_when_signMasterURLWithTemporaryCredentials_then_returnValidSignedURL() throws {
        let masterURIToSignProtocolAndHost = "wss://m-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com"
        let uriToSign = URL(string: masterURIToSignProtocolAndHost + "?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123")!
        let accessKeyId = "AKIAIOSFODNN7EXAMPLE"
        let secretKeyId = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        let sessionToken = "AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT+FvwqnKwRcOIfrRh3c/LTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE/IvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb/AXlzBBko7b15fjrBs2+cTQtpZ3CYWFXG8C5zqx37wnOE49mRl/+OtkIKGO7fAE"
        let region = "us-west-2"
        let dateSecs: TimeInterval = 1690186022.951
        
        let expected = URLRequest(url: URL(string: "\(masterURIToSignProtocolAndHost)/?\(SignerConstants.X_AMZ_ALGORITHM)=\(SignerConstants.ALGORITHM_AWS4_HMAC_SHA_256)&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&\(SignerConstants.X_AMZ_CREDENTIAL)=\(accessKeyId)%2F20230724%2F\(region)%2F\(SignerConstants.SERVICE)%2F\(SignerConstants.AWS4_REQUEST_TYPE)&\(SignerConstants.X_AMZ_DATE)=20230724T080702Z&\(SignerConstants.X_AMZ_EXPIRES)=299&\(SignerConstants.X_AMZ_SECURITY_TOKEN)=AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT%2BFvwqnKwRcOIfrRh3c%2FLTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE%2FIvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb%2FAXlzBBko7b15fjrBs2%2BcTQtpZ3CYWFXG8C5zqx37wnOE49mRl%2F%2BOtkIKGO7fAE&\(SignerConstants.X_AMZ_SIGNED_HEADERS)=\(SignerConstants.SIGNED_HEADERS)&\(SignerConstants.X_AMZ_SIGNATURE)=f8fed632bbe38ac920c7ed2eeaba1a4ba5e2b1bd7aada9f852708112eab76baa")!)
        
        let actual = try Signer.sign(request: URLRequest(url: uriToSign), credentials: AWSCredentials(accessKey: accessKeyId, secretKey: secretKeyId, sessionToken: sessionToken), region: region, currentDate: Date(timeIntervalSince1970: dateSecs))
        
        XCTAssertEqual(expected, actual)
    }
    
    func test_when_signMasterURLWithLongTermCredentials_then_returnValidSignedURL() throws {
        let masterURIToSignProtocolAndHost: String = "wss://m-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com"
        let uriToSignString: String = "\(masterURIToSignProtocolAndHost)?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123"
        let uriToSign: URL = URL(string: uriToSignString)!

        let accessKeyId: String = "AKIAIOSFODJJ7EXAMPLE"
        let secretKeyId: String = "wJalrXUtnFEMI/K7MDENG/bPxQQiCYEXAMPLEKEY"
        let sessionToken: String? = nil
        let region: String = "us-west-2"
        let dateSecs: TimeInterval = 1690186022.101

        let expectedString: String = "\(masterURIToSignProtocolAndHost)/?\(SignerConstants.X_AMZ_ALGORITHM)=\(SignerConstants.ALGORITHM_AWS4_HMAC_SHA_256)&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&\(SignerConstants.X_AMZ_CREDENTIAL)=\(accessKeyId)%2F20230724%2F\(region)%2F\(SignerConstants.SERVICE)%2F\(SignerConstants.AWS4_REQUEST_TYPE)&\(SignerConstants.X_AMZ_DATE)=20230724T080702Z&\(SignerConstants.X_AMZ_EXPIRES)=299&\(SignerConstants.X_AMZ_SIGNED_HEADERS)=\(SignerConstants.SIGNED_HEADERS)&\(SignerConstants.X_AMZ_SIGNATURE)=0bbef329f0d9d3e68635f7b844ac684c7764a0c228ca013232d935c111b9a370"
        let expected: URL = URL(string: expectedString)!

        let actual = try Signer.sign(request: URLRequest(url: uriToSign), credentials: AWSCredentials(accessKey: accessKeyId, secretKey: secretKeyId, sessionToken: sessionToken), region: region, currentDate: Date(timeIntervalSince1970: dateSecs))

        XCTAssertEqual(expected, actual.url!)
    }
    
    func test_when_signViewerURLWithTemporaryCredentials_then_returnValidSignedURL() throws {
        let viewerURIToSignProtocolAndHost: String = "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com"
        let uriToSign: URL = URL(string: "\(viewerURIToSignProtocolAndHost)?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557")!
        let accessKeyId: String = "AKIAIOSFODNN7EXAMPLE"
        let secretKeyId: String = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        let sessionToken: String = "AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT+FvwqnKwRcOIfrRh3c/LTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE/IvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb/AXlzBBko7b15fjrBs2+cTQtpZ3CYWFXG8C5zqx37wnOE49mRl/+OtkIKGO7fAE"
        let region: String = "us-west-2"
        let dateSecs: TimeInterval = 1690186022.958

        let expected: URLRequest = URLRequest(url: URL(string: "\(viewerURIToSignProtocolAndHost)/?\(SignerConstants.X_AMZ_ALGORITHM)=\(SignerConstants.ALGORITHM_AWS4_HMAC_SHA_256)&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557&\(SignerConstants.X_AMZ_CREDENTIAL)=\(accessKeyId)%2F20230724%2F\(region)%2F\(SignerConstants.SERVICE)%2F\(SignerConstants.AWS4_REQUEST_TYPE)&\(SignerConstants.X_AMZ_DATE)=20230724T080702Z&\(SignerConstants.X_AMZ_EXPIRES)=299&\(SignerConstants.X_AMZ_SECURITY_TOKEN)=AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT%2BFvwqnKwRcOIfrRh3c%2FLTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE%2FIvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb%2FAXlzBBko7b15fjrBs2%2BcTQtpZ3CYWFXG8C5zqx37wnOE49mRl%2F%2BOtkIKGO7fAE&\(SignerConstants.X_AMZ_SIGNED_HEADERS)=\(SignerConstants.SIGNED_HEADERS)&\(SignerConstants.X_AMZ_SIGNATURE)=77ea5ff8ede2e22aa268a3a068f1ad3a5d92f0fa8a427579f9e6376e97139761")!)

        let actual: URLRequest = try Signer.sign(
            request: URLRequest(url: uriToSign),
            credentials: AWSCredentials(accessKey: accessKeyId, secretKey: secretKeyId, sessionToken: sessionToken),
            region: region,
            currentDate: Date(timeIntervalSince1970: dateSecs)
        )

        XCTAssertEqual(expected, actual)

    }
    
    func test_when_signViewerURLWithLongTermCredentials_then_returnValidSignedURL() throws {
        let viewerURIToSignProtocolAndHost = "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com";
        let uriToSign = URL(string: "\(viewerURIToSignProtocolAndHost)?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557")!
        let accessKeyId = "AKIAIOSFODJJ7EXAMPLE";
        let secretKeyId = "wJalrXUtnFEMI/K7MDENG/bPxQQiCYEXAMPLEKEY";
        let sessionToken = "";
        let region = "us-west-2";
        let dateSecs: TimeInterval = 1690186022.958;
        
        let expected = URLRequest(url: URL(string: viewerURIToSignProtocolAndHost + "/?" + SignerConstants.X_AMZ_ALGORITHM + "=" + SignerConstants.ALGORITHM_AWS4_HMAC_SHA_256 + "&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557&" + SignerConstants.X_AMZ_CREDENTIAL + "=" + accessKeyId + "%2F20230724%2F" + region + "%2F" + SignerConstants.SERVICE + "%2F" + SignerConstants.AWS4_REQUEST_TYPE + "&" + SignerConstants.X_AMZ_DATE + "=20230724T080702Z&" + SignerConstants.X_AMZ_EXPIRES + "=299&" + SignerConstants.X_AMZ_SIGNED_HEADERS + "=" + SignerConstants.SIGNED_HEADERS + "&" + SignerConstants.X_AMZ_SIGNATURE + "=cea541f699dc51bc53a55590ce817e63cc06fac2bdef4696b63e0889eb448f0b")!)
        
        let actual = try Signer.sign(request: URLRequest(url: uriToSign), credentials: AWSCredentials(accessKey: accessKeyId, secretKey: secretKeyId, sessionToken: sessionToken), region: region, currentDate: Date(timeIntervalSince1970: dateSecs))
        
        XCTAssertEqual(expected, actual);
    }
    
    func test_when_getCanonicalRequestWithQueryParameters_then_validCanonicalQueryStringReturned() {
        let canonicalResultExpected: String = """
            \(SignerConstants.METHOD)
            /
            Param1=value1&Param2=value2
            host:example.amazonaws.com
            
            \(SignerConstants.SIGNED_HEADERS)
            e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            """
        
        let uri: URL = URL(string: "http://example.amazonaws.com/")!
        
        // Add query parameters out of order to ensure that the resulting query parameters are in alphabetical order
        var queryParamsMap: [(String, String)] = [("Param2", "value2"), ("Param1", "value1")]
        let canonicalQueryString: String = Signer.getCanonicalizedQueryString(&queryParamsMap)
        
        let canonicalQuerystring: String = Signer.buildCanonicalRequest(url: uri, canonicalQueryString: canonicalQueryString)
        
        XCTAssertEqual(canonicalResultExpected, canonicalQuerystring)
    }
    
    func test_when_buildQueryParamsMapWithLongTermCredentials_then_mapContainsCorrectParametersAndDoesNotContainXAmzSecurityToken() throws {
        let testUri: URL = URL(string: "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123")!
        let testAccessKeyId: String = "AKIDEXAMPLE"
        let testSessionToken: String? = nil // since session token is not provided, we expect X-Amz-Security-Token to not be present in the map
        let testRegion: String = "us-west-2"
        let testTimestamp: String = "20230724T000000Z"
        let testDatestamp: String = "20230724"

        var expectedQueryParams: [(String, String)] = []
        expectedQueryParams.append((SignerConstants.X_AMZ_ALGORITHM, SignerConstants.ALGORITHM_AWS4_HMAC_SHA_256))
        expectedQueryParams.append((SignerConstants.X_AMZ_CREDENTIAL,  "\(testAccessKeyId)%2F\(testDatestamp)%2F\(testRegion)%2F\(SignerConstants.SERVICE)%2F\(SignerConstants.AWS4_REQUEST_TYPE)"))
        expectedQueryParams.append((SignerConstants.X_AMZ_DATE, testTimestamp))
        expectedQueryParams.append((SignerConstants.X_AMZ_EXPIRES, "299"))
        expectedQueryParams.append((SignerConstants.X_AMZ_SIGNED_HEADERS, SignerConstants.SIGNED_HEADERS))
        expectedQueryParams.append(("X-Amz-ChannelARN",  "arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123"))

        let actualQueryParams: [(String, String)] = try Signer.buildQueryParamsMap(url: testUri, accessKey: testAccessKeyId, sessionToken: testSessionToken, region: testRegion, amzDate: testTimestamp, dateStamp: testDatestamp)

        XCTAssertTrue(expectedQueryParams.elementsEqual(actualQueryParams, by: ==))
    }
    
    func test_when_buildQueryParamsMapWithTemporaryCredentials_then_mapContainsCorrectParameters() throws {
        let testUri: URL = URL(string: "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557")!
        let testAccessKeyId: String = "AKIDEXAMPLE"
        let testSessionToken: String = "SSEXAMPLE"
        let testRegion: String = "us-west-2"
        let testTimestamp: String = "20230724T000000Z"
        let testDatestamp: String = "20230724"

        var expectedQueryParams: [(String, String)] = []
        expectedQueryParams.append((SignerConstants.X_AMZ_ALGORITHM, SignerConstants.ALGORITHM_AWS4_HMAC_SHA_256))
        expectedQueryParams.append((SignerConstants.X_AMZ_CREDENTIAL,  "\(testAccessKeyId)%2F\(testDatestamp)%2F\(testRegion)%2F\(SignerConstants.SERVICE)%2F\(SignerConstants.AWS4_REQUEST_TYPE)"))
        expectedQueryParams.append((SignerConstants.X_AMZ_DATE, testTimestamp))
        expectedQueryParams.append((SignerConstants.X_AMZ_EXPIRES, "299"))
        expectedQueryParams.append((SignerConstants.X_AMZ_SIGNED_HEADERS, SignerConstants.SIGNED_HEADERS))
        expectedQueryParams.append((SignerConstants.X_AMZ_SECURITY_TOKEN, "SSEXAMPLE"))
        expectedQueryParams.append(("X-Amz-ChannelARN",  "arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123"))
        expectedQueryParams.append(("X-Amz-ClientId", "d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557"))

        let actualQueryParams: [(String, String)] = try Signer.buildQueryParamsMap(url: testUri, accessKey: testAccessKeyId, sessionToken: testSessionToken, region: testRegion, amzDate: testTimestamp, dateStamp: testDatestamp)

        XCTAssertTrue(expectedQueryParams.elementsEqual(actualQueryParams, by: ==))
    }
    
    func test_when_buildQueryParamsMapWithInvalidQueryParameter_then_errorThrown() {
        // The query parameter is malformed because it contains more than one `=` sign. `=` signs are used
        // to seperate the key from the value.
        let malformedQueryParameterString: String = "\(SignerConstants.X_AMZ_SECURITY_TOKEN)=IQoJb3JpZ2luX2VjEKj//////////wEaCXVzLWVhc3QtMSJIMEYCIQCTthgQ/TPulBrPMhU5h/UtUse+BpwUab+C9btFV3i39AIhAJVcIPQL2fOnawG6SG+iedPPxqiGyOJQxFdbtkOsr1KVKp4CCBEQAhoMNzkwNjc1MTI4OTQxIgx/dYEfFDWltpFKMiYq+wEJJf5Z0J05YV4uShxtJIJU3JiFQzSimB7i3uyyli3RZ4Crdk4M7jmYi3j10u//rmWh4tj07wP6UiExrQqge/dLG1l2/mI+npSLhpSOLPQpXZ8P3wj05oZdki3gc0HfIyOVXYscMHWt51CLG6mex46rl6gAVhPIvfgHKrvu+a9lEsqsKgZgDSx/vxU3jJORXde+OoOOh6H1kJbh1Z8YjcZF4gddiAtr+lDAbkP+wfkvx/NXNdX4gvOn2A1q04rwmAqajXnRwrAFfdsTgyuVgtwLzLetgLROU33V9HzlmmB8cgqSfOmckRLYY2oQhjAR9u7miCSgpKlExiKNAzCcnqarBjqcASd000CV05xssboLehIZ76mDRRuLIjAgdT8kghGoCZ7LZsSwtUuyMKXf50g/QCYHoFgJKkMAVKykZArxdwbfuszo/+JVOHTu8ZuXUYjhDHHrImdMdmTd+fIzpjJUmqVbi2MtNKZuGMwJtk5/Yqn9BbnpIAhx3JU7R2TMopaae6ISQH9vW8Qunq4KIIGCvEznB0v8hf9ot4a6po3B/A=="
        let testUri: URL = URL(string: "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?\(malformedQueryParameterString)")!
        let testAccessKeyId: String = "AKIDEXAMPLE"
        let testSessionToken: String? = nil
        let testRegion: String = "us-west-2"
        let testTimestamp: String = "20230724T000000Z"
        let testDatestamp: String = "20230724"

        XCTAssertThrowsError(try Signer.buildQueryParamsMap(url: testUri, accessKey: testAccessKeyId, sessionToken: testSessionToken, region: testRegion, amzDate: testTimestamp, dateStamp: testDatestamp)) { error in
            
            guard case let SignerError.illegalArgument(message) = error else {
                XCTFail("Unexpected error type thrown")
                return
            }
            
            XCTAssertTrue(message.contains(malformedQueryParameterString), "The error message \"\(message)\" should contain \"\(malformedQueryParameterString)\"!")
        }
    }
    
    func test_when_buildQueryParamsMapWithEmptyQueryParameter_then_errorThrown() {
        // The query parameter is malformed because it contains more than one `=` sign. `=` signs are used
        // to seperate the key from the value.
        let malformedQueryParameterString: String = "X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&"
        let testUri: URL = URL(string: "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?\(malformedQueryParameterString)")!
        let testAccessKeyId: String = "AKIDEXAMPLE"
        let testSessionToken: String? = nil
        let testRegion: String = "us-west-2"
        let testTimestamp: String = "20230724T000000Z"
        let testDatestamp: String = "20230724"

        XCTAssertThrowsError(try Signer.buildQueryParamsMap(url: testUri, accessKey: testAccessKeyId, sessionToken: testSessionToken, region: testRegion, amzDate: testTimestamp, dateStamp: testDatestamp)) { error in
            
            guard case let SignerError.illegalArgument(message) = error else {
                XCTFail("Unexpected error type thrown")
                return
            }
            
            XCTAssertTrue(message.lowercased().contains("malformed"), "The error message \"\(message)\" should contain \"malformed\"!")
        }
    }
    
    func test_when_signStringWithExampleInputs_then_validStringToSignIsReturned() {
        let credentialScope = "AKIDEXAMPLE/20150830/us-east-1/service/aws4_request"
        let requestDate = "20150830T123600Z"
        let canonicalRequest = """
            \(SignerConstants.METHOD)
            /
            Param1=value1&Param2=value2
            host:example.amazonaws.com
            x-amz-date:20150830T123600Z
            
            \(SignerConstants.SIGNED_HEADERS);x-amz-date
            e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            """
        
        let expected = """
            AWS4-HMAC-SHA256
            20150830T123600Z
            AKIDEXAMPLE/20150830/us-east-1/service/\(SignerConstants.AWS4_REQUEST_TYPE)
            816cd5b414d056048ba4f7c5386d6e0533120fb1fcfa93762cf0fc39e2cf19e0
            """
        
        let actual = Signer.signString(canonicalRequest: canonicalRequest, amzDate: requestDate, credentialScope: credentialScope)
        
        XCTAssertEqual(expected, actual)
    }
    
    func test_when_getSignatureKeyWithStringToSign_then_validSignatureKeyReturned() {
        let stringToSign: String = """
            \(SignerConstants.ALGORITHM_AWS4_HMAC_SHA_256)
            20150830T123600Z
            20150830/us-east-1/iam/\(SignerConstants.AWS4_REQUEST_TYPE)
            f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59
            """
        
        let signatureKeyBytes: Data = Signer.getSignatureKey(key: "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                                                             dateStamp: "20150830",
                                                             regionName: "us-east-1",
                                                             service: "iam")
        
        let expectedSignature: String = "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9"
        XCTAssertEqual(expectedSignature, signatureKeyBytes.hexString)
        
        let expectedSignatureString: String = "5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7"
        XCTAssertEqual(expectedSignatureString, Signer.hmacSHA256(stringToSign, key: signatureKeyBytes).hexString)
    }
    
    func test_when_urlEncodeStringWithSpecialCharacters_then_specialCharactersAreURLEncoded() {
        let exampleArn: String = "arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123";
        
        let expected: String = "arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123";
        
        let actual: String = Signer.urlEncode(exampleArn);
        
        XCTAssertEqual(expected, actual);
    }
    
    
    func test_when_createCredentialScope_then_validCredentialScopeReturned() {
        let expected: String = "20150930/us-west-2/kinesisvideo/aws4_request"
        
        let actual: String = Signer.buildCredentialScope(region: "us-west-2", dateStamp: "20150930")
        
        XCTAssertEqual(expected, actual)
    }
    
    func test_when_hmacSha256GivenKeyAndData_then_correctSignatureIsReturned() {
        let testKey: Data = "testKey".data(using: .utf8)!
        let testData: String = "testData123"
        let expectedHex: String = "f8117085c5b8be75d01ce86d16d04e90fedfc4be4668fe75d39e72c92da45568"
        
        let actualResult: String = Signer.hmacSHA256(testData, key: testKey).hexString
        
        XCTAssertEqual(expectedHex, actualResult)
    }
    
    struct TimeAndDateParameters {
        let parameter: TimeInterval
        let expectedOutput: String
    }
    
    let sourceFor_when_getTimeStampAroundMidnightUTC_then_dateAndTimeReturnedIsCorrectAndFormattedCorrectly: [TimeAndDateParameters] = [
        // 1689984000000 = Saturday, July 22, 2023 12:00:00.000 AM (UTC)
        TimeAndDateParameters(parameter: 1689984000, expectedOutput: "20230722T000000Z"),
        // 1689983999999 = Friday, July 21, 2023 11:59:59.999 AM (UTC)
        TimeAndDateParameters(parameter: 1689983999.999, expectedOutput: "20230721T235959Z"),
    ]
    
    func test_when_getTimeStampAroundMidnightUTC_then_dateAndTimeReturnedIsCorrectAndFormattedCorrectly() {
        for data in sourceFor_when_getTimeStampAroundMidnightUTC_then_dateAndTimeReturnedIsCorrectAndFormattedCorrectly {
            perform_when_getTimeStampAroundMidnightUTC_then_dateAndTimeReturnedIsCorrectAndFormattedCorrectly(data)
        }
    }
    
    func perform_when_getTimeStampAroundMidnightUTC_then_dateAndTimeReturnedIsCorrectAndFormattedCorrectly(_ data: TimeAndDateParameters) {
        let expectedTimeStamp = data.expectedOutput
        let actualTimeStamp = Signer.getTimeStamp(Date(timeIntervalSince1970: data.parameter))
        
        XCTAssertEqual(expectedTimeStamp, actualTimeStamp)
    }
    
    let sourceFor_when_getDateStampAroundMidnightUTC_then_dateReturnedIsCorrectAndFormattedCorrectly: [TimeAndDateParameters] = [
        // 1689984000000 = Saturday, July 22, 2023 12:00:00.000 AM (UTC)
        TimeAndDateParameters(parameter: 1689984000, expectedOutput: "20230722"),
        // 1689983999999 = Friday, July 21, 2023 11:59:59.999 AM (UTC)
        TimeAndDateParameters(parameter: 1689983999.999, expectedOutput: "20230721"),
    ]
    
    func test_when_getDateStampAroundMidnightUTC_then_dateReturnedIsCorrectAndFormattedCorrectly() {
        for data in sourceFor_when_getDateStampAroundMidnightUTC_then_dateReturnedIsCorrectAndFormattedCorrectly {
            perform_when_getDateStampAroundMidnightUTC_then_dateReturnedIsCorrectAndFormattedCorrectly(data)
        }
    }
    
    func perform_when_getDateStampAroundMidnightUTC_then_dateReturnedIsCorrectAndFormattedCorrectly(_ data: TimeAndDateParameters) {
        let expectedDateStamp = data.expectedOutput
        let actualDateStamp = Signer.getDateStamp(Date(timeIntervalSince1970: data.parameter))
        
        XCTAssertEqual(expectedDateStamp, actualDateStamp)
    }
    
}
