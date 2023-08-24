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


import com.amazonaws.auth.SystemPropertiesCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClientBuilder;
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
import lombok.extern.log4j.Log4j2;



import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoMediaClientBuilder;
import com.amazonaws.services.kinesisvideo.model.GetMediaResult;
import com.amazonaws.services.kinesisvideo.model.StartSelector;
import com.amazonaws.services.kinesisvideo.model.StartSelectorType;
import com.amazonaws.services.kinesisvideo.model.GetMediaRequest;



// TODO: don't use list fragment worker class, it how I was trying to before within this file

@Log4j2
public class WebrtcStorageCanaryConsumer {
    private static void calculateFragmentContinuityMetric(CanaryFragmentList fragmentList, Date canaryStartTime, String streamName, String canaryLabel, SystemPropertiesCredentialsProvider credentialsProvider, String dataEndpoint, String region) {
        try {
            // TODO: eliminate this duplicated code, other metric function does this too.
            TimestampRange timestampRange = new TimestampRange();
            timestampRange.setStartTimestamp(canaryStartTime);
            timestampRange.setEndTimestamp(new Date());

            FragmentSelector fragmentSelector = new FragmentSelector();
            fragmentSelector.setFragmentSelectorType("SERVER_TIMESTAMP");
            fragmentSelector.setTimestampRange(timestampRange);

            Boolean newFragmentReceived = false;

            final FutureTask<List<CanaryFragment>> futureTask = new FutureTask<>(
                new CanaryListFragmentWorker(streamName, credentialsProvider, dataEndpoint, Regions.fromName(region), fragmentSelector)
            );
            Thread thread = new Thread(futureTask);
            thread.start();
            List<CanaryFragment> newFragmentList = futureTask.get();

            if (newFragmentList.size() > fragmentList.getFragmentList().size()) {
                newFragmentReceived = true;
            }
            log.info("New fragment received: {}", newFragmentReceived);

            fragmentList.setFragmentList(newFragmentList);

            publishMetricToCW("FragmentReceived", newFragmentReceived ? 1.0 : 0.0, StandardUnit.None, streamName, canaryLabel, credentialsProvider, region);

        } catch (Exception e) {
            log.error(e);
        }
    }

