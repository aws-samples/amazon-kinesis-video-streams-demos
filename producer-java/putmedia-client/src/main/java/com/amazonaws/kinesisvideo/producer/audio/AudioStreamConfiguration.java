package com.amazonaws.kinesisvideo.producer.audio;

/**
 * Configuration for audio streaming
 */
public class AudioStreamConfiguration {
    public enum Codec { PCM, ALAW, MULAW, AAC }
    
    private final Codec codec;
    private final double sampleRate;
    private final int channels;
    private final int bitDepth;
    
    /**
     * Creates a new AudioStreamConfiguration.
     * 
     * @param codec the audio codec
     * @param sampleRate the sample rate in Hz
     * @param channels the number of audio channels
     * @param bitDepth the bit depth
     */
    public AudioStreamConfiguration(Codec codec, double sampleRate, int channels, int bitDepth) {
        this.codec = codec;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitDepth = bitDepth;
    }
    
    /**
     * Creates a PCM audio configuration.
     * 
     * @param sampleRate the sample rate in Hz
     * @param channels the number of channels
     * @param bitDepth the bit depth
     * @return a PCM audio configuration
     */
    public static AudioStreamConfiguration pcm(double sampleRate, int channels, int bitDepth) {
        return new AudioStreamConfiguration(Codec.PCM, sampleRate, channels, bitDepth);
    }
    
    /**
     * Creates an A-law audio configuration.
     * 
     * @param sampleRate the sample rate in Hz
     * @param channels the number of channels
     * @return an A-law audio configuration
     */
    public static AudioStreamConfiguration alaw(double sampleRate, int channels) {
        return new AudioStreamConfiguration(Codec.ALAW, sampleRate, channels, 8);
    }
    
    /**
     * Creates a μ-law audio configuration.
     * 
     * @param sampleRate the sample rate in Hz
     * @param channels the number of channels
     * @return a μ-law audio configuration
     */
    public static AudioStreamConfiguration mulaw(double sampleRate, int channels) {
        return new AudioStreamConfiguration(Codec.MULAW, sampleRate, channels, 8);
    }
    
    /** @return the audio codec */
    public Codec getCodec() { return codec; }
    
    /** @return the sample rate in Hz */
    public double getSampleRate() { return sampleRate; }
    
    /** @return the number of channels */
    public int getChannels() { return channels; }
    
    /** @return the bit depth */
    public int getBitDepth() { return bitDepth; }
}