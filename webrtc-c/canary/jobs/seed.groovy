NAMESPACE="webrtc-canary"
WORKSPACE="webrtc-c/canary"
JOBS_DIR="$WORKSPACE/jobs"
NUM_LOGS=20

pipelineJob("${NAMESPACE}-orchestrator") {
    throttleConcurrentBuilds {
        maxTotal(1)
    }
    logRotator {
        numToKeep(NUM_LOGS)
    }
    definition {
        cps {
            script(readFileFromWorkspace("${JOBS_DIR}/orchestrator.groovy"))
            sandbox()
        }
    }
}

/**
 * Create Runners. Runner 0 and runner 1 are identical, the backup is being
 * used for rolling updates
 */
pipelineJob("${NAMESPACE}-runner-0") {
    logRotator {
        numToKeep(NUM_LOGS)
    }
    definition {
        cps {
          script(readFileFromWorkspace("${JOBS_DIR}/runner.groovy"))
          sandbox()
        }
    }
}

pipelineJob("${NAMESPACE}-runner-1") {
    logRotator {
        numToKeep(NUM_LOGS)
    }
    definition {
        cps {
          script(readFileFromWorkspace("${JOBS_DIR}/runner.groovy"))
          sandbox()
        }
    }
}

queue("${NAMESPACE}-orchestrator")
