/**
 * Kinesis Video Producer Continuous Retry Stream Callbacks
 */
#define LOG_CLASS "CanaryStreamCallbacks"
#include "CanaryUtils.h"

STATUS createCanaryStreamCallbacks(Aws::CloudWatch::CloudWatchClient* cwClient, PCHAR pStreamName, PCHAR canaryLabel, PCanaryStreamCallbacks* ppCanaryStreamCallbacks)
{
    ENTERS();
    STATUS retStatus = STATUS_SUCCESS;
    Aws::CloudWatch::Model::Dimension dimension;
    PCanaryStreamCallbacks pCanaryStreamCallbacks = NULL;

    CHK(ppCanaryStreamCallbacks != NULL, STATUS_NULL_ARG);

    // Allocate the entire structure
    pCanaryStreamCallbacks = (PCanaryStreamCallbacks) MEMCALLOC(1, SIZEOF(CanaryStreamCallbacks));
    CHK(pCanaryStreamCallbacks != NULL, STATUS_NOT_ENOUGH_MEMORY);

    // Set the version, self
    pCanaryStreamCallbacks->streamCallbacks.version = STREAM_CALLBACKS_CURRENT_VERSION;
    pCanaryStreamCallbacks->streamCallbacks.customData = (UINT64) pCanaryStreamCallbacks;
    pCanaryStreamCallbacks->timeOfNextKeyFrame = new std::map<UINT64, UINT64>();

    pCanaryStreamCallbacks->pCwClient = cwClient;

    pCanaryStreamCallbacks->dimensionPerStream.SetName("ProducerSDKCanaryStreamName");
    pCanaryStreamCallbacks->dimensionPerStream.SetValue(pStreamName);

    pCanaryStreamCallbacks->aggregatedDimension.SetName("ProducerSDKCanaryType");
    pCanaryStreamCallbacks->aggregatedDimension.SetValue(canaryLabel);

    // Set callbacks
    pCanaryStreamCallbacks->streamCallbacks.fragmentAckReceivedFn = canaryStreamFragmentAckHandler;
    pCanaryStreamCallbacks->streamCallbacks.streamErrorReportFn = canaryStreamErrorReportHandler;
    pCanaryStreamCallbacks->streamCallbacks.freeStreamCallbacksFn = canaryStreamFreeHandler;

CleanUp:

    if (STATUS_FAILED(retStatus)) {
        pCanaryStreamCallbacks = NULL;
        MEMFREE(pCanaryStreamCallbacks);
    }

    if (ppCanaryStreamCallbacks != NULL) {
        *ppCanaryStreamCallbacks = (PCanaryStreamCallbacks) pCanaryStreamCallbacks;
    }

    LEAVES();
    return retStatus;
}

STATUS freeCanaryStreamCallbacks(PStreamCallbacks* ppStreamCallbacks)
{
    ENTERS();
    STATUS retStatus = STATUS_SUCCESS;
    PCanaryStreamCallbacks pCanaryStreamCallbacks = NULL;

    CHK(ppStreamCallbacks != NULL, STATUS_NULL_ARG);

    pCanaryStreamCallbacks = (PCanaryStreamCallbacks) *ppStreamCallbacks;

    // Call is idempotent
    CHK(pCanaryStreamCallbacks != NULL, retStatus);

    delete (pCanaryStreamCallbacks->timeOfNextKeyFrame);
    // Release the object
    MEMFREE(pCanaryStreamCallbacks);

    // Set the pointer to NULL
    *ppStreamCallbacks = NULL;

CleanUp:

    LEAVES();
    return retStatus;
}

STATUS canaryStreamFreeHandler(PUINT64 customData)
{
    ENTERS();
    STATUS retStatus = STATUS_SUCCESS;
    PStreamCallbacks pStreamCallbacks;

    CHK(customData != NULL, STATUS_NULL_ARG);
    pStreamCallbacks = (PStreamCallbacks) *customData;
    CHK_STATUS(freeCanaryStreamCallbacks(&pStreamCallbacks));

CleanUp:

    LEAVES();
    return retStatus;
}

