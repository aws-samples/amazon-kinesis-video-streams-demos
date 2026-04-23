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
VIEWER_SESSION_RESULTS = [:]

// Signal flag: set to true when the storage master build is complete and the binary
// is about to start streaming. Viewers poll this instead of sleeping a fixed duration.
MASTER_READY = false

// @NonCPS prevents Jenkins CPS from trying to serialize local variables in this
// method.  java.util.regex.Matcher is NOT serializable — holding one across a CPS
// step boundary (sh, sleep, echo …) causes NotSerializableException and kills the
// pipeline thread.
@NonCPS
def extractViewerStats(String output) {
    def m = (output =~ /VIEWER_STATS:(\{.*?\})/)
    return m.find() ? m.group(1) : null
}

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

def buildConsumerProject() {
    def consumerStartUpDelay = 45
    sleep consumerStartUpDelay

    checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH]],
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

def runViewerSessions(viewerId = "", waitMinutes = 2, viewerCount = "1") {
    def workspaceName = "${env.JOB_NAME}-${viewerId ?: 'viewer'}-${BUILD_NUMBER}"
    ws(workspaceName) {
        try {
            checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH]],
                      userRemoteConfigs: [[url: params.GIT_URL]]])
            
            if (waitMinutes > 0) {
                echo "Waiting for master to be ready (timeout: ${waitMinutes} minutes)..."
                def startWait = System.currentTimeMillis()
                def timeoutMs = waitMinutes * 60 * 1000
                while (!MASTER_READY && (System.currentTimeMillis() - startWait) < timeoutMs) {
                    sleep 5
                }
                def waitedSec = (System.currentTimeMillis() - startWait) / 1000
                if (MASTER_READY) {
                    echo "Master is ready! Waited ${waitedSec}s"
                } else {
                    echo "WARNING: Master not ready after ${waitMinutes} minutes, proceeding anyway"
                }
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
            
            def viewerKey = viewerId ?: 'viewer'
            VIEWER_SESSION_RESULTS[viewerKey] = [attempts: 1, successes: 0]

            def viewerSessionDuration = (params.VIEWER_SESSION_DURATION_SECONDS != null && params.VIEWER_SESSION_DURATION_SECONDS.toString().trim() != '') 
                ? params.VIEWER_SESSION_DURATION_SECONDS 
                : '600'

            echo "Starting ${viewerId ? viewerId + ' ' : ''}viewer session"
            
            try {
                def output = sh(
                    script: """
                        export JOB_NAME="${env.JOB_NAME}"
                        export RUNNER_LABEL="${params.RUNNER_LABEL}"
                        export AWS_DEFAULT_REGION="${params.AWS_DEFAULT_REGION}"
                        export DURATION_IN_SECONDS="${viewerSessionDuration}"
                        export FORCE_TURN="${params.FORCE_TURN ?: 'false'}"
                        export VIEWER_COUNT="${viewerCount}"
                        export VIEWER_ID="${viewerId}"
                        export CLIENT_ID="${viewerId ? viewerId.toLowerCase() + '-' : 'viewer-'}${BUILD_NUMBER}"
                        export ENDPOINT="${endpointValue}"
                        export METRIC_SUFFIX="${metricSuffixValue}"
                        export KEEP_RECORDING="${params.KEEP_RECORDING}"
                        
                        ./canary/webrtc-c/scripts/setup-storage-viewer.sh
                    """,
                    returnStdout: true
                ).trim()
                
                echo output
                
                // Parse VIEWER_STATS from output
                def statsJson = extractViewerStats(output)
                if (statsJson != null) {
                    def stats = readJSON text: statsJson
                    VIEWER_SESSION_RESULTS[viewerKey] = [attempts: stats.attempts, successes: stats.successes]
                    echo "${viewerKey} stats: ${stats.attempts} attempts, ${stats.successes} successes"
                }
            } catch (FlowInterruptedException err) {
                echo 'Aborted due to cancellation'
                throw err
            } catch (err) {
                HAS_ERROR = true
                unstable err.toString()
            }

            echo "${viewerId ? viewerId + ' ' : ''}viewer session completed"
        } finally {
            // Cleanup handled by Pre Cleanup stage on next iteration
        }
    }
}

