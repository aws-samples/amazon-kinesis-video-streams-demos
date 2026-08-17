/*******************************************
GStreamer media source for the media-server-storage canary.

Ported from the WebRTC C SDK sample media source (samples/common/GstMedia.c).
Frames arrive asynchronously through appsink "new-sample" callbacks and are
fanned out to every streaming session via writeFrame(), with the canary
CloudWatch metric hooks spliced in at the same points they sit in the
disk-frame path (sendVideoPackets/sendAudioPackets):
  - handleWriteFrameMetricIncrementation() before writeFrame() on video
  - first-frame block (writeFirstFrameSentTimeToFile, pushTimeToFirstFrame,
    pushJoinSSCallToFirstFrame, calculateDisconnectToFrameSentTime) on the
    first successful write per session
*******************************************/
#include "GstMedia.h"
#include "../Include.h"

static GstElement* senderPipeline = NULL;

// Shared first-frame metric block, identical to the one in
// sendVideoPackets/sendAudioPackets (kvsWebRTCClientMaster.cpp).
static VOID handleFirstFrameMetrics(PSampleConfiguration pSampleConfiguration, PSampleStreamingSession pSampleStreamingSession)
{
    writeFirstFrameSentTimeToFile((PCHAR) (std::string(FIRST_FRAME_TS_FILE_PATH) + pSampleConfiguration->fristFrameSentTSFileName).c_str());
    PROFILE_WITH_START_TIME(pSampleStreamingSession->offerReceiveTime, "Time to first frame");

    DOUBLE timeToFirstFrame = (DOUBLE) (GETTIME() - pSampleConfiguration->offerReceiveTimestamp) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
    DLOGD("[Canary] Start up latency from offer received to first frame sent (timeToFirstFrame): %lf ms", timeToFirstFrame);
    Canary::Cloudwatch::getInstance().monitoring.pushTimeToFirstFrame(timeToFirstFrame, Aws::CloudWatch::Model::StandardUnit::Milliseconds);

    // Push JoinSSCallToFirstFrame - time from JoinStorageSession call to first frame sent
    if (pSampleConfiguration->joinSSCallStartTime != 0) {
        UINT64 joinSSToFirstFrame = (GETTIME() - pSampleConfiguration->joinSSCallStartTime) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
        DLOGI("[Canary] JoinSSCallToFirstFrame: %" PRIu64 " ms", joinSSToFirstFrame);
        Canary::Cloudwatch::getInstance().monitoring.pushJoinSSCallToFirstFrame(joinSSToFirstFrame,
                                                                                Aws::CloudWatch::Model::StandardUnit::Milliseconds);
    }

    calculateDisconnectToFrameSentTime(pSampleConfiguration);

    pSampleStreamingSession->firstFrame = FALSE;
}

