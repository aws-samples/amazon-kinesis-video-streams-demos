import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

START_TIMESTAMP = new Date().getTime()
RUNNING_NODES_IN_BUILDING = 0
HAS_ERROR = false

def buildWebRTCProject(useMbedTLS, thing_prefix) {
    echo 'Flag set to ' + useMbedTLS
    checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH ]],
              userRemoteConfigs: [[url: params.GIT_URL]]])

    def configureCmd = "cmake .. -DCMAKE_BUILD_TYPE=Debug -DCMAKE_INSTALL_PREFIX=\"\$PWD\""
    if (useMbedTLS) {
      echo 'Using mbedtls'
      configureCmd += " -DCANARY_USE_OPENSSL=OFF -DCANARY_USE_MBEDTLS=ON"
    }

    sh """
        cd ./canary/webrtc-c/scripts &&
        chmod a+x cert_setup.sh &&
        ./cert_setup.sh ${thing_prefix} &&
        cd .. &&
        rm -rf build &&
        mkdir -p build &&
        cd build &&
        ${configureCmd} &&
        make"""
}

def withRunnerWrapper(envs, fn) {
    withEnv(envs) {
        try {
            fn()
        } catch (FlowInterruptedException err) {
            echo "Interrupted: ${err.getMessage()}"
            HAS_ERROR = true
            unstable err.toString()
        } catch (err) {
            HAS_ERROR = true
            unstable err.toString()
        }
    }
}

def buildPeer(isMaster, params) {
    def clientID = isMaster ? "Master" : "Viewer"
    RUNNING_NODES_IN_BUILDING++

    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    buildWebRTCProject(params.USE_MBEDTLS, thing_prefix)

    RUNNING_NODES_IN_BUILDING--

    waitUntil {
        RUNNING_NODES_IN_BUILDING == 0
    }

    def scripts_dir = "$WORKSPACE/canary/webrtc-c/scripts"
    def endpoint = "${scripts_dir}/iot-credential-provider.txt"
    def core_cert_file = "${scripts_dir}/${thing_prefix}_certificate.pem"
    def private_key_file = "${scripts_dir}/${thing_prefix}_private.key"
    def role_alias = "${thing_prefix}_role_alias"
    def thing_name = "${thing_prefix}_thing"

    def envs = [
      'AWS_KVS_LOG_LEVEL': params.AWS_KVS_LOG_LEVEL,
      'DEBUG_LOG_SDP': params.DEBUG_LOG_SDP,
      'CANARY_USE_TURN': params.USE_TURN,
      'CANARY_FORCE_TURN': params.FORCE_TURN,
      'CANARY_IS_PROFILING_MODE': params.IS_PROFILING,
      'CANARY_TRICKLE_ICE': params.TRICKLE_ICE,
      'CANARY_USE_IOT_PROVIDER': params.USE_IOT,
      'CANARY_LOG_GROUP_NAME': params.LOG_GROUP_NAME,
      'CANARY_LOG_STREAM_NAME': "${params.RUNNER_LABEL}-${clientID}-${START_TIMESTAMP}",
      'CANARY_CHANNEL_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}",
      'CANARY_LABEL': params.SCENARIO_LABEL,
      'CANARY_CLIENT_ID': clientID,
      'CANARY_IS_MASTER': isMaster,
      'CANARY_DURATION_IN_SECONDS': params.DURATION_IN_SECONDS,
      'CANARY_VIDEO_CODEC': params.VIDEO_CODEC,
      'AWS_IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
      'AWS_IOT_CORE_CERT': "${core_cert_file}",
      'AWS_IOT_CORE_PRIVATE_KEY': "${private_key_file}",
      'AWS_IOT_CORE_ROLE_ALIAS': "${role_alias}",
      'AWS_IOT_CORE_THING_NAME': "${thing_name}"
    ].collect{ k, v -> "${k}=${v}" }

    withRunnerWrapper(envs) {
        sh """
            cd ./canary/webrtc-c/build &&
            ${isMaster ? "" : "sleep 10 &&"}
            ./kvsWebrtcCanaryWebrtc"""
    }
}

