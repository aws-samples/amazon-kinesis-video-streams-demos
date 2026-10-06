#include "Include.h"

VOID pushMetric(std::string metricName, DOUBLE metricValue, Aws::CloudWatch::Model::StandardUnit unit, Aws::CloudWatch::Model::MetricDatum datum,
                Aws::CloudWatch::Model::Dimension *dimension, Aws::CloudWatch::Model::PutMetricDataRequest &cwRequest) {
    datum.SetMetricName(metricName);
    datum.AddDimensions(*dimension);
    datum.SetValue(metricValue);
    datum.SetUnit(unit);

    // Pushes back the data array, can include no more than 20 metrics per call
    cwRequest.AddMetricData(datum);
}

VOID onPutMetricDataResponseReceivedHandler(const Aws::CloudWatch::CloudWatchClient* cwClient,
                                            const Aws::CloudWatch::Model::PutMetricDataRequest& request,
                                            const Aws::CloudWatch::Model::PutMetricDataOutcome& outcome,
                                            const std::shared_ptr<const Aws::Client::AsyncCallerContext>& context) {
    if (!outcome.IsSuccess()) {
        LOG_ERROR("Failed to put sample metric data: " << outcome.GetError().GetMessage().c_str());
    } else {
        LOG_DEBUG("Successfully put sample metric data");
    }
}

VOID determineCredentials(GstElement *kvssink, CanaryConfig* config) {
    if(config->useIotCredentialProvider) {
        LOG_DEBUG("Setting Gstreamer IOT Credentials");
        GstStructure *iot_credentials = gst_structure_new(
                "iot-certificate",
                "iot-thing-name", G_TYPE_STRING, config->thingName,
                "endpoint", G_TYPE_STRING, (PCHAR) config->iotGetCredentialEndpoint,
                "cert-path", G_TYPE_STRING, config->certPath,
                "key-path", G_TYPE_STRING, config->privateKeyPath,
                "ca-path", G_TYPE_STRING, config->caCertPath,
                "role-aliases", G_TYPE_STRING, config->roleAlias, NULL);

        g_object_set(G_OBJECT (kvssink), "iot-certificate", iot_credentials, NULL);
        gst_structure_free(iot_credentials);
    }
    else {
        LOG_DEBUG("Setting AWS Credentials");
        g_object_set(G_OBJECT(kvssink), "access-key", config->accessKey, NULL);
        g_object_set(G_OBJECT(kvssink), "secret-key", config->secretKey, NULL);
    }
}

// Records when each fragment was fully handed to the SDK, so that fragmentAckReceivedHandler() can measure
// ack latency from that moment.
//
// A fragment starts at a key frame and ends when the next key frame arrives. The map is keyed by the fragment
// start timecode in milliseconds (the key frame PTS, which is what FragmentAck.timestamp carries back) and the
// value is the wall-clock time in milliseconds since epoch at which the key frame that CLOSED the fragment was
// put to the SDK.
//
// The value is deliberately a wall-clock reading and not the closing key frame's PTS. kvssink converts pipeline
// running time to absolute time once per process using the wall clock at the moment the FIRST frame reached it
// (gstkvssink.cpp: buf->pts += producer_start_time - first_pts), so every PTS in a process carries the first
// frame's encoder start-up delay as a constant offset. Measuring "now - PTS" therefore under-reported the
// latency by that per-process offset, and when the offset exceeded the real latency the unsigned subtraction
// wrapped to ~1.8e19 ms and was pushed to CloudWatch as is.
VOID updateFragmentEndTimes(UINT64 curKeyFramePts, UINT64 curKeyFrameWallClockMs, UINT64 &lastKeyFramePts, std::map<UINT64, UINT64> *mapPtr)
{
    if (lastKeyFramePts != 0)
    {
        (*mapPtr)[lastKeyFramePts / HUNDREDS_OF_NANOS_IN_A_MILLISECOND] = curKeyFrameWallClockMs;
        // clean up map: removing fragments that were closed more than 5 min ago. Keys and values both increase
        // monotonically, so the oldest entries are at the front and the scan can stop at the first live one.
        auto iter = mapPtr->begin();
        while (iter != mapPtr->end()) {
            if (iter->second < curKeyFrameWallClockMs - 300000)
            {
                iter = mapPtr->erase(iter);
            } else {
                break;
            }
        }
    }
    lastKeyFramePts = curKeyFramePts;
}

