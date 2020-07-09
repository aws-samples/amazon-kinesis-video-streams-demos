#ifndef __KINESIS_VIDEO_CONTINUOUS_RETRY_INCLUDE_I__
#define __KINESIS_VIDEO_CONTINUOUS_RETRY_INCLUDE_I__

#pragma once

#include <com/amazonaws/kinesis/video/cproducer/Include.h>
#include <aws/core/Aws.h>
#include <aws/monitoring/CloudWatchClient.h>
#include <aws/monitoring/model/PutMetricDataRequest.h>
#include <aws/s3/model/CreateBucketRequest.h>
#include <aws/logs/CloudWatchLogsClient.h>
#include <aws/logs/model/CreateLogGroupRequest.h>
#include <aws/logs/model/CreateLogStreamRequest.h>
#include <aws/logs/model/PutLogEventsRequest.h>
#include <aws/logs/model/DeleteLogStreamRequest.h>
#include <aws/logs/model/DescribeLogStreamsRequest.h>

#ifdef  __cplusplus
extern "C" {
#endif

#define DEFAULT_RETENTION_PERIOD            (2 * HUNDREDS_OF_NANOS_IN_AN_HOUR)
#define DEFAULT_BUFFER_DURATION             (120 * HUNDREDS_OF_NANOS_IN_A_SECOND)
#define DEFAULT_CALLBACK_CHAIN_COUNT        5
#define DEFAULT_KEY_FRAME_INTERVAL          45
#define DEFAULT_FPS_VALUE                   25
#define DEFAULT_STREAM_DURATION             (20 * HUNDREDS_OF_NANOS_IN_A_SECOND)

#define NUMBER_OF_FRAME_FILES               403
#define CANARY_METADATA_SIZE                (SIZEOF(INT64) + SIZEOF(UINT32) + SIZEOF(UINT32) + SIZEOF(UINT64))

#define CANARY_FILE_LOGGING_BUFFER_SIZE     (200 * 1024)
#define CANARY_MAX_NUMBER_OF_LOG_FILES      10
#define CANARY_APP_FILE_LOGGER              (PCHAR) "ENABLE_FILE_LOGGER"
struct __CallbackStateMachine;
struct __CallbacksProvider;

using namespace Aws::Client;
using namespace Aws::CloudWatchLogs;
using namespace Aws::CloudWatchLogs::Model;
using namespace Aws::CloudWatch::Model;
using namespace Aws::CloudWatch;
using namespace std;

////////////////////////////////////////////////////////////////////////
// Struct definition
////////////////////////////////////////////////////////////////////////

typedef struct __CloudwatchLogsObject CloudwatchLogsObject;
struct __CloudwatchLogsObject {
    CloudWatchLogsClient* pCwl;
    CreateLogGroupRequest canaryLogGroupRequest;
    CreateLogStreamRequest canaryLogStreamRequest;
    PutLogEventsRequest canaryPutLogEventRequest;
    PutLogEventsResult canaryPutLogEventresult;
    Aws::Vector<InputLogEvent> canaryInputLogEventVec;
    Aws::String token;
    CHAR logGroupName[MAX_STREAM_NAME_LEN + 1];
    CHAR logStreamName[MAX_STREAM_NAME_LEN + 1];
};
typedef struct __CloudwatchLogsObject* PCloudwatchLogsObject;

typedef struct __CanaryStreamCallbacks CanaryStreamCallbacks;
struct __CanaryStreamCallbacks {
    // First member should be the stream callbacks
    StreamCallbacks streamCallbacks;
    PCHAR pStreamName;
    CloudWatchClient* pCwClient;
    PutMetricDataRequest* cwRequest;
    MetricDatum receivedAckDatum;
    MetricDatum persistedAckDatum;
    MetricDatum bufferingAckDatum;
    MetricDatum streamErrorDatum;
    MetricDatum currentFrameRateDatum;
    MetricDatum currentViewDurationDatum;
    MetricDatum contentStoreAvailableSizeDatum;
    MetricDatum memoryAllocationSizeDatum;
    map<UINT64, UINT64>* timeOfNextKeyFrame;
};
typedef struct __CanaryStreamCallbacks* PCanaryStreamCallbacks;

////////////////////////////////////////////////////////////////////////
// Callback function implementations
////////////////////////////////////////////////////////////////////////
STATUS createCanaryStreamCallbacks(Aws::CloudWatch::CloudWatchClient*, PCHAR, PCanaryStreamCallbacks*);
STATUS freeCanaryStreamCallbacks(PStreamCallbacks*);
STATUS canaryStreamFragmentAckHandler(UINT64, STREAM_HANDLE, UPLOAD_HANDLE, PFragmentAck);
STATUS canaryStreamErrorReportHandler(UINT64, STREAM_HANDLE, UPLOAD_HANDLE, UINT64, STATUS);
STATUS canaryStreamFreeHandler(PUINT64);
VOID canaryStreamSendMetrics(PCanaryStreamCallbacks, Aws::CloudWatch::Model::MetricDatum&);
VOID canaryStreamRecordFragmentEndSendTime(PCanaryStreamCallbacks, UINT64, UINT64);
STATUS computeStreamMetricsFromCanary(STREAM_HANDLE, PCanaryStreamCallbacks);
STATUS computeClientMetricsFromCanary(CLIENT_HANDLE, PCanaryStreamCallbacks);
VOID currentMemoryAllocation(PCanaryStreamCallbacks);

////////////////////////////////////////////////////////////////////////
// Cloudwatch logging related functions
////////////////////////////////////////////////////////////////////////
VOID cloudWatchLogger(UINT32, PCHAR, PCHAR, ...);
STATUS initializeCloudwatchLogger(PCloudwatchLogsObject);
VOID canaryStreamSendLogs(PCloudwatchLogsObject);
VOID canaryStreamSendLogSync(PCloudwatchLogsObject);

#ifdef  __cplusplus
}
#endif

#endif //__KINESIS_VIDEO_CONTINUOUS_RETRY_INCLUDE_I__
