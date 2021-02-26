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

/**
 * SECTION:element-kvs
 *
 * GStrteamer plugin for AWS KVS service
 *
 * <refsect2>
 * <title>Example launch line</title>
 * |[
 *   gst-launch-1.0
 *     autovideosrc
 *   ! videoconvert
 *   ! video/x-raw,format=I420,width=1280,height=720,framerate=30/1
 *   ! vtenc_h264_hw allow-frame-reordering=FALSE realtime=TRUE max-keyframe-interval=45 bitrate=512
 *   ! h264parse
 *   ! video/x-h264,stream-format=avc, alignment=au,width=1280,height=720,framerate=30/1
 *   ! kvsplugin stream-name="plugin-stream" max-latency=30
 * ]|
 * </refsect2>
 */

#define LOG_CLASS "GstPlugin"
#include "GstPlugin.h"

GST_DEBUG_CATEGORY_STATIC(gst_kvs_plugin_debug);
#define GST_CAT_DEFAULT gst_kvs_plugin_debug

#define GST_TYPE_KVS_PLUGIN_STREAMING_TYPE (gst_kvs_plugin_streaming_type_get_type())
GType gst_kvs_plugin_streaming_type_get_type(VOID)
{
    // Need to use static. Could have used a global as well
    static GType kvsPluginStreamingType = 0;
    static GEnumValue enumType[] = {
        {STREAMING_TYPE_REALTIME, "streaming type realtime", "realtime"},
        {STREAMING_TYPE_NEAR_REALTIME, "streaming type near realtime", "near-realtime"},
        {STREAMING_TYPE_OFFLINE, "streaming type offline", "offline"},
        {0, NULL, NULL},
    };

    if (kvsPluginStreamingType == 0) {
        kvsPluginStreamingType = g_enum_register_static("STREAMING_TYPE", enumType);
    }

    return kvsPluginStreamingType;
}

#define GST_TYPE_KVS_PLUGIN_WEBRTC_CONNECTION_MODE (gst_kvs_plugin_connection_mode_get_type())
GType gst_kvs_plugin_connection_mode_get_type(VOID)
{
    // Need to use static. Could have used a global as well
    static GType kvsPluginWebRtcMode = 0;
    static GEnumValue enumType[] = {
        {WEBRTC_CONNECTION_MODE_DEFAULT, "Default connection mode allowing both P2P and TURN", "default"},
        {WEBRTC_CONNECTION_MODE_TURN_ONLY, "TURN only connection mode", "turn"},
        {WEBRTC_CONNECTION_MODE_P2P_ONLY, "P2P only connection mode", "p2p"},
        {0, NULL, NULL},
    };

    if (kvsPluginWebRtcMode == 0) {
        kvsPluginWebRtcMode = g_enum_register_static("WEBRTC_CONNECTION_MODE", enumType);
    }

    return kvsPluginWebRtcMode;
}

GstStaticPadTemplate audiosink_templ = GST_STATIC_PAD_TEMPLATE(
    "audio_%u", GST_PAD_SINK, GST_PAD_REQUEST,
    GST_STATIC_CAPS("audio/mpeg, mpegversion = (int) { 2, 4 }, stream-format = (string) raw, channels = (int) [ 1, MAX ], rate = (int) [ 1, MAX ] ; "
                    "audio/x-alaw, channels = (int) { 1, 2 }, rate = (int) [ 8000, 192000 ] ; "
                    "audio/x-mulaw, channels = (int) { 1, 2 }, rate = (int) [ 8000, 192000 ] ; "));

GstStaticPadTemplate videosink_templ = GST_STATIC_PAD_TEMPLATE(
    "video_%u", GST_PAD_SINK, GST_PAD_REQUEST,
    GST_STATIC_CAPS("video/x-h264, stream-format = (string) avc, alignment = (string) au, width = (int) [ 16, MAX ], height = (int) [ 16, MAX ] ; "
                    "video/x-h265, alignment = (string) au, width = (int) [ 16, MAX ], height = (int) [ 16, MAX ] ;"));

#define _init_kvs_plugin GST_DEBUG_CATEGORY_INIT(gst_kvs_plugin_debug, "kvsgstplugin", 0, "KVS GStreamer plug-in");

#define gst_kvs_plugin_parent_class parent_class

G_DEFINE_TYPE_WITH_CODE(GstKvsPlugin, gst_kvs_plugin, GST_TYPE_ELEMENT, _init_kvs_plugin);

STATUS initKinesisVideoStructs(PGstKvsPlugin pGstPlugin)
{
    STATUS retStatus = STATUS_SUCCESS;
    PCHAR pAccessKey = NULL, pSecretKey = NULL, pSessionToken = NULL;
    IotInfo iotInfo;

    CHK(pGstPlugin != NULL, STATUS_NULL_ARG);

    CHK_STATUS(initKvsWebRtc());

    // Zero out the kvs sub-structures for proper cleanup later
    MEMSET(&pGstPlugin->kvsContext, 0x00, SIZEOF(KvsContext));

    // Load the CA cert path
    lookForSslCert(pGstPlugin);

    pSessionToken = GETENV(SESSION_TOKEN_ENV_VAR);
    if (0 == STRCMP(pGstPlugin->gstParams.accessKey, DEFAULT_ACCESS_KEY)) { // if no static credential is available in plugin property.
        if (NULL == (pAccessKey = GETENV(ACCESS_KEY_ENV_VAR)) ||
            NULL == (pSecretKey = GETENV(SECRET_KEY_ENV_VAR))) { // if no static credential is available in env var.
        }
    } else {
        pAccessKey = pGstPlugin->gstParams.accessKey;
        pSecretKey = pGstPlugin->gstParams.secretKey;
    }

    if (NULL == (pGstPlugin->pRegion = GETENV(DEFAULT_REGION_ENV_VAR))) {
        pGstPlugin->pRegion = pGstPlugin->gstParams.awsRegion;
    }

    if (NULL == pGstPlugin->pRegion) {
        // Use the default
        pGstPlugin->pRegion = DEFAULT_AWS_REGION;
    }

    if (0 != STRCMP(pGstPlugin->gstParams.fileLogPath, DEFAULT_FILE_LOG_PATH)) {
        CHK_STATUS(
            createFileLogger(FILE_LOGGING_BUFFER_SIZE, MAX_NUMBER_OF_LOG_FILES, (PCHAR) FILE_LOGGER_LOG_FILE_DIRECTORY_PATH, TRUE, TRUE, NULL));
    }

    // Create the Credential Provider which will be used by both the producer and the signaling client
    // Check if we have access key then use static credential provider.
    // If we have IoT struct then use IoT credential provider.
    // If we have File then we use file credential provider.
    // We also need to set the appropriate free function pointer.
    if (pAccessKey != NULL) {
        CHK_STATUS(
            createStaticCredentialProvider(pAccessKey, 0, pSecretKey, 0, pSessionToken, 0, MAX_UINT64, &pGstPlugin->kvsContext.pCredentialProvider));
        pGstPlugin->kvsContext.freeCredentialProviderFn = freeStaticCredentialProvider;
    } else if (pGstPlugin->gstParams.iotCertificate != NULL) {
        CHK_STATUS(gstStructToIotInfo(pGstPlugin->gstParams.iotCertificate, &iotInfo));
        CHK_STATUS(createCurlIotCredentialProvider(iotInfo.endPoint, iotInfo.certPath, iotInfo.privateKeyPath, iotInfo.caCertPath, iotInfo.roleAlias,
                                                   pGstPlugin->gstParams.streamName, &pGstPlugin->kvsContext.pCredentialProvider));
        pGstPlugin->kvsContext.freeCredentialProviderFn = freeIotCredentialProvider;
    } else if (pGstPlugin->gstParams.credentialFilePath != NULL) {
        CHK_STATUS(createFileCredentialProvider(pGstPlugin->gstParams.credentialFilePath, &pGstPlugin->kvsContext.pCredentialProvider));
        pGstPlugin->kvsContext.freeCredentialProviderFn = freeFileCredentialProvider;
    }

CleanUp:

    CHK_LOG_ERR(retStatus);

    return retStatus;
}

