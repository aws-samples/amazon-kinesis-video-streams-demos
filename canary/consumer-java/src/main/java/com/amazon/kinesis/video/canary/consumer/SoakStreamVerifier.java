package com.amazon.kinesis.video.canary.consumer;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoMediaClientBuilder;
import com.amazonaws.services.kinesisvideo.model.APIName;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointRequest;
import com.amazonaws.services.kinesisvideo.model.GetMediaRequest;
import com.amazonaws.services.kinesisvideo.model.GetMediaResult;
import com.amazonaws.services.kinesisvideo.model.StartSelector;
import com.amazonaws.services.kinesisvideo.model.StartSelectorType;
import com.amazonaws.services.cloudwatch.model.StandardUnit;

import org.apache.log4j.Logger;

/*
 * Continuous soak video verification: GetMedia -> ffmpeg segmenter -> per-segment verify.py.
 *
 * Unlike the old periodic GetClip probe (which sampled ~156s every 15min, leaving most of the
 * stream unverified), this pulls the ingested stream CONTINUOUSLY and verifies every segment, so
 * every minute of media is content-checked (SSIM for deterministic sources) with no coverage gaps.
 *
 * Pipeline (all heavy work outside the JVM, in niced subprocesses):
 *   1. Pull thread (daemon, blocking I/O only): GetMedia from the stream's data endpoint and pump
 *      the MKV byte stream into ffmpeg's stdin. Reconnects with backoff on EOF/error (e.g. the
 *      master reconnecting), re-resolving the endpoint and re-reading credentials each time (the
 *      credentials provider auto-refreshes, so a soak never dies on expiry here).
 *   2. ffmpeg subprocess (nice): -c:v copy (no transcode) split into SEGMENT_SECONDS mp4 segments
 *      in a spool dir. Video only (-an); verification is video-based.
 *   3. Segment worker (single-threaded, fixed-delay): picks up finished segments (all but the
 *      newest, which ffmpeg is still writing, and the generation-boundary ones -- see
 *      boundarySegments), runs verify.py on each via
 *      WebrtcStorageCanaryConsumer.runVerifyScript (itself a niced, timeout-bounded subprocess),
 *      publishes SoakVideoDecodable (+ drift metrics inside runVerifyScript), and deletes the
 *      segment. Fixed-delay + single thread means verifies never overlap; if verification falls
 *      behind, the oldest pending segments are skipped (SoakSegmentSkipped) instead of filling the
 *      disk -- backpressure never touches the pull side or the ListFragments/heartbeat threads.
 */
public class SoakStreamVerifier {
    static final Logger logger = Logger.getLogger(SoakStreamVerifier.class);

    // Segment length = verification granularity: every SEGMENT_SECONDS of media yields one
    // SoakVideoDecodable datapoint. verify.py thresholds scale with --expected-duration.
    private static final long SEGMENT_SECONDS = 60;
    // Max finished segments awaiting verification before we start dropping the oldest.
    private static final int MAX_PENDING_SEGMENTS = 5;
    private static final long PULL_RETRY_BACKOFF_MS = 5_000;
    private static final long WORKER_DELAY_SECONDS = 10;

    private final String streamName;
    private final String region;
    private final AWSCredentialsProvider credentialsProvider;
    private final AmazonKinesisVideo kvsClient;

    // Emit an explicit SoakVideoDecodable=0.0 when no segment has been verified for this long
    // (media outage -> ffmpeg produces no segments -> the worker would otherwise go silent for
    // the whole gap, as it did for ~54min in the first soak). Keeps the metric a continuous
    // signal instead of relying purely on missing-datapoint alarms.
    private static final long NO_SEGMENT_EMIT_MS = 3 * SEGMENT_SECONDS * 1000;

    private File spoolDir;
    private volatile Process ffmpeg;
    private volatile long lastEmitMs;

