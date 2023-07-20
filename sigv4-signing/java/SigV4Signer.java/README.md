# Sigv4 Signer Samples

## Features

These are some sample reference code you can use to create
an [AWS Sigv4](https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html)
Signed WebSocket URL to connect to Amazon Kinesis Video Streams Signaling
as [Master](https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-2.html)
or [Viewer](https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-1.html)
.

Each sample also contains variables you can use to verify that the WebSocket
URLs work with your resources.

## Run

In each of the samples, modify the variables in the `main()` method:

| Variable Name | Description                                                                                                                                                                                                                                                                                                                                                                              | Example                                                                                                                                                                                                   |
|---------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| uri | The URI to sign. The result from GetSignalingChannelEndpoint plus the relevant Query Parameters. For more information, see [ConnectAsMaster](https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-2.html) or [ConnectAsViewer](https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-1.html). | wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557 |
| wssUri | Secure WebSocket method "wss" plus hostname obtained from GetSignalingChannelEndpoint                                                                                                                                                                                                                                                                                                    | wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com                                                                                                                                                     |
| accessKey | AWS Access Key                                                                                                                                                                                                                                                                                                                                                                           | AKIAIOSFODNN7EXAMPLE                                                                                                                                                                                      |
| secretKey | AWS Secret Key                                                                                                                                                                                                                                                                                                                                                                           | wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY                                                                                                                                                                  |
| sessionToken | AWS Session Token, if temporary credentials are used. Otherwise, it should be an empty String (`""`).                                                                                                                                                                                                                                                                                      | AQoDYXdzEJr...\<remainder of token\>
| region | AWS Region.                                                                                                                                                                                                                                                                                                                                                                              | us-west-2                                                                                                                                                                                                 |

Afterwards, run the `main()` method, and a signed URL will be generated and the
sample application will also attempt to connect to the generated URL.

## Using `wscat`

You can also use [`wscat`](https://www.npmjs.com/package/wscat) as your
WebSocket client.

1. Install wscat:

```shell
npm install -g wscat
```

2. Run `wscat` with the generated WebSocket URL:

```shell
wscat -c "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com/?X-Amz-Algorithm=AWS4-HMAC-SHA256&..."
```

```shell
Connected (press CTRL+C to quit)
> %     
```

## Troubleshooting

If you are experiencing a `400` error:
* The URL is invalid. Check the following:
* Ensure that the endpoint used matches the role you are connecting as. The `uri`/`wssUri` for your signaling channel is different depending on `role` specified when making the [GetSignalingChannelEndpoint](https://docs.aws.amazon.com/kinesisvideostreams/latest/dg/API_GetSignalingChannelEndpoint.html) API request.
* If connecting as viewer, ensure that `X-Amz-ClientId` does not begin with `AWS_`.

If you are experiencing a `403` error:
* Something is wrong with signing. Try the following:
* Double-check that the AWS Credentials used are not expired, or copy/pasted incorrectly.
* Ensure that the `uri` and `wssUri` does not have any copy/paste errors.
* Ensure that the correct region is passed in.

If you are experiencing a `404` error:
* Ensure that the Signaling Channel ARN specified as the `X-Amz-ChannelARN` query parameter in the `uri` is valid. 

