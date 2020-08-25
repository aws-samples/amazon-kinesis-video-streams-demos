package com.amazonaws.kinesisvideo.labeldetectionwebapp.exceptions;

public class ArchivedVideoStreamNotFoundException extends RuntimeException {

    public ArchivedVideoStreamNotFoundException(Long id) {
        super("Could not find archivd stream id: " + id);
    }
}