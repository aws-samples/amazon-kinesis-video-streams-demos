package kvs.putmediaclient.handlers;

import com.amazonaws.services.kinesisvideo.PutMediaAckResponseHandler;
import com.amazonaws.services.kinesisvideo.model.AckEvent;
import kvs.mediapublisher.StreamProducer;

public class NetworkAwareAckHandler implements PutMediaAckResponseHandler {
    private final StreamProducer producer;
    private boolean connectionLost = false;

    public NetworkAwareAckHandler(StreamProducer producer) {
        this.producer = producer;
    }

    @Override
    public void onAckEvent(AckEvent ack) {
        // If we were disconnected and now receiving ACKs, connection is restored
        if (connectionLost) {
            connectionLost = false;
            producer.notifyConnectionRestored();
        }

        System.out.printf("ACK Event: type=%-10s fragment=%-50s timecode=%-8s status=%s%n",
                         ack.getAckEventType(), ack.getFragmentNumber(), ack.getFragmentTimecode(), ack.getErrorCode());

        // Handle error ACKs
        if (ack.getErrorCode() != null) {
            producer.notifyErrorAck(ack.getErrorCode().toString(), ack.getFragmentNumber(), ack.getFragmentTimecode());
            Exception error = new RuntimeException("KVS Error: " + ack.getErrorCode());
            producer.notifyStreamingError(error);
        }
    }

    @Override
    public void onFailure(Throwable t) {
        connectionLost = true;
        System.err.println("PutMedia failed: " + t.getMessage());
        producer.notifyConnectionLost(new Exception(t));
        t.printStackTrace(System.err);
    }

    @Override
    public void onComplete() {
        System.out.println("PutMedia completed successfully.");
        producer.notifyStreamingComplete();
    }
}