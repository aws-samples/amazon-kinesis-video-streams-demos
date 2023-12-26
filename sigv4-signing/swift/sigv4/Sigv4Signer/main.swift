//import Foundation
//import Starscream
//
//class WebSocketExample: WebSocketDelegate {
//    private var socket: WebSocket?
//
//    func main() {
//        // Initialize WebSocket with the desired URL
//        let socketURL = URL(string: "wss://socketsbay.com/wss/v2/1/demo/")!
//        var request = URLRequest(url: socketURL)
//        request.timeoutInterval = 5
//
//        // Create a WebSocket instance
//        socket = WebSocket(request: request)
//        socket?.delegate = self
//
//        // Connect to the WebSocket server
//        socket?.connect()
//    }
//
//    // MARK: - WebSocketDelegate
//
//    func didReceive(event: Starscream.WebSocketEvent, client: Starscream.WebSocketClient) {
//        switch event {
//        case .connected:
//            print("WebSocket connected successfully!")
//        case .disconnected(let reason, _):
//            print("WebSocket disconnected: \(reason)")
//        case .text(let string):
//            print("Received text: \(string)")
//        case .binary(let data):
//            print("Received binary data: \(data)")
//        case .pong:
//            print("Received Pong")
//        case .ping:
//            print("Received Ping")
//        case .error(let error):
//            print("WebSocket error: \(error?.localizedDescription ?? "Unknown error")")
//        default:
//            break
//        }
//    }
//}
//
//// Entry point of the program
//let example = WebSocketExample()
//example.main()
//
// Run the runloop to keep the program alive
//RunLoop.current.run()

// Example usage:
let credentials = AWSCredentials(accessKey: "your_access_key", secretKey: "your_secret_key", sessionToken: nil)
//let url = URL(string: "your_request_url")!
//var request = URLRequest(url: url)
//request.httpMethod = "GET"
//
//let signedRequest = KVSSigner.sign(request, credentials: credentials, serviceName: "your_service_name", region: "your_region")
//print(signedRequest)
