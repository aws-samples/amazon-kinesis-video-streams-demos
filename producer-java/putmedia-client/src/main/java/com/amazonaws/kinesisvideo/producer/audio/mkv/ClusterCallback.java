package com.amazonaws.kinesisvideo.producer.audio.mkv;

/**
 * Callback interface for cluster creation events
 */
public interface ClusterCallback {
    /**
     * Called when a new cluster is created
     * 
     * @param timestamp the cluster timestamp in milliseconds
     * @param creationTime the system time when cluster was created
     */
    void onClusterCreated(long timestamp, long creationTime);
}