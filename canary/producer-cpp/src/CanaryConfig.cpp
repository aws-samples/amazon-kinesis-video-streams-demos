#include "CanaryConfig.h"

CanaryConfig::CanaryConfig()
{
    this->streamName = DEFAULT_CANARY_STREAM_NAME;
    this->sourceType = DEFAULT_SOURCE_TYPE;
    this->canaryRunScenario = DEFAULT_RUN_SCENARIO; // (or intermittent)
    this->streamType = DEFAULT_STREAM_TYPE_REALTIME;
    this->canaryLabel = DEFAULT_CANARY_RUN_LABEL; // need to decide on a default value

    this->fragmentSize = DEFAULT_FRAGMENT_DURATION_MILLISECONDS;
    this->canaryDuration = DEFAULT_CANARY_DURATION_SECONDS;
    this->bufferDuration = DEFAULT_BUFFER_DURATION_SECONDS;
    this->storageSizeInMB = DEFAULT_STORAGE_MB;
    this->testVideoFps = DEFAULT_CANARY_FRAME_RATE;
    this->useAggMetrics = true;

    this->defaultRegion = DEFAULT_CANARY_REGION;
}

VOID CanaryConfig::setEnvVarsString(std::string &configVar, std::string envVar)
{
    if (GETENV(envVar.c_str()) != NULL)
    {
        configVar = GETENV(envVar.c_str());
    }
}

VOID CanaryConfig::setEnvVarsInt(PUINT32 pConfigVar, std::string envVar)
{
    if (GETENV(envVar.c_str()) != NULL)
    {
        strtoui32(GETENV(envVar.c_str()), NULL, 10, pConfigVar);
    }
}

BOOL strtobool(const CHAR* value)
{
    if (STRCMPI(value, "on") == 0 || STRCMPI(value, "true") == 0) {
        return TRUE;
    }

    return FALSE;
}

VOID CanaryConfig::setEnvVarsBool(BOOL &configVar, std::string envVar)
{
    if (GETENV(envVar.c_str()) != NULL) {
        configVar = strtobool(GETENV(envVar.c_str()));
    }
}

STATUS CanaryConfig::initConfigWithEnvVars()
{
    STATUS retStatus = STATUS_SUCCESS;
    setEnvVarsBool(this->useIotCredentialProvider, CANARY_USE_IOT_ENV_VAR);

    if(this->useIotCredentialProvider) {
        if (nullptr != (this->iotGetCredentialEndpointFile = GETENV(IOT_CORE_CREDENTIAL_ENDPOINT_ENV_VAR)) &&
            nullptr != (this->certPath = GETENV(IOT_CORE_CERT_ENV_VAR)) &&
            nullptr != (this->privateKeyPath = GETENV(IOT_CORE_PRIVATE_KEY_ENV_VAR)) &&
            nullptr != (this->roleAlias = GETENV(IOT_CORE_ROLE_ALIAS_ENV_VAR)) &&
            nullptr != (this->caCertPath = GETENV(IOT_CORE_CA_CERT_PATH_ENV_VAR)) &&
            nullptr != (this->thingName = GETENV(IOT_CORE_THING_NAME_ENV_VAR))) {
            UINT64 fileSize;
            CHK_STATUS(readFile(this->iotGetCredentialEndpointFile, TRUE, NULL, &fileSize));
            CHK_ERR(fileSize != 0, STATUS_EMPTY_IOT_CRED_FILE, "Empty credential file");
            CHK_STATUS(readFile((PCHAR)this->iotGetCredentialEndpointFile, TRUE, this->iotGetCredentialEndpoint, &fileSize));
            this->iotGetCredentialEndpoint[fileSize - 1] = '\0';
            this->streamName = this->thingName;
        }
        else {
            LOG_ERROR("Missing Credential: IOT Credential");
            CHK(FALSE, STATUS_NOT_FOUND);
        }
    } else {
        if(nullptr != (this->accessKey = GETENV(ACCESS_KEY_ENV_VAR)) &&
           nullptr != (this->secretKey = GETENV(SECRET_KEY_ENV_VAR))){
            setEnvVarsString(streamName, "CANARY_STREAM_NAME");
        }
        else{
            LOG_ERROR("Missing Credential: AWS Credential");
            CHK(FALSE, STATUS_NOT_FOUND);
        }
    }

    setEnvVarsString(this->canaryRunScenario, CANARY_RUN_SCENARIO_ENV_VAR);
    setEnvVarsString(this->streamType, CANARY_STREAM_TYPE_ENV_VAR);

    if(this->streamType.compare(DEFAULT_STREAM_TYPE_REALTIME) && this->streamType.compare(DEFAULT_STREAM_TYPE_OFFLINE)) {
        LOG_ERROR("Unsupported stream type provided. Supported types are Realtime and Offline..." << this->streamType << " found");
        CHK(FALSE, STATUS_INVALID_ARG);
    }

    setEnvVarsString(this->canaryLabel, CANARY_LABEL_ENV_VAR);


    setEnvVarsInt(&this->fragmentSize, CANARY_FRAGMENT_SIZE_ENV_VAR);
    setEnvVarsInt(&this->canaryDuration, CANARY_RUN_DURATION_ENV_VAR);
    setEnvVarsInt(&this->bufferDuration, CANARY_BUFFER_DURATION_ENV_VAR);
    setEnvVarsInt(&this->storageSizeInMB, CANARY_STORAGE_SIZE_MB_ENV_VAR);
    setEnvVarsInt(&this->testVideoFps, CANARY_FRAME_RATE_ENV_VAR);
    setEnvVarsString(this->defaultRegion, DEFAULT_REGION_ENV_VAR);

CleanUp:
    return retStatus;
}

VOID CanaryConfig::print() {
    LOG_DEBUG("Applied configuration:\n\n"
          "\tRegion             : " << this->defaultRegion <<
          "\n\tLabel              : " << this->canaryLabel <<
          "\n\tStream Name        : " << this->streamName <<
          "\n\tDuration           : " << this->canaryDuration << " seconds" <<
          "\n\tCanary run scenario: " << this->canaryRunScenario <<
          "\n\tSource type:       : " << this->sourceType <<
          "\n\tStreaming type:    : " << this->streamType <<
          "\n\tCredential type    : " << (this->useIotCredentialProvider ? "IoT" : "Static") <<
          "\n");

    LOG_INFO("Canary label: " << this->canaryLabel);
    if(this->useIotCredentialProvider) {
        LOG_DEBUG("IoT specific configuration:\n\n"
                  "\tEndpoint             : " << (PCHAR) this->iotGetCredentialEndpoint <<
                  "\n\tCert file path       : " << this->certPath <<
                  "\n\tPrivate key path     : " << this->privateKeyPath <<
                  "\n\tRole Alias           : " << this->roleAlias <<
                  "\n\tCA cert file path    : " << this->caCertPath <<
                  "\n\tThing name:          : " << this->thingName <<
                  "\n")
    }
}