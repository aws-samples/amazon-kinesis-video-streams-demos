package samples;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoPutMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoPutMediaClient;
import com.amazonaws.services.kinesisvideo.model.APIName;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointRequest;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointResult;
import com.amazonaws.services.kinesisvideo.model.FragmentTimecodeType;
import com.amazonaws.services.kinesisvideo.model.PutMediaRequest;
import kvs.model.AudioFrame;
import kvs.mediapublisher.StreamProducer;
import kvs.putmediaclient.callbacks.StreamingCallback;
import kvs.mkv.AudioMkvGenerator;
import kvs.putmediaclient.handlers.NetworkAwareAckHandler;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.Date;
import java.util.Random;

/**
 * Demo application for streaming synthetic audio data to Amazon Kinesis Video Streams.
 * 
 * <p>This application demonstrates real-time streaming of generated audio frames to KVS,
 * showing proper MKV cluster formation and timing. It creates a debug file to inspect
 * the generated MKV structure and provides detailed logging of the streaming process.
 * 
 * <p>Features:
 * <ul>
 *   <li>Synthetic audio generation (440Hz sine wave)</li>
 *   <li>MKV cluster formation and timing</li>
 *   <li>Debug file output for inspection</li>
 *   <li>Hex dump of MKV data for analysis</li>
 * </ul>
 * 
 * <p>Required environment variables:
 * <ul>
 *   <li>STREAM_NAME - The name of the KVS stream</li>
 *   <li>AWS_REGION - AWS region (optional, defaults to us-west-2)</li>
 * </ul>
 */
public class StreamingDemo {

    /**
     * Main entry point for the streaming demo.
     * 
     * @param args Command line arguments (not used)
     * @throws Exception If any error occurs during streaming
     */
    public static void main(String[] args) throws Exception {
        final String streamName = env("STREAM_NAME");
        final String regionStr = envOr("AWS_REGION", "us-west-2");
        final Regions region = Regions.fromName(regionStr);

        // Setup KVS clients
        AmazonKinesisVideo kv = AmazonKinesisVideoClientBuilder.standard()
                .withRegion(region)
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .withClientConfiguration(new ClientConfiguration()
                        .withConnectionTimeout(30_000)
                        .withSocketTimeout(120_000))
                .build();

        System.out.println("Discovering PUT_MEDIA endpoint for stream: " + streamName);
        GetDataEndpointResult gde = kv.getDataEndpoint(new GetDataEndpointRequest()
                .withAPIName(APIName.PUT_MEDIA)
                .withStreamName(streamName));
        String dataEndpoint = gde.getDataEndpoint();
        System.out.println("Data endpoint: " + dataEndpoint);

        AmazonKinesisVideoPutMedia putMedia = AmazonKinesisVideoPutMediaClient.builder()
                .withRegion(region.getName())
                .withEndpoint(dataEndpoint)
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .withConnectionTimeoutInMillis(30_000)
                .build();

        // Create audio track info and stream producer
        AudioMkvGenerator.AudioTrackInfo trackInfo = AudioMkvGenerator.AudioTrackInfo.createPCM(
                1, 8000.0, 1, 16);
        StreamProducer producer = new StreamProducer(trackInfo);
        
        // Create piped streams and debug file
        PipedOutputStream outputStream = new PipedOutputStream();
        PipedInputStream inputStream = new PipedInputStream(outputStream, 64 * 1024);
        FileOutputStream debugFile = new FileOutputStream("mkv_debug.mkv");
        System.out.println("Created debug file: mkv_debug.mkv");

        // Set up callback for network events
        producer.setCallback(new StreamingCallback() {
            @Override
            public void onConnectionLost(Exception cause) {
                System.err.println("Connection lost: " + cause.getMessage());
            }
            
            @Override
            public void onConnectionRestored() {
                System.out.println("Connection restored");
            }
            
            @Override
            public void onStreamingError(Exception error) {
                System.err.println("Streaming error: " + error.getMessage());
            }
            
            @Override
            public void onStreamingComplete() {
                System.out.println("Streaming completed successfully");
            }
            
            @Override
            public void onErrorAck(String errorCode, String fragmentNumber, Long timecode) {
                System.err.printf("Error ACK received: code=%s fragment=%s timecode=%s%n", 
                                errorCode, fragmentNumber, timecode);
            }
        });
        
        // Start PutMedia in separate thread
        Thread putMediaThread = new Thread(() -> {
            try {
                PutMediaRequest req = new PutMediaRequest()
                        .withStreamName(streamName)
                        .withPayload(inputStream)
                        .withFragmentTimecodeType(FragmentTimecodeType.RELATIVE)
                        .withProducerStartTimestamp(Date.from(Instant.now()));

                System.out.println("Starting PutMedia stream...");
                putMedia.putMedia(req, new NetworkAwareAckHandler(producer));
                producer.notifyStreamingComplete();
            } catch (Exception e) {
                System.err.println("PutMedia failed: " + e.getMessage());
                producer.notifyConnectionLost(e);
                e.printStackTrace();
            }
        });
        putMediaThread.start();

        // Generate frames following proper flow
        Random random = new Random();
        long timestamp = 0;
        
        System.out.println("Generating frames with proper MKV structure...");
        
        try {
            for (int i = 0; i < 30; i++) { // 30 frames = 3 clusters of 10 frames each
                // Generate 100ms audio frame (800 bytes at 8kHz, 16-bit)
                byte[] audioData = generateSyntheticAudio(800, random);
                AudioFrame frame = AudioFrame.create(audioData, timestamp);
                
                // Process frame through StreamProducer
                byte[] mkvData = producer.processFrame(frame);
                
                // Only send data when we have a complete cluster (or header + first cluster)
                if (mkvData.length > 0) {
                    outputStream.write(mkvData);
                    outputStream.flush();
                    
                    // Write to debug file
                    debugFile.write(mkvData);
                    debugFile.flush();
                    System.out.printf("Wrote %d bytes to debug file%n", mkvData.length);
                    
                    if (i == 9) { // First cluster complete (frames 0-9)
                        System.out.printf("Sent MKV header + cluster 1 (%d bytes) - frames 1-10%n", mkvData.length);
                        dumpHex(mkvData, "Header + Cluster 1");
                    } else if (i == 19) { // Second cluster complete (frames 10-19)
                        System.out.printf("Sent cluster 2 (%d bytes) - frames 11-20%n", mkvData.length);
                        dumpHex(mkvData, "Cluster 2");
                    } else if (i == 29) { // Third cluster complete (frames 20-29)
                        System.out.printf("Sent cluster 3 (%d bytes) - frames 21-30%n", mkvData.length);
                        dumpHex(mkvData, "Cluster 3");
                    }
                } else {
                    System.out.printf("Frame %d: No data to send (accumulating in cluster)%n", i + 1);
                }
                
                timestamp += 100; // 100ms per frame
                System.out.printf("Generated frame %d at timestamp %d ms%n", i + 1, timestamp);
                
                // Only sleep after sending a complete cluster to maintain timing
                if ((i + 1) % 10 == 0) {
                    Thread.sleep(1000); // 1 second per cluster
                } else {
                    Thread.sleep(100); // 100ms per frame
                }
            }
            
            // Send any remaining frames
            byte[] finalData = producer.flush();
            if (finalData.length > 0) {
                outputStream.write(finalData);
                outputStream.flush();
                System.out.printf("Sent final cluster (%d bytes)%n", finalData.length);
            }
            
        } finally {
            outputStream.close();
            debugFile.close();
            System.out.println("Closed debug file");
        }

        System.out.println("Streaming completed. Waiting for PutMedia to finish...");
        putMediaThread.join(10000);
        System.out.println("Demo finished. MKV data written to mkv_debug.mkv");
    }
    
