import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

/**
 * Gamma Test Runner
 * 
 * This is a dedicated runner for gamma tests. It runs the WebRTC storage master
 * and JS viewer tests against a custom endpoint.
 * 
 * Key differences from production runner:
 *   - Triggered via cron schedule
 *   - Simplified configuration
 *   - Dedicated for gamma/testing purposes
 */

START_TIMESTAMP = new Date().getTime()
RUNNING_NODES_IN_BUILDING = 0
HAS_ERROR = false
IS_ABORTED = false
VIEWER_SESSION_RESULTS = [:]

def pushKeepAlive(stageName) {
    def region = params.AWS_DEFAULT_REGION ?: 'us-west-2'
    def scenarioLabel = params.SCENARIO_LABEL ?: 'GammaTest'
    def runnerLabel = params.RUNNER_LABEL ?: 'GammaTest'
    def rc = sh(script: """
        export PATH="/usr/local/bin:/usr/bin:\$PATH"
        aws cloudwatch put-metric-data \
            --namespace KinesisVideoSDKCanary \
            --region ${region} \
            --metric-data \
                'MetricName=PipelineKeepAlive,Value=1.0,Unit=None,Dimensions=[{Name=Stage,Value=${stageName}},{Name=StorageWebRTCSDKCanaryLabel,Value=${scenarioLabel}},{Name=RunnerLabel,Value=${runnerLabel}}]'
    """, returnStatus: true)
    if (rc == 0) {
        echo "KeepAlive: ${stageName}"
    } else {
        echo "KeepAlive: ${stageName} (emit failed, rc=${rc} — aws CLI may not be installed on this node)"
    }
}

// Signal flag: set to true when the storage master build is complete and the binary
// is about to start streaming. Viewers poll this instead of sleeping a fixed duration.
MASTER_READY = false

// Signal flag: set to true when a viewer has started and is ready to receive media.
// The master waits for this before starting the C binary (used in VO Mixed Viewers).
VIEWER_STARTED = false

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
    def repoDir = "${env.HOME}/webrtc-c-storage-master/repo"
    def certsDir = "${env.HOME}/webrtc-c-storage-master/certs/${thing_prefix}"

    // Bootstrap: ensure repo exists and is on the correct commit
    sh """
        mkdir -p '${env.HOME}/webrtc-c-storage-master'
        exec 9>'${env.HOME}/webrtc-c-storage-master/.build.lock'
        flock 9
        if [ ! -d '${repoDir}/.git' ]; then
            git clone '${params.GIT_URL}' '${repoDir}'
        fi
        cd '${repoDir}' && git fetch origin '+refs/heads/*:refs/remotes/origin/*' && git checkout -f '${params.GIT_HASH}' && git reset --hard 'origin/${params.GIT_HASH}' 2>/dev/null || true
        flock -u 9"""

    pushKeepAlive('GitCheckoutComplete')

    // Sync cleanup cron script if it changed
    sh """
        cmp -s '${repoDir}/canary/webrtc-c/scripts/cron/cleanup-master.sh' '${env.HOME}/webrtc-c-storage-master/cleanup-master.sh' \
            || cp '${repoDir}/canary/webrtc-c/scripts/cron/cleanup-master.sh' '${env.HOME}/webrtc-c-storage-master/cleanup-master.sh'"""

    // Build the binary (handles git fetch, skip-rebuild, and flock internally).
    // Export asset-set env so build-storage-master.sh can fetch the requested set from S3.
    sh """
        export CANARY_ASSET_SET='${params.STORAGE_ASSET_SET ?: ''}'
        export CANARY_ASSET_BUCKET='${params.CANARY_ASSET_BUCKET ?: ''}'
        export CANARY_ASSET_PREFIX='${params.CANARY_ASSET_PREFIX ?: ''}'
        export CANARY_ASSET_REGION='${params.CANARY_ASSET_REGION ?: ''}'
        export AWS_DEFAULT_REGION='${params.AWS_DEFAULT_REGION ?: 'us-west-2'}'
        chmod a+x '${repoDir}/canary/webrtc-c/scripts/build-storage-master.sh' &&
        '${repoDir}/canary/webrtc-c/scripts/build-storage-master.sh' '${params.GIT_URL}' '${params.GIT_HASH}'"""

    // Generate IoT certs in a persistent directory outside the repo
    sh """
        mkdir -p '${certsDir}' &&
        cd '${certsDir}' &&
        chmod a+x '${repoDir}/canary/webrtc-c/scripts/cert_setup.sh' &&
        '${repoDir}/canary/webrtc-c/scripts/cert_setup.sh' '${thing_prefix}'"""
}

