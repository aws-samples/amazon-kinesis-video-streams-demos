package com.amazon.kinesis.video.canary.consumer;

import com.amazonaws.kinesisvideo.parser.mkv.Frame;
import com.amazonaws.kinesisvideo.parser.mkv.FrameProcessException;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadata;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.FrameVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.MkvTrackMetadata;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.MetricDatum;
import com.amazonaws.services.cloudwatch.model.PutMetricDataRequest;
import com.amazonaws.services.cloudwatch.model.StandardUnit;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

public class RealTimeFrameProcessor implements FrameVisitor.FrameProcessor {
    int lastFrameIndex = -1;
    final AmazonCloudWatchAsync cwClient;
    final Dimension dimensionPerStream;
    final Dimension aggregatedDimension;

    public CanaryFrameProcessor(AmazonCloudWatchAsync cwClient, String streamName, String canaryLabel) {
        this.cwClient = cwClient;
        dimensionPerStream = new Dimension()
                .withName("ProducerSDKCanaryStreamName")
                .withValue(streamName);
        aggregatedDimension = new Dimension()
                .withName("ProducerSDKCanaryType")
                .withValue(canaryLabel);
    }

    @Override
    public void process(Frame frame, MkvTrackMetadata trackMetadata, Optional<FragmentMetadata> fragmentMetadata, Optional<FragmentMetadataVisitor.MkvTagProcessor> tagProcessor) throws FrameProcessException {
        // TODO: Change an reachedFirstFrame bool to TRUE

    }

    @Override
    public void close() {

    }
}
