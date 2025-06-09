use std::time::{Duration, SystemTime};
use aws_config::Region;
use aws_credential_types::{provider::{ProvideCredentials}, Credentials};
use aws_sdk_kinesisvideo::{Client, config::BehaviorVersion};
use aws_sdk_kinesisvideo::types::{ChannelProtocol, ChannelRole, SingleMasterChannelEndpointConfiguration};
use aws_sigv4::http_request::{sign, SignableBody, SignableRequest, SignatureLocation, SigningError, SigningSettings};
use aws_sigv4::sign::v4;
use http::Uri;
use rand::distributions::Alphanumeric;
use rand::Rng;
use kvs_signaling_sigv4_wss_signer::sigv4_signer_constants::constants::{SERVICE, METHOD};

/// Configuration struct holding AWS credentials and client information
#[derive(Debug)]
pub struct KVSWebsocketSignerConfig {
    region: Region,
    credentials: Credentials,
    client: Client,
    role: ChannelRole,
    viewer_id: Option<String>
}

/// Custom error types for the Kinesis operations
#[derive(Debug)]
pub enum SignerSampleError {
    /// Errors related to AWS credentials
    CredentialsError(String),
    /// Errors related to endpoint operations
    EndpointError(String),
    /// Errors related to URI parsing/building
    UriError(String),
}

/// Entry point
/// Extracts the signaling channel name and role from the command-line arguments
/// This program signs the MASTER URL
#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();

    // Get channel name (arg[1])
    let channel_name = if args.len() < 2 {
        eprintln!("Usage: {} <channel-name> [MASTER|VIEWER]", args[0]);
        std::process::exit(1);
    } else {
        &args[1]
    };

    // Get role (arg[2])
    let role: ChannelRole = if args.len() >= 3 {
        match args[2].to_uppercase().as_str() {
            "MASTER" => ChannelRole::Master,
            "VIEWER" => ChannelRole::Viewer,
            _ => {
                eprintln!("Invalid role. Must be either MASTER or VIEWER. Defaulting to MASTER.");
                ChannelRole::Master
            }
        }
    } else {
        println!("Using role: MASTER.");
        ChannelRole::Master // Default value
    };

    let viewer_id: Option<String> = if role == ChannelRole::Viewer {
        Some(rand::thread_rng()
                 .sample_iter(&Alphanumeric)
                 .take(10)
                 .map(char::from)
                 .collect())
    } else {
        None
    };

    run(&channel_name, role, viewer_id)
        .await
        .expect("Unable to connect to the signaling websocket");
}

/// Main entry point for generating a signed WebSocket URL
///
/// This function orchestrates the entire process of:
/// 1. Initializing AWS configuration
/// 2. Getting the channel ARN
/// 3. Getting the WebSocket endpoint
/// 4. Signing the request to generate the final URL
pub async fn run(channel_name: &str, role: ChannelRole, viewer_id: Option<String>) -> Result<(), SignerSampleError> {
    let config = initialize_kinesis_config(role, viewer_id).await;
    if config.is_err() {
        return config.map(|_| ());
    }
    let config = config.unwrap();

    // Get the channel ARN for the specified channel name
    println!("Channel name: {}, region: {}", &channel_name, &config.region);
    let channel_arn = get_channel_arn(&config.client, &channel_name).await;
    if channel_arn.is_err() {
        return channel_arn.map(|_| ());
    }
    let channel_arn = channel_arn.unwrap();

    // Get the WebSocket endpoint for the channel
    let endpoint_wss_uri = get_websocket_endpoint(&config, &channel_arn).await;
    if endpoint_wss_uri.is_err() {
        return endpoint_wss_uri.map(|_| ());
    }
    let endpoint_wss_uri = endpoint_wss_uri.unwrap();

    // Generate the signed URL
    let signed_url = sign_request(&config, &endpoint_wss_uri, &channel_arn).await;
    if signed_url.is_err() {
        return signed_url.map(|_| ());
    }
    let signed_url = signed_url.unwrap();

    println!("The signed URL is: {}", signed_url);
    Ok(())
}

/// Creates the KVS Client
/// Returns a KVSWebsocketSignerConfig containing the KVS Client, credentials, and region
async fn initialize_kinesis_config(role: ChannelRole, viewer_id: Option<String>) -> Result<KVSWebsocketSignerConfig, SignerSampleError> {
    // Use the default credentials and region providers
    // Check the documentation https://docs.aws.amazon.com/sdk-for-rust/latest/dg/credproviders.html
    // And https://docs.aws.amazon.com/sdk-for-rust/latest/dg/region.html
    let client_configuration = aws_config::defaults(BehaviorVersion::latest())
        .load()
        .await;

    // Extract the region from the provider
    let region: Region = client_configuration.region().unwrap().clone();

    // Extract credentials from the provider
    let credentials: Credentials = client_configuration
        .credentials_provider()
        .expect("Unable to find credentials")
        .provide_credentials()
        .await
        .map_err(|e| SignerSampleError::CredentialsError(e.to_string()))
        .expect("Unable to fetch credentials")
        .clone();

    // Create KVS Client
    let client = Client::new(&client_configuration);

    Ok(KVSWebsocketSignerConfig {
        region,
        credentials,
        client,
        role,
        viewer_id
    })
}

