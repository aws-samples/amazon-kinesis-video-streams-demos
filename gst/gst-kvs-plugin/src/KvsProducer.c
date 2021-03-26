#define LOG_CLASS "KvsProducer"
#include "GstPlugin.h"

STATUS traverseDirectoryPemFileScan(UINT64 customData, DIR_ENTRY_TYPES entryType, PCHAR fullPath, PCHAR fileName)
{
    UNUSED_PARAM(entryType);
    UNUSED_PARAM(fullPath);

    PCHAR certName = (PCHAR) customData;
    UINT32 fileNameLen = STRLEN(fileName);

    if (fileNameLen > ARRAY_SIZE(CA_CERT_PEM_FILE_EXTENSION) + 1 &&
        (STRCMPI(CA_CERT_PEM_FILE_EXTENSION, &fileName[fileNameLen - ARRAY_SIZE(CA_CERT_PEM_FILE_EXTENSION) + 1]) == 0)) {
        certName[0] = FPATHSEPARATOR;
        certName++;
        STRCPY(certName, fileName);
    }

    return STATUS_SUCCESS;
}

STATUS lookForSslCert(PGstKvsPlugin pGstKvsPlugin)
{
    STATUS retStatus = STATUS_SUCCESS;
    struct stat pathStat;
    PCHAR pCaCertPath = NULL;
    CHAR certName[MAX_PATH_LEN];

    CHK(pGstKvsPlugin != NULL, STATUS_NULL_ARG);

    MEMSET(certName, 0x0, ARRAY_SIZE(certName));
    pCaCertPath = GETENV(CACERT_PATH_ENV_VAR);

    // if ca cert path is not set from the environment, try to use the one that cmake detected
    if (pCaCertPath == NULL) {
        CHK_ERR(STRNLEN(DEFAULT_KVS_CACERT_PATH, MAX_PATH_LEN) > 0, STATUS_INVALID_OPERATION, "No ca cert path given (error:%s)", strerror(errno));
        STRNCPY(pGstKvsPlugin->caCertPath, DEFAULT_KVS_CACERT_PATH, MAX_PATH_LEN);
    } else {
        // Copy the env path into local dir
        STRNCPY(pGstKvsPlugin->caCertPath, pCaCertPath, MAX_PATH_LEN);
        pCaCertPath = pGstKvsPlugin->caCertPath;

        // Check if the environment variable is a path
        CHK(0 == FSTAT(pCaCertPath, &pathStat), STATUS_DIRECTORY_ENTRY_STAT_ERROR);

        if (S_ISDIR(pathStat.st_mode)) {
            CHK_STATUS(traverseDirectory(pCaCertPath, (UINT64) &certName, /* iterate */ FALSE, traverseDirectoryPemFileScan));

            if (certName[0] != 0x0) {
                STRCAT(pCaCertPath, certName);
            } else {
                DLOGW("Cert not found in path set...checking if CMake detected a path\n");
                CHK_ERR(STRNLEN(DEFAULT_KVS_CACERT_PATH, MAX_PATH_LEN) > 0, STATUS_INVALID_OPERATION, "No ca cert path given (error:%s)",
                        strerror(errno));
                DLOGD("CMake detected cert path\n");
                pCaCertPath = DEFAULT_KVS_CACERT_PATH;
            }
        }
    }

CleanUp:

    CHK_LOG_ERR(retStatus);
    return retStatus;
}

