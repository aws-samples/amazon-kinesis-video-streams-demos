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
    setEnvVarsString(streamName, "CANARY_STREAM_NAME");
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
    accessKey = GETENV(ACCESS_KEY_ENV_VAR);
    secretKey = GETENV(SECRET_KEY_ENV_VAR);
    sessionToken = GETENV(SESSION_TOKEN_ENV_VAR);
    std::cout<<"here at setting env"<<endl;
    use_Iot_Credential_Provider = GETENV("USE_IOT_PROVIDER");
    std::cout<<"here at setting env use iot set"<<endl;
    iot_get_credential_endpoint = GETENV("IOT_GET_CREDENTIAL_ENDPOINT");
    std::cout<<"here at setting env iot cred set"<<endl;
    cert_path = GETENV("CERT_PATH");
    std::cout<<"here at setting env cert path set"<<endl;
    private_key_path = GETENV("PRIVATE_KEY_PATH");
    std::cout<<"here at setting env private key set"<<endl;
    role_alias = GETENV("ROLE_ALIAS");
    std::cout<<"here at setting env role alias set"<<endl;
    ca_cert_path = GETENV("CA_CERT_PATH");
    std::cout<<"here at setting env ca cert set"<<endl;
    thing_name = GETENV("IOT_THING_NAME");
    std::cout<<"here at setting env iot thing name set"<<endl;

    string flag(use_Iot_Credential_Provider);
    std::cout<<"here at setting env initializing flag"<<endl;

    if(flag == "TRUE"){
        streamName = thing_name;
    }

    LOG_DEBUG("CANARY_STREAM_NAME: " << streamName);
    LOG_DEBUG("CANARY_RUN_SCENARIO: " << canaryRunScenario);
    LOG_DEBUG("CANARY_STREAM_TYPE: " << streamType);
    LOG_DEBUG("CANARY_LABEL: " << canaryLabel);
    LOG_DEBUG("CANARY_CP_URL: " << cpUrl);
    LOG_DEBUG("CANARY_FRAGMENT_SIZE: " << fragmentSize);
    LOG_DEBUG("CANARY_DURATION: " << canaryDuration);
    LOG_DEBUG("CANARY_STORAGE_SIZE: " << storageSizeInBytes);
    LOG_DEBUG("CANARY_FPS: " << testVideoFps);

    if(flag == "TRUE"){
        LOG_DEBUG("IOT_ENDPOINT: "<< iot_get_credential_endpoint);
        LOG_DEBUG("IOT_CERT_FILE: "<< cert_path);
        LOG_DEBUG("IOT_PRIVATE_KEY: "<< private_key_path);
        LOG_DEBUG("IOT_ROLE_ALIAS: "<< role_alias);
        LOG_DEBUG("IOT_CA_CERT_FILE: "<< ca_cert_path);
        LOG_DEBUG("IOT_THING_NAME: "<< thing_name);
    }
}