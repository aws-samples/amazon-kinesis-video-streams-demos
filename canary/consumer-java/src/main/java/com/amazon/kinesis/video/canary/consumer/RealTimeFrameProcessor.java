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

import org.apache.log4j.Logger;

public class RealTimeFrameProcessor extends WebrtcStorageCanaryConsumer implements FrameVisitor.FrameProcessor {
    static final Logger logger = Logger.getLogger(CanaryListFragmentWorker.class);

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
                final long currentTime = System.currentTimeMillis();
                final long canaryStartTime = super.mCanaryStartTime.getTime();

                final String filePath = CanaryConstants.FIRST_FRAME_TS_FILE_PATH + super.firstFrameSentTSFile;
                final String frameSentTimeStr = FileUtils.readFileToString(new File(filePath)).trim();
                final long frameSentTime = Long.parseLong(frameSentTimeStr);

                // Delete the firstFrameSentTS file in case WebRTC-end fails to do so.
                new File(filePath).delete();

                // Check for a late consumer end start that would falsely reduce the measured
                // FirstFrameSentToFirstFrameConsumed time.
                if (canaryStartTime > frameSentTime) {
                    logger.info(
                            "Consumer started after master sent out first frame, not pushing the FirstFrameSentToFirstFrameConsumed metric.");
                } else {
                    logger.info("Timestamp read from file: " + frameSentTimeStr + " and " + frameSentTime + "...currentTime:" + currentTime);
                    long rtpToFirstFragment = currentTime - frameSentTime;
                    super.publishMetricToCW("FirstFrameSentToFirstFrameConsumed", rtpToFirstFragment,
                            StandardUnit.Milliseconds);
                }

                long timeToFirstFragment = currentTime - canaryStartTime;
                super.publishMetricToCW("TotalTimeToFirstFrameConsumed", timeToFirstFragment,
                        StandardUnit.Milliseconds);
            } catch (FileNotFoundException ex) {
                logger.error(
                        "Master-end timestamp file not found, try reducing the frequency of runs by increasing CANARY_DURATION_IN_SECONDS: "
                                + ex);
            } catch (IOException ioEx) {
                logger.error("IO Exception: " + ioEx);
            } finally {
                isFirstFrame = true;
                super.shutdownCanaryResources();
                System.exit(0);
            }
        } else {
            logger.info("Non first frame. Skipping...");
        }

    }
}
