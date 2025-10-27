package com.amazonaws.kinesisvideo.producer.audio.mkv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal MKV generator for audio tracks only
 *
 */
public class AudioMkvGenerator {
    
    // EBML/MKV constants from C implementation
    private static final byte[] EBML_HEADER = {
        0x1A, 0x45, (byte)0xDF, (byte)0xA3, // EBML
        (byte)0xA3, // Size (35 bytes)
        0x42, (byte)0x86, (byte)0x81, 0x01, // EBMLVersion = 1
        0x42, (byte)0xF7, (byte)0x81, 0x01, // EBMLReadVersion = 1
        0x42, (byte)0xF2, (byte)0x81, 0x04, // EBMLMaxIDLength = 4
        0x42, (byte)0xF3, (byte)0x81, 0x08, // EBMLMaxSizeLength = 8
        0x42, (byte)0x82, (byte)0x88, 0x6D, 0x61, 0x74, 0x72, 0x6F, 0x73, 0x6B, 0x61, // DocType = "matroska"
        0x42, (byte)0x87, (byte)0x81, 0x04, // DocTypeVersion = 4
        0x42, (byte)0x85, (byte)0x81, 0x02  // DocTypeReadVersion = 2
    };
    
    private static final byte[] SEGMENT_HEADER = {
        0x18, 0x53, (byte)0x80, 0x67, // Segment
        0x01, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF // Unknown size
    };
    
    private static final long DEFAULT_TIMECODE_SCALE = 1000000L; // 1ms in nanoseconds
    
    private final AudioTrackInfo trackInfo;
    private final ByteArrayOutputStream buffer;
    private boolean headerWritten = false;
    private long clusterTimestamp = 0;
    private int trackNumber = 1;
    
    public AudioMkvGenerator(AudioTrackInfo trackInfo) {
        this.trackInfo = trackInfo;
        this.buffer = new ByteArrayOutputStream();
    }
    
    public byte[] generateHeader() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        
        // EBML Header
        header.write(EBML_HEADER);
        
        // Segment Header
        header.write(SEGMENT_HEADER);
        
        // Segment Info
        writeSegmentInfo(header);
        
        // Tracks
        writeTracks(header);
        
