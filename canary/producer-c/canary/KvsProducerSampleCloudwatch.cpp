
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

BOOL strtobool(const PCHAR value)
{
    if (STRCMPI(value, "on") == 0 || STRCMPI(value, "true") == 0) {
        return TRUE;
    }
    return FALSE;
}

VOID getJsonValue(PBYTE params, jsmntok_t tokens, PCHAR param_str)
{
    PCHAR json_key = NULL;
    UINT32 params_val_length = 0;
    params_val_length = (UINT32)(tokens.end - tokens.start);
    json_key = (PCHAR) params + tokens.start;
    SNPRINTF(param_str, params_val_length + 1, "%s\n", json_key);
}

VOID getJsonBoolValue(PBYTE params, jsmntok_t tokens, PBOOL pResult)
{
    PCHAR paramStr;
    getJsonValue(params, tokens, paramStr);
    *pResult = strtobool(paramStr);
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
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, CANARY_LABEL_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], pCanaryConfig->canaryLabel);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, CANARY_SCENARIO_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], pCanaryConfig->canaryScenario);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, CANARY_TRACK_TYPE_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], pCanaryConfig->canaryTrackType);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, (PCHAR) CANARY_USE_IOT_CREDENTIALS_ENV_VAR)) {
            getJsonBoolValue(params, tokens[++i], &pCanaryConfig->useIotCredentialProvider);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, (PCHAR) IOT_CORE_CREDENTIAL_ENDPOINT_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], pCanaryConfig->iotCoreCredentialEndPoint);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, (PCHAR) IOT_CORE_CERT_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], pCanaryConfig->iotCoreCert);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, (PCHAR) IOT_CORE_PRIVATE_KEY_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], pCanaryConfig->iotCorePrivateKey);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, (PCHAR) IOT_CORE_ROLE_ALIAS_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], pCanaryConfig->iotCoreRoleAlias);
            i++;
        } else if (compareJsonString((PCHAR) params, &tokens[i], JSMN_STRING, (PCHAR) IOT_CORE_THING_NAME_ENV_VAR)) {
            getJsonValue(params, tokens[i + 1], pCanaryConfig->iotThingName);
            i++;
        }
    }
CleanUp:
    return retStatus;
}

