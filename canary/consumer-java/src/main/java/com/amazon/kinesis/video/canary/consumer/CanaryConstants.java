package com.amazon.kinesis.video.canary.consumer;

public final class CanaryConstants {

    public static final int MILLISECONDS_IN_A_SECOND = 1000;

    // Ths is the max number of fragments returned per paginated ListFragments
    // request
    public static final long MAX_FRAGMENTS_PER_FRAGMENT_LIST = 1000;

    // Initial delay of to allow for ListFragments response to be populated. [ms]
    public static final long LIST_FRAGMENTS_INITIAL_DELAY = 30000;

    // Period at which to call ListFragments to check for stream continuity. [ms]
    // Setting this to 2x the fragment duration coming from Media Server has been
    // optimal.
    public static final long LIST_FRAGMENTS_INTERVAL = 20000;

    public static final String INTERVAL_METRICS_TIMER_NAME = "IntervalMetricsTimer";

    public static final String PERIODIC_LABEL = "StoragePeriodic"; // Short period, used for time to first frame consumed metrics.
    public static final String SUB_RECONNECT_LABEL = "StorageSubReconnect"; // < 1 Media Server forced reconnect, currently must be 60min.
    public static final String SINGLE_RECONNECT_LABEL = "StorageSingleReconnect"; // 1 Media Server forced reconnect, currently must be 60-120min.
    public static final String EXTENDED_LABEL = "StorageExtended"; // > 1 Media Server forced reconnect, currently must be > 120min.

    public static final String CW_DIMENSION_INDIVIDUAL = "StorageWebRTCSDKCanaryStreamName";
    public static final String CW_DIMENSION_AGGREGATE = "StorageWebRTCSDKCanaryLabel";

    public static final String FIRST_FRAME_TS_FILE_PATH = "../../../";
    public static final String DEFAULT_FIRST_FRAME_TS_FILE = "DefaultFirstFrameSentTSFileName.txt";

    public static final String CANARY_STREAM_NAME_ENV_VAR = "CANARY_STREAM_NAME";
    public static final String FIRST_FRAME_TS_FILE_ENV_VAR = "STORAGE_CANARY_FIRST_FRAME_TS_FILE";
    public static final String CANARY_LABEL_ENV_VAR = "CANARY_LABEL";
    public static final String AWS_DEFAULT_REGION_ENV_VAR = "AWS_DEFAULT_REGION";

    private CanaryConstants() {
    }
}
