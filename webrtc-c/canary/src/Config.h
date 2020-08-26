#pragma once

namespace Canary {

class Config;
typedef Config* PConfig;

class Config {
  public:
    static STATUS init(INT32 argc, PCHAR argv[], PConfig);

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
    CHAR pLogGroupName[MAX_LOG_STREAM_NAME + 1];
    CHAR pLogStreamName[MAX_LOG_STREAM_NAME + 1];

    UINT64 duration;

    VOID print();
};

} // namespace Canary
