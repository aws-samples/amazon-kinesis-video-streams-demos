#!/bin/bash

# Wait for StorageMaster to start up
echo "Waiting 1 minute for StorageMaster to start..."
sleep 60

# Install Node.js if not present with retry logic
if ! command -v npm &> /dev/null; then
    echo "Node.js not found, installing..."
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

cd ./canary/webrtc-c/scripts || { echo "ERROR: Failed to change directory to ./canary/webrtc-c/scripts"; exit 1; }

# Install Node.js dependencies if not exists
if [ ! -d "node_modules" ]; then
    npm install puppeteer @aws-sdk/client-cloudwatch || { echo "ERROR: Failed to install Node.js dependencies"; exit 1; }
fi

# Set environment variables for the test
export CANARY_CHANNEL_NAME="${JOB_NAME}-${RUNNER_LABEL}"
export AWS_REGION="${AWS_DEFAULT_REGION}"
export TEST_DURATION="${DURATION_IN_SECONDS}"

# Run storage viewer test
node chrome-headless.js || { echo "ERROR: Chrome headless test failed"; exit 1; }