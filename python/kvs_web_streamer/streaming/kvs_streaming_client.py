"""
High-level KVS Streaming Client - Simple API for WebRTC to KVS streaming
"""
import asyncio
import os
from typing import Optional, Callable, Dict, Any
from enum import Enum
from webrtc.webrtc_kvs_master import WebRTCKVSMaster


class StreamQuality(Enum):
    """Predefined streaming quality presets"""
    LOW = {"width": 320, "height": 240, "cluster_ms": 3000}
    MEDIUM = {"width": 640, "height": 480, "cluster_ms": 2000}
    HIGH = {"width": 1280, "height": 720, "cluster_ms": 2000}
    ULTRA = {"width": 1920, "height": 1080, "cluster_ms": 1000}


class KVSStreamingClient:
    """
    Simplified KVS streaming client with  defaults
    
    Usage:
        client = KVSStreamingClient(
            channel_name="my-channel",
            stream_name="my-stream"
        )
        await client.start()
    """
    
    def __init__(
        self,
        channel_name: str,
        stream_name: str,
        region: Optional[str] = None,
        quality: StreamQuality = StreamQuality.MEDIUM,
        auto_keyframe: bool = False,
        on_connected: Optional[Callable] = None,
        on_error: Optional[Callable[[Exception], None]] = None,
        on_stats: Optional[Callable[[Dict[str, Any]], None]] = None
    ):
        """
        Initialize streaming client
        
        Args:
            channel_name: KVS signaling channel name
            stream_name: KVS stream name
            region: AWS region (defaults to AWS_REGION env var or us-east-1)
            quality: StreamQuality preset
            auto_keyframe: Auto-insert keyframes if browser doesn't send them
            on_connected: Callback when client connects
            on_error: Callback for errors
            on_stats: Callback for periodic statistics
        """
        self.channel_name = channel_name
        self.stream_name = stream_name
        self.region = region or os.getenv("AWS_REGION", "us-east-1")
        self.quality = quality
        self.auto_keyframe = auto_keyframe
        self.on_connected = on_connected
        self.on_error = on_error
        self.on_stats = on_stats
        
        self._master = None
        self._running = False
    
    async def start(self):
        """Start streaming (blocking)"""
        try:
            # Set environment for auto-keyframe
            os.environ['AUTO_INSERT_KEYFRAME'] = str(self.auto_keyframe).lower()
            
            self._master = WebRTCKVSMaster(
                channel_name=self.channel_name,
                stream_name=self.stream_name,
                region=self.region
            )
            
            await self._master.initialize()
            
            if self.on_connected:
                self.on_connected()
            
            self._running = True
            await self._master.start()
            
        except Exception as e:
            if self.on_error:
                self.on_error(e)
            raise
    
    async def stop(self):
        """Stop streaming"""
        self._running = False
        if self._master:
            await self._master.stop()
    
    def is_running(self) -> bool:
        """Check if streaming is active"""
        return self._running
    
    @property
    def dash_url(self) -> str:
        """Get DASH playback URL (requires AWS CLI or boto3 call)"""
        return f"Use: aws kinesisvideo get-dash-streaming-session-url --stream-name {self.stream_name}"
    
    @property
    def hls_url(self) -> str:
        """Get HLS playback URL (requires AWS CLI or boto3 call)"""
        return f"Use: aws kinesisvideo get-hls-streaming-session-url --stream-name {self.stream_name}"


class KVSStreamingBuilder:
    """
    Builder pattern for KVS streaming configuration
    
    Usage:
        client = (KVSStreamingBuilder()
            .with_channel("my-channel")
            .with_stream("my-stream")
            .with_quality(StreamQuality.HIGH)
            .with_auto_keyframe()
            .build())
        await client.start()
    """
    
    def __init__(self):
        self._config = {
            "region": None,
            "quality": StreamQuality.MEDIUM,
            "auto_keyframe": False,
            "on_connected": None,
            "on_error": None,
            "on_stats": None
        }
    
    def with_channel(self, name: str):
        self._config["channel_name"] = name
        return self
    
    def with_stream(self, name: str):
        self._config["stream_name"] = name
        return self
    
    def with_region(self, region: str):
        self._config["region"] = region
        return self
    
    def with_quality(self, quality: StreamQuality):
        self._config["quality"] = quality
        return self
    
    def with_auto_keyframe(self, enabled: bool = True):
        self._config["auto_keyframe"] = enabled
        return self
    
    def on_connected(self, callback: Callable):
        self._config["on_connected"] = callback
        return self
    
    def on_error(self, callback: Callable[[Exception], None]):
        self._config["on_error"] = callback
        return self
    
    def on_stats(self, callback: Callable[[Dict[str, Any]], None]):
        self._config["on_stats"] = callback
        return self
    
    def build(self) -> KVSStreamingClient:
        if "channel_name" not in self._config:
            raise ValueError("Channel name is required")
        if "stream_name" not in self._config:
            raise ValueError("Stream name is required")
        return KVSStreamingClient(**self._config)
