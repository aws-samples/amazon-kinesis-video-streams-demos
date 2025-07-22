use chrono::Utc;
use kvs_signaling_sigv4_wss_signer::sigv4_signer::aws_v4_signer::AwsV4Signer;
use tungstenite::connect;
use url::Url;

fn main() {
    /////////////////////////////////////////

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
    // let uri = Url::parse("wss://<Your GetSignalingChannelEndpoint response hostname>?X-Amz-ChannelARN=<YourChannelARN>&X-Amz-ClientId=<YourClientId>").unwrap();
    let uri = Url::parse("wss://<Your GetSignalingChannelEndpoint response hostname>?X-Amz-ChannelARN=<YourChannelARN>&X-Amz-ClientId=<YourClientId>").unwrap();

    // AWS Credentials. Session Token should be empty string if non-temporary credentials are used.
    let aws_access_key_id = "YourAccessKey";
    let aws_secret_access_key = "YourSecretKey";
    let aws_session_token = "YourSessionToken";

    // AWS Region. For example, us-west-2
    let aws_region = "YourChannelRegion";

    /////////////////////////////////////////

    let url = AwsV4Signer::sign(
        &uri,
        &aws_access_key_id,
        &aws_secret_access_key,
        &aws_session_token,
        &aws_region,
        Utc::now().timestamp_millis(),
    );
    println!("Connecting to the WebSocket URL:\n{}", url);

    // Connect to the SigV4 signed Endpoint
    let (mut socket, response) = connect(url.as_str()).expect("Failed to connect");

    // Print the responses from the connection
    println!("-----------------");
    println!("HTTP Response Code: {}", response.status());
    println!("HTTP Response Headers: {:?}", response.headers());
    println!("HTTP Response Body: {:?}", response.body());
    println!("-----------------");

    socket
        .close(None)
        .and_then(|()| {
            println!("Successfully closed the WebSocket connection.");
            Ok(())
        })
        .expect("Failed to close WebSocket connection");
}