static GstFlowReturn on_new_sample(GstElement* sink, gpointer data, UINT64 trackid)
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

    info.data = NULL;

    CHK_ERR(pSampleConfiguration != NULL, STATUS_NULL_ARG, "NULL sample configuration");
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

            if (trackid == DEFAULT_AUDIO_TRACK_ID) {
                // TWCC actuator (Module 2): publish the live audio encoder bitrate for the
                // congestion callback, and apply any pending target it produced. bps.
                if (pSampleStreamingSession->pSampleConfiguration->enableTwcc && senderPipeline != NULL) {
                    GstElement* encoder = gst_bin_get_by_name(GST_BIN(senderPipeline), "sampleAudioEncoder");
                    if (encoder != NULL) {
                        guint bitrate = 0;
                        g_object_get(G_OBJECT(encoder), "bitrate", &bitrate, NULL);
                        MUTEX_LOCK(pSampleStreamingSession->twccMetadata.updateLock);
                        pSampleStreamingSession->twccMetadata.currentAudioBitrate = (UINT64) bitrate;
                        if (pSampleStreamingSession->twccMetadata.newAudioBitrate != 0) {
                            bitrate = (guint) pSampleStreamingSession->twccMetadata.newAudioBitrate;
                            pSampleStreamingSession->twccMetadata.newAudioBitrate = 0;
                            g_object_set(G_OBJECT(encoder), "bitrate", bitrate, NULL);
                        }
                        MUTEX_UNLOCK(pSampleStreamingSession->twccMetadata.updateLock);
                        gst_object_unref(encoder);
                    }
                }
                pRtcRtpTransceiver = pSampleStreamingSession->pAudioRtcRtpTransceiver;
                frame.presentationTs = pSampleStreamingSession->audioTimestamp;
                frame.decodingTs = frame.presentationTs;
                pSampleStreamingSession->audioTimestamp +=
                    SAMPLE_AUDIO_FRAME_DURATION; // assume audio frame size is 20ms, which is default in opusenc
            } else {
                // TWCC actuator (Module 2): same hand-off for video. x264enc "bitrate" is kbps.
                if (pSampleStreamingSession->pSampleConfiguration->enableTwcc && senderPipeline != NULL) {
                    GstElement* encoder = gst_bin_get_by_name(GST_BIN(senderPipeline), "sampleVideoEncoder");
                    if (encoder != NULL) {
                        guint bitrate = 0;
                        g_object_get(G_OBJECT(encoder), "bitrate", &bitrate, NULL);
                        MUTEX_LOCK(pSampleStreamingSession->twccMetadata.updateLock);
                        pSampleStreamingSession->twccMetadata.currentVideoBitrate = (UINT64) bitrate;
                        if (pSampleStreamingSession->twccMetadata.newVideoBitrate != 0) {
                            bitrate = (guint) pSampleStreamingSession->twccMetadata.newVideoBitrate;
                            pSampleStreamingSession->twccMetadata.newVideoBitrate = 0;
                            g_object_set(G_OBJECT(encoder), "bitrate", bitrate, NULL);
                        }
                        MUTEX_UNLOCK(pSampleStreamingSession->twccMetadata.updateLock);
                        gst_object_unref(encoder);
                    }
                }
                pRtcRtpTransceiver = pSampleStreamingSession->pVideoRtcRtpTransceiver;
                frame.presentationTs = pSampleStreamingSession->videoTimestamp;
                frame.decodingTs = frame.presentationTs;
                pSampleStreamingSession->videoTimestamp += SAMPLE_VIDEO_FRAME_DURATION; // assume video fps matches DEFAULT_FPS_VALUE

                // Canary hook: count frames/bytes generated (feeds discarded/retransmitted percentages)
                handleWriteFrameMetricIncrementation(pSampleStreamingSession, frame.size);
            }

            status = writeFrame(pRtcRtpTransceiver, &frame);
            if (status != STATUS_SRTP_NOT_READY_YET && status != STATUS_SUCCESS) {
                DLOGV("[KVS GStreamer Master] writeFrame() failed with 0x%08x", status);
            } else if (status == STATUS_SUCCESS && pSampleStreamingSession->firstFrame) {
                // Canary hook: first-frame latency metrics
                handleFirstFrameMetrics(pSampleConfiguration, pSampleStreamingSession);
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

GstFlowReturn on_new_sample_audio(GstElement* sink, gpointer data)
{
    return on_new_sample(sink, data, DEFAULT_AUDIO_TRACK_ID);
}

PVOID sendGstreamerAudioVideo(PVOID args)
{
    STATUS retStatus = STATUS_SUCCESS;
    GstElement *appsinkVideo = NULL, *appsinkAudio = NULL;
    GstBus* bus = NULL;
    GstMessage* msg = NULL;
    GError* gError = NULL;
    gchar* gDebug = NULL;
    GstStateChangeReturn stateChangeStatus = GST_STATE_CHANGE_SUCCESS;
    PSampleConfiguration pSampleConfiguration = (PSampleConfiguration) args;
    PCHAR pCustomPipeline;
    CHAR pipelineBuffer[GST_PIPELINE_MAX_CHAR_COUNT];

    CHK_ERR(pSampleConfiguration != NULL, STATUS_NULL_ARG, "[KVS Gstreamer Master] Streaming session is NULL");

    // A full custom pipeline overrides the built-in ones. Needed on hosts where
    // the built-in source elements don't pick up the camera (e.g. Raspberry Pi
    // CSI cameras want libcamerasrc, optionally with a hardware encoder).
    pCustomPipeline = GETENV(CANARY_GST_CUSTOM_PIPELINE_ENV_VAR);
    if (pCustomPipeline != NULL && pCustomPipeline[0] != '\0') {
        DLOGI("[KVS GStreamer Master] Using custom pipeline from %s: %s", CANARY_GST_CUSTOM_PIPELINE_ENV_VAR, pCustomPipeline);
        senderPipeline = gst_parse_launch(pCustomPipeline, &gError);
    } else {
        switch (pSampleConfiguration->srcType) {
            case TEST_SOURCE: {
                senderPipeline = gst_parse_launch(
                    "videotestsrc pattern=ball is-live=TRUE ! "
                    "queue ! videorate ! videoscale ! videoconvert ! video/x-raw,width=1280,height=720,framerate=30/1 ! "
                    "clockoverlay halignment=right valignment=top time-format=\"%Y-%m-%d %H:%M:%S\" ! "
                    "x264enc name=sampleVideoEncoder bframes=0 key-int-max=30 speed-preset=veryfast bitrate=512 byte-stream=TRUE tune=zerolatency ! "
                    "video/x-h264,stream-format=byte-stream,alignment=au,profile=baseline ! "
                    "appsink sync=TRUE emit-signals=TRUE name=appsink-video audiotestsrc wave=ticks is-live=TRUE ! "
                    "queue leaky=2 max-size-buffers=400 ! audioconvert ! audioresample ! opusenc name=sampleAudioEncoder ! "
                    "audio/x-opus,rate=48000,channels=2 ! appsink sync=TRUE emit-signals=TRUE name=appsink-audio",
                    &gError);
                break;
            }
            case DEVICE_SOURCE: {
                // NOTE: no videoconvert between the source and the caps filter. With the
                // Raspberry Pi camera (e.g. imx708 via libcamera), inserting videoconvert
                // breaks caps negotiation and the pipeline fails to start. The camera
                // already delivers a raw format x264enc accepts.
                senderPipeline = gst_parse_launch(
                    "autovideosrc ! queue ! video/x-raw,width=1280,height=720,framerate=30/1 ! "
                    "x264enc name=sampleVideoEncoder bframes=0 key-int-max=30 speed-preset=veryfast bitrate=512 byte-stream=TRUE tune=zerolatency ! "
                    "video/x-h264,stream-format=byte-stream,alignment=au,profile=baseline ! appsink sync=TRUE emit-signals=TRUE "
                    "name=appsink-video autoaudiosrc ! "
                    "queue leaky=2 max-size-buffers=400 ! audioconvert ! audioresample ! opusenc name=sampleAudioEncoder ! "
                    "audio/x-opus,rate=48000,channels=2 ! appsink sync=TRUE emit-signals=TRUE name=appsink-audio",
                    &gError);
                break;
            }
            case FILE_SOURCE: // file:// URI built in gstParseSrcTypeFromEnv; same uridecodebin pipeline as RTSP
            case RTSP_SOURCE: {
                UINT32 stringOutcome =
                    (UINT32) SNPRINTF(pipelineBuffer, GST_PIPELINE_MAX_CHAR_COUNT,
                                      "uridecodebin uri=%s name=src ! videoconvert ! "
                                      "x264enc name=sampleVideoEncoder bframes=0 key-int-max=30 speed-preset=veryfast bitrate=512 byte-stream=TRUE "
                                      "tune=zerolatency ! "
                                      "video/x-h264,stream-format=byte-stream,alignment=au,profile=baseline ! queue ! "
                                      "appsink sync=TRUE emit-signals=TRUE name=appsink-video "
                                      "src. ! audioconvert ! "
                                      "audioresample ! opusenc name=sampleAudioEncoder ! audio/x-opus,rate=48000,channels=2 ! queue ! "
                                      "appsink sync=TRUE emit-signals=TRUE name=appsink-audio",
                                      pSampleConfiguration->rtspUri);
                CHK_ERR(stringOutcome < GST_PIPELINE_MAX_CHAR_COUNT, STATUS_INVALID_OPERATION,
                        "[KVS GStreamer Master] RTSP uri entered exceeds maximum allowed length");
                senderPipeline = gst_parse_launch(pipelineBuffer, &gError);
                break;
            }
            case FRAME_SOURCE: {
                // Reuse the repo's pre-encoded frame sequence (same CANARY_ASSET_SET
                // selector as the disk path), but decode + re-encode LIVE so TWCC drives
                // the x264enc bitrate. multifilesrc reads frame-0001.h264, 0002, ... as
                // one byte-stream and loops at EOS (no run-duration limit). avdec_h264
                // needs gstreamer1.0-libav (installed by rpi-onboard.sh).
                PCHAR pAssetSet = GETENV((PCHAR) "CANARY_ASSET_SET");
                if (pAssetSet == NULL || pAssetSet[0] == '\0') {
                    pAssetSet = (PCHAR) "h264SampleFrames";
                }
                UINT32 stringOutcome =
                    (UINT32) SNPRINTF(pipelineBuffer, GST_PIPELINE_MAX_CHAR_COUNT,
                                      "multifilesrc location=./assets/%s/frame-%%04d.h264 index=1 loop=true "
                                      "caps=video/x-h264,stream-format=byte-stream,alignment=au,framerate=30/1 ! "
                                      "h264parse ! avdec_h264 ! videoconvert ! "
                                      "x264enc name=sampleVideoEncoder bframes=0 key-int-max=30 speed-preset=veryfast bitrate=512 "
                                      "byte-stream=TRUE tune=zerolatency ! "
                                      "video/x-h264,stream-format=byte-stream,alignment=au,profile=baseline ! "
                                      "appsink sync=TRUE emit-signals=TRUE name=appsink-video "
                                      "audiotestsrc wave=ticks is-live=TRUE ! queue leaky=2 max-size-buffers=400 ! audioconvert ! "
                                      "audioresample ! opusenc name=sampleAudioEncoder ! audio/x-opus,rate=48000,channels=2 ! "
                                      "appsink sync=TRUE emit-signals=TRUE name=appsink-audio",
                                      pAssetSet);
                CHK_ERR(stringOutcome < GST_PIPELINE_MAX_CHAR_COUNT, STATUS_INVALID_OPERATION,
                        "[KVS GStreamer Master] frame-source pipeline exceeds maximum allowed length");
                senderPipeline = gst_parse_launch(pipelineBuffer, &gError);
                break;
            }
        }
    }

    // When the senderPipeline is non-null and gError is non-null at the same time, it means
    // GStreamer encountered a "recoverable parsing error".
    if (gError != NULL) {
        DLOGE("[KVS GStreamer Master] Pipeline error: %s", gError->message);
        g_clear_error(&gError);
        retStatus = STATUS_INVALID_OPERATION;
        goto CleanUp;
    }
    CHK_ERR(senderPipeline != NULL, STATUS_NULL_ARG, "[KVS Gstreamer Master] Pipeline is NULL");

    appsinkVideo = gst_bin_get_by_name(GST_BIN(senderPipeline), "appsink-video");
    appsinkAudio = gst_bin_get_by_name(GST_BIN(senderPipeline), "appsink-audio");

    if (!(appsinkVideo != NULL || appsinkAudio != NULL)) {
        DLOGE("[KVS GStreamer Master] sendGstreamerAudioVideo(): cant find appsink, operation returned status code: 0x%08x", STATUS_INTERNAL_ERROR);
        goto CleanUp;
    }

    if (appsinkVideo != NULL) {
        g_signal_connect(appsinkVideo, "new-sample", G_CALLBACK(on_new_sample_video), (gpointer) pSampleConfiguration);
    }
    if (appsinkAudio != NULL) {
        g_signal_connect(appsinkAudio, "new-sample", G_CALLBACK(on_new_sample_audio), (gpointer) pSampleConfiguration);
    }
    stateChangeStatus = gst_element_set_state(senderPipeline, GST_STATE_PLAYING);
    DLOGD("[KVS GStreamer Master] State change null->playing returned status: %d", stateChangeStatus);
    CHK_ERR(stateChangeStatus != GST_STATE_CHANGE_FAILURE, STATUS_FORMAT_ERROR, "State change to PLAYING failed!");

    /* block until error or EOS */
    bus = gst_element_get_bus(senderPipeline);
    msg = gst_bus_timed_pop_filtered(bus, GST_CLOCK_TIME_NONE, (GstMessageType) (GST_MESSAGE_ERROR | GST_MESSAGE_EOS));

CleanUp:
    if (gError != NULL) {
        DLOGE("[KVS GStreamer Master] GStreamer error: %s", gError->message);
        g_clear_error(&gError);
        gError = NULL;
    }

    if (msg != NULL) {
        switch (GST_MESSAGE_TYPE(msg)) {
            case GST_MESSAGE_ERROR:
                gst_message_parse_error(msg, &gError, &gDebug);
                DLOGE("[KVS GStreamer Master] Received error from GStreamer: %s", gError->message);
                DLOGD("[KVS GStreamer Master] Received debug from GStreamer: %s", gDebug);
                g_clear_error(&gError);
                g_free(gDebug);
                gDebug = NULL;
                break;
            case GST_MESSAGE_EOS:
                DLOGI("[KVS GStreamer Master] Received GStreamer End-Of-Stream");
                break;
            default:
                DLOGE("[KVS GStreamer Master] Unhandled message type: %d", GST_MESSAGE_TYPE(msg));
                break;
        }
        gst_message_unref(msg);
        msg = NULL;
    }

    if (bus != NULL) {
        gst_object_unref(bus);
        bus = NULL;
    }
    if (senderPipeline != NULL) {
        stateChangeStatus = gst_element_set_state(senderPipeline, GST_STATE_NULL);
        if (stateChangeStatus == GST_STATE_CHANGE_FAILURE) {
            DLOGE("[KVS GStreamer Master] State change to NULL failed!");
        }
        gst_object_unref(senderPipeline);
        senderPipeline = NULL;
    }
    if (appsinkAudio != NULL) {
        gst_object_unref(appsinkAudio);
        appsinkAudio = NULL;
    }
    if (appsinkVideo != NULL) {
        gst_object_unref(appsinkVideo);
        appsinkVideo = NULL;
    }

    if (STATUS_SUCCEEDED(retStatus)) {
        DLOGI("[KVS GStreamer Master] Media sender thread exited gracefully");
    } else {
        DLOGE("[KVS GStreamer Master] Media sender thread exited with status: 0x%08x", retStatus);
    }

    return (PVOID) (ULONG_PTR) retStatus;
}

STATUS gstParseSrcTypeFromEnv(PSampleConfiguration pSampleConfiguration)
{
    ENTERS();
    STATUS retStatus = STATUS_SUCCESS;
    PCHAR pMediaSource, pRtspUri, pFile;

    CHK(pSampleConfiguration != NULL, STATUS_NULL_ARG);

    pSampleConfiguration->srcType = DEVICE_SOURCE;
    pMediaSource = GETENV(CANARY_MEDIA_SOURCE_ENV_VAR);
    CHK(pMediaSource != NULL, retStatus);

    if (STRCMP(pMediaSource, "testsrc") == 0) {
        DLOGI("[KVS GStreamer Master] Using test source in GStreamer");
        pSampleConfiguration->srcType = TEST_SOURCE;
    } else if (STRCMP(pMediaSource, "devicesrc") == 0) {
        DLOGI("[KVS GStreamer Master] Using device source (camera) in GStreamer");
        pSampleConfiguration->srcType = DEVICE_SOURCE;
    } else if (STRCMP(pMediaSource, "rtspsrc") == 0) {
        pRtspUri = GETENV(CANARY_RTSP_URI_ENV_VAR);
        CHK_ERR(pRtspUri != NULL && pRtspUri[0] != '\0', STATUS_INVALID_OPERATION,
                "[KVS GStreamer Master] %s must be set when %s=rtspsrc", CANARY_RTSP_URI_ENV_VAR, CANARY_MEDIA_SOURCE_ENV_VAR);
        DLOGI("[KVS GStreamer Master] Using RTSP source in GStreamer: %s", pRtspUri);
        pSampleConfiguration->srcType = RTSP_SOURCE;
        pSampleConfiguration->rtspUri = pRtspUri;
    } else if (STRCMP(pMediaSource, "filesrc") == 0) {
        // A local file is just a file:// URI into uridecodebin, so FILE_SOURCE reuses
        // the RTSP pipeline (decode -> re-encode via x264enc, so TWCC drives bitrate).
        static CHAR fileUri[GST_PIPELINE_MAX_CHAR_COUNT];
        pFile = GETENV(CANARY_GST_FILE_ENV_VAR);
        CHK_ERR(pFile != NULL && pFile[0] != '\0', STATUS_INVALID_OPERATION,
                "[KVS GStreamer Master] %s must be set (absolute path) when %s=filesrc", CANARY_GST_FILE_ENV_VAR, CANARY_MEDIA_SOURCE_ENV_VAR);
        SNPRINTF(fileUri, GST_PIPELINE_MAX_CHAR_COUNT, "file://%s", pFile);
        DLOGI("[KVS GStreamer Master] Using file source in GStreamer: %s", fileUri);
        pSampleConfiguration->srcType = FILE_SOURCE;
        pSampleConfiguration->rtspUri = fileUri;
    } else if (STRCMP(pMediaSource, "framesrc") == 0) {
        // Stream the repo's pre-encoded frame sequence, decoded + re-encoded live so
        // TWCC can drive the x264enc bitrate. Reuses CANARY_ASSET_SET (same selector
        // as the disk path). Pipeline built in the FRAME_SOURCE case below.
        DLOGI("[KVS GStreamer Master] Using frame-file source (multifilesrc, decode+re-encode) in GStreamer");
        pSampleConfiguration->srcType = FRAME_SOURCE;
    } else {
        DLOGI("[KVS GStreamer Master] Unrecognized %s value '%s'. Defaulting to device source", CANARY_MEDIA_SOURCE_ENV_VAR, pMediaSource);
    }

CleanUp:
    CHK_LOG_ERR(retStatus);

    LEAVES();
    return retStatus;
}

STATUS useGstreamer(PSampleConfiguration pSampleConfiguration)
{
    ENTERS();
    STATUS retStatus = STATUS_SUCCESS;

    CHK(pSampleConfiguration != NULL, STATUS_NULL_ARG);

    gst_init(NULL, NULL);

    CHK_STATUS(gstParseSrcTypeFromEnv(pSampleConfiguration));

    // One thread drives both appsinks; leave audioSource NULL so
    // mediaSenderRoutine only spawns the video sender thread.
    pSampleConfiguration->videoSource = sendGstreamerAudioVideo;
    pSampleConfiguration->audioSource = NULL;

CleanUp:
    CHK_LOG_ERR(retStatus);

    LEAVES();
    return retStatus;
}
