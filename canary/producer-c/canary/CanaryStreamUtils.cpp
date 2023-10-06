/**
 * Kinesis Video Producer Continuous Retry Stream Callbacks
 */
#define LOG_CLASS "CanaryStreamCallbacks"
#include <numeric>
#include "CanaryUtils.h"

std::atomic<UINT64> pendingMetrics;

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

    pCanaryStreamCallbacks->aggregateMetrics = TRUE;

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
    pushMetric(pCanaryStreamCallbacks, streamErrorDatum, Aws::CloudWatch::Model::StandardUnit::None, 1.0);

    if (pCanaryStreamCallbacks->aggregateMetrics) {
        aggstreamErrorDatum.SetMetricName("StreamError");
        aggstreamErrorDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
        pushMetric(pCanaryStreamCallbacks, aggstreamErrorDatum, Aws::CloudWatch::Model::StandardUnit::None, 1.0);
    }
    pCanaryStreamCallbacks->totalNumberOfErrors++;

    return STATUS_SUCCESS;
}

static const CHAR* unitToString(const Aws::CloudWatch::Model::StandardUnit& unit)
{
    switch (unit) {
        case Aws::CloudWatch::Model::StandardUnit::Count:
            return "Count";
        case Aws::CloudWatch::Model::StandardUnit::Count_Second:
            return "Count_Second";
        case Aws::CloudWatch::Model::StandardUnit::Milliseconds:
            return "Milliseconds";
        case Aws::CloudWatch::Model::StandardUnit::Percent:
            return "Percent";
        case Aws::CloudWatch::Model::StandardUnit::None:
            return "None";
        case Aws::CloudWatch::Model::StandardUnit::Kilobits_Second:
            return "Kilobits_Second";
        case Aws::CloudWatch::Model::StandardUnit::Kilobytes:
            return "Kilobytes";
        default:
            return "Unknown unit";
    }
}

VOID printMetric(Aws::CloudWatch::Model::MetricDatum& metricDatum)
{
    std::stringstream ss;

    ss << "Emitted the following metric:\n\n";
    ss << "  Name       : " << metricDatum.GetMetricName() << '\n';
    ss << "  Unit       : " << unitToString(metricDatum.GetUnit()) << '\n';

    ss << "  Values     : ";
    auto& values = metricDatum.GetValues();
    // If the datum uses single value, GetValues will be empty and the data will be accessible
    // from GetValue
    if (values.empty()) {
        ss << metricDatum.GetValue();
    } else {
        for (auto i = 0; i < values.size(); i++) {
            ss << values[i];
            if (i != values.size() - 1) {
                ss << ", ";
            }
        }
    }
    ss << '\n';

    ss << "  Dimensions : ";
    auto& dimensions = metricDatum.GetDimensions();
    if (dimensions.empty()) {
        ss << "N/A";
    } else {
        ss << '\n';
        for (auto& dimension : dimensions) {
            ss << "    - " << dimension.GetName() << "\t: " << dimension.GetValue() << '\n';
        }
    }
    ss << '\n';

    DLOGD("%s", ss.str().c_str());
}

VOID pushMetric(PCanaryStreamCallbacks pCanaryStreamCallback, Aws::CloudWatch::Model::MetricDatum& metricDatum, Aws::CloudWatch::Model::StandardUnit unit, DOUBLE data)
{
    metricDatum.SetValue(data);
    metricDatum.SetUnit(unit);
    canaryStreamSendMetrics(pCanaryStreamCallback, metricDatum);

    printMetric(metricDatum);
}

