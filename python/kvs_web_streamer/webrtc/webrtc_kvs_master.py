import asyncio
import boto3
import websockets
import json
import logging
import os
import requests
import time
from base64 import b64decode, b64encode
from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription, RTCRtpReceiver
from aiortc.contrib.media import MediaBlackhole
from aiortc.sdp import candidate_from_sdp
from aiortc.codecs import get_decoder
from aiortc.rtcrtpreceiver import RemoteStreamTrack
from botocore.auth import SigV4QueryAuth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials
from streaming.mkv_packager import MKVPackager
from streaming.sigv4_signer import SigV4Signer
from webrtc.h264_rtp_parser import H264RTPParser
from common.events import EventBus, StreamEvent

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Suppress aioice TURN errors (non-critical, falls back to STUN)
logging.getLogger('aioice').setLevel(logging.ERROR)


class KVSPutMediaUploader:
    """Upload MKV to KVS using PutMedia with streaming HTTP connection"""
    
    def __init__(self, stream_name: str, region: str):
        print(f"Stream name!! {stream_name}")
        self.stream_name = stream_name
        self.region = region
        self.kvs_client = boto3.client(
            'kinesisvideo',
            region_name=region
        )
        self.endpoint = None
        self.stream_start_time = int(time.time())
        self.data_queue = asyncio.Queue(maxsize=100)  # Buffer up to 100 chunks
        self.socket = None
        self.stream_ready = asyncio.Event()
        self._send_task = None
        self._receive_task = None
        
    def _get_endpoint(self):
        """Get PutMedia endpoint"""
        if not self.endpoint:
            response = self.kvs_client.get_data_endpoint(
                StreamName=self.stream_name,
                APIName='PUT_MEDIA'
            )
            self.endpoint = response['DataEndpoint']
            logger.info(f"PutMedia endpoint: {self.endpoint}")
        return self.endpoint
    
    async def start_stream(self):
        """Start streaming PutMedia connection using raw socket"""
        import ssl
        from urllib.parse import urlparse
        from botocore.auth import SigV4Auth
        from botocore.awsrequest import AWSRequest
        
        endpoint = self._get_endpoint()
        parsed = urlparse(endpoint)
        host = parsed.netloc
        port = 443
        path = "/putMedia"
        url = f"https://{host}{path}"
        
        logger.info(f"[HTTP] Connecting to {host}:{port}")
        
        # Create SSL socket
        context = ssl.create_default_context()
        reader, writer = await asyncio.open_connection(host, port, ssl=context)
        self.socket = writer
        
        logger.info(f"[HTTP] SSL connection established")
        
        # Build headers
        headers = {
            'Host': host,
            'x-amzn-stream-name': self.stream_name,
            'x-amzn-fragment-timecode-type': 'RELATIVE',
            'x-amzn-producer-start-timestamp': str(self.stream_start_time),
            'Content-Type': 'application/octet-stream',
            'x-amz-content-sha256': 'UNSIGNED-PAYLOAD',
        }
        
        # Use boto3's SigV4Auth for signing
        request = AWSRequest(method='POST', url=url, headers=headers)
        SigV4Auth(boto3.Session().get_credentials(), 'kinesisvideo', self.region).add_auth(request)
        
        # Build HTTP request
        http_request = f"POST {path} HTTP/1.1\r\n"
        for key, value in request.headers.items():
            http_request += f"{key}: {value}\r\n"
        http_request += "Transfer-Encoding: chunked\r\n"
        http_request += "\r\n"
        
        writer.write(http_request.encode())
        await writer.drain()
        logger.info(f"[HTTP] Request sent, starting data sender and ACK receiver")
        
        # Start sender and receiver tasks
        self._send_task = asyncio.create_task(self._send_data(writer))
        self._receive_task = asyncio.create_task(self._receive_acks(reader))
        
        # Signal that stream is ready
        self.stream_ready.set()
        logger.info(f"[HTTP] Stream ready for data")
    
    async def _send_data(self, writer):
        """Send data in chunked encoding"""
        bytes_sent = 0
        try:
            while True:
                data = await self.data_queue.get()
                if data is None:
                    break
                
                # Send chunk: size in hex + CRLF + data + CRLF
                chunk_size = f"{len(data):X}\r\n".encode()
                writer.write(chunk_size)
                writer.write(data)
                writer.write(b"\r\n")
                await writer.drain()
                
                bytes_sent += len(data)
        except Exception as e:
            logger.error(f"[HTTP] Send error: {e}")
            logger.error(f"[KVS] Connection dropped after {bytes_sent} bytes")
    
    async def _receive_acks(self, reader):
        """Receive ACK responses"""
        try:
            # Read HTTP response headers
            status_line = await reader.readline()
            status_text = status_line.decode().strip()
            
            # Check status code
            if not status_text.startswith("HTTP/1.1 200"):
                logger.error(f"[HTTP] Non-200 response: {status_text}")
            else:
                logger.info(f"[HTTP] {status_text}")
            
            # Read headers until empty line
            while True:
                line = await reader.readline()
                if line == b"\r\n":
                    break
            
            # Read and parse ACK messages
            while True:
                line = await reader.readline()
                if not line:
                    break
                try:
                    ack = json.loads(line.decode().strip())
                    logger.info(f"[ACK] {ack}")
                except:
                    pass
        except Exception as e:
            logger.error(f"[HTTP] Receive error: {e}")
    
    async def send_data(self, data: bytes):
        """Send MKV data to stream"""
        # Wait for stream to be ready before sending
        await self.stream_ready.wait()
        await self.data_queue.put(data)
    
    async def close(self):
        """Close the uploader and cleanup tasks"""
        await self.data_queue.put(None)  # Signal end
        if self._send_task:
            await self._send_task
        if self._receive_task:
            self._receive_task.cancel()
            try:
                await self._receive_task
            except asyncio.CancelledError:
                pass


