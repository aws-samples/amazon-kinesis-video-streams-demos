#!/bin/bash
# cleanup-viewer.sh — cron job for storage VIEWER nodes
#
# Per-run artifacts by age, plus /tmp + /dev/shm scratch. Workspace reaping and the
# verify scratch sweep live in cleanup-common.sh; read the "Workspace reaping
# safety" comment there before changing anything about deletion.
#
# Install (bounded-canary node):
#   0 * * * * $HOME/JS-viewer-build/cleanup-viewer.sh >> $HOME/JS-viewer-build/cleanup.log 2>&1
#
# Install (soak-dedicated viewer node) — a soak viewer recycles its session every
# VIEWER_SESSION_RECYCLE_SECONDS (default 2400 = 40min), and each segment brings a
# brand-new browser, so Chromium profiles and /dev/shm segments arrive in batches
# every 40 minutes. Reaping off, sweep every 5 minutes:
#   */5 * * * * REAP_WORKSPACES=0 $HOME/JS-viewer-build/cleanup-viewer.sh >> $HOME/JS-viewer-build/cleanup.log 2>&1
#
# Verify before arming a node: DRY_RUN=1 $HOME/JS-viewer-build/cleanup-viewer.sh

set -euo pipefail

VIEWER_HOME="${HOME}/JS-viewer-build"

# cron redirects into this directory; if it is missing the shell cannot open the
# >> target and the script never runs at all.
mkdir -p "${VIEWER_HOME}"

CLEANUP_TAG="cleanup-viewer"
# cleanup-common.sh ships next to this script in the repo, but this file is copied
# to ~/JS-viewer-build by the runner, so look in both places.
if [ -f "$(dirname "$0")/cleanup-common.sh" ]; then
    # shellcheck source=./cleanup-common.sh
    . "$(dirname "$0")/cleanup-common.sh"
else
    # shellcheck source=/dev/null
    . "${VIEWER_HOME}/cleanup-common.sh"
fi

# Any browser older than this is orphaned from a crashed session and leaks memory
# until the node OOMs. The default covers the longest bounded scenario
# (SingleReconnect, ~65min) with margin. On a soak node this MUST stay above
# VIEWER_SESSION_RECYCLE_SECONDS or the cron kills live soak browsers -- the
# default 2400s recycle is well under 5400, but raise this if you raise that.
CHROME_MAX_AGE_SEC="${CHROME_MAX_AGE_SEC:-5400}"

log "Starting cleanup"

# Dev server logs
find "${VIEWER_HOME}" -maxdepth 1 -name 'dev-server-*.log' -mmin +60 -delete 2>/dev/null || true

# Recorded viewer sessions, under <ws>/canary/webrtc-c/scripts/recordings/. A soak
# writes one per ~40min segment and verifies it at the end of that segment, so a
# 60min age floor never catches the file still being written.
find "${HOME}/Jenkins" -path '*/recordings/viewer-*' -mmin +60 -delete 2>/dev/null || true

# Screenshots from viewer tests
find "${HOME}/Jenkins" -name 'storage-session-active-*.png' -mmin +60 -delete 2>/dev/null || true

# Jenkins workspaces. Viewer stages use ws("${JOB_NAME}-${viewerId}-${BUILD_NUMBER}"),
# and the trailing * also covers the '@tmp' durable-task sibling.
reap_workspaces "${HOME}/Jenkins"/webrtc-*-[Vv]iewer*

# verify.py scratch (the biggest disk hog here: each verified session leaves a
# 150-300MB dir of extracted frames plus Tesseract scratch).
sweep_verify_scratch

# Puppeteer / Chromium crash dumps and temp profiles. A soak produces a fresh batch
# every recycle interval, which is why this wants a */5 schedule there.
find /tmp -maxdepth 1 -name 'puppeteer_dev_*' -mmin "+${SCRATCH_MAX_AGE_MIN}" -exec rm -rf {} + 2>/dev/null || true
find /tmp -maxdepth 1 -name 'chrome_crashpad*' -mmin "+${SCRATCH_MAX_AGE_MIN}" -exec rm -rf {} + 2>/dev/null || true
find /tmp -maxdepth 1 \( -name 'org.chromium.Chromium.*' -o -name '.org.chromium.Chromium.*' \
     -o -name '.com.google.Chrome.*' \) -mmin "+${SCRATCH_MAX_AGE_MIN}" -exec rm -rf {} + 2>/dev/null || true

# Orphan browsers. earlyoom is the real-time backstop for live memory pressure;
# this reaps slow leaks proactively.
for pid in $(pgrep -i 'chrome|chromium' 2>/dev/null || true); do
    etimes=$(ps -o etimes= -p "$pid" 2>/dev/null | tr -d ' ')
    [ -n "${etimes:-}" ] || continue
    if [ "$etimes" -gt "$CHROME_MAX_AGE_SEC" ]; then
        if [ "${DRY_RUN:-0}" = "1" ]; then
            log "DRY_RUN would kill orphan browser pid $pid (age ${etimes}s)"
        else
            log "Killing orphan browser pid $pid (age ${etimes}s)"
            kill -9 "$pid" 2>/dev/null || true
        fi
    fi
done

# Stale Chrome shared memory in /dev/shm (tmpfs = RAM). Only when no Chrome is
# running, so we never unlink a segment a live viewer still has open (which frees
# nothing and could break it). earlyoom handles live pressure; this reclaims tmpfs
# left behind by already-dead Chrome, which earlyoom cannot -- it kills processes,
# not orphaned files.
if ! pgrep -i chrom >/dev/null 2>&1; then
    if [ "${DRY_RUN:-0}" = "1" ]; then
        log "DRY_RUN would clear /dev/shm Chrome segments"
    else
        find /dev/shm -maxdepth 1 \( -name '.org.chromium.*' -o -name '.com.google.Chrome.*' \
             -o -name 'org.chromium.*' \) -exec rm -rf {} + 2>/dev/null || true
    fi
fi

log "Done"
