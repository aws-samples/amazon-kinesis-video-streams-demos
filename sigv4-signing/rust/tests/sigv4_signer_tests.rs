#[cfg(test)]
mod tests {
    use kvs_signaling_sigv4_wss_signer::sigv4_signer::aws_v4_signer::AwsV4Signer;
    use kvs_signaling_sigv4_wss_signer::sigv4_signer_constants::constants::{
        ALGORITHM_AWS4_HMAC_SHA_256, AWS4_REQUEST_TYPE, METHOD, NEW_LINE_DELIMITER, SIGNED_HEADERS,
        X_AMZ_ALGORITHM, X_AMZ_CREDENTIAL, X_AMZ_DATE, X_AMZ_EXPIRES, X_AMZ_SECURITY_TOKEN,
        X_AMZ_SIGNED_HEADERS,
    };
    use std::collections::HashMap;
    use url::Url;

    #[test]
    fn test_sign_master_url_with_temporary_credentials() {
        let master_uri_to_sign_protocol_and_host =
            "wss://m-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com";
        let uri_to_sign = Url::parse(&(master_uri_to_sign_protocol_and_host.to_string() + "?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123")).unwrap();
        let access_key_id = "AKIAIOSFODNN7EXAMPLE";
        let secret_key_id = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        let session_token = "AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT+FvwqnKwRcOIfrRh3c/LTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE/IvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb/AXlzBBko7b15fjrBs2+cTQtpZ3CYWFXG8C5zqx37wnOE49mRl/+OtkIKGO7fAE";
        let region = "us-west-2";
        let date_milli = 1690186022951;

        let actual = AwsV4Signer::sign(
            &uri_to_sign,
            access_key_id,
            secret_key_id,
            session_token,
            region,
            date_milli,
        );

        let expected = Url::parse(&(master_uri_to_sign_protocol_and_host.to_string() + "/?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20230724%2Fus-west-2%2Fkinesisvideo%2Faws4_request&X-Amz-Date=20230724T080702Z&X-Amz-Expires=299&X-Amz-Security-Token=AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT%2BFvwqnKwRcOIfrRh3c%2FLTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE%2FIvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb%2FAXlzBBko7b15fjrBs2%2BcTQtpZ3CYWFXG8C5zqx37wnOE49mRl%2F%2BOtkIKGO7fAE&X-Amz-SignedHeaders=host&X-Amz-Signature=f8fed632bbe38ac920c7ed2eeaba1a4ba5e2b1bd7aada9f852708112eab76baa")).unwrap();

        assert_eq!(actual, expected, "Expected {} but got {}", expected, actual);
    }

    #[test]
    fn test_sign_viewer_url_with_temporary_credentials() {
        let viewer_uri_to_sign_protocol_and_host =
            "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com";
        let uri_to_sign = Url::parse(&(viewer_uri_to_sign_protocol_and_host.to_string() + "?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557")).unwrap();
        let access_key_id = "AKIAIOSFODNN7EXAMPLE";
        let secret_key_id = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        let session_token = "AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT+FvwqnKwRcOIfrRh3c/LTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE/IvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb/AXlzBBko7b15fjrBs2+cTQtpZ3CYWFXG8C5zqx37wnOE49mRl/+OtkIKGO7fAE";
        let region = "us-west-2";
        let date_milli = 1690186022958;

        let actual = AwsV4Signer::sign(
            &uri_to_sign,
            access_key_id,
            secret_key_id,
            session_token,
            region,
            date_milli,
        );

        let expected = Url::parse(&(viewer_uri_to_sign_protocol_and_host.to_string() + "/?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20230724%2Fus-west-2%2Fkinesisvideo%2Faws4_request&X-Amz-Date=20230724T080702Z&X-Amz-Expires=299&X-Amz-Security-Token=AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT%2BFvwqnKwRcOIfrRh3c%2FLTo6UDdyJwOOvEVPvLXCrrrUtdnniCEXAMPLE%2FIvU1dYUg2RVAJBanLiHb4IgRmpRV3zrkuWJOgQs8IZZaIv2BXIa2R4OlgkBN9bkUDNCJiBeb%2FAXlzBBko7b15fjrBs2%2BcTQtpZ3CYWFXG8C5zqx37wnOE49mRl%2F%2BOtkIKGO7fAE&X-Amz-SignedHeaders=host&X-Amz-Signature=77ea5ff8ede2e22aa268a3a068f1ad3a5d92f0fa8a427579f9e6376e97139761")).unwrap();

        assert_eq!(actual, expected, "Expected {} but got {}", expected, actual);
    }

