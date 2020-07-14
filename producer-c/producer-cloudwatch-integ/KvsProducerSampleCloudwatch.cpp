
#include "CanaryStreamUtils.h"

volatile ATOMIC_BOOL sigCaptureInterrupt;

VOID sigintHandler(INT32 sigNum)
{
    ATOMIC_STORE_BOOL(&sigCaptureInterrupt, TRUE);
}

// add frame pts, frame index, original frame size, CRC to beginning of buffer
VOID addCanaryMetadataToFrameData(PFrame pFrame) {
    PBYTE pCurPtr = pFrame->frameData;
    putUnalignedInt64BigEndian((PINT64)pCurPtr, pFrame->presentationTs / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
    pCurPtr += SIZEOF(UINT64);
    putUnalignedInt32BigEndian((PINT32)pCurPtr, pFrame->index);
    pCurPtr += SIZEOF(UINT32);
    putUnalignedInt32BigEndian((PINT32)pCurPtr, pFrame->size);
    pCurPtr += SIZEOF(UINT32);
    putUnalignedInt32BigEndian((PINT32)pCurPtr, COMPUTE_CRC32(pFrame->frameData, pFrame->size));
}

VOID createCanaryFrameData(PFrame pFrame) {
    UINT32 i;

    for (i = CANARY_METADATA_SIZE; i < pFrame->size; i++) {
        pFrame->frameData[i] = RAND();
    }
    addCanaryMetadataToFrameData(pFrame);
}

PCHAR getCanaryStr(UINT32 canaryType) {
    switch (canaryType) {
        case 0:
            return (PCHAR)"realtime";
        case 1:
            return (PCHAR)"offline";
        default:
            return (PCHAR)"";
    }
}

VOID adjustStreamInfoToCanaryType(PStreamInfo pStreamInfo, UINT32 canaryType) {
    switch (canaryType) {
        case 0:
            pStreamInfo->streamCaps.streamingType = STREAMING_TYPE_REALTIME;
            break;
        case 1:
            pStreamInfo->streamCaps.streamingType = STREAMING_TYPE_OFFLINE;
            break;
        default:
            break;
    }

}

INT32 main(INT32 argc, CHAR *argv[])
{
#ifndef _WIN32
    signal(SIGINT, sigintHandler);
#endif
    SET_INSTRUMENTED_ALLOCATORS();
    PDeviceInfo pDeviceInfo = NULL;
    PStreamInfo pStreamInfo = NULL;

    PClientCallbacks pClientCallbacks = NULL;
    CLIENT_HANDLE clientHandle = INVALID_CLIENT_HANDLE_VALUE;
    STREAM_HANDLE streamHandle = INVALID_STREAM_HANDLE_VALUE;
    STATUS retStatus = STATUS_SUCCESS;
    PCHAR accessKey = NULL, secretKey = NULL, sessionToken = NULL, streamNamePrefix = NULL, canaryTypeStr = NULL, region = NULL, cacertPath = NULL;
    CHAR streamName[MAX_STREAM_NAME_LEN + 1];
    Frame frame;
    UINT32 frameIndex = 0, fileIndex = 0, canaryType = 0;
    UINT64 fragmentSizeInByte = 0;
    UINT64 lastKeyFrameTimestamp = 0;
    CloudwatchLogsObject cloudwatchLogsObject;
    PCanaryStreamCallbacks pCanaryStreamCallbacks = NULL;
    UINT64 currentTime;
    BOOL cleanUpDone = FALSE;
    BOOL fileLoggingEnabled = FALSE;

    initializeEndianness();
    SRAND(time(0));
    Aws::SDKOptions options;
    Aws::InitAPI(options);
    {
        frame.frameData = NULL;

        if (argc < 3) {
            DLOGE("Usage: AWS_ACCESS_KEY_ID=SAMPLEKEY AWS_SECRET_ACCESS_KEY=SAMPLESECRET %s <stream_name_prefix> <canary_type> <bandwidth>\n", argv[0]);
            CHK(FALSE, STATUS_INVALID_ARG);
        }

        if ((accessKey = getenv(ACCESS_KEY_ENV_VAR)) == NULL || (secretKey = getenv(SECRET_KEY_ENV_VAR)) == NULL) {
            DLOGE("Error missing credentials");
            CHK(FALSE, STATUS_INVALID_ARG);
        }

        if (argc < 4) {
            fragmentSizeInByte = 1024 * 1024; // default to 1 MB
        } else {
            STRTOUI64(argv[3], NULL, 10, &fragmentSizeInByte);
        }

        cacertPath = getenv(CACERT_PATH_ENV_VAR);
        sessionToken = getenv(SESSION_TOKEN_ENV_VAR);
        streamNamePrefix = argv[1];
        STRTOUI32(argv[2], NULL, 10, &canaryType);;
        canaryTypeStr = getCanaryStr(canaryType);
        CHK(STRCMP(canaryTypeStr, "") != 0, STATUS_INVALID_ARG);
        SNPRINTF(streamName, MAX_STREAM_NAME_LEN, "%s-canary-%s-%s", streamNamePrefix, canaryTypeStr, argv[3]);

        if ((region = getenv(DEFAULT_REGION_ENV_VAR)) == NULL) {
            region = (PCHAR) DEFAULT_AWS_REGION;
        }

        ClientConfiguration clientConfiguration;
        clientConfiguration.region = region;
        CloudWatchClient cw(clientConfiguration);

        CloudWatchLogsClient cwl(clientConfiguration);

        STRCPY(cloudwatchLogsObject.logGroupName, "ProducerSDK");
        SNPRINTF(cloudwatchLogsObject.logStreamName, MAX_STREAM_NAME_LEN + 5, "%s-log", streamName);
        cloudwatchLogsObject.pCwl = &cwl;
        if((retStatus = initializeCloudwatchLogger(&cloudwatchLogsObject)) != STATUS_SUCCESS) {
            DLOGW("Cloudwatch logger failed to be initialized with 0x%08x error code. Fallback to file logging", retStatus);
            fileLoggingEnabled = TRUE;
        }

        // default storage size is 128MB. Use setDeviceInfoStorageSize after create to change storage size.
        CHK_STATUS(createDefaultDeviceInfo(&pDeviceInfo));
        // adjust members of pDeviceInfo here if needed
        pDeviceInfo->clientInfo.loggerLogLevel = LOG_LEVEL_DEBUG;

        CHK_STATUS(createRealtimeVideoStreamInfoProvider(streamName, DEFAULT_RETENTION_PERIOD, DEFAULT_BUFFER_DURATION, &pStreamInfo));
        adjustStreamInfoToCanaryType(pStreamInfo, canaryType);
        // adjust members of pStreamInfo here if needed
        pStreamInfo->streamCaps.nalAdaptationFlags = NAL_ADAPTATION_FLAG_NONE;

        CHK_STATUS(createDefaultCallbacksProviderWithAwsCredentials(accessKey,
                                                                    secretKey,
                                                                    sessionToken,
                                                                    MAX_UINT64,
                                                                    region,
                                                                    cacertPath,
                                                                    NULL,
                                                                    NULL,
                                                                    &pClientCallbacks));

        if(getenv(CANARY_APP_FILE_LOGGER) != NULL || fileLoggingEnabled) {
            if((retStatus = addFileLoggerPlatformCallbacksProvider(pClientCallbacks,
                                                                   CANARY_FILE_LOGGING_BUFFER_SIZE,
                                                                   CANARY_MAX_NUMBER_OF_LOG_FILES,
                                                                   (PCHAR) FILE_LOGGER_LOG_FILE_DIRECTORY_PATH,
                                                                   TRUE) != STATUS_SUCCESS)) {
                 DLOGE("File logging enable option failed with 0x%08x error code\n", retStatus);
                 fileLoggingEnabled = FALSE;
            }
            else {
                fileLoggingEnabled = TRUE;
            }
        }

        CHK_STATUS(createCanaryStreamCallbacks(&cw, streamName, &pCanaryStreamCallbacks));
        CHK_STATUS(addStreamCallbacks(pClientCallbacks, &pCanaryStreamCallbacks->streamCallbacks));

        if(!fileLoggingEnabled) {
            pClientCallbacks->logPrintFn = cloudWatchLogger;
        }

        CHK_STATUS(createKinesisVideoClient(pDeviceInfo, pClientCallbacks, &clientHandle));
        CHK_STATUS(createKinesisVideoStreamSync(clientHandle, pStreamInfo, &streamHandle));

        // setup dummy frame
        frame.size = CANARY_METADATA_SIZE + fragmentSizeInByte / DEFAULT_FPS_VALUE;
        frame.frameData = (PBYTE) MEMALLOC(frame.size);
        frame.version = FRAME_CURRENT_VERSION;
        frame.trackId = DEFAULT_VIDEO_TRACK_ID;
        frame.duration = 0;
        frame.decodingTs = GETTIME(); // current time
        frame.presentationTs = frame.decodingTs;
        currentTime = GETTIME();
        while (ATOMIC_LOAD_BOOL(&sigCaptureInterrupt) != TRUE) {
            if (frameIndex < 0) {
                frameIndex = 0;
            }
            frame.index = frameIndex;
            frame.flags = frameIndex % DEFAULT_KEY_FRAME_INTERVAL == 0 ? FRAME_FLAG_KEY_FRAME : FRAME_FLAG_NONE;

            createCanaryFrameData(&frame);
            if (frame.flags == FRAME_FLAG_KEY_FRAME) {
                if (lastKeyFrameTimestamp != 0) {
                    canaryStreamRecordFragmentEndSendTime(pCanaryStreamCallbacks, lastKeyFrameTimestamp, frame.presentationTs);
                    CHK_STATUS(computeStreamMetricsFromCanary(streamHandle, pCanaryStreamCallbacks));
                    CHK_STATUS(computeClientMetricsFromCanary(clientHandle, pCanaryStreamCallbacks));
                    currentMemoryAllocation(pCanaryStreamCallbacks);
                    if((!fileLoggingEnabled) && (GETTIME() > currentTime + (60 * HUNDREDS_OF_NANOS_IN_A_SECOND))) {
                        canaryStreamSendLogs(&cloudwatchLogsObject);
                        currentTime = GETTIME();
                    }
                }
                lastKeyFrameTimestamp = frame.presentationTs;
            }
            CHK_STATUS(putKinesisVideoFrame(streamHandle, &frame));

            THREAD_SLEEP(HUNDREDS_OF_NANOS_IN_A_SECOND / DEFAULT_FPS_VALUE);

            frame.decodingTs = GETTIME(); // current time
            frame.presentationTs = frame.decodingTs;
            frameIndex++;
        }
        CHK_LOG_ERR(retStatus);
        SAFE_MEMFREE(frame.frameData);

        freeDeviceInfo(&pDeviceInfo);
        freeStreamInfoProvider(&pStreamInfo);
        freeKinesisVideoStream(&streamHandle);
        freeKinesisVideoClient(&clientHandle);
        freeCallbacksProvider(&pClientCallbacks); // This will also take care of freeing canaryStreamCallbacks
        RESET_INSTRUMENTED_ALLOCATORS();
        DLOGI("CleanUp Done");
        cleanUpDone = TRUE;
        if(!fileLoggingEnabled) {
            // This is necessary to ensure that we do not lose the last set of logs
            canaryStreamSendLogSync(&cloudwatchLogsObject);
        }
    }
CleanUp:
    Aws::ShutdownAPI(options);
    CHK_LOG_ERR(retStatus);

    // canaryStreamSendLogSync() will lead to segfault outside the block scope
    // https://docs.aws.amazon.com/sdk-for-cpp/v1/developer-guide/basic-use.html
    // The clean up related logs also need to be captured in cloudwatch logs. This flag
    // will cater to two scenarios, one, if any of the commands fail, this clean up is invoked
    // Second, it will cater to scenarios where a SIG is sent to exit the while loop above in
    // which case the clean up related logs will be captured as well.
    if(!cleanUpDone) {
        CHK_LOG_ERR(retStatus);
        SAFE_MEMFREE(frame.frameData);

        freeDeviceInfo(&pDeviceInfo);
        freeStreamInfoProvider(&pStreamInfo);
        freeKinesisVideoStream(&streamHandle);
        freeKinesisVideoClient(&clientHandle);
        freeCallbacksProvider(&pClientCallbacks); // This will also take care of freeing canaryStreamCallbacks
        RESET_INSTRUMENTED_ALLOCATORS();
        DLOGI("CleanUp Done");
    }

    return (INT32) retStatus;
}
