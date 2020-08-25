package com.amazonaws.kinesisvideo.labeldetectionwebapp.kvsservices;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;

import com.amazonaws.kinesisvideo.labeldetectionwebapp.JpaFrame;
import com.amazonaws.kinesisvideo.parser.ebml.InputStreamParserByteSource;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoCommon;
import com.amazonaws.kinesisvideo.parser.mkv.MkvElementVisitException;
import com.amazonaws.kinesisvideo.parser.mkv.MkvElementVisitor;
import com.amazonaws.kinesisvideo.parser.mkv.StreamingMkvReader;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMediaClient;
import com.amazonaws.services.kinesisvideo.model.*;

import lombok.extern.slf4j.Slf4j;


@Slf4j
public class GetMediaArchivedRekognitionWorker extends KinesisVideoCommon implements Callable {
    private FragmentSelector fragmentSelector;
    private final AmazonKinesisVideoArchivedMedia amazonKinesisVideoArchivedMediaListFragments;
    private final AmazonKinesisVideoArchivedMedia amazonKinesisVideoArchivedMediaGetMediaForFragmentList;
    private MkvElementVisitor elementVisitor;
    private H264ImageDetectionBoundingBoxSaver h264ImageDetectionBoundingBoxSaver;
    private final long fragmentsPerRequest = 100;
    private AtomicLong playbackLength;

    public GetMediaArchivedRekognitionWorker(final String streamName,
                                             final AWSCredentialsProvider awsCredentialsProvider,
                                             final String listFragmentsEndPoint,
                                             final String getMediaForFragmentListEndPoint,
                                             final Regions region,
                                             final FragmentSelector fragmentSelector,
                                             final MkvElementVisitor elementVisitor,
                                             final H264ImageDetectionBoundingBoxSaver h264ImageDetectionBoundingBoxSaver,
                                             final AtomicLong playbackLength) {
        super(region, awsCredentialsProvider, streamName);
        this.fragmentSelector = fragmentSelector;
        this.elementVisitor = elementVisitor;
        this.playbackLength = playbackLength;
        this.h264ImageDetectionBoundingBoxSaver = h264ImageDetectionBoundingBoxSaver;

        amazonKinesisVideoArchivedMediaListFragments = AmazonKinesisVideoArchivedMediaClient
                .builder()
                .withCredentials(awsCredentialsProvider)
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(listFragmentsEndPoint, region.getName()))
                .build();

        amazonKinesisVideoArchivedMediaGetMediaForFragmentList = AmazonKinesisVideoArchivedMediaClient
                .builder()
                .withCredentials(awsCredentialsProvider)
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(getMediaForFragmentListEndPoint, region.getName()))
                .build();

    }

    public static GetMediaArchivedRekognitionWorker create(final String streamName,
                                                           final AWSCredentialsProvider awsCredentialsProvider,
                                                           final Regions region,
                                                           final AmazonKinesisVideo amazonKinesisVideo,
                                                           final FragmentSelector fragmentSelector,
                                                           final MkvElementVisitor elementVisitor,
                                                           final H264ImageDetectionBoundingBoxSaver h264ImageDetectionBoundingBoxSaver,
                                                           final String listFragmentsEndpoint,
                                                           final String getMediaForFragmentListEndpoint,
                                                           final AtomicLong playbackLength) {

        return new GetMediaArchivedRekognitionWorker(
                streamName, awsCredentialsProvider, listFragmentsEndpoint, getMediaForFragmentListEndpoint, region, fragmentSelector, elementVisitor, h264ImageDetectionBoundingBoxSaver, playbackLength);
    }

    @Override
    public List<JpaFrame> call() {
        try {
            log.info("Start ListFragment worker on stream {}", streamName);

            /* ---------------------------- LIST FRAGMENTS SECTION ---------------------------- */
            ListFragmentsRequest listFragmentsRequest = new ListFragmentsRequest()
                    .withStreamName(streamName).withFragmentSelector(fragmentSelector).withMaxResults(fragmentsPerRequest);

            log.info(listFragmentsRequest.toString());

            ListFragmentsResult listFragmentsResult = amazonKinesisVideoArchivedMediaListFragments.listFragments(listFragmentsRequest);


            log.info("List Fragments called on stream {} response {} request ID {}",
                    streamName,
                    listFragmentsResult.getSdkHttpMetadata().getHttpStatusCode(),
                    listFragmentsResult.getSdkResponseMetadata().getRequestId());


            List<String> fragmentNumbers = new ArrayList<>();
            for (Fragment f : listFragmentsResult.getFragments()) {
                fragmentNumbers.add(f.getFragmentNumber());
                playbackLength.addAndGet(f.getFragmentLengthInMilliseconds());
            }

            String nextToken = listFragmentsResult.getNextToken();

            /* If result is truncated, keep making requests until nextToken is empty */
            while (nextToken != null) {
                listFragmentsRequest = new ListFragmentsRequest()
                        .withStreamName(streamName).withNextToken(nextToken);
                listFragmentsResult = amazonKinesisVideoArchivedMediaListFragments.listFragments(listFragmentsRequest);

                for (Fragment f : listFragmentsResult.getFragments()) {
                    fragmentNumbers.add(f.getFragmentNumber());
                }
                nextToken = listFragmentsResult.getNextToken();
            }

            Collections.sort(fragmentNumbers);

            /* ------------------------- GET MEDIA SECTION ------------------------- */

            if (fragmentNumbers.size() > 0) {
                GetMediaForFragmentListRequest getMediaFragmentListRequest = new GetMediaForFragmentListRequest()
                        .withFragments(fragmentNumbers)
                        .withStreamName(streamName);

                GetMediaForFragmentListResult getMediaForFragmentListResult = amazonKinesisVideoArchivedMediaGetMediaForFragmentList.getMediaForFragmentList(getMediaFragmentListRequest);

                StreamingMkvReader mkvStreamReader = StreamingMkvReader.createDefault(
                        new InputStreamParserByteSource(getMediaForFragmentListResult.getPayload()));

                try {
                    mkvStreamReader.apply(this.elementVisitor);
                } catch (final MkvElementVisitException e) {
                    log.warn("Exception while accepting visitor {}", e);
                }
            }

        } catch (Throwable t) {
            log.error("Failure in GetMediaArchivedRekognitionWorker for streamName {} {} with timestamp range {}", streamName, t.toString(), fragmentSelector.getTimestampRange().toString());
            t.printStackTrace();
            throw t;
        } finally {
            log.info("Exiting GetMediaArchivedRekognitionWorker for stream {}", streamName);
            return this.h264ImageDetectionBoundingBoxSaver.getFramesInTask();
        }
    }
}