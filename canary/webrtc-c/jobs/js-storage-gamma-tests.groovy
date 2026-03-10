import jenkins.model.*

/**
 * JS Storage Gamma Tests Pipeline
 * 
 * This pipeline runs all JS-based storage viewer tests against a custom endpoint (e.g., gamma).
 * Tests included:
 *   - StorageWithViewer (1 viewer)
 *   - StorageTwoViewers (2 viewers)
 *   - StorageThreeViewers (3 viewers)
 * 
 * Usage:
 *   1. Click "Build with Parameters"
 *   2. Enter the gamma endpoint URL in the ENDPOINT field
 *   3. Click "Build"
 */

RUNNER_JOB_NAME_PREFIX = "webrtc-canary-runner"
GIT_URL = 'https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git'
GIT_HASH = 'clean_viewer_test'

// Test duration for JS storage tests
JS_STORAGE_TEST_DURATION_IN_SECONDS = 600 // 10 min

// Common parameters for all tests
COMMON_PARAMS = [
    string(name: 'AWS_KVS_LOG_LEVEL', value: "2"),
    string(name: 'DEBUG_LOG_SDP', value: "TRUE"),
    string(name: 'MIN_RETRY_DELAY_IN_SECONDS', value: "60"),
    string(name: 'GIT_URL', value: GIT_URL),
    string(name: 'LOG_GROUP_NAME', value: "WebrtcSDK"),
]

def findAvailableRunner() {
    def runners = Jenkins.instance
        .getAllItems(Job.class)
        .findAll({ item -> item.getDisplayName().startsWith(RUNNER_JOB_NAME_PREFIX) })
    
    def availableRunner = runners.find({ item -> 
        item.isDisabled() || !item.isBuilding()
    })
    
    if (availableRunner == null) {
        error "No available runner found. All runners are busy."
    }
    
    return availableRunner.getDisplayName()
}

