package com.amazonaws.kinesisvideo;

import java.io.Closeable;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoCommon;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoFrameViewer;
import com.amazonaws.kinesisvideo.parser.examples.StreamOps;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.FrameVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.H264FrameRenderer;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.kinesisvideo.workers.GetMediaForFragmentListBatchWorker;
import com.amazonaws.kinesisvideo.workers.ListFragmentWorker;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.model.*;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class KinesisVideoRendererArchivedExample extends KinesisVideoCommon {
    private final TimestampRange timestampRange;

    private static final int FRAME_WIDTH=1280;
    private static final int FRAME_HEIGHT=720;
    private final int awaitTerminationTime = 180;

    private final StreamOps streamOps;
    private final ExecutorService executorService;
    private boolean renderFragmentMetadata;
    private KinesisVideoRendererArchivedExample.GetMediaProcessingArguments getMediaProcessingArguments;

    @Builder
    private KinesisVideoRendererArchivedExample(Regions region,
                                                String streamName,
                                                AWSCredentialsProvider credentialsProvider,
                                                boolean renderFragmentMetadata,
                                                TimestampRange timestampRange) {
        super(region, credentialsProvider, streamName);
        this.streamOps = new StreamOps(region, streamName, credentialsProvider);
        this.executorService = Executors.newSingleThreadExecutor();
        this.renderFragmentMetadata = renderFragmentMetadata;
        this.timestampRange = timestampRange;
    }


    public void execute() throws InterruptedException, ExecutionException {

        getMediaProcessingArguments = new KinesisVideoRendererArchivedExample.GetMediaProcessingArguments(renderFragmentMetadata ?
                Optional.of(new FragmentMetadataVisitor.BasicMkvTagProcessor()) : Optional.empty());

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

        //Start a GetMediaForFragmentListBatch Worker to GetMedia for all of the fragments from List Fragment
        GetMediaForFragmentListBatchWorker getMediaForFragmentListBatchWorker = GetMediaForFragmentListBatchWorker.create(getStreamName(),
                fragmentNumbers,
                getCredentialsProvider(),
                getRegion(),
                streamOps.getAmazonKinesisVideo(),
                getMediaProcessingArguments.getFrameVisitor());

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

    private static class GetMediaProcessingArguments {
        @Getter
        private final FrameVisitor frameVisitor;

        GetMediaProcessingArguments(Optional<FragmentMetadataVisitor.MkvTagProcessor> tagProcessor) {
            KinesisVideoFrameViewer kinesisVideoFrameViewer = new KinesisVideoFrameViewer(FRAME_WIDTH, FRAME_HEIGHT);
            this.frameVisitor = FrameVisitor.create(H264FrameRenderer.create(kinesisVideoFrameViewer), tagProcessor, Optional.of(1L));
        }
    }

}

