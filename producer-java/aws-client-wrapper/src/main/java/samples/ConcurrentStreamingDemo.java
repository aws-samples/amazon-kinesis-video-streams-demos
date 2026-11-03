package samples;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoPutMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoPutMediaClient;
import com.amazonaws.services.kinesisvideo.model.*;
import kvs.model.AudioFrame;
import kvs.putmediaclient.callbacks.StreamingCallback;
import kvs.mediapublisher.StreamProducer;
import kvs.mkv.AudioMkvGenerator;


import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Instant;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo application for concurrent streaming to multiple Amazon Kinesis Video Streams.
 * 
 * <p>This application demonstrates parallel streaming to multiple KVS streams simultaneously,
 * creating synthetic audio data and streaming it using multiple threads. It shows how to
 * handle concurrent stream creation, management, and data upload.
 * 
 * <p>Required environment variables:
 * <ul>
 *   <li>AWS_REGION - AWS region (optional, defaults to us-west-2)</li>
 * </ul>
 */
public class ConcurrentStreamingDemo {

    private static final int STREAM_COUNT = 10;
    private static final String STREAM_PREFIX = "demo-stream-";
    
    /**
     * Main entry point for the concurrent streaming demo.
     * 
     * @param args Command line arguments (not used)
     * @throws Exception If any error occurs during streaming
     */
    public static void main(String[] args) throws Exception {
        final String regionStr = envOr("AWS_REGION", "us-west-2");
        final Regions region = Regions.fromName(regionStr);

        // Setup KVS control plane client
        AmazonKinesisVideo kv = AmazonKinesisVideoClientBuilder.standard()
                .withRegion(region)
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .withClientConfiguration(new ClientConfiguration()
                        .withConnectionTimeout(30_000)
                        .withSocketTimeout(120_000))
                .build();

        // Create thread pool for parallel streaming
        ExecutorService executor = Executors.newFixedThreadPool(50);
        AtomicInteger completedStreams = new AtomicInteger(0);
        AtomicInteger failedStreams = new AtomicInteger(0);

        try {
            System.out.printf("Starting parallel streaming to %d streams...%n", STREAM_COUNT);

            // Launch streaming tasks for each stream
            for (int i = 0; i < STREAM_COUNT; i++) {
                final String streamName = STREAM_PREFIX + i;
                final int streamIndex = i;

                executor.submit(() -> {
                    try {
                        // Ensure stream exists
                        ensureStreamExists(kv, streamName);

                        // Start streaming
                        streamToKVS(kv, streamName, streamIndex, region);

                        int completed = completedStreams.incrementAndGet();
                        System.out.printf("Stream %s completed (%d/%d)%n", streamName, completed, STREAM_COUNT);

                    } catch (Exception e) {
                        int failed = failedStreams.incrementAndGet();
                        System.err.printf("Stream %s failed (%d failures): %s%n", streamName, failed, e.getMessage());
                    }
                });
            }

            // Shutdown executor and wait for completion
            executor.shutdown();
            
            // Wait until all tasks complete or timeout
            while (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                int completed = completedStreams.get();
                int failed = failedStreams.get();
                if (completed + failed >= STREAM_COUNT) {
                    break;
                }
                System.out.printf("Progress: %d/%d completed, %d failed%n", completed, STREAM_COUNT, failed);
            }
        } finally {
            executor.shutdownNow();
        }

        System.out.printf("Parallel streaming completed. Success: %d, Failed: %d%n", 
                         completedStreams.get(), failedStreams.get());
    }

    /**
     * Ensures that a KVS stream exists, creating it if necessary.
     * 
     * @param kv The KVS client
     * @param streamName The name of the stream to check/create
     */
    private static void ensureStreamExists(AmazonKinesisVideo kv, String streamName) {
        try {
            // Check if stream exists
            kv.describeStream(new DescribeStreamRequest().withStreamName(streamName));
            System.out.printf("Stream %s already exists%n", streamName);
        } catch (ResourceNotFoundException e) {
            // Stream doesn't exist, create it
            try {
                CreateStreamRequest createRequest = new CreateStreamRequest()
                        .withStreamName(streamName)
                        .withDataRetentionInHours(1)
                        .withMediaType("audio/x-mkv");
                
                kv.createStream(createRequest);
                System.out.printf("Created stream %s%n", streamName);
                
                // Wait for stream to become active
                waitForStreamActive(kv, streamName);
                
            } catch (Exception createEx) {
                throw new RuntimeException("Failed to create stream " + streamName, createEx);
            }
        }
    }

