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
    bool useAggMetrics;

    // credential related items
    char const *accessKey;
    char const *secretKey;
    char const *sessionToken;
    char const *defaultRegion;
    char const *use_Iot_Credential_Provider;
    char const *iot_get_credential_endpoint;
    char const *cert_path;
    char const *private_key_path;
    char const *role_alias;
    char const *ca_cert_path;
    char const *thing_name;

    CanaryConfig();
    VOID setEnvVarsString(string &configVar, string envVar);
    VOID setEnvVarsInt(PUINT32 pConfigVar, string envVar);
    VOID setEnvVarsBool(bool &configVar, string envVar);
    VOID initConfigWithEnvVars();

};