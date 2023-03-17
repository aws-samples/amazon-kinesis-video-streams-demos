#pragma once

#include <vector>
#include <stdlib.h>
#include <string.h>
#include <mutex>
#include <iostream>
#include <Logger.h>

#include "com/amazonaws/kinesis/video/cproducer/Include.h"

using namespace std;

#define DEFAULT_FRAGMENT_DURATION_MILLISECONDS 2000
#define DEFAULT_CANARY_DURATION_SECONDS (12 * 60 * 60)
#define DEFAULT_BUFFER_DURATION_SECONDS 120

LOGGER_TAG("com.amazonaws.kinesis.video");


class CanaryConfig{

public: 
    string streamName;
    string sourceType;
    string canaryRunScenario; // continuous or intermittent
    string streamType; // real-time or offline
    string canaryLabel; // typically: longrun or periodic
    string cpUrl;
    UINT32 fragmentSize; // [milliseconds]
    UINT32 canaryDuration; // [seconds]
    UINT32 bufferDuration; // [seconds]
    UINT32 storageSizeInBytes;
    UINT32 testVideoFps;
    BOOL useAggMetrics;

    // credential related items
    char const *accessKey = nullptr;
    char const *secretKey = nullptr;
    char const *sessionToken = nullptr;
    char const *defaultRegion = nullptr;
    char const *useIotCredentialProvider = nullptr;
    char const *iotGetCredentialEndpoint = nullptr;
    char const *certPath = nullptr;
    char const *privateKeyPath = nullptr;
    char const *roleAlias = nullptr;
    char const *caCertPath = nullptr;
    char const *thingName = nullptr;

    CanaryConfig();
    VOID setEnvVarsString(string &configVar, string envVar);
    VOID setEnvVarsInt(PUINT32 pConfigVar, string envVar);
    VOID setEnvVarsBool(bool &configVar, string envVar);
    STATUS initConfigWithEnvVars();

};