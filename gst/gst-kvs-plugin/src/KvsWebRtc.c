#define LOG_CLASS "KvsWebRtc"
#include "GstPlugin.h"

STATUS signalingClientStateChangedFn(UINT64 customData, SIGNALING_CLIENT_STATE state)
{
    UNUSED_PARAM(customData);
    STATUS retStatus = STATUS_SUCCESS;
    PCHAR pStateStr;

    signalingClientGetStateString(state, &pStateStr);

    DLOGV("Signaling client state changed to %d - '%s'", state, pStateStr);

    // Return success to continue
    return retStatus;
}

STATUS signalingClientErrorFn(UINT64 customData, STATUS status, PCHAR msg, UINT32 msgLen)
{
    PGstKvsPlugin pGstKvsPlugin = (PGstKvsPlugin) customData;

    DLOGW("Signaling client generated an error 0x%08x - '%.*s'", status, msgLen, msg);

    // We will force re-create the signaling client on the following errors
    if (status == STATUS_SIGNALING_ICE_CONFIG_REFRESH_FAILED || status == STATUS_SIGNALING_RECONNECT_FAILED) {
        ATOMIC_STORE_BOOL(&pGstKvsPlugin->recreateSignalingClient, TRUE);
    }

    return STATUS_SUCCESS;
}

VOID onDataChannelMessage(UINT64 customData, PRtcDataChannel pDataChannel, BOOL isBinary, PBYTE pMessage, UINT32 pMessageLen)
{
    UNUSED_PARAM(customData);
    UNUSED_PARAM(pDataChannel);
    if (isBinary) {
        DLOGI("DataChannel Binary Message");
    } else {
        DLOGI("DataChannel String Message: %.*s\n", pMessageLen, pMessage);
    }
}

VOID onDataChannel(UINT64 customData, PRtcDataChannel pRtcDataChannel)
{
    DLOGI("New DataChannel has been opened %s \n", pRtcDataChannel->name);
    dataChannelOnMessage(pRtcDataChannel, customData, onDataChannelMessage);
}

VOID onConnectionStateChange(UINT64 customData, RTC_PEER_CONNECTION_STATE newState)
{
    STATUS retStatus = STATUS_SUCCESS;
    PWebRtcStreamingSession pStreamingSession = (PWebRtcStreamingSession) customData;

    CHK(pStreamingSession != NULL && pStreamingSession->pGstKvsPlugin != NULL, STATUS_INTERNAL_ERROR);

    DLOGD("New connection state %u", newState);

    switch (newState) {
        case RTC_PEER_CONNECTION_STATE_CONNECTED:
            ATOMIC_STORE_BOOL(&pStreamingSession->connected, TRUE);
            if (STATUS_FAILED(retStatus = logSelectedIceCandidatesInformation(pStreamingSession))) {
                DLOGW("Failed to get information about selected Ice candidates: 0x%08x", retStatus);
            }
            break;
        case RTC_PEER_CONNECTION_STATE_FAILED:
            // explicit fallthrough
        case RTC_PEER_CONNECTION_STATE_CLOSED:
            // explicit fallthrough
        case RTC_PEER_CONNECTION_STATE_DISCONNECTED:
            ATOMIC_STORE_BOOL(&pStreamingSession->terminateFlag, TRUE);
            // explicit fallthrough
        default:
            ATOMIC_STORE_BOOL(&pStreamingSession->connected, FALSE);
            break;
    }

CleanUp:

    CHK_LOG_ERR(retStatus);
}

STATUS signalingClientMessageReceivedFn(UINT64 customData, PReceivedSignalingMessage pReceivedSignalingMessage)
{
    STATUS retStatus = STATUS_SUCCESS;
    PGstKvsPlugin pGstKvsPlugin = (PGstKvsPlugin) customData;
    BOOL peerConnectionFound = FALSE;
    BOOL locked = TRUE;
    UINT32 clientIdHash;
    UINT64 hashValue = 0;
    PPendingMessageQueue pPendingMessageQueue = NULL;
    PWebRtcStreamingSession pStreamingSession = NULL;
    PReceivedSignalingMessage pReceivedSignalingMessageCopy = NULL;

    CHK(pGstKvsPlugin != NULL, STATUS_NULL_ARG);

    MUTEX_LOCK(pGstKvsPlugin->sessionLock);
    locked = TRUE;

    clientIdHash = COMPUTE_CRC32((PBYTE) pReceivedSignalingMessage->signalingMessage.peerClientId,
                                 (UINT32) STRLEN(pReceivedSignalingMessage->signalingMessage.peerClientId));
    CHK_STATUS(hashTableContains(pGstKvsPlugin->pRtcPeerConnectionForRemoteClient, clientIdHash, &peerConnectionFound));
    if (peerConnectionFound) {
        CHK_STATUS(hashTableGet(pGstKvsPlugin->pRtcPeerConnectionForRemoteClient, clientIdHash, &hashValue));
        pStreamingSession = (PWebRtcStreamingSession) hashValue;
    }

    switch (pReceivedSignalingMessage->signalingMessage.messageType) {
        case SIGNALING_MESSAGE_TYPE_OFFER:
            // Check if we already have an ongoing master session with the same peer
            CHK_ERR(!peerConnectionFound, STATUS_INVALID_OPERATION, "Peer connection %s is in progress",
                    pReceivedSignalingMessage->signalingMessage.peerClientId);

            /*
             * Create new streaming session for each offer, then insert the client id and streaming session into
             * pRtcPeerConnectionForRemoteClient for subsequent ice candidate messages. Lastly check if there is
             * any ice candidate messages queued in pPendingSignalingMessageForRemoteClient. If so then submit
             * all of them.
             */
            if (pGstKvsPlugin->streamingSessionCount == ARRAY_SIZE(pGstKvsPlugin->streamingSessionList)) {
                DLOGW("Max simultaneous streaming session count reached.");

                // Need to remove the pending queue if any.
                // This is a simple optimization as the session cleanup will
                // handle the cleanup of pending message queue after a while
                CHK_STATUS(
                    getPendingMessageQueueForHash(pGstKvsPlugin->pPendingSignalingMessageForRemoteClient, clientIdHash, TRUE, &pPendingMessageQueue));

                CHK(FALSE, retStatus);
            }
            CHK_STATUS(
                createWebRtcStreamingSession(pGstKvsPlugin, pReceivedSignalingMessage->signalingMessage.peerClientId, TRUE, &pStreamingSession));
            pStreamingSession->offerReceiveTime = GETTIME();
            MUTEX_LOCK(pGstKvsPlugin->sessionListReadLock);
            pGstKvsPlugin->streamingSessionList[pGstKvsPlugin->streamingSessionCount++] = pStreamingSession;
            MUTEX_UNLOCK(pGstKvsPlugin->sessionListReadLock);

            CHK_STATUS(handleOffer(pGstKvsPlugin, pStreamingSession, &pReceivedSignalingMessage->signalingMessage));
            CHK_STATUS(hashTablePut(pGstKvsPlugin->pRtcPeerConnectionForRemoteClient, clientIdHash, (UINT64) pStreamingSession));

            // If there are any ice candidate messages in the queue for this client id, submit them now.
            CHK_STATUS(
                getPendingMessageQueueForHash(pGstKvsPlugin->pPendingSignalingMessageForRemoteClient, clientIdHash, TRUE, &pPendingMessageQueue));
            if (pPendingMessageQueue != NULL) {
                CHK_STATUS(submitPendingIceCandidate(pPendingMessageQueue, pStreamingSession));

                // NULL the pointer to avoid it being freed in the cleanup
                pPendingMessageQueue = NULL;
            }
            break;

        case SIGNALING_MESSAGE_TYPE_ANSWER:
            /*
             * for viewer, pStreamingSession should've already been created. insert the client id and
             * streaming session into pRtcPeerConnectionForRemoteClient for subsequent ice candidate messages.
             * Lastly check if there is any ice candidate messages queued in pPendingSignalingMessageForRemoteClient.
             * If so then submit all of them.
             */
            pStreamingSession = pGstKvsPlugin->streamingSessionList[0];
            CHK_STATUS(handleAnswer(pGstKvsPlugin, pStreamingSession, &pReceivedSignalingMessage->signalingMessage));
            CHK_STATUS(hashTablePut(pGstKvsPlugin->pRtcPeerConnectionForRemoteClient, clientIdHash, (UINT64) pStreamingSession));

            // If there are any ice candidate messages in the queue for this client id, submit them now.
            CHK_STATUS(
                getPendingMessageQueueForHash(pGstKvsPlugin->pPendingSignalingMessageForRemoteClient, clientIdHash, TRUE, &pPendingMessageQueue));
            if (pPendingMessageQueue != NULL) {
                CHK_STATUS(submitPendingIceCandidate(pPendingMessageQueue, pStreamingSession));

                // NULL the pointer to avoid it being freed in the cleanup
                pPendingMessageQueue = NULL;
            }
            break;

        case SIGNALING_MESSAGE_TYPE_ICE_CANDIDATE:
            /*
             * if peer connection hasn't been created, create an queue to store the ice candidate message. Otherwise
             * submit the signaling message into the corresponding streaming session.
             */
            if (!peerConnectionFound) {
                CHK_STATUS(getPendingMessageQueueForHash(pGstKvsPlugin->pPendingSignalingMessageForRemoteClient, clientIdHash, FALSE,
                                                         &pPendingMessageQueue));
                if (pPendingMessageQueue == NULL) {
                    CHK_STATUS(createMessageQueue(clientIdHash, &pPendingMessageQueue));
                    CHK_STATUS(stackQueueEnqueue(pGstKvsPlugin->pPendingSignalingMessageForRemoteClient, (UINT64) pPendingMessageQueue));
                }

                pReceivedSignalingMessageCopy = (PReceivedSignalingMessage) MEMCALLOC(1, SIZEOF(ReceivedSignalingMessage));

                *pReceivedSignalingMessageCopy = *pReceivedSignalingMessage;

                CHK_STATUS(stackQueueEnqueue(pPendingMessageQueue->messageQueue, (UINT64) pReceivedSignalingMessageCopy));

                // NULL the pointers to not free any longer
                pPendingMessageQueue = NULL;
                pReceivedSignalingMessageCopy = NULL;
            } else {
                CHK_STATUS(handleRemoteCandidate(pStreamingSession, &pReceivedSignalingMessage->signalingMessage));
            }
            break;

        default:
            DLOGD("Unhandled signaling message type %u", pReceivedSignalingMessage->signalingMessage.messageType);
            break;
    }

CleanUp:

    SAFE_MEMFREE(pReceivedSignalingMessageCopy);
    if (pPendingMessageQueue != NULL) {
        freeMessageQueue(pPendingMessageQueue);
    }

    if (locked) {
        MUTEX_UNLOCK(pGstKvsPlugin->sessionLock);
    }

    CHK_LOG_ERR(retStatus);
    return retStatus;
}

