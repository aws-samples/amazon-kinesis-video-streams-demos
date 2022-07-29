#pragma once

#include <vector>
#include <stdlib.h>
#include <string.h>
#include <mutex>
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
#include <gst/gst.h>
#include <gst/app/gstappsink.h>

#include "CanaryConfig.h"
#include "CanaryLogs.h"

typedef enum _StreamSource {
TEST_SOURCE,
FILE_SOURCE,
LIVE_SOURCE,
RTSP_SOURCE
} StreamSource;

class CustomData
{
public:
    CanaryConfig* pCanaryConfig;

    Aws::Client::ClientConfiguration clientConfig;
    Aws::CloudWatch::CloudWatchClient* pCWclient;
    Aws::CloudWatch::Model::Dimension* pDimensionPerStream;
    Aws::CloudWatch::Model::Dimension* pAggregatedDimension;

    CanaryLogs* pCanaryLogs;
    CanaryLogs::CloudwatchLogsObject* pCloudwatchLogsObject;

    double timeCounter;
    double totalPutFrameErrorCount;
    double totalErrorAckCount;

    int runTill;
    int sleepTimeStamp;

    bool onFirstFrame;
    bool streamStarted;
    bool h264streamSupported;
    bool useAbsoluteFragmentTimes;

    GMainLoop* mainLoop;
    unique_ptr<KinesisVideoProducer> kinesisVideoProducer;
    shared_ptr<KinesisVideoStream> kinesisVideoStream;
   
    char* streamName;
    string rtspUrl;

    map<uint64_t, uint64_t> *timeOfNextKeyFrame;
    UINT64 lastKeyFrameTime;
    UINT64 curKeyFrameTime;

    // stores any error status code reported by StreamErrorCallback.
    atomic_uint streamStatus;

    // Used in file uploading only. Assuming frame timestamp are relative. Add producerStartTime to each frame's
    // timestamp to convert them to absolute timestamp. This way fragments dont overlap after token rotation when doing
    // file uploading.
    uint64_t producerStartTime; // [nanoSeconds]
    uint64_t startTime;  // [nanoSeconds]
    volatile StreamSource streamSource;

    unique_ptr<Credentials> credential;

    uint64_t syntheticDts;
    
    // Pts of first video frame
    uint64_t firstPts;

    CustomData();
};