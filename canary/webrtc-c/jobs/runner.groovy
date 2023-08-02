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

def buildProject(useMbedTLS, thing_prefix) {
    checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH ]],
              userRemoteConfigs: [[url: params.GIT_URL]]])

    def configureCmd = "cmake .. -DCMAKE_INSTALL_PREFIX=\"\$PWD\""
    if (useMbedTLS) {
      configureCmd += " -DUSE_OPENSSL=OFF -DUSE_MBEDTLS=ON"
    }     

    sh """
        cd ./canary/webrtc-c/scripts &&
        chmod a+x cert_setup.sh &&
        ./cert_setup.sh ${thing_prefix} &&
        cd .. &&
        mkdir -p build && 
        cd build && 
        ${configureCmd} && 
        make"""
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

    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    buildProject(params.USE_MBEDTLS, thing_prefix)

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
            cd ./canary/webrtc-c/build &&
            ${isMaster ? "" : "sleep 10 &&"}
            ./kvsWebrtcCanaryWebrtc"""
    }
}

def buildStorageMasterPeer(params) {
    def clientID = "StorageMaster"
    RUNNING_NODES_IN_BUILDING++

    // TODO: get the branch and version from orchestrator
    if (params.FIRST_ITERATION) {
        deleteDir()
    }

    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    buildProject(params.USE_MBEDTLS, thing_prefix)

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
      'CANARY_USE_TURN': params.USE_TURN,
      'CANARY_TRICKLE_ICE': params.TRICKLE_ICE,
      'CANARY_USE_IOT_PROVIDER': params.USE_IOT,
      'CANARY_LOG_GROUP_NAME': params.LOG_GROUP_NAME,
      'CANARY_LOG_STREAM_NAME': "${params.RUNNER_LABEL}-${clientID}-${START_TIMESTAMP}",

      'CANARY_CHANNEL_NAME': "aTestChannel", //  TODO: replace hardcoded name with descriptive, labeled name
      
      'CANARY_LABEL': params.SCENARIO_LABEL,
      'CANARY_CLIENT_ID': clientID,
      'CANARY_IS_MASTER': true,
      'CANARY_DURATION_IN_SECONDS': params.DURATION_IN_SECONDS,
      'AWS_IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
      'AWS_IOT_CORE_CERT': "${core_cert_file}",
      'AWS_IOT_CORE_PRIVATE_KEY': "${private_key_file}",
      'AWS_IOT_CORE_ROLE_ALIAS': "${role_alias}",
      'AWS_IOT_CORE_THING_NAME': "${thing_name}"
    ].collect{ k, v -> "${k}=${v}" }

    withRunnerWrapper(envs) {
        sh """
            cd ./canary/webrtc-c/build &&
            ${"sleep 10 &&"}
            ./kvsWebrtcCanaryWebrtcStorage"""
    }
}

def buildStorageConsumerPeer(params) {
    def clientID = "StorageConsumer"

    def consumerEnvs = [        
        'JAVA_HOME': "/opt/jdk-13.0.1",
        'M2_HOME': "/opt/apache-maven-3.6.3"
    ].collect({k,v -> "${k}=${v}" })

    // TODO: get the branch and version from orchestrator
    if (params.FIRST_ITERATION) {
        deleteDir()
    }

    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    buildProject(params.USE_MBEDTLS, thing_prefix)

    def consumerStartUpDelay = 45
    echo "NODE_NAME = ${env.NODE_NAME}"

    checkout([
        scm: [
            $class: 'GitSCM', 
            branches: [[name: params.GIT_HASH]],
            userRemoteConfigs: [[url: params.GIT_URL]]
        ]
    ])

    RUNNING_NODES_IN_BUILDING++
    echo "Number of running nodes: ${RUNNING_NODES_IN_BUILDING}"

    sleep consumerStartUpDelay
    withEnv(consumerEnvs) {
        sh '''
            PATH="$JAVA_HOME/bin:$PATH"
            export PATH="$M2_HOME/bin:$PATH"
            cd ./canary/consumer-java
            make -j4
        '''
    }

    RUNNING_NODES_IN_BUILDING--
    echo "Number of running nodes after build: ${RUNNING_NODES_IN_BUILDING}"
    waitUntil {
        RUNNING_NODES_IN_BUILDING == 0
    }
    
    echo "Done waiting in NODE_NAME = ${env.NODE_NAME}"

    def scripts_dir = "$WORKSPACE/canary/webrtc-c/scripts"
    def endpoint = "${scripts_dir}/iot-credential-provider.txt"
    def core_cert_file = "${scripts_dir}/${thing_prefix}_certificate.pem"
    def private_key_file = "${scripts_dir}/${thing_prefix}_private.key"
    def role_alias = "${thing_prefix}_role_alias"
    def thing_name = "${thing_prefix}_thing"

    def envs = [
        'JAVA_HOME': "/opt/jdk-13.0.1",
        'M2_HOME': "/opt/apache-maven-3.6.3",
        'AWS_KVS_LOG_LEVEL': params.AWS_KVS_LOG_LEVEL,

        'CANARY_STREAM_NAME': "aTestStream", //  TODO: replace hardcoded name with descriptive, labeled name

        'CANARY_LABEL': params.RUNNER_LABEL,
        'CANARY_TYPE': "Realtime",
        'FRAGMENT_SIZE_IN_BYTES' : "1048576", // TODO: eliminate this from the consumer apps? not used in the apps
        'CANARY_DURATION_IN_SECONDS': params.DURATION_IN_SECONDS,
        'AWS_DEFAULT_REGION': params.AWS_DEFAULT_REGION,
        'CANARY_RUN_SCENARIO': params.SCENARIO_LABEL,
        'TRACK_TYPE': "SingleTrack",
        'CANARY_USE_IOT_PROVIDER': params.USE_IOT,
        'AWS_IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
        'AWS_IOT_CORE_CERT': "${core_cert_file}",
        'AWS_IOT_CORE_PRIVATE_KEY': "${private_key_file}",
        'AWS_IOT_CORE_ROLE_ALIAS': "${role_alias}",
        'AWS_IOT_CORE_THING_NAME': "${thing_name}"
    ].collect({k,v -> "${k}=${v}" })

    withRunnerWrapper(envs) {
        sh '''
            cd $WORKSPACE/canary/consumer-java
            java -classpath target/aws-kinesisvideo-producer-sdk-canary-consumer-1.0-SNAPSHOT.jar:$(cat tmp_jar) -Daws.accessKeyId=${AWS_ACCESS_KEY_ID} -Daws.secretKey=${AWS_SECRET_ACCESS_KEY} com.amazon.kinesis.video.canary.consumer.WebrtcStorageCanaryConsumer
        '''
    }
}

def buildSignaling(params) {

    // TODO: get the branch and version from orchestrator
    if (params.FIRST_ITERATION) {
        deleteDir()
    }
    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    buildProject(params.USE_MBEDTLS, thing_prefix)

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
        booleanParam(name: 'TRICKLE_ICE')
        booleanParam(name: 'USE_MBEDTLS', defaultValue: false)
        string(name: 'LOG_GROUP_NAME')
        string(name: 'MASTER_NODE_LABEL')
        string(name: 'CONSUMER_NODE_LABEL')
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

        stage('Build and Run Webrtc Storage Master and Consumer Canaries') {
            failFast true
            when {
                allOf {
                    equals expected: false, actual: params.IS_SIGNALING
                    equals expected: true, actual: params.IS_STORAGE
                }
            }
            parallel {
                stage('StorageMaster') {
                    steps {
                        script {
                            buildStorageMasterPeer(params)
                        }
                    }
                }
                stage('StorageConsumer') {
                     agent {
                        label params.CONSUMER_NODE_LABEL
                    }
                    steps {
                        script {
                            buildStorageConsumerPeer(params)
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
                      booleanParam(name: 'IS_SIGNALING', value: params.IS_SIGNALING),
                      booleanParam(name: 'IS_STORAGE', value: params.IS_STORAGE),
                      booleanParam(name: 'USE_TURN', value: params.USE_TURN),
                      booleanParam(name: 'USE_IOT', value: params.USE_IOT),
                      booleanParam(name: 'TRICKLE_ICE', value: params.TRICKLE_ICE),
                      booleanParam(name: 'USE_MBEDTLS', value: params.USE_MBEDTLS),
                      string(name: 'LOG_GROUP_NAME', value: params.LOG_GROUP_NAME),
                      string(name: 'MASTER_NODE_LABEL', value: params.MASTER_NODE_LABEL),
                      string(name: 'CONSUMER_NODE_LABEL', value: params.CONSUMER_NODE_LABEL),
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
