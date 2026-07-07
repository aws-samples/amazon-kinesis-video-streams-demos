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
#   CANARY_ASSET_REGION  - optional; region of the S3 bucket (may differ
#                          from the canary run's AWS_DEFAULT_REGION).
#                          Falls back to AWS_DEFAULT_REGION if unset.
#                          If both are unset, the aws CLI auto-detects.
#   AWS_DEFAULT_REGION   - optional fallback for CANARY_ASSET_REGION
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
S3_KEY="${CANARY_ASSET_PREFIX}/${SET_NAME}.tar.gz"

mkdir -p "$ASSET_DIR"

# Prefer bucket-specific region, fall back to the canary's operational region.
# If neither is set, drop --region and let the aws CLI auto-detect via
# S3's PermanentRedirect handling.
ASSET_REGION="${CANARY_ASSET_REGION:-${AWS_DEFAULT_REGION:-}}"
REGION_FLAG=""
if [ -n "$ASSET_REGION" ]; then
    REGION_FLAG="--region ${ASSET_REGION}"
fi

# --- Diagnostics: log the identity and preflight the object so failures
#     are self-explanatory in the build log ---
echo "fetch-asset-set: --- diagnostics ---"
echo "fetch-asset-set: bucket=${CANARY_ASSET_BUCKET}"
echo "fetch-asset-set: key=${S3_KEY}"
echo "fetch-asset-set: asset-region=${ASSET_REGION:-<unset, CLI auto-detects>}"
echo "fetch-asset-set: (CANARY_ASSET_REGION=${CANARY_ASSET_REGION:-<unset>}, AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION:-<unset>})"
echo "fetch-asset-set: caller identity:"
aws sts get-caller-identity 2>&1 | sed 's/^/    /' || true
echo "fetch-asset-set: head-object preflight:"
if head_out=$(aws s3api head-object $REGION_FLAG --bucket "$CANARY_ASSET_BUCKET" --key "$S3_KEY" 2>&1); then
    echo "$head_out" | sed 's/^/    /'
    echo "fetch-asset-set: head-object OK, proceeding with download"
else
    echo "$head_out" | sed 's/^/    /' >&2
    echo "ERROR: head-object failed for s3://${CANARY_ASSET_BUCKET}/${S3_KEY}" >&2
    echo "       — check IAM permissions on the caller identity above, region, or object existence" >&2
    exit 5
fi

# --- Download and extract (streamed; no intermediate file on disk) ---
echo "fetch-asset-set: downloading $S3_URI"
set +e
aws s3 cp $REGION_FLAG "$S3_URI" - | tar -xzf - -C "$ASSET_DIR"
aws_rc=${PIPESTATUS[0]}
tar_rc=${PIPESTATUS[1]}
set -e
if [ "$aws_rc" -ne 0 ] || [ "$tar_rc" -ne 0 ]; then
    echo "ERROR: fetch/extract failed for $S3_URI (aws rc=$aws_rc, tar rc=$tar_rc)" >&2
    rm -rf "$TARGET_DIR"
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
