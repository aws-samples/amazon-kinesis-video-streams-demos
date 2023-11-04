package com.amazon.kinesis.video.canary.consumer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.lang.Exception;
import java.util.Date;
import java.text.MessageFormat;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.ArrayList;
import java.io.InputStream;

import java.util.Scanner;
import java.io.FileReader;
import java.io.File; // TODO: remove, just for testing
import java.util.concurrent.atomic.AtomicBoolean;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.auth.SystemPropertiesCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
import com.amazonaws.services.kinesisvideo.model.APIName;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointRequest;
import com.amazonaws.services.kinesisvideo.model.TimestampRange;

import java.util.concurrent.Future;

import com.amazonaws.services.kinesisvideo.model.FragmentSelector;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.MetricDatum;
import com.amazonaws.services.cloudwatch.model.PutMetricDataRequest;
import com.amazonaws.services.cloudwatch.model.StandardUnit;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoMediaClientBuilder;
import com.amazonaws.services.kinesisvideo.model.GetMediaResult;
import com.amazonaws.services.kinesisvideo.model.StartSelector;
import com.amazonaws.services.kinesisvideo.model.StartSelectorType;
import com.amazonaws.services.kinesisvideo.model.GetMediaRequest;
import com.amazonaws.kinesisvideo.parser.utilities.FrameVisitor;
import com.amazonaws.kinesisvideo.parser.examples.GetMediaWorker;

import com.amazonaws.SDKGlobalConfiguration;


import lombok.extern.log4j.Log4j2;

/*
 * Canary for WebRTC with Storage Through Media Server
 * 
 * For longrun-configured jobs, this Canary will emit FragmentContinuity metrics by continuously
 * checking for any newly ingested fragments for the given stream. The fragment list is checked
 * for new fragments every "max-fragment-duration + 1 sec." The max-fragment-duration is determined
 * by Media Server.
 * 
 * For periodic-configured jobs, this Canary will emit TimeToFirstFragment metrics by continuously
 * checking for consumable media from the specified stream via GetMedia calls. It takes ~1 sec for
 * InputStream.read() to verify that a stream is empty, so the resolution of this metric is approx
 * 1 sec.
 */

@Log4j2
public class WebrtcStorageCanaryConsumer {
    protected static Date canaryStartTime;
    protected static String streamName;
    private static String canaryLabel;
    private static String region;
    private static EnvironmentVariableCredentialsProvider credentialsProvider;
    private static AmazonKinesisVideo amazonKinesisVideo;
    private static AmazonCloudWatch cwClient;

    private static void calculateFragmentContinuityMetric(CanaryFragmentList fragmentList) {
        try {
            final GetDataEndpointRequest dataEndpointRequest = new GetDataEndpointRequest()
                .withAPIName(APIName.LIST_FRAGMENTS).withStreamName(streamName);
            final String listFragmentsEndpoint = amazonKinesisVideo.getDataEndpoint(dataEndpointRequest).getDataEndpoint();

            TimestampRange timestampRange = new TimestampRange();
            timestampRange.setStartTimestamp(canaryStartTime);
            timestampRange.setEndTimestamp(new Date());

            // TODO: change this to be PRODUCER_TS instead...
            FragmentSelector fragmentSelector = new FragmentSelector();
            fragmentSelector.setFragmentSelectorType("SERVER_TIMESTAMP");
            fragmentSelector.setTimestampRange(timestampRange);

            Boolean newFragmentReceived = false;

            final FutureTask<List<CanaryFragment>> futureTask = new FutureTask<>(
                new CanaryListFragmentWorker(streamName, credentialsProvider, listFragmentsEndpoint, Regions.fromName(region), fragmentSelector)
            );
            Thread thread = new Thread(futureTask);
            thread.start();
            List<CanaryFragment> newFragmentList = futureTask.get();

            if (newFragmentList.size() > fragmentList.getFragmentList().size()) {
                newFragmentReceived = true;
            }
            log.info("New fragment received: {}", newFragmentReceived);
            fragmentList.setFragmentList(newFragmentList);

            publishMetricToCW("FragmentReceived", newFragmentReceived ? 1.0 : 0.0, StandardUnit.None);
        } catch (Exception e) {
            log.error(e);
        }
    }

