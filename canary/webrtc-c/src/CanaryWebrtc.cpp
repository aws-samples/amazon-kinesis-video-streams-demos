#include "Include.h"
#include <Samples.h>
STATUS onNewConnection(Canary::PPeer);
STATUS run(Canary::PConfig);
VOID runPeer(Canary::PConfig, TIMER_QUEUE_HANDLE, STATUS*);
VOID sendLocalFrames(Canary::PPeer, MEDIA_STREAM_TRACK_KIND, const std::string&, UINT64, UINT32);
VOID sendCustomFrames(Canary::PPeer, MEDIA_STREAM_TRACK_KIND, UINT64, UINT64);
VOID sendProfilingMetrics(Canary::PPeer);
STATUS canaryRtpOutboundStats(UINT32, UINT64, UINT64);
STATUS canaryRtpInboundStats(UINT32, UINT64, UINT64);
STATUS canaryEndToEndStats(UINT32, UINT64, UINT64);
STATUS canaryKvsStats(UINT32, UINT64, UINT64);

std::atomic<bool> terminated;
VOID handleSignal(INT32 signal)
{
    UNUSED_PARAM(signal);
    terminated = TRUE;
}

PVOID sampleReceiveAudioVideoFrame(PVOID args)
{
    STATUS retStatus = STATUS_SUCCESS;
    PSampleStreamingSession pSampleStreamingSession = (PSampleStreamingSession) args;
    CHK_ERR(pSampleStreamingSession != NULL, STATUS_NULL_ARG, "[KVS Master] Streaming session is NULL");
    CHK_STATUS(transceiverOnFrame(pSampleStreamingSession->pVideoRtcRtpTransceiver, (UINT64) pSampleStreamingSession, sampleVideoFrameHandler));
    CHK_STATUS(transceiverOnFrame(pSampleStreamingSession->pAudioRtcRtpTransceiver, (UINT64) pSampleStreamingSession, sampleAudioFrameHandler));

    CleanUp:

    return (PVOID) (ULONG_PTR) retStatus;
}

