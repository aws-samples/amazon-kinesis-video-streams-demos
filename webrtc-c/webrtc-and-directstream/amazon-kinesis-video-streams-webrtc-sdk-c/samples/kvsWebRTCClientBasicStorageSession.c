#include "Samples.h"
#include <gst/gst.h>
#include <gst/app/gstappsink.h>

extern PSampleConfiguration gSampleConfiguration;

// #define VERBOSE

GstElement* senderPipeline = NULL;

GstFlowReturn on_new_sample(GstElement* sink, gpointer data, UINT64 trackid)
{
    GstBuffer* buffer;
    STATUS retStatus = STATUS_SUCCESS;
    BOOL isDroppable, delta;
    GstFlowReturn ret = GST_FLOW_OK;
    GstSample* sample = NULL;
    GstMapInfo info;
    GstSegment* segment;
    GstClockTime buf_pts;
    Frame frame;
    STATUS status;
    PSampleConfiguration pSampleConfiguration = (PSampleConfiguration) data;
    PSampleStreamingSession pSampleStreamingSession = NULL;
    PRtcRtpTransceiver pRtcRtpTransceiver = NULL;
    UINT32 i;

    CHK_ERR(pSampleConfiguration != NULL, STATUS_NULL_ARG, "NULL sample configuration");

    info.data = NULL;
    sample = gst_app_sink_pull_sample(GST_APP_SINK(sink));

    buffer = gst_sample_get_buffer(sample);
    isDroppable = GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_CORRUPTED) || GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DECODE_ONLY) ||
        (GST_BUFFER_FLAGS(buffer) == GST_BUFFER_FLAG_DISCONT) ||
        (GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DISCONT) && GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT)) ||
        // drop if buffer contains header only and has invalid timestamp
        !GST_BUFFER_PTS_IS_VALID(buffer);

    if (!isDroppable) {
        delta = GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT);

        frame.flags = delta ? FRAME_FLAG_NONE : FRAME_FLAG_KEY_FRAME;

        // convert from segment timestamp to running time in live mode.
        segment = gst_sample_get_segment(sample);
        buf_pts = gst_segment_to_running_time(segment, GST_FORMAT_TIME, buffer->pts);
        if (!GST_CLOCK_TIME_IS_VALID(buf_pts)) {
            DLOGE("[KVS GStreamer Master] Frame contains invalid PTS dropping the frame");
        }

        if (!(gst_buffer_map(buffer, &info, GST_MAP_READ))) {
            DLOGE("[KVS GStreamer Master] on_new_sample(): Gst buffer mapping failed");
            goto CleanUp;
        }

        frame.trackId = trackid;
        frame.duration = 0;
        frame.version = FRAME_CURRENT_VERSION;
        frame.size = (UINT32) info.size;
        frame.frameData = (PBYTE) info.data;

        MUTEX_LOCK(pSampleConfiguration->streamingSessionListReadLock);
        for (i = 0; i < pSampleConfiguration->streamingSessionCount; ++i) {
            pSampleStreamingSession = pSampleConfiguration->sampleStreamingSessionList[i];
            frame.index = (UINT32) ATOMIC_INCREMENT(&pSampleStreamingSession->frameIndex);

            pRtcRtpTransceiver = pSampleStreamingSession->pVideoRtcRtpTransceiver;
            frame.presentationTs = pSampleStreamingSession->videoTimestamp;
            frame.decodingTs = frame.presentationTs;
            pSampleStreamingSession->videoTimestamp += HUNDREDS_OF_NANOS_IN_A_SECOND / 15; // 15 fps
            
            status = writeFrame(pRtcRtpTransceiver, &frame);
            if (status != STATUS_SRTP_NOT_READY_YET && status != STATUS_SUCCESS) {
#ifdef VERBOSE
                DLOGE("[KVS GStreamer Master] writeFrame() failed with 0x%08x", status);
#endif
            } else if (status == STATUS_SUCCESS && pSampleStreamingSession->firstFrame) {
                // Mark first frame received
                pSampleStreamingSession->firstFrame = FALSE;
            } else if (status == STATUS_SRTP_NOT_READY_YET) {
                DLOGI("[KVS GStreamer Master] SRTP not ready yet, dropping frame");
            }
        }
        MUTEX_UNLOCK(pSampleConfiguration->streamingSessionListReadLock);
    }

