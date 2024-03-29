package com.amazon.kinesis.video.canary.consumer;

import java.lang.AutoCloseable;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.Callable;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMediaClient;
import com.amazonaws.services.kinesisvideo.model.*;

import org.apache.log4j.Logger;

/*
    This is a modified version of the ListFragmentWorker example found here:
    https://github.com/aws/amazon-kinesis-video-streams-parser-library/blob/0056da083402a96cffcdfd8bca9433dd25eb7470/src/main/java/com/amazonaws/kinesisvideo/parser/examples/ListFragmentWorker.java#L7
 */

public class CanaryListFragmentWorker implements AutoCloseable, Callable {
    static final Logger logger = Logger.getLogger(CanaryListFragmentWorker.class);

    private final FragmentSelector mFragmentSelector;
    private final AmazonKinesisVideoArchivedMedia mAmazonKinesisVideoArchivedMedia;
    private final String mStreamName;

    public CanaryListFragmentWorker(final String streamName,
            final AWSCredentialsProvider credentialsProvider, final String endPoint,
            final Regions region,
            final FragmentSelector fragmentSelector) {
        this.mStreamName = streamName;
        this.mFragmentSelector = fragmentSelector;

        this.mAmazonKinesisVideoArchivedMedia = AmazonKinesisVideoArchivedMediaClient
                .builder()
                .withCredentials(credentialsProvider)
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endPoint, region.getName()))
                .build();
    }

    public static CanaryListFragmentWorker create(final String streamName,
            final AWSCredentialsProvider credentialsProvider,
            final Regions region,
            final AmazonKinesisVideo amazonKinesisVideo,
            final FragmentSelector fragmentSelector) {
        final GetDataEndpointRequest request = new GetDataEndpointRequest()
                .withAPIName(APIName.LIST_FRAGMENTS).withStreamName(streamName);
        final String endpoint = amazonKinesisVideo.getDataEndpoint(request).getDataEndpoint();

        return new CanaryListFragmentWorker(
                streamName, credentialsProvider, endpoint, region, fragmentSelector);
    }

    // Public for AutoClosable
    @Override
    public void close() throws Exception {
        this.mAmazonKinesisVideoArchivedMedia.shutdown();
    }

    @Override
    public List<Fragment> call() {
        List<Fragment> fragments = new ArrayList<>();
        try {
            logger.info("Start CanaryListFragment worker on stream " + this.mStreamName);

            ListFragmentsRequest request;
            ListFragmentsResult result;
            String nextToken = null;

            /* If result is truncated, keep making requests until nextToken is empty */
            do {
                request = new ListFragmentsRequest()
                        .withStreamName(this.mStreamName)
                        .withFragmentSelector(this.mFragmentSelector)
                        .withMaxResults(CanaryConstants.MAX_FRAGMENTS_PER_FRAGMENT_LIST)
                        .withNextToken(nextToken);
                result = this.mAmazonKinesisVideoArchivedMedia.listFragments(request);

                logger.info("List Fragments called on stream " + this.mStreamName +
                        " response " + result.getSdkHttpMetadata().getHttpStatusCode() +
                        " request ID " + result.getSdkResponseMetadata().getRequestId());

                for (Fragment f : result.getFragments()) {
                    fragments.add(f);
                }

                nextToken = result.getNextToken();
            } while (nextToken != null);

            logger.info("Retrieved " + fragments.size() + " Fragments and exiting CanaryListFragmentWorker for stream "
                    + this.mStreamName);
            return fragments;
        } catch (Exception e) {
            logger.error("Failure in CanaryListFragmentWorker for streamName " + this.mStreamName + " " + e);
            throw e;
        }
    }
}
