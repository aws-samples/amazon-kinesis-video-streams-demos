#!/bin/bash
# cleanup-master.sh — Cron job for storage master nodes
# Removes temporary files older than 1 hour.
#
# Install: crontab -e
#   0 * * * * /home/ubuntu/webrtc-c-storage-master/repo/canary/webrtc-c/scripts/cron/cleanup-master.sh >> /home/ubuntu/webrtc-c-storage-master/logs/cleanup.log 2>&1

set -euo pipefail

MASTER_HOME="${HOME}/webrtc-c-storage-master"

echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-master] Starting cleanup"

# Build logs older than 1 hour (keep recent for debugging)
find "${MASTER_HOME}/logs" -name 'build-*.log' -mmin +60 -delete 2>/dev/null || true

# IoT cert artifacts older than 1 hour
# Certs are regenerated each run, old ones are stale
find "${MASTER_HOME}/certs" -type f -mmin +60 -delete 2>/dev/null || true
# Remove empty cert prefix directories
find "${MASTER_HOME}/certs" -type d -empty -delete 2>/dev/null || true

# Jenkins workspace leftovers (old workspaces from previous runs)
# Only clean workspaces, not the persistent repo/build dirs
find "${HOME}/Jenkins/workspace" -maxdepth 1 -type d -name 'webrtc-*' -mmin +60 -exec rm -rf {} + 2>/dev/null || true

# Core dumps if any
find "${MASTER_HOME}" -name 'core.*' -mmin +60 -delete 2>/dev/null || true

# firstFrameSentTimeStamp files
find "${MASTER_HOME}/build" -name 'firstFrameSentTimeStamp*.txt' -mmin +60 -delete 2>/dev/null || true

echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-master] Done"
