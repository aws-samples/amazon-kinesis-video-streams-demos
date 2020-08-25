package com.amazonaws.kinesisvideo.labeldetectionwebapp.kvsservices;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;


import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.kinesisvideo.labeldetectionwebapp.JpaFrame;
import com.amazonaws.kinesisvideo.labeldetectionwebapp.TimestampCollection;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoCommon;
import com.amazonaws.kinesisvideo.parser.examples.StreamOps;
import com.amazonaws.kinesisvideo.parser.utilities.*;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.model.*;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class GetArchivedMedia extends KinesisVideoCommon {

    private final TimestampRange timestampRange;
    private final StreamOps streamOps;
    private final ExecutorService executorService;
    private final int sampleRate;
    private int tasks;

    private final int awaitTerminationTime = 10800;
    private AtomicLong playbackLength = new AtomicLong();

    @Getter
    private Set<String> labels = Collections.synchronizedSet(new HashSet<>());

    @Getter
    private Map<String, TimestampCollection> labelToTimestamps = Collections.synchronizedMap(new HashMap<>());

    @Getter
    private Map<JpaFrame, JpaFrame> frames = Collections.synchronizedMap(new HashMap<>());

    @Builder
    private GetArchivedMedia(Regions region,
                             String streamName,
                             AWSCredentialsProvider awsCredentialsProvider,
                             TimestampRange timestampRange,
                             int sampleRate,
                             int threads,
                             int tasks) {
        super(region, awsCredentialsProvider, streamName);
        this.streamOps = new StreamOps(region, streamName, awsCredentialsProvider);
        this.executorService = Executors.newFixedThreadPool(threads);
        this.timestampRange = timestampRange;
        this.sampleRate = sampleRate;
        this.tasks = tasks;
    }

    public void execute() throws InterruptedException, ParseException, ExecutionException {

        List<TimestampRange> timestampRanges = partitionTimeRange(timestampRange);

        String listFragmentsEndpoint = getListFragmentsEndpoint(getStreamName());
        String getMediaFragmentListEndpoint = getGetMediaForFragmentListEndpoint(getStreamName());

        List<Future<List<JpaFrame>>> framesForEachTask = new ArrayList<>();

        for (TimestampRange timestampRange : timestampRanges) {

            log.info(timestampRange.toString());

            H264ImageDetectionBoundingBoxSaver h264ImageDetectionBoundingBoxSaver = H264ImageDetectionBoundingBoxSaver.create(sampleRate, getLabels(), getFrames(), getLabelToTimestamps());
            FrameVisitor frameVisitor = FrameVisitor.create(h264ImageDetectionBoundingBoxSaver);

            GetMediaArchivedRekognitionWorker getMediaArchivedRekognitionWorker = GetMediaArchivedRekognitionWorker.create(getStreamName(),
                    getCredentialsProvider(),
                    getRegion(),
                    streamOps.getAmazonKinesisVideo(),
                    new FragmentSelector()
                            .withFragmentSelectorType(FragmentSelectorType.SERVER_TIMESTAMP)
                            .withTimestampRange(timestampRange),
                    frameVisitor,
                    h264ImageDetectionBoundingBoxSaver,
                    listFragmentsEndpoint,
                    getMediaFragmentListEndpoint,
                    playbackLength);

            Future<List<JpaFrame>> framesForTask = executorService.submit(getMediaArchivedRekognitionWorker);
            framesForEachTask.add(framesForTask);
        }

        //Wait for the workers to finish.
        executorService.shutdown();
        executorService.awaitTermination(awaitTerminationTime, TimeUnit.SECONDS);
        if (!executorService.isTerminated()) {
            log.warn("Shutting down executor service by force");
            executorService.shutdownNow();
        } else {
            log.info("Executor service is shutdown");
            log.info("Total playback time duration: {} milliseconds", playbackLength.get());
            log.info("Total frames processed: {}", this.frames.size());
            updateFramePlaybackTimestamps(framesForEachTask, playbackLength, frames.size());
        }
    }

    /* Create N time stamp ranges so that each of the N threads can call ListFragments on a specified partition */
    public List<TimestampRange> partitionTimeRange(TimestampRange timestampRange) throws ParseException {
        List<TimestampRange> timestampRanges = new ArrayList<>();


        Date startDate = timestampRange.getStartTimestamp();
        Date endDate = timestampRange.getEndTimestamp();

        /* Time between two timestamps in milliseconds */
        long timespan = (endDate.getTime() - startDate.getTime());
        long taskTimeSlice = timespan / tasks;

        long startTime = startDate.getTime();
        long endTime = endDate.getTime();

        long taskStart = startTime;
        long taskEnd = startTime + taskTimeSlice;

        while (taskStart <= endTime) {

            TimestampRange taskTimestampRange = new TimestampRange();

            Date threadStartDate = new Date(taskStart);
            Date threadEndDate = new Date(taskEnd);

            taskTimestampRange.setStartTimestamp(threadStartDate);
            taskTimestampRange.setEndTimestamp(threadEndDate);

            taskStart = taskEnd + 1;
            taskEnd = Math.min(endTime, taskStart + taskTimeSlice);

            timestampRanges.add(taskTimestampRange);
        }

        return timestampRanges;
    }

    private void updateFramePlaybackTimestamps(List<Future<List<JpaFrame>>> framesForEachTask, AtomicLong playbackLength, long numFrames) throws ExecutionException, InterruptedException {
        long timespan = playbackLength.get();
        long msStartTimestamp = 0;
        long timePerFrame = timespan / numFrames;
        long frameCount = 0;

        for (Future<List<JpaFrame>> listFuture : framesForEachTask) {
            List<JpaFrame> framesForTask = listFuture.get();
            long framesPerTask = (long) framesForTask.size();

            if (framesPerTask > 0) {

                for (JpaFrame f : framesForTask) {
                    long timeAfterStart = timePerFrame * frameCount;
                    long frameTimestampInMs = timeAfterStart + msStartTimestamp;
                    String frameTimestamp = convertMillisecondsToTimestamp(frameTimestampInMs);

                    this.frames.get(f).setPlaybackTimestampAndFrameNum(frameTimestamp, frameCount);

                    for (String label : f.getLabels()) {
                        this.labelToTimestamps.get(label).addTimestamp(frameTimestamp);
                        this.labelToTimestamps.get(label).addTimestampAndFrame(frameTimestamp, f);
                    }
                    frameCount++;
                }
            }
        }
    }

    private String convertMillisecondsToTimestamp(long millis) {
        String timestamp = String.format("%02d:%02d:%02d",
                TimeUnit.MILLISECONDS.toHours(millis),
                TimeUnit.MILLISECONDS.toMinutes(millis) -
                        TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(millis)), // The change is in this line
                TimeUnit.MILLISECONDS.toSeconds(millis) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis)));
        return timestamp;
    }

    private String getListFragmentsEndpoint(String streamName) {
        GetDataEndpointRequest listFragmentsEndpointRequest = new GetDataEndpointRequest()
                .withAPIName(APIName.LIST_FRAGMENTS).withStreamName(streamName);
        String listFragmentsEndpoint = streamOps.getAmazonKinesisVideo().getDataEndpoint(listFragmentsEndpointRequest).getDataEndpoint();
        return listFragmentsEndpoint;
    }

    private String getGetMediaForFragmentListEndpoint(String streamName) {
        GetDataEndpointRequest getMediaForFragmentListEndpointRequest = new GetDataEndpointRequest()
                .withAPIName(APIName.GET_MEDIA_FOR_FRAGMENT_LIST).withStreamName(streamName);
        String getMediaForFragmentListEndpoint = streamOps.getAmazonKinesisVideo().getDataEndpoint(getMediaForFragmentListEndpointRequest).getDataEndpoint();
        return getMediaForFragmentListEndpoint;
    }
}

