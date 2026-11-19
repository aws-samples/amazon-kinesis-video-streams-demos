package kvs.mediapublisher;

import kvs.model.AudioFrame;
import kvs.putmediaclient.callbacks.StreamingCallback;
import kvs.mkv.AudioMkvGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces Matroska (MKV) formatted media streams for Amazon Kinesis Video Streams.
 * 
 * <p>This class handles the packaging of audio frames into MKV format using the Matroska
 * container specification. It works in conjunction with {@link AudioMkvGenerator} to create
 * properly formatted MKV streams suitable for KVS ingestion.
 * 
 * <h2>MKV Structure</h2>
 * The producer generates MKV data in the following structure:
 * <ol>
 *   <li><b>EBML Header</b> - Written once at the start, identifies the file as Matroska</li>
 *   <li><b>Segment</b> - Container for all stream data</li>
 *   <li><b>Segment Info</b> - Metadata about the stream (timecode scale, duration)</li>
 *   <li><b>Tracks</b> - Audio track configuration (codec, sample rate, channels)</li>
 *   <li><b>Clusters</b> - Groups of frames with timestamps (generated periodically)</li>
 * </ol>
 * 
 * <h2>Clustering Strategy</h2>
 * Frames are batched into clusters for efficient streaming:
 * <ul>
 *   <li>Each cluster contains {@value #framesPerCluster} frames (typically 1 second of audio)</li>
 *   <li>Clusters are generated when full or on explicit flush</li>
 *   <li>Each cluster has a timecode relative to segment start</li>
 *   <li>Frames within clusters have timecodes relative to cluster start</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create track info for 8kHz, 16-bit mono PCM audio
 * AudioTrackInfo trackInfo = AudioTrackInfo.createPCM(1, 8000.0, 1, 16);
 * StreamProducer producer = new StreamProducer(trackInfo);
 * 
 * // Process frames
 * for (AudioFrame frame : frames) {
 *     byte[] mkvData = producer.processFrame(frame);
 *     if (mkvData.length > 0) {
 *         // Send to KVS when cluster is complete
 *         outputStream.write(mkvData);
 *     }
 * }
 * 
 * // Flush remaining frames
 * byte[] remaining = producer.flush();
 * if (remaining.length > 0) {
 *     outputStream.write(remaining);
 * }
 * }</pre>
 * 
 * @see AudioMkvGenerator
 * @see AudioFrame
 */
public class StreamProducer {
    private final AudioMkvGenerator.AudioTrackInfo trackInfo;
    private final AudioMkvGenerator generator;
    private boolean headerWritten = false;
    private final List<AudioFrame> currentCluster = new ArrayList<>();
    private long clusterStartTime = 0;
    private final int framesPerCluster = 10; // 1 second = 10 frames of 100ms each
    private StreamingCallback callback;
    
    /**
     * Creates a new StreamProducer with the specified audio track configuration.
     * 
     * @param trackInfo Audio track configuration including codec, sample rate, and channels
     */
    public StreamProducer(AudioMkvGenerator.AudioTrackInfo trackInfo) {
        this.trackInfo = trackInfo;
        this.generator = new AudioMkvGenerator(trackInfo);
    }
    
    /**
     * Sets the callback for streaming events.
     * 
     * @param callback Callback to receive connection, error, and completion events
     */
    public void setCallback(StreamingCallback callback) {
        this.callback = callback;
    }
    
    public void notifyConnectionLost(Exception cause) {
        if (callback != null) {
            callback.onConnectionLost(cause);
        }
    }
    
    public void notifyConnectionRestored() {
        if (callback != null) {
            callback.onConnectionRestored();
        }
    }
    
    public void notifyStreamingError(Exception error) {
        if (callback != null) {
            callback.onStreamingError(error);
        }
    }
    
    public void notifyStreamingComplete() {
        if (callback != null) {
            callback.onStreamingComplete();
        }
    }
    
    public void notifyErrorAck(String errorCode, String fragmentNumber, Long timecode) {
        if (callback != null) {
            callback.onErrorAck(errorCode, fragmentNumber, timecode);
        }
    }
    
    /**
     * Processes an audio frame and returns MKV-formatted data when a cluster is complete.
     * 
     * <p>The first call writes the MKV header. Subsequent calls accumulate frames into
     * clusters. When a cluster reaches {@value #framesPerCluster} frames, it is generated
     * and returned along with any header data.
     * 
     * @param frame The audio frame to process
     * @return MKV-formatted byte array (header + cluster when complete, or empty array)
     * @throws IOException If an error occurs during MKV generation
     */
    public byte[] processFrame(AudioFrame frame) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        
        // Write header only once
        if (!headerWritten) {
            byte[] header = generator.generateHeader();
            output.write(header);
            headerWritten = true;
            clusterStartTime = frame.getTimestampMs();
        }
        
        // Add frame to current cluster
        currentCluster.add(frame);
        
        // If cluster is full, generate and return cluster data
        if (currentCluster.size() >= framesPerCluster) {
            byte[] clusterData = generateCluster();
            output.write(clusterData);
            currentCluster.clear();
            clusterStartTime += 1000; // Next cluster starts 1 second later
        }
        
