#!/bin/bash
# fetch-asset-set.sh
#
# Ensure one canary media asset is present under <asset-dir>, downloading it
# from S3 when it is missing or incomplete. The media is NOT kept in git; every
# node fetches it once into a persistent directory and later runs skip the
# download.
#
# Assets:
#   h264SampleFrames            default H.264 frame set   -> <asset-dir>/h264SampleFrames/frame-NNNN.h264
#   h264SampleFrames-<variant>  bitrate variant frame set -> <asset-dir>/h264SampleFrames-<variant>/frame-NNNN.h264
#   opusSampleFrames            Opus audio frame set      -> <asset-dir>/opusSampleFrames/sample-NNNN.opus
#   audio-source.wav            viewer fake-mic WAV       -> <asset-dir>/audio-source.wav
#
# S3 layout (frame sets are tarballs whose single top-level entry is the set
# directory; the WAV is stored as-is):
#   s3://<CANARY_ASSET_BUCKET>/<CANARY_ASSET_PREFIX>/<set-name>.tar.gz
#   s3://<CANARY_ASSET_BUCKET>/<CANARY_ASSET_PREFIX>/audio-source.wav
#
# Idempotent: an asset that is already present and passes verification
# (frame count + first frame, or SHA-256 for the WAV) is left alone. An
# incomplete asset is removed and re-fetched. Concurrent callers on the same
# node (parallel viewers / consumers) serialise on a per-asset flock, so only
# one of them downloads.
#
# Usage:
#   fetch-asset-set.sh <asset> [<asset-dir>]
#
# Env vars:
#   CANARY_ASSET_BUCKET  - required for a download
#   CANARY_ASSET_PREFIX  - required for a download, e.g. "webrtc-canary/frame-sets/v1"
#   CANARY_ASSET_REGION  - optional; region of the S3 bucket (may differ from
#                          the canary run's AWS_DEFAULT_REGION). Falls back to
#                          AWS_DEFAULT_REGION; if both are unset the aws CLI
#                          auto-detects.
#
# Exit codes:
#   0  asset present and verified (fetched now or already there)
#   2  bad arguments / unknown asset
#   3  aws CLI missing
#   4  missing required env vars for a download
#   5  S3 download failed
#   6  extract failed
#   7  post-fetch verification failed (wrong frame count / checksum)

set -euo pipefail

# --- Constants matching Include.h / verify.py ---
EXPECTED_H264_FRAME_COUNT=4676      # NUMBER_OF_H264_FRAME_FILES
EXPECTED_OPUS_FRAME_COUNT=7798      # NUMBER_OF_OPUS_FRAME_FILES
AUDIO_SOURCE_WAV="audio-source.wav" # 48 kHz mono 16-bit PCM, fed to Chrome's fake audio capture
AUDIO_SOURCE_WAV_SHA256="c0d544f60d293cb76a6db08f65379f3af4af506a274c994fe5ea12ae9841c057"

# --- Args ---
ASSET="${1:-}"
ASSET_DIR="${2:-$(pwd)/assets}"

if [ -z "$ASSET" ]; then
    echo "usage: fetch-asset-set.sh <asset> [<asset-dir>]" >&2
    exit 2
fi

# --- Classify the asset -----------------------------------------------------
# KIND=dir  : tarball in S3, verified by file count + first file
# KIND=file : plain object in S3, verified by SHA-256
case "$ASSET" in
    h264SampleFrames|h264SampleFrames-*)
        KIND="dir"; FILE_GLOB="frame-*.h264"; FIRST_FILE="frame-0001.h264"
        EXPECTED_COUNT="$EXPECTED_H264_FRAME_COUNT" ;;
    opusSampleFrames)
        KIND="dir"; FILE_GLOB="sample-*.opus"; FIRST_FILE="sample-0001.opus"
        EXPECTED_COUNT="$EXPECTED_OPUS_FRAME_COUNT" ;;
    "$AUDIO_SOURCE_WAV")
        KIND="file"; EXPECTED_SHA256="$AUDIO_SOURCE_WAV_SHA256" ;;
    *)
        echo "ERROR: unknown asset '$ASSET' (expected h264SampleFrames[-<variant>], opusSampleFrames or ${AUDIO_SOURCE_WAV})" >&2
        exit 2 ;;
esac

