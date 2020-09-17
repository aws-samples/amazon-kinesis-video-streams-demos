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

STATUS optenv(CHAR const* pKey, const CHAR** ppValue, const CHAR* pDefault)
{
    STATUS retStatus = STATUS_SUCCESS;

    CHK(ppValue != NULL, STATUS_NULL_ARG);

    if (NULL == (*ppValue = getenv(pKey))) {
        *ppValue = pDefault;
    }

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

STATUS optenvBool(CHAR const* pKey, PBOOL pResult, BOOL defVal)
{
    STATUS retStatus = STATUS_SUCCESS;
    const CHAR* pValue;

    CHK_STATUS(optenv(pKey, &pValue, NULL));
    if (pValue != NULL) {
        if (STRCMPI(pValue, "on") == 0 || STRCMPI(pValue, "true") == 0) {
            *pResult = TRUE;
        } else {
            *pResult = FALSE;
        }
    } else {
        *pResult = defVal;
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

STATUS optenvUint64(CHAR const* pKey, PUINT64 pResult, UINT64 defVal)
{
    STATUS retStatus = STATUS_SUCCESS;
    const CHAR* pValue;

    CHK_STATUS(optenv(pKey, &pValue, NULL));
    if (pValue != NULL) {
        STRTOUI64((PCHAR) pValue, NULL, 10, pResult);
    } else {
        *pResult = defVal;
    }

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
          "\tIteration     : %lu seconds\n"
          "\n",
          this->pChannelName, this->pRegion, this->pClientId, this->isMaster ? "Master" : "Viewer", this->trickleIce ? "True" : "False",
          this->useTurn ? "True" : "False", this->logLevel, this->logGroupName, this->logStreamName,
          this->duration / HUNDREDS_OF_NANOS_IN_A_SECOND, this->iterationDuration / HUNDREDS_OF_NANOS_IN_A_SECOND);
}

STATUS Config::init(INT32 argc, PCHAR argv[])
{
    // TODO: Probably also support command line args to fill the config
    // TODO: Probably also support JSON format to fill the config to allow more scalable option
    UNUSED_PARAM(argc);
    UNUSED_PARAM(argv);

    STATUS retStatus = STATUS_SUCCESS;
    PCHAR pLogStreamName;
    const CHAR *pLogGroupName;
    UINT64 logLevel64;

    CHK(argv != NULL, STATUS_NULL_ARG);

    MEMSET(this, 0, SIZEOF(Config));

    /* This is ignored for master. Master can extract the info from offer. Viewer has to know if peer can trickle or
     * not ahead of time. */
    CHK_STATUS(optenvBool(CANARY_TRICKLE_ICE_ENV_VAR, &trickleIce, FALSE));
    CHK_STATUS(optenvBool(CANARY_USE_TURN_ENV_VAR, &useTurn, TRUE));
    CHK_STATUS(optenvBool(CANARY_FORCE_TURN_ENV_VAR, &forceTurn, FALSE));

    CHK_STATUS(mustenv(ACCESS_KEY_ENV_VAR, &pAccessKey));
    CHK_STATUS(mustenv(SECRET_KEY_ENV_VAR, &pSecretKey));
    CHK_STATUS(optenv(SESSION_TOKEN_ENV_VAR, &pSessionToken, NULL));
    CHK_STATUS(optenv(DEFAULT_REGION_ENV_VAR, &pRegion, DEFAULT_AWS_REGION));

    // Set the logger log level
    CHK_STATUS(optenvUint64(DEBUG_LOG_LEVEL_ENV_VAR, &logLevel64, LOG_LEVEL_WARN));
    logLevel = (UINT32) logLevel64;

    CHK_STATUS(optenv(CANARY_CHANNEL_NAME_ENV_VAR, &pChannelName, CANARY_DEFAULT_CHANNEL_NAME));
    CHK_STATUS(optenv(CANARY_CLIENT_ID_ENV_VAR, &pClientId, CANARY_DEFAULT_CLIENT_ID));
    CHK_STATUS(optenvBool(CANARY_IS_MASTER_ENV_VAR, &isMaster, TRUE));

    CHK_STATUS(optenv(CANARY_LOG_GROUP_NAME_ENV_VAR, &pLogGroupName, CANARY_DEFAULT_LOG_GROUP_NAME));
    STRNCPY(logGroupName, pLogGroupName, ARRAY_SIZE(logGroupName) - 1);

    pLogStreamName = getenv(CANARY_LOG_STREAM_NAME_ENV_VAR);
    if (pLogStreamName != NULL) {
        STRNCPY(logStreamName, pLogStreamName, ARRAY_SIZE(logStreamName) - 1);
    } else {
        SNPRINTF(logStreamName, ARRAY_SIZE(logStreamName) - 1, "%s-%s-%llu", pChannelName,
                 isMaster ? "master" : "viewer", GETTIME() / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
    }

    CHK_STATUS(optenvUint64(CANARY_DURATION_IN_SECONDS_ENV_VAR, &duration, 0));
    duration *= HUNDREDS_OF_NANOS_IN_A_SECOND;

    // Iteration duration is an optional param
    CHK_STATUS(optenvUint64(CANARY_ITERATION_IN_SECONDS_ENV_VAR, &iterationDuration, CANARY_DEFAULT_ITERATION_DURATION_IN_SECONDS));
    iterationDuration *= HUNDREDS_OF_NANOS_IN_A_SECOND;

CleanUp:

    return retStatus;
}

} // namespace Canary