STATUS initKinesisVideoWebRtc(PGstKvsPlugin pGstPlugin)
{
    STATUS retStatus = STATUS_SUCCESS;

    CHK(pGstPlugin != NULL, STATUS_NULL_ARG);

    DLOGD("Init invoked");
    ATOMIC_STORE_BOOL(&pGstPlugin->terminate, FALSE);
    ATOMIC_STORE_BOOL(&pGstPlugin->recreateSignalingClient, FALSE);
    ATOMIC_STORE_BOOL(&pGstPlugin->signalingConnected, FALSE);

    pGstPlugin->sessionLock = MUTEX_CREATE(TRUE);

    pGstPlugin->sessionListReadLock = MUTEX_CREATE(FALSE);
    pGstPlugin->signalingLock = MUTEX_CREATE(FALSE);

    pGstPlugin->pregenerateCertTimerId = MAX_UINT32;
    pGstPlugin->serviceRoutineTimerId = MAX_UINT32;
    pGstPlugin->iceUriCount = 0;

    MEMSET(&pGstPlugin->kvsContext.channelInfo, 0x00, SIZEOF(ChannelInfo));
    MEMSET(&pGstPlugin->kvsContext.signalingClientInfo, 0x00, SIZEOF(SignalingClientInfo));
    MEMSET(&pGstPlugin->kvsContext.signalingClientCallbacks, 0x00, SIZEOF(SignalingClientCallbacks));

    pGstPlugin->kvsContext.channelInfo.pRegion = pGstPlugin->pRegion;
    pGstPlugin->kvsContext.channelInfo.version = CHANNEL_INFO_CURRENT_VERSION;
    pGstPlugin->kvsContext.channelInfo.pChannelName = pGstPlugin->gstParams.channelName;
    pGstPlugin->kvsContext.channelInfo.pKmsKeyId = NULL;
    pGstPlugin->kvsContext.channelInfo.pUserAgentPostfix = KVS_WEBRTC_CLIENT_USER_AGENT_NAME;
    pGstPlugin->kvsContext.channelInfo.tagCount = pGstPlugin->kvsContext.pStreamInfo->tagCount;
    pGstPlugin->kvsContext.channelInfo.pTags = pGstPlugin->kvsContext.pStreamInfo->tags;
    pGstPlugin->kvsContext.channelInfo.channelType = SIGNALING_CHANNEL_TYPE_SINGLE_MASTER;
    pGstPlugin->kvsContext.channelInfo.channelRoleType = SIGNALING_CHANNEL_ROLE_TYPE_MASTER;
    pGstPlugin->kvsContext.channelInfo.cachingPolicy = SIGNALING_API_CALL_CACHE_TYPE_FILE;
    pGstPlugin->kvsContext.channelInfo.cachingPeriod = DEFAULT_API_CACHE_PERIOD;
    pGstPlugin->kvsContext.channelInfo.asyncIceServerConfig = TRUE; // has no effect
    pGstPlugin->kvsContext.channelInfo.retry = TRUE;
    pGstPlugin->kvsContext.channelInfo.reconnect = TRUE;
    pGstPlugin->kvsContext.channelInfo.pCertPath = pGstPlugin->caCertPath;
    pGstPlugin->kvsContext.channelInfo.messageTtl = 0; // Default is 60 seconds

    pGstPlugin->kvsContext.signalingClientCallbacks.version = SIGNALING_CLIENT_CALLBACKS_CURRENT_VERSION;
    pGstPlugin->kvsContext.signalingClientCallbacks.errorReportFn = signalingClientErrorFn;
    pGstPlugin->kvsContext.signalingClientCallbacks.stateChangeFn = signalingClientStateChangedFn;
    pGstPlugin->kvsContext.signalingClientCallbacks.messageReceivedFn = signalingClientMessageReceivedFn;
    pGstPlugin->kvsContext.signalingClientCallbacks.customData = (UINT64) pGstPlugin;

    pGstPlugin->kvsContext.signalingClientInfo.version = SIGNALING_CLIENT_INFO_CURRENT_VERSION;
    pGstPlugin->kvsContext.signalingClientInfo.loggingLevel = pGstPlugin->kvsContext.pDeviceInfo->clientInfo.loggerLogLevel;
    STRCPY(pGstPlugin->kvsContext.signalingClientInfo.clientId, DEFAULT_MASTER_CLIENT_ID);
    pGstPlugin->kvsContext.signalingClientInfo.cacheFilePath = NULL; // Use the default path

    CHK_STATUS(stackQueueCreate(&pGstPlugin->pPendingSignalingMessageForRemoteClient));
    CHK_STATUS(hashTableCreateWithParams(GST_PLUGIN_HASH_TABLE_BUCKET_COUNT, GST_PLUGIN_HASH_TABLE_BUCKET_LENGTH,
                                         &pGstPlugin->pRtcPeerConnectionForRemoteClient));

    CHK_STATUS(timerQueueCreate(&pGstPlugin->kvsContext.timerQueueHandle));

    CHK_STATUS(stackQueueCreate(&pGstPlugin->pregeneratedCertificates));

    CHK_LOG_ERR(retStatus = timerQueueAddTimer(pGstPlugin->kvsContext.timerQueueHandle, GST_PLUGIN_PRE_GENERATE_CERT_START,
                                               GST_PLUGIN_PRE_GENERATE_CERT_PERIOD, pregenerateCertTimerCallback, (UINT64) pGstPlugin,
                                               &pGstPlugin->pregenerateCertTimerId));

    // Create the signaling client and connect to it
    CHK_STATUS(createSignalingClientSync(&pGstPlugin->kvsContext.signalingClientInfo, &pGstPlugin->kvsContext.channelInfo,
                                         &pGstPlugin->kvsContext.signalingClientCallbacks, pGstPlugin->kvsContext.pCredentialProvider,
                                         &pGstPlugin->kvsContext.signalingHandle));

    if (ATOMIC_LOAD_BOOL(&pGstPlugin->connectWebRtc)) {
        CHK_STATUS(signalingClientConnectSync(pGstPlugin->kvsContext.signalingHandle));
    }

CleanUp:

    CHK_LOG_ERR(retStatus);

    return retStatus;
}

STATUS createMessageQueue(UINT64 hashValue, PPendingMessageQueue* ppPendingMessageQueue)
{
    STATUS retStatus = STATUS_SUCCESS;
    PPendingMessageQueue pPendingMessageQueue = NULL;

    CHK(ppPendingMessageQueue != NULL, STATUS_NULL_ARG);

    CHK(NULL != (pPendingMessageQueue = (PPendingMessageQueue) MEMCALLOC(1, SIZEOF(PendingMessageQueue))), STATUS_NOT_ENOUGH_MEMORY);
    pPendingMessageQueue->hashValue = hashValue;
    pPendingMessageQueue->createTime = GETTIME();
    CHK_STATUS(stackQueueCreate(&pPendingMessageQueue->messageQueue));

CleanUp:

    if (STATUS_FAILED(retStatus) && pPendingMessageQueue != NULL) {
        freeMessageQueue(pPendingMessageQueue);
        pPendingMessageQueue = NULL;
    }

    if (ppPendingMessageQueue != NULL) {
        *ppPendingMessageQueue = pPendingMessageQueue;
    }

    return retStatus;
}

STATUS freeMessageQueue(PPendingMessageQueue pPendingMessageQueue)
{
    STATUS retStatus = STATUS_SUCCESS;

    // free is idempotent
    CHK(pPendingMessageQueue != NULL, retStatus);

    if (pPendingMessageQueue->messageQueue != NULL) {
        stackQueueClear(pPendingMessageQueue->messageQueue, TRUE);
        stackQueueFree(pPendingMessageQueue->messageQueue);
    }

    MEMFREE(pPendingMessageQueue);

CleanUp:
    return retStatus;
}

