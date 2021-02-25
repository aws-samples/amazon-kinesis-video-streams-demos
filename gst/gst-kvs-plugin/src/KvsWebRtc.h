#ifndef __KVS_WEBRTC_FUNCTIONALITY_H__
#define __KVS_WEBRTC_FUNCTIONALITY_H__

#define DEFAULT_MASTER_CLIENT_ID       "KvsPluginMaster"
#define DEFAULT_VIEWER_CLIENT_ID       "KvsPluginViewer"
#define DEFAULT_CHANNEL_NAME           "DEFAULT_CHANNEL"
#define DEFAULT_TRICKLE_ICE_MODE       TRUE
#define DEFAULT_WEBRTC_CONNECTION_MODE WEBRTC_CONNECTION_MODE_DEFAULT
#define DEFAULT_WEBRTC_CONNECT         TRUE

#define GST_PLUGIN_HASH_TABLE_BUCKET_COUNT  50
#define GST_PLUGIN_HASH_TABLE_BUCKET_LENGTH 2

#define GST_PLUGIN_PRE_GENERATE_CERT_START          (200 * HUNDREDS_OF_NANOS_IN_A_MILLISECOND)
#define GST_PLUGIN_PRE_GENERATE_CERT_PERIOD         (1000 * HUNDREDS_OF_NANOS_IN_A_MILLISECOND)
#define GST_PLUGIN_PENDING_MESSAGE_CLEANUP_DURATION (20 * HUNDREDS_OF_NANOS_IN_A_SECOND)
#define GST_PLUGIN_STATS_DURATION                   (60 * HUNDREDS_OF_NANOS_IN_A_SECOND)
#define GST_PLUGIN_SERVICE_ROUTINE_START            (300 * HUNDREDS_OF_NANOS_IN_A_MILLISECOND)
#define GST_PLUGIN_SERVICE_ROUTINE_PERIOD           (1000 * HUNDREDS_OF_NANOS_IN_A_MILLISECOND)

// Default opus frame duration
#define GST_PLUGIN_DEFAULT_FRAME_DURATION (20 * HUNDREDS_OF_NANOS_IN_A_MILLISECOND)

// IDR NALU type value
#define IDR_NALU_TYPE      0x05
#define H264_SPS_NALU_TYPE 0x07
#define H264_PPS_NALU_TYPE 0x08

// H265 IDR NALU type value
#define IDR_W_RADL_NALU_TYPE 0x13
#define IDR_N_LP_NALU_TYPE   0x14
#define H265_VPS_NALU_TYPE   0x20
#define H265_SPS_NALU_TYPE   0x21
#define H265_PPS_NALU_TYPE   0x22

#define KVS_WEBRTC_CLIENT_USER_AGENT_NAME "KVS_GST_PLUGIN_WEBRTC"

#define IS_NALU_H264_IDR_HEADER(h) (((h) &0x80) == 0 && ((h) &0x60) != 0 && ((h) &0x1f) == IDR_NALU_TYPE)
#define IS_NALU_H264_SPS_PPS_HEADER(h)                                                                                                               \
    (((h) &0x80) == 0 && ((h) &0x60) != 0 && (((h) &0x1f) == H264_SPS_NALU_TYPE || ((h) &0x1f) == H264_PPS_NALU_TYPE))
#define IS_NALU_H265_IDR_HEADER(h)         ((((h) >> 1) == IDR_W_RADL_NALU_TYPE || ((h) >> 1) == IDR_N_LP_NALU_TYPE))
#define IS_NALU_H265_VPS_SPS_PPS_HEADER(h) (((h) >> 1) == H265_VPS_NALU_TYPE || ((h) >> 1) == H265_SPS_NALU_TYPE || ((h) >> 1) == H265_PPS_NALU_TYPE)

typedef VOID (*StreamSessionShutdownCallback)(UINT64, PWebRtcStreamingSession);

STATUS signalingClientStateChangedFn(UINT64, SIGNALING_CLIENT_STATE);
STATUS signalingClientErrorFn(UINT64, STATUS, PCHAR, UINT32);
STATUS signalingClientMessageReceivedFn(UINT64, PReceivedSignalingMessage);
STATUS initKinesisVideoWebRtc(PGstKvsPlugin);
STATUS freeGstKvsWebRtcPlugin(PGstKvsPlugin);
STATUS createMessageQueue(UINT64, PPendingMessageQueue*);
STATUS freeMessageQueue(PPendingMessageQueue);
STATUS gatherIceServerStats(PWebRtcStreamingSession);
STATUS freeWebRtcStreamingSession(PWebRtcStreamingSession*);
STATUS streamingSessionOnShutdown(PWebRtcStreamingSession, UINT64, StreamSessionShutdownCallback);
STATUS pregenerateCertTimerCallback(UINT32, UINT64, UINT64);
STATUS removeExpiredMessageQueues(PStackQueue);
STATUS getPendingMessageQueueForHash(PStackQueue, UINT64, BOOL, PPendingMessageQueue*);
STATUS createWebRtcStreamingSession(PGstKvsPlugin, PCHAR, BOOL, PWebRtcStreamingSession*);
STATUS initializePeerConnection(PGstKvsPlugin, PRtcPeerConnection*);
VOID onIceCandidateHandler(UINT64, PCHAR);
STATUS sendSignalingMessage(PWebRtcStreamingSession, PSignalingMessage);
STATUS respondWithAnswer(PWebRtcStreamingSession);
VOID onDataChannel(UINT64, PRtcDataChannel);
VOID onDataChannelMessage(UINT64, PRtcDataChannel, BOOL, PBYTE, UINT32);
VOID onConnectionStateChange(UINT64, RTC_PEER_CONNECTION_STATE);
STATUS submitPendingIceCandidate(PPendingMessageQueue, PWebRtcStreamingSession);
STATUS logSelectedIceCandidatesInformation(PWebRtcStreamingSession);
STATUS handleRemoteCandidate(PWebRtcStreamingSession, PSignalingMessage);
VOID sampleBandwidthEstimationHandler(UINT64, DOUBLE);
STATUS handleOffer(PGstKvsPlugin, PWebRtcStreamingSession, PSignalingMessage);
STATUS handleAnswer(PGstKvsPlugin, PWebRtcStreamingSession, PSignalingMessage);
STATUS getIceCandidatePairStatsCallback(UINT32, UINT64, UINT64);
PVOID receiveGstreamerAudioVideo(PVOID);
VOID onGstAudioFrameReady(UINT64, PFrame);
VOID onSampleStreamingSessionShutdown(UINT64, PWebRtcStreamingSession);
STATUS sessionServiceHandler(UINT32, UINT64, UINT64);
STATUS putFrameToWebRtcPeers(PGstKvsPlugin, PFrame, ELEMENTARY_STREAM_NAL_FORMAT);
STATUS adaptVideoFrameFromAvccToAnnexB(PGstKvsPlugin, PFrame, ELEMENTARY_STREAM_NAL_FORMAT);

#endif //__KVS_WEBRTC_FUNCTIONALITY_H__