import struct
import logging

logger = logging.getLogger(__name__)

class H264RTPParser:
    """Parse H264 RTP payloads to extract NAL units"""
    
    def __init__(self):
        self.sps = None
        self.pps = None
        self.pending_fu_a = []
        self.fu_a_nal_type = None  # Track expected NAL type for FU-A sequence
        self.stats = {'single': 0, 'fu_a': 0, 'stap_a': 0, 'errors': 0}
        
    def parse_rtp_payload(self, payload: bytes) -> list:
        """Parse RTP payload and return list of complete NAL units"""
        if len(payload) < 2:
            return []
        
        nal_units = []
        nal_header = payload[0]
        nal_type = nal_header & 0x1F
        
        logger.debug(f"[RTP] Payload size={len(payload)}, NAL header=0x{nal_header:02x}, type={nal_type}, first 4 bytes={payload[:4].hex()}")
        
        # Single NAL unit (types 1-23)
        if nal_type >= 1 and nal_type <= 23:
            logger.debug(f"[RTP] Single NAL unit, type={nal_type}")
            self.stats['single'] += 1
            nal_units.append(payload)
            self._store_parameter_sets(payload)
            
        # FU-A (Fragmentation Unit) - type 28
        elif nal_type == 28:
            logger.debug(f"[RTP] FU-A fragment detected")
            fu_header = payload[1]
            start_bit = (fu_header & 0x80) >> 7
            end_bit = (fu_header & 0x40) >> 6
            nal_type_fu = fu_header & 0x1F
            logger.debug(f"[RTP] FU-A: start={start_bit}, end={end_bit}, nal_type={nal_type_fu}")
            
            if start_bit:
                # Start of fragmented NAL
                self.stats['fu_a'] += 1
                reconstructed_nal_header = (nal_header & 0xE0) | nal_type_fu
                self.pending_fu_a = [bytes([reconstructed_nal_header])]
                self.pending_fu_a.append(payload[2:])
                self.fu_a_nal_type = nal_type_fu
            elif end_bit:
                # End of fragmented NAL - only if we have pending data
                if self.pending_fu_a and self.fu_a_nal_type == nal_type_fu:
                    self.pending_fu_a.append(payload[2:])
                    complete_nal = b''.join(self.pending_fu_a)
                    nal_units.append(complete_nal)
                    self._store_parameter_sets(complete_nal)
                    self.pending_fu_a = []
                    self.fu_a_nal_type = None
                else:
                    logger.warning(f"[RTP] FU-A end fragment without matching start (expected type={self.fu_a_nal_type}, got={nal_type_fu})")
                    self.stats['errors'] += 1
                    self.pending_fu_a = []
                    self.fu_a_nal_type = None
            else:
                # Middle fragment - only if we have pending data
                if self.pending_fu_a and self.fu_a_nal_type == nal_type_fu:
                    self.pending_fu_a.append(payload[2:])
                else:
                    logger.warning(f"[RTP] FU-A middle fragment without matching start (expected type={self.fu_a_nal_type}, got={nal_type_fu})")
                    self.stats['errors'] += 1
                    self.pending_fu_a = []
                    self.fu_a_nal_type = None
        
        # STAP-A (Single Time Aggregation Packet) - type 24
        elif nal_type == 24:
            logger.info(f"[RTP] STAP-A aggregation packet")
            self.stats['stap_a'] += 1
            offset = 1
            while offset < len(payload):
                if offset + 2 > len(payload):
                    break
                nal_size = struct.unpack('>H', payload[offset:offset+2])[0]
                offset += 2
                if offset + nal_size > len(payload):
                    break
                nal_unit = payload[offset:offset+nal_size]
                nal_units.append(nal_unit)
                self._store_parameter_sets(nal_unit)
                offset += nal_size
        
        return nal_units
    
    def get_stats(self):
        """Get RTP packet statistics"""
        return self.stats
    
    def _store_parameter_sets(self, nal_unit: bytes):
        """Store SPS and PPS for later use"""
        if len(nal_unit) < 1:
            return
        
        nal_type = nal_unit[0] & 0x1F
        if nal_type == 7:  # SPS
            self.sps = nal_unit
            logger.info(f"Stored SPS: {len(nal_unit)} bytes")
        elif nal_type == 8:  # PPS
            self.pps = nal_unit
            logger.info(f"Stored PPS: {len(nal_unit)} bytes")
    
    def get_parameter_sets(self):
        """Get SPS and PPS"""
        return self.sps, self.pps
    
    def is_keyframe(self, nal_unit: bytes) -> bool:
        """Check if NAL unit is a keyframe (IDR)"""
        if len(nal_unit) < 1:
            return False
        return (nal_unit[0] & 0x1F) == 5
