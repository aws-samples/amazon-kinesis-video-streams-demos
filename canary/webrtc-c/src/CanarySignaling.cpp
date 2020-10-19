#include "Include.h"

#undef ENABLE_DATA_CHANNEL

ATOMIC_BOOL gExitCanary;

STATUS run(Canary::PConfig);

typedef struct {
    ATOMIC_BOOL answerReceived;
    CVAR terminateCv;
    STATUS exitStatus;
    CVAR roundtripCv;
    MUTEX roundtripLock;
    UINT32 iterationFailCount;
    SIGNALING_CLIENT_HANDLE masterHandle;
    SIGNALING_CLIENT_HANDLE viewerHandle;
    PSignalingClientInfo pMasterClientInfo;
    PSignalingClientInfo pViewerClientInfo;
    PChannelInfo pMasterChannelInfo;
    PChannelInfo pViewerChannelInfo;
    PSignalingClientCallbacks pMasterCallbacks;
    PSignalingClientCallbacks pViewerCallbacks;
} CanarySessionInfo, *PCanarySessionInfo;

VOID handleSignal(INT32 signal)
{
    UNUSED_PARAM(signal);
    ATOMIC_STORE_BOOL(&gExitCanary, TRUE);
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

STATUS signalingClientStateChanged(UINT64 customData, SIGNALING_CLIENT_STATE state)
{
    UNUSED_PARAM(customData);
    STATUS retStatus = STATUS_SUCCESS;
    PCHAR pStateStr;

    signalingClientGetStateString(state, &pStateStr);

    DLOGD("Signaling client state changed to %d - '%s'", state, pStateStr);

    // Return success to continue
    return retStatus;
}

STATUS signalingClientError(UINT64 customData, STATUS status, PCHAR msg, UINT32 msgLen)
{
    PCanarySessionInfo pCanarySessionInfo = (PCanarySessionInfo) customData;
    CHECK_EXT(pCanarySessionInfo != NULL, "Application error - the custom data to error handler is NULL");

    DLOGE("Signaling client generated an error 0x%08x - '%.*s'", status, msgLen, msg);

    // Store the status and terminate the loop
    ATOMIC_STORE_BOOL(&gExitCanary, TRUE);
    pCanarySessionInfo->exitStatus = status;
    CVAR_BROADCAST(pCanarySessionInfo->terminateCv);

    return STATUS_SUCCESS;
}

STATUS signalingMessageReceived(UINT64 customData, PReceivedSignalingMessage pReceivedSignalingMessage)
{
    STATUS retStatus = STATUS_SUCCESS;
    PCanarySessionInfo pCanarySessionInfo = (PCanarySessionInfo) customData;
    SignalingMessage message = {};

    CHK(pCanarySessionInfo != NULL, STATUS_INTERNAL_ERROR);

    switch (pReceivedSignalingMessage->signalingMessage.messageType) {
        case SIGNALING_MESSAGE_TYPE_OFFER:
            // If we received an offer then the receiving end should be the master.
            // We need to do some validation and respond with an answer
            CHK(0 == STRCMP(pReceivedSignalingMessage->signalingMessage.peerClientId, SIGNALING_CANARY_VIEWER_CLIENT_ID),
                STATUS_SIGNALING_CANARY_OFFER_CID_MISMATCH);
            CHK(0 == STRCMP(pReceivedSignalingMessage->signalingMessage.payload, SIGNALING_CANARY_OFFER),
                STATUS_SIGNALING_CANARY_OFFER_PAYLOAD_MISMATCH);

            // Respond with an answer
            message.version = SIGNALING_MESSAGE_CURRENT_VERSION;
            message.messageType = SIGNALING_MESSAGE_TYPE_ANSWER;
            STRCPY(message.peerClientId, SIGNALING_CANARY_VIEWER_CLIENT_ID);
            STRCPY(message.payload, (PCHAR) SIGNALING_CANARY_ANSWER);
            message.payloadLen = 0; // Will calculate it
            message.correlationId[0] = '\0';

            CHK_STATUS(signalingClientSendMessageSync(pCanarySessionInfo->masterHandle, &message));

            break;

        case SIGNALING_MESSAGE_TYPE_ANSWER:
            // If we received an answer then the receiving end should be the viewer.
            // NOTE: The clientID in this case is left empty by the service
            CHK(0 == STRCMP(pReceivedSignalingMessage->signalingMessage.payload, SIGNALING_CANARY_ANSWER),
                STATUS_SIGNALING_CANARY_ANSWER_PAYLOAD_MISMATCH);

            // All good, trigger the cond variable to wake up the awaiting thread to log and proceed
            ATOMIC_STORE_BOOL(&pCanarySessionInfo->answerReceived, TRUE);
            CVAR_SIGNAL(pCanarySessionInfo->roundtripCv);
            break;

        default:
            // Unexpected message received
            CHK_ERR(FALSE, STATUS_SIGNALING_CANARY_UNEXPECTED_MESSAGE, "Unhandled signaling message type %u",
                    pReceivedSignalingMessage->signalingMessage.messageType);
    }

CleanUp:

    CHK_LOG_ERR(retStatus);

    if (STATUS_FAILED(retStatus)) {
        DLOGW("Signaling message listener routine failed with 0x%08x", retStatus);
        Canary::Cloudwatch::getInstance().monitoring.pushSignalingRoundtripStatus(retStatus);
    }

    return retStatus;
}

STATUS terminateCanaryCallback(UINT32 timerId, UINT64 time, UINT64 customData)
{
    UNUSED_PARAM(timerId);
    UNUSED_PARAM(time);

    PCanarySessionInfo pCanarySessionInfo = (PCanarySessionInfo) customData;
    CHECK_EXT(pCanarySessionInfo != NULL, STATUS_INTERNAL_ERROR, "Custom data to terminateCanaryCallback is NULL");

    DLOGD("Stopping signaling canary...");
    ATOMIC_STORE_BOOL(&gExitCanary, TRUE);
    pCanarySessionInfo->exitStatus = STATUS_SUCCESS;
    CVAR_BROADCAST(pCanarySessionInfo->terminateCv);

    return STATUS_SUCCESS;
}

STATUS sendViewerOfferCallback(UINT32 timerId, UINT64 time, UINT64 customData)
{
    STATUS retStatus = STATUS_SUCCESS;
    UNUSED_PARAM(timerId);
    SignalingMessage message = {};
    PCanarySessionInfo pCanarySessionInfo = (PCanarySessionInfo) customData;
    BOOL locked = FALSE;
    UINT64 curTime, latency;

    CHECK_EXT(pCanarySessionInfo != NULL, STATUS_INTERNAL_ERROR, "Custom data to sendViewerOfferCallback is NULL");

    // Create a dummy offer with the current time timestamp and send for a round trip
    message.version = SIGNALING_MESSAGE_CURRENT_VERSION;
    message.messageType = SIGNALING_MESSAGE_TYPE_OFFER;
    STRCPY(message.peerClientId, SIGNALING_CANARY_MASTER_CLIENT_ID);
    STRCPY(message.payload, (PCHAR) SIGNALING_CANARY_OFFER);
    message.payloadLen = 0; // Will calculate it
    message.correlationId[0] = '\0';

    // Try to acquire. If the lock had been already acquired then there is an ongoing session
    CHK(MUTEX_TRYLOCK(pCanarySessionInfo->roundtripLock), STATUS_INVALID_OPERATION);
    locked = TRUE;

    // Set the not received atomic so we can distinguish between spurious wake-ups
    ATOMIC_STORE_BOOL(&pCanarySessionInfo->answerReceived, FALSE);

    // Send the offer first
    CHK_STATUS(signalingClientSendMessageSync(pCanarySessionInfo->viewerHandle, &message));

    while (!ATOMIC_LOAD_BOOL(&pCanarySessionInfo->answerReceived)) {
        // This will jump to cleanup on timeout
        CHK_STATUS(CVAR_WAIT(pCanarySessionInfo->roundtripCv, pCanarySessionInfo->roundtripLock, SIGNALING_CANARY_ROUNDTRIP_TIMEOUT));
    }

    curTime = GETTIME();
    latency = (curTime - time) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND;

    Canary::Cloudwatch::getInstance().monitoring.pushSignalingRoundtripLatency(latency, StandardUnit::Milliseconds);

CleanUp:

    if (locked) {
        MUTEX_UNLOCK(pCanarySessionInfo->roundtripLock);
    }

    if (STATUS_FAILED(retStatus)) {
        DLOGW("Rountrip handler failed with 0x%08x", retStatus);
        Canary::Cloudwatch::getInstance().monitoring.pushSignalingRoundtripStatus(retStatus);

        // Here, we apply a super simple box filter - if we failed N consecutive number of
        // times in a row we quit the application. We could later decide to change the heuristics.
        if (pCanarySessionInfo == NULL || pCanarySessionInfo->iterationFailCount++ >= SIGNALING_CANARY_MAX_CONSECUTIVE_ITERATION_FAILURE_COUNT) {
            DLOGE("Rountrip handler failed more than %u times in a row. Exiting...", SIGNALING_CANARY_MAX_CONSECUTIVE_ITERATION_FAILURE_COUNT);

            // Change the retStatus to not iterate any longer
            retStatus = STATUS_TIMER_QUEUE_STOP_SCHEDULING;

            terminateCanaryCallback(0, 0, (UINT64) pCanarySessionInfo);
        }
    } else {
        // Reset the failure count
        pCanarySessionInfo->iterationFailCount = 0;
    }

    return retStatus;
}

VOID generateChannelName(PCHAR pChannelName)
{
    // Prepare the test channel name by prefixing with test channel name
    // and generating random chars replacing a potentially bad characters with '.'
    STRCPY(pChannelName, SIGNALING_CANARY_CHANNEL_NAME);
    UINT32 nameLen = STRLEN(SIGNALING_CANARY_CHANNEL_NAME);
    const UINT32 randSize = 16;

    PCHAR pCur = &pChannelName[nameLen];

    for (UINT32 i = 0; i < randSize; i++) {
        *pCur++ = SIGNALING_VALID_NAME_CHARS[RAND() % (ARRAY_SIZE(SIGNALING_VALID_NAME_CHARS) - 1)];
    }

    *pCur = '\0';
}

STATUS run(Canary::PConfig pConfig)
{
    STATUS retStatus = STATUS_SUCCESS;
    BOOL initialized = FALSE, channelNameGenerated = FALSE;
    TIMER_QUEUE_HANDLE timerQueueHandle = 0;
    UINT32 timeoutTimerId;
    ChannelInfo masterChannelInfo = {};
    ChannelInfo viewerChannelInfo = {};
    SignalingClientInfo masterClientInfo = {};
    SignalingClientInfo viewerClientInfo = {};
    SignalingClientCallbacks masterSignalingClientCallbacks = {};
    SignalingClientCallbacks viewerSignalingClientCallbacks = {};
    PAwsCredentialProvider pCredentialProvider = NULL;
    SIGNALING_CLIENT_HANDLE masterSignalingClientHandle = INVALID_SIGNALING_CLIENT_HANDLE_VALUE;
    SIGNALING_CLIENT_HANDLE viewerSignalingClientHandle = INVALID_SIGNALING_CLIENT_HANDLE_VALUE;
    CanarySessionInfo canarySessionInfo = {};
    MUTEX lock = INVALID_MUTEX_VALUE;
    CVAR terminateCv = INVALID_CVAR_VALUE;
    CHAR channelName[MAX_CHANNEL_NAME_LEN + 1];

    canarySessionInfo.roundtripLock = INVALID_MUTEX_VALUE;
    canarySessionInfo.roundtripCv = INVALID_CVAR_VALUE;

    // Create the lock and the cvar for iteration
    CHK(IS_VALID_MUTEX_VALUE(lock = MUTEX_CREATE(FALSE)), STATUS_NOT_ENOUGH_MEMORY);
    CHK(IS_VALID_CVAR_VALUE(terminateCv = CVAR_CREATE()), STATUS_NOT_ENOUGH_MEMORY);
    CHK(IS_VALID_MUTEX_VALUE(canarySessionInfo.roundtripLock = MUTEX_CREATE(FALSE)), STATUS_NOT_ENOUGH_MEMORY);
    CHK(IS_VALID_CVAR_VALUE(canarySessionInfo.roundtripCv = CVAR_CREATE()), STATUS_NOT_ENOUGH_MEMORY);
    canarySessionInfo.exitStatus = STATUS_SUCCESS;
    canarySessionInfo.iterationFailCount = 0;

    CHK_STATUS(Canary::Cloudwatch::init(pConfig));
    CHK_STATUS(initKvsWebRtc());
    initialized = TRUE;

    SET_LOGGER_LOG_LEVEL(pConfig->logLevel.value);
    pConfig->print();

    // The timer loop for iteration
    CHK_STATUS(timerQueueCreate(&timerQueueHandle));

    // We will create a static credential provider. We can replace it with others if needed.
    CHK_STATUS(createStaticCredentialProvider((PCHAR) pConfig->accessKey.value.c_str(), 0, (PCHAR) pConfig->secretKey.value.c_str(), 0,
                                              (PCHAR) pConfig->sessionToken.value.c_str(), 0, MAX_UINT64, &pCredentialProvider));

    // Generate a random channel name if not specified in the config.
    // In case we generate the random name we will follow-up with deleting
    // it upon exit to prevent the account from ever increasing channel count
    if (pConfig->channelName.value.empty()) {
        generateChannelName(channelName);
        channelNameGenerated = TRUE;
    } else {
        STRNCPY(channelName, pConfig->channelName.value.c_str(), MAX_CHANNEL_NAME_LEN);
    }

    // Prepare the channel info structure
    masterChannelInfo.version = CHANNEL_INFO_CURRENT_VERSION;
    masterChannelInfo.pChannelName = channelName;
    masterChannelInfo.pRegion = (PCHAR) pConfig->region.value.c_str();
    masterChannelInfo.pKmsKeyId = NULL;
    masterChannelInfo.tagCount = 0;
    masterChannelInfo.pTags = NULL;
    masterChannelInfo.channelType = SIGNALING_CHANNEL_TYPE_SINGLE_MASTER;
    masterChannelInfo.channelRoleType = SIGNALING_CHANNEL_ROLE_TYPE_MASTER;
    masterChannelInfo.cachingPolicy = SIGNALING_API_CALL_CACHE_TYPE_FILE;
    masterChannelInfo.cachingPeriod = SIGNALING_API_CALL_CACHE_TTL_SENTINEL_VALUE;
    masterChannelInfo.asyncIceServerConfig = FALSE;
    masterChannelInfo.retry = TRUE;
    masterChannelInfo.reconnect = TRUE;
    masterChannelInfo.pCertPath = (PCHAR) DEFAULT_KVS_CACERT_PATH;
    masterChannelInfo.messageTtl = 0; // Default is 60 seconds

    masterSignalingClientCallbacks.version = SIGNALING_CLIENT_CALLBACKS_CURRENT_VERSION;
    masterSignalingClientCallbacks.errorReportFn = signalingClientError;
    masterSignalingClientCallbacks.stateChangeFn = signalingClientStateChanged;
    masterSignalingClientCallbacks.messageReceivedFn = signalingMessageReceived;
    masterSignalingClientCallbacks.customData = (UINT64) &canarySessionInfo;

    masterClientInfo.version = SIGNALING_CLIENT_INFO_CURRENT_VERSION;
    masterClientInfo.loggingLevel = pConfig->logLevel.value;
    STRCPY(masterClientInfo.clientId, SIGNALING_CANARY_MASTER_CLIENT_ID);

    // Create the master signaling client
    CHK_STATUS(createSignalingClientSync(&masterClientInfo, &masterChannelInfo, &masterSignalingClientCallbacks, pCredentialProvider,
                                         &masterSignalingClientHandle));

    canarySessionInfo.pMasterChannelInfo = &masterChannelInfo;
    canarySessionInfo.pMasterClientInfo = &masterClientInfo;
    canarySessionInfo.pMasterCallbacks = &masterSignalingClientCallbacks;
    canarySessionInfo.masterHandle = masterSignalingClientHandle;

    // Prepare the structs and create the viewer signaling client
    viewerChannelInfo = masterChannelInfo;
    viewerChannelInfo.channelRoleType = SIGNALING_CHANNEL_ROLE_TYPE_VIEWER;

    viewerSignalingClientCallbacks = masterSignalingClientCallbacks;
    viewerSignalingClientCallbacks.customData = (UINT64) &canarySessionInfo;

    viewerClientInfo = masterClientInfo;
    STRCPY(viewerClientInfo.clientId, SIGNALING_CANARY_VIEWER_CLIENT_ID);

    CHK_STATUS(createSignalingClientSync(&viewerClientInfo, &viewerChannelInfo, &viewerSignalingClientCallbacks, pCredentialProvider,
                                         &viewerSignalingClientHandle));

    canarySessionInfo.pViewerChannelInfo = &viewerChannelInfo;
    canarySessionInfo.pViewerClientInfo = &viewerClientInfo;
    canarySessionInfo.pViewerCallbacks = &viewerSignalingClientCallbacks;
    canarySessionInfo.viewerHandle = viewerSignalingClientHandle;

    // Set it to a non-terminated state and iterate
    ATOMIC_STORE_BOOL(&gExitCanary, FALSE);

    // Connect the signaling clients
    CHK_STATUS(signalingClientConnectSync(canarySessionInfo.masterHandle));
    CHK_STATUS(signalingClientConnectSync(canarySessionInfo.viewerHandle));

    if (pConfig->duration.value != 0) {
        CHK_STATUS(timerQueueAddTimer(timerQueueHandle, pConfig->duration.value, TIMER_QUEUE_SINGLE_INVOCATION_PERIOD, terminateCanaryCallback,
                                      (UINT64) &canarySessionInfo, &timeoutTimerId));
    }

    // Set the duration to iterate
    CHK_STATUS(timerQueueAddTimer(timerQueueHandle, SIGNALING_CANARY_START_DELAY, pConfig->iterationDuration.value, sendViewerOfferCallback,
                                  (UINT64) &canarySessionInfo, &timeoutTimerId));

    MUTEX_LOCK(lock);
    while (!ATOMIC_LOAD_BOOL(&gExitCanary)) {
        // Having the cvar waking up often allows for Ctrl+C cancellation to be more responsive
        CVAR_WAIT(terminateCv, lock, 1 * HUNDREDS_OF_NANOS_IN_A_SECOND);
    }
    MUTEX_UNLOCK(lock);

CleanUp:

    if (IS_VALID_TIMER_QUEUE_HANDLE(timerQueueHandle)) {
        timerQueueFree(&timerQueueHandle);
    }

    STATUS combinedStatus = STATUS_FAILED(canarySessionInfo.exitStatus) ? canarySessionInfo.exitStatus : retStatus;

    DLOGI("Exiting with 0x%08x", combinedStatus);
    if (initialized) {
        Canary::Cloudwatch::getInstance().monitoring.pushExitStatus(combinedStatus);
    }

    if (IS_VALID_SIGNALING_CLIENT_HANDLE(masterSignalingClientHandle)) {
        freeSignalingClient(&masterSignalingClientHandle);
    }

    if (IS_VALID_SIGNALING_CLIENT_HANDLE(viewerSignalingClientHandle)) {
        // As we are freeing the viewer (the last of the clients),
        // we need to check whether we generated the
        // channel name and if so delete it
        if (channelNameGenerated) {
            signalingClientDeleteSync(masterSignalingClientHandle);
        }

        freeSignalingClient(&viewerSignalingClientHandle);
    }

    if (pCredentialProvider != NULL) {
        freeStaticCredentialProvider(&pCredentialProvider);
    }

    if (IS_VALID_MUTEX_VALUE(lock)) {
        MUTEX_FREE(lock);
    }

    if (IS_VALID_CVAR_VALUE(terminateCv)) {
        CVAR_FREE(terminateCv);
    }

    if (IS_VALID_MUTEX_VALUE(canarySessionInfo.roundtripLock)) {
        MUTEX_FREE(canarySessionInfo.roundtripLock);
    }

    if (IS_VALID_CVAR_VALUE(canarySessionInfo.roundtripCv)) {
        CVAR_FREE(canarySessionInfo.roundtripCv);
    }

    deinitKvsWebRtc();
    Canary::Cloudwatch::deinit();

    return retStatus;
}
