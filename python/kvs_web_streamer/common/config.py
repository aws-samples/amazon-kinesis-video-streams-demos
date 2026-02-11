"""
Configuration Management - Centralized config with validation
"""
from dataclasses import dataclass, field
from typing import Optional, Dict, Any
import os
import json


@dataclass
class StreamConfig:
    """Stream configuration with validation"""
    channel_name: str
    stream_name: str
    region: str = "us-east-1"
    
    # Video settings
    width: int = 640
    height: int = 480
    fps: int = 30
    
    # Streaming settings
    cluster_duration_ms: int = 2000
    auto_insert_keyframe: bool = False
    
    # Network settings
    queue_size: int = 100
    
    # Logging
    log_level: str = "WARNING"
    
    def validate(self):
        """Validate configuration"""
        if not self.channel_name:
            raise ValueError("channel_name is required")
        if not self.stream_name:
            raise ValueError("stream_name is required")
        if self.width <= 0 or self.height <= 0:
            raise ValueError("Invalid video dimensions")
        if self.cluster_duration_ms < 1000 or self.cluster_duration_ms > 10000:
            raise ValueError("cluster_duration_ms must be between 1000-10000")
        if self.log_level not in ["DEBUG", "INFO", "WARNING", "ERROR"]:
            raise ValueError("Invalid log_level")
    
    @classmethod
    def from_env(cls) -> "StreamConfig":
        """Load configuration from environment variables"""
        return cls(
            channel_name=os.getenv("KVS_CHANNEL_NAME", ""),
            stream_name=os.getenv("KVS_STREAM_NAME", ""),
            region=os.getenv("AWS_REGION", "us-east-1"),
            width=int(os.getenv("VIDEO_WIDTH", "640")),
            height=int(os.getenv("VIDEO_HEIGHT", "480")),
            fps=int(os.getenv("VIDEO_FPS", "30")),
            cluster_duration_ms=int(os.getenv("CLUSTER_DURATION_MS", "2000")),
            auto_insert_keyframe=os.getenv("AUTO_INSERT_KEYFRAME", "false").lower() == "true",
            queue_size=int(os.getenv("QUEUE_SIZE", "100")),
            log_level=os.getenv("LOG_LEVEL", "WARNING")
        )
    
    @classmethod
    def from_file(cls, path: str) -> "StreamConfig":
        """Load configuration from JSON file"""
        with open(path) as f:
            data = json.load(f)
        return cls(**data)
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary"""
        return {
            "channel_name": self.channel_name,
            "stream_name": self.stream_name,
            "region": self.region,
            "width": self.width,
            "height": self.height,
            "fps": self.fps,
            "cluster_duration_ms": self.cluster_duration_ms,
            "auto_insert_keyframe": self.auto_insert_keyframe,
            "queue_size": self.queue_size,
            "log_level": self.log_level
        }
    
    def to_file(self, path: str):
        """Save configuration to JSON file"""
        with open(path, 'w') as f:
            json.dump(self.to_dict(), f, indent=2)


class ConfigPresets:
    """Predefined configuration presets"""
    
    @staticmethod
    def low_latency(channel: str, stream: str) -> StreamConfig:
        """Low latency configuration (1s clusters)"""
        return StreamConfig(
            channel_name=channel,
            stream_name=stream,
            cluster_duration_ms=1000,
            auto_insert_keyframe=True
        )
    
    @staticmethod
    def high_quality(channel: str, stream: str) -> StreamConfig:
        """High quality configuration (720p)"""
        return StreamConfig(
            channel_name=channel,
            stream_name=stream,
            width=1280,
            height=720,
            cluster_duration_ms=2000
        )
    
    @staticmethod
    def mobile_optimized(channel: str, stream: str) -> StreamConfig:
        """Mobile-optimized configuration (low bandwidth)"""
        return StreamConfig(
            channel_name=channel,
            stream_name=stream,
            width=480,
            height=360,
            cluster_duration_ms=3000,
            queue_size=50
        )
    
    @staticmethod
    def production(channel: str, stream: str) -> StreamConfig:
        """Production-ready configuration"""
        return StreamConfig(
            channel_name=channel,
            stream_name=stream,
            width=640,
            height=480,
            cluster_duration_ms=2000,
            auto_insert_keyframe=False,
            log_level="WARNING"
        )
