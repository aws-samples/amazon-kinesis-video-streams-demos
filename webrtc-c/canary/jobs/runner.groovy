import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

START_TIMESTAMP = new Date().getTime()
RUNNING_NODES_IN_BUILDING = 0
HAS_ERROR = false

def buildPeer(isMaster, params) {
    def clientID = isMaster ? "Master" : "Viewer"
    def envs = [
      'AWS_KVS_LOG_LEVEL': params.AWS_KVS_LOG_LEVEL,
      'CANARY_USE_TURN': params.USE_TURN,
      'CANARY_TRICKLE_ICE': params.TRICKLE_ICE,
      'CANARY_LOG_GROUP_NAME': params.LOG_GROUP_NAME,
      'CANARY_LOG_STREAM_NAME': "${params.RUNNER_LABEL}-${clientID}-${START_TIMESTAMP}",
      'CANARY_CHANNEL_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}",
      'CANARY_CLIENT_ID': clientID,
      'CANARY_IS_MASTER': isMaster,
      'CANARY_DURATION_IN_SECONDS': params.DURATION_IN_SECONDS
    ].collect({ k, v -> "${k}=${v}" })
    def credentials = [
        [
            $class: 'AmazonWebServicesCredentialsBinding', 
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            credentialsId: 'CANARY_CREDENTIALS',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        ]
    ]
    
    RUNNING_NODES_IN_BUILDING++

    // TODO: get the branch and version from orchestrator
    if (params.FIRST_ITERATION) {
        // TODO: uncomment this when this is stable
        // deleteDir()

        checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH ]],
                  userRemoteConfigs: [[url: params.GIT_URL]]])

        sh """
            cd ./webrtc-c/canary && 
            mkdir -p build && 
            cd build && 
            cmake .. -DCMAKE_INSTALL_PREFIX="\$PWD" && 
            make -j"""
    }

    RUNNING_NODES_IN_BUILDING--
    
    waitUntil {
        RUNNING_NODES_IN_BUILDING == 0
    }

    withEnv(envs) {
        withCredentials(credentials) {
            try {
                sh """
                    cd ./webrtc-c/canary/build && 
                    ${isMaster ? "" : "sleep 5 &&"}
                    ./kvsWebrtcCanaryWebrtc"""
            } catch (FlowInterruptedException err) {
                echo 'Aborted due to cancellation'
                throw err
            } catch (err) {
                HAS_ERROR = true
                // Ignore errors so that we can auto recover by retrying
                unstable err.toString()
            }
        }
    }
}

pipeline {
    agent {
        label params.MASTER_NODE_LABEL
    }

    parameters {
        choice(name: 'AWS_KVS_LOG_LEVEL', choices: ["1", "2", "3", "4", "5"])
        booleanParam(name: 'USE_TURN')
        booleanParam(name: 'TRICKLE_ICE')
        string(name: 'LOG_GROUP_NAME')
        string(name: 'MASTER_NODE_LABEL')
        string(name: 'VIEWER_NODE_LABEL')
        string(name: 'RUNNER_LABEL')
        string(name: 'DURATION_IN_SECONDS')
        string(name: 'MIN_RETRY_DELAY_IN_SECONDS')
        string(name: 'GIT_URL')
        string(name: 'GIT_HASH')
        booleanParam(name: 'FIRST_ITERATION', defaultValue: true)
    }

    stages {
        stage('Preparation') {
            steps {
              echo params.toString()
            }
        }

        stage('Build and Run') {
            failFast true

            parallel {
                stage('Master') {
                    steps {
                        script {
                            buildPeer(true, params)
                        }
                    }
                }

                stage('Viewer') {
                    agent {
                        label params.VIEWER_NODE_LABEL
                    }

                    steps {
                        script {
                            buildPeer(false, params)
                        }
                    }
                }
            }
        }

        // In case of failures, we should add some delays so that we don't get into a tight loop of retrying
        stage('Throttling Retry') {
            when {
                equals expected: true, actual: HAS_ERROR
            }

            steps {
                sleep Math.max(0, params.MIN_RETRY_DELAY_IN_SECONDS.toInteger() - currentBuild.duration.intdiv(1000))
            }
        }

        stage('Reschedule') {
            steps {
                // TODO: Maybe there's a better way to write this instead of duplicating it
                build(
                    job: env.JOB_NAME,
                    parameters: [
                      string(name: 'AWS_KVS_LOG_LEVEL', value: params.AWS_KVS_LOG_LEVEL),
                      booleanParam(name: 'USE_TURN', value: params.USE_TURN),
                      booleanParam(name: 'TRICKLE_ICE', value: params.TRICKLE_ICE),
                      string(name: 'LOG_GROUP_NAME', value: params.LOG_GROUP_NAME),
                      string(name: 'MASTER_NODE_LABEL', value: params.MASTER_NODE_LABEL),
                      string(name: 'VIEWER_NODE_LABEL', value: params.VIEWER_NODE_LABEL),
                      string(name: 'RUNNER_LABEL', value: params.RUNNER_LABEL),
                      string(name: 'DURATION_IN_SECONDS', value: params.DURATION_IN_SECONDS),
                      string(name: 'MIN_RETRY_DELAY_IN_SECONDS', value: params.MIN_RETRY_DELAY_IN_SECONDS),
                      string(name: 'GIT_URL', value: params.GIT_URL),
                      string(name: 'GIT_HASH', value: params.GIT_HASH),
                      booleanParam(name: 'FIRST_ITERATION', value: false)
                    ],
                    wait: false
                )
            }
        }
    }
}

