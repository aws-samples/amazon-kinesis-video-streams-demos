#!/bin/bash
# setup-storage-viewer.sh
#
# Legacy wrapper that runs both prepare and run steps sequentially.
# Kept for backward compatibility. New code should call
# prepare-storage-viewer.sh and run-storage-viewer.sh separately.

SCRIPT_DIR="$(dirname "$0")"

"$SCRIPT_DIR/prepare-storage-viewer.sh" || exit 1
"$SCRIPT_DIR/run-storage-viewer.sh" || exit 1
