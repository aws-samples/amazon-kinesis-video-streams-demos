"""
Stream Manager - Handle multiple concurrent KVS streams
"""
import asyncio
from typing import Dict, Optional
from streaming.kvs_streaming_client import KVSStreamingClient, StreamQuality


class StreamManager:
    """
    Manage ingesting into KVS streams

    Usage:
        manager = StreamManager()
        await manager.add_stream("stream1", "channel1", "stream1")
        await manager.start_all()
    """

    def __init__(self):
        self._streams: Dict[str, KVSStreamingClient] = {}
        self._tasks: Dict[str, asyncio.Task] = {}

    def add_stream(
        self,
        stream_id: str,
        channel_name: str,
        stream_name: str,
        quality: StreamQuality = StreamQuality.MEDIUM,
        **kwargs
    ) -> KVSStreamingClient:
        """Add a stream to manage"""
        client = KVSStreamingClient(
            channel_name=channel_name,
            stream_name=stream_name,
            quality=quality,
            **kwargs
        )
        self._streams[stream_id] = client
        return client

    async def start_stream(self, stream_id: str):
        """Start a specific stream"""
        if stream_id not in self._streams:
            raise ValueError(f"Stream {stream_id} not found")

        client = self._streams[stream_id]
        task = asyncio.create_task(client.start())
        self._tasks[stream_id] = task

    async def stop_stream(self, stream_id: str):
        """Stop a specific stream"""
        if stream_id in self._streams:
            await self._streams[stream_id].stop()
        if stream_id in self._tasks:
            self._tasks[stream_id].cancel()
            del self._tasks[stream_id]

    async def start_all(self):
        """Start all streams concurrently"""
        tasks = []
        for stream_id in self._streams:
            task = asyncio.create_task(self.start_stream(stream_id))
            tasks.append(task)
        await asyncio.gather(*tasks, return_exceptions=True)

    async def stop_all(self):
        """Stop all streams"""
        for stream_id in list(self._streams.keys()):
            await self.stop_stream(stream_id)

    def get_stream(self, stream_id: str) -> Optional[KVSStreamingClient]:
        """Get a specific stream client"""
        return self._streams.get(stream_id)

    def list_streams(self) -> list:
        """List all stream IDs"""
        return list(self._streams.keys())

    def get_status(self) -> Dict[str, bool]:
        """Get status of all streams"""
        return {
            stream_id: client.is_running()
            for stream_id, client in self._streams.items()
        }
