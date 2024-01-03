import Foundation
import CommonCrypto

public struct AWSCredentials {
    let accessKey: String
    let secretKey: String
    let sessionToken: String?
    
    public init(accessKey: String, secretKey: String, sessionToken: String?) {
        self.accessKey = accessKey
        self.secretKey = secretKey
        self.sessionToken = sessionToken
    }
}

enum SignerError: Error {
    case illegalArgument(String)
    case badUrl(String)
}

/// SigV4 Signer sample reference for the Kinesis Video Streams WebRTC WebSocket connections
public class Signer {
    
    /// Constructs and signs the Kinesis Video Streams Signaling WebRTC WebSocket Connect URI with Query
    /// Parameters to connect to Kinesis Video Signaling. This method is responsible for signing the URLRequest
    /// using the AWS Signature Version 4 process. It adds the necessary AWS-specific headers, including X-Amz-Date,
    /// X-Amz-Expires, and X-Amz-Signature, to the request.
    ///
    /// - Parameters:
    ///   - request: The Secure Websocket URLRequest to sign.
    ///     - Important: The URL to sign should be a pre-constructed AWS Kinesis Video Streams WebSocket Connect
    ///       URI, including the necessary query parameters. The URI structure depends on the specific operation. Use
    ///       the GetSignalingChannelEndpoint API to obtain the host name. The two supported operations are:
    ///       - Connect as Master (GetSignalingChannelEndpoint - master role):
    ///         Example: wss://m-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123
    ///         Ref: https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-2.html
    ///       - Connect as Viewer (GetSignalingChannelEndpoint - viewer role):
    ///         Example: wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557
    ///         Ref: https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-1.html
    ///     - Note: The base signaling channel endpoints are different depending on the role (master/viewer) specified in the
    ///       GetSignalingChannelEndpoint API call.
    ///       Ref: https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_SingleMasterChannelEndpointConfiguration.html#KinesisVideo-Type-SingleMasterChannelEndpointConfiguration-Role
    ///   - credentials: The AWS credentials for authentication, including access key, secret key, and optional session token.
    ///   - region: The AWS region where the service resides (e.g., "us-west-2").
    ///   - currentDate: The current date used for signing. The signed URL is valid for 5 minutes from this date.
    ///
    /// - Returns: A signed URLRequest ready to connect to Kinesis Video Streams Signaling.
    ///
    /// - Throws: An error of type `SignerError` if any issues occur during the signing process.
    ///
    /// - Example:
    ///   ```swift
    ///   let unsignedRequest = URLRequest(url: URL(string: "https://example.com/path/to/resource")!)
    ///   let credentials = AWSCredentials(accessKey: "YourAccessKey", secretKey: "YourSecretKey", sessionToken: "YourSessionToken")
    ///   let region = "YourChannelRegion"
    ///   let currentDate = Date()
    ///   do {
    ///       let signedRequest = try Signer.sign(request: unsignedRequest, credentials: credentials, region: region, currentDate: currentDate)
    ///       // Use a websocket client to connect to this secure websocket URL...
    ///   } catch {
    ///       print("Error signing the request: \(error)")
    ///   }
    ///   ```
    ///
    /// - SeeAlso: [Signing AWS requests with Signature Version 4](https://docs.aws.amazon.com/general/latest/gr/signature-version-4.html)
    public static func sign(request: URLRequest, credentials: AWSCredentials, region: String, currentDate: Date) throws -> URLRequest {
        guard let url = request.url, let host = url.host() else {
            throw SignerError.badUrl("A URL with a host is required!")
        }
        
        let amzDate: String = getTimeStamp(currentDate)
        let dateStamp: String = getDateStamp(currentDate)
        
        var queryParametersMap: [(String, String)]
        do {
            queryParametersMap = try buildQueryParamsMap(url: url, accessKey: credentials.accessKey, sessionToken: credentials.sessionToken, region: region, amzDate: amzDate, dateStamp: dateStamp)
        } catch SignerError.illegalArgument(let message) {
            throw SignerError.badUrl(message)
        }
        
        let canonicalQuerystring: String = getCanonicalizedQueryString(&queryParametersMap)
        let canonicalRequest: String = buildCanonicalRequest(url: url, canonicalQueryString: canonicalQuerystring)
        let stringToSign: String = signString(canonicalRequest: canonicalRequest, amzDate: amzDate, credentialScope: buildCredentialScope(region: region, dateStamp: dateStamp))
        let signingKey: Data = getSignatureKey(key: credentials.secretKey, dateStamp: dateStamp, regionName: region, service: SignerConstants.SERVICE)
        
        let signature: String = hmacSHA256(stringToSign, key: signingKey).hexString
        
        return URLRequest(url: URL(string: "wss://\(host)\(url.path())/?\(canonicalQuerystring)&\(SignerConstants.X_AMZ_SIGNATURE)=\(signature)")!)
    }
    
