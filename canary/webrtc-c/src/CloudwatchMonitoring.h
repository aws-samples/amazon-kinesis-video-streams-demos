#pragma once

namespace Canary {

class CloudwatchMonitoring {
  public:
    CloudwatchMonitoring(Canary::PConfig, ClientConfiguration*);
    STATUS init();
    VOID deinit();
    VOID push(const MetricDatum&);
    VOID pushExitStatus(STATUS);
    VOID pushSignalingRoundtripStatus(STATUS);
    VOID pushSignalingInitDelay(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushTimeToFirstFrame(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushSignalingRoundtripLatency(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushSignalingConnectionDuration(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushICEHolePunchingDelay(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushOutboundRtpStats(Canary::POutgoingRTPMetricsContext);
    VOID pushInboundRtpStats(Canary::PIncomingRTPMetricsContext);
    VOID pushEndToEndMetrics(Canary::EndToEndMetricsContext);
    VOID pushPeerConnectionMetrics(PPeerConnectionMetrics);
    VOID pushKvsIceAgentMetrics(PKvsIceAgentMetrics);
    VOID pushSignalingClientMetrics(PSignalingClientMetrics);
    VOID pushRetryCount(UINT32);

    VOID pushStorageDisconnectToFrameSentTime(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushJoinSessionTime(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushJoinStorageSessionAvailability(DOUBLE);
    VOID pushPeerConnectionAvailability(DOUBLE);
    VOID pushCMasterRetryCount(UINT32);
    VOID pushJoinSSTimeout(UINT32);
    VOID pushJoinSSCallToSessionJoined(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushCMasterUnexpectedDisconnection(UINT32);
    VOID pushMasterStreamingAvailability(DOUBLE);
    VOID pushTimeToPeerConnection(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushTimeToSendAnswer(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushTimeToReceiveInboundMedia(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushTimeToSendIce(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushTimeToReceiveIce(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushJoinSSCallToFirstFrame(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushRoundTripTime(DOUBLE, Aws::CloudWatch::Model::StandardUnit);
    // Inbound-media receiver metrics (only emitted when the master actually receives media,
    // e.g. VOMasterMixedViewer where the audio transceiver is RECVONLY and viewer audio is mixed in).
    VOID pushJitterBufferDelay(DOUBLE, Aws::CloudWatch::Model::StandardUnit);
    VOID pushDecodeTime(DOUBLE, Aws::CloudWatch::Model::StandardUnit);
    // RTP media-level (per-track) packets/bytes sent rates, distinct from the transport-level
    // PacketsSentPerSecond (ICE candidate pair). Sourced from outboundRtpStreamStats.sent.*.
    VOID pushRtpVideoPacketsSentPerSecond(DOUBLE);
    VOID pushRtpVideoBytesSentPerSecond(DOUBLE);
    VOID pushRtpAudioPacketsSentPerSecond(DOUBLE);
    VOID pushRtpAudioBytesSentPerSecond(DOUBLE);

    // Remote-inbound RTP metrics, derived from the RTCP Receiver Reports the remote sends back
    // about the stream the master sent. FractionLost is the only packet-loss signal available on
    // the master; RtcpRoundTripTime is RR-based RTT; RtcpReportsReceived indicates whether RRs are
    // arriving at all (distinguishes "0% loss" from "no reports").
    VOID pushFractionLost(DOUBLE, Aws::CloudWatch::Model::StandardUnit);
    VOID pushRtcpRoundTripTime(DOUBLE, Aws::CloudWatch::Model::StandardUnit);
    VOID pushRtcpReportsReceived(DOUBLE, Aws::CloudWatch::Model::StandardUnit);

    // Periodic during-session metrics
    VOID pushPacketsSentPerSecond(DOUBLE);
    VOID pushPacketsReceivedPerSecond(DOUBLE);
    VOID pushOutgoingBitrate(DOUBLE);
    VOID pushPliCountPerSecond(DOUBLE);

    // End-of-session totals
    VOID pushTotalPacketsSent(UINT64);
    VOID pushTotalPacketsReceived(UINT64);
    VOID pushTotalNackCount(UINT64);
    VOID pushTotalPliCount(UINT64);
    VOID pushTotalFramesDiscardedPercentage(DOUBLE);

  private:
    Dimension channelDimension;
    Dimension labelDimension;
    PConfig pConfig;
    CloudWatchClient client;
    std::atomic<UINT64> pendingMetrics;
    BOOL isStorage;
};

} // namespace Canary