STATUS initTrackData(PGstKvsPlugin pGstKvsPlugin)
{
    STATUS retStatus = STATUS_SUCCESS;
    GSList* walk;
    GstCaps* caps;
    gchar* videoContentType = NULL;
    gchar* audioContentType = NULL;
    const gchar* mediaType;

    for (walk = pGstKvsPlugin->collect->data; walk != NULL; walk = g_slist_next(walk)) {
        PGstKvsPluginTrackData pTrackData = (PGstKvsPluginTrackData) walk->data;

        if (pTrackData->trackType == MKV_TRACK_INFO_TYPE_VIDEO) {
            if (pGstKvsPlugin->mediaType == GST_PLUGIN_MEDIA_TYPE_AUDIO_VIDEO) {
                pTrackData->trackId = DEFAULT_VIDEO_TRACK_ID;
            }

            GstCollectData* collect_data = (GstCollectData*) walk->data;

            // extract media type from GstCaps to check whether it's h264 or h265
            caps = gst_pad_get_allowed_caps(collect_data->pad);
            mediaType = gst_structure_get_name(gst_caps_get_structure(caps, 0));
            if (STRNCMP(mediaType, GSTREAMER_MEDIA_TYPE_H264, MAX_GSTREAMER_MEDIA_TYPE_LEN) == 0) {
                // default codec id is for h264 video.
                videoContentType = g_strdup(MKV_H264_CONTENT_TYPE);
            } else if (STRNCMP(mediaType, GSTREAMER_MEDIA_TYPE_H265, MAX_GSTREAMER_MEDIA_TYPE_LEN) == 0) {
                g_free(pGstKvsPlugin->gstParams.codecId);
                pGstKvsPlugin->gstParams.codecId = g_strdup(DEFAULT_CODEC_ID_H265);
                videoContentType = g_strdup(MKV_H265_CONTENT_TYPE);
            } else {
                // no-op, should result in a caps negotiation error before getting here.
                DLOGE("Error, media type %s not accepted by plugin", mediaType);
                CHK(FALSE, STATUS_INVALID_ARG);
            }
            gst_caps_unref(caps);

        } else if (pTrackData->trackType == MKV_TRACK_INFO_TYPE_AUDIO) {
            if (pGstKvsPlugin->mediaType == GST_PLUGIN_MEDIA_TYPE_AUDIO_VIDEO) {
                pTrackData->trackId = DEFAULT_AUDIO_TRACK_ID;
            }

            GstCollectData* collect_data = (GstCollectData*) walk->data;

            // extract media type from GstCaps to check whether it's h264 or h265
            caps = gst_pad_get_allowed_caps(collect_data->pad);
            mediaType = gst_structure_get_name(gst_caps_get_structure(caps, 0));
            if (STRNCMP(mediaType, GSTREAMER_MEDIA_TYPE_AAC, MAX_GSTREAMER_MEDIA_TYPE_LEN) == 0) {
                // default codec id is for aac audio.
                audioContentType = g_strdup(MKV_AAC_CONTENT_TYPE);
            } else if (STRNCMP(mediaType, GSTREAMER_MEDIA_TYPE_ALAW, MAX_GSTREAMER_MEDIA_TYPE_LEN) == 0) {
                g_free(pGstKvsPlugin->audioCodecId);
                pGstKvsPlugin->audioCodecId = g_strdup(DEFAULT_AUDIO_CODEC_ID_PCM);
                audioContentType = g_strdup(MKV_ALAW_CONTENT_TYPE);
            } else if (STRNCMP(mediaType, GSTREAMER_MEDIA_TYPE_MULAW, MAX_GSTREAMER_MEDIA_TYPE_LEN) == 0) {
                g_free(pGstKvsPlugin->audioCodecId);
                pGstKvsPlugin->audioCodecId = g_strdup(DEFAULT_AUDIO_CODEC_ID_PCM);
                audioContentType = g_strdup(MKV_MULAW_CONTENT_TYPE);
            } else {
                // no-op, should result in a caps negotiation error before getting here.
                DLOGE("Error, media type %s not accepted by plugin", mediaType);
                CHK(FALSE, STATUS_INVALID_ARG);
            }

            gst_caps_unref(caps);
        }
    }

    switch (pGstKvsPlugin->mediaType) {
        case GST_PLUGIN_MEDIA_TYPE_AUDIO_VIDEO:
            pGstKvsPlugin->gstParams.audioContentType = g_strdup(audioContentType);
            pGstKvsPlugin->gstParams.videoContentType = g_strdup(videoContentType);
            pGstKvsPlugin->gstParams.contentType = g_strjoin(",", videoContentType, audioContentType, NULL);
            break;
        case GST_PLUGIN_MEDIA_TYPE_AUDIO_ONLY:
            pGstKvsPlugin->gstParams.audioContentType = g_strdup(audioContentType);
            pGstKvsPlugin->gstParams.contentType = g_strdup(audioContentType);
            break;
        case GST_PLUGIN_MEDIA_TYPE_VIDEO_ONLY:
            pGstKvsPlugin->gstParams.contentType = g_strdup(videoContentType);
            pGstKvsPlugin->gstParams.videoContentType = g_strdup(videoContentType);
            break;
    }

CleanUp:

    g_free(videoContentType);
    g_free(audioContentType);

    return retStatus;
}

