import jenkins.model.*
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

START_TIMESTAMP = new Date().getTime()
HAS_ERROR = false

RUNNING_NODES=0

// IoT identity prefix. With IoT the stream is named after the thing (sink_<prefix>_thing), so develop builds use
// their own per-node things to stay off the release streams. Create them once with generate-iot-credentials.sh.
def iotPrefix(params) {
  return params.KVS_SDK_TRACK == 'develop' ? "dev${env.NODE_NAME}" : "${env.NODE_NAME}"
}

def buildProducer(params) {
  // A develop-track build always starts from a clean build dir so FetchContent re-resolves the branch heads
  // instead of reusing the SDK sources cached by the previous iteration.
  def cleanBuildDir = params.KVS_SDK_TRACK == 'develop' ? 'rm -rf build &&' : ''
  // A release-track build reuses build/ and the SDK's install dirs (lib/, include/, bin/, share/) between
  // iterations, and a workspace is only wiped on the first iteration that lands in it. When the canary commit or
  // Producer CPP master (which pins Producer C and PIC) moves, the new sources would build on top of files from
  // the old ones, so wipe them whenever that pair differs from the one recorded by the last successful build.
  def wipeStaleRelease = params.KVS_SDK_TRACK != 'release' ? '' : '''
    sdk_head=$(git ls-remote https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp.git refs/heads/master | cut -f1) &&
    build_key="$(git rev-parse HEAD) ${sdk_head}" &&
    if [ -n "${sdk_head}" ] && [ "$(cat .sdk-build-key 2>/dev/null)" != "${build_key}" ]; then
      echo "Canary or Producer CPP master changed since the last build, wiping build/ lib/ include/ bin/ share/" &&
      rm -rf build lib include bin share .sdk-build-key
    fi &&
  '''
  def recordBuildKey = params.KVS_SDK_TRACK != 'release' ? '' : '''&&
    if [ -n "${sdk_head}" ]; then echo "${build_key}" > ../.sdk-build-key; fi
  '''
  sh  """
    cd ./canary/producer-cpp/scripts &&
    chmod a+x cert_setup.sh &&
    ./cert_setup.sh ${iotPrefix(params)} &&
    cd .. &&
    ${cleanBuildDir}
    ${wipeStaleRelease}
    mkdir -p build &&
    cd build && 
    cmake .. -DKVS_SDK_TRACK=${params.KVS_SDK_TRACK} &&
    make -j4 ${recordBuildKey}
  """
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

def runClient(isProducer, params) {

    // TODO: get the branch and version from orchestrator
    if (params.FIRST_ITERATION) {

        // TODO: Move to deletDir(). deleteDir() causes an exception right now 
        sh """
            cd $WORKSPACE
            rm -rf *
        """
    }
    
    //def consumerStartUpDelay = 45
    echo "NODE_NAME = ${env.NODE_NAME}"
    checkout([
        scm: [
            $class: 'GitSCM', 
            branches: [[name: params.GIT_HASH]],
            userRemoteConfigs: [[url: params.GIT_URL]]
        ]
    ])

    RUNNING_NODES++
    echo "Number of running nodes: ${RUNNING_NODES}"
    if(isProducer) {
        buildProducer(params)
    }

    RUNNING_NODES--
    echo "Number of running nodes after build: ${RUNNING_NODES}"
    waitUntil {
        RUNNING_NODES == 0
    }
    
    echo "Done waiting in NODE_NAME = ${env.NODE_NAME}"

    def scripts_dir = "$WORKSPACE/canary/producer-cpp/scripts"
    def creds_file = "${scripts_dir}/iot-credential-provider.txt"
    def endpoint = "${scripts_dir}/iot-credential-provider.txt"
    def prefix = iotPrefix(params)
    def core_cert_file = "${scripts_dir}/sink_${prefix}_certificate.pem"
    def private_key_file = "${scripts_dir}/sink_${prefix}_private.key"
    def role_alias = "sink_${prefix}_role_alias"
    def ca_cert_file = "${scripts_dir}/cacert.pem"
    def thing_name = "sink_${prefix}_thing"

    def envs = [
        'M2_HOME': "/opt/apache-maven-3.6.3",
        'AWS_KVS_LOG_LEVEL': params.AWS_KVS_LOG_LEVEL,
        'CANARY_STREAM_NAME' : "${env.JOB_NAME}" + "-" + params.CANARY_STREAM_NAME,
        'CANARY_LABEL': params.RUNNER_LABEL,
        'CANARY_STREAM_TYPE': params.CANARY_TYPE,
        'FRAGMENT_SIZE_IN_BYTES' : params.FRAGMENT_SIZE_IN_BYTES,
        'CANARY_DURATION_IN_SECONDS': params.CANARY_DURATION_IN_SECONDS,
        'AWS_DEFAULT_REGION': params.AWS_DEFAULT_REGION,
        'CANARY_RUN_SCENARIO': params.CANARY_RUN_SCENARIO,
        'CANARY_USE_IOT': params.USE_IOT,
        'AWS_IOT_CORE_CREDENTIAL_ENDPOINT': "${endpoint}",
        'AWS_IOT_CORE_CERT': "${core_cert_file}",
        'AWS_IOT_CORE_PRIVATE_KEY': "${private_key_file}",
        'AWS_IOT_CORE_ROLE_ALIAS': "${role_alias}",
        'AWS_IOT_CORE_THING_NAME': "${thing_name}",
        'AWS_IOT_CA_CERT': "${ca_cert_file}",
        'GST_PLUGIN_PATH': "$WORKSPACE/canary/producer-cpp/build:/usr/include/gstreamer-1.0",
        'LD_LIBRARY_PATH': "$WORKSPACE/canary/producer-cpp/open-source/local/lib"
    ].collect({k,v -> "${k}=${v}" })
  
    withRunnerWrapper(envs) {
        sh """
            echo "Running producer"
            ls ./canary/producer-cpp
            cd ./canary/producer-cpp/build &&
            ./producer_cpp_canary
        """
    }
}

pipeline {
    agent {
        label params.PRODUCER_NODE_LABEL
    }

    parameters {
        string(name: 'CANARY_STREAM_NAME')
        choice(name: 'AWS_KVS_LOG_LEVEL', choices: ["1", "2", "3", "4", "5"])
        string(name: 'PRODUCER_NODE_LABEL')
        string(name: 'GIT_URL')
        string(name: 'GIT_HASH')
        string(name: 'CANARY_TYPE')
        string(name: 'FRAGMENT_SIZE_IN_BYTES')
        string(name: 'RUNNER_LABEL')
        string(name: 'CANARY_DURATION_IN_SECONDS')
        string(name: 'MIN_RETRY_DELAY_IN_SECONDS')
        string(name: 'AWS_DEFAULT_REGION')
        string(name: 'CANARY_RUN_SCENARIO')
        // release: Producer CPP master with its pinned Producer C / PIC tags. develop: all three from develop.
        choice(name: 'KVS_SDK_TRACK', choices: ["release", "develop"])
        booleanParam(name: 'FIRST_ITERATION', defaultValue: true)
        booleanParam(name: 'USE_IOT')
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
        
        stage('Echo params') {
            steps {
                echo params.toString()
            }
        }
        stage('Set build description') {
            steps {
                script {
                    currentBuild.displayName = "${params.RUNNER_LABEL} [#${BUILD_NUMBER}]"
                    currentBuild.description = "Executed on: ${NODE_NAME}\nSDK track: ${params.KVS_SDK_TRACK}\n"
                }
            }
        }
        stage('Run') {
            failFast true
            parallel {
                stage('Producer') {
                    steps {
                        script {
                            // Each SDK track builds in its own workspace. Jenkins shares the default workspace slots
                            // (<job>@2, @3, ...) between all jobs of this runner on a node, and the build reuses
                            // build/ plus the SDK's install dirs (lib/, include/) between iterations. Without this, a
                            // release iteration could reuse files a develop iteration built, and the reverse. ws()
                            // still adds @2, @3, ... when two jobs of the same track run on one node.
                            ws("workspace/${env.JOB_NAME}-${params.KVS_SDK_TRACK}") {
                                runClient(true, params)
                            }
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
                                string(name: 'CANARY_STREAM_NAME', value: params.CANARY_STREAM_NAME),
                                string(name: 'AWS_KVS_LOG_LEVEL', value: params.AWS_KVS_LOG_LEVEL),
                                booleanParam(name: 'USE_IOT', value: params.USE_IOT),
                                string(name: 'PRODUCER_NODE_LABEL', value: params.PRODUCER_NODE_LABEL),
                                string(name: 'GIT_URL', value: params.GIT_URL),
                                string(name: 'GIT_HASH', value: params.GIT_HASH),
                                string(name: 'CANARY_TYPE', value: params.CANARY_TYPE),
                                string(name: 'FRAGMENT_SIZE_IN_BYTES', value: params.FRAGMENT_SIZE_IN_BYTES),
                                string(name: 'CANARY_DURATION_IN_SECONDS', value: params.CANARY_DURATION_IN_SECONDS),
                                string(name: 'MIN_RETRY_DELAY_IN_SECONDS', value: params.MIN_RETRY_DELAY_IN_SECONDS),
                                string(name: 'AWS_DEFAULT_REGION', value: params.AWS_DEFAULT_REGION),
                                string(name: 'RUNNER_LABEL', value: params.RUNNER_LABEL),
                                string(name: 'CANARY_RUN_SCENARIO', value: params.CANARY_RUN_SCENARIO),
                                string(name: 'KVS_SDK_TRACK', value: params.KVS_SDK_TRACK),
                                booleanParam(name: 'FIRST_ITERATION', value: false)
                            ],
                    wait: false
                )
            }
        }
    }
}