STATUS canaryStreamErrorReportHandler(UINT64 customData, STREAM_HANDLE streamHandle, UPLOAD_HANDLE uploadHandle, UINT64 erroredTimecode,
                                      STATUS statusCode)
{
    PCanaryStreamCallbacks pCanaryStreamCallbacks = (PCanaryStreamCallbacks) customData;
    Aws::CloudWatch::Model::MetricDatum streamErrorDatum, aggstreamErrorDatum;
    DLOGE("CanaryStreamErrorReportHandler got error %lu at time %" PRIu64 " for stream % " PRIu64 " for upload handle %" PRIu64, statusCode,
          erroredTimecode, streamHandle, uploadHandle);
    streamErrorDatum.SetMetricName("StreamError");
    streamErrorDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, streamErrorDatum, Aws::CloudWatch::Model::StandardUnit::None, statusCode);

    aggstreamErrorDatum.SetMetricName("StreamError");
    aggstreamErrorDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
    pushMetric(pCanaryStreamCallbacks, aggstreamErrorDatum, Aws::CloudWatch::Model::StandardUnit::None, statusCode);

    return STATUS_SUCCESS;
}

VOID pushMetric(PCanaryStreamCallbacks pCanaryStreamCallback, Aws::CloudWatch::Model::MetricDatum& metricDatum, Aws::CloudWatch::Model::StandardUnit unit, DOUBLE data)
{
    metricDatum.SetValue(data);
    metricDatum.SetUnit(unit);
    canaryStreamSendMetrics(pCanaryStreamCallback, metricDatum);
}

STATUS canaryStreamFragmentAckHandler(UINT64 customData, STREAM_HANDLE streamHandle, UPLOAD_HANDLE uploadHandle, PFragmentAck pFragmentAck)
{
    PCanaryStreamCallbacks pCanaryStreamCallbacks = (PCanaryStreamCallbacks) customData;
    UINT64 timeOfFragmentEndSent = pCanaryStreamCallbacks->timeOfNextKeyFrame->find(pFragmentAck->timestamp)->second;
    Aws::CloudWatch::Model::MetricDatum ackDatum, aggAckDatum;
    switch (pFragmentAck->ackType) {
        case FRAGMENT_ACK_TYPE_BUFFERING:
            ackDatum.SetMetricName("BufferedAckLatency");
            ackDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
            pushMetric(pCanaryStreamCallbacks, ackDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds, (GETTIME() - timeOfFragmentEndSent) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);

            aggAckDatum.SetMetricName("BufferedAckLatency");
            aggAckDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
            pushMetric(pCanaryStreamCallbacks, aggAckDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds, (GETTIME() - timeOfFragmentEndSent) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);

            break;
        case FRAGMENT_ACK_TYPE_RECEIVED:
            ackDatum.SetMetricName("ReceivedAckLatency");
            ackDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
            pushMetric(pCanaryStreamCallbacks, ackDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds, (GETTIME() - timeOfFragmentEndSent) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);

            aggAckDatum.SetMetricName("ReceivedAckLatency");
            aggAckDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
            pushMetric(pCanaryStreamCallbacks, aggAckDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds, (GETTIME() - timeOfFragmentEndSent) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);

            break;

        case FRAGMENT_ACK_TYPE_PERSISTED:
            ackDatum.SetMetricName("PersistedAckLatency");
            ackDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
            pushMetric(pCanaryStreamCallbacks, ackDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds, (GETTIME() - timeOfFragmentEndSent) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);

            aggAckDatum.SetMetricName("PersistedAckLatency");
            aggAckDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
            pushMetric(pCanaryStreamCallbacks, aggAckDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds, (GETTIME() - timeOfFragmentEndSent) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);

            pCanaryStreamCallbacks->timeOfNextKeyFrame->erase(pFragmentAck->timestamp);

            break;
        case FRAGMENT_ACK_TYPE_ERROR:
            DLOGE("Received Error Ack timestamp %" PRIu64 " fragment number %s error code %lu", pFragmentAck->timestamp, pFragmentAck->sequenceNumber,
                  pFragmentAck->result);
            break;
        default:
            break;
    }
    return STATUS_SUCCESS;
}

