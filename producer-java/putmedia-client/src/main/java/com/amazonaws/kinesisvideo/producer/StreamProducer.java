package com.amazonaws.kinesisvideo.producer;

import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.client.PutMediaClient;
import com.amazonaws.kinesisvideo.producer.audio.AudioFrame;
import com.amazonaws.kinesisvideo.producer.audio.AudioStreamConfiguration;
import com.amazonaws.kinesisvideo.producer.audio.mkv.AudioMkvGenerator;
import com.amazonaws.kinesisvideo.producer.audio.mkv.ClusterCallback;

import java.io.Closeable;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * High-level stream producer for media bytes
 */
public class StreamProducer implements Closeable {
    private final KinesisVideoClient client;
    private final String streamName;
    private final BlockingQueue<AudioFrame> audioQueue;
    private PutMediaClient putMediaClient;
    private AudioMkvGenerator mkvGenerator;
    private volatile boolean streaming = false;
    private ClusterCallback clusterCallback;
    
    /**
     * Creates a new StreamProducer.
     * 
     * @param client the KinesisVideoClient to use
     * @param streamName the name of the stream to produce to
     */
    public StreamProducer(KinesisVideoClient client, String streamName) {
        this.client = client;
        this.streamName = streamName;
        this.audioQueue = new LinkedBlockingQueue<>(1000);
    }
    
    /**
     * Sets a callback for cluster creation events.
     * 
     * @param callback the cluster callback
     */
    public void setClusterCallback(ClusterCallback callback) {
        this.clusterCallback = callback;
    }
    
    /**
     * Starts audio streaming with the specified configuration.
     * 
     * @param config the audio stream configuration
     * @throws IllegalStateException if stream is already started
     */
    public void startAudioStream(AudioStreamConfiguration config) {
        if (streaming) {
            throw new IllegalStateException("Stream already started");
        }
        
        AudioMkvGenerator.AudioTrackInfo trackInfo = createTrackInfo(config);
        this.mkvGenerator = clusterCallback != null ? 
            new AudioMkvGenerator(trackInfo, clusterCallback) : 
            new AudioMkvGenerator(trackInfo);
        this.putMediaClient = client.createPutMediaClient();
        
        URI putMediaUri = client.getPutMediaEndpoint(streamName);
        Map<String, String> headers = createHeaders();
        
        PutMediaClient.Sender sender = (out) -> {
            try {
                byte[] buffer = new byte[1024];
                AudioFrameReader reader = new AudioFrameReader(audioQueue, mkvGenerator);
                int bytesRead;
                
                while ((bytesRead = reader.read(buffer, 0, buffer.length)) > 0) {
                    String chunkHeader = Integer.toHexString(bytesRead) + "\r\n";
                    out.write(chunkHeader.getBytes());
                    out.write(buffer, 0, bytesRead);
                    out.write("\r\n".getBytes());
                    out.flush();
                    Thread.sleep(50);
                }
                
                out.write("0\r\n\r\n".getBytes());
                out.flush();
            } catch (Exception e) {
                throw new RuntimeException("Sender error", e);
            }
        };
        
        PutMediaClient.Receiver receiver = (in) -> {
            try {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) > 0) {
                    String response = new String(buffer, 0, bytesRead).trim();
                    if (!response.isEmpty()) {
                        System.out.println("[ACK][" + streamName + "] " + response);
                    }
                }
            } catch (Exception e) {
                System.err.println("[RECEIVER][" + streamName + "] Error: " + e.getMessage());
            }
        };
        
        putMediaClient.connectAndProcessInBackground(putMediaUri, headers, sender, receiver);
        streaming = true;
    }
    
    /**
     * Sends an audio frame to the stream.
     * 
     * @param frame the audio frame to send
     * @throws IllegalStateException if stream is not started
     * @throws RuntimeException if frame cannot be queued
     */
    public void putAudioFrame(AudioFrame frame) {
        if (!streaming) {
            throw new IllegalStateException("Stream not started");
        }
        
        try {
            audioQueue.offer(frame);
        } catch (Exception e) {
            throw new RuntimeException("Failed to queue audio frame", e);
        }
    }
    
    private AudioMkvGenerator.AudioTrackInfo createTrackInfo(AudioStreamConfiguration config) {
        switch (config.getCodec()) {
            case PCM:
                return AudioMkvGenerator.AudioTrackInfo.createPCM(
                    1, config.getSampleRate(), config.getChannels(), config.getBitDepth());
            case ALAW:
                return AudioMkvGenerator.AudioTrackInfo.createALaw(
                    1, config.getSampleRate(), config.getChannels());
            case MULAW:
                return AudioMkvGenerator.AudioTrackInfo.createMuLaw(
                    1, config.getSampleRate(), config.getChannels());
            default:
                throw new IllegalArgumentException("Unsupported codec: " + config.getCodec());
        }
    }
    
    private Map<String, String> createHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-amzn-stream-name", streamName);
        headers.put("x-amzn-fragment-timecode-type", "RELATIVE");
        long currentMillis = System.currentTimeMillis();
        double currentSeconds = currentMillis / 1000.0;
        String timestamp = String.format("%.12E", currentSeconds);
        System.out.println("[SDK] Current time millis: " + currentMillis);
        System.out.println("[SDK] Current time seconds: " + currentSeconds);
        System.out.println("[SDK] Timestamp string: " + timestamp);
        System.out.println("[SDK] Expected range: " + (currentMillis - 1209600000) + " to " + (currentMillis + 1209600000));
        headers.put("x-amzn-producer-start-timestamp", timestamp);
        return headers;
    }
    
    /**
     * Stops streaming and releases all resources.
     */
    @Override
    public void close() {
        streaming = false;
        if (putMediaClient != null) {
            putMediaClient.close();
        }
    }
    
    private static class AudioFrameReader {
        private final BlockingQueue<AudioFrame> queue;
        private final AudioMkvGenerator generator;
        private java.io.ByteArrayInputStream currentBuffer = new java.io.ByteArrayInputStream(new byte[0]);
        private final long startTime = System.currentTimeMillis();
        
        AudioFrameReader(BlockingQueue<AudioFrame> queue, AudioMkvGenerator generator) {
            this.queue = queue;
            this.generator = generator;
        }
        
        public int read(byte[] buffer, int offset, int length) throws Exception {
            int bytesRead = currentBuffer.read(buffer, offset, length);
            if (bytesRead > 0) return bytesRead;
            
            AudioFrame frame = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (frame == null) return 0;
            
            // Use relative timestamp from start of stream
            long relativeTimestamp = frame.getTimestampMs() - startTime;
            byte[] mkvData = generator.packageAudioFrame(frame.getDataBytes(), relativeTimestamp);
            currentBuffer = new java.io.ByteArrayInputStream(mkvData);
            
            return currentBuffer.read(buffer, offset, length);
        }
    }
}