STATUS freeGstKvsWebRtcPlugin(PGstKvsPlugin pGstKvsPlugin)
{
    STATUS retStatus = STATUS_SUCCESS;
    UINT32 i;
    UINT64 data;
    StackQueueIterator iterator;
    BOOL locked = FALSE;

    CHK(pGstKvsPlugin != NULL, STATUS_NULL_ARG);

    if (IS_VALID_SIGNALING_CLIENT_HANDLE(pGstKvsPlugin->kvsContext.signalingHandle)) {
        freeSignalingClient(&pGstKvsPlugin->kvsContext.signalingHandle);
    }

    if (pGstKvsPlugin->pPendingSignalingMessageForRemoteClient != NULL) {
        // Iterate and free all the pending queues
        stackQueueGetIterator(pGstKvsPlugin->pPendingSignalingMessageForRemoteClient, &iterator);
        while (IS_VALID_ITERATOR(iterator)) {
            stackQueueIteratorGetItem(iterator, &data);
            stackQueueIteratorNext(&iterator);
            freeMessageQueue((PPendingMessageQueue) data);
        }

        stackQueueClear(pGstKvsPlugin->pPendingSignalingMessageForRemoteClient, FALSE);
        stackQueueFree(pGstKvsPlugin->pPendingSignalingMessageForRemoteClient);
        pGstKvsPlugin->pPendingSignalingMessageForRemoteClient = NULL;
    }

    if (pGstKvsPlugin->pRtcPeerConnectionForRemoteClient != NULL) {
        hashTableClear(pGstKvsPlugin->pRtcPeerConnectionForRemoteClient);
        hashTableFree(pGstKvsPlugin->pRtcPeerConnectionForRemoteClient);
        pGstKvsPlugin->pRtcPeerConnectionForRemoteClient = NULL;
    }

    if (IS_VALID_MUTEX_VALUE(pGstKvsPlugin->sessionLock)) {
        MUTEX_LOCK(pGstKvsPlugin->sessionLock);
        locked = TRUE;
    }
    for (i = 0; i < pGstKvsPlugin->streamingSessionCount; ++i) {
        retStatus = gatherIceServerStats(pGstKvsPlugin->streamingSessionList[i]);
        if (STATUS_FAILED(retStatus)) {
            DLOGW("Failed to ICE Server Stats for streaming session %d: %08x", i, retStatus);
        }

        freeWebRtcStreamingSession(&pGstKvsPlugin->streamingSessionList[i]);
    }
    if (locked) {
        MUTEX_UNLOCK(pGstKvsPlugin->sessionLock);
    }

    deinitKvsWebRtc();

    if (IS_VALID_MUTEX_VALUE(pGstKvsPlugin->sessionLock)) {
        MUTEX_FREE(pGstKvsPlugin->sessionLock);
        pGstKvsPlugin->sessionLock = INVALID_MUTEX_VALUE;
    }

    if (IS_VALID_MUTEX_VALUE(pGstKvsPlugin->sessionListReadLock)) {
        MUTEX_FREE(pGstKvsPlugin->sessionListReadLock);
        pGstKvsPlugin->sessionListReadLock = INVALID_MUTEX_VALUE;
    }

    if (IS_VALID_MUTEX_VALUE(pGstKvsPlugin->signalingLock)) {
        MUTEX_FREE(pGstKvsPlugin->signalingLock);
        pGstKvsPlugin->signalingLock = INVALID_MUTEX_VALUE;
    }

    if (IS_VALID_TIMER_QUEUE_HANDLE(pGstKvsPlugin->kvsContext.timerQueueHandle)) {
        if (pGstKvsPlugin->iceCandidatePairStatsTimerId != MAX_UINT32) {
            retStatus = timerQueueCancelTimer(pGstKvsPlugin->kvsContext.timerQueueHandle, pGstKvsPlugin->iceCandidatePairStatsTimerId,
                                              (UINT64) pGstKvsPlugin);
            if (STATUS_FAILED(retStatus)) {
                DLOGE("Failed to cancel stats timer with: 0x%08x", retStatus);
            }
            pGstKvsPlugin->iceCandidatePairStatsTimerId = MAX_UINT32;
        }

        if (pGstKvsPlugin->pregenerateCertTimerId != MAX_UINT32) {
            retStatus =
                timerQueueCancelTimer(pGstKvsPlugin->kvsContext.timerQueueHandle, pGstKvsPlugin->pregenerateCertTimerId, (UINT64) pGstKvsPlugin);
            if (STATUS_FAILED(retStatus)) {
                DLOGE("Failed to cancel certificate pre-generation timer with: 0x%08x", retStatus);
            }
            pGstKvsPlugin->pregenerateCertTimerId = MAX_UINT32;
        }

        if (pGstKvsPlugin->serviceRoutineTimerId != MAX_UINT32) {
            retStatus =
                timerQueueCancelTimer(pGstKvsPlugin->kvsContext.timerQueueHandle, pGstKvsPlugin->serviceRoutineTimerId, (UINT64) pGstKvsPlugin);
            if (STATUS_FAILED(retStatus)) {
                DLOGE("Failed to cancel service handler routine timer with: 0x%08x", retStatus);
            }
            pGstKvsPlugin->serviceRoutineTimerId = MAX_UINT32;
        }

        timerQueueFree(&pGstKvsPlugin->kvsContext.timerQueueHandle);
        pGstKvsPlugin->kvsContext.timerQueueHandle = INVALID_TIMER_QUEUE_HANDLE_VALUE;
    }

    if (pGstKvsPlugin->pregeneratedCertificates != NULL) {
        stackQueueGetIterator(pGstKvsPlugin->pregeneratedCertificates, &iterator);
        while (IS_VALID_ITERATOR(iterator)) {
            stackQueueIteratorGetItem(iterator, &data);
            stackQueueIteratorNext(&iterator);
            freeRtcCertificate((PRtcCertificate) data);
        }

        CHK_LOG_ERR(stackQueueClear(pGstKvsPlugin->pregeneratedCertificates, FALSE));
        CHK_LOG_ERR(stackQueueFree(pGstKvsPlugin->pregeneratedCertificates));
        pGstKvsPlugin->pregeneratedCertificates = NULL;
    }

CleanUp:

    return retStatus;
}

