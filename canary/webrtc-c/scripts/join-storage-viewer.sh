#!/bin/bash
# parameters: CHANNEL_NAME, CLIENT_ID, REGION

set -e  # Exit on error

CHANNEL_NAME="$1"
CLIENT_ID="$2"
REGION="$3"

echo "Channel: $CHANNEL_NAME, Client: $CLIENT_ID, Region: $REGION"

# Get channel ARN
echo "Getting channel ARN..."
CHANNEL_ARN=$(aws kinesisvideo describe-signaling-channel \
  --channel-name "$CHANNEL_NAME" \
  --region "$REGION" \
  --query 'ChannelInfo.ChannelARN' \
  --output text)

if [ -z "$CHANNEL_ARN" ] || [ "$CHANNEL_ARN" = "None" ]; then
    echo "Error: Channel not found or ARN is empty"
    exit 1
fi
echo "Channel ARN: $CHANNEL_ARN"

# Get WebRTC Endpoint
echo "Getting WebRTC endpoint..."
WEBRTC_ENDPOINT=$(aws kinesisvideo get-signaling-channel-endpoint \
  --channel-arn "$CHANNEL_ARN" \
  --single-master-channel-endpoint-configuration Protocols=WEBRTC,Role=VIEWER \
  --region "$REGION" \
  --query 'ResourceEndpointList[0].ResourceEndpoint' \
  --output text)

if [ -z "$WEBRTC_ENDPOINT" ] || [ "$WEBRTC_ENDPOINT" = "None" ]; then
    echo "Error: WebRTC endpoint is empty"
    exit 1
fi
echo "WebRTC Endpoint: $WEBRTC_ENDPOINT"

# Call JoinStorageSessionAsViewer API
echo "Calling JoinStorageSessionAsViewer..."
aws kinesis-video-webrtc-storage join-storage-session-as-viewer \
  --channel-arn "$CHANNEL_ARN" \
  --client-id "$CLIENT_ID" \
  --endpoint-url "$WEBRTC_ENDPOINT" \
  --region "$REGION"

echo "Successfully joined storage session"