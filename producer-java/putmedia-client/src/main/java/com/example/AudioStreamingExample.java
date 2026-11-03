 package com.example;

import com.amazonaws.kinesisvideo.KinesisVideoStreamsSDK;
import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.config.ClientConfiguration;
import com.amazonaws.kinesisvideo.producer.StreamProducer;
import com.amazonaws.kinesisvideo.producer.audio.AudioFrame;
import com.amazonaws.kinesisvideo.producer.audio.AudioStreamConfiguration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simple example using the Kinesis Video Streams SDK simple putmedia client
 */
public class AudioStreamingExample {
    
    public static void main(String[] args) throws Exception {
        final String streamName = (args.length > 0) ? args[0] : "demo-stream";
        final String region = (args.length > 1) ? args[1] : "us-west-2";
        
        // Create SDK client
        ClientConfiguration config = new ClientConfiguration(region)
            .withCredentials(
                System.getenv("AWS_ACCESS_KEY_ID"),
                System.getenv("AWS_SECRET_ACCESS_KEY")
            )
            .withSessionToken(System.getenv("AWS_SESSION_TOKEN"));
        
        KinesisVideoClient client = KinesisVideoStreamsSDK.createClient(config);
        StreamProducer producer = KinesisVideoStreamsSDK.createProducer(client, streamName);
        
        // Configure audio stream
        AudioStreamConfiguration audioConfig = AudioStreamConfiguration.alaw(8000, 1);
        producer.startAudioStream(audioConfig);
        
        // Generate and send audio frames
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            byte[] audioData = generateAlawFrame();
            AudioFrame frame = AudioFrame.create(audioData, System.currentTimeMillis());
            producer.putAudioFrame(frame);
        }, 0, 20, TimeUnit.MILLISECONDS);
        
        System.out.println("Streaming audio to: " + streamName);
        
        // Run for 30 seconds
        Thread.sleep(30000);
        
        // Cleanup
        scheduler.shutdown();
        producer.close();
        client.close();
        
        System.out.println("Streaming completed");
    }
    
    private static byte[] generateAlawFrame() {
        // Generate 160 bytes of A-law audio (20ms at 8kHz)
        byte[] frame = new byte[160];
        for (int i = 0; i < frame.length; i++) {
            // Generate sine wave and convert to A-law
            double sample = Math.sin(2.0 * Math.PI * 440.0 * i / 8000.0);
            short pcmSample = (short) (sample * 32767);
            frame[i] = linearToAlaw(pcmSample);
        }
        return frame;
    }
    
    private static byte linearToAlaw(short pcm) {
        int mask = 0x55;
        int seg = 7;
        if (pcm >= 0) {
            if (pcm >= 256) {
                int val = pcm >> 4;
                for (seg = 0; seg < 7; seg++) {
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
                for (seg = 0; seg < 7; seg++) {
                    if (val <= (0x1F << seg)) break;
                }
                return (byte) ((seg << 4) | ((val >> seg) & 0x0F));
            } else {
                return (byte) (pcm >> 4);
            }
        }
    }
    

}