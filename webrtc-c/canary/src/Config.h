#pragma once

namespace Canary {

class Config;
typedef Config* PConfig;

class Config {
  public:
    STATUS init(INT32 argc, PCHAR argv[]);
    template <typename T> class Value {
      public:
        Value() : initialized(FALSE)
        {
        }

        T value;
        BOOL initialized;
    };

    static STATUS init(INT32 argc, PCHAR argv[], PConfig);

    Value<const CHAR*> channelName;
    Value<const CHAR*> clientId;
    Value<BOOL> isMaster;
    Value<BOOL> trickleIce;
    Value<BOOL> useTurn;
    Value<BOOL> forceTurn;

    // credentials
    Value<const CHAR*> accessKey;
    Value<const CHAR*> secretKey;
    Value<const CHAR*> sessionToken;
    Value<const CHAR*> region;

    // logging
    Value<UINT32> logLevel;
    Value<CHAR[MAX_LOG_STREAM_NAME + 1]> logGroupName;
    Value<CHAR[MAX_LOG_STREAM_NAME + 1]> logStreamName;

    Value<UINT64> duration;
    Value<UINT64> iterationDuration;
    Value<UINT64> bitRate;
    Value<UINT64> frameRate;

    VOID print();
};

} // namespace Canary
