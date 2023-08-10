#pragma once
namespace Canary {

class Peer;
typedef Peer* PPeer;

typedef struct {
    UINT64 prevNumberOfPacketsSent;
    UINT64 prevNumberOfPacketsReceived;
    UINT64 prevNumberOfBytesSent;
    UINT64 prevNumberOfBytesReceived;
    UINT64 prevFramesDiscardedOnSend;
    UINT64 prevTs;
    UINT64 prevVideoFramesGenerated;
    UINT64 prevFramesSent;
    UINT64 prevNackCount;
    UINT64 prevRetxBytesSent;
    std::atomic<UINT64> videoFramesGenerated;
    UINT64 videoBytesGenerated;
    DOUBLE framesPercentageDiscarded;
    DOUBLE nacksPerSecond;
    DOUBLE averageFramesSentPerSecond;
    DOUBLE retxBytesPercentage;
} OutgoingRTPMetricsContext;
typedef OutgoingRTPMetricsContext* POutgoingRTPMetricsContext;

typedef struct {
    UINT64 prevPacketsReceived;
    UINT64 prevTs;
    UINT64 prevBytesReceived;
    UINT64 prevFramesDropped;
    DOUBLE packetReceiveRate;
    DOUBLE incomingBitRate;
    DOUBLE framesDroppedPerSecond;
} IncomingRTPMetricsContext;
typedef IncomingRTPMetricsContext* PIncomingRTPMetricsContext;

struct EndToEndMetricsContext{
    DOUBLE frameLatencyAvg = 0.0;
    DOUBLE dataMatchAvg = 0.0;
    DOUBLE sizeMatchAvg = 0.0;
};
typedef EndToEndMetricsContext* PEndToEndMetricsContext;

class Peer {
  public:
    struct Callbacks {
        std::function<VOID()> onDisconnected;
        std::function<STATUS(PPeer)> onNewConnection;
    };

    Peer();
    ~Peer();
    STATUS init(const Canary::PConfig, const Callbacks&);
    STATUS shutdown();
    STATUS connect();
    STATUS addTransceiver(RtcMediaStreamTrack&);
    STATUS addSupportedCodec(RTC_CODEC);
    STATUS writeFrame(PFrame, MEDIA_STREAM_TRACK_KIND);
    STATUS sendProfilingMetrics();

    // WebRTC Stats
    STATUS publishStatsForCanary(RTC_STATS_TYPE);
    STATUS publishEndToEndMetrics();
    STATUS publishRetryCount();

  private:
    Callbacks callbacks;
    PAwsCredentialProvider pAwsCredentialProvider;
    SIGNALING_CLIENT_HANDLE signalingClientHandle;
    std::recursive_mutex mutex;
    std::mutex countUpdateMutex;
    std::mutex e2eLock;
    std::condition_variable_any cvar;
    std::atomic<BOOL> terminated;
    std::atomic<BOOL> iceGatheringDone;
    std::atomic<BOOL> receivedOffer;
    std::atomic<BOOL> receivedAnswer;
    std::atomic<BOOL> foundPeerId;
    std::atomic<BOOL> recorded;
    BOOL initializedSignaling = FALSE;
    std::string peerId;
    RtcConfiguration rtcConfiguration;
    PRtcPeerConnection pPeerConnection;
    std::vector<PRtcRtpTransceiver> audioTransceivers;
    std::vector<PRtcRtpTransceiver> videoTransceivers;
    BOOL isMaster;
    BOOL trickleIce;
    BOOL isProfilingMode;
    UINT64 offerReceiveTimestamp;
    BOOL firstFrame;
    BOOL useIotCredentialProvider;
    STATUS status;
    SignalingClientInfo clientInfo;

    // metrics
    UINT64 signalingStartTime;
    UINT64 iceHolePunchingStartTime;
    RtcStats canaryMetrics;
    PeerConnectionMetrics peerConnectionMetrics;
    KvsIceAgentMetrics iceMetrics;
    SignalingClientMetrics signalingClientMetrics;
    OutgoingRTPMetricsContext canaryOutgoingRTPMetricsContext;
    IncomingRTPMetricsContext canaryIncomingRTPMetricsContext;
    EndToEndMetricsContext endToEndMetricsContext;

    STATUS initSignaling(const Canary::PConfig);
    STATUS initRtcConfiguration(const Canary::PConfig);
    STATUS initPeerConnection();
    STATUS awaitIceGathering(PRtcSessionDescriptionInit);
    STATUS handleSignalingMsg(PReceivedSignalingMessage);
    STATUS send(PSignalingMessage);
    STATUS populateOutgoingRtpMetricsContext();
    STATUS populateIncomingRtpMetricsContext();
};

} // namespace Canary