    // TODO: eventually shorten name as there will only be a getMedia version anyway, so remove "getMedia" from name
private static void getMediaTimeToFirstFragment() {
        try {
            final StartSelector startSelector = new StartSelector().withStartSelectorType(StartSelectorType.PRODUCER_TIMESTAMP).withStartTimestamp(canaryStartTime);
        
            final long currentTime = new Date().getTime();
            double timeToFirstFragment = Double.MAX_VALUE;

            RealTimeFrameProcessor realTimeFrameProcessor = RealTimeFrameProcessor.create();
            final FrameVisitor frameVisitor = FrameVisitor.create(realTimeFrameProcessor);

            final ExecutorService executorService = Executors.newSingleThreadExecutor();
            final GetMediaWorker getMediaWorker = GetMediaWorker.create(
                    Regions.fromName(region),
                    credentialsProvider,
                    streamName,
                    startSelector,
                    amazonKinesisVideo,
                    frameVisitor);
                    
            final Future<?> task = executorService.submit(getMediaWorker);
            task.get();
    
        } catch (Exception e) {
            log.error(e);
        }
    }

    // NOTE: unused
    // TODO: remove this if not going to use...
    private static void calculateTimeToFirstFragment(Timer intervalMetricsTimer) {
        try {
            double timeToFirstFragment = Double.MAX_VALUE;

            final GetDataEndpointRequest dataEndpointRequest = new GetDataEndpointRequest()
                .withAPIName(APIName.LIST_FRAGMENTS).withStreamName(streamName);
            final String listFragmentsEndpoint = amazonKinesisVideo.getDataEndpoint(dataEndpointRequest).getDataEndpoint();
        
            // TODO: make the below two blocks of code into a 
            // getFragmentSelector() function, reuse in the other metric

            // Time range for listFragments request
            TimestampRange timestampRange = new TimestampRange();
            timestampRange.setStartTimestamp(canaryStartTime);
            timestampRange.setEndTimestamp(new Date());

            // Configures listFragments request
            FragmentSelector fragmentSelector = new FragmentSelector();
            fragmentSelector.setFragmentSelectorType("PRODUCER_TIMESTAMP");
            fragmentSelector.setTimestampRange(timestampRange);

            long currentTime = new Date().getTime();

            final FutureTask<List<CanaryFragment>> futureTask = new FutureTask<>(
                new CanaryListFragmentWorker(streamName, credentialsProvider, listFragmentsEndpoint, Regions.fromName(region), fragmentSelector)
            );
            Thread thread = new Thread(futureTask);
            thread.start();
            List<CanaryFragment> fragmentList = futureTask.get();

            if (fragmentList.size() > 0) {
                //System.out.println(fragmentList.size());

                String filePath = "../webrtc-c/" + streamName + ".txt";
                Scanner scanner = new Scanner(new FileReader(filePath));
                long rtpSendTime = Long.parseLong(scanner.next());
                scanner.close();

                timeToFirstFragment = fragmentList.get(0).getFragment().getServerTimestamp().getTime() - rtpSendTime;
                publishMetricToCW("TimeToFirstFragment1", timeToFirstFragment, StandardUnit.Milliseconds);
                timeToFirstFragment = fragmentList.get(0).getFragment().getServerTimestamp().getTime() - fragmentList.get(0).getFragment().getProducerTimestamp().getTime();
                publishMetricToCW("KVSSinkToInletLatency", timeToFirstFragment, StandardUnit.Milliseconds);
                intervalMetricsTimer.cancel();
            }

        } catch (Exception e) {
            log.error(e);
        }
           
    }
    
    
    protected static void publishMetricToCW(String metricName, double value, StandardUnit cwUnit) {
        try {
            System.out.println(MessageFormat.format("Emitting the following metric: {0} - {1}", metricName, value));
            final Dimension dimensionPerStream = new Dimension()
                    .withName("StorageWebRTCSDKCanaryStreamName")
                    .withValue(streamName);
            final Dimension aggregatedDimension = new Dimension()
                    .withName("StorageWebRTCSDKCanaryLabel")
                    .withValue(canaryLabel);
            List<MetricDatum> datumList = new ArrayList<>();

            MetricDatum datum = new MetricDatum()
                    .withMetricName(metricName)
                    .withUnit(cwUnit)
                    .withValue(value)
                    .withDimensions(dimensionPerStream);
            datumList.add(datum);
            MetricDatum aggDatum = new MetricDatum()
                    .withMetricName(metricName)
                    .withUnit(cwUnit)
                    .withValue(value)
                    .withDimensions(aggregatedDimension);
            datumList.add(aggDatum);

            PutMetricDataRequest request = new PutMetricDataRequest()
                    .withNamespace("KinesisVideoSDKCanary")
                    .withMetricData(datumList);
            cwClient.putMetricData(request);
        } catch (Exception e) {
            System.out.println(e);
            log.error(e);
        }
    }