STATUS initKinesisVideoStream(PGstKvsPlugin pGstPlugin)
{
    STATUS retStatus = STATUS_SUCCESS;
    GstTags gstTags;

    CHK(pGstPlugin != NULL, STATUS_NULL_ARG);

    CHK_STATUS(gstStructToTags(pGstPlugin->gstParams.streamTags, &gstTags));

    // If doing offline mode file uploading, and the user wants to use a specific file start time,
    // then force absolute fragment time. Since we will be adding the file_start_time to the timestamp
    // of each frame to make each frame's timestamp absolute. Assuming each frame's timestamp is relative
    // (i.e. starting from 0)
    if (pGstPlugin->gstParams.streamingType == STREAMING_TYPE_OFFLINE && pGstPlugin->gstParams.fileStartTime != 0) {
        pGstPlugin->gstParams.absoluteFragmentTimecodes = TRUE;

        // Store the base of the PTS which will be the file start time.
        // NOTE: file start time is given in seconds.
        pGstPlugin->basePts = pGstPlugin->gstParams.fileStartTime / HUNDREDS_OF_NANOS_IN_A_SECOND;
    }

    switch (pGstPlugin->mediaType) {
        case GST_PLUGIN_MEDIA_TYPE_AUDIO_VIDEO:
            pGstPlugin->gstParams.frameRate = MAX(pGstPlugin->gstParams.frameRate, DEFAULT_STREAM_FRAMERATE_HIGH_DENSITY);
            CHK_STATUS(createRealtimeAudioVideoStreamInfoProvider(
                pGstPlugin->gstParams.streamName, pGstPlugin->gstParams.retentionPeriodInHours * HUNDREDS_OF_NANOS_IN_AN_HOUR,
                pGstPlugin->gstParams.bufferDurationInSeconds * HUNDREDS_OF_NANOS_IN_A_SECOND, &pGstPlugin->kvsContext.pStreamInfo));
            break;
        case GST_PLUGIN_MEDIA_TYPE_AUDIO_ONLY:
            g_free(pGstPlugin->gstParams.codecId);
            pGstPlugin->gstParams.codecId = pGstPlugin->audioCodecId;
            pGstPlugin->gstParams.keyFrameFragmentation = FALSE;
            pGstPlugin->gstParams.frameRate = MAX(pGstPlugin->gstParams.frameRate, DEFAULT_STREAM_FRAMERATE_HIGH_DENSITY);

            // Explicit fall-through
        case GST_PLUGIN_MEDIA_TYPE_VIDEO_ONLY:

            CHK_STATUS(createRealtimeVideoStreamInfoProvider(
                pGstPlugin->gstParams.streamName, pGstPlugin->gstParams.retentionPeriodInHours * HUNDREDS_OF_NANOS_IN_AN_HOUR,
                pGstPlugin->gstParams.bufferDurationInSeconds * HUNDREDS_OF_NANOS_IN_A_SECOND, &pGstPlugin->kvsContext.pStreamInfo));
            break;
    }

    pGstPlugin->kvsContext.pStreamInfo->tagCount = gstTags.tagCount;
    pGstPlugin->kvsContext.pStreamInfo->tags = gstTags.tags;
    STRNCPY(pGstPlugin->kvsContext.pStreamInfo->kmsKeyId, pGstPlugin->gstParams.kmsKeyId, MAX_ARN_LEN);
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.streamingType = pGstPlugin->gstParams.streamingType;
    STRNCPY(pGstPlugin->kvsContext.pStreamInfo->streamCaps.contentType, pGstPlugin->gstParams.contentType, MAX_CONTENT_TYPE_LEN);

    // Need to reset the NAL adaptation flags as we take care of it later with the first frame
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.nalAdaptationFlags = NAL_ADAPTATION_FLAG_NONE;

    // Override only if specified
    if (pGstPlugin->gstParams.maxLatencyInSeconds != DEFAULT_MAX_LATENCY_SECONDS) {
        pGstPlugin->kvsContext.pStreamInfo->streamCaps.maxLatency = pGstPlugin->gstParams.maxLatencyInSeconds * HUNDREDS_OF_NANOS_IN_A_SECOND;
    }

    pGstPlugin->kvsContext.pStreamInfo->streamCaps.fragmentDuration =
        pGstPlugin->gstParams.fragmentDurationInMillis * HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.timecodeScale = pGstPlugin->gstParams.timeCodeScaleInMillis * HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.keyFrameFragmentation = pGstPlugin->gstParams.keyFrameFragmentation;
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.frameTimecodes = pGstPlugin->gstParams.frameTimecodes;
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.absoluteFragmentTimes = pGstPlugin->gstParams.absoluteFragmentTimecodes;
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.fragmentAcks = pGstPlugin->gstParams.fragmentAcks;
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.recoverOnError = pGstPlugin->gstParams.restartOnErrors;
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.frameRate = pGstPlugin->gstParams.frameRate;
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.avgBandwidthBps = pGstPlugin->gstParams.avgBandwidthBps;
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.recalculateMetrics = pGstPlugin->gstParams.recalculateMetrics;
    pGstPlugin->kvsContext.pStreamInfo->streamCaps.nalAdaptationFlags = NAL_ADAPTATION_FLAG_NONE;

    // Override only if specified
    if (pGstPlugin->gstParams.replayDurationInSeconds != DEFAULT_REPLAY_DURATION_SECONDS) {
        pGstPlugin->kvsContext.pStreamInfo->streamCaps.replayDuration = pGstPlugin->gstParams.replayDurationInSeconds * HUNDREDS_OF_NANOS_IN_A_SECOND;
    }

    // Override only if specified
    if (pGstPlugin->gstParams.connectionStalenessInSeconds != DEFAULT_CONNECTION_STALENESS_SECONDS) {
        pGstPlugin->kvsContext.pStreamInfo->streamCaps.connectionStalenessDuration =
            pGstPlugin->gstParams.connectionStalenessInSeconds * HUNDREDS_OF_NANOS_IN_A_SECOND;
    }

    // Replace the video codecId
    STRNCPY(pGstPlugin->kvsContext.pStreamInfo->streamCaps.trackInfoList[0].codecId, pGstPlugin->gstParams.codecId, MKV_MAX_CODEC_ID_LEN);

    // Deal with audio
    if (pGstPlugin->mediaType == GST_PLUGIN_MEDIA_TYPE_AUDIO_VIDEO) {
        STRNCPY(pGstPlugin->kvsContext.pStreamInfo->streamCaps.trackInfoList[1].codecId, pGstPlugin->audioCodecId, MKV_MAX_CODEC_ID_LEN);
    }

    CHK_STATUS(
        createKinesisVideoStreamSync(pGstPlugin->kvsContext.clientHandle, pGstPlugin->kvsContext.pStreamInfo, &pGstPlugin->kvsContext.streamHandle));

    pGstPlugin->frameCount = 0;

    DLOGI("Stream is ready");

CleanUp:

    return retStatus;
}

