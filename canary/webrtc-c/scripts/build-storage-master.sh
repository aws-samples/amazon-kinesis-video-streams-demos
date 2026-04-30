#!/bin/bash
# build-storage-master.sh
#
# Builds the kvsWebrtcStorageSample binary in a persistent directory outside
# the Jenkins workspace. Skips the build entirely when neither the canary repo
# nor the webrtc-c dependency has changed since the last successful build.
#
# Persistent location: ~/webrtc-c-storage-master/
#   repo/   — full git clone used for building
#   build/  — cmake output including the binary and FetchContent deps
#   logs/   — build logs (last 10 kept)
#   .last-commit       — canary repo commit hash of last build
#   .webrtc-c-version  — webrtc-c GIT_TAG from CMakeLists.txt
#   .build.lock        — flock target
#
# Usage:
#   ./build-storage-master.sh <git_url> <git_hash> [openssl|mbedtls]
#
# Outputs the path to the built binary on stdout (last line).

set -euo pipefail

GIT_URL="${1:?Usage: build-storage-master.sh <git_url> <git_hash> [openssl|mbedtls]}"
GIT_HASH="${2:?Usage: build-storage-master.sh <git_url> <git_hash> [openssl|mbedtls]}"
TLS_BACKEND="${3:-openssl}"

BUILD_HOME="${HOME}/webrtc-c-storage-master"
REPO_DIR="${BUILD_HOME}/repo"
BUILD_DIR="${BUILD_HOME}/build"
LOGS_DIR="${BUILD_HOME}/logs"
LOCK_FILE="${BUILD_HOME}/.build.lock"
COMMIT_FILE="${BUILD_HOME}/.last-commit"
WEBRTC_VERSION_FILE="${BUILD_HOME}/.webrtc-c-version"

mkdir -p "$BUILD_HOME" "$LOGS_DIR"

# ---------------------------------------------------------------------------
# Acquire exclusive lock — blocks if another build is in progress
# ---------------------------------------------------------------------------
exec 9>"$LOCK_FILE"
echo "Acquiring build lock..."
flock 9
echo "Build lock acquired"

# ---------------------------------------------------------------------------
# 1. Clone or update the repo
# ---------------------------------------------------------------------------
if [ ! -d "$REPO_DIR/.git" ]; then
    echo "Initial clone of canary repo..."
    git clone "$GIT_URL" "$REPO_DIR" \
        || { echo "ERROR: git clone failed"; exit 1; }
    (cd "$REPO_DIR" && git checkout "$GIT_HASH") \
        || { echo "ERROR: git checkout failed"; exit 1; }
else
    echo "Updating canary repo..."
    (cd "$REPO_DIR" && git fetch origin '+refs/heads/*:refs/remotes/origin/*' && git checkout -f "$GIT_HASH") \
        || { echo "ERROR: git fetch/checkout failed"; exit 1; }
fi

CURRENT_COMMIT=$(cd "$REPO_DIR" && git rev-parse HEAD)
echo "Current commit: ${CURRENT_COMMIT:0:12}"

# ---------------------------------------------------------------------------
# 2. Extract webrtc-c dependency version from CMakeLists.txt
# ---------------------------------------------------------------------------
CURRENT_WEBRTC_VERSION=$(grep 'GIT_TAG' "$REPO_DIR/canary/webrtc-c/CMakeLists.txt" \
    | head -1 \
    | sed 's/.*GIT_TAG\s*//' | tr -d '[:space:]') || true
echo "Parsed webrtc-c GIT_TAG: '${CURRENT_WEBRTC_VERSION}'"

if [ -z "$CURRENT_WEBRTC_VERSION" ]; then
    echo "WARNING: Could not parse webrtc-c version from CMakeLists.txt, forcing rebuild"
    echo "DEBUG: GIT_TAG lines in CMakeLists.txt:"
    grep 'GIT_TAG' "$REPO_DIR/canary/webrtc-c/CMakeLists.txt" || echo "  (none found)"
    CURRENT_WEBRTC_VERSION="unknown-$(date +%s)"
fi
echo "webrtc-c dependency version: $CURRENT_WEBRTC_VERSION"

# ---------------------------------------------------------------------------
# 3. Check if rebuild is needed
# ---------------------------------------------------------------------------
CACHED_COMMIT=$(cat "$COMMIT_FILE" 2>/dev/null || echo "")
CACHED_WEBRTC_VERSION=$(cat "$WEBRTC_VERSION_FILE" 2>/dev/null || echo "")
BINARY_PATH="${BUILD_DIR}/kvsWebrtcStorageSample"

echo "Comparing: current commit=${CURRENT_COMMIT:0:12} vs cached commit=${CACHED_COMMIT:0:12}"
echo "Comparing: current webrtc-c=${CURRENT_WEBRTC_VERSION} vs cached webrtc-c=${CACHED_WEBRTC_VERSION}"

NEED_REBUILD=false
if [ ! -f "$BINARY_PATH" ]; then
    echo "Binary not found, rebuild needed"
    NEED_REBUILD=true
elif [ "$CURRENT_COMMIT" != "$CACHED_COMMIT" ]; then
    echo "Commit changed ($CACHED_COMMIT -> $CURRENT_COMMIT), rebuild needed"
    NEED_REBUILD=true
elif [ "$CURRENT_WEBRTC_VERSION" != "$CACHED_WEBRTC_VERSION" ]; then
    echo "webrtc-c version changed ($CACHED_WEBRTC_VERSION -> $CURRENT_WEBRTC_VERSION), rebuild needed"
    NEED_REBUILD=true
else
    echo "No changes detected, skipping build"
    echo "Binary ready at: $BINARY_PATH"
    # Release lock
    flock -u 9
    echo "$BINARY_PATH"
    exit 0
fi

# ---------------------------------------------------------------------------
# 4. Build (clean rebuild to avoid stale CMake cache / FetchContent artifacts)
# ---------------------------------------------------------------------------
BUILD_LOG="${LOGS_DIR}/build-$(date +%s).log"
echo "Building... (log: $BUILD_LOG)"

CMAKE_FLAGS="-DCMAKE_BUILD_TYPE=Debug -DCMAKE_INSTALL_PREFIX=${BUILD_DIR}"
if [ "$TLS_BACKEND" = "mbedtls" ]; then
    CMAKE_FLAGS="$CMAKE_FLAGS -DCANARY_USE_OPENSSL=OFF -DCANARY_USE_MBEDTLS=ON"
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

(
    cd "$BUILD_DIR"
    cmake "$REPO_DIR/canary/webrtc-c" $CMAKE_FLAGS
    make -j"$(nproc)"
) > "$BUILD_LOG" 2>&1

BUILD_EXIT=$?

if [ $BUILD_EXIT -ne 0 ]; then
    echo "ERROR: Build failed (exit code $BUILD_EXIT). See log: $BUILD_LOG"
    tail -50 "$BUILD_LOG"
    # Release lock
    flock -u 9
    exit 1
fi

# ---------------------------------------------------------------------------
# 5. Update stamps
# ---------------------------------------------------------------------------
echo "$CURRENT_COMMIT" > "$COMMIT_FILE"
echo "$CURRENT_WEBRTC_VERSION" > "$WEBRTC_VERSION_FILE"

# Clean up old logs (keep last 10)
ls -1t "$LOGS_DIR"/build-*.log 2>/dev/null | tail -n +11 | xargs rm -f 2>/dev/null || true

echo "Build successful"
echo "Binary ready at: $BINARY_PATH"

# Release lock
flock -u 9

echo "$BINARY_PATH"
