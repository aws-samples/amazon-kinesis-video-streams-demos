import jenkins.model.*

RUNNER_JOB_NAME_PREFIX = "webrtc-canary-runner"

STORAGE_PERIODIC_DURATION_IN_SECONDS = 300 // 5 min
STORAGE_SUB_RECONNECT_DURATION_IN_SECONDS = 2700 // 45 min
STORAGE_SINGLE_RECONNECT_DURATION_IN_SECONDS = 3900 // 65 min
STORAGE_EXTENDED_DURATION_IN_SECONDS = 43200 // 12 hr

MIN_RETRY_DELAY_IN_SECONDS = 60
COLD_STARTUP_DELAY_IN_SECONDS = 60 * 60
GIT_URL_WEBRTC = 'https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-c.git'
GIT_HASH_WEBRTC = 'simplify-sample'
GIT_URL_CONSUMER = 'https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git'
GIT_HASH_CONSUMER = 'new-canary'
COMMON_PARAMS = [
    string(name: 'AWS_KVS_LOG_LEVEL', value: "2"),
    string(name: 'DEBUG_LOG_SDP', value: "TRUE"),
    string(name: 'MIN_RETRY_DELAY_IN_SECONDS', value: MIN_RETRY_DELAY_IN_SECONDS.toString()),
    string(name: 'GIT_URL_WEBRTC', value: GIT_URL_WEBRTC),
    string(name: 'GIT_URL_CONSUMER', value: GIT_URL_CONSUMER),
    string(name: 'GIT_HASH_WEBRTC', value: GIT_HASH_WEBRTC),
    string(name: 'GIT_HASH_CONSUMER', value: GIT_HASH_CONSUMER),
    string(name: 'LOG_GROUP_NAME', value: "WebrtcSDK"),
]

def getJobLastBuildTimestamp(job) {
    def timestamp = 0
    def lastBuild = job.getLastBuild()

    if (lastBuild != null) {
        timestamp = lastBuild.getTimeInMillis()
    }

    return timestamp
}

def cancelJob(jobName) {
    def job = Jenkins.instance.getItemByFullName(jobName)

    echo "Tear down ${jobName}"
    job.setDisabled(true)
    job.getBuilds()
       .findAll({ build -> build.isBuilding() })
       .each({ build -> 
            echo "Kill $build"
            build.doKill()
        })
}

def findRunners() {
    def filterClosure = { item -> item.getDisplayName().startsWith(RUNNER_JOB_NAME_PREFIX) }
    return Jenkins.instance
                    .getAllItems(Job.class)
                    .findAll(filterClosure)
}

NEXT_AVAILABLE_RUNNER = null
ACTIVE_RUNNERS = [] 