    /// Builds a list of query parameters for AWS request signing based on the provided URL and additional parameters.
    ///
    /// This method constructs a list of query parameters necessary for AWS request signing. It includes standard parameters
    /// such as X-Amz-Algorithm, X-Amz-Credential, X-Amz-Date, X-Amz-Expires, and X-Amz-SignedHeaders. Additionally, it
    /// includes any query parameters present in the original request URL.
    ///
    /// - Example:
    ///   ```swift
    ///   let url = URL(string: "https://example.com?param2=value2&param1=value1")!
    ///   let accessKey = "AKIAIOSFODNN7EXAMPLE"
    ///   let sessionToken = "AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT+FvwqnKwRcOIfrRh3c/LTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE/IvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb/AXlzBBko7b15fjrBs2+cTQtpZ3CYWFXG8C5zqx37wnOE49mRl/+OtkIKGO7fAE"
    ///   let region = "us-west-2"
    ///   let amzDate = "20230718T191301Z"
    ///   let dateStamp = "20230718"
    ///   let queryParams = try buildQueryParamsMap(url: url, accessKey: accessKey, sessionToken: sessionToken, region: region, amzDate: amzDate, dateStamp: dateStamp)
    ///   print(queryParams)
    ///   ```
    ///   Output: `[("X-Amz-Algorithm", "AWS4-HMAC-SHA256"), ("X-Amz-Credential", "AKIAIOSFODNN7EXAMPLE%2F20230718%2Fus-west-2%2Fkinesisvideo%2Faws4_request"), ("X-Amz-Date", "20230718T191301Z"), ("X-Amz-Expires", "299"), ("X-Amz-SignedHeaders", "host"), ("param1", "value1"), ("param2", "value2")]`
    ///
    /// - Parameters:
    ///   - url: The URL to sign.
    ///   - accessKey: The AWS access key used for signing.
    ///   - sessionToken: The optional AWS session token. If provided, X-Amz-Security-Token is added to the parameters and the session token is encoded and assigned as its value.
    ///   - region: The AWS region.
    ///   - amzDate: The formatted date for X-Amz-Date: "yyyyMMdd'T'HHmmss'Z'".
    ///   - dateStamp: The date stamp used in the credential scope: "yyyyMMdd".
    ///
    /// - Returns: An array of key-value pairs representing the query parameters for AWS request signing.
    ///
    /// - Throws: An error of type `SignerError` if the query parameters included in the original url are malformed.
    ///
    /// - Note:
    ///   The query parameters are not guaranteed to be ordered alphabetically based on their keys.
    ///
    /// - SeeAlso: [Query string components](https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html#query-string-components)
    ///
    /// - SeeAlso: [Canonical request specification](https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html#create-canonical-request)
    static func buildQueryParamsMap(url: URL,
                                    accessKey: String,
                                    sessionToken: String?,
                                    region: String,
                                    amzDate: String,
                                    dateStamp: String) throws -> [(String, String)] {
        
        var keyValuePairs: [(String, String)] = []
        keyValuePairs.append((SignerConstants.X_AMZ_ALGORITHM, SignerConstants.ALGORITHM_AWS4_HMAC_SHA_256))
        keyValuePairs.append((SignerConstants.X_AMZ_CREDENTIAL, urlEncode("\(accessKey)/\(buildCredentialScope(region: region, dateStamp: dateStamp))")))
        keyValuePairs.append((SignerConstants.X_AMZ_DATE, amzDate))
        keyValuePairs.append((SignerConstants.X_AMZ_EXPIRES, "299"))
        keyValuePairs.append((SignerConstants.X_AMZ_SIGNED_HEADERS, SignerConstants.SIGNED_HEADERS))
        
        if let unwrappedSessionToken: String = sessionToken, !unwrappedSessionToken.isEmpty {
            keyValuePairs.append((SignerConstants.X_AMZ_SECURITY_TOKEN, urlEncode(unwrappedSessionToken)))
        }
        
        // Add the query parameters included in the original request as well
        // Note: query parameters follow the format: key1=val1&key2=val2&key3=val3
        let queryParams: [String] = (url.query ?? "").components(separatedBy: "&")
        
        // If there are no query parameters, exit.
        if queryParams == [""] {
            return keyValuePairs
        }
        
        // Check that there are no two "&"'s in a row.
        if queryParams.count > 1 && queryParams.contains("") {
            throw SignerError.illegalArgument("Malformed query string!")
        }
        
        // Parse key-value pairs
        for param in queryParams {
            let pair = param.components(separatedBy: "=")
            guard pair.count == 2 else {
                throw SignerError.illegalArgument("Illegal query parameter: \(param)")
            }
            keyValuePairs.append((pair[0], urlEncode(pair[1])))
        }
        
        return keyValuePairs
    }
    
