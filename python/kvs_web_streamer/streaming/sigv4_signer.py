import hashlib
import hmac
from datetime import datetime
from urllib.parse import urlparse

class SigV4Signer:
    """AWS Signature Version 4 signer for KVS"""
    
    def __init__(self, access_key: str, secret_key: str, session_token: str, region: str):
        self.access_key = access_key
        self.secret_key = secret_key
        self.session_token = session_token
        self.region = region
        self.service = 'kinesisvideo'
    
    def sign_request(self, method: str, url: str, headers: dict, payload: bytes) -> dict:
        """Sign HTTP request with SigV4"""
        parsed = urlparse(url)
        uri = parsed.path or '/'
        
        now = datetime.utcnow()
        amz_date = now.strftime('%Y%m%dT%H%M%SZ')
        date_stamp = now.strftime('%Y%m%d')
        
        # Add AWS headers
        headers['X-Amz-Date'] = amz_date
        if self.session_token:
            headers['X-Amz-Security-Token'] = self.session_token
        
        # For POST, use UNSIGNED-PAYLOAD
        if method == 'POST':
            headers['x-amz-content-sha256'] = 'UNSIGNED-PAYLOAD'
            payload_hash = 'UNSIGNED-PAYLOAD'
        else:
            payload_hash = hashlib.sha256(payload).hexdigest()
            headers['x-amz-content-sha256'] = payload_hash
        
        # Canonical request
        canonical_headers = ''.join(f'{k.lower()}:{v.strip()}\n' for k, v in sorted(headers.items()))
        signed_headers = ';'.join(sorted(k.lower() for k in headers.keys()))
        canonical_request = f'{method}\n{uri}\n\n{canonical_headers}\n{signed_headers}\n{payload_hash}'
        
        print(f"[SIGV4] Canonical Request:\n{canonical_request}")
        print(f"[SIGV4] Canonical Request Hash: {hashlib.sha256(canonical_request.encode()).hexdigest()}")
        
        # String to sign
        credential_scope = f'{date_stamp}/{self.region}/{self.service}/aws4_request'
        string_to_sign = f'AWS4-HMAC-SHA256\n{amz_date}\n{credential_scope}\n{hashlib.sha256(canonical_request.encode()).hexdigest()}'
        
        print(f"[SIGV4] String to Sign:\n{string_to_sign}")
        
        # Signature
        k_date = hmac.new(f'AWS4{self.secret_key}'.encode(), date_stamp.encode(), hashlib.sha256).digest()
        k_region = hmac.new(k_date, self.region.encode(), hashlib.sha256).digest()
        k_service = hmac.new(k_region, self.service.encode(), hashlib.sha256).digest()
        k_signing = hmac.new(k_service, b'aws4_request', hashlib.sha256).digest()
        signature = hmac.new(k_signing, string_to_sign.encode(), hashlib.sha256).hexdigest()
        
        print(f"[SIGV4] Signature: {signature}")
        
        # Authorization header
        headers['Authorization'] = f'AWS4-HMAC-SHA256 Credential={self.access_key}/{credential_scope}, SignedHeaders={signed_headers}, Signature={signature}'
        
        return headers
