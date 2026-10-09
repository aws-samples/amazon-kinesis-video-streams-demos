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
import com.amazonaws.services.kinesisvideo.model.AckEvent;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Instant;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo application for queue-based streaming to Amazon Kinesis Video Streams.
 * 
 * <p>This application demonstrates producer-consumer pattern streaming using a LinkedBlockingQueue
 * to decouple frame generation from network transmission. It includes rate control, retry logic,
 * and backpressure handling for robust streaming.
 * 
 * <p>Features:
 * <ul>
 *   <li>Queue-based frame buffering with overflow handling</li>
 *   <li>Rate-controlled consumption (200ms intervals)</li>
 *   <li>Network retry with exponential backoff</li>
 *   <li>Backpressure handling when queue is full</li>
 * </ul>
 * 
 * <p>Required environment variables:
 * <ul>
 *   <li>STREAM_NAME - The name of the KVS stream</li>
 *   <li>AWS_REGION - AWS region (optional, defaults to us-west-2)</li>
 * </ul>
 */
public class QueuedStreamingDemo {

    private static final LinkedBlockingQueue<AudioFrame> frameQueue = new LinkedBlockingQueue<>(1000);
    private static final AtomicBoolean streaming = new AtomicBoolean(true);
    
    // Metrics
    private static final AtomicInteger framesPublished = new AtomicInteger(0);
    private static final AtomicInteger fragmentsSent = new AtomicInteger(0);
    private static final AtomicInteger fragmentsAcknowledged = new AtomicInteger(0);

