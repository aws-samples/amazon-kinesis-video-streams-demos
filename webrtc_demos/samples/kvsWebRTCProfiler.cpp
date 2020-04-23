#include "Samples.h"
#include <aws/core/Aws.h>
#include <aws/monitoring/CloudWatchClient.h>
#include <aws/monitoring/model/PutMetricDataRequest.h>
#include <iostream>

using namespace std;

VOID OnPutMetricDataResponseReceivedHandler(const Aws::CloudWatch::CloudWatchClient* cwClient,
                                            const Aws::CloudWatch::Model::PutMetricDataRequest& request,
                                            const Aws::CloudWatch::Model::PutMetricDataOutcome& outcome,
                                            const std::shared_ptr<const Aws::Client::AsyncCallerContext>& context) {
    printf("Got metrics\n");
    if (!outcome.IsSuccess())
    {
        DLOGE("Failed to put sample metric data: %s", outcome.GetError().GetMessage().c_str());
    }
    else
    {
        DLOGV("Successfully put sample metric data");
    }
}

VOID CanaryStreamSendMetrics(const Aws::CloudWatch::CloudWatchClient* cwClient, Aws::CloudWatch::Model::MetricDatum& metricDrum)
{
    printf("Here\n");
    Aws::CloudWatch::Model::PutMetricDataRequest cwRequest;
    cwRequest.SetNamespace("KinesisVideoWebRTCSDKCanary");
    cwRequest.AddMetricData(metricDrum);
    Aws::CloudWatch::Model::PutMetricDataOutcome outcome = cwClient->PutMetricData(cwRequest); //, OnPutMetricDataResponseReceivedHandler);
    if (!outcome.IsSuccess())
        {
            DLOGE("Failed to put sample metric data: %s", outcome.GetError().GetMessage().c_str());
        }
        else
        {
            DLOGV("Successfully put sample metric data");
        }
}


