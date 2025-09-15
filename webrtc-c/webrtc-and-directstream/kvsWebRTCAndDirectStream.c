#include <gst/gst.h>
#include <gst/app/gstappsink.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <time.h>
#include "Samples.h"

extern PSampleConfiguration gSampleConfiguration;

// Global variables
GstElement *pipeline = NULL;
GMainLoop *main_loop = NULL;
volatile ATOMIC_BOOL terminate = FALSE;

// Signal handler for graceful termination
void signal_handler(int signum) {
    printf("Caught signal %d, terminating...\n", signum);
    ATOMIC_STORE_BOOL(&terminate, TRUE);
    if (main_loop != NULL) {
        g_main_loop_quit(main_loop);
    }
}

// Custom message handler to handle ICE candidates properly
VOID customMessageReceived(UINT64 customData, PReceivedSignalingMessage pReceivedSignalingMessage) {
    // Forward all messages directly to the standard handler without filtering
    // This ensures all ICE candidates are processed properly
    signalingMessageReceived(customData, pReceivedSignalingMessage);
}

// Bus watch callback
static gboolean bus_call(GstBus *bus, GstMessage *msg, gpointer data) {
    switch (GST_MESSAGE_TYPE(msg)) {
        case GST_MESSAGE_ERROR: {
            GError *err;
            gchar *debug;
            gst_message_parse_error(msg, &err, &debug);
            g_print("Error: %s\n", err->message);
            g_error_free(err);
            g_free(debug);
            g_main_loop_quit(main_loop);
            break;
        }
        case GST_MESSAGE_EOS:
            g_print("End of stream\n");
            g_main_loop_quit(main_loop);
            break;
        default:
            break;
    }
    return TRUE;
}

// WebRTC callback for new video samples
GstFlowReturn on_new_webrtc_sample(GstElement *sink, gpointer data) {
    GstSample *sample;
    GstBuffer *buffer;
    GstMapInfo map;
    Frame frame;
    STATUS status;
    PSampleConfiguration pSampleConfiguration = (PSampleConfiguration) data;
    PSampleStreamingSession pSampleStreamingSession = NULL;
    PRtcRtpTransceiver pRtcRtpTransceiver = NULL;
    UINT32 i;
    gboolean isDroppable, delta;

    // Pull the sample from the sink
    sample = gst_app_sink_pull_sample(GST_APP_SINK(sink));
    if (sample == NULL) {
        return GST_FLOW_ERROR;
    }

    buffer = gst_sample_get_buffer(sample);
    isDroppable = GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_CORRUPTED) || 
                  GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DECODE_ONLY) ||
                  (GST_BUFFER_FLAGS(buffer) == GST_BUFFER_FLAG_DISCONT) ||
                  (GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DISCONT) && 
                   GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT)) ||
                  !GST_BUFFER_PTS_IS_VALID(buffer);

    if (!isDroppable) {
        delta = GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT);
        frame.flags = delta ? FRAME_FLAG_NONE : FRAME_FLAG_KEY_FRAME;

        if (gst_buffer_map(buffer, &map, GST_MAP_READ)) {
            frame.trackId = DEFAULT_VIDEO_TRACK_ID;
            frame.duration = 0;
            frame.version = FRAME_CURRENT_VERSION;
            frame.size = (UINT32) map.size;
            frame.frameData = (PBYTE) map.data;

            MUTEX_LOCK(pSampleConfiguration->streamingSessionListReadLock);
            for (i = 0; i < pSampleConfiguration->streamingSessionCount; ++i) {
                pSampleStreamingSession = pSampleConfiguration->sampleStreamingSessionList[i];
                frame.index = (UINT32) ATOMIC_INCREMENT(&pSampleStreamingSession->frameIndex);

                pRtcRtpTransceiver = pSampleStreamingSession->pVideoRtcRtpTransceiver;
                frame.presentationTs = pSampleStreamingSession->videoTimestamp;
                frame.decodingTs = frame.presentationTs;
                pSampleStreamingSession->videoTimestamp += SAMPLE_VIDEO_FRAME_DURATION;

                status = writeFrame(pRtcRtpTransceiver, &frame);
                if (status != STATUS_SRTP_NOT_READY_YET && status != STATUS_SUCCESS) {
                    printf("writeFrame failed with 0x%08x\n", status);
                } else if (status == STATUS_SUCCESS && pSampleStreamingSession->firstFrame) {
                    pSampleStreamingSession->firstFrame = FALSE;
                    printf("First frame sent successfully\n");
                }
            }
            MUTEX_UNLOCK(pSampleConfiguration->streamingSessionListReadLock);

            gst_buffer_unmap(buffer, &map);
        }
    }

    gst_sample_unref(sample);
    return GST_FLOW_OK;
}

