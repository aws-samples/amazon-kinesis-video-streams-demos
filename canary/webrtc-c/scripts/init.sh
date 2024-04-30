#!/bin/bash

prefix=$1
export CANARY_CHANNEL_NAME=test_channel
export CANARY_CLIENT_ID=test
export CANARY_IS_MASTER=TRUE  # if you are running the app as master
export CANARY_TRICKLE_ICE=TRUE  # If you would like to enable trickle ICE
export CANARY_USE_TURN=TRUE  # This means turn urls are are also selected as an alternate path
export CANARY_DURATION_IN_SECONDS=90 # How long you want to run this in seconds.
export CANARY_FORCE_TURN=FALSE #This will enforce TURN, which is your use case
export CANARY_USE_IOT_PROVIDER=FALSE #This will enforce use of IoT credential provider
export CANARY_LABEL=Profiling
export CANARY_IS_PROFILING_MODE=TRUE
export CANARY_VIDEO_CODEC_ENV_VAR=h264
#export AWS_IOT_CORE_CREDENTIAL_ENDPOINT=$(PWD)/iot-credential-provider.txt
#export AWS_IOT_CORE_CERT=$(PWD)/${prefix}_certificate.pem
#export AWS_IOT_CORE_PRIVATE_KEY=$(PWD)/${prefix}_private.key
#export AWS_IOT_CORE_ROLE_ALIAS="${prefix}_role_alias"
#export AWS_IOT_CORE_THING_NAME="${prefix}_thing"
