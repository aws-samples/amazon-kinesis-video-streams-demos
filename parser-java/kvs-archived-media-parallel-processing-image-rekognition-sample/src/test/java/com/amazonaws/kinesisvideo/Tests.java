package com.amazonaws.kinesisvideo;

import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.model.TimestampRange;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;


import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;

public class Tests {
    @Ignore
    @Test
    public void testPartitioningExample() throws ParseException {
        TimestampRange timestampRange = new TimestampRange();
        timestampRange.setStartTimestamp(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").parse("00/01/2020 00:00:00"));
        timestampRange.setEndTimestamp(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").parse("00/01/2020 01:00:00"));

        KinesisVideoArchivedParallelProcessingExample example = KinesisVideoArchivedParallelProcessingExample.builder()
                .region(Regions.US_WEST_2)
                .streamName("myTestStream")
                .awsCredentialsProvider(new ProfileCredentialsProvider())
                .timestampRange(timestampRange)
                .sampleRate(0)
                .tasks(6)
                .threads(10)
                .build();

        Assert.assertEquals(6, example.partitionTimeRange(timestampRange).size());
    }

    @Ignore
    @Test
    public void testPartionLengthExample() throws ParseException {
        TimestampRange timestampRange = new TimestampRange();
        timestampRange.setStartTimestamp(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").parse("00/01/2020 00:00:00"));
        timestampRange.setEndTimestamp(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").parse("00/01/2020 01:00:00"));

        KinesisVideoArchivedParallelProcessingExample example = KinesisVideoArchivedParallelProcessingExample.builder()
                .region(Regions.US_WEST_2)
                .streamName("myTestStream")
                .awsCredentialsProvider(new ProfileCredentialsProvider())
                .timestampRange(timestampRange)
                .sampleRate(0)
                .tasks(6)
                .threads(10)
                .build();

        List<TimestampRange> timestampRanges = example.partitionTimeRange(timestampRange);
        for (int i = 0; i < timestampRanges.size() - 1; i++) {
            long difference = timestampRanges.get(i).getEndTimestamp().getTime() - timestampRanges.get(i).getStartTimestamp().getTime();
            Assert.assertEquals(600000, difference);
        }

        /* Last time slice will be truncated slightly to the end time of the initial timestamp range */
        long lastDiff = timestampRanges.get(timestampRanges.size() - 1).getEndTimestamp().getTime() - timestampRanges.get(timestampRanges.size() - 1).getStartTimestamp().getTime();
        Assert.assertEquals(600000 - timestampRanges.size() + 1, lastDiff);
    }
}
