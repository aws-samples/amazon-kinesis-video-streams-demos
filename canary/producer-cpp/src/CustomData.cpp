#include "CustomData.h"

CustomData::CustomData()
{
    sleepTimeStamp = 0;
    totalPutFrameErrorCount = 0;
    totalErrorAckCount = 0;
    lastKeyFrameTime = 0;
    curKeyFrameTime = 0;
    onFirstFrame = true;
    streamSource = TEST_SOURCE;
    h264streamSupported = false;
    syntheticDts = 0;
    streamStatus = STATUS_SUCCESS;
    mainLoop = NULL;
    firstPts = GST_CLOCK_TIME_NONE;
    useAbsoluteFragmentTimes = true;

    producerStartTime = chrono::duration_cast<nanoseconds>(systemCurrentTime().time_since_epoch()).count(); // [nanoSeconds]
    startTime = chrono::duration_cast<nanoseconds>(systemCurrentTime().time_since_epoch()).count(); // [nanoSeconds]
    clientConfig.region = "us-west-2";
    pCwClient = nullptr;
    pDimensionPerStream = nullptr;
    pAggregatedDimension = nullptr;
    timeOfNextKeyFrame = new map<UINT64, UINT64>();
    timeCounter = producerStartTime / 1000000000; // [seconds]
    // Default first intermittent run to 1 min for testing
    runTill = producerStartTime / 1000000000 / 60 + 1; // [minutes]
    pCanaryConfig = nullptr;
}