#include "Include.h"

STATUS onNewConnection(Canary::PPeer);
STATUS run(Canary::PConfig);
VOID sendLocalFrames(Canary::PPeer, MEDIA_STREAM_TRACK_KIND, const std::string&, UINT64, UINT32);

std::atomic<bool> terminated;
VOID handleSignal(INT32 signal)
{
    UNUSED_PARAM(signal);
    terminated = TRUE;
}

INT32 main(INT32 argc, CHAR* argv[])
{
#ifndef _WIN32
    signal(SIGINT, handleSignal);
#endif

    STATUS retStatus = STATUS_SUCCESS;
    SET_INSTRUMENTED_ALLOCATORS();

    // Make sure that all destructors have been called first before resetting the instrumented allocators
    CHK_STATUS([&]() -> STATUS {
        STATUS retStatus = STATUS_SUCCESS;
        Canary::Config config;

        Aws::SDKOptions options;
        Aws::InitAPI(options);

        CHK_STATUS(Canary::Config::init(argc, argv, &config));
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

    SET_LOGGER_LOG_LEVEL(pConfig->logLevel);
    pConfig->print();

    CHK_STATUS(timerQueueCreate(&timerQueueHandle));

    if (pConfig->duration != 0) {
        auto terminate = [](UINT32 timerId, UINT64 currentTime, UINT64 customData) -> STATUS {
            UNUSED_PARAM(timerId);
            UNUSED_PARAM(currentTime);
            UNUSED_PARAM(customData);
            terminated = TRUE;
            return STATUS_TIMER_QUEUE_STOP_SCHEDULING;
        };
        CHK_STATUS(
            timerQueueAddTimer(timerQueueHandle, pConfig->duration, TIMER_QUEUE_SINGLE_INVOCATION_PERIOD, terminate, (UINT64) NULL, &timeoutTimerId));
    }

    {
        Canary::Peer::Callbacks callbacks;
        callbacks.onNewConnection = onNewConnection;
        callbacks.onDisconnected = []() { terminated = TRUE; };

        RtcMediaStreamTrack videoTrack, audioTrack;

        Canary::Peer peer(pConfig, callbacks);
        CHK_STATUS(peer.init());
        CHK_STATUS(peer.connect());

        std::thread videoThread(sendLocalFrames, &peer, MEDIA_STREAM_TRACK_KIND_VIDEO, "./assets/h264SampleFrames/frame-%04d.h264",
                                NUMBER_OF_H264_FRAME_FILES, SAMPLE_VIDEO_FRAME_DURATION);
        std::thread audioThread(sendLocalFrames, &peer, MEDIA_STREAM_TRACK_KIND_AUDIO, "./assets/opusSampleFrames/sample-%03d.opus",
                                NUMBER_OF_OPUS_FRAME_FILES, SAMPLE_AUDIO_FRAME_DURATION);

        videoThread.join();
        audioThread.join();
        CHK_STATUS(peer.shutdown());
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