CleanUp:

    if (info.data != NULL) {
        gst_buffer_unmap(buffer, &info);
    }

    if (sample != NULL) {
        gst_sample_unref(sample);
    }

    if (ATOMIC_LOAD_BOOL(&pSampleConfiguration->appTerminateFlag)) {
        ret = GST_FLOW_EOS;
    }

    return ret;
}

GstFlowReturn on_new_sample_video(GstElement* sink, gpointer data)
{
    return on_new_sample(sink, data, DEFAULT_VIDEO_TRACK_ID);
}

PVOID sendGstreamerVideo(PVOID args)
{
    STATUS retStatus = STATUS_SUCCESS;
    GstElement *appsinkVideo = NULL;
    GstBus* bus;
    GstMessage* msg;
    GError* error = NULL;
    PSampleConfiguration pSampleConfiguration = (PSampleConfiguration) args;

    CHK_ERR(pSampleConfiguration != NULL, STATUS_NULL_ARG, "[KVS Gstreamer Master] Streaming session is NULL");

    // Simplified pipeline known to work with KVS media storage
    senderPipeline = gst_parse_launch(
    "v4l2src device=\"/dev/video0\" ! "
    "queue ! videoconvert ! video/x-raw,width=640,height=480,framerate=15/1 ! "
    "x264enc name=sampleVideoEncoder bframes=0 speed-preset=veryfast bitrate=500 byte-stream=TRUE tune=zerolatency ! "
    "video/x-h264,stream-format=byte-stream,alignment=au,profile=baseline ! "
    "appsink sync=TRUE emit-signals=TRUE name=appsink-video",
    &error);

    CHK_ERR(senderPipeline != NULL, STATUS_NULL_ARG, "[KVS Gstreamer Master] Pipeline is NULL");

    appsinkVideo = gst_bin_get_by_name(GST_BIN(senderPipeline), "appsink-video");

    if (appsinkVideo == NULL) {
        DLOGE("[KVS GStreamer Master] sendGstreamerVideo(): cant find appsink, operation returned status code: 0x%08x", STATUS_INTERNAL_ERROR);
        goto CleanUp;
    }

    g_signal_connect(appsinkVideo, "new-sample", G_CALLBACK(on_new_sample_video), (gpointer) pSampleConfiguration);
    gst_element_set_state(senderPipeline, GST_STATE_PLAYING);

    /* block until error or EOS */
    bus = gst_element_get_bus(senderPipeline);
    msg = gst_bus_timed_pop_filtered(bus, GST_CLOCK_TIME_NONE, GST_MESSAGE_ERROR | GST_MESSAGE_EOS);

    /* Free resources */
    if (msg != NULL) {
        gst_message_unref(msg);
    }
    if (bus != NULL) {
        gst_object_unref(bus);
    }
    if (senderPipeline != NULL) {
        gst_element_set_state(senderPipeline, GST_STATE_NULL);
        gst_object_unref(senderPipeline);
    }
    if (appsinkVideo != NULL) {
        gst_object_unref(appsinkVideo);
    }

CleanUp:

    if (error != NULL) {
        DLOGE("[KVS GStreamer Master] %s", error->message);
        g_clear_error(&error);
    }

    return (PVOID) (ULONG_PTR) retStatus;
}

