package com.amazon.kinesis.video.canary.consumer;

import com.amazonaws.kinesisvideo.parser.mkv.Frame;
import com.amazonaws.kinesisvideo.parser.mkv.FrameProcessException;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadata;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.FrameVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.MkvTrackMetadata;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;

/**
 * Frame processor that saves received video frames to disk for verification.
 * Saves the first frame of each second (every FPS-th frame) as a raw H.264 file.
 * These can later be compared against the source frames using verify.py.
 */
public class VideoRecordingFrameProcessor implements FrameVisitor.FrameProcessor {
    private static final Logger logger = Logger.getLogger(VideoRecordingFrameProcessor.class);
    private static final int FRAMES_PER_SECOND = 25;

    private final String outputDir;
    private final AtomicInteger totalFrameCount = new AtomicInteger(0);
    private final AtomicInteger savedFrameCount = new AtomicInteger(0);

    private VideoRecordingFrameProcessor(String outputDir) {
        this.outputDir = outputDir;
        new File(outputDir).mkdirs();
        logger.info("VideoRecordingFrameProcessor initialized: outputDir=" + outputDir + ", saving 1 frame per second");
    }

    public static VideoRecordingFrameProcessor create(String outputDir) {
        return new VideoRecordingFrameProcessor(outputDir);
    }

    public int getSavedFrameCount() {
        return savedFrameCount.get();
    }

    @Override
    public void process(Frame frame, MkvTrackMetadata trackMetadata, Optional<FragmentMetadata> fragmentMetadata,
            Optional<FragmentMetadataVisitor.MkvTagProcessor> tagProcessor) throws FrameProcessException {

        // Only save video frames (track number 1 is typically video)
        if (trackMetadata != null && trackMetadata.getTrackNumber() != 1) {
            return;
        }

        int frameNum = totalFrameCount.incrementAndGet();

        // Save only the first frame of each second
        if ((frameNum - 1) % FRAMES_PER_SECOND != 0) {
            return;
        }

        int saved = savedFrameCount.incrementAndGet();

        ByteBuffer frameData = frame.getFrameData();
        byte[] data = new byte[frameData.remaining()];
        frameData.get(data);

        String fileName = String.format("frame-%05d.h264", saved);
        File outputFile = new File(outputDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(data);
        } catch (IOException e) {
            logger.error("Failed to write frame " + fileName + ": " + e.getMessage());
            throw new FrameProcessException("Failed to write frame to disk", e);
        }

        if (saved % 60 == 0 || saved == 1) {
            logger.info("Saved frame " + saved + " (from raw frame " + frameNum + "): " + fileName + " (" + data.length + " bytes)");
        }
    }

    @Override
    public void close() {
        logger.info("VideoRecordingFrameProcessor closed. Total raw frames: " + totalFrameCount.get() + ", saved: " + savedFrameCount.get());
    }
}