// Return ICE server stats for a specific streaming session
STATUS gatherIceServerStats(PWebRtcStreamingSession pStreamingSession)
{
    STATUS retStatus = STATUS_SUCCESS;
    RtcStats rtcmetrics;
    UINT32 j;
    rtcmetrics.requestedTypeOfStats = RTC_STATS_TYPE_ICE_SERVER;

    for (j = 0; j < pStreamingSession->pGstKvsPlugin->iceUriCount; j++) {
        rtcmetrics.rtcStatsObject.iceServerStats.iceServerIndex = j;
        CHK_STATUS(rtcPeerConnectionGetMetrics(pStreamingSession->pPeerConnection, NULL, &rtcmetrics));
        DLOGD("ICE Server URL: %s", rtcmetrics.rtcStatsObject.iceServerStats.url);
        DLOGD("ICE Server port: %d", rtcmetrics.rtcStatsObject.iceServerStats.port);
        DLOGD("ICE Server protocol: %s", rtcmetrics.rtcStatsObject.iceServerStats.protocol);
        DLOGD("Total requests sent:%" PRIu64, rtcmetrics.rtcStatsObject.iceServerStats.totalRequestsSent);
        DLOGD("Total responses received: %" PRIu64, rtcmetrics.rtcStatsObject.iceServerStats.totalResponsesReceived);
        DLOGD("Total round trip time: %" PRIu64 "ms",
              rtcmetrics.rtcStatsObject.iceServerStats.totalRoundTripTime / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
    }

CleanUp:

    return retStatus;
}

STATUS freeWebRtcStreamingSession(PWebRtcStreamingSession* ppStreamingSession)
{
    STATUS retStatus = STATUS_SUCCESS;
    PWebRtcStreamingSession pStreamingSession = NULL;
    PGstKvsPlugin pGstKvsPlugin;

    CHK(ppStreamingSession != NULL, STATUS_NULL_ARG);
    pStreamingSession = *ppStreamingSession;
    CHK(pStreamingSession != NULL && pStreamingSession->pGstKvsPlugin != NULL, retStatus);
    pGstKvsPlugin = pStreamingSession->pGstKvsPlugin;

    DLOGD("Freeing streaming session with peer id: %s ", pStreamingSession->peerId);

    ATOMIC_STORE_BOOL(&pStreamingSession->terminateFlag, TRUE);

    if (pStreamingSession->shutdownCallback != NULL) {
        pStreamingSession->shutdownCallback(pStreamingSession->shutdownCallbackCustomData, pStreamingSession);
    }

    if (IS_VALID_TID_VALUE(pStreamingSession->receiveAudioVideoSenderTid)) {
        THREAD_JOIN(pStreamingSession->receiveAudioVideoSenderTid, NULL);
    }

    // De-initialize the session stats timer if there are no active sessions
    // NOTE: we need to perform this under the lock which might be acquired by
    // the running thread but it's OK as it's re-entrant
    MUTEX_LOCK(pGstKvsPlugin->sessionLock);
    if (pGstKvsPlugin->iceCandidatePairStatsTimerId != MAX_UINT32 && pGstKvsPlugin->streamingSessionCount == 0) {
        CHK_LOG_ERR(
            timerQueueCancelTimer(pGstKvsPlugin->kvsContext.timerQueueHandle, pGstKvsPlugin->iceCandidatePairStatsTimerId, (UINT64) pGstKvsPlugin));
        pGstKvsPlugin->iceCandidatePairStatsTimerId = MAX_UINT32;
    }
    MUTEX_UNLOCK(pGstKvsPlugin->sessionLock);

    CHK_LOG_ERR(closePeerConnection(pStreamingSession->pPeerConnection));
    CHK_LOG_ERR(freePeerConnection(&pStreamingSession->pPeerConnection));

    SAFE_MEMFREE(pStreamingSession);

CleanUp:

    CHK_LOG_ERR(retStatus);

    return retStatus;
}

STATUS streamingSessionOnShutdown(PWebRtcStreamingSession pStreamingSession, UINT64 customData,
                                  StreamSessionShutdownCallback streamSessionShutdownCallback)
{
    STATUS retStatus = STATUS_SUCCESS;

    CHK(pStreamingSession != NULL && streamSessionShutdownCallback != NULL, STATUS_NULL_ARG);

    pStreamingSession->shutdownCallbackCustomData = customData;
    pStreamingSession->shutdownCallback = streamSessionShutdownCallback;

CleanUp:

    return retStatus;
}

STATUS pregenerateCertTimerCallback(UINT32 timerId, UINT64 currentTime, UINT64 customData)
{
    UNUSED_PARAM(timerId);
    UNUSED_PARAM(currentTime);
    STATUS retStatus = STATUS_SUCCESS;
    PGstKvsPlugin pGstKvsPlugin = (PGstKvsPlugin) customData;
    BOOL locked = FALSE;
    UINT32 certCount;
    PRtcCertificate pRtcCertificate = NULL;

    CHK_WARN(pGstKvsPlugin != NULL, STATUS_NULL_ARG, "pregenerateCertTimerCallback(): Passed argument is NULL");

    MUTEX_LOCK(pGstKvsPlugin->sessionLock);
    locked = TRUE;

    // Quick check if there is anything that needs to be done.
    CHK_STATUS(stackQueueGetCount(pGstKvsPlugin->pregeneratedCertificates, &certCount));
    CHK(certCount != MAX_RTCCONFIGURATION_CERTIFICATES, retStatus);

    // Generate the certificate with the keypair
    CHK_STATUS(createRtcCertificate(&pRtcCertificate));

    // Add to the stack queue
    CHK_STATUS(stackQueueEnqueue(pGstKvsPlugin->pregeneratedCertificates, (UINT64) pRtcCertificate));

    DLOGV("New certificate has been pre-generated and added to the queue");

    // Reset it so it won't be freed on exit
    pRtcCertificate = NULL;

    MUTEX_UNLOCK(pGstKvsPlugin->sessionLock);
    locked = FALSE;

CleanUp:

    if (pRtcCertificate != NULL) {
        freeRtcCertificate(pRtcCertificate);
    }

    if (locked) {
        MUTEX_UNLOCK(pGstKvsPlugin->sessionLock);
    }

    return retStatus;
}

STATUS getPendingMessageQueueForHash(PStackQueue pPendingQueue, UINT64 clientHash, BOOL remove, PPendingMessageQueue* ppPendingMessageQueue)
{
    STATUS retStatus = STATUS_SUCCESS;
    PPendingMessageQueue pPendingMessageQueue = NULL;
    StackQueueIterator iterator;
    BOOL iterate = TRUE;
    UINT64 data;

    CHK(pPendingQueue != NULL && ppPendingMessageQueue != NULL, STATUS_NULL_ARG);

    CHK_STATUS(stackQueueGetIterator(pPendingQueue, &iterator));
    while (iterate && IS_VALID_ITERATOR(iterator)) {
        CHK_STATUS(stackQueueIteratorGetItem(iterator, &data));
        CHK_STATUS(stackQueueIteratorNext(&iterator));

        pPendingMessageQueue = (PPendingMessageQueue) data;

        if (clientHash == pPendingMessageQueue->hashValue) {
            *ppPendingMessageQueue = pPendingMessageQueue;
            iterate = FALSE;

            // Check if the item needs to be removed
            if (remove) {
                // This is OK to do as we are terminating the iterator anyway
                CHK_STATUS(stackQueueRemoveItem(pPendingQueue, data));
            }
        }
    }

CleanUp:

    return retStatus;
}

STATUS removeExpiredMessageQueues(PStackQueue pPendingQueue)
{
    STATUS retStatus = STATUS_SUCCESS;
    PPendingMessageQueue pPendingMessageQueue = NULL;
    UINT32 i, count;
    UINT64 data, curTime;

    CHK(pPendingQueue != NULL, STATUS_NULL_ARG);

    curTime = GETTIME();
    CHK_STATUS(stackQueueGetCount(pPendingQueue, &count));

    // Dequeue and enqueue in order to not break the iterator while removing an item
    for (i = 0; i < count; i++) {
        CHK_STATUS(stackQueueDequeue(pPendingQueue, &data));

        // Check for expiry
        pPendingMessageQueue = (PPendingMessageQueue) data;
        if (pPendingMessageQueue->createTime + GST_PLUGIN_PENDING_MESSAGE_CLEANUP_DURATION < curTime) {
            // Message queue has expired and needs to be freed
            CHK_STATUS(freeMessageQueue(pPendingMessageQueue));
        } else {
            // Enqueue back again as it's still valued
            CHK_STATUS(stackQueueEnqueue(pPendingQueue, data));
        }
    }

CleanUp:

    return retStatus;
}

STATUS createWebRtcStreamingSession(PGstKvsPlugin pGstKvsPlugin, PCHAR peerId, BOOL isMaster, PWebRtcStreamingSession* ppStreamingSession)
{
    STATUS retStatus = STATUS_SUCCESS;
    RtcMediaStreamTrack videoTrack, audioTrack;
    PWebRtcStreamingSession pStreamingSession = NULL;

    MEMSET(&videoTrack, 0x00, SIZEOF(RtcMediaStreamTrack));
    MEMSET(&audioTrack, 0x00, SIZEOF(RtcMediaStreamTrack));

    CHK(pGstKvsPlugin != NULL && ppStreamingSession != NULL, STATUS_NULL_ARG);
    CHK((isMaster && peerId != NULL) || !isMaster, STATUS_INVALID_ARG);

    pStreamingSession = (PWebRtcStreamingSession) MEMCALLOC(1, SIZEOF(WebRtcStreamingSession));
    CHK(pStreamingSession != NULL, STATUS_NOT_ENOUGH_MEMORY);

    if (isMaster) {
        STRCPY(pStreamingSession->peerId, peerId);
    } else {
        STRCPY(pStreamingSession->peerId, DEFAULT_VIEWER_CLIENT_ID);
    }
    ATOMIC_STORE_BOOL(&pStreamingSession->peerIdReceived, TRUE);

    pStreamingSession->pGstKvsPlugin = pGstKvsPlugin;
    pStreamingSession->rtcMetricsHistory.prevTs = GETTIME();
    // if we're the viewer, we control the trickle ice mode
    pStreamingSession->remoteCanTrickleIce = !isMaster && pGstKvsPlugin->gstParams.trickleIce;

    ATOMIC_STORE_BOOL(&pStreamingSession->terminateFlag, FALSE);
    ATOMIC_STORE_BOOL(&pStreamingSession->candidateGatheringDone, FALSE);
    ATOMIC_STORE_BOOL(&pStreamingSession->connected, FALSE);

    CHK_STATUS(initializePeerConnection(pGstKvsPlugin, &pStreamingSession->pPeerConnection));
    CHK_STATUS(peerConnectionOnIceCandidate(pStreamingSession->pPeerConnection, (UINT64) pStreamingSession, onIceCandidateHandler));
    CHK_STATUS(peerConnectionOnConnectionStateChange(pStreamingSession->pPeerConnection, (UINT64) pStreamingSession, onConnectionStateChange));
    if (pGstKvsPlugin->onDataChannel != NULL) {
        CHK_STATUS(peerConnectionOnDataChannel(pStreamingSession->pPeerConnection, (UINT64) pStreamingSession, pGstKvsPlugin->onDataChannel));
    }

    // Declare that we support H264,Profile=42E01F,level-asymmetry-allowed=1,packetization-mode=1 and Opus
    CHK_STATUS(addSupportedCodec(pStreamingSession->pPeerConnection, RTC_CODEC_H264_PROFILE_42E01F_LEVEL_ASYMMETRY_ALLOWED_PACKETIZATION_MODE));
    CHK_STATUS(addSupportedCodec(pStreamingSession->pPeerConnection, RTC_CODEC_VP8));
    CHK_STATUS(addSupportedCodec(pStreamingSession->pPeerConnection, RTC_CODEC_OPUS));
    CHK_STATUS(addSupportedCodec(pStreamingSession->pPeerConnection, RTC_CODEC_MULAW));
    CHK_STATUS(addSupportedCodec(pStreamingSession->pPeerConnection, RTC_CODEC_ALAW));

    // Add a SendRecv Transceiver of type video
    videoTrack.kind = MEDIA_STREAM_TRACK_KIND_VIDEO;
    videoTrack.codec = RTC_CODEC_H264_PROFILE_42E01F_LEVEL_ASYMMETRY_ALLOWED_PACKETIZATION_MODE;
    STRCPY(videoTrack.streamId, "myKvsVideoStream");
    STRCPY(videoTrack.trackId, "myVideoTrack");
    CHK_STATUS(addTransceiver(pStreamingSession->pPeerConnection, &videoTrack, NULL, &pStreamingSession->pVideoRtcRtpTransceiver));

    CHK_STATUS(
        transceiverOnBandwidthEstimation(pStreamingSession->pVideoRtcRtpTransceiver, (UINT64) pStreamingSession, sampleBandwidthEstimationHandler));

    // Set up audio transceiver codec id according to type of encoding used
    if (STRNCMP(pGstKvsPlugin->gstParams.audioContentType, MKV_MULAW_CONTENT_TYPE, MAX_GSTREAMER_MEDIA_TYPE_LEN) == 0) {
        audioTrack.codec = RTC_CODEC_MULAW;
    } else if (STRNCMP(pGstKvsPlugin->gstParams.audioContentType, MKV_ALAW_CONTENT_TYPE, MAX_GSTREAMER_MEDIA_TYPE_LEN) == 0) {
        audioTrack.codec = RTC_CODEC_ALAW;
    } else {
        // no-op, should result in a caps negotiation error before getting here.
        DLOGE("Error, audio content type %s not accepted by plugin", pGstKvsPlugin->gstParams.audioContentType);
        CHK(FALSE, STATUS_INVALID_ARG);
    }
    // Add a SendRecv Transceiver of type video
    audioTrack.kind = MEDIA_STREAM_TRACK_KIND_AUDIO;
    STRCPY(audioTrack.streamId, "myKvsVideoStream");
    STRCPY(audioTrack.trackId, "myAudioTrack");
    CHK_STATUS(addTransceiver(pStreamingSession->pPeerConnection, &audioTrack, NULL, &pStreamingSession->pAudioRtcRtpTransceiver));

    CHK_STATUS(
        transceiverOnBandwidthEstimation(pStreamingSession->pAudioRtcRtpTransceiver, (UINT64) pStreamingSession, sampleBandwidthEstimationHandler));
    pStreamingSession->firstFrame = TRUE;
    pStreamingSession->startUpLatency = 0;

CleanUp:

    if (STATUS_FAILED(retStatus) && pStreamingSession != NULL) {
        freeWebRtcStreamingSession(&pStreamingSession);
        pStreamingSession = NULL;
    }

    if (ppStreamingSession != NULL) {
        *ppStreamingSession = pStreamingSession;
    }

    return retStatus;
}

STATUS initializePeerConnection(PGstKvsPlugin pGstKvsPlugin, PRtcPeerConnection* ppRtcPeerConnection)
{
    ENTERS();
    STATUS retStatus = STATUS_SUCCESS;
    RtcConfiguration configuration;
    UINT32 i, j, iceConfigCount, uriCount = 0, maxTurnServer = 1;
    PIceConfigInfo pIceConfigInfo;
    UINT64 data, curTime;
    PRtcCertificate pRtcCertificate = NULL;

    CHK(pGstKvsPlugin != NULL && ppRtcPeerConnection != NULL, STATUS_NULL_ARG);

    MEMSET(&configuration, 0x00, SIZEOF(RtcConfiguration));

    // Set this to custom callback to enable filtering of interfaces
    configuration.kvsRtcConfiguration.iceSetInterfaceFilterFunc = NULL;

    // Set the ICE mode explicitly
    configuration.iceTransportPolicy =
        pGstKvsPlugin->gstParams.connectionMode == WEBRTC_CONNECTION_MODE_TURN_ONLY ? ICE_TRANSPORT_POLICY_RELAY : ICE_TRANSPORT_POLICY_ALL;

    // Set the  STUN server
    SNPRINTF(configuration.iceServers[0].urls, MAX_ICE_CONFIG_URI_LEN, KINESIS_VIDEO_STUN_URL, pGstKvsPlugin->kvsContext.channelInfo.pRegion);

    if (pGstKvsPlugin->gstParams.connectionMode != WEBRTC_CONNECTION_MODE_P2P_ONLY) {
        // Set the URIs from the configuration
        CHK_STATUS(signalingClientGetIceConfigInfoCount(pGstKvsPlugin->kvsContext.signalingHandle, &iceConfigCount));

        /* signalingClientGetIceConfigInfoCount can return more than one turn server. Use only one to optimize
         * candidate gathering latency. But user can also choose to use more than 1 turn server. */
        for (uriCount = 0, i = 0; i < maxTurnServer; i++) {
            CHK_STATUS(signalingClientGetIceConfigInfo(pGstKvsPlugin->kvsContext.signalingHandle, i, &pIceConfigInfo));
            for (j = 0; j < pIceConfigInfo->uriCount; j++) {
                CHECK(uriCount < MAX_ICE_SERVERS_COUNT);
                /*
                 * if configuration.iceServers[uriCount + 1].urls is "turn:ip:port?transport=udp" then ICE will try TURN over UDP
                 * if configuration.iceServers[uriCount + 1].urls is "turn:ip:port?transport=tcp" then ICE will try TURN over TCP/TLS
                 * if configuration.iceServers[uriCount + 1].urls is "turns:ip:port?transport=udp", it's currently ignored because sdk dont do TURN
                 * over DTLS yet. if configuration.iceServers[uriCount + 1].urls is "turns:ip:port?transport=tcp" then ICE will try TURN over TCP/TLS
                 * if configuration.iceServers[uriCount + 1].urls is "turn:ip:port" then ICE will try both TURN over UPD and TCP/TLS
                 *
                 * It's recommended to not pass too many TURN iceServers to configuration because it will slow down ice gathering in non-trickle mode.
                 */

                STRNCPY(configuration.iceServers[uriCount + 1].urls, pIceConfigInfo->uris[j], MAX_ICE_CONFIG_URI_LEN);
                STRNCPY(configuration.iceServers[uriCount + 1].credential, pIceConfigInfo->password, MAX_ICE_CONFIG_CREDENTIAL_LEN);
                STRNCPY(configuration.iceServers[uriCount + 1].username, pIceConfigInfo->userName, MAX_ICE_CONFIG_USER_NAME_LEN);

                uriCount++;
            }
        }
    }

    pGstKvsPlugin->iceUriCount = uriCount + 1;

    // Check if we have any pre-generated certs and use them
    // NOTE: We are running under the config lock
    retStatus = stackQueueDequeue(pGstKvsPlugin->pregeneratedCertificates, &data);
    CHK(retStatus == STATUS_SUCCESS || retStatus == STATUS_NOT_FOUND, retStatus);

    if (retStatus == STATUS_NOT_FOUND) {
        retStatus = STATUS_SUCCESS;
    } else {
        // Use the pre-generated cert and get rid of it to not reuse again
        pRtcCertificate = (PRtcCertificate) data;
        configuration.certificates[0] = *pRtcCertificate;
    }

    curTime = GETTIME();
    CHK_STATUS(createPeerConnection(&configuration, ppRtcPeerConnection));
    DLOGD("time taken to create peer connection %" PRIu64 " ms", (GETTIME() - curTime) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);

CleanUp:

    CHK_LOG_ERR(retStatus);

    // Free the certificate which can be NULL as we no longer need it and won't reuse
    freeRtcCertificate(pRtcCertificate);

    LEAVES();
    return retStatus;
}

VOID onIceCandidateHandler(UINT64 customData, PCHAR candidateJson)
{
    STATUS retStatus = STATUS_SUCCESS;
    PWebRtcStreamingSession pStreamingSession = (PWebRtcStreamingSession) customData;
    SignalingMessage message;

    CHK(pStreamingSession != NULL, STATUS_NULL_ARG);

    if (candidateJson == NULL) {
        DLOGD("ice candidate gathering finished");
        ATOMIC_STORE_BOOL(&pStreamingSession->candidateGatheringDone, TRUE);

        // if application is master and non-trickle ice, send answer now.
        if (pStreamingSession->pGstKvsPlugin->kvsContext.channelInfo.channelRoleType == SIGNALING_CHANNEL_ROLE_TYPE_MASTER &&
            !pStreamingSession->remoteCanTrickleIce) {
            CHK_STATUS(createAnswer(pStreamingSession->pPeerConnection, &pStreamingSession->answerSessionDescriptionInit));
            CHK_STATUS(respondWithAnswer(pStreamingSession));
            DLOGD("time taken to send answer %" PRIu64 " ms", (GETTIME() - pStreamingSession->offerReceiveTime) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
        } else if (pStreamingSession->pGstKvsPlugin->kvsContext.channelInfo.channelRoleType == SIGNALING_CHANNEL_ROLE_TYPE_VIEWER &&
                   !pStreamingSession->pGstKvsPlugin->gstParams.trickleIce) {
            // MMMMMM
        }

    } else if (pStreamingSession->remoteCanTrickleIce && ATOMIC_LOAD_BOOL(&pStreamingSession->peerIdReceived)) {
        message.version = SIGNALING_MESSAGE_CURRENT_VERSION;
        message.messageType = SIGNALING_MESSAGE_TYPE_ICE_CANDIDATE;
        STRNCPY(message.peerClientId, pStreamingSession->peerId, MAX_SIGNALING_CLIENT_ID_LEN);
        message.payloadLen = (UINT32) STRNLEN(candidateJson, MAX_SIGNALING_MESSAGE_LEN);
        STRNCPY(message.payload, candidateJson, message.payloadLen);
        message.correlationId[0] = '\0';
        CHK_STATUS(sendSignalingMessage(pStreamingSession, &message));
    }

CleanUp:

    CHK_LOG_ERR(retStatus);
}

STATUS sendSignalingMessage(PWebRtcStreamingSession pStreamingSession, PSignalingMessage pMessage)
{
    STATUS retStatus = STATUS_SUCCESS;
    BOOL locked = FALSE;

    // Validate the input params
    CHK(pStreamingSession != NULL && pStreamingSession->pGstKvsPlugin != NULL && pMessage != NULL, STATUS_NULL_ARG);
    CHK(IS_VALID_MUTEX_VALUE(pStreamingSession->pGstKvsPlugin->signalingLock) &&
            IS_VALID_SIGNALING_CLIENT_HANDLE(pStreamingSession->pGstKvsPlugin->kvsContext.signalingHandle),
        STATUS_INVALID_OPERATION);

    MUTEX_LOCK(pStreamingSession->pGstKvsPlugin->signalingLock);
    locked = TRUE;
    CHK_STATUS(signalingClientSendMessageSync(pStreamingSession->pGstKvsPlugin->kvsContext.signalingHandle, pMessage));

CleanUp:

    if (locked) {
        MUTEX_UNLOCK(pStreamingSession->pGstKvsPlugin->signalingLock);
    }

    CHK_LOG_ERR(retStatus);
    return retStatus;
}

STATUS respondWithAnswer(PWebRtcStreamingSession pStreamingSession)
{
    STATUS retStatus = STATUS_SUCCESS;
    SignalingMessage message;
    UINT32 buffLen = MAX_SIGNALING_MESSAGE_LEN;

    CHK_STATUS(serializeSessionDescriptionInit(&pStreamingSession->answerSessionDescriptionInit, message.payload, &buffLen));

    message.version = SIGNALING_MESSAGE_CURRENT_VERSION;
    message.messageType = SIGNALING_MESSAGE_TYPE_ANSWER;
    STRNCPY(message.peerClientId, pStreamingSession->peerId, MAX_SIGNALING_CLIENT_ID_LEN);
    message.payloadLen = (UINT32) STRLEN(message.payload);
    message.correlationId[0] = '\0';

    CHK_STATUS(sendSignalingMessage(pStreamingSession, &message));

CleanUp:

    CHK_LOG_ERR(retStatus);
    return retStatus;
}

STATUS submitPendingIceCandidate(PPendingMessageQueue pPendingMessageQueue, PWebRtcStreamingSession pStreamingSession)
{
    STATUS retStatus = STATUS_SUCCESS;
    BOOL noPendingSignalingMessageForClient = FALSE;
    PReceivedSignalingMessage pReceivedSignalingMessage = NULL;
    UINT64 hashValue;

    CHK(pPendingMessageQueue != NULL && pPendingMessageQueue->messageQueue != NULL && pStreamingSession != NULL, STATUS_NULL_ARG);

    do {
        CHK_STATUS(stackQueueIsEmpty(pPendingMessageQueue->messageQueue, &noPendingSignalingMessageForClient));
        if (!noPendingSignalingMessageForClient) {
            hashValue = 0;
            CHK_STATUS(stackQueueDequeue(pPendingMessageQueue->messageQueue, &hashValue));
            pReceivedSignalingMessage = (PReceivedSignalingMessage) hashValue;
            CHK(pReceivedSignalingMessage != NULL, STATUS_INTERNAL_ERROR);
            if (pReceivedSignalingMessage->signalingMessage.messageType == SIGNALING_MESSAGE_TYPE_ICE_CANDIDATE) {
                CHK_STATUS(handleRemoteCandidate(pStreamingSession, &pReceivedSignalingMessage->signalingMessage));
            }
            SAFE_MEMFREE(pReceivedSignalingMessage);
        }
    } while (!noPendingSignalingMessageForClient);

    CHK_STATUS(freeMessageQueue(pPendingMessageQueue));

CleanUp:

    SAFE_MEMFREE(pReceivedSignalingMessage);
    CHK_LOG_ERR(retStatus);
    return retStatus;
}

VOID sampleBandwidthEstimationHandler(UINT64 customData, DOUBLE maxiumBitrate)
{
    UNUSED_PARAM(customData);
    DLOGD("Received bitrate suggestion: %f", maxiumBitrate);
}

STATUS handleRemoteCandidate(PWebRtcStreamingSession pStreamingSession, PSignalingMessage pSignalingMessage)
{
    STATUS retStatus = STATUS_SUCCESS;
    RtcIceCandidateInit iceCandidate;
    CHK(pStreamingSession != NULL && pSignalingMessage != NULL, STATUS_NULL_ARG);

    CHK_STATUS(deserializeRtcIceCandidateInit(pSignalingMessage->payload, pSignalingMessage->payloadLen, &iceCandidate));
    CHK_STATUS(addIceCandidate(pStreamingSession->pPeerConnection, iceCandidate.candidate));

CleanUp:

    CHK_LOG_ERR(retStatus);
    return retStatus;
}

STATUS logSelectedIceCandidatesInformation(PWebRtcStreamingSession pStreamingSession)
{
    STATUS retStatus = STATUS_SUCCESS;
    RtcStats rtcMetrics;

    CHK(pStreamingSession != NULL, STATUS_NULL_ARG);

    rtcMetrics.requestedTypeOfStats = RTC_STATS_TYPE_LOCAL_CANDIDATE;
    CHK_STATUS(rtcPeerConnectionGetMetrics(pStreamingSession->pPeerConnection, NULL, &rtcMetrics));
    DLOGD("Local Candidate IP Address: %s", rtcMetrics.rtcStatsObject.localIceCandidateStats.address);
    DLOGD("Local Candidate type: %s", rtcMetrics.rtcStatsObject.localIceCandidateStats.candidateType);
    DLOGD("Local Candidate port: %d", rtcMetrics.rtcStatsObject.localIceCandidateStats.port);
    DLOGD("Local Candidate priority: %d", rtcMetrics.rtcStatsObject.localIceCandidateStats.priority);
    DLOGD("Local Candidate transport protocol: %s", rtcMetrics.rtcStatsObject.localIceCandidateStats.protocol);
    DLOGD("Local Candidate relay protocol: %s", rtcMetrics.rtcStatsObject.localIceCandidateStats.relayProtocol);
    DLOGD("Local Candidate Ice server source: %s", rtcMetrics.rtcStatsObject.localIceCandidateStats.url);

    rtcMetrics.requestedTypeOfStats = RTC_STATS_TYPE_REMOTE_CANDIDATE;
    CHK_STATUS(rtcPeerConnectionGetMetrics(pStreamingSession->pPeerConnection, NULL, &rtcMetrics));
    DLOGD("Remote Candidate IP Address: %s", rtcMetrics.rtcStatsObject.remoteIceCandidateStats.address);
    DLOGD("Remote Candidate type: %s", rtcMetrics.rtcStatsObject.remoteIceCandidateStats.candidateType);
    DLOGD("Remote Candidate port: %d", rtcMetrics.rtcStatsObject.remoteIceCandidateStats.port);
    DLOGD("Remote Candidate priority: %d", rtcMetrics.rtcStatsObject.remoteIceCandidateStats.priority);
    DLOGD("Remote Candidate transport protocol: %s", rtcMetrics.rtcStatsObject.remoteIceCandidateStats.protocol);

CleanUp:

    return retStatus;
}

STATUS handleOffer(PGstKvsPlugin pGstKvsPlugin, PWebRtcStreamingSession pStreamingSession, PSignalingMessage pSignalingMessage)
{
    STATUS retStatus = STATUS_SUCCESS;
    RtcSessionDescriptionInit offerSessionDescriptionInit;
    NullableBool canTrickle;
    BOOL active;

    CHK(pGstKvsPlugin != NULL && pSignalingMessage != NULL && pStreamingSession != NULL, STATUS_NULL_ARG);

    MEMSET(&offerSessionDescriptionInit, 0x00, SIZEOF(RtcSessionDescriptionInit));
    MEMSET(&pStreamingSession->answerSessionDescriptionInit, 0x00, SIZEOF(RtcSessionDescriptionInit));

    CHK_STATUS(deserializeSessionDescriptionInit(pSignalingMessage->payload, pSignalingMessage->payloadLen, &offerSessionDescriptionInit));
    CHK_STATUS(setRemoteDescription(pStreamingSession->pPeerConnection, &offerSessionDescriptionInit));
    canTrickle = canTrickleIceCandidates(pStreamingSession->pPeerConnection);

    // cannot be null after setRemoteDescription
    CHK(!NULLABLE_CHECK_EMPTY(canTrickle), STATUS_INTERNAL_ERROR);

    pStreamingSession->remoteCanTrickleIce = canTrickle.value;
    CHK_STATUS(setLocalDescription(pStreamingSession->pPeerConnection, &pStreamingSession->answerSessionDescriptionInit));

    // If remote support trickle ice, send answer now. Otherwise answer will be sent once ice candidate gathering is complete.
    if (pStreamingSession->remoteCanTrickleIce) {
        CHK_STATUS(createAnswer(pStreamingSession->pPeerConnection, &pStreamingSession->answerSessionDescriptionInit));
        CHK_STATUS(respondWithAnswer(pStreamingSession));
        DLOGD("time taken to send answer %" PRIu64 " ms", (GETTIME() - pStreamingSession->offerReceiveTime) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
    }

    // We need the metrics timer only when there isn't one already in progress
    // IMPORTANT: This is called under the lock
    if (pGstKvsPlugin->iceCandidatePairStatsTimerId == MAX_UINT32 &&
        STATUS_FAILED(retStatus = timerQueueAddTimer(pGstKvsPlugin->kvsContext.timerQueueHandle, GST_PLUGIN_STATS_DURATION, GST_PLUGIN_STATS_DURATION,
                                                     getIceCandidatePairStatsCallback, (UINT64) pGstKvsPlugin,
                                                     &pGstKvsPlugin->iceCandidatePairStatsTimerId))) {
        DLOGW("Failed to add getIceCandidatePairStatsCallback to add to timer queue (code 0x%08x). Cannot pull ice candidate pair metrics "
              "periodically",
              retStatus);
    }

    // The audio video receive routine should be per streaming session
    THREAD_CREATE(&pStreamingSession->receiveAudioVideoSenderTid, receiveGstreamerAudioVideo, (PVOID) pStreamingSession);

CleanUp:

    CHK_LOG_ERR(retStatus);
    return retStatus;
}

STATUS handleAnswer(PGstKvsPlugin pGstKvsPlugin, PWebRtcStreamingSession pStreamingSession, PSignalingMessage pSignalingMessage)
{
    UNUSED_PARAM(pGstKvsPlugin);
    STATUS retStatus = STATUS_SUCCESS;
    RtcSessionDescriptionInit answerSessionDescriptionInit;

    MEMSET(&answerSessionDescriptionInit, 0x00, SIZEOF(RtcSessionDescriptionInit));

    CHK_STATUS(deserializeSessionDescriptionInit(pSignalingMessage->payload, pSignalingMessage->payloadLen, &answerSessionDescriptionInit));
    CHK_STATUS(setRemoteDescription(pStreamingSession->pPeerConnection, &answerSessionDescriptionInit));

CleanUp:

    CHK_LOG_ERR(retStatus);

    return retStatus;
}

STATUS getIceCandidatePairStatsCallback(UINT32 timerId, UINT64 currentTime, UINT64 customData)
{
    UNUSED_PARAM(timerId);
    UNUSED_PARAM(currentTime);
    STATUS retStatus = STATUS_SUCCESS;
    PGstKvsPlugin pGstKvsPlugin = (PGstKvsPlugin) customData;
    UINT32 i;
    UINT64 currentMeasureDuration = 0;
    DOUBLE averagePacketsDiscardedOnSend = 0.0;
    DOUBLE averageNumberOfPacketsSentPerSecond = 0.0;
    DOUBLE averageNumberOfPacketsReceivedPerSecond = 0.0;
    DOUBLE outgoingBitrate = 0.0;
    DOUBLE incomingBitrate = 0.0;
    BOOL locked = FALSE;

    CHK_WARN(pGstKvsPlugin != NULL, STATUS_NULL_ARG, "GetPeriodicStats(): Passed argument is NULL");

    pGstKvsPlugin->rtcIceCandidatePairMetrics.requestedTypeOfStats = RTC_STATS_TYPE_CANDIDATE_PAIR;

    // We need to execute this under the object lock due to race conditions that it could pose
    MUTEX_LOCK(pGstKvsPlugin->sessionLock);
    locked = TRUE;

    for (i = 0; i < pGstKvsPlugin->streamingSessionCount; ++i) {
        if (STATUS_SUCCEEDED(rtcPeerConnectionGetMetrics(pGstKvsPlugin->streamingSessionList[i]->pPeerConnection, NULL,
                                                         &pGstKvsPlugin->rtcIceCandidatePairMetrics))) {
            currentMeasureDuration =
                (pGstKvsPlugin->rtcIceCandidatePairMetrics.timestamp - pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevTs) /
                HUNDREDS_OF_NANOS_IN_A_SECOND;
            DLOGD("Current duration: %" PRIu64 " seconds", currentMeasureDuration);
            if (currentMeasureDuration > 0) {
                DLOGD("Selected local candidate ID: %s",
                      pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.localCandidateId);
                DLOGD("Selected remote candidate ID: %s",
                      pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.remoteCandidateId);
                // TODO: Display state as a string for readability
                DLOGD("Ice Candidate Pair state: %d", pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.state);
                DLOGD("Nomination state: %s",
                      pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.nominated ? "nominated" : "not nominated");
                averageNumberOfPacketsSentPerSecond =
                    (DOUBLE)(pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.packetsSent -
                             pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevNumberOfPacketsSent) /
                    (DOUBLE) currentMeasureDuration;
                DLOGD("Packet send rate: %lf pkts/sec", averageNumberOfPacketsSentPerSecond);

                averageNumberOfPacketsReceivedPerSecond =
                    (DOUBLE)(pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.packetsReceived -
                             pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevNumberOfPacketsReceived) /
                    (DOUBLE) currentMeasureDuration;
                DLOGD("Packet receive rate: %lf pkts/sec", averageNumberOfPacketsReceivedPerSecond);

                outgoingBitrate = (DOUBLE)(pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.bytesSent -
                                           pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevNumberOfBytesSent * 8.0) /
                    currentMeasureDuration;
                DLOGD("Outgoing bit rate: %lf bps", outgoingBitrate);

                incomingBitrate = (DOUBLE)(pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.bytesReceived -
                                           pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevNumberOfBytesReceived * 8.0) /
                    currentMeasureDuration;
                DLOGD("Incoming bit rate: %lf bps", incomingBitrate);

                averagePacketsDiscardedOnSend =
                    (DOUBLE)(pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.packetsDiscardedOnSend -
                             pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevPacketsDiscardedOnSend) /
                    (DOUBLE) currentMeasureDuration;
                DLOGD("Packet discard rate: %lf pkts/sec", averagePacketsDiscardedOnSend);

                DLOGD("Current STUN request round trip time: %lf sec",
                      pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.currentRoundTripTime);
                DLOGD("Number of STUN responses received: %llu",
                      pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.responsesReceived);

                pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevTs = pGstKvsPlugin->rtcIceCandidatePairMetrics.timestamp;
                pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevNumberOfPacketsSent =
                    pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.packetsSent;
                pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevNumberOfPacketsReceived =
                    pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.packetsReceived;
                pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevNumberOfBytesSent =
                    pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.bytesSent;
                pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevNumberOfBytesReceived =
                    pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.bytesReceived;
                pGstKvsPlugin->streamingSessionList[i]->rtcMetricsHistory.prevPacketsDiscardedOnSend =
                    pGstKvsPlugin->rtcIceCandidatePairMetrics.rtcStatsObject.iceCandidatePairStats.packetsDiscardedOnSend;
            }
        }
    }

CleanUp:

    if (locked) {
        MUTEX_UNLOCK(pGstKvsPlugin->sessionLock);
    }

    return retStatus;
}

PVOID receiveGstreamerAudioVideo(PVOID args)
{
    STATUS retStatus = STATUS_SUCCESS;
    GstElement *pipeline = NULL, *appsrcAudio = NULL;
    GstBus* bus;
    GstMessage* msg;
    GError* error = NULL;
    PWebRtcStreamingSession pStreamingSession = (PWebRtcStreamingSession) args;
    gchar *videoDescription = "", *audioDescription = "", *audioVideoDescription = NULL;

    CHK(pStreamingSession != NULL, STATUS_NULL_ARG);

    // TODO: Wire video up with gstreamer pipeline

    switch (pStreamingSession->pAudioRtcRtpTransceiver->receiver.track.codec) {
        case RTC_CODEC_OPUS:
            audioDescription = "appsrc name=appsrc-audio ! opusparse ! decodebin ! autoaudiosink";
            break;

        case RTC_CODEC_MULAW:
        case RTC_CODEC_ALAW:
            audioDescription = "appsrc name=appsrc-audio ! rawaudioparse ! decodebin ! autoaudiosink";
            break;
        default:
            break;
    }

    audioVideoDescription = g_strjoin(" ", audioDescription, videoDescription, NULL);

    pipeline = gst_parse_launch(audioVideoDescription, &error);

    appsrcAudio = gst_bin_get_by_name(GST_BIN(pipeline), "appsrc-audio");
    CHK_ERR(appsrcAudio != NULL, STATUS_INVALID_OPERATION, "gst_bin_get_by_name(): cant find appsrc");

    transceiverOnFrame(pStreamingSession->pAudioRtcRtpTransceiver, (UINT64) appsrcAudio, onGstAudioFrameReady);

    CHK_STATUS(streamingSessionOnShutdown(pStreamingSession, (UINT64) appsrcAudio, onSampleStreamingSessionShutdown));

    g_free(audioVideoDescription);
    audioVideoDescription = NULL;

    CHK_ERR(pipeline != NULL, STATUS_INVALID_OPERATION,
            "receiveGstreamerAudioVideo(): Failed to launch gstreamer pipeline for receiving audio/video");

    gst_element_set_state(pipeline, GST_STATE_PLAYING);

    // block until error or EOS
    bus = gst_element_get_bus(pipeline);
    msg = gst_bus_timed_pop_filtered(bus, GST_CLOCK_TIME_NONE, GST_MESSAGE_ERROR | GST_MESSAGE_EOS);

    if (msg != NULL) {
        gst_message_unref(msg);
    }

    gst_object_unref(bus);
    gst_element_set_state(pipeline, GST_STATE_NULL);
    gst_object_unref(pipeline);

CleanUp:

    if (error != NULL) {
        DLOGE("GStreamer returned error: %s", error->message);
        g_clear_error(&error);
    }

    if (audioVideoDescription != NULL) {
        g_free(audioVideoDescription);
    }

    return (PVOID)(ULONG_PTR) retStatus;
}

VOID onGstAudioFrameReady(UINT64 customData, PFrame pFrame)
{
    GstFlowReturn ret;
    GstBuffer* buffer;
    GstElement* appsrcAudio = (GstElement*) customData;

    /* Create a new empty buffer */
    buffer = gst_buffer_new_and_alloc(pFrame->size);
    gst_buffer_fill(buffer, 0, pFrame->frameData, pFrame->size);

    /* Push the buffer into the appsrc */
    g_signal_emit_by_name(appsrcAudio, "push-buffer", buffer, &ret);

    /* Free the buffer now that we are done with it */
    gst_buffer_unref(buffer);
}

VOID onSampleStreamingSessionShutdown(UINT64 customData, PWebRtcStreamingSession pStreamingSession)
{
    UNUSED_PARAM(pStreamingSession);
    GstElement* appsrc = (GstElement*) customData;
    GstFlowReturn ret;

    g_signal_emit_by_name(appsrc, "end-of-stream", &ret);
}

STATUS sessionServiceHandler(UINT32 timerId, UINT64 currentTime, UINT64 customData)
{
    STATUS retStatus = STATUS_SUCCESS;
    PGstKvsPlugin pGstKvsPlugin = (PGstKvsPlugin) customData;
    PWebRtcStreamingSession pStreamingSession = NULL;
    UINT32 i, clientIdHash;
    BOOL locked = FALSE, peerConnectionFound = FALSE;
    SIGNALING_CLIENT_STATE signalingClientState;

    CHK(pGstKvsPlugin != NULL, STATUS_NULL_ARG);

    MUTEX_LOCK(pGstKvsPlugin->sessionLock);
    locked = TRUE;

    // scan and cleanup terminated streaming session
    for (i = 0; i < pGstKvsPlugin->streamingSessionCount; ++i) {
        if (ATOMIC_LOAD_BOOL(&pGstKvsPlugin->streamingSessionList[i]->terminateFlag)) {
            pStreamingSession = pGstKvsPlugin->streamingSessionList[i];

            MUTEX_LOCK(pGstKvsPlugin->sessionListReadLock);

            // swap with last element and decrement count
            pGstKvsPlugin->streamingSessionCount--;
            pGstKvsPlugin->streamingSessionList[i] = pGstKvsPlugin->streamingSessionList[pGstKvsPlugin->streamingSessionCount];

            // Remove from the hash table
            clientIdHash = COMPUTE_CRC32((PBYTE) pStreamingSession->peerId, (UINT32) STRLEN(pStreamingSession->peerId));
            CHK_STATUS(hashTableContains(pGstKvsPlugin->pRtcPeerConnectionForRemoteClient, clientIdHash, &peerConnectionFound));
            if (peerConnectionFound) {
                CHK_STATUS(hashTableRemove(pGstKvsPlugin->pRtcPeerConnectionForRemoteClient, clientIdHash));
            }

            MUTEX_UNLOCK(pGstKvsPlugin->sessionListReadLock);

            CHK_STATUS(freeWebRtcStreamingSession(&pStreamingSession));
        }
    }

    // Check if we need to re-create the signaling client on-the-fly
    if (ATOMIC_LOAD_BOOL(&pGstKvsPlugin->recreateSignalingClient) &&
        STATUS_SUCCEEDED(freeSignalingClient(&pGstKvsPlugin->kvsContext.signalingHandle)) &&
        STATUS_SUCCEEDED(createSignalingClientSync(&pGstKvsPlugin->kvsContext.signalingClientInfo, &pGstKvsPlugin->kvsContext.channelInfo,
                                                   &pGstKvsPlugin->kvsContext.signalingClientCallbacks, pGstKvsPlugin->kvsContext.pCredentialProvider,
                                                   &pGstKvsPlugin->kvsContext.signalingHandle))) {
        // Re-set the variable again
        ATOMIC_STORE_BOOL(&pGstKvsPlugin->recreateSignalingClient, FALSE);
    }

    // Check the signaling client state and connect if needed
    if (ATOMIC_LOAD_BOOL(&pGstKvsPlugin->connectWebRtc) && IS_VALID_SIGNALING_CLIENT_HANDLE(pGstKvsPlugin->kvsContext.signalingHandle)) {
        CHK_STATUS(signalingClientGetCurrentState(pGstKvsPlugin->kvsContext.signalingHandle, &signalingClientState));
        if (signalingClientState == SIGNALING_CLIENT_STATE_READY) {
            UNUSED_PARAM(signalingClientConnectSync(pGstKvsPlugin->kvsContext.signalingHandle));
        }
    }

    // Check if any lingering pending message queues
    CHK_STATUS(removeExpiredMessageQueues(pGstKvsPlugin->pPendingSignalingMessageForRemoteClient));

    // periodically wake up and clean up terminated streaming session
    MUTEX_UNLOCK(pGstKvsPlugin->sessionLock);
    locked = FALSE;

CleanUp:

    CHK_LOG_ERR(retStatus);

    if (locked) {
        MUTEX_UNLOCK(pGstKvsPlugin->sessionLock);
    }

    return retStatus;
}

STATUS putFrameToWebRtcPeers(PGstKvsPlugin pGstKvsPlugin, PFrame pFrame, ELEMENTARY_STREAM_NAL_FORMAT nalFormat)
{
    STATUS retStatus = STATUS_SUCCESS;
    PWebRtcStreamingSession pStreamingSession;
    PRtcRtpTransceiver pRtcRtpTransceiver;
    UINT32 i;

    CHK(pGstKvsPlugin != NULL && pFrame != NULL, STATUS_NULL_ARG);

    // Adjust the duration as some peers are sensitive to 0 duration
    if (pFrame->duration == 0) {
        pFrame->duration = GST_PLUGIN_DEFAULT_FRAME_DURATION;
    }

    MUTEX_LOCK(pGstKvsPlugin->sessionListReadLock);
    // Check if the bits need adaptation and if we have any active sessions
    if (IS_AVCC_HEVC_CPD_NAL_FORMAT(nalFormat) && pFrame->trackId == DEFAULT_VIDEO_TRACK_ID && pGstKvsPlugin->streamingSessionCount != 0) {
        CHK_STATUS(adaptVideoFrameFromAvccToAnnexB(pGstKvsPlugin, pFrame, nalFormat));
    }

    for (i = 0; i < pGstKvsPlugin->streamingSessionCount; ++i) {
        pStreamingSession = pGstKvsPlugin->streamingSessionList[i];
        pRtcRtpTransceiver =
            pFrame->trackId == DEFAULT_AUDIO_TRACK_ID ? pStreamingSession->pAudioRtcRtpTransceiver : pStreamingSession->pVideoRtcRtpTransceiver;

        retStatus = writeFrame(pRtcRtpTransceiver, pFrame);

        CHK(retStatus == STATUS_SUCCESS || retStatus == STATUS_SRTP_NOT_READY_YET, retStatus);
        retStatus = STATUS_SUCCESS;
    }
    MUTEX_UNLOCK(pGstKvsPlugin->sessionListReadLock);

CleanUp:

    CHK_LOG_ERR(retStatus);
    return retStatus;
}

STATUS adaptVideoFrameFromAvccToAnnexB(PGstKvsPlugin pGstKvsPlugin, PFrame pFrame, ELEMENTARY_STREAM_NAL_FORMAT nalFormat)
{
    STATUS retStatus = STATUS_SUCCESS;
    PBYTE pCurPnt, pEndPnt, pDst;
    UINT32 runLen = 0, overallSize;
    BOOL includeCpd, iterate = TRUE;
    BYTE naluHeader;

    CHK(pGstKvsPlugin != NULL && pFrame != NULL, STATUS_NULL_ARG);
    CHK(pFrame->size > SIZEOF(UINT32) + 1, STATUS_FORMAT_ERROR);

    pCurPnt = pFrame->frameData;
    pEndPnt = pCurPnt + pFrame->size;

    // Pre-set the defaults before determining whether we need to include the CPD
    includeCpd = FALSE;
    overallSize = pFrame->size;

    // Check if we need to prepend the Annex-B format stored CPD
    // It should only be prepended to an IDR frame.
    // We are skipping over non-RBSP NALus and check if we have an IDR or VPS/SPS/PPS
    if (CHECK_FRAME_FLAG_KEY_FRAME(pFrame->flags)) {
        while (iterate && pCurPnt != pEndPnt) {
            CHK(pCurPnt + SIZEOF(UINT32) < pEndPnt, STATUS_FORMAT_ERROR);

            // Skip the AvCC/HEVC NALu run length
            naluHeader = *(pCurPnt + SIZEOF(UINT32));
            runLen = (UINT32) GET_UNALIGNED_BIG_ENDIAN((PUINT32) pCurPnt);

            // Check if we need to prepend the stored Annex-B formatted CPD only to a key frame
            if ((nalFormat == ELEMENTARY_STREAM_NAL_FORMAT_AVCC && IS_NALU_H264_IDR_HEADER(naluHeader)) ||
                (nalFormat == ELEMENTARY_STREAM_NAL_FORMAT_HEVC && IS_NALU_H265_IDR_HEADER(naluHeader))) {
                includeCpd = TRUE;
                overallSize += pGstKvsPlugin->videoCpdSize;
                iterate = FALSE;
            } else if ((nalFormat == ELEMENTARY_STREAM_NAL_FORMAT_AVCC && IS_NALU_H264_SPS_PPS_HEADER(naluHeader)) ||
                       (nalFormat == ELEMENTARY_STREAM_NAL_FORMAT_HEVC && IS_NALU_H265_VPS_SPS_PPS_HEADER(naluHeader))) {
                iterate = FALSE;
            }

            pCurPnt += runLen + SIZEOF(UINT32);
        }
    }

    pCurPnt = pFrame->frameData;

    // Check if we need to allocate/re-allocate
    if (pGstKvsPlugin->adaptedFrameBufSize < overallSize) {
        CHK(NULL != (pGstKvsPlugin->pAdaptedFrameBuf = (PBYTE) MEMREALLOC(pGstKvsPlugin->pAdaptedFrameBuf, overallSize)), STATUS_NOT_ENOUGH_MEMORY);
        pGstKvsPlugin->adaptedFrameBufSize = overallSize;
    }

    pDst = pGstKvsPlugin->pAdaptedFrameBuf;

    // Copy the stored Annex-B format CPD
    if (includeCpd) {
        MEMCPY(pDst, pGstKvsPlugin->videoCpd, pGstKvsPlugin->videoCpdSize);
        pDst += pGstKvsPlugin->videoCpdSize;
    }

    pEndPnt = pCurPnt + pFrame->size;

    while (pCurPnt != pEndPnt) {
        // Check if we can still read 32 bit
        CHK(pCurPnt + SIZEOF(UINT32) <= pEndPnt, STATUS_FORMAT_ERROR);

        runLen = (UINT32) GET_UNALIGNED_BIG_ENDIAN((PUINT32) pCurPnt);
        pCurPnt += SIZEOF(UINT32);

        CHK(pCurPnt + runLen <= pEndPnt, STATUS_FORMAT_ERROR);

        // Adapt with 4 byte version of the start sequence
        PUT_UNALIGNED_BIG_ENDIAN((PINT32) pDst, 0x0001);

        // Copy the bits to adapted destination
        pDst += SIZEOF(UINT32);
        MEMCPY(pDst, pCurPnt, runLen);

        pDst += runLen;

        // Jump to the next NAL
        pCurPnt += runLen;
    }

    // Set the adapted frame buffer
    pFrame->frameData = pGstKvsPlugin->pAdaptedFrameBuf;
    pFrame->size = overallSize;

CleanUp:

    return retStatus;
}
