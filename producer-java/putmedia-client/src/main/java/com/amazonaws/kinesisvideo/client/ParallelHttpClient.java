package com.amazonaws.kinesisvideo.client;

import com.amazonaws.kinesisvideo.java.auth.AwsSigV4Signer;

import java.io.*;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;

/**
 * HTTP client that handles concurrent operations for Kinesis Video Streams PutMedia API.
 * Supports SSL connections and AWS Signature Version 4 authentication.
 */
public class ParallelHttpClient implements Closeable {
    private final ExecutorService exec;
    private final AwsSigV4Signer signer;
    private final boolean ownsExecutor;
    
    /**
     * Creates a new ParallelHttpClient with AWS credentials.
     * 
     * @param accessKey the AWS access key
     * @param secretKey the AWS secret key
     * @param sessionToken the AWS session token (can be null)
     * @param region the AWS region
     */
    public ParallelHttpClient(String accessKey, String secretKey, String sessionToken, String region) {
        this.exec = Executors.newFixedThreadPool(3);
        this.signer = new AwsSigV4Signer(accessKey, secretKey, sessionToken, region);
        this.ownsExecutor = true;
    }
    
    /**
     * Creates a new ParallelHttpClient with AWS credentials and custom ExecutorService.
     * 
     * @param accessKey the AWS access key
     * @param secretKey the AWS secret key
     * @param sessionToken the AWS session token (can be null)
     * @param region the AWS region
     * @param executorService the ExecutorService to use for concurrent operations
     */
    public ParallelHttpClient(String accessKey, String secretKey, String sessionToken, String region, ExecutorService executorService) {
        this.exec = executorService;
        this.signer = new AwsSigV4Signer(accessKey, secretKey, sessionToken, region);
        this.ownsExecutor = false;
    }

