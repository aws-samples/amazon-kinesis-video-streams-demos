// Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
//
// SPDX-License-Identifier: Apache-2.0
//
// Portions Copyright
/*
 * GStreamer
 * Copyright (C) 2005 Thomas Vander Stichele <thomas@apestaart.org>
 * Copyright (C) 2005 Ronald S. Bultje <rbultje@ronald.bitfreak.net>
 * Copyright (C) 2017 <<user@hostname.org>>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

#ifndef __GST_KVS_PLUGIN_H__
#define __GST_KVS_PLUGIN_H__

#ifdef HAVE_CONFIG_H
#include <config.h>
#endif

typedef struct __GstKvsPlugin GstKvsPlugin;
typedef struct __GstKvsPlugin* PGstKvsPlugin;
typedef struct __WebRtcStreamingSession WebRtcStreamingSession;
typedef struct __WebRtcStreamingSession* PWebRtcStreamingSession;
typedef struct __PendingMessageQueue PendingMessageQueue;
typedef struct __PendingMessageQueue* PPendingMessageQueue;

#include <gst/gst.h>
#include <gst/base/gstcollectpads.h>
#include <com/amazonaws/kinesis/video/cproducer/Include.h>
#include <com/amazonaws/kinesis/video/webrtcclient/Include.h>
#include "GstPluginUtils.h"
#include "KvsProducer.h"
#include "KvsWebRtc.h"

typedef enum {
    PROP_0,
    PROP_STREAM_NAME,
    PROP_CHANNEL_NAME,
    PROP_RETENTION_PERIOD,
    PROP_STREAMING_TYPE,
    PROP_CONTENT_TYPE,
    PROP_MAX_LATENCY,
    PROP_FRAGMENT_DURATION,
    PROP_TIMECODE_SCALE,
    PROP_KEY_FRAME_FRAGMENTATION,
    PROP_FRAME_TIMECODES,
    PROP_ABSOLUTE_FRAGMENT_TIMES,
    PROP_FRAGMENT_ACKS,
    PROP_RESTART_ON_ERROR,
    PROP_RECALCULATE_METRICS,
    PROP_FRAMERATE,
    PROP_AVG_BANDWIDTH_BPS,
    PROP_BUFFER_DURATION,
    PROP_REPLAY_DURATION,
    PROP_CONNECTION_STALENESS,
    PROP_CODEC_ID,
    PROP_ACCESS_KEY,
    PROP_SECRET_KEY,
    PROP_AWS_REGION,
    PROP_ROTATION_PERIOD,
    PROP_LOG_LEVEL,
    PROP_STORAGE_SIZE,
    PROP_CREDENTIAL_FILE_PATH,
    PROP_IOT_CERTIFICATE,
    PROP_STREAM_TAGS,
    PROP_FILE_START_TIME,
    PROP_DISABLE_BUFFER_CLIPPING,
    PROP_STREAM_CREATE_TIMEOUT,
    PROP_STREAM_STOP_TIMEOUT,
    PROP_ADAPT_CPD_NALS_TO_AVC,
    PROP_ADAPT_FRAME_NALS_TO_AVC,
    PROP_FILE_LOG_PATH,
    PROP_TRICKLE_ICE,
    PROP_WEBRTC_CONNECTION_MODE,
    PROP_ENABLE_STREAMING,
    PROP_WEBRTC_CONNECT,
} KVS_GST_PLUGIN_PROPS;

#define KVS_ADD_METADATA_G_STRUCT_NAME "kvs-add-metadata"
#define KVS_ADD_METADATA_NAME          "name"
#define KVS_ADD_METADATA_VALUE         "value"
#define KVS_ADD_METADATA_PERSISTENT    "persist"

#define KVS_ENABLE_STREAMING_G_STRUCT_NAME "kvs-enable-streaming"
#define KVS_ENABLE_STREAMING_FIELD         "enable"

#define KVS_CONNECT_WEBRTC_G_STRUCT_NAME "kvs-connect-webrtc"
#define KVS_CONNECT_WEBRTC_FIELD         "connect"

#define GSTREAMER_MEDIA_TYPE_H265  "video/x-h265"
#define GSTREAMER_MEDIA_TYPE_H264  "video/x-h264"
#define GSTREAMER_MEDIA_TYPE_AAC   "audio/mpeg"
#define GSTREAMER_MEDIA_TYPE_MULAW "audio/x-mulaw"
#define GSTREAMER_MEDIA_TYPE_ALAW  "audio/x-alaw"

#define MAX_GSTREAMER_MEDIA_TYPE_LEN 16

#define DEFAULT_MAX_CONCURRENT_WEBRTC_STREAMING_SESSION 10

G_BEGIN_DECLS

#define KVS_GST_VERSION AWS_SDK_KVS_PRODUCER_VERSION_STRING

#define GST_TYPE_KVS_PLUGIN            (gst_kvs_plugin_get_type())
#define GST_KVS_PLUGIN(obj)            (G_TYPE_CHECK_INSTANCE_CAST((obj), GST_TYPE_KVS_PLUGIN, GstKvsPlugin))
#define GST_KVS_PLUGIN_CLASS(klass)    (G_TYPE_CHECK_CLASS_CAST((klass), GST_TYPE_KVS_PLUGIN, GstKvsPluginClass))
#define GST_IS_KVS_PLUGIN(obj)         (G_TYPE_CHECK_INSTANCE_TYPE((obj), GST_TYPE_KVS_PLUGIN))
#define GST_IS_KVS_PLUGIN_CLASS(klass) (G_TYPE_CHECK_CLASS_TYPE((klass), GST_TYPE_KVS_PLUGIN))
#define GST_KVS_PLUGIN_CAST(obj)       ((GstKvsPlugin*) obj)

typedef enum {
    GST_PLUGIN_MEDIA_TYPE_AUDIO_VIDEO,
    GST_PLUGIN_MEDIA_TYPE_VIDEO_ONLY,
    GST_PLUGIN_MEDIA_TYPE_AUDIO_ONLY
} GST_PLUGIN_MEDIA_TYPE;

typedef enum {
    WEBRTC_CONNECTION_MODE_DEFAULT,
    WEBRTC_CONNECTION_MODE_TURN_ONLY,
    WEBRTC_CONNECTION_MODE_P2P_ONLY
} WEBRTC_CONNECTION_MODE;

typedef STATUS (*freeCredentialProviderFunc)(PAwsCredentialProvider*);

typedef struct __KvsContext KvsContext;
struct __KvsContext {
    PDeviceInfo pDeviceInfo;
    PStreamInfo pStreamInfo;
    PAwsCredentialProvider pCredentialProvider;
    PClientCallbacks pClientCallbacks;
    PStreamCallbacks pStreamCallbacks;
    CLIENT_HANDLE clientHandle;
    STREAM_HANDLE streamHandle;
    TIMER_QUEUE_HANDLE timerQueueHandle;
    SIGNALING_CLIENT_HANDLE signalingHandle;
    freeCredentialProviderFunc freeCredentialProviderFn;
    ChannelInfo channelInfo;
    SignalingClientCallbacks signalingClientCallbacks;
    SignalingClientInfo signalingClientInfo;
};
typedef struct __KvsContext* PKvsContext;

typedef struct __GstParams GstParams;
struct __GstParams {
    gchar* streamName;
    gchar* channelName;
    guint retentionPeriodInHours;
    gchar* kmsKeyId;
    STREAMING_TYPE streamingType;
    gchar* contentType;
    gchar* audioContentType;
    gchar* videoContentType;
    guint maxLatencyInSeconds;
    guint fragmentDurationInMillis;
    guint timeCodeScaleInMillis;
    gboolean keyFrameFragmentation;
    gboolean frameTimecodes;
    gboolean absoluteFragmentTimecodes;
    gboolean fragmentAcks;
    gboolean restartOnErrors;
    gboolean recalculateMetrics;
    gboolean disableBufferClipping;
    guint frameRate;
    guint avgBandwidthBps;
    guint bufferDurationInSeconds;
    guint replayDurationInSeconds;
    guint connectionStalenessInSeconds;
    gchar* codecId;
    gchar* accessKey;
    gchar* secretKey;
    gchar* awsRegion;
    guint rotationPeriodInSeconds;
    guint logLevel;
    gchar* fileLogPath;
    guint storageSizeInBytes;
    gchar* credentialFilePath;
    GstStructure* iotCertificate;
    GstStructure* streamTags;
    guint64 fileStartTime;
    guint streamCreateTimeoutInSeconds;
    guint streamStopTimeoutInSeconds;
    gboolean adaptCpdNals;
    gboolean adaptFrameNals;
    gboolean trickleIce;
    WEBRTC_CONNECTION_MODE connectionMode;
    gboolean enableStreaming;
    gboolean webRtcConnect;
};
typedef struct __GstParams* PGstParams;

struct __PendingMessageQueue {
    UINT64 hashValue;
    UINT64 createTime;
    PStackQueue messageQueue;
};

typedef struct __RtcMetricsHistory RtcMetricsHistory;
struct __RtcMetricsHistory {
    UINT64 prevNumberOfPacketsSent;
    UINT64 prevNumberOfPacketsReceived;
    UINT64 prevNumberOfBytesSent;
    UINT64 prevNumberOfBytesReceived;
    UINT64 prevPacketsDiscardedOnSend;
    UINT64 prevTs;
};
typedef struct __RtcMetricsHistory* PRtcMetricsHistory;

typedef VOID (*StreamSessionShutdownCallback)(UINT64, PWebRtcStreamingSession);

struct __WebRtcStreamingSession {
    volatile ATOMIC_BOOL terminateFlag;
    volatile ATOMIC_BOOL candidateGatheringDone;
    volatile ATOMIC_BOOL peerIdReceived;
    volatile ATOMIC_BOOL connected;

    PRtcPeerConnection pPeerConnection;
    PRtcRtpTransceiver pVideoRtcRtpTransceiver;
    PRtcRtpTransceiver pAudioRtcRtpTransceiver;
    RtcSessionDescriptionInit answerSessionDescriptionInit;
    UINT64 audioTimestamp;
    UINT64 videoTimestamp;
    CHAR peerId[MAX_SIGNALING_CLIENT_ID_LEN + 1];
    TID receiveAudioVideoSenderTid;
    UINT64 offerReceiveTime;
    UINT64 startUpLatency;
    BOOL firstFrame;
    RtcMetricsHistory rtcMetricsHistory;
    BOOL remoteCanTrickleIce;

    // this is called when the WebRtcStreamingSession is being freed
    StreamSessionShutdownCallback shutdownCallback;
    UINT64 shutdownCallbackCustomData;

    // Back pointer to the main object
    PGstKvsPlugin pGstKvsPlugin;
};

struct __GstKvsPlugin {
    // NOTE: GstElement has to be the first member of the structure
    GstElement element;
    GstCollectPads* collect;

    // Used to store GST params
    GstParams gstParams;

    // KVS related context
    KvsContext kvsContext;

    // Internal fields
    volatile ATOMIC_BOOL terminate;
    volatile ATOMIC_BOOL recreateSignalingClient;
    volatile ATOMIC_BOOL signalingConnected;
    volatile ATOMIC_BOOL streamStopped;
    volatile ATOMIC_BOOL enableStreaming;
    volatile ATOMIC_BOOL connectWebRtc;

    CHAR caCertPath[MAX_PATH_LEN + 1];

    BOOL trackCpdReceived[DEFAULT_AUDIO_TRACK_ID]; // We should only have up-to two tacks

    PCHAR pRegion;

    MUTEX sessionLock;
    MUTEX sessionListReadLock;
    MUTEX signalingLock;
    PStackQueue pPendingSignalingMessageForRemoteClient;
    PHashTable pRtcPeerConnectionForRemoteClient;

    PWebRtcStreamingSession streamingSessionList[DEFAULT_MAX_CONCURRENT_WEBRTC_STREAMING_SESSION];
    UINT32 streamingSessionCount;

    UINT32 iceUriCount;

    UINT32 iceCandidatePairStatsTimerId;

    RtcOnDataChannel onDataChannel;

    UINT32 pregenerateCertTimerId;
    UINT32 serviceRoutineTimerId;
    PStackQueue pregeneratedCertificates; // Max MAX_RTCCONFIGURATION_CERTIFICATES certificates

    RtcStats rtcIceCandidatePairMetrics;

    UINT32 frameCount;
    GST_PLUGIN_MEDIA_TYPE mediaType;

    PBYTE pAdaptedFrameBuf;
    UINT32 adaptedFrameBufSize;

    UINT64 lastDts;
    UINT64 basePts;
    UINT64 firstPts;
    UINT64 producerStartTime;

    gchar* audioCodecId;
    guint numStreams;
    guint numAudioStreams;
    guint numVideoStreams;

    STATUS streamStatus;

    ELEMENTARY_STREAM_NAL_FORMAT detectedCpdFormat;

    BYTE videoCpd[GST_PLUGIN_MAX_CPD_SIZE];
    UINT32 videoCpdSize;
};

/* all information needed for one track */
typedef struct __GstKvsPluginTrackData GstKvsPluginTrackData;
struct __GstKvsPluginTrackData {
    GstCollectData collect; /* we extend the CollectData */
    MKV_TRACK_INFO_TYPE trackType;
    guint trackId;
    PGstKvsPlugin pGstKvsPlugin;
};
typedef struct __GstKvsPluginTrackData* PGstKvsPluginTrackData;

