#include "Include.h"

namespace com { namespace amazonaws { namespace kinesis { namespace video {

                class CanaryClientCallbackProvider : public ClientCallbackProvider {
                public:
                    UINT64 getCallbackCustomData() override {
                        return reinterpret_cast<UINT64> (this);
                    }
                    StorageOverflowPressureFunc getStorageOverflowPressureCallback() override {
                        return storageOverflowPressure;
                    }
                    static STATUS storageOverflowPressure(UINT64 custom_handle, UINT64 remaining_bytes);
                };

                VOID pushMetric(string metricName, double metricValue, Aws::CloudWatch::Model::StandardUnit unit, Aws::CloudWatch::Model::MetricDatum datum,
                                Aws::CloudWatch::Model::Dimension *dimension, Aws::CloudWatch::Model::PutMetricDataRequest &cwRequest)
                {
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
                                                            const std::shared_ptr<const Aws::Client::AsyncCallerContext>& context)
                {
                    if (!outcome.IsSuccess()) {
                        LOG_ERROR("Failed to put sample metric data: " << outcome.GetError().GetMessage().c_str());
                    } else {
                        LOG_DEBUG("Successfully put sample metric data");
                    }
                }

                STATUS
                CanaryClientCallbackProvider::storageOverflowPressure(UINT64 custom_handle, UINT64 remaining_bytes) {
                    UNUSED_PARAM(custom_handle);
                    LOG_WARN("Reporting storage overflow. Bytes remaining " << remaining_bytes);
                    return STATUS_SUCCESS;
                }
            }  // namespace video
        }  // namespace kinesis
    }  // namespace amazonaws
}  // namespace com;

void determine_credentials(GstElement *kvsSink, CustomData *data) {

    char const *iot_credential_endpoint;
    char const *cert_path;
    char const *private_key_path;
    char const *role_alias;
    char const *ca_cert_path;
    char const *credential_path;
    if (nullptr != (iot_credential_endpoint = data->pCanaryConfig->iot_get_credential_endpoint) &&
        nullptr != (cert_path = data->pCanaryConfig->cert_path) &&
        nullptr != (private_key_path = data->pCanaryConfig->private_key_path) &&
        nullptr != (role_alias = data->pCanaryConfig->role_alias) &&
        nullptr != (ca_cert_path = data->pCanaryConfig->ca_cert_path)) {
        // set the IoT Credentials if provided in envvar
        LOG_DEBUG("Setting IOT Credentials");
        GstStructure *iot_credentials =  gst_structure_new(
                "iot-certificate",
                "iot-thing-name", G_TYPE_STRING, data->streamName,
                "endpoint", G_TYPE_STRING, iot_credential_endpoint,
                "cert-path", G_TYPE_STRING, cert_path,
                "key-path", G_TYPE_STRING, private_key_path,
                "ca-path", G_TYPE_STRING, ca_cert_path,
                "role-aliases", G_TYPE_STRING, role_alias, NULL);

        g_object_set(G_OBJECT (kvsSink), "iot-certificate", iot_credentials, NULL);
        gst_structure_free(iot_credentials);
        // kvssink will search for long term credentials in envvar automatically so no need to include here
        // if no long credentials or IoT credentials provided will look for credential file as last resort
    } else if(nullptr != (credential_path = getenv("AWS_CREDENTIAL_PATH"))){
        g_object_set(G_OBJECT (kvsSink), "credential-path", credential_path, NULL);
    }
}

VOID updateFragmentEndTimes(UINT64 curKeyFrameTime, uint64_t &lastKeyFrameTime, map<uint64_t, uint64_t> *mapPtr)
{
    if (lastKeyFrameTime != 0)
    {
        (*mapPtr)[lastKeyFrameTime / HUNDREDS_OF_NANOS_IN_A_MILLISECOND] = curKeyFrameTime / HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
        auto iter = mapPtr->begin();
        while (iter != mapPtr->end()) {
            // clean up map: removing timestamps older than 5 min from now
            if (iter->first < (duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count() - (300000)))
            {
                iter = mapPtr->erase(iter);
            } else {
                break;
            }
        }
    }
    lastKeyFrameTime = curKeyFrameTime;
}


VOID pushStartupLatencyMetric(CustomData *data)
{
    double currentTimestamp = duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
    double startUpLatency = (double)(currentTimestamp - data->startTime / 1000000); // [milliseconds]
    Aws::CloudWatch::Model::MetricDatum startupLatencyDatum;
    Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
    cwRequest.SetNamespace("KinesisVideoSDKCanary");

    LOG_DEBUG("Startup Latency: " << startUpLatency);

    pushMetric("StartupLatency", startUpLatency, Aws::CloudWatch::Model::StandardUnit::Milliseconds, startupLatencyDatum, data->pDimensionPerStream, cwRequest);
    if (data->pCanaryConfig->useAggMetrics)
    {
        pushMetric("StartupLatency", startUpLatency, Aws::CloudWatch::Model::StandardUnit::Milliseconds, startupLatencyDatum, data->pAggregatedDimension, cwRequest);
    }

    // Send metrics to CW
    data->pCWclient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
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
    cusData->pCWclient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
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
    cusData->pCWclient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
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
    cusData->pCWclient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
}