def publishViewerConnectionSuccessRate(scenarioLabel) {
    if (VIEWER_SESSION_RESULTS.isEmpty()) {
        echo "No viewer session results to aggregate"
        return
    }

    def channelName = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    def metricSuffix = params.METRIC_SUFFIX ?: ''
    def region = params.AWS_DEFAULT_REGION ?: 'us-west-2'

    int totalAttempts = 0
    int totalSuccesses = 0
    VIEWER_SESSION_RESULTS.each { viewerKey, stats ->
        totalAttempts += stats.attempts ?: 0
        totalSuccesses += stats.successes ?: 0
    }

    def percentage = totalAttempts > 0 ? (totalSuccesses * 100.0) / totalAttempts : 0
    echo "ViewerConnectionSuccessRate: ${totalSuccesses}/${totalAttempts} successful connections (${percentage}%)"
    echo "Per-viewer breakdown: ${VIEWER_SESSION_RESULTS}"

    def metricName = "ViewerConnectionSuccessRate${metricSuffix}"
    sh """
        aws cloudwatch put-metric-data \
            --namespace ViewerApplication \
            --region ${region} \
            --metric-data \
                MetricName=${metricName},Value=${percentage},Unit=Percent,Dimensions="[{Name=StorageWithViewerChannelName,Value=${channelName}},{Name=JobName,Value=${env.JOB_NAME}},{Name=RunnerLabel,Value=${params.RUNNER_LABEL}}]"
    """
    echo "Pushed ${metricName}=${percentage}%"

    VIEWER_SESSION_RESULTS = [:]
}

