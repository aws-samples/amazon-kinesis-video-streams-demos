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

public class RealTimeFrameProcessor extends WebrtcStorageCanaryConsumer implements FrameVisitor.FrameProcessor {
    private Boolean isFirstFrameReceived;

    public static RealTimeFrameProcessor create() {
        return new RealTimeFrameProcessor();
    }

    public Boolean getIsFirstFrameReceived () {
        return this.isFirstFrameReceived;
    }

    private RealTimeFrameProcessor(AmazonCloudWatchAsync cwClient, String streamName, String canaryLabel) {
        this.firstFrameReceived = false;
    }

    @Override
    public void process(Frame frame, MkvTrackMetadata trackMetadata, Optional<FragmentMetadata> fragmentMetadata, Optional<FragmentMetadataVisitor.MkvTagProcessor> tagProcessor) throws FrameProcessException {
        if (!this.isFirstFrameReceived) {
            this.isFirstFrameReceived = true;

            final long currentTime = new Date().getTime();
            double timeToFirstFragment = currentTime - canaryStartTime.getTime();

            super.publishMetricToCW(timeToFirstFragment, timeToFirstFragment, StandardUnit.Milliseconds);
            super.keepProcessing = false;
        }

    }

    @Override
    public void close() {

    }
}
