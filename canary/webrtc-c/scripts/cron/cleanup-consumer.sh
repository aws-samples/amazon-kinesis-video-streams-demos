#!/bin/bash
# cleanup-consumer.sh — cron job for storage CONSUMER nodes
#
# Per-run artifacts by age, plus /tmp scratch. Workspace reaping and the scratch
# sweep live in cleanup-common.sh; read the "Workspace reaping safety" comment
# there before changing anything about deletion.
#
# Install (bounded-canary node):
#   0 * * * * $HOME/webrtc-c-storage-master/cleanup-consumer.sh >> $HOME/webrtc-c-storage-master/logs/cleanup.log 2>&1
#
# Install (soak-dedicated consumer node) — this is the node that actually needs a
# high-frequency sweep. SoakStreamVerifier verifies one 60s segment per minute, and
# each verify.py run leaves a 150-300MB /tmp/video-verify-* dir, so an hourly tick
# can leave ~60 of them standing (9-18GB). Reaping off, sweep every 5 minutes:
#   */5 * * * * REAP_WORKSPACES=0 $HOME/webrtc-c-storage-master/cleanup-consumer.sh >> $HOME/webrtc-c-storage-master/logs/cleanup.log 2>&1
#
# Verify before arming a node: DRY_RUN=1 $HOME/webrtc-c-storage-master/cleanup-consumer.sh

set -euo pipefail

CONSUMER_HOME="${HOME}/webrtc-c-storage-master"
REPO_DIR="${CONSUMER_HOME}/repo"

# cron redirects into this directory; if it is missing the shell cannot open the
# >> target and the script never runs at all.
mkdir -p "${CONSUMER_HOME}/logs"

CLEANUP_TAG="cleanup-consumer"
# shellcheck source=./cleanup-common.sh
. "$(dirname "$0")/cleanup-common.sh"

log "Starting cleanup"

# GetClip MP4s from the end-of-run verification. Soak skips that step entirely, so
# this glob is empty on a soak node.
find "${REPO_DIR}/canary/consumer-java" -name 'clip-*.mp4' -mmin +60 -delete 2>/dev/null || true

# Maven build logs. Scoped to target/ and to *build*.log so this can no longer
# unlink a consumer log file that a live run is still writing to.
find "${REPO_DIR}/canary/consumer-java/target" -maxdepth 1 -name '*.log' -mmin +60 -delete 2>/dev/null || true

# Jenkins workspaces. Two globs: the default per-job workspace and the custom
# ws() workspaces the runner creates.
reap_workspaces "${HOME}/Jenkins/workspace"/webrtc-* "${HOME}/Jenkins"/webrtc-*

# verify.py scratch + the soak GetMedia segment spool. This is the main reason a
# soak consumer runs on a */5 schedule rather than hourly.
sweep_verify_scratch

log "Done"