PVOID sendVideoPackets(PVOID args)
{
    STATUS retStatus = STATUS_SUCCESS;
    PDemoConfiguration pDemoConfiguration = (PDemoConfiguration) args;
    RtcEncoderStats encoderStats;
    Frame frame;
    UINT32 fileIndex = 0, frameSize;
    CHAR filePath[MAX_PATH_LEN + 1];
    STATUS status;
    UINT32 i;
    UINT64 startTime, lastFrameTime, elapsed;
    MEMSET(&encoderStats, 0x00, SIZEOF(RtcEncoderStats));
    CHK_ERR(pDemoConfiguration != NULL, STATUS_NULL_ARG, "[KVS Master] Streaming session is NULL");

    frame.presentationTs = 0;
    startTime = GETTIME();
    lastFrameTime = startTime;

    while (!ATOMIC_LOAD_BOOL(&pDemoConfiguration->appTerminateFlag)) {
        fileIndex = fileIndex % NUMBER_OF_H264_FRAME_FILES + 1;
        SNPRINTF(filePath, MAX_PATH_LEN, "./assets/h264SampleFrames/frame-%04d.h264", fileIndex);

        CHK_STATUS(readFrameFromDisk(NULL, &frameSize, filePath));

        // Re-alloc if needed
        if (frameSize > pDemoConfiguration->appMediaCtx.videoBufferSize) {
            pDemoConfiguration->appMediaCtx.pVideoFrameBuffer = (PBYTE) MEMREALLOC(pDemoConfiguration->appMediaCtx.pVideoFrameBuffer, frameSize);
            CHK_ERR(pDemoConfiguration->appMediaCtx.pVideoFrameBuffer != NULL, STATUS_NOT_ENOUGH_MEMORY,
                    "[KVS Master] Failed to allocate video frame buffer");
            pDemoConfiguration->appMediaCtx.videoBufferSize = frameSize;
        }

        frame.frameData = pDemoConfiguration->appMediaCtx.pVideoFrameBuffer;
        frame.size = frameSize;

        CHK_STATUS(readFrameFromDisk(frame.frameData, &frameSize, filePath));

        // based on bitrate of samples/h264SampleFrames/frame-*
        encoderStats.width = 640;
        encoderStats.height = 480;
        encoderStats.targetBitrate = 262000;
        frame.presentationTs += SAMPLE_VIDEO_FRAME_DURATION;
        MUTEX_LOCK(pDemoConfiguration->streamingSessionListReadLock);
        for (i = 0; i < pDemoConfiguration->streamingSessionCount; ++i) {
            status = writeFrame(pDemoConfiguration->sampleStreamingSessionList[i]->pVideoRtcRtpTransceiver, &frame);
            if (pDemoConfiguration->sampleStreamingSessionList[i]->firstFrame && status == STATUS_SUCCESS) {
                PROFILE_WITH_START_TIME(pDemoConfiguration->sampleStreamingSessionList[i]->offerReceiveTime, "Time to first frame");
                pDemoConfiguration->sampleStreamingSessionList[i]->firstFrame = FALSE;
            }
            encoderStats.encodeTimeMsec = 4; // update encode time to an arbitrary number to demonstrate stats update
            updateEncoderStats(pDemoConfiguration->sampleStreamingSessionList[i]->pVideoRtcRtpTransceiver, &encoderStats);
            if (status != STATUS_SRTP_NOT_READY_YET) {
                if (status != STATUS_SUCCESS) {
                    DLOGV("writeFrame() failed with 0x%08x", status);
                }
            } else {
                // Reset file index to ensure first frame sent upon SRTP ready is a key frame.
                fileIndex = 0;
            }
        }
        MUTEX_UNLOCK(pDemoConfiguration->streamingSessionListReadLock);

        // Adjust sleep in the case the sleep itself and writeFrame take longer than expected. Since sleep makes sure that the thread
        // will be paused at least until the given amount, we can assume that there's no too early frame scenario.
        // Also, it's very unlikely to have a delay greater than SAMPLE_VIDEO_FRAME_DURATION, so the logic assumes that this is always
        // true for simplicity.
        elapsed = lastFrameTime - startTime;
        THREAD_SLEEP(SAMPLE_VIDEO_FRAME_DURATION - elapsed % SAMPLE_VIDEO_FRAME_DURATION);
        lastFrameTime = GETTIME();
    }

    CleanUp:
    DLOGI("[KVS Master] Closing video thread");
    CHK_LOG_ERR(retStatus);

    return (PVOID) (ULONG_PTR) retStatus;
}

