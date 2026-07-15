#!/bin/bash
# cleanup-master.sh — Cron job for storage master nodes
# Removes temporary files older than 1 hour.
# Respects .in_use lockfiles to avoid deleting active Jenkins workspaces.
#
# Install: crontab -e
#   0 * * * * /home/ubuntu/webrtc-c-storage-master/repo/canary/webrtc-c/scripts/cron/cleanup-master.sh >> /home/ubuntu/webrtc-c-storage-master/logs/cleanup.log 2>&1

set -euo pipefail

MASTER_HOME="${HOME}/webrtc-c-storage-master"

echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-master] Starting cleanup"

# Build logs older than 24 hours (keep recent for debugging)
find "${MASTER_HOME}/logs" -name 'build-*.log' -mmin +1440 -delete 2>/dev/null || true

# IoT cert artifacts older than 1 hour
# Certs are regenerated each run, old ones are stale
find "${MASTER_HOME}/certs" -type f -mmin +60 -delete 2>/dev/null || true
# Remove empty cert prefix directories
find "${MASTER_HOME}/certs" -type d -empty -delete 2>/dev/null || true

# Jenkins workspace leftovers (old workspaces from previous runs)
# Only clean workspaces, not the persistent repo/build dirs
# Skip workspaces that have a .in_use file younger than 2 hours (active builds)
for dir in "${HOME}/Jenkins/workspace"/webrtc-* "${HOME}/Jenkins"/webrtc-*; do
    [ -d "$dir" ] || continue
    # Skip if directory is less than 60 minutes old
    if ! find "$dir" -maxdepth 0 -mmin +60 | grep -q .; then
        continue
    fi
    # Skip if .in_use exists and is less than 2 hours old (active build)
    if [ -f "$dir/.in_use" ] && find "$dir/.in_use" -mmin -120 | grep -q .; then
        echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-master] Skipping active workspace: $dir"
        continue
    fi
    echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-master] Removing stale workspace: $dir"
    rm -rf "$dir"
done

# Core dumps if any
find "${MASTER_HOME}" -name 'core.*' -mmin +60 -delete 2>/dev/null || true

# firstFrameSentTimeStamp files
find "${MASTER_HOME}/build" -name 'firstFrameSentTimeStamp*.txt' -mmin +60 -delete 2>/dev/null || true

echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-master] Done"
