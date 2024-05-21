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

def buildWebRTCProject(useMbedTLS, config_file_header, thing_prefix) {
    echo 'Flag set to ' + useMbedTLS
    checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH]], userRemoteConfigs: [[url: params.GIT_URL]]])

    def config_file_path = "../cloudwatch-integ/"
    config_file_path += "${config_file_header}"
    echo "Config file path: ${config_file_path}"
    def configureCmd = "cmake .. -DSAMPLE_CONFIG_HEADER=${config_file_path} -DCMAKE_BUILD_TYPE=Debug"
    echo "Configure Command: ${configureCmd}"
    if (useMbedTLS) {
      echo 'Using mbedtls'
      configureCmd += " -DUSE_OPENSSL=OFF -DUSE_MBEDTLS=ON"
    }

    sh """
        mkdir -p build &&
        cd build &&
        ${configureCmd} &&
        make"""
}

def buildConsumerProject() {
    // TODO: should probably remove this - not needed for webrtc consumer
    def consumerStartUpDelay = 45
    sleep consumerStartUpDelay

    checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH ]],
              userRemoteConfigs: [[url: params.GIT_URL]]])
              
    def consumerEnvs = [        
        'JAVA_HOME': "/usr/lib/jvm/default-java",
        'M2_HOME': "/usr/share/maven"
    ].collect({k,v -> "${k}=${v}" })

    withEnv(consumerEnvs) {
        sh '''
            PATH="$JAVA_HOME/bin:$PATH"
            export PATH="$M2_HOME/bin:$PATH"
            cd ./canary/consumer-java
            make -j4'''
    }
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
    buildWebRTCProject(params.USE_MBEDTLS, params.CONFIG_FILE_HEADER, thing_prefix)

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
      'IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
      'IOT_CORE_CERT': "${core_cert_file}",
      'IOT_CORE_PRIVATE_KEY': "${private_key_file}",
      'IOT_CORE_ROLE_ALIAS': "${role_alias}",
      'IOT_CORE_THING_NAME': "${thing_name}"
    ].collect{ k, v -> "${k}=${v}" }

    withRunnerWrapper(envs) {
        sh """
            cd ./canary/webrtc-c/build &&
            ${isMaster ? "" : "sleep 10 &&"}
            ${isMaster} ? "./cloudwatch-integ/kvsWebrtcClientMasterCW ${env.JOB_NAME}" : "./cloudwatch-integ/kvsWebrtcClientViewerCW ${env.JOB_NAME}"}
        """
    }
}

