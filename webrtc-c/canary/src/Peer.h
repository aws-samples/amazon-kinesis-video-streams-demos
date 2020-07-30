#pragma once
namespace Canary {

class Peer;
typedef Peer* PPeer;

class Peer {
  public:
    struct Callbacks {
        std::function<VOID()> onDisconnected;
        std::function<STATUS(PPeer)> onNewConnection;
    };

    Peer(const Canary::PConfig, const Callbacks&);
    ~Peer();
    STATUS init();
    STATUS shutdown();
    STATUS connect();
    STATUS addTransceiver(RtcMediaStreamTrack&);
    STATUS addSupportedCodec(RTC_CODEC);
    STATUS writeFrame(PFrame, MEDIA_STREAM_TRACK_KIND);

  private:
    const Canary::PConfig pConfig;
    const Callbacks callbacks;
    PAwsCredentialProvider pAwsCredentialProvider;
    SIGNALING_CLIENT_HANDLE pSignalingClientHandle;
    std::recursive_mutex mutex;
    std::condition_variable_any cvar;
    std::atomic<BOOL> terminated;
    std::atomic<BOOL> iceGatheringDone;
    std::atomic<BOOL> receivedOffer;
    std::atomic<BOOL> receivedAnswer;
    std::atomic<BOOL> foundPeerId;
    std::string peerId;
    RtcConfiguration rtcConfiguration;
    PRtcPeerConnection pPeerConnection;
    std::vector<PRtcRtpTransceiver> audioTransceivers;
    std::vector<PRtcRtpTransceiver> videoTransceivers;
    STATUS status;

    STATUS initSignaling();
    STATUS initRtcConfiguration();
    STATUS initPeerConnection();
    STATUS awaitIceGathering(PRtcSessionDescriptionInit);
    STATUS handleSignalingMsg(PReceivedSignalingMessage);
    STATUS send(PSignalingMessage);
};

} // namespace Canary
