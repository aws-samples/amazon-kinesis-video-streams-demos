package com.amazon.kinesis.video.canary.consumer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.lang.Exception;
import java.text.MessageFormat;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
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
import com.amazonaws.services.kinesisvideo.model.StartSelector;
import com.amazonaws.services.kinesisvideo.model.StartSelectorType;
import com.amazonaws.kinesisvideo.parser.utilities.FrameVisitor;
import com.amazonaws.kinesisvideo.parser.examples.GetMediaWorker;

import lombok.extern.log4j.Log4j2;

/*
 * Canary for WebRTC with Storage thro Media Server
 * 
 * For longRun-configured jobs, this Canary will emit FragmentContinuity metrics by continuously
 * checking for any newly ingested fragments for the given stream. The fragment list is checked
 * for new fragments every "fragmentDuration * 2" The fragmentDuration is set by the encoder in
 * configuration in Media Server.
 * 
 * For periodic-configured jobs, this Canary will emit TimeToFirstFragment metrics by continuously
 * checking for consumable media from the specified stream via GetMedia calls.
 */

@Log4j2
public class WebrtcStorageCanaryConsumer {
    protected static final Date mCanaryStartTime = new Date();
    protected static final String mStreamName = System.getenv("CANARY_STREAM_NAME"); 

    private static final String mCanaryLabel = System.getenv("CANARY_LABEL");
    private static final String mRegion = System.getenv("AWS_DEFAULT_REGION");

    private static EnvironmentVariableCredentialsProvider mCredentialsProvider;
    private static AmazonKinesisVideo mAmazonKinesisVideo;
    private static AmazonCloudWatch mCwClient;

    private static void calculateFragmentContinuityMetric(CanaryFragmentList fragmentList) {
        try {
            final GetDataEndpointRequest dataEndpointRequest = new GetDataEndpointRequest()
                    .withAPIName(APIName.LIST_FRAGMENTS).withStreamName(mStreamName);
            final String listFragmentsEndpoint = mAmazonKinesisVideo.getDataEndpoint(dataEndpointRequest)
                    .getDataEndpoint();

            TimestampRange timestampRange = new TimestampRange();
            timestampRange.setStartTimestamp(mCanaryStartTime);
            timestampRange.setEndTimestamp(new Date());

            FragmentSelector fragmentSelector = new FragmentSelector();
            fragmentSelector.setFragmentSelectorType("SERVER_TIMESTAMP");
            fragmentSelector.setTimestampRange(timestampRange);

            Boolean newFragmentReceived = false;

            final FutureTask<List<CanaryFragment>> futureTask = new FutureTask<>(
                    new CanaryListFragmentWorker(mStreamName, mCredentialsProvider, listFragmentsEndpoint,
                            Regions.fromName(mRegion), fragmentSelector));
            Thread thread = new Thread(futureTask);
            thread.start();

            List<CanaryFragment> newFragmentList = futureTask.get();
            if (newFragmentList.size() > fragmentList.getFragmentList().size()) {
                newFragmentReceived = true;
            }
            
            fragmentList.setFragmentList(newFragmentList);

            publishMetricToCW("FragmentReceived", newFragmentReceived ? 1.0 : 0.0, StandardUnit.None);
        } catch (Exception e) {
            log.error("Failed while calculating continuity metric, {}", e);
        }
    }

    private static void calculateTimeToFirstFragment() {
        try {
            System.out.println("Spawning new frameProcessor");
            final StartSelector startSelector = new StartSelector()
                    .withStartSelectorType(StartSelectorType.PRODUCER_TIMESTAMP).withStartTimestamp(mCanaryStartTime);

            final long currentTime = new Date().getTime();
            double timeToFirstFragment = Double.MAX_VALUE;

            RealTimeFrameProcessor realTimeFrameProcessor = RealTimeFrameProcessor.create();
            final FrameVisitor frameVisitor = FrameVisitor.create(realTimeFrameProcessor);

            final ExecutorService executorService = Executors.newSingleThreadExecutor();
            final GetMediaWorker getMediaWorker = GetMediaWorker.create(
                    Regions.fromName(mRegion),
                    mCredentialsProvider,
                    mStreamName,
                    startSelector,
                    mAmazonKinesisVideo,
                    frameVisitor);

            final Future<?> task = executorService.submit(getMediaWorker);
            task.get();
            executorService.shutdown();
            System.out.println("getMediaWorker returned");


        } catch (Exception e) {
            log.error("Failed while calculating time to first fragment, {}", e);
        }
    }

    protected static void publishMetricToCW(String metricName, double value, StandardUnit cwUnit) {
        try {
            System.out.println(MessageFormat.format("Emitting the following metric: {0} - {1}", metricName, value));
            log.info("Emitting the following metric: {} - {}", metricName, value);
            final Dimension dimensionPerStream = new Dimension()
                    .withName("StorageWebRTCSDKCanaryStreamName")
                    .withValue(mStreamName);
            final Dimension aggregatedDimension = new Dimension()
                    .withName("StorageWebRTCSDKCanaryLabel")
                    .withValue(mCanaryLabel);
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
            mCwClient.putMetricData(request);
        } catch (Exception e) {
            log.error("Failed while while publishing metric to CW, {}", e);
        }
    }

    public static void main(final String[] args) throws Exception {
        final Integer canaryRunTime = Integer.parseInt(System.getenv("CANARY_DURATION_IN_SECONDS"));

        log.info("Stream name: {}", mStreamName);

        mCredentialsProvider = new EnvironmentVariableCredentialsProvider();
        mAmazonKinesisVideo = AmazonKinesisVideoClientBuilder.standard()
                .withRegion(mRegion)
                .withCredentials(mCredentialsProvider)
                .build();
        mCwClient = AmazonCloudWatchClientBuilder.standard()
                .withRegion(mRegion)
                .withCredentials(mCredentialsProvider)
                .build();

        switch (mCanaryLabel) {
            case "WebrtcLongRunning": {
                final CanaryFragmentList fragmentList = new CanaryFragmentList();

                Timer intervalMetricsTimer = new Timer("IntervalMetricsTimer");
                TimerTask intervalMetricsTask = new TimerTask() {
                    @Override
                    public void run() {
                        calculateFragmentContinuityMetric(fragmentList);
                    }
                };

                // Initial delay of 30s to allow for ListFragment response to be populated
                // TODO: revert back to 3000 after done testing
                final long intervalInitialDelay = 1000;
                final long intervalDelay = 20000; // NOTE: 16s interval was causing gaps in continuity, changing to 20s (2x the
                                                  // typical fragment duration coming from media server)

                // NOTE: Metric publishing will NOT begin if canaryRunTime is < intervalInitialDelay
                intervalMetricsTimer.scheduleAtFixedRate(intervalMetricsTask, intervalInitialDelay, intervalDelay);
                Thread.sleep(canaryRunTime * 1000);
                intervalMetricsTimer.cancel();
                break;
            }
            case "WebrtcPeriodic": {
                while ((System.currentTimeMillis() - mCanaryStartTime.getTime()) < canaryRunTime * 1000) {
                    calculateTimeToFirstFragment();
                }
                break;
            }
            default: {
                log.error("Env var CANARY_LABEL: {} must be set to either WebrtcLongRunning or WebrtcPeriodic",
                        mCanaryLabel);
                throw new Exception("CANARY_LABEL must be set to either WebrtcLongRunning or WebrtcPeriodic");
            }
        }
    }
}