// Pushes one ack latency sample (PersistedAckLatency / ReceivedAckLatency) measured from the wall-clock time at
// which the fragment was fully handed to the SDK. The subtraction is done in signed arithmetic and a negative
// result is logged and dropped rather than wrapped to a huge unsigned value.
VOID pushAckLatencyMetric(CustomData *cusData, const char *metricName, UINT64 timeOfFragmentEndSentMs)
{
    INT64 currentTimestamp = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    INT64 ackLatency = currentTimestamp - (INT64) timeOfFragmentEndSentMs; // [milliseconds]
    if (ackLatency < 0)
    {
        LOG_WARN(metricName << " is negative (" << ackLatency << " ms), fragment end sent at " << timeOfFragmentEndSentMs
                            << ", now " << currentTimestamp << "; sample dropped");
        return;
    }

    Aws::CloudWatch::Model::MetricDatum ackLatencyDatum;
    Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
    cwRequest.SetNamespace("KinesisVideoSDKCanary");

    pushMetric(metricName, (DOUBLE) ackLatency, Aws::CloudWatch::Model::StandardUnit::Milliseconds, ackLatencyDatum, cusData->pDimensionPerStream, cwRequest);
    LOG_DEBUG(metricName << ": " << ackLatency);
    if (cusData->pCanaryConfig->useAggMetrics)
    {
        pushMetric(metricName, (DOUBLE) ackLatency, Aws::CloudWatch::Model::StandardUnit::Milliseconds, ackLatencyDatum, cusData->pAggregatedDimension, cwRequest);
    }
    cusData->pCwClient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
}


VOID pushStartupLatencyMetric(CustomData *cusData)
{
    DOUBLE currentTimestamp = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    DOUBLE startUpLatency = (DOUBLE)(currentTimestamp - cusData->startTime / 1000000); // [milliseconds]
    Aws::CloudWatch::Model::MetricDatum startupLatencyDatum;
    Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
    cwRequest.SetNamespace("KinesisVideoSDKCanary");

    LOG_DEBUG("Startup Latency: " << startUpLatency);

    pushMetric("StartupLatency", startUpLatency, Aws::CloudWatch::Model::StandardUnit::Milliseconds, startupLatencyDatum, cusData->pDimensionPerStream, cwRequest);
    if (cusData->pCanaryConfig->useAggMetrics)
    {
        pushMetric("StartupLatency", startUpLatency, Aws::CloudWatch::Model::StandardUnit::Milliseconds, startupLatencyDatum, cusData->pAggregatedDimension, cwRequest);
    }

    // Send metrics to CW
    cusData->pCwClient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
}

VOID pushErrorMetrics(CustomData *cusData, double duration, KinesisVideoStreamMetrics streamMetrics)
{
    Aws::CloudWatch::Model::MetricDatum metricDatum;
    Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
    cwRequest.SetNamespace("KinesisVideoSDKCanary");

    auto rawStreamMetrics = streamMetrics.getRawMetrics();

    UINT64 newPutFrameErrors = rawStreamMetrics->putFrameErrors - cusData->totalPutFrameErrorCount;
    cusData->totalPutFrameErrorCount = rawStreamMetrics->putFrameErrors;
    double putFrameErrorRate = newPutFrameErrors / (double)duration;
    pushMetric("PutFrameErrorRate", putFrameErrorRate, Aws::CloudWatch::Model::StandardUnit::Count_Second, metricDatum, cusData->pDimensionPerStream, cwRequest);
    LOG_DEBUG("PutFrame Error Rate: " << putFrameErrorRate);

    UINT64 newErrorAcks = rawStreamMetrics->errorAcks - cusData->totalErrorAckCount;
    cusData->totalErrorAckCount = rawStreamMetrics->errorAcks;
    double errorAckRate = newErrorAcks / (double)duration;
    pushMetric("ErrorAckRate", errorAckRate, Aws::CloudWatch::Model::StandardUnit::Count_Second, metricDatum, cusData->pDimensionPerStream, cwRequest);
    LOG_DEBUG("Error Ack Rate: " << errorAckRate);

    UINT64 totalNumberOfErrors = cusData->totalPutFrameErrorCount + cusData->totalErrorAckCount;
    pushMetric("TotalNumberOfErrors", totalNumberOfErrors, Aws::CloudWatch::Model::StandardUnit::Count, metricDatum, cusData->pDimensionPerStream, cwRequest);
    LOG_DEBUG("Total Number of Errors: " << totalNumberOfErrors);

    if (cusData->pCanaryConfig->useAggMetrics)
    {
        pushMetric("PutFrameErrorRate", putFrameErrorRate, Aws::CloudWatch::Model::StandardUnit::Count_Second, metricDatum, cusData->pAggregatedDimension, cwRequest);
        pushMetric("ErrorAckRate", errorAckRate, Aws::CloudWatch::Model::StandardUnit::Count_Second, metricDatum, cusData->pAggregatedDimension, cwRequest);
        pushMetric("TotalNumberOfErrors", totalNumberOfErrors, Aws::CloudWatch::Model::StandardUnit::Count, metricDatum, cusData->pAggregatedDimension, cwRequest);
    }

    // Send metrics to CW
    cusData->pCwClient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
}

