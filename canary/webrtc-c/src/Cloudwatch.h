#pragma once

namespace Canary {

class Cloudwatch {
  public:
    Cloudwatch() = delete;
    Cloudwatch(Cloudwatch const&) = delete;
    void operator=(Cloudwatch const&) = delete;

    CloudwatchLogs logs;
    CloudwatchMonitoring monitoring;

    static Cloudwatch& getInstance();
    static STATUS init(Canary::PConfig);
    static VOID deinit();
    static VOID logger(UINT32, PCHAR, PCHAR, ...);

  private:
    static Cloudwatch& getInstanceImpl(Canary::PConfig = nullptr, ClientConfiguration* = nullptr);

    Cloudwatch(Canary::PConfig, ClientConfiguration*);
    BOOL terminated;
    BOOL useFileLogger;
};
typedef Cloudwatch* PCloudwatch;

} // namespace Canary