    #[test]
    fn test_sign_master_url_with_long_term_credentials() {
        let master_uri_to_sign_protocol_and_host =
            "wss://m-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com";
        let uri_to_sign = Url::parse(&(master_uri_to_sign_protocol_and_host.to_string() + "?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123")).unwrap();
        let access_key_id = "AKIAIOSFODJJ7EXAMPLE";
        let secret_key_id = "wJalrXUtnFEMI/K7MDENG/bPxQQiCYEXAMPLEKEY";
        let session_token = "";
        let region = "us-west-2";
        let date_milli = 1690186022101;

        let actual = AwsV4Signer::sign(
            &uri_to_sign,
            access_key_id,
            secret_key_id,
            session_token,
            region,
            date_milli,
        );

        let expected = Url::parse(&(master_uri_to_sign_protocol_and_host.to_string() + "/?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&X-Amz-Credential=AKIAIOSFODJJ7EXAMPLE%2F20230724%2Fus-west-2%2Fkinesisvideo%2Faws4_request&X-Amz-Date=20230724T080702Z&X-Amz-Expires=299&X-Amz-SignedHeaders=host&X-Amz-Signature=0bbef329f0d9d3e68635f7b844ac684c7764a0c228ca013232d935c111b9a370")).unwrap();

        assert_eq!(actual, expected, "Expected {} but got {}", expected, actual);
    }

    #[test]
    fn test_sign_viewer_url_with_long_term_credentials() {
        let viewer_uri_to_sign_protocol_and_host =
            "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com";
        let uri_to_sign = Url::parse(&(viewer_uri_to_sign_protocol_and_host.to_string() + "?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557")).unwrap();
        let access_key_id = "AKIAIOSFODJJ7EXAMPLE";
        let secret_key_id = "wJalrXUtnFEMI/K7MDENG/bPxQQiCYEXAMPLEKEY";
        let session_token = "";
        let region = "us-west-2";
        let date_milli = 1690186022208;

        let actual = AwsV4Signer::sign(
            &uri_to_sign,
            access_key_id,
            secret_key_id,
            session_token,
            region,
            date_milli,
        );

        let expected = Url::parse(&(viewer_uri_to_sign_protocol_and_host.to_string() + "/?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-ChannelARN=arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557&X-Amz-Credential=AKIAIOSFODJJ7EXAMPLE%2F20230724%2Fus-west-2%2Fkinesisvideo%2Faws4_request&X-Amz-Date=20230724T080702Z&X-Amz-Expires=299&X-Amz-SignedHeaders=host&X-Amz-Signature=cea541f699dc51bc53a55590ce817e63cc06fac2bdef4696b63e0889eb448f0b")).unwrap();

        assert_eq!(
            actual, expected,
            "Expected\n{}\nbut got\n{}",
            expected, actual
        );
    }

