package com.amazonaws.kinesisvideo.java.service;

import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesisvideo.KinesisVideoClient;
import software.amazon.awssdk.services.kinesisvideo.model.*;

public class JavaKinesisVideoServiceClient {
    private final KinesisVideoClient kvsClient;
    
    public JavaKinesisVideoServiceClient(String region) {
        AwsCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
        this.kvsClient = KinesisVideoClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                .build();
    }
    
    public JavaKinesisVideoServiceClient(String region, String accessKey, String secretKey, String sessionToken) {
        AwsCredentials credentials = sessionToken != null ? 
            AwsSessionCredentials.create(accessKey, secretKey, sessionToken) :
            AwsBasicCredentials.create(accessKey, secretKey);
        
        this.kvsClient = KinesisVideoClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }

    public String getDataEndpoint(String streamName, String apiName) {
        try {
            GetDataEndpointRequest request = GetDataEndpointRequest.builder()
                    .streamName(streamName)
                    .apiName(APIName.fromValue(apiName))
                    .build();
            
            GetDataEndpointResponse response = kvsClient.getDataEndpoint(request);
            return response.dataEndpoint();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get data endpoint for stream: " + streamName, e);
        }
    }
    
    public void close() {
        if (kvsClient != null) {
            kvsClient.close();
        }
    }
}
