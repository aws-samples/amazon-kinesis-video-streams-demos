package org.amazonaws.kinesisvideo;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TimeZone;

import static com.google.common.hash.Hashing.sha256;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

public class AwsV4Signer {

    private static final String ALGORITHM_AWS4_HMAC_SHA_256 = "AWS4-HMAC-SHA256";
    private static final String AWS4_REQUEST_TYPE = "aws4_request";
    private static final String SERVICE = "kinesisvideo";
    private static final String X_AMZ_ALGORITHM = "X-Amz-Algorithm";
    private static final String X_AMZ_CREDENTIAL = "X-Amz-Credential";
    private static final String X_AMZ_DATE = "X-Amz-Date";
    private static final String X_AMZ_EXPIRES = "X-Amz-Expires";
    private static final String X_AMZ_SECURITY_TOKEN = "X-Amz-Security-Token";
    private static final String X_AMZ_SIGNATURE = "X-Amz-Signature";
    private static final String X_AMZ_SIGNED_HEADERS = "X-Amz-SignedHeaders";
    private static final String NEW_LINE_DELIMITER = "\n";
    private static final String DATE_PATTERN = "yyyyMMdd";
    private static final String TIME_PATTERN = "yyyyMMdd'T'HHmmss'Z'";

    private static final String METHOD = "GET";
    private static final String SIGNED_HEADERS = "host";

    // SigV4 Signer sample reference for the Kinesis Video Streams WebRTC WebSocket connections
    // It will generate a sigv4 url given valid parameters, then connect to it using Tyrus WebSocket client
    public static void main(String[] args) throws NoSuchAlgorithmException, KeyManagementException, URISyntaxException {

        /////////////////////////////////////////

        // URI to sign.
        // Connect as Viewer URL - GetSignalingChannelEndpoint + Query Parameters: Channel ARN as X-Amz-ChannelARN & Client Id as X-Amz-ClientId
        // For example: wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557
        final URI uri = new URI("wss://<Your GetSignalingChannelEndpoint response hostname>?X-Amz-ChannelARN=<YourChannelARN>&X-Amz-ClientId=<YourClientId>");

        // AWS Credentials. Session Token should be empty string if non-temporary credentials are used.
        final String accessKey = "YourAccessKey";
        final String secretKey = "YourSecretKey";
        final String sessionToken = "YourSessionToken";

        // Secure WebSocket method "wss" plus hostname obtained from GetSignalingChannelEndpoint
        // For example: wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com
        final URI wssUri = new URI("wss://<Your GetSignalingChannelEndpoint Response hostname>");

        // AWS Region. For example, us-west-2
        final String region = "us-west-2";

        /////////////////////////////////////////

        final ClientManager client = new ClientManager();
        client.getProperties().put(ClientProperties.LOG_HTTP_UPGRADE, true);

        final URI signedUri = sign(uri, accessKey, secretKey, sessionToken, wssUri, region);

        try (final Session session = client.connectToServer(new Endpoint() {
            @Override
            public void onOpen(Session session, EndpointConfig config) {
                System.out.println("WebSocket opened!");
            }
        }, null, URI.create(signedUri.toString()))) {
            System.out.println("Success!");
        } catch (final Exception ex) {
            ex.printStackTrace();
        }
    }


    // Guide - https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
    // Implementation based on https://docs.aws.amazon.com/general/latest/gr/sigv4-signed-request-examples.html#sig-v4-examples-get-query-string
    public static URI sign(final URI uri, final String accessKey, final String secretKey,
                           final String sessionToken, final URI wssUri, final String region) {

        final long dateMilli = new Date().getTime();
        final String amzDate = getTimeStamp(dateMilli);
        final String datestamp = getDateStamp(dateMilli);

        final Map<String, String> queryParamsMap = buildQueryParamsMap(uri, accessKey, sessionToken, region, amzDate, datestamp);
        final String canonicalQuerystring = getCanonicalizedQueryString(queryParamsMap);
        final String canonicalRequest = getCanonicalRequest(uri, canonicalQuerystring);
        final String stringToSign = signString(amzDate, createCredentialScope(region, datestamp), canonicalRequest);
        final byte[] signatureKey = getSignatureKey(secretKey, datestamp, region, SERVICE);
        final String signature = toHex(hmacSha256(stringToSign, signatureKey));

        final String signedCanonicalQueryString = canonicalQuerystring + "&" + X_AMZ_SIGNATURE + "=" + signature;

        URI uriResult = null;
        try {
            uriResult = new URI(wssUri.getScheme(),
                    wssUri.getRawAuthority(),
                    getCanonicalUri(uri),
                    signedCanonicalQueryString, // Note: URI constructor will encode non-URI characters in this String.
                    null);
        } catch (final URISyntaxException e) {
            e.printStackTrace();
        }

        return uriResult;
    }

    private static Map<String, String> buildQueryParamsMap(URI uri, String accessKey, String sessionToken, String region, String amzDate, String datestamp) {
        final ImmutableMap.Builder<String, String> queryParamsBuilder = ImmutableMap.<String, String>builder()
                .put(X_AMZ_ALGORITHM, ALGORITHM_AWS4_HMAC_SHA_256)
                .put(X_AMZ_CREDENTIAL, urlEncode(accessKey + "/" + createCredentialScope(region, datestamp)))
                .put(X_AMZ_DATE, amzDate)
                .put(X_AMZ_EXPIRES, "299")
                .put(X_AMZ_SIGNED_HEADERS, SIGNED_HEADERS);

        if (isNotEmpty(sessionToken)) {
            queryParamsBuilder.put(X_AMZ_SECURITY_TOKEN, urlEncode(sessionToken));
        }

        if (isNotEmpty(uri.getQuery())) {
            final String[] params = uri.getQuery().split("&");
            for (final String param : params) {
                final int index = param.indexOf('=');
                if (index > 0) {
                    queryParamsBuilder.put(param.substring(0, index), urlEncode(param.substring(index + 1)));
                }
            }
        }
        return queryParamsBuilder.build();
    }

