package kvs.putmediaclient.callbacks;

public interface StreamingCallback {
    void onConnectionLost(Exception cause);
    void onConnectionRestored();
    void onStreamingError(Exception error);
    void onStreamingComplete();
    void onErrorAck(String errorCode, String fragmentNumber, Long timecode);
}