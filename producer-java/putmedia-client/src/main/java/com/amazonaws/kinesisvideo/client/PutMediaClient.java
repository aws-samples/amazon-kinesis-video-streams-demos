package com.amazonaws.kinesisvideo.client;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Future;

public class PutMediaClient implements Closeable {
    /** Interface for sending data to the PutMedia stream */
    public interface Sender { 
        /**
         * Writes data to the output stream.
         * @param socketOutputStream the output stream to write to
         * @throws Exception if an error occurs during writing
         */
        void write(OutputStream socketOutputStream) throws Exception; 
    }
    
    /** Interface for receiving responses from the PutMedia stream */
    public interface Receiver { 
        /**
         * Reads responses from the input stream.
         * @param socketInputStream the input stream to read from
         * @throws Exception if an error occurs during reading
         */
        void read(InputStream socketInputStream) throws Exception; 
    }

    private final ParallelHttpClient httpClient;
    private Future<?> ioFuture;

    /**
     * Creates a new PutMediaClient.
     * 
     * @param httpClient the HTTP client to use for connections
     */
    public PutMediaClient(ParallelHttpClient httpClient) { this.httpClient = httpClient; }

    /**
     * Connects to PutMedia endpoint and processes data in background threads.
     * 
     * @param putMediaUri the PutMedia endpoint URI
     * @param headers the HTTP headers to send
     * @param sender the sender for writing data
     * @param receiver the receiver for reading responses
     */
    public void connectAndProcessInBackground(URI putMediaUri, Map<String, String> headers, Sender sender, Receiver receiver) {
        ioFuture = httpClient.startCommunication(putMediaUri, headers, sender, receiver);
    }

    /**
     * Closes the client and cancels any ongoing operations.
     */
    @Override public void close() {
        if (ioFuture != null) ioFuture.cancel(true);
        httpClient.close();
    }
}
