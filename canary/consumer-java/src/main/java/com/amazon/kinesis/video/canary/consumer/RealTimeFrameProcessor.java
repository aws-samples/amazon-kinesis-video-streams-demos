package com.amazon.kinesis.video.canary.consumer;

import com.amazonaws.kinesisvideo.parser.mkv.Frame;
import com.amazonaws.kinesisvideo.parser.mkv.FrameProcessException;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadata;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.FrameVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.MkvTrackMetadata;
import com.amazonaws.services.cloudwatch.model.StandardUnit;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Optional;


public class RealTimeFrameProcessor extends WebrtcStorageCanaryConsumer implements FrameVisitor.FrameProcessor {

    boolean isFirstFrame = false;

    public static RealTimeFrameProcessor create() {
        return new RealTimeFrameProcessor();
    }

    private RealTimeFrameProcessor() {
    }

    @Override
    public void process(Frame frame, MkvTrackMetadata trackMetadata, Optional<FragmentMetadata> fragmentMetadata, Optional<FragmentMetadataVisitor.MkvTagProcessor> tagProcessor) throws FrameProcessException {
        if (!isFirstFrame) {
            try
            {
                long currentTime = System.currentTimeMillis();
                long canaryStartTime = super.canaryStartTime.getTime();

                String filePath = "../webrtc-c/toConsumer.txt";
                final String frameSentTimeStr = FileUtils.readFileToString(new File(filePath)).trim();
                long frameSentTime = Long.parseLong(frameSentTimeStr);
                // Check for a late consumer end start that may add to the measured FirstFrameSentToFirstFrameConsumed time
                if (canaryStartTime > frameSentTime){
                    // Consumer started after master sent out first frame, not pushing FirstFrameSentToFirstFrameConsumed metric
                } else {
                    long rtpToFirstFragment = currentTime - frameSentTime;
                    super.publishMetricToCW("FirstFrameSentToFirstFrameConsumed", rtpToFirstFragment, StandardUnit.Milliseconds);
                }

                long timeToFirstFragment = currentTime - canaryStartTime;
                super.publishMetricToCW("TotalTimeToFirstFrameConsumed", timeToFirstFragment, StandardUnit.Milliseconds);
            }
            catch (FileNotFoundException ex)
            {
                // Master-end timestamp file not found, reduce frequency of runs by increasing CANARY_DURATION_IN_SECONDS
            }
            catch(IOException ioEx)
            {
                // IO Exception
            }
            isFirstFrame = true;
            System.exit(0);
        } else {
            //Non first frame. Skipping...
        }

    }
}
