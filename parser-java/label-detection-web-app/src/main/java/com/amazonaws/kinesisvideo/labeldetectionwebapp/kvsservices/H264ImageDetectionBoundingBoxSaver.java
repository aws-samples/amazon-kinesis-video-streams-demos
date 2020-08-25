package com.amazonaws.kinesisvideo.labeldetectionwebapp.kvsservices;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.List;

import com.amazonaws.kinesisvideo.labeldetectionwebapp.JpaFrame;
import com.amazonaws.kinesisvideo.labeldetectionwebapp.TimestampCollection;
import com.amazonaws.kinesisvideo.parser.mkv.Frame;
import com.amazonaws.kinesisvideo.parser.mkv.FrameProcessException;
import com.amazonaws.kinesisvideo.parser.utilities.*;
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
public class H264ImageDetectionBoundingBoxSaver extends H264FrameDecoder {

    private final AmazonRekognition rekognitionClient = AmazonRekognitionClientBuilder.defaultClient();
    private final int sampleRate;
    private Set<String> labels;
    private Map<JpaFrame, JpaFrame> frames;
    private Map<String, TimestampCollection> labelToTimestamps;
    private List<JpaFrame> framesInTask = new ArrayList<>();
    private long frameNumber;

    private H264ImageDetectionBoundingBoxSaver(final int sampleRate, Set<String> labels, Map<JpaFrame, JpaFrame> frames, Map<String, TimestampCollection> labelToTimestamps) {
        this.sampleRate = sampleRate;
        this.labels = labels;
        this.frames = frames;
        this.labelToTimestamps = labelToTimestamps;
    }

    public static H264ImageDetectionBoundingBoxSaver create(final int sampleRate, Set<String> labels, Map<JpaFrame, JpaFrame> frames, Map<String, TimestampCollection> labelToTimestamps) {
        return new H264ImageDetectionBoundingBoxSaver(sampleRate, labels, frames, labelToTimestamps);
    }

    @Override
    public void process(Frame frame, MkvTrackMetadata trackMetadata, Optional<FragmentMetadata> fragmentMetadata,
                        Optional<FragmentMetadataVisitor.MkvTagProcessor> tagProcessor) throws FrameProcessException {

        BufferedImage bufferedImage = decodeH264Frame(frame, trackMetadata);

        /* Only send key frames to Rekognition */
        if (sampleRate == 0) {
            if (frame.isKeyFrame()) {
                saveFrame(bufferedImage);
            }
        } else {
            /* Only send to Rekognition every N frames */
            if ((frameNumber % sampleRate) == 0) {
                saveFrame(bufferedImage);
            }
            frameNumber++;
        }
    }

    public void saveFrame(final BufferedImage bufferedImage) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            ImageIO.write(bufferedImage, "png", outputStream);
            ByteBuffer imageBytes = ByteBuffer.wrap(outputStream.toByteArray());
            List<BoundingBox> boundingBoxes = new ArrayList<>();
            List<String> labelsInFrame = sendToRekognition(imageBytes, bufferedImage, boundingBoxes);

            for (BoundingBox boundingBox : boundingBoxes) {
                addBoundingBoxToImage(bufferedImage, boundingBox);
            }
            byte[] boundingBoxImageByteArray = toByteArrayAutoClosable(bufferedImage, "png");
            JpaFrame jpaFrameToSave = new JpaFrame(boundingBoxImageByteArray);

            for (String label : labelsInFrame) {
                jpaFrameToSave.addLabel(label);
                this.labelToTimestamps.get(label).addFrame(jpaFrameToSave);
            }

            this.frames.put(jpaFrameToSave, jpaFrameToSave);
            this.framesInTask.add(jpaFrameToSave);

        } catch (IOException e) {
            log.warn("Error with png conversion", e);
            System.out.println("Error with byte buffer conversion");
        }
    }

    public List<String> sendToRekognition(ByteBuffer imageBytes, BufferedImage bufferedImage, List<BoundingBox> boundingBoxes) {
        List<String> labelsInFrame = new ArrayList<>();

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
            for (Label label : labels) {
                log.info(label.getName() + ": " + label.getConfidence().toString());
                this.labels.add(label.getName());
                this.labelToTimestamps.putIfAbsent(label.getName(), new TimestampCollection());
                labelsInFrame.add(label.getName());
            }
            log.info("----------------------");

            for (Label label : labels) {
                for (Instance instance : label.getInstances()) {
                    boundingBoxes.add(instance.getBoundingBox());
                    final int left = (int) (instance.getBoundingBox().getLeft() * width);
                    final int top = (int) (instance.getBoundingBox().getTop() * height);
                    addTextToImage(bufferedImage, label.getName(), left, top);
                }
            }
            return labelsInFrame;

        } catch (AmazonRekognitionException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void addBoundingBoxToImage(@NonNull BufferedImage bufferedImage, BoundingBox boundingBox) {
        Graphics graphics = bufferedImage.getGraphics();

        graphics.setColor(Color.RED);

        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        final int left = (int) (boundingBox.getLeft() * width);
        final int top = (int) (boundingBox.getTop() * height);
        final int bbWidth = (int) (boundingBox.getWidth() * width);
        final int bbHeight = (int) (boundingBox.getHeight() * height);

        graphics.drawRect(left, top, bbWidth, bbHeight);
    }

    private static byte[] toByteArrayAutoClosable(BufferedImage image, String type) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, type, out);
            return out.toByteArray();
        }
    }

    public List<JpaFrame> getFramesInTask() {
        return this.framesInTask;
    }

}