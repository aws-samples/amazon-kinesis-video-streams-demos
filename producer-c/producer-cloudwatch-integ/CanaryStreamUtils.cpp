/**
 * Kinesis Video Producer Continuous Retry Stream Callbacks
 */
#define LOG_CLASS "CanaryStreamCallbacks"
#include "CanaryStreamUtils.h"

STATUS createCanaryStreamCallbacks(Aws::CloudWatch::CloudWatchClient* cwClient,
                                   PCHAR pStreamName,
                                   PCanaryStreamCallbacks* ppCanaryStreamCallbacks)
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

    dimension.SetName(pStreamName);
    dimension.SetValue("ProducerSDK");

    pCanaryStreamCallbacks->receivedAckDatum.SetMetricName("ReceivedAckLatency");
    pCanaryStreamCallbacks->persistedAckDatum.SetMetricName("PersistedAckLatency");
    pCanaryStreamCallbacks->bufferingAckDatum.SetMetricName("BufferedAckLatency");
    pCanaryStreamCallbacks->streamErrorDatum.SetMetricName("StreamError");
    pCanaryStreamCallbacks->currentFrameRateDatum.SetMetricName("FrameRate");
    pCanaryStreamCallbacks->contentStoreAvailableSizeDatum.SetMetricName("StorageSizeAvailable");
    pCanaryStreamCallbacks->currentViewDurationDatum.SetMetricName("CurrentViewDuration");
    pCanaryStreamCallbacks->memoryAllocationSizeDatum.SetMetricName("MemoryAllocation");

    pCanaryStreamCallbacks->receivedAckDatum.AddDimensions(dimension);
    pCanaryStreamCallbacks->persistedAckDatum.AddDimensions(dimension);
    pCanaryStreamCallbacks->bufferingAckDatum.AddDimensions(dimension);
    pCanaryStreamCallbacks->streamErrorDatum.AddDimensions(dimension);
    pCanaryStreamCallbacks->currentFrameRateDatum.AddDimensions(dimension);
    pCanaryStreamCallbacks->contentStoreAvailableSizeDatum.AddDimensions(dimension);
    pCanaryStreamCallbacks->currentViewDurationDatum.AddDimensions(dimension);
    pCanaryStreamCallbacks->memoryAllocationSizeDatum.AddDimensions(dimension);

    // Set callbacks
    pCanaryStreamCallbacks->streamCallbacks.fragmentAckReceivedFn = canaryStreamFragmentAckHandler;
    pCanaryStreamCallbacks->streamCallbacks.streamErrorReportFn = canaryStreamErrorReportHandler;
    pCanaryStreamCallbacks->streamCallbacks.freeStreamCallbacksFn = canaryStreamFreeHandler;

CleanUp:

    if (STATUS_FAILED(retStatus)) {
        pCanaryStreamCallbacks = NULL;
        MEMFREE(pCanaryStreamCallbacks);
    }

    if(ppCanaryStreamCallbacks != NULL) {
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

    delete(pCanaryStreamCallbacks->timeOfNextKeyFrame);
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

STATUS canaryStreamErrorReportHandler(UINT64 customData, STREAM_HANDLE streamHandle,
                                               UPLOAD_HANDLE uploadHandle, UINT64 erroredTimecode,
                                               STATUS statusCode)
{
    PCanaryStreamCallbacks pCanaryStreamCallbacks = (PCanaryStreamCallbacks) customData;

    DLOGE("CanaryStreamErrorReportHandler got error %lu at time %" PRIu64 " for stream % " PRIu64 " for upload handle %" PRIu64, statusCode, erroredTimecode, streamHandle, uploadHandle);
    pCanaryStreamCallbacks->streamErrorDatum.SetValue(1.0);
    pCanaryStreamCallbacks->streamErrorDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::None);
    canaryStreamSendMetrics(pCanaryStreamCallbacks, pCanaryStreamCallbacks->streamErrorDatum);

    return STATUS_SUCCESS;
}

