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
import java.util.Date;

import java.util.Scanner;
import java.io.FileReader;
import java.io.FileNotFoundException;


public class RealTimeFrameProcessor extends WebrtcStorageCanaryConsumer implements FrameVisitor.FrameProcessor {
    public static RealTimeFrameProcessor create() {
        return new RealTimeFrameProcessor();
    }

    private RealTimeFrameProcessor() {
    }

    @Override
    public void process(Frame frame, MkvTrackMetadata trackMetadata, Optional<FragmentMetadata> fragmentMetadata, Optional<FragmentMetadataVisitor.MkvTagProcessor> tagProcessor) throws FrameProcessException {
        System.out.println("Here 2");
        if (super.keepProcessing.compareAndSet(true, false)) {

            final long currentTime = new Date().getTime();
            double timeToFirstFragment = currentTime - super.canaryStartTime.getTime();

            super.publishMetricToCW("TimeToFirstFragment", timeToFirstFragment, StandardUnit.Milliseconds);
            //super.keepProcessing = false;

            try
            {
                String filePath = "../webrtc-c/" + super.streamName + ".txt";
                Scanner scanner = new Scanner(new FileReader(filePath));
                long rtpToFirstFragment = currentTime - Long.parseLong(scanner.next());
                System.out.println("rtpToFirstFragment: ");
                System.out.println(rtpToFirstFragment);
                scanner.close();
                super.publishMetricToCW("RtpToFirstFragment", rtpToFirstFragment, StandardUnit.Milliseconds);       
            }
            catch (FileNotFoundException ex)  
            {
                // handle
            }

            
            this.close();
        }

    }

    @Override
    public void close() {
        // How can I close??
        System.exit(0);
    }
}
