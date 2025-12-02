# WebSocket Signer Test

Sample showing how to use AWS SDK JS v3 SigV4 Signer to connect to KVS WebRTC signaling WebSocket.

## What the demo application does

1. Uses AWS SDK JS v3 to fetch the signaling channel details and endpoints
2. Creates a presigned WebSocket URL using AWS SDK v3 SignatureV4
3. Connects to the WebSocket endpoint
4. Sends a ping frame and waits for pong response
5. Logs connection status and messages, including any failures

## Setup

1. Change directories to this directory (containing `package.json`).

2. Install dependencies:
    ```shell
    npm install
    ```

3. Configure AWS credentials. See [AWS SDK JS v3 Documentation](https://docs.aws.amazon.com/sdk-for-javascript/v3/developer-guide/migrate-credential-providers.html) for other authentication methods.
    ```shell 
   export AWS_ACCESS_KEY_ID=YourAccessKey
   export AWS_SECRET_ACCESS_KEY=YourSecretKey
    ```

4. Configure region.
    ```shell
    export AWS_DEFAULT_REGION=us-west-2
    ```

5. Run the demo:
    ```shell
    npm start <channelName> [role=master] [clientId='randStr']
    ```
   

## Sample logs

### Successful case

```log
 npm start demo-channel master                

> kvs-signaling-websocket-signer-sample@1.0.0 start
> node index.js demo-channel master

Using region: us-west-2
Getting info for channel: demo-channel
Channel ARN: arn:aws:kinesisvideo:us-west-2:123412341234:channel/demo-channel/1234567890123
Endpoint: wss://m-1234abcd.kinesisvideo.us-west-2.amazonaws.com
Signed URL: wss://m-1234abcd.kinesisvideo.us-west-2.amazonaws.com/?X-Amz-ChannelARN=...76c4db2e

=== HTTP Response Headers ===
Status: 101 Switching Protocols
date: Thu, 20 Nov 2025 17:38:18 GMT
connection: upgrade
upgrade: websocket
sec-websocket-accept: abcdab...
sec-websocket-extensions: permessage-deflate
=============================

WebSocket connected successfully!
Sent ping frame: test-ping
Received pong: test-ping
WebSocket closed: 1005 
```

### Error cases

#### Maximum number of viewers connected to the signaling channel:

```log
npm start demo-channel viewer

> kvs-signaling-websocket-signer-sample@1.0.0 start
> node index.js demo-channel viewer

Using region: us-west-2
Getting info for channel: demo-channel
Channel ARN: arn:aws:kinesisvideo:us-west-2:123412341234:channel/demo-channel/1234567890123
Endpoint: wss://v-1234abcd.kinesisvideo.us-west-2.amazonaws.com
Client ID: test-client-1763660438339
Signed URL: wss://v-1234abcd.kinesisvideo.us-west-2.amazonaws.com/?X-Amz-C...511

=== HTTP Error Response ===
Status: 400 Bad Request
Headers:
  date: Thu, 20 Nov 2025 17:40:38 GMT
  content-type: application/json; charset=UTF-8
  content-length: 66
  connection: keep-alive
  x-amz-apigw-id: abcdabcd....
Response Body: {"Message":"Maximum number of clients connected to the channel !"}
==========================

WebSocket error: WebSocket was closed before the connection was established
WebSocket closed: 1006 
```

#### ClientID uses not allowed characters

```log
 npm start demo-channel viewer ..,; 

> kvs-signaling-websocket-signer-sample@1.0.0 start
> node index.js demo-channel viewer ..,

Using region: us-west-2
Getting info for channel: demo-channel
Channel ARN: arn:aws:kinesisvideo:us-west-2:123412341234:channel/demo-channel/1234567890123
Endpoint: wss://v-1234abcd.kinesisvideo.us-west-2.amazonaws.com
Client ID: ..,
Signed URL: wss://v-1234abcd.kinesisvideo.us-west-2.amazonaws.com/?X-Amz-Chann...60d81f

=== HTTP Error Response ===
Status: 400 Bad Request
Headers:
  date: Thu, 20 Nov 2025 17:44:10 GMT
  content-type: application/json; charset=UTF-8
  content-length: 42
  connection: keep-alive
  x-amz-apigw-id: abcdabcd1234=
Response Body: {"message":"Invalid ClientId specified !"}
==========================

WebSocket error: WebSocket was closed before the connection was established
WebSocket closed: 1006 
```
