package com.amazon.kinesis.video.canary.consumer;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.AWSLogsClientBuilder;
import com.amazonaws.services.logs.model.CreateLogGroupRequest;
import com.amazonaws.services.logs.model.CreateLogStreamRequest;
import com.amazonaws.services.logs.model.InputLogEvent;
import com.amazonaws.services.logs.model.PutLogEventsRequest;
import com.amazonaws.services.logs.model.ResourceAlreadyExistsException;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Layout;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.LoggingEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ships the consumer's log4j output to CloudWatch Logs, one log stream per canary run.
 *
 * WHY THIS EXISTS. Every other component of the storage canary has had a CloudWatch Logs path
 * for a long time and the consumer never did: its only appender is the ConsoleAppender in
 * src/main/resources/log4j.properties, so the consumer's log has only ever existed as build-record
 * stdout. That is unqueryable, and it is deleted when the build rotates out of Jenkins history --
 * on a soak, losing one build record loses weeks of the only record of what the consumer saw.
 *
 * WHY IT IS ATTACHED FROM CODE, NOT FROM log4j.properties. log4j initializes on the first
 * Logger.getLogger() call, which happens in a static initializer -- before main() runs at all
 * (this is why the three log4j:ERROR lines documented in log4j.properties appear ahead of every
 * other line). At that point the consumer's credential provider does not exist yet: on a soak run
 * it is an STSAssumeRoleSessionCredentialsProvider built in main() from CANARY_CREDENTIALS_ROLE_ARN.
 * A properties-declared appender would therefore be constructed with no usable credentials. So
 * WebrtcStorageCanaryConsumer.main() calls attach() once the provider is ready, and this appender
 * shares that provider -- which also means the soak's credential auto-refresh is inherited for free
 * rather than reimplemented.
 *
 * WHY THE FLUSH IS TIME-BASED. A soak never ends, so a count-triggered flush would hold the tail
 * of the log in memory for as long as the log stayed quiet -- exactly when the consumer has wedged
 * and exactly the lines that explain it. This is the defect the C master still has
 * (webrtc-c/src/CloudwatchLogs.cpp flushes only at MAX_CLOUDWATCH_LOG_COUNT, with no timer), so
 * this appender is deliberately timer-only: a fixed FLUSH_INTERVAL_SECONDS tick, no size trigger.
 * The bounded runs get the same path; flush(final) on shutdown covers their tail.
 *
 * WHAT IT DELIBERATELY DOES NOT COVER. Only log4j events reach an appender. Output written before
 * log4j is configured, raw System.out/System.err writes, uncaught-exception stack traces printed by
 * the JVM, and JVM fatal-error output are all structurally invisible here. Those live in the
 * runner's `| tee` file on the node and, via the Jenkins build record, in the JenkinsBuildLogs
 * group. This class is not a replacement for either.
 */
public class CloudWatchLogsAppender extends AppenderSkeleton {

    /**
     * Timer period. Matches the JS viewer's 5s CloudWatchLogger interval (webrtc-c/scripts/
     * cloudwatch.js) so the two components' streams interleave at comparable granularity when
     * read side by side.
     */
    static final long FLUSH_INTERVAL_SECONDS = 5;

    /**
     * Hard cap on buffered events. PutLogEvents accepts at most 10,000 events per call, so a
     * larger buffer could not be drained in one request anyway. On overflow the OLDEST events are
     * dropped: if CloudWatch is unreachable for long enough to reach this cap, the lines that
     * describe what is happening right now are worth more than the lines from ten minutes ago.
     */
    static final int MAX_BUFFERED_EVENTS = 10_000;

    /**
     * PutLogEvents rejects a batch whose total size exceeds 1 MiB, counting 26 bytes of overhead
     * per event. Kept under that with margin; a batch is split rather than rejected.
     */
    static final int MAX_BATCH_BYTES = 900_000;
    static final int EVENT_OVERHEAD_BYTES = 26;

    /**
     * PutLogEvents rejects any single event over 256 KiB (again including the 26-byte overhead).
     * Longer messages are truncated with a marker rather than silently dropping the whole batch.
     */
    static final int MAX_EVENT_BYTES = 256 * 1024 - EVENT_OVERHEAD_BYTES;
    private static final String TRUNCATION_MARKER = "...[truncated]";

    private final AWSLogs logsClient;
    private final String logGroupName;
    private final String logStreamName;
    private final ScheduledExecutorService flusher;

    /** Guards buffer only. Never held across a PutLogEvents call -- see flush(). */
    private final Deque<InputLogEvent> buffer = new ArrayDeque<>();
    private final Object bufferLock = new Object();

    /** Serializes PutLogEvents so the timer tick and a final flush cannot interleave batches. */
    private final Object putLock = new Object();

    private volatile boolean closed = false;
    private final java.util.concurrent.atomic.AtomicBoolean closing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private long droppedEvents = 0;

