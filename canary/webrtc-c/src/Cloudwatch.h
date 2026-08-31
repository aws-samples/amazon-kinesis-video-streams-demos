#pragma once

namespace Canary {

// Registers / fetches the master's auto-refreshing IoT credential provider so the CloudWatch and
// CloudWatch Logs clients can use it. Set once the IoT provider is created (only on the
// USE_IOT_CREDENTIALS path); left NULL otherwise.
VOID setCwCredentialProvider(PAwsCredentialProvider provider);
PAwsCredentialProvider getCwCredentialProvider();

// Credentials provider for the CloudWatch/Logs clients. When an IoT credential provider is
// registered (long/soak runs), it pulls current creds from that auto-refreshing provider on every
// request; otherwise it falls back to environment credentials -- which covers both the early
// build-stage metrics (before the IoT provider exists) and non-IoT runs (identical to the previous
// default-chain behavior). This keeps the master's metric pushes alive past the ~1h static-STS
// expiry that role-chained Pi masters hit on a soak.
class IotBackedCredentialsProvider : public Aws::Auth::AWSCredentialsProvider {
  public:
    Aws::Auth::AWSCredentials GetAWSCredentials() override;

  private:
    Aws::Auth::EnvironmentAWSCredentialsProvider mEnvFallback;
};

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
