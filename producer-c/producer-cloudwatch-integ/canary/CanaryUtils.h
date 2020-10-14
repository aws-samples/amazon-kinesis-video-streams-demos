#ifndef __KINESIS_VIDEO_CONTINUOUS_RETRY_INCLUDE_I__
#define __KINESIS_VIDEO_CONTINUOUS_RETRY_INCLUDE_I__

#pragma once

#include <com/amazonaws/kinesis/video/cproducer/Include.h>
#include <aws/core/Aws.h>
#include <aws/monitoring/CloudWatchClient.h>
#include <aws/monitoring/model/PutMetricDataRequest.h>
#include <aws/logs/CloudWatchLogsClient.h>
#include <aws/logs/model/CreateLogGroupRequest.h>
#include <aws/logs/model/CreateLogStreamRequest.h>
#include <aws/logs/model/PutLogEventsRequest.h>
#include <aws/logs/model/DeleteLogStreamRequest.h>
#include <aws/logs/model/DescribeLogStreamsRequest.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DEFAULT_RETENTION_PERIOD     (2 * HUNDREDS_OF_NANOS_IN_AN_HOUR)
#define DEFAULT_BUFFER_DURATION      (120 * HUNDREDS_OF_NANOS_IN_A_SECOND)
#define DEFAULT_CALLBACK_CHAIN_COUNT 5
#define DEFAULT_KEY_FRAME_INTERVAL   45
#define DEFAULT_FPS_VALUE            25
#define DEFAULT_STREAM_DURATION      (20 * HUNDREDS_OF_NANOS_IN_A_SECOND)

#define NUMBER_OF_FRAME_FILES 403
#define CANARY_METADATA_SIZE  (SIZEOF(INT64) + SIZEOF(UINT32) + SIZEOF(UINT32) + SIZEOF(UINT64))

#define CANARY_FILE_LOGGING_BUFFER_SIZE (200 * 1024)
#define CANARY_MAX_NUMBER_OF_LOG_FILES  10
#define CANARY_APP_FILE_LOGGER          (PCHAR) "ENABLE_FILE_LOGGER"

#define CANARY_TYPE_REALTIME           (PCHAR) "Realtime"
#define CANARY_TYPE_OFFLINE            (PCHAR) "Offline"
#define CANARY_STREAM_NAME_ENV_VAR     (PCHAR) "CANARY_STREAM_NAME"
#define CANARY_TYPE_ENV_VAR            (PCHAR) "CANARY_TYPE"
#define FRAGMENT_SIZE_ENV_VAR          (PCHAR) "FRAGMENT_SIZE_IN_BYTES"
#define CANARY_DURATION_ENV_VAR        (PCHAR) "CANARY_DURATION_IN_SECONDS"
#define CANARY_BUFFER_DURATION_ENV_VAR (PCHAR) "CANARY_BUFFER_DURATION_IN_SECONDS"
#define CANARY_STORAGE_SIZE_ENV_VAR    (PCHAR) "CANARY_STORAGE_SIZE_IN_BYTES"
#define CANARY_LABEL_ENV_VAR           (PCHAR) "CANARY_LABEL"

#define CANARY_DEFAULT_STREAM_NAME         (PCHAR) "TestStream"
#define CANARY_DEFAULT_CANARY_TYPE         CANARY_TYPE_REALTIME
#define CANARY_DEFAULT_DURATION_IN_SECONDS 60
#define CANARY_DEFAULT_FRAGMENT_SIZE       (25 * 1024)
#define CANARY_DEFAULT_CANARY_LABEL        (PCHAR)"Longrun"

#define CANARY_TYPE_STR_LEN        10
#define CANARY_STREAM_NAME_STR_LEN 200
#define CANARY_LABEL_LEN           40
#define MAX_LOG_FILE_NAME_LEN      300

struct __CallbackStateMachine;
struct __CallbacksProvider;

////////////////////////////////////////////////////////////////////////
// Struct definition
////////////////////////////////////////////////////////////////////////

typedef struct {
    CHAR streamNamePrefix[CANARY_STREAM_NAME_STR_LEN + 1];
    CHAR canaryTypeStr[CANARY_TYPE_STR_LEN + 1];
    CHAR canaryLabel[CANARY_LABEL_LEN + 1];
    UINT64 fragmentSizeInBytes;
    UINT64 canaryDuration;
    UINT64 bufferDuration;
    UINT64 storageSizeInBytes;
} CanaryConfig;

typedef CanaryConfig* PCanaryConfig;