VOID onPutMetricDataResponseReceivedHandler(const Aws::CloudWatch::CloudWatchClient* cwClient,
                                            const Aws::CloudWatch::Model::PutMetricDataRequest& request,
                                            const Aws::CloudWatch::Model::PutMetricDataOutcome& outcome,
                                            const std::shared_ptr<const Aws::Client::AsyncCallerContext>& context)
{
    if (!outcome.IsSuccess()) {
        DLOGE("Failed to put sample metric data: %s", outcome.GetError().GetMessage().c_str());
    } else {
        DLOGS("Successfully put sample metric data");
    }
}

VOID canaryStreamSendMetrics(PCanaryStreamCallbacks pCanaryStreamCallbacks, Aws::CloudWatch::Model::MetricDatum& metricDatum)
{
    Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
    cwRequest.SetNamespace("KinesisVideoSDKCanary");
    cwRequest.AddMetricData(metricDatum);
    pCanaryStreamCallbacks->pCwClient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
}

STATUS publishErrorRate(STREAM_HANDLE streamHandle, PCanaryStreamCallbacks pCanaryStreamCallbacks, UINT64 duration)
{
    STATUS retStatus = STATUS_SUCCESS;
    StreamMetrics canaryStreamMetrics;
    canaryStreamMetrics.version = STREAM_METRICS_CURRENT_VERSION;
    Aws::CloudWatch::Model::MetricDatum putFrameErrorRateDatum, aggputFrameErrorRateDatum, errorAckDatum, aggErrorAckDatum;
    CHK(pCanaryStreamCallbacks != NULL, STATUS_NULL_ARG);
    CHK_STATUS(getKinesisVideoStreamMetrics(streamHandle, &canaryStreamMetrics));
    DOUBLE putFrameErrorRate, errorAckRate;
    putFrameErrorRate = (DOUBLE) (canaryStreamMetrics.putFrameErrors - pCanaryStreamCallbacks->historicStreamMetric.prevPutFrameErrorCount) / (DOUBLE) duration;
    pCanaryStreamCallbacks->historicStreamMetric.prevPutFrameErrorCount = canaryStreamMetrics.putFrameErrors;
    putFrameErrorRateDatum.SetMetricName("PutFrameErrorRate");
    putFrameErrorRateDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, putFrameErrorRateDatum, Aws::CloudWatch::Model::StandardUnit::Count_Second, putFrameErrorRate);

    aggputFrameErrorRateDatum.SetMetricName("PutFrameErrorRate");
    aggputFrameErrorRateDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, aggputFrameErrorRateDatum, Aws::CloudWatch::Model::StandardUnit::Count_Second, putFrameErrorRate);

    errorAckRate = (DOUBLE) (canaryStreamMetrics.errorAcks - pCanaryStreamCallbacks->historicStreamMetric.prevErrorAckCount) / (DOUBLE) duration;
    pCanaryStreamCallbacks->historicStreamMetric.prevErrorAckCount = canaryStreamMetrics.errorAcks;
    errorAckDatum.SetMetricName("ErrorAckRate");
    errorAckDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, errorAckDatum, Aws::CloudWatch::Model::StandardUnit::Count_Second, putFrameErrorRate);

    aggErrorAckDatum.SetMetricName("ErrorAckRate");
    aggErrorAckDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, aggErrorAckDatum, Aws::CloudWatch::Model::StandardUnit::Count_Second, putFrameErrorRate);

    DLOGD("Error ack rate: %lf, putFrame error rate: %lf", errorAckRate, putFrameErrorRate);
CleanUp:
    return retStatus;
}

