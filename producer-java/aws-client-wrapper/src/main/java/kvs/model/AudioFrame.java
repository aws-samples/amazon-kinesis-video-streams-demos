package kvs.model;

import java.nio.ByteBuffer;

public class AudioFrame {
    private final int index;
    private final int flags;
    private final long decodingTs;
    private final long presentationTs;
    private final long duration;
    private final ByteBuffer data;
    
    public static final int FRAME_FLAG_KEY_FRAME = 1;
    public static final int FRAME_FLAG_NONE = 0;
    
    public AudioFrame(int index, int flags, long decodingTs, long presentationTs, long duration, ByteBuffer data) {
        this.index = index;
        this.flags = flags;
        this.decodingTs = decodingTs;
        this.presentationTs = presentationTs;
        this.duration = duration;
        this.data = data.duplicate();
    }
    
    public static AudioFrame create(byte[] audioData, long timestampMs) {
        return new AudioFrame(
            0, // index
            FRAME_FLAG_KEY_FRAME, // audio frames are always key frames
            timestampMs * 10000, // convert ms to 100ns units (decodingTs)
            timestampMs * 10000, // convert ms to 100ns units (presentationTs) 
            100 * 10000, // 100ms duration in 100ns units
            ByteBuffer.wrap(audioData)
        );
    }
    
    public int getIndex() {
        return index;
    }
    
    public int getFlags() {
        return flags;
    }
    
    public long getDecodingTs() {
        return decodingTs;
    }
    
    public long getPresentationTs() {
        return presentationTs;
    }
    
    public long getDuration() {
        return duration;
    }
    
    public ByteBuffer getData() {
        return data.duplicate();
    }
    
    // Convenience methods for backward compatibility
    public byte[] getDataBytes() {
        ByteBuffer buffer = data.duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
    
    public long getTimestampMs() {
        return presentationTs / 10000; // convert 100ns units back to ms
    }
}