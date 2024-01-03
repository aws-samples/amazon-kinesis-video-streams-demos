import Foundation

public final class SignerConstants {
    static let ALGORITHM_AWS4_HMAC_SHA_256 = "AWS4-HMAC-SHA256"
    static let AWS4_REQUEST_TYPE = "aws4_request"
    static let SERVICE = "kinesisvideo"
    static let X_AMZ_ALGORITHM = "X-Amz-Algorithm"
    static let X_AMZ_CREDENTIAL = "X-Amz-Credential"
    static let X_AMZ_DATE = "X-Amz-Date"
    static let X_AMZ_EXPIRES = "X-Amz-Expires"
    static let X_AMZ_SECURITY_TOKEN = "X-Amz-Security-Token"
    static let X_AMZ_SIGNATURE = "X-Amz-Signature"
    static let X_AMZ_SIGNED_HEADERS = "X-Amz-SignedHeaders"
    static let NEW_LINE_DELIMITER = "\n"
    static let DATE_PATTERN = "yyyyMMdd"
    static let TIME_PATTERN = "yyyyMMdd'T'HHmmss'Z'"
    static let METHOD = "GET"
    static let SIGNED_HEADERS = "host"
}