    /// Gets the canonicalized query string for AWS request signing.
    ///
    /// This method takes an array of query parameters in the form of key-value pairs and sorts them lexicographically
    /// based on the parameter names. It then reconstructs the sorted query string in the form "key1=value1&key2=value2".
    ///
    /// - Example:
    ///   ```swift
    ///   var queryParams = [("X-Amz-Expires", "299"), ("X-Amz-Algorithm", "AWS4-HMAC-SHA256"), ("X-Amz-Date", "20230718T191301Z")]
    ///   let canonicalQueryString = getCanonicalizedQueryString(&queryParams)
    ///   print(canonicalQueryString)
    ///   ```
    ///   Output: `"X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20230718T191301Z&X-Amz-Expires=299"`
    ///
    /// - Parameter queryParamsMap: An inout array of query parameters represented as key-value pairs.
    ///   The array is sorted lexicographically based on the parameter names. It should contain the following parameters:
    ///   X-Amz-Algorithm, X-Amz-ChannelARN, X-Amz-ClientId (if viewer), X-Amz-Credential, X-Amz-Date, X-Amz-Expires.
    ///
    /// - Returns: The canonicalized query string.
    ///
    /// - Important:
    ///   This method is an essential step in the AWS request signing process, ensuring that the query parameters are
    ///   sorted before creating the canonical request.
    ///
    /// - Note:
    ///   The `queryParamsMap` parameter is an inout parameter, meaning it is modified directly in the function.
    ///
    /// - SeeAlso: [Query string components](https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html#query-string-components)
    ///
    /// - SeeAlso: [Canonical request specification](https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html#create-canonical-request)
    static func getCanonicalizedQueryString(_ queryParamsMap: inout [(String, String)]) -> String {
        // Sort the key-value pairs based on the keys
        queryParamsMap.sort { $0.0 < $1.0 }
        
        // Reconstruct the sorted query string
        return queryParamsMap.map { "\($0.0)=\($0.1)" }.joined(separator: "&")
    }
    
    /// Builds and returns the canonical request for an AWS request.
    ///
    /// An example canonical request looks like the following:
    /// ```
    /// GET
    /// /
    /// X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20230718%2Fus-west-2%2Fkinesisvideo%2Faws4_request&X-Amz-Date=20230718T191301Z&X-Amz-Expires=299&X-Amz-SignedHeaders=host
    /// host:v-1a2b3c4d.kinesisvideo.us-west-2.amazonaws.com
    ///
    /// host
    /// e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
    /// ```
    ///
    /// The format of the canonical request is as follows:
    /// 1. `{HTTP method}\n` - With presigned URLs, it's always `GET`.
    /// 2. `{Canonical URI}\n` - Resource path. In this case, it's always `/`. 
    /// 3. `{Canonical Query String}\n` - Sorted list of query parameters (and their values), excluding X-Amz-Signature, already URL-encoded.
    ///    In this case: X-Amz-Algorithm, X-Amz-ChannelARN, X-Amz-ClientId (if viewer), X-Amz-Credential, X-Amz-Date, X-Amz-Expires.
    /// 4. `{Canonical Headers}\n` - In this case, we only have the required HTTP `host` header.
    /// 5. `{Signed Headers}\n` - Which headers, from the canonical headers, in alphabetical order.
    /// 6. `{Hashed Payload}` - In this case, it's always the SHA-256 checksum of an empty string.
    ///
    /// - Parameters:
    ///   - url: The URL of the request. The canonical URI is determined based on the path of the provided URL. If the path
    ///   is empty, a forward slash ("/") is used.
    ///   - canonicalQueryString: The canonical query string for the request. Should contain the following parameters: X-Amz-Algorithm, X-Amz-ChannelARN, X-Amz-ClientId (if viewer), X-Amz-Credential, X-Amz-Date, X-Amz-Expires.
    ///
    /// - Returns: The canonical request string.
    ///
    /// - SeeAlso: [Creating a Canonical Request](https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html)
    ///
    /// - Parameters:
    ///   - url: The URL to sign. For example, `wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?
    ///     X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557`.
    ///   - canonicalQuerystring: The Canonical Query String to use in the Canonical Request. Sorted list of query
    ///     parameters (and their values) already URL-encoded. Should include X-Amz-Algorithm, X-Amz-ChannelARN, X-Amz-ClientId (if viewer), X-Amz-Credential, X-Amz-Date, X-Amz-Expires and not include X-Amz-Signature.
    ///
    /// - SeeAlso: [URL encoding](https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html#w28981aab9c27c27c11.UriEncode%28%29:~:text=or%20trailing%20whitespace.-,UriEncode(),-URI%20encode%20every)
    /// - SeeAlso: [Canonical request specification](https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html#create-canonical-request)
    static func buildCanonicalRequest(url: URL, canonicalQueryString: String) -> String {
        let canonicalURI: String = url.path.isEmpty ? "/" : url.path
        let canonicalHeaders: String = buildCanonicalHeaders(url)
        let payloadHash: String = sha256("".data(using: .utf8)!).hexString
        
        return """
            \(SignerConstants.METHOD)
            \(canonicalURI)
            \(canonicalQueryString)
            \(canonicalHeaders)
            
            \(SignerConstants.SIGNED_HEADERS)
            \(payloadHash)
            """
    }
    
