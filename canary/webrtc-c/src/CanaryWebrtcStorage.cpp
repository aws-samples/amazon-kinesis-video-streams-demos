#include "Include.h"

STATUS onNewConnection(Canary::PPeer);
VOID calculateDisconnectToFrameSentTime();
STATUS run(Canary::PConfig);
VOID runPeer(Canary::PConfig, TIMER_QUEUE_HANDLE, STATUS*);
VOID sendLocalFrames(Canary::PPeer, MEDIA_STREAM_TRACK_KIND, const std::string&, UINT64, UINT32);
STATUS canaryRtpOutboundStats(UINT32, UINT64, UINT64);
STATUS canaryKvsStats(UINT32, UINT64, UINT64);
VOID sendProfilingMetrics(Canary::PPeer);

std::atomic<bool> needToReconnect;
std::atomic<bool> isTerminated;
std::atomic<UINT64> storageDisconnectedTime;

VOID handleSignal(INT32 signal)
{
    UNUSED_PARAM(signal);
    isTerminated = TRUE;
}


INT32 main(INT32 argc, CHAR* argv[])
{
#ifndef _WIN32
    signal(SIGINT, handleSignal);
#endif

    std::cout << "WEBRTC CANARY START TIME: " << GETTIME();

    STATUS retStatus = STATUS_SUCCESS;
    initializeEndianness();
    SET_INSTRUMENTED_ALLOCATORS();
    SRAND(time(0));
    // Make sure that all destructors have been called first before resetting the instrumented allocators
    CHK_STATUS([&]() -> STATUS {
        STATUS retStatus = STATUS_SUCCESS;
        auto config = Canary::Config();
        config.isStorage = true;	

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
            isTerminated = TRUE;
            DLOGD("TESTING : Terminated by timer");
            return STATUS_TIMER_QUEUE_STOP_SCHEDULING;
        };
        CHK_STATUS(timerQueueAddTimer(timerQueueHandle, pConfig->duration.value, TIMER_QUEUE_SINGLE_INVOCATION_PERIOD, terminate, (UINT64) NULL,
                                      &timeoutTimerId));
    }

    runPeer(pConfig, timerQueueHandle, &retStatus);

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
    callbacks.onDisconnected = []() {
        needToReconnect = TRUE;
        storageDisconnectedTime = GETTIME();
        };
    callbacks.calculateDisconnectToFrameSentTime = calculateDisconnectToFrameSentTime;

    
    BOOL firstIteration = TRUE;
    
    CHK(pConfig != NULL, STATUS_NULL_ARG);
    pConfig->print();


    while(!isTerminated.load())
    {
        DLOGD("TESTING : Entering runPeer while loop");

        Canary::Peer peer;

        CHK_STATUS(peer.init(pConfig, callbacks));
        CHK_STATUS(peer.connect());

        CHK_STATUS(timerQueueAddTimer(timerQueueHandle, KVS_METRICS_INVOCATION_PERIOD, KVS_METRICS_INVOCATION_PERIOD,
                                  canaryKvsStats, (UINT64) &peer, &timeoutTimerId));

        // TODO: ask why does this happen in it's own block?
        {
            if(needToReconnect)
            {
                DLOGD("TESTING : calling reconnectStorageSession");
                needToReconnect = FALSE;
            }

            DLOGD("TESTING : adding timerQueue");
            // All metrics tracking will happen on a time queue to simplify handling periodicity
            CHK_STATUS(timerQueueAddTimer(timerQueueHandle, METRICS_INVOCATION_PERIOD, METRICS_INVOCATION_PERIOD, canaryRtpOutboundStats, (UINT64) &peer,
                                        &timeoutTimerId));

            DLOGD("TESTING : making audio,video threads");
            std::thread videoThread(sendLocalFrames, &peer, MEDIA_STREAM_TRACK_KIND_VIDEO, "../assets/h264SampleFrames/frame-%04d.h264", NUMBER_OF_H264_FRAME_FILES, SAMPLE_VIDEO_FRAME_DURATION);
            std::thread audioThread(sendLocalFrames, &peer, MEDIA_STREAM_TRACK_KIND_AUDIO, "../assets/opusSampleFrames/sample-%03d.opus", NUMBER_OF_OPUS_FRAME_FILES, SAMPLE_AUDIO_FRAME_DURATION);
            
            if(firstIteration)
            {
                firstIteration = FALSE;
                DLOGD("TESTING : making pushProfilingThread thread");
                std::thread pushProfilingThread(sendProfilingMetrics, &peer);
                pushProfilingThread.join();
            }

            DLOGD("TESTING : joining with audio,video threads");
            videoThread.join();
            audioThread.join();
            DLOGD("TESTING : Audio,video threads stopped");
        }
        DLOGD("TESTING : Calling softShutdown()");
        CHK_STATUS(peer.softShutdown());
    }

CleanUp:
    *pRetStatus = retStatus;
}

STATUS onNewConnection(Canary::PPeer pPeer)
{
    DLOGD("TESTING : onNewConnection called");
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

    // Add a SendRecv Transceiver of type audio
    audioTrack.kind = MEDIA_STREAM_TRACK_KIND_AUDIO;
    audioTrack.codec = RTC_CODEC_OPUS;
    STRCPY(audioTrack.streamId, "myKvsVideoStream");
    STRCPY(audioTrack.trackId, "myAudioTrack");
    CHK_STATUS(pPeer->addTransceiver(audioTrack));

CleanUp:

    return retStatus;
}

VOID calculateDisconnectToFrameSentTime()
{
    if (storageDisconnectedTime.load() != 0){
        DOUBLE storageDisconnectToFrameSentTime = (DOUBLE) (GETTIME() - storageDisconnectedTime.load()) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
        Canary::Cloudwatch::getInstance().monitoring.pushStorageDisconnectToFrameSentTime(storageDisconnectToFrameSentTime,
                                                                        Aws::CloudWatch::Model::StandardUnit::Milliseconds);
        storageDisconnectedTime = 0;
    } else {
        DLOGE("Failed to send storageDisconnectToFrameSentTime metric, storageDisconnectedTime is zero (not set)");
    }
}

STATUS canaryRtpOutboundStats(UINT32 timerId, UINT64 currentTime, UINT64 customData)
{
    UNUSED_PARAM(timerId);
    UNUSED_PARAM(currentTime);
    STATUS retStatus = STATUS_SUCCESS;
    if (!isTerminated.load()) {
        Canary::PPeer pPeer = (Canary::PPeer) customData;
        pPeer->publishStatsForCanary(RTC_STATS_TYPE_OUTBOUND_RTP);
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
    if (!isTerminated.load()) {
        Canary::PPeer pPeer = (Canary::PPeer) customData;
        pPeer->publishRetryCount();
    } else {
        retStatus = STATUS_TIMER_QUEUE_STOP_SCHEDULING;
    }

    return retStatus;
}

VOID sendProfilingMetrics(Canary::PPeer pPeer)
{
    STATUS retStatus = STATUS_SUCCESS;
    BOOL done = FALSE;
    while (!needToReconnect.load() && !isTerminated.load()) {
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

    DLOGD("TESTING : entering while loop");
    while (!needToReconnect.load() && !isTerminated.load()) {
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
        
        STATUS writeFrameStatus = pPeer->writeFrame(&frame, kind);

        // Stay on first frame if SRTP not ready
        if(writeFrameStatus == STATUS_SRTP_NOT_READY_YET)
        {
            fileIndex = 0;
        }

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