    public static void main(final String[] args) throws Exception {
        //System.setProperty(SDKGlobalConfiguration.DISABLE_CERT_CHECKING_SYSTEM_PROPERTY, "true");

        System.out.println("CONSUMER CANARY START TIME: " + new Date().getTime());

        // Import configurable parameters.
        final Integer canaryRunTime = Integer.parseInt(System.getenv("CANARY_DURATION_IN_SECONDS"));
        streamName = System.getenv("CANARY_STREAM_NAME");
        canaryLabel = System.getenv("CANARY_LABEL");
        region = "us-west-2"; // TODO: remove this hardcode
        
        log.info("Stream name: {}", streamName);

        canaryStartTime = new Date();

        credentialsProvider = new EnvironmentVariableCredentialsProvider();
        amazonKinesisVideo = AmazonKinesisVideoClientBuilder.standard()
                .withRegion(region)
                .withCredentials(credentialsProvider)
                .build();
        cwClient = AmazonCloudWatchClientBuilder.standard()
                    .withRegion(region)
                    .withCredentials(credentialsProvider)
                    .build();
        

        Timer intervalMetricsTimer = new Timer("IntervalMetricsTimer");
        TimerTask intervalMetricsTask;
        // TODO: consider changing these labels to be "StorageWebrtc...", but might be not worth it if they are tied to dashboard terminology
        switch (canaryLabel){
            case "WebrtcLongRunning": {
                final CanaryFragmentList fragmentList = new CanaryFragmentList();
                intervalMetricsTask = new TimerTask() {
                    @Override
                    public void run() {
                        calculateFragmentContinuityMetric(fragmentList);
                    }
                };
                // TODO: the initial delay can be tweaked now that master-end latencies have been reduced
                final long intervalInitialDelay = 60000;
                final long intervalDelay = 16000;
                // NOTE: Metric publishing will NOT begin if canaryRunTime is < intervalInitialDelay
                intervalMetricsTimer.scheduleAtFixedRate(intervalMetricsTask, intervalInitialDelay, intervalDelay); // initial delay of 'intervalInitialDelay' ms at an interval of 'intervalDelay' ms
                Thread.sleep(canaryRunTime * 1000);
                intervalMetricsTimer.cancel();
                break;
            }
            case "WebrtcPeriodic": {
                while((System.currentTimeMillis() - canaryStartTime.getTime()) < canaryRunTime*1000) {
                    getMediaTimeToFirstFragment();
                }
                System.exit(0);
                break;
            }
            default: {
                log.error("Env var CANARY_LABEL: {} must be set to either WebrtcLongRunning or WebrtcPeriodic", canaryLabel);
                throw new Exception("CANARY_LABEL must be set to either WebrtcLongRunning or WebrtcPeriodic");
            }
        }
        
        // // TODO: consider not running this sleep for the periodic case if it only is measuring startup/timetofirst metrics (can do this now that the 5min cooldown is fixed)
    }
}
