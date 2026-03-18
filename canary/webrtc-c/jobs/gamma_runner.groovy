import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

/**
 * Gamma Test Runner
 * 
 * This is a dedicated runner for gamma tests. It runs the WebRTC storage master
 * and JS viewer tests against a custom endpoint.
 * 
 * Key differences from production runner:
 *   - No automatic rescheduling (runs once and stops)
 *   - Simplified configuration
 *   - Dedicated for gamma/testing purposes
 * 
 * Do not run this directly - use webrtc-gamma-orchestrator instead.
 */

START_TIMESTAMP = new Date().getTime()
RUNNING_NODES_IN_BUILDING = 0
HAS_ERROR = false

def buildWebRTCProject(thing_prefix) {
    checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH]],
              userRemoteConfigs: [[url: params.GIT_URL]]])

    def configureCmd = "cmake .. -DCMAKE_BUILD_TYPE=Debug -DCMAKE_INSTALL_PREFIX=\"\$PWD\""

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
        try {
            fn()
        } catch (FlowInterruptedException err) {
            echo 'Aborted due to cancellation'
            throw err
        } catch (err) {
            HAS_ERROR = true
            unstable err.toString()
        }
    }
}

def runViewerSessions(viewerId = "", waitMinutes = 2, viewerCount = "1", staggerDelaySeconds = 0) {
    def workspaceName = "${env.JOB_NAME}-${viewerId ?: 'viewer'}-${BUILD_NUMBER}"
    ws(workspaceName) {
        try {
            deleteDir()
            checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH]],
                      userRemoteConfigs: [[url: params.GIT_URL]]])
            
            // Wait for master to build before starting viewers
            if (waitMinutes > 0) {
                echo "Waiting ${waitMinutes} minutes for master to build"
                sleep waitMinutes * 60
            }
            
            // Staggered start delay to avoid all viewers starting at the exact same time
            if (staggerDelaySeconds > 0) {
                echo "Staggered start - waiting ${staggerDelaySeconds} seconds before starting ${viewerId ?: 'viewer'}"
                sleep staggerDelaySeconds
            }
            
            def endpointValue = params.ENDPOINT ?: ''
            def metricSuffixValue = params.METRIC_SUFFIX ?: ''
            
            echo "=========================================="
            echo "Viewer Configuration"
            echo "=========================================="
            echo "Viewer ID: ${viewerId ?: 'default'}"
            echo "Endpoint: ${endpointValue}"
            echo "Metric Suffix: ${metricSuffixValue}"
            echo "Region: ${params.AWS_DEFAULT_REGION}"
            echo "=========================================="
            
            // Run 3 viewer sessions
            for (int session = 1; session <= 3; session++) {
                echo "Starting ${viewerId ? viewerId + ' ' : ''}session ${session}/3"
                
                def viewerSessionDuration = (params.VIEWER_SESSION_DURATION_SECONDS != null && params.VIEWER_SESSION_DURATION_SECONDS.toString().trim() != '') 
                    ? params.VIEWER_SESSION_DURATION_SECONDS 
                    : '600'
                
                try {
                    sh """
                        export JOB_NAME="${env.JOB_NAME}"
                        export RUNNER_LABEL="${params.RUNNER_LABEL}"
                        export AWS_DEFAULT_REGION="${params.AWS_DEFAULT_REGION}"
                        export DURATION_IN_SECONDS="${viewerSessionDuration}"
                        export FORCE_TURN="${params.FORCE_TURN ?: 'false'}"
                        export VIEWER_COUNT="${viewerCount}"
                        export VIEWER_ID="${viewerId}"
                        export CLIENT_ID="${viewerId ? viewerId.toLowerCase() + '-' : 'viewer-'}session-${session}-${BUILD_NUMBER}"
                        export ENDPOINT="${endpointValue}"
                        export METRIC_SUFFIX="${metricSuffixValue}"
                        
                        ./canary/webrtc-c/scripts/setup-storage-viewer.sh
                    """
                } catch (FlowInterruptedException err) {
                    echo 'Aborted due to cancellation'
                    throw err
                } catch (err) {
                    HAS_ERROR = true
                    unstable err.toString()
                }
                
                if (session < 3) {
                    echo "${viewerId ? viewerId + ' ' : ''}session ${session} completed. Waiting 1 minute before next session."
                    sleep 60
                }
            }
            echo "All 3 ${viewerId ? viewerId + ' ' : ''}sessions completed"
        } finally {
            deleteDir()
        }
    }
}

