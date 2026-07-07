package kvs.putmediaclient.handlers;

import com.amazonaws.services.kinesisvideo.PutMediaAckResponseHandler;
import com.amazonaws.services.kinesisvideo.model.AckEvent;

/**
 * Simple handler that logs acknowledgements and lifecycle events.
 */
public class LoggingAckResponseHandler implements PutMediaAckResponseHandler {

    @Override
    public void onAckEvent(AckEvent ack) {
         System.out.printf(
                "ACK Event: type=%-10s fragment=%-50s timecode=%-8s status=%s%n", ack.getAckEventType(), ack.getFragmentNumber(), ack.getFragmentTimecode(), ack.getErrorCode());
    }

    @Override
    public void onFailure(Throwable t) {
        System.err.println("PutMedia failed: " + t.getMessage());
        t.printStackTrace(System.err);
    }

    @Override
    public void onComplete() {
        System.out.println("PutMedia completed successfully.");
    }
}