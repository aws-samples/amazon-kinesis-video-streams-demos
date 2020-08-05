package com.amazonaws.kinesisvideo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoCommon;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoFrameViewer;
import com.amazonaws.kinesisvideo.parser.examples.StreamOps;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.FrameVisitor;
import com.amazonaws.kinesisvideo.utilities.H264ImageDetectionBoundingBoxRenderer;
import com.amazonaws.kinesisvideo.workers.GetMediaForFragmentListBatchWorker;
import com.amazonaws.kinesisvideo.workers.ListFragmentWorker;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.model.*;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j

public class KinesisVideoArchivedDetectLabelsExample extends KinesisVideoCommon {

    private final TimestampRange timestampRange;

    private final StreamOps streamOps;
    private final ExecutorService executorService;
    private final int sampleRate;

    private static final int FRAME_WIDTH=1280;
    private static final int FRAME_HEIGHT=720;

    private final int awaitTerminationTime = 180;

    @Builder
    private KinesisVideoArchivedDetectLabelsExample(Regions region,
                                                    String streamName,
                                                    AWSCredentialsProvider awsCredentialsProvider,
                                                    TimestampRange timestampRange,
                                                    int sampleRate) {
        super(region, awsCredentialsProvider, streamName);
        this.streamOps = new StreamOps(region, streamName, awsCredentialsProvider);
        this.executorService = Executors.newSingleThreadExecutor();
        this.timestampRange = timestampRange;
        this.sampleRate = sampleRate;
    }

    public void execute() throws InterruptedException, ExecutionException {

        KinesisVideoFrameViewer kinesisVideoFrameViewer = new KinesisVideoFrameViewer(FRAME_WIDTH, FRAME_HEIGHT);
        FrameVisitor frameVisitor = FrameVisitor.create(H264ImageDetectionBoundingBoxRenderer.create(kinesisVideoFrameViewer,sampleRate), Optional.empty(), Optional.of(1L));

        //Start a ListFragment worker to read fragments from Kinesis Video Stream.
        ListFragmentWorker listFragmentWorker = ListFragmentWorker.create(getStreamName(),
                getCredentialsProvider(),
                getRegion(),
                streamOps.getAmazonKinesisVideo(),
                new FragmentSelector()
                        .withFragmentSelectorType(FragmentSelectorType.SERVER_TIMESTAMP)
                        .withTimestampRange(timestampRange));


        Future<List<String>> result = executorService.submit(listFragmentWorker);
        List<String> fragmentNumbers = result.get();

        GetMediaForFragmentListBatchWorker getMediaForFragmentListBatchWorker = GetMediaForFragmentListBatchWorker.create(getStreamName(),
                fragmentNumbers,
                getCredentialsProvider(),
                getRegion(),
                streamOps.getAmazonKinesisVideo(),
                frameVisitor);

        executorService.submit(getMediaForFragmentListBatchWorker);

        executorService.shutdown();
        executorService.awaitTermination(awaitTerminationTime, TimeUnit.SECONDS);
        if (!executorService.isTerminated()) {
            log.warn("Shutting down executor service by force");
            executorService.shutdownNow();
        } else {
            log.info("Executor service is shutdown");
        }
    }
}
