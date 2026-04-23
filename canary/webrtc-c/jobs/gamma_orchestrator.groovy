/**
 * Gamma Test Orchestrator
 * 
 * This orchestrator runs WebRTC storage viewer tests against a custom endpoint (e.g., gamma).
 * It uses the dedicated webrtc-gamma-runner job, which is independent from production runners.
 * 
 * Tests available:
 *   - StorageWithViewer (1 viewer)
 *   - StorageTwoViewers (2 viewers)  
 *   - StorageThreeViewers (3 viewers)
 * 
 * Usage:
 *   1. Click "Build with Parameters"
 *   2. Enter the gamma endpoint URL in the ENDPOINT field
 *   3. Select which tests to run
 *   4. Click "Build"
 */

GIT_URL = 'https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git'
GIT_HASH = 'clean_viewer_test'

// Dedicated gamma runner job name
GAMMA_RUNNER_JOB = "webrtc-gamma-runner"

// Test duration for gamma tests (shorter than production for faster feedback)
TEST_DURATION_IN_SECONDS = 600 // 10 min

pipeline {
    agent {
        label 'profiling'
    }

    parameters {
        string(
            name: 'ENDPOINT',
            defaultValue: '',
            description: 'Custom endpoint URL (e.g., gamma endpoint). This is REQUIRED.'
        )
        string(
            name: 'AWS_DEFAULT_REGION',
            defaultValue: 'us-west-2',
            description: 'AWS region for the tests'
        )
        string(
            name: 'VIEWER_WAIT_MINUTES',
            defaultValue: '2',
            description: 'Minutes to wait for master to build before starting viewers (0 = no wait)'
        )
        string(
            name: 'VIEWER_SESSION_DURATION_SECONDS',
            defaultValue: '600',
            description: 'Duration in seconds for each viewer session (default 10 minutes)'
        )
        booleanParam(
            name: 'RUN_STORAGE_WITH_VIEWER',
            defaultValue: true,
            description: 'Run StorageWithViewer test (1 viewer)'
        )
        booleanParam(
            name: 'RUN_STORAGE_TWO_VIEWERS',
            defaultValue: true,
            description: 'Run StorageTwoViewers test (2 viewers)'
        )
        booleanParam(
            name: 'RUN_STORAGE_THREE_VIEWERS',
            defaultValue: true,
            description: 'Run StorageThreeViewers test (3 viewers)'
        )
        booleanParam(
            name: 'RUN_STORAGE_PERIODIC',
            defaultValue: true,
            description: 'Run StoragePeriodic test (master + consumer, 156s)'
        )
        booleanParam(
            name: 'RUN_STORAGE_SUB_RECONNECT',
            defaultValue: true,
            description: 'Run StorageSubReconnect test (master + consumer, 45 min)'
        )
        booleanParam(
            name: 'RUN_STORAGE_SINGLE_RECONNECT',
            defaultValue: true,
            description: 'Run StorageSingleReconnect test (master + consumer, 65 min)'
        )
        string(
            name: 'GIT_HASH',
            defaultValue: GIT_HASH,
            description: 'Git branch/tag/commit to use'
        )
        booleanParam(
            name: 'KEEP_RECORDING',
            defaultValue: false,
            description: 'Keep viewer video recordings after verification (for debugging)'
        )
        booleanParam(
            name: 'RESCHEDULE',
            defaultValue: false,
            description: 'Enable continuous testing (reschedule after each run completes)'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timestamps()
    }

    stages {
        stage('Validate Parameters') {
            steps {
                script {
                    if (!params.ENDPOINT?.trim()) {
                        error "ENDPOINT parameter is required. Please provide the gamma endpoint URL."
                    }
                    
                    if (!params.RUN_STORAGE_WITH_VIEWER && !params.RUN_STORAGE_TWO_VIEWERS && !params.RUN_STORAGE_THREE_VIEWERS && !params.RUN_STORAGE_PERIODIC && !params.RUN_STORAGE_SUB_RECONNECT && !params.RUN_STORAGE_SINGLE_RECONNECT) {
                        error "At least one test must be selected to run."
                    }
                    
                    echo "=========================================="
                    echo "Gamma Test Configuration"
                    echo "=========================================="
                    echo "Endpoint: ${params.ENDPOINT}"
                    echo "Region: ${params.AWS_DEFAULT_REGION}"
                    echo "Viewer Wait: ${params.VIEWER_WAIT_MINUTES} minutes"
                    echo "Git Hash: ${params.GIT_HASH}"
                    echo "Tests to run:"
                    if (params.RUN_STORAGE_PERIODIC) echo "  - StoragePeriodic (master + consumer, 156s)"
                    if (params.RUN_STORAGE_SUB_RECONNECT) echo "  - StorageSubReconnect (master + consumer, 45 min)"
                    if (params.RUN_STORAGE_SINGLE_RECONNECT) echo "  - StorageSingleReconnect (master + consumer, 65 min)"
                    if (params.RUN_STORAGE_WITH_VIEWER) echo "  - StorageWithViewer (1 viewer)"
                    if (params.RUN_STORAGE_TWO_VIEWERS) echo "  - StorageTwoViewers (2 viewers)"
                    if (params.RUN_STORAGE_THREE_VIEWERS) echo "  - StorageThreeViewers (3 viewers)"
                    echo "=========================================="
                }
            }
        }

        stage('Checkout') {
            steps {
                checkout([$class: 'GitSCM', branches: [[name: params.GIT_HASH]],
                          userRemoteConfigs: [[url: GIT_URL]]])
                
                script {
                    env.CURRENT_GIT_HASH = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                    echo "Using git hash: ${env.CURRENT_GIT_HASH}"
                }
            }
        }

        stage('Run Gamma Tests') {
            parallel {
                stage('StorageWithViewer') {
                    when {
                        expression { return params.RUN_STORAGE_WITH_VIEWER }
                    }
                    steps {
                        script {
                            echo "Starting StorageWithViewer test..."
                            def result = build(
                                job: GAMMA_RUNNER_JOB,
                                parameters: [
                                    string(name: 'AWS_KVS_LOG_LEVEL', value: "2"),
                                    string(name: 'GIT_URL', value: GIT_URL),
                                    string(name: 'GIT_HASH', value: env.CURRENT_GIT_HASH),
                                    string(name: 'LOG_GROUP_NAME', value: "WebrtcSDK"),
                                    booleanParam(name: 'DEBUG_LOG_SDP', value: true),
                                    booleanParam(name: 'JS_STORAGE_VIEWER_JOIN', value: true),
                                    booleanParam(name: 'JS_STORAGE_TWO_VIEWERS', value: false),
                                    booleanParam(name: 'JS_STORAGE_THREE_VIEWERS', value: false),
                                    string(name: 'DURATION_IN_SECONDS', value: TEST_DURATION_IN_SECONDS.toString()),
                                    string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master"),
                                    string(name: 'STORAGE_VIEWER_NODE_LABEL', value: "webrtc-storage-viewer"),
                                    string(name: 'RUNNER_LABEL', value: "GammaStorageWithViewer"),
                                    string(name: 'SCENARIO_LABEL', value: "GammaStorageWithViewer"),
                                    string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                                    string(name: 'ENDPOINT', value: params.ENDPOINT),
                                    string(name: 'METRIC_SUFFIX', value: "-gamma"),
                                    string(name: 'VIEWER_WAIT_MINUTES', value: params.VIEWER_WAIT_MINUTES),
                                    string(name: 'VIEWER_SESSION_DURATION_SECONDS', value: params.VIEWER_SESSION_DURATION_SECONDS),
                                    booleanParam(name: 'KEEP_RECORDING', value: params.KEEP_RECORDING),
                                    booleanParam(name: 'FIRST_ITERATION', value: true),
                                    booleanParam(name: 'RESCHEDULE', value: params.RESCHEDULE),
                                ],
                                wait: true,
                                propagate: false
                            )
                            echo "StorageWithViewer result: ${result.result}"
                        }
                    }
                }

                stage('StorageTwoViewers') {
                    when {
                        expression { return params.RUN_STORAGE_TWO_VIEWERS }
                    }
                    steps {
                        script {
                            echo "Starting StorageTwoViewers test..."
                            def result = build(
                                job: GAMMA_RUNNER_JOB,
                                parameters: [
                                    string(name: 'AWS_KVS_LOG_LEVEL', value: "2"),
                                    string(name: 'GIT_URL', value: GIT_URL),
                                    string(name: 'GIT_HASH', value: env.CURRENT_GIT_HASH),
                                    string(name: 'LOG_GROUP_NAME', value: "WebrtcSDK"),
                                    booleanParam(name: 'DEBUG_LOG_SDP', value: true),
                                    booleanParam(name: 'JS_STORAGE_VIEWER_JOIN', value: false),
                                    booleanParam(name: 'JS_STORAGE_TWO_VIEWERS', value: true),
                                    booleanParam(name: 'JS_STORAGE_THREE_VIEWERS', value: false),
                                    string(name: 'DURATION_IN_SECONDS', value: TEST_DURATION_IN_SECONDS.toString()),
                                    string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master"),
                                    string(name: 'STORAGE_VIEWER_ONE_NODE_LABEL', value: "webrtc-storage-multi-viewer-1"),
                                    string(name: 'STORAGE_VIEWER_TWO_NODE_LABEL', value: "webrtc-storage-multi-viewer-2"),
                                    string(name: 'RUNNER_LABEL', value: "GammaStorageTwoViewers"),
                                    string(name: 'SCENARIO_LABEL', value: "GammaStorageTwoViewers"),
                                    string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                                    string(name: 'ENDPOINT', value: params.ENDPOINT),
                                    string(name: 'METRIC_SUFFIX', value: "-gamma"),
                                    string(name: 'VIEWER_WAIT_MINUTES', value: params.VIEWER_WAIT_MINUTES),
                                    string(name: 'VIEWER_SESSION_DURATION_SECONDS', value: params.VIEWER_SESSION_DURATION_SECONDS),
                                    booleanParam(name: 'KEEP_RECORDING', value: params.KEEP_RECORDING),
                                    booleanParam(name: 'FIRST_ITERATION', value: true),
                                    booleanParam(name: 'RESCHEDULE', value: params.RESCHEDULE),
                                ],
                                wait: true,
                                propagate: false
                            )
                            echo "StorageTwoViewers result: ${result.result}"
                        }
                    }
                }

                stage('StorageThreeViewers') {
                    when {
                        expression { return params.RUN_STORAGE_THREE_VIEWERS }
                    }
                    steps {
                        script {
                            echo "Starting StorageThreeViewers test..."
                            def result = build(
                                job: GAMMA_RUNNER_JOB,
                                parameters: [
                                    string(name: 'AWS_KVS_LOG_LEVEL', value: "2"),
                                    string(name: 'GIT_URL', value: GIT_URL),
                                    string(name: 'GIT_HASH', value: env.CURRENT_GIT_HASH),
                                    string(name: 'LOG_GROUP_NAME', value: "WebrtcSDK"),
                                    booleanParam(name: 'DEBUG_LOG_SDP', value: true),
                                    booleanParam(name: 'JS_STORAGE_VIEWER_JOIN', value: false),
                                    booleanParam(name: 'JS_STORAGE_TWO_VIEWERS', value: false),
                                    booleanParam(name: 'JS_STORAGE_THREE_VIEWERS', value: true),
                                    string(name: 'DURATION_IN_SECONDS', value: TEST_DURATION_IN_SECONDS.toString()),
                                    string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master"),
                                    string(name: 'STORAGE_VIEWER_ONE_NODE_LABEL', value: "webrtc-storage-multi-viewer-1"),
                                    string(name: 'STORAGE_VIEWER_TWO_NODE_LABEL', value: "webrtc-storage-multi-viewer-2"),
                                    string(name: 'STORAGE_VIEWER_THREE_NODE_LABEL', value: "webrtc-storage-consumer"),
                                    string(name: 'RUNNER_LABEL', value: "GammaStorageThreeViewers"),
                                    string(name: 'SCENARIO_LABEL', value: "GammaStorageThreeViewers"),
                                    string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                                    string(name: 'ENDPOINT', value: params.ENDPOINT),
                                    string(name: 'METRIC_SUFFIX', value: "-gamma"),
                                    string(name: 'VIEWER_WAIT_MINUTES', value: params.VIEWER_WAIT_MINUTES),
                                    string(name: 'VIEWER_SESSION_DURATION_SECONDS', value: params.VIEWER_SESSION_DURATION_SECONDS),
                                    booleanParam(name: 'KEEP_RECORDING', value: params.KEEP_RECORDING),
                                    booleanParam(name: 'FIRST_ITERATION', value: true),
                                    booleanParam(name: 'RESCHEDULE', value: params.RESCHEDULE),
                                ],
                                wait: true,
                                propagate: false
                            )
                            echo "StorageThreeViewers result: ${result.result}"
                        }
                    }
                }

                stage('StoragePeriodic') {
                    when {
                        expression { return params.RUN_STORAGE_PERIODIC }
                    }
                    steps {
                        script {
                            echo "Starting StoragePeriodic test..."
                            def result = build(
                                job: GAMMA_RUNNER_JOB,
                                parameters: [
                                    string(name: 'AWS_KVS_LOG_LEVEL', value: "2"),
                                    string(name: 'GIT_URL', value: GIT_URL),
                                    string(name: 'GIT_HASH', value: env.CURRENT_GIT_HASH),
                                    string(name: 'LOG_GROUP_NAME', value: "WebrtcSDK"),
                                    booleanParam(name: 'IS_STORAGE', value: true),
                                    booleanParam(name: 'VIDEO_VERIFY_ENABLED', value: true),
                                    string(name: 'DURATION_IN_SECONDS', value: "156"),
                                    string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master"),
                                    string(name: 'CONSUMER_NODE_LABEL', value: "webrtc-storage-consumer"),
                                    string(name: 'RUNNER_LABEL', value: "GammaStoragePeriodic"),
                                    string(name: 'SCENARIO_LABEL', value: "GammaStoragePeriodic"),
                                    string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                                    string(name: 'ENDPOINT', value: params.ENDPOINT),
                                    string(name: 'METRIC_SUFFIX', value: "-gamma"),
                                    booleanParam(name: 'USE_TURN', value: true),
                                    booleanParam(name: 'TRICKLE_ICE', value: true),
                                    booleanParam(name: 'FIRST_ITERATION', value: true),
                                    booleanParam(name: 'RESCHEDULE', value: params.RESCHEDULE),
                                ],
                                wait: true,
                                propagate: false
                            )
                            echo "StoragePeriodic result: ${result.result}"
                        }
                    }
                }

                stage('StorageSubReconnect') {
                    when {
                        expression { return params.RUN_STORAGE_SUB_RECONNECT }
                    }
                    steps {
                        script {
                            echo "Starting StorageSubReconnect test..."
                            def result = build(
                                job: GAMMA_RUNNER_JOB,
                                parameters: [
                                    string(name: 'AWS_KVS_LOG_LEVEL', value: "2"),
                                    string(name: 'GIT_URL', value: GIT_URL),
                                    string(name: 'GIT_HASH', value: env.CURRENT_GIT_HASH),
                                    string(name: 'LOG_GROUP_NAME', value: "WebrtcSDK"),
                                    booleanParam(name: 'IS_STORAGE', value: true),
                                    booleanParam(name: 'VIDEO_VERIFY_ENABLED', value: true),
                                    string(name: 'DURATION_IN_SECONDS', value: "2700"),
                                    string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master"),
                                    string(name: 'CONSUMER_NODE_LABEL', value: "webrtc-storage-consumer"),
                                    string(name: 'RUNNER_LABEL', value: "GammaStorageSubReconnect"),
                                    string(name: 'SCENARIO_LABEL', value: "GammaStorageSubReconnect"),
                                    string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                                    string(name: 'ENDPOINT', value: params.ENDPOINT),
                                    string(name: 'METRIC_SUFFIX', value: "-gamma"),
                                    booleanParam(name: 'USE_TURN', value: true),
                                    booleanParam(name: 'TRICKLE_ICE', value: true),
                                    booleanParam(name: 'FIRST_ITERATION', value: true),
                                    booleanParam(name: 'RESCHEDULE', value: params.RESCHEDULE),
                                ],
                                wait: true,
                                propagate: false
                            )
                            echo "StorageSubReconnect result: ${result.result}"
                        }
                    }
                }

                stage('StorageSingleReconnect') {
                    when {
                        expression { return params.RUN_STORAGE_SINGLE_RECONNECT }
                    }
                    steps {
                        script {
                            echo "Starting StorageSingleReconnect test..."
                            def result = build(
                                job: GAMMA_RUNNER_JOB,
                                parameters: [
                                    string(name: 'AWS_KVS_LOG_LEVEL', value: "2"),
                                    string(name: 'GIT_URL', value: GIT_URL),
                                    string(name: 'GIT_HASH', value: env.CURRENT_GIT_HASH),
                                    string(name: 'LOG_GROUP_NAME', value: "WebrtcSDK"),
                                    booleanParam(name: 'IS_STORAGE', value: true),
                                    booleanParam(name: 'VIDEO_VERIFY_ENABLED', value: true),
                                    string(name: 'DURATION_IN_SECONDS', value: "3900"),
                                    string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master"),
                                    string(name: 'CONSUMER_NODE_LABEL', value: "webrtc-storage-consumer"),
                                    string(name: 'RUNNER_LABEL', value: "GammaStorageSingleReconnect"),
                                    string(name: 'SCENARIO_LABEL', value: "GammaStorageSingleReconnect"),
                                    string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                                    string(name: 'ENDPOINT', value: params.ENDPOINT),
                                    string(name: 'METRIC_SUFFIX', value: "-gamma"),
                                    booleanParam(name: 'USE_TURN', value: true),
                                    booleanParam(name: 'TRICKLE_ICE', value: true),
                                    booleanParam(name: 'FIRST_ITERATION', value: true),
                                    booleanParam(name: 'RESCHEDULE', value: params.RESCHEDULE),
                                ],
                                wait: true,
                                propagate: false
                            )
                            echo "StorageSingleReconnect result: ${result.result}"
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            echo "All gamma tests completed successfully!"
        }
        failure {
            echo "Some gamma tests failed. Check individual test results."
        }
        always {
            echo "=========================================="
            echo "Gamma Test Summary"
            echo "=========================================="
            echo "Endpoint: ${params.ENDPOINT}"
            echo "Region: ${params.AWS_DEFAULT_REGION}"
            echo "Build result: ${currentBuild.result ?: 'SUCCESS'}"
            echo "=========================================="
        }
    }
}
