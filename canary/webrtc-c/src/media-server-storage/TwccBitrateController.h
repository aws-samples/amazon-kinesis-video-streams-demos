/*******************************************
 * TWCC bitrate controller — pure AIMD policy
 *
 * Media-agnostic, side-effect-free computation of new video/audio encoder
 * targets from TWCC congestion signals. Mirrors the AIMD logic in the SDK
 * sample (samples/common/Common.c) but as a standalone, unit-testable function:
 * it holds no state, touches no GStreamer/CloudWatch/SDK session types, and
 * takes the bitrate bounds as parameters. The caller (Common.cpp) owns the
 * stateful bits (EMA of loss, adjustment-interval gating, the lock-guarded
 * hand-off to the media thread).
 *******************************************/
#pragma once

#include <com/amazonaws/kinesis/video/webrtcclient/Include.h>

typedef struct {
    UINT64 newVideoBitrateKbps; // kilobits/sec
    UINT64 newAudioBitrateBps;  // bits/sec
} AdaptedBitrate;

/**
 * Combine loss-based and delay-based congestion signals into new encoder
 * targets, clamped to [min,max] for each track.
 *
 * @param curVideoKbps  current video encoder bitrate (kbps)
 * @param curAudioBps   current audio encoder bitrate (bps)
 * @param avgLossPct    EMA-smoothed packet-loss percentage
 * @param delayTrendMs  TWCC delay-trend (ms): >0 queue building, <0 draining
 * @param minVideoKbps  video floor (kbps)
 * @param maxVideoKbps  video ceiling (kbps)
 * @param minAudioBps   audio floor (bps)
 * @param maxAudioBps   audio ceiling (bps)
 */
AdaptedBitrate computeAdaptedBitrate(UINT64 curVideoKbps, UINT64 curAudioBps, DOUBLE avgLossPct, DOUBLE delayTrendMs, UINT32 minVideoKbps,
                                     UINT32 maxVideoKbps, UINT32 minAudioBps, UINT32 maxAudioBps);
