#include "Include.h"

namespace Canary {

STATUS mustenv(CHAR const* pKey, const CHAR** ppValue)
{
    STATUS retStatus = STATUS_SUCCESS;

    CHK(ppValue != NULL, STATUS_NULL_ARG);

    CHK_ERR((*ppValue = getenv(pKey)) != NULL, STATUS_INVALID_OPERATION, "%s must be set", pKey);

CleanUp:

    return retStatus;
}

STATUS mustenvBool(CHAR const* pKey, PBOOL pResult)
{
    STATUS retStatus = STATUS_SUCCESS;
    const CHAR* pValue;

    CHK_STATUS(mustenv(pKey, &pValue));
    if (STRCMPI(pValue, "on") == 0 || STRCMPI(pValue, "true") == 0) {
        *pResult = TRUE;
    } else {
        *pResult = FALSE;
    }

CleanUp:

    return retStatus;
}

STATUS mustenvUint64(CHAR const* pKey, PUINT64 pResult)
{
    STATUS retStatus = STATUS_SUCCESS;
    const CHAR* pValue;

    CHK_STATUS(mustenv(pKey, &pValue));
    STRTOUI64((PCHAR) pValue, NULL, 10, pResult);

CleanUp:

    return retStatus;
}

VOID Config::print()
{
    DLOGD("\n\n"
          "\tChannel Name  : %s\n"
          "\tRegion        : %s\n"
          "\tClient ID     : %s\n"
          "\tRole          : %s\n"
          "\tTrickle ICE   : %s\n"
          "\tUse TURN      : %s\n"
          "\tLog Level     : %u\n"
          "\tLog Group     : %s\n"
          "\tLog Stream    : %s\n"
          "\tDuration      : %lu seconds\n"
          "\n",
          this->pChannelName, this->pRegion, this->pClientId, this->isMaster ? "Master" : "Viewer", this->trickleIce ? "True" : "False",
          this->useTurn ? "True" : "False", this->logLevel, this->pLogGroupName, this->pLogStreamName,
          this->duration / HUNDREDS_OF_NANOS_IN_A_SECOND);
}

STATUS Config::init(INT32 argc, PCHAR argv[], Canary::PConfig pConfig)
{
    // TODO: Probably also support command line args to fill the config
    // TODO: Probably also support JSON format to fill the config to allow more scalable option
    UNUSED_PARAM(argc);
    UNUSED_PARAM(argv);

    STATUS retStatus = STATUS_SUCCESS;
    PCHAR pLogLevel, pLogStreamName;
    const CHAR *pLogGroupName, *pClientId;
    UINT64 durationInSeconds;

    CHK(pConfig != NULL, STATUS_NULL_ARG);

    MEMSET(pConfig, 0, SIZEOF(Config));

    CHK_STATUS(mustenv(CANARY_CHANNEL_NAME_ENV_VAR, &pConfig->pChannelName));
    CHK_STATUS(mustenv(CANARY_CLIENT_ID_ENV_VAR, &pClientId));
    CHK_STATUS(mustenv(CANARY_CLIENT_ID_ENV_VAR, &pConfig->pClientId));
    CHK_STATUS(mustenvBool(CANARY_IS_MASTER_ENV_VAR, &pConfig->isMaster));
    /* This is ignored for master. Master can extract the info from offer. Viewer has to know if peer can trickle or
     * not ahead of time. */
    CHK_STATUS(mustenvBool(CANARY_TRICKLE_ICE_ENV_VAR, &pConfig->trickleIce));
    CHK_STATUS(mustenvBool(CANARY_USE_TURN_ENV_VAR, &pConfig->useTurn));

    CHK_STATUS(mustenv(ACCESS_KEY_ENV_VAR, &pConfig->pAccessKey));
    CHK_STATUS(mustenv(SECRET_KEY_ENV_VAR, &pConfig->pSecretKey));
    pConfig->pSessionToken = getenv(SESSION_TOKEN_ENV_VAR);
    if ((pConfig->pRegion = getenv(DEFAULT_REGION_ENV_VAR)) == NULL) {
        pConfig->pRegion = DEFAULT_AWS_REGION;
    }

    // Set the logger log level
    if (NULL == (pLogLevel = getenv(DEBUG_LOG_LEVEL_ENV_VAR)) || (STATUS_SUCCESS != STRTOUI32(pLogLevel, NULL, 10, &pConfig->logLevel))) {
        pConfig->logLevel = LOG_LEVEL_WARN;
    }

    CHK_STATUS(mustenv(CANARY_LOG_GROUP_NAME_ENV_VAR, &pLogGroupName));
    STRNCPY(pConfig->pLogGroupName, pLogGroupName, ARRAY_SIZE(pConfig->pLogGroupName) - 1);

    pLogStreamName = getenv(CANARY_LOG_STREAM_NAME_ENV_VAR);
    if (pLogStreamName != NULL) {
        STRNCPY(pConfig->pLogStreamName, pLogStreamName, ARRAY_SIZE(pConfig->pLogStreamName) - 1);
    } else {
        SNPRINTF(pConfig->pLogStreamName, ARRAY_SIZE(pConfig->pLogStreamName) - 1, "%s-%s-%llu", pConfig->pChannelName,
                 pConfig->isMaster ? "master" : "viewer", GETTIME() / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
    }

    CHK_STATUS(mustenvUint64(CANARY_DURATION_IN_SECONDS_ENV_VAR, &durationInSeconds));
    pConfig->duration = durationInSeconds * HUNDREDS_OF_NANOS_IN_A_SECOND;

CleanUp:

    return retStatus;
}

} // namespace Canary