def buildStorageCanary(params) {
    def scripts_dir = "$WORKSPACE/canary/webrtc-c/scripts"
    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    def endpoint = "${scripts_dir}/iot-credential-provider.txt"
    def core_cert_file = "${scripts_dir}/${thing_prefix}_certificate.pem"
    def private_key_file = "${scripts_dir}/${thing_prefix}_private.key"
    def role_alias = "${thing_prefix}_role_alias"
    def thing_name = "${thing_prefix}_thing"

    def envs = [
        'AWS_KVS_LOG_LEVEL': params.AWS_KVS_LOG_LEVEL,
        'CANARY_USE_IOT_PROVIDER': params.USE_IOT ?: false,
        'CANARY_LABEL': params.SCENARIO_LABEL,
        'CANARY_DURATION_IN_SECONDS': params.DURATION_IN_SECONDS,
        'CANARY_USE_TURN': params.USE_TURN ?: false,
        'CANARY_TRICKLE_ICE': params.TRICKLE_ICE ?: false,
        'CANARY_LOG_GROUP_NAME': params.LOG_GROUP_NAME,
        'CANARY_LOG_STREAM_NAME': "${params.RUNNER_LABEL}-StorageMaster-${START_TIMESTAMP}",
        'CANARY_CLIENT_ID': "Master",
        'CANARY_IS_MASTER': true,
        'CANARY_CHANNEL_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}",
        'AWS_DEFAULT_REGION': params.AWS_DEFAULT_REGION,
        'CONTROL_PLANE_URI': params.ENDPOINT ?: '',
        'AWS_IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
        'AWS_IOT_CORE_CERT': "${core_cert_file}",
        'AWS_IOT_CORE_PRIVATE_KEY': "${private_key_file}",
        'AWS_IOT_CORE_ROLE_ALIAS': "${role_alias}",
        'AWS_IOT_CORE_THING_NAME': "${thing_name}"
    ].collect{ k, v -> "${k}=${v}" }

    RUNNING_NODES_IN_BUILDING++
    if (params.FIRST_ITERATION) {
        deleteDir()
    }
    buildWebRTCProject(thing_prefix)
    RUNNING_NODES_IN_BUILDING--
    
    waitUntil {
        RUNNING_NODES_IN_BUILDING == 0
    }

    withRunnerWrapper(envs) {
        sh """
            cd ./canary/webrtc-c/build &&
            ./kvsWebrtcStorageSample"""
    }
}