def buildSignaling(params) {

    if (params.FIRST_ITERATION) {
        if(params.CACHED_WORKSPACE_ID == "${env.WORKSPACE}") {
            echo "Same workspace: " + params.CACHED_WORKSPACE_ID
            echo "New one: ${env.WORKSPACE}"
        }
    }
    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    buildWebRTCProject(params.USE_MBEDTLS, thing_prefix)

    def scripts_dir = "$WORKSPACE/canary/webrtc-c/scripts"
    def endpoint = "${scripts_dir}/iot-credential-provider.txt"
    def core_cert_file = "${scripts_dir}/${thing_prefix}_certificate.pem"
    def private_key_file = "${scripts_dir}/${thing_prefix}_private.key"
    def role_alias = "${thing_prefix}_role_alias"
    def thing_name = "${thing_prefix}_thing"

    def envs = [
      'AWS_KVS_LOG_LEVEL': params.AWS_KVS_LOG_LEVEL,
      'CANARY_LOG_GROUP_NAME': params.LOG_GROUP_NAME,
      'CANARY_USE_IOT_PROVIDER': params.USE_IOT,
      'CANARY_LOG_STREAM_NAME': "${params.RUNNER_LABEL}-Signaling-${START_TIMESTAMP}",
      'CANARY_CHANNEL_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}",
      'CANARY_LABEL': params.SCENARIO_LABEL,
      'CANARY_DURATION_IN_SECONDS': params.DURATION_IN_SECONDS,
      'AWS_IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
      'AWS_IOT_CORE_CERT': "${core_cert_file}",
      'AWS_IOT_CORE_PRIVATE_KEY': "${private_key_file}",
      'AWS_IOT_CORE_ROLE_ALIAS': "${role_alias}",
      'AWS_IOT_CORE_THING_NAME': "${thing_name}"
    ].collect({ k, v -> "${k}=${v}" })

    withRunnerWrapper(envs) {
        sh """
            cd ./canary/webrtc-c/build &&
            ./kvsWebrtcCanarySignaling"""
    }
}