STATUS canaryStreamFragmentAckHandler(UINT64 customData, STREAM_HANDLE streamHandle, UPLOAD_HANDLE uploadHandle, PFragmentAck pFragmentAck)
{
    PCanaryStreamCallbacks pCanaryStreamCallbacks = (PCanaryStreamCallbacks) customData;
    UINT64 timeOfFragmentEndSent = pCanaryStreamCallbacks->timeOfNextKeyFrame->find(pFragmentAck->timestamp)->second;
    DOUBLE time;
    switch (pFragmentAck->ackType) {
        case FRAGMENT_ACK_TYPE_BUFFERING:
            break;
        case FRAGMENT_ACK_TYPE_RECEIVED:
            time = (DOUBLE)(GETTIME() - timeOfFragmentEndSent) / (DOUBLE)HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
            pCanaryStreamCallbacks->receivedAckLatencyVec.push_back(time);
            break;

        case FRAGMENT_ACK_TYPE_PERSISTED:
            time = (DOUBLE)(GETTIME() - timeOfFragmentEndSent) / (DOUBLE)HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
            pCanaryStreamCallbacks->persistedAckLatencyVec.push_back(time);
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
    pendingMetrics--;
    DLOGI("Pending metrics in callback: %d", pendingMetrics.load());
}

VOID canaryStreamSendMetrics(PCanaryStreamCallbacks pCanaryStreamCallbacks, Aws::CloudWatch::Model::MetricDatum& metricDatum)
{
    Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
    cwRequest.SetNamespace("KinesisVideoSDKCanary");
    cwRequest.AddMetricData(metricDatum);

    auto outcome = pCanaryStreamCallbacks->pCwClient->PutMetricData(cwRequest);
    if (!outcome.IsSuccess())
    {
        DLOGE("Failed to put sample metric data: %s" , outcome.GetError().GetMessage().c_str());
    }
    else
    {
        DLOGI("Successfully put sample metric data");
    }
//    pCanaryStreamCallbacks->pCwClient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
}

STATUS publishErrorRate(UINT32 timerId, UINT64 currentTime, UINT64 customData)
{
    STATUS retStatus = STATUS_SUCCESS;
    StreamMetrics canaryStreamMetrics;
    canaryStreamMetrics.version = STREAM_METRICS_CURRENT_VERSION;
    CanaryCustomData* c = (CanaryCustomData*) customData;
    static UINT64 currentTimeLocal = 0;
    UINT64 duration = 0;

    Aws::CloudWatch::Model::MetricDatum putFrameErrorRateDatum, errorAckDatum, totalErrorDatum;
    DOUBLE putFrameErrorRate, errorAckRate;
    UINT64 numberOfPutFrameErrors, numberOfErrorAcks;

    CHK(c->pCanaryStreamCallbacks != NULL, STATUS_NULL_ARG);
    CHK_STATUS(getKinesisVideoStreamMetrics(c->streamHandle, &canaryStreamMetrics));

    duration = currentTime - currentTimeLocal;

    numberOfPutFrameErrors = canaryStreamMetrics.putFrameErrors - c->pCanaryStreamCallbacks->historicStreamMetric.prevPutFrameErrorCount;
    numberOfErrorAcks = canaryStreamMetrics.errorAcks - c->pCanaryStreamCallbacks->historicStreamMetric.prevErrorAckCount;
    putFrameErrorRate = (DOUBLE) (numberOfPutFrameErrors) / (DOUBLE) (duration / HUNDREDS_OF_NANOS_IN_A_SECOND);

    c->pCanaryStreamCallbacks->historicStreamMetric.prevPutFrameErrorCount = canaryStreamMetrics.putFrameErrors;
    putFrameErrorRateDatum.SetMetricName("PutFrameErrorRate");
    putFrameErrorRateDatum.AddDimensions(c->pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(c->pCanaryStreamCallbacks, putFrameErrorRateDatum, Aws::CloudWatch::Model::StandardUnit::Count_Second, putFrameErrorRate);

    errorAckRate = (DOUBLE)(numberOfErrorAcks) / (DOUBLE) (duration / HUNDREDS_OF_NANOS_IN_A_SECOND);
    c->pCanaryStreamCallbacks->historicStreamMetric.prevErrorAckCount = canaryStreamMetrics.errorAcks;
    errorAckDatum.SetMetricName("ErrorAckRate");
    errorAckDatum.AddDimensions(c->pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(c->pCanaryStreamCallbacks, errorAckDatum, Aws::CloudWatch::Model::StandardUnit::Count_Second, putFrameErrorRate);


    c->pCanaryStreamCallbacks->totalNumberOfErrors += numberOfPutFrameErrors + numberOfErrorAcks;

    totalErrorDatum.SetMetricName("NumberOfErrors");
    totalErrorDatum.AddDimensions(c->pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(c->pCanaryStreamCallbacks, totalErrorDatum, Aws::CloudWatch::Model::StandardUnit::Count, c->pCanaryStreamCallbacks->totalNumberOfErrors);


    if (c->pCanaryStreamCallbacks->aggregateMetrics) {
        Aws::CloudWatch::Model::MetricDatum aggputFrameErrorRateDatum, aggErrorAckDatum, aggTotalErrorDatum;
        aggputFrameErrorRateDatum.SetMetricName("PutFrameErrorRate");
        aggputFrameErrorRateDatum.AddDimensions(c->pCanaryStreamCallbacks->aggregatedDimension);
        pushMetric(c->pCanaryStreamCallbacks, aggputFrameErrorRateDatum, Aws::CloudWatch::Model::StandardUnit::Count_Second, putFrameErrorRate);

        aggErrorAckDatum.SetMetricName("ErrorAckRate");
        aggErrorAckDatum.AddDimensions(c->pCanaryStreamCallbacks->aggregatedDimension);
        pushMetric(c->pCanaryStreamCallbacks, aggErrorAckDatum, Aws::CloudWatch::Model::StandardUnit::Count_Second, putFrameErrorRate);

        aggTotalErrorDatum.SetMetricName("NumberOfErrors");
        aggTotalErrorDatum.AddDimensions(c->pCanaryStreamCallbacks->aggregatedDimension);
        pushMetric(c->pCanaryStreamCallbacks, aggTotalErrorDatum, Aws::CloudWatch::Model::StandardUnit::Count, c->pCanaryStreamCallbacks->totalNumberOfErrors);

    }

    DLOGD("Total number of errors in %lf seconds : %d", (DOUBLE)(duration / HUNDREDS_OF_NANOS_IN_A_SECOND),
          c->pCanaryStreamCallbacks->totalNumberOfErrors);
    c->pCanaryStreamCallbacks->totalNumberOfErrors = 0;

    DLOGD("Error ack rate: %lf", errorAckRate);
    DLOGD("PutFrame error rate: %lf", putFrameErrorRate);
    currentTimeLocal = GETTIME();
CleanUp:
    return retStatus;
}

STATUS pushStartUpLatency(PCanaryStreamCallbacks pCanaryStreamCallbacks, DOUBLE startUpLatency)
{
    Aws::CloudWatch::Model::MetricDatum startupLatencyDatum;
    STATUS retStatus = STATUS_SUCCESS;
    CHK(pCanaryStreamCallbacks != NULL, STATUS_NULL_ARG);
    startupLatencyDatum.SetMetricName("StartupLatency");
    startupLatencyDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, startupLatencyDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds, startUpLatency);

    if (pCanaryStreamCallbacks->aggregateMetrics) {
        Aws::CloudWatch::Model::MetricDatum aggstartupLatencyDatum;
        aggstartupLatencyDatum.SetMetricName("StartupLatency");
        aggstartupLatencyDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
        pushMetric(pCanaryStreamCallbacks, aggstartupLatencyDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds, startUpLatency);
    }
CleanUp:
    return STATUS_SUCCESS;
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

    currentViewDatum.SetMetricName("CurrentViewDuration");
    currentViewDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, currentViewDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds,
               canaryStreamMetrics.currentViewDuration / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);

    if (pCanaryStreamCallbacks->aggregateMetrics) {
        Aws::CloudWatch::Model::MetricDatum aggStreamDatum, aggCurrentViewDatum;
        aggStreamDatum.SetMetricName("FrameRate");
        aggStreamDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
        pushMetric(pCanaryStreamCallbacks, aggStreamDatum, Aws::CloudWatch::Model::StandardUnit::Count_Second, canaryStreamMetrics.currentFrameRate);

        aggCurrentViewDatum.SetMetricName("CurrentViewDuration");
        aggCurrentViewDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
        pushMetric(pCanaryStreamCallbacks, aggCurrentViewDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds,
                   canaryStreamMetrics.currentViewDuration / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);

    }
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
    pushMetric(pCanaryStreamCallbacks, clientDatum, Aws::CloudWatch::Model::StandardUnit::Kilobytes,
               canaryClientMetrics.contentStoreAvailableSize / 1024);

    if (pCanaryStreamCallbacks->aggregateMetrics) {
        Aws::CloudWatch::Model::MetricDatum aggClientDatum;
        aggClientDatum.SetMetricName("StorageSizeAvailable");
        aggClientDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
        pushMetric(pCanaryStreamCallbacks, aggClientDatum, Aws::CloudWatch::Model::StandardUnit::Kilobytes,
                   canaryClientMetrics.contentStoreAvailableSize / 1024);
    }
CleanUp:
    return retStatus;
}

VOID currentMemoryAllocation(PCanaryStreamCallbacks pCanaryStreamCallbacks)
{
    Aws::CloudWatch::Model::MetricDatum memoryDatum;
    memoryDatum.SetMetricName("MemoryAllocation");
    memoryDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, memoryDatum, Aws::CloudWatch::Model::StandardUnit::Kilobytes, getInstrumentedTotalAllocationSize() / 1024);

    if (pCanaryStreamCallbacks->aggregateMetrics) {
        Aws::CloudWatch::Model::MetricDatum aggMemoryDatum;
        aggMemoryDatum.SetMetricName("MemoryAllocation");
        aggMemoryDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
        pushMetric(pCanaryStreamCallbacks, aggMemoryDatum, Aws::CloudWatch::Model::StandardUnit::Kilobytes, getInstrumentedTotalAllocationSize() / 1024);
    }
}

STATUS computeAckMetricsFromCanary(PCanaryStreamCallbacks pCanaryStreamCallbacks) {
    Aws::CloudWatch::Model::MetricDatum receiveAckDatum, persistedAckDatum, paggAckDatum, raggAckDatum;
    DOUBLE rAckAvg, pAckAvg;
    rAckAvg = (std::accumulate(pCanaryStreamCallbacks->receivedAckLatencyVec.begin(), pCanaryStreamCallbacks->receivedAckLatencyVec.end(), 0.0)) / pCanaryStreamCallbacks->receivedAckLatencyVec.size();
    pCanaryStreamCallbacks->receivedAckLatencyVec.clear();
    pAckAvg = (std::accumulate(pCanaryStreamCallbacks->persistedAckLatencyVec.begin(), pCanaryStreamCallbacks->persistedAckLatencyVec.end(), 0.0)) / pCanaryStreamCallbacks->persistedAckLatencyVec.size();
    pCanaryStreamCallbacks->persistedAckLatencyVec.clear();

    receiveAckDatum.SetMetricName("ReceivedAckLatency");
    receiveAckDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, receiveAckDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds,
               rAckAvg);
    if (pCanaryStreamCallbacks->aggregateMetrics) {
        raggAckDatum.SetMetricName("ReceivedAckLatency");
        raggAckDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
        pushMetric(pCanaryStreamCallbacks, raggAckDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds,
                   rAckAvg);
    }

    persistedAckDatum.SetMetricName("PersistedAckLatency");
    persistedAckDatum.AddDimensions(pCanaryStreamCallbacks->dimensionPerStream);
    pushMetric(pCanaryStreamCallbacks, persistedAckDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds,
               pAckAvg);
    if (pCanaryStreamCallbacks->aggregateMetrics) {
        paggAckDatum.SetMetricName("PersistedAckLatency");
        paggAckDatum.AddDimensions(pCanaryStreamCallbacks->aggregatedDimension);
        pushMetric(pCanaryStreamCallbacks, paggAckDatum, Aws::CloudWatch::Model::StandardUnit::Milliseconds,
                   pAckAvg);
    }
    return STATUS_SUCCESS;
}

STATUS publishMetrics(UINT32 timerId, UINT64 currentTime, UINT64 customData)
{
    STATUS retStatus = STATUS_SUCCESS;
    CanaryCustomData* c = (CanaryCustomData*) customData;
    CHK_STATUS(computeStreamMetricsFromCanary(c->streamHandle, c->pCanaryStreamCallbacks));
    CHK_STATUS(computeClientMetricsFromCanary(c->clientHandle, c->pCanaryStreamCallbacks));
    CHK_STATUS(computeAckMetricsFromCanary(c->pCanaryStreamCallbacks));
    currentMemoryAllocation(c->pCanaryStreamCallbacks);
CleanUp:
    return retStatus;
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