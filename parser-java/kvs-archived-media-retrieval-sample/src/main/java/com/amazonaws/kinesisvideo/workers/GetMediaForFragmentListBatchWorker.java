package com.amazonaws.kinesisvideo.workers;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.kinesisvideo.parser.ebml.InputStreamParserByteSource;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoCommon;
import com.amazonaws.kinesisvideo.parser.mkv.MkvElementVisitException;
import com.amazonaws.kinesisvideo.parser.mkv.MkvElementVisitor;
import com.amazonaws.kinesisvideo.parser.mkv.StreamingMkvReader;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMediaClient;
import com.amazonaws.services.kinesisvideo.model.APIName;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointRequest;
import com.amazonaws.services.kinesisvideo.model.GetMediaForFragmentListRequest;
import com.amazonaws.services.kinesisvideo.model.GetMediaForFragmentListResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class GetMediaForFragmentListBatchWorker extends KinesisVideoCommon implements Runnable {
    private final AmazonKinesisVideoArchivedMedia amazonKinesisVideoArchivedMedia;
    private final MkvElementVisitor elementVisitor;
    private final List<String> fragmentNumbers;
    private final int MAX_CONTENT_BYTES = 16000;

    public GetMediaForFragmentListBatchWorker(final String streamName, final List<String> fragmentNumbers,
                                              final AWSCredentialsProvider awsCredentialsProvider, final String endPoint,
                                              final Regions region, final MkvElementVisitor elementVisitor) {
        super(region, awsCredentialsProvider, streamName);
        this.fragmentNumbers = fragmentNumbers;

        this.elementVisitor = elementVisitor;
        amazonKinesisVideoArchivedMedia = AmazonKinesisVideoArchivedMediaClient
                .builder()
                .withCredentials(awsCredentialsProvider)
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endPoint, region.getName()))
                .build();
    }

    public static GetMediaForFragmentListBatchWorker create(final String streamName, final List<String> fragmentNumbers,
                                                            final AWSCredentialsProvider awsCredentialsProvider,
                                                            final Regions region,
                                                            final AmazonKinesisVideo amazonKinesisVideo,
                                                            final MkvElementVisitor elementVisitor) {
        final GetDataEndpointRequest request = new GetDataEndpointRequest()
                .withAPIName(APIName.GET_MEDIA_FOR_FRAGMENT_LIST).withStreamName(streamName);
        final String endpoint = amazonKinesisVideo.getDataEndpoint(request).getDataEndpoint();
        return new GetMediaForFragmentListBatchWorker(
                streamName, fragmentNumbers, awsCredentialsProvider, endpoint, region, elementVisitor);
    }

    @Override
    public void run() {
        try {
            log.info("Start GetMediaForFragmentListBatch worker on stream {}", streamName);
            final GetMediaForFragmentListResult result = amazonKinesisVideoArchivedMedia.getMediaForFragmentList(
                    new GetMediaForFragmentListRequest()
                            .withFragments(fragmentNumbers)
                            .withStreamName(streamName));

            log.info("GetMediaForFragmentListBatch called on stream {} response {} requestId {}",
                    streamName,
                    result.getSdkHttpMetadata().getHttpStatusCode(),
                    result.getSdkResponseMetadata().getRequestId());
            /*final StreamingMkvReader mkvStreamReader = StreamingMkvReader.createDefault(
                    new InputStreamParserByteSource(result.getPayload()));*/
            final StreamingMkvReader mkvStreamReader = StreamingMkvReader.createWithMaxContentSize(
                    new InputStreamParserByteSource(result.getPayload()), MAX_CONTENT_BYTES);

            log.info("StreamingMkvReader created for stream {} ", streamName);
            try {
                mkvStreamReader.apply(this.elementVisitor);
            } catch (final MkvElementVisitException e) {
                log.warn("Exception while accepting visitor {}", e);
            }
        } catch (final Throwable t) {
            log.error("Failure in GetMediaForFragmentListBatchWorker for streamName {} {}", streamName, t);
            throw t;
        } finally {
            log.info("Exiting GetMediaWorker for stream {}", streamName);
        }
    }
}
