#!/bin/bash
# fetch-asset-set.sh
#
# Ensure a canary H.264 frame asset set is present at <asset-dir>/<set-name>/.
# Idempotent: skips download if the set is already present with the expected
# frame count. Default set ("h264SampleFrames") is a no-op because it ships
# in the git checkout.
#
# Usage:
#   fetch-asset-set.sh <set-name> [<asset-dir>]
#
# Env vars:
#   CANARY_ASSET_BUCKET  - required unless set-name is default or empty
#   CANARY_ASSET_PREFIX  - required unless set-name is default or empty
#                          S3 key prefix, e.g. "webrtc-canary/frame-sets/v1"
#   AWS_DEFAULT_REGION   - optional; uses aws-cli default if unset
#
# Exit codes:
#   0  success (or no-op for default set)
#   2  bad arguments
#   3  aws CLI missing
#   4  missing required env vars for a non-default set
#   5  S3 download failed
#   6  extract failed
#   7  post-extract verification failed (wrong frame count)

set -euo pipefail

# --- Constants matching Include.h ---
DEFAULT_ASSET_SET="h264SampleFrames"
EXPECTED_FRAME_COUNT=4676

# --- Args ---
SET_NAME="${1:-}"
ASSET_DIR="${2:-$(pwd)/assets}"

if [ -z "$SET_NAME" ]; then
    echo "usage: fetch-asset-set.sh <set-name> [<asset-dir>]" >&2
    exit 2
fi

# --- Default set is a no-op (git provides it) ---
if [ "$SET_NAME" = "$DEFAULT_ASSET_SET" ]; then
    echo "fetch-asset-set: '$SET_NAME' is the default set, nothing to fetch"
    exit 0
fi

TARGET_DIR="${ASSET_DIR}/${SET_NAME}"

# --- Skip if already present with correct frame count ---
if [ -d "$TARGET_DIR" ]; then
    n=$(find "$TARGET_DIR" -maxdepth 1 -name "frame-*.h264" | wc -l | tr -d ' ')
    if [ "$n" = "$EXPECTED_FRAME_COUNT" ]; then
        echo "fetch-asset-set: '$SET_NAME' already present at $TARGET_DIR ($n frames), skipping"
        exit 0
    fi
    echo "fetch-asset-set: '$SET_NAME' present but incomplete ($n/${EXPECTED_FRAME_COUNT} frames), refetching"
    rm -rf "$TARGET_DIR"
fi

# --- Validate environment for a real fetch ---
if ! command -v aws >/dev/null 2>&1; then
    echo "ERROR: aws CLI not found on PATH; cannot fetch '$SET_NAME'" >&2
    exit 3
fi

if [ -z "${CANARY_ASSET_BUCKET:-}" ]; then
    echo "ERROR: CANARY_ASSET_BUCKET must be set to fetch '$SET_NAME'" >&2
    exit 4
fi
if [ -z "${CANARY_ASSET_PREFIX:-}" ]; then
    echo "ERROR: CANARY_ASSET_PREFIX must be set to fetch '$SET_NAME'" >&2
    exit 4
fi

S3_URI="s3://${CANARY_ASSET_BUCKET}/${CANARY_ASSET_PREFIX}/${SET_NAME}.tar.gz"

mkdir -p "$ASSET_DIR"

# --- Download and extract (streamed; no intermediate file on disk) ---
echo "fetch-asset-set: downloading $S3_URI"
REGION_FLAG=""
if [ -n "${AWS_DEFAULT_REGION:-}" ]; then
    REGION_FLAG="--region ${AWS_DEFAULT_REGION}"
fi

# Use pipefail so a failed download aborts the extract too
if ! aws s3 cp $REGION_FLAG "$S3_URI" - | tar -xzf - -C "$ASSET_DIR"; then
    rc=$?
    echo "ERROR: fetch/extract failed for $S3_URI (rc=$rc)" >&2
    rm -rf "$TARGET_DIR"
    # Distinguish network vs unpack failures poorly here; return generic 5
    exit 5
fi

# --- Verify ---
if [ ! -d "$TARGET_DIR" ]; then
    echo "ERROR: tarball extracted but $TARGET_DIR does not exist" >&2
    exit 6
fi

n=$(find "$TARGET_DIR" -maxdepth 1 -name "frame-*.h264" | wc -l | tr -d ' ')
if [ "$n" != "$EXPECTED_FRAME_COUNT" ]; then
    echo "ERROR: '$SET_NAME' has $n frames, expected ${EXPECTED_FRAME_COUNT}" >&2
    exit 7
fi

# Confirm frame-0001 exists (IDR entry point the canary requires)
if [ ! -f "$TARGET_DIR/frame-0001.h264" ]; then
    echo "ERROR: '$SET_NAME' missing frame-0001.h264 (IDR entry point)" >&2
    exit 7
fi

echo "fetch-asset-set: '$SET_NAME' ready at $TARGET_DIR ($n frames)"
