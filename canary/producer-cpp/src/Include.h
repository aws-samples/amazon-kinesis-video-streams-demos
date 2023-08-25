#pragma once


#include <string.h>
#include <chrono>
#include <Logger.h>
#include <vector>
#include <stdlib.h>
#include <mutex>
#include <unistd.h>

#include <IotCertCredentialProvider.h>
#include <com/amazonaws/kinesis/video/cproducer/Include.h>
#include <aws/core/Aws.h>
#include <aws/monitoring/CloudWatchClient.h>
#include <aws/monitoring/model/PutMetricDataRequest.h>

#include <gstreamer/gstkvssink.h>
#include <gst/gst.h>
#include <gst/app/gstappsink.h>

#include "CanaryConfig.h"
#include "CustomData.h"

int gstreamer_init(int, char **);
LOGGER_TAG("com.amazonaws.kinesis.video.canarycpp");

#define DEFAULT_RETENTION_PERIOD_HOURS 2
#define DEFAULT_KMS_KEY_ID ""
#define DEFAULT_STREAMING_TYPE STREAMING_TYPE_REALTIME
#define DEFAULT_CONTENT_TYPE "video/h264"
#define DEFAULT_MAX_LATENCY_SECONDS 60
#define DEFAULT_TIMECODE_SCALE_MILLISECONDS 1
#define DEFAULT_KEY_FRAME_FRAGMENTATION TRUE
#define DEFAULT_FRAME_TIMECODES TRUE
#define DEFAULT_ABSOLUTE_FRAGMENT_TIMES TRUE
#define DEFAULT_FRAGMENT_ACKS TRUE
#define DEFAULT_RESTART_ON_ERROR TRUE
#define DEFAULT_RECALCULATE_METRICS TRUE
#define DEFAULT_AVG_BANDWIDTH_BPS (4 * 1024 * 1024)
#define DEFAULT_REPLAY_DURATION_SECONDS 40
#define DEFAULT_CONNECTION_STALENESS_SECONDS 60
#define DEFAULT_CODEC_ID "V_MPEG4/ISO/AVC"
#define DEFAULT_TRACKNAME "kinesis_video"
#define DEFAULT_FRAME_DURATION_MS 1
#define DEFAULT_CREDENTIAL_ROTATION_SECONDS 3600
#define DEFAULT_CREDENTIAL_EXPIRATION_SECONDS 180

#define CANARY_USER_AGENT_NAME          "KVS-CPP-CANARY-CLIENT"
#define DEFAULT_CANARY_REGION           "us-west-2"
#define DEFAULT_CANARY_STREAM_NAME      "DefaultStreamName12132"
#define DEFAULT_SOURCE_TYPE             "TEST_SOURCE"
#define DEFAULT_RUN_SCENARIO            "Continuous"
#define DEFAULT_STREAM_TYPE_REALTIME    "Realtime"
#define DEFAULT_STREAM_TYPE_OFFLINE     "Offline"
#define DEFAULT_CANARY_RUN_LABEL        "DefaultLabel"


#define CANARY_METADATA_SIZE  (SIZEOF(INT64) + SIZEOF(UINT32) + SIZEOF(UINT32) + SIZEOF(UINT64))

#define CANARY_LABEL_LEN                   40


#define STATUS_PRODUCER_CPP_CANARY_BASE                0xA0000000
#define STATUS_EMPTY_IOT_CRED_FILE                     STATUS_PRODUCER_CPP_CANARY_BASE + 0x00000001