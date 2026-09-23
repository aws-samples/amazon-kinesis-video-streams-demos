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
    (cd "$REPO_DIR" && git fetch origin '+refs/heads/*:refs/remotes/origin/*' && git checkout -f "$GIT_HASH" && git reset --hard "origin/$GIT_HASH" 2>/dev/null || true) \
        || { echo "ERROR: git fetch/checkout failed"; exit 1; }
fi

CURRENT_COMMIT=$(cd "$REPO_DIR" && git rev-parse HEAD)
echo "Current commit: ${CURRENT_COMMIT:0:12}"

# ---------------------------------------------------------------------------
# 2. Extract webrtc-c dependency version from CMakeLists.txt
# ---------------------------------------------------------------------------
WEBRTC_GIT_TAG=$(grep 'GIT_TAG' "$REPO_DIR/canary/webrtc-c/CMakeLists.txt" \
    | head -1 \
    | sed 's/.*GIT_TAG\s*//' | tr -d '[:space:]') || true
echo "Parsed webrtc-c GIT_TAG: '${WEBRTC_GIT_TAG}'"

if [ -z "$WEBRTC_GIT_TAG" ]; then
    echo "WARNING: Could not parse webrtc-c version from CMakeLists.txt, forcing rebuild"
    echo "DEBUG: GIT_TAG lines in CMakeLists.txt:"
    grep 'GIT_TAG' "$REPO_DIR/canary/webrtc-c/CMakeLists.txt" || echo "  (none found)"
    CURRENT_WEBRTC_VERSION="unknown-$(date +%s)"
else
    # Resolve branch/tag names to actual commit SHA so we detect upstream changes
    WEBRTC_REPO_URL=$(grep 'GIT_REPOSITORY' "$REPO_DIR/canary/webrtc-c/CMakeLists.txt" \
        | head -1 \
        | sed 's/.*GIT_REPOSITORY\s*//' | tr -d '[:space:]') || true
    if [ -n "$WEBRTC_REPO_URL" ]; then
        RESOLVED_SHA=$(git ls-remote "$WEBRTC_REPO_URL" "$WEBRTC_GIT_TAG" 2>/dev/null | awk '{print $1}' | head -1)
        if [ -n "$RESOLVED_SHA" ]; then
            CURRENT_WEBRTC_VERSION="$RESOLVED_SHA"
            echo "Resolved webrtc-c '${WEBRTC_GIT_TAG}' to commit: ${RESOLVED_SHA:0:12}"
        else
            # GIT_TAG might be a commit SHA already, use as-is
            CURRENT_WEBRTC_VERSION="$WEBRTC_GIT_TAG"
            echo "Could not resolve '${WEBRTC_GIT_TAG}' via ls-remote, using as-is"
        fi
    else
        CURRENT_WEBRTC_VERSION="$WEBRTC_GIT_TAG"
        echo "Could not parse GIT_REPOSITORY URL, using GIT_TAG as-is"
    fi
fi
echo "webrtc-c dependency version: $CURRENT_WEBRTC_VERSION"

# ---------------------------------------------------------------------------
# 3. Check if rebuild is needed
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# Derive GStreamer support from the job's runtime media source: any non-disk
# CANARY_MEDIA_SOURCE (devicesrc / testsrc / rtspsrc) requires the GStreamer
# build. ENABLE_GST_MEDIA_SOURCE, if set explicitly, overrides the derivation.
# ---------------------------------------------------------------------------
if [ -z "${ENABLE_GST_MEDIA_SOURCE:-}" ]; then
    MEDIA_SOURCE="${CANARY_MEDIA_SOURCE:-disk}"
    if [ "$MEDIA_SOURCE" != "disk" ]; then
        ENABLE_GST_MEDIA_SOURCE=ON
    else
        ENABLE_GST_MEDIA_SOURCE=OFF
    fi
fi
echo "GStreamer media source: ${ENABLE_GST_MEDIA_SOURCE} (CANARY_MEDIA_SOURCE='${CANARY_MEDIA_SOURCE:-}')"
CURRENT_BUILD_FLAGS="gst=${ENABLE_GST_MEDIA_SOURCE}"
BUILD_FLAGS_FILE="${BUILD_HOME}/.build-flags"

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
elif [ "$CURRENT_BUILD_FLAGS" != "$(cat "$BUILD_FLAGS_FILE" 2>/dev/null || echo "")" ]; then
    echo "Build flags changed ($(cat "$BUILD_FLAGS_FILE" 2>/dev/null || echo "<none>") -> $CURRENT_BUILD_FLAGS), rebuild needed"
    NEED_REBUILD=true
else
    echo "No changes detected, skipping build"
fi