VOID gst_kvs_plugin_class_init(GstKvsPluginClass* klass)
{
    GObjectClass* gobject_class;
    GstElementClass* gstelement_class;

    gobject_class = G_OBJECT_CLASS(klass);
    gstelement_class = GST_ELEMENT_CLASS(klass);

    gobject_class->set_property = gst_kvs_plugin_set_property;
    gobject_class->get_property = gst_kvs_plugin_get_property;
    gobject_class->finalize = gst_kvs_plugin_finalize;

    g_object_class_install_property(gobject_class, PROP_STREAM_NAME,
                                    g_param_spec_string("stream-name", "Stream Name", "Name of the destination stream", DEFAULT_STREAM_NAME,
                                                        (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_CHANNEL_NAME,
                                    g_param_spec_string("channel-name", "Channel Name", "Name of the signaling channel", DEFAULT_CHANNEL_NAME,
                                                        (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_RETENTION_PERIOD,
                                    g_param_spec_uint("retention-period", "Retention Period", "Length of time stream is preserved. Unit: hours", 0,
                                                      G_MAXUINT, DEFAULT_RETENTION_PERIOD_HOURS,
                                                      (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_STREAMING_TYPE,
                                    g_param_spec_enum("streaming-type", "Streaming Type", "KVS Producer streaming type",
                                                      GST_TYPE_KVS_PLUGIN_STREAMING_TYPE,
                                                      DEFAULT_STREAMING_TYPE, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_WEBRTC_CONNECTION_MODE,
                                    g_param_spec_enum("webrtc-connection-mode", "WebRTC connection mode",
                                                      "WebRTC connection mode - Default, Turn only, P2P only",
                                                      GST_TYPE_KVS_PLUGIN_WEBRTC_CONNECTION_MODE,
                                                      DEFAULT_WEBRTC_CONNECTION_MODE, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_CONTENT_TYPE,
                                    g_param_spec_string("content-type", "Content Type", "content type", MKV_H264_CONTENT_TYPE,
                                                        (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_MAX_LATENCY,
                                    g_param_spec_uint("max-latency", "Max Latency", "Max Latency. Unit: seconds", 0, G_MAXUINT,
                                                      DEFAULT_MAX_LATENCY_SECONDS, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_FRAGMENT_DURATION,
                                    g_param_spec_uint("fragment-duration", "Fragment Duration", "Fragment Duration. Unit: miliseconds", 0, G_MAXUINT,
                                                      DEFAULT_FRAGMENT_DURATION_MILLISECONDS,
                                                      (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_TIMECODE_SCALE,
                                    g_param_spec_uint("timecode-scale", "Timecode Scale", "Timecode Scale. Unit: milliseconds", 0, G_MAXUINT,
                                                      DEFAULT_TIMECODE_SCALE_MILLISECONDS,
                                                      (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_KEY_FRAME_FRAGMENTATION,
                                    g_param_spec_boolean("key-frame-fragmentation", "Do key frame fragmentation", "If true, generate new fragment on each keyframe, otherwise generate new fragment on first keyframe after fragment-duration has passed.",
                                                         DEFAULT_KEY_FRAME_FRAGMENTATION,
                                                         (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_FRAME_TIMECODES,
                                    g_param_spec_boolean("frame-timecodes", "Do frame timecodes", "Do frame timecodes", DEFAULT_FRAME_TIMECODES,
                                                         (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_ABSOLUTE_FRAGMENT_TIMES,
                                    g_param_spec_boolean("absolute-fragment-times", "Use absolute fragment time", "Use absolute fragment time",
                                                         DEFAULT_ABSOLUTE_FRAGMENT_TIMES, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_FRAGMENT_ACKS,
                                    g_param_spec_boolean("fragment-acks", "Do fragment acks", "Do fragment acks", DEFAULT_FRAGMENT_ACKS,
                                                         (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_RESTART_ON_ERROR,
                                    g_param_spec_boolean("restart-on-error", "Do restart on error", "Do restart on error", DEFAULT_RESTART_ON_ERROR,
                                                         (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_RECALCULATE_METRICS,
                                    g_param_spec_boolean("recalculate-metrics", "Do recalculate metrics", "Do recalculate metrics",
                                                         DEFAULT_RECALCULATE_METRICS, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_ADAPT_CPD_NALS_TO_AVC,
                                    g_param_spec_boolean("adapt-cpd-nals", "Whether to adapt CPD NALs from Annex-B to AvCC format", "Adapt CPD NALs",
                                                         DEFAULT_ADAPT_CPD_NALS, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_ADAPT_FRAME_NALS_TO_AVC,
                                    g_param_spec_boolean("adapt-frame-nals", "Whether to adapt Frame NALs from Annex-B to AvCC format",
                                                         "Adapt Frame NALs", DEFAULT_ADAPT_FRAME_NALS,
                                                         (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_FRAMERATE,
                                    g_param_spec_uint("framerate", "Framerate", "Framerate", 0, G_MAXUINT, DEFAULT_STREAM_FRAMERATE,
                                                      (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_AVG_BANDWIDTH_BPS,
                                    g_param_spec_uint("avg-bandwidth-bps", "Average bandwidth bps", "Average bandwidth bps", 0, G_MAXUINT,
                                                      DEFAULT_AVG_BANDWIDTH_BPS, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_BUFFER_DURATION,
                                    g_param_spec_uint("buffer-duration", "Buffer duration", "Buffer duration. Unit: seconds", 0, G_MAXUINT,
                                                      DEFAULT_BUFFER_DURATION_SECONDS, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_REPLAY_DURATION,
                                    g_param_spec_uint("replay-duration", "Replay duration", "Replay duration. Unit: seconds", 0, G_MAXUINT,
                                                      DEFAULT_REPLAY_DURATION_SECONDS, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_CONNECTION_STALENESS,
                                    g_param_spec_uint("connection-staleness", "Connection staleness", "Connection staleness. Unit: seconds", 0,
                                                      G_MAXUINT, DEFAULT_CONNECTION_STALENESS_SECONDS,
                                                      (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_CODEC_ID,
                                    g_param_spec_string("codec-id", "Codec ID", "Codec ID",
                                                        DEFAULT_CODEC_ID_H264,
                                                        (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_ACCESS_KEY,
                                    g_param_spec_string("access-key", "Access Key", "AWS Access Key", DEFAULT_ACCESS_KEY,
                                                        (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_SECRET_KEY,
                                    g_param_spec_string("secret-key", "Secret Key", "AWS Secret Key", DEFAULT_SECRET_KEY,
                                                        (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_AWS_REGION,
                                    g_param_spec_string("aws-region", "AWS Region", "AWS Region",
                                                        DEFAULT_REGION,
                                                        (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_ROTATION_PERIOD,
                                    g_param_spec_uint("rotation-period", "Rotation Period", "Rotation Period. Unit: seconds", 0, G_MAXUINT,
                                                      DEFAULT_ROTATION_PERIOD_SECONDS, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_LOG_LEVEL,
                                    g_param_spec_uint("log-level", "Logging Level", "Logging Verbosity Level", LOG_LEVEL_VERBOSE,
                                                      LOG_LEVEL_SILENT + 1, LOG_LEVEL_SILENT + 1,
                                                      (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_FILE_LOG_PATH,
                                    g_param_spec_string("log-path", "Log path",
                                                        "Specifying the directory where the file-based logger will store the files. ",
                                                        DEFAULT_FILE_LOG_PATH, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_STORAGE_SIZE,
                                    g_param_spec_uint("storage-size", "Storage Size", "Storage Size. Unit: MB", 0, G_MAXUINT, DEFAULT_STORAGE_SIZE_MB,
                                                      (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_CREDENTIAL_FILE_PATH,
                                    g_param_spec_string("credential-path", "Credential File Path", "Credential File Path",
                                                        DEFAULT_CREDENTIAL_FILE_PATH, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_IOT_CERTIFICATE,
                                    g_param_spec_boxed("iot-certificate", "Iot Certificate", "Use aws iot certificate to obtain credentials",
                                                       GST_TYPE_STRUCTURE, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_STREAM_TAGS,
                                    g_param_spec_boxed("stream-tags", "Stream Tags", "key-value pair that you can define and assign to each stream",
                                                       GST_TYPE_STRUCTURE, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_FILE_START_TIME,
                                    g_param_spec_uint64("file-start-time", "File Start Time",                            "Epoch time that the file starts in kinesis video stream. By default, current time is used. Unit: Seconds",
                                                        0, G_MAXULONG, 0,
                                                        (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_DISABLE_BUFFER_CLIPPING,
                                    g_param_spec_boolean("disable-buffer-clipping", "Disable Buffer Clipping",
                                                         "Set to true only if your src/mux elements produce GST_CLOCK_TIME_NONE for segment start times.  It is non-standard "
                                                         "behavior to set this to true, only use if there are known issues with your src/mux segment start/stop times.",
                                                         DEFAULT_DISABLE_BUFFER_CLIPPING,
                                                         (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_TRICKLE_ICE,
                                    g_param_spec_boolean("trickle-ice", "Enable Trickle ICE", "Whether to use tricle ICE mode",
                                                         DEFAULT_TRICKLE_ICE_MODE, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_ENABLE_STREAMING,
                                    g_param_spec_boolean("enable-streaming", "Enable Streaming", "Whether to enable streaming frames to KVS",
                                                         DEFAULT_ENABLE_STREAMING, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_WEBRTC_CONNECT,
                                    g_param_spec_boolean("connect-webrtc", "WebRTC Connect", "Whether to connect to WebRTC signaling channel",
                                                         DEFAULT_WEBRTC_CONNECT, (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_STREAM_CREATE_TIMEOUT,
                                    g_param_spec_uint("stream-create-timeout", "Stream creation timeout", "Stream create timeout. Unit: seconds", 0,
                                                      G_MAXUINT, DEFAULT_STREAM_CREATE_TIMEOUT_SECONDS,
                                                      (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    g_object_class_install_property(gobject_class, PROP_STREAM_STOP_TIMEOUT,
                                    g_param_spec_uint("stream-stop-timeout", "Stream stop timeout", "Stream stop timeout. Unit: seconds", 0,
                                                      G_MAXUINT, DEFAULT_STREAM_STOP_TIMEOUT_SECONDS,
                                                      (GParamFlags)(G_PARAM_READWRITE | G_PARAM_STATIC_STRINGS)));

    gst_element_class_set_static_metadata(gstelement_class, "KVS Plugin", "Sink/Video/Network", "GStreamer AWS KVS plugin",
                                          "AWS KVS <kinesis-video-support@amazon.com>");

    gst_element_class_add_pad_template(gstelement_class, gst_static_pad_template_get(&audiosink_templ));
    gst_element_class_add_pad_template(gstelement_class, gst_static_pad_template_get(&videosink_templ));

    gstelement_class->change_state = GST_DEBUG_FUNCPTR(gst_kvs_plugin_change_state);
    gstelement_class->request_new_pad = GST_DEBUG_FUNCPTR(gst_kvs_plugin_request_new_pad);
    gstelement_class->release_pad = GST_DEBUG_FUNCPTR(gst_kvs_plugin_release_pad);
}

VOID gst_kvs_plugin_init(PGstKvsPlugin pGstKvsPlugin)
{
    pGstKvsPlugin->collect = gst_collect_pads_new();
    gst_collect_pads_set_buffer_function(pGstKvsPlugin->collect, GST_DEBUG_FUNCPTR(gst_kvs_plugin_handle_buffer), pGstKvsPlugin);
    gst_collect_pads_set_event_function(pGstKvsPlugin->collect, GST_DEBUG_FUNCPTR(gst_kvs_plugin_handle_plugin_event), pGstKvsPlugin);

    pGstKvsPlugin->numStreams = 0;
    pGstKvsPlugin->numAudioStreams = 0;
    pGstKvsPlugin->numVideoStreams = 0;

    // Stream definition
    pGstKvsPlugin->gstParams.streamName = g_strdup(DEFAULT_STREAM_NAME);
    pGstKvsPlugin->gstParams.channelName = g_strdup(DEFAULT_CHANNEL_NAME);
    pGstKvsPlugin->gstParams.retentionPeriodInHours = DEFAULT_RETENTION_PERIOD_HOURS;
    pGstKvsPlugin->gstParams.kmsKeyId = g_strdup(DEFAULT_KMS_KEY_ID);
    pGstKvsPlugin->gstParams.streamingType = DEFAULT_STREAMING_TYPE;
    pGstKvsPlugin->gstParams.maxLatencyInSeconds = DEFAULT_MAX_LATENCY_SECONDS;
    pGstKvsPlugin->gstParams.fragmentDurationInMillis = DEFAULT_FRAGMENT_DURATION_MILLISECONDS;
    pGstKvsPlugin->gstParams.timeCodeScaleInMillis = DEFAULT_TIMECODE_SCALE_MILLISECONDS;
    pGstKvsPlugin->gstParams.keyFrameFragmentation = DEFAULT_KEY_FRAME_FRAGMENTATION;
    pGstKvsPlugin->gstParams.frameTimecodes = DEFAULT_FRAME_TIMECODES;
    pGstKvsPlugin->gstParams.absoluteFragmentTimecodes = DEFAULT_ABSOLUTE_FRAGMENT_TIMES;
    pGstKvsPlugin->gstParams.fragmentAcks = DEFAULT_FRAGMENT_ACKS;
    pGstKvsPlugin->gstParams.restartOnErrors = DEFAULT_RESTART_ON_ERROR;
    pGstKvsPlugin->gstParams.recalculateMetrics = DEFAULT_RECALCULATE_METRICS;
    pGstKvsPlugin->gstParams.frameRate = DEFAULT_STREAM_FRAMERATE;
    pGstKvsPlugin->gstParams.avgBandwidthBps = DEFAULT_AVG_BANDWIDTH_BPS;
    pGstKvsPlugin->gstParams.bufferDurationInSeconds = DEFAULT_BUFFER_DURATION_SECONDS;
    pGstKvsPlugin->gstParams.replayDurationInSeconds = DEFAULT_REPLAY_DURATION_SECONDS;
    pGstKvsPlugin->gstParams.connectionStalenessInSeconds = DEFAULT_CONNECTION_STALENESS_SECONDS;
    pGstKvsPlugin->gstParams.disableBufferClipping = DEFAULT_DISABLE_BUFFER_CLIPPING;
    pGstKvsPlugin->gstParams.codecId = g_strdup(DEFAULT_CODEC_ID_H264);
    pGstKvsPlugin->gstParams.accessKey = g_strdup(DEFAULT_ACCESS_KEY);
    pGstKvsPlugin->gstParams.secretKey = g_strdup(DEFAULT_SECRET_KEY);
    pGstKvsPlugin->gstParams.awsRegion = g_strdup(DEFAULT_REGION);
    pGstKvsPlugin->gstParams.rotationPeriodInSeconds = DEFAULT_ROTATION_PERIOD_SECONDS;
    pGstKvsPlugin->gstParams.logLevel = DEFAULT_LOG_LEVEL;
    pGstKvsPlugin->gstParams.fileLogPath = g_strdup(DEFAULT_FILE_LOG_PATH);
    pGstKvsPlugin->gstParams.storageSizeInBytes = DEFAULT_STORAGE_SIZE_MB;
    pGstKvsPlugin->gstParams.credentialFilePath = g_strdup(DEFAULT_CREDENTIAL_FILE_PATH);
    pGstKvsPlugin->gstParams.fileStartTime = GETTIME() / HUNDREDS_OF_NANOS_IN_A_SECOND;
    pGstKvsPlugin->audioCodecId = g_strdup(DEFAULT_AUDIO_CODEC_ID_AAC);
    pGstKvsPlugin->gstParams.streamCreateTimeoutInSeconds = DEFAULT_STREAM_CREATE_TIMEOUT_SECONDS;
    pGstKvsPlugin->gstParams.streamStopTimeoutInSeconds = DEFAULT_STREAM_STOP_TIMEOUT_SECONDS;
    pGstKvsPlugin->gstParams.trickleIce = DEFAULT_TRICKLE_ICE_MODE;
    pGstKvsPlugin->gstParams.enableStreaming = DEFAULT_ENABLE_STREAMING;
    pGstKvsPlugin->gstParams.webRtcConnect = DEFAULT_WEBRTC_CONNECT;

    ATOMIC_STORE_BOOL(&pGstKvsPlugin->enableStreaming, pGstKvsPlugin->gstParams.enableStreaming);
    ATOMIC_STORE_BOOL(&pGstKvsPlugin->connectWebRtc, pGstKvsPlugin->gstParams.webRtcConnect);

    pGstKvsPlugin->adaptedFrameBufSize = 0;
    pGstKvsPlugin->pAdaptedFrameBuf = NULL;

    // Mark plugin as sink
    GST_OBJECT_FLAG_SET(pGstKvsPlugin, GST_ELEMENT_FLAG_SINK);
}

VOID gst_kvs_plugin_finalize(GObject* object)
{
    PGstKvsPlugin pGstKvsPlugin = GST_KVS_PLUGIN(object);

    if (pGstKvsPlugin == NULL) {
        return;
    }

    if (pGstKvsPlugin->kvsContext.pDeviceInfo != NULL) {
        freeDeviceInfo(&pGstKvsPlugin->kvsContext.pDeviceInfo);
    }

    if (pGstKvsPlugin->kvsContext.pStreamInfo != NULL) {
        freeStreamInfoProvider(&pGstKvsPlugin->kvsContext.pStreamInfo);
    }

    if (IS_VALID_STREAM_HANDLE(pGstKvsPlugin->kvsContext.streamHandle)) {
        freeKinesisVideoStream(&pGstKvsPlugin->kvsContext.streamHandle);
    }

    if (IS_VALID_CLIENT_HANDLE(pGstKvsPlugin->kvsContext.clientHandle)) {
        freeKinesisVideoClient(&pGstKvsPlugin->kvsContext.clientHandle);
    }

    if (pGstKvsPlugin->kvsContext.pClientCallbacks != NULL) {
        freeCallbacksProvider(&pGstKvsPlugin->kvsContext.pClientCallbacks);
    }

    freeGstKvsWebRtcPlugin(pGstKvsPlugin);

    // Last object to be freed
    if (pGstKvsPlugin->kvsContext.pCredentialProvider != NULL) {
        pGstKvsPlugin->kvsContext.freeCredentialProviderFn(&pGstKvsPlugin->kvsContext.pCredentialProvider);
    }

    gst_object_unref(pGstKvsPlugin->collect);
    g_free(pGstKvsPlugin->gstParams.streamName);
    g_free(pGstKvsPlugin->gstParams.channelName);
    g_free(pGstKvsPlugin->gstParams.contentType);
    g_free(pGstKvsPlugin->gstParams.codecId);
    g_free(pGstKvsPlugin->gstParams.secretKey);
    g_free(pGstKvsPlugin->gstParams.accessKey);
    g_free(pGstKvsPlugin->audioCodecId);
    g_free(pGstKvsPlugin->gstParams.fileLogPath);

    if (pGstKvsPlugin->gstParams.iotCertificate != NULL) {
        gst_structure_free(pGstKvsPlugin->gstParams.iotCertificate);
        pGstKvsPlugin->gstParams.iotCertificate = NULL;
    }

    if (pGstKvsPlugin->gstParams.streamTags != NULL) {
        gst_structure_free(pGstKvsPlugin->gstParams.streamTags);
        pGstKvsPlugin->gstParams.streamTags = NULL;
    }

    SAFE_MEMFREE(pGstKvsPlugin->pAdaptedFrameBuf);

    G_OBJECT_CLASS(parent_class)->finalize(object);
}

VOID gst_kvs_plugin_set_property(GObject* object, guint propId, const GValue* value, GParamSpec* pspec)
{
    PGstKvsPlugin pGstKvsPlugin = GST_KVS_PLUGIN(object);

    if (pGstKvsPlugin == NULL) {
        return;
    }

    switch (propId) {
        case PROP_STREAM_NAME:
            g_free(pGstKvsPlugin->gstParams.streamName);
            pGstKvsPlugin->gstParams.streamName = g_strdup(g_value_get_string(value));
            break;
        case PROP_CHANNEL_NAME:
            g_free(pGstKvsPlugin->gstParams.channelName);
            pGstKvsPlugin->gstParams.channelName = g_strdup(g_value_get_string(value));
            break;
        case PROP_RETENTION_PERIOD:
            pGstKvsPlugin->gstParams.retentionPeriodInHours = g_value_get_uint(value);
            break;
        case PROP_STREAMING_TYPE:
            pGstKvsPlugin->gstParams.streamingType = (STREAMING_TYPE) g_value_get_enum(value);
            break;
        case PROP_WEBRTC_CONNECTION_MODE:
            pGstKvsPlugin->gstParams.connectionMode = (WEBRTC_CONNECTION_MODE) g_value_get_enum(value);
            break;
        case PROP_CONTENT_TYPE:
            g_free(pGstKvsPlugin->gstParams.contentType);
            pGstKvsPlugin->gstParams.contentType = g_strdup(g_value_get_string(value));
            break;
        case PROP_MAX_LATENCY:
            pGstKvsPlugin->gstParams.maxLatencyInSeconds = g_value_get_uint(value);
            break;
        case PROP_FRAGMENT_DURATION:
            pGstKvsPlugin->gstParams.fragmentDurationInMillis = g_value_get_uint(value);
            break;
        case PROP_TIMECODE_SCALE:
            pGstKvsPlugin->gstParams.timeCodeScaleInMillis = g_value_get_uint(value);
            break;
        case PROP_KEY_FRAME_FRAGMENTATION:
            pGstKvsPlugin->gstParams.keyFrameFragmentation = g_value_get_boolean(value);
            break;
        case PROP_FRAME_TIMECODES:
            pGstKvsPlugin->gstParams.frameTimecodes = g_value_get_boolean(value);
            break;
        case PROP_ABSOLUTE_FRAGMENT_TIMES:
            pGstKvsPlugin->gstParams.absoluteFragmentTimecodes = g_value_get_boolean(value);
            break;
        case PROP_FRAGMENT_ACKS:
            pGstKvsPlugin->gstParams.fragmentAcks = g_value_get_boolean(value);
            break;
        case PROP_RESTART_ON_ERROR:
            pGstKvsPlugin->gstParams.restartOnErrors = g_value_get_boolean(value);
            break;
        case PROP_RECALCULATE_METRICS:
            pGstKvsPlugin->gstParams.recalculateMetrics = g_value_get_boolean(value);
            break;
        case PROP_ADAPT_CPD_NALS_TO_AVC:
            pGstKvsPlugin->gstParams.adaptCpdNals = g_value_get_boolean(value);
            break;
        case PROP_ADAPT_FRAME_NALS_TO_AVC:
            pGstKvsPlugin->gstParams.adaptFrameNals = g_value_get_boolean(value);
            break;
        case PROP_AVG_BANDWIDTH_BPS:
            pGstKvsPlugin->gstParams.avgBandwidthBps = g_value_get_uint(value);
            break;
        case PROP_BUFFER_DURATION:
            pGstKvsPlugin->gstParams.bufferDurationInSeconds = g_value_get_uint(value);
            break;
        case PROP_REPLAY_DURATION:
            pGstKvsPlugin->gstParams.replayDurationInSeconds = g_value_get_uint(value);
            break;
        case PROP_CONNECTION_STALENESS:
            pGstKvsPlugin->gstParams.connectionStalenessInSeconds = g_value_get_uint(value);
            break;
        case PROP_CODEC_ID:
            g_free(pGstKvsPlugin->gstParams.codecId);
            pGstKvsPlugin->gstParams.codecId = g_strdup(g_value_get_string(value));
            break;
        case PROP_ACCESS_KEY:
            g_free(pGstKvsPlugin->gstParams.accessKey);
            pGstKvsPlugin->gstParams.accessKey = g_strdup(g_value_get_string(value));
            break;
        case PROP_SECRET_KEY:
            g_free(pGstKvsPlugin->gstParams.secretKey);
            pGstKvsPlugin->gstParams.secretKey = g_strdup(g_value_get_string(value));
            break;
        case PROP_AWS_REGION:
            g_free(pGstKvsPlugin->gstParams.awsRegion);
            pGstKvsPlugin->gstParams.awsRegion = g_strdup(g_value_get_string(value));
            break;
        case PROP_ROTATION_PERIOD:
            pGstKvsPlugin->gstParams.rotationPeriodInSeconds = g_value_get_uint(value);
            break;
        case PROP_LOG_LEVEL:
            pGstKvsPlugin->gstParams.logLevel = g_value_get_uint(value);
            break;
        case PROP_FILE_LOG_PATH:
            g_free(pGstKvsPlugin->gstParams.fileLogPath);
            pGstKvsPlugin->gstParams.fileLogPath = g_strdup(g_value_get_string(value));
            break;
        case PROP_FRAMERATE:
            pGstKvsPlugin->gstParams.frameRate = g_value_get_uint(value);
            break;
        case PROP_STORAGE_SIZE:
            pGstKvsPlugin->gstParams.storageSizeInBytes = g_value_get_uint(value);
            break;
        case PROP_CREDENTIAL_FILE_PATH:
            pGstKvsPlugin->gstParams.credentialFilePath = g_strdup(g_value_get_string(value));
            break;
        case PROP_IOT_CERTIFICATE: {
            const GstStructure* iotStruct = gst_value_get_structure(value);

            if (pGstKvsPlugin->gstParams.iotCertificate != NULL) {
                gst_structure_free(pGstKvsPlugin->gstParams.iotCertificate);
            }

            pGstKvsPlugin->gstParams.iotCertificate = (iotStruct != NULL) ? gst_structure_copy(iotStruct) : NULL;
            break;
        }
        case PROP_STREAM_TAGS: {
            const GstStructure* tagsStruct = gst_value_get_structure(value);

            if (pGstKvsPlugin->gstParams.streamTags != NULL) {
                gst_structure_free(pGstKvsPlugin->gstParams.streamTags);
            }

            pGstKvsPlugin->gstParams.streamTags = (tagsStruct != NULL) ? gst_structure_copy(tagsStruct) : NULL;
            break;
        }
        case PROP_FILE_START_TIME:
            pGstKvsPlugin->gstParams.fileStartTime = g_value_get_uint64(value);
            break;
        case PROP_DISABLE_BUFFER_CLIPPING:
            pGstKvsPlugin->gstParams.disableBufferClipping = g_value_get_boolean(value);
            break;
        case PROP_STREAM_CREATE_TIMEOUT:
            pGstKvsPlugin->gstParams.streamCreateTimeoutInSeconds = g_value_get_uint(value);
            break;
        case PROP_STREAM_STOP_TIMEOUT:
            pGstKvsPlugin->gstParams.streamStopTimeoutInSeconds = g_value_get_uint(value);
            break;
        case PROP_TRICKLE_ICE:
            pGstKvsPlugin->gstParams.trickleIce = g_value_get_boolean(value);
            break;
        case PROP_ENABLE_STREAMING:
            pGstKvsPlugin->gstParams.enableStreaming = g_value_get_boolean(value);
            ATOMIC_STORE_BOOL(&pGstKvsPlugin->enableStreaming, pGstKvsPlugin->gstParams.enableStreaming);
            break;
        case PROP_WEBRTC_CONNECT:
            pGstKvsPlugin->gstParams.webRtcConnect = g_value_get_boolean(value);
            ATOMIC_STORE_BOOL(&pGstKvsPlugin->connectWebRtc, pGstKvsPlugin->gstParams.webRtcConnect);
            break;
        default:
            G_OBJECT_WARN_INVALID_PROPERTY_ID(object, propId, pspec);
            break;
    }
}

VOID gst_kvs_plugin_get_property(GObject* object, guint propId, GValue* value, GParamSpec* pspec)
{
    PGstKvsPlugin pGstKvsPlugin = GST_KVS_PLUGIN(object);

    if (pGstKvsPlugin == NULL) {
        return;
    }

    switch (propId) {
        case PROP_STREAM_NAME:
            g_value_set_string(value, pGstKvsPlugin->gstParams.streamName);
            break;
        case PROP_CHANNEL_NAME:
            g_value_set_string(value, pGstKvsPlugin->gstParams.channelName);
            break;
        case PROP_RETENTION_PERIOD:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.retentionPeriodInHours);
            break;
        case PROP_STREAMING_TYPE:
            g_value_set_enum(value, pGstKvsPlugin->gstParams.streamingType);
            break;
        case PROP_WEBRTC_CONNECTION_MODE:
            g_value_set_enum(value, pGstKvsPlugin->gstParams.connectionMode);
            break;
        case PROP_CONTENT_TYPE:
            g_value_set_string(value, pGstKvsPlugin->gstParams.contentType);
            break;
        case PROP_MAX_LATENCY:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.maxLatencyInSeconds);
            break;
        case PROP_FRAGMENT_DURATION:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.fragmentDurationInMillis);
            break;
        case PROP_TIMECODE_SCALE:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.timeCodeScaleInMillis);
            break;
        case PROP_KEY_FRAME_FRAGMENTATION:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.keyFrameFragmentation);
            break;
        case PROP_FRAME_TIMECODES:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.frameTimecodes);
            break;
        case PROP_ABSOLUTE_FRAGMENT_TIMES:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.absoluteFragmentTimecodes);
            break;
        case PROP_FRAGMENT_ACKS:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.fragmentAcks);
            break;
        case PROP_RESTART_ON_ERROR:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.restartOnErrors);
            break;
        case PROP_RECALCULATE_METRICS:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.recalculateMetrics);
            break;
        case PROP_ADAPT_CPD_NALS_TO_AVC:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.adaptCpdNals);
            break;
        case PROP_ADAPT_FRAME_NALS_TO_AVC:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.adaptFrameNals);
            break;
        case PROP_AVG_BANDWIDTH_BPS:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.avgBandwidthBps);
            break;
        case PROP_BUFFER_DURATION:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.bufferDurationInSeconds);
            break;
        case PROP_REPLAY_DURATION:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.replayDurationInSeconds);
            break;
        case PROP_CONNECTION_STALENESS:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.connectionStalenessInSeconds);
            break;
        case PROP_CODEC_ID:
            g_value_set_string(value, pGstKvsPlugin->gstParams.codecId);
            break;
        case PROP_ACCESS_KEY:
            g_value_set_string(value, pGstKvsPlugin->gstParams.accessKey);
            break;
        case PROP_SECRET_KEY:
            g_value_set_string(value, pGstKvsPlugin->gstParams.secretKey);
            break;
        case PROP_AWS_REGION:
            g_value_set_string(value, pGstKvsPlugin->gstParams.awsRegion);
            break;
        case PROP_ROTATION_PERIOD:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.rotationPeriodInSeconds);
            break;
        case PROP_LOG_LEVEL:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.logLevel);
            break;
        case PROP_FILE_LOG_PATH:
            g_value_set_string(value, pGstKvsPlugin->gstParams.fileLogPath);
            break;
        case PROP_FRAMERATE:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.frameRate);
            break;
        case PROP_STORAGE_SIZE:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.storageSizeInBytes);
            break;
        case PROP_CREDENTIAL_FILE_PATH:
            g_value_set_string(value, pGstKvsPlugin->gstParams.credentialFilePath);
            break;
        case PROP_IOT_CERTIFICATE:
            gst_value_set_structure(value, pGstKvsPlugin->gstParams.iotCertificate);
            break;
        case PROP_STREAM_TAGS:
            gst_value_set_structure(value, pGstKvsPlugin->gstParams.streamTags);
            break;
        case PROP_FILE_START_TIME:
            g_value_set_uint64(value, pGstKvsPlugin->gstParams.fileStartTime);
            break;
        case PROP_DISABLE_BUFFER_CLIPPING:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.disableBufferClipping);
            break;
        case PROP_TRICKLE_ICE:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.trickleIce);
            break;
        case PROP_STREAM_CREATE_TIMEOUT:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.streamCreateTimeoutInSeconds);
            break;
        case PROP_STREAM_STOP_TIMEOUT:
            g_value_set_uint(value, pGstKvsPlugin->gstParams.streamStopTimeoutInSeconds);
            break;
        case PROP_ENABLE_STREAMING:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.enableStreaming);
            break;
        case PROP_WEBRTC_CONNECT:
            g_value_set_boolean(value, pGstKvsPlugin->gstParams.webRtcConnect);
            break;
        default:
            G_OBJECT_WARN_INVALID_PROPERTY_ID(object, propId, pspec);
            break;
    }
}

gboolean gst_kvs_plugin_handle_plugin_event(GstCollectPads* pads, GstCollectData* track_data, GstEvent* event, gpointer user_data)
{
    STATUS retStatus = STATUS_SUCCESS;
    PGstKvsPlugin pGstKvsPlugin = GST_KVS_PLUGIN(user_data);
    PGstKvsPluginTrackData pTrackData = (PGstKvsPluginTrackData) track_data;
    GstCaps* gstcaps = NULL;
    UINT64 trackId = pTrackData->trackId;
    BYTE cpd[GST_PLUGIN_MAX_CPD_SIZE];
    UINT32 cpdSize;
    gchar* gstCpd = NULL;
    gboolean persistent, enableStreaming, connectWeRtc;
    const GstStructure* gstStruct;
    PCHAR pName, pVal;
    UINT32 nalFlags = NAL_ADAPTATION_FLAG_NONE;

    gint samplerate = 0, channels = 0;
    const gchar* mediaType;

    switch (GST_EVENT_TYPE(event)) {
        case GST_EVENT_EOS:
            if (!ATOMIC_LOAD_BOOL(&pGstKvsPlugin->streamStopped)) {
                if (STATUS_FAILED(retStatus = stopKinesisVideoStreamSync(pGstKvsPlugin->kvsContext.streamHandle))) {
                    GST_ERROR_OBJECT(pGstKvsPlugin, "Failed to stop the stream with 0x%08x", retStatus);
                    CHK_STATUS(retStatus);
                }

                ATOMIC_STORE_BOOL(&pGstKvsPlugin->streamStopped, TRUE);
            }

            break;

        case GST_EVENT_CAPS:
            gst_event_parse_caps(event, &gstcaps);
            GstStructure* gststructforcaps = gst_caps_get_structure(gstcaps, 0);
            mediaType = gst_structure_get_name(gststructforcaps);

            if (0 == STRCMP(mediaType, GSTREAMER_MEDIA_TYPE_ALAW) || 0 == STRCMP(mediaType, GSTREAMER_MEDIA_TYPE_MULAW)) {
                KVS_PCM_FORMAT_CODE format = KVS_PCM_FORMAT_CODE_MULAW;

                gst_structure_get_int(gststructforcaps, "rate", &samplerate);
                gst_structure_get_int(gststructforcaps, "channels", &channels);

                if (samplerate == 0 || channels == 0) {
                    GST_ERROR_OBJECT(pGstKvsPlugin, "Missing channels/sample rate on caps");
                    CHK(FALSE, STATUS_INVALID_OPERATION);
                }

                if (0 == STRCMP(mediaType, GSTREAMER_MEDIA_TYPE_ALAW)) {
                    format = KVS_PCM_FORMAT_CODE_ALAW;
                } else {
                    format = KVS_PCM_FORMAT_CODE_MULAW;
                }

                if (STATUS_FAILED(mkvgenGeneratePcmCpd(format, (UINT32) samplerate, (UINT16) channels, (PBYTE) cpd, KVS_PCM_CPD_SIZE_BYTE))) {
                    GST_ERROR_OBJECT(pGstKvsPlugin, "Failed to generate pcm cpd");
                    CHK(FALSE, STATUS_INVALID_OPERATION);
                }

                // Send cpd to kinesis video stream
                CHK_STATUS(kinesisVideoStreamFormatChanged(pGstKvsPlugin->kvsContext.streamHandle, KVS_PCM_CPD_SIZE_BYTE, cpd, trackId));
            } else if (!pGstKvsPlugin->trackCpdReceived[trackId] && gst_structure_has_field(gststructforcaps, "codec_data")) {
                const GValue* gstStreamFormat = gst_structure_get_value(gststructforcaps, "codec_data");
                gstCpd = gst_value_serialize(gstStreamFormat);

                // Convert hex cpd to byte array by getting the size, allocating and converting
                CHK_STATUS(hexDecode(gstCpd, 0, NULL, &cpdSize));
                CHK(cpdSize < GST_PLUGIN_MAX_CPD_SIZE, STATUS_INVALID_ARG_LEN);
                CHK_STATUS(hexDecode(gstCpd, 0, cpd, &cpdSize));

                // Need to detect the CPD format first time only for video
                if (trackId == DEFAULT_VIDEO_TRACK_ID && pGstKvsPlugin->detectedCpdFormat == ELEMENTARY_STREAM_NAL_FORMAT_UNKNOWN) {
                    CHK_STATUS(identifyCpdNalFormat(cpd, cpdSize, &pGstKvsPlugin->detectedCpdFormat));

                    // We should store the CPD as is if it's in Annex-B format and convert from AvCC/HEVC
                    // The stored CPD will be used for WebRTC RTP stream prefixing each I-frame if it's not
                    if (pGstKvsPlugin->detectedCpdFormat == ELEMENTARY_STREAM_NAL_FORMAT_AVCC) {
                        // Convert from AvCC to Annex-B format
                        // NOTE: This will also store the data
                        CHK_STATUS(convertCpdFromAvcToAnnexB(pGstKvsPlugin, cpd, cpdSize));
                    } else if (pGstKvsPlugin->detectedCpdFormat == ELEMENTARY_STREAM_NAL_FORMAT_HEVC) {
                        // Convert from HEVC to Annex-B format
                        // NOTE: This will also store the data
                        CHK_STATUS(convertCpdFromHevcToAnnexB(pGstKvsPlugin, cpd, cpdSize));
                    } else {
                        // Store it for use with WebRTC where we will pre-pend the Annex-B CPD to each I-frame
                        // if the Annex-B format I-frame doesn't have it already pre-pended
                        MEMCPY(pGstKvsPlugin->videoCpd, cpd, cpdSize);
                        pGstKvsPlugin->videoCpdSize = cpdSize;
                    }

                    // Prior to setting the CPD we need to set the flags
                    if (pGstKvsPlugin->gstParams.adaptCpdNals && pGstKvsPlugin->detectedCpdFormat == ELEMENTARY_STREAM_NAL_FORMAT_ANNEX_B) {
                        nalFlags |= NAL_ADAPTATION_ANNEXB_CPD_NALS;
                    }

                    if (pGstKvsPlugin->gstParams.adaptFrameNals && pGstKvsPlugin->detectedCpdFormat == ELEMENTARY_STREAM_NAL_FORMAT_ANNEX_B) {
                        nalFlags |= NAL_ADAPTATION_ANNEXB_NALS;
                    }

                    CHK_STATUS(kinesisVideoStreamSetNalAdaptationFlags(pGstKvsPlugin->kvsContext.streamHandle, nalFlags));
                }

                // Send cpd to kinesis video stream
                CHK_STATUS(kinesisVideoStreamFormatChanged(pGstKvsPlugin->kvsContext.streamHandle, cpdSize, cpd, trackId));

                // Mark as received
                pGstKvsPlugin->trackCpdReceived[trackId] = TRUE;
            }

            gst_event_unref(event);
            event = NULL;

            break;

        case GST_EVENT_CUSTOM_DOWNSTREAM:
            gstStruct = gst_event_get_structure(event);

            if (gst_structure_has_name(gstStruct, KVS_ADD_METADATA_G_STRUCT_NAME) &&
                NULL != (pName = (PCHAR) gst_structure_get_string(gstStruct, KVS_ADD_METADATA_NAME)) &&
                NULL != (pVal = (PCHAR) gst_structure_get_string(gstStruct, KVS_ADD_METADATA_VALUE)) &&
                gst_structure_get_boolean(gstStruct, KVS_ADD_METADATA_PERSISTENT, &persistent)) {
                DLOGD("received " KVS_ADD_METADATA_G_STRUCT_NAME " event");

                CHK_STATUS(putKinesisVideoFragmentMetadata(pGstKvsPlugin->kvsContext.streamHandle, pName, pVal, persistent));

                gst_event_unref(event);
                event = NULL;
            } else if (gst_structure_has_name(gstStruct, KVS_ENABLE_STREAMING_G_STRUCT_NAME) &&
                       gst_structure_get_boolean(gstStruct, KVS_ENABLE_STREAMING_FIELD, &enableStreaming)) {
                DLOGD("received " KVS_ENABLE_STREAMING_G_STRUCT_NAME " event");

                ATOMIC_STORE_BOOL(&pGstKvsPlugin->enableStreaming, enableStreaming);

                gst_event_unref(event);
                event = NULL;
            } else if (gst_structure_has_name(gstStruct, KVS_CONNECT_WEBRTC_G_STRUCT_NAME) &&
                       gst_structure_get_boolean(gstStruct, KVS_CONNECT_WEBRTC_FIELD, &connectWeRtc)) {
                DLOGD("received " KVS_CONNECT_WEBRTC_G_STRUCT_NAME " event");

                ATOMIC_STORE_BOOL(&pGstKvsPlugin->connectWebRtc, connectWeRtc);

                gst_event_unref(event);
                event = NULL;
            }

            break;

        default:
            break;
    }

CleanUp:

    if (event != NULL) {
        gst_collect_pads_event_default(pads, track_data, event, FALSE);
    }

    if (gstCpd != NULL) {
        g_free(gstCpd);
    }

    if (STATUS_FAILED(retStatus)) {
        GST_ELEMENT_ERROR(pGstKvsPlugin, STREAM, FAILED, (NULL), ("Failed to handle event"));
    }

    return STATUS_SUCCEEDED(retStatus);
}

GstFlowReturn gst_kvs_plugin_handle_buffer(GstCollectPads* pads, GstCollectData* track_data, GstBuffer* buf, gpointer user_data)
{
    PGstKvsPlugin pGstKvsPlugin = GST_KVS_PLUGIN(user_data);
    GstFlowReturn ret = GST_FLOW_OK;
    PGstKvsPluginTrackData pTrackData = (PGstKvsPluginTrackData) track_data;

    BOOL isDroppable, delta;
    STATUS streamStatus = pGstKvsPlugin->streamStatus;
    GstMessage* message;
    UINT64 trackId;
    FRAME_FLAGS frameFlags = FRAME_FLAG_NONE;
    GstMapInfo info;
    STATUS status;
    Frame frame;

    info.data = NULL;

    // eos reached
    if (buf == NULL && pTrackData == NULL) {
        if (!ATOMIC_LOAD_BOOL(&pGstKvsPlugin->streamStopped)) {
            if (STATUS_FAILED(status = stopKinesisVideoStreamSync(pGstKvsPlugin->kvsContext.streamHandle))) {
                DLOGW("Failed to stop the stream with 0x%08x", status);
            }
        }

        ATOMIC_STORE_BOOL(&pGstKvsPlugin->streamStopped, TRUE);

        DLOGD("Sending eos");

        // send out eos message to gstreamer bus
        message = gst_message_new_eos(GST_OBJECT_CAST(pGstKvsPlugin));
        gst_element_post_message(GST_ELEMENT_CAST(pGstKvsPlugin), message);

        ret = GST_FLOW_EOS;
        goto CleanUp;
    }

    if (STATUS_FAILED(streamStatus)) {
        // in offline case, we cant tell the pipeline to restream the file again in case of network outage.
        // therefore error out and let higher level application do the retry.
        if (IS_OFFLINE_STREAMING_MODE(pGstKvsPlugin->gstParams.streamingType) || !IS_RETRIABLE_ERROR(streamStatus)) {
            // fatal cases
            GST_ELEMENT_ERROR(pGstKvsPlugin, STREAM, FAILED, (NULL), ("Stream error occurred. Status: 0x%08x", streamStatus));
            ret = GST_FLOW_ERROR;
            goto CleanUp;
        } else {
            // resetStream, note that this will flush out producer buffer
            if (STATUS_FAILED(status = kinesisVideoStreamResetStream(pGstKvsPlugin->kvsContext.streamHandle))) {
                DLOGW("Failed to reset the stream with 0x%08x", status);
            }

            // reset state
            pGstKvsPlugin->streamStatus = STATUS_SUCCESS;
        }
    }

    isDroppable = GST_BUFFER_FLAG_IS_SET(buf, GST_BUFFER_FLAG_CORRUPTED) || GST_BUFFER_FLAG_IS_SET(buf, GST_BUFFER_FLAG_DECODE_ONLY) ||
        (GST_BUFFER_FLAGS(buf) == GST_BUFFER_FLAG_DISCONT) ||
        (GST_BUFFER_FLAG_IS_SET(buf, GST_BUFFER_FLAG_DISCONT) && GST_BUFFER_FLAG_IS_SET(buf, GST_BUFFER_FLAG_DELTA_UNIT)) ||
        // drop if buffer contains header and has invalid timestamp
        (GST_BUFFER_FLAG_IS_SET(buf, GST_BUFFER_FLAG_HEADER) && (!GST_BUFFER_PTS_IS_VALID(buf) || !GST_BUFFER_DTS_IS_VALID(buf)));

    if (isDroppable) {
        DLOGD("Dropping frame with flag: %d", GST_BUFFER_FLAGS(buf));
        goto CleanUp;
    }

    // In offline mode, if user specifies a file_start_time, the stream will be configured to use absolute
    // timestamp. Therefore in here we add the file_start_time to frame pts to create absolute timestamp.
    // If user did not specify file_start_time, file_start_time will be 0 and has no effect.
    if (IS_OFFLINE_STREAMING_MODE(pGstKvsPlugin->gstParams.streamingType)) {
        buf->dts = 0; // if offline mode, i.e. streaming a file, the dts from gstreamer is undefined.
        buf->pts += pGstKvsPlugin->basePts;
    } else if (!GST_BUFFER_DTS_IS_VALID(buf)) {
        buf->dts = pGstKvsPlugin->lastDts + DEFAULT_FRAME_DURATION_MS * HUNDREDS_OF_NANOS_IN_A_MILLISECOND * DEFAULT_TIME_UNIT_IN_NANOS;
    }

    pGstKvsPlugin->lastDts = buf->dts;
    trackId = pTrackData->trackId;

    if (!gst_buffer_map(buf, &info, GST_MAP_READ)) {
        goto CleanUp;
    }

    delta = GST_BUFFER_FLAG_IS_SET(buf, GST_BUFFER_FLAG_DELTA_UNIT);

    switch (pGstKvsPlugin->mediaType) {
        case GST_PLUGIN_MEDIA_TYPE_AUDIO_ONLY:
        case GST_PLUGIN_MEDIA_TYPE_VIDEO_ONLY:
            if (!delta) {
                frameFlags = FRAME_FLAG_KEY_FRAME;
            }
            break;
        case GST_PLUGIN_MEDIA_TYPE_AUDIO_VIDEO:
            if (!delta && pTrackData->trackType == MKV_TRACK_INFO_TYPE_VIDEO) {
                frameFlags = FRAME_FLAG_KEY_FRAME;
            }
            break;
    }

    if (!IS_OFFLINE_STREAMING_MODE(pGstKvsPlugin->gstParams.streamingType)) {
        if (pGstKvsPlugin->firstPts == GST_CLOCK_TIME_NONE) {
            pGstKvsPlugin->firstPts = buf->pts;
        }

        if (pGstKvsPlugin->producerStartTime == GST_CLOCK_TIME_NONE) {
            pGstKvsPlugin->producerStartTime = GETTIME() * DEFAULT_TIME_UNIT_IN_NANOS;
        }

        buf->pts += pGstKvsPlugin->producerStartTime - pGstKvsPlugin->firstPts;
    }

    frame.version = FRAME_CURRENT_VERSION;
    frame.flags = frameFlags;
    frame.index = pGstKvsPlugin->frameCount;
    frame.decodingTs = buf->dts / DEFAULT_TIME_UNIT_IN_NANOS;
    frame.presentationTs = buf->pts / DEFAULT_TIME_UNIT_IN_NANOS;
    frame.trackId = trackId;
    frame.size = info.size;
    frame.frameData = info.data;
    frame.duration = 0;

    if (ATOMIC_LOAD_BOOL(&pGstKvsPlugin->enableStreaming)) {
        if (STATUS_FAILED(status = putKinesisVideoFrame(pGstKvsPlugin->kvsContext.streamHandle, &frame))) {
            DLOGW("Failed to put frame with 0x%08x", status);
        }
    }

    // Need to produce the frame into peer connections
    // Check whether the frame is in AvCC/HEVC and set the flag to adapt the
    // bits to Annex-B format for RTP
    if (STATUS_FAILED(status = putFrameToWebRtcPeers(pGstKvsPlugin, &frame, pGstKvsPlugin->detectedCpdFormat))) {
        DLOGW("Failed to put frame to peer connections with 0x%08x", status);
    }

    pGstKvsPlugin->frameCount++;

CleanUp:

    if (info.data != NULL) {
        gst_buffer_unmap(buf, &info);
    }

    if (buf != NULL) {
        gst_buffer_unref(buf);
    }

    return ret;
}

GstPad* gst_kvs_plugin_request_new_pad(GstElement* element, GstPadTemplate* templ, const gchar* req_name, const GstCaps* caps)
{
    GstElementClass* klass = GST_ELEMENT_GET_CLASS(element);
    PGstKvsPlugin pGstKvsPlugin = GST_KVS_PLUGIN(element);

    gchar* name = NULL;
    GstPad* newpad = NULL;
    const gchar* padName = NULL;
    MKV_TRACK_INFO_TYPE trackType = MKV_TRACK_INFO_TYPE_VIDEO;
    gboolean locked = TRUE;
    PGstKvsPluginTrackData pTrackData;

    if (req_name != NULL) {
        GST_WARNING_OBJECT(pGstKvsPlugin, "Custom pad name not supported");
    }

    // Check if the pad template is supported
    if (templ == gst_element_class_get_pad_template(klass, "audio_%u")) {
        if (pGstKvsPlugin->numAudioStreams == 1) {
            GST_ERROR_OBJECT(pGstKvsPlugin, "Can not have more than one audio stream.");
            goto CleanUp;
        }

        name = g_strdup_printf("audio_%u", pGstKvsPlugin->numAudioStreams++);
        padName = name;
        trackType = MKV_TRACK_INFO_TYPE_AUDIO;

    } else if (templ == gst_element_class_get_pad_template(klass, "video_%u")) {
        if (pGstKvsPlugin->numVideoStreams == 1) {
            GST_ERROR_OBJECT(pGstKvsPlugin, "Can not have more than one video stream.");
            goto CleanUp;
        }

        name = g_strdup_printf("video_%u", pGstKvsPlugin->numVideoStreams++);
        padName = name;
        trackType = MKV_TRACK_INFO_TYPE_VIDEO;

    } else {
        GST_WARNING_OBJECT(pGstKvsPlugin, "Invalid template!");
        goto CleanUp;
    }

    if (pGstKvsPlugin->numVideoStreams > 0 && pGstKvsPlugin->numAudioStreams > 0) {
        pGstKvsPlugin->mediaType = GST_PLUGIN_MEDIA_TYPE_AUDIO_VIDEO;
    } else if (pGstKvsPlugin->numVideoStreams > 0) {
        pGstKvsPlugin->mediaType = GST_PLUGIN_MEDIA_TYPE_VIDEO_ONLY;
    } else {
        pGstKvsPlugin->mediaType = GST_PLUGIN_MEDIA_TYPE_AUDIO_ONLY;
    }

    newpad = GST_PAD_CAST(g_object_new(GST_TYPE_PAD, "name", padName, "direction", templ->direction, "template", templ, NULL));

    pTrackData =
        (PGstKvsPluginTrackData) gst_collect_pads_add_pad(pGstKvsPlugin->collect, GST_PAD(newpad), SIZEOF(GstKvsPluginTrackData), NULL, locked);

    pTrackData->pGstKvsPlugin = pGstKvsPlugin;
    pTrackData->trackType = trackType;
    pTrackData->trackId = DEFAULT_VIDEO_TRACK_ID;

    if (!gst_element_add_pad(element, GST_PAD(newpad))) {
        gst_object_unref(newpad);
        newpad = NULL;
        GST_WARNING_OBJECT(pGstKvsPlugin, "Adding the new pad '%s' failed", padName);
        goto CleanUp;
    }

    pGstKvsPlugin->numStreams++;

    DLOGD("Added new request pad");

CleanUp:

    g_free(name);
    return newpad;
}

VOID gst_kvs_plugin_release_pad(GstElement* element, GstPad* pad)
{
    PGstKvsPlugin pGstKvsPlugin = GST_KVS_PLUGIN(GST_PAD_PARENT(pad));
    GSList* walk;

    if (pGstKvsPlugin == NULL) {
        return;
    }

    // when a pad is released, check whether it's audio or video and keep track of the stream count
    for (walk = pGstKvsPlugin->collect->data; walk != NULL; walk = g_slist_next(walk)) {
        GstCollectData* cData;
        cData = (GstCollectData*) walk->data;

        if (cData->pad == pad) {
            PGstKvsPluginTrackData trackData;
            trackData = (PGstKvsPluginTrackData) walk->data;
            if (trackData->trackType == MKV_TRACK_INFO_TYPE_VIDEO) {
                pGstKvsPlugin->numVideoStreams--;
            } else if (trackData->trackType == MKV_TRACK_INFO_TYPE_AUDIO) {
                pGstKvsPlugin->numAudioStreams--;
            }
        }
    }

    gst_collect_pads_remove_pad(pGstKvsPlugin->collect, pad);
    if (gst_element_remove_pad(element, pad)) {
        pGstKvsPlugin->numStreams--;
    }
}

GstStateChangeReturn gst_kvs_plugin_change_state(GstElement* element, GstStateChange transition)
{
    GstStateChangeReturn ret = GST_STATE_CHANGE_SUCCESS;
    PGstKvsPlugin pGstKvsPlugin = GST_KVS_PLUGIN(element);
    STATUS status = STATUS_SUCCESS;

    switch (transition) {
        case GST_STATE_CHANGE_NULL_TO_READY:
            if (STATUS_FAILED(status = initKinesisVideoStructs(pGstKvsPlugin))) {
                DLOGE("Failed to initialize KVS structures with 0x%08x", status);
                ret = GST_STATE_CHANGE_FAILURE;
                goto CleanUp;
            }

            if (STATUS_FAILED(status = initKinesisVideoProducer(pGstKvsPlugin))) {
                DLOGE("Failed to initialize KVS producer with 0x%08x", status);
                ret = GST_STATE_CHANGE_FAILURE;
                goto CleanUp;
            }
            if (STATUS_FAILED(status = initTrackData(pGstKvsPlugin))) {
                DLOGE("Failed to initialize track with 0x%08x", status);
                ret = GST_STATE_CHANGE_FAILURE;
                goto CleanUp;
            }

            pGstKvsPlugin->firstPts = GST_CLOCK_TIME_NONE;
            pGstKvsPlugin->producerStartTime = GST_CLOCK_TIME_NONE;

            if (STATUS_FAILED(status = initKinesisVideoStream(pGstKvsPlugin))) {
                DLOGE("Failed to initialize KVS stream with 0x%08x", status);
                ret = GST_STATE_CHANGE_FAILURE;
                goto CleanUp;
            }

            ATOMIC_STORE_BOOL(&pGstKvsPlugin->streamStopped, FALSE);
            pGstKvsPlugin->streamStatus = STATUS_SUCCESS;
            pGstKvsPlugin->lastDts = 0;
            pGstKvsPlugin->basePts = 0;
            pGstKvsPlugin->frameCount = 0;

            pGstKvsPlugin->detectedCpdFormat = ELEMENTARY_STREAM_NAL_FORMAT_UNKNOWN;

            // This needs to happen after we've read in ALL of the properties
            if (!pGstKvsPlugin->gstParams.disableBufferClipping) {
                gst_collect_pads_set_clip_function(pGstKvsPlugin->collect, GST_DEBUG_FUNCPTR(gst_collect_pads_clip_running_time), pGstKvsPlugin);
            }

            if (STATUS_FAILED(status = initKinesisVideoWebRtc(pGstKvsPlugin))) {
                DLOGE("Failed to initialize KVS signaling client with 0x%08x", status);
                ret = GST_STATE_CHANGE_FAILURE;
                goto CleanUp;
            }

            // Schedule the WebRTC master session servicing periodic routine
            if (STATUS_FAILED(status = timerQueueAddTimer(pGstKvsPlugin->kvsContext.timerQueueHandle, GST_PLUGIN_SERVICE_ROUTINE_START,
                                                          GST_PLUGIN_SERVICE_ROUTINE_PERIOD, sessionServiceHandler, (UINT64) pGstKvsPlugin,
                                                          &pGstKvsPlugin->serviceRoutineTimerId))) {
                DLOGE("Failed to schedule WebRTC service routine with 0x%08x", status);
                ret = GST_STATE_CHANGE_FAILURE;
                goto CleanUp;
            }

            break;
        case GST_STATE_CHANGE_READY_TO_PAUSED:
            gst_collect_pads_start(pGstKvsPlugin->collect);
            break;
        case GST_STATE_CHANGE_PAUSED_TO_READY:
            gst_collect_pads_stop(pGstKvsPlugin->collect);
            break;
        default:
            break;
    }

    ret = GST_ELEMENT_CLASS(parent_class)->change_state(element, transition);

CleanUp:

    if (ret != GST_STATE_CHANGE_SUCCESS) {
        GST_ELEMENT_ERROR(pGstKvsPlugin, LIBRARY, INIT, (NULL), ("Failed to initialize with 0x%08x", status));
    }

    return ret;
}

GST_DEBUG_CATEGORY(kvs_debug);

static gboolean plugin_init(GstPlugin* plugin)
{
    if (!gst_element_register(plugin, "kvsplugin", GST_RANK_PRIMARY + 10, GST_TYPE_KVS_PLUGIN)) {
        return FALSE;
    }

    GST_DEBUG_CATEGORY_INIT(kvs_debug, "kvs", 0, "KVS plugin elements");
    return TRUE;
}

#define PACKAGE "kvspluginpackage"
GST_PLUGIN_DEFINE(GST_VERSION_MAJOR, GST_VERSION_MINOR, kvsplugin, "GStreamer AWS KVS plugin", plugin_init, "1.0", "Proprietary", "GStreamer",
                  "http://gstreamer.net/")