TARGET="${ASSET_DIR}/${ASSET}"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# Prints 0 (verified) or 1, and a human-readable reason on stderr.
verify_asset() {
    if [ "$KIND" = "dir" ]; then
        if [ ! -d "$TARGET" ]; then
            echo "missing" >&2; return 1
        fi
        local n
        n=$(find "$TARGET" -maxdepth 1 -name "$FILE_GLOB" | wc -l | tr -d ' ')
        if [ "$n" != "$EXPECTED_COUNT" ]; then
            echo "incomplete ($n/${EXPECTED_COUNT} files)" >&2; return 1
        fi
        if [ ! -f "$TARGET/$FIRST_FILE" ]; then
            echo "missing $FIRST_FILE (entry point the canary requires)" >&2; return 1
        fi
        return 0
    else
        if [ ! -f "$TARGET" ]; then
            echo "missing" >&2; return 1
        fi
        local sum
        sum=$(sha256_of "$TARGET")
        if [ "$sum" != "$EXPECTED_SHA256" ]; then
            echo "checksum mismatch (got ${sum:0:12}..., want ${EXPECTED_SHA256:0:12}...)" >&2; return 1
        fi
        return 0
    fi
}

mkdir -p "$ASSET_DIR"

# --- Serialise per asset: parallel stages on one node must not race on the
#     same cache entry. flock is standard on the Linux nodes; skip the lock
#     where it is unavailable (macOS dev boxes).
LOCK_FILE="${ASSET_DIR}/.fetch-${ASSET}.lock"
if command -v flock >/dev/null 2>&1; then
    exec 8>"$LOCK_FILE"
    flock 8
fi

# --- Fast path: already present and verified ---
if reason=$(verify_asset 2>&1); then
    echo "fetch-asset-set: '$ASSET' already present at $TARGET, skipping download"
    exit 0
fi
echo "fetch-asset-set: '$ASSET' at $TARGET is $reason, fetching"
rm -rf "$TARGET"

# --- Validate environment for a real fetch ---
if ! command -v aws >/dev/null 2>&1; then
    echo "ERROR: aws CLI not found on PATH; cannot fetch '$ASSET'" >&2
    exit 3
fi
if [ -z "${CANARY_ASSET_BUCKET:-}" ]; then
    echo "ERROR: CANARY_ASSET_BUCKET must be set to fetch '$ASSET' (the media is not in git)" >&2
    exit 4
fi
if [ -z "${CANARY_ASSET_PREFIX:-}" ]; then
    echo "ERROR: CANARY_ASSET_PREFIX must be set to fetch '$ASSET' (the media is not in git)" >&2
    exit 4
fi

if [ "$KIND" = "dir" ]; then
    S3_KEY="${CANARY_ASSET_PREFIX}/${ASSET}.tar.gz"
else
    S3_KEY="${CANARY_ASSET_PREFIX}/${ASSET}"
fi
S3_URI="s3://${CANARY_ASSET_BUCKET}/${S3_KEY}"

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
    echo "ERROR: head-object failed for ${S3_URI}" >&2
    echo "       - check IAM permissions on the caller identity above, region, or object existence" >&2
    exit 5
fi

# --- Download into a scratch location next to the target, verify, then move
#     into place atomically so a concurrent reader never sees a half-written
#     asset.
SCRATCH="$(mktemp -d "${ASSET_DIR}/.fetch-${ASSET}.XXXXXX")"
cleanup() { rm -rf "$SCRATCH"; }
trap cleanup EXIT

echo "fetch-asset-set: downloading $S3_URI"
if [ "$KIND" = "dir" ]; then
    # Streamed extract; no intermediate tarball on disk. Capture PIPESTATUS
    # into a local array *before* any other command runs, otherwise the very
    # next assignment resets PIPESTATUS.
    set +e
    aws s3 cp $REGION_FLAG "$S3_URI" - | tar -xzf - -C "$SCRATCH"
    pipe_status=("${PIPESTATUS[@]}")
    set -e
    aws_rc="${pipe_status[0]}"
    tar_rc="${pipe_status[1]}"
    if [ "$aws_rc" -ne 0 ] || [ "$tar_rc" -ne 0 ]; then
        echo "ERROR: fetch/extract failed for $S3_URI (aws rc=$aws_rc, tar rc=$tar_rc)" >&2
        exit 5
    fi
    if [ ! -d "$SCRATCH/$ASSET" ]; then
        echo "ERROR: tarball extracted but contains no top-level '$ASSET/' directory:" >&2
        ls -la "$SCRATCH" >&2 || true
        exit 6
    fi
    mv "$SCRATCH/$ASSET" "$TARGET"
else
    if ! aws s3 cp $REGION_FLAG "$S3_URI" "$SCRATCH/$ASSET"; then
        echo "ERROR: download failed for $S3_URI" >&2
        exit 5
    fi
    mv "$SCRATCH/$ASSET" "$TARGET"
fi

# --- Verify what landed ---
if ! reason=$(verify_asset 2>&1); then
    echo "ERROR: '$ASSET' failed verification after fetch: $reason" >&2
    rm -rf "$TARGET"
    exit 7
fi

if [ "$KIND" = "dir" ]; then
    echo "fetch-asset-set: '$ASSET' ready at $TARGET (${EXPECTED_COUNT} files)"
else
    echo "fetch-asset-set: '$ASSET' ready at $TARGET (sha256 ${EXPECTED_SHA256:0:12}...)"
fi
