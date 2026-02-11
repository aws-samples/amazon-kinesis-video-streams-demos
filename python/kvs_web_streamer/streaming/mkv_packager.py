import struct
import io


class MKVPackager:
    """Minimal MKV packager for H264 video"""

    def __init__(self, width: int, height: int, fps: int = 30, auto_insert_keyframe: bool = False):
        self.width = width
        self.height = height
        self.fps = fps
        self.header_written = False
        self.cluster_started = False
        self.cluster_timestamp = 0
        self.cluster_duration_ms = 2000  # 2 seconds for better HLS compatibility
        self.force_cluster_on_time = True
        self.auto_insert_keyframe = auto_insert_keyframe
        self.sps = None
        self.pps = None
        self.last_keyframe_data = None
        self.last_keyframe_timestamp = 0
        self.last_absolute_timestamp = -1
        self.frame_count = 0

    def set_codec_private(self, sps: bytes, pps: bytes):
        """Set SPS and PPS for CodecPrivate"""
        self.sps = sps
        self.pps = pps

    def generate_header(self) -> bytes:
        """Generate MKV header with EBML, Segment, Info, and Tracks"""
        buf = io.BytesIO()

        # EBML Header
        buf.write(bytes([0x1A, 0x45, 0xDF, 0xA3, 0xA3]))
        buf.write(bytes([0x42, 0x86, 0x81, 0x01]))
        buf.write(bytes([0x42, 0xF7, 0x81, 0x01]))
        buf.write(bytes([0x42, 0xF2, 0x81, 0x04]))
        buf.write(bytes([0x42, 0xF3, 0x81, 0x08]))
        buf.write(bytes([0x42, 0x82, 0x88]) + b'matroska')
        buf.write(bytes([0x42, 0x87, 0x81, 0x04]))
        buf.write(bytes([0x42, 0x85, 0x81, 0x02]))

        # Segment (unknown size)
        buf.write(bytes([0x18, 0x53, 0x80, 0x67]))
        buf.write(bytes([0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]))

        # Info
        info = io.BytesIO()
        info.write(bytes([0x2A, 0xD7, 0xB1, 0x88]) + struct.pack('>Q', 1_000_000))
        info_data = info.getvalue()
        buf.write(bytes([0x15, 0x49, 0xA9, 0x66]) + self._encode_size(len(info_data)) + info_data)

        # Tracks
        track = io.BytesIO()
        track.write(bytes([0xD7, 0x81, 0x01]))
        track.write(bytes([0x73, 0xC5, 0x88]) + struct.pack('>Q', 1))
        track.write(bytes([0x83, 0x81, 0x01]))

        # CodecID
        codec_id = b'V_MPEG4/ISO/AVC'
        track.write(bytes([0x86]) + self._encode_size(len(codec_id)) + codec_id)

        # CodecPrivate (avcC format) if we have SPS/PPS
        if self.sps and self.pps:
            avcc = self._create_avcc(self.sps, self.pps)
            track.write(bytes([0x63, 0xA2]) + self._encode_size(len(avcc)) + avcc)

        # Video settings
        video = io.BytesIO()
        video.write(bytes([0xB0, 0x82]) + struct.pack('>H', self.width))
        video.write(bytes([0xBA, 0x82]) + struct.pack('>H', self.height))
        video_data = video.getvalue()
        track.write(bytes([0xE0]) + self._encode_size(len(video_data)) + video_data)

        track_entry = track.getvalue()
        tracks = bytes([0xAE]) + self._encode_size(len(track_entry)) + track_entry
        buf.write(bytes([0x16, 0x54, 0xAE, 0x6B]) + self._encode_size(len(tracks)) + tracks)

        return buf.getvalue()

    def package_frame(self, frame_data: bytes, timestamp_ms: int, is_keyframe: bool) -> bytes:
        """Package H264 frame into MKV following streaming approach"""
        output = io.BytesIO()
        self.frame_count += 1

        # Write header only once
        if not self.header_written:
            output.write(self.generate_header())
            self.header_written = True

        # Wait for first keyframe to start streaming
        if not self.cluster_started:
            if not is_keyframe:
                return b''  # Skip non-keyframes before first keyframe
            # Start first cluster on first keyframe
            output.write(self._create_cluster_header(timestamp_ms))
            self.cluster_timestamp = timestamp_ms
            self.cluster_started = True
            self.last_keyframe_data = frame_data
            self.last_keyframe_timestamp = timestamp_ms
        elif timestamp_ms - self.cluster_timestamp >= self.cluster_duration_ms:
            if is_keyframe:
                # Start new cluster on keyframe after duration threshold
                output.write(self._create_cluster_header(timestamp_ms))
                self.cluster_timestamp = timestamp_ms
                self.last_keyframe_data = frame_data
                self.last_keyframe_timestamp = timestamp_ms
            elif self.auto_insert_keyframe and self.last_keyframe_data:
                # Force slice after duration without keyframe, insert previous keyframe
                output.write(self._create_cluster_header(timestamp_ms))
                output.write(self._create_simple_block(self.last_keyframe_data, 0, True))
                self.cluster_timestamp = timestamp_ms

        # Store keyframe for potential reuse
        if is_keyframe:
            self.last_keyframe_data = frame_data
            self.last_keyframe_timestamp = timestamp_ms
        
        # Ensure monotonically increasing absolute timestamps
        adjusted_timestamp = timestamp_ms
        if timestamp_ms <= self.last_absolute_timestamp:
            adjusted_timestamp = self.last_absolute_timestamp + 1
        
        self.last_absolute_timestamp = adjusted_timestamp
        relative_ts = adjusted_timestamp - self.cluster_timestamp
        
        # Validate relative timestamp doesn't overflow 16-bit signed int
        if relative_ts > 32767 or relative_ts < -32768:
            output.write(self._create_cluster_header(adjusted_timestamp))
            self.cluster_timestamp = adjusted_timestamp
            relative_ts = 0
        
        output.write(self._create_simple_block(frame_data, relative_ts, is_keyframe))
        
        return output.getvalue()

    def _create_cluster_header(self, timestamp: int) -> bytes:
        """Create cluster header"""
        buf = io.BytesIO()
        buf.write(bytes([0x1F, 0x43, 0xB6, 0x75]))  # Cluster ID
        buf.write(bytes([0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]))  # Unknown size
        buf.write(bytes([0xE7]))  # Timecode ID
        buf.write(self._encode_size(8))
        buf.write(struct.pack('>Q', timestamp))  # Timecode value
        return buf.getvalue()

    def _create_simple_block(self, frame_data: bytes, relative_timestamp: int, is_keyframe: bool) -> bytes:
        """Create SimpleBlock element"""
        # Validate NAL unit type
        if len(frame_data) > 0:
            nal_type = frame_data[0] & 0x1F
            if nal_type > 23:  # Invalid NAL type
                return b''

        block = io.BytesIO()
        block.write(bytes([0x81]))  # Track number (EBML encoded)
        block.write(struct.pack('>h', relative_timestamp))  # Relative timestamp (signed 16-bit)
        block.write(bytes([0x80 if is_keyframe else 0x00]))  # Flags

        # For AVC format in MKV, use 4-byte length prefix instead of Annex B start code
        block.write(struct.pack('>I', len(frame_data)))  # 4-byte NAL unit length
        block.write(frame_data)  # Frame data

        # Wrap in SimpleBlock element
        simple_block = io.BytesIO()
        simple_block.write(bytes([0xA3]))  # SimpleBlock ID
        simple_block.write(self._encode_size(block.tell()))
        simple_block.write(block.getvalue())

        return simple_block.getvalue()

    def _encode_size(self, size: int) -> bytes:
        """Encode EBML variable-length size"""
        if size < 0x7F:
            return bytes([0x80 | size])
        elif size < 0x3FFF:
            return bytes([0x40 | (size >> 8), size & 0xFF])
        elif size < 0x1FFFFF:
            return bytes([0x20 | (size >> 16), (size >> 8) & 0xFF, size & 0xFF])
        elif size < 0x0FFFFFFF:
            return bytes([0x10 | (size >> 24), (size >> 16) & 0xFF, (size >> 8) & 0xFF, size & 0xFF])
        else:
            return bytes([0x01]) + size.to_bytes(8, 'big')

    def _create_avcc(self, sps: bytes, pps: bytes) -> bytes:
        """Create avcC (AVC Decoder Configuration Record)"""
        avcc = io.BytesIO()
        avcc.write(bytes([0x01]))  # configurationVersion
        avcc.write(bytes([sps[1], sps[2], sps[3]]))  # profile, profile_compat, level
        avcc.write(bytes([0xFF]))  # 6 bits reserved + 2 bits lengthSizeMinusOne (3)
        avcc.write(bytes([0xE1]))  # 3 bits reserved + 5 bits numOfSPS (1)
        avcc.write(struct.pack('>H', len(sps)))  # SPS length
        avcc.write(sps)  # SPS
        avcc.write(bytes([0x01]))  # numOfPPS (1)
        avcc.write(struct.pack('>H', len(pps)))  # PPS length
        avcc.write(pps)  # PPS
        return avcc.getvalue()
