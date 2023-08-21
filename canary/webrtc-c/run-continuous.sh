#!/bin/bash

while true; do
    # Log statement
    echo "Starting new iteration at $(date)"

    # Running first shell script
    source scripts/init.sh

    # Run the C executable and the second shell script simultaneously
    ./build/kvsWebrtcCanaryWebrtc

    sleep 5

    source scripts/v_init.sh
    ./build/kvsWebrtcCanaryWebrtc

    wait

done
