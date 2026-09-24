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
# high-frequency sweep. SegmentedStreamVerifier verifies one 60s segment per minute, and
# each verify.py run leaves a 150-300MB /tmp/video-verify-* dir, so an hourly tick
# can leave ~60 of them standing (9-18GB). Reaping off, sweep every 5 minutes:
#   */5 * * * * REAP_WORKSPACES=0 $HOME/webrtc-c-storage-master/cleanup-consumer.sh >> $HOME/webrtc-c-storage-master/logs/cleanup.log 2>&1
#
# Verify before arming a node: DRY_RUN=1 $HOME/webrtc-c-storage-master/cleanup-consumer.sh

set -euo pipefail

CONSUMER_HOME="${HOME}/webrtc-c-storage-master"
REPO_DIR="${CONSUMER_HOME}/repo"

# Where the runner's consumer stage tees its stdout+stderr, one file per build
# (storage_runner.groovy / gamma_runner.groovy consumer stage). Kept for a week by
# default: measured at ~0.24 MB/h (11-hour-consumer.log = 2.6MB), so a 7-day window
# over the bounded jobs is a few tens of MB, and a live 30-day soak is one ~170MB
# file. Cheap insurance, because this file is the ONLY durable copy of the consumer's
# log once the Jenkins build record rotates out -- and the only copy anywhere of its
# pre-log4j-init and JVM-crash output.
CONSUMER_LOG_DIR="${CONSUMER_LOG_DIR:-${HOME}/canary-logs}"
CONSUMER_LOG_MAX_AGE_MIN="${CONSUMER_LOG_MAX_AGE_MIN:-10080}"

# cron redirects into this directory; if it is missing the shell cannot open the
# >> target and the script never runs at all.
mkdir -p "${CONSUMER_HOME}/logs"

CLEANUP_TAG="cleanup-consumer"
# shellcheck source=./cleanup-common.sh
. "$(dirname "$0")/cleanup-common.sh"

# Teed consumer logs. Age alone protects a LIVE run: its file is appended to every few
# seconds, so -mmin never matches it. The dangerous case is a HUNG consumer -- the
# process is up, the log has gone quiet, its mtime ages past the window, and deleting
# it would destroy precisely the evidence needed to diagnose the hang. tee is what
# holds the fd, not java, so a pid does not tell us which file is in use; instead,
# while any consumer is running, keep the newest file unconditionally. Same shape as
# sweep_verify_scratch's spool veto in cleanup-common.sh, and the same bias: if the
# guard misfires we leak one file, we do not delete a live log.
reap_consumer_logs() {
    [ -d "${CONSUMER_LOG_DIR}" ] || return 0

    # ls -t is safe here: the runner names these consumer-<BUILD_NUMBER>.log, so no
    # spaces or newlines are possible.
    local protected=''
    if pgrep -f 'WebrtcStorageCanaryConsumer' > /dev/null 2>&1; then
        protected="$(ls -t "${CONSUMER_LOG_DIR}"/consumer-*.log 2>/dev/null | head -n 1)" || true
        if [ -n "${protected}" ]; then
            log "Consumer process is up; keeping newest log: ${protected}"
        fi
    fi

    local f
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        if [ "$f" = "${protected}" ]; then
            continue
        fi
        log "Removing aged consumer log: $f"
        [ "$DRY_RUN" = "1" ] && { log "DRY_RUN would remove: $f"; continue; }
        rm -f "$f" 2>/dev/null || true
    done <<EOF
$(find "${CONSUMER_LOG_DIR}" -maxdepth 1 -type f -name 'consumer-*.log' -mmin "+${CONSUMER_LOG_MAX_AGE_MIN}" 2>/dev/null || true)
EOF
}

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

# Teed per-build consumer logs (~0.24 MB/h). Runs on both node types.
reap_consumer_logs

log "Done"
