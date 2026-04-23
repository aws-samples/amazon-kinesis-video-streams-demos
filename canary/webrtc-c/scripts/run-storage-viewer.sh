#!/bin/bash
# run-storage-viewer.sh
#
# Starts the JS SDK dev server (if using a branch) and runs the chrome-headless
# viewer test. Call this AFTER prepare-storage-viewer.sh has completed and the
# storage master is ready.
#
# Expected env vars:
#   JOB_NAME, RUNNER_LABEL, AWS_DEFAULT_REGION, DURATION_IN_SECONDS
#   ENDPOINT (optional), METRIC_SUFFIX (optional), VIEWER_ID (optional)
#   JS_PAGE_URL (optional) — branch name or full URL

cd ./canary/webrtc-c/scripts || { echo "ERROR: Failed to change directory to ./canary/webrtc-c/scripts"; exit 1; }

# Activate Python venv (set up by prepare-storage-viewer.sh)
VENV_DIR="${HOME}/.venv/video-verify"
if [ -d "$VENV_DIR" ]; then
    source "$VENV_DIR/bin/activate"
fi

# Set environment variables for the test
export CANARY_CHANNEL_NAME="${JOB_NAME}-${RUNNER_LABEL}"
export AWS_REGION="${AWS_DEFAULT_REGION}"
export TEST_DURATION="${DURATION_IN_SECONDS}"

# CloudWatch Logs configuration — JS viewer logs go to a separate log group
export CANARY_LOG_GROUP_NAME="JSSDK"
export CANARY_LOG_STREAM_NAME="${RUNNER_LABEL}-${VIEWER_ID:-Viewer}-JSViewer-$(date +%s%3N)"

# Debug: Show what ENDPOINT value we received
echo "DEBUG: ENDPOINT env var = '${ENDPOINT}'"

# Set endpoint if provided (defaults to empty)
if [ -n "${ENDPOINT}" ]; then
    export ENDPOINT="${ENDPOINT}"
    echo "Using custom endpoint: ${ENDPOINT}"
else
    echo "No custom endpoint provided, using default"
fi

# Start JS SDK dev server if using a branch name
if [ -n "${JS_PAGE_URL}" ]; then
    if [[ "${JS_PAGE_URL}" != *"://"* ]]; then
        JS_SDK_DIR="/tmp/kvs-webrtc-js-sdk-${JS_PAGE_URL}"
        # Find a free port to avoid collisions with other processes
        JS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')
        echo "Selected free port: ${JS_PORT}"
        # Start the dev server in the background, logging output so we can detect readiness
        DEV_SERVER_LOG=$(mktemp /tmp/dev-server-XXXXXX.log)
        echo "Starting JS SDK dev server on port ${JS_PORT}..."
        (cd "$JS_SDK_DIR" && npm run develop -- --port "$JS_PORT" --host 127.0.0.1 --allowed-hosts all > "$DEV_SERVER_LOG" 2>&1) &
        DEV_SERVER_PID=$!
        # Wait for webpack to finish compiling and the server to start
        READY_MSG="compiled successfully"
        for i in $(seq 1 120); do
            if grep -q "$READY_MSG" "$DEV_SERVER_LOG" 2>/dev/null; then
                echo "Dev server ready on port ${JS_PORT} (took ${i}s)"
                break
            fi
            # Check if the process died
            if ! kill -0 "$DEV_SERVER_PID" 2>/dev/null; then
                echo "ERROR: Dev server process died. Log output:"
                cat "$DEV_SERVER_LOG"
                exit 1
            fi
            sleep 1
        done
        if ! grep -q "$READY_MSG" "$DEV_SERVER_LOG" 2>/dev/null; then
            echo "ERROR: Dev server failed to start within 120 seconds. Log output:"
            cat "$DEV_SERVER_LOG"
            kill "$DEV_SERVER_PID" 2>/dev/null
            exit 1
        fi
        export JS_PAGE_URL="http://localhost:${JS_PORT}/index.html"
        echo "Using local JS page: ${JS_PAGE_URL}"
    else
        export JS_PAGE_URL="${JS_PAGE_URL}"
        echo "Using custom JS page URL: ${JS_PAGE_URL}"
    fi
fi

# Set metric suffix if provided (defaults to empty)
if [ -n "${METRIC_SUFFIX}" ]; then
    export METRIC_SUFFIX="${METRIC_SUFFIX}"
    echo "Using metric suffix: ${METRIC_SUFFIX}"
fi

# Run storage viewer test
node chrome-headless.js || { echo "ERROR: Chrome headless test failed"; exit 1; }

# Clean up the dev server if we started one
if [ -n "$DEV_SERVER_PID" ]; then
    echo "Stopping JS SDK dev server (PID $DEV_SERVER_PID)..."
    kill "$DEV_SERVER_PID" 2>/dev/null
fi