    /**
     * Waits for a KVS stream to become active.
     * 
     * @param kv The KVS client
     * @param streamName The name of the stream to wait for
     * @throws InterruptedException If the thread is interrupted while waiting
     */
    private static void waitForStreamActive(AmazonKinesisVideo kv, String streamName) throws InterruptedException {
        int maxRetries = 30; // 5 minutes max
        for (int i = 0; i < maxRetries; i++) {
            try {
                DescribeStreamResult result = kv.describeStream(new DescribeStreamRequest().withStreamName(streamName));
                if ("ACTIVE".equals(result.getStreamInfo().getStatus())) {
                    return;
                }
                Thread.sleep(10000); // Wait 10 seconds
            } catch (Exception e) {
                Thread.sleep(10000);
            }
        }
        throw new RuntimeException("Stream " + streamName + " did not become active within timeout");
    }

    /**
     * Streams synthetic audio data to a specific KVS stream.
     * 
     * @param kv The KVS client
     * @param streamName The name of the target stream
     * @param streamIndex The index of this stream (for unique data generation)
     * @param region The AWS region
     * @throws Exception If any error occurs during streaming
     */
    private static void streamToKVS(AmazonKinesisVideo kv, String streamName, int streamIndex, Regions region) throws Exception {
        // Get data endpoint
        GetDataEndpointResult gde = kv.getDataEndpoint(new GetDataEndpointRequest()
                .withAPIName(APIName.PUT_MEDIA)
                .withStreamName(streamName));
        String dataEndpoint = gde.getDataEndpoint();

        // Create PutMedia client
        AmazonKinesisVideoPutMedia putMedia = AmazonKinesisVideoPutMediaClient.builder()
                .withRegion(region.getName())
                .withEndpoint(dataEndpoint)
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .withConnectionTimeoutInMillis(30_000)
                .build();

        // Create stream producer
        AudioMkvGenerator.AudioTrackInfo trackInfo = AudioMkvGenerator.AudioTrackInfo.createPCM(
                streamIndex + 1, 8000.0, 1, 16);
        StreamProducer producer = new StreamProducer(trackInfo);

        // Set up callback
        producer.setCallback(new StreamingCallback() {
            @Override
            public void onConnectionLost(Exception cause) {
                System.err.printf("[%s] Connection lost: %s%n", streamName, cause.getMessage());
            }
            
            @Override
            public void onConnectionRestored() {
                System.out.printf("[%s] Connection restored%n", streamName);
            }
            
            @Override
            public void onStreamingError(Exception error) {
                System.err.printf("[%s] Streaming error: %s%n", streamName, error.getMessage());
            }
            
            @Override
            public void onStreamingComplete() {
                System.out.printf("[%s] Streaming completed%n", streamName);
            }
            
            @Override
            public void onErrorAck(String errorCode, String fragmentNumber, Long timecode) {
                System.err.printf("[%s] Error ACK: %s%n", streamName, errorCode);
            }
        });

        // Create piped streams
        PipedOutputStream outputStream = new PipedOutputStream();
        PipedInputStream inputStream = new PipedInputStream(outputStream, 64 * 1024);

        // Start PutMedia in separate thread
        Thread putMediaThread = new Thread(() -> {
            try {
                PutMediaRequest req = new PutMediaRequest()
                        .withStreamName(streamName)
                        .withPayload(inputStream)
                        .withFragmentTimecodeType(FragmentTimecodeType.RELATIVE)
                        .withProducerStartTimestamp(Date.from(Instant.now()));

                putMedia.putMedia(req, new kvs.putmediaclient.handlers.NetworkAwareAckHandler(producer));
                producer.notifyStreamingComplete();
            } catch (Exception e) {
                producer.notifyConnectionLost(e);
            }
        });
        putMediaThread.start();

        // Generate and send frames
        Random random = new Random(streamIndex); // Seed with stream index for reproducibility
        long timestamp = 0;
        
        try {
            for (int i = 0; i < 30; i++) { // 30 frames = 3 seconds
                byte[] audioData = generateSyntheticAudio(800, random, streamIndex);
                AudioFrame frame = AudioFrame.create(audioData, timestamp);
                
                byte[] mkvData = producer.processFrame(frame);
                
                if (mkvData.length > 0) {
                    outputStream.write(mkvData);
                    outputStream.flush();
                }
                
                timestamp += 100;
                Thread.sleep(100); // Real-time simulation
            }
            
            // Send final data
            byte[] finalData = producer.flush();
            if (finalData.length > 0) {
                outputStream.write(finalData);
                outputStream.flush();
            }
            
        } finally {
            outputStream.close();
        }

        putMediaThread.join(30000); // Wait up to 30 seconds
    }

    private static byte[] generateSyntheticAudio(int size, Random random, int streamIndex) {
        byte[] audio = new byte[size];
        // Generate unique tone per stream (440Hz + streamIndex * 10Hz)
        double frequency = 440.0 + (streamIndex * 10.0);
        for (int i = 0; i < size; i += 2) {
            short sample = (short) (Math.sin(2 * Math.PI * frequency * (i/2) / 8000.0) * 1000);
            audio[i] = (byte) (sample & 0xFF);
            audio[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return audio;
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