PVOID sendAudioPackets(PVOID args)
{
    STATUS retStatus = STATUS_SUCCESS;
    PDemoConfiguration pDemoConfiguration = (PDemoConfiguration) args;
    Frame frame;
    UINT32 fileIndex = 0, frameSize;
    CHAR filePath[MAX_PATH_LEN + 1];
    UINT32 i;
    STATUS status;

    CHK_ERR(pDemoConfiguration != NULL, STATUS_NULL_ARG, "[KVS Master] Streaming session is NULL");
    frame.presentationTs = 0;

    while (!ATOMIC_LOAD_BOOL(&pDemoConfiguration->appTerminateFlag)) {
        fileIndex = fileIndex % NUMBER_OF_OPUS_FRAME_FILES + 1;
        SNPRINTF(filePath, MAX_PATH_LEN, "./assets/opusSampleFrames/sample-%03d.opus", fileIndex);

        CHK_STATUS(readFrameFromDisk(NULL, &frameSize, filePath));

        // Re-alloc if needed
        if (frameSize > pDemoConfiguration->appMediaCtx.audioBufferSize) {
            pDemoConfiguration->appMediaCtx.pAudioFrameBuffer = (UINT8*) MEMREALLOC(pDemoConfiguration->appMediaCtx.pAudioFrameBuffer, frameSize);
            CHK_ERR(pDemoConfiguration->appMediaCtx.pAudioFrameBuffer != NULL, STATUS_NOT_ENOUGH_MEMORY,
                    "[KVS Master] Failed to allocate audio frame buffer");
            pDemoConfiguration->appMediaCtx.audioBufferSize = frameSize;
        }

        frame.frameData = pDemoConfiguration->appMediaCtx.pAudioFrameBuffer;
        frame.size = frameSize;

        CHK_STATUS(readFrameFromDisk(frame.frameData, &frameSize, filePath));

        frame.presentationTs += SAMPLE_AUDIO_FRAME_DURATION;

        MUTEX_LOCK(pDemoConfiguration->streamingSessionListReadLock);
        for (i = 0; i < pDemoConfiguration->streamingSessionCount; ++i) {
            status = writeFrame(pDemoConfiguration->sampleStreamingSessionList[i]->pAudioRtcRtpTransceiver, &frame);
            if (status != STATUS_SRTP_NOT_READY_YET) {
                if (status != STATUS_SUCCESS) {
                    DLOGV("writeFrame() failed with 0x%08x", status);
                } else if (pDemoConfiguration->sampleStreamingSessionList[i]->firstFrame && status == STATUS_SUCCESS) {
                    PROFILE_WITH_START_TIME(pDemoConfiguration->sampleStreamingSessionList[i]->offerReceiveTime, "Time to first frame");
                    pDemoConfiguration->sampleStreamingSessionList[i]->firstFrame = FALSE;
                }
            } else {
                // Reset file index to stay in sync with video frames.
                fileIndex = 0;
            }
        }
        MUTEX_UNLOCK(pDemoConfiguration->streamingSessionListReadLock);
        THREAD_SLEEP(SAMPLE_AUDIO_FRAME_DURATION);
    }

    CleanUp:
    DLOGI("[KVS Master] closing audio thread");
    return (PVOID) (ULONG_PTR) retStatus;
}

// add frame pts, original frame size, CRC to beginning of buffer after Annex-B format NALu
VOID addCanaryMetadataToFrameData(PBYTE buffer, PFrame pFrame)
{
    PBYTE pCurPtr = buffer + ANNEX_B_NALU_SIZE;
    putUnalignedInt64BigEndian((PINT64) pCurPtr, pFrame->presentationTs);
    pCurPtr += SIZEOF(UINT64);
    putUnalignedInt32BigEndian((PINT32) pCurPtr, pFrame->size);
    pCurPtr += SIZEOF(UINT32);
    putUnalignedInt32BigEndian((PINT32) pCurPtr, COMPUTE_CRC32(buffer, pFrame->size));
}

// Frame Data format: NALu (4 bytes) + Header (PTS, Size (including header), CRC (frame data) + Randomly generated frameBits

// TODO: Support dynamic random frame sizes to bring it closer to real time scenarios
VOID createCanaryFrameData(PBYTE buffer, PFrame pFrame)
{
    UINT32 i;
    // For decoding purposes, the first 4 bytes need to be a NALu
    putUnalignedInt32BigEndian((PINT32) buffer, 0x00000001);
    for (i = ANNEX_B_NALU_SIZE + CANARY_METADATA_SIZE; i < pFrame->size; i++) {
        buffer[i] = RAND();
    }
    addCanaryMetadataToFrameData(buffer, pFrame);
}

