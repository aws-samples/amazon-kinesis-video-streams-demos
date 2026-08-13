#include "TwccBitrateController.h"

// AIMD policy mirroring the SDK sample (samples/common/Common.c). Kept pure so
// it can be unit-tested in isolation:
//
//   averagePacketLoss (EMA) | lossFactor        delayTrendMs (ms) | delayFactor
//   ------------------------+-----------        ------------------+------------
//   > 10.0%                 | 0.70              > 5.0             | 0.50
//   > 5.0% and <= 10.0%     | 0.85              > 1.0 and <= 5.0  | 0.70
//   <= 5.0%                 | 1.00              > 0.5 and <= 1.0  | 0.95
//                                               <= 0.5            | 1.00
//
// Multiplicative decrease (take MIN of the two factors) when congested;
// additive increase (video by MAX/40 or MAX/80, audio by MAX/20) when clear;
// hold otherwise. Results clamped to [min,max] for each track.
AdaptedBitrate computeAdaptedBitrate(UINT64 curVideoKbps, UINT64 curAudioBps, DOUBLE avgLossPct, DOUBLE delayTrendMs, UINT32 minVideoKbps,
                                     UINT32 maxVideoKbps, UINT32 minAudioBps, UINT32 maxAudioBps)
{
    UINT64 videoBitrate = curVideoKbps;
    UINT64 audioBitrate = curAudioBps;

    BOOL lossCongested = avgLossPct > 5.0;
    BOOL delayCongested = delayTrendMs > 0.5;
    BOOL lossClear = avgLossPct <= 2.0;
    BOOL delayClear = delayTrendMs < -0.1;

    DOUBLE factor = 1.0;
    if (lossCongested || delayCongested) {
        // Multiplicative decrease: take the more aggressive of the two signals.
        DOUBLE lossFactor = 1.0;
        DOUBLE delayFactor = 1.0;
        if (avgLossPct > 10.0) {
            lossFactor = 0.7;
        } else if (lossCongested) {
            lossFactor = 0.85;
        }
        if (delayTrendMs > 5.0) {
            delayFactor = 0.5;
        } else if (delayTrendMs > 1.0) {
            delayFactor = 0.7;
        } else if (delayCongested) {
            delayFactor = 0.95;
        }
        factor = MIN(lossFactor, delayFactor);
    } else if (lossClear && delayClear) {
        // Strong additive increase.
        videoBitrate = MIN(videoBitrate + maxVideoKbps / 40, (UINT64) maxVideoKbps);
        factor = 0; // signal that additive increase was used
    } else if (lossClear || delayClear) {
        // Cautious additive increase.
        videoBitrate = MIN(videoBitrate + maxVideoKbps / 80, (UINT64) maxVideoKbps);
        factor = 0;
    }
    // else: both neutral -> hold (factor = 1.0)

    if (factor > 0) {
        videoBitrate = (UINT64) MAX(MIN(videoBitrate * factor, (DOUBLE) maxVideoKbps), (DOUBLE) minVideoKbps);
    } else {
        videoBitrate = (UINT64) MAX(videoBitrate, (UINT64) minVideoKbps);
    }
    if (factor > 0) {
        audioBitrate = (UINT64) MAX(MIN(audioBitrate * factor, (DOUBLE) maxAudioBps), (DOUBLE) minAudioBps);
    } else {
        audioBitrate = MIN(audioBitrate + maxAudioBps / 20, (UINT64) maxAudioBps);
        audioBitrate = (UINT64) MAX(audioBitrate, (UINT64) minAudioBps);
    }

    AdaptedBitrate result;
    result.newVideoBitrateKbps = videoBitrate;
    result.newAudioBitrateBps = audioBitrate;
    return result;
}
