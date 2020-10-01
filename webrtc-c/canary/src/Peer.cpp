#include "Include.h"

namespace Canary {

Peer::Peer()
    : pAwsCredentialProvider(nullptr), terminated(FALSE), iceGatheringDone(FALSE), receivedOffer(FALSE), receivedAnswer(FALSE), foundPeerId(FALSE),
      pPeerConnection(nullptr), status(STATUS_SUCCESS)
{
}

Peer::~Peer()
{
    CHK_LOG_ERR(freePeerConnection(&this->pPeerConnection));
    CHK_LOG_ERR(freeSignalingClient(&this->pSignalingClientHandle));
    CHK_LOG_ERR(freeStaticCredentialProvider(&this->pAwsCredentialProvider));
}

STATUS Peer::init(const Canary::PConfig pConfig, const Callbacks& callbacks)
{
    STATUS retStatus = STATUS_SUCCESS;

    this->isMaster = pConfig->isMaster.value;
    this->trickleIce = pConfig->trickleIce.value;
    this->callbacks = callbacks;
    this->canaryOutgoingRTPMetricsContext.prevTs = GETTIME();
    this->canaryOutgoingRTPMetricsContext.prevFramesDiscardedOnSend = 0;
    this->canaryOutgoingRTPMetricsContext.prevNackCount = 0;
    this->canaryOutgoingRTPMetricsContext.prevRetxBytesSent = 0;
    this->canaryOutgoingRTPMetricsContext.prevFramesSent = 0;
    this->canaryIncomingRTPMetricsContext.prevPacketsReceived = 0;
    this->canaryIncomingRTPMetricsContext.prevBytesReceived = 0;
    this->canaryIncomingRTPMetricsContext.prevFramesDropped = 0;
    this->canaryIncomingRTPMetricsContext.prevTs = GETTIME();

    CHK_STATUS(createStaticCredentialProvider((PCHAR) pConfig->accessKey.value, 0, (PCHAR) pConfig->secretKey.value, 0,
                                              (PCHAR) pConfig->sessionToken.value, 0, MAX_UINT64, &pAwsCredentialProvider));
    CHK_STATUS(initSignaling(pConfig));
    CHK_STATUS(initRtcConfiguration(pConfig));

CleanUp:

    return retStatus;
}

STATUS Peer::initSignaling(const Canary::PConfig pConfig)
{
    STATUS retStatus = STATUS_SUCCESS;

    SignalingClientInfo clientInfo;
    ChannelInfo channelInfo;
    SignalingClientCallbacks clientCallbacks;

    MEMSET(&clientInfo, 0, SIZEOF(clientInfo));
    MEMSET(&channelInfo, 0, SIZEOF(channelInfo));
    MEMSET(&clientCallbacks, 0, SIZEOF(clientCallbacks));

    clientInfo.version = SIGNALING_CLIENT_INFO_CURRENT_VERSION;
    clientInfo.loggingLevel = pConfig->logLevel.value;
    STRCPY(clientInfo.clientId, pConfig->clientId.value);

    channelInfo.version = CHANNEL_INFO_CURRENT_VERSION;
    channelInfo.pChannelName = (PCHAR) pConfig->channelName.value;
    channelInfo.pKmsKeyId = NULL;
    channelInfo.tagCount = 0;
    channelInfo.pTags = NULL;
    channelInfo.channelType = SIGNALING_CHANNEL_TYPE_SINGLE_MASTER;
    channelInfo.channelRoleType = pConfig->isMaster.value ? SIGNALING_CHANNEL_ROLE_TYPE_MASTER : SIGNALING_CHANNEL_ROLE_TYPE_VIEWER;
    channelInfo.cachingPolicy = SIGNALING_API_CALL_CACHE_TYPE_FILE;
    channelInfo.cachingPeriod = SIGNALING_API_CALL_CACHE_TTL_SENTINEL_VALUE;
    channelInfo.asyncIceServerConfig = TRUE;
    channelInfo.retry = TRUE;
    channelInfo.reconnect = TRUE;
    channelInfo.pCertPath = (PCHAR) DEFAULT_KVS_CACERT_PATH;
    channelInfo.messageTtl = 0; // Default is 60 seconds

    clientCallbacks.customData = (UINT64) this;
    clientCallbacks.stateChangeFn = [](UINT64 customData, SIGNALING_CLIENT_STATE state) -> STATUS {
        STATUS retStatus = STATUS_SUCCESS;
        PPeer pPeer = (PPeer) customData;
        PCHAR pStateStr;

        signalingClientGetStateString(state, &pStateStr);
        DLOGD("Signaling client state changed to %d - '%s'", state, pStateStr);

        switch (state) {
            case SIGNALING_CLIENT_STATE_NEW:
                pPeer->signalingStartTime = GETTIME();
                break;
            case SIGNALING_CLIENT_STATE_CONNECTED: {
                auto duration = (GETTIME() - pPeer->signalingStartTime) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
                DLOGI("Signaling took %lu ms to connect", duration);
                Canary::Cloudwatch::getInstance().monitoring.pushSignalingInitDelay(duration, StandardUnit::Milliseconds);
                break;
            }
            default:
                break;
        }

        // Return success to continue
        return retStatus;
    };
    clientCallbacks.errorReportFn = [](UINT64 customData, STATUS status, PCHAR msg, UINT32 msgLen) -> STATUS {
        PPeer pPeer = (PPeer) customData;
        DLOGW("Signaling client generated an error 0x%08x - '%.*s'", status, msgLen, msg);

        // When an error happens with signaling, we'll let it crash so that this canary can be restarted.
        // The error will be captured in at higher level metrics.
        if (status == STATUS_SIGNALING_ICE_CONFIG_REFRESH_FAILED || status == STATUS_SIGNALING_RECONNECT_FAILED) {
            pPeer->status = status;

            // Let the higher level to terminate
            if (pPeer->callbacks.onDisconnected != NULL) {
                pPeer->callbacks.onDisconnected();
            }
        }

        return STATUS_SUCCESS;
    };
    clientCallbacks.messageReceivedFn = [](UINT64 customData, PReceivedSignalingMessage pMsg) -> STATUS {
        STATUS retStatus = STATUS_SUCCESS;
        PPeer pPeer = (PPeer) customData;
        std::lock_guard<std::recursive_mutex> lock(pPeer->mutex);

        if (!pPeer->foundPeerId.load()) {
            pPeer->peerId = pMsg->signalingMessage.peerClientId;
            DLOGI("Found peer id: %s", pPeer->peerId.c_str());
            pPeer->foundPeerId = TRUE;
            CHK_STATUS(pPeer->initPeerConnection());
        }

        if (pPeer->isMaster && STRCMP(pPeer->peerId.c_str(), pMsg->signalingMessage.peerClientId) != 0) {
            DLOGW("Unexpected receiving message from extra peer: %s", pMsg->signalingMessage.peerClientId);
            CHK(FALSE, retStatus);
        }

        DLOGD("Handling signaling message:\n%s", pMsg->signalingMessage.payload);
        CHK_STATUS(pPeer->handleSignalingMsg(pMsg));

    CleanUp:

        return retStatus;
    };

    CHK_STATUS(createSignalingClientSync(&clientInfo, &channelInfo, &clientCallbacks, pAwsCredentialProvider, &pSignalingClientHandle));

CleanUp:

    return retStatus;
}

STATUS Peer::initRtcConfiguration(const Canary::PConfig pConfig)
{
    auto awaitGetIceConfigInfoCount = [](SIGNALING_CLIENT_HANDLE pSignalingClientHandle, PUINT32 pIceConfigInfoCount) -> STATUS {
        STATUS retStatus = STATUS_SUCCESS;
        UINT64 elapsed = 0;

        CHK(IS_VALID_SIGNALING_CLIENT_HANDLE(pSignalingClientHandle) && pIceConfigInfoCount != NULL, STATUS_NULL_ARG);

        while (TRUE) {
            // Get the configuration count
            CHK_STATUS(signalingClientGetIceConfigInfoCount(pSignalingClientHandle, pIceConfigInfoCount));

            // Return OK if we have some ice configs
            CHK(*pIceConfigInfoCount == 0, retStatus);

            // Check for timeout
            CHK_ERR(elapsed <= ASYNC_ICE_CONFIG_INFO_WAIT_TIMEOUT, STATUS_OPERATION_TIMED_OUT,
                    "Couldn't retrieve ICE configurations in alotted time.");

            THREAD_SLEEP(ICE_CONFIG_INFO_POLL_PERIOD);
            elapsed += ICE_CONFIG_INFO_POLL_PERIOD;
        }

    CleanUp:

        return retStatus;
    };

    STATUS retStatus = STATUS_SUCCESS;
    auto pSignalingClientHandle = this->pSignalingClientHandle;
    UINT32 i, j, iceConfigCount, uriCount;
    PIceConfigInfo pIceConfigInfo;
    PRtcConfiguration pConfiguration = &this->rtcConfiguration;

    MEMSET(pConfiguration, 0x00, SIZEOF(RtcConfiguration));

    // Set this to custom callback to enable filtering of interfaces
    pConfiguration->kvsRtcConfiguration.iceSetInterfaceFilterFunc = NULL;

    if (pConfig->forceTurn.value) {
        pConfiguration->iceTransportPolicy = ICE_TRANSPORT_POLICY_RELAY;
    }

    // Set the  STUN server
    SNPRINTF(pConfiguration->iceServers[0].urls, MAX_ICE_CONFIG_URI_LEN, KINESIS_VIDEO_STUN_URL, pConfig->region.value);

    if (pConfig->useTurn.value) {
        // Set the URIs from the configuration
        CHK_STATUS(awaitGetIceConfigInfoCount(pSignalingClientHandle, &iceConfigCount));

        /* signalingClientGetIceConfigInfoCount can return more than one turn server. Use only one to optimize
         * candidate gathering latency. But user can also choose to use more than 1 turn server. */
        for (uriCount = 0, i = 0; i < MAX_TURN_SERVERS; i++) {
            CHK_STATUS(signalingClientGetIceConfigInfo(pSignalingClientHandle, i, &pIceConfigInfo));
            for (j = 0; j < pIceConfigInfo->uriCount; j++) {
                CHECK(uriCount < MAX_ICE_SERVERS_COUNT);
                /*
                 * if configuration.iceServers[uriCount + 1].urls is "turn:ip:port?transport=udp" then ICE will try TURN over UDP
                 * if configuration.iceServers[uriCount + 1].urls is "turn:ip:port?transport=tcp" then ICE will try TURN over TCP/TLS
                 * if configuration.iceServers[uriCount + 1].urls is "turns:ip:port?transport=udp", it's currently ignored because sdk dont do
                 * TURN over DTLS yet. if configuration.iceServers[uriCount + 1].urls is "turns:ip:port?transport=tcp" then ICE will try TURN over
                 * TCP/TLS if configuration.iceServers[uriCount + 1].urls is "turn:ip:port" then ICE will try both TURN over UPD and TCP/TLS
                 *
                 * It's recommended to not pass too many TURN iceServers to configuration because it will slow down ice gathering in non-trickle
                 * mode.
                 */

                STRNCPY(pConfiguration->iceServers[uriCount + 1].urls, pIceConfigInfo->uris[j], MAX_ICE_CONFIG_URI_LEN);
                STRNCPY(pConfiguration->iceServers[uriCount + 1].credential, pIceConfigInfo->password, MAX_ICE_CONFIG_CREDENTIAL_LEN);
                STRNCPY(pConfiguration->iceServers[uriCount + 1].username, pIceConfigInfo->userName, MAX_ICE_CONFIG_USER_NAME_LEN);

                uriCount++;
            }
        }
    }

CleanUp:

    return retStatus;
}

STATUS Peer::initPeerConnection()
{
    auto handleOnIceCandidate = [](UINT64 customData, PCHAR candidateJson) -> VOID {
        STATUS retStatus = STATUS_SUCCESS;
        auto pPeer = (PPeer) customData;
        SignalingMessage message;

        if (candidateJson == NULL) {
            DLOGD("ice candidate gathering finished");
            pPeer->iceGatheringDone = TRUE;
            pPeer->cvar.notify_all();
        } else if (pPeer->trickleIce) {
            message.messageType = SIGNALING_MESSAGE_TYPE_ICE_CANDIDATE;
            STRCPY(message.payload, candidateJson);
            CHK_STATUS(pPeer->send(&message));
        }

    CleanUp:

        CHK_LOG_ERR(retStatus);
    };

    auto onConnectionStateChange = [](UINT64 customData, RTC_PEER_CONNECTION_STATE newState) -> VOID {
        auto pPeer = (PPeer) customData;

        DLOGI("New connection state %u", newState);

        switch (newState) {
            case RTC_PEER_CONNECTION_STATE_CONNECTING:
                pPeer->iceHolePunchingStartTime = GETTIME();
                break;
            case RTC_PEER_CONNECTION_STATE_CONNECTED: {
                auto duration = (GETTIME() - pPeer->iceHolePunchingStartTime) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
                DLOGI("ICE hole punching took %lu ms", duration);
                Canary::Cloudwatch::getInstance().monitoring.pushICEHolePunchingDelay(duration, StandardUnit::Milliseconds);
                break;
            }
            case RTC_PEER_CONNECTION_STATE_FAILED:
                // TODO: Replace this with a proper error code. Since there's no way to get the actual error code
                // at this moment, STATUS_PEERCONNECTION_BASE seems to be the best error code.
                pPeer->status = STATUS_PEERCONNECTION_BASE;
                // explicit fallthrough
            case RTC_PEER_CONNECTION_STATE_CLOSED:
                // explicit fallthrough
            case RTC_PEER_CONNECTION_STATE_DISCONNECTED:
                // Let the higher level to terminate
                if (pPeer->callbacks.onDisconnected != NULL) {
                    pPeer->callbacks.onDisconnected();
                }
                break;
            default:
                break;
        }
    };

    STATUS retStatus = STATUS_SUCCESS;
    CHK(this->pPeerConnection == NULL, STATUS_INVALID_OPERATION);
    CHK_STATUS(createPeerConnection(&this->rtcConfiguration, &this->pPeerConnection));
    CHK_STATUS(peerConnectionOnIceCandidate(this->pPeerConnection, (UINT64) this, handleOnIceCandidate));
    CHK_STATUS(peerConnectionOnConnectionStateChange(this->pPeerConnection, (UINT64) this, onConnectionStateChange));

    if (this->callbacks.onNewConnection != NULL) {
        this->callbacks.onNewConnection(this);
    }

CleanUp:

    return retStatus;
}

STATUS Peer::shutdown()
{
    this->terminated = TRUE;

    this->cvar.notify_all();
    {
        // lock to wait until awoken thread finish.
        std::lock_guard<std::recursive_mutex> lock(this->mutex);
    }

    if (this->pPeerConnection != NULL) {
        CHK_LOG_ERR(closePeerConnection(this->pPeerConnection));
    }

    if (!this->isMaster && IS_VALID_SIGNALING_CLIENT_HANDLE(this->pSignalingClientHandle)) {
        CHK_LOG_ERR(signalingClientDeleteSync(this->pSignalingClientHandle));
    }

    return this->status;
}

STATUS Peer::connect()
{
    auto connectPeerConnection = [this]() -> STATUS {
        STATUS retStatus = STATUS_SUCCESS;
        RtcSessionDescriptionInit offerSDPInit;
        UINT32 buffLen;
        SignalingMessage msg;

        MEMSET(&offerSDPInit, 0, SIZEOF(offerSDPInit));
        CHK_STATUS(createOffer(this->pPeerConnection, &offerSDPInit));
        CHK_STATUS(setLocalDescription(this->pPeerConnection, &offerSDPInit));

        if (!this->trickleIce) {
            CHK_STATUS(this->awaitIceGathering(&offerSDPInit));
        }

        msg.messageType = SIGNALING_MESSAGE_TYPE_OFFER;
        CHK_STATUS(serializeSessionDescriptionInit(&offerSDPInit, NULL, &buffLen));
        CHK_STATUS(serializeSessionDescriptionInit(&offerSDPInit, msg.payload, &buffLen));
        CHK_STATUS(this->send(&msg));

    CleanUp:

        return retStatus;
    };

    STATUS retStatus = STATUS_SUCCESS;
    CHK_STATUS(signalingClientConnectSync(pSignalingClientHandle));

    if (!this->isMaster) {
        this->foundPeerId = TRUE;
        this->peerId = DEFAULT_VIEWER_PEER_ID;
        CHK_STATUS(this->initPeerConnection());
        CHK_STATUS(connectPeerConnection());
    }

CleanUp:

    return retStatus;
}

STATUS Peer::send(PSignalingMessage pMsg)
{
    STATUS retStatus = STATUS_SUCCESS;

    if (this->foundPeerId.load()) {
        pMsg->version = SIGNALING_MESSAGE_CURRENT_VERSION;
        pMsg->correlationId[0] = '\0';
        STRCPY(pMsg->peerClientId, peerId.c_str());
        pMsg->payloadLen = (UINT32) STRLEN(pMsg->payload);
        CHK_STATUS(signalingClientSendMessageSync(this->pSignalingClientHandle, pMsg));
    } else {
        // TODO: maybe queue messages when there's no peer id
        DLOGW("Peer id hasn't been found yet. Failed to send a signaling message");
    }

CleanUp:

    return retStatus;
}

STATUS Peer::awaitIceGathering(PRtcSessionDescriptionInit pSDPInit)
{
    STATUS retStatus = STATUS_SUCCESS;
    std::unique_lock<std::recursive_mutex> lock(this->mutex);
    this->cvar.wait(lock, [this]() { return this->terminated.load() || this->iceGatheringDone.load(); });
    CHK_WARN(!this->terminated.load(), STATUS_OPERATION_TIMED_OUT, "application terminated and candidate gathering still not done");

    CHK_STATUS(peerConnectionGetLocalDescription(this->pPeerConnection, pSDPInit));

CleanUp:

    return retStatus;
};

STATUS Peer::handleSignalingMsg(PReceivedSignalingMessage pMsg)
{
    auto handleOffer = [this](SignalingMessage& msg) -> STATUS {
        STATUS retStatus = STATUS_SUCCESS;
        RtcSessionDescriptionInit offerSDPInit, answerSDPInit;
        NullableBool canTrickle;
        UINT32 buffLen;

        if (!this->isMaster) {
            DLOGW("Unexpected message SIGNALING_MESSAGE_TYPE_OFFER");
            CHK(FALSE, retStatus);
        }

        if (receivedOffer.exchange(TRUE)) {
            DLOGW("Offer already received, ignore new offer from client id %s", msg.peerClientId);
            CHK(FALSE, retStatus);
        }

        MEMSET(&offerSDPInit, 0, SIZEOF(offerSDPInit));
        MEMSET(&answerSDPInit, 0, SIZEOF(answerSDPInit));

        CHK_STATUS(deserializeSessionDescriptionInit(msg.payload, msg.payloadLen, &offerSDPInit));
        CHK_STATUS(setRemoteDescription(this->pPeerConnection, &offerSDPInit));

        canTrickle = canTrickleIceCandidates(this->pPeerConnection);
        /* cannot be null after setRemoteDescription */
        CHECK(!NULLABLE_CHECK_EMPTY(canTrickle));

        CHK_STATUS(createAnswer(this->pPeerConnection, &answerSDPInit));
        CHK_STATUS(setLocalDescription(this->pPeerConnection, &answerSDPInit));

        if (!canTrickle.value) {
            CHK_STATUS(this->awaitIceGathering(&answerSDPInit));
        }

        msg.messageType = SIGNALING_MESSAGE_TYPE_ANSWER;
        CHK_STATUS(serializeSessionDescriptionInit(&answerSDPInit, NULL, &buffLen));
        CHK_STATUS(serializeSessionDescriptionInit(&answerSDPInit, msg.payload, &buffLen));

        CHK_STATUS(this->send(&msg));

    CleanUp:

        return retStatus;
    };

    auto handleAnswer = [this](SignalingMessage& msg) -> STATUS {
        STATUS retStatus = STATUS_SUCCESS;
        RtcSessionDescriptionInit answerSDPInit;

        if (this->isMaster) {
            DLOGW("Unexpected message SIGNALING_MESSAGE_TYPE_ANSWER");
        } else if (receivedAnswer.exchange(TRUE)) {
            DLOGW("Offer already received, ignore new offer from client id %s", msg.peerClientId);
        } else {
            MEMSET(&answerSDPInit, 0x00, SIZEOF(RtcSessionDescriptionInit));

            CHK_STATUS(deserializeSessionDescriptionInit(msg.payload, msg.payloadLen, &answerSDPInit));
            CHK_STATUS(setRemoteDescription(this->pPeerConnection, &answerSDPInit));
        }

    CleanUp:

        return retStatus;
    };

    auto handleICECandidate = [this](SignalingMessage& msg) -> STATUS {
        STATUS retStatus = STATUS_SUCCESS;
        RtcIceCandidateInit iceCandidate;

        CHK_STATUS(deserializeRtcIceCandidateInit(msg.payload, msg.payloadLen, &iceCandidate));
        CHK_STATUS(addIceCandidate(this->pPeerConnection, iceCandidate.candidate));

    CleanUp:

        CHK_LOG_ERR(retStatus);
        return retStatus;
    };

    STATUS retStatus = STATUS_SUCCESS;
    auto& msg = pMsg->signalingMessage;

    CHK(!this->terminated.load(), retStatus);
    switch (msg.messageType) {
        case SIGNALING_MESSAGE_TYPE_OFFER:
            CHK_STATUS(handleOffer(msg));
            break;
        case SIGNALING_MESSAGE_TYPE_ICE_CANDIDATE:
            CHK_STATUS(handleICECandidate(msg));
            break;
        case SIGNALING_MESSAGE_TYPE_ANSWER:
            CHK_STATUS(handleAnswer(msg));
            break;
        default:
            DLOGW("Unknown message type %u", msg.messageType);
            break;
    }

CleanUp:

    return retStatus;
}

STATUS Peer::addTransceiver(RtcMediaStreamTrack& track)
{
    auto handleBandwidthEstimation = [](UINT64 customData, DOUBLE maxiumBitrate) -> VOID {
        UNUSED_PARAM(customData);
        // TODO: Probably reexpose or add metrics here directly
        DLOGV("received bitrate suggestion: %f", maxiumBitrate);
    };

    auto handleVideoFrame = [](UINT64 customData, PFrame pFrame) -> VOID {
        PPeer pPeer = (Canary::PPeer)(customData);
        std::unique_lock<std::recursive_mutex> lock(pPeer->mutex);
        PBYTE frameDataPtr = pFrame->frameData + ANNEX_B_NALU_SIZE;
        UINT32 rawPacketSize = 0;

        // Get size of hex encoded data
        hexDecode((PCHAR) frameDataPtr, pFrame->size - ANNEX_B_NALU_SIZE, NULL, &rawPacketSize);
        PBYTE rawPacket = (PBYTE) MEMCALLOC(1, (rawPacketSize * SIZEOF(BYTE)));
        hexDecode((PCHAR) frameDataPtr, pFrame->size - ANNEX_B_NALU_SIZE, rawPacket, &rawPacketSize);

        // Extract the timestamp field from raw packet
        frameDataPtr = rawPacket;
        UINT64 receivedTs = getUnalignedInt64BigEndian((PINT64)(frameDataPtr));
        frameDataPtr += SIZEOF(UINT64);
        UINT32 receivedSize = getUnalignedInt32BigEndian((PINT32)(frameDataPtr));

        pPeer->endToEndMetricsContext.frameLatency.push_back((DOUBLE)(GETTIME() - receivedTs) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);

        // Do a size match of the raw packet. Since raw packet does not contain the NALu, the
        // comparison would be rawPacketSize + ANNEX_B_NALU_SIZE and the received size
        pPeer->endToEndMetricsContext.sizeMatch.push_back((rawPacketSize + ANNEX_B_NALU_SIZE) == receivedSize ? 0.0 : 1.0);
        SAFE_MEMFREE(rawPacket);
    };

    PRtcRtpTransceiver pTransceiver;
    STATUS retStatus = STATUS_SUCCESS;

    CHK_STATUS(::addTransceiver(pPeerConnection, &track, NULL, &pTransceiver));
    if (track.kind == MEDIA_STREAM_TRACK_KIND_VIDEO) {
        this->videoTransceivers.push_back(pTransceiver);

        // As part of canaries, we will only be monitoring video transceiver as we do for every other metrics
        CHK_STATUS(transceiverOnFrame(pTransceiver, (UINT64) this, handleVideoFrame));
    } else {
        this->audioTransceivers.push_back(pTransceiver);
    }

    CHK_STATUS(transceiverOnBandwidthEstimation(pTransceiver, (UINT64) this, handleBandwidthEstimation));

CleanUp:

    return retStatus;
}

STATUS Peer::addSupportedCodec(RTC_CODEC codec)
{
    STATUS retStatus = STATUS_SUCCESS;

    CHK_STATUS(::addSupportedCodec(pPeerConnection, codec));

CleanUp:

    return retStatus;
}

STATUS Peer::writeFrame(PFrame pFrame, MEDIA_STREAM_TRACK_KIND kind)
{
    STATUS retStatus = STATUS_SUCCESS;

    auto& transceivers = kind == MEDIA_STREAM_TRACK_KIND_VIDEO ? this->videoTransceivers : this->audioTransceivers;
    if (kind == MEDIA_STREAM_TRACK_KIND_VIDEO) {
        std::lock_guard<std::mutex> lock(this->countUpdateMutex);
        if (this->recorded.load()) {
            this->canaryOutgoingRTPMetricsContext.videoFramesGenerated = 0;
            this->canaryOutgoingRTPMetricsContext.videoBytesGenerated = 0;
            this->recorded = FALSE;
        }
        this->canaryOutgoingRTPMetricsContext.videoFramesGenerated++;
        this->canaryOutgoingRTPMetricsContext.videoBytesGenerated += pFrame->size;
    }
    for (auto& transceiver : transceivers) {
        retStatus = ::writeFrame(transceiver, pFrame);
        if (retStatus == STATUS_SRTP_NOT_READY_YET) {
            // do nothing
            retStatus = STATUS_SUCCESS;
        }

        CHK_STATUS(retStatus);
    }

CleanUp:

    return STATUS_SUCCESS;
}

STATUS Peer::populateOutgoingRtpMetricsContext()
{
    DOUBLE currentDuration = 0;

    currentDuration = (DOUBLE)(this->canaryMetrics.timestamp - this->canaryOutgoingRTPMetricsContext.prevTs) / HUNDREDS_OF_NANOS_IN_A_SECOND;
    DLOGD("duration:%lf", currentDuration);
    {
        std::lock_guard<std::mutex> lock(this->countUpdateMutex);
        this->canaryOutgoingRTPMetricsContext.framesPercentageDiscarded =
            ((DOUBLE)(this->canaryMetrics.rtcStatsObject.outboundRtpStreamStats.framesDiscardedOnSend -
                      this->canaryOutgoingRTPMetricsContext.prevFramesDiscardedOnSend) /
             (DOUBLE) this->canaryOutgoingRTPMetricsContext.videoFramesGenerated) *
            100.0;
        this->canaryOutgoingRTPMetricsContext.retxBytesPercentage =
            (((DOUBLE) this->canaryMetrics.rtcStatsObject.outboundRtpStreamStats.retransmittedBytesSent -
              (DOUBLE)(this->canaryOutgoingRTPMetricsContext.prevRetxBytesSent)) /
             (DOUBLE) this->canaryOutgoingRTPMetricsContext.videoBytesGenerated) *
            100.0;
    }

    // This flag ensures the reset of video bytes count is done only when this flag is set
    this->recorded = TRUE;
    this->canaryOutgoingRTPMetricsContext.averageFramesSentPerSecond =
        ((DOUBLE)(this->canaryMetrics.rtcStatsObject.outboundRtpStreamStats.framesSent -
                  (DOUBLE) this->canaryOutgoingRTPMetricsContext.prevFramesSent)) /
        currentDuration;
    this->canaryOutgoingRTPMetricsContext.nacksPerSecond =
        ((DOUBLE) this->canaryMetrics.rtcStatsObject.outboundRtpStreamStats.nackCount - this->canaryOutgoingRTPMetricsContext.prevNackCount) /
        currentDuration;
    this->canaryOutgoingRTPMetricsContext.prevFramesSent = this->canaryMetrics.rtcStatsObject.outboundRtpStreamStats.framesSent;
    this->canaryOutgoingRTPMetricsContext.prevTs = this->canaryMetrics.timestamp;
    this->canaryOutgoingRTPMetricsContext.prevFramesDiscardedOnSend = this->canaryMetrics.rtcStatsObject.outboundRtpStreamStats.framesDiscardedOnSend;
    this->canaryOutgoingRTPMetricsContext.prevNackCount = this->canaryMetrics.rtcStatsObject.outboundRtpStreamStats.nackCount;
    this->canaryOutgoingRTPMetricsContext.prevRetxBytesSent = this->canaryMetrics.rtcStatsObject.outboundRtpStreamStats.retransmittedBytesSent;

    DLOGD("Frames discard percent: %lf", this->canaryOutgoingRTPMetricsContext.framesPercentageDiscarded);
    DLOGD("Average frame rate: %lf", this->canaryOutgoingRTPMetricsContext.averageFramesSentPerSecond);
    DLOGD("Nack rate: %lf", this->canaryOutgoingRTPMetricsContext.nacksPerSecond);
    DLOGD("Retransmission percent: %lf", this->canaryOutgoingRTPMetricsContext.retxBytesPercentage);

    return STATUS_SUCCESS;
}

STATUS Peer::populateIncomingRtpMetricsContext()
{
    DOUBLE currentDuration = 0;
    currentDuration = (DOUBLE)(this->canaryMetrics.timestamp - this->canaryIncomingRTPMetricsContext.prevTs) / HUNDREDS_OF_NANOS_IN_A_SECOND;
    DLOGD("duration:%lf", currentDuration);
    this->canaryIncomingRTPMetricsContext.packetReceiveRate =
        (DOUBLE)(this->canaryMetrics.rtcStatsObject.inboundRtpStreamStats.received.packetsReceived -
                 this->canaryIncomingRTPMetricsContext.prevPacketsReceived) /
        currentDuration;
    this->canaryIncomingRTPMetricsContext.incomingBitRate =
        ((DOUBLE)(this->canaryMetrics.rtcStatsObject.inboundRtpStreamStats.bytesReceived - this->canaryIncomingRTPMetricsContext.prevBytesReceived) /
         currentDuration) /
        0.008;
    this->canaryIncomingRTPMetricsContext.framesDroppedPerSecond =
        ((DOUBLE) this->canaryMetrics.rtcStatsObject.inboundRtpStreamStats.received.framesDropped -
         this->canaryIncomingRTPMetricsContext.prevFramesDropped) /
        currentDuration;

    this->canaryIncomingRTPMetricsContext.prevPacketsReceived = this->canaryMetrics.rtcStatsObject.inboundRtpStreamStats.received.packetsReceived;
    this->canaryIncomingRTPMetricsContext.prevBytesReceived = this->canaryMetrics.rtcStatsObject.inboundRtpStreamStats.bytesReceived;
    this->canaryIncomingRTPMetricsContext.prevFramesDropped = this->canaryMetrics.rtcStatsObject.inboundRtpStreamStats.received.framesDropped;
    this->canaryIncomingRTPMetricsContext.prevTs = this->canaryMetrics.timestamp;

    DLOGD("Packet receive rate: %lf", this->canaryIncomingRTPMetricsContext.packetReceiveRate);
    DLOGD("Incoming bit rate: %lf", this->canaryIncomingRTPMetricsContext.incomingBitRate / 1024.0);
    DLOGD("Frame drop rate: %lf", this->canaryIncomingRTPMetricsContext.framesDroppedPerSecond);

    return STATUS_SUCCESS;
}
STATUS Peer::publishStatsForCanary(RTC_STATS_TYPE statsType)
{
    STATUS retStatus = STATUS_SUCCESS;
    this->canaryMetrics.requestedTypeOfStats = statsType;
    switch (statsType) {
        case RTC_STATS_TYPE_OUTBOUND_RTP:
            CHK_LOG_ERR(::rtcPeerConnectionGetMetrics(this->pPeerConnection, this->videoTransceivers.back(), &this->canaryMetrics));
            this->populateOutgoingRtpMetricsContext();
            Canary::Cloudwatch::getInstance().monitoring.pushOutboundRtpStats(&this->canaryOutgoingRTPMetricsContext);
            break;
        case RTC_STATS_TYPE_INBOUND_RTP:
            CHK_LOG_ERR(::rtcPeerConnectionGetMetrics(this->pPeerConnection, this->videoTransceivers.back(), &this->canaryMetrics));
            this->populateIncomingRtpMetricsContext();
            Canary::Cloudwatch::getInstance().monitoring.pushInboundRtpStats(&this->canaryIncomingRTPMetricsContext);

            break;
        default:
            CHK(FALSE, STATUS_NOT_IMPLEMENTED);
    }
CleanUp:
    return retStatus;
}

STATUS Peer::publishEndToEndMetrics()
{
    Canary::Cloudwatch::getInstance().monitoring.pushEndToEndMetrics(&this->endToEndMetricsContext);

    return STATUS_SUCCESS;
}

} // namespace Canary
