#!/bin/bash

prefix=$1

export CANARY_STREAM_NAME=test
export CANARY_STREAM_TYPE=Realtime # Allowed values: Realtime, Offline
export FRAGMENT_SIZE_IN_BYTES=1048576 # Preferrably multiples of 1024 bytes
export CANARY_DURATION_IN_SECONDS=1800
export CANARY_STORAGE_SIZE_IN_MBYTES=134217728 # in bytes
export CANARY_BUFFER_DURATION_IN_SECONDS=120 #in seconds
export CANARY_LABEL=Longrun #This will used as a dimension. Allowed 20 characters
export CANARY_RUN_SCENARIO=Continuous # Allowed values: Intermittent, Continuous
export CANARY_USE_IOT=TRUE