int main(int argc, char *argv[]) {
    STATUS retStatus = STATUS_SUCCESS;
    PSampleConfiguration pSampleConfiguration = NULL;
    SignalingClientMetrics signalingClientMetrics;
    PCHAR pChannelName;
    PCHAR pStreamName;
    GstElement *source, *capsfilter, *convert, *tee;
    
    // WebRTC branch elements
    GstElement *webrtc_queue, *webrtc_convert, *webrtc_encoder, *webrtc_caps, *webrtc_appsink;
    
    // KVS branch elements
    GstElement *kvs_queue, *kvs_convert, *kvs_encoder, *kvs_parse, *kvs_caps, *kvs_sink;
    
    GstBus *bus;
    GstPad *tee_webrtc_pad = NULL, *tee_kvs_pad = NULL;
    GstPad *webrtc_queue_pad, *kvs_queue_pad;
    GstCaps *caps, *webrtc_h264_caps, *kvs_h264_caps;

    // Initialize GStreamer first
    gst_init(&argc, &argv);

    // Check arguments
    if (argc < 3) {
        g_printerr("Usage: %s <signaling-channel> <stream-name>\n", argv[0]);
        return 1;
    }

    pChannelName = argv[1];
    pStreamName = argv[2];

    // Set up signal handlers
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    // Create the main loop
    main_loop = g_main_loop_new(NULL, FALSE);

    // Create WebRTC sample configuration - video only, no audio
    retStatus = createSampleConfiguration(pChannelName, SIGNALING_CHANNEL_ROLE_TYPE_MASTER, TRUE, FALSE, TRUE, &pSampleConfiguration);
    if (retStatus != STATUS_SUCCESS) {
        printf("createSampleConfiguration(): operation returned status code: 0x%08x\n", retStatus);
        goto CleanUp;
    }

    // Enable media storage
    pSampleConfiguration->channelInfo.useMediaStorage = TRUE;
    
    // Configure for better connectivity
    pSampleConfiguration->trickleIce = TRUE;
    pSampleConfiguration->useTurn = TRUE;
    
    // Set media type to video only
    pSampleConfiguration->mediaType = SAMPLE_STREAMING_VIDEO_ONLY;
    
    // Set custom data for callbacks
    pSampleConfiguration->customData = (UINT64) pSampleConfiguration;
    
    printf("Created signaling channel %s\n", pChannelName);
    printf("ICE configuration: trickleIce=%s, useTurn=%s\n", 
           pSampleConfiguration->trickleIce ? "enabled" : "disabled",
           pSampleConfiguration->useTurn ? "enabled" : "disabled");

    // Set KVS log configuration path
    setenv("KVS_LOG_CONFIG", "./kvs_log_configuration", 1);
    
    // Initialize KVS WebRTC
    retStatus = initKvsWebRtc();
    if (retStatus != STATUS_SUCCESS) {
        printf("initKvsWebRtc(): operation returned status code: 0x%08x\n", retStatus);
        goto CleanUp;
    }
    printf("KVS WebRTC initialization completed successfully\n");

    // Set up signaling client with custom message handler
    pSampleConfiguration->signalingClientCallbacks.messageReceivedFn = customMessageReceived;
    pSampleConfiguration->signalingClientCallbacks.customData = (UINT64) pSampleConfiguration;
    
    // Generate a unique client ID with timestamp to avoid conflicts between multiple devices
    char uniqueClientId[MAX_SIGNALING_CLIENT_ID_LEN];
    snprintf(uniqueClientId, MAX_SIGNALING_CLIENT_ID_LEN, "%s-%llu", SAMPLE_MASTER_CLIENT_ID, (unsigned long long)time(NULL));
    strcpy(pSampleConfiguration->clientInfo.clientId, uniqueClientId);
    
    printf("Using unique client ID: %s\n", uniqueClientId);

    retStatus = createSignalingClientSync(&pSampleConfiguration->clientInfo, &pSampleConfiguration->channelInfo,
                                          &pSampleConfiguration->signalingClientCallbacks, pSampleConfiguration->pCredentialProvider,
                                          &pSampleConfiguration->signalingClientHandle);
    if (retStatus != STATUS_SUCCESS) {
        printf("createSignalingClientSync(): operation returned status code: 0x%08x\n", retStatus);
        goto CleanUp;
    }
    printf("Signaling client created successfully\n");

    // Enable the processing of the messages
    retStatus = signalingClientFetchSync(pSampleConfiguration->signalingClientHandle);
    if (retStatus != STATUS_SUCCESS) {
        printf("signalingClientFetchSync(): operation returned status code: 0x%08x\n", retStatus);
        goto CleanUp;
    }

    retStatus = signalingClientConnectSync(pSampleConfiguration->signalingClientHandle);
    if (retStatus != STATUS_SUCCESS) {
        printf("signalingClientConnectSync(): operation returned status code: 0x%08x\n", retStatus);
        goto CleanUp;
    }
    printf("Signaling client connection to socket established\n");

    // Join storage session if media storage is enabled
    if (pSampleConfiguration->channelInfo.useMediaStorage == TRUE) {
        printf("Invoking join storage session\n");
        retStatus = signalingClientJoinSessionSync(pSampleConfiguration->signalingClientHandle);
        if (retStatus != STATUS_SUCCESS) {
            printf("signalingClientJoinSessionSync(): operation returned status code: 0x%08x\n", retStatus);
            goto CleanUp;
        }
        printf("Joined storage session successfully\n");
    }

    gSampleConfiguration = pSampleConfiguration;

    // Create GStreamer pipeline with tee for both WebRTC and KVS direct streaming
    pipeline = gst_pipeline_new("dual-streaming-pipeline");
    
    // Create the common elements
    source = gst_element_factory_make("autovideosrc", "source");
    capsfilter = gst_element_factory_make("capsfilter", "capsfilter");
    convert = gst_element_factory_make("videoconvert", "convert");
    tee = gst_element_factory_make("tee", "tee");
    
    // WebRTC branch elements
    webrtc_queue = gst_element_factory_make("queue", "webrtc_queue");
    webrtc_convert = gst_element_factory_make("videoconvert", "webrtc_convert");
    webrtc_encoder = gst_element_factory_make("x264enc", "webrtc_encoder");
    webrtc_caps = gst_element_factory_make("capsfilter", "webrtc_caps");
    webrtc_appsink = gst_element_factory_make("appsink", "webrtc_appsink");
    
    // KVS branch elements
    kvs_queue = gst_element_factory_make("queue", "kvs_queue");
    kvs_convert = gst_element_factory_make("videoconvert", "kvs_convert");
    kvs_encoder = gst_element_factory_make("x264enc", "kvs_encoder");
    kvs_parse = gst_element_factory_make("h264parse", "kvs_parse");
    kvs_caps = gst_element_factory_make("capsfilter", "kvs_caps");
    kvs_sink = gst_element_factory_make("kvssink", "kvs_sink");

    // Check if all elements were created successfully
    if (!source || !capsfilter || !convert || !tee ||
        !webrtc_queue || !webrtc_convert || !webrtc_encoder || !webrtc_caps || !webrtc_appsink ||
        !kvs_queue || !kvs_convert || !kvs_encoder || !kvs_parse || !kvs_caps || !kvs_sink) {
        g_printerr("Not all elements could be created. Exiting.\n");
        goto CleanUp;
    }

    // Configure common elements
    caps = gst_caps_from_string("video/x-raw,width=640,height=480,framerate=25/1");
    g_object_set(capsfilter, "caps", caps, NULL);
    gst_caps_unref(caps);

    // Configure WebRTC branch - using settings from kvsWebrtcClientMasterGstSample.c
    g_object_set(webrtc_encoder, 
                "bframes", 0,
                "speed-preset", 1,  // veryfast
                "bitrate", 150,
                "byte-stream", TRUE,
                "tune", 0x04,       // zerolatency
                NULL);
    
    webrtc_h264_caps = gst_caps_from_string("video/x-h264,stream-format=byte-stream,alignment=au,profile=baseline");
    g_object_set(webrtc_caps, "caps", webrtc_h264_caps, NULL);
    gst_caps_unref(webrtc_h264_caps);

    g_object_set(webrtc_appsink, "sync", TRUE, "emit-signals", TRUE, NULL);
    g_signal_connect(webrtc_appsink, "new-sample", G_CALLBACK(on_new_webrtc_sample), pSampleConfiguration);

    // Configure KVS branch - using default settings that worked before
    g_object_set(kvs_encoder, 
                "bitrate", 150,
                "key-int-max", 45,
                "tune", 0x00000004,  // zerolatency
                NULL);
    
    kvs_h264_caps = gst_caps_from_string("video/x-h264,profile=baseline");
    g_object_set(kvs_caps, "caps", kvs_h264_caps, NULL);
    gst_caps_unref(kvs_h264_caps);

    // Configure KVS sink
    g_object_set(kvs_sink, "stream-name", pStreamName, NULL);

    // Add all elements to the pipeline
    gst_bin_add_many(GST_BIN(pipeline), source, capsfilter, convert, tee, NULL);
    
    // Add WebRTC branch elements
    gst_bin_add_many(GST_BIN(pipeline), 
                     webrtc_queue, webrtc_convert, webrtc_encoder, webrtc_caps, webrtc_appsink, 
                     NULL);
    
    // Add KVS branch elements
    gst_bin_add_many(GST_BIN(pipeline), 
                     kvs_queue, kvs_convert, kvs_encoder, kvs_parse, kvs_caps, kvs_sink, 
                     NULL);

    // Link common elements
    if (!gst_element_link_many(source, capsfilter, convert, tee, NULL)) {
        g_printerr("Common elements could not be linked. Exiting.\n");
        goto CleanUp;
    }

    // Link WebRTC branch
    if (!gst_element_link_many(webrtc_queue, webrtc_convert, webrtc_encoder, webrtc_caps, webrtc_appsink, NULL)) {
        g_printerr("WebRTC branch elements could not be linked. Exiting.\n");
        goto CleanUp;
    }
    
    tee_webrtc_pad = gst_element_get_request_pad(tee, "src_%u");
    webrtc_queue_pad = gst_element_get_static_pad(webrtc_queue, "sink");
    if (gst_pad_link(tee_webrtc_pad, webrtc_queue_pad) != GST_PAD_LINK_OK) {
        g_printerr("WebRTC branch could not be linked to tee. Exiting.\n");
        goto CleanUp;
    }
    gst_object_unref(webrtc_queue_pad);

    // Link KVS branch
    if (!gst_element_link_many(kvs_queue, kvs_convert, kvs_encoder, kvs_parse, kvs_caps, kvs_sink, NULL)) {
        g_printerr("KVS branch elements could not be linked. Exiting.\n");
        goto CleanUp;
    }
    
    tee_kvs_pad = gst_element_get_request_pad(tee, "src_%u");
    kvs_queue_pad = gst_element_get_static_pad(kvs_queue, "sink");
    if (gst_pad_link(tee_kvs_pad, kvs_queue_pad) != GST_PAD_LINK_OK) {
        g_printerr("KVS branch could not be linked to tee. Exiting.\n");
        goto CleanUp;
    }
    gst_object_unref(kvs_queue_pad);

    // Set up bus watch
    bus = gst_element_get_bus(pipeline);
    gst_bus_add_watch(bus, bus_call, main_loop);
    gst_object_unref(bus);

    // Start the pipeline
    gst_element_set_state(pipeline, GST_STATE_PLAYING);
    printf("Pipeline started\n");

    // Start the main loop
    g_main_loop_run(main_loop);

    // Clean up the pipeline
    gst_element_set_state(pipeline, GST_STATE_NULL);
    if (tee_webrtc_pad != NULL) {
        gst_element_release_request_pad(tee, tee_webrtc_pad);
        gst_object_unref(tee_webrtc_pad);
    }
    if (tee_kvs_pad != NULL) {
        gst_element_release_request_pad(tee, tee_kvs_pad);
        gst_object_unref(tee_kvs_pad);
    }

CleanUp:
    if (pSampleConfiguration != NULL) {
        // Get signaling client metrics
        retStatus = signalingClientGetMetrics(pSampleConfiguration->signalingClientHandle, &signalingClientMetrics);
        if (retStatus == STATUS_SUCCESS) {
            printf("Signaling client metrics retrieved successfully\n");
        }

        // Cleanup resources
        if (IS_VALID_SIGNALING_CLIENT_HANDLE(pSampleConfiguration->signalingClientHandle)) {
            signalingClientDeleteSync(pSampleConfiguration->signalingClientHandle);
        }

        freeSampleConfiguration(&pSampleConfiguration);
    }

    printf("Cleanup done\n");

    if (pipeline != NULL) {
        gst_object_unref(GST_OBJECT(pipeline));
    }

    if (main_loop != NULL) {
        g_main_loop_unref(main_loop);
    }

    deinitKvsWebRtc();

    return (retStatus == STATUS_SUCCESS) ? EXIT_SUCCESS : EXIT_FAILURE;
}