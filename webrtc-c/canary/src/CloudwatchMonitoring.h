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
    VOID pushSignalingInitDelay(UINT64, StandardUnit);
    VOID pushSignalingRoundtripLatency(UINT64, StandardUnit);
    VOID pushICEHolePunchingDelay(UINT64, StandardUnit);
    VOID pushOutboundRtpStats(Canary::POutgoingRTPMetricsContext);
    VOID pushInboundRtpStats(Canary::PIncomingRTPMetricsContext);
    VOID pushEndToEndMetrics(Canary::PEndToEndMetricsContext);

  private:
    Dimension channelDimension;
    PConfig pConfig;
    CloudWatchClient client;
    std::atomic<UINT64> pendingMetrics;
};

} // namespace Canary