STATUS initFromEnvs(UINT64 sampleConfigHandle, SIGNALING_CHANNEL_ROLE_TYPE roleType, INT32 argc, CHAR* argv[]) {
    STATUS retStatus = STATUS_SUCCESS;
    PDemoConfiguration pDemoConfiguration = (PDemoConfiguration) sampleConfigHandle;
    Canary::Config config;
    Canary::Config::Value<UINT64> logLevel64;
    std::stringstream defaultLogStreamName;
    UINT64 fileSize;

    /* This is ignored for master. Master can extract the info from offer. Viewer has to know if peer can trickle or
    * not ahead of time. */
    CHK_STATUS(config.optenvBool(CANARY_TRICKLE_ICE_ENV_VAR, &config.trickleIce, TRUE));
    CHK_STATUS(config.optenvBool(CANARY_USE_TURN_ENV_VAR, &config.useTurn, TRUE));
    CHK_STATUS(config.optenvBool(CANARY_FORCE_TURN_ENV_VAR, &config.forceTurn, FALSE));
    CHK_STATUS(config.optenvBool(CANARY_USE_IOT_CREDENTIALS_ENV_VAR, &config.useIotCredentialProvider, FALSE));
    CHK_STATUS(config.optenvBool(CANARY_RUN_IN_PROFILING_MODE_ENV_VAR, &config.isProfilingMode, FALSE));

    CHK_STATUS(config.optenv(CACERT_PATH_ENV_VAR, &config.caCertPath, KVS_CA_CERT_PATH));


    pDemoConfiguration->useIot = config.useIotCredentialProvider.value; // TODO: Handle the envs setting for IoT to match sample
    CHK_STATUS(config.optenv(CANARY_CHANNEL_NAME_ENV_VAR, &config.channelName, CANARY_DEFAULT_CHANNEL_NAME));
    CHK_STATUS(config.optenv(SESSION_TOKEN_ENV_VAR, &config.sessionToken, ""));
    CHK_STATUS(config.optenv(DEFAULT_REGION_ENV_VAR, &config.region, DEFAULT_AWS_REGION));

    CHK_STATUS(config.optenv(CANARY_ENDPOINT_ENV_VAR, &config.endpoint, ""));
    CHK_STATUS(config.optenv(CANARY_LABEL_ENV_VAR, &config.label, CANARY_DEFAULT_LABEL));

    CHK_STATUS(config.optenv(CANARY_CLIENT_ID_ENV_VAR, &config.clientId, CANARY_DEFAULT_CLIENT_ID));
    CHK_STATUS(config.optenvBool(CANARY_IS_MASTER_ENV_VAR, &config.isMaster, TRUE));
    CHK_STATUS(config.optenvBool(CANARY_RUN_BOTH_PEERS_ENV_VAR, &config.runBothPeers, FALSE));

    CHK_STATUS(config.optenv(CANARY_LOG_GROUP_NAME_ENV_VAR, &config.logGroupName, CANARY_DEFAULT_LOG_GROUP_NAME));
    defaultLogStreamName << config.channelName.value << '-' << (config.isMaster.value ? "master" : "viewer") << '-'
                         << GETTIME() / HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
    CHK_STATUS(config.optenv(CANARY_LOG_STREAM_NAME_ENV_VAR, &config.logStreamName, defaultLogStreamName.str()));

    if (!config.duration.initialized) {
        CHK_STATUS(config.optenvUint64(CANARY_DURATION_IN_SECONDS_ENV_VAR, &config.duration, 0));
        config.duration.value *= HUNDREDS_OF_NANOS_IN_A_SECOND;
    }

    // Iteration duration is an optional param
    if (!config.iterationDuration.initialized) {
        CHK_STATUS(config.optenvUint64(CANARY_ITERATION_IN_SECONDS_ENV_VAR, &config.iterationDuration, CANARY_DEFAULT_ITERATION_DURATION_IN_SECONDS));
        config.iterationDuration.value *= HUNDREDS_OF_NANOS_IN_A_SECOND;
    }

    CHK_STATUS(config.optenvUint64(CANARY_BIT_RATE_ENV_VAR, &config.bitRate, CANARY_DEFAULT_BITRATE));
    CHK_STATUS(config.optenvUint64(CANARY_FRAME_RATE_ENV_VAR, &config.frameRate, CANARY_DEFAULT_FRAMERATE));

    if (config.isStorage) {
        CHK_STATUS(config.optenv(STORAGE_CANARY_FIRST_FRAME_TS_FILE_ENV_VAR, &config.storageFristFrameSentTSFileName, STORAGE_CANARY_DEFAULT_FIRST_FRAME_TS_FILE));
    }
//
//    /* This is ignored for master. Master can extract the info from offer. Viewer has to know if peer can trickle or
//     * not ahead of time. */
    pDemoConfiguration->appConfigCtx.trickleIce = config.trickleIce.value;
    pDemoConfiguration->appConfigCtx.useTurn = config.useTurn.value;
    if(config.isMaster.value) {
        pDemoConfiguration->appConfigCtx.roleType = SIGNALING_CHANNEL_ROLE_TYPE_MASTER;
    } else {
        pDemoConfiguration->appConfigCtx.roleType = SIGNALING_CHANNEL_ROLE_TYPE_VIEWER;
    }
    pDemoConfiguration->appSignalingCtx.channelInfo.useMediaStorage = FALSE;
    pDemoConfiguration->appConfigCtx.pChannelName = (PCHAR) MEMCALLOC(1, STRLEN(config.channelName.value.c_str()+ 1));
    DLOGI("Channel name: %s", config.channelName.value.c_str());
    STRCPY(pDemoConfiguration->appConfigCtx.pChannelName, config.channelName.value.c_str());
CleanUp:
    return retStatus;
}

