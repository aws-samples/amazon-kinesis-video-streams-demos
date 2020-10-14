
#include "CanaryUtils.h"

volatile ATOMIC_BOOL sigCaptureInterrupt;

VOID sigintHandler(INT32 sigNum)
{
    ATOMIC_STORE_BOOL(&sigCaptureInterrupt, TRUE);
}

// add frame pts, frame index, original frame size, CRC to beginning of buffer
VOID addCanaryMetadataToFrameData(PFrame pFrame)
{
    PBYTE pCurPtr = pFrame->frameData;
    putUnalignedInt64BigEndian((PINT64) pCurPtr, pFrame->presentationTs / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
    pCurPtr += SIZEOF(UINT64);
    putUnalignedInt32BigEndian((PINT32) pCurPtr, pFrame->index);
    pCurPtr += SIZEOF(UINT32);
    putUnalignedInt32BigEndian((PINT32) pCurPtr, pFrame->size);
    pCurPtr += SIZEOF(UINT32);
    putUnalignedInt32BigEndian((PINT32) pCurPtr, COMPUTE_CRC32(pFrame->frameData, pFrame->size));
}

VOID createCanaryFrameData(PFrame pFrame)
{
    UINT32 i;

    for (i = CANARY_METADATA_SIZE; i < pFrame->size; i++) {
        pFrame->frameData[i] = RAND();
    }
    addCanaryMetadataToFrameData(pFrame);
}

VOID adjustStreamInfoToCanaryType(PStreamInfo pStreamInfo, PCHAR canaryType)
{
    if (0 == STRNCMP(canaryType, CANARY_TYPE_REALTIME, STRLEN(CANARY_TYPE_REALTIME))) {
        pStreamInfo->streamCaps.streamingType = STREAMING_TYPE_REALTIME;
    } else if (0 == STRNCMP(canaryType, CANARY_TYPE_OFFLINE, STRLEN(CANARY_TYPE_OFFLINE))) {
        pStreamInfo->streamCaps.streamingType = STREAMING_TYPE_OFFLINE;
    }
}
VOID getJsonValue(PBYTE params, jsmntok_t tokens, PCHAR param_str)
{
    PCHAR json_key = NULL;
    UINT32 params_val_length = 0;
    params_val_length = (UINT32)(tokens.end - tokens.start);
    json_key = (PCHAR) params + tokens.start;
    SNPRINTF(param_str, params_val_length + 1, "%s\n", json_key);
}

STATUS parseConfigFile(PCanaryConfig pCanaryConfig, PCHAR filePath)
{
    STATUS retStatus = STATUS_SUCCESS;

    UINT64 size;
    jsmn_parser parser;
    CHAR final_attr_str[256];
    int r;
    BYTE params[1024];

    CHK(pCanaryConfig != NULL, STATUS_NULL_ARG);
    CHK_STATUS(readFile(filePath, TRUE, NULL, &size));
    CHK_ERR(size < 1024, STATUS_INVALID_ARG_LEN, "File size too big. Max allowed is 1024 bytes");
    CHK_STATUS(readFile(filePath, TRUE, params, &size));

    jsmn_init(&parser);
    jsmntok_t tokens[256];

    r = jsmn_parse(&parser, (PCHAR) params, size, tokens, 256);

    for (UINT32 i = 1; i < (UINT32) r; i++) {
        if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, CANARY_STREAM_NAME_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], pCanaryConfig->streamNamePrefix);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, CANARY_TYPE_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], pCanaryConfig->canaryTypeStr);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, FRAGMENT_SIZE_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], final_attr_str);
            STRTOUI64(final_attr_str, NULL, 10, &pCanaryConfig->fragmentSizeInBytes);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, CANARY_DURATION_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], final_attr_str);
            STRTOUI64(final_attr_str, NULL, 10, &pCanaryConfig->canaryDuration);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, CANARY_BUFFER_DURATION_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], final_attr_str);
            STRTOUI64(final_attr_str, NULL, 10, &pCanaryConfig->bufferDuration);
            pCanaryConfig->bufferDuration = pCanaryConfig->bufferDuration * HUNDREDS_OF_NANOS_IN_A_SECOND;
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, CANARY_STORAGE_SIZE_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], final_attr_str);
            STRTOUI64(final_attr_str, NULL, 10, &pCanaryConfig->storageSizeInBytes);
            i++;
        }
        else if(compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, CANARY_LABEL_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], pCanaryConfig->canaryLabel);
            i++;
        }
    }
