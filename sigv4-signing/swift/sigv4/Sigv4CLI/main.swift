import Foundation
import Starscream
import Sigv4

func main() {
    // Step 1. Obtain AWS Credentials. Some examples of how to obtain these
    // are AWS Cognito or AWS STS. If using permanent credentials (not
    // recommended for security reasons), session token can be nil or an empty string.
    let accessKeyId: String = "YourAccessKey"
    let secretKeyId: String = "YourSecretKey"
    let sessionToken: String? = "YourSessionToken"
    
    // Step 2. Specify region. For example, "us-west-2".
    let region: String = "YourRegion"
    
    // Step 3. Build the websocket URL to sign. The two operations supported by this signer are:
    // 1. ConnectAsMaster https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-2.html
    //    format: wss://{GetSignalingChannelEndpoint API Response (Master Role, WSS)}?X-Amz-ChannelARN={ChannelARN}
    //    example: wss://m-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123
    // 2. ConnectAsViewer https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-1.html
    //    format: {GetSignalingChannelEndpoint API Response (Viewer Role, WSS)}?X-Amz-ChannelARN={ChannelARN}&XX-Amz-ClientId={ClientId}
    //    example: wss://v-a1b2c3d4.kinesisvideo.us-west-2.amazonaws.com?X-Amz-ChannelARN=arn:aws:kinesisvideo:us-west-2:123456789012:channel/demo-channel/1234567890123&X-Amz-ClientId=d7d1c6e2-9cb0-4d61-bea9-ecb3d3816557
    //
    // To obtain the URL host, we need to make the Kinesis Video Streams GetSignalingChannelEndpoint API call.
    // Using the AWS CLI:
    //
    // aws kinesisvideo get-signaling-channel-endpoint --channel-arn {ChannelARN} --single-master-channel-endpoint-configuration='{"Protocols":["WSS"],"Role":"MASTER"}'
    //
    // To obtain the ChannelARN given the Signaling Channel Name, you can make the Kinesis Video Streams DescribeSignalingChannel API call.
    // Using the AWS CLI:
    //
    // aws kinesisvideo describe-signaling-channel --channel-name {ChannelName}
    
    let urlToSign: URL = URL(string: "wss://...")!
    
    // Check if the user is using default values
    if accessKeyId == "YourAccessKey" || secretKeyId == "YourSecretKey" || sessionToken == "YourSessionToken" || region == "YourRegion" || urlToSign.absoluteString == "wss://..." {
        print("Please update the default values with your own AWS credentials, region, and construct the websocket URL to sign before running the program.")
        return
    }
    
    // Step 4. Sign the URL.
    var request: URLRequest
    do {
        request = try Signer.sign(request: URLRequest(url: urlToSign),
                        credentials: AWSCredentials(accessKey: accessKeyId, secretKey: secretKeyId, sessionToken: sessionToken),
                        region: region,
                        currentDate: Date())
    } catch {
        print("Error signing request! \(error)")
        return
    }
    
    // Step 5. Connect to the WebSocket URL using a WebSocket client.
    // The Kinesis Video Streams WebRTC SDK in iOS offers a WebSocket client implementation that handles
    // exchanging Signaling messages between WebRTC peers. In this minimal example, we'll use a
    // SimpleWebSocketClient (implemented below) that connects to the WebSocket, sends a ping, and disconnects.
    
    // 5a. Configure the URLRequest
    request.timeoutInterval = 5
    request.setValue("aws-kvs-ios-sigv4-signer-sample/1.0.0", forHTTPHeaderField: "User-Agent")
    
    // 5b. Initialize WebSocket Client
    let client: SimpleWebSocketClient = SimpleWebSocketClient(request)
    
    // 5c. Connect!
    print("Connecting to \(request.url!.absoluteString)")
    client.connect()
    
    print("Waiting 5 seconds...")
    waitNSeconds(5)
    
    print("Sending a ping...")
    client.sendPing()
    waitNSeconds(3)
    
    print("Disconnecting...")
    client.disconnect()
    print("Finished!")
}

/// Waits for a specified duration before stopping the current RunLoop.
///
/// - Parameters:
///   - n: The duration, in seconds, to wait before stopping the RunLoop.
///
/// - Important:
///   This method uses a combination of RunLoop and DispatchQueue to wait for the specified duration
///   and then stop the RunLoop. It's recommended to avoid busy-waiting loops and use this method
///   judiciously to prevent unnecessary CPU resource consumption.
///
/// - Note:
///   The RunLoop is stopped by scheduling a task on a DispatchQueue after the specified duration.
///   This allows the RunLoop to process events until the specified time is reached.
///
/// - Parameter n: The duration, in seconds, to wait before stopping the RunLoop.
private func waitNSeconds(_ n: TimeInterval) {
    // Calculate the end time
    let runLoopEndTime = Date().addingTimeInterval(n)
    
    // Run the RunLoop until the specified end time
    RunLoop.current.run(until: runLoopEndTime)
    
    // Schedule a task on a DispatchQueue to stop the RunLoop after n seconds
    DispatchQueue.global().asyncAfter(deadline: .now() + n) {
        CFRunLoopStop(CFRunLoopGetCurrent())
    }
}


/// A simple WebSocket client using the Starscream library.
class SimpleWebSocketClient: WebSocketDelegate {
    /// The underlying WebSocket instance.
    private var socket: WebSocket?
    
    /// Initializes a new WebSocket client with the specified URLRequest.
    ///
    /// - Parameter request: The URLRequest for the WebSocket connection.
    init(_ request: URLRequest) {
        socket = WebSocket(request: request)
        socket?.delegate = self
    }
    
    /// Connects to the WebSocket server.
    func connect() {
        socket!.connect()
    }
    
    /// Send a Ping message.
    func sendPing() {
        socket!.write(ping: Data())
    }
    
    /// Disconnects from the WebSocket server.
    func disconnect() {
        socket!.disconnect()
    }
    
    // MARK: - WebSocketDelegate
    
    /// Called when the WebSocket client receives an event.
    ///
    /// - Parameters:
    ///   - event: The WebSocket event.
    ///   - client: The WebSocket client.
    func didReceive(event: Starscream.WebSocketEvent, client: Starscream.WebSocketClient) {
        switch event {
        case .connected:
            print("WebSocket connected successfully!")
        case .disconnected(let reason, _):
            print("WebSocket disconnected: \(reason)")
        case .text(let string):
            print("Received text: \(string)")
        case .binary(let data):
            print("Received binary data: \(data)")
        case .pong:
            print("Received Pong")
        case .ping:
            print("Received Ping")
        case .error(let error):
            print("WebSocket error: \(error?.localizedDescription ?? "Unknown error")")
        default:
            break
        }
    }
}

main()
