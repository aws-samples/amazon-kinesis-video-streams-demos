#!/bin/bash

prefix=$1
export CANARY_CHANNEL_NAME=test_channel
export CANARY_CLIENT_ID=test
export CANARY_IS_MASTER=FALSE
export CANARY_TRICKLE_ICE=TRUE
export CANARY_USE_TURN=TRUE
export CANARY_DURATION_IN_SECONDS=90
export CANARY_FORCE_TURN=FALSE
export CANARY_USE_IOT_PROVIDER=FALSE
export CANARY_LABEL=Profiling
export CANARY_IS_PROFILING_MODE=TRUE
export CANARY_VIDEO_CODEC=h265
#export AWS_IOT_CORE_CREDENTIAL_ENDPOINT=$(PWD)/iot-credential-provider.txt
#export AWS_IOT_CORE_CERT=$(PWD)/${prefix}_certificate.pem
#export AWS_IOT_CORE_PRIVATE_KEY=$(PWD)/${prefix}_private.key
#export AWS_IOT_CORE_ROLE_ALIAS="${prefix}_role_alias"
#export AWS_IOT_CORE_THING_NAME="${prefix}_thing"
