package com.amazonaws.kinesisvideo.utilities;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoFrameViewer;
import com.amazonaws.kinesisvideo.parser.mkv.Frame;
import com.amazonaws.kinesisvideo.parser.mkv.FrameProcessException;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadata;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.H264FrameDecoder;
import com.amazonaws.kinesisvideo.parser.utilities.MkvTrackMetadata;
import com.amazonaws.services.rekognition.AmazonRekognitionClient;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.AmazonRekognition;

import com.amazonaws.services.rekognition.model.*;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;

@Slf4j
public class H264FrameLabelDetector extends H264FrameDecoder {

    private final int sampleRate;
    private int frameNumber = 0;
    AmazonRekognition rekognitionClient = AmazonRekognitionClientBuilder.defaultClient();

    protected H264FrameLabelDetector(final int sampleRate) {
        super();
        this.sampleRate = sampleRate;
    }

    public static H264FrameLabelDetector create(int sampleRate) {
        return new H264FrameLabelDetector(sampleRate);
    }

    @Override
    public void process(Frame frame, MkvTrackMetadata trackMetadata, Optional<FragmentMetadata> fragmentMetadata,
                        Optional<FragmentMetadataVisitor.MkvTagProcessor> tagProcessor) throws FrameProcessException {

        boolean isKeyFrame = frame.isKeyFrame();
        BufferedImage bufferedImage = decodeH264Frame(frame, trackMetadata);

        /* Only send key frames to Rekognition */
        if (sampleRate == 0) {
            if (frame.isKeyFrame()) {
                sendFrameToRekognition(bufferedImage);
            }
        }
        else {
            /* Only send to Rekognition every N frames */
            if ((frameNumber % sampleRate) == 0) {
                sendFrameToRekognition(bufferedImage);
            }
            frameNumber++;
        }

    }

    public void sendFrameToRekognition(BufferedImage bufferedImage) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            ImageIO.write(bufferedImage, "png", outputStream);
            ByteBuffer imageBytes = ByteBuffer.wrap(outputStream.toByteArray());
            long startTime = System.nanoTime();
            detectLabels(imageBytes);  // Label Detection
            //detectFaces(imageBytes);    // Face Detection
            //recognizeCelebrities(imageBytes); // Celebrity Detection
            //detectText(imageBytes); // Text Detection
            long endTime = System.nanoTime();
            long totalTime = endTime - startTime;
            double seconds = (double)totalTime / 1_000_000_000.0;
            log.info("Time to Rekognize frame: " + seconds + " seconds");
            log.info("----------------------");
        }
        catch (IOException e) {
            log.warn("Error with png conversion", e);
        }
    }

    public void detectLabels(ByteBuffer imageBytes) {
        DetectLabelsRequest request = new DetectLabelsRequest()
                .withImage(new Image()
                        .withBytes(imageBytes))
                .withMaxLabels(10)
                .withMinConfidence(77F);

        try {
            DetectLabelsResult result = rekognitionClient.detectLabels(request);
            List<Label> labels = result.getLabels();

            log.info("Detected Labels in " + Thread.currentThread().getName() + ":");
            for (Label label: labels) {
                log.info(label.getName() + ": " + label.getConfidence().toString());
            }
        } catch (AmazonRekognitionException e) {
            log.error(e.getMessage());
        }
    }

    public void detectFaces(ByteBuffer imageBytes) {
        DetectFacesRequest request = new DetectFacesRequest()
                .withImage(new Image()
                        .withBytes(imageBytes));

        try {
            DetectFacesResult result = rekognitionClient.detectFaces(request);
            List<FaceDetail> faceDetails = result.getFaceDetails();

            log.info("Face details:");
            for (FaceDetail faceDetail: faceDetails) {
                log.info(faceDetail.toString());
            }
            log.info("----------------------");

        } catch (AmazonRekognitionException e) {
            log.error(e.getMessage());
        }

    }

    public void recognizeCelebrities(ByteBuffer imageBytes) {
        RecognizeCelebritiesRequest request = new RecognizeCelebritiesRequest()
                .withImage(new Image()
                        .withBytes(imageBytes));

        try {
            RecognizeCelebritiesResult result = rekognitionClient.recognizeCelebrities(request);
            List<Celebrity> celebrities = result.getCelebrityFaces();

            log.info("Detected Celebrities:");
            for (Celebrity celebrity: celebrities) {
                log.info(celebrity.getName() + ": " + celebrity.getMatchConfidence().toString());
            }
            log.info("----------------------");

        } catch (AmazonRekognitionException e) {
            log.error(e.getMessage());
        }
    }

    public void detectText(ByteBuffer imageBytes) {
        DetectTextRequest request = new DetectTextRequest()
                .withImage(new Image()
                        .withBytes(imageBytes));

        try {
            DetectTextResult result = rekognitionClient.detectText(request);
            List<TextDetection> textDetections = result.getTextDetections();

            log.info("Detected Text:");
            for (TextDetection textDetection: textDetections) {
                log.info(textDetection.toString());
            }
            log.info("----------------------");
        }  catch (AmazonRekognitionException e) {
            log.error(e.getMessage());
        }
    }


}