// put frame function to publish metrics to cloudwatch after getting g signal from producer sdk cpp
static bool put_frame_kvs(GstElement *kvsSink, KvsSinkMetric *kvsSinkMetric, CustomData *cusData)
{
    LOG_DEBUG("put frame at canary");
    updateFragmentEndTimes(kvsSinkMetric->framePTS, cusData->lastKeyFrameTime, cusData->timeOfNextKeyFrame);
    pushStreamMetrics(cusData, kvsSinkMetric->streamMetrics);
    pushClientMetrics(cusData, kvsSinkMetric->clientMetrics);

    double duration = duration_cast<seconds>(system_clock::now().time_since_epoch()).count() - cusData->timeCounter;
    // Push error metrics and logs every 60 seconds
    if(duration > 60)
    {
        pushErrorMetrics(cusData, duration, kvsSinkMetric->streamMetrics);
        cusData->pCanaryLogs->canaryStreamSendLogs(cusData->pCloudwatchLogsObject);
        cusData->timeCounter = duration_cast<seconds>(system_clock::now().time_since_epoch()).count();
    }
    return kvsSinkMetric->putFrameSuccess;
}

static VOID start_put_frame_kvs(GstElement *kvsSink, VOID *gMetrics, gpointer data){

    CustomData *cusData = (CustomData*) data;
    KvsSinkMetric *kvsSinkMetric = reinterpret_cast<KvsSinkMetric *> (gMetrics);
    bool ret = put_frame_kvs(kvsSink, kvsSinkMetric, cusData);
    if(kvsSinkMetric->onFirstFrame && ret){
        pushStartupLatencyMetric(cusData);
        cusData->onFirstFrame = false;
    }
}

