#pragma once

#include "Include.h"

#define DEFAULT_FRAGMENT_DURATION_MILLISECONDS 2000
#define DEFAULT_CANARY_DURATION_SECONDS (12 * 60 * 60)
#define DEFAULT_BUFFER_DURATION_SECONDS 120
#define DEFAULT_STORAGE_MB              256
#define DEFAULT_CANARY_FRAME_RATE       25

#define CANARY_USE_IOT_ENV_VAR              "CANARY_USE_IOT"
#define CANARY_RUN_SCENARIO_ENV_VAR         "CANARY_RUN_SCENARIO"
#define CANARY_STREAM_TYPE_ENV_VAR          "CANARY_STREAM_TYPE"
#define CANARY_STREAM_NAME_ENV_VAR          "CANARY_STREAM_NAME"
#define CANARY_LABEL_ENV_VAR                "CANARY_LABEL"
#define CANARY_FRAGMENT_SIZE_ENV_VAR        "CANARY_FRAGMENT_SIZE"
#define CANARY_RUN_DURATION_ENV_VAR         "CANARY_DURATION_IN_SECONDS"
#define CANARY_BUFFER_DURATION_ENV_VAR      "CANARY_BUFFER_DURATION"
#define CANARY_STORAGE_SIZE_MB_ENV_VAR      "CANARY_STORAGE_SIZE"
#define CANARY_FRAME_RATE_ENV_VAR           "CANARY_FPS"

#define IOT_CORE_CREDENTIAL_ENDPOINT_ENV_VAR "AWS_IOT_CORE_CREDENTIAL_ENDPOINT"
#define IOT_CORE_CERT_ENV_VAR                "AWS_IOT_CORE_CERT"
#define IOT_CORE_PRIVATE_KEY_ENV_VAR         "AWS_IOT_CORE_PRIVATE_KEY"
#define IOT_CORE_ROLE_ALIAS_ENV_VAR          "AWS_IOT_CORE_ROLE_ALIAS"
#define IOT_CORE_CA_CERT_PATH_ENV_VAR        "AWS_IOT_CA_CERT"
#define IOT_CORE_THING_NAME_ENV_VAR          "AWS_IOT_CORE_THING_NAME"

class CanaryConfig {

public: 
    std::string streamName;
    std::string sourceType;
    std::string canaryRunScenario; // continuous or intermittent
    std::string streamType; // real-time or offline
    std::string canaryLabel; // typically: longrun or periodic
    std::string defaultRegion;
    UINT32 fragmentSize; // [milliseconds]
    UINT32 canaryDuration; // [seconds]
    UINT32 bufferDuration; // [seconds]
    UINT32 storageSizeInMB;
    UINT32 testVideoFps;
    BOOL useAggMetrics;
    BOOL useIotCredentialProvider;

    // credential related items
    PCHAR accessKey = nullptr;
    PCHAR secretKey = nullptr;
    BYTE iotGetCredentialEndpoint[MAX_URI_CHAR_LEN + 1];
    PCHAR iotGetCredentialEndpointFile = nullptr;
    PCHAR certPath = nullptr;
    PCHAR privateKeyPath = nullptr;
    PCHAR roleAlias = nullptr;
    PCHAR caCertPath = nullptr;
    PCHAR thingName = nullptr;

    CanaryConfig();
    VOID setEnvVarsString(std::string &configVar, std::string envVar);
    VOID setEnvVarsInt(PUINT32 pConfigVar, std::string envVar);
    VOID setEnvVarsBool(BOOL &configVar, std::string envVar);
    VOID print();
    STATUS initConfigWithEnvVars();
};