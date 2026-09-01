#!/bin/bash
# cleanup-common.sh — shared helpers for cleanup-{master,consumer,viewer}.sh
#
# Sourced, never run directly. Holds the two pieces that MUST behave identically
# on every role: the workspace reaper and the /tmp scratch sweep. Keeping them in
# one file is deliberate -- three hand-maintained copies of the reaper is how the
# viewer script silently went stale before, and the reaper is the one place where
# a bug deletes a live build instead of just leaking disk.
#
# ---------------------------------------------------------------------------
# Workspace reaping safety
# ---------------------------------------------------------------------------
# A bounded canary run is 156s-65min, so "older than an hour" is a fine proxy for
# "dead". A soak run is unbounded, which inverts that: its workspace is the OLDEST
# one on the node and the one that must never be touched. Age alone can therefore
# never be the deciding signal, so the reaper takes three independent vetoes and
# deletes only when all three agree the workspace is dead:
#
#   1. REAP_WORKSPACES=0            -- config. Set on soak-dedicated nodes.
#   2. node hosts a long-running    -- STRUCTURAL. Independent of config, so a
#      durable step (> LONG_RUN_MIN)   soak survives even if (1) was forgotten in
#      -> skip ALL reaping this tick   the crontab. Never fires on a bounded node,
#                                      where no scenario reaches 180 min.
#   3. per-workspace: .in_use is    -- ordinary short-lived protection.
#      fresh, OR its own durable
#      step is still alive
#
# "durable step still alive" needs both a filesystem and a process signal:
#   - <ws>@tmp/durable-XXXX/ has no jenkins-result.txt  (step hasn't finished), AND
#   - pgrep -f "<that durable dir>" matches             (it really is still running)
# The pgrep here is sound because Jenkins' durable-task launches the step as
#   sh -xe <ws>@tmp/durable-XXXX/script.sh
# so the control-dir path is guaranteed to be in that wrapper's command line. This
# is NOT the same as matching the workspace path against arbitrary processes: the
# master binary runs as `./kvsWebrtcStorageSample` from ~/webrtc-c-storage-master/
# build and the consumer as `java -classpath target/...`, so neither carries the
# workspace path at all, and pgrep can only ever see a command line -- never a cwd.
# Requiring the result file too means a crashed/rebooted build's stale control dir
# stops vetoing once its wrapper is gone, instead of protecting the dir forever.
#
# If jenkins-result.txt is ever renamed upstream, every durable dir reads as "live"
# and nothing is reaped -- a disk leak, not a deletion. The failure direction is
# deliberately safe.
#
# Env knobs (all optional):
#   REAP_WORKSPACES     1   0 disables workspace reaping entirely (soak nodes)
#   LONG_RUN_MIN      180   a durable step older than this marks the node "busy"
#   IN_USE_MAX_AGE_MIN 120  .in_use younger than this protects its workspace
#   WS_MIN_AGE_MIN     60   never consider a workspace younger than this
#   SCRATCH_MAX_AGE_MIN 15  /tmp scratch older than this is swept
#   DRY_RUN             0   1 logs what it would delete and deletes nothing

# shellcheck shell=bash

REAP_WORKSPACES="${REAP_WORKSPACES:-1}"
# INVARIANT: LONG_RUN_MIN < IN_USE_MAX_AGE_MIN.
# The three vetoes hand off to each other over time, and the handoff must not leave
# a gap. Consider a soak on a node where REAP_WORKSPACES=0 was forgotten, and the
# pipeline's TOP-LEVEL agent workspace: no sh step runs there for the whole soak, so
# it has no durable dir and veto (3a) can never see it, and its .in_use was touched
# once at the Fetch STS stage and is never refreshed. Its only protections are
# .in_use freshness (expires at IN_USE_MAX_AGE_MIN) and the node-wide veto (starts at
# LONG_RUN_MIN). If LONG_RUN_MIN were the larger of the two, that workspace would be
# unprotected for the minutes in between -- 60 of them at the original 180/120.
# 90 < 120 closes it. 90 is still well above every bounded scenario (~65min), and a
# false positive here only costs one skipped tick.
LONG_RUN_MIN="${LONG_RUN_MIN:-90}"
IN_USE_MAX_AGE_MIN="${IN_USE_MAX_AGE_MIN:-120}"
WS_MIN_AGE_MIN="${WS_MIN_AGE_MIN:-60}"
SCRATCH_MAX_AGE_MIN="${SCRATCH_MAX_AGE_MIN:-15}"
DRY_RUN="${DRY_RUN:-0}"

CLEANUP_TAG="${CLEANUP_TAG:-cleanup}"

log() { echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') [${CLEANUP_TAG}] $*"; }

# --- rm -rf with hard guards. Refuses anything outside $HOME/Jenkins/. ---------
safe_rmrf() {
    local target="$1"
    case "$HOME" in
        ''|'/') log "REFUSING: HOME is '${HOME}'"; return 1 ;;
    esac
    case "$target" in
        *..*)                 log "REFUSING (contains ..): $target"; return 1 ;;
        "${HOME}/Jenkins"|"${HOME}/Jenkins/") log "REFUSING (is the Jenkins root): $target"; return 1 ;;
        "${HOME}/Jenkins/"*)  : ;;
        *)                    log "REFUSING (outside \$HOME/Jenkins): $target"; return 1 ;;
    esac
    [ -L "$target" ] && { log "REFUSING (symlink): $target"; return 1; }
    [ -d "$target" ] || { log "REFUSING (not a directory): $target"; return 1; }
    if [ "$DRY_RUN" = "1" ]; then
        log "DRY_RUN would remove: $target"
        return 0
    fi
    log "Removing: $target"
    rm -rf "$target"
}

