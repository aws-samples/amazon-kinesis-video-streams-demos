#!/bin/bash
# cleanup-consumer.sh — Cron job for storage consumer nodes
# Removes temporary files older than 1 hour.
# Respects .in_use lockfiles to avoid deleting active Jenkins workspaces.
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
# Covers both ~/Jenkins/workspace/webrtc-* (default) and ~/Jenkins/webrtc-* (custom ws() calls)
# Skip workspaces that have a .in_use file younger than 2 hours (active builds)
for dir in "${HOME}/Jenkins/workspace"/webrtc-* "${HOME}/Jenkins"/webrtc-*; do
    [ -d "$dir" ] || continue
    # Skip if directory is less than 60 minutes old
    if ! find "$dir" -maxdepth 0 -mmin +60 | grep -q .; then
        continue
    fi
    # Skip if .in_use exists and is less than 2 hours old (active build)
    if [ -f "$dir/.in_use" ] && find "$dir/.in_use" -mmin -120 | grep -q .; then
        echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-consumer] Skipping active workspace: $dir"
        continue
    fi
    echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-consumer] Removing stale workspace: $dir"
    rm -rf "$dir"
done

# Video verification temp files (from verify.py)
find /tmp -name 'verify_*' -mmin +60 -delete 2>/dev/null || true
find /tmp -name 'frame_extract_*' -type d -mmin +60 -exec rm -rf {} + 2>/dev/null || true

echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-consumer] Done"
