"""
Usage Examples – How to Use the KVS Streaming SDK Abstractions

This is a demo showing how to ingest media from the browser using KVS WebRTC and send it to KVS for storage and playback.

Note:
    This is only a demo sample showing how to ingest video, but it can be extended for audio.
    It highlights a cost-effective approach;
    however, it requires browser compatibility verification and additional testing before being used in production.
"""
import asyncio
import os
from dotenv import load_dotenv
from streaming.kvs_streaming_client import KVSStreamingClient, KVSStreamingBuilder, StreamQuality
from streaming.stream_manager import StreamManager
from common.config import StreamConfig, ConfigPresets
from common.events import EventBus, StreamEvent, StreamMetrics
from webrtc.webrtc_kvs_master import WebRTCKVSMaster

load_dotenv()


# Example 1: Simple streaming with defaults
async def example_simple():
    """Simplest way to start streaming"""
    # Setup event bus with logging
    bus = EventBus()
    bus.subscribe(StreamEvent.CONNECTED, lambda e: print(f"✓ CONNECTED: {e.stream_id} at {e.timestamp}"))
    bus.subscribe(StreamEvent.KEYFRAME_RECEIVED, lambda e: print(f"✓ KEYFRAME: {e.stream_id} - {e.data}"))
    bus.subscribe(StreamEvent.ERROR, lambda e: print(f"✗ ERROR: {e.stream_id} - {e.data}"))
    
    master = WebRTCKVSMaster(
        channel_name=os.getenv('KVS_CHANNEL_NAME'),
        stream_name=os.getenv('KVS_STREAM_NAME'),
        region=os.getenv('AWS_REGION'),
        event_bus=bus
    )

    print(f"Channel name: {os.getenv('KVS_CHANNEL_NAME')}")
    print(f"Stream name: {os.getenv('KVS_STREAM_NAME')}")
    
    await master.initialize()
    await master.start()


# Example 2: Using builder pattern
async def example_builder():
    """Builder pattern for more control"""
    client = (KVSStreamingBuilder()
        .with_channel("my-channel")
        .with_stream("my-stream")
        .with_region("us-west-2")
        .with_quality(StreamQuality.HIGH)
        .with_auto_keyframe()
        .on_connected(lambda: print("Connected!"))
        .on_error(lambda e: print(f"Error: {e}"))
        .build())
    
    await client.start()


# Example 3: Using configuration presets
async def example_config():
    """Using predefined configuration presets"""
    # Low latency preset
    config = ConfigPresets.low_latency("my-channel", "my-stream")
    config.validate()
    
    # Or load from environment
    config = StreamConfig.from_env()
    
    # Or load from file
    config = StreamConfig.from_file("stream_config.json")
    
    client = KVSStreamingClient(
        channel_name=config.channel_name,
        stream_name=config.stream_name,
        region=config.region
    )
    await client.start()


# Example 4: Multiple concurrent streams
async def example_multi_stream():
    """Manage multiple streams concurrently"""
    manager = StreamManager()
    
    # Add streams
    manager.add_stream("camera1", "channel1", "stream1", StreamQuality.HIGH)
    manager.add_stream("camera2", "channel2", "stream2", StreamQuality.MEDIUM)
    manager.add_stream("camera3", "channel3", "stream3", StreamQuality.LOW)
    
    # Start all streams
    await manager.start_all()
    
    # Or start individually
    # await manager.start_stream("camera1")
    
    # Check status
    print(manager.get_status())
    
    # Stop specific stream
    # await manager.stop_stream("camera2")


# Example 5: Event monitoring
async def example_events():
    """Monitor stream events"""
    bus = EventBus()
    
    # Subscribe to events
    bus.subscribe(StreamEvent.CONNECTED, 
                  lambda e: print(f"Stream {e.stream_id} connected at {e.timestamp}"))
    
    bus.subscribe(StreamEvent.ERROR,
                  lambda e: print(f"Error in {e.stream_id}: {e.data}"))
    
    bus.subscribe(StreamEvent.STATS_UPDATE,
                  lambda e: print(f"Stats: {e.data}"))
    
    # Publish events (would be integrated into client)
    bus.publish(StreamEvent.CONNECTED, "stream1", {"client_id": "abc123"})


