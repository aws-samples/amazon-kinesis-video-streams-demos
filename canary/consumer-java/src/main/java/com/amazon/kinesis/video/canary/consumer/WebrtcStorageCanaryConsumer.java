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
import com.amazonaws.services.kinesisvideo.model.FragmentSelectorType;
import com.amazonaws.services.kinesisvideo.model.Fragment;

import org.apache.log4j.Logger;
import org.apache.log4j.BasicConfigurator;

/*
 * Canary for WebRTC with Storage thro Media Server
 * 
 * For longRun-configured jobs, this Canary will emit FragmentContinuity metrics by continuously
 * checking for any newly ingested fragments for the given stream. The fragment list is checked
 * for new fragments every "fragmentDuration * 2" The fragmentDuration is set by the encoder in
 * the WebRTC Storage Media Server.
 * 
 * For periodic-configured jobs, this Canary will emit time to first frame consumed metrics by
 * continuously checking for consumable media from the specified stream via GetMedia calls.
 */

public class WebrtcStorageCanaryConsumer {
    static final Logger logger = Logger.getLogger(WebrtcStorageCanaryConsumer.class);

    protected static final Date mCanaryStartTime = new Date();
    protected static final String mStreamName = System.getenv(CanaryConstants.CANARY_STREAM_NAME_ENV_VAR);
    protected static final String firstFrameSentTSFile = System.getenv()
            .getOrDefault(CanaryConstants.FIRST_FRAME_TS_FILE_ENV_VAR, CanaryConstants.DEFAULT_FIRST_FRAME_TS_FILE);

    private static final String mCanaryLabel = System.getenv(CanaryConstants.CANARY_LABEL_ENV_VAR);
    private static final String mRegion = System.getenv(CanaryConstants.AWS_DEFAULT_REGION_ENV_VAR);

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
            fragmentSelector.setFragmentSelectorType(FragmentSelectorType.SERVER_TIMESTAMP.toString());
            fragmentSelector.setTimestampRange(timestampRange);

            Boolean newFragmentReceived = false;

            // Try with resources to utilize AutoClosable implementation of
            // CanaryListFragmentWorker
            try (CanaryListFragmentWorker listFragmentWorker = new CanaryListFragmentWorker(mStreamName,
                    mCredentialsProvider, listFragmentsEndpoint, Regions.fromName(mRegion), fragmentSelector)) {
                final FutureTask<List<Fragment>> futureTask = new FutureTask<>(listFragmentWorker);
                Thread thread = new Thread(futureTask);
                thread.start();

                List<Fragment> newFragmentList = futureTask.get();
                // NOTE: The below newFragmentReceived logic assumes that fragments are not
                // expiring, so stream retention must be greater than canary run duration.
                if (newFragmentList.size() > fragmentList.getFragmentList().size()) {
                    newFragmentReceived = true;
                }

                fragmentList.setFragmentList(newFragmentList);
                publishMetricToCW("FragmentReceived", newFragmentReceived ? 1.0 : 0.0, StandardUnit.None);

            } catch (Exception e) {
                logger.error("Failed while calculating continuity metric, " + e);
            }
        } catch (Exception e) {
            logger.error("Failed while fetching attributes for CanaryListFragmentWorker, " + e);
        }
    }

    private static void calculateTimeToFirstFragment() {
        try {
            final StartSelector startSelector = new StartSelector()
                    .withStartSelectorType(StartSelectorType.PRODUCER_TIMESTAMP).withStartTimestamp(mCanaryStartTime);

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

        } catch (Exception e) {
            logger.error("Failed while calculating time to first fragment, " + e);
        }
    }

    protected static void publishMetricToCW(String metricName, double value, StandardUnit cwUnit) {
        try {
            logger.info("Emitting the following metric: " + metricName + " - " + value);
            final Dimension dimensionPerStream = new Dimension()
                    .withName(CanaryConstants.CW_DIMENSION_INDIVIDUAL)
                    .withValue(mStreamName);
            final Dimension aggregatedDimension = new Dimension()
                    .withName(CanaryConstants.CW_DIMENSION_AGGREGATE)
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
            logger.error("Failed while while publishing metric to CW, " + e);
        }
    }

    protected static void shutdownCanaryResources() {
        mCwClient.shutdown();
        mAmazonKinesisVideo.shutdown();
    }

    public static void main(final String[] args) throws Exception {
        BasicConfigurator.configure();

        final Integer canaryRunTime = Integer.parseInt(System.getenv("CANARY_DURATION_IN_SECONDS"));

        logger.info("Stream name: " + mStreamName);

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
            case CanaryConstants.PERIODIC_LABEL: {
                // Continuously attempt getMedia() calls until footage is available.
                // GetMedia() will return after ~3 sec if no footage is available. Once footage
                // is available,
                // it will continue to visit frames as long as there is footage available.
                while ((System.currentTimeMillis() - mCanaryStartTime.getTime()) < canaryRunTime
                        * CanaryConstants.MILLISECONDS_IN_A_SECOND) {
                    calculateTimeToFirstFragment();
                }
                break;
            }
            // Non-periodic cases.
            default: {
                // Handle if incorrect label.
                if (!mCanaryLabel.equals(CanaryConstants.EXTENDED_LABEL) &&
                        !mCanaryLabel.equals(CanaryConstants.SINGLE_RECONNECT_LABEL) &&
                        !mCanaryLabel.equals(CanaryConstants.SUB_RECONNECT_LABEL)) {
                    logger.error(String.format("Env var CANARY_LABEL: %s must be set to either %s, %s, %s, or %s.",
                            mCanaryLabel, CanaryConstants.PERIODIC_LABEL, CanaryConstants.EXTENDED_LABEL,
                            CanaryConstants.SINGLE_RECONNECT_LABEL, CanaryConstants.SUB_RECONNECT_LABEL));
                    throw new Exception("Improper canary label " + mCanaryLabel + " assigned to "
                            + CanaryConstants.CANARY_LABEL_ENV_VAR + " env var.");
                }
                final CanaryFragmentList fragmentList = new CanaryFragmentList();

                Timer intervalMetricsTimer = new Timer(CanaryConstants.INTERVAL_METRICS_TIMER_NAME);
                TimerTask intervalMetricsTask = new TimerTask() {
                    @Override
                    public void run() {
                        calculateFragmentContinuityMetric(fragmentList);
                    }
                };

                // NOTE: Metric publishing will NOT begin if canaryRunTime is <
                // intervalInitialDelay
                intervalMetricsTimer.scheduleAtFixedRate(intervalMetricsTask,
                        CanaryConstants.LIST_FRAGMENTS_INITIAL_DELAY, CanaryConstants.LIST_FRAGMENTS_INTERVAL);
                Thread.sleep(canaryRunTime * CanaryConstants.MILLISECONDS_IN_A_SECOND);
                intervalMetricsTimer.cancel();
                shutdownCanaryResources();
                break;
            }
        }
    }
}
