package samples;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoPutMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoPutMediaClient;
import com.amazonaws.services.kinesisvideo.model.APIName;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointRequest;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointResult;
import com.amazonaws.services.kinesisvideo.model.FragmentTimecodeType;
import com.amazonaws.services.kinesisvideo.model.PutMediaRequest;
import kvs.putmediaclient.handlers.LoggingAckResponseHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.time.Instant;
import java.util.Date;

/**
 * Demo application for uploading MKV files to Amazon Kinesis Video Streams.
 * 
 * <p>This application reads an MKV file from disk and streams it to KVS using the PutMedia API.
 * It demonstrates the basic workflow of discovering the data endpoint and uploading media content.
 * 
 * <p>Required environment variables:
 * <ul>
 *   <li>STREAM_NAME - The name of the KVS stream</li>
 *   <li>MKV_FILE - Path to the MKV file to upload</li>
 *   <li>AWS_REGION - AWS region (optional, defaults to us-west-2)</li>
 * </ul>
 */
public class PutMkvFileDemo {

    /**
     * Main entry point for the MKV file upload demo.
     * 
     * @param args Command line arguments (not used)
     * @throws Exception If any error occurs during upload
     */
    public static void main(String[] args) throws Exception {
        final String streamName = env("STREAM_NAME");
        final String mkvPath    = env("MKV_FILE");
        final String regionStr  = envOr("AWS_REGION", "us-west-2");
        final Regions region    = Regions.fromName(regionStr);

        //  Control-plane client: discover endpoint
        AmazonKinesisVideo kv = AmazonKinesisVideoClientBuilder.standard()
                .withRegion(region)
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .withClientConfiguration(new ClientConfiguration()
                        .withConnectionTimeout(30_000)
                        .withSocketTimeout(120_000))
                .build();

        System.out.println("Discovering PUT_MEDIA endpoint for stream: " + streamName);
        GetDataEndpointResult gde = kv.getDataEndpoint(new GetDataEndpointRequest()
                .withAPIName(APIName.PUT_MEDIA)
                .withStreamName(streamName));
        String dataEndpoint = gde.getDataEndpoint();
        System.out.println("Data endpoint: " + dataEndpoint);

        // Data-plane client: PutMedia
        AmazonKinesisVideoPutMedia putMedia = AmazonKinesisVideoPutMediaClient.builder()
                .withRegion(region.getName())
                .withEndpoint(dataEndpoint)
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .withConnectionTimeoutInMillis(30_000)
//                .withSocketTimeoutInMillis(300_000)
                .build();

        // 3 Read MKV file
        File mkvFile = new File(mkvPath);
        if (!mkvFile.isFile()) {
            throw new IllegalArgumentException("MKV_FILE does not exist: " + mkvFile.getAbsolutePath());
        }

        BufferedInputStream payload = new BufferedInputStream(new FileInputStream(mkvFile));

        // 4️ Construct request
        PutMediaRequest req = new PutMediaRequest()
                .withStreamName(streamName)
                .withPayload(payload)
                .withFragmentTimecodeType(FragmentTimecodeType.RELATIVE)
                .withProducerStartTimestamp(Date.from(Instant.now()));

        // Send MKV to Kinesis Video Streams
        System.out.printf("Uploading file (%d bytes) to stream %s...%n", mkvFile.length(), streamName);

        putMedia.putMedia(req, new LoggingAckResponseHandler());

        System.out.println("Upload finished.");
        
        // Wait for ACK responses
        Thread.sleep(5000);
        
        // Shutdown client and exit
        kv.shutdown();
        System.exit(0);
    }

    /**
     * Gets a required environment variable.
     * 
     * @param key The environment variable name
     * @return The environment variable value
     * @throws IllegalArgumentException If the environment variable is missing or empty
     */
    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isEmpty())
            throw new IllegalArgumentException("Missing required environment variable: " + key);
        return v;
    }

    /**
     * Gets an environment variable with a default value.
     * 
     * @param key The environment variable name
     * @param def The default value to use if the variable is missing or empty
     * @return The environment variable value or the default value
     */
    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }
}


