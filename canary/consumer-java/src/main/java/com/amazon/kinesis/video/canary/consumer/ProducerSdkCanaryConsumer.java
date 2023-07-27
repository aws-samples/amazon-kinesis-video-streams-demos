package com.amazon.kinesis.video.canary.consumer;

import com.amazonaws.auth.SystemPropertiesCredentialsProvider;
import com.amazonaws.kinesisvideo.parser.examples.ContinuousGetMediaWorker;
import com.amazonaws.kinesisvideo.parser.mkv.MkvElementVisitException;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.FrameVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.consumer.FragmentMetadataCallback;
import com.amazonaws.kinesisvideo.parser.utilities.consumer.GetMediaResponseStreamConsumer;
import com.amazonaws.kinesisvideo.parser.utilities.consumer.GetMediaResponseStreamConsumerFactory;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClientBuilder;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
import com.amazonaws.services.kinesisvideo.model.StartSelector;
import com.amazonaws.services.kinesisvideo.model.StartSelectorType;


import com.amazonaws.services.kinesisvideo.model.APIName;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointRequest;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMedia;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.kinesisvideo.model.TimestampRange;
import com.amazonaws.services.kinesisvideo.model.FragmentSelector;
import java.text.SimpleDateFormat;
// import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMediaClient;
// import com.amazonaws.services.kinesisvideo.model.ListFragmentsRequest;
// import com.amazonaws.services.kinesisvideo.model.ListFragmentsResult;
// import com.amazonaws.kinesisvideo.parser.examples.ListFragmentWorker;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


import lombok.extern.log4j.Log4j2;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

@Slf4j
public class ProducerSdkCanaryConsumer {

    private static void getIntervalMetrics(){
        System.out.println("10 sec have passed...");
        
    }

    public static void main(final String[] args) throws Exception {
        String streamNamePrefix = System.getenv("CANARY_STREAM_NAME");
        String canaryType = System.getenv("CANARY_TYPE");
        String canaryFragmentSizeStr = System.getenv("FRAGMENT_SIZE_IN_BYTES");
        String canaryLabel = System.getenv("CANARY_LABEL");
        String region = System.getenv("AWS_DEFAULT_REGION");

        // TODO: Revert back to adding the Canary descriptions to stream name, removed for testing
        final String streamName = streamNamePrefix;

        Integer canaryRunTime = Integer.parseInt(System.getenv("CANARY_DURATION_IN_SECONDS"));
        log.info("Stream name {}", streamName);

        final SystemPropertiesCredentialsProvider credentialsProvider = new SystemPropertiesCredentialsProvider();
        final AmazonKinesisVideo amazonKinesisVideo = AmazonKinesisVideoClientBuilder.standard()
                .withRegion(region)
                .withCredentials(credentialsProvider)
                .build();
        final AmazonCloudWatchAsync amazonCloudWatch = AmazonCloudWatchAsyncClientBuilder.standard()
                .withRegion(region)
                .withCredentials(credentialsProvider)
                .build();

        GetMediaResponseStreamConsumerFactory consumerFactory = new GetMediaResponseStreamConsumerFactory() {
            @Override
            public GetMediaResponseStreamConsumer createConsumer() throws IOException {
                return new GetMediaResponseStreamConsumer() {
                    @Override
                    public void process(InputStream inputStream, FragmentMetadataCallback fragmentMetadataCallback) throws MkvElementVisitException, IOException {
                        processWithFragmentEndCallbacks(inputStream, fragmentMetadataCallback,
                                FrameVisitor.create(new CanaryFrameProcessor(amazonCloudWatch, streamName, canaryLabel),
                                        Optional.of(new FragmentMetadataVisitor.BasicMkvTagProcessor())));
                    }
                };
            }
        };
        ContinuousGetMediaWorker getMediaWorker = ContinuousGetMediaWorker.create(Regions.fromName(region),
                credentialsProvider, streamName, new StartSelector().withStartSelectorType(StartSelectorType.NOW),
                amazonKinesisVideo,
                consumerFactory);

        Timer timer = new Timer("Timer");
        TimerTask task = new TimerTask() {
            public void run() {
                getMediaWorker.stop();
            }
        };
        long delay = canaryRunTime * 1000;
        timer.schedule(task, delay);



        final GetDataEndpointRequest dataEndpointRequest = new GetDataEndpointRequest()
            .withAPIName(APIName.LIST_FRAGMENTS).withStreamName(streamName);
        final String dataEndpoint = amazonKinesisVideo.getDataEndpoint(dataEndpointRequest).getDataEndpoint();

        TimestampRange timestampRange = new TimestampRange();
        timestampRange.setStartTimestamp(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").parse("27/07/2023 17:50:38"));
        timestampRange.setEndTimestamp(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").parse("27/07/2023 17:59:38")); 
        FragmentSelector fragmentSelector = new FragmentSelector();
            fragmentSelector.setFragmentSelectorType("SERVER_TIMESTAMP");
            fragmentSelector.setTimestampRange(timestampRange);


        ExecutorService executorService = Executors.newFixedThreadPool(10);
        Future listFragmentResult = executorService.submit(new CanaryListFragmentWorker(streamName, credentialsProvider, dataEndpoint, Regions.fromName(region), fragmentSelector));
        System.out.println(listFragmentResult.get());






        // CanaryListFragmentWorker listFragmentWorker = new CanaryListFragmentWorker(streamName, credentialsProvider, dataEndpoint, Regions.fromName(region), fragmentSelector);
        // final GetDataEndpointRequest dataEndpointRequest = new GetDataEndpointRequest()
        //         .withAPIName(APIName.LIST_FRAGMENTS).withStreamName(streamName);
        // final String dataEndpoint = amazonKinesisVideo.getDataEndpoint(dataEndpointRequest).getDataEndpoint();

        // System.out.println(dataEndpoint);
        // System.out.println(region);

        // final AmazonKinesisVideoArchivedMedia amazonKinesisVideoArchivedMedia = AmazonKinesisVideoArchivedMediaClient
        // .builder()
        // .withCredentials(credentialsProvider)
        // .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(dataEndpoint, region))
        // .build();

        // System.out.println("Here 1");

        // ListFragmentsRequest request = new ListFragmentsRequest()
        //     .withStreamName(streamName);

        // System.out.println("Here 2");

        // ListFragmentsResult result = amazonKinesisVideoArchivedMedia.listFragments(request);
        
        // // System.out.println(result.getSdkResponseMetadata());

        // System.out.println("Here 3");



        Timer intervalMetricsTimer = new Timer("IntervalMetricsTimer");
        TimerTask intervalMetricsTask = new TimerTask() {
            public void run() {
                getIntervalMetrics();
            }
        };
        intervalMetricsTimer.scheduleAtFixedRate(intervalMetricsTask, 0, 10000); // delay of 0 ms at an interval of 10,000 ms



        getMediaWorker.run();
        timer.cancel(); 

        // Using System.exit(0) to exit from application. 
        // The application does not exit on its own. Need to inspect what the issue
        // is
        System.exit(0);
    }
}
