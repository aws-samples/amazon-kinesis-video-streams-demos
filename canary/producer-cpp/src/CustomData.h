#pragma once

#include "Include.h"

typedef enum _StreamSource {
TEST_SOURCE,
FILE_SOURCE,
LIVE_SOURCE,
RTSP_SOURCE
} StreamSource;

struct CanaryConfig;

class CustomData
{
public:
    CanaryConfig* pCanaryConfig;

    Aws::Client::ClientConfiguration clientConfig;
    Aws::CloudWatch::CloudWatchClient* pCwClient;
    Aws::CloudWatch::Model::Dimension* pDimensionPerStream;
    Aws::CloudWatch::Model::Dimension* pAggregatedDimension;

    DOUBLE timeCounter;
    DOUBLE totalPutFrameErrorCount;
    DOUBLE totalErrorAckCount;

    INT64 runTill;
    INT64 sleepTimeStamp;

    BOOL onFirstFrame;
    BOOL streamStarted;
    BOOL h264streamSupported;
    BOOL useAbsoluteFragmentTimes;

    GMainLoop* mainLoop;
    std::unique_ptr<com::amazonaws::kinesis::video::KinesisVideoProducer> kinesisVideoProducer;
    std::shared_ptr<com::amazonaws::kinesis::video::KinesisVideoStream> kinesisVideoStream;
   
    PCHAR streamName;
    std::string rtspUrl;

    std::map<UINT64, UINT64> *timeOfNextKeyFrame;
    UINT64 lastKeyFrameTime;
    UINT64 curKeyFrameTime;

    // stores any error status code reported by StreamErrorCallback.
    std::atomic_uint streamStatus;

    // Used in file uploading only. Assuming frame timestamp are relative. Add producerStartTime to each frame's
    // timestamp to convert them to absolute timestamp. This way fragments dont overlap after token rotation when doing
    // file uploading.
    UINT64 producerStartTime; // [nanoSeconds]
    UINT64 startTime;  // [nanoSeconds]
    volatile StreamSource streamSource;

    std::unique_ptr<Credentials> credential;

    UINT64 syntheticDts;
    // Pts of first video frame
    UINT64 firstPts;

    CustomData();
};