STATUS initKinesisVideoProducer(PGstKvsPlugin pGstPlugin)
{
    STATUS retStatus = STATUS_SUCCESS;
    PAuthCallbacks pAuthCallbacks;
    PStreamCallbacks pStreamCallbacks;
    BOOL freeStreamCallbacksOnError = TRUE;

    CHK(pGstPlugin != NULL, STATUS_NULL_ARG);

    CHK_STATUS(createDefaultDeviceInfo(&pGstPlugin->kvsContext.pDeviceInfo));

    // Set the overrides if specified
    if (pGstPlugin->gstParams.logLevel != LOG_LEVEL_SILENT + 1) {
        pGstPlugin->kvsContext.pDeviceInfo->clientInfo.loggerLogLevel = pGstPlugin->gstParams.logLevel;
    }

    pGstPlugin->kvsContext.pDeviceInfo->clientInfo.createStreamTimeout =
        pGstPlugin->gstParams.streamCreateTimeoutInSeconds * HUNDREDS_OF_NANOS_IN_A_SECOND;
    pGstPlugin->kvsContext.pDeviceInfo->clientInfo.stopStreamTimeout =
        pGstPlugin->gstParams.streamStopTimeoutInSeconds * HUNDREDS_OF_NANOS_IN_A_SECOND;

    CHK_STATUS(createAbstractDefaultCallbacksProvider(DEFAULT_CALLBACK_CHAIN_COUNT, API_CALL_CACHE_TYPE_ALL, DEFAULT_API_CACHE_PERIOD,
                                                      pGstPlugin->pRegion, EMPTY_STRING, pGstPlugin->caCertPath, KVS_PRODUCER_CLIENT_USER_AGENT_NAME,
                                                      NULL, &pGstPlugin->kvsContext.pClientCallbacks));

    CHK_STATUS(createContinuousRetryStreamCallbacks(pGstPlugin->kvsContext.pClientCallbacks, &pStreamCallbacks));
    freeStreamCallbacksOnError = FALSE;

    CHK_STATUS(
        createCredentialProviderAuthCallbacks(pGstPlugin->kvsContext.pClientCallbacks, pGstPlugin->kvsContext.pCredentialProvider, &pAuthCallbacks));

    CHK_STATUS(
        createKinesisVideoClient(pGstPlugin->kvsContext.pDeviceInfo, pGstPlugin->kvsContext.pClientCallbacks, &pGstPlugin->kvsContext.clientHandle));

CleanUp:

    CHK_LOG_ERR(retStatus);

    // We need to free up the continuous retry stream callbacks only on error and if
    // we didn't get a chance to add it to the callback chain as otherwise it would
    // be freed later when the provider is freed
    if (freeStreamCallbacksOnError && STATUS_FAILED(retStatus) && pStreamCallbacks != NULL) {
        freeContinuousRetryStreamCallbacks(&pStreamCallbacks);
    }

    return retStatus;
}