VOID pushClientMetrics(CustomData *cusData, KinesisVideoProducerMetrics clientMetrics)
{
    Aws::CloudWatch::Model::MetricDatum metricDatum;
    Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
    cwRequest.SetNamespace("KinesisVideoSDKCanary");

    double availableStoreSize = clientMetrics.getContentStoreSizeSize() / 1000; // [kilobytes]
    pushMetric("ContentStoreAvailableSize", availableStoreSize, Aws::CloudWatch::Model::StandardUnit::Kilobytes,
               metricDatum, cusData->pDimensionPerStream, cwRequest);
    LOG_DEBUG("Content Store Available Size: " << availableStoreSize);

    if (cusData->pCanaryConfig->useAggMetrics)
    {
        pushMetric("ContentStoreAvailableSize", availableStoreSize, Aws::CloudWatch::Model::StandardUnit::Kilobytes,
                   metricDatum, cusData->pAggregatedDimension, cwRequest);
    }

    // Send metrics to CW
    cusData->pCwClient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
}

// push metric function to publish metrics to cloudwatch after getting g signal from producer sdk cpp
VOID pushStreamMetrics(CustomData *cusData, KinesisVideoStreamMetrics streamMetrics)
{
    Aws::CloudWatch::Model::MetricDatum metricDatum;
    Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
    cwRequest.SetNamespace("KinesisVideoSDKCanary");

    double frameRate = streamMetrics.getCurrentElementaryFrameRate();
    pushMetric("FrameRate", frameRate, Aws::CloudWatch::Model::StandardUnit::Count_Second, metricDatum, cusData->pDimensionPerStream, cwRequest);
    LOG_DEBUG("Frame Rate: " << frameRate);

    double transferRate = 8 * streamMetrics.getCurrentTransferRate() / 1024; // *8 makes it bytes->bits. /1024 bits->kilobits
    pushMetric("TransferRate", transferRate, Aws::CloudWatch::Model::StandardUnit::Kilobits_Second,
               metricDatum, cusData->pDimensionPerStream, cwRequest);
    LOG_DEBUG("Transfer Rate: " << transferRate);

    double currentViewDuration = streamMetrics.getCurrentViewDuration().count();
    pushMetric("CurrentViewDuration", currentViewDuration, Aws::CloudWatch::Model::StandardUnit::Milliseconds,
               metricDatum, cusData->pDimensionPerStream, cwRequest);
    LOG_DEBUG("Current View Duration: " << currentViewDuration);

    if (cusData->pCanaryConfig->useAggMetrics)
    {
        pushMetric("FrameRate", frameRate, Aws::CloudWatch::Model::StandardUnit::Count_Second,
                   metricDatum, cusData->pAggregatedDimension, cwRequest);
        pushMetric("TransferRate", transferRate, Aws::CloudWatch::Model::StandardUnit::Kilobits_Second,
                   metricDatum, cusData->pAggregatedDimension, cwRequest);
        pushMetric("CurrentViewDuration", currentViewDuration, Aws::CloudWatch::Model::StandardUnit::Milliseconds,
                   metricDatum, cusData->pAggregatedDimension, cwRequest);
    }

    // Send metrics to CW
    cusData->pCwClient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
}