STATUS canaryStreamFragmentAckHandler(UINT64 customData,
                                      STREAM_HANDLE streamHandle,
                                      UPLOAD_HANDLE uploadHandle,
                                      PFragmentAck pFragmentAck)
{
    PCanaryStreamCallbacks pCanaryStreamCallbacks = (PCanaryStreamCallbacks) customData;
    UINT64 timeOfFragmentEndSent = pCanaryStreamCallbacks->timeOfNextKeyFrame->find(pFragmentAck->timestamp)->second;

    switch (pFragmentAck->ackType) {
    case FRAGMENT_ACK_TYPE_BUFFERING:
            pCanaryStreamCallbacks->bufferingAckDatum.SetValue((GETTIME() - timeOfFragmentEndSent) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
            pCanaryStreamCallbacks->bufferingAckDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Milliseconds);
            canaryStreamSendMetrics(pCanaryStreamCallbacks, pCanaryStreamCallbacks->bufferingAckDatum);
    break;
        case FRAGMENT_ACK_TYPE_RECEIVED:
            pCanaryStreamCallbacks->receivedAckDatum.SetValue((GETTIME() - timeOfFragmentEndSent) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
            pCanaryStreamCallbacks->receivedAckDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Milliseconds);
            canaryStreamSendMetrics(pCanaryStreamCallbacks, pCanaryStreamCallbacks->receivedAckDatum);
            break;
        case FRAGMENT_ACK_TYPE_PERSISTED:
            pCanaryStreamCallbacks->persistedAckDatum.SetValue((GETTIME() - timeOfFragmentEndSent) / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
            pCanaryStreamCallbacks->persistedAckDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Milliseconds);
            canaryStreamSendMetrics(pCanaryStreamCallbacks, pCanaryStreamCallbacks->persistedAckDatum);
            pCanaryStreamCallbacks->timeOfNextKeyFrame->erase(pFragmentAck->timestamp);
            break;
        case FRAGMENT_ACK_TYPE_ERROR:
            DLOGE("Received Error Ack timestamp %" PRIu64 " fragment number %s error code %lu", pFragmentAck->timestamp, pFragmentAck->sequenceNumber, pFragmentAck->result);
            break;
        default:
            break;
    }
    return STATUS_SUCCESS;
}

VOID onPutMetricDataResponseReceivedHandler(const Aws::CloudWatch::CloudWatchClient* cwClient,
                                            const Aws::CloudWatch::Model::PutMetricDataRequest& request,
                                            const Aws::CloudWatch::Model::PutMetricDataOutcome& outcome,
                                            const std::shared_ptr<const Aws::Client::AsyncCallerContext>& context) {
    if (!outcome.IsSuccess()) {
        DLOGE("Failed to put sample metric data: %s", outcome.GetError().GetMessage().c_str());
    }
    else {
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

STATUS computeStreamMetricsFromCanary(STREAM_HANDLE streamHandle, PCanaryStreamCallbacks pCanaryStreamCallbacks) {
    STATUS retStatus = STATUS_SUCCESS;
    StreamMetrics canaryStreamMetrics;
    canaryStreamMetrics.version = STREAM_METRICS_CURRENT_VERSION;
    CHK_STATUS(getKinesisVideoStreamMetrics(streamHandle, &canaryStreamMetrics));
    pCanaryStreamCallbacks->currentFrameRateDatum.SetValue(canaryStreamMetrics.currentFrameRate);
    pCanaryStreamCallbacks->currentFrameRateDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::None);
    canaryStreamSendMetrics(pCanaryStreamCallbacks, pCanaryStreamCallbacks->currentFrameRateDatum);
    pCanaryStreamCallbacks->currentViewDurationDatum.SetValue(canaryStreamMetrics.currentViewDuration / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
    pCanaryStreamCallbacks->currentViewDurationDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Milliseconds);
    canaryStreamSendMetrics(pCanaryStreamCallbacks, pCanaryStreamCallbacks->currentViewDurationDatum);
CleanUp:
    return retStatus;
}

STATUS computeClientMetricsFromCanary(CLIENT_HANDLE clientHandle, PCanaryStreamCallbacks pCanaryStreamCallbacks) {
    STATUS retStatus = STATUS_SUCCESS;
    ClientMetrics canaryClientMetrics;
    canaryClientMetrics.version = CLIENT_METRICS_CURRENT_VERSION;
    CHK_STATUS(getKinesisVideoMetrics(clientHandle, &canaryClientMetrics));
    pCanaryStreamCallbacks->contentStoreAvailableSizeDatum.SetValue(canaryClientMetrics.contentStoreAvailableSize);
    pCanaryStreamCallbacks->contentStoreAvailableSizeDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::None);
    canaryStreamSendMetrics(pCanaryStreamCallbacks, pCanaryStreamCallbacks->contentStoreAvailableSizeDatum);
CleanUp:
    return retStatus;
}

VOID currentMemoryAllocation(PCanaryStreamCallbacks pCanaryStreamCallbacks) {
    pCanaryStreamCallbacks->memoryAllocationSizeDatum.SetValue(getInstrumentedTotalAllocationSize());
    pCanaryStreamCallbacks->memoryAllocationSizeDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::None);
    canaryStreamSendMetrics(pCanaryStreamCallbacks, pCanaryStreamCallbacks->memoryAllocationSizeDatum);
}
VOID canaryStreamRecordFragmentEndSendTime(PCanaryStreamCallbacks pCanaryStreamCallbacks, UINT64 lastKeyFrameTime, UINT64 curKeyFrameTime) {
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