//Test function to check signal connect and fragment ack
static STATUS fragmentAckHandler(GstElement *kvssink, PFragmentAck pFragmentAck, gpointer custom_data){

    LOG_DEBUG("Fragment ack received handler canary cpp invoked " << pFragmentAck->timestamp);
    CustomData *data = reinterpret_cast<CustomData *>(custom_data);

    if (pFragmentAck->ackType != FRAGMENT_ACK_TYPE_PERSISTED && pFragmentAck->ackType != FRAGMENT_ACK_TYPE_RECEIVED)
    {
        return STATUS_SUCCESS;
    }

    map<uint64_t, uint64_t>::iterator iter;
    iter = data->timeOfNextKeyFrame->find(pFragmentAck->timestamp);
    auto temp = (iter == data->timeOfNextKeyFrame->end());
    LOG_DEBUG("iter check "<< temp);

    uint64_t timeOfFragmentEndSent = 0;
    timeOfFragmentEndSent = temp ? 0 : data->timeOfNextKeyFrame->find(pFragmentAck->timestamp)->second;

    if (timeOfFragmentEndSent > pFragmentAck->timestamp)
    {
        switch (pFragmentAck->ackType)
        {
            case FRAGMENT_ACK_TYPE_PERSISTED:
            {
                Aws::CloudWatch::Model::MetricDatum persistedAckLatencyDatum;
                Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
                cwRequest.SetNamespace("KinesisVideoSDKCanary");

                auto currentTimestamp = duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
                auto persistedAckLatency = (currentTimestamp - timeOfFragmentEndSent); // [milliseconds]
                pushMetric("PersistedAckLatency", persistedAckLatency, Aws::CloudWatch::Model::StandardUnit::Milliseconds, persistedAckLatencyDatum, data->pDimensionPerStream, cwRequest);
                LOG_DEBUG("Persisted Ack Latency: " << persistedAckLatency);
                if (data->pCanaryConfig->useAggMetrics)
                {
                    pushMetric("PersistedAckLatency", persistedAckLatency, Aws::CloudWatch::Model::StandardUnit::Milliseconds, persistedAckLatencyDatum, data->pAggregatedDimension, cwRequest);

                }
                data->pCWclient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
                break;
            }
            case FRAGMENT_ACK_TYPE_RECEIVED:
            {
                Aws::CloudWatch::Model::MetricDatum receivedAckLatencyDatum;
                Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
                cwRequest.SetNamespace("KinesisVideoSDKCanary");

                auto currentTimestamp = duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
                auto receivedAckLatency = (currentTimestamp - timeOfFragmentEndSent); // [milliseconds]
                pushMetric("ReceivedAckLatency", receivedAckLatency, Aws::CloudWatch::Model::StandardUnit::Milliseconds, receivedAckLatencyDatum, data->pDimensionPerStream, cwRequest);
                LOG_DEBUG("Received Ack Latency: " << receivedAckLatency);
                if (data->pCanaryConfig->useAggMetrics)
                {
                    pushMetric("ReceivedAckLatency", receivedAckLatency, Aws::CloudWatch::Model::StandardUnit::Milliseconds, receivedAckLatencyDatum, data->pAggregatedDimension, cwRequest);
                }
                data->pCWclient->PutMetricDataAsync(cwRequest, onPutMetricDataResponseReceivedHandler);
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
        }
    }
}

// This function is called when an error message is posted on the bus
static VOID error_cb(GstBus *bus, GstMessage *msg, CustomData *data) {
    GError *err;
    gchar *debug_info;

    // Print error details on the screen
    gst_message_parse_error(msg, &err, &debug_info);
    g_printerr("Error received from element %s: %s\n", GST_OBJECT_NAME (msg->src), err->message);
    g_printerr("Debugging information: %s\n", debug_info ? debug_info : "none");
    g_clear_error(&err);
    g_free(debug_info);

    g_main_loop_quit(data->mainLoop);
}

int gstreamer_test_source_init(CustomData *data, GstElement *pipeline) {
    GstElement *kvssink, *source, *video_src_filter, *h264parse, *video_filter, *h264enc, *autovidcon;

    GstCaps *caps;

    // define the elements
    source = gst_element_factory_make("videotestsrc", "source");
    autovidcon = gst_element_factory_make("autovideoconvert", "vidconv");
    h264enc = gst_element_factory_make("x264enc", "h264enc");
    h264parse = gst_element_factory_make("h264parse", "h264parse");
    kvssink = gst_element_factory_make("kvssink", "kvssink");
    h264parse = gst_element_factory_make("h264parse", "h264parse");

    // videotestsrc must be set to "live" in order for pts and dts to be incremented
    g_object_set(source, "is-live", TRUE, NULL);

    // configure kvssink
    g_object_set(G_OBJECT (kvssink), "stream-name", data->streamName, "storage-size", 128, NULL);
    determine_credentials(kvssink, data);
    g_signal_connect(G_OBJECT(kvssink), "stream-client-metric", (GCallback) start_put_frame_kvs, data);
    g_signal_connect(G_OBJECT(kvssink), "fragment-ack", (GCallback) fragmentAckHandler, data);

    // define and configure video filter, we only want the specified format to pass to the sink
    // ("caps" is short for "capabilities")
    string video_caps_string = "video/x-h264, stream-format=(string) avc, alignment=(string) au";
    video_filter = gst_element_factory_make("capsfilter", "video_filter");
    caps = gst_caps_from_string(video_caps_string.c_str());
    g_object_set(G_OBJECT (video_filter), "caps", caps, NULL);
    gst_caps_unref(caps);

    video_caps_string = "video/x-raw, framerate=" + to_string(data->pCanaryConfig->testVideoFps) + "/1" + ", width=1440, height=1080";
    video_src_filter = gst_element_factory_make("capsfilter", "video_source_filter");
    caps = gst_caps_from_string(video_caps_string.c_str());
    g_object_set(G_OBJECT (video_src_filter), "caps", caps, NULL);
    gst_caps_unref(caps);

    // check if all elements were created
    if (!pipeline || !source || !video_src_filter || !kvssink || !autovidcon || !h264parse ||
        !video_filter || !h264enc)
    {
        g_printerr("Not all elements could be created.\n");
        return 1;
    }

    // build the pipeline
    gst_bin_add_many(GST_BIN (pipeline), source, video_src_filter, autovidcon, h264enc,
                     h264parse, video_filter, kvssink, NULL);

    // check if all elements were linked
    if (!gst_element_link_many(source, video_src_filter, autovidcon, h264enc,
                               h264parse, video_filter, kvssink, NULL))
    {
        g_printerr("Elements could not be linked.\n");
        gst_object_unref(pipeline);
        return 1;
    }

    return 0;
}

int gstreamer_init(int argc, char* argv[], CustomData *data) {

    // init GStreamer
    gst_init(&argc, &argv);

    GstElement *pipeline;
    int ret;
    GstStateChangeReturn gst_ret;

    // Reset first frame pts
    data->firstPts = GST_CLOCK_TIME_NONE;

    switch (data->streamSource) {
        case TEST_SOURCE:
            LOG_INFO("Streaming from test source");
            pipeline = gst_pipeline_new("test-kinesis-pipeline");
            ret = gstreamer_test_source_init(data, pipeline);
            break;
    }
    if (ret != 0){
        return ret;
    }

    // Instruct the bus to emit signals for each received message, and connect to the interesting signals
    GstBus *bus = gst_element_get_bus(pipeline);
    gst_bus_add_signal_watch(bus);
    g_signal_connect (G_OBJECT(bus), "message::error", (GCallback) error_cb, data);
    gst_object_unref(bus);

    // start streaming
    gst_ret = gst_element_set_state(pipeline, GST_STATE_PLAYING);
    if (gst_ret == GST_STATE_CHANGE_FAILURE) {
        g_printerr("Unable to set the pipeline to the playing state.\n");
        gst_object_unref(pipeline);
        return 1;
    }

    data->mainLoop = g_main_loop_new(NULL, FALSE);
    g_main_loop_run(data->mainLoop);

    // free resources
    gst_bus_remove_signal_watch(bus);
    gst_element_set_state(pipeline, GST_STATE_NULL);
    gst_object_unref(pipeline);
    g_main_loop_unref(data->mainLoop);
    data->mainLoop = NULL;
    return 0;
}

int main(int argc, char* argv[]) {
    PropertyConfigurator::doConfigure("../kvs_log_configuration");
    initializeEndianness();
    srand(time(0));
    Aws::SDKOptions options;
    Aws::InitAPI(options);
    {
        CanaryConfig canaryConfig;

        // Option to not use env for when JSON config available
        bool useEnvVars = true;
        if (useEnvVars)
        {
            canaryConfig.initConfigWithEnvVars();
        }

        CanaryLogs canaryLogs;

        CustomData data;
        data.pCanaryConfig = &canaryConfig;
        data.streamName = const_cast<char*>(data.pCanaryConfig->streamName.c_str());
        data.pCanaryLogs = &canaryLogs;

        STATUS streamStatus = STATUS_SUCCESS;


        // CloudWatch initialization steps
        Aws::CloudWatch::CloudWatchClient CWclient(data.clientConfig);
        data.pCWclient = &CWclient;
        STATUS retStatus = STATUS_SUCCESS;
        Aws::CloudWatchLogs::CloudWatchLogsClient CWLclient(data.clientConfig);
        CanaryLogs::CloudwatchLogsObject cloudwatchLogsObject;
        cloudwatchLogsObject.logGroupName = "ProducerCppSDK";
        cloudwatchLogsObject.logStreamName = data.pCanaryConfig->streamName +"-log-" + to_string(GETTIME() / HUNDREDS_OF_NANOS_IN_A_MILLISECOND);
        cloudwatchLogsObject.pCwl = &CWLclient;
        if ((retStatus = canaryLogs.initializeCloudwatchLogger(&cloudwatchLogsObject)) != STATUS_SUCCESS) {
            LOG_DEBUG("Cloudwatch logger failed to be initialized with 0x" << retStatus << ">> error code.");
        }
        else
        {
            LOG_DEBUG("Cloudwatch logger initialization success");
        }
        data.pCloudwatchLogsObject = &cloudwatchLogsObject;

        // Set the video stream source
        if (data.pCanaryConfig->sourceType == "TEST_SOURCE")
        {
            data.streamSource = TEST_SOURCE;
        }

        // Non-aggregate CW dimension
        Aws::CloudWatch::Model::Dimension DimensionPerStream;
        DimensionPerStream.SetName("ProducerCppCanaryStreamName");
        DimensionPerStream.SetValue(data.streamName);
        data.pDimensionPerStream = &DimensionPerStream;

        // Aggregate CW dimension
        Aws::CloudWatch::Model::Dimension aggregated_dimension;
        aggregated_dimension.SetName("ProducerCppCanaryType");
        aggregated_dimension.SetValue(canaryConfig.canaryLabel);
        data.pAggregatedDimension = &aggregated_dimension;

        // Set start time after CW initializations
        data.startTime = chrono::duration_cast<nanoseconds>(systemCurrentTime().time_since_epoch()).count();

        if (data.streamSource == TEST_SOURCE)
        {
            gstreamer_init(argc, argv, &data);
            if (STATUS_SUCCEEDED(streamStatus))
            {
                // If streamStatus is success after EOS, send out remaining frames.
                data.kinesisVideoStream->stopSync();
            } else {
                data.kinesisVideoStream->stop();
            }
        }

        // CleanUp
        data.kinesisVideoProducer->freeStream(data.kinesisVideoStream);
        delete (data.timeOfNextKeyFrame);
        canaryLogs.canaryStreamSendLogSync(&cloudwatchLogsObject);
        LOG_DEBUG("end of canary");
    }

    return 0;
}