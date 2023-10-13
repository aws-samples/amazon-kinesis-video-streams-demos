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

import org.apache.commons.io.FileUtils;

import java.util.Date;

import java.util.Scanner;
import java.io.FileReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;


public class RealTimeFrameProcessor extends WebrtcStorageCanaryConsumer implements FrameVisitor.FrameProcessor {

    boolean isFirstFrame = false;

    public static RealTimeFrameProcessor create() {
        return new RealTimeFrameProcessor();
    }

    private RealTimeFrameProcessor() {
    }

    @Override
    public void process(Frame frame, MkvTrackMetadata trackMetadata, Optional<FragmentMetadata> fragmentMetadata, Optional<FragmentMetadataVisitor.MkvTagProcessor> tagProcessor) throws FrameProcessException {
        System.out.println("RealTimeFrameProcessor invoked for frame" + frame);
        if (!isFirstFrame) {

            System.out.println("First Frame invoked for frame" + frame);

            try
            {
                long currentTime = System.currentTimeMillis();
                String filePath = "../webrtc-c/" + super.streamName + ".txt";
                final String startTimeStr = FileUtils.readFileToString(new File(filePath)).trim();
                System.out.println("Start Time String :" + startTimeStr);
                long startTime = Long.parseLong(startTimeStr);
                System.out.println("RTP startTime : " + startTime);
                System.out.println("RTP currentTime : " + currentTime);
                long rtpToFirstFragment = currentTime - startTime;
                System.out.println("rtpToFirstFragment: " + rtpToFirstFragment);
                super.publishMetricToCW("RtpToFirstFragment", rtpToFirstFragment, StandardUnit.Milliseconds);
            }
            catch (FileNotFoundException ex)
            {
                System.out.println("File not found!");
            }
            catch(IOException ioEx)
            {
                System.out.println("IO Exception!");
            }

            isFirstFrame = true;
        } else {
            System.out.println("Non first frame. Skipping...");
        }

    }
}