    #[test]
    fn test_build_query_params_map_with_temporary_credentials() {
        let test_uri = Url::parse("wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557").unwrap();
        let test_access_key_id = "AKIDEXAMPLE";
        let test_session_token = "SSEXAMPLE";
        let test_region = "us-west-2";
        let test_timestamp = "20230724T000000Z";
        let test_datestamp = "20230724";

        let mut expected_query_params = HashMap::new();
        expected_query_params.insert(
            X_AMZ_ALGORITHM.to_string(),
            ALGORITHM_AWS4_HMAC_SHA_256.to_string(),
        );
        expected_query_params.insert(
            X_AMZ_CREDENTIAL.to_string(),
            "AKIDEXAMPLE%2F20230724%2Fus-west-2%2Fkinesisvideo%2Faws4_request".to_string(),
        );
        expected_query_params.insert(X_AMZ_DATE.to_string(), test_timestamp.to_string());
        expected_query_params.insert(X_AMZ_EXPIRES.to_string(), "299".to_string());
        expected_query_params.insert(X_AMZ_SIGNED_HEADERS.to_string(), SIGNED_HEADERS.to_string());
        expected_query_params.insert(
            X_AMZ_SECURITY_TOKEN.to_string(),
            AwsV4Signer::url_encode(test_session_token),
        );
        expected_query_params.insert("X-Amz-ChannelARN".to_string(), "arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123".to_string());
        expected_query_params.insert(
            "X-Amz-ClientId".to_string(),
            "d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557".to_string(),
        );

        let actual_query_params = AwsV4Signer::build_query_params_map(
            &test_uri,
            test_access_key_id,
            test_session_token,
            test_region,
            test_timestamp,
            test_datestamp,
        );

        assert!(
            are_maps_same(&expected_query_params, &actual_query_params),
            "Expected {:?}, but got {:?}",
            expected_query_params,
            actual_query_params
        );
    }

    fn are_maps_same(expected: &HashMap<String, String>, actual: &HashMap<String, String>) -> bool {
        if expected.len() != actual.len() {
            return false;
        }

        for (key, value) in expected {
            if actual.get(key) != Some(value) {
                return false;
            }
        }

        true
    }

    #[test]
    fn test_get_canonical_request_with_query_parameters() {
        // Parse the URL
        let uri = Url::parse("http://example.amazonaws.com").unwrap();

        // Define query parameters
        let mut params_map = HashMap::new();
        params_map.insert("Param2".to_string(), "value2".to_string());
        params_map.insert("Param1".to_string(), "value1".to_string());

        // Generate canonical query string
        let canonical_querystring = AwsV4Signer::get_canonicalized_query_string(&params_map);

        // Expected canonical request
        let canonical_result_expected = format!(
            "{}{}{}{}{}{}{}{}{}{}{}{}{}",
            METHOD,
            NEW_LINE_DELIMITER,
            "/",
            NEW_LINE_DELIMITER,
            canonical_querystring,
            NEW_LINE_DELIMITER,
            "host:example.amazonaws.com",
            NEW_LINE_DELIMITER,
            "",
            NEW_LINE_DELIMITER,
            SIGNED_HEADERS,
            NEW_LINE_DELIMITER,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );

        let actual_canonical_request =
            AwsV4Signer::get_canonical_request(&uri, &canonical_querystring);

        assert_eq!(
            actual_canonical_request, canonical_result_expected,
            "Expected {}, but got {}",
            canonical_result_expected, actual_canonical_request
        );
    }

    #[test]
    fn test_get_canonical_uri() {
        let test_cases = vec![("", "/"), ("/", "/"), ("/hey", "/hey")];

        for (input_url_ending, expected_output) in test_cases {
            let url = Url::parse(
                format!(
                    "{}{}",
                    "wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com", input_url_ending
                )
                .as_str(),
            )
            .unwrap();
            let actual_output = AwsV4Signer::get_canonical_uri(&url);

            assert_eq!(
                actual_output,
                expected_output,
                "{}: Expected {}, but got {}",
                url.as_str(),
                expected_output,
                actual_output
            );
        }
    }

