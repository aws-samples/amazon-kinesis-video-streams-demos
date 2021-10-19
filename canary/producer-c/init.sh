#!/bin/bash

prefix=$1

export CANARY_STREAM_NAME=test
export CANARY_TYPE=Realtime # Allowed values: Realtime, Offline
export FRAGMENT_SIZE_IN_BYTES=1048576 # Preferrably multiples of 1024 bytes
export CANARY_DURATION_IN_SECONDS=1800
export CANARY_STORAGE_SIZE_IN_BYTES=134217728 # in bytes
export CANARY_BUFFER_DURATION_IN_SECONDS=120 #in seconds
export CANARY_LABEL=Intermittent #This will used as a dimension. Allowed 20 characters
export CANARY_RUN_SCENARIO=Intermittent # Allowed values: Intermittent, Continuous
export TRACK_TYPE=SingleTrack #Allowed values: SingleTrack, MultiTrack
export CANARY_USE_IOT_PROVIDER=TRUE
export AWS_IOT_CORE_CREDENTIAL_ENDPOINT=$(pwd)/iot-credential-provider.txt
export AWS_IOT_CORE_CERT=$(pwd)/p${prefix}_certificate.pem
export AWS_IOT_CORE_PRIVATE_KEY=$(pwd)/p${prefix}_private.key
export AWS_IOT_CORE_ROLE_ALIAS="p${prefix}_role_alias"
export AWS_IOT_CORE_THING_NAME="p${prefix}_thing"
