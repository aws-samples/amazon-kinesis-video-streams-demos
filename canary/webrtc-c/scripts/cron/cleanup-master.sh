#!/bin/bash
# cleanup-master.sh — cron job for storage MASTER nodes
#
# Per-run artifacts by age, plus /tmp scratch. Workspace reaping and the scratch
# sweep live in cleanup-common.sh; read the "Workspace reaping safety" comment
# there before changing anything about deletion.
#
# $HOME is /home/ubuntu on the EC2 nodes and /home/jenkins on the Raspberry Pi
# masters, so the crontab path below differs per node. The script itself only ever
# uses ${HOME}.
#
# Install (bounded-canary node):
#   0 * * * * $HOME/webrtc-c-storage-master/cleanup-master.sh >> $HOME/webrtc-c-storage-master/logs/cleanup.log 2>&1
#
# Install (soak-dedicated master node) — a soak master produces almost nothing:
# no verify.py runs here, the session never ends, and a workspace only appears
# when the soak is restarted. Reaping is off; an hourly tick is plenty.
#   0 * * * * REAP_WORKSPACES=0 $HOME/webrtc-c-storage-master/cleanup-master.sh >> $HOME/webrtc-c-storage-master/logs/cleanup.log 2>&1
#
# Verify before arming a node: DRY_RUN=1 $HOME/webrtc-c-storage-master/cleanup-master.sh

set -euo pipefail

MASTER_HOME="${HOME}/webrtc-c-storage-master"
REPO_DIR="${MASTER_HOME}/repo"

# cron redirects into this directory; if it is missing the shell cannot open the
# >> target and the script never runs at all.
mkdir -p "${MASTER_HOME}/logs"

CLEANUP_TAG="cleanup-master"
# shellcheck source=./cleanup-common.sh
. "$(dirname "$0")/cleanup-common.sh"

log "Starting cleanup"

# Build logs (keep a day for debugging a failed build)
find "${MASTER_HOME}/logs" -name 'build-*.log' -mmin +1440 -delete 2>/dev/null || true

# Post-build smoke-test logs
find "${MASTER_HOME}/logs" -name 'smoke-*.log' -mmin +1440 -delete 2>/dev/null || true

# GetClip MP4s: the consumer/GetClip step runs on the master node in the
# single-node topologies, so clips land here too. Soak never produces these (it
# skips the end-of-run GetClip), so this glob is simply empty on a soak node.
find "${REPO_DIR}/canary/consumer-java" -name 'clip-*.mp4' -mmin +60 -delete 2>/dev/null || true

# Core dumps
find "${MASTER_HOME}" -name 'core.*' -mmin +60 -delete 2>/dev/null || true

# First-frame timestamp handoff files
find "${MASTER_HOME}/build" -name 'firstFrameSentTimeStamp*.txt' -mmin +60 -delete 2>/dev/null || true

# Leftover TWCC env-file if a shaped run died before its finally block
find "${MASTER_HOME}/build" -maxdepth 1 -name '.twcc-master.env' -mmin +60 -delete 2>/dev/null || true

# Jenkins workspaces. Two globs: the default per-job workspace and the custom
# ws() workspaces the runner creates for the master stages.
reap_workspaces "${HOME}/Jenkins/workspace"/webrtc-* "${HOME}/Jenkins"/webrtc-*

# verify.py scratch, present when a co-resident consumer verification runs here.
sweep_verify_scratch

log "Done"
