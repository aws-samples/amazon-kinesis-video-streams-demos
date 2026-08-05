#include "Include.h"

namespace Canary {

Cloudwatch::Cloudwatch(Canary::PConfig pConfig, ClientConfiguration* pClientConfig)
    : logs(pConfig, pClientConfig), monitoring(pConfig, pClientConfig), terminated(FALSE), useFileLogger(FALSE)
{
}

std::atomic<BOOL> Cloudwatch::initialized{FALSE};

STATUS Cloudwatch::init(Canary::PConfig pConfig)
{
    ENTERS();
    STATUS retStatus = STATUS_SUCCESS;
    ClientConfiguration clientConfig;
    CreateLogGroupRequest createLogGroupRequest;
    Aws::CloudWatchLogs::Model::CreateLogStreamOutcome createLogStreamOutcome;
    CreateLogStreamRequest createLogStreamRequest;

    CHK(pConfig != NULL, STATUS_NULL_ARG);

    clientConfig.region = pConfig->region.value;
    auto& instance = getInstanceImpl(pConfig, &clientConfig);
    // The singleton now exists (constructed with real arguments above), so
    // deinit()/logger() are safe from here on even if the steps below fail.
    initialized = TRUE;

    if (STATUS_FAILED(instance.logs.init())) {
        DLOGW("Failed to create Cloudwatch logger, fallback to file logger");
        CHK_STATUS(createFileLogger(DEFAULT_FILE_LOGGING_BUFFER_SIZE, MAX_FILE_LOGGER_LOG_FILE_COUNT, (PCHAR) FILE_LOGGER_LOG_FILE_DIRECTORY_PATH,
                                    TRUE, TRUE, NULL));
        instance.useFileLogger = TRUE;
    } else {
        globalCustomLogPrintFn = logger;
    }

    CHK_STATUS(instance.monitoring.init());

CleanUp:

    LEAVES();
    return retStatus;
}

Cloudwatch& Cloudwatch::getInstance()
{
    return getInstanceImpl();
}

Cloudwatch& Cloudwatch::getInstanceImpl(Canary::PConfig pConfig, ClientConfiguration* pClientConfig)
{
    static Cloudwatch instance{pConfig, pClientConfig};
    return instance;
}

VOID Cloudwatch::deinit()
{
    // If init() never constructed the singleton (e.g. it bailed on missing
    // credentials), there is nothing to tear down — and calling getInstance()
    // here would lazily construct it with NULL config and segfault inside the
    // AWS client constructors.
    if (!initialized) {
        return;
    }

    auto& instance = getInstance();
    if (instance.useFileLogger) {
        freeFileLogger();
    } else {
        instance.logs.deinit();
    }
    instance.monitoring.deinit();
    instance.terminated = TRUE;
}

VOID Cloudwatch::logger(UINT32 level, PCHAR tag, PCHAR fmt, ...)
{
    CHAR logFmtString[MAX_LOG_FORMAT_LENGTH + 1];
    CHAR cwLogFmtString[MAX_LOG_FORMAT_LENGTH + 1];
    UINT32 logLevel = GET_LOGGER_LOG_LEVEL();
    UNUSED_PARAM(tag);

    if (level >= logLevel) {
        addLogMetadata(logFmtString, (UINT32) ARRAY_SIZE(logFmtString), fmt, level);

        // Creating a copy to store the logFmtString for cloudwatch logging purpose
        va_list valist, valist_cw;
        va_start(valist_cw, fmt);
        vsnprintf(cwLogFmtString, (SIZE_T) SIZEOF(cwLogFmtString), logFmtString, valist_cw);
        va_end(valist_cw);
        va_start(valist, fmt);
        vprintf(logFmtString, valist);
        va_end(valist);

        // Same guard as deinit(): never lazily construct the singleton with
        // NULL config from a log call that races/precedes init().
        if (initialized) {
            auto& instance = getInstance();
            if (!instance.terminated) {
                instance.logs.push(cwLogFmtString);
            }
        }
    }
}

} // namespace Canary