    /**
     * Main entry point for the queued streaming demo.
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

        GetDataEndpointResult gde = kv.getDataEndpoint(new GetDataEndpointRequest()
                .withAPIName(APIName.PUT_MEDIA)
                .withStreamName(streamName));

        AmazonKinesisVideoPutMedia putMedia = AmazonKinesisVideoPutMediaClient.builder()
                .withRegion(region.getName())
                .withEndpoint(gde.getDataEndpoint())
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .withConnectionTimeoutInMillis(30_000)
                .build();

        // Create stream producer
        AudioMkvGenerator.AudioTrackInfo trackInfo = AudioMkvGenerator.AudioTrackInfo.createPCM(
                1, 8000.0, 1, 16);
        StreamProducer producer = new StreamProducer(trackInfo);

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
                System.out.println("Streaming completed");
            }
            
            @Override
            public void onErrorAck(String errorCode, String fragmentNumber, Long timecode) {
                System.err.printf("Error ACK: %s%n", errorCode);
            }
        });

        // Create piped streams
        PipedOutputStream outputStream = new PipedOutputStream();
        PipedInputStream inputStream = new PipedInputStream(outputStream, 64 * 1024);

        // Start PutMedia thread
        Thread putMediaThread = new Thread(() -> {
            try {
                PutMediaRequest req = new PutMediaRequest()
                        .withStreamName(streamName)
                        .withPayload(inputStream)
                        .withFragmentTimecodeType(FragmentTimecodeType.RELATIVE)
                        .withProducerStartTimestamp(Date.from(Instant.now()));

                putMedia.putMedia(req, new NetworkAwareAckHandler(producer) {
                    @Override
                    public void onAckEvent(AckEvent ackEvent) {
                        super.onAckEvent(ackEvent);
                        if ("PERSISTED".equals(ackEvent.getAckEventType().toString())) {
                            fragmentsAcknowledged.incrementAndGet();
                            System.out.printf("[%d] Fragment PERSISTED ACK (Total ACKs: %d)%n", 
                                            System.currentTimeMillis(), fragmentsAcknowledged.get());
                        }
                    }
                });
                producer.notifyStreamingComplete();
            } catch (Exception e) {
                producer.notifyConnectionLost(e);
            }
        });
        putMediaThread.start();

        // Start frame sender thread with rate control
        Thread senderThread = new Thread(() -> {
            try {
                long lastSendTime = System.nanoTime();
                final long frameIntervalNs = TimeUnit.MILLISECONDS.toNanos(100); // 100ms = 10fps

                while (streaming.get()) {
                    AudioFrame frame = frameQueue.poll(5, TimeUnit.SECONDS); // Longer timeout for slow generation
                    if (frame != null) {
                        // Rate control - ensure 200ms between frames
                        long currentTime = System.nanoTime();
                        long elapsed = currentTime - lastSendTime;
                        if (elapsed < frameIntervalNs) {
                            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(frameIntervalNs - elapsed));
                        }

                        // Retry logic for network issues
                        int retries = 3;
                        boolean sent = false;
                        while (retries > 0 && !sent) {
                            try {
                                byte[] mkvData = producer.processFrame(frame);
                                if (mkvData.length > 0) {
                                    outputStream.write(mkvData);
                                    outputStream.flush();
                                    fragmentsSent.incrementAndGet();
                                    System.out.printf("[%d] Sent frame at timestamp %d ms (%d bytes) (Total sent: %d)%n", 
                                                    System.currentTimeMillis(), frame.getTimestampMs(), mkvData.length, fragmentsSent.get());
                                }
                                sent = true;
                            } catch (Exception e) {
                                retries--;
                                if (retries > 0) {
                                    System.err.printf("Send failed, retrying... (%d attempts left)%n", retries);
                                    Thread.sleep((long) Math.pow(2, 4 - retries) * 100); // Exponential backoff: 200ms, 400ms, 800ms
                                } else {
                                    System.err.println("Failed to send frame after retries: " + e.getMessage());
                                }
                            }
                        }
                        lastSendTime = System.nanoTime();
                    }
                }
                outputStream.close();
            } catch (Exception e) {
                System.err.println("Sender thread error: " + e.getMessage());
            }
        });
        senderThread.start();

        // Producer thread - generates frames and adds to queue
        Thread producerThread = new Thread(() -> {
            Random random = new Random();
            long timestamp = 0;
            
            try {
                for (int i = 0; i < 30; i++) {
                    byte[] audioData = generateSyntheticAudio(800, random);
                    AudioFrame frame = AudioFrame.create(audioData, timestamp);
                    
                    // Backpressure handling - drop oldest if queue full
                    if (!frameQueue.offer(frame, 1, TimeUnit.SECONDS)) {
                        frameQueue.poll(); // Drop oldest
                        frameQueue.offer(frame); // Add new
                        System.out.printf("[%d] Queue full - dropped oldest frame%n", System.currentTimeMillis());
                    }
                    framesPublished.incrementAndGet();
                    System.out.printf("[%d] Queued frame %d (queue size: %d) (Total frames: %d)%n", 
                                    System.currentTimeMillis(), i + 1, frameQueue.size(), framesPublished.get());
                    
                    timestamp += 100;
                    Thread.sleep(50); // Produce faster than consume to test queue
                }
            } catch (Exception e) {
                System.err.println("Producer thread error: " + e.getMessage());
            }
        });
        producerThread.start();

        // Wait for producer to finish
        producerThread.join();
        
        // Wait for queue to drain
        while (!frameQueue.isEmpty()) {
            Thread.sleep(100);
        }
        
        streaming.set(false);
        senderThread.join();
        
        // Wait for ACKs to arrive
        System.out.println("Waiting for ACKs...");
        Thread.sleep(3000);
        
        putMediaThread.join(5000);
        
        kv.shutdown();
        
        // Print final metrics
        System.out.println("\n=== Final Metrics ===");
        System.out.printf("Frames Published: %d%n", framesPublished.get());
        System.out.printf("Fragments Sent: %d%n", fragmentsSent.get());
        System.out.printf("Fragments Acknowledged: %d%n", fragmentsAcknowledged.get());
        System.out.println("Demo completed");
    }

    /**
     * Generates synthetic audio data for testing.
     * 
     * @param samples Number of audio samples to generate
     * @param random Random number generator for data
     * @return Byte array containing synthetic audio data
     */
    private static byte[] generateSyntheticAudio(int samples, Random random) {
        byte[] data = new byte[samples];
        random.nextBytes(data);
        return data;
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