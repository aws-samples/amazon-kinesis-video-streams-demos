import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

START_TIMESTAMP = new Date().getTime()
RUNNING_NODES_IN_BUILDING = 0
HAS_ERROR = false

// Track per-viewer connection attempt results for aggregate ViewerConnectionSuccessRate metric.
// Structure: { "Viewer1": {attempts: N, successes: N}, "Viewer2": {attempts: N, successes: N}, ... }
// Access is synchronized since parallel stages write to different keys.
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

def buildWebRTCProject(useMbedTLS, thing_prefix) {
    echo 'Flag set to ' + useMbedTLS
    checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH ]],
              userRemoteConfigs: [[url: params.GIT_URL]]])

    // Determine cache key from both the demos repo and the WebRTC C SDK repo.
    // The CMakeLists.txt pins a GIT_TAG for the SDK, but we resolve it to a
    // commit hash so we detect any tag force-pushes or branch changes too.
    def demosHash = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    def webrtcSdkTag = sh(returnStdout: true, script: """
        grep -A2 'FetchContent_Declare' canary/webrtc-c/CMakeLists.txt | grep 'GIT_TAG' | head -1 | awk '{print \$2}'
    """).trim()
    def webrtcSdkHash = sh(returnStdout: true, script: """
        git ls-remote https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-c '${webrtcSdkTag}' | cut -f1
    """).trim()

    echo "Demos repo hash: ${demosHash}"
    echo "WebRTC C SDK tag: ${webrtcSdkTag} -> ${webrtcSdkHash}"

    def combinedHash = "${demosHash}-${webrtcSdkHash}"
    def cacheKey = useMbedTLS ? "mbedtls" : "openssl"
    def cacheDir = "/tmp/kvs-webrtc-build-cache/${cacheKey}"
    def cachedHashFile = "${cacheDir}/.git-hash"
    def lockFile = "/tmp/kvs-webrtc-build-cache/.${cacheKey}.lock"
    def buildDir = "${env.WORKSPACE}/canary/webrtc-c/build"

    // Always run cert_setup (certs are per-job, not cacheable)
    sh """
        cd ./canary/webrtc-c/scripts &&
        chmod a+x cert_setup.sh &&
        ./cert_setup.sh ${thing_prefix}"""

    // Use flock to serialize builds across concurrent jobs on the same node.
    // The first job to acquire the lock builds and caches. Others wait, then
    // find the cache populated and skip the build.
    def binDir = sh(returnStdout: true, script: """
        mkdir -p /tmp/kvs-webrtc-build-cache
        exec 9>'${lockFile}'
        flock 9
        echo "Lock acquired for ${cacheKey}" >&2

        CACHED_HASH=\$(cat '${cachedHashFile}' 2>/dev/null || echo '')
        echo "Cache check: cached=\$CACHED_HASH, current=${combinedHash}" >&2
        if [ "\$CACHED_HASH" = '${combinedHash}' ]; then
            echo "Build cache hit for ${cacheKey} — demos and WebRTC SDK unchanged" >&2
            echo '${cacheDir}'
        else
            if [ -z "\$CACHED_HASH" ]; then
                echo "Build cache miss — no previous cache" >&2
            else
                # Log which part changed
                OLD_DEMOS=\$(echo "\$CACHED_HASH" | cut -d- -f1)
                OLD_SDK=\$(echo "\$CACHED_HASH" | cut -d- -f2)
                if [ "\$OLD_DEMOS" != '${demosHash}' ]; then
                    echo "Build cache miss — demos repo changed (\$OLD_DEMOS -> ${demosHash})" >&2
                fi
                if [ "\$OLD_SDK" != '${webrtcSdkHash}' ]; then
                    echo "Build cache miss — WebRTC C SDK changed (\$OLD_SDK -> ${webrtcSdkHash})" >&2
                fi
            fi

            cd ./canary/webrtc-c
            rm -rf build
            mkdir -p build
            cd build
            cmake .. -DCMAKE_BUILD_TYPE=Debug -DCMAKE_INSTALL_PREFIX="\$PWD" ${useMbedTLS ? '-DCANARY_USE_OPENSSL=OFF -DCANARY_USE_MBEDTLS=ON' : ''} >&2 2>&1
            make >&2 2>&1

            echo "Caching binaries to ${cacheDir}..." >&2
            TMPDIR=\$(mktemp -d /tmp/kvs-webrtc-build-cache/.${cacheKey}.XXXXXX)
            cp '${buildDir}/kvsWebrtcCanaryWebrtc' "\$TMPDIR/" 2>/dev/null || true
            cp '${buildDir}/kvsWebrtcCanarySignaling' "\$TMPDIR/" 2>/dev/null || true
            cp '${buildDir}/kvsWebrtcStorageSample' "\$TMPDIR/" 2>/dev/null || true
            cp '${buildDir}/libkvsWebrtcCanary.so' "\$TMPDIR/" 2>/dev/null || true
            if [ -d '${buildDir}/lib' ]; then
                cp -a '${buildDir}/lib' "\$TMPDIR/"
            fi
            echo '${combinedHash}' > "\$TMPDIR/.git-hash"
            # Atomic swap: rename old cache out, rename new cache in
            rm -rf '${cacheDir}.old' 2>/dev/null || true
            mv '${cacheDir}' '${cacheDir}.old' 2>/dev/null || true
            mv "\$TMPDIR" '${cacheDir}'
            rm -rf '${cacheDir}.old' 2>/dev/null || true
            echo "Build cached for ${cacheKey} at ${combinedHash}" >&2
            echo '${cacheDir}'
        fi
    """).trim()

    echo "Using binaries from: ${binDir}"
    return binDir
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
    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    def binDir = buildWebRTCProject(params.USE_MBEDTLS, thing_prefix)

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
            cd '${binDir}' &&
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
        }
    }
    def thing_prefix = "${env.JOB_NAME}-${params.RUNNER_LABEL}"
    def binDir = buildWebRTCProject(params.USE_MBEDTLS, thing_prefix)

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
            cd '${binDir}' && 
            ./kvsWebrtcCanarySignaling"""
    }
}

def runViewerSessions(viewerId = "", waitMinutes = 10, viewerCount = "1", staggerDelaySeconds = 0) {
    // Create unique workspace for each viewer to prevent Git conflicts
    def workspaceName = "${env.JOB_NAME}-${viewerId ?: 'viewer'}-${BUILD_NUMBER}"
    ws(workspaceName) {
        try {
            checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH ]],
                      userRemoteConfigs: [[url: params.GIT_URL]]])
            
            def endpointValue = params.ENDPOINT ?: ''
            def metricSuffixValue = params.METRIC_SUFFIX ?: ''

            // Run prepare while master is still building
            echo "Preparing viewer dependencies (parallel with master build)..."
            sh """
                export JS_PAGE_URL="${params.JS_PAGE_URL ?: ''}"
                ./canary/webrtc-c/scripts/prepare-storage-viewer.sh
            """

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
            
            // Staggered start delay to avoid all viewers starting at the exact same time
            if (staggerDelaySeconds > 0) {
                echo "Staggered start - waiting ${staggerDelaySeconds} seconds before starting ${viewerId ?: 'viewer'}"
                sleep staggerDelaySeconds
            }
            
            def viewerKey = viewerId ?: 'viewer'
            // Default stats in case parsing fails
            VIEWER_SESSION_RESULTS[viewerKey] = [attempts: 1, successes: 0]

            echo "Starting ${viewerId ? viewerId + ' ' : ''}viewer session"
            echo "DEBUG: ENDPOINT value = '${endpointValue}'"
            echo "DEBUG: METRIC_SUFFIX value = '${metricSuffixValue}'"
            
            try {
                def output = sh(
                    script: """
                        export JOB_NAME="${env.JOB_NAME}"
                        export RUNNER_LABEL="${params.RUNNER_LABEL}"
                        export AWS_DEFAULT_REGION="${params.AWS_DEFAULT_REGION}"
                        export DURATION_IN_SECONDS="${(params.VIEWER_SESSION_DURATION_SECONDS != null && params.VIEWER_SESSION_DURATION_SECONDS.toString().trim() != '') ? params.VIEWER_SESSION_DURATION_SECONDS : '900'}"
                        export FORCE_TURN="${params.FORCE_TURN}"
                        export VIEWER_COUNT="${viewerCount}"
                        export VIEWER_ID="${viewerId}"
                        export CLIENT_ID="${viewerId ? viewerId.toLowerCase() + '-' : 'viewer-'}${BUILD_NUMBER}"
                        export ENDPOINT="${endpointValue}"
                        export METRIC_SUFFIX="${metricSuffixValue}"
                        export JS_PAGE_URL="${params.JS_PAGE_URL ?: ''}"
                        
                        ./canary/webrtc-c/scripts/run-storage-viewer.sh
                    """,
                    returnStdout: true
                ).trim()
                
                echo output
                
                // Parse VIEWER_STATS from output
                // NOTE: avoid holding a Matcher reference across CPS step
                // boundaries — Matcher is not serializable and will crash
                // the pipeline with NotSerializableException.
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

/**
 * Computes and pushes the ViewerConnectionSuccessRate metric to CloudWatch.
 *
 * Aggregates connection attempts and successes across all viewers in the session.
 * ViewerConnectionSuccessRate = (total successes / total attempts) * 100
 *
 * @param scenarioLabel  The scenario label (e.g. "StorageThreeViewers") used as a CW dimension
 */
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
      'AWS_IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
      'AWS_IOT_CORE_CERT': "${core_cert_file}",
      'AWS_IOT_CORE_PRIVATE_KEY': "${private_key_file}",
      'AWS_IOT_CORE_ROLE_ALIAS': "${role_alias}",
      'AWS_IOT_CORE_THING_NAME': "${thing_name}"
    ]

    def masterEnvs = [
      'CANARY_DURATION_IN_SECONDS': params.DURATION_IN_SECONDS,
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
      'CANARY_DURATION_IN_SECONDS': "${params.DURATION_IN_SECONDS.toInteger() + 120}",
      'VIDEO_VERIFY_ENABLED': params.VIDEO_VERIFY_ENABLED?.toString() ?: 'false',
      'CANARY_CLIP_OUTPUT_PATH': "${env.WORKSPACE}/canary/consumer-java/clip-${START_TIMESTAMP}.mp4",
      'CONTROL_PLANE_URI': params.ENDPOINT ?: ''
    ]

    RUNNING_NODES_IN_BUILDING++
    if (!isConsumer) {
        MASTER_READY = false
    }
    def binDir = null
    if (!isConsumer){
        binDir = buildWebRTCProject(params.USE_MBEDTLS, thing_prefix)
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
            timeout(time: params.DURATION_IN_SECONDS.toInteger() + 900, unit: 'SECONDS') {
                sh """
                    cd '${binDir}' &&
                    ./kvsWebrtcStorageSample"""
            }
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
            echo "No GetClip MP4 found at ${clipPath}, skipping consumer video verification"
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
        string(name: 'VIEWER_WAIT_MINUTES', defaultValue: '20')
        string(name: 'VIEWER_SESSION_DURATION_SECONDS', defaultValue: '900', description: 'Hard timeout in seconds for the viewer process (default 15 minutes, must be larger than monitoring duration)')
        booleanParam(name: 'VIDEO_VERIFY_ENABLED', defaultValue: false, description: 'Enable consumer-side video verification via GetClip')
    }
    
    // Set the role ARN to environment to avoid string interpolation to follow Jenkins security guidelines.
    environment {
        AWS_KVS_STS_ROLE_ARN = credentials('CANARY_STS_ROLE_ARN')
    }

    stages {
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 20
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 20
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 20
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 20
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 20
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
                            def waitMins = (params.VIEWER_WAIT_MINUTES != null && params.VIEWER_WAIT_MINUTES.toString().trim() != '') ? params.VIEWER_WAIT_MINUTES.toInteger() : 20
                            // Viewer3 starts 30 seconds after Viewer1
                            runViewerSessions("Viewer3", waitMins, "3", 30)
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

        // In case of failures, we should add some delays so that we don't get into a tight loop of retrying
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
                // Don't reschedule if the build was manually aborted
                if (currentBuild.result == 'ABORTED') {
                    echo "Build was aborted, skipping reschedule"
                } else {
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
    }
}