CleanUp:
    return retStatus;
}

STATUS optenv(PCHAR pKey, PCHAR pResult, PCHAR pDefault)
{
    STATUS retStatus = STATUS_SUCCESS;
    if (NULL == getenv(pKey)) {
        STRCPY(pResult, pDefault);
    } else {
        STRCPY(pResult, getenv(pKey));
    }
CleanUp:
    return retStatus;
}

STATUS optenvUint64(PCHAR pKey, PUINT64 val, UINT64 defVal)
{
    STATUS retStatus = STATUS_SUCCESS;
    PCHAR raw;
    UINT64 intVal;
    if (NULL == getenv(pKey)) {
        *val = defVal;
    } else {
        STRTOUI64((PCHAR) getenv(pKey), NULL, 10, val);
    }

CleanUp:
    return retStatus;
}

STATUS printConfig(PCanaryConfig pCanaryConfig)
{
    STATUS retStatus = STATUS_SUCCESS;
    DLOGI("Canary Stream name prefix: %s", pCanaryConfig->streamNamePrefix);
    DLOGI("Canary type: %s", pCanaryConfig->canaryTypeStr);
    DLOGI("Fragment size in bytes: %llu bytes", pCanaryConfig->fragmentSizeInBytes);
    DLOGI("Canary duration: %llu seconds", pCanaryConfig->canaryDuration);
    DLOGI("Canary buffer duration: %llu seconds", pCanaryConfig->bufferDuration);
    DLOGI("Canary storage size: %llu bytes", pCanaryConfig->storageSizeInBytes);
CleanUp:
    return retStatus;
}

STATUS initWithEnvVars(PCanaryConfig pCanaryConfig)
{
    STATUS retStatus = STATUS_SUCCESS;
    PCHAR type;
    CHAR canaryType[CANARY_TYPE_STR_LEN + 1];
    CHAR streamName[CANARY_STREAM_NAME_STR_LEN + 1];
    CHAR canaryLabel[CANARY_LABEL_LEN + 1];
    CHK(pCanaryConfig != NULL, STATUS_NULL_ARG);

    CHK_STATUS(optenv(CANARY_STREAM_NAME_ENV_VAR, streamName, CANARY_DEFAULT_STREAM_NAME));
    STRCPY(pCanaryConfig->streamNamePrefix, streamName);

    CHK_STATUS(optenv(CANARY_TYPE_ENV_VAR, canaryType, CANARY_DEFAULT_CANARY_TYPE));
    STRCPY(pCanaryConfig->canaryTypeStr, canaryType);

    CHK_STATUS(optenv(CANARY_LABEL_ENV_VAR, canaryLabel, CANARY_DEFAULT_CANARY_LABEL));
    STRCPY(pCanaryConfig->canaryLabel, canaryLabel);

    CHK_STATUS(optenvUint64(FRAGMENT_SIZE_ENV_VAR, &pCanaryConfig->fragmentSizeInBytes, CANARY_DEFAULT_FRAGMENT_SIZE));
    CHK_STATUS(optenvUint64(CANARY_DURATION_ENV_VAR, &pCanaryConfig->canaryDuration, CANARY_DEFAULT_DURATION_IN_SECONDS));

    CHK_STATUS(optenvUint64(CANARY_BUFFER_DURATION_ENV_VAR, &pCanaryConfig->bufferDuration, DEFAULT_BUFFER_DURATION));
    CHK_STATUS(optenvUint64(CANARY_STORAGE_SIZE_ENV_VAR, &pCanaryConfig->storageSizeInBytes, 0));
    printConfig(pCanaryConfig);

    pCanaryConfig->bufferDuration = pCanaryConfig->bufferDuration * HUNDREDS_OF_NANOS_IN_A_SECOND;
CleanUp:
    return retStatus;
}

