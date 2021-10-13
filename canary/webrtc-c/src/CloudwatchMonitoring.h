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
    VOID pushICEHolePunchingDelay(UINT64, Aws::CloudWatch::Model::StandardUnit);
    VOID pushOutboundRtpStats(Canary::POutgoingRTPMetricsContext);
    VOID pushInboundRtpStats(Canary::PIncomingRTPMetricsContext);
    VOID pushEndToEndMetrics(Canary::EndToEndMetricsContext);

  private:
    Dimension channelDimension;
    Dimension labelDimension;
    PConfig pConfig;
    CloudWatchClient client;
    std::atomic<UINT64> pendingMetrics;
};

} // namespace Canary
