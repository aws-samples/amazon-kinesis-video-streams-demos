/// Custom SigV4 Signer for KVS Signaling WebSocket, ConnectAsMaster and ConnectAsViewer
/// Compared to AWS SDK Rust implementation, this has less dependencies
pub mod aws_v4_signer {
    use crate::sigv4_signer_constants::constants::{
        ALGORITHM_AWS4_HMAC_SHA_256, AWS4_REQUEST_TYPE, DATE_PATTERN, METHOD, NEW_LINE_DELIMITER,
        SERVICE, SIGNED_HEADERS, TIME_PATTERN, X_AMZ_ALGORITHM, X_AMZ_CREDENTIAL, X_AMZ_DATE,
        X_AMZ_EXPIRES, X_AMZ_SECURITY_TOKEN, X_AMZ_SIGNATURE, X_AMZ_SIGNED_HEADERS,
    };
    use chrono::DateTime;
    use hmac::digest::Digest;
    use hmac::{Hmac, Mac};
    use sha2::Sha256;
    use std::collections::HashMap;
    use url::{form_urlencoded, Url};

    pub struct AwsV4Signer {}

    impl AwsV4Signer {
        pub fn sign(
            uri: &Url,
            access_key: &str,
            secret_key: &str,
            session_token: &str,
            region: &str,
            date_milli: i64,
        ) -> Url {
            // Step 1. Create canonical request.
            let amz_date = Self::get_time_stamp(date_milli);
            let datestamp = Self::get_date_stamp(date_milli);
            let query_params_map = Self::build_query_params_map(
                uri,
                access_key,
                session_token,
                region,
                &amz_date,
                &datestamp,
            );
            let canonical_querystring = Self::get_canonicalized_query_string(&query_params_map);
            let canonical_request = Self::get_canonical_request(uri, &canonical_querystring);

            // Step 2. Construct StringToSign.
            let string_to_sign = Self::sign_string(
                &amz_date,
                Self::create_credential_scope(region, datestamp.as_str()).as_str(),
                &canonical_request,
            );

            // Step 3. Calculate the signature.
            let signature_key = Self::get_signature_key(secret_key, &datestamp, region, SERVICE);
            let signature = hex::encode(Self::hmac_sha256(string_to_sign.as_str(), &signature_key));

            // Step 4. Combine steps 1 and 3 to form the final URL.
            let signed_canonical_query_string = format!(
                "{}&{}={}",
                canonical_querystring, X_AMZ_SIGNATURE, signature
            );
            let mut signed_uri = uri.clone();
            signed_uri.set_query(Some(&signed_canonical_query_string));

            signed_uri
        }
        pub fn build_query_params_map(
            uri: &Url,
            access_key: &str,
            session_token: &str,
            region: &str,
            amz_date: &str,
            datestamp: &str,
        ) -> HashMap<String, String> {
            let x_amz_credential = Self::url_encode(&format!(
                "{}/{}",
                access_key,
                Self::create_credential_scope(region, datestamp)
            ));

            let mut query_params_map = HashMap::new();
            query_params_map.insert(
                X_AMZ_ALGORITHM.to_string(),
                ALGORITHM_AWS4_HMAC_SHA_256.to_string(),
            );
            query_params_map.insert(X_AMZ_CREDENTIAL.to_string(), x_amz_credential);
            query_params_map.insert(X_AMZ_DATE.to_string(), amz_date.to_string());
            query_params_map.insert(X_AMZ_EXPIRES.to_string(), "299".to_string());

            if !session_token.is_empty() {
                query_params_map.insert(
                    X_AMZ_SECURITY_TOKEN.to_string(),
                    Self::url_encode(session_token),
                );
            }

            query_params_map.insert(X_AMZ_SIGNED_HEADERS.to_string(), SIGNED_HEADERS.to_string());

            // Add the query parameters included in the uri.
            // Note: query parameters follow the format: key1=val1&key2=val2&key3=val3
            if let Some(query) = uri.query() {
                for param in query.split('&') {
                    if let Some((key, value)) = param.split_once('=') {
                        query_params_map.insert(key.to_string(), Self::url_encode(value));
                    }
                }
            }

            query_params_map
        }

        pub fn create_credential_scope(region: &str, datestamp: &str) -> String {
            vec![datestamp, region, SERVICE, AWS4_REQUEST_TYPE].join("/")
        }

        pub fn get_canonical_request(uri: &Url, canonical_querystring: &str) -> String {
            // Compute SHA-256 hash of an empty payload
            let payload_hash = Sha256::digest(&[] as &[u8]);
            let payload_hash_hex = hex::encode(payload_hash);

            // Get canonical URI
            let canonical_uri = Self::get_canonical_uri(uri);

            // Build canonical headers
            let canonical_headers = format!(
                "host:{}{}",
                uri.host_str().unwrap_or(""),
                NEW_LINE_DELIMITER
            );

            vec![
                METHOD,
                &*canonical_uri,
                canonical_querystring,
                &*canonical_headers,
                SIGNED_HEADERS,
                &*payload_hash_hex,
            ]
            .join(NEW_LINE_DELIMITER)
        }

        pub fn get_canonical_uri(uri: &Url) -> String {
            if uri.path().is_empty() {
                "/".to_string()
            } else {
                uri.path().to_string()
            }
        }

        pub fn sign_string(
            amz_date: &str,
            credential_scope: &str,
            canonical_request: &str,
        ) -> String {
            let result_hex = hex::encode(Sha256::digest(canonical_request.as_bytes()));

            format!(
                "{}\n{}\n{}\n{}",
                ALGORITHM_AWS4_HMAC_SHA_256, amz_date, credential_scope, result_hex
            )
        }

        pub fn url_encode(s: &str) -> String {
            form_urlencoded::byte_serialize(s.as_bytes()).collect()
        }

        pub fn hmac_sha256(data: &str, key: &[u8]) -> Vec<u8> {
            let mut hmac: Hmac<Sha256> = Hmac::new_from_slice(key).unwrap();
            hmac.update(data.as_bytes());
            hmac.finalize().into_bytes().to_vec()
        }

        pub fn get_signature_key(
            key: &str,
            date_stamp: &str,
            region_name: &str,
            service_name: &str,
        ) -> Vec<u8> {
            let binding = format!("AWS4{}", &key).to_string();
            let k_secret = binding.as_bytes();
            let k_date = Self::hmac_sha256(&date_stamp, &k_secret);
            let k_region = Self::hmac_sha256(&region_name, &k_date);
            let k_service = Self::hmac_sha256(&service_name, &k_region);
            Self::hmac_sha256(&AWS4_REQUEST_TYPE, &k_service)
        }

        pub fn get_time_stamp(date_milli: i64) -> String {
            let datetime = DateTime::from_timestamp_millis(date_milli).unwrap();
            datetime.format(&TIME_PATTERN).to_string()
        }

        pub fn get_date_stamp(date_milli: i64) -> String {
            let datetime = DateTime::from_timestamp_millis(date_milli).unwrap();
            datetime.format(&DATE_PATTERN).to_string()
        }

        pub fn get_canonicalized_query_string(params: &HashMap<String, String>) -> String {
            let mut query_pairs: Vec<String> =
                params.iter().map(|(k, v)| format!("{}={}", k, v)).collect();
            query_pairs.sort(); // Must be in canonical order
            query_pairs.join("&")
        }
    }
}
