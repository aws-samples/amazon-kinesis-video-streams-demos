#pragma once

namespace Canary {

class Config;
typedef Config* PConfig;

class Config {
  public:
    STATUS init(INT32 argc, PCHAR argv[]);
    template <typename T> class Value {
      public:
        T value;
        BOOL initialized = FALSE;
    };

    Value<std::string> channelName;
    Value<std::string> clientId;
    Value<BOOL> isMaster;
    Value<BOOL> runBothPeers;
    Value<BOOL> trickleIce;
    Value<BOOL> useTurn;
    Value<BOOL> forceTurn;

    // credentials
    Value<std::string> accessKey;
    Value<std::string> secretKey;
    Value<std::string> sessionToken;
    Value<std::string> region;

    // logging
    Value<UINT32> logLevel;
    Value<std::string> logGroupName;
    Value<std::string> logStreamName;

    Value<UINT64> duration;
    Value<UINT64> iterationDuration;
    Value<UINT64> bitRate;
    Value<UINT64> frameRate;

    VOID print();

  private:
    STATUS initWithJSON(PCHAR);
    STATUS initWithEnvVars();
};

} // namespace Canary
