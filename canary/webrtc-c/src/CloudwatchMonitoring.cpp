#include "Include.h"

namespace Canary {

CloudwatchMonitoring::CloudwatchMonitoring(PConfig pConfig, ClientConfiguration* pClientConfig) : pConfig(pConfig), client(*pClientConfig)
{
}

STATUS CloudwatchMonitoring::init()
{
    STATUS retStatus = STATUS_SUCCESS;

    this->channelDimension.SetName("WebRTCSDKCanaryChannelName");
    this->channelDimension.SetValue(pConfig->channelName.value);

    this->labelDimension.SetName("WebRTCSDKCanaryLabel");
    this->labelDimension.SetValue(pConfig->label.value);

    return retStatus;
}

VOID CloudwatchMonitoring::deinit()
{
    // need to wait all metrics to be flushed out, otherwise we'll get a segfault.
    // https://docs.aws.amazon.com/sdk-for-cpp/v1/developer-guide/basic-use.html
    // TODO: maybe add a timeout? But, this might cause a segfault if it hits a timeout.
    while (this->pendingMetrics.load() > 0) {
        THREAD_SLEEP(HUNDREDS_OF_NANOS_IN_A_MILLISECOND * 500);
    }
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
        default:
            return "Unknown unit";
    }
}

VOID CloudwatchMonitoring::push(const MetricDatum& datum)
{
    Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
    MetricDatum single = datum;
    MetricDatum aggregated = datum;

    single.AddDimensions(this->channelDimension);
    single.AddDimensions(this->labelDimension);
    aggregated.AddDimensions(this->labelDimension);

    cwRequest.SetNamespace(DEFAULT_CLOUDWATCH_NAMESPACE);
    cwRequest.AddMetricData(single);
    cwRequest.AddMetricData(aggregated);

    auto asyncHandler = [this](const Aws::CloudWatch::CloudWatchClient* cwClient, const Aws::CloudWatch::Model::PutMetricDataRequest& request,
                               const Aws::CloudWatch::Model::PutMetricDataOutcome& outcome,
                               const std::shared_ptr<const Aws::Client::AsyncCallerContext>& context) {
        UNUSED_PARAM(cwClient);
        UNUSED_PARAM(request);
        UNUSED_PARAM(context);

        if (!outcome.IsSuccess()) {
            DLOGE("Failed to put sample metric data: %s", outcome.GetError().GetMessage().c_str());
        } else {
            DLOGS("Successfully put sample metric data");
        }
        this->pendingMetrics--;
    };
    this->pendingMetrics++;
    this->client.PutMetricDataAsync(cwRequest, asyncHandler);

    std::stringstream ss;

    ss << "Emitted the following metric:\n\n";
    ss << "  Name       : " << datum.GetMetricName() << '\n';
    ss << "  Unit       : " << unitToString(datum.GetUnit()) << '\n';

    ss << "  Values     : ";
    auto& values = datum.GetValues();
    // If the datum uses single value, GetValues will be empty and the data will be accessible
    // from GetValue
    if (values.empty()) {
        ss << datum.GetValue();
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
    auto& dimensions = datum.GetDimensions();
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

VOID CloudwatchMonitoring::pushExitStatus(STATUS retStatus)
{
    MetricDatum datum;
    Dimension statusDimension;
    CHAR status[MAX_STATUS_CODE_LENGTH];

    statusDimension.SetName("Code");
    SPRINTF(status, "0x%08x", retStatus);
    statusDimension.SetValue(status);

    datum.SetMetricName("ExitStatus");
    datum.SetValue(1.0);
    datum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Count);

    datum.AddDimensions(statusDimension);

    this->push(datum);
}

VOID CloudwatchMonitoring::pushSignalingRoundtripStatus(STATUS retStatus)
{
    MetricDatum datum;
    Dimension statusDimension;
    CHAR status[MAX_STATUS_CODE_LENGTH];

    statusDimension.SetName("Code");
    SPRINTF(status, "0x%08x", retStatus);
    statusDimension.SetValue(status);

    datum.SetMetricName("SignalingRoundtripStatus");
    datum.SetValue(1.0);
    datum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Count);

    datum.AddDimensions(statusDimension);

    this->push(datum);
}

VOID CloudwatchMonitoring::pushSignalingRoundtripLatency(UINT64 delay, Aws::CloudWatch::Model::StandardUnit unit)
{
    MetricDatum datum;

    datum.SetMetricName("SignalingRoundtripLatency");
    datum.SetValue(delay);
    datum.SetUnit(unit);

    this->push(datum);
}

