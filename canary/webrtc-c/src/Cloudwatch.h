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

    // TRUE once init() has constructed the singleton with real arguments. The
    // function-local static in getInstanceImpl() is built on FIRST call with THAT
    // call's arguments — if init() bails before reaching it (e.g. missing
    // credentials), a later deinit()/logger() call would construct it with NULLs
    // and the AWS client constructors would segfault dereferencing the NULL
    // ClientConfiguration. Guarding on this flag keeps failed-init exits clean.
    static std::atomic<BOOL> initialized;

    Cloudwatch(Canary::PConfig, ClientConfiguration*);
    BOOL terminated;
    BOOL useFileLogger;
};
typedef Cloudwatch* PCloudwatch;

} // namespace Canary
