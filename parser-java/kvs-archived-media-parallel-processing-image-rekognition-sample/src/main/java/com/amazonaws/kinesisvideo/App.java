package com.amazonaws.kinesisvideo;

import java.io.IOException;
import java.sql.Time;
import java.sql.Timestamp;
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
    public static void main( String[] args ) throws InterruptedException, ExecutionException, java.text.ParseException, IOException {
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

        Option tasks = new Option("t", "tasks", true, "number of tasks");
        tasks.setRequired(true);
        options.addOption(tasks);

        Option threads = new Option("th", "threads", true , "number of threads");
        threads.setRequired(true);
        options.addOption(threads);

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
        int numTasks = Integer.parseInt(cmd.getOptionValue("tasks"));
        int numThreads = Integer.parseInt(cmd.getOptionValue("threads"));

        TimestampRange timestampRange = new TimestampRange();
        try {
            timestampRange.setStartTimestamp(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").parse(startTimestamp));
            timestampRange.setEndTimestamp(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").parse(endTimestamp));
        } catch (java.text.ParseException e) {
            log.error(e.getMessage());
            System.exit(1);
        }

        long start = System.nanoTime();

        try {
            KinesisVideoArchivedParallelProcessingExample example = KinesisVideoArchivedParallelProcessingExample.builder().region(Regions.US_WEST_2)
                    .streamName(streamName)
                    .awsCredentialsProvider(new ProfileCredentialsProvider())
                    .timestampRange(timestampRange)
                    .sampleRate(inputSampleRate)
                    .threads(numThreads)
                    .tasks(numTasks)
                    .build();

            example.execute();
        } catch (ExecutionException e) {
            log.error(e.getMessage());
            System.exit(1);
        }
        long end = System.nanoTime();
        long totalTime = end - start;
        double seconds = (double)totalTime / 1_000_000_000.0;
        log.info("Total runtime: " + seconds + " seconds");

    }
}
