#include "Include.h"

STATUS onNewConnection(Canary::PPeer);
STATUS run(Canary::PConfig);
VOID runPeer(Canary::PConfig, TIMER_QUEUE_HANDLE, STATUS*);
VOID sendLocalFrames(Canary::PPeer, MEDIA_STREAM_TRACK_KIND, const std::string&, UINT64, UINT32);
VOID sendCustomFrames(Canary::PPeer, MEDIA_STREAM_TRACK_KIND, UINT64, UINT64);
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

INT32 main(INT32 argc, CHAR* argv[])
{
#ifndef _WIN32
    signal(SIGINT, handleSignal);
#endif

    STATUS retStatus = STATUS_SUCCESS;
    initializeEndianness();
    SET_INSTRUMENTED_ALLOCATORS();
    SRAND(time(0));
    // Make sure that all destructors have been called first before resetting the instrumented allocators
    CHK_STATUS([&]() -> STATUS {
        STATUS retStatus = STATUS_SUCCESS;
        auto config = Canary::Config();

        Aws::SDKOptions options;
        Aws::InitAPI(options);

        CHK_STATUS(config.init(argc, argv));
        CHK_STATUS(run(&config));

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

STATUS run(Canary::PConfig pConfig)
{
    STATUS retStatus = STATUS_SUCCESS;
    BOOL initialized = FALSE;
    TIMER_QUEUE_HANDLE timerQueueHandle = 0;
    UINT32 timeoutTimerId;

    CHK_STATUS(Canary::Cloudwatch::init(pConfig));
    CHK_STATUS(initKvsWebRtc());
    initialized = TRUE;

    SET_LOGGER_LOG_LEVEL(pConfig->logLevel.value);

    CHK_STATUS(timerQueueCreate(&timerQueueHandle));

    if (pConfig->duration.value != 0) {
        auto terminate = [](UINT32 timerId, UINT64 currentTime, UINT64 customData) -> STATUS {
            UNUSED_PARAM(timerId);
            UNUSED_PARAM(currentTime);
            UNUSED_PARAM(customData);
            terminated = TRUE;
            return STATUS_TIMER_QUEUE_STOP_SCHEDULING;
        };
        CHK_STATUS(timerQueueAddTimer(timerQueueHandle, pConfig->duration.value, TIMER_QUEUE_SINGLE_INVOCATION_PERIOD, terminate, (UINT64) NULL,
                                      &timeoutTimerId));
    }

    if (!pConfig->runBothPeers.value) {
        runPeer(pConfig, timerQueueHandle, &retStatus);
    } else {
        // Modify config to differentiate master and viewer
        UINT64 timestamp = GETTIME() / HUNDREDS_OF_NANOS_IN_A_SECOND;
        STATUS masterRetStatus;
        std::stringstream ss;

        Canary::Config masterConfig = *pConfig;
        masterConfig.isMaster.value = TRUE;

        ss << pConfig->clientId.value << "Master";
        masterConfig.clientId.value = ss.str();
        ss.str("");

        ss << pConfig->channelName.value << "-master-" << timestamp;
        masterConfig.logStreamName.value = ss.str();
        ss.str("");

        Canary::Config viewerConfig = *pConfig;
        viewerConfig.isMaster.value = FALSE;

        ss << pConfig->clientId.value << "Viewer";
        viewerConfig.clientId.value = ss.str();
        ss.str("");

        ss << pConfig->channelName.value << "-viewer-" << timestamp;
        viewerConfig.logStreamName.value = ss.str();
        ss.str("");

        std::thread masterThread(runPeer, &masterConfig, timerQueueHandle, &masterRetStatus);
        THREAD_SLEEP(CANARY_DEFAULT_VIEWER_INIT_DELAY);

        runPeer(&viewerConfig, timerQueueHandle, &retStatus);
        masterThread.join();

        retStatus = STATUS_FAILED(retStatus) ? retStatus : masterRetStatus;
    }

CleanUp:

    if (IS_VALID_TIMER_QUEUE_HANDLE(timerQueueHandle)) {
        timerQueueFree(&timerQueueHandle);
    }

    DLOGI("Exiting with 0x%08x", retStatus);
    if (initialized) {
        Canary::Cloudwatch::getInstance().monitoring.pushExitStatus(retStatus);
    }

    deinitKvsWebRtc();
    Canary::Cloudwatch::deinit();

    return retStatus;
}

VOID runPeer(Canary::PConfig pConfig, TIMER_QUEUE_HANDLE timerQueueHandle, STATUS* pRetStatus)
{
    STATUS retStatus = STATUS_SUCCESS;
    UINT32 timeoutTimerId;

    Canary::Peer::Callbacks callbacks;
    callbacks.onNewConnection = onNewConnection;
    callbacks.onDisconnected = []() { terminated = TRUE; };

    Canary::Peer peer;

    CHK(pConfig != NULL, STATUS_NULL_ARG);

    pConfig->print();
    CHK_STATUS(timerQueueAddTimer(timerQueueHandle, KVS_METRICS_INVOCATION_PERIOD, KVS_METRICS_INVOCATION_PERIOD,
                                  canaryKvsStats, (UINT64) &peer, &timeoutTimerId));
    CHK_STATUS(peer.init(pConfig, callbacks));
    CHK_STATUS(peer.connect());

    {
        // Since the goal of the canary is to test robustness of the SDK, there is not an immediate need
        // to send audio frames as well. It can always be added in if needed in the future
        //        std::thread videoThread(sendCustomFrames, &peer, MEDIA_STREAM_TRACK_KIND_VIDEO, pConfig->bitRate.value, pConfig->frameRate.value);
        std::thread videoThread(sendLocalFrames, &peer, MEDIA_STREAM_TRACK_KIND_VIDEO, "./samples/h264SampleFrames/frame-%04d.h264",
                                NUMBER_OF_H264_FRAME_FILES, SAMPLE_VIDEO_FRAME_DURATION);
        // All metrics tracking will happen on a time queue to simplify handling periodicity
        CHK_STATUS(timerQueueAddTimer(timerQueueHandle, METRICS_INVOCATION_PERIOD, METRICS_INVOCATION_PERIOD, canaryRtpOutboundStats, (UINT64) &peer,
                                      &timeoutTimerId));
        CHK_STATUS(timerQueueAddTimer(timerQueueHandle, METRICS_INVOCATION_PERIOD, METRICS_INVOCATION_PERIOD, canaryRtpInboundStats, (UINT64) &peer,
                                      &timeoutTimerId));
        CHK_STATUS(timerQueueAddTimer(timerQueueHandle, END_TO_END_METRICS_INVOCATION_PERIOD, END_TO_END_METRICS_INVOCATION_PERIOD,
                                      canaryEndToEndStats, (UINT64) &peer, &timeoutTimerId));
        videoThread.join();
    }
    CHK_STATUS(peer.jointSession());

    CHK_STATUS(peer.shutdown());

CleanUp:

    *pRetStatus = retStatus;
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

VOID sendCustomFrames(Canary::PPeer pPeer, MEDIA_STREAM_TRACK_KIND kind, UINT64 dataRate, UINT64 frameRate)
{
    STATUS retStatus = STATUS_SUCCESS;
    Frame frame;
    UINT32 hexStrLen = 0;
    UINT32 actualFrameSize = 0;
    UINT32 frameSizeWithoutNalu = 0;
    // This is the actual frame size that includes the metadata and the actual frame data
    actualFrameSize = CANARY_METADATA_SIZE + ((dataRate / 8) / frameRate);
    frameSizeWithoutNalu = actualFrameSize - ANNEX_B_NALU_SIZE;

    PBYTE canaryFrameData = NULL;
    canaryFrameData = (PBYTE) MEMALLOC(actualFrameSize);

    // We allocate a bigger buffer to accomodate the hex encoded string
    frame.frameData = (PBYTE) MEMALLOC(frameSizeWithoutNalu * 2 + 1 + ANNEX_B_NALU_SIZE);
    frame.version = FRAME_CURRENT_VERSION;
    frame.presentationTs = GETTIME();

    while (!terminated.load()) {
        frame.size = actualFrameSize;
        createCanaryFrameData(canaryFrameData, &frame);

        // Hex encode the data (without the ANNEX-B NALu) to ensure parts of random frame data is not skipped if they
        // are the same as the ANNEX-B NALu
        CHK_STATUS(hexEncode(frame.frameData + ANNEX_B_NALU_SIZE, frameSizeWithoutNalu, NULL, &hexStrLen));

        // This re-alloc is done in case the estimated size does not match the actual requirement.
        // We do not want to constantly malloc within a loop. Hence, we re-allocate only if required
        // Either ways, the realloc should not happen
        if (hexStrLen != (frameSizeWithoutNalu * 2 + 1)) {
            DLOGW("Re allocating...this should not happen...something might be wrong");
            frame.frameData = (PBYTE) REALLOC(frame.frameData, hexStrLen + ANNEX_B_NALU_SIZE);
            CHK_ERR(frame.frameData != NULL, STATUS_NOT_ENOUGH_MEMORY, "Failed to realloc media buffer");
        }
        CHK_STATUS(hexEncode(canaryFrameData + ANNEX_B_NALU_SIZE, frameSizeWithoutNalu, (PCHAR)(frame.frameData + ANNEX_B_NALU_SIZE), &hexStrLen));
        MEMCPY(frame.frameData, canaryFrameData, ANNEX_B_NALU_SIZE);

        // We must update the size to reflect the original data with hex encoded data
        frame.size = hexStrLen + ANNEX_B_NALU_SIZE;
        pPeer->writeFrame(&frame, kind);
        THREAD_SLEEP(HUNDREDS_OF_NANOS_IN_A_SECOND / frameRate);
        frame.presentationTs = GETTIME();
    }
CleanUp:

    SAFE_MEMFREE(frame.frameData);
    SAFE_MEMFREE(canaryFrameData);

    auto threadKind = kind == MEDIA_STREAM_TRACK_KIND_VIDEO ? "video" : "audio";
    if (STATUS_FAILED(retStatus)) {
        DLOGE("%s thread exited with 0x%08x", threadKind, retStatus);
    } else {
        DLOGI("%s thread exited successfully", threadKind);
    }
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

VOID sendLocalFrames(Canary::PPeer pPeer, MEDIA_STREAM_TRACK_KIND kind, const std::string& pattern, UINT64 frameCount, UINT32 frameDuration)
{
    STATUS retStatus = STATUS_SUCCESS;
    Frame frame;
    UINT64 fileIndex = 0, frameSize;
    CHAR filePath[MAX_PATH_LEN + 1];
    UINT64 startTime, lastFrameTime, elapsed;

    frame.frameData = NULL;
    frame.size = 0;
    frame.presentationTs = 0;
    startTime = GETTIME();
    lastFrameTime = startTime;

    while (!terminated.load()) {
        fileIndex = fileIndex % frameCount + 1;
        SNPRINTF(filePath, MAX_PATH_LEN, pattern.c_str(), fileIndex);

        CHK_STATUS(readFile(filePath, TRUE, NULL, &frameSize));

        // Re-alloc if needed
        if (frameSize > frame.size) {
            frame.frameData = (PBYTE) REALLOC(frame.frameData, frameSize);
            CHK_ERR(frame.frameData != NULL, STATUS_NOT_ENOUGH_MEMORY, "Failed to realloc media buffer");
        }
        frame.size = (UINT32) frameSize;

        CHK_STATUS(readFile(filePath, TRUE, frame.frameData, &frameSize));

        frame.presentationTs += frameDuration;

        pPeer->writeFrame(&frame, kind);


        // Adjust sleep in the case the sleep itself and writeFrame take longer than expected. Since sleep makes sure that the thread
        // will be paused at least until the given amount, we can assume that there's no too early frame scenario.
        // Also, it's very unlikely to have a delay greater than SAMPLE_VIDEO_FRAME_DURATION, so the logic assumes that this is always
        // true for simplicity.
        elapsed = lastFrameTime - startTime;
        THREAD_SLEEP(frameDuration - elapsed % frameDuration);
        lastFrameTime = GETTIME();
    }

CleanUp:

    SAFE_MEMFREE(frame.frameData);

    auto threadKind = kind == MEDIA_STREAM_TRACK_KIND_VIDEO ? "video" : "audio";
    if (STATUS_FAILED(retStatus)) {
        DLOGE("%s thread exited with 0x%08x", threadKind, retStatus);
    } else {
        DLOGI("%s thread exited successfully", threadKind);
    }
}