    /**
     * Starts communication with the PutMedia endpoint in background threads.
     * 
     * @param uri the endpoint URI
     * @param headers the HTTP headers to send
     * @param sender the sender for writing data
     * @param receiver the receiver for reading responses
     * @return a Future representing the communication task
     */
    public Future<?> startCommunication(URI uri, Map<String, String> headers, PutMediaClient.Sender sender, PutMediaClient.Receiver receiver) {
        return exec.submit(() -> {
            System.out.println("[HTTP] Creating socket connection to: " + uri);
            try (SocketLike sock = SocketFactory.createSocket(uri)) {
                System.out.println("[HTTP] Socket created successfully");
                OutputStream out = sock.getOutputStream();
                InputStream in = sock.getInputStream();
                System.out.println("[HTTP] Sending initial request with SigV4 headers");
                sendInitRequest(out, uri, headers);
                System.out.println("[HTTP] Initial request sent, starting sender and receiver threads");

                Future<?> sendF = exec.submit(() -> { 
                    try { 
                        System.out.println("[HTTP] Sender thread started");
                        sender.write(out); 
                    } catch (Throwable t) { 
                        System.err.println("[HTTP] Sender error: " + t.getMessage());
                        throw new RuntimeException(t); 
                    } 
                });
                System.out.println("[HTTP] About to submit receiver thread");
                Future<?> recvF = exec.submit(() -> { 
                    try { 
                        System.out.println("[HTTP] Receiver thread started");
                        receiver.read(in); 
                    } catch (Throwable t) { 
                        System.err.println("[HTTP] Receiver error: " + t.getMessage());
                        t.printStackTrace();
                        throw new RuntimeException(t); 
                    } 
                });

                try {
                    while (!sendF.isDone() && !recvF.isDone()) {
                        Thread.sleep(100);
                    }
                } finally {
                    sendF.cancel(true);
                    recvF.cancel(true);
                }
            } catch (Exception e) {
                System.err.println("[HTTP] Connection error: " + e.getMessage());
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    /**
     * Sends the initial HTTP request with AWS Signature V4 authentication.
     * 
     * @param out the output stream to write to
     * @param uri the target URI
     * @param headers the headers to include
     * @throws IOException if an I/O error occurs
     */
    private void sendInitRequest(OutputStream out, URI uri, Map<String, String> headers) throws IOException {
        String host = uri.getHost();
        String path = (uri.getPath() == null || uri.getPath().isEmpty()) ? "/putMedia" : uri.getPath();
        
        Map<String, String> signedHeaders = signer.signRequest(uri, headers, null);

        StringBuilder sb = new StringBuilder();
        sb.append("POST ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        
        for (Map.Entry<String, String> e : signedHeaders.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        
        sb.append("Transfer-Encoding: chunked\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes());
        out.flush();
    }

    /**
     * Closes the HTTP client and shuts down executor threads if owned by this instance.
     */
    @Override 
    public void close() { 
        if (ownsExecutor) {
            exec.shutdownNow(); 
        }
    }

    /**
     * Interface for socket-like objects that provide input/output streams.
     */
    public interface SocketLike extends Closeable {
        /**
         * Gets the output stream for writing data.
         * @return the output stream
         * @throws IOException if an I/O error occurs
         */
        OutputStream getOutputStream() throws IOException;
        
        /**
         * Gets the input stream for reading data.
         * @return the input stream
         * @throws IOException if an I/O error occurs
         */
        InputStream getInputStream() throws IOException;
    }

    /**
     * Factory for creating socket connections.
     */
    public static class SocketFactory {
        /**
         * Creates a socket connection to the specified URI.
         * 
         * @param uri the target URI
         * @return a SocketLike instance
         * @throws IOException if connection fails
         */
        public static SocketLike createSocket(URI uri) throws IOException {
            return new RealHttpSocketLike(uri);
        }
    }

    /**
     * SSL socket implementation for real HTTP connections.
     */
    public static class RealHttpSocketLike implements SocketLike {
        private final java.net.Socket socket;
        private final OutputStream outputStream;
        private final InputStream inputStream;

        /**
         * Creates an SSL socket connection to the specified URI.
         * 
         * @param uri the target URI
         * @throws IOException if connection fails
         */
        public RealHttpSocketLike(URI uri) throws IOException {
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 443;
            System.out.println("[SOCKET] Connecting to " + host + ":" + port + " (SSL)");
            
            javax.net.ssl.SSLSocketFactory factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            this.socket = factory.createSocket(host, port);
            this.outputStream = socket.getOutputStream();
            this.inputStream = socket.getInputStream();
            System.out.println("[SOCKET] SSL connection established successfully");
        }

        /** @return the output stream for writing data */
        @Override 
        public OutputStream getOutputStream() { 
            return outputStream; 
        }
        
        /** @return the input stream for reading data */
        @Override 
        public InputStream getInputStream() { 
            return inputStream; 
        }
        
        /** Closes the SSL socket connection */
        @Override 
        public void close() throws IOException { 
            System.out.println("[SOCKET] Closing SSL connection");
            socket.close(); 
        }
    }

    /**
     * Mock socket implementation using piped streams for testing.
     */
    public static class PlainSocketLike implements SocketLike {
        private final PipedOutputStream clientOut = new PipedOutputStream();
        private final PipedInputStream serverIn = new PipedInputStream();
        private final PipedOutputStream serverOut = new PipedOutputStream();
        private final PipedInputStream clientIn = new PipedInputStream();

        /**
         * Creates a mock socket with piped streams and simulated ACK responses.
         * 
         * @param uri the target URI (unused in mock)
         * @throws IOException if pipe setup fails
         */
        public PlainSocketLike(URI uri) throws IOException {
            serverIn.connect(clientOut);
            clientIn.connect(serverOut);

            new Thread(() -> {
                try (DataOutputStream dos = new DataOutputStream(serverOut)) {
                    int counter = 0;
                    while (true) {
                        Thread.sleep(1000);
                        String ack = "{\"Event\":\"PUT_MEDIA_ACK\",\"AckIndex\":" + counter++ + "}";
                        dos.write((ack + "\n").getBytes());
                        dos.flush();
                    }
                } catch (Exception ignore) {}
            }, "ServerAck").start();
        }

        /** @return the output stream for writing data */
        @Override 
        public OutputStream getOutputStream() { 
            return clientOut; 
        }
        
        /** @return the input stream for reading data */
        @Override 
        public InputStream getInputStream() { 
            return clientIn; 
        }
        
        /** Closes all piped streams */
        @Override 
        public void close() throws IOException { 
            clientOut.close(); 
            clientIn.close(); 
            serverIn.close(); 
            serverOut.close(); 
        }
    }
}