// put frame function to publish metrics to cloudwatch after getting g signal from producer sdk cpp
VOID metricHandler(GstElement *kvssink, KvsSinkMetric *kvssinkMetric, CustomData *cusData)
{
    LOG_DEBUG("put frame at canary");
    // kvssink emits this signal synchronously from put_frame() right after the key frame was accepted by the SDK,
    // so the wall clock read here is the time the fragment closed by this key frame was fully handed to the SDK.
    UINT64 keyFrameWallClockMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    updateFragmentEndTimes(kvssinkMetric->frame_pts, keyFrameWallClockMs, cusData->lastKeyFrameTime, cusData->timeOfNextKeyFrame);
    pushStreamMetrics(cusData, kvssinkMetric->stream_metrics);
    pushClientMetrics(cusData, kvssinkMetric->client_metrics);

    double duration = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count() - cusData->timeCounter;
    // Push error metrics every 60 seconds
    if(duration > 60)
    {
        pushErrorMetrics(cusData, duration, kvssinkMetric->stream_metrics);
        cusData->timeCounter = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    }
}

static VOID putFrameHandler(GstElement *kvssink, VOID *gMetrics, gpointer data){

    CustomData *cusData = (CustomData*) data;
    KvsSinkMetric *kvssinkMetric = reinterpret_cast<KvsSinkMetric *> (gMetrics);
    metricHandler(kvssink, kvssinkMetric, cusData);
    if(kvssinkMetric->on_first_frame){
        pushStartupLatencyMetric(cusData);
        cusData->onFirstFrame = false;
    }

    // Check if we have reached Canary's stop time
    int currTime;
    currTime = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    if (currTime > (cusData->producerStartTime / 1000000000 + cusData->pCanaryConfig->canaryDuration))
    {
        LOG_DEBUG("Canary has reached end of run time");
        gst_element_send_event(kvssink, gst_event_new_eos());
        g_main_loop_quit(cusData->mainLoop);
    }
}

STATUS fragmentAckReceivedHandler(GstElement *kvssink, PFragmentAck pFragmentAck, gpointer data){

    LOG_DEBUG("Fragment ack received handler canary cpp invoked " << pFragmentAck->timestamp);
    CustomData *cusData = reinterpret_cast<CustomData *>(data);

    // FragmentAck.timestamp is the fragment start timecode in milliseconds, which is the key used by
    // updateFragmentEndTimes(). The value is the wall-clock time the fragment was fully handed to the SDK.
    std::map<UINT64, UINT64>::iterator iter = cusData->timeOfNextKeyFrame->find(pFragmentAck->timestamp);
    if (iter == cusData->timeOfNextKeyFrame->end())
    {
        // Either the fragment is still open (no closing key frame yet, e.g. the last fragment of a run) or it
        // was closed more than 5 min ago and has been cleaned up. No latency sample can be produced for it.
        LOG_DEBUG("Fragment start timestamp " << pFragmentAck->timestamp << " not found in map, ack type " << pFragmentAck->ackType);
        return STATUS_SUCCESS;
    }
    UINT64 timeOfFragmentEndSent = iter->second;
    LOG_DEBUG("Time of the fragment end sent "<<timeOfFragmentEndSent);

    switch (pFragmentAck->ackType)
    {
        case FRAGMENT_ACK_TYPE_PERSISTED:
        {
            pushAckLatencyMetric(cusData, "PersistedAckLatency", timeOfFragmentEndSent);
            break;
        }
        case FRAGMENT_ACK_TYPE_RECEIVED:
        {
            pushAckLatencyMetric(cusData, "ReceivedAckLatency", timeOfFragmentEndSent);
            break;
        }
        case FRAGMENT_ACK_TYPE_BUFFERING:
        {
            LOG_DEBUG("FRAGMENT_ACK_TYPE_BUFFERING callback invoked");
            break;
        }
        case FRAGMENT_ACK_TYPE_ERROR:
        {
            LOG_DEBUG("FRAGMENT_ACK_TYPE_ERROR callback invoked");
            break;
        }
        case FRAGMENT_ACK_TYPE_UNDEFINED:
        {
            LOG_DEBUG("FRAGMENT_ACK_TYPE_UNDEFINED callback invoked");
            break;
        }
        case  FRAGMENT_ACK_TYPE_IDLE:
        {
            LOG_DEBUG("FRAGMENT_ACK_TYPE_IDLE callback invoked");
            break;
        }
    }
    return STATUS_SUCCESS;
}

