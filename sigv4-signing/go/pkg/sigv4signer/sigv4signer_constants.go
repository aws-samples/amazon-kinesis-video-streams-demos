package sigv4signer

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
