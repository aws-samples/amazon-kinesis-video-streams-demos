#!/bin/bash
# =============================================================================
# build-twcc-sample.sh - Build the SDK's GStreamer storage master sample on the
# Pi, with the encoder floor lowered so the 250 kbps BAD profile is honored.
#
# WHY this binary (and not the canary's kvsWebrtcStorageSample):
#   The canary streams FIXED pre-encoded frames from disk (no encoder to adapt)
#   and its TWCC callback only logs. The SDK sample kvsWebrtcStorageVideoOnly-
#   MasterGstSample is the one that actually wires TWCC feedback into the live
#   x264enc via g_object_set (samples/common/GstMedia.c) - it is the adapting
#   sender we need to make the media service's TWCC feedback observable.
#
# Run as your SUDO-USER (e.g. yuqi), NOT as jenkins. Uses sudo only for apt.
#
# Usage:
#   ./build-twcc-sample.sh                 # clone @rr-extension, floor->100, build
#   SDK_DIR=~/twcc-sdk FLOOR_KBPS=100 ./build-twcc-sample.sh
#   SKIP_APT=1 ./build-twcc-sample.sh      # skip the apt install step
#
# Pi 5 has NO hardware H.264 encoder -> x264enc runs in software on 4 cores.
# The full build (deps from source) takes ~20-40 min, like the canary build.
# =============================================================================
set -euo pipefail

SDK_REPO=${SDK_REPO:-https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-c}
SDK_TAG=${SDK_TAG:-rr-extension}          # branch the canary already fetches (CMakeLists.txt:12-16)
SDK_DIR=${SDK_DIR:-$HOME/twcc-sdk}
FLOOR_KBPS=${FLOOR_KBPS:-100}             # MIN_VIDEO_BITRATE_KBPS; doc used 100 to reach the 250k BAD profile
BIN=kvsWebrtcStorageVideoOnlyMasterGstSample

echo "== 1/5 deps =="
if [ "${SKIP_APT:-0}" != "1" ]; then
  sudo apt-get update
  # toolchain + GStreamer (x264enc lives in plugins-ugly; needs libx264)
  sudo apt-get install -y \
    git cmake build-essential pkg-config m4 \
    libssl-dev \
    gstreamer1.0-tools libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
    gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly gstreamer1.0-libav
fi
# Sanity: the sample's `testsrc` path uses x264enc. If this fails, the build will
# still succeed but the pipeline won't launch at runtime.
if ! gst-inspect-1.0 x264enc >/dev/null 2>&1; then
  echo "WARN: x264enc not found (gstreamer1.0-plugins-ugly + libx264). testsrc will fail at runtime."
fi

echo "== 2/5 clone $SDK_TAG =="
if [ ! -d "$SDK_DIR/.git" ]; then
  git clone --depth 1 --branch "$SDK_TAG" "$SDK_REPO" "$SDK_DIR"
else
  echo "  $SDK_DIR already a git repo; leaving as-is (delete it to re-clone)."
fi

echo "== 3/5 lower encoder floor to ${FLOOR_KBPS} kbps =="
SAMPLES_H="$SDK_DIR/samples/common/Samples.h"
# Robust to whitespace; idempotent (no-op if already patched).
sed -i -E "s/(#define[[:space:]]+MIN_VIDEO_BITRATE_KBPS[[:space:]]+)[0-9]+/\1${FLOOR_KBPS}/" "$SAMPLES_H"
echo "  now: $(grep -n 'MIN_VIDEO_BITRATE_KBPS' "$SAMPLES_H")"

echo "== 4/5 build (this is the slow part) =="
mkdir -p "$SDK_DIR/build"
cd "$SDK_DIR/build"
cmake .. 2>&1 | tee cmake.log
# The GStreamer samples only build if pkg-config found gstreamer-1.0.
if ! grep -q "Will build gstreamer samples" cmake.log; then
  echo "ERROR: cmake did NOT detect GStreamer - the gst sample will not be built."
  echo "       Install the -dev packages (step 1) and re-run."
  exit 1
fi
make -j"$(nproc)" "$BIN"

echo "== 5/5 verify binary =="
BIN_PATH="$SDK_DIR/build/samples/$BIN"
ls -la "$BIN_PATH"
# Just confirm it isn't a segfault (139); a usage/arg exit is fine.
"$BIN_PATH" 2>&1 | head -3 || true
echo
echo "DONE. Binary: $BIN_PATH"
echo "Next: bring the router up, then run it in the namespace (see README.md):"
echo "  sudo ./netns_router.sh up"
echo "  BIN=$BIN_PATH ./run-master.sh <channelName>"
