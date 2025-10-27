package com.amazonaws.kinesisvideo.producer.audio.mkv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Streaming version of AudioMkvGenerator that properly manages MKV structure
 */
public class StreamingAudioMkvGenerator {
    
    private final AudioMkvGenerator.AudioTrackInfo trackInfo;
    private final ByteArrayOutputStream mkvStream;
    private boolean headerWritten = false;
    private boolean clusterStarted = false;
    private long clusterTimestamp = 0;
    private final long clusterDurationMs = 2000; // 2 seconds
    
    public StreamingAudioMkvGenerator(AudioMkvGenerator.AudioTrackInfo trackInfo) {
        this.trackInfo = trackInfo;
        this.mkvStream = new ByteArrayOutputStream();
    }
    
    public byte[] addAudioFrame(byte[] audioData, long timestampMs) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        
        // Write header only once at the beginning
        if (!headerWritten) {
            AudioMkvGenerator generator = new AudioMkvGenerator(trackInfo);
            byte[] header = generator.generateHeader();
            mkvStream.write(header);
            output.write(header);
            headerWritten = true;
        }
        
        // Start new cluster if needed
        if (!clusterStarted || (timestampMs - clusterTimestamp >= clusterDurationMs)) {
            byte[] clusterHeader = createClusterHeader(timestampMs);
            mkvStream.write(clusterHeader);
            output.write(clusterHeader);
            clusterTimestamp = timestampMs;
            clusterStarted = true;
        }
        
        // Create SimpleBlock
        byte[] simpleBlock = createSimpleBlock(audioData, timestampMs - clusterTimestamp);
        mkvStream.write(simpleBlock);
        output.write(simpleBlock);
        
        return output.toByteArray();
    }
    

    
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
            // For larger sizes, use 8-byte encoding
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