        return output.toByteArray();
    }
    
    /**
     * Flushes any remaining frames in the current cluster.
     * 
     * <p>Call this method at the end of streaming to ensure all frames are sent,
     * even if the final cluster is not full.
     * 
     * @return MKV-formatted cluster data for remaining frames, or empty array if none
     * @throws IOException If an error occurs during MKV generation
     */
    public byte[] flush() throws IOException {
        if (!currentCluster.isEmpty()) {
            return generateCluster();
        }
        return new byte[0];
    }
    
    /**
     * Generates a complete MKV cluster from accumulated frames.
     * 
     * <p>A cluster consists of:
     * <ul>
     *   <li>Cluster element header with ID and size</li>
     *   <li>Timecode element with cluster timestamp</li>
     *   <li>SimpleBlock elements for each frame with relative timestamps</li>
     * </ul>
     * 
     * @return MKV-formatted cluster byte array
     * @throws IOException If an error occurs during cluster generation
     */
    private byte[] generateCluster() throws IOException {
        if (currentCluster.isEmpty()) {
            return new byte[0];
        }
        
        ByteArrayOutputStream cluster = new ByteArrayOutputStream();
        
        // Cluster header
        cluster.write(createClusterHeader(clusterStartTime));
        
        // Add all frames as SimpleBlocks
        for (AudioFrame frame : currentCluster) {
            long relativeTimestamp = frame.getTimestampMs() - clusterStartTime;
            byte[] simpleBlock = createSimpleBlock(frame.getDataBytes(), relativeTimestamp);
            cluster.write(simpleBlock);
        }
        
        return cluster.toByteArray();
    }
    
    /**
     * Creates the MKV cluster header with timestamp.
     * 
     * @param timestamp Cluster timestamp in milliseconds
     * @return Cluster header bytes including ID, size, and timecode element
     * @throws IOException If an error occurs during header creation
     */
    private byte[] createClusterHeader(long timestamp) throws IOException {
        ByteArrayOutputStream cluster = new ByteArrayOutputStream();
        
        // Cluster ID
        cluster.write(new byte[]{0x1F, 0x43, (byte)0xB6, 0x75});
        // Unknown size
        cluster.write(new byte[]{0x01, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF});
        // Timecode element
        cluster.write(new byte[]{(byte)0xE7}); // Timecode ID
        cluster.write(encodeEbmlSize(8));
        writeUint64BE(cluster, timestamp);
        
        return cluster.toByteArray();
    }
    
    /**
     * Creates an MKV SimpleBlock element containing audio data.
     * 
     * <p>SimpleBlock format:
     * <ul>
     *   <li>Track number (EBML encoded)</li>
     *   <li>Timecode relative to cluster (2 bytes, signed)</li>
     *   <li>Flags (0x80 for keyframe)</li>
     *   <li>Audio data payload</li>
     * </ul>
     * 
     * @param audioData Raw audio data bytes
     * @param relativeTimestamp Timestamp relative to cluster start in milliseconds
     * @return SimpleBlock element bytes
     * @throws IOException If an error occurs during block creation
     */
    private byte[] createSimpleBlock(byte[] audioData, long relativeTimestamp) throws IOException {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        
        // Track number (EBML encoded)
        block.write(0x81); // Track 1
        // Timecode (2 bytes, signed)
        writeUint16BE(block, (int)relativeTimestamp);
        // Flags (keyframe for audio)
        block.write(0x80);
        // Audio data
        block.write(audioData);
        
        // Wrap in SimpleBlock element
        ByteArrayOutputStream simpleBlock = new ByteArrayOutputStream();
        simpleBlock.write(new byte[]{(byte)0xA3}); // SimpleBlock ID
        simpleBlock.write(encodeEbmlSize(block.size()));
        simpleBlock.write(block.toByteArray());
        
        return simpleBlock.toByteArray();
    }
    
    /**
     * Encodes a size value using EBML variable-length encoding.
     * 
     * <p>EBML uses the first bits to indicate the length of the size field:
     * <ul>
     *   <li>1 byte: 0x80 | size (for sizes 0-126)</li>
     *   <li>2 bytes: 0x40 | (size >> 8) (for sizes 127-16382)</li>
     *   <li>3 bytes: 0x20 | (size >> 16) (for sizes 16383-2097150)</li>
     *   <li>And so on...</li>
     * </ul>
     * 
     * @param size The size value to encode
     * @return EBML-encoded size bytes
     */
    private byte[] encodeEbmlSize(long size) {
        if (size < 0x7F) {
            return new byte[]{(byte)(0x80 | size)};
        } else if (size < 0x3FFF) {
            return new byte[]{(byte)(0x40 | (size >> 8)), (byte)(size & 0xFF)};
        } else if (size < 0x1FFFFF) {
            return new byte[]{(byte)(0x20 | (size >> 16)), (byte)((size >> 8) & 0xFF), (byte)(size & 0xFF)};
        } else if (size < 0x0FFFFFFF) {
            return new byte[]{(byte)(0x10 | (size >> 24)), (byte)((size >> 16) & 0xFF), 
                             (byte)((size >> 8) & 0xFF), (byte)(size & 0xFF)};
        } else {
            byte[] result = new byte[8];
            long encoded = 0x0100000000000000L | size;
            for (int i = 7; i >= 0; i--) {
                result[7-i] = (byte)((encoded >> (i * 8)) & 0xFF);
            }
            return result;
        }
    }
    
    private void writeUint64BE(ByteArrayOutputStream out, long value) {
        for (int i = 7; i >= 0; i--) {
            out.write((byte)((value >> (i * 8)) & 0xFF));
        }
    }
    
    private void writeUint16BE(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}