    private CloudWatchLogsAppender(final AWSLogs logsClient, final String logGroupName,
            final String logStreamName, final Layout layout) {
        this.logsClient = logsClient;
        this.logGroupName = logGroupName;
        this.logStreamName = logStreamName;
        setLayout(layout);
        // Daemon thread: a soak is killed rather than shut down, and this must never be the
        // reason a bounded run's JVM lingers after main() returns.
        this.flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread t = new Thread(runnable, "CloudWatchLogsAppender-flush");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Builds the appender, creates the group/stream, attaches it to the root logger and starts the
     * flush timer. Returns null (having logged why) if anything required is missing or fails --
     * shipping logs is never allowed to be the reason a canary run does not start.
     *
     * @param credentialsProvider the provider main() already built; on a soak this is the
     *                            auto-refreshing assume-role provider, so the appender keeps
     *                            working past the base session's expiry.
     */
    public static CloudWatchLogsAppender attach(final String logGroupName, final String logStreamName,
            final String region, final AWSCredentialsProvider credentialsProvider) {
        final Logger logger = Logger.getLogger(CloudWatchLogsAppender.class);

        if (logGroupName == null || logGroupName.isEmpty() || logStreamName == null || logStreamName.isEmpty()) {
            logger.info("CloudWatch Logs shipping disabled: CANARY_LOG_GROUP_NAME/CANARY_LOG_STREAM_NAME not set");
            return null;
        }

        try {
            // Bounded timeouts, for the same reason the GetClip client has them: the final flush
            // runs from a JVM shutdown hook, and Jenkins follows its SIGTERM with a SIGKILL after a
            // grace period. On SDK defaults (50s socket, unlimited request) an unreachable endpoint
            // would hold the hook open past that window, so the JVM would be killed mid-flush and
            // lose the tail anyway -- while also making every bounded run's exit look like a hang.
            final ClientConfiguration clientConfig = new ClientConfiguration()
                    .withConnectionTimeout(5 * 1000)
                    .withSocketTimeout(10 * 1000)
                    .withRequestTimeout(15 * 1000)
                    .withClientExecutionTimeout(20 * 1000);

            final AWSLogs client = AWSLogsClientBuilder.standard()
                    .withRegion(region)
                    .withCredentials(credentialsProvider)
                    .withClientConfiguration(clientConfig)
                    .build();

            // Both creates are idempotent by design. The group is normally pre-existing and shared
            // with the master and viewer; the stream name carries the run's start timestamp so it is
            // normally new, but tolerating AlreadyExists keeps a retry or a re-attach harmless.
            try {
                client.createLogGroup(new CreateLogGroupRequest().withLogGroupName(logGroupName));
                logger.info("Created CloudWatch log group " + logGroupName);
            } catch (final ResourceAlreadyExistsException e) {
                // Expected in the normal case.
            }
            try {
                client.createLogStream(new CreateLogStreamRequest()
                        .withLogGroupName(logGroupName)
                        .withLogStreamName(logStreamName));
            } catch (final ResourceAlreadyExistsException e) {
                // Expected on a re-attach.
            }

            // Same pattern as the ConsoleAppender in log4j.properties, so a line read out of
            // CloudWatch is byte-identical to the same line read out of the tee'd file. %d renders
            // in the JVM default zone, which the runner pins to UTC via -Duser.timezone=UTC.
            final Layout layout = new PatternLayout("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c{1} - %m%n");

            final CloudWatchLogsAppender appender =
                    new CloudWatchLogsAppender(client, logGroupName, logStreamName, layout);
            appender.setName("CLOUDWATCH");
            appender.flusher.scheduleWithFixedDelay(appender::flushQuietly,
                    FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
            Logger.getRootLogger().addAppender(appender);

            // The timer alone leaves up to FLUSH_INTERVAL_SECONDS of the tail unsent whenever a run
            // ends between two ticks -- which is every run, since nothing aligns the end of a run to
            // the tick. Registering the final flush as a JVM shutdown hook here, rather than relying
            // on a call site, is what makes that hold for EVERY exit path: a bounded run returning
            // from main(), System.exit(1) from main's uncaught-exception handler (which does not go
            // through shutdownCanaryResources()), and SIGTERM -- including the SIGTERM Jenkins sends
            // before it escalates to SIGKILL on abort. close() is idempotent, so the explicit call in
            // shutdownCanaryResources() and this hook cannot double-flush.
            //
            // The one case no in-process mechanism can cover is SIGKILL with no SIGTERM first, or a
            // JVM crash: the buffer dies with the process. That residual loss is bounded by
            // FLUSH_INTERVAL_SECONDS, which is why the interval is 5s and not a minute.
            Runtime.getRuntime().addShutdownHook(new Thread(appender::close, "CloudWatchLogsAppender-shutdown"));

            logger.info("CloudWatch Logs shipping enabled: group=" + logGroupName
                    + ", stream=" + logStreamName + ", flush=" + FLUSH_INTERVAL_SECONDS + "s");
            return appender;
        } catch (final Exception e) {
            // Includes credential resolution failures, which on a misconfigured soak node are the
            // likely case. Degrade to stdout-only rather than failing the run.
            logger.warn("CloudWatch Logs shipping disabled: " + e.getMessage(), e);
            return null;
        }
    }

    @Override
    protected void append(final LoggingEvent event) {
        if (closed) {
            return;
        }

        final StringBuilder message = new StringBuilder(getLayout().format(event));
        // The layout does not render the throwable, so append it explicitly. Kept as ONE event so
        // the frames stay together -- the reason a file-tailing agent needs multi_line_start_pattern
        // to guess at this and an in-process appender does not.
        final String[] throwableTrace = event.getThrowableStrRep();
        if (throwableTrace != null) {
            for (final String line : throwableTrace) {
                message.append(line).append(Layout.LINE_SEP);
            }
        }

        String text = message.toString();
        if (text.isEmpty()) {
            return;
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) {
            text = truncateToUtf8Bytes(text, MAX_EVENT_BYTES - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
        }

        final InputLogEvent logEvent = new InputLogEvent()
                .withTimestamp(event.getTimeStamp())
                .withMessage(text);

        synchronized (bufferLock) {
            while (buffer.size() >= MAX_BUFFERED_EVENTS) {
                buffer.pollFirst();
                droppedEvents++;
            }
            buffer.addLast(logEvent);
        }
    }

    /** Timer entry point: a flush failure must never propagate out and kill the scheduled task. */
    private void flushQuietly() {
        try {
            flush();
        } catch (final Throwable t) {
            // Deliberately System.err, not the logger: logging a shipping failure through the
            // appender that is failing would feed the buffer it cannot drain.
            System.err.println("CloudWatchLogsAppender: flush failed: " + t);
        }
    }

    /**
     * Drains the buffer to CloudWatch. Events are drained under the lock and the network call is
     * made outside it, so a slow or hanging PutLogEvents cannot block the threads that are logging.
     * On failure the batch is returned to the FRONT of the buffer so order is preserved and the
     * next tick retries.
     */
    public void flush() {
        final List<InputLogEvent> batch;
        final long dropped;
        synchronized (bufferLock) {
            if (buffer.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(buffer);
            buffer.clear();
            dropped = droppedEvents;
            droppedEvents = 0;
        }

        if (dropped > 0) {
            System.err.println("CloudWatchLogsAppender: dropped " + dropped
                    + " oldest events after buffer reached " + MAX_BUFFERED_EVENTS);
        }

        // PutLogEvents requires events in ascending timestamp order. Appends arrive in order per
        // thread but not across threads, and the consumer logs from several (heartbeat timer,
        // ListFragments, SoakStreamVerifier pull loop and segment workers).
        batch.sort(Comparator.comparingLong(InputLogEvent::getTimestamp));

        synchronized (putLock) {
            int index = 0;
            while (index < batch.size()) {
                int bytes = 0;
                int end = index;
                while (end < batch.size()) {
                    final int eventBytes = batch.get(end).getMessage()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8).length + EVENT_OVERHEAD_BYTES;
                    if (end > index && bytes + eventBytes > MAX_BATCH_BYTES) {
                        break;
                    }
                    bytes += eventBytes;
                    end++;
                }
                final List<InputLogEvent> slice = batch.subList(index, end);
                try {
                    logsClient.putLogEvents(new PutLogEventsRequest()
                            .withLogGroupName(logGroupName)
                            .withLogStreamName(logStreamName)
                            .withLogEvents(slice));
                } catch (final Exception e) {
                    // Requeue everything not yet sent, oldest first, and let the next tick retry.
                    synchronized (bufferLock) {
                        final List<InputLogEvent> unsent = batch.subList(index, batch.size());
                        for (int i = unsent.size() - 1; i >= 0; i--) {
                            if (buffer.size() >= MAX_BUFFERED_EVENTS) {
                                droppedEvents++;
                                continue;
                            }
                            buffer.addFirst(unsent.get(i));
                        }
                    }
                    System.err.println("CloudWatchLogsAppender: PutLogEvents failed, "
                            + (batch.size() - index) + " events requeued: " + e.getMessage());
                    return;
                }
                index = end;
            }
        }
    }

    /**
     * Final flush plus teardown, for the tail the timer has not reached. Idempotent: it is reached
     * both from shutdownCanaryResources() on a bounded run and from the shutdown hook registered in
     * attach(), and whichever arrives first does the work.
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        // Stop the periodic task first so it cannot race this final drain.
        flusher.shutdownNow();
        // Drain BEFORE refusing new events, then set closed and drain again. Closing first would
        // silently discard anything logged concurrently with shutdown -- another shutdown hook, a
        // teardown message, the last line of a stack trace -- which is exactly the content that
        // makes a final flush worth having.
        flushQuietly();
        closed = true;
        flushQuietly();
        try {
            logsClient.shutdown();
        } catch (final Exception e) {
            // Nothing useful left to do at this point.
        }
    }

    @Override
    public boolean requiresLayout() {
        return true;
    }

    /** Truncates on a UTF-8 boundary so a multi-byte character cannot be cut in half. */
    private static String truncateToUtf8Bytes(final String text, final int maxBytes) {
        final byte[] raw = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) {
            return text;
        }
        int end = maxBytes;
        // 10xxxxxx is a continuation byte; back up until the start of the character.
        while (end > 0 && (raw[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(raw, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }
}
