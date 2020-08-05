package com.amazonaws.kinesisvideo;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Time;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;


import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoCommon;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoFrameViewer;
import com.amazonaws.kinesisvideo.parser.examples.StreamOps;
import com.amazonaws.kinesisvideo.parser.utilities.*;
import com.amazonaws.kinesisvideo.utilities.H264FrameLabelDetector;
import com.amazonaws.kinesisvideo.workers.GetMediaArchivedRekognitionWorker;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.model.*;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class KinesisVideoArchivedParallelProcessingExample extends KinesisVideoCommon {

    private final TimestampRange timestampRange;

    private final StreamOps streamOps;
    private final ExecutorService executorService;
    private final int sampleRate;
    private int tasks;
    private AtomicLong framesProcessed = new AtomicLong();

    private static final int AWAIT_TERMINATION_TIME = 10800;

    @Builder
    private KinesisVideoArchivedParallelProcessingExample(Regions region,
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

    public void execute() throws InterruptedException, IOException, ParseException, ExecutionException {

        List<TimestampRange> timestampRanges = partitionTimeRange(timestampRange);

        String listFragmentsEndpoint = getListFragmentsEndpoint(getStreamName());
        String getMediaFragmentListEndpoint = getGetMediaForFragmentListEndpoint(getStreamName());

        for (TimestampRange timestampRange: timestampRanges) {

            log.info(timestampRange.toString());

            FrameVisitor frameVisitor = FrameVisitor.create(H264FrameLabelDetector.create(sampleRate, framesProcessed), Optional.empty(), Optional.of(1L));

            GetMediaArchivedRekognitionWorker getMediaArchivedRekognitionWorker = GetMediaArchivedRekognitionWorker.create(getStreamName(),
                    getCredentialsProvider(),
                    getRegion(),
                    streamOps.getAmazonKinesisVideo(),
                    new FragmentSelector()
                            .withFragmentSelectorType(FragmentSelectorType.SERVER_TIMESTAMP)
                            .withTimestampRange(timestampRange),
                    frameVisitor,
                    listFragmentsEndpoint,
                    getMediaFragmentListEndpoint);

            executorService.submit(getMediaArchivedRekognitionWorker);

        }

        //Wait for the workers to finish.
        executorService.shutdown();
        executorService.awaitTermination(AWAIT_TERMINATION_TIME, TimeUnit.SECONDS);
        if (!executorService.isTerminated()) {
            log.warn("Shutting down executor service by force");
            executorService.shutdownNow();
        } else {
            log.info("Executor service is shutdown");
            log.info("Total number of frames processed: {}", framesProcessed);
        }
    }

    /* Create N time stamp ranges so that each of the N threads can call ListFragments on a specified partition */
    public List<TimestampRange> partitionTimeRange(TimestampRange timestampRange) throws ParseException{
        List<TimestampRange> timestampRanges= new ArrayList<>();


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