pipeline {
    agent {
        label params.MASTER_NODE_LABEL
    }

    parameters {
        choice(name: 'AWS_KVS_LOG_LEVEL', choices: ["1", "2", "3", "4", "5"])
        booleanParam(name: 'IS_SIGNALING')
        booleanParam(name: 'USE_TURN')
        booleanParam(name: 'FORCE_TURN')
        booleanParam(name: 'IS_PROFILING')
        booleanParam(name: 'TRICKLE_ICE')
        booleanParam(name: 'USE_IOT')
        booleanParam(name: 'USE_MBEDTLS', defaultValue: false)
        booleanParam(name: 'DEBUG_LOG_SDP', defaultValue: true)
        string(name: 'LOG_GROUP_NAME')
        string(name: 'MASTER_NODE_LABEL')
        string(name: 'VIEWER_NODE_LABEL')
        string(name: 'RUNNER_LABEL')
        string(name: 'SCENARIO_LABEL')
        string(name: 'DURATION_IN_SECONDS')
        string(name: 'VIDEO_CODEC')
        string(name: 'MIN_RETRY_DELAY_IN_SECONDS')
        string(name: 'GIT_URL')
        string(name: 'GIT_HASH')
        string(name: 'AWS_DEFAULT_REGION')
        booleanParam(name: 'FIRST_ITERATION', defaultValue: true)
    }

    environment {
        AWS_KVS_STS_ROLE_ARN = credentials('CANARY_STS_ROLE_ARN')
    }

    stages {
        stage('Fetch STS credentials and export to env vars') {
            steps {
                script {
                    sh "touch '${env.WORKSPACE}/.in_use'"

                    def assumeRoleOutput = sh(script: 'aws sts assume-role --role-arn $AWS_KVS_STS_ROLE_ARN --role-session-name roleSessionName --duration-seconds 43200 --output json',
                                                returnStdout: true
                                                ).trim()
                    def assumeRoleJson = readJSON text: assumeRoleOutput

                    env.AWS_ACCESS_KEY_ID = assumeRoleJson.Credentials.AccessKeyId
                    env.AWS_SECRET_ACCESS_KEY = assumeRoleJson.Credentials.SecretAccessKey
                    env.AWS_SESSION_TOKEN = assumeRoleJson.Credentials.SessionToken
                }
            }
        }

        stage('Set build description') {
            steps {
                script {
                    currentBuild.displayName = "${params.RUNNER_LABEL} [#${BUILD_NUMBER}]"
                    currentBuild.description = "Executed on: ${NODE_NAME}\n"
                }
            }
        }

        stage('Preparation') {
            steps {
              echo params.toString()
            }
        }

        stage('Build and Run Webrtc Canary') {
            failFast true
            when {
                equals expected: false, actual: params.IS_SIGNALING
            }
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

        stage('Build and Run Signaling Canary') {
            failFast true
            when {
                equals expected: true, actual: params.IS_SIGNALING
            }

            steps {
                script {
                    buildSignaling(params)
                }
            }
            post {
                always {
                    script {
                        CACHED_WORKSPACE_ID = "${env.WORKSPACE}"
                        echo "Cached workspace id post job: ${CACHED_WORKSPACE_ID}"
                    }
                }
            }
        }

        stage('Unset credentials') {
            steps {
                script {
                    env.AWS_ACCESS_KEY_ID = ''
                    env.AWS_SECRET_ACCESS_KEY = ''
                    env.AWS_SESSION_TOKEN = ''
                }
            }
        }

        stage('Throttling Retry') {
            when {
                equals expected: true, actual: HAS_ERROR
            }

            steps {
                sleep Math.max(0, params.MIN_RETRY_DELAY_IN_SECONDS.toInteger() - currentBuild.duration.intdiv(1000))
            }
        }

    }

    post {
        always {
            script {
                sh "rm -f '${env.WORKSPACE}/.in_use'"

                if (currentBuild.result == 'ABORTED') {
                    echo "Build was aborted, skipping reschedule"
                } else {
                    def rescheduleParams = [
                      string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                      string(name: 'AWS_KVS_LOG_LEVEL', value: params.AWS_KVS_LOG_LEVEL),
                      booleanParam(name: 'DEBUG_LOG_SDP', value: params.DEBUG_LOG_SDP),
                      booleanParam(name: 'IS_SIGNALING', value: params.IS_SIGNALING),
                      booleanParam(name: 'USE_TURN', value: params.USE_TURN),
                      booleanParam(name: 'FORCE_TURN', value: params.FORCE_TURN),
                      booleanParam(name: 'USE_IOT', value: params.USE_IOT),
                      booleanParam(name: 'IS_PROFILING', value: params.IS_PROFILING),
                      booleanParam(name: 'TRICKLE_ICE', value: params.TRICKLE_ICE),
                      booleanParam(name: 'USE_MBEDTLS', value: params.USE_MBEDTLS),
                      string(name: 'LOG_GROUP_NAME', value: params.LOG_GROUP_NAME),
                      string(name: 'MASTER_NODE_LABEL', value: params.MASTER_NODE_LABEL),
                      string(name: 'VIEWER_NODE_LABEL', value: params.VIEWER_NODE_LABEL),
                      string(name: 'RUNNER_LABEL', value: params.RUNNER_LABEL),
                      string(name: 'SCENARIO_LABEL', value: params.SCENARIO_LABEL),
                      string(name: 'DURATION_IN_SECONDS', value: params.DURATION_IN_SECONDS),
                      string(name: 'VIDEO_CODEC', value: params.VIDEO_CODEC),
                      string(name: 'MIN_RETRY_DELAY_IN_SECONDS', value: params.MIN_RETRY_DELAY_IN_SECONDS),
                      string(name: 'GIT_URL', value: params.GIT_URL),
                      string(name: 'GIT_HASH', value: params.GIT_HASH),
                      booleanParam(name: 'FIRST_ITERATION', value: false)
                    ]

                    try {
                        build(job: env.JOB_NAME, parameters: rescheduleParams, wait: false)
                    } catch (err) {
                        echo "WARNING: Reschedule failed: ${err.getMessage()}, retrying in 5s..."
                        try {
                            sleep 5
                            build(job: env.JOB_NAME, parameters: rescheduleParams, wait: false)
                        } catch (retryErr) {
                            echo "ERROR: Reschedule retry also failed: ${retryErr.getMessage()}"
                        }
                    }
                }
            }
        }
    }
}