INT32 main(INT32 argc, CHAR *argv[])
{
    STATUS retStatus = STATUS_SUCCESS;
    RtcSessionDescriptionInit offerSessionDescriptionInit;
    UINT32 buffLen = 0;
    SignalingMessage message;
    PSampleConfiguration pSampleConfiguration = NULL;
    PSampleStreamingSession pSampleStreamingSession = NULL;
    BOOL locked = FALSE;

    signal(SIGINT, sigintHandler);
    Aws::SDKOptions options;
    Aws::InitAPI(options);
    Aws::Client::ClientConfiguration clientConfiguration;
    clientConfiguration.region = "us-west-2";
    Aws::CloudWatch::CloudWatchClient cw(clientConfiguration);

    Aws::CloudWatch::Model::Dimension dimension;
    dimension.SetName("KinesisVideoWebRTCSDK");
    dimension.SetValue("WebRTC");

    Aws::CloudWatch::Model::MetricDatum offerToAnswerTimeDatum;
    offerToAnswerTimeDatum.SetMetricName("OfferToAnswer");
    offerToAnswerTimeDatum.SetUnit(Aws::CloudWatch::Model::StandardUnit::Milliseconds);
    offerToAnswerTimeDatum.AddDimensions(dimension);
    // do trickle-ice by default
    cout<<"[KVS Master] Using trickleICE by default\n";

    retStatus = createSampleConfiguration(argc > 1 ? argv[1] : SAMPLE_CHANNEL_NAME,
                                          SIGNALING_CHANNEL_ROLE_TYPE_VIEWER,
                                          TRUE,
                                          TRUE,
                                          &pSampleConfiguration);
    if(retStatus != STATUS_SUCCESS) {
        cout<<"[KVS Viewer] createSampleConfiguration(): operation returned status code \n"<<retStatus<<"\n";
        goto CleanUp;

    }
    cout<<"[KVS Viewer] Created signaling channel "<< (argc > 1 ? argv[1] : SAMPLE_CHANNEL_NAME)<<"\n";

    // Initialize KVS WebRTC. This must be done before anything else, and must only be done once.
    retStatus = initKvsWebRtc();
    if(retStatus != STATUS_SUCCESS) {
        cout<<"[KVS Master] initKvsWebRtc(): operation returned status code "<<retStatus<<"\n";
        goto CleanUp;

    }

    cout<<"[KVS Viewer] KVS WebRTC initialization completed successfully\n";

    pSampleConfiguration->signalingClientCallbacks.messageReceivedFn = viewerMessageReceived;

    strcpy(pSampleConfiguration->clientInfo.clientId, SAMPLE_VIEWER_CLIENT_ID);

    retStatus = createSignalingClientSync(&pSampleConfiguration->clientInfo, &pSampleConfiguration->channelInfo,
                                          &pSampleConfiguration->signalingClientCallbacks, pSampleConfiguration->pCredentialProvider,
                                          &pSampleConfiguration->signalingClientHandle);
    if(retStatus != STATUS_SUCCESS) {
        cout<<"[KVS Viewer] createSignalingClientSync(): operation returned status code "<<retStatus<<"\n";
        goto CleanUp;

    }

    cout<<"[KVS Viewer] Signaling client created successfully \n";

    // Initialize streaming session
    MUTEX_LOCK(pSampleConfiguration->sampleConfigurationObjLock);
    locked = TRUE;

    retStatus = createSampleStreamingSession(pSampleConfiguration, NULL, FALSE, &pSampleStreamingSession);
    if(retStatus != STATUS_SUCCESS) {
        cout<<"[KVS Viewer] createSampleStreamingSession(): operation returned status code "<<retStatus<<"\n";
        goto CleanUp;

    }

    cout<<"[KVS Viewer] Creating streaming session...completed\n";
    pSampleConfiguration->sampleStreamingSessionList[pSampleConfiguration->streamingSessionCount++] = pSampleStreamingSession;

    MUTEX_UNLOCK(pSampleConfiguration->sampleConfigurationObjLock);
    locked = FALSE;

    // Enable the processing of the messages
    retStatus = signalingClientConnectSync(pSampleConfiguration->signalingClientHandle);
    if(retStatus != STATUS_SUCCESS) {
        cout<<"[KVS Viewer] signalingClientConnectSync(): operation returned status code "<<retStatus<<"\n";
        goto CleanUp;

    }

    cout<<"[KVS Viewer] Signaling client connection to socket established\n";

    while(1) {
    memset(&offerSessionDescriptionInit, 0x00, SIZEOF(RtcSessionDescriptionInit));

    retStatus = createOffer(pSampleStreamingSession->pPeerConnection, &offerSessionDescriptionInit);
    if(retStatus != STATUS_SUCCESS) {
        cout<<"[KVS Viewer] createOffer(): operation returned status code "<<retStatus<<"\n";
        goto CleanUp;
//
    }
    cout<<"[KVS Viewer] Offer creation successful\n";

    retStatus = setLocalDescription(pSampleStreamingSession->pPeerConnection, &offerSessionDescriptionInit);
    if(retStatus != STATUS_SUCCESS) {
        cout<<"[KVS Viewer] setLocalDescription(): operation returned status code "<<retStatus<<"\n";
        goto CleanUp;

    }
    cout<<"[KVS Viewer] Completed setting local description\n";


    cout<<"[KVS Viewer] Generating JSON of session description....";
    retStatus = serializeSessionDescriptionInit(&offerSessionDescriptionInit, NULL, &buffLen);
    if(retStatus != STATUS_SUCCESS) {
        cout<<"[KVS Viewer] serializeSessionDescriptionInit(): operation returned status code "<<retStatus<<"\n";
        goto CleanUp;

    }

    if(buffLen >= SIZEOF(message.payload)) {
        cout<<"[KVS Viewer] serializeSessionDescriptionInit(): operation returned status code "<<STATUS_INVALID_OPERATION<<"\n";
        retStatus = STATUS_INVALID_OPERATION;
        goto CleanUp;

    }

    retStatus = serializeSessionDescriptionInit(&offerSessionDescriptionInit, message.payload, &buffLen);
    if(retStatus != STATUS_SUCCESS) {
        cout<<"[KVS Viewer] serializeSessionDescriptionInit(): operation returned status code "<<retStatus<<"\n";
        goto CleanUp;
    }

    cout<<"Completed\n";

    message.version = SIGNALING_MESSAGE_CURRENT_VERSION;
    message.messageType = SIGNALING_MESSAGE_TYPE_OFFER;
    STRCPY(message.peerClientId, SAMPLE_MASTER_CLIENT_ID);
    message.payloadLen = (buffLen / SIZEOF(CHAR)) - 1;
    message.correlationId[0] = '\0';

    retStatus = signalingClientSendMessageSync(pSampleConfiguration->signalingClientHandle, &message);
    if(retStatus != STATUS_SUCCESS) {
        cout<<"[KVS Viewer] signalingClientSendMessageSync(): operation returned status code "<<retStatus<<"\n";
        goto CleanUp;
    }
    UINT64 start_time = GETTIME();
    MUTEX_LOCK(pSampleConfiguration->profiler.getAnswerLock);
    if(CVAR_WAIT(pSampleConfiguration->profiler.cvarGetAnswer, pSampleConfiguration->profiler.getAnswerLock, 300 * HUNDREDS_OF_NANOS_IN_A_SECOND) == STATUS_OPERATION_TIMED_OUT) {
        cout<<"[KVS Viewer] Receive answer timed out\n";
        goto CleanUp;

    }
    UINT64 end_time = GETTIME();
    MUTEX_UNLOCK(pSampleConfiguration->profiler.getAnswerLock);
    cout<<"[KVS Viewer] Answer Time:"<< end_time - start_time<<"\n";

    offerToAnswerTimeDatum.SetValue(((end_time - start_time) * 100)/1000000);
    CanaryStreamSendMetrics(&cw, offerToAnswerTimeDatum);
    sleep(1);
}

CleanUp:
    Aws::ShutdownAPI(options);
    if (retStatus != STATUS_SUCCESS) {
        cout<<"[KVS Viewer] Terminated with status code "<<retStatus<<"\n";
    }

    cout<<"[KVS Viewer] Cleaning up....\n";

    if (locked) {
        MUTEX_UNLOCK(pSampleConfiguration->sampleConfigurationObjLock);
    }

    if (pSampleConfiguration != NULL) {
        retStatus = freeSignalingClient(&pSampleConfiguration->signalingClientHandle);
        if(retStatus != STATUS_SUCCESS) {
            cout<<"[KVS Master] freeSignalingClient(): operation returned status code "<<retStatus<<"\n";
        }

        retStatus = freeSampleConfiguration(&pSampleConfiguration);
        if(retStatus != STATUS_SUCCESS) {
            cout<<"[KVS Master] freeSampleConfiguration(): operation returned status code: "<<retStatus<<"\n";
        }
    }
    cout<<"[KVS Viewer] Cleanup done\n";
    return (INT32) retStatus;
}