STATUS mustenv(PCHAR pKey, PCHAR pResult)
{
    STATUS retStatus = STATUS_SUCCESS;

    CHK(pResult != NULL, STATUS_NULL_ARG);

    CHK_ERR(getenv(pKey) != NULL, STATUS_INVALID_OPERATION, "%s must be set", pKey);
    STRCPY(pResult, getenv(pKey));

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

STATUS optenvBool(PCHAR pKey, PBOOL pResult, BOOL defVal)
{
    STATUS retStatus = STATUS_SUCCESS;
    BOOL intVal;

    if (NULL == getenv(pKey)) {
        *pResult = defVal;
    } else {
        *pResult = strtobool((PCHAR) getenv(pKey));
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
    DLOGI("Canary scenario: %s", pCanaryConfig->canaryScenario);
    DLOGI("Canary track type: %s", pCanaryConfig->canaryTrackType);
    DLOGI("Credential type: %s", pCanaryConfig->useIotCredentialProvider ? "IoT" : "Static");
    DLOGI("IOT Thing name: %s", pCanaryConfig->iotThingName);

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
    CHAR canaryScenario[CANARY_LABEL_LEN + 1];
    CHAR canaryTrackType[CANARY_TRACK_TYPE_STR_LEN + 1];
    CHK(pCanaryConfig != NULL, STATUS_NULL_ARG);
    UINT64 fileSize;

    CHK_STATUS(optenv(CANARY_STREAM_NAME_ENV_VAR, streamName, CANARY_DEFAULT_STREAM_NAME));
    STRCPY(pCanaryConfig->streamNamePrefix, streamName);

    CHK_STATUS(optenv(CANARY_SCENARIO_ENV_VAR, canaryScenario, CANARY_DEFAULT_SCENARIO_NAME));
    STRCPY(pCanaryConfig->canaryScenario, canaryScenario);

    CHK_STATUS(optenv(CANARY_TYPE_ENV_VAR, canaryType, CANARY_DEFAULT_CANARY_TYPE));
    STRCPY(pCanaryConfig->canaryTypeStr, canaryType);

    CHK_STATUS(optenv(CANARY_LABEL_ENV_VAR, canaryLabel, CANARY_DEFAULT_CANARY_LABEL));
    STRCPY(pCanaryConfig->canaryLabel, canaryLabel);

    CHK_STATUS(optenv(CANARY_TRACK_TYPE_ENV_VAR, canaryTrackType, CANARY_DEFAULT_TRACK_TYPE));
    STRCPY(pCanaryConfig->canaryTrackType, canaryTrackType);

    CHK_STATUS(optenvUint64(FRAGMENT_SIZE_ENV_VAR, &pCanaryConfig->fragmentSizeInBytes, CANARY_DEFAULT_FRAGMENT_SIZE));
    CHK_STATUS(optenvUint64(CANARY_DURATION_ENV_VAR, &pCanaryConfig->canaryDuration, CANARY_DEFAULT_DURATION_IN_SECONDS));

    CHK_STATUS(optenvUint64(CANARY_BUFFER_DURATION_ENV_VAR, &pCanaryConfig->bufferDuration, DEFAULT_BUFFER_DURATION));
    CHK_STATUS(optenvUint64(CANARY_STORAGE_SIZE_ENV_VAR, &pCanaryConfig->storageSizeInBytes, 0));

    CHK_STATUS(optenvBool(CANARY_USE_IOT_CREDENTIALS_ENV_VAR, &pCanaryConfig->useIotCredentialProvider, FALSE));

    pCanaryConfig->bufferDuration = pCanaryConfig->bufferDuration * HUNDREDS_OF_NANOS_IN_A_SECOND;

    if (pCanaryConfig->useIotCredentialProvider == TRUE) {
        CHK_STATUS(mustenv(IOT_CORE_CREDENTIAL_ENDPOINT_ENV_VAR, pCanaryConfig->iotCoreCredentialEndPoint));
        CHK_STATUS(readFile(pCanaryConfig->iotCoreCredentialEndPoint, TRUE, NULL, &fileSize));
        CHK_STATUS(readFile(pCanaryConfig->iotCoreCredentialEndPoint, TRUE,pCanaryConfig->iotEndpoint, &fileSize));
        pCanaryConfig->iotEndpoint[fileSize - 1] = '\0';
        CHK_STATUS(mustenv(IOT_CORE_CERT_ENV_VAR, pCanaryConfig->iotCoreCert));
        CHK_STATUS(mustenv(IOT_CORE_PRIVATE_KEY_ENV_VAR, pCanaryConfig->iotCorePrivateKey));
        CHK_STATUS(mustenv(IOT_CORE_ROLE_ALIAS_ENV_VAR, pCanaryConfig->iotCoreRoleAlias));
        CHK_STATUS(mustenv(IOT_CORE_THING_NAME_ENV_VAR, pCanaryConfig->iotThingName));
    }
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
    PCHAR accessKey = NULL, secretKey = NULL, sessionToken = NULL, region = NULL, cacertPath = NULL, logLevel;
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
    PAuthCallbacks pAuthCallbacks = NULL;
    CanaryConfig config;
    BOOL firstFrame = TRUE;
    UINT64 startTime;
    DOUBLE startUpLatency;
    UINT64 runTill = MAX_UINT64;
    UINT64 randomTime = 0;

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
                  "\t\texport CANARY_DURATION_IN_SECONDS=<duration in seconds>"
                  "\t\texport CANARY_BUFFER_DURATION_IN_SECONDS=<duration in seconds>"
                  "\t\texport CANARY_STORAGE_SIZE_IN_BYTES=<storage size in bytes>"
                  "\t\texport CANARY_LABEL=<canary label (longtime,periodic, etc >"
                  "\t\texport CANARY_RUN_SCENARIO=<canary label (normal/intermittent) >");
            CHK_STATUS(initWithEnvVars(&config));
        } else {
            CHK_ERR(STRLEN(argv[1]) < (MAX_PATH_LEN + 1), STATUS_INVALID_ARG_LEN, "File path length too long");
            CHK_STATUS(parseConfigFile(&config, argv[1]));
        }

        MEMSET(streamName, '\0', SIZEOF(streamName));
        cacertPath = getenv(CACERT_PATH_ENV_VAR);
        sessionToken = getenv(SESSION_TOKEN_ENV_VAR);

        if (config.useIotCredentialProvider) {
            STRCPY(streamName, config.iotThingName);
        } else {
            SNPRINTF(streamName, MAX_STREAM_NAME_LEN, "%s-%s-%s", config.streamNamePrefix, config.canaryTypeStr, config.canaryLabel);
        }

        if ((region = getenv(DEFAULT_REGION_ENV_VAR)) == NULL) {
            region = (PCHAR) DEFAULT_AWS_REGION;
        }

        Aws::Client::ClientConfiguration clientConfiguration;
        clientConfiguration.region = region;
        Aws::CloudWatch::CloudWatchClient cw(clientConfiguration);

        Aws::CloudWatchLogs::CloudWatchLogsClient cwl(clientConfiguration);

        STRCPY(cloudwatchLogsObject.logGroupName, "ProducerSDK");
        SNPRINTF(cloudwatchLogsObject.logStreamName, MAX_LOG_FILE_NAME_LEN, "%s-log-%llu", streamName,
                 GETTIME() / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
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
        logLevel = getenv(DEBUG_LOG_LEVEL_ENV_VAR);
        if (logLevel != NULL) {
            STRTOUI32(logLevel, NULL, 10, &pDeviceInfo->clientInfo.loggerLogLevel);
        }

        // Run multitrack only for intermittent producer scenario
        if (STRCMP(config.canaryTrackType, CANARY_MULTI_TRACK_TYPE) == 0) {
            CHK_STATUS(createRealtimeAudioVideoStreamInfoProvider(streamName, DEFAULT_RETENTION_PERIOD, config.bufferDuration, &pStreamInfo));
        } else {
            CHK_STATUS(createRealtimeVideoStreamInfoProvider(streamName, DEFAULT_RETENTION_PERIOD, config.bufferDuration, &pStreamInfo));
        }
        adjustStreamInfoToCanaryType(pStreamInfo, config.canaryTypeStr);
        // adjust members of pStreamInfo here if needed
        pStreamInfo->streamCaps.nalAdaptationFlags = NAL_ADAPTATION_FLAG_NONE;

        startTime = GETTIME();
        CHK_STATUS(createAbstractDefaultCallbacksProvider(DEFAULT_CALLBACK_CHAIN_COUNT, API_CALL_CACHE_TYPE_NONE,
                                                          ENDPOINT_UPDATE_PERIOD_SENTINEL_VALUE, region, EMPTY_STRING, cacertPath, NULL, NULL,
                                                          &pClientCallbacks));

        if (config.useIotCredentialProvider) {
            CHK_STATUS(createDefaultCallbacksProviderWithIotCertificate(PCHAR(config.iotEndpoint), config.iotCoreCert,
                                                                        config.iotCorePrivateKey, cacertPath, config.iotCoreRoleAlias, streamName,
                                                                        region, NULL, NULL, &pClientCallbacks));
        } else {
            if ((accessKey = getenv(ACCESS_KEY_ENV_VAR)) == NULL || (secretKey = getenv(SECRET_KEY_ENV_VAR)) == NULL) {
                DLOGE("Error missing credentials");
                CHK(FALSE, STATUS_INVALID_ARG);
            }
            CHK_STATUS(createDefaultCallbacksProviderWithAwsCredentials(accessKey, secretKey, sessionToken, MAX_UINT64, region, cacertPath, NULL,
                                                                        NULL, &pClientCallbacks));
        }
        PStreamCallbacks pStreamcallbacks = &pCanaryStreamCallbacks->streamCallbacks;
        CHK_STATUS(createContinuousRetryStreamCallbacks(pClientCallbacks, &pStreamcallbacks));

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
        CHK(frame.frameData != NULL, STATUS_NOT_ENOUGH_MEMORY);
        frame.version = FRAME_CURRENT_VERSION;
        frame.trackId = DEFAULT_VIDEO_TRACK_ID;
        frame.duration = HUNDREDS_OF_NANOS_IN_A_MILLISECOND / DEFAULT_FPS_VALUE;
        frame.decodingTs = GETTIME(); // current time
        frame.presentationTs = frame.decodingTs;
        currentTime = GETTIME();
        canaryStopTime = currentTime + (config.canaryDuration * HUNDREDS_OF_NANOS_IN_A_SECOND);
        UINT64 duration;

        DLOGD("Producer SDK Log file name: %s", cloudwatchLogsObject.logStreamName);

        printConfig(&config);

        // Check if we have continuous run or intermittent scenario
        if (STRCMP(config.canaryScenario, CANARY_INTERMITTENT_SCENARIO) == 0) {
            // Set up runTill. This will be used if canary is run under intermittent scenario
            randomTime = (RAND() % 10) + 1;
            runTill = GETTIME() + randomTime * HUNDREDS_OF_NANOS_IN_A_MINUTE;
            DLOGD("Intermittent run time is set to: %" PRIu64 " minutes", randomTime);
            pCanaryStreamCallbacks->aggregateMetrics = FALSE;
        }

        // Say, the canary needs to be stopped before designated canary run time, signal capture
        // must still be supported

        while (GETTIME() < canaryStopTime && ATOMIC_LOAD_BOOL(&sigCaptureInterrupt) != TRUE) {
            frame.index = frameIndex;
            frame.flags = frameIndex % DEFAULT_KEY_FRAME_INTERVAL == 0 ? FRAME_FLAG_KEY_FRAME : FRAME_FLAG_NONE;
            createCanaryFrameData(&frame);
            if (frame.flags == FRAME_FLAG_KEY_FRAME) {
                if (lastKeyFrameTimestamp != 0) {
                    canaryStreamRecordFragmentEndSendTime(pCanaryStreamCallbacks, lastKeyFrameTimestamp, frame.presentationTs);
                    publishMetrics(streamHandle, clientHandle, pCanaryStreamCallbacks);
                    duration = GETTIME() - currentTime;
                    if ((!fileLoggingEnabled) && (duration > (60 * HUNDREDS_OF_NANOS_IN_A_SECOND))) {
                        canaryStreamSendLogs(&cloudwatchLogsObject);
                        currentTime = GETTIME();
                        retStatus = publishErrorRate(streamHandle, pCanaryStreamCallbacks, duration);
                        if (STATUS_FAILED(retStatus)) {
                            DLOGW("Could not publish error rate. Failed with %08x", retStatus);
                        }
                    }
                }
                lastKeyFrameTimestamp = frame.presentationTs;
            }

            if (GETTIME() < runTill) {
                frame.trackId = DEFAULT_VIDEO_TRACK_ID;
                CHK_STATUS(putKinesisVideoFrame(streamHandle, &frame));

                // Send frame on another track only if we want to run multi track. For the sake of
                // multitrack, we use the same frame for video and audio and just modify the flags.
                if (STRCMP(config.canaryTrackType, CANARY_MULTI_TRACK_TYPE) == 0) {
                    frame.flags = FRAME_FLAG_NONE;
                    frame.trackId = DEFAULT_AUDIO_TRACK_ID;
                    CHK_STATUS(putKinesisVideoFrame(streamHandle, &frame));
                }
                THREAD_SLEEP(HUNDREDS_OF_NANOS_IN_A_SECOND / DEFAULT_FPS_VALUE);
            } else {
                canaryStreamRecordFragmentEndSendTime(pCanaryStreamCallbacks, lastKeyFrameTimestamp, frame.presentationTs);
                DLOGD("Last frame type put before stopping: %s", (frame.flags == FRAME_FLAG_KEY_FRAME ? "Key Frame" : "Non key frame"));
                UINT64 sleepTime = ((RAND() % 10) + 1) * HUNDREDS_OF_NANOS_IN_A_MINUTE;
                DLOGD("Intermittent sleep time is set to: %" PRIu64 " minutes", sleepTime / HUNDREDS_OF_NANOS_IN_A_MINUTE);
                THREAD_SLEEP(sleepTime);
                // Reset runTill after 1 run of intermittent scenario
                randomTime = (RAND() % 10) + 1;
                DLOGD("Intermittent run time is set to: %" PRIu64 " minutes", randomTime);
                runTill = GETTIME() + randomTime * HUNDREDS_OF_NANOS_IN_A_MINUTE;
            }
            // We measure this after first call to ensure that the latency is measured after the first SUCCESSFUL
            // putKinesisVideoFrame() call
            if (firstFrame) {
                startUpLatency = (DOUBLE)(GETTIME() - startTime) / (DOUBLE) HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
                CHK_STATUS(pushStartUpLatency(pCanaryStreamCallbacks, startUpLatency));
                DLOGD("Start up latency: %lf ms", startUpLatency);
                firstFrame = FALSE;
            }

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
    DLOGI("Exiting application with status code: 0x%08x", retStatus);
    return STATUS_FAILED(retStatus) ? EXIT_FAILURE : EXIT_SUCCESS;
}
