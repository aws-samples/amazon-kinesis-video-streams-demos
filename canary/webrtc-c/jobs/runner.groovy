import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

START_TIMESTAMP = new Date().getTime()
RUNNING_NODES_IN_BUILDING = 0
HAS_ERROR = false

// Track per-viewer, per-session peer connection results for aggregate ViewerJoinPercentage metric.
// Structure: { "Viewer1": [true, false, true], "Viewer2": [true, true, true], ... }
// Access is synchronized since parallel stages write to different keys.
VIEWER_SESSION_RESULTS = [:]

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

def buildPeer(isMaster, params) {
    def clientID = isMaster ? "Master" : "Viewer"
    RUNNING_NODES_IN_BUILDING++

    // TODO: get the branch and version from orchestrator
    if (params.FIRST_ITERATION) {
        deleteDir()
    }

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

    // TODO: get the branch and version from orchestrator
    if (params.FIRST_ITERATION) {
        if(params.CACHED_WORKSPACE_ID == "${env.WORKSPACE}") {
            echo "Same workspace: " + params.CACHED_WORKSPACE_ID
            echo "New one: ${env.WORKSPACE}"
        } else {
            deleteDir()
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

def runViewerSessions(viewerId = "", waitMinutes = 10, viewerCount = "1", staggerDelaySeconds = 0) {
    // Create unique workspace for each viewer to prevent Git conflicts
    def workspaceName = "${env.JOB_NAME}-${viewerId ?: 'viewer'}-${BUILD_NUMBER}"
    ws(workspaceName) {
        try {
            deleteDir()
            checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH ]],
                      userRemoteConfigs: [[url: params.GIT_URL]]])
            
            if (waitMinutes > 0) {
                echo "Waiting ${waitMinutes} minutes for master to build"
                sleep waitMinutes * 60
            }
            
            // Staggered start delay to avoid all viewers starting at the exact same time
            if (staggerDelaySeconds > 0) {
                echo "Staggered start - waiting ${staggerDelaySeconds} seconds before starting ${viewerId ?: 'viewer'}"
                sleep staggerDelaySeconds
            }
            
            // Capture endpoint value before loop to ensure it's available
            def endpointValue = params.ENDPOINT ?: ''
            def metricSuffixValue = params.METRIC_SUFFIX ?: ''
            
            // Initialize session results tracking for this viewer
            def viewerKey = viewerId ?: 'viewer'
            VIEWER_SESSION_RESULTS[viewerKey] = []

            for (int session = 1; session <= 3; session++) {
                echo "Starting ${viewerId ? viewerId + ' ' : ''}session ${session}/3"
                echo "DEBUG: ENDPOINT value = '${endpointValue}'"
                echo "DEBUG: METRIC_SUFFIX value = '${metricSuffixValue}'"
                
                def sessionSuccess = false
                try {
                    sh """
                        export JOB_NAME="${env.JOB_NAME}"
                        export RUNNER_LABEL="${params.RUNNER_LABEL}"
                        export AWS_DEFAULT_REGION="${params.AWS_DEFAULT_REGION}"
                        export DURATION_IN_SECONDS="${(params.VIEWER_SESSION_DURATION_SECONDS != null && params.VIEWER_SESSION_DURATION_SECONDS.toString().trim() != '') ? params.VIEWER_SESSION_DURATION_SECONDS : '600'}"
                        export FORCE_TURN="${params.FORCE_TURN}"
                        export VIEWER_COUNT="${viewerCount}"
                        export VIEWER_ID="${viewerId}"
                        export CLIENT_ID="${viewerId ? viewerId.toLowerCase() + '-' : 'viewer-'}session-${session}-${BUILD_NUMBER}"
                        export ENDPOINT="${endpointValue}"
                        export METRIC_SUFFIX="${metricSuffixValue}"
                        
                        echo "Shell ENDPOINT: \$ENDPOINT"
                        
                        ./canary/webrtc-c/scripts/setup-storage-viewer.sh
                    """
                    sessionSuccess = true
                } catch (FlowInterruptedException err) {
                    echo 'Aborted due to cancellation'
                    throw err
                } catch (err) {
                    HAS_ERROR = true
                    unstable err.toString()
                }

                VIEWER_SESSION_RESULTS[viewerKey].add(sessionSuccess)
                echo "${viewerId ? viewerId + ' ' : ''}session ${session} result: ${sessionSuccess ? 'SUCCESS' : 'FAILURE'}"
                
                if (session < 3) {
                    echo "${viewerId ? viewerId + ' ' : ''}session ${session} completed. Waiting 1 minute before next session."
                    sleep 60
                }
            }
            echo "All 3 ${viewerId ? viewerId + ' ' : ''}sessions completed"
        } finally {
            // Ensure complete cleanup
            deleteDir()
        }
    }
}

