package com.amazonaws.kinesisvideo;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutionException;

import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.model.TimestampRange;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.*;

@Slf4j
public class App 
{
    public static void main( String[] args ) throws InterruptedException, IOException, ExecutionException, java.text.ParseException
    {
        /* Option parsing */
        Options options = new Options();

        Option stream = new Option("s", "stream", true, "input stream");
        stream.setRequired(true);
        options.addOption(stream);

        Option startTime = new Option("st", "startTime", true, "start time");
        startTime.setRequired(true);
        options.addOption(startTime);

        Option endTime = new Option("et", "endTime", true, "end time");
        endTime.setRequired(true);
        options.addOption(endTime);

        Option sampleRate = new Option("sr", "sampleRate", true, "sample rate");
        sampleRate.setRequired(true);
        options.addOption(sampleRate);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            log.error(e.getMessage());
            formatter.printHelp("kvs-archived-media-retrieval-sample", options);
            System.exit(1);
        }

        String streamName = cmd.getOptionValue("stream");
        String startTimestamp = cmd.getOptionValue("startTime");
        String endTimestamp = cmd.getOptionValue("endTime");
        int inputSampleRate = Integer.parseInt(cmd.getOptionValue("sampleRate"));

        //Create the TimeStamp object with start and end times
        TimestampRange timestampRange = new TimestampRange();

        try {
            timestampRange.setStartTimestamp(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").parse(startTimestamp));
            timestampRange.setEndTimestamp(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").parse(endTimestamp));
        } catch (java.text.ParseException e) {
            log.error(e.getMessage());
            System.exit(1);
        }

        try {
            KinesisVideoArchivedDetectLabelsExample example = KinesisVideoArchivedDetectLabelsExample.builder().region(Regions.US_WEST_2)
                    .streamName(streamName)
                    .awsCredentialsProvider(new ProfileCredentialsProvider())
                    .timestampRange(timestampRange)
                    .sampleRate(inputSampleRate)
                    .build();

            example.execute();
        } catch (ExecutionException e) {
            log.error(e.getMessage());
            System.exit(1);
        }
    }

}
