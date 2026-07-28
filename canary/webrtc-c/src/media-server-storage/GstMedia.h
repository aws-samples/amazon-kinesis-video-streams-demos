/*******************************************
GStreamer media source for the media-server-storage canary.

Lets the storage canary ingest live media (physical camera / RTSP / GStreamer
test source) instead of the pre-encoded frame files on disk. Ported from the
WebRTC C SDK sample media source (samples/common/GstMedia.c) with the canary
CloudWatch metric hooks spliced into the frame send path.

Only compiled when the build is configured with -DENABLE_GST_MEDIA_SOURCE=ON.
*******************************************/
#pragma once

#include "Samples.h"

#include <gst/gst.h>
#include <gst/app/gstappsink.h>

// Selects the media source. Unset/empty/"disk" keeps the existing disk-frame
// behavior. Other values: "testsrc", "devicesrc" (camera + mic), "rtspsrc".
#define CANARY_MEDIA_SOURCE_ENV_VAR (PCHAR) "CANARY_MEDIA_SOURCE"

// RTSP URI, required when CANARY_MEDIA_SOURCE=rtspsrc.
#define CANARY_RTSP_URI_ENV_VAR (PCHAR) "CANARY_RTSP_URI"

// Full custom GStreamer pipeline override (takes precedence over the built-in
// pipelines). Must contain an appsink named "appsink-video" producing
// video/x-h264,stream-format=byte-stream,alignment=au and, for audio, an
// appsink named "appsink-audio" producing audio/x-opus,rate=48000,channels=2.
// Useful on Raspberry Pi where the CSI camera needs libcamerasrc, e.g.:
//   libcamerasrc ! video/x-raw,width=1280,height=720,framerate=30/1 ! \
//   v4l2h264enc ! h264parse ! video/x-h264,stream-format=byte-stream,alignment=au ! \
//   appsink sync=TRUE emit-signals=TRUE name=appsink-video
#define CANARY_GST_CUSTOM_PIPELINE_ENV_VAR (PCHAR) "CANARY_GST_CUSTOM_PIPELINE"

#define GST_PIPELINE_MAX_CHAR_COUNT 2048

// Initializes GStreamer and plugs sendGstreamerAudioVideo in as the video
// source (a single thread drives both the video and audio appsinks, so
// audioSource is left NULL).
STATUS useGstreamer(PSampleConfiguration pSampleConfiguration);

// Resolves srcType/rtspUri from CANARY_MEDIA_SOURCE / CANARY_RTSP_URI.
STATUS gstParseSrcTypeFromEnv(PSampleConfiguration pSampleConfiguration);

GstFlowReturn on_new_sample_video(GstElement* sink, gpointer data);
GstFlowReturn on_new_sample_audio(GstElement* sink, gpointer data);
