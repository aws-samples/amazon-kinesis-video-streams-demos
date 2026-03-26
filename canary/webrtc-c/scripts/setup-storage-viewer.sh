#!/bin/bash
# Install Node.js if not present with retry logic
if ! command -v npm &> /dev/null; then
    echo "Node.js not found, installing..."
    
    # Remove conflicting packages first
    sudo apt-get remove -y libnode72 nodejs 2>/dev/null || true
    sudo apt-get autoremove -y 2>/dev/null || true
    
    for i in {1..3}; do
        if curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash - && sudo apt-get install -y nodejs; then
            break
        else
            echo "Installation attempt $i failed, retrying in 10 seconds..."
            sleep 10
        fi
    done
    echo "Node.js installation completed"
else
    echo "Node.js already installed: $(node --version)"
fi

# Verify npm is available after installation
if ! command -v npm &> /dev/null; then
    echo "ERROR: npm command not found after installation. Node.js installation failed."
    exit 1
fi
echo "npm verified: $(npm --version)"

cd ./canary/webrtc-c/scripts || { echo "ERROR: Failed to change directory to ./canary/webrtc-c/scripts"; exit 1; }

npm install || { echo "ERROR: Failed to install Node.js dependencies"; exit 1; }

# Verify Puppeteer can find Chrome. If the cached version doesn't match what
# Puppeteer expects (e.g., after a Puppeteer upgrade), force-install the correct one.
CHROME_PATH=$(node -e "try { console.log(require('puppeteer').executablePath()) } catch(e) { console.log('') }" 2>/dev/null)
if [ -z "$CHROME_PATH" ] || [ ! -f "$CHROME_PATH" ]; then
    echo "Puppeteer Chrome not found or version mismatch, installing correct version..."
    # Clear stale cache to avoid disk bloat
    rm -rf "$HOME/.cache/puppeteer/chrome" 2>/dev/null || true
    npx puppeteer browsers install chrome || { echo "ERROR: Failed to install Chrome for Puppeteer"; exit 1; }
    echo "Chrome installed successfully"
else
    echo "Puppeteer Chrome verified at: $CHROME_PATH"
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

# Set metric suffix if provided (defaults to empty)
if [ -n "${METRIC_SUFFIX}" ]; then
    export METRIC_SUFFIX="${METRIC_SUFFIX}"
    echo "Using metric suffix: ${METRIC_SUFFIX}"
fi

# Run storage viewer test
node chrome-headless.js || { echo "ERROR: Chrome headless test failed"; exit 1; }