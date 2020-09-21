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
    typedef CHAR String[MAX_CONFIG_STRING_LENGTH + 1];

    static STATUS init(INT32 argc, PCHAR argv[], PConfig);

    Value<String> channelName;
    Value<String> clientId;
    Value<BOOL> isMaster;
    Value<BOOL> trickleIce;
    Value<BOOL> useTurn;
    Value<BOOL> forceTurn;

    // credentials
    Value<String> accessKey;
    Value<String> secretKey;
    Value<String> sessionToken;
    Value<String> region;

    // logging
    Value<UINT32> logLevel;
    Value<String> logGroupName;
    Value<String> logStreamName;

    Value<UINT64> duration;
    Value<UINT64> iterationDuration;
    Value<UINT64> bitRate;
    Value<UINT64> frameRate;

    VOID print();
};

} // namespace Canary