# ---------------------------------------------------------------------------
# 4. Build (clean rebuild to avoid stale CMake cache / FetchContent artifacts)
#    Only runs when NEED_REBUILD=true; otherwise fall through to asset fetch.
# ---------------------------------------------------------------------------
if [ "$NEED_REBUILD" = "true" ]; then
    BUILD_LOG="${LOGS_DIR}/build-$(date +%s).log"
    echo "Building... (log: $BUILD_LOG)"

    CMAKE_FLAGS="-DCMAKE_BUILD_TYPE=Debug -DCMAKE_INSTALL_PREFIX=${BUILD_DIR}"
    # GStreamer media source, derived above from CANARY_MEDIA_SOURCE
    CMAKE_FLAGS="$CMAKE_FLAGS -DENABLE_GST_MEDIA_SOURCE=${ENABLE_GST_MEDIA_SOURCE}"
    if [ "$TLS_BACKEND" = "mbedtls" ]; then
        CMAKE_FLAGS="$CMAKE_FLAGS -DCANARY_USE_OPENSSL=OFF -DCANARY_USE_MBEDTLS=ON"
    fi

    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"

    # Build only the storage master target. The canary signaling/webrtc
    # executables are not used by the storage scenario, and building them
    # pulls in symbols (writeFirstFrameSentTimeToFile,
    # calculateDisconnectToFrameSentTime) that are only defined in the storage
    # sample source — so a full `make` fails at link time on those two targets
    # even though kvsWebrtcStorageSample links fine. Restrict the build to the
    # one target we run.
    (
        cd "$BUILD_DIR"
        cmake "$REPO_DIR/canary/webrtc-c" $CMAKE_FLAGS
        make -j"$(nproc)" kvsWebrtcStorageSample
    ) > "$BUILD_LOG" 2>&1

    BUILD_EXIT=$?

    if [ $BUILD_EXIT -ne 0 ]; then
        echo "ERROR: Build failed (exit code $BUILD_EXIT). See log: $BUILD_LOG"
        tail -50 "$BUILD_LOG"
        # Release lock
        flock -u 9
        exit 1
    fi

    # -----------------------------------------------------------------------
    # 4b. Post-build smoke test — a build can "succeed" yet produce corrupt
    #     artifacts (observed once: garbage pages inside libusrsctp.a made
    #     initKvsWebRtc die with SIGILL, and the stamp cache then reused the
    #     poisoned binary on every run). Launch the binary with dummy creds:
    #     it must survive WebRTC/SCTP init. Signaling failures afterwards are
    #     expected (creds are fake) and exit with a normal status; death by
    #     signal (exit >= 128, e.g. 132=SIGILL, 139=SIGSEGV) means the
    #     artifacts are corrupt. In that case we do NOT write the stamps, so
    #     the next invocation rebuilds from scratch instead of caching junk.
    # -----------------------------------------------------------------------
    SMOKE_LOG="${LOGS_DIR}/smoke-$(date +%s).log"
    echo "Running post-build smoke test... (log: $SMOKE_LOG)"
    SMOKE_RC=0
    (
        cd "$BUILD_DIR"
        env AWS_ACCESS_KEY_ID=smoke-test AWS_SECRET_ACCESS_KEY=smoke-test \
            AWS_SESSION_TOKEN=smoke-test AWS_DEFAULT_REGION=us-east-1 \
            CANARY_CHANNEL_NAME=smoke-test CANARY_LABEL=StorageWithViewer \
            CANARY_LOG_GROUP_NAME=WebrtcSDK CANARY_DURATION_IN_SECONDS=5 \
            CANARY_IS_MASTER=TRUE \
            timeout 30 ./kvsWebrtcStorageSample
    ) > "$SMOKE_LOG" 2>&1 || SMOKE_RC=$?
    if [ "$SMOKE_RC" -ge 128 ]; then
        echo "ERROR: smoke test died with signal $((SMOKE_RC - 128)) (exit $SMOKE_RC) — build artifacts look corrupt"
        echo "Refusing to cache this build; next invocation will rebuild from scratch."
        tail -20 "$SMOKE_LOG"
        # Release lock
        flock -u 9
        exit 1
    fi
    echo "Smoke test passed (exit $SMOKE_RC)"

    # -----------------------------------------------------------------------
    # 5. Update stamps
    # -----------------------------------------------------------------------
    echo "$CURRENT_COMMIT" > "$COMMIT_FILE"
    echo "$CURRENT_WEBRTC_VERSION" > "$WEBRTC_VERSION_FILE"
    echo "$CURRENT_BUILD_FLAGS" > "$BUILD_FLAGS_FILE"

    # Pin the fresh artifacts and stamps to disk immediately. Without this, a
    # power loss during the kernel writeback window leaves "valid" stamps
    # pointing at a binary with holes in it (metadata is journaled, file data
    # is not).
    sync

    # Clean up old logs (keep last 10)
    ls -1t "$LOGS_DIR"/build-*.log 2>/dev/null | tail -n +11 | xargs rm -f 2>/dev/null || true

    echo "Build successful"
fi

# ---------------------------------------------------------------------------
# 6. Fetch optional bitrate-variant asset set (idempotent, runs every invocation)
#    - Default set ("h264SampleFrames") ships in the git checkout; the fetch
#      script no-ops for it.
#    - A CMake rebuild wipes ${BUILD_DIR}, so previously fetched sets need
#      re-fetching; that's why this runs unconditionally, not only on rebuild.
# ---------------------------------------------------------------------------
FETCH_SCRIPT="${REPO_DIR}/canary/webrtc-c/scripts/fetch-asset-set.sh"
REQUESTED_ASSET_SET="${CANARY_ASSET_SET:-}"
if [ -n "$REQUESTED_ASSET_SET" ]; then
    if [ ! -x "$FETCH_SCRIPT" ]; then
        chmod +x "$FETCH_SCRIPT" 2>/dev/null || true
    fi
    if ! "$FETCH_SCRIPT" "$REQUESTED_ASSET_SET" "${BUILD_DIR}/assets"; then
        echo "ERROR: fetch-asset-set.sh failed for '$REQUESTED_ASSET_SET'"
        # Release lock
        flock -u 9
        exit 1
    fi
else
    echo "No CANARY_ASSET_SET requested, using default asset set from git checkout"
fi

echo "Binary ready at: $BINARY_PATH"

# Release lock
flock -u 9

echo "$BINARY_PATH"
