import jenkins.model.*

RUNNER_JOB_NAME_PREFIX = "webrtc-canary-runner"
PERIODIC_DURATION_IN_SECONDS = 30
LONG_RUNNING_DURATION_IN_SECONDS = 0
MIN_RETRY_DELAY_IN_SECONDS = 60
COLD_STARTUP_DELAY_IN_SECONDS = 60 * 60
GIT_URL = 'https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git'
GIT_HASH = 'master'
COMMON_PARAMS = [
    string(name: 'AWS_KVS_LOG_LEVEL', value: "2"),
    string(name: 'MIN_RETRY_DELAY_IN_SECONDS', value: MIN_RETRY_DELAY_IN_SECONDS.toString()),
    string(name: 'GIT_URL', value: GIT_URL),
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
        label 'ec2-us-west-2'
    }

    options {
        disableConcurrentBuilds()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout([$class: 'GitSCM', branches: [[name: GIT_HASH ]],
                          userRemoteConfigs: [[url: GIT_URL]]])
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
                            def gitHash = sh(returnStdout: true, script: 'git rev-parse HEAD')
                            COMMON_PARAMS << string(name: 'GIT_HASH', value: gitHash)
                        }

                        // TODO: Use matrix to spawn runners
                        // build(
                        //     job: NEXT_AVAILABLE_RUNNER,
                        //     parameters: COMMON_PARAMS + [
                        //         booleanParam(name: 'USE_TURN', value: true),
                        //         booleanParam(name: 'TRICKLE_ICE', value: true),
                        //         string(name: 'DURATION_IN_SECONDS', value: PERIODIC_DURATION_IN_SECONDS.toString()),
                        //         string(name: 'MASTER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'VIEWER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'RUNNER_LABEL', value: "WebrtcPeriodicOpenSSL"),
                        //         string(name: 'SCENARIO_LABEL', value: "WebrtcPeriodic"),
                        //     ],
                        //     wait: false
                        // )

                        // build(
                        //     job: NEXT_AVAILABLE_RUNNER,
                        //     parameters: COMMON_PARAMS + [
                        //         booleanParam(name: 'USE_TURN', value: true),
                        //         booleanParam(name: 'TRICKLE_ICE', value: true),
                        //         string(name: 'DURATION_IN_SECONDS', value: LONG_RUNNING_DURATION_IN_SECONDS.toString()),
                        //         string(name: 'MASTER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'VIEWER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'RUNNER_LABEL', value: "WebrtcLongRunningOpenSSL"),
                        //         string(name: 'SCENARIO_LABEL', value: "WebrtcLongRunning"),
                        //     ],
                        //     wait: false
                        // )

                        build(
                            job: NEXT_AVAILABLE_RUNNER,
                            parameters: COMMON_PARAMS + [
                                booleanParam(name: 'USE_TURN', value: true),
                                booleanParam(name: 'TRICKLE_ICE', value: true),
                                booleanParam(name: 'USE_IOT', value: true),
                                string(name: 'DURATION_IN_SECONDS', value: LONG_RUNNING_DURATION_IN_SECONDS.toString()),
                                string(name: 'MASTER_NODE_LABEL', value: "ec2-us-west-2"),
                                string(name: 'VIEWER_NODE_LABEL', value: "ec2-us-west-2"),
                                string(name: 'RUNNER_LABEL', value: "WebrtcLongRunningIoTOpenSSL"),
                                string(name: 'SCENARIO_LABEL', value: "WebrtcLongRunning"),
                            ],
                            wait: false
                        )

                        // build(
                        //     job: NEXT_AVAILABLE_RUNNER,
                        //     parameters: COMMON_PARAMS + [
                        //         booleanParam(name: 'IS_SIGNALING', value: true),
                        //         string(name: 'DURATION_IN_SECONDS', value: PERIODIC_DURATION_IN_SECONDS.toString()),
                        //         string(name: 'MASTER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         // TODO: should not need viewer node label for signaling. If not set, Jenkins pipeline will crash
                        //         //       because it's used to defined an agent
                        //         string(name: 'VIEWER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'RUNNER_LABEL', value: "SignalingPeriodic"),
                        //         string(name: 'SCENARIO_LABEL', value: "SignalingPeriodic"),
                        //     ],
                        //     wait: false
                        // )

                        // build(
                        //     job: NEXT_AVAILABLE_RUNNER,
                        //     parameters: COMMON_PARAMS + [
                        //         booleanParam(name: 'IS_SIGNALING', value: true),
                        //         string(name: 'DURATION_IN_SECONDS', value: LONG_RUNNING_DURATION_IN_SECONDS.toString()),
                        //         string(name: 'MASTER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         // TODO: should not need viewer node label for signaling. If not set, Jenkins pipeline will crash
                        //         //       because it's used to defined an agent
                        //         string(name: 'VIEWER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'RUNNER_LABEL', value: "SignalingLongRunning"),
                        //         string(name: 'SCENARIO_LABEL', value: "SignalingLongRunning"),
                        //     ],
                        //     wait: false
                        // )
                        // build(
                        //     job: NEXT_AVAILABLE_RUNNER,
                        //     parameters: COMMON_PARAMS + [
                        //         booleanParam(name: 'USE_TURN', value: true),
                        //         booleanParam(name: 'TRICKLE_ICE', value: true),
                        //         booleanParam(name: 'USE_MBEDTLS', value: true),
                        //         string(name: 'DURATION_IN_SECONDS', value: PERIODIC_DURATION_IN_SECONDS.toString()),
                        //         string(name: 'MASTER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'VIEWER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'RUNNER_LABEL', value: "WebrtcPeriodicMbedTLS"),
                        //         string(name: 'SCENARIO_LABEL', value: "WebrtcPeriodic"),
                        //     ],
                        //     wait: false
                        // )

                        // build(
                        //     job: NEXT_AVAILABLE_RUNNER,
                        //     parameters: COMMON_PARAMS + [
                        //         booleanParam(name: 'USE_TURN', value: true),
                        //         booleanParam(name: 'TRICKLE_ICE', value: true),
                        //         booleanParam(name: 'USE_MBEDTLS', value: true),
                        //         string(name: 'DURATION_IN_SECONDS', value: LONG_RUNNING_DURATION_IN_SECONDS.toString()),
                        //         string(name: 'MASTER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'VIEWER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'RUNNER_LABEL', value: "WebrtcLongRunningMbedTLS"),
                        //         string(name: 'SCENARIO_LABEL', value: "WebrtcLongRunning"),
                        //     ],
                        //     wait: false
                        // )

                        // build(
                        //     job: NEXT_AVAILABLE_RUNNER,
                        //     parameters: COMMON_PARAMS + [
                        //         booleanParam(name: 'IS_SIGNALING', value: true),
                        //         string(name: 'DURATION_IN_SECONDS', value: PERIODIC_DURATION_IN_SECONDS.toString()),
                        //         string(name: 'MASTER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         // TODO: should not need viewer node label for signaling. If not set, Jenkins pipeline will crash
                        //         //       because it's used to defined an agent
                        //         string(name: 'VIEWER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'RUNNER_LABEL', value: "SignalingPeriodic"),
                        //         string(name: 'SCENARIO_LABEL', value: "SignalingPeriodic"),
                        //     ],
                        //     wait: false
                        // )

                        // build(
                        //     job: NEXT_AVAILABLE_RUNNER,
                        //     parameters: COMMON_PARAMS + [
                        //         booleanParam(name: 'IS_SIGNALING', value: true),
                        //         string(name: 'DURATION_IN_SECONDS', value: LONG_RUNNING_DURATION_IN_SECONDS.toString()),
                        //         string(name: 'MASTER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         // TODO: should not need viewer node label for signaling. If not set, Jenkins pipeline will crash
                        //         //       because it's used to defined an agent
                        //         string(name: 'VIEWER_NODE_LABEL', value: "ec2-us-west-2"),
                        //         string(name: 'RUNNER_LABEL', value: "SignalingLongRunning"),
                        //         string(name: 'SCENARIO_LABEL', value: "SignalingLongRunning"),
                        //     ],
                        //     wait: false
                        // )
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