    #[test]
    fn test_sign_string() {
        let credential_scope = "AKIDEXAMPLE/20150830/us-east-1/service/aws4_request";
        let request_date = "20150830T123600Z";
        let canonical_request = format!(
            "{}\n{}\n{}\n{}\n{}\n{}\n{}{}\n{}",
            METHOD,
            "/",
            "Param1=value1&Param2=value2",
            "host:example.amazonaws.com",
            "x-amz-date:20150830T123600Z",
            "",
            SIGNED_HEADERS,
            ";x-amz-date",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        );

        let expected = format!(
            "{}\n{}\n{}{}\n{}",
            ALGORITHM_AWS4_HMAC_SHA_256,
            "20150830T123600Z",
            "AKIDEXAMPLE/20150830/us-east-1/service/",
            AWS4_REQUEST_TYPE,
            "816cd5b414d056048ba4f7c5386d6e0533120fb1fcfa93762cf0fc39e2cf19e0",
        );

        let actual =
            AwsV4Signer::sign_string(request_date, credential_scope, canonical_request.as_str());

        assert_eq!(actual, expected, "Signed string mismatch");
    }

    #[test]
    fn test_url_encode() {
        let example_arn =
            "arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123";
        let expected = "arn%3Aaws%3Akinesisvideo%3Aus-west-2%3A123456789012%3Achannel%2Fdemo-channel%2F1234567890123";

        let actual = AwsV4Signer::url_encode(example_arn);

        assert_eq!(actual, expected, "URL encoding mismatch");
    }

    #[test]
    fn test_hmac_sha256() {
        let test_key = b"testKey";
        let test_data = "testData123";
        let expected_hex = "f8117085c5b8be75d01ce86d16d04e90fedfc4be4668fe75d39e72c92da45568";

        let actual_result = AwsV4Signer::hmac_sha256(test_data, test_key);
        let actual_hex = hex::encode(actual_result);

        assert_eq!(actual_hex, expected_hex, "HMAC-SHA256 mismatch");
    }

    #[test]
    fn test_get_signature_key_and_hmac_sha256() {
        let string_to_sign = format!(
            "{}\n{}\n{}\n{}",
            ALGORITHM_AWS4_HMAC_SHA_256,
            "20150830T123600Z",
            "20150830/us-east-1/iam/aws4_request",
            "f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59"
        );

        let signature_key_bytes = AwsV4Signer::get_signature_key(
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            "20150830",
            "us-east-1",
            "iam",
        );

        let expected_signature = "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9";
        let actual_signature = hex::encode(&signature_key_bytes);

        assert_eq!(actual_signature, expected_signature, "Signature mismatch");

        let expected_signature_string =
            "5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7";
        let actual_signature_string = hex::encode(AwsV4Signer::hmac_sha256(
            string_to_sign.as_str(),
            &signature_key_bytes,
        ));

        assert_eq!(
            actual_signature_string, expected_signature_string,
            "Signature string mismatch"
        );
    }

    #[test]
    fn test_get_time_stamp() {
        let test_cases = vec![
            (
                "Saturday, July 22, 2023 12:00:00.000 AM (UTC)",
                1689984000000,
                "20230722T000000Z",
            ),
            (
                "Friday, July 21, 2023 11:59:59.999 PM (UTC)",
                1689983999999,
                "20230721T235959Z",
            ),
        ];

        for (name, date_milli, expected_output) in test_cases {
            let actual_output = AwsV4Signer::get_time_stamp(date_milli);
            assert_eq!(
                actual_output, expected_output,
                "{}: Expected {}, but got {}",
                name, expected_output, actual_output
            );
        }
    }

    #[test]
    fn test_get_date_stamp() {
        let test_cases = vec![
            (
                "Saturday, July 22, 2023 12:00:00.000 AM (UTC)",
                1689984000000,
                "20230722",
            ),
            (
                "Friday, July 21, 2023 11:59:59.999 PM (UTC)",
                1689983999999,
                "20230721",
            ),
        ];

        for (name, date_milli, expected_output) in test_cases {
            let actual_output = AwsV4Signer::get_date_stamp(date_milli);
            assert_eq!(
                actual_output, expected_output,
                "{}: Expected {}, but got {}",
                name, expected_output, actual_output
            );
        }
    }
}
