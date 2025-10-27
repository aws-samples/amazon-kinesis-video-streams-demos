package com.amazonaws.kinesisvideo.config;

/**
 * Configuration for Kinesis Video Streams client
 */
public class ClientConfiguration {
    private String region;
    private String accessKey;
    private String secretKey;
    private String sessionToken;
    private int connectionTimeoutMs = 30000;
    private int socketTimeoutMs = 60000;
    
    /**
     * Creates a new ClientConfiguration for the specified region.
     * 
     * @param region the AWS region (e.g., "us-west-2")
     */
    public ClientConfiguration(String region) {
        this.region = region;
    }
    
    /**
     * Sets AWS credentials.
     * 
     * @param accessKey the AWS access key
     * @param secretKey the AWS secret key
     * @return this configuration instance for method chaining
     */
    public ClientConfiguration withCredentials(String accessKey, String secretKey) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        return this;
    }
    
    /**
     * Sets the AWS session token for temporary credentials.
     * 
     * @param sessionToken the AWS session token
     * @return this configuration instance for method chaining
     */
    public ClientConfiguration withSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
        return this;
    }
    
    /**
     * Sets the connection timeout.
     * 
     * @param timeoutMs timeout in milliseconds
     * @return this configuration instance for method chaining
     */
    public ClientConfiguration withConnectionTimeout(int timeoutMs) {
        this.connectionTimeoutMs = timeoutMs;
        return this;
    }
    
    /**
     * Sets the socket timeout.
     * 
     * @param timeoutMs timeout in milliseconds
     * @return this configuration instance for method chaining
     */
    public ClientConfiguration withSocketTimeout(int timeoutMs) {
        this.socketTimeoutMs = timeoutMs;
        return this;
    }
    
    /** @return the AWS region */
    public String getRegion() { return region; }
    
    /** @return the AWS access key */
    public String getAccessKey() { return accessKey; }
    
    /** @return the AWS secret key */
    public String getSecretKey() { return secretKey; }
    
    /** @return the AWS session token */
    public String getSessionToken() { return sessionToken; }
    
    /** @return the connection timeout in milliseconds */
    public int getConnectionTimeoutMs() { return connectionTimeoutMs; }
    
    /** @return the socket timeout in milliseconds */
    public int getSocketTimeoutMs() { return socketTimeoutMs; }
}