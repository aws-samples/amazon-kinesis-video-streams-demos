package com.amazonaws.kinesisvideo.labeldetectionwebapp;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArchivedVideoStreamsRepository extends JpaRepository <ArchivedVideoStream, Long> {
}
