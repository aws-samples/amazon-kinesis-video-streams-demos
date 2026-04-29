#!/bin/bash
# cleanup-viewer.sh — Cron job for storage viewer nodes
# Removes temporary files older than 1 hour.
#
# Install: crontab -e
#   0 * * * * /home/ubuntu/JS-viewer-build/cleanup-viewer.sh >> /home/ubuntu/JS-viewer-build/cleanup.log 2>&1
# Note: copy this script to ~/JS-viewer-build/ on the viewer node, or
# reference it from the repo after first checkout.

set -euo pipefail

VIEWER_HOME="${HOME}/JS-viewer-build"

echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-viewer] Starting cleanup"

# Dev server logs older than 1 hour
find "${VIEWER_HOME}" -name 'dev-server-*.log' -mmin +60 -delete 2>/dev/null || true

# Viewer video recordings older than 1 hour
# These are in workspace/canary/webrtc-c/scripts/recordings/
find "${HOME}/Jenkins" -path '*/recordings/viewer-*' -mmin +60 -delete 2>/dev/null || true

# Screenshots from viewer tests
find "${HOME}/Jenkins" -name 'storage-session-active-*.png' -mmin +60 -delete 2>/dev/null || true

# Old viewer workspaces (Jenkins creates per-build workspaces like {job}-Viewer1-{build})
find "${HOME}/Jenkins" -maxdepth 1 -type d -name 'webrtc-*-Viewer*' -mmin +60 -exec rm -rf {} + 2>/dev/null || true
find "${HOME}/Jenkins" -maxdepth 1 -type d -name 'webrtc-*-viewer*' -mmin +60 -exec rm -rf {} + 2>/dev/null || true

# Puppeteer crash dumps if any
find /tmp -name 'puppeteer_dev_*' -mmin +60 -delete 2>/dev/null || true
find /tmp -name 'chrome_crashpad*' -type d -mmin +60 -exec rm -rf {} + 2>/dev/null || true

echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [cleanup-viewer] Done"
