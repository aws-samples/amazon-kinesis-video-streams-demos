package com.amazonaws.kinesisvideo.java.auth;

import com.amazonaws.DefaultRequest;
import com.amazonaws.SignableRequest;
import com.amazonaws.auth.AWS4Signer;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.http.HttpMethodName;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;

/**
 * AWS Signature Version 4 signer for Kinesis Video Streams requests.
 * Extends the AWS SDK's AWS4Signer to provide custom signing behavior for streaming operations.
 */
public class AwsSigV4Signer extends AWS4Signer {
    private static final String CONTENT_HASH_HEADER = "x-amz-content-sha256";
    private static final String CONTENT_UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    private static final String AUTH_HEADER = "Authorization";
    private static final String DATE_HEADER = "X-Amz-Date";
    private static final String SECURITY_TOKEN_HEADER = "X-Amz-Security-Token";

    private final AWSCredentials credentials;
    private final String region;
    private final String serviceName;

    /**
     * Creates a new AWS Signature V4 signer.
     * 
     * @param accessKey the AWS access key
     * @param secretKey the AWS secret key
     * @param sessionToken the AWS session token (can be null for permanent credentials)
     * @param region the AWS region (e.g., "us-west-2")
     */
    public AwsSigV4Signer(String accessKey, String secretKey, String sessionToken, String region) {
        this.credentials = sessionToken != null && !sessionToken.isEmpty() 
            ? new BasicSessionCredentials(accessKey, secretKey, sessionToken)
            : new BasicAWSCredentials(accessKey, secretKey);
        this.region = region;
        this.serviceName = "kinesisvideo";
    }

    /**
     * Calculates the content hash for the request.
     * For POST requests, returns UNSIGNED-PAYLOAD to support streaming.
     * 
     * @param request the request to calculate hash for
     * @return the content hash string
     */
    @Override
    protected String calculateContentHash(SignableRequest<?> request) {
        if (HttpMethodName.POST.name().equals(request.getHttpMethod().name())) {
            return CONTENT_UNSIGNED_PAYLOAD;
        }
        return super.calculateContentHash(request);
    }

    /**
     * Signs an HTTP request using AWS Signature Version 4.
     * 
     * @param uri the request URI
     * @param headers the HTTP headers to include in signing
     * @param content the request content (can be null)
     * @return a map containing all headers including authentication headers
     */
    public Map<String, String> signRequest(URI uri, Map<String, String> headers, InputStream content) {
        setServiceName(serviceName);
        setRegionName(region);

        DefaultRequest<Void> request = new DefaultRequest<>(serviceName);
        request.setHttpMethod(HttpMethodName.POST);
        request.setEndpoint(URI.create(uri.getScheme() + "://" + uri.getHost()));
        request.setResourcePath(uri.getPath());
        request.setHeaders(headers);
        if (content != null) {
            request.setContent(content);
        }

        sign(request, credentials);

        Map<String, String> result = new java.util.HashMap<>(headers);
        result.put(AUTH_HEADER, (String) request.getHeaders().get(AUTH_HEADER));
        result.put(DATE_HEADER, (String) request.getHeaders().get(DATE_HEADER));
        
        Object securityToken = request.getHeaders().get(SECURITY_TOKEN_HEADER);
        if (securityToken != null) {
            result.put(SECURITY_TOKEN_HEADER, (String) securityToken);
        }
        
        if (HttpMethodName.POST.name().equals(request.getHttpMethod().name())) {
            result.put(CONTENT_HASH_HEADER, CONTENT_UNSIGNED_PAYLOAD);
        }

        return result;
    }
}