INT32 main(INT32 argc, CHAR* argv[])
{
    STATUS retStatus = STATUS_SUCCESS;
    PSampleConfiguration pSampleConfiguration = NULL;
    SignalingClientMetrics signalingClientMetrics;
    PCHAR pChannelName;
    signalingClientMetrics.version = SIGNALING_CLIENT_METRICS_CURRENT_VERSION;

    SET_INSTRUMENTED_ALLOCATORS();

#ifndef _WIN32
    signal(SIGINT, sigintHandler);
#endif

    // do trickleIce by default
    printf("[KVS Master] Using trickleICE by default\n");

#ifdef IOT_CORE_ENABLE_CREDENTIALS
    CHK_ERR((pChannelName = getenv(IOT_CORE_THING_NAME)) != NULL, STATUS_INVALID_OPERATION, "AWS_IOT_CORE_THING_NAME must be set");
#else
    pChannelName = argc > 1 ? argv[1] : SAMPLE_CHANNEL_NAME;
#endif

    retStatus = createSampleConfiguration(pChannelName, SIGNALING_CHANNEL_ROLE_TYPE_MASTER, TRUE, TRUE, TRUE, &pSampleConfiguration);
    if (retStatus != STATUS_SUCCESS) {
        printf("[KVS Master] createSampleConfiguration(): operation returned status code: 0x%08x \n", retStatus);
        goto CleanUp;
    }
    
    // Enable media storage
    pSampleConfiguration->channelInfo.useMediaStorage = TRUE;
    
    // Set the media storage configuration stream ARN if provided as third argument
    if (argc > 2) {
        pSampleConfiguration->channelInfo.pStorageStreamArn = argv[2];
        printf("[KVS Master] Using storage stream ARN: %s\n", pSampleConfiguration->channelInfo.pStorageStreamArn);
    }
    
    // Configure for better connectivity
    pSampleConfiguration->trickleIce = TRUE; // Ensure trickle ICE is enabled
    pSampleConfiguration->useTurn = TRUE; // Enable TURN for better connectivity
    
    printf("[KVS Master] ICE configuration: trickleIce=%s, useTurn=%s\n", 
           pSampleConfiguration->trickleIce ? "enabled" : "disabled",
           pSampleConfiguration->useTurn ? "enabled" : "disabled");

    printf("[KVS Master] Created signaling channel %s\n", pChannelName);

    if (pSampleConfiguration->enableFileLogging) {
        retStatus =
            createFileLogger(FILE_LOGGING_BUFFER_SIZE, MAX_NUMBER_OF_LOG_FILES, (PCHAR) FILE_LOGGER_LOG_FILE_DIRECTORY_PATH, TRUE, TRUE, NULL);
        if (retStatus != STATUS_SUCCESS) {
            printf("[KVS Master] createFileLogger(): operation returned status code: 0x%08x \n", retStatus);
            pSampleConfiguration->enableFileLogging = FALSE;
        }
    }

    // Initialize GStreamer
    gst_init(&argc, &argv);
    printf("[KVS Master] Initialized GStreamer\n");

    // Set the video handler
    pSampleConfiguration->videoSource = sendGstreamerVideo;
    pSampleConfiguration->onDataChannel = onDataChannel;
    pSampleConfiguration->mediaType = SAMPLE_STREAMING_VIDEO_ONLY;
    printf("[KVS Master] Media storage is %s\n", pSampleConfiguration->channelInfo.useMediaStorage ? "enabled" : "disabled");
    printf("[KVS Master] Finished setting video handler\n");

    // Initialize KVS WebRTC
    retStatus = initKvsWebRtc();
    if (retStatus != STATUS_SUCCESS) {
        printf("[KVS Master] initKvsWebRtc(): operation returned status code: 0x%08x \n", retStatus);
        goto CleanUp;
    }
    printf("[KVS Master] KVS WebRTC initialization completed successfully\n");

    pSampleConfiguration->signalingClientCallbacks.messageReceivedFn = signalingMessageReceived;

    strcpy(pSampleConfiguration->clientInfo.clientId, SAMPLE_MASTER_CLIENT_ID);

    retStatus = createSignalingClientSync(&pSampleConfiguration->clientInfo, &pSampleConfiguration->channelInfo,
                                          &pSampleConfiguration->signalingClientCallbacks, pSampleConfiguration->pCredentialProvider,
                                          &pSampleConfiguration->signalingClientHandle);
    if (retStatus != STATUS_SUCCESS) {
        printf("[KVS Master] createSignalingClientSync(): operation returned status code: 0x%08x \n", retStatus);
        goto CleanUp;
    }
    printf("[KVS Master] Signaling client created successfully\n");

    // Enable the processing of the messages
    retStatus = signalingClientFetchSync(pSampleConfiguration->signalingClientHandle);
    if (retStatus != STATUS_SUCCESS) {
        printf("[KVS Master] signalingClientFetchSync(): operation returned status code: 0x%08x \n", retStatus);
        goto CleanUp;
    }

    retStatus = signalingClientConnectSync(pSampleConfiguration->signalingClientHandle);
    if (retStatus != STATUS_SUCCESS) {
        printf("[KVS Master] signalingClientConnectSync(): operation returned status code: 0x%08x \n", retStatus);
        goto CleanUp;
    }
    printf("[KVS Master] Signaling client connection to socket established\n");

    printf("Beginning streaming...check the stream over channel %s\n", pChannelName);

    if (pSampleConfiguration->channelInfo.useMediaStorage == TRUE) {
        printf("invoke join storage session\n");
        retStatus = signalingClientJoinSessionSync(pSampleConfiguration->signalingClientHandle);
        if (retStatus != STATUS_SUCCESS) {
            printf("[KVS Master] signalingClientConnectSync(): operation returned status code: 0x%08x\n", retStatus);
            goto CleanUp;
        }
        printf("[KVS Master] Signaling client connection to socket established\n");
    }

    gSampleConfiguration = pSampleConfiguration;

    printf("[KVS Master] Channel %s set up done \n", pChannelName);

    // Checking for termination
    retStatus = sessionCleanupWait(pSampleConfiguration);
    if (retStatus != STATUS_SUCCESS) {
        printf("[KVS Master] sessionCleanupWait(): operation returned status code: 0x%08x \n", retStatus);
        goto CleanUp;
    }

    printf("[KVS Master] Streaming session terminated\n");

CleanUp:

    if (retStatus != STATUS_SUCCESS) {
        printf("[KVS Master] Terminated with status code 0x%08x\n", retStatus);
    }

    printf("[KVS Master] Cleaning up....\n");
    if (pSampleConfiguration != NULL) {
        // Kick of the termination sequence
        ATOMIC_STORE_BOOL(&pSampleConfiguration->appTerminateFlag, TRUE);

        if (IS_VALID_MUTEX_VALUE(pSampleConfiguration->sampleConfigurationObjLock)) {
            MUTEX_LOCK(pSampleConfiguration->sampleConfigurationObjLock);
        }

        // Cancel the media thread
        if (pSampleConfiguration->mediaThreadStarted) {
            DLOGD("Canceling media thread");
            THREAD_CANCEL(pSampleConfiguration->mediaSenderTid);
        }

        if (IS_VALID_MUTEX_VALUE(pSampleConfiguration->sampleConfigurationObjLock)) {
            MUTEX_UNLOCK(pSampleConfiguration->sampleConfigurationObjLock);
        }

        if (pSampleConfiguration->mediaSenderTid != INVALID_TID_VALUE) {
            THREAD_JOIN(pSampleConfiguration->mediaSenderTid, NULL);
        }

        if (pSampleConfiguration->enableFileLogging) {
            freeFileLogger();
        }
        retStatus = signalingClientGetMetrics(pSampleConfiguration->signalingClientHandle, &signalingClientMetrics);
        if (retStatus == STATUS_SUCCESS) {
            logSignalingClientStats(&signalingClientMetrics);
        } else {
            printf("[KVS Master] signalingClientGetMetrics() operation returned status code: 0x%08x\n", retStatus);
        }
        retStatus = freeSignalingClient(&pSampleConfiguration->signalingClientHandle);
        if (retStatus != STATUS_SUCCESS) {
            printf("[KVS Master] freeSignalingClient(): operation returned status code: 0x%08x", retStatus);
        }

        retStatus = freeSampleConfiguration(&pSampleConfiguration);
        if (retStatus != STATUS_SUCCESS) {
            printf("[KVS Master] freeSampleConfiguration(): operation returned status code: 0x%08x", retStatus);
        }
    }
    printf("[KVS Master] Cleanup done\n");

    RESET_INSTRUMENTED_ALLOCATORS();

    return STATUS_FAILED(retStatus) ? EXIT_FAILURE : EXIT_SUCCESS;
}