typedef struct __GstKvsPluginClass GstKvsPluginClass;
struct __GstKvsPluginClass {
    GstElementClass parent_class;
};
typedef struct __GstKvsPluginClass* PGstKvsPluginClass;

GType gst_kvs_plugin_get_type(VOID);

G_END_DECLS

STATUS initKinesisVideoStructs(PGstKvsPlugin);
VOID gst_kvs_plugin_set_property(GObject*, guint, const GValue*, GParamSpec*);
VOID gst_kvs_plugin_get_property(GObject*, guint, GValue*, GParamSpec*);
VOID gst_kvs_plugin_finalize(GObject*);
GstStateChangeReturn gst_kvs_plugin_change_state(GstElement*, GstStateChange);

/* collect pad callback */
GstFlowReturn gst_kvs_plugin_handle_buffer(GstCollectPads*, GstCollectData*, GstBuffer*, gpointer);
gboolean gst_kvs_plugin_handle_plugin_event(GstCollectPads*, GstCollectData*, GstEvent*, gpointer);

/* Request pad callback */
GstPad* gst_kvs_plugin_request_new_pad(GstElement*, GstPadTemplate*, const gchar*, const GstCaps*);
VOID gst_kvs_plugin_release_pad(GstElement*, GstPad*);

#endif /* __GST_KVS_PLUGIN_H__ */