STATUS identifyFrameNalFormat(PBYTE pData, UINT32 size, ELEMENTARY_STREAM_NAL_FORMAT* pFormat)
{
    STATUS retStatus = STATUS_SUCCESS;
    PBYTE pEnd = pData + size;
    ELEMENTARY_STREAM_NAL_FORMAT format = ELEMENTARY_STREAM_NAL_FORMAT_UNKNOWN;
    BYTE start3ByteCode[] = {0x00, 0x00, 0x01};
    BYTE start4ByteCode[] = {0x00, 0x00, 0x00, 0x01};
    BYTE start5ByteCode[] = {0x00, 0x00, 0x00, 0x00, 0x01};
    UINT32 runLen;

    CHK(pData != NULL && pFormat != NULL, STATUS_NULL_ARG);
    CHK(size > SIZEOF(start5ByteCode), STATUS_FORMAT_ERROR);

    // We really do very crude check for the Annex-B start code

    // First of all, we need to determine what format the CPD is in - Annex-B, Avcc or raw
    // NOTE: Some "bad" encoders encode an extra 0 at the end of the NALu resulting in
    // an extra zero interfering with the Annex-B start code so we check for 4 zeroes and 1
    if ((0 == MEMCMP(pData, start5ByteCode, SIZEOF(start5ByteCode))) || (0 == MEMCMP(pData, start4ByteCode, SIZEOF(start4ByteCode))) ||
        (0 == MEMCMP(pData, start3ByteCode, SIZEOF(start3ByteCode)))) {
        // Must be an Annex-B format
        format = ELEMENTARY_STREAM_NAL_FORMAT_ANNEX_B;

        // Early exit
        CHK(FALSE, retStatus);
    }

    // For AvCC we will walk through all NALus
    while (pData != pEnd) {
        // Check if we can still read 32 bit
        CHK(pData + SIZEOF(UINT32) <= pEnd, retStatus);
        runLen = (UINT32) GET_UNALIGNED_BIG_ENDIAN((PUINT32) pData);
        CHK(runLen != 0 && pData + runLen <= pEnd, retStatus);

        // Jump to the next NAL
        pData += runLen + SIZEOF(UINT32);
    }

    // All checks, must be AvCC
    format = ELEMENTARY_STREAM_NAL_FORMAT_AVCC;

CleanUp:

    if (pFormat != NULL) {
        *pFormat = format;
    }

    return retStatus;
}

