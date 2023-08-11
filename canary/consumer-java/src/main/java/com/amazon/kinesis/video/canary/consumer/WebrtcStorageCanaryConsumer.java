package com.amazon.kinesis.video.canary.consumer;

import java.util.concurrent.FutureTask;
import java.lang.Exception;
import java.util.Date;
import java.text.MessageFormat;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.ArrayList;

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

@Log4j2
public class WebrtcStorageCanaryConsumer {
    private static void calculateFragmentContinuityMetric(CanaryFragmentList fragmentList, Date canaryStartTime, String streamName, String canaryLabel, SystemPropertiesCredentialsProvider credentialsProvider, String dataEndpoint, String region) {
        try {
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

            sendFragmentContinuityMetric(newFragmentReceived, streamName, canaryLabel, credentialsProvider, region);

        } catch (Exception e) {
            log.error(e);
        }
    }

    private static void sendFragmentContinuityMetric(Boolean newFragmentReceived, String streamName, String canaryLabel, SystemPropertiesCredentialsProvider credentialsProvider, String region) {
        try {
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
                    .withMetricName("FragmentReceived")
                    .withUnit(StandardUnit.None)
                    .withValue(newFragmentReceived ? 1.0 : 0.0)
                    .withDimensions(dimensionPerStream);
            datumList.add(datum);
            MetricDatum aggDatum = new MetricDatum()
                    .withMetricName("FragmentReceived")
                    .withUnit(StandardUnit.None)
                    .withValue(newFragmentReceived ? 1.0 : 0.0)
                    .withDimensions(aggregatedDimension);
            datumList.add(aggDatum);

            PutMetricDataRequest request = new PutMetricDataRequest()
                    .withNamespace("KinesisVideoSDKCanary")
                    .withMetricData(datumList);
            cwClient.putMetricDataAsync(request);

        } catch (Exception e) {
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

        CanaryFragmentList fragmentList = new CanaryFragmentList();
        final Date canaryStartTime = new Date();

        Timer intervalMetricsTimer = new Timer("IntervalMetricsTimer");
        TimerTask intervalMetricsTask = new TimerTask() {
            @Override
            public void run() {
                calculateFragmentContinuityMetric(fragmentList, canaryStartTime, streamName, canaryLabel, credentialsProvider, dataEndpoint, region);
            }
        };

        final long intervalDelay = 16000;
        intervalMetricsTimer.scheduleAtFixedRate(intervalMetricsTask, 60000, intervalDelay); // initial delay of 60 s at an interval of intervalDelay ms

        final long delay = canaryRunTime * 1000;
        Thread.sleep(delay);

        // Using System.exit(0) to exit from application. 
        // The application does not exit on its own. Need to inspect what the issue
        // is
        System.exit(0);
    }
}
