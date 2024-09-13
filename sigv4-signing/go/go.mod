module github.com/aws-samples/amazon-kinesis-video-streams-demos/sigv4-signing/go

go 1.20

require (
	github.com/aws/aws-sdk-go-v2 v1.30.5
	github.com/aws/aws-sdk-go-v2/credentials v1.17.32
	github.com/aws/aws-sdk-go-v2/service/kinesisvideo v1.25.6
	github.com/aws/aws-sdk-go-v2/service/sts v1.30.7
	github.com/gorilla/websocket v1.5.3
)

require (
	github.com/aws/aws-sdk-go-v2/internal/configsources v1.3.17 // indirect
	github.com/aws/aws-sdk-go-v2/internal/endpoints/v2 v2.6.17 // indirect
	github.com/aws/aws-sdk-go-v2/service/internal/accept-encoding v1.11.4 // indirect
	github.com/aws/aws-sdk-go-v2/service/internal/presigned-url v1.11.19 // indirect
	github.com/aws/smithy-go v1.20.4 // indirect
)
