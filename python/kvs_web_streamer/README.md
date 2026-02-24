# WebRTC to KVS Streamer

A Python-based **WebRTC master** that receives video from a WebRTC viewer and uploads it to Amazon Kinesis Video Streams (KVS) for ingestion, storage, and playback.

This application extracts H.264 frames from RTP packets, packages them into MKV format, and streams them to KVS using the PutMedia API.

## Features

- **WebRTC Master**: Acts as signaling master, waiting for viewer connections
- **H.264 RTP Parsing**: Extracts and reassembles H.264 frames from RTP packets
- **MKV Packaging**: Packages H.264 frames into MKV format with proper timecodes
- **KVS Integration**: Streams packaged video to Kinesis Video Streams via PutMedia
- **Event System**: Built-in event bus for monitoring stream lifecycle
- **Configuration Presets**: Pre-configured settings for different use cases
- **Multi-stream Support**: Manage multiple concurrent streams

## Architecture

```
Browser (Viewer) --> WebRTC --> Master --> H.264 RTP Parser --> MKV Packager --> KVS PutMedia
```

## Prerequisites

- Python 3.13

## Installation

1. **Clone the repository**
   ```bash
   cd python/kvs_web_streamer
   ```

2. **Create virtual environment (once)**
   ```bash
   python3 -m venv venv
   ```

3. Activate virtual environment
   ```bash
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   ```

4. **Install dependencies (once)**
   ```bash
   pip install -r requirements.txt
   ```

## Configuration

### Environment Variables

Create a `.env` file in the project root:

```bash
# Required
KVS_CHANNEL_NAME=your-webrtc-channel-name
KVS_STREAM_NAME=your-kvs-stream-name
AWS_REGION=us-west-2
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
AWS_SESSION_TOKEN=...

# Optional - Video Settings
VIDEO_WIDTH=640
VIDEO_HEIGHT=480
VIDEO_FPS=30

# Optional - Streaming Settings
CLUSTER_DURATION_MS=2000
QUEUE_SIZE=100

# Optional - Logging
LOG_LEVEL=WARNING
```

See the `.env.example`. Create a copy, edit the fields, and rename the copy to `.env`.

## Usage

### Basic Usage

```bash
python sample_ingestion_client.py
```

This runs the simple example that:
1. Loads configuration from `.env`
2. Initializes WebRTC master
3. Waits for viewer connection
4. Streams received video to KVS

#### Setup Viewer

1. Open https://awslabs.github.io/amazon-kinesis-video-streams-webrtc-sdk-js/examples/index.html and fill in the details to the same signaling channel. The signaling channel must be in the same region, and configured for peer-to-peer mode (not WebRTC ingestion mode).
2. Enable "Send video"
3. Use "STUN/TURN" for NAT traversal
4. Enable Trickle ICE
5. Click "Start viewer"
6. Allow webcam access when prompted

#### View your stream

1. Open https://aws-samples.github.io/amazon-kinesis-video-streams-media-viewer
2. Configure to access the same stream configured in the Python
3. Choose either HLS or DASH
4. Use "LIVE" timestamp selector
5. Click "Start playback". It may take a few seconds for the media to appear, click the button again 

### Advanced Examples

The `sample_ingestion_client.py` contains 10 different usage examples:

1. **Simple Streaming** - Basic setup with defaults
2. **Builder Pattern** - Fluent API for configuration
3. **Configuration Presets** - Pre-defined settings (low latency, high quality, etc.)
4. **Multi-stream** - Manage multiple concurrent streams
5. **Event Monitoring** - Subscribe to stream lifecycle events
6. **Metrics Collection** - Track performance metrics
7. **Production Setup** - Production-ready configuration
8. **Dynamic Management** - Add/remove streams on the fly
9. **Environment Config** - Load everything from `.env`
10. **Config Persistence** - Save/load configurations to JSON

### Configuration Presets