pipeline {
    agent {
        label 'profiling'
    }

    parameters {
        string(
            name: 'ENDPOINT',
            defaultValue: '',
            description: 'Custom endpoint URL (e.g., gamma endpoint). This is REQUIRED for this job.'
        )
        string(
            name: 'AWS_DEFAULT_REGION',
            defaultValue: 'us-west-2',
            description: 'AWS region for the tests'
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
                    
                    if (!params.RUN_STORAGE_WITH_VIEWER && !params.RUN_STORAGE_TWO_VIEWERS && !params.RUN_STORAGE_THREE_VIEWERS) {
                        error "At least one test must be selected to run."
                    }
                    
                    echo "=========================================="
                    echo "JS Storage Gamma Tests Configuration"
                    echo "=========================================="
                    echo "Endpoint: ${params.ENDPOINT}"
                    echo "Region: ${params.AWS_DEFAULT_REGION}"
                    echo "Tests to run:"
                    if (params.RUN_STORAGE_WITH_VIEWER) echo "  - StorageWithViewer (1 viewer)"
                    if (params.RUN_STORAGE_TWO_VIEWERS) echo "  - StorageTwoViewers (2 viewers)"
                    if (params.RUN_STORAGE_THREE_VIEWERS) echo "  - StorageThreeViewers (3 viewers)"
                    echo "=========================================="
                }
            }
        }

        stage('Checkout') {
            steps {
                checkout([$class: 'GitSCM', branches: [[name: GIT_HASH]],
                          userRemoteConfigs: [[url: GIT_URL]]])
            }
        }

        stage('Find Available Runner') {
            steps {
                script {
                    env.RUNNER_JOB = findAvailableRunner()
                    echo "Using runner: ${env.RUNNER_JOB}"
                    
                    // Lock in current commit hash
                    env.CURRENT_GIT_HASH = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                    echo "Git hash: ${env.CURRENT_GIT_HASH}"
                }
            }
        }

        stage('Run JS Storage Tests') {
            parallel {
                stage('StorageWithViewer') {
                    when {
                        expression { return params.RUN_STORAGE_WITH_VIEWER }
                    }
                    steps {
                        script {
                            echo "Starting StorageWithViewer test with endpoint: ${params.ENDPOINT}"
                            build(
                                job: env.RUNNER_JOB,
                                parameters: COMMON_PARAMS + [
                                    string(name: 'GIT_HASH', value: env.CURRENT_GIT_HASH),
                                    booleanParam(name: 'IS_SIGNALING', value: false),
                                    booleanParam(name: 'IS_STORAGE', value: false),
                                    booleanParam(name: 'IS_STORAGE_SINGLE_NODE', value: false),
                                    booleanParam(name: 'USE_TURN', value: false),
                                    booleanParam(name: 'TRICKLE_ICE', value: false),
                                    booleanParam(name: 'USE_IOT', value: false),
                                    booleanParam(name: 'JS_STORAGE_VIEWER_JOIN', value: true),
                                    string(name: 'DURATION_IN_SECONDS', value: JS_STORAGE_TEST_DURATION_IN_SECONDS.toString()),
                                    string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master-2"),
                                    string(name: 'STORAGE_VIEWER_NODE_LABEL', value: "webrtc-storage-viewer"),
                                    string(name: 'RUNNER_LABEL', value: "GammaStorageWithViewer"),
                                    string(name: 'SCENARIO_LABEL', value: "GammaStorageWithViewer"),
                                    string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                                    string(name: 'ENDPOINT', value: params.ENDPOINT),
                                    string(name: 'METRIC_SUFFIX', value: "-gamma"),
                                    string(name: 'VIEWER_WAIT_MINUTES', value: "25"),
                                    booleanParam(name: 'FIRST_ITERATION', value: true),
                                ],
                                wait: true,
                                propagate: false
                            )
                        }
                    }
                }

                stage('StorageTwoViewers') {
                    when {
                        expression { return params.RUN_STORAGE_TWO_VIEWERS }
                    }
                    steps {
                        script {
                            echo "Starting StorageTwoViewers test with endpoint: ${params.ENDPOINT}"
                            build(
                                job: env.RUNNER_JOB,
                                parameters: COMMON_PARAMS + [
                                    string(name: 'GIT_HASH', value: env.CURRENT_GIT_HASH),
                                    booleanParam(name: 'IS_SIGNALING', value: false),
                                    booleanParam(name: 'IS_STORAGE', value: false),
                                    booleanParam(name: 'IS_STORAGE_SINGLE_NODE', value: false),
                                    booleanParam(name: 'USE_TURN', value: false),
                                    booleanParam(name: 'TRICKLE_ICE', value: false),
                                    booleanParam(name: 'USE_IOT', value: false),
                                    booleanParam(name: 'JS_STORAGE_VIEWER_JOIN', value: false),
                                    booleanParam(name: 'JS_STORAGE_TWO_VIEWERS', value: true),
                                    string(name: 'DURATION_IN_SECONDS', value: JS_STORAGE_TEST_DURATION_IN_SECONDS.toString()),
                                    string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master-2"),
                                    string(name: 'STORAGE_VIEWER_NODE_LABEL', value: "webrtc-storage-viewer"),
                                    string(name: 'STORAGE_VIEWER_ONE_NODE_LABEL', value: "webrtc-storage-multi-viewer-1"),
                                    string(name: 'STORAGE_VIEWER_TWO_NODE_LABEL', value: "webrtc-storage-multi-viewer-2"),
                                    string(name: 'RUNNER_LABEL', value: "GammaStorageTwoViewers"),
                                    string(name: 'SCENARIO_LABEL', value: "GammaStorageTwoViewers"),
                                    string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                                    string(name: 'ENDPOINT', value: params.ENDPOINT),
                                    string(name: 'METRIC_SUFFIX', value: "-gamma"),
                                    string(name: 'VIEWER_WAIT_MINUTES', value: "25"),
                                    booleanParam(name: 'FIRST_ITERATION', value: true),
                                ],
                                wait: true,
                                propagate: false
                            )
                        }
                    }
                }

                stage('StorageThreeViewers') {
                    when {
                        expression { return params.RUN_STORAGE_THREE_VIEWERS }
                    }
                    steps {
                        script {
                            echo "Starting StorageThreeViewers test with endpoint: ${params.ENDPOINT}"
                            build(
                                job: env.RUNNER_JOB,
                                parameters: COMMON_PARAMS + [
                                    string(name: 'GIT_HASH', value: env.CURRENT_GIT_HASH),
                                    booleanParam(name: 'IS_SIGNALING', value: false),
                                    booleanParam(name: 'IS_STORAGE', value: false),
                                    booleanParam(name: 'IS_STORAGE_SINGLE_NODE', value: false),
                                    booleanParam(name: 'USE_TURN', value: false),
                                    booleanParam(name: 'TRICKLE_ICE', value: false),
                                    booleanParam(name: 'USE_IOT', value: false),
                                    booleanParam(name: 'JS_STORAGE_VIEWER_JOIN', value: false),
                                    booleanParam(name: 'JS_STORAGE_TWO_VIEWERS', value: false),
                                    booleanParam(name: 'JS_STORAGE_THREE_VIEWERS', value: true),
                                    string(name: 'DURATION_IN_SECONDS', value: JS_STORAGE_TEST_DURATION_IN_SECONDS.toString()),
                                    string(name: 'MASTER_NODE_LABEL', value: "webrtc-storage-master-2"),
                                    string(name: 'STORAGE_VIEWER_NODE_LABEL', value: "webrtc-storage-viewer"),
                                    string(name: 'STORAGE_VIEWER_ONE_NODE_LABEL', value: "webrtc-storage-multi-viewer-1"),
                                    string(name: 'STORAGE_VIEWER_TWO_NODE_LABEL', value: "webrtc-storage-multi-viewer-2"),
                                    string(name: 'STORAGE_VIEWER_THREE_NODE_LABEL', value: "webrtc-storage-consumer"),
                                    string(name: 'RUNNER_LABEL', value: "GammaStorageThreeViewers"),
                                    string(name: 'SCENARIO_LABEL', value: "GammaStorageThreeViewers"),
                                    string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                                    string(name: 'ENDPOINT', value: params.ENDPOINT),
                                    string(name: 'METRIC_SUFFIX', value: "-gamma"),
                                    string(name: 'VIEWER_WAIT_MINUTES', value: "25"),
                                    booleanParam(name: 'FIRST_ITERATION', value: true),
                                ],
                                wait: true,
                                propagate: false
                            )
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            echo "All JS Storage Gamma tests completed successfully!"
        }
        failure {
            echo "Some JS Storage Gamma tests failed. Check individual test results."
        }
        always {
            echo "=========================================="
            echo "JS Storage Gamma Tests Summary"
            echo "=========================================="
            echo "Endpoint used: ${params.ENDPOINT}"
            echo "Region: ${params.AWS_DEFAULT_REGION}"
            echo "Build result: ${currentBuild.result ?: 'SUCCESS'}"
            echo "=========================================="
        }
    }
}