def buildStorageCanary(isConsumer, params) {
    def scripts_dir = "$WORKSPACE/canary/webrtc-c/scripts"
    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    def endpoint = "${scripts_dir}/iot-credential-provider.txt"
    def core_cert_file = "${scripts_dir}/${thing_prefix}_certificate.pem"
    def private_key_file = "${scripts_dir}/${thing_prefix}_private.key"
    def role_alias = "${thing_prefix}_role_alias"
    def thing_name = "${thing_prefix}_thing"

    def commonEnvs = [
        'AWS_KVS_LOG_LEVEL': params.AWS_KVS_LOG_LEVEL,
        'CANARY_USE_IOT_PROVIDER': params.USE_IOT ?: false,
        'CANARY_LABEL': params.SCENARIO_LABEL,
        'AWS_IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
        'AWS_IOT_CORE_CERT': "${core_cert_file}",
        'AWS_IOT_CORE_PRIVATE_KEY': "${private_key_file}",
        'AWS_IOT_CORE_ROLE_ALIAS': "${role_alias}",
        'AWS_IOT_CORE_THING_NAME': "${thing_name}"
    ]

    def masterEnvs = [
        'CANARY_DURATION_IN_SECONDS': params.DURATION_IN_SECONDS,
        'CANARY_USE_TURN': params.USE_TURN ?: false,
        'CANARY_TRICKLE_ICE': params.TRICKLE_ICE ?: false,
        'CANARY_LOG_GROUP_NAME': params.LOG_GROUP_NAME,
        'CANARY_LOG_STREAM_NAME': "${params.RUNNER_LABEL}-StorageMaster-${START_TIMESTAMP}",
        'CANARY_CLIENT_ID': "Master",
        'CANARY_IS_MASTER': true,
        'CANARY_CHANNEL_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}",
        'AWS_DEFAULT_REGION': params.AWS_DEFAULT_REGION,
        'CONTROL_PLANE_URI': params.ENDPOINT ?: ''
    ]

    def consumerEnvs = [
        'JAVA_HOME': "/opt/jdk-11.0.20",
        'M2_HOME': "/opt/apache-maven-3.6.3",
        'AWS_DEFAULT_REGION': params.AWS_DEFAULT_REGION,
        'CANARY_STREAM_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}",
        'CANARY_DURATION_IN_SECONDS': "${params.DURATION_IN_SECONDS.toInteger() + 120}",
        'VIDEO_VERIFY_ENABLED': params.VIDEO_VERIFY_ENABLED?.toString() ?: 'false',
        'CANARY_CLIP_OUTPUT_PATH': "${env.WORKSPACE}/canary/consumer-java/clip-${START_TIMESTAMP}.mp4"
    ]

    RUNNING_NODES_IN_BUILDING++
    if (!isConsumer) {
        MASTER_READY = false
    }
    if (!isConsumer) {
        buildWebRTCProject(thing_prefix)
    } else {
        buildConsumerProject()
    }
    RUNNING_NODES_IN_BUILDING--

    waitUntil {
        RUNNING_NODES_IN_BUILDING == 0
    }

    if (!isConsumer) {
        def envs = (commonEnvs + masterEnvs).collect{ k, v -> "${k}=${v}" }
        MASTER_READY = true
        echo "Master build complete, signaling viewers (MASTER_READY=true)"
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
                java -classpath target/aws-kinesisvideo-producer-sdk-canary-consumer-1.0-SNAPSHOT.jar:$(cat tmp_jar) -Daws.accessKeyId=${AWS_ACCESS_KEY_ID} -Daws.secretKey=${AWS_SECRET_ACCESS_KEY} -Daws.sessionToken=${AWS_SESSION_TOKEN} com.amazon.kinesis.video.canary.consumer.WebrtcStorageCanaryConsumer
            '''
        }

        // Run video verification on the GetClip MP4
        def clipPath = "${env.WORKSPACE}/canary/consumer-java/clip-${START_TIMESTAMP}.mp4"
        def verifyScript = "${env.WORKSPACE}/canary/webrtc-c/scripts/video-verification/verify.py"
        def sourceFrames = "${env.WORKSPACE}/canary/webrtc-c/assets/h264SampleFrames"
        def streamName = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
        def scenarioLabel = params.SCENARIO_LABEL

        def clipExists = sh(script: "test -f '${clipPath}'", returnStatus: true) == 0

        if (clipExists) {
            try {
                echo "Running consumer-side video verification on GetClip MP4..."
                def output = sh(
                    script: "python3 '${verifyScript}' --recording '${clipPath}' --source-frames '${sourceFrames}' --expected-duration '${params.DURATION_IN_SECONDS}' --json",
                    returnStdout: true
                ).trim()
                echo "Consumer video verification results: ${output}"

                def results = readJSON text: output
                def storageAvailability = results.storage_availability ?: 0

                sh """
                    aws cloudwatch put-metric-data \
                        --namespace KinesisVideoSDKCanary \
                        --metric-data \
                            MetricName=ConsumerStorageAvailability,Value=${storageAvailability},Unit=Count,Dimensions="[{Name=StorageWebRTCSDKCanaryStreamName,Value=${streamName}},{Name=StorageWebRTCSDKCanaryLabel,Value=${scenarioLabel}}]"
                """
                echo "Pushed ConsumerStorageAvailability=${storageAvailability}"

                def avgSsim = results.avg_ssim ?: 0
                def minSsim = results.min_ssim ?: 0
                def maxSsim = results.max_ssim ?: 0
                sh """
                    aws cloudwatch put-metric-data \
                        --namespace KinesisVideoSDKCanary \
                        --metric-data \
                            MetricName=ConsumerSSIMAvg,Value=${avgSsim},Unit=None,Dimensions="[{Name=StorageWebRTCSDKCanaryStreamName,Value=${streamName}},{Name=StorageWebRTCSDKCanaryLabel,Value=${scenarioLabel}}]" \
                            MetricName=ConsumerSSIMMin,Value=${minSsim},Unit=None,Dimensions="[{Name=StorageWebRTCSDKCanaryStreamName,Value=${streamName}},{Name=StorageWebRTCSDKCanaryLabel,Value=${scenarioLabel}}]" \
                            MetricName=ConsumerSSIMMax,Value=${maxSsim},Unit=None,Dimensions="[{Name=StorageWebRTCSDKCanaryStreamName,Value=${streamName}},{Name=StorageWebRTCSDKCanaryLabel,Value=${scenarioLabel}}]"
                """
                echo "Pushed ConsumerSSIM avg=${avgSsim}, min=${minSsim}, max=${maxSsim}"

                echo "GetClip MP4 preserved at: ${clipPath}"
            } catch (err) {
                echo "Consumer video verification failed: ${err.getMessage()}"
            }
        } else {
            echo "No GetClip MP4 found at ${clipPath}, skipping consumer video verification"
        }
    }
}

pipeline {
    agent {
        label params.MASTER_NODE_LABEL
    }

    parameters {
        string(name: 'AWS_KVS_LOG_LEVEL', defaultValue: '2')
        string(name: 'LOG_GROUP_NAME', defaultValue: 'WebrtcSDK')
        string(name: 'MASTER_NODE_LABEL', defaultValue: 'webrtc-storage-master')
        string(name: 'STORAGE_VIEWER_NODE_LABEL', defaultValue: 'webrtc-storage-viewer')
        string(name: 'STORAGE_VIEWER_ONE_NODE_LABEL', defaultValue: 'webrtc-storage-multi-viewer-1')
        string(name: 'STORAGE_VIEWER_TWO_NODE_LABEL', defaultValue: 'webrtc-storage-multi-viewer-2')
        string(name: 'STORAGE_VIEWER_THREE_NODE_LABEL', defaultValue: 'webrtc-storage-consumer')
        string(name: 'RUNNER_LABEL', defaultValue: 'GammaTest')
        string(name: 'SCENARIO_LABEL', defaultValue: 'GammaTest')
        string(name: 'DURATION_IN_SECONDS', defaultValue: '156')
        string(name: 'GIT_URL', defaultValue: 'https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git')
        string(name: 'GIT_HASH', defaultValue: 'clean_viewer_test')
        string(name: 'AWS_DEFAULT_REGION', defaultValue: 'us-west-2')
        string(name: 'ENDPOINT', defaultValue: '', description: 'Custom endpoint URL (e.g., gamma endpoint)')
        string(name: 'METRIC_SUFFIX', defaultValue: '-gamma')
        string(name: 'VIEWER_WAIT_MINUTES', defaultValue: '20', description: 'Minutes to wait for master to build')
        string(name: 'VIEWER_SESSION_DURATION_SECONDS', defaultValue: '900', description: 'Hard timeout in seconds for the viewer process (default 15 minutes, must be larger than monitoring duration)')
        booleanParam(name: 'KEEP_RECORDING', defaultValue: false, description: 'Keep viewer video recordings after verification')
        booleanParam(name: 'DEBUG_LOG_SDP', defaultValue: true)
        booleanParam(name: 'FIRST_ITERATION', defaultValue: true)
        booleanParam(name: 'IS_STORAGE', defaultValue: false, description: 'Run storage master + consumer test')
        booleanParam(name: 'IS_STORAGE_SINGLE_NODE', defaultValue: false, description: 'Run master and consumer on same node')
        booleanParam(name: 'VIDEO_VERIFY_ENABLED', defaultValue: false, description: 'Enable consumer-side video verification via GetClip')
        string(name: 'CONSUMER_NODE_LABEL', defaultValue: 'webrtc-storage-consumer')
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
                    echo "Test Type: ${params.IS_STORAGE ? 'Storage Master+Consumer' : params.JS_STORAGE_VIEWER_JOIN ? '1 Viewer' : params.JS_STORAGE_TWO_VIEWERS ? '2 Viewers' : params.JS_STORAGE_THREE_VIEWERS ? '3 Viewers' : 'Unknown'}"
                    echo "=========================================="
                }
            }
        }

        stage('Build and Run Storage Master and Consumer') {
            failFast true
            when {
                equals expected: true, actual: params.IS_STORAGE
            }
            parallel {
                stage('StorageMaster') {
                    steps {
                        script {
                            buildStorageCanary(false, params)
                        }
                    }
                }
                stage('StorageConsumer') {
                    agent {
                        label params.CONSUMER_NODE_LABEL
                    }
                    steps {
                        script {
                            buildStorageCanary(true, params)
                        }
                    }
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
                            mutableParams.DURATION_IN_SECONDS = "156"
                            buildStorageCanary(false, mutableParams)
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
                                : 20
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
                            mutableParams.DURATION_IN_SECONDS = "156"
                            buildStorageCanary(false, mutableParams)
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
                                : 20
                            runViewerSessions("Viewer1", waitMins, "2")
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
                                : 20
                            runViewerSessions("Viewer2", waitMins, "2")
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
                            mutableParams.DURATION_IN_SECONDS = "156"
                            buildStorageCanary(false, mutableParams)
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
                                : 20
                            runViewerSessions("Viewer1", waitMins, "3")
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
                                : 20
                            runViewerSessions("Viewer2", waitMins, "3")
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
                                : 20
                            runViewerSessions("Viewer3", waitMins, "3")
                        }
                    }
                }
            }
        }

        stage('Publish Viewer Connection Success Rate') {
            when {
                anyOf {
                    equals expected: true, actual: params.JS_STORAGE_VIEWER_JOIN
                    equals expected: true, actual: params.JS_STORAGE_TWO_VIEWERS
                    equals expected: true, actual: params.JS_STORAGE_THREE_VIEWERS
                }
            }
            steps {
                script {
                    publishViewerConnectionSuccessRate(params.SCENARIO_LABEL)
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
                        booleanParam(name: 'IS_STORAGE', value: params.IS_STORAGE),
                        booleanParam(name: 'IS_STORAGE_SINGLE_NODE', value: params.IS_STORAGE_SINGLE_NODE),
                        booleanParam(name: 'VIDEO_VERIFY_ENABLED', value: params.VIDEO_VERIFY_ENABLED),
                        string(name: 'CONSUMER_NODE_LABEL', value: params.CONSUMER_NODE_LABEL),
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