    /// Builds and returns the canonical headers for the AWS request.
    ///
    /// The canonical headers are constructed by including the "host" header with the
    /// host information extracted from the provided URL.
    ///
    /// - Parameter url: The URL of the request.
    ///
    /// - Returns: The canonical headers string
    ///
    /// - SeeAlso: [Canonical Headers](https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-headers.html)
    static func buildCanonicalHeaders(_ url: URL) -> String {
        return "host:\(url.host()!)"
    }
    
    /// Creates and returns the string to sign for AWS request signing.
    ///
    /// The string to sign is constructed by combining the hashing algorithm used, the
    /// formatted timestamp (amzDate), the credential scope, and the hashed canonical request. The string to sign is formatted as follows:
    /// ```
    /// AWS4-HMAC-SHA256
    /// {amzDate}
    /// {credentialScope}
    /// {hashedCanonicalRequest}
    /// ```
    ///
    /// - Parameters:
    ///   - canonicalRequest: The canonical request string. This function will hash this using the HMAC-SHA256 algorithm.
    ///   - amzDate: The formatted timestamp in the format "yyyyMMdd'T'HHmmss'Z'".
    ///   - credentialScope: The credential scope used in the request signing process.
    ///
    /// - Returns: The string to sign for AWS request signing.
    ///
    /// - SeeAlso: [String to Sign](https://docs.aws.amazon.com/general/latest/gr/sigv4-create-string-to-sign.html)
    static func signString(canonicalRequest: String, amzDate: String, credentialScope: String) -> String {
        return """
            \(SignerConstants.ALGORITHM_AWS4_HMAC_SHA_256)
            \(amzDate)
            \(credentialScope)
            \(sha256(canonicalRequest.data(using: .utf8)!).hexString)
            """
    }

    
    /// Builds the credential scope required for AWS request signing.
    ///
    /// The credential scope is constructed by combining the date stamp, AWS region, service name,
    /// and AWS request type in the following format:
    /// ```
    /// {dateStamp}/{region}/{service}/{AWS4_REQUEST_TYPE}
    /// ```
    ///
    /// - Parameters:
    ///   - region: The AWS region where the request is made, e.g., "us-west-2".
    ///   - dateStamp: The date stamp in the format "yyyyMMdd".
    ///
    /// - Returns: The credential scope string.
    ///
    /// - SeeAlso: [AWS Request Signing](https://docs.aws.amazon.com/general/latest/gr/sigv4-create-string-to-sign.html)
    static func buildCredentialScope(region: String, dateStamp: String) -> String {
        return "\(dateStamp)/\(region)/\(SignerConstants.SERVICE)/\(SignerConstants.AWS4_REQUEST_TYPE)"
    }

    
    /// Calculates and returns the signature key used in the AWS signing process.
    ///
    /// The formula for generating the signature key is as follows:
    /// ```
    /// kDate = hash("AWS4" + Key, Date)
    /// kRegion = hash(kDate, Region)
    /// kService = hash(kRegion, ServiceName)
    /// kSigning = hash(kService, "aws4_request")
    /// kSignature = hash(kSigning, string-to-sign)
    /// ```
    ///
    /// - Parameters:
    ///   - key: AWS secret access key.
    ///   - dateStamp: Date used in the credential scope. Format: yyyyMMdd.
    ///   - regionName: AWS region. Example: us-west-2.
    ///   - serviceName: The name of the service. Should be `kinesisvideo`.
    /// - Returns: `kSignature` as specified above.
    ///
    /// - SeeAlso: [Calculate Signature Documentation](https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html#calculate-signature)
    static func getSignatureKey(key: String, dateStamp: String, regionName: String, service: String) -> Data {
        let kDate = hmacSHA256(dateStamp, key: ("AWS4" + key).data(using: .utf8)!)
        let kRegion = hmacSHA256(regionName, key: kDate)
        let kService = hmacSHA256(service, key: kRegion)
        let kSigning = hmacSHA256(SignerConstants.AWS4_REQUEST_TYPE, key: kService)
        return kSigning
    }
    