def buildStorageCanary(isConsumer, params) {
    def scripts_dir = !isConsumer ? "$WORKSPACE/canary/webrtc-c/scripts" :
        "$WORKSPACE/canary/webrtc-c/scripts"
    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    def endpoint = "${scripts_dir}/iot-credential-provider.txt"
    def core_cert_file = "${scripts_dir}/${thing_prefix}_certificate.pem"
    def private_key_file = "${scripts_dir}/${thing_prefix}_private.key"
    def role_alias = "${thing_prefix}_role_alias"
    def thing_name = "${thing_prefix}_thing"

    def commonEnvs = [
      'AWS_KVS_LOG_LEVEL': params.AWS_KVS_LOG_LEVEL,
      'CANARY_USE_IOT_PROVIDER': params.USE_IOT,
      'CANARY_LABEL': params.SCENARIO_LABEL,
      'IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
      'IOT_CORE_CERT': "${core_cert_file}",
      'IOT_CORE_PRIVATE_KEY': "${private_key_file}",
      'IOT_CORE_ROLE_ALIAS': "${role_alias}",
      'IOT_CORE_THING_NAME': "${thing_name}"
    ]

    def masterEnvs = [
      'CANARY_LOG_STREAM_NAME': "${params.RUNNER_LABEL}-StorageMaster-${START_TIMESTAMP}",
      'CANARY_CLIENT_ID': "Master",
      'CANARY_IS_MASTER': true,
      'CANARY_CHANNEL_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    ]

    def consumerEnvs = [
      'JAVA_HOME': "/opt/jdk-11.0.20",
      'M2_HOME': "/opt/apache-maven-3.6.3",
      'AWS_DEFAULT_REGION': params.AWS_DEFAULT_REGION,
      'CANARY_STREAM_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    ]

    RUNNING_NODES_IN_BUILDING++
    if (params.FIRST_ITERATION) {
        deleteDir()
    }
    if (!isConsumer){
        buildWebRTCProject(params.USE_MBEDTLS, params.CONFIG_FILE_HEADER, thing_prefix)
    } else {
        buildConsumerProject()
    }
    RUNNING_NODES_IN_BUILDING--
    
    waitUntil {
        RUNNING_NODES_IN_BUILDING == 0
    }

    if (!isConsumer) {
        def envs = (commonEnvs + masterEnvs).collect{ k, v -> "${k}=${v}" }
        withRunnerWrapper(envs) {
            sh """
                cd ./canary/webrtc-c/build &&
                ./kvsWebrtcStorageSample"""
        }
    } else {
        def envs = (commonEnvs + consumerEnvs).collect{ k, v -> "${k}=${v}" }
        withRunnerWrapper(envs) {
            sh '''
                cd $WORKSPACE/canary/consumer-java
                java -classpath target/aws-kinesisvideo-producer-sdk-canary-consumer-1.0-SNAPSHOT.jar:$(cat tmp_jar) -Daws.accessKeyId=${AWS_ACCESS_KEY_ID} -Daws.secretKey=${AWS_SECRET_ACCESS_KEY} com.amazon.kinesis.video.canary.consumer.WebrtcStorageCanaryConsumer
            '''
        }
    }
}

pipeline {
    agent {
        label params.MASTER_NODE_LABEL
    }

    parameters {
        choice(name: 'AWS_KVS_LOG_LEVEL', choices: ["1", "2", "3", "4", "5"])
        booleanParam(name: 'IS_STORAGE')
        booleanParam(name: 'IS_STORAGE_SINGLE_NODE')
        booleanParam(name: 'USE_MBEDTLS', defaultValue: false)
        booleanParam(name: 'DEBUG_LOG_SDP', defaultValue: true)
        string(name: 'MASTER_NODE_LABEL')
        string(name: 'CONSUMER_NODE_LABEL')
        string(name: 'VIEWER_NODE_LABEL')
        string(name: 'RUNNER_LABEL')
        string(name: 'MIN_RETRY_DELAY_IN_SECONDS')
        string(name: 'GIT_URL')
        string(name: 'GIT_HASH')
        string(name: 'CONFIG_FILE_HEADER')
        booleanParam(name: 'FIRST_ITERATION', defaultValue: true)
    }

    stages {
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
                allOf {
                    equals expected: false, actual: params.IS_STORAGE
                    equals expected: false, actual: params.IS_STORAGE_SINGLE_NODE 

                }
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

//         stage('Build and Run Webrtc-Storage Master and Consumer Canaries') {
//             failFast true
//             when {
//                 allOf {
//                     equals expected: false, actual: params.IS_SIGNALING
//                     equals expected: true, actual: params.IS_STORAGE
//                     equals expected: false, actual: params.IS_STORAGE_SINGLE_NODE
//                 }
//             }
//             parallel {
//                 stage('StorageMaster') {
//                     steps {
//                         script {
//                             buildStorageCanary(false, params)
//                         }
//                     }
//                 }
//                 stage('StorageConsumer') {
//                      agent {
//                         label params.CONSUMER_NODE_LABEL
//                     }
//                     steps {
//                         script {
//                             buildStorageCanary(true, params)
//                         }
//                     }
//                 }
//             }
//         }
//
//
//         stage('Build and Run Webrtc-Storage Master and Consumer Canaries on Same Node') {
//             failFast true
//             when {
//                 allOf {
//                     equals expected: false, actual: params.IS_SIGNALING
//                     equals expected: true, actual: params.IS_STORAGE
//                     equals expected: true, actual: params.IS_STORAGE_SINGLE_NODE
//                 }
//             }
//             parallel {
//                 stage('StorageMaster') {
//                     steps {
//                         script {
//                             buildStorageCanary(false, params)
//                         }
//                     }
//                 }
//                 stage('StorageConsumer') {
//                     steps {
//                         script {
//                             buildStorageCanary(true, params)
//                         }
//                     }
//                 }
//             }
//         }

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
                      string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                      string(name: 'AWS_KVS_LOG_LEVEL', value: params.AWS_KVS_LOG_LEVEL),
                      booleanParam(name: 'DEBUG_LOG_SDP', value: params.DEBUG_LOG_SDP),
                      booleanParam(name: 'IS_STORAGE', value: params.IS_STORAGE),
                      booleanParam(name: 'IS_STORAGE_SINGLE_NODE', value: params.IS_STORAGE_SINGLE_NODE),
                      booleanParam(name: 'USE_MBEDTLS', value: params.USE_MBEDTLS),
                      string(name: 'MASTER_NODE_LABEL', value: params.MASTER_NODE_LABEL),
                      string(name: 'CONSUMER_NODE_LABEL', value: params.CONSUMER_NODE_LABEL),
                      string(name: 'VIEWER_NODE_LABEL', value: params.VIEWER_NODE_LABEL),
                      string(name: 'RUNNER_LABEL', value: params.RUNNER_LABEL),
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