STATUS identifyCpdNalFormat(PBYTE pData, UINT32 size, ELEMENTARY_STREAM_NAL_FORMAT* pFormat)
{
    STATUS retStatus = STATUS_SUCCESS;
    ELEMENTARY_STREAM_NAL_FORMAT format = ELEMENTARY_STREAM_NAL_FORMAT_UNKNOWN;
    BYTE start3ByteCode[] = {0x00, 0x00, 0x01};
    BYTE start4ByteCode[] = {0x00, 0x00, 0x00, 0x01};
    BYTE start5ByteCode[] = {0x00, 0x00, 0x00, 0x00, 0x01};

    CHK(pData != NULL && pFormat != NULL, STATUS_NULL_ARG);
    CHK(size > SIZEOF(start5ByteCode), STATUS_FORMAT_ERROR);

    // We really do very crude check for the Annex-B start code

    // First of all, we need to determine what format the CPD is in - Annex-B, Avcc or raw
    // NOTE: Some "bad" encoders encode an extra 0 at the end of the NALu resulting in
    // an extra zero interfering with the Annex-B start code so we check for 4 zeroes and 1
    if ((0 == MEMCMP(pData, start5ByteCode, SIZEOF(start5ByteCode))) || (0 == MEMCMP(pData, start4ByteCode, SIZEOF(start4ByteCode))) ||
        (0 == MEMCMP(pData, start3ByteCode, SIZEOF(start3ByteCode)))) {
        // Must be an Annex-B format
        format = ELEMENTARY_STREAM_NAL_FORMAT_ANNEX_B;

        // Early exit
        CHK(FALSE, retStatus);
    } else if (pData[0] == AVCC_VERSION_CODE && pData[4] == AVCC_NALU_LEN_MINUS_ONE && pData[5] == AVCC_NUMBER_OF_SPS_ONE) {
        // Looks like an AvCC format
        format = ELEMENTARY_STREAM_NAL_FORMAT_AVCC;
    } else if (size > HEVC_CPD_HEADER_SIZE && pData[0] == 1 && (pData[13] & 0xf0) == 0xf0 && (pData[15] & 0xfc) == 0xfc &&
               (pData[16] & 0xfc) != 0xfc && (pData[17] & 0xf8) != 0xf8 && (pData[18] & 0xf8) != 0xf8) {
        // Looks like an HEVC format
        format = ELEMENTARY_STREAM_NAL_FORMAT_HEVC;
    }

CleanUp:

    if (pFormat != NULL) {
        *pFormat = format;
    }

    return retStatus;
}