INT32 main(INT32 argc, CHAR* argv[])
{
    STATUS retStatus = STATUS_SUCCESS;
    PDemoConfiguration pDemoConfiguration = NULL;
    TimerTaskConfiguration statsconfig;
    // Make sure that all destructors have been called first before resetting the instrumented allocators
    CHK_STATUS([&]() -> STATUS {
        STATUS retStatus = STATUS_SUCCESS;

        Aws::SDKOptions options;
        Aws::InitAPI(options);

        CHK_STATUS(initializeConfiguration(&pDemoConfiguration, SIGNALING_CHANNEL_ROLE_TYPE_MASTER, argc, argv, initFromEnvs));
        DLOGI("Channel name: %s", pDemoConfiguration->appConfigCtx.pChannelName);
        CHK_STATUS(enablePregenerateCertificate(pDemoConfiguration));
        CHK_STATUS(initializeMediaSenders(pDemoConfiguration, sendAudioPackets, sendVideoPackets));
        CHK_STATUS(initializeMediaReceivers(pDemoConfiguration, sampleReceiveAudioVideoFrame));
        CHK_STATUS(initSignaling(pDemoConfiguration, (PCHAR) SAMPLE_MASTER_CLIENT_ID));
        statsconfig.startTime = SAMPLE_STATS_DURATION;
        statsconfig.iterationTime = SAMPLE_STATS_DURATION;
        statsconfig.timerCallbackFunc = getIceCandidatePairStatsCallback;
        statsconfig.customData = (UINT64) pDemoConfiguration;
        CHK_STATUS(addTaskToTimerQueue(pDemoConfiguration, &statsconfig));
        // Checking for termination
        CHK_STATUS(sessionCleanupWait(pDemoConfiguration));

    CleanUp:

        Aws::ShutdownAPI(options);

        return retStatus;
    }());

CleanUp:

    if (STATUS_FAILED(RESET_INSTRUMENTED_ALLOCATORS())) {
        DLOGE("FOUND MEMORY LEAK");
    }

    // https://www.gnu.org/software/libc/manual/html_node/Exit-Status.html
    // We can only return with 0 - 127. Some platforms treat exit code >= 128
    // to be a success code, which might give an unintended behaviour.
    // Some platforms also treat 1 or 0 differently, so it's better to use
    // EXIT_FAILURE and EXIT_SUCCESS macros for portability.
    return STATUS_FAILED(retStatus) ? EXIT_FAILURE : EXIT_SUCCESS;
}

// This is not a time sensitive thread. It is ok if pushing the metrics gets delayed by 100 ms because of the
// thread sleep
VOID sendProfilingMetrics(Canary::PPeer pPeer)
{
    STATUS retStatus = STATUS_SUCCESS;
    BOOL done = FALSE;
    while (!terminated.load()) {
        retStatus = pPeer->sendProfilingMetrics();
        if (retStatus == STATUS_WAITING_ON_FIRST_FRAME) {
            THREAD_SLEEP(HUNDREDS_OF_NANOS_IN_A_MILLISECOND * 100); // to prevent busy waiting
        } else if (retStatus == STATUS_SUCCESS) {
            DLOGI("First frame sent out, pushed profiling metrics");
            done = TRUE;
            break;
        }
    }
    if(!done) {
        DLOGE("First frame never got sent out...no profiling metrics pushed to cloudwatch..error code: 0x%08x", retStatus);
    }
}