// This function is called when an error message is posted on the bus
static VOID error_cb(GstBus *bus, GstMessage *msg, CustomData *cusData) {
    GError *err;
    gchar *debug_info;

    // Print error details on the screen
    gst_message_parse_error(msg, &err, &debug_info);
    g_printerr("Error received from element %s: %s\n", GST_OBJECT_NAME (msg->src), err->message);
    g_printerr("Debugging information: %s\n", debug_info ? debug_info : "none");
    g_clear_error(&err);
    g_free(debug_info);

    g_main_loop_quit(cusData->mainLoop);
}

int gstreamer_test_source_init(CustomData *cusData, GstElement *pipeline) {
    GstElement *kvssink, *source, *video_src_filter, *h264parse, *video_filter, *h264enc, *autoVidCon;

    GstCaps *caps;

    // define the elements
    source = gst_element_factory_make("videotestsrc", "source");
    autoVidCon = gst_element_factory_make("autovideoconvert", "vidconv");
    h264enc = gst_element_factory_make("x264enc", "h264enc");
    h264parse = gst_element_factory_make("h264parse", "h264parse");
    kvssink = gst_element_factory_make("kvssink", "kvssink");
    h264parse = gst_element_factory_make("h264parse", "h264parse");

    // videotestsrc must be set to "live" in order for pts and dts to be incremented
    g_object_set(source, "is-live", TRUE, NULL);

    // configure kvssink
    g_object_set(G_OBJECT (kvssink), "stream-name", cusData->streamName, NULL);
    g_object_set(G_OBJECT (kvssink), "storage-size", cusData->pCanaryConfig->storageSizeInMB, NULL);
    g_object_set(G_OBJECT (kvssink), "get-kvs-metrics", TRUE, NULL);
    g_object_set(G_OBJECT (kvssink), "user-agent", CANARY_USER_AGENT_NAME, NULL);
    g_object_set(G_OBJECT (kvssink), "log-config", NULL, NULL);

    if(cusData->pCanaryConfig->streamType == "Realtime") {
        g_object_set(G_OBJECT (kvssink), "streaming-type", STREAMING_TYPE_REALTIME, NULL);
    } else if (cusData->pCanaryConfig->streamType == "Offline") {
        g_object_set(G_OBJECT (kvssink), "streaming-type", STREAMING_TYPE_OFFLINE, NULL);
    }
    g_signal_connect(G_OBJECT(kvssink), "stream-client-metric", (GCallback) putFrameHandler, cusData);
    g_signal_connect(G_OBJECT(kvssink), "fragment-ack", (GCallback) fragmentAckReceivedHandler, cusData);
    determineCredentials(kvssink, cusData->pCanaryConfig);

    // define and configure video filter, we only want the specified format to pass to the sink
    // ("caps" is short for "capabilities")
    std::string video_caps_string = "video/x-h264, stream-format=(string) avc, alignment=(string) au";
    video_filter = gst_element_factory_make("capsfilter", "video_filter");
    caps = gst_caps_from_string(video_caps_string.c_str());
    g_object_set(G_OBJECT (video_filter), "caps", caps, NULL);
    gst_caps_unref(caps);

    video_caps_string = "video/x-raw, framerate=" + std::to_string(cusData->pCanaryConfig->testVideoFps) + "/1" + ", width=1440, height=1080";
    video_src_filter = gst_element_factory_make("capsfilter", "video_source_filter");
    caps = gst_caps_from_string(video_caps_string.c_str());
    g_object_set(G_OBJECT (video_src_filter), "caps", caps, NULL);
    gst_caps_unref(caps);

    // check if all elements were created
    if (!pipeline || !source || !video_src_filter || !kvssink || !autoVidCon || !h264parse ||
        !video_filter || !h264enc)
    {
        LOG_ERROR("Not all elements could be created");
        return 1;
    }

    // build the pipeline
    gst_bin_add_many(GST_BIN (pipeline), source, video_src_filter, autoVidCon, h264enc,
                     h264parse, video_filter, kvssink, NULL);

    // check if all elements were linked
    if (!gst_element_link_many(source, video_src_filter, autoVidCon, h264enc,
                               h264parse, video_filter, kvssink, NULL))
    {
        LOG_ERROR("Elements could not be linked");
        gst_object_unref(pipeline);
        return 1;
    }

    return 0;
}