async fn get_channel_arn(client: &Client, channel_name: &str) -> Result<String, SignerSampleError> {
    let resp = client
        .describe_signaling_channel()
        .set_channel_name(Some(channel_name.to_string()))
        .send()
        .await;

    if resp.is_err() {
        eprintln!("{:?}", resp);
        return Err(SignerSampleError::EndpointError(resp.err().unwrap().into_service_error().to_string()));
    }
    let resp = resp.unwrap();

    let channel_info = resp.channel_info
        .expect("ChannelInfo wasn't returned!");

    let channel_arn = channel_info.channel_arn()
        .expect("No ChannelARN was found in the ChannelInfo!");

    Ok(channel_arn.to_string())
}

/// Retrieves the WebSocket (WSS) endpoint for the channel
async fn get_websocket_endpoint(config: &KVSWebsocketSignerConfig, channel_arn: &str) -> Result<Uri, SignerSampleError> {
    let client = &config.client;
    let get_signaling_endpoint_request = SingleMasterChannelEndpointConfiguration::builder()
        .set_protocols(Some(vec![ChannelProtocol::Wss]))
        .set_role(Some(config.role.clone()))
        .build();

    let resp = client
        .get_signaling_channel_endpoint()
        .set_channel_arn(Some(channel_arn.to_string()))
        .set_single_master_channel_endpoint_configuration(Some(get_signaling_endpoint_request))
        .send()
        .await;

    if resp.is_err() {
        return Err(SignerSampleError::EndpointError(resp.err().unwrap().into_service_error().to_string()));
    }
    let resp = resp.unwrap();

    let endpoint_uri_str = resp.resource_endpoint_list()
        .iter()
        .find_map(|endpoint| {
            if endpoint.protocol == Some(ChannelProtocol::Wss) {
                endpoint.resource_endpoint().map(String::from)
            } else {
                None
            }
        });

    if endpoint_uri_str.is_none() {
        return Err(SignerSampleError::EndpointError("No WSS endpoint found".to_string()));
    }
    let endpoint_uri_str = endpoint_uri_str.unwrap();

    let uri = Uri::from_maybe_shared(endpoint_uri_str);
    if uri.is_err() {
        return Err(SignerSampleError::UriError(uri.err().unwrap().to_string()));
    }

    Ok(uri.unwrap())
}

/// Signs the request
async fn sign_request(
    config: &KVSWebsocketSignerConfig,
    endpoint_wss_uri: &Uri,
    channel_arn: &str,
) -> Result<String, SignerSampleError> {
    let mut signing_settings = SigningSettings::default();
    signing_settings.signature_location = SignatureLocation::QueryParams;
    signing_settings.expires_in = Some(Duration::from_secs(5 * 60)); // 5 mins, 300 secs

    let identity = config.credentials.clone().into();
    let region_string = config.region.to_string();

    let signing_params = v4::SigningParams::builder()
        .identity(&identity)
        .region(&region_string)
        .name(SERVICE)
        .time(SystemTime::now())
        .settings(signing_settings)
        .build();

    if signing_params.is_err() {
        return Err(SignerSampleError::EndpointError(signing_params.err().unwrap().to_string()));
    }
    let signing_params = signing_params.unwrap().into();

    let mut query = format!(
        "/?X-Amz-ChannelARN={}",
        aws_smithy_http::query::fmt_string(channel_arn) // URL-encode the ARN
    ).to_owned();

    if config.role == ChannelRole::Viewer {
        let viewer_id_param = format!("&X-Amz-ClientId={}", config.viewer_id.clone().unwrap());
        query.push_str(viewer_id_param.as_str()); // ClientId is required to be URL-safe already
    }

    let url_to_sign = Uri::builder()
        .scheme("wss")
        .authority(endpoint_wss_uri.authority().unwrap().to_owned())
        .path_and_query(query)
        .build();

    if url_to_sign.is_err() {
        return Err(SignerSampleError::UriError(url_to_sign.err().unwrap().to_string()));
    }
    let transcribe_uri = url_to_sign.unwrap();

    let signable_request: Result<SignableRequest, SigningError> = SignableRequest::new(
        METHOD,
        transcribe_uri.to_string(),
        std::iter::empty(),
        SignableBody::Bytes(&[]),
    );

    if signable_request.is_err() {
        return Err(SignerSampleError::EndpointError(signable_request.err().unwrap().to_string()));
    }
    let signable_request: SignableRequest = signable_request.unwrap();

    let request = http::Request::builder()
        .uri(transcribe_uri)
        .body(aws_smithy_types::body::SdkBody::empty());

    if request.is_err() {
        return Err(SignerSampleError::EndpointError(request.err().unwrap().to_string()));
    }
    let mut request = request.unwrap();

    let sign_result = sign(signable_request, &signing_params);
    if sign_result.is_err() {
        return Err(SignerSampleError::EndpointError(sign_result.err().unwrap().to_string()));
    }

    let (signing_instructions, _) = sign_result.unwrap().into_parts();
    signing_instructions.apply_to_request_http1x(&mut request);

    Ok(request.uri().to_string())
}