    // Segments at a generation boundary are structurally incomplete rather than undecodable
    // media, so they must not count as SoakVideoDecodable=0:
    //   - on the way out, ffmpeg is killed mid-write (destroyForcibly below), leaving a
    //     truncated segment with no moov atom;
    //   - on the way in, GetMedia's NOW start selector drops us mid-GOP, so the first segment
    //     of a generation can lack a leading keyframe.
    // Both used to be handed to verify.py: on the 2026-09-03 soak 35 of 46 zeros fell within
    // 600s of one of the 17 hourly reconnects. They are discarded and counted separately so a
    // change in the rate is still visible instead of being smoothed into the availability
    // metric. The comment on processSegments' "all but the newest are finished" only holds
    // within one generation -- a new generation reseeds segment numbering upward, so the dying
    // generation's truncated tail stops being the newest file and becomes eligible.
    private final Set<String> boundarySegments = ConcurrentHashMap.newKeySet();
    private volatile long currentGenStart;

    public SoakStreamVerifier(String streamName, String region, AWSCredentialsProvider credentialsProvider,
                              AmazonKinesisVideo kvsClient) {
        this.streamName = streamName;
        this.region = region;
        this.credentialsProvider = credentialsProvider;
        this.kvsClient = kvsClient;
    }

    public void start() {
        try {
            spoolDir = Files.createTempDirectory("soak-verify-spool").toFile();
        } catch (Exception e) {
            logger.error("SoakStreamVerifier: failed to create spool dir, verification disabled, " + e);
            return;
        }
        lastEmitMs = System.currentTimeMillis(); // grace period before the first no-segment 0.0
        logger.info("SoakStreamVerifier: continuous verification started (segment=" + SEGMENT_SECONDS
                + "s, spool=" + spoolDir.getAbsolutePath() + ")");

        final Thread pullThread = new Thread(this::pullLoop, "SoakStreamPull");
        pullThread.setDaemon(true);
        pullThread.start();

        final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SoakSegmentWorker");
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(this::processSegments, SEGMENT_SECONDS, WORKER_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ pull side

    private void pullLoop() {
        while (true) {
            try {
                pullOnce();
            } catch (Exception e) {
                logger.error("SoakStreamVerifier: GetMedia pull ended, reconnecting: " + e);
            }
            try {
                Thread.sleep(PULL_RETRY_BACKOFF_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pullOnce() throws Exception {
        // Fresh endpoint + creds each (re)connect; the provider auto-refreshes in soak mode.
        final String endpoint = kvsClient.getDataEndpoint(new GetDataEndpointRequest()
                .withAPIName(APIName.GET_MEDIA).withStreamName(streamName)).getDataEndpoint();
        final AmazonKinesisVideoMedia media = AmazonKinesisVideoMediaClientBuilder.standard()
                .withCredentials(credentialsProvider)
                .withEndpointConfiguration(
                        new com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration(endpoint, region))
                .build();
        Process proc = null;
        try {
            final GetMediaResult result = media.getMedia(new GetMediaRequest()
                    .withStreamName(streamName)
                    .withStartSelector(new StartSelector().withStartSelectorType(StartSelectorType.NOW)));

            proc = startFfmpeg();
            ffmpeg = proc;
            logger.info("SoakStreamVerifier: GetMedia connected, segmenting...");

            try (InputStream in = result.getPayload(); OutputStream out = proc.getOutputStream()) {
                final byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
        } finally {
            if (proc != null) {
                try {
                    proc.getOutputStream().close();
                } catch (Exception ignore) {
                }
                if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
                markGenerationTailAsBoundary();
            }
            media.shutdown();
        }
    }

    private Process startFfmpeg() throws Exception {
        // Seed numbering with epoch seconds so a reconnect's new ffmpeg never collides with
        // (or sorts before) segments still pending from the previous session.
        currentGenStart = System.currentTimeMillis() / 1000;
        boundarySegments.add(segmentName(currentGenStart));
        final ProcessBuilder pb = new ProcessBuilder(
                "nice", "-n", "19", "ffmpeg",
                "-hide_banner", "-loglevel", "error",
                "-i", "pipe:0",
                "-an", "-c:v", "copy",
                "-f", "segment",
                "-segment_time", String.valueOf(SEGMENT_SECONDS),
                "-segment_start_number", String.valueOf(currentGenStart),
                "-reset_timestamps", "1",
                new File(spoolDir, "seg_%010d.mp4").getAbsolutePath());
        // Drain ffmpeg's output into a log file so a full pipe can never stall it.
        final File log = new File(spoolDir, "ffmpeg.log");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(log));
        return pb.start();
    }

    private static String segmentName(long number) {
        return String.format("seg_%010d.mp4", number);
    }

    /*
     * Mark the segment this generation's ffmpeg was writing when it exited. Called after the
     * process is gone, so the highest-numbered file from this generation is by definition the
     * one that was open: on a forced kill it is truncated, and even on a clean stdin close it
     * is a short tail that would fail the duration threshold. Either way it is a boundary
     * artefact, not a media failure.
     */
    private void markGenerationTailAsBoundary() {
        final File[] segs = spoolDir.listFiles((d, name) -> name.startsWith("seg_") && name.endsWith(".mp4"));
        if (segs == null || segs.length == 0) {
            return;
        }
        Arrays.sort(segs);
        final File tail = segs[segs.length - 1];
        if (segmentNumber(tail.getName()) >= currentGenStart) {
            boundarySegments.add(tail.getName());
        }
    }

    private static long segmentNumber(String name) {
        try {
            return Long.parseLong(name.substring("seg_".length(), name.length() - ".mp4".length()));
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ verify side

    private void processSegments() {
        try {
            final File[] segs = spoolDir.listFiles((d, name) -> name.startsWith("seg_") && name.endsWith(".mp4"));
            if (segs == null || segs.length < 2) {
                // No finished segment. If this persists (media outage: GetMedia delivers nothing,
                // ffmpeg writes nothing), emit an explicit 0.0 so the metric keeps flowing.
                if (System.currentTimeMillis() - lastEmitMs > NO_SEGMENT_EMIT_MS) {
                    logger.warn("SoakStreamVerifier: no finished segment in "
                            + (NO_SEGMENT_EMIT_MS / 1000) + "s (media outage?), emitting 0");
                    WebrtcStorageCanaryConsumer.publishMetricToCW("SoakVideoDecodable", 0.0, StandardUnit.None);
                    lastEmitMs = System.currentTimeMillis();
                }
                return; // newest segment (if any) is still being written
            }
            Arrays.sort(segs);
            // All but the newest are finished (ffmpeg writes segments strictly in order).
            int pending = segs.length - 1;

            // Backpressure: skip oldest segments if verification has fallen behind, so the spool
            // never grows unbounded. The pull side is unaffected.
            int skipFrom = 0;
            if (pending > MAX_PENDING_SEGMENTS) {
                final int toSkip = pending - MAX_PENDING_SEGMENTS;
                for (int i = 0; i < toSkip; i++) {
                    boundarySegments.remove(segs[i].getName());
                    segs[i].delete();
                }
                skipFrom = toSkip;
                WebrtcStorageCanaryConsumer.publishMetricToCW("SoakSegmentSkipped", toSkip, StandardUnit.Count);
                logger.warn("SoakStreamVerifier: verification behind, skipped " + toSkip + " segment(s)");
            }

            for (int i = skipFrom; i < segs.length - 1; i++) {
                final File seg = segs[i];
                if (boundarySegments.remove(seg.getName())) {
                    seg.delete();
                    WebrtcStorageCanaryConsumer.publishMetricToCW(
                            "SoakSegmentBoundaryDiscarded", 1.0, StandardUnit.Count);
                    logger.info("SoakStreamVerifier: discarding boundary segment " + seg.getName()
                            + " (incomplete by construction, not a media failure)");
                    continue;
                }
                boolean ok = false;
                try {
                    final Boolean scriptResult =
                            WebrtcStorageCanaryConsumer.runVerifyScript(seg.getAbsolutePath(), SEGMENT_SECONDS);
                    ok = (scriptResult != null) ? scriptResult
                                                : WebrtcStorageCanaryConsumer.probeDecodable(seg.getAbsolutePath());
                } catch (Exception e) {
                    logger.error("SoakStreamVerifier: verify failed for " + seg.getName() + ", " + e);
                } finally {
                    seg.delete();
                }
                WebrtcStorageCanaryConsumer.publishMetricToCW("SoakVideoDecodable", ok ? 1.0 : 0.0, StandardUnit.None);
                lastEmitMs = System.currentTimeMillis();
            }
        } catch (Exception e) {
            // Never let the worker die -- the next tick retries.
            logger.error("SoakStreamVerifier: segment worker error, " + e);
        }
    }
}
