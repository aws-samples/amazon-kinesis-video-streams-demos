package com.amazon.kinesis.video.canary.consumer;

public final class StorageWebRTCConstants {

    // public static final String WEBRTC_LONG_RUNNING_LABEL = "WebrtcLongRunning";
    // public static final String INTERVAL_TIMER_NAME = "IntervalMetricsTimer";

    public static final String DEFAULT_FIRST_FRAME_TS_FILE = "DefaultFirstFrameSentTSFileName.txt";
    public static final String FIRST_FRAME_TS_FILE_ENV_VAR = "STORAGE_CANARY_FIRST_FRAME_TS_FILE";
    public static final String FIRST_FRAME_TS_FILE_PATH = "../webrtc-c/build/";

    private StorageWebRTCConstants() {
    }
}