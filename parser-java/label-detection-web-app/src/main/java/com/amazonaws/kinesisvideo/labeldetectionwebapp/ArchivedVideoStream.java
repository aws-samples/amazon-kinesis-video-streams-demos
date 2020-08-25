package com.amazonaws.kinesisvideo.labeldetectionwebapp;

import lombok.Data;
import org.hibernate.annotations.LazyCollection;
import org.hibernate.annotations.LazyCollectionOption;

import javax.persistence.*;
import java.util.*;

@Data
@Entity
public class ArchivedVideoStream {

    private @Id
    @GeneratedValue
    Long id;

    @OneToMany(cascade = CascadeType.ALL)
    private List<JpaFrame> frames = new ArrayList<>();


    @OneToMany(cascade = CascadeType.ALL)
    @LazyCollection(LazyCollectionOption.FALSE)
    private Map<String, TimestampCollection> labelToTimestamps = new HashMap<>();

    private String name;

    private String startTimestamp;
    private String endTimestamp;
    private int sampleRate;
    private int threads;

    protected ArchivedVideoStream() {
    }

    public ArchivedVideoStream(String name, String startTimestamp, String endTimestamp, int sampleRate) {
        this.name = name;
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
        this.sampleRate = sampleRate;
    }

    public void addFrame(JpaFrame jpaFrame) {
        this.frames.add(jpaFrame);
    }

    public void addLabelAndTimestampCollection(String label, TimestampCollection timestampCollection) {
        this.labelToTimestamps.put(label, timestampCollection);
    }

    public void sortFrames() {
        Collections.sort(this.frames, (f1, f2) -> f1.getFrameNumber().compareTo(f2.getFrameNumber()));
    }
}
