package com.amazon.kinesis.video.canary.consumer;

import java.util.concurrent.FutureTask;
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

import com.amazonaws.auth.SystemPropertiesCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
import com.amazonaws.services.kinesisvideo.model.APIName;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointRequest;
import com.amazonaws.services.kinesisvideo.model.TimestampRange;
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
    private static SystemPropertiesCredentialsProvider credentialsProvider;
    private static AmazonKinesisVideo amazonKinesisVideo;
    private static AmazonCloudWatch cwClient;

    //protected static Boolean keepProcessing;
    final protected static AtomicBoolean keepProcessing = new AtomicBoolean(true);



    private static void calculateFragmentContinuityMetric(CanaryFragmentList fragmentList) {
        try {
            final GetDataEndpointRequest dataEndpointRequest = new GetDataEndpointRequest()
                .withAPIName(APIName.LIST_FRAGMENTS).withStreamName(streamName);
            final String listFragmentsEndpoint = amazonKinesisVideo.getDataEndpoint(dataEndpointRequest).getDataEndpoint();

            TimestampRange timestampRange = new TimestampRange();
            timestampRange.setStartTimestamp(canaryStartTime);
            timestampRange.setEndTimestamp(new Date());

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

    // TODO: shorten name as there is only a getMedia version now anyway, so remove "getMedia" from name
    private static void getMediaTimeToFirstFragment(Timer intervalMetricsTimer) {
        try {

            // final GetDataEndpointRequest dataEndpointRequestGetMedia = new GetDataEndpointRequest()
            //     .withAPIName(APIName.GET_MEDIA).withStreamName(streamName);
            // final String getMediaEndpoint = amazonKinesisVideo.getDataEndpoint(dataEndpointRequestGetMedia).getDataEndpoint();

            // final AmazonKinesisVideoMedia videoMedia;
            // final AmazonKinesisVideoMediaClientBuilder builder = AmazonKinesisVideoMediaClientBuilder.standard().withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(getMediaEndpoint, region)).withCredentials(credentialsProvider);
            // videoMedia = builder.build();
            
            //final StartSelector startSelector = new StartSelector().withStartSelectorType(StartSelectorType.NOW);
            final StartSelector startSelector = new StartSelector().withStartSelectorType(StartSelectorType.PRODUCER_TIMESTAMP).withStartTimestamp(canaryStartTime);
            
            //long t1 = new Date().getTime();

            // final GetMediaResult result = videoMedia.getMedia(new GetMediaRequest().withStreamName(streamName).withStartSelector(startSelector));
            // final InputStream payload = result.getPayload();

            long t2 = new Date().getTime();
            //System.out.println("t2 - t1 = " + (t2-t1));

            
            final long currentTime = new Date().getTime();
            double timeToFirstFragment = Double.MAX_VALUE;

            // Note: this will create file in /consumer-java folder
            // File myObj = new File("filename.txt");
            // myObj.createNewFile();

            
            // TODO: pass this stuff into the function, then just start on new threads every time called
            RealTimeFrameProcessor realTimeFrameProcessor = RealTimeFrameProcessor.create();
            final FrameVisitor frameVisitor = FrameVisitor.create(realTimeFrameProcessor);

            final GetMediaWorker getMediaWorker = GetMediaWorker.create(
                    Regions.fromName(region),
                    credentialsProvider,
                    streamName,
                    startSelector,
                    amazonKinesisVideo,
                    frameVisitor);

            System.out.println("Here 1");


            Thread thread1 = new Thread(getMediaWorker);
            thread1.start();

            //getMediaWorker.run();

            // while(keepProcessing)
            // {

            // }

            /*

            // If getMedia result payload is not empty, calculate TimeToFirstFragment
            if (payload.read() != -1) {
                long ta = new Date().getTime();
                System.out.println("ta - t2 = " + (ta-t2));

                timeToFirstFragment = currentTime - canaryStartTime.getTime();

                String filePath = "../webrtc-c/" + streamName + ".txt";
                Scanner scanner = new Scanner(new FileReader(filePath));
                long rtpToFirstFragment = currentTime - Long.parseLong(scanner.next());
                System.out.println(rtpToFirstFragment);
                // while(scanner.hasNext()) {
                //     System.out.println(scanner.next());
                // }
                scanner.close();

                publishMetricToCW("RtpToFirstFragment", rtpToFirstFragment, StandardUnit.Milliseconds);

                publishMetricToCW("TimeToFirstFragment", timeToFirstFragment, StandardUnit.Milliseconds);

                // Cancel any further occurrences of timer task, as this is a startup metric.
                // TODO: the below comment is not longer true, fix
                // The Canary will continue running for the specified period to allow for cool-down of Media-Server reconnection.
                intervalMetricsTimer.cancel();
            }

            */

            long t3 = new Date().getTime();
            //System.out.println("t3 - t2 = " + (t3-t2));

            if (!keepProcessing.get()) {
                intervalMetricsTimer.cancel();
            }
    
        } catch (Exception e) {
            log.error(e);
        }
    }

    
    private static void calculateTimeToFirstFragment(Timer intervalMetricsTimer) {
        try {
            double timeToFirstFragment = Double.MAX_VALUE;
        
            // TODO: make the below two blocks of code into a 
            // getFragmentSelector() function, reuse in the other metric

            // Time range for listFragments request
            TimestampRange timestampRange = new TimestampRange();
            timestampRange.setStartTimestamp(canaryStartTime);
            timestampRange.setEndTimestamp(new Date());

            // Configures listFragments request
            FragmentSelector fragmentSelector = new FragmentSelector();
            fragmentSelector.setFragmentSelectorType("SERVER_TIMESTAMP");
            fragmentSelector.setTimestampRange(timestampRange);

            long currentTime = new Date().getTime();

            final FutureTask<List<CanaryFragment>> futureTask = new FutureTask<>(
                new CanaryListFragmentWorker(streamName, credentialsProvider, dataEndpoint, Regions.fromName(region), fragmentSelector)
            );
            Thread thread = new Thread(futureTask);
            thread.start();
            List<CanaryFragment> fragmentList = futureTask.get();

            if (fragmentList.size() > 0) {
                timeToFirstFragment = fragmentList[0].getFragment.getServerTimestamp() - canaryStartTime.getTime();
                publishMetricToCW("TimeToFirstFragment", timeToFirstFragment, StandardUnit.Milliseconds, streamName, canaryLabel, credentialsProvider, region);
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
        System.out.println("CONSUMER CANARY START TIME: " + new Date().getTime());

        // Import configurable parameters.
        final Integer canaryRunTime = Integer.parseInt(System.getenv("CANARY_DURATION_IN_SECONDS"));
        streamName = System.getenv("CANARY_STREAM_NAME");
        canaryLabel = System.getenv("CANARY_LABEL");
        region = System.getenv("AWS_DEFAULT_REGION");

        log.info("Stream name: {}", streamName);

        canaryStartTime = new Date();

        credentialsProvider = new SystemPropertiesCredentialsProvider();
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
                final long intervalInitialDelay = 60000;
                final long intervalDelay = 16000;
                // NOTE: Metric publishing will NOT begin if canaryRunTime is < intervalInitialDelay
                intervalMetricsTimer.scheduleAtFixedRate(intervalMetricsTask, intervalInitialDelay, intervalDelay); // initial delay of 'intervalInitialDelay' ms at an interval of 'intervalDelay' ms
                break;
            }
            case "WebrtcPeriodic": {
                //keepProcessing = true;
                //getMediaTimeToFirstFragment(intervalMetricsTimer);
                intervalMetricsTask = new TimerTask() {
                    @Override
                    public void run() {
                        //getMediaTimeToFirstFragment(intervalMetricsTimer);
                        calculateTimeToFirstFragment(intervalMetricsTimer);
                    }
                };
                // final long intervalDelay = 250;
                final long intervalDelay = 250;
                intervalMetricsTimer.scheduleAtFixedRate(intervalMetricsTask, 0, intervalDelay); // initial delay of 0 ms at an interval of 'intervalDelay' ms
                break;
            }
            default: {
                log.error("Env var CANARY_LABEL: {} must be set to either WebrtcLongRunning or WebrtcPeriodic", canaryLabel);
                throw new Exception("CANARY_LABEL must be set to either WebrtcLongRunning or WebrtcPeriodic");
            }
        }
        
        // TODO: consider not running this sleep for the periodic case if it only is measuring startup/timetofirst metrics (can do this now that the 5min cooldown is fixed)
        Thread.sleep(canaryRunTime * 1000);
        intervalMetricsTimer.cancel();
    }
}