```python
from common.config import ConfigPresets

# Low latency (1s clusters)
config = ConfigPresets.low_latency("channel", "stream")

# High quality (720p)
config = ConfigPresets.high_quality("channel", "stream")

# Mobile optimized (low bandwidth)
config = ConfigPresets.mobile_optimized("channel", "stream")

# Production ready
config = ConfigPresets.production("channel", "stream")
```

## Project Structure

```
kvs_web_streamer/
├── common/
│   ├── config.py                # Configuration management
│   └── events.py                # Event bus and metrics
├── streaming/
│   ├── kvs_streaming_client.py  # Main streaming client
│   ├── mkv_packager.py          # MKV packaging logic
│   ├── sigv4_signer.py          # AWS SigV4 signing
│   └── stream_manager.py        # Multi-stream management
├── webrtc/
│   ├── h264_rtp_parser.py       # H.264 RTP parsing
│   └── webrtc_kvs_master.py     # WebRTC master implementation
├── sample_ingestion_client.py   # Usage examples
├── requirements.txt
└── README.md
```

## How It Works

1. **WebRTC Signaling**: Master connects to KVS signaling channel and waits for viewer
2. **Peer Connection**: Establishes WebRTC connection with browser viewer
3. **Keyframe Management**: Sends PLI (Picture Loss Indication / FIR) requests to browser to generate keyframes more frequently, as browser defaults produce intervals too large for KVS fragment requirements
4. **RTP Reception**: Receives H.264 encoded video via RTP
5. **Frame Extraction**: Parses RTP packets to extract complete H.264 frames
6. **MKV Packaging**: Packages frames into MKV clusters with timecodes
7. **KVS Upload**: Streams MKV data to KVS via PutMedia API

## Event Monitoring

Subscribe to stream events:

```python
from common.events import EventBus, StreamEvent

bus = EventBus()

bus.subscribe(StreamEvent.CONNECTED, 
              lambda e: print(f"Connected: {e.stream_id}"))

bus.subscribe(StreamEvent.KEYFRAME_RECEIVED,
              lambda e: print(f"Keyframe: {e.data}"))

bus.subscribe(StreamEvent.ERROR,
              lambda e: print(f"Error: {e.data}"))
```

## Troubleshooting

### Connection Issues

- Verify AWS credentials are configured correctly
- Check KVS channel and stream names exist and in the same region
- Ensure proper IAM permissions
- Check the browser supports H.264, Constrained Baseline Level 3.1 Profile (42e01f)

### Video Quality Issues

- Adjust `CLUSTER_DURATION_MS`
- Modify video dimensions in `.env`

### Performance Issues

- Increase `QUEUE_SIZE` for high bitrate streams
- Use `ConfigPresets.mobile_optimized()` for bandwidth-constrained scenarios
- Monitor metrics with `StreamMetrics`

## Limitations

- **Video Only**: Current implementation supports video ingestion only
- **H.264 Only**: Only H.264 codec is supported
- **Browser Compatibility**: Requires WebRTC-compatible browser, not guaranteed to work across all browsers.
- **Demo Status**: This is a demo; additional testing required for production use

## Development

### Adding Custom Configuration

```python
from common.config import StreamConfig

config = StreamConfig(
    channel_name="my-channel",
    stream_name="my-stream",
    region="us-west-2",
    width=1280,
    height=720,
    cluster_duration_ms=1500
)

config.validate()
config.to_file("my_config.json")
```

## License

See [LICENSE.md](../../LICENSE) for details.

## Contributing

See [CONTRIBUTING.md](../../CONTRIBUTING.md) for details.

## Additional Resources

- [Amazon Kinesis Video Streams Documentation](https://docs.aws.amazon.com/kinesisvideostreams/)
- [Amazon Kinesis Video Streams WebRTC Documentation](https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/what-is-kvswebrtc.html)
- [KVS WebRTC JS SDK](https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-js)
- [aiortc Documentation](https://aiortc.readthedocs.io/)
