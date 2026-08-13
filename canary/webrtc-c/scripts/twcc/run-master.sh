#!/bin/bash
# =============================================================================
# run-master.sh - Run the adapting GStreamer master INSIDE the kvsns namespace,
# teeing its stdout/stderr to a timestamped log for analyze-twcc-log.py.
#
# PREREQ: netns_router.sh must be UP, and you must have exported the credentials
# + endpoint the sample needs to reach the GAMMA media service, e.g.:
#
#   export AWS_ACCESS_KEY_ID=...      AWS_SECRET_ACCESS_KEY=...
#   export AWS_SESSION_TOKEN=...      AWS_DEFAULT_REGION=us-west-2
#   export AWS_KVS_LOG_LEVEL=2        # 2 = DEBUG; needed for the BWE / TWCC lines
#   # plus whatever points the SDK at gamma (e.g. CONTROL_PLANE_URI=...)
#
# The values are baked into the command BEFORE sudo, so this works regardless of
# whether sudoers strips the environment.
#
# Usage:
#   BIN=~/twcc-sdk/build/samples/kvsWebrtcStorageVideoOnlyMasterGstSample \
#     ./run-master.sh <channelName> [testsrc]
# =============================================================================
set -euo pipefail

NS=${NS:-kvsns}
CHANNEL=${1:?usage: run-master.sh <channelName> [testsrc]}
SRC=${2:-testsrc}
BIN=${BIN:?set BIN=/path/to/kvsWebrtcStorageVideoOnlyMasterGstSample}
LOGDIR=${LOGDIR:-$HOME/twcc-logs}
mkdir -p "$LOGDIR"
LOG="$LOGDIR/master-$(date +%Y%m%d-%H%M%S).log"

# Sanity: is the namespace up?
if ! ip netns list | grep -qw "$NS"; then
  echo "ERROR: netns '$NS' not found. Run: sudo ./netns_router.sh up"; exit 1
fi

echo "channel=$CHANNEL src=$SRC bin=$BIN"
echo "log=$LOG"
echo "Streaming inside netns '$NS'. Ctrl+C to stop. Start the throttle in another shell:"
echo "  sudo LOSS=0 ./netns_profiles.sh start"
echo

# Pass creds/endpoint explicitly (expanded by THIS shell before sudo runs).
sudo ip netns exec "$NS" env \
  AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-}" \
  AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-}" \
  AWS_SESSION_TOKEN="${AWS_SESSION_TOKEN:-}" \
  AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-west-2}" \
  AWS_KVS_LOG_LEVEL="${AWS_KVS_LOG_LEVEL:-2}" \
  ${CONTROL_PLANE_URI:+CONTROL_PLANE_URI="$CONTROL_PLANE_URI"} \
  ${AWS_KVS_CACERT_PATH:+AWS_KVS_CACERT_PATH="$AWS_KVS_CACERT_PATH"} \
  stdbuf -oL -eL "$BIN" "$CHANNEL" "$SRC" 2>&1 | tee "$LOG"