    private static String createCredentialScope(String region, String datestamp) {
        return new StringJoiner("/").add(datestamp).add(region).add(SERVICE).add(AWS4_REQUEST_TYPE).toString();
    }

    private static String getCanonicalRequest(URI uri, String canonicalQuerystring) {
        final String payloadHash = sha256().hashString(EMPTY, UTF_8).toString();
        final String canonicalUri = getCanonicalUri(uri);
        final String canonicalHeaders = "host:" + uri.getHost() + NEW_LINE_DELIMITER;
        final String canonicalRequest = new StringJoiner(NEW_LINE_DELIMITER).add(METHOD)
                .add(canonicalUri)
                .add(canonicalQuerystring)
                .add(canonicalHeaders)
                .add(SIGNED_HEADERS)
                .add(payloadHash)
                .toString();

        return canonicalRequest;
    }

    private static String getCanonicalUri(URI uri) {
        return isEmpty(uri.getPath()) ? "/" : uri.getPath();
    }

    private static String signString(String amzDate, String credentialScope, String canonicalRequest) {
        final String stringToSign = new StringJoiner(NEW_LINE_DELIMITER).add(ALGORITHM_AWS4_HMAC_SHA_256)
                .add(amzDate)
                .add(credentialScope)
                .add(sha256().hashString(canonicalRequest, UTF_8).toString()) // checksum
                .toString();
        return stringToSign;
    }

    private static String urlEncode(final String str) {
        try {
            return URLEncoder.encode(str, UTF_8.name());
        } catch (final UnsupportedEncodingException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    //  https://docs.aws.amazon.com/general/latest/gr/signature-v4-examples.html#signature-v4-examples-java
    private static byte[] hmacSha256(final String data, final byte[] key) {
        final String algorithm = "HmacSHA256";
        final Mac mac;
        try {
            mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    //   https://docs.aws.amazon.com/general/latest/gr/signature-v4-examples.html#signature-v4-examples-java
    private static byte[] getSignatureKey(
            final String key,
            final String dateStamp,
            final String regionName,
            final String serviceName) {
        final byte[] kSecret = ("AWS4" + key).getBytes(StandardCharsets.UTF_8);
        final byte[] kDate = hmacSha256(dateStamp, kSecret);
        final byte[] kRegion = hmacSha256(regionName, kDate);
        final byte[] kService = hmacSha256(serviceName, kRegion);
        return hmacSha256(AWS4_REQUEST_TYPE, kService);
    }

    private static String getTimeStamp(long dateMilli) {
        return format(TIME_PATTERN, new Date(dateMilli));
    }

    private static String getDateStamp(long dateMilli) {
        return format(DATE_PATTERN, new Date(dateMilli));
    }

    private static String getCanonicalizedQueryString(Map<String, String> queryParamsMap) {
        final List<String> queryKeys = new ArrayList<>(queryParamsMap.keySet());
        Collections.sort(queryKeys);

        final StringBuilder builder = new StringBuilder();

        for (int i = 0; i < queryKeys.size(); i++) {
            builder.append(queryKeys.get(i)).append("=").append(queryParamsMap.get(queryKeys.get(i)));
            if (queryKeys.size() - 1 > i) {
                builder.append("&");
            }
        }

        return builder.toString();
    }

    private static String format(String pattern, Date date) {
        return getSimpleDateFormat(pattern).get().format(date);
    }

    private static final Map<String, ThreadLocal<SimpleDateFormat>> SDF_MAP = new HashMap<>();

    private static final TimeZone GMT_TIMEZONE = TimeZone.getTimeZone("GMT");

    private static ThreadLocal<SimpleDateFormat> getSimpleDateFormat(final String pattern) {
        ThreadLocal<SimpleDateFormat> sdf = SDF_MAP.get(pattern);
        if (sdf == null) {
            synchronized (SDF_MAP) {
                sdf = SDF_MAP.get(pattern);
                if (sdf == null) {
                    sdf = new ThreadLocal<SimpleDateFormat>() {
                        @Override
                        protected SimpleDateFormat initialValue() {
                            final SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
                            sdf.setTimeZone(GMT_TIMEZONE);
                            sdf.setLenient(false);
                            return sdf;
                        }
                    };
                    SDF_MAP.put(pattern, sdf);
                }
            }
        }
        return sdf;
    }

    private static final int HEX_LENGTH_8 = 8;
    private static final int FF_LOCATION = 6;

    private static String toHex(byte[] data) {
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (int i = 0; i < data.length; i++) {
            String hex = Integer.toHexString(data[i]);
            if (hex.length() == 1) {
                // Append leading zero.
                sb.append("0");
            } else if (hex.length() == HEX_LENGTH_8) {
                // Remove ff prefix from negative numbers.
                hex = hex.substring(FF_LOCATION);
            }
            sb.append(hex);
        }
        return StringUtils.lowerCase(sb.toString());
    }
}
