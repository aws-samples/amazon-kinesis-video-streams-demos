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

import lombok.extern.log4j.Log4j2;

@Log4j2
public class RealTimeFrameProcessor extends WebrtcStorageCanaryConsumer implements FrameVisitor.FrameProcessor {

    private boolean isFirstFrame = false;

    public static RealTimeFrameProcessor create() {
        return new RealTimeFrameProcessor();
    }

    private RealTimeFrameProcessor() {
    }

    @Override
    public void process(Frame frame, MkvTrackMetadata trackMetadata, Optional<FragmentMetadata> fragmentMetadata,
            Optional<FragmentMetadataVisitor.MkvTagProcessor> tagProcessor) throws FrameProcessException {
        if (!isFirstFrame) {
            try {
                long currentTime = System.currentTimeMillis();
                long canaryStartTime = super.mCanaryStartTime.getTime();

                // TODO: Add this to contants file for now
                String filePath = "../webrtc-c/firstFrameSentTimeStamp.txt";
                final String frameSentTimeStr = FileUtils.readFileToString(new File(filePath)).trim();
                long frameSentTime = Long.parseLong(frameSentTimeStr);

                // Check for a late consumer end start that may add to the measured
                // FirstFrameSentToFirstFrameConsumed time
                if (canaryStartTime > frameSentTime) {
                    // Consumer started after master sent out first frame, not pushing the
                    // FirstFrameSentToFirstFrameConsumed metric
                } else {
                    long rtpToFirstFragment = currentTime - frameSentTime;
                    super.publishMetricToCW("FirstFrameSentToFirstFrameConsumed", rtpToFirstFragment,
                            StandardUnit.Milliseconds);
                }

                long timeToFirstFragment = currentTime - canaryStartTime;
                super.publishMetricToCW("TotalTimeToFirstFrameConsumed", timeToFirstFragment,
                        StandardUnit.Milliseconds);
            } catch (FileNotFoundException ex) {
                log.error("Master-end timestamp file not found, try reducing the frequency of runs by increasing CANARY_DURATION_IN_SECONDS: " + ex);

            } catch (IOException ioEx) {
                log.error("IO Exception: " + ioEx);
            } finally {
                isFirstFrame = true;
                System.exit(0);
            }
        } else {
            log.info("Non first frame. Skipping...");
        }

    }
}
