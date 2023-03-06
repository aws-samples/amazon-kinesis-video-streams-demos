#include "CanaryConfig.h"

CanaryConfig::CanaryConfig()
{
    testVideoFps = 25;
    streamName = "DefaultStreamName";
    sourceType = "TEST_SOURCE";
    canaryRunScenario = "Continuous"; // (or intermittent)
    streamType = "REALTIME";
    canaryLabel = "DEFAULT_CANARY_LABEL"; // need to decide on a default value
    cpUrl = "";
    fragmentSize = DEFAULT_FRAGMENT_DURATION_MILLISECONDS;
    canaryDuration = DEFAULT_CANARY_DURATION_SECONDS;
    bufferDuration = DEFAULT_BUFFER_DURATION_SECONDS;
    storageSizeInBytes = 0;
    useAggMetrics = true;
}

VOID CanaryConfig::setEnvVarsString(string &configVar, string envVar)
{
    if (GETENV(envVar.c_str()) != NULL)
    {
        configVar = GETENV(envVar.c_str());
    }
}

VOID CanaryConfig::setEnvVarsInt(PUINT32 pConfigVar, string envVar)
{
    if (GETENV(envVar.c_str()) != NULL)
    {
        strtoui32(GETENV(envVar.c_str()), NULL, 10, pConfigVar);
    }
}

VOID CanaryConfig::setEnvVarsBool(bool &configVar, string envVar)
{
    if (GETENV(envVar.c_str()) != NULL)
    {
        if (GETENV(envVar.c_str()) == "TRUE" || GETENV(envVar.c_str()) == "true" || GETENV(envVar.c_str()) == "True")
        {
            configVar = true;
        } else
        {
            configVar = false;
        }
    }
}

VOID CanaryConfig::initConfigWithEnvVars()
{
    use_iot_credential_provider = GETENV("CANARY_USE_IOT_PROVIDER");
    string use_iot_cred(use_iot_credential_provider);
    transform(use_iot_cred.begin(), use_iot_cred.end(),use_iot_cred.begin(), ::tolower);

    if(use_iot_cred.compare("true") == 0){
        iot_get_credential_endpoint = GETENV("AWS_IOT_CORE_CREDENTIAL_ENDPOINT");
        cert_path = GETENV("AWS_IOT_CORE_CERT");
        private_key_path = GETENV("AWS_IOT_CORE_PRIVATE_KEY");
        role_alias = GETENV("AWS_IOT_CORE_ROLE_ALIAS");
        ca_cert_path = GETENV("AWS_IOT_CORE_CA_CERT_PATH");
        thing_name = GETENV("AWS_IOT_CORE_THING_NAME");
        streamName = thing_name;
    }
    else {
        setEnvVarsString(streamName, "CANARY_STREAM_NAME");
        accessKey = GETENV(ACCESS_KEY_ENV_VAR);
        secretKey = GETENV(SECRET_KEY_ENV_VAR);
    }
    //setEnvVarsString(sourceType, "CANARY_SOURCE_TYPE");
    setEnvVarsString(canaryRunScenario, "CANARY_RUN_SCENARIO");
    setEnvVarsString(streamType, "CANARY_STREAM_TYPE");
    setEnvVarsString(canaryLabel, "CANARY_LABEL");
    setEnvVarsString(cpUrl, "CANARY_CP_URL");

    setEnvVarsInt(&fragmentSize, "CANARY_FRAGMENT_SIZE");
    setEnvVarsInt(&canaryDuration, "CANARY_DURATION_IN_SECONDS");
    setEnvVarsInt(&bufferDuration, "CANARY_BUFFER_DURATION");
    setEnvVarsInt(&storageSizeInBytes, "CANARY_STORAGE_SIZE");
    setEnvVarsInt(&testVideoFps, "CANARY_FPS");

    defaultRegion = GETENV(DEFAULT_REGION_ENV_VAR);
    sessionToken = GETENV(SESSION_TOKEN_ENV_VAR);

    LOG_DEBUG("CANARY_STREAM_NAME: " << streamName);
    LOG_DEBUG("CANARY_RUN_SCENARIO: " << canaryRunScenario);
    LOG_DEBUG("CANARY_STREAM_TYPE: " << streamType);
    LOG_DEBUG("CANARY_LABEL: " << canaryLabel);
    LOG_DEBUG("CANARY_CP_URL: " << cpUrl);
    LOG_DEBUG("CANARY_FRAGMENT_SIZE: " << fragmentSize);
    LOG_DEBUG("CANARY_DURATION: " << canaryDuration);
    LOG_DEBUG("CANARY_STORAGE_SIZE: " << storageSizeInBytes);
    LOG_DEBUG("CANARY_FPS: " << testVideoFps);

    if(use_iot_cred.compare("true") == 0){
        LOG_DEBUG("IOT_ENDPOINT: "<< iot_get_credential_endpoint);
        LOG_DEBUG("IOT_CERT_FILE: "<< cert_path);
        LOG_DEBUG("IOT_PRIVATE_KEY: "<< private_key_path);
        LOG_DEBUG("IOT_ROLE_ALIAS: "<< role_alias);
        LOG_DEBUG("IOT_CA_CERT_FILE: "<< ca_cert_path);
        LOG_DEBUG("IOT_THING_NAME: "<< thing_name);
    }
}