    /// Calculates the HMAC-SHA256 hash value for the given data and key.
    ///
    /// - Parameters:
    ///   - data: The string data to be hashed.
    ///   - key: The key used in the HMAC-SHA256 calculation.
    /// - Returns: The resulting HMAC-SHA256 hash value as raw data.
    static func hmacSHA256(_ data: String, key: Data) -> Data {
        // Convert the string data to UTF-8 encoded data
        let dataToSign = data.data(using: .utf8) ?? Data()
        
        // Prepare an array to store the resulting hash value
        var result = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        
        // Perform the HMAC-SHA256 calculation
        dataToSign.withUnsafeBytes { dataToSignBuffer in
            key.withUnsafeBytes { keyDataBuffer in
                CCHmac(CCHmacAlgorithm(kCCHmacAlgSHA256),
                       keyDataBuffer.baseAddress,
                       keyDataBuffer.count,
                       dataToSignBuffer.baseAddress,
                       dataToSignBuffer.count,
                       &result)
            }
        }
        
        // Return the hash value as raw data
        return Data(result)
    }
    
    /// Calculates the SHA-256 hash of the given data.
    ///
    /// - Parameters:
    ///   - data: The input data for which the SHA-256 hash needs to be calculated.
    /// - Returns: The SHA-256 hash of the input data.
    static private func sha256(_ data: Data) -> Data {
        // Prepare an array to store the resulting hash value
        var result = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        
        // Perform the SHA-256 calculation
        data.withUnsafeBytes { bytes in
            _ = CC_SHA256(bytes.baseAddress, CC_LONG(data.count), &result)
        }

        // Return the hash value as raw data
        return Data(result)
    }

    
    /// URL-encodes the given string, making it safe for inclusion in a URL.
    /// This function uses the .urlHostAllowed character set for URL encoding.
    /// Additionally, it replaces the characters '+' with '%2B' and '=' with '%3D',
    /// which are commonly used in query parameters.
    ///
    /// - Parameters:
    ///   - toEncode: The string to be URL-encoded.
    /// - Returns: A URL-encoded string, or an empty string if the input is nil.
    static func urlEncode(_ toEncode: String) -> String {
        return toEncode.addingPercentEncoding(withAllowedCharacters: .urlHostAllowed)!
            .replacingOccurrences(of: "+", with: "%2B")
            .replacingOccurrences(of: "=", with: "%3D")
    }

    /// Returns a formatted timestamp string based on the provided date.
    ///
    /// This function formats the given date in the "yyyyMMdd'T'HHmmss'Z'" format
    /// and returns the resulting timestamp string.
    ///
    /// - Parameter date: The date to be formatted.
    /// - Returns: A formatted timestamp string.
    static func getTimeStamp(_ date: Date) -> String {
        return formatDate(date, format: "yyyyMMdd'T'HHmmss'Z'")
    }

    /// Returns a formatted date stamp string based on the provided date.
    ///
    /// This function formats the given date in the "yyyyMMdd" format
    /// and returns the resulting date stamp string.
    ///
    /// - Parameter date: The date to be formatted.
    /// - Returns: A formatted date stamp string.
    static func getDateStamp(_ date: Date) -> String {
        return formatDate(date, format: "yyyyMMdd")
    }

    
    /// Formats the provided date using the specified format and returns the result as a string.
    ///
    /// This function uses the DateFormatter to format the given date based on the provided format string.
    /// The time zone is set to UTC by default.
    ///
    /// - Parameters:
    ///   - date: The date to be formatted.
    ///   - format: The format string used for date formatting.
    /// - Returns: A string representation of the formatted date.
    private static func formatDate(_ date: Date, format: String) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = format
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.string(from: date)
    }

}

extension Data {
    var hexString: String {
        return map { String(format: "%02hhx", $0) }.joined()
    }
}
