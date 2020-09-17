#pragma once

namespace Canary {

class Config;
typedef Config* PConfig;

class Config {
  public:
    STATUS init(INT32 argc, PCHAR argv[]);

    const CHAR* pChannelName;
    const CHAR* pClientId;
    BOOL isMaster;
    BOOL trickleIce;
    BOOL useTurn;

    // credentials
    const CHAR* pAccessKey;
    const CHAR* pSecretKey;
    const CHAR* pSessionToken;
    const CHAR* pRegion;

    // logging
    UINT32 logLevel;
    CHAR logGroupName[MAX_LOG_STREAM_NAME + 1];
    CHAR logStreamName[MAX_LOG_STREAM_NAME + 1];

    UINT64 duration;
    UINT64 iterationDuration;

    VOID print();
};

} // namespace Canary
