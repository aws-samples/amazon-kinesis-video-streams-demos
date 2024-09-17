use aws_config::Region;
use aws_credential_types::Credentials as Creds;
use aws_sdk_kinesisvideo::types::{
    ChannelProtocol, ChannelRole, SingleMasterChannelEndpointConfiguration,
};
use aws_sdk_sts::types::Credentials;
use aws_sdk_sts::Error;
use chrono::Utc;
use kvs_signaling_sigv4_wss_signer::sigv4_signer::aws_v4_signer::AwsV4Signer;
use rand::Rng;
use tokio::time::sleep;
use tungstenite::connect;
use url::Url;

#[tokio::main]
async fn main() {
    /////////////////////////////////////////
    // ***Repeated connection test*** - fill in the variables
    // You can use this application to manually verify the signer implementation using different
    // sets of credentials and timestamps. Each attempt, a new set of temporary credentials
    // from AWS STS is used to sign the request and connect to the endpoint.

    // AWS Credentials. Session Token should be empty string if non-temporary credentials are used.
    let aws_access_key_id = "YourAccessKey";
    let aws_secret_access_key = "YourSecretKey";
    let aws_session_token = "YourSessionToken";

    let channel_name = "YourSignalingChannelName";
    let aws_region = "YourRegion"; // AWS Region. For example, us-west-2
    let role: ChannelRole = ChannelRole::Master; // or ChannelRole::Viewer

    let num_iterations = 50;
    let sleep_duration_ms = 5000; // Wait between iterations = sleepDurationMs + rand(0, sleepJitterMs)
    let sleep_jitter_ms = 5000;

    /////////////////////////////////////////

    if aws_access_key_id == "YourAccessKey"
        || aws_secret_access_key == "YourSecretKey"
        || aws_session_token == "YourSessionToken"
        || channel_name == "YourSignalingChannelName"
        || aws_region == "YourRegion"
    {
        panic!("You need to configure the application with your credentials, channel name, and region.");
    }

    // Refer to https://docs.aws.amazon.com/sdk-for-rust/latest/dg/credproviders.html for other authentication methods
    let creds;
    if aws_session_token.is_empty() {
        creds = Creds::from_keys(aws_access_key_id, aws_secret_access_key, None);
    } else {
        creds = Creds::from_keys(
            aws_access_key_id,
            aws_secret_access_key,
            Some(aws_session_token.to_string()),
        );
    }

    let channel_arn_result = fetch_signaling_channel_arn(aws_region, creds.clone(), channel_name)
        .await
        .unwrap();
    let channel_arn = channel_arn_result.as_str();
    let wss_endpoint_result =
        fetch_web_socket_endpoint(aws_region, creds.clone(), channel_arn, role.clone())
            .await
            .unwrap();
    let wss_endpoint = wss_endpoint_result.as_str();

    for i in 1..num_iterations {
        println!("Attempt: {}", i);

        let temp_creds = fetch_new_temp_credentials(aws_region, creds.clone())
            .await
            .unwrap();

        let uri;
        if role == ChannelRole::Master {
            uri = Url::parse(format!("{}?X-Amz-ChannelARN={}", wss_endpoint, channel_arn).as_str())
                .unwrap();
        } else {
            // If connecting to the viewer endpoint, randomly generate a Client ID
            let mut rng = rand::thread_rng();
            let client_id: String = (0..16)
                .map(|_| rng.sample(rand::distributions::Alphanumeric))
                .map(char::from)
                .collect();
            uri = Url::parse(
                format!(
                    "{}?X-Amz-ChannelARN={}&X-Amz-ClientId={}",
                    wss_endpoint, channel_arn, client_id
                )
                .as_str(),
            )
            .unwrap();
        }

        let url = AwsV4Signer::sign(
            &uri,
            temp_creds.access_key_id.as_str(),
            temp_creds.secret_access_key.as_str(),
            temp_creds.session_token.as_str(),
            &aws_region,
            Utc::now().timestamp_millis(),
        );
        println!("Connecting to the WebSocket URL:\n{}", url);

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

        // Wait between iterations = sleepDurationMs + rand(0, sleepJitterMs)
        let random_sleep_time = rand::thread_rng()
            .gen_range(sleep_duration_ms..sleep_duration_ms + sleep_jitter_ms + 1);
        sleep(std::time::Duration::from_millis(random_sleep_time)).await;
    }
}

// fetchNewTempCredentials fetches new temporary credentials from AWS STS (Security Token Service)
async fn fetch_new_temp_credentials(aws_region: &str, creds: Creds) -> Result<Credentials, Error> {
    let sts_client = aws_sdk_sts::client::Client::from_conf(
        aws_sdk_sts::config::Config::builder()
            .behavior_version_latest()
            .region(Region::new(aws_region.to_string()))
            .credentials_provider(creds)
            .build(),
    );

    let result = sts_client
        .get_session_token()
        .duration_seconds(900)
        .send()
        .await;

    Ok(result?.credentials.unwrap())
}

// fetchSignalingChannelARN fetches the Amazon Resource Name (ARN) of the specified Kinesis Video Signaling Channel
async fn fetch_signaling_channel_arn(
    aws_region: &str,
    creds: Creds,
    signaling_channel_name: &str,
) -> Result<String, Error> {
    let kvs_client = aws_sdk_kinesisvideo::client::Client::from_conf(
        aws_sdk_kinesisvideo::config::Config::builder()
            .behavior_version_latest()
            .region(Region::new(aws_region.to_string()))
            .credentials_provider(creds)
            .build(),
    );

    let result = kvs_client
        .describe_signaling_channel()
        .channel_name(signaling_channel_name)
        .send()
        .await;

    Ok(result.unwrap().channel_info.unwrap().channel_arn.unwrap())
}

// fetchWebSocketEndpoint fetches the Secure WebSocket (WSS) endpoint for the specified Kinesis Video Signaling Channel
async fn fetch_web_socket_endpoint(
    aws_region: &str,
    creds: Creds,
    signaling_channel_arn: &str,
    role: ChannelRole,
) -> Result<String, Error> {
    let kvs_client = aws_sdk_kinesisvideo::client::Client::from_conf(
        aws_sdk_kinesisvideo::config::Config::builder()
            .behavior_version_latest()
            .region(Region::new(aws_region.to_string()))
            .credentials_provider(creds)
            .build(),
    );

    let result = kvs_client
        .get_signaling_channel_endpoint()
        .channel_arn(signaling_channel_arn)
        .single_master_channel_endpoint_configuration(
            SingleMasterChannelEndpointConfiguration::builder()
                .protocols(ChannelProtocol::Wss)
                .role(role)
                .build(),
        )
        .send()
        .await;

    Ok(result
        .unwrap()
        .resource_endpoint_list
        .unwrap()
        .get(0)
        .unwrap()
        .resource_endpoint
        .as_ref()
        .unwrap()
        .clone())
}
