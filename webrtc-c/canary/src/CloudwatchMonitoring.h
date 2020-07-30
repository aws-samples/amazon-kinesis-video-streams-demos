#pragma once

namespace Canary {

class CloudwatchMonitoring {
  public:
    CloudwatchMonitoring(Canary::PConfig, ClientConfiguration*);
    STATUS init();
    VOID deinit();
    VOID push(const MetricDatum&);
    VOID pushExitStatus(STATUS);

  private:
    Dimension channelDimension;
    PConfig pConfig;
    CloudWatchClient client;
    std::atomic<UINT64> pendingMetrics;
};

} // namespace Canary
