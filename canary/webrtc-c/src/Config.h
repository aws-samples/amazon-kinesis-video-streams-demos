#pragma once

// #include <aws/s3/S3Client.h>
#include <aws/core/auth/AWSCredentialsProvider.h>
#include <aws/sts/STSClient.h>
#include <aws/sts/model/AssumeRoleRequest.h>
// #include <aws/s3/model/ListBucketsResult.h>

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

    Value<std::string> endpoint;
    Value<std::string> label;
    Value<std::string> channelName;
    Value<std::string> clientId;
    Value<std::string> videoCodec;
    Value<BOOL> isMaster;
    Value<BOOL> runBothPeers;
    Value<BOOL> trickleIce;
    Value<BOOL> useTurn;
    Value<BOOL> forceTurn;
    Value<BOOL> useIotCredentialProvider;
    Value<BOOL> isProfilingMode;

    BOOL isStorage;

    // credentials
    Aws::Auth::AWSCredentials credentials;
    Value<std::string> accessKey;
    Value<std::string> secretKey;
    Value<std::string> sessionToken;
    Value<std::string> region;
    Value<std::string> iotCoreCredentialEndPointFile;
    Value<std::string> iotCoreCert;
    Value<std::string> iotCorePrivateKey;
    Value<std::string> iotCoreRoleAlias;

    // logging
    Value<UINT32> logLevel;
    Value<std::string> logGroupName;
    Value<std::string> logStreamName;

    Value<UINT64> duration;
    Value<UINT64> iterationDuration;
    Value<UINT64> bitRate;
    Value<UINT64> frameRate;

    Value<std::string> caCertPath;
    Value<std::string> storageFristFrameSentTSFileName;

    BYTE iotEndpoint[MAX_CONFIG_JSON_FILE_SIZE];

    VOID print();

  private:
    STATUS initWithJSON(PCHAR);
    STATUS initWithEnvVars();
    bool assumeRole(const Aws::String &roleArn,
                             const Aws::String &roleSessionName,
                             const Aws::String &externalId,
                             Aws::Auth::AWSCredentials &credentials,
                             const Aws::Client::ClientConfiguration &clientConfig);
};

} // namespace Canary