def buildConsumerProject() {
    def consumerStartUpDelay = 45
    sleep consumerStartUpDelay

    def repoDir = "${env.HOME}/webrtc-c-storage-master/repo"
    def lockFile = "${env.HOME}/webrtc-c-storage-master/.build.lock"
    def commitFile = "${env.HOME}/webrtc-c-storage-master/.consumer-last-commit"
    def jarPath = "${repoDir}/canary/consumer-java/target/aws-kinesisvideo-producer-sdk-canary-consumer-1.0-SNAPSHOT.jar"

    def consumerEnvs = [
        'JAVA_HOME': "/usr/lib/jvm/default-java",
        'M2_HOME': "/usr/share/maven"
    ].collect({k,v -> "${k}=${v}" })

    // All git + build operations under flock to prevent concurrent consumers
    // from racing on the same repo and target/ directory
    sh """
        mkdir -p '${env.HOME}/webrtc-c-storage-master'
        exec 9>'${lockFile}'
        flock 9

        # Clone or update repo
        if [ ! -d '${repoDir}/.git' ]; then
            git clone '${params.GIT_URL}' '${repoDir}'
            cd '${repoDir}' && git checkout -f '${params.GIT_HASH}'
        else
            cd '${repoDir}' && git fetch origin '+refs/heads/*:refs/remotes/origin/*' && git checkout -f '${params.GIT_HASH}' && git reset --hard 'origin/${params.GIT_HASH}' 2>/dev/null || true
        fi

        # Skip rebuild if commit unchanged and jar exists
        CURRENT_COMMIT=\$(cd '${repoDir}' && git rev-parse HEAD)
        CACHED_COMMIT=\$(cat '${commitFile}' 2>/dev/null || echo '')
        echo "Consumer: current commit=\$(echo \$CURRENT_COMMIT | cut -c1-12) vs cached=\$(echo \$CACHED_COMMIT | cut -c1-12)"

        if [ -f '${jarPath}' ] && [ "\$CURRENT_COMMIT" = "\$CACHED_COMMIT" ]; then
            echo "Consumer jar up to date, skipping build"
        else
            echo "Consumer jar needs rebuild"
            export PATH="/usr/lib/jvm/default-java/bin:\$PATH"
            export PATH="/usr/share/maven/bin:\$PATH"
            cd '${repoDir}/canary/consumer-java'
            make -j4
            echo "\$CURRENT_COMMIT" > '${commitFile}'
        fi

        flock -u 9"""

    // Sync cleanup cron script if it changed
    sh """
        cmp -s '${repoDir}/canary/webrtc-c/scripts/cron/cleanup-consumer.sh' '${env.HOME}/webrtc-c-storage-master/cleanup-consumer.sh' \
            || cp '${repoDir}/canary/webrtc-c/scripts/cron/cleanup-consumer.sh' '${env.HOME}/webrtc-c-storage-master/cleanup-consumer.sh'"""

    // Fetch bitrate-variant asset set into the repo's assets dir so verify.py can use it
    // as the SSIM reference (Option A: like-for-like comparison). Default set is a no-op
    // because it's already in the git checkout.
    sh """
        export CANARY_ASSET_SET='${params.STORAGE_ASSET_SET ?: ''}'
        export CANARY_ASSET_BUCKET='${params.CANARY_ASSET_BUCKET ?: ''}'
        export CANARY_ASSET_PREFIX='${params.CANARY_ASSET_PREFIX ?: ''}'
        export CANARY_ASSET_REGION='${params.CANARY_ASSET_REGION ?: ''}'
        export AWS_DEFAULT_REGION='${params.AWS_DEFAULT_REGION ?: 'us-west-2'}'
        if [ -n "\$CANARY_ASSET_SET" ]; then
            chmod +x '${repoDir}/canary/webrtc-c/scripts/fetch-asset-set.sh' 2>/dev/null || true
            '${repoDir}/canary/webrtc-c/scripts/fetch-asset-set.sh' \"\$CANARY_ASSET_SET\" '${repoDir}/canary/webrtc-c/assets'
        else
            echo "No CANARY_ASSET_SET requested, using default assets from git checkout"
        fi"""
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

def runViewerSessions(viewerId = "", waitMinutes = 2, viewerCount = "1", staggerDelaySeconds = 0, sendAudio = false) {
    def workspaceName = "${env.JOB_NAME}-${viewerId ?: 'viewer'}-${BUILD_NUMBER}"
    ws(workspaceName) {
        sh "touch '${env.WORKSPACE}/.in_use'"
        try {
            checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH]],
                      userRemoteConfigs: [[url: params.GIT_URL]]])

            // Sync cleanup cron script if it changed
            sh """
                mkdir -p '${env.HOME}/JS-viewer-build'
                cmp -s './canary/webrtc-c/scripts/cron/cleanup-viewer.sh' '${env.HOME}/JS-viewer-build/cleanup-viewer.sh' \
                    || cp './canary/webrtc-c/scripts/cron/cleanup-viewer.sh' '${env.HOME}/JS-viewer-build/cleanup-viewer.sh'"""
            
            def endpointValue = params.ENDPOINT ?: ''
            def metricSuffixValue = params.METRIC_SUFFIX ?: ''
            def viewerSessionDuration = (params.VIEWER_SESSION_DURATION_SECONDS != null && params.VIEWER_SESSION_DURATION_SECONDS.toString().trim() != '') 
                ? params.VIEWER_SESSION_DURATION_SECONDS 
                : '600'

            // Run prepare while master is still building
            echo "Preparing viewer dependencies (parallel with master build)..."
            sh """
                export JS_PAGE_URL="${params.JS_BRANCH ?: 'master'}"
                ./canary/webrtc-c/scripts/prepare-storage-viewer.sh
            """
            pushKeepAlive('ViewerPrepareComplete')

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

            if (staggerDelaySeconds > 0) {
                echo "Stagger delay: waiting ${staggerDelaySeconds} seconds before starting viewer..."
                sleep staggerDelaySeconds
            }

            echo "Starting ${viewerId ? viewerId + ' ' : ''}viewer session"

            // Signal master that viewer is ready — master waits for this before streaming
            VIEWER_STARTED = true
            
            try {
                def output = sh(
                    script: """
                        export JOB_NAME="${env.JOB_NAME}"
                        export RUNNER_LABEL="${params.RUNNER_LABEL}"
                        export AWS_DEFAULT_REGION="${params.AWS_DEFAULT_REGION}"
                        export DURATION_IN_SECONDS="${viewerSessionDuration}"
                        export MASTER_DURATION="${params.DURATION_IN_SECONDS ?: '153'}"
                        export FORCE_TURN="${params.FORCE_TURN ?: 'false'}"
                        export VIEWER_COUNT="${viewerCount}"
                        export VIEWER_ID="${viewerId}"
                        export CLIENT_ID="${viewerId ? viewerId.toLowerCase() + '-' : 'viewer-'}${BUILD_NUMBER}"
                        export ENDPOINT="${endpointValue}"
                        export METRIC_SUFFIX="${metricSuffixValue}"
                        export KEEP_RECORDING="${params.KEEP_RECORDING}"
                        export JS_PAGE_URL="${params.JS_BRANCH ?: 'master'}"
                        export VIEWER_SEND_AUDIO="${sendAudio}"
                        export VIEWER_AUDIO_FILE="\${WORKSPACE}/canary/webrtc-c/assets/audio-source.wav"
                        
                        ./canary/webrtc-c/scripts/run-storage-viewer.sh
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
                IS_ABORTED = true
                throw err
            } catch (err) {
                HAS_ERROR = true
                unstable err.toString()
            }

            pushKeepAlive('ViewerSessionComplete')
            echo "${viewerId ? viewerId + ' ' : ''}viewer session completed"
        } finally {
            sh "rm -f '${env.WORKSPACE}/.in_use'"
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
    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    def certsDir = "${env.HOME}/webrtc-c-storage-master/certs/${thing_prefix}"
    def endpoint = "${certsDir}/iot-credential-provider.txt"
    def core_cert_file = "${certsDir}/${thing_prefix}_certificate.pem"
    def private_key_file = "${certsDir}/${thing_prefix}_private.key"
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
        'CONTROL_PLANE_URI': params.ENDPOINT ?: '',
        'CANARY_NO_LOOP_FRAMES': params.NO_LOOP_FRAMES ?: false,
        'CANARY_FRAME_RATE': params.STORAGE_FPS ?: '',
        'CANARY_ASSET_SET': params.STORAGE_ASSET_SET ?: '',
        'CANARY_MEDIA_TYPE': env.CANARY_MEDIA_TYPE ?: ''
    ]

    def repoDir = "${env.HOME}/webrtc-c-storage-master/repo"

    def consumerEnvs = [
        'JAVA_HOME': "/opt/jdk-11.0.20",
        'M2_HOME': "/opt/apache-maven-3.6.3",
        'AWS_DEFAULT_REGION': params.AWS_DEFAULT_REGION,
        'CANARY_STREAM_NAME': "${env.JOB_NAME}-${params.RUNNER_LABEL}",
        'CANARY_DURATION_IN_SECONDS': "${params.DURATION_IN_SECONDS.toInteger() + 120}",
        'VIDEO_VERIFY_ENABLED': params.VIDEO_VERIFY_ENABLED?.toString() ?: 'false',
        'CANARY_CLIP_OUTPUT_PATH': "${repoDir}/canary/consumer-java/clip-${START_TIMESTAMP}.mp4",
        'CONTROL_PLANE_URI': params.ENDPOINT ?: ''
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

        // If CANARY_WAIT_FOR_VIEWERS is set, wait for viewers to join before starting the binary
        if (env.CANARY_WAIT_FOR_VIEWERS == "true") {
            echo "Waiting for viewer to start (VIEWER_STARTED)..."
            def viewerWaitStart = System.currentTimeMillis()
            def viewerWaitTimeout = 120 * 1000 // 2 minutes max
            while (!VIEWER_STARTED && (System.currentTimeMillis() - viewerWaitStart) < viewerWaitTimeout) {
                sleep 2
            }
            if (VIEWER_STARTED) {
                echo "Viewer started! Launching master binary..."
            } else {
                echo "WARNING: Viewer not started after 2 minutes, launching master anyway"
            }
        }

        def buildDir = "${env.HOME}/webrtc-c-storage-master/build"
        pushKeepAlive('MasterStarted')
        withRunnerWrapper(envs) {
            // Timeout: duration + 5 min buffer. The C binary should exit on its own
            // after CANARY_DURATION_IN_SECONDS, but if it hangs (e.g., ICE agent
            // threads not cleaned up after connection failure), Jenkins kills it.
            timeout(time: params.DURATION_IN_SECONDS.toInteger() + 900, unit: 'SECONDS') {
                sh """
                    cd ${buildDir} &&
                    ./kvsWebrtcStorageSample"""
            }
        }
        pushKeepAlive('MasterFinished')
    } else {
        def envs = (commonEnvs + consumerEnvs).collect{ k, v -> "${k}=${v}" }
        withRunnerWrapper(envs) {
            sh """
                cd '${repoDir}/canary/consumer-java'
                java -classpath target/aws-kinesisvideo-producer-sdk-canary-consumer-1.0-SNAPSHOT.jar:\$(cat tmp_jar) -Daws.accessKeyId=\${AWS_ACCESS_KEY_ID} -Daws.secretKey=\${AWS_SECRET_ACCESS_KEY} -Daws.sessionToken=\${AWS_SESSION_TOKEN} com.amazon.kinesis.video.canary.consumer.WebrtcStorageCanaryConsumer
            """
        }

        // Run video verification on the GetClip MP4. sourceFrames points at the same
        // asset set the master streamed, so SSIM measures transport degradation only
        // (Option A). Empty STORAGE_ASSET_SET falls back to the default set.
        def assetSet = params.STORAGE_ASSET_SET ?: 'h264SampleFrames'
        def clipPath = "${repoDir}/canary/consumer-java/clip-${START_TIMESTAMP}.mp4"
        def verifyScript = "${repoDir}/canary/webrtc-c/scripts/video-verification/verify.py"
        def sourceFrames = "${repoDir}/canary/webrtc-c/assets/${assetSet}"
        def streamName = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
        def scenarioLabel = params.SCENARIO_LABEL

        def clipExists = sh(script: "test -f '${clipPath}'", returnStatus: true) == 0

        if (clipExists) {
            try {
                echo "Running consumer-side video verification on GetClip MP4..."
                def output = sh(
                    script: """
                        # Ensure Python venv with video verification deps exists
                        VENV_DIR="\${HOME}/.venv/video-verify"
                        if [ ! -d "\$VENV_DIR" ]; then
                            sudo apt-get install -y python3-venv ffmpeg tesseract-ocr 2>/dev/null || true
                            python3 -m venv "\$VENV_DIR"
                        fi
                        . "\$VENV_DIR/bin/activate"
                        pip install -q pytesseract Pillow scikit-image numpy 2>/dev/null
                        python3 '${verifyScript}' --recording '${clipPath}' --source-frames '${sourceFrames}' --expected-duration '${params.DURATION_IN_SECONDS}' --json
                    """,
                    returnStdout: true
                ).trim()
                echo "Consumer video verification results: ${output}"

                def results = readJSON text: output
                def storageAvailability = results.storage_availability ?: 0

                sh """
                    aws cloudwatch put-metric-data \
                        --namespace KinesisVideoSDKCanary \
                        --region ${params.AWS_DEFAULT_REGION} \
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
                        --region ${params.AWS_DEFAULT_REGION} \
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
            echo "No GetClip MP4 found at ${clipPath}, pushing ConsumerStorageAvailability=0"
            sh """
                aws cloudwatch put-metric-data \
                    --namespace KinesisVideoSDKCanary \
                    --region ${params.AWS_DEFAULT_REGION} \
                    --metric-data \
                        MetricName=ConsumerStorageAvailability,Value=0,Unit=Count,Dimensions="[{Name=StorageWebRTCSDKCanaryStreamName,Value=${streamName}},{Name=StorageWebRTCSDKCanaryLabel,Value=${scenarioLabel}}]"
            """
            echo "Pushed ConsumerStorageAvailability=0"
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
        string(name: 'GIT_HASH', defaultValue: 'metric-implementation')
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
        booleanParam(name: 'JS_STORAGE_VO_MIXED_VIEWERS', defaultValue: false, description: 'VO master + 2 AO viewers + 1 RO viewer')
        booleanParam(name: 'RESCHEDULE', defaultValue: false, description: 'Whether to reschedule after completion')
        booleanParam(name: 'USE_TURN', defaultValue: false)
        booleanParam(name: 'USE_IOT', defaultValue: false)
        booleanParam(name: 'TRICKLE_ICE', defaultValue: false)
        booleanParam(name: 'FORCE_TURN', defaultValue: false)
        booleanParam(name: 'NO_LOOP_FRAMES', defaultValue: false, description: 'Stop after sending all frames once instead of looping')
        string(name: 'STORAGE_FPS', defaultValue: '', description: 'Override storage master frame rate (e.g., 10 for low FPS test). Empty uses default 30 fps.')
        string(name: 'STORAGE_ASSET_SET', defaultValue: '', description: 'Frame asset set: empty=default h264SampleFrames, or e.g. h264SampleFrames-500kbps / -1mbps / -5mbps')
        string(name: 'CANARY_ASSET_BUCKET', defaultValue: '', description: 'S3 bucket hosting the frame-set tarballs (required when STORAGE_ASSET_SET is set)')
        string(name: 'CANARY_ASSET_PREFIX', defaultValue: '', description: 'S3 key prefix for frame-set tarballs, e.g. webrtc-canary/frame-sets/v1 (required when STORAGE_ASSET_SET is set)')
        string(name: 'CANARY_ASSET_REGION', defaultValue: '', description: 'Region of the S3 asset bucket. May differ from AWS_DEFAULT_REGION. Empty falls back to AWS_DEFAULT_REGION.')
        string(name: 'JS_BRANCH', defaultValue: 'master', description: 'JS SDK branch name to clone and serve locally (default: master)')
    }
    
    options {
        // Whole-pipeline hard timeout: scenario duration + 35 min buffer (incremental
        // build ~2-5 min, viewer wait, consumer runs DURATION+120s, GetClip + verify).
        // Guarantees the executor is always released even if any step hangs
        // (GetClip, git, flock, viewer waits, ...).
        //
        // NOTE: the previous lock(resource: RUNNER_LABEL) was removed deliberately.
        // Lock waiters block INSIDE an executor (agent is allocated before the lock),
        // so one hung build turned every cron tick into a leaked executor and wedged
        // the whole node (see docs/gamma-queue-pileup-investigation.md). Same-scenario
        // concurrency is already prevented by the 'Skip if duplicate' stage, which
        // exits in seconds instead of holding an executor.
        timeout(time: params.DURATION_IN_SECONDS.toInteger() + 2100, unit: 'SECONDS')
    }

    environment {
        AWS_KVS_STS_ROLE_ARN = credentials('CANARY_STS_ROLE_ARN')
    }

    stages {
        stage('Skip if duplicate') {
            steps {
                script {
                    // Dedup on the scenario identifier so distinct scenarios don't skip each other.
                    // Fall back to RUNNER_LABEL if SCENARIO_LABEL is unset.
                    def myLabel = params.SCENARIO_LABEL ?: params.RUNNER_LABEL
                    def runningBuilds = Jenkins.instance.getItemByFullName(env.JOB_NAME).builds.findAll { b ->
                        b.isBuilding() && b.number != currentBuild.number &&
                        (b.getAction(hudson.model.ParametersAction)?.getParameter('SCENARIO_LABEL')?.value ?:
                         b.getAction(hudson.model.ParametersAction)?.getParameter('RUNNER_LABEL')?.value) == myLabel
                    }
                    if (runningBuilds) {
                        echo "Another ${myLabel} build is already running (#${runningBuilds[0].number}), skipping"
                        currentBuild.result = 'NOT_BUILT'
                        error("Duplicate skipped")
                    }
                }
            }
        }

        stage('Fetch STS credentials') {
            steps {
                script {
                    // Mark workspace as in-use to prevent cron cleanup
                    sh "touch '${env.WORKSPACE}/.in_use'"

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
                            sh "touch '${env.WORKSPACE}/.in_use'"
                            try {
                                buildStorageCanary(true, params)
                            } finally {
                                sh "rm -f '${env.WORKSPACE}/.in_use'"
                            }
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
                            ws("${env.JOB_NAME}-master-${BUILD_NUMBER}") {
                                sh "touch '${env.WORKSPACE}/.in_use'"
                                try {
                                    def mutableParams = [:] + params
                                    mutableParams.DURATION_IN_SECONDS = "156"
                                    buildStorageCanary(false, mutableParams)
                                } finally {
                                    sh "rm -f '${env.WORKSPACE}/.in_use'"
                                }
                            }
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
                // Co-resident consumer: verifies storage from the SAME continuous master the viewer
                // is watching, so master + consumer + viewer all exercise one session. Gated on
                // VIDEO_VERIFY_ENABLED so plain viewer runs (no storage verification) are unaffected.
                // Uses the same 156s window as the continuous master above.
                stage('StorageConsumer') {
                    when {
                        equals expected: true, actual: params.VIDEO_VERIFY_ENABLED
                    }
                    agent {
                        label params.CONSUMER_NODE_LABEL
                    }
                    steps {
                        script {
                            sh "touch '${env.WORKSPACE}/.in_use'"
                            try {
                                def mutableParams = [:] + params
                                mutableParams.DURATION_IN_SECONDS = "156"
                                buildStorageCanary(true, mutableParams)
                            } finally {
                                sh "rm -f '${env.WORKSPACE}/.in_use'"
                            }
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
                            ws("${env.JOB_NAME}-master-${BUILD_NUMBER}") {
                                sh "touch '${env.WORKSPACE}/.in_use'"
                                try {
                                    def mutableParams = [:] + params
                                    mutableParams.DURATION_IN_SECONDS = "156"
                                    buildStorageCanary(false, mutableParams)
                                } finally {
                                    sh "rm -f '${env.WORKSPACE}/.in_use'"
                                }
                            }
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
                // Co-resident consumer (see single-viewer stage). Gated on VIDEO_VERIFY_ENABLED so
                // master + 2 viewers + consumer all exercise the same continuous master session.
                stage('StorageConsumer') {
                    when {
                        equals expected: true, actual: params.VIDEO_VERIFY_ENABLED
                    }
                    agent {
                        label params.CONSUMER_NODE_LABEL
                    }
                    steps {
                        script {
                            sh "touch '${env.WORKSPACE}/.in_use'"
                            try {
                                def mutableParams = [:] + params
                                mutableParams.DURATION_IN_SECONDS = "156"
                                buildStorageCanary(true, mutableParams)
                            } finally {
                                sh "rm -f '${env.WORKSPACE}/.in_use'"
                            }
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
                            ws("${env.JOB_NAME}-master-${BUILD_NUMBER}") {
                                sh "touch '${env.WORKSPACE}/.in_use'"
                                try {
                                    def mutableParams = [:] + params
                                    mutableParams.DURATION_IN_SECONDS = "156"
                                    buildStorageCanary(false, mutableParams)
                                } finally {
                                    sh "rm -f '${env.WORKSPACE}/.in_use'"
                                }
                            }
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
                // Co-resident consumer (see single-viewer stage). Gated on VIDEO_VERIFY_ENABLED so
                // master + 3 viewers + consumer all exercise the same continuous master session.
                stage('StorageConsumer') {
                    when {
                        equals expected: true, actual: params.VIDEO_VERIFY_ENABLED
                    }
                    agent {
                        label params.CONSUMER_NODE_LABEL
                    }
                    steps {
                        script {
                            sh "touch '${env.WORKSPACE}/.in_use'"
                            try {
                                def mutableParams = [:] + params
                                mutableParams.DURATION_IN_SECONDS = "156"
                                buildStorageCanary(true, mutableParams)
                            } finally {
                                sh "rm -f '${env.WORKSPACE}/.in_use'"
                            }
                        }
                    }
                }
            }
        }

        stage('VO Master with Mixed Viewers') {
            when {
                equals expected: true, actual: params.JS_STORAGE_VO_MIXED_VIEWERS
            }
            parallel {
                stage('VO StorageMaster') {
                    agent {
                        label params.MASTER_NODE_LABEL
                    }
                    steps {
                        script {
                            ws("${env.JOB_NAME}-master-${BUILD_NUMBER}") {
                                sh "touch '${env.WORKSPACE}/.in_use'"
                                try {
                                    def mutableParams = [:] + params
                                    mutableParams.DURATION_IN_SECONDS = "156"
                                    // Set video-only mode for the master
                                    env.CANARY_MEDIA_TYPE = "video_only"
                                    // Wait for all viewers to join before starting the master binary
                                    env.CANARY_WAIT_FOR_VIEWERS = "true"
                                    buildStorageCanary(false, mutableParams)
                                } finally {
                                    sh "rm -f '${env.WORKSPACE}/.in_use'"
                                }
                            }
                        }
                    }
                }
                stage('AOViewer1') {
                    agent {
                        label params.STORAGE_VIEWER_ONE_NODE_LABEL
                    }
                    steps {
                        script {
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '')
                                ? params.VIEWER_WAIT_MINUTES.toInteger()
                                : 20
                            runViewerSessions("Viewer1", waitMins, "3", 0, true)
                        }
                    }
                }
                stage('AOViewer2') {
                    agent {
                        label params.STORAGE_VIEWER_TWO_NODE_LABEL
                    }
                    steps {
                        script {
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '')
                                ? params.VIEWER_WAIT_MINUTES.toInteger()
                                : 20
                            runViewerSessions("Viewer2", waitMins, "3", 2, true)
                        }
                    }
                }
                stage('ROViewer3') {
                    agent {
                        label params.STORAGE_VIEWER_THREE_NODE_LABEL
                    }
                    steps {
                        script {
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '')
                                ? params.VIEWER_WAIT_MINUTES.toInteger()
                                : 20
                            runViewerSessions("Viewer3", waitMins, "3", 4, false)
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
                    equals expected: true, actual: params.JS_STORAGE_VO_MIXED_VIEWERS
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
                    pushKeepAlive('CleanupComplete')
                    env.AWS_ACCESS_KEY_ID = ''
                    env.AWS_SECRET_ACCESS_KEY = ''
                    env.AWS_SESSION_TOKEN = ''
                }
            }
        }

    }
    
    post {
        always {
            script {
                // Remove workspace lock so cron can clean it up later
                sh "rm -f '${env.WORKSPACE}/.in_use'"

                echo "=========================================="
                echo "Gamma Runner Summary"
                echo "=========================================="
                echo "Build result: ${currentBuild.result ?: 'SUCCESS'}"
                echo "Has errors: ${HAS_ERROR}"
                echo "=========================================="

                if (currentBuild.result == 'ABORTED' || IS_ABORTED) {
                    echo "Build was aborted, skipping reschedule"
                } else if (currentBuild.result == 'NOT_BUILT') {
                    echo "Build was skipped as a duplicate, skipping reschedule"
                } else if (params.RESCHEDULE) {
                    def rescheduleParams = [
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
                        booleanParam(name: 'JS_STORAGE_VO_MIXED_VIEWERS', value: params.JS_STORAGE_VO_MIXED_VIEWERS),
                        booleanParam(name: 'RESCHEDULE', value: params.RESCHEDULE),
                        booleanParam(name: 'USE_TURN', value: params.USE_TURN),
                        booleanParam(name: 'USE_IOT', value: params.USE_IOT),
                        booleanParam(name: 'TRICKLE_ICE', value: params.TRICKLE_ICE),
                        booleanParam(name: 'FORCE_TURN', value: params.FORCE_TURN),
                        booleanParam(name: 'NO_LOOP_FRAMES', value: params.NO_LOOP_FRAMES),
                        string(name: 'STORAGE_FPS', value: params.STORAGE_FPS),
                        string(name: 'STORAGE_ASSET_SET', value: params.STORAGE_ASSET_SET),
                        string(name: 'CANARY_ASSET_BUCKET', value: params.CANARY_ASSET_BUCKET),
                        string(name: 'CANARY_ASSET_PREFIX', value: params.CANARY_ASSET_PREFIX),
                        string(name: 'CANARY_ASSET_REGION', value: params.CANARY_ASSET_REGION),
                        string(name: 'JS_BRANCH', value: params.JS_BRANCH),
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
