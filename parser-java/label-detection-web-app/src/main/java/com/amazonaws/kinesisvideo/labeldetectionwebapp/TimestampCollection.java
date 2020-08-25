package com.amazonaws.kinesisvideo.labeldetectionwebapp;

import lombok.Data;
import org.hibernate.annotations.LazyCollection;
import org.hibernate.annotations.LazyCollectionOption;

import javax.persistence.*;
import java.util.*;

@Data
@Entity
public class TimestampCollection {

    @Id
    @GeneratedValue
    private Long id;

    @ElementCollection
    private List<String> timestamps = new ArrayList<>();

    @ManyToMany
    private Set<JpaFrame> frames = new HashSet<>();

    @ManyToMany
    @LazyCollection(LazyCollectionOption.FALSE)
    private Map<String, JpaFrame> timestampToFrame = new HashMap<>();

    public TimestampCollection() {
    }

    public List<String> getTimestamps() {
        return this.timestamps;
    }

    public void addTimestamp(String timestamp) {
        this.timestamps.add(timestamp);
    }

    public void addTimestampAndFrame(String timestamp, JpaFrame frame) {
        this.timestampToFrame.put(timestamp, frame);
    }

    public void addFrame(JpaFrame frame) {
        this.frames.add(frame);
    }
}
