#ifndef __KINESIS_VIDEO_CONTINUOUS_RETRY_INCLUDE_I__
#define __KINESIS_VIDEO_CONTINUOUS_RETRY_INCLUDE_I__

#pragma once

#include <com/amazonaws/kinesis/video/cproducer/Include.h>
#include <aws/core/Aws.h>
#include <aws/monitoring/CloudWatchClient.h>
#include <aws/monitoring/model/PutMetricDataRequest.h>

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

struct __CallbackStateMachine;
struct __CallbacksProvider;

////////////////////////////////////////////////////////////////////////
// Struct definition
////////////////////////////////////////////////////////////////////////

typedef struct __CanaryStreamCallbacks CanaryStreamCallbacks;
struct __CanaryStreamCallbacks {
    // First member should be the stream callbacks
    StreamCallbacks streamCallbacks;
    PCHAR pStreamName;
    Aws::CloudWatch::CloudWatchClient* pCwClient;
    Aws::CloudWatch::Model::PutMetricDataRequest* cwRequest;
    Aws::CloudWatch::Model::MetricDatum receivedAckDatum;
    Aws::CloudWatch::Model::MetricDatum persistedAckDatum;
    Aws::CloudWatch::Model::MetricDatum bufferingAckDatum;
    Aws::CloudWatch::Model::MetricDatum streamErrorDatum;
    Aws::CloudWatch::Model::MetricDatum currentFrameRateDatum;
    Aws::CloudWatch::Model::MetricDatum currentViewDurationDatum;
    Aws::CloudWatch::Model::MetricDatum contentStoreAvailableSizeDatum;
    Aws::CloudWatch::Model::MetricDatum memoryAllocationSizeDatum;
    std::map<UINT64, UINT64>* timeOfNextKeyFrame;
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

#ifdef  __cplusplus
}
#endif

#endif //__KINESIS_VIDEO_CONTINUOUS_RETRY_INCLUDE_I__
