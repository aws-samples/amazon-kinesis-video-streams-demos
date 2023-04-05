#pragma once

#include "Include.h"

#define DEFAULT_FRAGMENT_DURATION_MILLISECONDS 2000
#define DEFAULT_CANARY_DURATION_SECONDS (12 * 60 * 60)
#define DEFAULT_BUFFER_DURATION_SECONDS 120

class CanaryConfig{

public: 
    std::string streamName;
    std::string sourceType;
    std::string canaryRunScenario; // continuous or intermittent
    std::string streamType; // real-time or offline
    std::string canaryLabel; // typically: longrun or periodic
    std::string cpUrl;
    UINT32 fragmentSize; // [milliseconds]
    UINT32 canaryDuration; // [seconds]
    UINT32 bufferDuration; // [seconds]
    UINT32 storageSizeInBytes;
    UINT32 testVideoFps;
    BOOL useAggMetrics;

    // credential related items
    PCHAR accessKey = nullptr;
    PCHAR secretKey = nullptr;
    PCHAR sessionToken = nullptr;
    PCHAR defaultRegion = nullptr;
    PCHAR useIotCredentialProvider = nullptr;
    PCHAR iotGetCredentialEndpoint = nullptr;
    PCHAR certPath = nullptr;
    PCHAR privateKeyPath = nullptr;
    PCHAR roleAlias = nullptr;
    PCHAR caCertPath = nullptr;
    PCHAR thingName = nullptr;

    CanaryConfig();
    VOID setEnvVarsString(std::string &configVar, std::string envVar);
    VOID setEnvVarsInt(PUINT32 pConfigVar, std::string envVar);
    VOID setEnvVarsBool(BOOL &configVar, std::string envVar);
    STATUS initConfigWithEnvVars();
};