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

    // Period at which to emit the consumer connection heartbeat. [ms]
    public static final long CONNECTION_HEARTBEAT_INTERVAL = 60000; // 1 minute

    // Metric emitted each interval the consumer can reach KVS and retrieve media
    // (1), or 0 when the connection fails / is closed abruptly.
    public static final String PERSISTENCE_STREAMING_AVAILABILITY_METRIC_NAME = "PersistenceStreamingAvailability";

    public static final String INTERVAL_METRICS_TIMER_NAME = "IntervalMetricsTimer";

    public static final String PERIODIC_LABEL = "StoragePeriodic"; // Short period, used for time to first frame consumed metrics.
    public static final String SUB_RECONNECT_LABEL = "StorageSubReconnect"; // < 1 Media Server forced reconnect, currently must be 60min.
    public static final String SINGLE_RECONNECT_LABEL = "StorageSingleReconnect"; // 1 Media Server forced reconnect, currently must be 60-120min.
    public static final String EXTENDED_LABEL = "StorageExtended"; // > 1 Media Server forced reconnect, currently must be > 120min.

    // Gamma variants
    public static final String GAMMA_PERIODIC_LABEL = "GammaStoragePeriodic";
    public static final String GAMMA_SUB_RECONNECT_LABEL = "GammaStorageSubReconnect";
    public static final String GAMMA_SINGLE_RECONNECT_LABEL = "GammaStorageSingleReconnect";

    // Low FPS variants
    public static final String LOW_FPS_LABEL = "StorageLowFps";
    public static final String GAMMA_LOW_FPS_LABEL = "GammaStorageLowFps";

    // Bitrate-tuned periodic variants (asset set selects encoded bitrate).
    // All three follow the same short-duration periodic pattern as PERIODIC_LABEL.
    public static final String PERIODIC_500KBPS_LABEL = "StoragePeriodic-500kbps";
    public static final String PERIODIC_1MBPS_LABEL = "StoragePeriodic-1mbps";
    public static final String PERIODIC_5MBPS_LABEL = "StoragePeriodic-5mbps";
    public static final String GAMMA_PERIODIC_500KBPS_LABEL = "GammaStoragePeriodic-500kbps";
    public static final String GAMMA_PERIODIC_1MBPS_LABEL = "GammaStoragePeriodic-1mbps";
    public static final String GAMMA_PERIODIC_5MBPS_LABEL = "GammaStoragePeriodic-5mbps";

    // Short-running Video+Audio master + Read-Only viewer (+ co-resident consumer), per bitrate
    // asset set. Same short-duration periodic verification path as PERIODIC_LABEL; bitrate is
    // kept in the label so the three variants stay distinguishable in metrics.
    public static final String SHORT_VA_MASTER_RO_VIEWER_500KBPS_LABEL = "ShortVAMasterROViewer-500kbps";
    public static final String SHORT_VA_MASTER_RO_VIEWER_1MBPS_LABEL = "ShortVAMasterROViewer-1mbps";
    public static final String SHORT_VA_MASTER_RO_VIEWER_5MBPS_LABEL = "ShortVAMasterROViewer-5mbps";
    public static final String GAMMA_SHORT_VA_MASTER_RO_VIEWER_500KBPS_LABEL = "GammaShortVAMasterROViewer-500kbps";
    public static final String GAMMA_SHORT_VA_MASTER_RO_VIEWER_1MBPS_LABEL = "GammaShortVAMasterROViewer-1mbps";
    public static final String GAMMA_SHORT_VA_MASTER_RO_VIEWER_5MBPS_LABEL = "GammaShortVAMasterROViewer-5mbps";

    public static final String CW_DIMENSION_INDIVIDUAL = "StorageWebRTCSDKCanaryStreamName";
    public static final String CW_DIMENSION_AGGREGATE = "StorageWebRTCSDKCanaryLabel";

    // Video verification via GetClip
    public static final String VIDEO_VERIFY_ENABLED_ENV_VAR = "VIDEO_VERIFY_ENABLED";
    public static final String CLIP_OUTPUT_PATH_ENV_VAR = "CANARY_CLIP_OUTPUT_PATH";
    public static final String DEFAULT_CLIP_OUTPUT_PATH = "clip.mp4";

    public static final String FIRST_FRAME_TS_FILE_PATH = "../webrtc-c/";
    public static final String DEFAULT_FIRST_FRAME_TS_FILE = "DefaultFirstFrameSentTSFileName.txt";

    public static final String CANARY_STREAM_NAME_ENV_VAR = "CANARY_STREAM_NAME";
    public static final String FIRST_FRAME_TS_FILE_ENV_VAR = "STORAGE_CANARY_FIRST_FRAME_TS_FILE";
    public static final String CANARY_LABEL_ENV_VAR = "CANARY_LABEL";
    public static final String AWS_DEFAULT_REGION_ENV_VAR = "AWS_DEFAULT_REGION";

    private CanaryConstants() {
    }
}
