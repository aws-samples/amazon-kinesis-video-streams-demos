pub mod constants {
    pub const ALGORITHM_AWS4_HMAC_SHA_256: &str = "AWS4-HMAC-SHA256";
    pub const AWS4_REQUEST_TYPE: &str = "aws4_request";
    pub const SERVICE: &str = "kinesisvideo";
    pub const X_AMZ_ALGORITHM: &str = "X-Amz-Algorithm";
    pub const X_AMZ_CREDENTIAL: &str = "X-Amz-Credential";
    pub const X_AMZ_DATE: &str = "X-Amz-Date";
    pub const X_AMZ_EXPIRES: &str = "X-Amz-Expires";
    pub const X_AMZ_SECURITY_TOKEN: &str = "X-Amz-Security-Token";
    pub const X_AMZ_SIGNATURE: &str = "X-Amz-Signature";
    pub const X_AMZ_SIGNED_HEADERS: &str = "X-Amz-SignedHeaders";
    pub const NEW_LINE_DELIMITER: &str = "\n";
    pub const DATE_PATTERN: &str = "%Y%m%d";
    pub const TIME_PATTERN: &str = "%Y%m%dT%H%M%SZ";
    pub const METHOD: &str = "GET";
    pub const SIGNED_HEADERS: &str = "host";
}