/**
 * Computes and pushes the ViewerJoinPercentage metric to CloudWatch.
 *
 * For each session round (1, 2, 3), this looks across all viewers that participated
 * and calculates: (viewers that succeeded in that round / total viewers) * 100.
 * This gives per-round join percentages like 33%, 66%, 100% for a 3-viewer scenario.
 *
 * @param scenarioLabel  The scenario label (e.g. "StorageThreeViewers") used as a CW dimension
 */
def publishViewerJoinPercentage(scenarioLabel) {
    if (VIEWER_SESSION_RESULTS.isEmpty()) {
        echo "No viewer session results to aggregate"
        return
    }

    def channelName = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    def metricSuffix = params.METRIC_SUFFIX ?: ''
    def region = params.AWS_DEFAULT_REGION ?: 'us-west-2'
    def totalViewers = VIEWER_SESSION_RESULTS.size()

    echo "Computing ViewerJoinPercentage: ${totalViewers} viewer(s), results: ${VIEWER_SESSION_RESULTS}"

    // Each viewer runs 3 sessions. Compute a percentage per session round.
    for (int round = 0; round < 3; round++) {
        int joinedCount = 0
        VIEWER_SESSION_RESULTS.each { viewerKey, sessions ->
            if (round < sessions.size() && sessions[round]) {
                joinedCount++
            }
        }
        def percentage = (joinedCount * 100.0) / totalViewers
        echo "Session round ${round + 1}: ${joinedCount}/${totalViewers} viewers joined (${percentage}%)"

        def metricName = "ViewerJoinPercentage${metricSuffix}"
        sh """
            aws cloudwatch put-metric-data \
                --namespace ViewerApplication \
                --region ${region} \
                --metric-data \
                    MetricName=${metricName},Value=${percentage},Unit=Percent,Dimensions="[{Name=StorageWithViewerChannelName,Value=${channelName}},{Name=JobName,Value=${env.JOB_NAME}},{Name=RunnerLabel,Value=${params.RUNNER_LABEL}}]"
        """
        echo "Pushed ${metricName}=${percentage}% for session round ${round + 1}"
    }

    // Reset for next iteration
    VIEWER_SESSION_RESULTS = [:]
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
      'CANARY_DURATION_IN_SECONDS': params.DURATION_IN_SECONDS,
      'AWS_IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
      'AWS_IOT_CORE_CERT': "${core_cert_file}",
      'AWS_IOT_CORE_PRIVATE_KEY': "${private_key_file}",
      'AWS_IOT_CORE_ROLE_ALIAS': "${role_alias}",
      'AWS_IOT_CORE_THING_NAME': "${thing_name}"
    ]

    def masterEnvs = [
      'CANARY_USE_TURN': params.USE_TURN,
      'CANARY_TRICKLE_ICE': params.TRICKLE_ICE,
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
      'VIDEO_VERIFY_ENABLED': params.VIDEO_VERIFY_ENABLED?.toString() ?: 'false',
      'CANARY_CLIP_OUTPUT_PATH': "${env.WORKSPACE}/canary/consumer-java/clip.mp4"
    ]

    RUNNING_NODES_IN_BUILDING++
    if (params.FIRST_ITERATION) {
        deleteDir()
    }
    if (!isConsumer){
        buildWebRTCProject(params.USE_MBEDTLS, thing_prefix)
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
                java -classpath target/aws-kinesisvideo-producer-sdk-canary-consumer-1.0-SNAPSHOT.jar:$(cat tmp_jar) -Daws.accessKeyId=${AWS_ACCESS_KEY_ID} -Daws.secretKey=${AWS_SECRET_ACCESS_KEY} -Daws.sessionToken=${AWS_SESSION_TOKEN} com.amazon.kinesis.video.canary.consumer.WebrtcStorageCanaryConsumer
            '''
        }

        // Run video verification on the GetClip MP4 if the consumer downloaded one
        def clipPath = "${env.WORKSPACE}/canary/consumer-java/clip.mp4"
        def verifyScript = "${env.WORKSPACE}/canary/webrtc-c/scripts/video-verification/verify.py"
        def sourceFrames = "${env.WORKSPACE}/canary/webrtc-c/assets/h264SampleFrames"
        def streamName = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
        def scenarioLabel = params.SCENARIO_LABEL

        def clipExists = sh(script: "test -f '${clipPath}'", returnStatus: true) == 0

        if (clipExists) {
            try {
                echo "Running consumer-side video verification on GetClip MP4..."
                def output = sh(
                    script: "python3 '${verifyScript}' --recording '${clipPath}' --source-frames '${sourceFrames}' --json",
                    returnStdout: true
                ).trim()
                echo "Consumer video verification results: ${output}"

                def results = readJSON text: output
                def ssimFailurePct = results.ssim_failure_pct ?: 0
                def frameLossPct = results.frame_loss_pct ?: 0

                sh """
                    aws cloudwatch put-metric-data \
                        --namespace KinesisVideoSDKCanary \
                        --metric-data \
                            MetricName=ConsumerVideoSSIMFailureRate,Value=${ssimFailurePct},Unit=Percent,Dimensions="[{Name=StorageWebRTCSDKCanaryStreamName,Value=${streamName}},{Name=StorageWebRTCSDKCanaryLabel,Value=${scenarioLabel}}]" \
                            MetricName=ConsumerVideoFrameLossRate,Value=${frameLossPct},Unit=Percent,Dimensions="[{Name=StorageWebRTCSDKCanaryStreamName,Value=${streamName}},{Name=StorageWebRTCSDKCanaryLabel,Value=${scenarioLabel}}]"
                """
                echo "Pushed ConsumerVideoSSIMFailureRate=${ssimFailurePct}%, ConsumerVideoFrameLossRate=${frameLossPct}%"

                sh "rm -f '${clipPath}'"
            } catch (err) {
                echo "Consumer video verification failed: ${err.getMessage()}"
            }
        } else {
            echo "No GetClip MP4 found, skipping consumer video verification"
        }
    }
}

pipeline {
    agent {
        label params.MASTER_NODE_LABEL
    }

    parameters {
        choice(name: 'AWS_KVS_LOG_LEVEL', choices: ["1", "2", "3", "4", "5"])
        booleanParam(name: 'IS_SIGNALING')
        booleanParam(name: 'IS_STORAGE')
        booleanParam(name: 'IS_STORAGE_SINGLE_NODE')
        booleanParam(name: 'USE_TURN')
        booleanParam(name: 'IS_PROFILING')
        booleanParam(name: 'TRICKLE_ICE')
        booleanParam(name: 'USE_MBEDTLS', defaultValue: false)
        booleanParam(name: 'DEBUG_LOG_SDP', defaultValue: true)
        string(name: 'LOG_GROUP_NAME')
        string(name: 'MASTER_NODE_LABEL')
        string(name: 'CONSUMER_NODE_LABEL')
        string(name: 'VIEWER_NODE_LABEL')
        string(name: 'STORAGE_VIEWER_NODE_LABEL')
        string(name: 'STORAGE_VIEWER_ONE_NODE_LABEL')
        string(name: 'STORAGE_VIEWER_TWO_NODE_LABEL')
        string(name: 'STORAGE_VIEWER_THREE_NODE_LABEL')
        string(name: 'VIEWER_COUNT')
        string(name: 'RUNNER_LABEL')
        string(name: 'SCENARIO_LABEL')
        string(name: 'DURATION_IN_SECONDS')
        string(name: 'VIDEO_CODEC')
        string(name: 'MIN_RETRY_DELAY_IN_SECONDS')
        string(name: 'GIT_URL')
        string(name: 'GIT_HASH')
        booleanParam(name: 'FIRST_ITERATION', defaultValue: true)
        booleanParam(name: 'JS_STORAGE_VIEWER_JOIN', defaultValue: false)
        booleanParam(name: 'JS_STORAGE_TWO_VIEWERS', defaultValue: false)
        booleanParam(name: 'JS_STORAGE_THREE_VIEWERS', defaultValue: false)
        string(name: 'ENDPOINT', defaultValue: '')
        string(name: 'METRIC_SUFFIX', defaultValue: '')
        string(name: 'VIEWER_WAIT_MINUTES', defaultValue: '55')
        string(name: 'VIEWER_SESSION_DURATION_SECONDS', defaultValue: '600', description: 'Duration in seconds for each viewer session (default 10 minutes)')
        booleanParam(name: 'VIDEO_VERIFY_ENABLED', defaultValue: false, description: 'Enable consumer-side video verification via GetClip')
    }
    
    // Set the role ARN to environment to avoid string interpolation to follow Jenkins security guidelines.
    environment {
        AWS_KVS_STS_ROLE_ARN = credentials('CANARY_STS_ROLE_ARN')
    }

    stages {
        stage('Pre Cleanup') {
            steps {
                script {
                    sh """
                        echo "Cleaning up workspace artifacts - Disk usage before cleanup:"
                        df -h
                        
                        # Clean all @tmp directories older than 1 hour
                        find ~/Jenkins -name "*@tmp" -type d -mmin +60 -exec rm -rf {} + 2>/dev/null || true
                        
                        # Keep only last 10 workspace directories per job
                        cd ~/Jenkins 2>/dev/null || true
                        ls -t | grep "webrtc-canary-runner" | grep -v "@tmp" | tail -n +11 | xargs -I {} rm -rf {} 2>/dev/null || true
                        
                        # Clean Puppeteer cache (keep only latest version for each browser type)
                        for browser_type in chrome chrome-headless-shell; do
                            if [ -d ~/.cache/puppeteer/\$browser_type ]; then
                                find ~/.cache/puppeteer/\$browser_type -maxdepth 1 -type d -name "linux-*" 2>/dev/null | sort -r | tail -n +2 | xargs rm -rf 2>/dev/null || true
                                # Remove any cache dirs where the executable is missing (corrupt partial downloads)
                                for dir in ~/.cache/puppeteer/\$browser_type/linux-*; do
                                    if [ -d "\$dir" ] && ! find "\$dir" -name "\$browser_type" -type f 2>/dev/null | grep -q .; then
                                        echo "Removing corrupt \$browser_type cache: \$dir"
                                        rm -rf "\$dir"
                                    fi
                                done
                            fi
                        done
                        
                        # Clean npm cache if over 200MB
                        if [ -d ~/.npm ] && [ \$(du -sm ~/.npm 2>/dev/null | cut -f1) -gt 200 ]; then
                            npm cache clean --force 2>/dev/null || true
                        fi
                        
                        # Clean temp files
                        find /tmp -name "*storage*viewer*" -type d -mmin +60 -exec rm -rf {} + 2>/dev/null || true
                        find /tmp -name "*webrtc-canary-runner*" -type d -mmin +60 -exec rm -rf {} + 2>/dev/null || true
                        find /tmp -name "jenkins-*" -type d -mtime +1 -exec rm -rf {} + 2>/dev/null || true
                        
                        echo "Cleanup completed - Disk usage after cleanup:"
                        df -h
                    """
                }
            }
        }

        stage('Fetch STS credentials and export to env vars') {
            steps {
                script {
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
                allOf {
                    equals expected: false, actual: params.IS_SIGNALING
                    equals expected: false, actual: params.IS_STORAGE
                    equals expected: false, actual: params.IS_STORAGE_SINGLE_NODE 
                    equals expected: false, actual: params.JS_STORAGE_VIEWER_JOIN
                    equals expected: false, actual: params.JS_STORAGE_TWO_VIEWERS
                    equals expected: false, actual: params.JS_STORAGE_THREE_VIEWERS
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

        stage('Build and Run Signaling Canary') {
            failFast true
            when {
                allOf {
                    equals expected: true, actual: params.IS_SIGNALING
                    equals expected: false, actual: params.IS_STORAGE
                }
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

        stage('Build and Run Webrtc-Storage Master and Consumer Canaries') {
            failFast true
            when {
                allOf {
                    equals expected: false, actual: params.IS_SIGNALING
                    equals expected: true, actual: params.IS_STORAGE
                    equals expected: false, actual: params.IS_STORAGE_SINGLE_NODE
                    equals expected: false, actual: params.JS_STORAGE_VIEWER_JOIN
                    equals expected: false, actual: params.JS_STORAGE_TWO_VIEWERS
                    equals expected: false, actual: params.JS_STORAGE_THREE_VIEWERS
                }
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


        stage('Build and Run Webrtc-Storage Master and Consumer Canaries on Same Node') {
            failFast true
            when {
                allOf {
                    equals expected: false, actual: params.IS_SIGNALING
                    equals expected: true, actual: params.IS_STORAGE
                    equals expected: true, actual: params.IS_STORAGE_SINGLE_NODE 
                }
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
                            def viewerWaitMin = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 55
                            def sessionDurationSec = (params.VIEWER_SESSION_DURATION_SECONDS != null && params.VIEWER_SESSION_DURATION_SECONDS.toString().trim() != '') ? params.VIEWER_SESSION_DURATION_SECONDS.toInteger() : 600
                            // Master duration = viewer wait + 3 sessions + 2 inter-session gaps + 10 min buffer
                            def masterDuration = (viewerWaitMin * 60) + (3 * sessionDurationSec) + (2 * 60) + 600
                            def mutableParams = [:] + params
                            mutableParams.DURATION_IN_SECONDS = "${masterDuration}"
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 55
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
                            def viewerWaitMin = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 55
                            def sessionDurationSec = (params.VIEWER_SESSION_DURATION_SECONDS != null && params.VIEWER_SESSION_DURATION_SECONDS.toString().trim() != '') ? params.VIEWER_SESSION_DURATION_SECONDS.toInteger() : 600
                            // Master duration = viewer wait + 3 sessions + 2 inter-session gaps + 10 min buffer
                            def masterDuration = (viewerWaitMin * 60) + (3 * sessionDurationSec) + (2 * 60) + 600
                            def mutableParams = [:] + params
                            mutableParams.DURATION_IN_SECONDS = "${masterDuration}"
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 55
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 55
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
                            def viewerWaitMin = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 55
                            def sessionDurationSec = (params.VIEWER_SESSION_DURATION_SECONDS != null && params.VIEWER_SESSION_DURATION_SECONDS.toString().trim() != '') ? params.VIEWER_SESSION_DURATION_SECONDS.toInteger() : 600
                            // Master duration = viewer wait + 3 sessions + 2 inter-session gaps + 10 min buffer
                            def masterDuration = (viewerWaitMin * 60) + (3 * sessionDurationSec) + (2 * 60) + 600
                            def mutableParams = [:] + params
                            mutableParams.DURATION_IN_SECONDS = "${masterDuration}"
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 55
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 55
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 55
                            // Viewer3 starts 30 seconds after Viewer1
                            runViewerSessions("Viewer3", waitMins, "3", 30)
                        }
                    }
                }
            }
        }

        stage('Publish Viewer Join Percentage') {
            when {
                anyOf {
                    equals expected: true, actual: params.JS_STORAGE_TWO_VIEWERS
                    equals expected: true, actual: params.JS_STORAGE_THREE_VIEWERS
                }
            }
            steps {
                script {
                    publishViewerJoinPercentage(params.SCENARIO_LABEL)
                }
            }
        }

        stage('Stop Continuous StorageMaster') {
            when {
                anyOf {
                    equals expected: true, actual: params.JS_STORAGE_VIEWER_JOIN
                    equals expected: true, actual: params.JS_STORAGE_TWO_VIEWERS
                    equals expected: true, actual: params.JS_STORAGE_THREE_VIEWERS
                }
            }
            agent {
                label params.MASTER_NODE_LABEL
            }
            steps {
                script {
                    sh """
                        # Stop the background master process
                        if [ -f master.pid ]; then
                            kill \$(cat master.pid) || true
                            rm -f master.pid
                        fi
                    """
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
                      booleanParam(name: 'IS_SIGNALING', value: params.IS_SIGNALING),
                      booleanParam(name: 'IS_STORAGE', value: params.IS_STORAGE),
                      booleanParam(name: 'IS_STORAGE_SINGLE_NODE', value: params.IS_STORAGE_SINGLE_NODE),
                      booleanParam(name: 'JS_STORAGE_VIEWER_JOIN', value: params.JS_STORAGE_VIEWER_JOIN),
                      booleanParam(name: 'JS_STORAGE_TWO_VIEWERS', value: params.JS_STORAGE_TWO_VIEWERS),
                      booleanParam(name: 'JS_STORAGE_THREE_VIEWERS', value: params.JS_STORAGE_THREE_VIEWERS),
                      booleanParam(name: 'USE_TURN', value: params.USE_TURN),
                      booleanParam(name: 'FORCE_TURN', value: params.FORCE_TURN),
                      booleanParam(name: 'USE_IOT', value: params.USE_IOT),
                      booleanParam(name: 'IS_PROFILING', value: params.IS_PROFILING),
                      booleanParam(name: 'TRICKLE_ICE', value: params.TRICKLE_ICE),
                      booleanParam(name: 'USE_MBEDTLS', value: params.USE_MBEDTLS),
                      string(name: 'LOG_GROUP_NAME', value: params.LOG_GROUP_NAME),
                      string(name: 'MASTER_NODE_LABEL', value: params.MASTER_NODE_LABEL),
                      string(name: 'CONSUMER_NODE_LABEL', value: params.CONSUMER_NODE_LABEL),
                      string(name: 'VIEWER_NODE_LABEL', value: params.VIEWER_NODE_LABEL),
                      string(name: 'STORAGE_VIEWER_NODE_LABEL', value: params.STORAGE_VIEWER_NODE_LABEL),
                      string(name: 'STORAGE_VIEWER_ONE_NODE_LABEL', value: params.STORAGE_VIEWER_ONE_NODE_LABEL),
                      string(name: 'STORAGE_VIEWER_TWO_NODE_LABEL', value: params.STORAGE_VIEWER_TWO_NODE_LABEL),
                      string(name: 'STORAGE_VIEWER_THREE_NODE_LABEL', value: params.STORAGE_VIEWER_THREE_NODE_LABEL),
                      string(name: 'VIEWER_COUNT', value: params.VIEWER_COUNT),
                      string(name: 'RUNNER_LABEL', value: params.RUNNER_LABEL),
                      string(name: 'SCENARIO_LABEL', value: params.SCENARIO_LABEL),
                      string(name: 'DURATION_IN_SECONDS', value: params.DURATION_IN_SECONDS),
                      string(name: 'VIDEO_CODEC', value: params.VIDEO_CODEC),
                      string(name: 'MIN_RETRY_DELAY_IN_SECONDS', value: params.MIN_RETRY_DELAY_IN_SECONDS),
                      string(name: 'GIT_URL', value: params.GIT_URL),
                      string(name: 'GIT_HASH', value: params.GIT_HASH),
                      string(name: 'ENDPOINT', value: params.ENDPOINT),
                      string(name: 'METRIC_SUFFIX', value: params.METRIC_SUFFIX),
                      string(name: 'VIEWER_WAIT_MINUTES', value: params.VIEWER_WAIT_MINUTES),
                      string(name: 'VIEWER_SESSION_DURATION_SECONDS', value: params.VIEWER_SESSION_DURATION_SECONDS),
                      booleanParam(name: 'VIDEO_VERIFY_ENABLED', value: params.VIDEO_VERIFY_ENABLED),
                      booleanParam(name: 'FIRST_ITERATION', value: false)
                    ],
                    wait: false
                )
            }
        }
    }
    
    post {
        always {
            script {
                sh """
                    echo "Post-build cleanup - removing current workspace @tmp directory"
                    rm -rf ${env.WORKSPACE}@tmp 2>/dev/null || true
                """
            }
        }
    }
}
