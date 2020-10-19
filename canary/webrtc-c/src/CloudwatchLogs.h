#pragma once

namespace Canary {

class CloudwatchLogs {
  public:
    CloudwatchLogs(Canary::PConfig, ClientConfiguration*);
    STATUS init();
    VOID deinit();
    VOID push(string log);
    VOID flush(BOOL sync = FALSE);

  private:
    class Synchronization {
      public:
        std::atomic<bool> pending;
        std::recursive_mutex mutex;
        std::condition_variable_any await;
    };

    PConfig pConfig;
    CloudWatchLogsClient client;
    Synchronization sync;
    Aws::Vector<InputLogEvent> logs;
    Aws::String token;
};

} // namespace Canary
