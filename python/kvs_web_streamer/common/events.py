"""
Event System - Monitoring and observability
"""
from enum import Enum
from typing import Callable, Dict, Any, List
from dataclasses import dataclass
from datetime import datetime


class StreamEvent(Enum):
    """Stream event types"""
    CONNECTED = "connected"
    DISCONNECTED = "disconnected"
    KEYFRAME_RECEIVED = "keyframe_received"
    ERROR = "error"
    STATS_UPDATE = "stats_update"
    CLUSTER_CREATED = "cluster_created"
    CODEC_CHANGE = "codec_change"
    BUFFER_FULL = "buffer_full"


@dataclass
class Event:
    """Event data structure"""
    type: StreamEvent
    timestamp: datetime
    stream_id: str
    data: Dict[str, Any]


class EventBus:
    """
    Event bus for stream monitoring
    
    Usage:
        bus = EventBus()
        bus.subscribe(StreamEvent.CONNECTED, lambda e: print(f"Connected: {e.stream_id}"))
        bus.publish(StreamEvent.CONNECTED, "stream1", {"client_id": "abc"})
    """
    
    def __init__(self):
        self._subscribers: Dict[StreamEvent, List[Callable]] = {}
    
    def subscribe(self, event_type: StreamEvent, callback: Callable[[Event], None]):
        """Subscribe to an event type"""
        if event_type not in self._subscribers:
            self._subscribers[event_type] = []
        self._subscribers[event_type].append(callback)
    
    def unsubscribe(self, event_type: StreamEvent, callback: Callable):
        """Unsubscribe from an event type"""
        if event_type in self._subscribers:
            self._subscribers[event_type].remove(callback)
    
    def publish(self, event_type: StreamEvent, stream_id: str, data: Dict[str, Any]):
        """Publish an event"""
        event = Event(
            type=event_type,
            timestamp=datetime.now(),
            stream_id=stream_id,
            data=data
        )
        
        if event_type in self._subscribers:
            for callback in self._subscribers[event_type]:
                try:
                    callback(event)
                except Exception as e:
                    print(f"Error in event handler: {e}")


class StreamMetrics:
    """
    Collect and expose stream metrics
    
    Usage:
        metrics = StreamMetrics("stream1")
        metrics.record_packet(100)
        metrics.record_keyframe()
        print(metrics.get_summary())
    """
    
    def __init__(self, stream_id: str):
        self.stream_id = stream_id
        self.start_time = datetime.now()
        
        self.packets_received = 0
        self.bytes_received = 0
        self.keyframes_received = 0
        self.errors = 0
        self.clusters_created = 0
        
        self.rtp_stats = {
            "single": 0,
            "fu_a": 0,
            "stap_a": 0,
            "errors": 0
        }
    
    def record_packet(self, size: int):
        """Record RTP packet"""
        self.packets_received += 1
        self.bytes_received += size
    
    def record_keyframe(self):
        """Record keyframe"""
        self.keyframes_received += 1
    
    def record_error(self):
        """Record error"""
        self.errors += 1
    
    def record_cluster(self):
        """Record cluster creation"""
        self.clusters_created += 1
    
    def update_rtp_stats(self, stats: Dict[str, int]):
        """Update RTP statistics"""
        self.rtp_stats = stats
    
    def get_summary(self) -> Dict[str, Any]:
        """Get metrics summary"""
        duration = (datetime.now() - self.start_time).total_seconds()
        return {
            "stream_id": self.stream_id,
            "duration_seconds": duration,
            "packets_received": self.packets_received,
            "bytes_received": self.bytes_received,
            "keyframes_received": self.keyframes_received,
            "errors": self.errors,
            "clusters_created": self.clusters_created,
            "packets_per_second": self.packets_received / duration if duration > 0 else 0,
            "bytes_per_second": self.bytes_received / duration if duration > 0 else 0,
            "rtp_stats": self.rtp_stats
        }
    
    def reset(self):
        """Reset metrics"""
        self.start_time = datetime.now()
        self.packets_received = 0
        self.bytes_received = 0
        self.keyframes_received = 0
        self.errors = 0
        self.clusters_created = 0
        self.rtp_stats = {"single": 0, "fu_a": 0, "stap_a": 0, "errors": 0}
