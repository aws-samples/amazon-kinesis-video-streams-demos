package com.amazonaws.kinesisvideo;

import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.config.ClientConfiguration;
import com.amazonaws.kinesisvideo.producer.StreamProducer;

/**
 * Main SDK entry point for Amazon Kinesis Video Streams
 */
public class KinesisVideoStreamsSDK {
    
    /**
     * Creates a new KinesisVideoClient with the specified configuration.
     * 
     * @param config the client configuration
     * @return a configured KinesisVideoClient instance
     */
    public static KinesisVideoClient createClient(ClientConfiguration config) {
        return new KinesisVideoClient(config);
    }
    
    /**
     * Creates a new StreamProducer for the specified stream.
     * 
     * @param client the KinesisVideoClient to use
     * @param streamName the name of the stream to produce to
     * @return a configured StreamProducer instance
     */
    public static StreamProducer createProducer(KinesisVideoClient client, String streamName) {
        return new StreamProducer(client, streamName);
    }
}