    private static void calculateTimeToFirstFragment(Timer intervalMetricsTimer, Date canaryStartTime, String streamName, String canaryLabel, SystemPropertiesCredentialsProvider credentialsProvider, String dataEndpoint, String region) {
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
                timeToFirstFragment = currentTime - canaryStartTime.getTime();
                publishMetricToCW("TimeToFirstFragment", timeToFirstFragment, StandardUnit.Milliseconds, streamName, canaryLabel, credentialsProvider, region);
                intervalMetricsTimer.cancel();
            }

        } catch (Exception e) {
            log.error(e);
        }
                
    }

    private static void modifiedCalculateTimeToFirstFragment(Timer intervalMetricsTimer, Date canaryStartTime, String streamName, String canaryLabel, SystemPropertiesCredentialsProvider credentialsProvider, String dataEndpoint, String region) {
        try {
            double timeToFirstFragment = Double.MAX_VALUE;
        
            // TODO: make the below two blocks of code into a 
            //      getFragmentSelector() function, reuse in the other metric

            // Time range for listFragments request
            TimestampRange timestampRange = new TimestampRange();
            timestampRange.setStartTimestamp(canaryStartTime);
            timestampRange.setEndTimestamp(new Date());

            // Configures listFragments request
            FragmentSelector fragmentSelector = new FragmentSelector();
            fragmentSelector.setFragmentSelectorType("SERVER_TIMESTAMP");
            fragmentSelector.setTimestampRange(timestampRange);

            final FutureTask<List<CanaryFragment>> futureTask = new FutureTask<>(
                new CanaryListFragmentWorker(streamName, credentialsProvider, dataEndpoint, Regions.fromName(region), fragmentSelector)
            );
            Thread thread = new Thread(futureTask);
            thread.start();
            List<CanaryFragment> fragmentList = futureTask.get();

            if (fragmentList.size() > 0) {
                Date ingestedAt = fragmentList.get(0).getFragment().getServerTimestamp();
                timeToFirstFragment = ingestedAt.getTime() - canaryStartTime.getTime();
                publishMetricToCW("TimeToFirstFragment", timeToFirstFragment, StandardUnit.Milliseconds, streamName, canaryLabel, credentialsProvider, region);
                intervalMetricsTimer.cancel();
            }

        } catch (Exception e) {
            log.error(e);
        }
                
    }

    private static void getMediaTimeToFirstFragment(AmazonKinesisVideo amazonKinesisVideo, Timer intervalMetricsTimer, Date canaryStartTime, String streamName, String canaryLabel, SystemPropertiesCredentialsProvider credentialsProvider, String dataEndpoint, String region) {
        try {
            double timeToFirstFragment = Double.MAX_VALUE;

            final GetDataEndpointRequest dataEndpointRequestGetMedia = new GetDataEndpointRequest()
                .withAPIName(APIName.GET_MEDIA).withStreamName(streamName);
            final String dataEndpointGetMedia = amazonKinesisVideo.getDataEndpoint(dataEndpointRequestGetMedia).getDataEndpoint();

            final AmazonKinesisVideoMedia videoMedia;
            AmazonKinesisVideoMediaClientBuilder builder = AmazonKinesisVideoMediaClientBuilder.standard().withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(dataEndpointGetMedia, region)).withCredentials(credentialsProvider);
            videoMedia = builder.build();
            
            StartSelector startSelector = new StartSelector().withStartSelectorType(StartSelectorType.NOW);
            
            int counter = 0;

            System.out.println(MessageFormat.format("Start GetMedia worker on stream {0}", streamName));
            GetMediaResult result = videoMedia.getMedia(new GetMediaRequest().withStreamName(streamName).withStartSelector(startSelector));
            System.out.println(MessageFormat.format("GetMedia called on stream {0} response {1} requestId {2}", streamName, result.getSdkHttpMetadata().getHttpStatusCode(), result.getSdkResponseMetadata().getRequestId()));
            
            InputStream payload = result.getPayload();

            long currentTime = new Date().getTime();

            if (payload.read() != -1) {
                timeToFirstFragment = currentTime - canaryStartTime.getTime();
                publishMetricToCW("TimeToFirstFragment", timeToFirstFragment, StandardUnit.Milliseconds, streamName, canaryLabel, credentialsProvider, region);
                intervalMetricsTimer.cancel();
            }
    
        } catch (Exception e) {
            System.out.println(e);
        }
        
    }

    private static void publishMetricToCW(String metricName, double value, StandardUnit cwUnit, String streamName, String canaryLabel, SystemPropertiesCredentialsProvider credentialsProvider, String region) {
        try {
            System.out.println("Publishing a metric");
            final AmazonCloudWatchAsync cwClient = AmazonCloudWatchAsyncClientBuilder.standard()
                    .withRegion(region)
                    .withCredentials(credentialsProvider)
                    .build();

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
            cwClient.putMetricDataAsync(request);
            System.out.println("Publishing metric: ");
            System.out.println(value);
        } catch (Exception e) {
            System.out.println(e);
            log.error(e);
        }
    }

    public static void main(final String[] args) throws Exception {
        final String streamName = System.getenv("CANARY_STREAM_NAME");


        final String canaryLabel = System.getenv("CANARY_LABEL");
        final String region = System.getenv("AWS_DEFAULT_REGION");
        final Integer canaryRunTime = Integer.parseInt(System.getenv("CANARY_DURATION_IN_SECONDS"));

        log.info("Stream name: {}", streamName);

        final SystemPropertiesCredentialsProvider credentialsProvider = new SystemPropertiesCredentialsProvider();
        final AmazonKinesisVideo amazonKinesisVideo = AmazonKinesisVideoClientBuilder.standard()
                .withRegion(region)
                .withCredentials(credentialsProvider)
                .build();
        final AmazonCloudWatchAsync amazonCloudWatch = AmazonCloudWatchAsyncClientBuilder.standard()
                .withRegion(region)
                .withCredentials(credentialsProvider)
                .build();

        final GetDataEndpointRequest dataEndpointRequest = new GetDataEndpointRequest()
                .withAPIName(APIName.LIST_FRAGMENTS).withStreamName(streamName);
        final String dataEndpoint = amazonKinesisVideo.getDataEndpoint(dataEndpointRequest).getDataEndpoint();

        final Date canaryStartTime = new Date();


        // TODO: put all things within switch case block that aren't shared
        CanaryFragmentList fragmentList = new CanaryFragmentList();
        Timer intervalMetricsTimer = new Timer("IntervalMetricsTimer");



        // Code for sharing:
            // final AmazonKinesisVideoMedia videoMedia;

            // AmazonKinesisVideoMediaClientBuilder builder = AmazonKinesisVideoMediaClientBuilder.standard()
            //         .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(dataEndpoint, region))
            //         .withCredentials(credentialsProvider);
            // videoMedia = builder.build();
            
            // StartSelector startSelector = new StartSelector().withStartSelectorType(StartSelectorType.NOW);

            // GetMediaResult result = videoMedia.getMedia(new GetMediaRequest()
            //         .withStreamName(streamName)
            //         .withStartSelector(startSelector));



        // try {
        //     final GetDataEndpointRequest dataEndpointRequestGetMedia = new GetDataEndpointRequest()
        //         .withAPIName(APIName.GET_MEDIA).withStreamName(streamName);
        //     final String dataEndpointGetMedia = amazonKinesisVideo.getDataEndpoint(dataEndpointRequestGetMedia).getDataEndpoint();

        //     // getMediaTimeToFirstFragment();
        //     final AmazonKinesisVideoMedia videoMedia;

        //     AmazonKinesisVideoMediaClientBuilder builder = AmazonKinesisVideoMediaClientBuilder.standard().withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(dataEndpointGetMedia, region)).withCredentials(credentialsProvider);
        //     videoMedia = builder.build();
            
        //     StartSelector startSelector = new StartSelector().withStartSelectorType(StartSelectorType.NOW);
        
        //     System.out.println(MessageFormat.format("Start GetMedia worker on stream {0}", streamName));
        //     GetMediaResult result = videoMedia.getMedia(new GetMediaRequest().withStreamName(streamName).withStartSelector(startSelector));
        //     System.out.println(MessageFormat.format("GetMedia called on stream {0} response {1} requestId {2}", streamName, result.getSdkHttpMetadata().getHttpStatusCode(), result.getSdkResponseMetadata().getRequestId()));
        //     System.out.println("here");
        //     InputStream payload = result.getPayload();

        //     System.out.println("AVAILABLE:");
        //     System.out.println(payload.available());
                        
        //     System.out.println(payload.read(new byte[1]));
    
        //     System.out.println(result.getContentType());
        // } catch (Exception e) {
        //     System.out.println(e);
        // }


        

        switch (canaryLabel){
            case "WebrtcLongRunning": {
                System.out.println("FragmentContinuity Case");
                TimerTask intervalMetricsTask = new TimerTask() {
                    @Override
                    public void run() {
                        calculateFragmentContinuityMetric(fragmentList, canaryStartTime, streamName, canaryLabel, credentialsProvider, dataEndpoint, region);
                    }
                };
                final long intervalDelay = 16000;
                intervalMetricsTimer.scheduleAtFixedRate(intervalMetricsTask, 60000, intervalDelay); // initial delay of 60 s at an interval of intervalDelay ms
                break;
            }
            case "WebrtcPeriodic": {
                System.out.println("TimeToFirstFragment Case");
                TimerTask intervalMetricsTask = new TimerTask() {
                    @Override
                    public void run() {
                        // TODO: make endpoint all cases within funciton rather than passing amazonKinesisVideo
                        //getMediaTimeToFirstFragment(amazonKinesisVideo, intervalMetricsTimer, canaryStartTime, streamName, canaryLabel, credentialsProvider, dataEndpoint, region);
                        //calculateTimeToFirstFragment(intervalMetricsTimer, canaryStartTime, streamName, canaryLabel, credentialsProvider, dataEndpoint, region);
                        modifiedCalculateTimeToFirstFragment(intervalMetricsTimer, canaryStartTime, streamName, canaryLabel, credentialsProvider, dataEndpoint, region);
                    }
                };
                final long intervalDelay = 300;
                intervalMetricsTimer.scheduleAtFixedRate(intervalMetricsTask, 0, intervalDelay); // initial delay of 0 ms at an interval of 1 ms
                break;
            }
            default:
                log.info("Env var CANARY_LABEL: {} must be set to either WebrtcLongRunning or WebrtcPeriodic", canaryLabel);
                System.out.println("Default Case");
                break;
        }

        // TODO: Make this comment more clear or remove:
        // Run this sleep for both FragmentReceived and TimeToFirstFrame metric cases to ensure
        // connection can be made to media-server for periodic runs
        Thread.sleep(canaryRunTime * 1000);

        // Using System.exit(0) to exit from application. 
        // The application does not exit on its own. Need to inspect what the issue
        // is
        System.exit(0);
    }
}