        return header.toByteArray();
    }
    
    public byte[] packageAudioFrame(byte[] audioData, long timestampMs) throws IOException {
        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream();
        
        if (!headerWritten) {
            frameBuffer.write(generateHeader());
            writeCluster(frameBuffer, timestampMs);
            clusterTimestamp = timestampMs;
            headerWritten = true;
        } else if (timestampMs - clusterTimestamp >= 2000) { // 2 second clusters
            writeCluster(frameBuffer, timestampMs);
            clusterTimestamp = timestampMs;
        }
        
        // Write SimpleBlock
        writeSimpleBlock(frameBuffer, audioData, timestampMs - clusterTimestamp);
        
        return frameBuffer.toByteArray();
    }
    
    private void writeSegmentInfo(ByteArrayOutputStream out) throws IOException {
        ByteArrayOutputStream segInfo = new ByteArrayOutputStream();
        
        // TimecodeScale
        segInfo.write(new byte[]{0x2A, (byte)0xD7, (byte)0xB1}); // TimecodeScale ID
        segInfo.write(encodeEbmlSize(8));
        writeUint64BE(segInfo, DEFAULT_TIMECODE_SCALE);
        
        // MuxingApp
        byte[] muxingApp = "AudioMkvGenerator".getBytes();
        segInfo.write(new byte[]{0x4D, (byte)0x80}); // MuxingApp ID
        segInfo.write(encodeEbmlSize(muxingApp.length));
        segInfo.write(muxingApp);
        
        // WritingApp
        segInfo.write(new byte[]{0x57, 0x41}); // WritingApp ID
        segInfo.write(encodeEbmlSize(muxingApp.length));
        segInfo.write(muxingApp);
        
        // Write Info element
        out.write(new byte[]{0x15, 0x49, (byte)0xA9, 0x66}); // Info ID
        out.write(encodeEbmlSize(segInfo.size()));
        out.write(segInfo.toByteArray());
    }
    
    private void writeTracks(ByteArrayOutputStream out) throws IOException {
        ByteArrayOutputStream tracks = new ByteArrayOutputStream();
        
        // TrackEntry
        ByteArrayOutputStream trackEntry = new ByteArrayOutputStream();
        
        // TrackNumber
        trackEntry.write(new byte[]{(byte)0xD7}); // TrackNumber ID
        trackEntry.write(encodeEbmlSize(1));
        trackEntry.write(trackNumber);
        
        // TrackUID
        trackEntry.write(new byte[]{0x73, (byte)0xC5}); // TrackUID ID
        trackEntry.write(encodeEbmlSize(8));
        writeUint64BE(trackEntry, trackInfo.trackId);
        
        // TrackType (Audio = 2)
        trackEntry.write(new byte[]{(byte)0x83}); // TrackType ID
        trackEntry.write(encodeEbmlSize(1));
        trackEntry.write(2);
        
        // CodecID
        byte[] codecId = trackInfo.codecId.getBytes();
        trackEntry.write(new byte[]{(byte)0x86}); // CodecID ID
        trackEntry.write(encodeEbmlSize(codecId.length));
        trackEntry.write(codecId);
        
        // Audio settings
        writeAudioSettings(trackEntry);
        
        // CodecPrivate (if present)
        if (trackInfo.codecPrivateData != null && trackInfo.codecPrivateData.length > 0) {
            trackEntry.write(new byte[]{0x63, (byte)0xA2}); // CodecPrivate ID
            trackEntry.write(encodeEbmlSize(trackInfo.codecPrivateData.length));
            trackEntry.write(trackInfo.codecPrivateData);
        }
        
        // Write TrackEntry
        tracks.write(new byte[]{(byte)0xAE}); // TrackEntry ID
        tracks.write(encodeEbmlSize(trackEntry.size()));
        tracks.write(trackEntry.toByteArray());
        
        // Write Tracks element
        out.write(new byte[]{0x16, 0x54, (byte)0xAE, 0x6B}); // Tracks ID
        out.write(encodeEbmlSize(tracks.size()));
        out.write(tracks.toByteArray());
    }
    
    private void writeAudioSettings(ByteArrayOutputStream out) throws IOException {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        
        // SamplingFrequency
        audio.write(new byte[]{(byte)0xB5}); // SamplingFrequency ID
        audio.write(encodeEbmlSize(8));
        writeDoubleIEEE754BE(audio, trackInfo.samplingFrequency);
        
        // Channels
        audio.write(new byte[]{(byte)0x9F}); // Channels ID
        audio.write(encodeEbmlSize(1));
        audio.write(trackInfo.channels);
        
        // BitDepth (if specified)
        if (trackInfo.bitDepth > 0) {
            audio.write(new byte[]{0x62, 0x64}); // BitDepth ID
            audio.write(encodeEbmlSize(1));
            audio.write(trackInfo.bitDepth);
        }
        
        // Write Audio element
        out.write(new byte[]{(byte)0xE1}); // Audio ID
        out.write(encodeEbmlSize(audio.size()));
        out.write(audio.toByteArray());
    }
    
    private void writeCluster(ByteArrayOutputStream out, long timestamp) throws IOException {
        // Only write cluster header if this is a new cluster
        out.write(new byte[]{0x1F, 0x43, (byte)0xB6, 0x75}); // Cluster ID
        out.write(new byte[]{0x01, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF}); // Unknown size
        
        // Timecode
        out.write(new byte[]{(byte)0xE7}); // Timecode ID
        out.write(encodeEbmlSize(8));
        writeUint64BE(out, timestamp);
    }
    
    private void writeSimpleBlock(ByteArrayOutputStream out, byte[] audioData, long relativeTimestamp) throws IOException {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        
        // Track number (EBML encoded)
        block.write(0x80 | trackNumber);
        
        // Timecode (2 bytes, signed)
        writeUint16BE(block, (int)relativeTimestamp);
        
        // Flags (keyframe for audio)
        block.write(0x80);
        
        // Audio data
        block.write(audioData);
        
        // Write SimpleBlock element
        out.write(new byte[]{(byte)0xA3}); // SimpleBlock ID
        out.write(encodeEbmlSize(block.size()));
        out.write(block.toByteArray());
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
            ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            buf.putLong(0x0100000000000000L | size);
            return buf.array();
        }
    }
    
    private void writeUint64BE(ByteArrayOutputStream out, long value) {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(value);
        out.write(buf.array(), 0, 8);
    }
    
    private void writeUint16BE(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
    
    private void writeDoubleIEEE754BE(ByteArrayOutputStream out, double value) {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buf.putDouble(value);
        out.write(buf.array(), 0, 8);
    }
    
    public static class AudioTrackInfo {
        public final long trackId;
        public final String codecId;
        public final double samplingFrequency;
        public final int channels;
        public final int bitDepth;
        public final byte[] codecPrivateData;
        
        public AudioTrackInfo(long trackId, String codecId, double samplingFrequency, 
                             int channels, int bitDepth, byte[] codecPrivateData) {
            this.trackId = trackId;
            this.codecId = codecId;
            this.samplingFrequency = samplingFrequency;
            this.channels = channels;
            this.bitDepth = bitDepth;
            this.codecPrivateData = codecPrivateData;
        }
        
        // Common audio formats
        public static AudioTrackInfo createAAC(long trackId, double samplingFreq, int channels, byte[] aacConfig) {
            return new AudioTrackInfo(trackId, "A_AAC", samplingFreq, channels, 0, aacConfig);
        }
        
        public static AudioTrackInfo createPCM(long trackId, double samplingFreq, int channels, int bitDepth) {
            return new AudioTrackInfo(trackId, "A_PCM/INT/LIT", samplingFreq, channels, bitDepth, null);
        }
        
        public static AudioTrackInfo createALaw(long trackId, double samplingFreq, int channels) {
            return new AudioTrackInfo(trackId, "A_MS/ACM", samplingFreq, channels, 8, generatePcmCodecPrivateData(0x0006, samplingFreq, channels));
        }
        
        public static AudioTrackInfo createMuLaw(long trackId, double samplingFreq, int channels) {
            return new AudioTrackInfo(trackId, "A_MS/ACM", samplingFreq, channels, 8, generatePcmCodecPrivateData(0x0007, samplingFreq, channels));
        }
        
        private static byte[] generatePcmCodecPrivateData(int formatCode, double samplingFreq, int channels) {
            ByteBuffer buf = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short)formatCode);           // Format code
            buf.putShort((short)channels);             // Channels
            buf.putInt((int)samplingFreq);             // Sample rate
            buf.putInt((int)(samplingFreq * channels)); // Byte rate
            buf.putShort((short)channels);             // Block align
            buf.putShort((short)8);                    // Bits per sample
            buf.putShort((short)0);                    // Extra data size
            return buf.array();
        }
    }
}