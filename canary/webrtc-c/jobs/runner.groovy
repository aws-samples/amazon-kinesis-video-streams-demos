import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

START_TIMESTAMP = new Date().getTime()
RUNNING_NODES_IN_BUILDING = 0
HAS_ERROR = false
CREDENTIALS = [
    [
        $class: 'AmazonWebServicesCredentialsBinding', 
        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
        credentialsId: 'CANARY_CREDENTIALS',
        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
    ]
]

def buildProject(useMbedTLS) {
    checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH ]],
              userRemoteConfigs: [[url: params.GIT_URL]]])

    def configureCmd = "cmake .. -DCMAKE_INSTALL_PREFIX=\"\$PWD\""
    if (useMbedTLS) {
      configureCmd += " -DUSE_OPENSSL=OFF -DUSE_MBEDTLS=ON"
    }     

    sh """
        cd ./canary/webrtc-c/scripts &&
        chmod a+x cert_setup.sh &&
        ./cert_setup.sh ${NODE_NAME} &&
        ls &&
        cd .. &&
        mkdir -p build && 
        cd build && 
        ${configureCmd} && 
        make -j"""
}

def withRunnerWrapper(envs, fn) {
    withEnv(envs) {
        withCredentials(CREDENTIALS) {
            try {
                fn()
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

def buildPeer(isMaster, params) {
    def clientID = isMaster ? "Master" : "Viewer"
    RUNNING_NODES_IN_BUILDING++

    // TODO: get the branch and version from orchestrator
    if (params.FIRST_ITERATION) {
        deleteDir()
    }
    
    buildProject(params.USE_MBEDTLS)

    RUNNING_NODES_IN_BUILDING--
    
    waitUntil {
        RUNNING_NODES_IN_BUILDING == 0
    }

    def endpoint = readFile('canary/webrtc-c/scripts/iot-credential-provider.txt')
    def scripts_dir = "$WORKSPACE/canary/webrtc-c/scripts"
    def core_cert_file = "${scripts_dir}/w${NODE_NAME}_certificate.pem"
    def private_key_file = "${scripts_dir}/w${NODE_NAME}_private.key"
    def role_alias = "w${NODE_NAME}_role_alias"
    def thing_name = "w${NODE_NAME}_thing"

    def envs = [
      'AWS_KVS_LOG_LEVEL': params.AWS_KVS_LOG_LEVEL,
      'CANARY_USE_TURN': params.USE_TURN,
      'CANARY_TRICKLE_ICE': params.TRICKLE_ICE,
      'CANARY_USE_IOT_PROVIDER': params.USE_IOT,
      'CANARY_LOG_GROUP_NAME': params.LOG_GROUP_NAME,
      'CANARY_LOG_STREAM_NAME': "${params.RUNNER_LABEL}-${clientID}-${START_TIMESTAMP}",
      'CANARY_CHANNEL_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}",
      'CANARY_LABEL': params.SCENARIO_LABEL,
      'CANARY_CLIENT_ID': clientID,
      'CANARY_IS_MASTER': isMaster,
      'CANARY_DURATION_IN_SECONDS': params.DURATION_IN_SECONDS,
      'AWS_IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
      'AWS_IOT_CORE_CERT': "${core_cert_file}",
      'AWS_IOT_CORE_PRIVATE_KEY': "${private_key_file}",
      'AWS_IOT_CORE_ROLE_ALIAS': "${role_alias}",
      'AWS_IOT_CORE_THING_NAME': "${thing_name}"
    ].collect{ k, v -> "${k}=${v}" }

    withRunnerWrapper(envs) {
        sh """
            cd ./canary/webrtc-c/scripts &&
            cd .. &&
            cd build &&
            ${isMaster ? "" : "sleep 10 &&"}
            ./kvsWebrtcCanaryWebrtc"""
    }
}

def buildSignaling(params) {
    def envs = [
      'AWS_KVS_LOG_LEVEL': params.AWS_KVS_LOG_LEVEL,
      'CANARY_LOG_GROUP_NAME': params.LOG_GROUP_NAME,
      'CANARY_LOG_STREAM_NAME': "${params.RUNNER_LABEL}-Signaling-${START_TIMESTAMP}",
      'CANARY_CHANNEL_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}",
      'CANARY_LABEL': params.SCENARIO_LABEL,
      'CANARY_DURATION_IN_SECONDS': params.DURATION_IN_SECONDS
    ].collect({ k, v -> "${k}=${v}" })
    
    // TODO: get the branch and version from orchestrator
    if (params.FIRST_ITERATION) {
        deleteDir()
    }
    buildProject(params.USE_MBEDTLS)

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
        booleanParam(name: 'TRICKLE_ICE')
        booleanParam(name: 'USE_MBEDTLS', defaultValue: false)
        string(name: 'LOG_GROUP_NAME')
        string(name: 'MASTER_NODE_LABEL')
        string(name: 'VIEWER_NODE_LABEL')
        string(name: 'RUNNER_LABEL')
        string(name: 'SCENARIO_LABEL')
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
                      booleanParam(name: 'IS_SIGNALING', value: params.IS_SIGNALING),
                      booleanParam(name: 'USE_TURN', value: params.USE_TURN),
                      booleanParam(name: 'USE_IOT', value: params.USE_IOT),
                      booleanParam(name: 'TRICKLE_ICE', value: params.TRICKLE_ICE),
                      booleanParam(name: 'USE_MBEDTLS', value: params.USE_MBEDTLS),
                      string(name: 'LOG_GROUP_NAME', value: params.LOG_GROUP_NAME),
                      string(name: 'MASTER_NODE_LABEL', value: params.MASTER_NODE_LABEL),
                      string(name: 'VIEWER_NODE_LABEL', value: params.VIEWER_NODE_LABEL),
                      string(name: 'RUNNER_LABEL', value: params.RUNNER_LABEL),
                      string(name: 'SCENARIO_LABEL', value: params.SCENARIO_LABEL),
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