pipeline {
    agent {
        label 'openssl-master'
    }

    options {
        disableConcurrentBuilds()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout([$class: 'GitSCM', branches: [[name: GIT_HASH_WEBRTC ]],
                          userRemoteConfigs: [[url: GIT_URL_WEBRTC]]])
                checkout([$class: 'GitSCM', branches: [[name: GIT_HASH_CONSUMER ]],
                          userRemoteConfigs: [[url: GIT_URL_CONSUMER]]])
            }
        }

        stage('Update runners') {
            /* TODO: Add a conditional step to check if there's any update in webrtc canary
            when {
              changeset 'webrtc-c/canary/**'
            }
            */

            stages {
                stage("Find the next available runner and current active runners") {
                    steps {
                        script {
                            def runners = findRunners()
                            def nextRunner = null 
                            def oldestTimestamp = Long.MAX_VALUE

                            // find the least active runner
                            runners.each {
                                def timestamp = getJobLastBuildTimestamp(it)
                                if ((it.isDisabled() || !it.isBuilding()) && timestamp < oldestTimestamp) {
                                    nextRunner = it
                                    oldestTimestamp = timestamp
                                }
                            }

                            if (nextRunner == null) {
                                error "There's no available runner"
                            }

                            NEXT_AVAILABLE_RUNNER = nextRunner.getDisplayName()
                            echo "Found next available runner: ${NEXT_AVAILABLE_RUNNER}"

                            ACTIVE_RUNNERS = runners.findAll({ item -> item != nextRunner && (!item.isDisabled() || item.isBuilding()) })
                                                    .collect({ item -> item.getDisplayName() })
                            echo "Found current active runners: ${ACTIVE_RUNNERS}"
                        }
                    }
                }
            
                stage("Spawn new runners") {
                    steps {
                        script {
                            echo "New runner: ${NEXT_AVAILABLE_RUNNER}"
                            Jenkins.instance.getItemByFullName(NEXT_AVAILABLE_RUNNER).setDisabled(false)

                            // Lock in current commit hash to avoid inconsistent version across runners
//                             def gitHashWebRtc = sh(returnStdout: true, script: 'git rev-parse HEAD')
//                             COMMON_PARAMS << string(name: 'GIT_HASH_WEBRTC', value: gitHashWebRtc)
//                             def gitHashConsumer = sh(returnStdout: true, script: 'git rev-parse HEAD')
//                             COMMON_PARAMS << string(name: 'GIT_HASH_CONSUMER', value: gitHashConsumer)
                        }

                        // TODO: Use matrix to spawn runners

//                         build(
//                             job: NEXT_AVAILABLE_RUNNER,
//                             parameters: COMMON_PARAMS + [
//                                 string(name: 'CONFIG_FILE_HEADER', value: "lr_iot_h264_openssl.h"),
//                                 string(name: 'MASTER_NODE_LABEL', value: "openssl-master"),
//                                 string(name: 'RUNNER_LABEL', value: "WebrtcPeriodicOpenSSL"),
//                                 string(name: 'VIEWER_NODE_LABEL', value: "openssl-viewer"),
//                             ],
//                             wait: false
//                         )
//
//                         build(
//                             job: NEXT_AVAILABLE_RUNNER,
//                             parameters: COMMON_PARAMS + [
//                                 string(name: 'CONFIG_FILE_HEADER', value: "p_iot_h265_openssl.h"),
//                                 string(name: 'RUNNER_LABEL', value: "WebrtcPeriodicOpenSSL-H265"),
//                                 string(name: 'MASTER_NODE_LABEL', value: "mbedtls-master"),
//                                 string(name: 'VIEWER_NODE_LABEL', value: "mbedtls-viewer"),
//                             ],
//                             wait: false
//                         )
//
//                         build(
//                             job: NEXT_AVAILABLE_RUNNER,
//                             parameters: COMMON_PARAMS + [
//                                 string(name: 'CONFIG_FILE_HEADER', value: "lr_static_h265_openssl.h"),
//                                 string(name: 'RUNNER_LABEL', value: "WebrtcLongRunningStaticOpenSSL-H265"),
//                                 string(name: 'MASTER_NODE_LABEL', value: "openssl-master"),
//                                 string(name: 'VIEWER_NODE_LABEL', value: "openssl-viewer"),
//                             ],
//                             wait: false
//                         )
//
//                         build(
//                             job: NEXT_AVAILABLE_RUNNER,
//                             parameters: COMMON_PARAMS + [
//                                 booleanParam(name: 'USE_MBEDTLS', value: true),
//                                 string(name: 'RUNNER_LABEL', value: "WebrtcPeriodicStaticMbedTLS"),
//                                 string(name: 'CONFIG_FILE_HEADER', value: "p_static_h264_mbedtls.h"),
//                                 string(name: 'MASTER_NODE_LABEL', value: "mbedtls-master"),
//                                 string(name: 'VIEWER_NODE_LABEL', value: "mbedtls-viewer"),
//                             ],
//                             wait: false
//                         )
//
//                         build(
//                             job: NEXT_AVAILABLE_RUNNER,
//                             parameters: COMMON_PARAMS + [
//                                 booleanParam(name: 'USE_MBEDTLS', value: true),
//                                 string(name: 'CONFIG_FILE_HEADER', value: "lr_iot_h264_mbedtls.h"),
//                                 string(name: 'RUNNER_LABEL', value: "WebrtcLongRunningMBedTLS"),
//                                 string(name: 'MASTER_NODE_LABEL', value: "openssl-master"),
//                                 string(name: 'VIEWER_NODE_LABEL', value: "openssl-viewer"),
//                             ],
//                             wait: false
//                         )
//
//                         build(
//                             job: NEXT_AVAILABLE_RUNNER,
//                             parameters: COMMON_PARAMS + [
//                                 booleanParam(name: 'USE_MBEDTLS', value: true),
//                                 string(name: 'CONFIG_FILE_HEADER', value: "p_iot_h265_mbedtls.h"),
//                                 string(name: 'RUNNER_LABEL', value: "WebrtcPeriodicMbedTLS"),
//                                 string(name: 'MASTER_NODE_LABEL', value: "mbedtls-master"),
//                                 string(name: 'VIEWER_NODE_LABEL', value: "mbedtls-viewer"),
//                             ],
//                             wait: false
//                         )

                        // Storage Periodic.
//                         build(
//                             job: NEXT_AVAILABLE_RUNNER,
//                             parameters: COMMON_PARAMS + [
//                                 booleanParam(name: 'IS_STORAGE', value: true),
//                                 booleanParam(name: 'IS_STORAGE_SINGLE_NODE', value: true),
//                                 string(name: 'DURATION_IN_SECONDS', value: STORAGE_PERIODIC_DURATION_IN_SECONDS.toString()),
//                                 string(name: 'CONFIG_FILE_HEADER', value: "storage_periodic.h"),
//                                 string(name: 'RUNNER_LABEL', value: "StoragePeriodic"),
//                                 string(name: 'SCENARIO_LABEL', value: "StoragePeriodic"),
//                                 string(name: 'MASTER_NODE_LABEL', value: "openssl-master"),
//                                 string(name: 'CONSUMER_NODE_LABEL', value: "openssl-viewer"),
//                                 string(name: 'AWS_DEFAULT_REGION', value: "us-west-2"),
//                             ],
//                             wait: false
//                         )

                        // Storage Sub-Reconnect.
                        build(
                            job: NEXT_AVAILABLE_RUNNER,
                            parameters: COMMON_PARAMS + [
                                booleanParam(name: 'IS_SIGNALING', value: false),
                                booleanParam(name: 'IS_STORAGE', value: true),
                                booleanParam(name: 'USE_TURN', value: true),
                                booleanParam(name: 'TRICKLE_ICE', value: true),
                                booleanParam(name: 'USE_IOT', value: false),
                                string(name: 'DURATION_IN_SECONDS', value: STORAGE_SUB_RECONNECT_DURATION_IN_SECONDS.toString()),
                                string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master"),
                                string(name: 'CONSUMER_NODE_LABEL', value: "webrtc-storage-consumer"),
                                string(name: 'RUNNER_LABEL', value: "StorageSubReconnect"),
                                string(name: 'SCENARIO_LABEL', value: "StorageSubReconnect"),
                                string(name: 'AWS_DEFAULT_REGION', value: "us-west-2"),
                            ],
                            wait: false
                        )
//
//                         // Storage Single Reconnect.
//                         build(
//                             job: NEXT_AVAILABLE_RUNNER,
//                             parameters: COMMON_PARAMS + [
//                                 booleanParam(name: 'IS_STORAGE', value: true),
//                                 booleanParam(name: 'USE_TURN', value: true),
//                                 booleanParam(name: 'TRICKLE_ICE', value: true),
//                                 booleanParam(name: 'USE_IOT', value: false),
//                                 string(name: 'DURATION_IN_SECONDS', value: STORAGE_SINGLE_RECONNECT_DURATION_IN_SECONDS.toString()),
//                                 string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master"),
//                                 string(name: 'CONSUMER_NODE_LABEL', value: "webrtc-storage-consumer"),
//                                 string(name: 'RUNNER_LABEL', value: "StorageSingleReconnect"),
//                                 string(name: 'SCENARIO_LABEL', value: "StorageSingleReconnect"),
//                                 string(name: 'AWS_DEFAULT_REGION', value: "us-west-2"),
//                             ],
//                             wait: false
//                         )
//
//                         // Storage Extended.
//                         build(
//                             job: NEXT_AVAILABLE_RUNNER,
//                             parameters: COMMON_PARAMS + [
//                                 booleanParam(name: 'IS_STORAGE', value: true),
//                                 booleanParam(name: 'USE_TURN', value: true),
//                                 booleanParam(name: 'TRICKLE_ICE', value: true),
//                                 booleanParam(name: 'USE_IOT', value: false),
//                                 string(name: 'DURATION_IN_SECONDS', value: STORAGE_EXTENDED_DURATION_IN_SECONDS.toString()),
//                                 string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master"),
//                                 string(name: 'CONSUMER_NODE_LABEL', value: "webrtc-storage-consumer"),
//                                 string(name: 'RUNNER_LABEL', value: "StorageExtended"),
//                                 string(name: 'SCENARIO_LABEL', value: "StorageExtended"),
//                                 string(name: 'AWS_DEFAULT_REGION', value: "us-west-2"),
//                             ],
//                             wait: false
//                         )
                    }
                }

                stage("Tear down old runners") {
                    when {
                        expression { return ACTIVE_RUNNERS.size() > 0 }
                    }

                    steps {
                        script {
                            try {
                                sleep COLD_STARTUP_DELAY_IN_SECONDS
                            } catch(err) {
                                // rollback the newly spawned runner
                                echo "Rolling back ${NEXT_AVAILABLE_RUNNER}"
                                cancelJob(NEXT_AVAILABLE_RUNNER)
                                throw err
                            }

                            for (def runner in ACTIVE_RUNNERS) {
                                cancelJob(runner)
                            }
                        }
                    }
                }
            }
        }
    }
}
