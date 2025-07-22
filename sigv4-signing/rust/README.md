# KVS Signaling SigV4 WebSocket Signer

A Rust library and toolkit for signing WebSocket connections to Amazon Kinesis Video Streams (KVS) Signaling channels using AWS SigV4 authentication.

## Prerequisites

- Rust programming environment with Cargo package manager
- Kinesis Video Streams Signaling Channel

## Installation

Clone the repository:
```bash
git clone https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git
cd amazon-kinesis-video-streams-demos/sigv4-signing/rust
```

Build the package:
```shell
cargo build
```

## Example Applications

### Custom Signer Implementation (Examples 1 & 2)

These examples demonstrate the custom KVS signer implementation without AWS SDK dependencies.

- **Example 1**: Demonstrates basic URL signing and WebSocket connection establishment
- **Example 2**: Implements repeated connections using:
  - Dynamic credential renewal via STS
  - Signaling channel ARN and endpoint retrieval via AWS SDK for Rust role

To run these, edit the constants at the top of the file. Then run:
```shell
cargo run --package kvs_signaling_sigv4_wss_signer --example 1_simple_connect_to_socket_sample
```

### AWS SDK-Based Signer (Example 3)

This sample uses the AWS SDK for Rust's built-in signing infrastructure. It has a lot more dependencies than the custom signer implementation.

The application uses AWS SDK for Rust's default credential and region providers. For configuration details, see:
- [Credential Provider Documentation](https://docs.aws.amazon.com/sdk-for-rust/latest/dg/credproviders.html)
- [Region Provider Documentation](https://docs.aws.amazon.com/sdk-for-rust/latest/dg/region.html)

Run Example 3 by providing the **signaling channel name** and **role** as command-line arguments. There are no constants needed to modify in this sample.

```shell
cargo run --package kvs_signaling_sigv4_wss_signer --example 3_signer_using_aws_sdk <channel-name> <MASTER|VIEWER> 
```

Sample output:
```log
Channel name: demo-channel
The signed URL is: wss://m-1234abcd.kinesisvideo.us-west-2.amazonaws.com/?X-Amz-ChannelARN=ar...20744787048a32aa
```

### Running the unit tests

To run the unit tests, run the following command from the `rust` (this) folder:

```shell
cargo test
```
