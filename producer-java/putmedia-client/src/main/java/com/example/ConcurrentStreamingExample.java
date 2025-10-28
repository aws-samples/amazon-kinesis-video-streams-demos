package com.example;

import com.amazonaws.kinesisvideo.KinesisVideoStreamsSDK;
import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.config.ClientConfiguration;
import com.amazonaws.kinesisvideo.producer.StreamProducer;
import com.amazonaws.kinesisvideo.producer.audio.AudioFrame;
import com.amazonaws.kinesisvideo.producer.audio.AudioStreamConfiguration;
import com.amazonaws.kinesisvideo.producer.audio.mkv.ClusterCallback;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Example showing concurrent streaming to multiple streams
 */
public class ConcurrentStreamingExample {
    
    public static void main(String[] args) throws Exception {
        final String region = (args.length > 0) ? args[0] : "us-west-2";
        
        // Executor sized for concurrent operations:
        // - 2 streams × 3 threads each (sender, receiver, scheduler) = 6
        // - Plus 2 for frame generation tasks = 8 total
        ScheduledExecutorService sharedExecutor = Executors.newScheduledThreadPool(8);
        
        ClientConfiguration config = new ClientConfiguration(region)
            .withCredentials(
                System.getenv("AWS_ACCESS_KEY_ID"),
                System.getenv("AWS_SECRET_ACCESS_KEY")
            )
            .withSessionToken(System.getenv("AWS_SESSION_TOKEN"))
            .withExecutorService(sharedExecutor);
        
        KinesisVideoClient client = KinesisVideoStreamsSDK.createClient(config);
        
        // Create two concurrent streams
        StreamProducer producer1 = KinesisVideoStreamsSDK.createProducer(client, "stream-1");
        StreamProducer producer2 = KinesisVideoStreamsSDK.createProducer(client, "stream-2");
        
        // Set cluster callbacks for both streams
        producer1.setClusterCallback(new ClusterCallback() {
            @Override
            public void onClusterCreated(long timestamp, long creationTime) {
                System.out.println("[CLUSTER][stream-1] Created at timestamp: " + timestamp + 
                                 "ms, system time: " + creationTime + "ms");
            }
        });
        
        producer2.setClusterCallback(new ClusterCallback() {
            @Override
            public void onClusterCreated(long timestamp, long creationTime) {
                System.out.println("[CLUSTER][stream-2] Created at timestamp: " + timestamp + 
                                 "ms, system time: " + creationTime + "ms");
            }
        });
        
        AudioStreamConfiguration audioConfig = AudioStreamConfiguration.alaw(8000, 1);
        producer1.startAudioStream(audioConfig);
        producer2.startAudioStream(audioConfig);
        
        // Schedule frame generation for both streams
        sharedExecutor.scheduleAtFixedRate(() -> {
            AudioFrame frame = AudioFrame.create(generateAlawFrame(), System.currentTimeMillis());
            producer1.putAudioFrame(frame);
        }, 0, 20, TimeUnit.MILLISECONDS);
        
        sharedExecutor.scheduleAtFixedRate(() -> {
            AudioFrame frame = AudioFrame.create(generateAlawFrame(), System.currentTimeMillis());
            producer2.putAudioFrame(frame);
        }, 0, 20, TimeUnit.MILLISECONDS);
        
        System.out.println("Streaming to both streams concurrently");
        
        Thread.sleep(30000);
        
        producer1.close();
        producer2.close();
        client.close();
        sharedExecutor.shutdown();
        
        System.out.println("Concurrent streaming completed");
    }
    
    private static byte[] generateAlawFrame() {
        byte[] frame = new byte[160];
        for (int i = 0; i < frame.length; i++) {
            double sample = Math.sin(2.0 * Math.PI * 440.0 * i / 8000.0);
            short pcmSample = (short) (sample * 32767);
            frame[i] = linearToAlaw(pcmSample);
        }
        return frame;
    }
    
    private static byte linearToAlaw(short pcm) {
        int mask = 0x55;
        if (pcm >= 0) {
            if (pcm >= 256) {
                int val = pcm >> 4;
                int seg = 0;
                for (; seg < 7; seg++) {
                    if (val <= (0x1F << seg)) break;
                }
                return (byte) ((seg << 4) | ((val >> seg) & 0x0F) | mask);
            } else {
                return (byte) ((pcm >> 4) | mask);
            }
        } else {
            pcm = (short) -pcm;
            if (pcm >= 256) {
                int val = pcm >> 4;
                int seg = 0;
                for (; seg < 7; seg++) {
                    if (val <= (0x1F << seg)) break;
                }
                return (byte) ((seg << 4) | ((val >> seg) & 0x0F));
            } else {
                return (byte) (pcm >> 4);
            }
        }
    }
}