# --- Is a durable-task step in this control dir still running? ----------------
# $1 = a <ws>@tmp/durable-XXXX directory
durable_dir_live() {
    local d="$1"
    [ -d "$d" ] || return 1
    # Jenkins writes jenkins-result.txt only when the step completes.
    [ -f "$d/jenkins-result.txt" ] && return 1
    # Unfinished on disk -- confirm the wrapper process is actually alive, so a
    # crashed build's leftover control dir does not veto reaping forever.
    pgrep -f "$d" > /dev/null 2>&1
}

# --- Does this workspace have a live step of its own? -------------------------
# $1 = base workspace path (no @tmp suffix)
workspace_busy() {
    local base="$1" d
    for d in "${base}@tmp"/durable-*; do
        durable_dir_live "$d" && return 0
    done
    return 1
}

# --- STRUCTURAL soak guard: is this node hosting any long-running step? --------
# Independent of REAP_WORKSPACES, so a misconfigured soak node is still safe.
# A durable control dir's mtime is effectively its creation time (nothing is added
# to the dir after setup), so -mmin +N means "this step started more than N minutes
# ago"; combined with durable_dir_live that is "has been running more than N".
node_has_long_running_step() {
    local d
    while IFS= read -r d; do
        [ -n "$d" ] || continue
        if durable_dir_live "$d"; then
            log "long-running step still active: $d"
            return 0
        fi
    done <<EOF
$(find "${HOME}/Jenkins" -maxdepth 3 -type d -name 'durable-*' -mmin "+${LONG_RUN_MIN}" 2>/dev/null)
EOF
    return 1
}

# --- The reaper. $@ = glob patterns of candidate workspace dirs ----------------
reap_workspaces() {
    if [ "$REAP_WORKSPACES" != "1" ]; then
        log "REAP_WORKSPACES=${REAP_WORKSPACES} -- workspace reaping disabled (soak-dedicated node)"
        return 0
    fi
    # Enforce the handoff invariant rather than trusting whoever overrode these.
    # Clamping (not aborting) keeps cleanup working while making the mistake visible.
    if [ "$LONG_RUN_MIN" -ge "$IN_USE_MAX_AGE_MIN" ]; then
        log "WARNING: LONG_RUN_MIN=${LONG_RUN_MIN} >= IN_USE_MAX_AGE_MIN=${IN_USE_MAX_AGE_MIN} leaves an unprotected window; clamping LONG_RUN_MIN to $((IN_USE_MAX_AGE_MIN - 30))"
        LONG_RUN_MIN=$((IN_USE_MAX_AGE_MIN - 30))
    fi
    if node_has_long_running_step; then
        log "node is hosting a build older than ${LONG_RUN_MIN}min -- skipping ALL workspace reaping this tick"
        return 0
    fi

    local dir base
    for dir in "$@"; do
        [ -d "$dir" ] || continue
        # Too young to be stale under any circumstances.
        find "$dir" -maxdepth 0 -mmin "+${WS_MIN_AGE_MIN}" | grep -q . || continue
        # '<ws>@tmp' is Jenkins' durable-task control dir and carries no .in_use of
        # its own; resolve it back to the base workspace for both checks.
        base="${dir%@tmp}"
        if workspace_busy "$base"; then
            log "Skipping (live durable step): $dir"
            continue
        fi
        if [ -f "$base/.in_use" ] && find "$base/.in_use" -mmin "-${IN_USE_MAX_AGE_MIN}" | grep -q .; then
            log "Skipping (.in_use is fresh): $dir"
            continue
        fi
        log "Stale workspace (no live step, no fresh .in_use): $dir"
        safe_rmrf "$dir" || true
    done
}

# --- /tmp scratch produced by verify.py and the soak stream verifier -----------
# Frequency matters more than the threshold here: a soak's consumer creates one
# 150-300MB video-verify-* dir every 60s (one per verified segment), so an hourly
# sweep can leave 60 of them standing. Soak nodes run this on a */5 schedule.
sweep_verify_scratch() {
    find /tmp -maxdepth 1 -name 'video-verify-*' -type d -mmin "+${SCRATCH_MAX_AGE_MIN}" -exec rm -rf {} + 2>/dev/null || true
    find /tmp -maxdepth 1 -name 'tess_*' -mmin "+${SCRATCH_MAX_AGE_MIN}" -exec rm -rf {} + 2>/dev/null || true
    # SoakStreamVerifier's GetMedia segment spool (Files.createTempDirectory
    # "soak-verify-spool"). It deletes each seg_*.mp4 after verifying and drops the
    # oldest when the backlog exceeds 5, so the live dir stays small -- but the dir
    # itself plus ffmpeg.log leaks on every consumer crash, and nothing else on the
    # node matches this glob. Only reap spools with no live consumer holding them.
    local d
    for d in /tmp/soak-verify-spool*; do
        [ -d "$d" ] || continue
        find "$d" -maxdepth 0 -mmin "+${SCRATCH_MAX_AGE_MIN}" | grep -q . || continue
        if pgrep -f 'WebrtcStorageCanaryConsumer' > /dev/null 2>&1; then
            continue
        fi
        log "Removing orphaned soak spool: $d"
        [ "$DRY_RUN" = "1" ] && { log "DRY_RUN would remove: $d"; continue; }
        rm -rf "$d" 2>/dev/null || true
    done
}