class WebRTCKVSMaster:
    """WebRTC Master that receives H264 and uploads to KVS"""
    
    def __init__(self, channel_name: str, stream_name: str, region: str, event_bus: EventBus = None):
        self.channel_name = channel_name
        self.stream_name = stream_name
        self.region = region
        self.event_bus = event_bus or EventBus()
        
        self.kinesisvideo = boto3.client(
            'kinesisvideo', region_name=region
        )
        self.peer_connections = {}
        self.packager = None
        self.uploader = KVSPutMediaUploader(stream_name, region)
        self.is_running = False
        self.frame_count = 0
        self.timestamp_ms = 0
        
    async def initialize(self):
        """Initialize WebRTC master"""
        # Get channel ARN
        response = self.kinesisvideo.describe_signaling_channel(ChannelName=self.channel_name)
        self.channel_arn = response['ChannelInfo']['ChannelARN']
        
        # Get endpoints
        response = self.kinesisvideo.get_signaling_channel_endpoint(
            ChannelARN=self.channel_arn,
            SingleMasterChannelEndpointConfiguration={'Protocols': ['HTTPS', 'WSS'], 'Role': 'MASTER'}
        )
        self.endpoints = {ep['Protocol']: ep['ResourceEndpoint'] for ep in response['ResourceEndpointList']}
        
        # Get ICE servers
        signaling_client = boto3.client(
            'kinesis-video-signaling',
            endpoint_url=self.endpoints['HTTPS'],
            region_name=self.region
        )
        response = signaling_client.get_ice_server_config(ChannelARN=self.channel_arn, ClientId='MASTER')
        
        self.ice_servers = [RTCIceServer(urls=f'stun:stun.kinesisvideo.{self.region}.amazonaws.com:443')]
        for ice_server in response['IceServerList']:
            self.ice_servers.append(RTCIceServer(urls=ice_server['Uris'], username=ice_server['Username'], credential=ice_server['Password']))
        
        logger.info("WebRTC master initialized")
    
    def _create_wss_url(self):
        """Create WebSocket URL"""
        sig_v4 = SigV4QueryAuth(boto3.Session().get_credentials(), 'kinesisvideo', self.region, 299)
        aws_request = AWSRequest(method='GET', url=self.endpoints['WSS'], params={'X-Amz-ChannelARN': self.channel_arn, 'X-Amz-ClientId': 'MASTER'})
        sig_v4.add_auth(aws_request)
        return aws_request.prepare().url
    
    async def _handle_sdp_offer(self, payload: dict, client_id: str, websocket):
        """Handle SDP offer"""
        pc = RTCPeerConnection(RTCConfiguration(iceServers=self.ice_servers))
        self.peer_connections[client_id] = pc
        
        @pc.on('connectionstatechange')
        async def on_connectionstatechange():
            logger.info(f"Connection state: {pc.connectionState}")
            if pc.connectionState == 'connected':
                self.event_bus.publish(StreamEvent.CONNECTED, client_id, {'channel': self.channel_name})
        
        @pc.on('iceconnectionstatechange')
        async def on_iceconnectionstatechange():
            logger.info(f"ICE connection state: {pc.iceConnectionState}")
        
        @pc.on('track')
        def on_track(track):
            logger.info(f"Track received: {track.kind} from {client_id}")
            if track.kind == 'video':
                # Get receiver to hook into RTP stream
                for transceiver in pc.getTransceivers():
                    if transceiver.receiver.track == track:
                        asyncio.create_task(self._process_video_rtp(transceiver.receiver, client_id))
                        break
        
        await pc.setRemoteDescription(RTCSessionDescription(sdp=payload['sdp'], type=payload['type']))
        await pc.setLocalDescription(await pc.createAnswer())
        
        # Modify SDP to recvonly and prefer H.264
        sdp_lines = pc.localDescription.sdp.split('\r\n')
        modified_sdp = []
        in_video = False
        h264_payload = None
        
        for line in sdp_lines:
            if line.startswith('a=sendrecv'):
                modified_sdp.append('a=recvonly')
            elif line.startswith('m=video'):
                in_video = True
                # Reorder to put H.264 first (103 and 104)
                parts = line.split()
                if len(parts) > 3:
                    # Move H.264 (103) and its rtx (104) to the front
                    codecs = parts[3:]
                    if '103' in codecs and '104' in codecs:
                        codecs.remove('103')
                        codecs.remove('104')
                        new_codecs = ['103', '104'] + codecs
                        new_line = f"{parts[0]} {parts[1]} {parts[2]} " + ' '.join(new_codecs)
                        modified_sdp.append(new_line)
                        h264_payload = '103'
                    else:
                        modified_sdp.append(line)
                else:
                    modified_sdp.append(line)
            elif line.startswith('m='):
                in_video = False
                modified_sdp.append(line)
            elif in_video and line.startswith('a=fmtp:') and h264_payload and line.startswith(f'a=fmtp:{h264_payload}'):
                # Force specific H.264 profile - Constrained Baseline Level 3.1
                modified_sdp.append(f'a=fmtp:{h264_payload} level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f')
            elif in_video and line.startswith('a=rtcp-fb:') and 'nack' in line:
                # Add PLI and FIR support after nack lines
                modified_sdp.append(line)
                payload = line.split(':')[1].split()[0]
                if f'a=rtcp-fb:{payload} ccm fir' not in modified_sdp:
                    modified_sdp.append(f'a=rtcp-fb:{payload} ccm fir')
                if f'a=rtcp-fb:{payload} nack pli' not in modified_sdp:
                    modified_sdp.append(f'a=rtcp-fb:{payload} nack pli')
            else:
                modified_sdp.append(line)
        
        # Create modified session description for sending
        modified_desc = RTCSessionDescription(
            sdp='\r\n'.join(modified_sdp),
            type='answer'
        )
        
        logger.info(f"Modified SDP answer: recvonly")
        
        answer_message = json.dumps({
            'action': 'SDP_ANSWER',
            'messagePayload': b64encode(json.dumps(modified_desc.__dict__).encode('ascii')).decode('ascii'),
            'recipientClientId': client_id,
        })
        await websocket.send(answer_message)
        logger.info(f"SDP answer sent to {client_id}")
    
    async def _process_video_rtp(self, receiver: RTCRtpReceiver, client_id: str):
        """Process video RTP packets and extract H264"""
        logger.info(f"Starting H264 RTP processing for {client_id}")
        
        auto_insert = os.getenv('AUTO_INSERT_KEYFRAME', 'false').lower() == 'true'
        parser = H264RTPParser()
        self.packager = MKVPackager(640, 480, auto_insert_keyframe=auto_insert)
        logger.info(f"Auto-insert keyframe: {auto_insert}")
        packet_count = 0
        nal_count = 0
        rtp_queue = asyncio.Queue()
        start_time = time.time()
        last_keyframe_request = 0
        
        # Monkey-patch jitter buffer to intercept RTP packets
        jitter_buffer = receiver._RTCRtpReceiver__jitter_buffer
        original_add = jitter_buffer.add
        
        def patched_add(packet):
            asyncio.create_task(rtp_queue.put(packet))
            return original_add(packet)
        
        jitter_buffer.add = patched_add
        logger.info("Patched jitter buffer to intercept RTP packets")
        
        # Start PutMedia stream and wait for it to be ready
        logger.info("Starting PutMedia stream...")
        await self.uploader.start_stream()
        logger.info("PutMedia stream ready, starting RTP processing")
        
        # Start keyframe request task
        remote_ssrc = None
        
        async def request_keyframes():
            from aiortc.rtcrtpsender import RtcpPsfbPacket, RTCP_PSFB_PLI
            await asyncio.sleep(2.0)  # Wait for first packets
            logger.info("[RTCP] Starting PLI request loop")
            while True:
                try:
                    if remote_ssrc:
                        for pc in self.peer_connections.values():
                            for transceiver in pc.getTransceivers():
                                if transceiver.receiver == receiver and transceiver.sender:
                                    pli = RtcpPsfbPacket(fmt=RTCP_PSFB_PLI, ssrc=0, media_ssrc=remote_ssrc)
                                    await transceiver.sender._send_rtcp([pli])
                                    logger.info(f"[RTCP] Sent PLI to SSRC {remote_ssrc}")
                                    break
                    else:
                        logger.debug("[RTCP] Waiting for remote SSRC from RTP packets")
                except Exception as e:
                    logger.error(f"[RTCP] PLI request failed: {e}")
                
                await asyncio.sleep(1.0)
        
        asyncio.create_task(request_keyframes())
        
        try:
            while True:
                packet = await rtp_queue.get()
                packet_count += 1
                
                # Capture remote SSRC from first packet
                if remote_ssrc is None and hasattr(packet, 'ssrc'):
                    remote_ssrc = packet.ssrc
                    logger.info(f"[RTCP] Captured remote SSRC: {remote_ssrc}")
                
                nal_units = parser.parse_rtp_payload(bytes(packet.payload))
                
                for nal_unit in nal_units:
                    nal_count += 1
                    timestamp_ms = int((time.time() - start_time) * 1000)
                    is_keyframe = parser.is_keyframe(nal_unit)
                    nal_type = nal_unit[0] & 0x1F
                    
                    if is_keyframe:
                        self.event_bus.publish(StreamEvent.KEYFRAME_RECEIVED, client_id, {'timestamp': timestamp_ms, 'size': len(nal_unit)})
                    
                    # Set SPS/PPS in packager before writing header
                    # Set SPS/PPS before writing header - wait for stable parameters
                    if not self.packager.header_written:
                        sps, pps = parser.get_parameter_sets()
                        if sps and pps:
                            # Check if this is the first SPS or if it changed
                            if self.packager.sps is None:
                                self.packager.set_codec_private(sps, pps)
                                logger.info(f"[MKV] Set initial CodecPrivate: SPS={len(sps)}B, PPS={len(pps)}B")
                            elif sps != self.packager.sps:
                                # SPS changed before header written - update it
                                logger.info(f"[MKV] SPS changed before header: Old={len(self.packager.sps)}B, New={len(sps)}B - updating")
                                self.packager.set_codec_private(sps, pps)
                        else:
                            # Wait for SPS/PPS before packaging any frames
                            logger.debug(f"[NAL] Waiting for SPS/PPS before starting MKV (got type={nal_type})")
                            continue
                    
                    # Skip all SPS/PPS after header is written
                    if nal_type == 7 or nal_type == 8:
                        current_sps, current_pps = parser.get_parameter_sets()
                        needs_restart = False
                        
                        if nal_type == 7 and current_sps != self.packager.sps:
                            logger.info(f"[NAL] SPS changed mid-stream! Old={len(self.packager.sps)}B, New={len(current_sps)}B")
                            logger.info(f"[NAL] Old SPS: {self.packager.sps.hex()}")
                            logger.info(f"[NAL] New SPS: {current_sps.hex()}")
                            needs_restart = True
                        elif nal_type == 8 and current_pps != self.packager.pps:
                            logger.info(f"[NAL] PPS changed mid-stream! Old={len(self.packager.pps)}B, New={len(current_pps)}B")
                            logger.info(f"[NAL] Old PPS: {self.packager.pps.hex()}")
                            logger.info(f"[NAL] New PPS: {current_pps.hex()}")
                            needs_restart = True
                        
                        if needs_restart and self.packager.header_written:
                            # Restart PutMedia stream with new codec parameters
                            logger.info("[STREAM] Restarting PutMedia stream due to codec parameter change")
                            self.uploader = KVSPutMediaUploader(self.uploader.stream_name, self.uploader.region)
                            await self.uploader.start_stream()
                            
                            # Create new packager with updated parameters
                            auto_insert = os.getenv('AUTO_INSERT_KEYFRAME', 'false').lower() == 'true'
                            self.packager = MKVPackager(640, 480, auto_insert_keyframe=auto_insert)
                            self.packager.set_codec_private(current_sps, current_pps)
                            logger.info(f"[MKV] New stream with CodecPrivate: SPS={len(current_sps)}B, PPS={len(current_pps)}B")
                        
                        logger.debug(f"[NAL] Skipping SPS/PPS (type={nal_type})")
                        continue
                    
                    # Log all NAL units
                    logger.debug(f"[NAL] type={nal_type}, keyframe={is_keyframe}, size={len(nal_unit)}B, ts={timestamp_ms}ms")
                    
                    # Package and send frame
                    frame_data = self.packager.package_frame(nal_unit, timestamp_ms, is_keyframe)
                    
                    if len(frame_data) > 0:
                        logger.debug(f"[MKV] Packaged: NAL={len(nal_unit)}B -> MKV={len(frame_data)}B")
                        await self.uploader.send_data(frame_data)
                    else:
                        logger.info(f"[MKV] Skipped non-keyframe before first keyframe")
                
                if packet_count % 300 == 0:
                    stats = parser.get_stats()
                    logger.info(f"Processed {packet_count} RTP packets, {nal_count} NAL units")
                    logger.info(f"RTP Stats: Single={stats['single']}, FU-A={stats['fu_a']}, STAP-A={stats['stap_a']}, Errors={stats['errors']}")
                    
        except Exception as e:
            logger.error(f"RTP processing error: {e}")
            self.event_bus.publish(StreamEvent.ERROR, client_id, {'error': str(e)})
            import traceback
            logger.error(traceback.format_exc())
    
    async def start(self):
        """Start WebRTC master"""
        self.is_running = True
        wss_url = self._create_wss_url()
        
        async with websockets.connect(wss_url) as websocket:
            logger.info("Connected to signaling channel")
            async for message in websocket:
                if not self.is_running:
                    break
                
                if not message or not message.strip():
                    continue
                
                try:
                    data = json.loads(message)
                except json.JSONDecodeError:
                    logger.warning(f"Invalid JSON message: {message[:100]}")
                    continue
                
                if 'messagePayload' in data:
                    payload = json.loads(b64decode(data['messagePayload'].encode('ascii')).decode('ascii'))
                    client_id = data.get('senderClientId')
                    
                    if data['messageType'] == 'SDP_OFFER':
                        await self._handle_sdp_offer(payload, client_id, websocket)
                    elif data['messageType'] == 'ICE_CANDIDATE':
                        if client_id in self.peer_connections:
                            candidate = candidate_from_sdp(payload['candidate'])
                            candidate.sdpMid = payload['sdpMid']
                            candidate.sdpMLineIndex = payload['sdpMLineIndex']
                            await self.peer_connections[client_id].addIceCandidate(candidate)
    
    async def stop(self):
        """Stop WebRTC master"""
        self.is_running = False
        
        # Close uploader
        if self.uploader:
            await self.uploader.close()
        
        # Close peer connections
        for pc in list(self.peer_connections.values()):
            try:
                await asyncio.wait_for(pc.close(), timeout=2.0)
            except:
                pass
        self.peer_connections.clear()
