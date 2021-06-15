#!/bin/bash

prefix=$1
export CANARY_CHANNEL_NAME=test_channel
export CANARY_CLIENT_ID=test
export CANARY_IS_MASTER=FALSE  # if you are running the app as master
export CANARY_TRICKLE_ICE=FALSE  # If you would like to enable trickle ICE
export CANARY_USE_TURN=TRUE  # This means turn urls are are also selected as an alternate path
export CANARY_DURATION_IN_SECONDS=0 # How long you want to run this in seconds.
export CANARY_FORCE_TURN=FALSE #This will enforce TURN, which is your use case
export CANARY_USE_IOT_PROVIDER=TRUE #This will enforce use of IoT credential provider
export AWS_IOT_CORE_CREDENTIAL_ENDPOINT=$(cat iot-credential-provider.txt)
export AWS_IOT_CORE_CERT=$(PWD)/w${prefix}_certificate.pem
export AWS_IOT_CORE_PRIVATE_KEY=$(PWD)/w${prefix}_private.key
export AWS_IOT_CORE_ROLE_ALIAS="w${prefix}_role_alias"
export AWS_IOT_CORE_THING_NAME="w${prefix}_thing"