STATUS computeStreamMetricsFromCanary(STREAM_HANDLE streamHandle, PCanaryStreamCallbacks pCanaryStreamCallbacks)
{
    STATUS retStatus = STATUS_SUCCESS;
    StreamMetrics canaryStreamMetrics;
    canaryStreamMetrics.version = STREAM_METRICS_CURRENT_VERSION;
    Aws::CloudWatch::Model::MetricDatum streamDatum, aggStreamDatum, currentViewDatum, aggCurrentViewDatum;
    CHK_STATUS(getKinesisVideoStreamMetrics(streamHandle, &canaryStreamMetrics));

    streamDatum.SetMetricName("FrameRate");
    streamDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, streamDatum, Aws::CloudWatch::Model::StandardUnit::Count_Second, canaryStreamMetrics.currentFrameRate);


    aggStreamDatum.SetMetricName("FrameRate");
    aggStreamDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
    pushMetric(pCanaryStreamCallbacks, aggStreamDatum, Aws::CloudWatch::Model::StandardUnit::Count_Second, canaryStreamMetrics.currentFrameRate);

    currentViewDatum.SetMetricName("CurrentViewDuration");
    currentViewDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, currentViewDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds, canaryStreamMetrics.currentViewDuration / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);

    aggCurrentViewDatum.SetMetricName("CurrentViewDuration");
    aggCurrentViewDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
    pushMetric(pCanaryStreamCallbacks, aggCurrentViewDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds, canaryStreamMetrics.currentViewDuration / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
CleanUp:
    return retStatus;
}

STATUS computeClientMetricsFromCanary(CLIENT_HANDLE clientHandle, PCanaryStreamCallbacks pCanaryStreamCallbacks)
{
    STATUS retStatus = STATUS_SUCCESS;
    ClientMetrics canaryClientMetrics;
    canaryClientMetrics.version = CLIENT_METRICS_CURRENT_VERSION;
    Aws::CloudWatch::Model::MetricDatum clientDatum, aggClientDatum;
    CHK_STATUS(getKinesisVideoMetrics(clientHandle, &canaryClientMetrics));

    clientDatum.SetMetricName("StorageSizeAvailable");
    clientDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, clientDatum, Aws::CloudWatch::Model::StandardUnit::Bytes, canaryClientMetrics.contentStoreAvailableSize);

    aggClientDatum.SetMetricName("StorageSizeAvailable");
    aggClientDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
    pushMetric(pCanaryStreamCallbacks, aggClientDatum, Aws::CloudWatch::Model::StandardUnit::Bytes, canaryClientMetrics.contentStoreAvailableSize);
CleanUp:
    return retStatus;
}

VOID currentMemoryAllocation(PCanaryStreamCallbacks pCanaryStreamCallbacks)
{
    Aws::CloudWatch::Model::MetricDatum memoryDatum, aggMemoryDatum;
    memoryDatum.SetMetricName("MemoryAllocation");
    memoryDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, memoryDatum, Aws::CloudWatch::Model::StandardUnit::Bytes, getInstrumentedTotalAllocationSize());

    aggMemoryDatum.SetMetricName("MemoryAllocation");
    aggMemoryDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
    pushMetric(pCanaryStreamCallbacks, aggMemoryDatum, Aws::CloudWatch::Model::StandardUnit::Bytes, getInstrumentedTotalAllocationSize());
}
VOID canaryStreamRecordFragmentEndSendTime(PCanaryStreamCallbacks pCanaryStreamCallbacks, UINT64 lastKeyFrameTime, UINT64 curKeyFrameTime)
{
    auto mapPtr = pCanaryStreamCallbacks->timeOfNextKeyFrame;
    (*mapPtr)[lastKeyFrameTime / HUNDREDS_OF_NANOS_IN_A_MILLISECOND] = curKeyFrameTime;
    auto iter = mapPtr->begin();
    while (iter != mapPtr->end()) {
        // clean up from current timestamp of 5 min timestamps would be removed
        if (iter->first < (GETTIME() - (300 * HUNDREDS_OF_NANOS_IN_A_SECOND)) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND) {
            iter = mapPtr->erase(iter);
        } else {
            break;
        }
    }
}