int gstreamer_init(int argc, char* argv[], CustomData *cusData) {

    // init GStreamer
    gst_init(&argc, &argv);

    GstElement *pipeline;
    int ret;
    GstStateChangeReturn gst_ret;

    // Reset first frame pts
    cusData->firstPts = GST_CLOCK_TIME_NONE;

    switch (cusData->streamSource) {
        case TEST_SOURCE:
            LOG_INFO("Streaming from test source");
            pipeline = gst_pipeline_new("test-kinesis-pipeline");
            ret = gstreamer_test_source_init(cusData, pipeline);
            break;
        case FILE_SOURCE:
            LOG_WARN("File source unsupported");
            break;
        case LIVE_SOURCE:
            LOG_WARN("Unsupported live source");
            break;
        case RTSP_SOURCE:
            LOG_WARN("Unsupported RTSP source");
            break;
    }
    if (ret != 0){
        return ret;
    }

    // Instruct the bus to emit signals for each received message, and connect to the interesting signals
    GstBus *bus = gst_element_get_bus(pipeline);
    gst_bus_add_signal_watch(bus);
    g_signal_connect (G_OBJECT(bus), "message::error", (GCallback) error_cb, cusData);
    gst_object_unref(bus);

    // start streaming
    gst_ret = gst_element_set_state(pipeline, GST_STATE_PLAYING);
    if (gst_ret == GST_STATE_CHANGE_FAILURE) {
        g_printerr("Unable to set the pipeline to the playing state.\n");
        gst_object_unref(pipeline);
        return 1;
    }

    cusData->mainLoop = g_main_loop_new(NULL, FALSE);
    g_main_loop_run(cusData->mainLoop);

    // free resources
    LOG_INFO("Cleaning up for stream "<<cusData->streamName);
    gst_bus_remove_signal_watch(bus);
    gst_element_set_state(pipeline, GST_STATE_NULL);
    gst_object_unref(pipeline);
    g_main_loop_unref(cusData->mainLoop);
    cusData->mainLoop = NULL;
    return 0;
}

int main(int argc, char* argv[]) {
    log4cplus::initialize();
    log4cplus::PropertyConfigurator::doConfigure("../kvs_log_configuration");
    initializeEndianness();
    srand(time(0));
    Aws::SDKOptions options;

    STATUS retStatus = STATUS_SUCCESS;

    Aws::InitAPI(options);
    {
        CanaryConfig canaryConfig;

        // Option to not use env for when JSON config available
        bool useEnvVars = true;
        if (useEnvVars) {
            retStatus = canaryConfig.initConfigWithEnvVars();
        }
        if (STATUS_FAILED(retStatus)) {
            LOG_ERROR("Failed to set up canary configs..exiting");
            goto CleanUp;
        }

        canaryConfig.print();

        CustomData cusData;
        cusData.pCanaryConfig = &canaryConfig;
        cusData.streamName = const_cast<char *>(cusData.pCanaryConfig->streamName.c_str());

        // CloudWatch initialization steps
        Aws::CloudWatch::CloudWatchClient cwClient(cusData.clientConfig);
        cusData.pCwClient = &cwClient;

        // Set the video stream source
        if (cusData.pCanaryConfig->sourceType == "TEST_SOURCE") {
            cusData.streamSource = TEST_SOURCE;
        }

        // Non-aggregate CW dimension
        Aws::CloudWatch::Model::Dimension DimensionPerStream;
        DimensionPerStream.SetName("ProducerCppCanaryStreamName");
        DimensionPerStream.SetValue(cusData.streamName);
        cusData.pDimensionPerStream = &DimensionPerStream;

        // Aggregate CW dimension
        Aws::CloudWatch::Model::Dimension aggregated_dimension;
        aggregated_dimension.SetName("ProducerCppCanaryType");
        aggregated_dimension.SetValue(canaryConfig.canaryLabel);
        cusData.pAggregatedDimension = &aggregated_dimension;

        // Set start time after CW initializations
        cusData.startTime = std::chrono::duration_cast<std::chrono::nanoseconds>(systemCurrentTime().time_since_epoch()).count();

        if (cusData.streamSource == TEST_SOURCE) {
            gstreamer_init(argc, argv, &cusData);
        }

        // CleanUp
        delete (cusData.timeOfNextKeyFrame);
        LOG_DEBUG("end of canary");
    }
    CleanUp:
    Aws::ShutdownAPI(options);
}