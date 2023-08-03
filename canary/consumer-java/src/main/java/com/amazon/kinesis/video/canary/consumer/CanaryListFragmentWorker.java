package com.amazon.kinesis.video.canary.consumer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.text.MessageFormat;

import org.jcodec.common.DictionaryCompressor.Int;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMediaClient;
import com.amazonaws.services.kinesisvideo.model.*;
import lombok.extern.slf4j.Slf4j;


/*
    This is a modified version of the ListFragmentWorker example found here:
    https://github.com/aws/amazon-kinesis-video-streams-parser-library/blob/0056da083402a96cffcdfd8bca9433dd25eb7470/src/main/java/com/amazonaws/kinesisvideo/parser/examples/ListFragmentWorker.java#L7
 */


/* This worker retrieves all fragments within the specified TimestampRange from a specified Kinesis Video Stream and returns them in a list */
@Slf4j
public class CanaryListFragmentWorker implements Callable {
    private final FragmentSelector fragmentSelector;
    private final AmazonKinesisVideoArchivedMedia amazonKinesisVideoArchivedMedia;
    private final long fragmentsPerRequest = 100;
    private final Regions region;
    private final AWSCredentialsProvider credentialsProvider;
    protected final String streamName;

    public CanaryListFragmentWorker(final String streamName,
                              final AWSCredentialsProvider credentialsProvider, final String endPoint,
                              final Regions region,
                              final FragmentSelector fragmentSelector) {
        this.region = region;
        this.credentialsProvider = credentialsProvider;
        this.streamName = streamName;
        this.fragmentSelector = fragmentSelector;

        amazonKinesisVideoArchivedMedia = AmazonKinesisVideoArchivedMediaClient
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

    @Override
    public List<CanaryFragment> call() {
        List<CanaryFragment> fragments = new ArrayList<>();
        try {
            System.out.println(MessageFormat.format("Start ListFragment worker on stream {0}", streamName));

            ListFragmentsRequest request = new ListFragmentsRequest()
                    .withStreamName(streamName).withFragmentSelector(fragmentSelector).withMaxResults(fragmentsPerRequest);

            ListFragmentsResult result = amazonKinesisVideoArchivedMedia.listFragments(request);


            System.out.println(MessageFormat.format("List Fragments called on stream {0} response {1} request ID {2}",
                    streamName,
                    result.getSdkHttpMetadata().getHttpStatusCode(),
                    result.getSdkResponseMetadata().getRequestId()));

            for (Fragment f: result.getFragments()) {
                fragments.add(new CanaryFragment(f));
            }
            String nextToken = result.getNextToken();

            /* If result is truncated, keep making requests until nextToken is empty */
            while (nextToken != null) {
                request = new ListFragmentsRequest()
                        .withStreamName(streamName).withNextToken(nextToken);
                result = amazonKinesisVideoArchivedMedia.listFragments(request);

                for (Fragment f: result.getFragments()) {
                    fragments.add(new CanaryFragment(f));
                }
                nextToken = result.getNextToken();
            }
           
            fragments.sort(Comparator.comparing(CanaryFragment::getFragmentNumberInt));

            for (CanaryFragment cf : fragments) {
                System.out.println(MessageFormat.format("Retrieved fragment number {0} ", cf.getFragment().getFragmentNumber()));
            }
        }
        catch (Throwable t) {
            System.out.println(MessageFormat.format("Failure in CanaryListFragmentWorker for streamName {0} {1}", streamName, t.toString()));
            throw t;
        } finally {
            System.out.println(MessageFormat.format("Retrieved {0} Fragments and exiting CanaryListFragmentWorker for stream {1}", fragments.size(), streamName));
            return fragments;
        }
    }
}