STATUS onNewConnection(Canary::PPeer pPeer)
{
    STATUS retStatus = STATUS_SUCCESS;
    RtcMediaStreamTrack videoTrack, audioTrack;

    MEMSET(&videoTrack, 0x00, SIZEOF(RtcMediaStreamTrack));
    MEMSET(&audioTrack, 0x00, SIZEOF(RtcMediaStreamTrack));

    // Declare that we support H264,Profile=42E01F,level-asymmetry-allowed=1,packetization-mode=1 and Opus
    CHK_STATUS(pPeer->addSupportedCodec(RTC_CODEC_H264_PROFILE_42E01F_LEVEL_ASYMMETRY_ALLOWED_PACKETIZATION_MODE));
    CHK_STATUS(pPeer->addSupportedCodec(RTC_CODEC_OPUS));

    // Add a SendRecv Transceiver of type video
    videoTrack.kind = MEDIA_STREAM_TRACK_KIND_VIDEO;
    videoTrack.codec = RTC_CODEC_H264_PROFILE_42E01F_LEVEL_ASYMMETRY_ALLOWED_PACKETIZATION_MODE;
    STRCPY(videoTrack.streamId, "myKvsVideoStream");
    STRCPY(videoTrack.trackId, "myVideoTrack");
    CHK_STATUS(pPeer->addTransceiver(videoTrack));

    // Add a SendRecv Transceiver of type video
    audioTrack.kind = MEDIA_STREAM_TRACK_KIND_AUDIO;
    audioTrack.codec = RTC_CODEC_OPUS;
    STRCPY(audioTrack.streamId, "myKvsVideoStream");
    STRCPY(audioTrack.trackId, "myAudioTrack");
    CHK_STATUS(pPeer->addTransceiver(audioTrack));

CleanUp:

    return retStatus;
}

STATUS canaryRtpOutboundStats(UINT32 timerId, UINT64 currentTime, UINT64 customData)
{
    UNUSED_PARAM(timerId);
    UNUSED_PARAM(currentTime);
    STATUS retStatus = STATUS_SUCCESS;
    if (!terminated.load()) {
        Canary::PPeer pPeer = (Canary::PPeer) customData;
        pPeer->publishStatsForCanary(RTC_STATS_TYPE_OUTBOUND_RTP);
    } else {
        retStatus = STATUS_TIMER_QUEUE_STOP_SCHEDULING;
    }

    return retStatus;
}

STATUS canaryRtpInboundStats(UINT32 timerId, UINT64 currentTime, UINT64 customData)
{
    UNUSED_PARAM(timerId);
    UNUSED_PARAM(currentTime);
    STATUS retStatus = STATUS_SUCCESS;
    if (!terminated.load()) {
        Canary::PPeer pPeer = (Canary::PPeer) customData;
        pPeer->publishStatsForCanary(RTC_STATS_TYPE_INBOUND_RTP);
    } else {
        retStatus = STATUS_TIMER_QUEUE_STOP_SCHEDULING;
    }

    return retStatus;
}

STATUS canaryEndToEndStats(UINT32 timerId, UINT64 currentTime, UINT64 customData)
{
    UNUSED_PARAM(timerId);
    UNUSED_PARAM(currentTime);
    STATUS retStatus = STATUS_SUCCESS;
    if (!terminated.load()) {
        Canary::PPeer pPeer = (Canary::PPeer) customData;
        pPeer->publishEndToEndMetrics();
    } else {
        retStatus = STATUS_TIMER_QUEUE_STOP_SCHEDULING;
    }

    return retStatus;
}

STATUS canaryKvsStats(UINT32 timerId, UINT64 currentTime, UINT64 customData)
{
    UNUSED_PARAM(timerId);
    UNUSED_PARAM(currentTime);
    STATUS retStatus = STATUS_SUCCESS;
    if (!terminated.load()) {
        Canary::PPeer pPeer = (Canary::PPeer) customData;
        pPeer->publishRetryCount();
    } else {
        retStatus = STATUS_TIMER_QUEUE_STOP_SCHEDULING;
    }

    return retStatus;
}