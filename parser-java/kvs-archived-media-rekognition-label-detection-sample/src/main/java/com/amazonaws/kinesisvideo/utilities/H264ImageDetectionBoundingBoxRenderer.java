package com.amazonaws.kinesisvideo.utilities;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoFrameViewer;
import com.amazonaws.kinesisvideo.parser.mkv.Frame;
import com.amazonaws.kinesisvideo.parser.mkv.FrameProcessException;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadata;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.H264FrameRenderer;
import com.amazonaws.kinesisvideo.parser.utilities.MkvTrackMetadata;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.*;
import com.amazonaws.services.rekognition.model.Image;
import com.amazonaws.services.rekognition.model.Label;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import static com.amazonaws.kinesisvideo.parser.utilities.BufferedImageUtil.addTextToImage;


import javax.imageio.ImageIO;

@Slf4j
public class H264ImageDetectionBoundingBoxRenderer extends H264FrameRenderer {

    private final KinesisVideoFrameViewer kinesisVideoFrameViewer;
    private final int sampleRate;
    private int frameNumber;
    private final AmazonRekognition rekognitionClient = AmazonRekognitionClientBuilder.defaultClient();
    private final Color boundingBoxColor = Color.RED;

    private H264ImageDetectionBoundingBoxRenderer(final KinesisVideoFrameViewer kinesisVideoFrameViewer, final int sampleRate) {
        super(kinesisVideoFrameViewer);
        this.kinesisVideoFrameViewer = kinesisVideoFrameViewer;
        this.sampleRate = sampleRate;
    }

    public static H264ImageDetectionBoundingBoxRenderer create(final KinesisVideoFrameViewer kinesisVideoFrameViewer, final int sampleRate) {
        return new H264ImageDetectionBoundingBoxRenderer(kinesisVideoFrameViewer, sampleRate);
    }

    @Override
    public void process(Frame frame, MkvTrackMetadata trackMetadata, Optional<FragmentMetadata> fragmentMetadata,
                        Optional<FragmentMetadataVisitor.MkvTagProcessor> tagProcessor) throws FrameProcessException {

        BufferedImage bufferedImage = decodeH264Frame(frame, trackMetadata);

        /* Only send key frames to Rekognition */
        if (sampleRate == 0) {
            if (frame.isKeyFrame()) {
                renderFrame(bufferedImage);
            }
        }
        else {
            /* Only send to Rekognition every N frames */
            if ((frameNumber % sampleRate) == 0) {
                renderFrame(bufferedImage);
            }
            frameNumber++;
        }
    }

    protected void renderFrame(final BufferedImage bufferedImage) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            ImageIO.write(bufferedImage, "png", outputStream);
            ByteBuffer imageBytes = ByteBuffer.wrap(outputStream.toByteArray());
            long startTime = System.nanoTime();
            sendToRekognition(imageBytes, bufferedImage);
            long endTime = System.nanoTime();
            long totalTime = endTime - startTime;
            double seconds = (double)totalTime / 1_000_000_000.0;
            log.info("Time to Rekognize frame: " + seconds + " seconds");
            log.info("----------------------");

            kinesisVideoFrameViewer.update(bufferedImage);
        }
        catch (IOException e) {
            log.warn("Error with png conversion", e);
        }
    }

    protected void sendToRekognition(ByteBuffer imageBytes, BufferedImage bufferedImage) {
        DetectLabelsRequest request = new DetectLabelsRequest()
                .withImage(new Image()
                        .withBytes(imageBytes))
                .withMaxLabels(10)
                .withMinConfidence(77F);

        try {
            DetectLabelsResult result = rekognitionClient.detectLabels(request);
            List<Label> labels = result.getLabels();

            int width = bufferedImage.getWidth();
            int height = bufferedImage.getHeight();

            log.info("Detected Labels:");
            for (Label label: labels) {
                log.info(label.getName() + ": " + label.getConfidence().toString());
            }

            for (Label label: labels) {
                for (Instance instance: label.getInstances()) {
                    addBoundingBoxToImage(bufferedImage, instance.getBoundingBox());
                    final int left = (int) (instance.getBoundingBox().getLeft() * width);
                    final int top = (int) (instance.getBoundingBox().getTop() * height);
                    addTextToImage(bufferedImage, label.getName(), left, top);
                }
            }
        } catch (AmazonRekognitionException e) {
            log.error(e.getMessage());
        }
    }

    public void addBoundingBoxToImage(@NonNull BufferedImage bufferedImage, BoundingBox boundingBox) {
        Graphics graphics = bufferedImage.getGraphics();

        graphics.setColor(boundingBoxColor);

        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        final int left = (int) (boundingBox.getLeft() * width);
        final int top = (int) (boundingBox.getTop() * height);
        final int bbWidth = (int) (boundingBox.getWidth() * width);
        final int bbHeight = (int) (boundingBox.getHeight() * height);

        graphics.drawRect(left, top, bbWidth, bbHeight);
    }
}