# Example 6: Metrics collection
def example_metrics():
    """Collect and display metrics"""
    metrics = StreamMetrics("stream1")
    
    # Record events
    metrics.record_packet(1500)
    metrics.record_keyframe()
    metrics.record_cluster()
    
    # Get summary
    summary = metrics.get_summary()
    print(f"Packets/sec: {summary['packets_per_second']:.2f}")
    print(f"Keyframes: {summary['keyframes_received']}")
    print(f"Errors: {summary['errors']}")


# Example 7: Production-ready setup
async def example_production():
    """Production-ready streaming setup"""
    # Load config
    config = ConfigPresets.production("prod-channel", "prod-stream")
    config.validate()
    
    # Setup event bus
    bus = EventBus()
    
    # Setup metrics
    metrics = StreamMetrics("prod-stream")
    
    # Error handler
    def handle_error(error: Exception):
        print(f"ERROR: {error}")
        metrics.record_error()
        bus.publish(StreamEvent.ERROR, "prod-stream", {"error": str(error)})
    
    # Stats handler
    def handle_stats(stats: dict):
        metrics.update_rtp_stats(stats)
        bus.publish(StreamEvent.STATS_UPDATE, "prod-stream", stats)
    
    # Create client
    client = (KVSStreamingBuilder()
        .with_channel(config.channel_name)
        .with_stream(config.stream_name)
        .with_region(config.region)
        .with_quality(StreamQuality.MEDIUM)
        .on_error(handle_error)
        .on_stats(handle_stats)
        .build())
    
    # Start streaming
    try:
        await client.start()
    except KeyboardInterrupt:
        await client.stop()
        print(metrics.get_summary())


# Example 8: Dynamic stream management
async def example_dynamic():
    """Add/remove streams dynamically"""
    manager = StreamManager()
    
    # Start with one stream
    manager.add_stream("main", "channel1", "stream1")
    await manager.start_stream("main")
    
    # Add more streams on demand
    await asyncio.sleep(10)
    manager.add_stream("backup", "channel2", "stream2")
    await manager.start_stream("backup")
    
    # Remove stream when done
    await asyncio.sleep(10)
    await manager.stop_stream("backup")
    
    # List active streams
    print(f"Active streams: {manager.list_streams()}")


# Example 9: Configuration from environment
async def example_env_config():
    """
    Load everything from environment variables
    
    Set these in .env:
        KVS_CHANNEL_NAME=my-channel
        KVS_STREAM_NAME=my-stream
        AWS_REGION=us-east-1
        VIDEO_WIDTH=1280
        VIDEO_HEIGHT=720
        CLUSTER_DURATION_MS=2000
        AUTO_INSERT_KEYFRAME=true
        LOG_LEVEL=WARNING
    """
    config = StreamConfig.from_env()
    config.validate()
    
    client = KVSStreamingClient(
        channel_name=config.channel_name,
        stream_name=config.stream_name,
        region=config.region,
        auto_keyframe=config.auto_insert_keyframe
    )
    
    await client.start()


# Example 10: Save/load configuration
def example_config_persistence():
    """Save and load configuration"""
    # Create config
    config = ConfigPresets.high_quality("my-channel", "my-stream")
    
    # Save to file
    config.to_file("my_stream_config.json")
    
    # Load from file
    loaded_config = StreamConfig.from_file("my_stream_config.json")
    loaded_config.validate()
    
    print(f"Loaded config: {loaded_config.to_dict()}")


if __name__ == "__main__":
    # Run simple example
    asyncio.run(example_simple())
    
    # Or run production example
    # asyncio.run(example_production())