    /**
     * Dumps hexadecimal representation of byte data for debugging.
     * 
     * @param data The byte array to dump
     * @param label A label to identify the data
     */
    private static void dumpHex(byte[] data, String label) {
        System.out.printf("=== %s (%d bytes) ===%n", label, data.length);
        for (int i = 0; i < Math.min(data.length, 64); i += 16) {
            System.out.printf("%04x: ", i);
            for (int j = 0; j < 16 && i + j < data.length && i + j < 64; j++) {
                System.out.printf("%02x ", data[i + j] & 0xFF);
            }
            System.out.println();
        }
        if (data.length > 64) {
            System.out.println("... (truncated)");
        }
        System.out.println();
    }

    /**
     * Generates synthetic audio data using a 440Hz sine wave.
     * 
     * @param size Number of bytes to generate
     * @param random Random number generator (not used in this implementation)
     * @return Byte array containing 16-bit PCM audio data
     */
    private static byte[] generateSyntheticAudio(int size, Random random) {
        byte[] audio = new byte[size];
        for (int i = 0; i < size; i += 2) {
            short sample = (short) (Math.sin(2 * Math.PI * 440 * (i/2) / 8000.0) * 1000);
            audio[i] = (byte) (sample & 0xFF);
            audio[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return audio;
    }

    /**
     * Gets a required environment variable.
     * 
     * @param key The environment variable name
     * @return The environment variable value
     * @throws IllegalArgumentException If the environment variable is missing or empty
     */
    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isEmpty())
            throw new IllegalArgumentException("Missing required environment variable: " + key);
        return v;
    }

    /**
     * Gets an environment variable with a default value.
     * 
     * @param key The environment variable name
     * @param def The default value to use if the variable is missing or empty
     * @return The environment variable value or the default value
     */
    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }
}