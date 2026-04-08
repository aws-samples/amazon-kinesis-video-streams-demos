package com.amazon.kinesis.video.canary.consumer;

import com.amazonaws.kinesisvideo.parser.mkv.Frame;
import com.amazonaws.kinesisvideo.parser.mkv.FrameProcessException;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadata;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.FrameVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.MkvTrackMetadata;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;

/**
 * Frame processor that saves received video frames to disk for verification.
 * Saves the first frame of each second (every FPS-th frame) as a raw H.264 file.
 * Also writes a metadata file with the source frame index offset so verify.py
 * can align received frames against source frames without brute-force SSIM search.
 *
 * The source frame index is derived from the total frame count within the stream.
 * The master sends frames 1-1500 in a loop at 25fps, so:
 *   source_index = ((total_frames_before_this_one) % 1500) + 1
 */
public class VideoRecordingFrameProcessor implements FrameVisitor.FrameProcessor {
    private static final Logger logger = Logger.getLogger(VideoRecordingFrameProcessor.class);
    private static final int FRAMES_PER_SECOND = 25;
    private static final int TOTAL_SOURCE_FRAMES = 1500;

    private final String outputDir;
    private final AtomicInteger totalFrameCount = new AtomicInteger(0);
    private final AtomicInteger savedFrameCount = new AtomicInteger(0);
    private int firstSavedSourceIndex = -1;

    private VideoRecordingFrameProcessor(String outputDir) {
        this.outputDir = outputDir;
        new File(outputDir).mkdirs();
        logger.info("VideoRecordingFrameProcessor initialized: outputDir=" + outputDir);
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
        if (trackMetadata != null && !java.math.BigInteger.ONE.equals(trackMetadata.getTrackNumber())) {
            return;
        }

        int frameNum = totalFrameCount.incrementAndGet();

        // Save only the first frame of each second
        if ((frameNum - 1) % FRAMES_PER_SECOND != 0) {
            return;
        }

        int saved = savedFrameCount.incrementAndGet();

        // Compute which source frame this corresponds to.
        // frameNum is 1-based, source loops 1-1500.
        int sourceIndex = ((frameNum - 1) % TOTAL_SOURCE_FRAMES) + 1;

        // Record the first saved frame's source index for alignment
        if (firstSavedSourceIndex < 0) {
            firstSavedSourceIndex = sourceIndex;
            writeMetadata();
        }

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
            logger.info("Saved frame " + saved + " (raw frame " + frameNum + ", source index " + sourceIndex + "): " + fileName);
        }
    }

    private void writeMetadata() {
        File metaFile = new File(outputDir, "metadata.txt");
        try (FileWriter fw = new FileWriter(metaFile)) {
            fw.write("start_frame_index=" + firstSavedSourceIndex + "\n");
            logger.info("Wrote metadata: start_frame_index=" + firstSavedSourceIndex);
        } catch (IOException e) {
            logger.error("Failed to write metadata: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        logger.info("VideoRecordingFrameProcessor closed. Total raw frames: " + totalFrameCount.get()
                + ", saved: " + savedFrameCount.get()
                + ", first source index: " + firstSavedSourceIndex);
    }
}