STATUS convertCpdFromAvcToAnnexB(PGstKvsPlugin pGstKvsPlugin, PBYTE pData, UINT32 size)
{
    STATUS retStatus = STATUS_SUCCESS;
    UINT32 i, offset = 0;
    UINT16 spsSize, ppsSize;
    BYTE start4ByteCode[] = {0x00, 0x00, 0x00, 0x01};
    PBYTE pSrc = pData, pEnd = pData + size;

    CHK(pData != NULL && pGstKvsPlugin != NULL, STATUS_NULL_ARG);
    CHK(size > 8, STATUS_FORMAT_ERROR);

    // Skip to SPS size and read the nalu count
    pSrc += 6;
    spsSize = GET_UNALIGNED_BIG_ENDIAN((PUINT16) pSrc);
    pSrc += SIZEOF(UINT16);

    CHK(offset + SIZEOF(start4ByteCode) + spsSize < GST_PLUGIN_MAX_CPD_SIZE, STATUS_FORMAT_ERROR);
    CHK(pSrc + spsSize <= pEnd, STATUS_FORMAT_ERROR);

    // Output the Annex-B start code
    MEMCPY(pGstKvsPlugin->videoCpd + offset, start4ByteCode, SIZEOF(start4ByteCode));
    offset += SIZEOF(start4ByteCode);

    // Output the NALu
    MEMCPY(pGstKvsPlugin->videoCpd + offset, pSrc, spsSize);
    offset += spsSize;
    pSrc += spsSize;

    // Skip pps count
    pSrc++;

    // Read pps size
    CHK(pSrc + SIZEOF(UINT16) <= pEnd, STATUS_FORMAT_ERROR);
    ppsSize = GET_UNALIGNED_BIG_ENDIAN((PUINT16) pSrc);
    pSrc += SIZEOF(UINT16);

    CHK(offset + SIZEOF(start4ByteCode) + ppsSize < GST_PLUGIN_MAX_CPD_SIZE, STATUS_FORMAT_ERROR);
    CHK(pSrc + ppsSize <= pEnd, STATUS_FORMAT_ERROR);

    // Output the Annex-B start code
    MEMCPY(pGstKvsPlugin->videoCpd + offset, start4ByteCode, SIZEOF(start4ByteCode));
    offset += SIZEOF(start4ByteCode);

    // Output the NALu
    MEMCPY(pGstKvsPlugin->videoCpd + offset, pSrc, ppsSize);
    offset += ppsSize;

    pGstKvsPlugin->videoCpdSize = offset;

CleanUp:

    return retStatus;
}

STATUS convertCpdFromHevcToAnnexB(PGstKvsPlugin pGstKvsPlugin, PBYTE pData, UINT32 size)
{
    STATUS retStatus = STATUS_SUCCESS;
    UINT32 i, naluCount, offset = 0;
    UINT16 naluUnitLen;
    BYTE start4ByteCode[] = {0x00, 0x00, 0x00, 0x01};
    PBYTE pSrc = pData, pEnd = pData + size;

    CHK(pData != NULL && pGstKvsPlugin != NULL, STATUS_NULL_ARG);
    CHK(size > 23, STATUS_FORMAT_ERROR);

    // Skip to numOfArrays and read the nalu count
    pSrc += 22;
    naluCount = *pSrc;
    pSrc++;

    for (i = 0; i < naluCount; i++) {
        // Skip array_completeness, reserved and NAL_unit_type
        pSrc += 3;

        CHK(pSrc + SIZEOF(UINT16) <= pEnd, STATUS_FORMAT_ERROR);

        // Read the naluUnitLength
        naluUnitLen = GET_UNALIGNED_BIG_ENDIAN((PUINT16) pSrc);

        pSrc += SIZEOF(UINT16);

        CHK(offset + SIZEOF(start4ByteCode) + naluUnitLen < GST_PLUGIN_MAX_CPD_SIZE, STATUS_FORMAT_ERROR);
        CHK(pSrc + naluUnitLen <= pEnd, STATUS_FORMAT_ERROR);

        // Output the Annex-B start code
        MEMCPY(pGstKvsPlugin->videoCpd + offset, start4ByteCode, SIZEOF(start4ByteCode));
        offset += SIZEOF(start4ByteCode);

        // Output the NALu
        MEMCPY(pGstKvsPlugin->videoCpd + offset, pSrc, naluUnitLen);
        offset += naluUnitLen;
        pSrc += naluUnitLen;
    }

    pGstKvsPlugin->videoCpdSize = offset;

CleanUp:

    return retStatus;
}