VOID CloudwatchMonitoring::pushTimeToFirstFrame(UINT64 timeToFirstFrame, Aws::CloudWatch::Model::StandardUnit unit)
{
    MetricDatum datum;

    datum.SetMetricName("TimeToFirstFrame");
    datum.SetValue(timeToFirstFrame);
    datum.SetUnit(unit);

    this->push(datum);
}
VOID CloudwatchMonitoring::pushSignalingInitDelay(UINT64 delay, Aws::CloudWatch::Model::StandardUnit unit)
{
    MetricDatum datum;

    datum.SetMetricName("SignalingInitDelay");
    datum.SetValue(delay);
    datum.SetUnit(unit);

    this->push(datum);
}

VOID CloudwatchMonitoring::pushICEHolePunchingDelay(UINT64 delay, Aws::CloudWatch::Model::StandardUnit unit)
{
    MetricDatum datum;

    datum.SetMetricName("ICEHolePunchingDelay");
    datum.SetValue(delay);
    datum.SetUnit(unit);

    this->push(datum);
}

VOID CloudwatchMonitoring::pushOutboundRtpStats(Canary::POutgoingRTPMetricsContext pOutboundRtpStats)
{
    MetricDatum bytesDiscardedPercentageDatum, averageFramesRateDatum, nackRateDatum, retransmissionPercentDatum;

    bytesDiscardedPercentageDatum.SetMetricName("PercentageFrameDiscarded");
    bytesDiscardedPercentageDatum.SetValue(pOutboundRtpStats->framesPercentageDiscarded);
    bytesDiscardedPercentageDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Percent);
    this->push(bytesDiscardedPercentageDatum);

    averageFramesRateDatum.SetMetricName("FramesPerSecond");
    averageFramesRateDatum.SetValue(pOutboundRtpStats->averageFramesSentPerSecond);
    averageFramesRateDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Count_Second);
    this->push(averageFramesRateDatum);

    nackRateDatum.SetMetricName("NackPerSecond");
    nackRateDatum.SetValue(pOutboundRtpStats->nacksPerSecond);
    nackRateDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Count_Second);
    this->push(nackRateDatum);

    retransmissionPercentDatum.SetMetricName("PercentageFramesRetransmitted");
    retransmissionPercentDatum.SetValue(pOutboundRtpStats->retxBytesPercentage);
    retransmissionPercentDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Percent);
    this->push(retransmissionPercentDatum);
}

VOID CloudwatchMonitoring::pushInboundRtpStats(Canary::PIncomingRTPMetricsContext pIncomingRtpStats)
{
    MetricDatum incomingBitrateDatum, incomingPacketRate, incomingFrameDropRateDatum;

    incomingBitrateDatum.SetMetricName("IncomingBitRate");
    incomingBitrateDatum.SetValue(pIncomingRtpStats->incomingBitRate);
    incomingBitrateDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Kilobits_Second);
    this->push(incomingBitrateDatum);

    incomingPacketRate.SetMetricName("IncomingPacketsPerSecond");
    incomingPacketRate.SetValue(pIncomingRtpStats->packetReceiveRate);
    incomingPacketRate.SetUnit(Aws::CloudWatch::Model::StandardUnit::Count_Second);
    this->push(incomingPacketRate);

    incomingFrameDropRateDatum.SetMetricName("IncomingFramesDroppedPerSecond");
    incomingFrameDropRateDatum.SetValue(pIncomingRtpStats->framesDroppedPerSecond);
    incomingFrameDropRateDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Count_Second);
    this->push(incomingFrameDropRateDatum);
}

VOID CloudwatchMonitoring::pushEndToEndMetrics(Canary::EndToEndMetricsContext ctx)
{
    MetricDatum endToEndLatencyDatum, sizeMatchDatum;
    DOUBLE latency = ctx.frameLatencyAvg / (DOUBLE) HUNDREDS_OF_NANOS_IN_A_MILLISECOND;

    // TODO: due to https://github.com/aws-samples/amazon-kinesis-video-streams-demos/issues/96,
    //       it's not clear why the emitted metric shows -nan. Since -nan is a string that's outputted
    //       from Datum's value stringify implementation, we should try to get the original value by
    //       printing it ourself.
    //
    //       If the issues doesn't exist anymore, please remove this as this is intended for debugging only.
    //       The generic metric logging should be sufficient.
    DLOGD("Current end-to-end frame latency: %4.2lf", latency);
    endToEndLatencyDatum.SetMetricName("EndToEndFrameLatency");
    endToEndLatencyDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Milliseconds);
    endToEndLatencyDatum.SetValue(latency);
    this->push(endToEndLatencyDatum);

    sizeMatchDatum.SetMetricName("FrameSizeMatch");
    sizeMatchDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Count);
    sizeMatchDatum.SetValue(ctx.sizeMatchAvg);
    this->push(sizeMatchDatum);
}

} // namespace Canary
