package com.amazonaws.kinesisvideo.labeldetectionwebapp;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.persistence.*;
import java.util.*;

@Slf4j
@Data
@Entity
public class JpaFrame {

    @Id
    @GeneratedValue
    private Long id;

    private long frameNumber;

    @Lob
    private byte[] imageBytes;

    private String playbackTimestamp;

    @ElementCollection
    private List<String> labels = new ArrayList<>();

    public JpaFrame() {
    }

    public JpaFrame(byte[] imageBytes) {
        this.imageBytes = imageBytes;
    }

    public Long getFrameNumber() {
        return this.frameNumber;
    }

    public byte[] getImageBytes() {
        return this.imageBytes;
    }

    public void setPlaybackTimestampAndFrameNum(String playbackTimestamp, long frameNumber) {
        this.playbackTimestamp = playbackTimestamp;
        this.frameNumber = frameNumber;
    }

    public String getPlaybackTimestamp() {
        return this.playbackTimestamp;
    }

    public List<String> getLabels() {
        return this.labels;
    }

    public void addLabel(String label) {
        this.labels.add(label);
    }
}
