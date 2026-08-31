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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.Exception;
import java.text.MessageFormat;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
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
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMediaClient;
import com.amazonaws.services.kinesisvideo.model.ClipFragmentSelector;
import com.amazonaws.services.kinesisvideo.model.ClipTimestampRange;
import com.amazonaws.services.kinesisvideo.model.GetClipRequest;
import com.amazonaws.services.kinesisvideo.model.GetClipResult;
import com.amazonaws.services.kinesisvideo.model.ClipFragmentSelectorType;

import org.apache.log4j.Logger;
import org.apache.log4j.BasicConfigurator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

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

    private static AWSCredentialsProvider mCredentialsProvider;
    private static AmazonKinesisVideo mAmazonKinesisVideo;
    private static AmazonCloudWatch mCwClient;
    private static Timer mConnectionHeartbeatTimer;

    // Bound on a single verify.py subprocess (see runVerifyScript); a slow/hung verify is killed
    // rather than piling up. Segmenting/cadence for continuous soak verification lives in
    // SoakStreamVerifier (GetMedia -> ffmpeg segments -> per-segment verify).
    static final long SOAK_VERIFY_TIMEOUT_SECONDS = 600;

    private static void calculateFragmentContinuityMetric(CanaryFragmentList fragmentList) {
        try {
            final GetDataEndpointRequest dataEndpointRequest = new GetDataEndpointRequest()
                    .withAPIName(APIName.LIST_FRAGMENTS).withStreamName(mStreamName);
            final String listFragmentsEndpoint = mAmazonKinesisVideo.getDataEndpoint(dataEndpointRequest)
                    .getDataEndpoint();

            // Trailing window: list only the fragments since the previous check, NOT the whole
            // history since canary start. Anchoring the window start at canary start makes both the
            // ListFragments call and the retained list grow without bound over a long run (slower
            // ticks, unbounded memory, and a false "count went up = new fragment" signal once old
            // fragments age out of retention). A sliding window keeps each tick small and correct no
            // matter how long the canary has run -- required for continuous/soak runs. On the first
            // tick there is no prior cursor, so start at canary start.
            final Date windowStart = fragmentList.getLastCheckTime() != null
                    ? fragmentList.getLastCheckTime() : mCanaryStartTime;
            final Date windowEnd = new Date();

            TimestampRange timestampRange = new TimestampRange();
            timestampRange.setStartTimestamp(windowStart);
            timestampRange.setEndTimestamp(windowEnd);

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

                List<Fragment> windowFragments = futureTask.get();
                // Only advance the cursor on a successful list. If this tick threw, the cursor stays
                // put so the next window re-covers this interval and no fragments are missed.
                fragmentList.setLastCheckTime(windowEnd);

                // Fragments arrived in this interval iff the trailing window returned any.
                newFragmentReceived = !windowFragments.isEmpty();

                // IngestionIncomingBitrateKbps: bitrate of the fragments persisted to storage during
                // this interval: sum(bytes)*8 / sum(seconds) / 1000. This is the storage (ingestion)
                // side counterpart to the master's OutgoingBitrate and the viewer's
                // IncomingBitrateKbps. Every fragment in this window is new (window is since the last
                // check), so sum over the whole window. Only emitted when fragments with positive
                // duration arrived.
                if (newFragmentReceived) {
                    long deltaBytes = 0;
                    long deltaMillis = 0;
                    for (Fragment f : windowFragments) {
                        if (f.getFragmentSizeInBytes() != null) {
                            deltaBytes += f.getFragmentSizeInBytes();
                        }
                        if (f.getFragmentLengthInMilliseconds() != null) {
                            deltaMillis += f.getFragmentLengthInMilliseconds();
                        }
                    }
                    if (deltaMillis > 0) {
                        double ingestionBitrateKbps = (deltaBytes * 8.0) / (deltaMillis / 1000.0) / 1000.0;
                        publishMetricToCW("IngestionIncomingBitrateKbps", ingestionBitrateKbps, StandardUnit.None);
                    }
                }

                publishMetricToCW("FragmentReceived", newFragmentReceived ? 1.0 : 0.0, StandardUnit.None);

            } catch (Exception e) {
                logger.error("Failed while calculating continuity metric, " + e);
            }
        } catch (Exception e) {
            logger.error("Failed while fetching attributes for CanaryListFragmentWorker, " + e);
        }
    }

    /**
     * Per-minute persistence streaming availability heartbeat. Emits
     * PERSISTENCE_STREAMING_AVAILABILITY_METRIC_NAME = 1 when the consumer can reach KVS and
     * retrieve persisted media for the stream, and 0 when that fails (connection closed abruptly /
     * stream unreachable). This consumer is fragment/GetMedia based, so there is no long-lived
     * connection object to observe directly — a successful getDataEndpoint + ListFragments
     * round-trip is the most faithful "persistence streaming is available" signal.
     */
    private static void emitPersistenceStreamingAvailability() {
        boolean connectionActive = false;
        try {
            final GetDataEndpointRequest dataEndpointRequest = new GetDataEndpointRequest()
                    .withAPIName(APIName.LIST_FRAGMENTS).withStreamName(mStreamName);
            final String listFragmentsEndpoint = mAmazonKinesisVideo.getDataEndpoint(dataEndpointRequest)
                    .getDataEndpoint();

            // Bounded lookback (last heartbeat interval), NOT since canary start: this probe only
            // needs to confirm KVS is reachable and the stream is retrievable, and a whole-history
            // window would grow unbounded and slow over a long run. An empty result still counts as
            // available (a successful response, not the presence of fragments, is the signal).
            final Date now = new Date();
            final Date lookbackStart = new Date(now.getTime() - CanaryConstants.CONNECTION_HEARTBEAT_INTERVAL);

            TimestampRange timestampRange = new TimestampRange();
            timestampRange.setStartTimestamp(lookbackStart);
            timestampRange.setEndTimestamp(now);

            FragmentSelector fragmentSelector = new FragmentSelector();
            fragmentSelector.setFragmentSelectorType(FragmentSelectorType.SERVER_TIMESTAMP.toString());
            fragmentSelector.setTimestampRange(timestampRange);

            try (CanaryListFragmentWorker listFragmentWorker = new CanaryListFragmentWorker(mStreamName,
                    mCredentialsProvider, listFragmentsEndpoint, Regions.fromName(mRegion), fragmentSelector)) {
                final FutureTask<List<Fragment>> futureTask = new FutureTask<>(listFragmentWorker);
                Thread thread = new Thread(futureTask);
                thread.start();
                // A successful response means the consumer reached KVS and the stream is
                // retrievable — the consumer connection is active for this interval.
                futureTask.get();
                connectionActive = true;
            }
        } catch (Exception e) {
            logger.error("Consumer connection heartbeat probe failed (connection closed/failed), " + e);
        }
        publishMetricToCW(CanaryConstants.PERSISTENCE_STREAMING_AVAILABILITY_METRIC_NAME,
                connectionActive ? 1.0 : 0.0, StandardUnit.None);
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

    /**
     * Blocks the calling (main) thread forever so the consumer runs until the process is killed,
     * used for continuous/soak runs. The metric timers (fragment continuity + persistence
     * heartbeat) are non-daemon and keep emitting for the life of the JVM; the finite
     * sleep-then-shutdown path is skipped. Uses a latch that is never counted down (no busy-wait);
     * only returns if the thread is interrupted (process shutdown).
     */
    private static void awaitForever() throws InterruptedException {
        new CountDownLatch(1).await();
    }

    protected static void shutdownCanaryResources() {
        if (mConnectionHeartbeatTimer != null) {
            mConnectionHeartbeatTimer.cancel();
        }
        mCwClient.shutdown();
        mAmazonKinesisVideo.shutdown();
    }

    /**
     * Downloads an MP4 clip of the stream via the GetClip API for video verification.
     * The clip covers from canary start time to now.
     */
    private static void downloadClip(Date startTime, Date endTime) {
        String outputPath = System.getenv().getOrDefault(
                CanaryConstants.CLIP_OUTPUT_PATH_ENV_VAR,
                CanaryConstants.DEFAULT_CLIP_OUTPUT_PATH);
        downloadClip(startTime, endTime, outputPath);
    }

    private static void downloadClip(Date startTime, Date endTime, String outputPath) {
        logger.info("downloadClip: outputPath='" + outputPath + "', stream=" + mStreamName
                + ", startTime=" + startTime + ", endTime=" + endTime);
        try {
            // Get the archived media endpoint
            logger.info("downloadClip: getting data endpoint for GET_CLIP...");
            final GetDataEndpointRequest endpointRequest = new GetDataEndpointRequest()
                    .withAPIName(APIName.GET_CLIP)
                    .withStreamName(mStreamName);
            final String clipEndpoint = mAmazonKinesisVideo.getDataEndpoint(endpointRequest).getDataEndpoint();
            logger.info("downloadClip: clipEndpoint=" + clipEndpoint);

            // Bound the GetClip call so a stalled request can't hang the consumer
            // indefinitely (root cause of the 2026-07 gamma queue pile-up, see
            // canary/webrtc-c/docs/gamma-queue-pileup-investigation.md). A hung
            // GetClip now surfaces as an exception caught below -> no clip file ->
            // the runner reports ConsumerStorageAvailability=0 instead of wedging.
            // The payload download itself is additionally bounded by the socket
            // timeout (fires only after 60s of complete silence, so it never
            // interrupts an active large-clip download).
            ClientConfiguration clipClientConfig = new ClientConfiguration()
                    .withConnectionTimeout(10 * 1000)
                    .withSocketTimeout(60 * 1000)
                    .withRequestTimeout(2 * 60 * 1000)
                    .withClientExecutionTimeout(10 * 60 * 1000);

            AmazonKinesisVideoArchivedMedia archivedMediaClient = AmazonKinesisVideoArchivedMediaClient
                    .builder()
                    .withClientConfiguration(clipClientConfig)
                    .withCredentials(mCredentialsProvider)
                    .withEndpointConfiguration(
                            new com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration(
                                    clipEndpoint, mRegion))
                    .build();
            logger.info("downloadClip: archived media client built");

            ClipTimestampRange timestampRange = new ClipTimestampRange()
                    .withStartTimestamp(startTime)
                    .withEndTimestamp(endTime);

            ClipFragmentSelector fragmentSelector = new ClipFragmentSelector()
                    .withFragmentSelectorType(ClipFragmentSelectorType.SERVER_TIMESTAMP)
                    .withTimestampRange(timestampRange);

            GetClipRequest getClipRequest = new GetClipRequest()
                    .withStreamName(mStreamName)
                    .withClipFragmentSelector(fragmentSelector);

            logger.info("Calling GetClip for stream " + mStreamName
                    + " from " + startTime + " to " + endTime);

            GetClipResult result = archivedMediaClient.getClip(getClipRequest);
            logger.info("downloadClip: GetClip returned, content-type=" + result.getContentType());

            try (InputStream payload = result.getPayload();
                 FileOutputStream fos = new FileOutputStream(new File(outputPath))) {
                byte[] buf = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                while ((bytesRead = payload.read(buf)) != -1) {
                    fos.write(buf, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                logger.info("GetClip saved " + totalBytes + " bytes to " + outputPath);
            }

            // Verify file was actually written
            File clipFile = new File(outputPath);
            logger.info("downloadClip: file exists=" + clipFile.exists() + ", size=" + clipFile.length());

            archivedMediaClient.shutdown();
            logger.info("downloadClip: done");
        } catch (Exception e) {
            logger.error("GetClip failed: " + e.getMessage(), e);
        }
    }

    /**
     * Runs the repo's verify.py against a downloaded clip/segment when CANARY_VERIFY_SCRIPT is
     * set, returning its storage_availability verdict (true=pass). Returns null when no script is
     * configured, so the caller can fall back to a plain ffprobe check.
     *
     * Non-interference with the ListFragments continuity/heartbeat work is guaranteed three ways:
     *   1. It's a separate OS process (not JVM threads), launched from a single-threaded worker
     *      (SoakStreamVerifier), so verifications never overlap and never block a ListFragments tick.
     *   2. It runs under `nice -n 19`, so the CPU-heavy frame-extraction/OCR/SSIM work is
     *      deprioritized behind everything else (the ListFragments calls are network-bound anyway).
     *   3. It's bounded by SOAK_VERIFY_TIMEOUT — a slow/hung verify is killed, never piling up.
     */
    static Boolean runVerifyScript(String clipPath, long expectedDurationSeconds) {
        final String script = System.getenv("CANARY_VERIFY_SCRIPT");
        if (script == null || script.isEmpty()) {
            return null;
        }
        final String python = System.getenv().getOrDefault("CANARY_VERIFY_PYTHON", "python3");
        final String mode = System.getenv().getOrDefault("CANARY_VERIFY_MODE", "presence");
        final String sourceFrames = System.getenv("CANARY_VERIFY_SOURCE_FRAMES");
        try {
            final List<String> cmd = new ArrayList<>();
            cmd.add("nice");
            cmd.add("-n");
            cmd.add("19");
            cmd.add(python);
            cmd.add(script);
            cmd.add("--recording");
            cmd.add(clipPath);
            cmd.add("--mode");
            cmd.add(mode);
            cmd.add("--expected-duration");
            cmd.add(String.valueOf(expectedDurationSeconds));
            cmd.add("--json");
            if ("ssim".equalsIgnoreCase(mode) && sourceFrames != null && !sourceFrames.isEmpty()) {
                cmd.add("--source-frames");
                cmd.add(sourceFrames);
            }
            final ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            final StringBuilder out = new StringBuilder();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = is.read(buf)) != -1) {
                    out.append(new String(buf, 0, n));
                }
            }
            if (!p.waitFor(SOAK_VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                logger.error("Soak verification: verify.py timed out after " + SOAK_VERIFY_TIMEOUT_SECONDS + "s");
                return false;
            }
            final String output = out.toString();
            // Emit the temporal-drift gauges (separate signal from availability, which is
            // content-only). Best-effort: absent in presence mode / on parse failure.
            emitJsonNumberAsMetric(output, "avg_drift_seconds", "FrameTimestampDriftSeconds");
            emitJsonNumberAsMetric(output, "max_drift_seconds", "FrameTimestampDriftMaxSeconds");
            // Parse storage_availability from the --json output without pulling in a JSON dependency.
            final Matcher m = Pattern.compile("\"storage_availability\"\\s*:\\s*([0-9.]+)").matcher(output);
            if (m.find()) {
                final double avail = Double.parseDouble(m.group(1));
                logger.info("Soak verification (verify.py, mode=" + mode + "): storage_availability=" + avail);
                return avail >= 1.0;
            }
            logger.error("Soak verification: could not parse storage_availability from verify.py output: "
                    + output.trim());
            return false;
        } catch (Exception e) {
            logger.error("Soak verification: verify.py invocation failed, " + e);
            return false;
        }
    }

    /**
     * Extracts a numeric field from verify.py's --json output and publishes it as a CloudWatch
     * gauge (Seconds). Best-effort: silently does nothing if the field is absent (e.g. presence
     * mode, which emits no drift), so it never affects the availability path.
     */
    private static void emitJsonNumberAsMetric(String json, String field, String metricName) {
        try {
            final Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*([0-9.]+)").matcher(json);
            if (m.find()) {
                publishMetricToCW(metricName, Double.parseDouble(m.group(1)), StandardUnit.Seconds);
            }
        } catch (Exception e) {
            logger.error("Soak verification: failed to emit " + metricName + ", " + e);
        }
    }

    /**
     * Returns true if ffprobe reports a positive duration for the clip (i.e. it is a decodable
     * container with media). Returns false -- never throws -- if ffprobe is missing, times out, or
     * the clip is invalid, so the caller can treat "not decodable" and "can't check" alike.
     */
    static boolean probeDecodable(String path) {
        try {
            final ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", path);
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            final StringBuilder out = new StringBuilder();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[512];
                int n;
                while ((n = is.read(buf)) != -1) {
                    out.append(new String(buf, 0, n));
                }
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                logger.error("Soak verification: ffprobe timed out");
                return false;
            }
            final double duration = Double.parseDouble(out.toString().trim());
            logger.info("Soak verification: clip duration=" + duration + "s");
            return duration > 0;
        } catch (Exception e) {
            logger.error("Soak verification: ffprobe probe failed (missing ffprobe or invalid clip), " + e);
            return false;
        }
    }

    public static void main(final String[] args) throws Exception {
        BasicConfigurator.configure();

        // Fail fast if anything escapes main(). Non-daemon timers (e.g. the persistence
        // heartbeat, scheduled before the run-mode switch) otherwise keep the JVM alive
        // as a zombie after main dies: it emits misleading heartbeat metrics every
        // minute until the pipeline hard timeout kills the build ~35+ minutes later
        // (seen with unrecognized CANARY_LABEL values on gamma and rpi runs).
        Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
            logger.error("Fatal error in main, exiting: " + throwable.getMessage(), throwable);
            System.exit(1);
        });

        final Integer canaryRunTime = Integer.parseInt(System.getenv("CANARY_DURATION_IN_SECONDS"));

        // Continuous/soak mode: instead of running for CANARY_DURATION_IN_SECONDS and exiting, keep
        // the metric timers alive and run until the process is killed. The end-of-run GetClip and
        // clean shutdown are skipped (there is no end); soak health comes from the continuous
        // FragmentReceived / PersistenceStreamingAvailability metrics. NOTE: the Jenkins job still
        // wraps the consumer in a hard timeout, so an actual never-ending run also requires lifting
        // those runner-side bounds -- this flag removes only the consumer's own fixed-duration exit.
        final boolean runForever = "true".equalsIgnoreCase(System.getenv("CANARY_CONTINUOUS"));

        logger.info("Stream name: " + mStreamName);
        logger.info("Region: " + mRegion);
        logger.info("Canary label: " + mCanaryLabel);
        logger.info("Canary run time: " + canaryRunTime + "s");
        logger.info("Continuous mode: " + runForever);

        String controlPlaneUri = System.getenv("CONTROL_PLANE_URI");
        logger.info("Control plane URI: " + (controlPlaneUri != null ? controlPlaneUri : "(default)"));

        // Credential selection.
        //   - Default (short runs): static credentials from the environment. They're minted
        //     once by the runner and are fine for a bounded run.
        //   - Continuous/soak runs (CANARY_CREDENTIALS_ROLE_ARN set): auto-refreshing
        //     assume-role credentials. A single static STS session expires (<=12h, and only
        //     1h on role-chained nodes), which would fail every KVS/CloudWatch call partway
        //     through a soak. STSAssumeRoleSessionCredentialsProvider re-assumes the role in
        //     the background before expiry, so the consumer can run indefinitely.
        //     IMPORTANT: the base credentials for the re-assume are the node's instance
        //     profile (InstanceProfileCredentialsProvider), NOT the ambient AWS_* env creds —
        //     those env creds are themselves a temporary Canary-STS session, and assuming
        //     Canary-STS from Canary-STS is a self-assume that fails. This requires the
        //     consumer node's instance profile to be trusted by the assumed role.
        final String credsRoleArn = System.getenv("CANARY_CREDENTIALS_ROLE_ARN");
        if (credsRoleArn != null && !credsRoleArn.isEmpty()) {
            final AWSSecurityTokenService stsClient = AWSSecurityTokenServiceClientBuilder.standard()
                    .withRegion(mRegion)
                    .withCredentials(InstanceProfileCredentialsProvider.getInstance())
                    .build();
            mCredentialsProvider = new STSAssumeRoleSessionCredentialsProvider.Builder(credsRoleArn, "canary-consumer")
                    .withStsClient(stsClient)
                    .build();
            logger.info("Using auto-refreshing assume-role credentials (role=" + credsRoleArn
                    + ", base=instance profile) for continuous run");
        } else {
            mCredentialsProvider = new EnvironmentVariableCredentialsProvider();
            logger.info("Using static environment-variable credentials");
        }

        AmazonKinesisVideoClientBuilder kvsBuilder = AmazonKinesisVideoClientBuilder.standard()
                .withCredentials(mCredentialsProvider);
        if (controlPlaneUri != null && !controlPlaneUri.isEmpty()) {
            kvsBuilder.withEndpointConfiguration(
                    new com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration(controlPlaneUri, mRegion));
        } else {
            kvsBuilder.withRegion(mRegion);
        }
        mAmazonKinesisVideo = kvsBuilder.build();

        mCwClient = AmazonCloudWatchClientBuilder.standard()
                .withRegion(mRegion)
                .withCredentials(mCredentialsProvider)
                .build();

        // Per-minute persistence streaming availability heartbeat, covering both periodic and
        // reconnect run modes. Started here (before the run-mode switch) and cancelled in
        // shutdownCanaryResources(). Uses the same initial delay as the fragment continuity check so
        // we don't record a spurious 0 before the stream has been created by the master.
        mConnectionHeartbeatTimer = new Timer("PersistenceStreamingAvailabilityTimer");
        mConnectionHeartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                emitPersistenceStreamingAvailability();
            }
        }, CanaryConstants.LIST_FRAGMENTS_INITIAL_DELAY, CanaryConstants.CONNECTION_HEARTBEAT_INTERVAL);

        // Soak video verification: the runner's end-of-run GetClip+verify.py stage never runs in
        // continuous mode (there is no end), so verify the ingested media CONTINUOUSLY instead:
        // SoakStreamVerifier pulls the stream via GetMedia, an ffmpeg subprocess splits it into
        // fixed-length segments, and every segment is verified with verify.py (SSIM against the
        // sample frames for framesrc/disk, presence otherwise) -- 100% coverage, no sampling gaps.
        // All heavy work runs in niced subprocesses off dedicated threads, so it never blocks the
        // ListFragments continuity/heartbeat threads. Emits SoakVideoDecodable + drift per segment.
        if (runForever && "true".equalsIgnoreCase(System.getenv(CanaryConstants.VIDEO_VERIFY_ENABLED_ENV_VAR))) {
            new SoakStreamVerifier(mStreamName, mRegion, mCredentialsProvider, mAmazonKinesisVideo).start();
        }

        switch (mCanaryLabel) {
            case CanaryConstants.PERIODIC_LABEL:
            case CanaryConstants.GAMMA_PERIODIC_LABEL:
            case CanaryConstants.LOW_FPS_LABEL:
            case CanaryConstants.GAMMA_LOW_FPS_LABEL:
            case CanaryConstants.PERIODIC_500KBPS_LABEL:
            case CanaryConstants.PERIODIC_1MBPS_LABEL:
            case CanaryConstants.PERIODIC_5MBPS_LABEL:
            case CanaryConstants.GAMMA_PERIODIC_500KBPS_LABEL:
            case CanaryConstants.GAMMA_PERIODIC_1MBPS_LABEL:
            case CanaryConstants.GAMMA_PERIODIC_5MBPS_LABEL:
            case CanaryConstants.SHORT_VA_MASTER_RO_VIEWER_500KBPS_LABEL:
            case CanaryConstants.SHORT_VA_MASTER_RO_VIEWER_1MBPS_LABEL:
            case CanaryConstants.SHORT_VA_MASTER_RO_VIEWER_5MBPS_LABEL:
            case CanaryConstants.GAMMA_SHORT_VA_MASTER_RO_VIEWER_500KBPS_LABEL:
            case CanaryConstants.GAMMA_SHORT_VA_MASTER_RO_VIEWER_1MBPS_LABEL:
            case CanaryConstants.GAMMA_SHORT_VA_MASTER_RO_VIEWER_5MBPS_LABEL:
            // Viewer scenarios with a co-resident consumer (VIDEO_VERIFY_ENABLED): same
            // short-duration verification path (GetMedia TTFF + fragment continuity + GetClip).
            case CanaryConstants.WITH_VIEWER_LABEL:
            case CanaryConstants.TWO_VIEWERS_LABEL:
            case CanaryConstants.THREE_VIEWERS_LABEL:
            case CanaryConstants.GAMMA_WITH_VIEWER_LABEL:
            case CanaryConstants.GAMMA_TWO_VIEWERS_LABEL:
            case CanaryConstants.GAMMA_THREE_VIEWERS_LABEL: {
                logger.info("Periodic case: canaryRunTime=" + canaryRunTime
                        + "s, mCanaryStartTime=" + mCanaryStartTime
                        + ", now=" + new Date()
                        + ", elapsed=" + (System.currentTimeMillis() - mCanaryStartTime.getTime()) + "ms");

                // Fire off GetMedia to measure time-to-first-frame, but don't block on it
                // for the full stream duration. Run it in a background thread and let it
                // get interrupted when we're done waiting.
                final ExecutorService periodicExecutor = Executors.newSingleThreadExecutor();
                periodicExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        calculateTimeToFirstFragment();
                    }
                });

                // Also run fragment continuity checks for periodic runs
                final CanaryFragmentList periodicFragmentList = new CanaryFragmentList();
                Timer periodicFragmentTimer = new Timer("PeriodicFragmentContinuityTimer");
                TimerTask periodicFragmentTask = new TimerTask() {
                    @Override
                    public void run() {
                        calculateFragmentContinuityMetric(periodicFragmentList);
                    }
                };
                periodicFragmentTimer.scheduleAtFixedRate(periodicFragmentTask,
                        CanaryConstants.LIST_FRAGMENTS_INITIAL_DELAY, CanaryConstants.LIST_FRAGMENTS_INTERVAL);

                if (runForever) {
                    logger.info("Continuous mode: consumer runs until killed; metric timers stay "
                            + "active (no fixed duration, no end-of-run GetClip)");
                    awaitForever();
                }

                // Wait for the canary duration
                long remainingMs = (canaryRunTime * CanaryConstants.MILLISECONDS_IN_A_SECOND)
                        - (System.currentTimeMillis() - mCanaryStartTime.getTime());
                if (remainingMs > 0) {
                    logger.info("Sleeping for " + remainingMs + "ms until canary duration elapses");
                    Thread.sleep(remainingMs);
                }
                logger.info("Periodic duration elapsed, shutting down GetMedia worker");
                periodicExecutor.shutdownNow();
                periodicFragmentTimer.cancel();

                // Download clip for video verification if enabled
                String videoVerifyEnabled = System.getenv(CanaryConstants.VIDEO_VERIFY_ENABLED_ENV_VAR);
                logger.info("Periodic path: VIDEO_VERIFY_ENABLED='" + videoVerifyEnabled + "', canaryRunTime=" + canaryRunTime);
                if ("true".equalsIgnoreCase(videoVerifyEnabled)) {
                    downloadClip(mCanaryStartTime, new Date());
                }

                shutdownCanaryResources();
                break;
            }
            // Non-periodic cases.
            default: {
                // Handle if incorrect label.
                if (!mCanaryLabel.equals(CanaryConstants.EXTENDED_LABEL) &&
                        !mCanaryLabel.equals(CanaryConstants.SINGLE_RECONNECT_LABEL) &&
                        !mCanaryLabel.equals(CanaryConstants.SUB_RECONNECT_LABEL) &&
                        !mCanaryLabel.equals(CanaryConstants.GAMMA_SINGLE_RECONNECT_LABEL) &&
                        !mCanaryLabel.equals(CanaryConstants.GAMMA_SUB_RECONNECT_LABEL)) {
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

                if (runForever) {
                    logger.info("Continuous mode: consumer runs until killed; metric timers stay "
                            + "active (no fixed duration, no end-of-run GetClip)");
                    awaitForever();
                }

                Thread.sleep(canaryRunTime * CanaryConstants.MILLISECONDS_IN_A_SECOND);
                intervalMetricsTimer.cancel();

                // Download clip for video verification if enabled
                String videoVerifyEnabled = System.getenv(CanaryConstants.VIDEO_VERIFY_ENABLED_ENV_VAR);
                if ("true".equalsIgnoreCase(videoVerifyEnabled)) {
                    downloadClip(mCanaryStartTime, new Date());
                }

                shutdownCanaryResources();
                break;
            }
        }
    }
}