INT32 main(INT32 argc, CHAR* argv[])
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
    PCHAR accessKey = NULL, secretKey = NULL, sessionToken = NULL, region = NULL, cacertPath = NULL;
    CHAR streamName[MAX_STREAM_NAME_LEN + 1];
    Frame frame;
    UINT32 frameIndex = 0, fileIndex = 0;
    UINT64 fragmentSizeInByte = 0;
    UINT64 lastKeyFrameTimestamp = 0;
    CloudwatchLogsObject cloudwatchLogsObject;
    PCanaryStreamCallbacks pCanaryStreamCallbacks = NULL;
    UINT64 currentTime, canaryStopTime;
    BOOL cleanUpDone = FALSE;
    BOOL fileLoggingEnabled = FALSE;
    BOOL runTillStopped = FALSE;

    CanaryConfig config;
    initializeEndianness();
    SRAND(time(0));

    Aws::SDKOptions options;
    Aws::InitAPI(options);
    {
        frame.frameData = NULL;

        if (argc < 2) {
            DLOGW("Optional Usage: %s <path-to-config-file>\n", argv[0]);
            DLOGD("Using environment variables now");
            DLOGD("Usage pattern:\n"
                  "\t\texport CANARY_STREAM_NAME=<val>\n"
                  "\t\texport CANARY_STREAM_TYPE=<realtime/offline>\n"
                  "\t\texport FRAGMENT_SIZE_IN_BYTES=<Size of fragment in bytes>\n"
                  "\t\texport CANARY_DURATION_IN_SECONDS=<duration in seconds>");
            CHK_STATUS(initWithEnvVars(&config));
        } else {
            CHK_ERR(STRLEN(argv[1]) < (MAX_PATH_LEN + 1), STATUS_INVALID_ARG_LEN, "File path length too long");
            CHK_STATUS(parseConfigFile(&config, argv[1]));
        }

        if ((accessKey = getenv(ACCESS_KEY_ENV_VAR)) == NULL || (secretKey = getenv(SECRET_KEY_ENV_VAR)) == NULL) {
            DLOGE("Error missing credentials");
            CHK(FALSE, STATUS_INVALID_ARG);
        }

        cacertPath = getenv(CACERT_PATH_ENV_VAR);
        sessionToken = getenv(SESSION_TOKEN_ENV_VAR);

        SNPRINTF(streamName, MAX_STREAM_NAME_LEN, "%s-%s-%s", config.streamNamePrefix, config.canaryTypeStr, config.canaryLabel);

        if ((region = getenv(DEFAULT_REGION_ENV_VAR)) == NULL) {
            region = (PCHAR) DEFAULT_AWS_REGION;
        }

        Aws::Client::ClientConfiguration clientConfiguration;
        clientConfiguration.region = region;
        Aws::CloudWatch::CloudWatchClient cw(clientConfiguration);

        Aws::CloudWatchLogs::CloudWatchLogsClient cwl(clientConfiguration);

        STRCPY(cloudwatchLogsObject.logGroupName, "ProducerSDK");
        SNPRINTF(cloudwatchLogsObject.logStreamName, MAX_LOG_FILE_NAME_LEN, "%s-log-%llu", streamName, GETTIME() / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
        cloudwatchLogsObject.pCwl = &cwl;
        if ((retStatus = initializeCloudwatchLogger(&cloudwatchLogsObject)) != STATUS_SUCCESS) {
            DLOGW("Cloudwatch logger failed to be initialized with 0x%08x error code. Fallback to file logging", retStatus);
            fileLoggingEnabled = TRUE;
        }

        // default storage size is 128MB. Use setDeviceInfoStorageSize after create to change storage size.
        CHK_STATUS(createDefaultDeviceInfo(&pDeviceInfo));

        if (config.storageSizeInBytes != 0) {
            CHK_STATUS(setDeviceInfoStorageSize(pDeviceInfo, config.storageSizeInBytes));
        }

        // adjust members of pDeviceInfo here if needed
        pDeviceInfo->clientInfo.loggerLogLevel = LOG_LEVEL_DEBUG;

        CHK_STATUS(createRealtimeVideoStreamInfoProvider(streamName, DEFAULT_RETENTION_PERIOD, config.bufferDuration, &pStreamInfo));
        adjustStreamInfoToCanaryType(pStreamInfo, config.canaryTypeStr);
        // adjust members of pStreamInfo here if needed
        pStreamInfo->streamCaps.nalAdaptationFlags = NAL_ADAPTATION_FLAG_NONE;

        CHK_STATUS(createDefaultCallbacksProviderWithAwsCredentials(accessKey, secretKey, sessionToken, MAX_UINT64, region, cacertPath, NULL, NULL,
                                                                    &pClientCallbacks));

        if (getenv(CANARY_APP_FILE_LOGGER) != NULL || fileLoggingEnabled) {
            if ((retStatus = addFileLoggerPlatformCallbacksProvider(pClientCallbacks, CANARY_FILE_LOGGING_BUFFER_SIZE, CANARY_MAX_NUMBER_OF_LOG_FILES,
                                                                    (PCHAR) FILE_LOGGER_LOG_FILE_DIRECTORY_PATH, TRUE) != STATUS_SUCCESS)) {
                DLOGE("File logging enable option failed with 0x%08x error code\n", retStatus);
                fileLoggingEnabled = FALSE;
            } else {
                fileLoggingEnabled = TRUE;
            }
        }

        CHK_STATUS(createCanaryStreamCallbacks(&cw, streamName, config.canaryLabel, &pCanaryStreamCallbacks));
        CHK_STATUS(addStreamCallbacks(pClientCallbacks, &pCanaryStreamCallbacks->streamCallbacks));

        if (!fileLoggingEnabled) {
            pClientCallbacks->logPrintFn = cloudWatchLogger;
        }

        CHK_STATUS(createKinesisVideoClient(pDeviceInfo, pClientCallbacks, &clientHandle));
        CHK_STATUS(createKinesisVideoStreamSync(clientHandle, pStreamInfo, &streamHandle));

        // setup dummy frame
        frame.size = CANARY_METADATA_SIZE + config.fragmentSizeInBytes / DEFAULT_FPS_VALUE;
        frame.frameData = (PBYTE) MEMALLOC(frame.size);
        frame.version = FRAME_CURRENT_VERSION;
        frame.trackId = DEFAULT_VIDEO_TRACK_ID;
        frame.duration = 0;
        frame.decodingTs = GETTIME(); // current time
        frame.presentationTs = frame.decodingTs;
        currentTime = GETTIME();
        canaryStopTime = currentTime + (config.canaryDuration * HUNDREDS_OF_NANOS_IN_A_SECOND);
        UINT64 duration;

        DLOGD("Producer SDK Log file name: %s", cloudwatchLogsObject.logStreamName);
        // Say, the canary needs to be stopped before designated canary run time, signal capture
        // must still be supported
        while (GETTIME() < canaryStopTime && ATOMIC_LOAD_BOOL(&sigCaptureInterrupt) != TRUE) {
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
                    duration = GETTIME() - currentTime;
                    if ((!fileLoggingEnabled) && (duration > (60 * HUNDREDS_OF_NANOS_IN_A_SECOND))) {
                        canaryStreamSendLogs(&cloudwatchLogsObject);
                        currentTime = GETTIME();
                        retStatus = publishErrorRate(streamHandle, pCanaryStreamCallbacks, duration);
                        if(STATUS_FAILED(retStatus)) {
                            DLOGW("Could not publish error rate. Failed with %08x", retStatus);
                        }
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
        if (!fileLoggingEnabled) {
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
    if (!cleanUpDone) {
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
    DLOGD("Exiting application with status code: 0x%08x", retStatus);
    return STATUS_FAILED(retStatus) ? EXIT_FAILURE : EXIT_SUCCESS;
}