typedef struct __CloudwatchLogsObject CloudwatchLogsObject;
struct __CloudwatchLogsObject {
    Aws::CloudWatchLogs::CloudWatchLogsClient* pCwl;
    Aws::CloudWatchLogs::Model::CreateLogGroupRequest canaryLogGroupRequest;
    Aws::CloudWatchLogs::Model::CreateLogStreamRequest canaryLogStreamRequest;
    Aws::CloudWatchLogs::Model::PutLogEventsRequest canaryPutLogEventRequest;
    Aws::CloudWatchLogs::Model::PutLogEventsResult canaryPutLogEventresult;
    Aws::Vector<Aws::CloudWatchLogs::Model::InputLogEvent> canaryInputLogEventVec;
    Aws::String token;
    CHAR logGroupName[MAX_STREAM_NAME_LEN + 1];
    CHAR logStreamName[MAX_LOG_FILE_NAME_LEN + 1];
};
typedef struct __CloudwatchLogsObject* PCloudwatchLogsObject;

typedef struct {
    UINT64 prevErrorAckCount;
    UINT64 prevPutFrameErrorCount;
} HistoricStreamMetric;

typedef struct __CanaryStreamCallbacks CanaryStreamCallbacks;
struct __CanaryStreamCallbacks {
    // First member should be the stream callbacks
    StreamCallbacks streamCallbacks;
    PCHAR pStreamName;
    UINT64 totalNumberOfErrors;
    Aws::CloudWatch::CloudWatchClient* pCwClient;
    Aws::CloudWatch::Model::PutMetricDataRequest* cwRequest;
    Aws::CloudWatch::Model::Dimension dimensionPerStream;
    Aws::CloudWatch::Model::Dimension aggregatedDimension;
    HistoricStreamMetric historicStreamMetric;
    std::map<UINT64, UINT64>* timeOfNextKeyFrame;
};
typedef struct __CanaryStreamCallbacks* PCanaryStreamCallbacks;

////////////////////////////////////////////////////////////////////////
// Callback function implementations
////////////////////////////////////////////////////////////////////////
STATUS createCanaryStreamCallbacks(Aws::CloudWatch::CloudWatchClient*, PCHAR, PCHAR, PCanaryStreamCallbacks*);
STATUS freeCanaryStreamCallbacks(PStreamCallbacks*);
STATUS canaryStreamFragmentAckHandler(UINT64, STREAM_HANDLE, UPLOAD_HANDLE, PFragmentAck);
STATUS canaryStreamErrorReportHandler(UINT64, STREAM_HANDLE, UPLOAD_HANDLE, UINT64, STATUS);
STATUS canaryStreamFreeHandler(PUINT64);
VOID canaryStreamSendMetrics(PCanaryStreamCallbacks, Aws::CloudWatch::Model::MetricDatum&);
VOID canaryStreamRecordFragmentEndSendTime(PCanaryStreamCallbacks, UINT64, UINT64);
STATUS computeStreamMetricsFromCanary(STREAM_HANDLE, PCanaryStreamCallbacks);
STATUS computeClientMetricsFromCanary(CLIENT_HANDLE, PCanaryStreamCallbacks);
VOID currentMemoryAllocation(PCanaryStreamCallbacks);
VOID pushMetric(PCanaryStreamCallbacks pCanaryStreamCallback, Aws::CloudWatch::Model::MetricDatum&, Aws::CloudWatch::Model::StandardUnit, DOUBLE);
STATUS publishErrorRate(STREAM_HANDLE, PCanaryStreamCallbacks, UINT64);

////////////////////////////////////////////////////////////////////////
// Cloudwatch logging related functions
////////////////////////////////////////////////////////////////////////
VOID cloudWatchLogger(UINT32, PCHAR, PCHAR, ...);
STATUS initializeCloudwatchLogger(PCloudwatchLogsObject);
VOID canaryStreamSendLogs(PCloudwatchLogsObject);
VOID canaryStreamSendLogSync(PCloudwatchLogsObject);

////////////////////////////////////////////////////////////////////////
// Initial canary setup
////////////////////////////////////////////////////////////////////////
VOID getJsonValue(PBYTE, jsmntok_t, PCHAR);
STATUS parseConfigFile(PCanaryConfig, PCHAR);
STATUS optenv(PCHAR, PCHAR, PCHAR);
STATUS optenvUint64(PCHAR, PUINT64, UINT64);
STATUS printConfig(PCanaryConfig);
STATUS initWithEnvVars(PCanaryConfig);

#ifdef __cplusplus
}
#endif

#endif //__KINESIS_VIDEO_CONTINUOUS_RETRY_INCLUDE_I__
