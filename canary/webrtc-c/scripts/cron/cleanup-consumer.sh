#!/bin/bash
# cleanup-consumer.sh — Cron job for storage consumer nodes
# Removes temporary files older than 1 hour.
#
# Install: crontab -e
#   0 * * * * /home/ubuntu/webrtc-c-storage-master/repo/canary/webrtc-c/scripts/cron/cleanup-consumer.sh >> /home/ubuntu/webrtc-c-storage-master/logs/cleanup.log 2>&1

set -euo pipefail

CONSUMER_HOME="${HOME}/webrtc-c-storage-master"
REPO_DIR="${CONSUMER_HOME}/repo"

echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-consumer] Starting cleanup"

# GetClip MP4 files older than 1 hour
find "${REPO_DIR}/canary/consumer-java" -name 'clip-*.mp4' -mmin +60 -delete 2>/dev/null || true

# Maven build logs if any
find "${REPO_DIR}/canary/consumer-java" -name '*.log' -mmin +60 -delete 2>/dev/null || true

# Jenkins workspace leftovers
find "${HOME}/Jenkins/workspace" -maxdepth 1 -type d -name 'webrtc-*' -mmin +60 -exec rm -rf {} + 2>/dev/null || true

# Video verification temp files (from verify.py)
find /tmp -name 'verify_*' -mmin +60 -delete 2>/dev/null || true
find /tmp -name 'frame_extract_*' -type d -mmin +60 -exec rm -rf {} + 2>/dev/null || true

echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-consumer] Done"
