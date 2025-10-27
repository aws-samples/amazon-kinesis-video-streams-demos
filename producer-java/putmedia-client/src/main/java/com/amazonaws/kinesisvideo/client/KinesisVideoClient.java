package com.amazonaws.kinesisvideo.client;

import com.amazonaws.kinesisvideo.config.ClientConfiguration;
import com.amazonaws.kinesisvideo.java.service.JavaKinesisVideoServiceClient;
import com.amazonaws.kinesisvideo.java.client.mediasource.ParallelHttpClient;

import java.io.Closeable;
import java.net.URI;

/**
 * Main client for Kinesis Video Streams operations
 */
public class KinesisVideoClient implements Closeable {
    private final ClientConfiguration config;
    private final JavaKinesisVideoServiceClient serviceClient;
    private final ParallelHttpClient httpClient;
    private final String PUT_MEDIA= "/putMedia";
    
    /**
     * Creates a new KinesisVideoClient with the specified configuration.
     * 
     * @param config the client configuration containing region, credentials, and timeouts
     */
    public KinesisVideoClient(ClientConfiguration config) {
        this.config = config;
        this.serviceClient = new JavaKinesisVideoServiceClient(
            config.getRegion(), 
            config.getAccessKey(), 
            config.getSecretKey(), 
            config.getSessionToken()
        );
        this.httpClient = new ParallelHttpClient(
            config.getAccessKey(), 
            config.getSecretKey(), 
            config.getSessionToken(), 
            config.getRegion()
        );
    }
    
    /**
     * Gets the data endpoint URL for the specified stream.
     * 
     * @param streamName the name of the Kinesis Video stream
     * @return the data endpoint URL for PutMedia operations
     */
    public String getDataEndpoint(String streamName) {
        return serviceClient.getDataEndpoint(streamName, "PUT_MEDIA");
    }
    
    /**
     * Gets the complete PutMedia endpoint URI for the specified stream.
     * 
     * @param streamName the name of the Kinesis Video stream
     * @return the complete URI for PutMedia API calls
     */
    public URI getPutMediaEndpoint(String streamName) {
        String dataEndpoint = getDataEndpoint(streamName);
        return URI.create(dataEndpoint + PUT_MEDIA);
    }
    
    /**
     * Creates a new PutMediaClient for streaming operations.
     * 
     * @return a configured PutMediaClient instance
     */
    public PutMediaClient createPutMediaClient() {
        return new PutMediaClient(httpClient);
    }
    
    /**
     * Gets the client configuration.
     * 
     * @return the current client configuration
     */
    public ClientConfiguration getConfiguration() {
        return config;
    }
    
    /**
     * Closes the client and releases all resources.
     */
    @Override
    public void close() {
        if (serviceClient != null) {
            serviceClient.close();
        }
        if (httpClient != null) {
            httpClient.close();
        }
    }
}