pipeline {
    agent {
        label params.MASTER_NODE_LABEL
    }

    parameters {
        string(name: 'AWS_KVS_LOG_LEVEL', defaultValue: '2')
        string(name: 'LOG_GROUP_NAME', defaultValue: 'WebrtcSDK')
        string(name: 'MASTER_NODE_LABEL', defaultValue: 'webrtc-storage-master-2')
        string(name: 'STORAGE_VIEWER_NODE_LABEL', defaultValue: 'webrtc-storage-viewer')
        string(name: 'STORAGE_VIEWER_ONE_NODE_LABEL', defaultValue: 'webrtc-storage-multi-viewer-1')
        string(name: 'STORAGE_VIEWER_TWO_NODE_LABEL', defaultValue: 'webrtc-storage-multi-viewer-2')
        string(name: 'STORAGE_VIEWER_THREE_NODE_LABEL', defaultValue: 'webrtc-storage-consumer')
        string(name: 'RUNNER_LABEL', defaultValue: 'GammaTest')
        string(name: 'SCENARIO_LABEL', defaultValue: 'GammaTest')
        string(name: 'DURATION_IN_SECONDS', defaultValue: '600')
        string(name: 'GIT_URL', defaultValue: 'https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git')
        string(name: 'GIT_HASH', defaultValue: 'clean_viewer_test')
        string(name: 'AWS_DEFAULT_REGION', defaultValue: 'us-west-2')
        string(name: 'ENDPOINT', defaultValue: '', description: 'Custom endpoint URL (e.g., gamma endpoint)')
        string(name: 'METRIC_SUFFIX', defaultValue: '-gamma')
        string(name: 'VIEWER_WAIT_MINUTES', defaultValue: '2', description: 'Minutes to wait for master to build')
        string(name: 'VIEWER_SESSION_DURATION_SECONDS', defaultValue: '600', description: 'Duration in seconds for each viewer session (default 10 minutes)')
        booleanParam(name: 'DEBUG_LOG_SDP', defaultValue: true)
        booleanParam(name: 'FIRST_ITERATION', defaultValue: true)
        booleanParam(name: 'JS_STORAGE_VIEWER_JOIN', defaultValue: false)
        booleanParam(name: 'JS_STORAGE_TWO_VIEWERS', defaultValue: false)
        booleanParam(name: 'JS_STORAGE_THREE_VIEWERS', defaultValue: false)
        booleanParam(name: 'RESCHEDULE', defaultValue: false, description: 'Whether to reschedule after completion')
        booleanParam(name: 'USE_TURN', defaultValue: false)
        booleanParam(name: 'USE_IOT', defaultValue: false)
        booleanParam(name: 'TRICKLE_ICE', defaultValue: false)
        booleanParam(name: 'FORCE_TURN', defaultValue: false)
    }
    
    environment {
        AWS_KVS_STS_ROLE_ARN = credentials('CANARY_STS_ROLE_ARN')
    }

    stages {
        stage('Fetch STS credentials') {
            steps {
                script {
                    def assumeRoleOutput = sh(script: 'aws sts assume-role --role-arn $AWS_KVS_STS_ROLE_ARN --role-session-name roleSessionName --duration-seconds 43200 --output json',
                                                returnStdout: true).trim()
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
                    currentBuild.description = "Endpoint: ${params.ENDPOINT}\nRegion: ${params.AWS_DEFAULT_REGION}"
                }
            }
        }

        stage('Preparation') {
            steps {
                script {
                    echo "=========================================="
                    echo "Gamma Runner Configuration"
                    echo "=========================================="
                    echo "Endpoint: ${params.ENDPOINT}"
                    echo "Region: ${params.AWS_DEFAULT_REGION}"
                    echo "Viewer Wait: ${params.VIEWER_WAIT_MINUTES} minutes"
                    echo "Test Type: ${params.JS_STORAGE_VIEWER_JOIN ? '1 Viewer' : params.JS_STORAGE_TWO_VIEWERS ? '2 Viewers' : params.JS_STORAGE_THREE_VIEWERS ? '3 Viewers' : 'Unknown'}"
                    echo "=========================================="
                }
            }
        }

        stage('Single Viewer with Continuous Master') {
            when {
                equals expected: true, actual: params.JS_STORAGE_VIEWER_JOIN
            }
            parallel {
                stage('Continuous StorageMaster') {
                    agent {
                        label params.MASTER_NODE_LABEL
                    }
                    steps {
                        script {
                            def mutableParams = [:] + params
                            mutableParams.DURATION_IN_SECONDS = "1380"
                            buildStorageCanary(mutableParams)
                        }
                    }
                }
                stage('StorageViewer') {
                    agent {
                        label params.STORAGE_VIEWER_NODE_LABEL
                    }
                    steps {
                        script {
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') 
                                ? params.VIEWER_WAIT_MINUTES.toInteger() 
                                : 2
                            runViewerSessions("", waitMins, "1")
                        }
                    }
                }
            }
        }

        stage('Two Viewers with Continuous Master') {
            when {
                equals expected: true, actual: params.JS_STORAGE_TWO_VIEWERS
            }
            parallel {
                stage('Continuous StorageMaster') {
                    agent {
                        label params.MASTER_NODE_LABEL
                    }
                    steps {
                        script {
                            def mutableParams = [:] + params
                            mutableParams.DURATION_IN_SECONDS = "1380"
                            buildStorageCanary(mutableParams)
                        }
                    }
                }
                stage('StorageViewer1') {
                    agent {
                        label params.STORAGE_VIEWER_ONE_NODE_LABEL
                    }
                    steps {
                        script {
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') 
                                ? params.VIEWER_WAIT_MINUTES.toInteger() 
                                : 2
                            // Viewer1 starts immediately (0 second stagger)
                            runViewerSessions("Viewer1", waitMins, "2", 0)
                        }
                    }
                }
                stage('StorageViewer2') {
                    agent {
                        label params.STORAGE_VIEWER_TWO_NODE_LABEL
                    }
                    steps {
                        script {
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') 
                                ? params.VIEWER_WAIT_MINUTES.toInteger() 
                                : 2
                            // Viewer2 starts 15 seconds after Viewer1
                            runViewerSessions("Viewer2", waitMins, "2", 15)
                        }
                    }
                }
            }
        }

        stage('Three Viewers with Continuous Master') {
            when {
                equals expected: true, actual: params.JS_STORAGE_THREE_VIEWERS
            }
            parallel {
                stage('Continuous StorageMaster') {
                    agent {
                        label params.MASTER_NODE_LABEL
                    }
                    steps {
                        script {
                            def mutableParams = [:] + params
                            mutableParams.DURATION_IN_SECONDS = "1380"
                            buildStorageCanary(mutableParams)
                        }
                    }
                }
                stage('StorageViewer1') {
                    agent {
                        label params.STORAGE_VIEWER_ONE_NODE_LABEL
                    }
                    steps {
                        script {
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') 
                                ? params.VIEWER_WAIT_MINUTES.toInteger() 
                                : 2
                            // Viewer1 starts immediately (0 second stagger)
                            runViewerSessions("Viewer1", waitMins, "3", 0)
                        }
                    }
                }
                stage('StorageViewer2') {
                    agent {
                        label params.STORAGE_VIEWER_TWO_NODE_LABEL
                    }
                    steps {
                        script {
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') 
                                ? params.VIEWER_WAIT_MINUTES.toInteger() 
                                : 2
                            // Viewer2 starts 15 seconds after Viewer1
                            runViewerSessions("Viewer2", waitMins, "3", 15)
                        }
                    }
                }
                stage('StorageViewer3') {
                    agent {
                        label params.STORAGE_VIEWER_THREE_NODE_LABEL
                    }
                    steps {
                        script {
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') 
                                ? params.VIEWER_WAIT_MINUTES.toInteger() 
                                : 2
                            // Viewer3 starts 30 seconds after Viewer1
                            runViewerSessions("Viewer3", waitMins, "3", 30)
                        }
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

        stage('Reschedule') {
            when {
                equals expected: true, actual: params.RESCHEDULE
            }
            steps {
                build(
                    job: env.JOB_NAME,
                    parameters: [
                        string(name: 'AWS_KVS_LOG_LEVEL', value: params.AWS_KVS_LOG_LEVEL),
                        string(name: 'LOG_GROUP_NAME', value: params.LOG_GROUP_NAME),
                        string(name: 'MASTER_NODE_LABEL', value: params.MASTER_NODE_LABEL),
                        string(name: 'STORAGE_VIEWER_NODE_LABEL', value: params.STORAGE_VIEWER_NODE_LABEL),
                        string(name: 'STORAGE_VIEWER_ONE_NODE_LABEL', value: params.STORAGE_VIEWER_ONE_NODE_LABEL),
                        string(name: 'STORAGE_VIEWER_TWO_NODE_LABEL', value: params.STORAGE_VIEWER_TWO_NODE_LABEL),
                        string(name: 'STORAGE_VIEWER_THREE_NODE_LABEL', value: params.STORAGE_VIEWER_THREE_NODE_LABEL),
                        string(name: 'RUNNER_LABEL', value: params.RUNNER_LABEL),
                        string(name: 'SCENARIO_LABEL', value: params.SCENARIO_LABEL),
                        string(name: 'DURATION_IN_SECONDS', value: params.DURATION_IN_SECONDS),
                        string(name: 'GIT_URL', value: params.GIT_URL),
                        string(name: 'GIT_HASH', value: params.GIT_HASH),
                        string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                        string(name: 'ENDPOINT', value: params.ENDPOINT),
                        string(name: 'METRIC_SUFFIX', value: params.METRIC_SUFFIX),
                        string(name: 'VIEWER_WAIT_MINUTES', value: params.VIEWER_WAIT_MINUTES),
                        string(name: 'VIEWER_SESSION_DURATION_SECONDS', value: params.VIEWER_SESSION_DURATION_SECONDS),
                        booleanParam(name: 'DEBUG_LOG_SDP', value: params.DEBUG_LOG_SDP),
                        booleanParam(name: 'FIRST_ITERATION', value: false),
                        booleanParam(name: 'JS_STORAGE_VIEWER_JOIN', value: params.JS_STORAGE_VIEWER_JOIN),
                        booleanParam(name: 'JS_STORAGE_TWO_VIEWERS', value: params.JS_STORAGE_TWO_VIEWERS),
                        booleanParam(name: 'JS_STORAGE_THREE_VIEWERS', value: params.JS_STORAGE_THREE_VIEWERS),
                        booleanParam(name: 'RESCHEDULE', value: params.RESCHEDULE),
                        booleanParam(name: 'USE_TURN', value: params.USE_TURN),
                        booleanParam(name: 'USE_IOT', value: params.USE_IOT),
                        booleanParam(name: 'TRICKLE_ICE', value: params.TRICKLE_ICE),
                        booleanParam(name: 'FORCE_TURN', value: params.FORCE_TURN),
                    ],
                    wait: false
                )
            }
        }
    }
    
    post {
        always {
            script {
                echo "=========================================="
                echo "Gamma Runner Summary"
                echo "=========================================="
                echo "Build result: ${currentBuild.result ?: 'SUCCESS'}"
                echo "Has errors: ${HAS_ERROR}"
                echo "=========================================="
            }
        }
    }
}
