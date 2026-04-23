import java.lang.reflect.*;
import jenkins.model.*;
import org.jenkinsci.plugins.scriptsecurity.scripts.*;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.*;

/**
 * Gamma Seed Job
 * 
 * This seed creates dedicated gamma testing jobs that are completely independent
 * from the production canary jobs. Running this seed will create:
 *   - webrtc-gamma-orchestrator: The main gamma test orchestrator
 *   - webrtc-gamma-runner: A dedicated runner for gamma tests
 * 
 * Usage:
 *   1. Run this seed job
 *   2. Go to webrtc-gamma-orchestrator
 *   3. Click "Build with Parameters"
 *   4. Enter the gamma endpoint URL
 *   5. Click "Build"
 */

NAMESPACE = "webrtc-gamma"
WORKSPACE = "canary/webrtc-c"
JOBS_DIR = "$WORKSPACE/jobs"
NUM_LOGS = 30

void approveSignatures(ArrayList<String> signatures) {
    scriptApproval = ScriptApproval.get()
    alreadyApproved = new HashSet<>(Arrays.asList(scriptApproval.getApprovedSignatures()))
    signatures.each {
        if (!alreadyApproved.contains(it)) {
            scriptApproval.approveSignature(it)
        }
    }
}

approveSignatures([
    "method hudson.model.ItemGroup getAllItems java.lang.Class",
    "method hudson.model.Job getBuilds",
    "method hudson.model.Job getLastBuild",
    "method hudson.model.Job isBuilding",
    "method hudson.model.Run getTimeInMillis",
    "method hudson.model.Run isBuilding",
    "method jenkins.model.Jenkins getItemByFullName java.lang.String",
    "method jenkins.model.ParameterizedJobMixIn\$ParameterizedJob isDisabled",
    "method jenkins.model.ParameterizedJobMixIn\$ParameterizedJob setDisabled boolean",
    "method org.jenkinsci.plugins.workflow.job.WorkflowRun doKill",
    "staticMethod jenkins.model.Jenkins getInstance",
    "staticField java.lang.Long MAX_VALUE"
])

// Create the gamma orchestrator job
pipelineJob("${NAMESPACE}-orchestrator") {
    description("""
        <h2>Gamma Test Orchestrator</h2>
        <p>Runs WebRTC storage viewer tests against a custom endpoint (e.g., gamma).</p>
        <h3>Usage:</h3>
        <ol>
            <li>Click "Build with Parameters"</li>
            <li>Enter the gamma endpoint URL</li>
            <li>Select which tests to run</li>
            <li>Click "Build"</li>
        </ol>
    """)
    parameters {
        stringParam('ENDPOINT', '', 'Custom endpoint URL (e.g., gamma endpoint). REQUIRED.')
        stringParam('AWS_DEFAULT_REGION', 'us-east-1', 'AWS region for the tests')
        stringParam('VIEWER_WAIT_MINUTES', '2', 'Minutes to wait for master to build before starting viewers')
        stringParam('VIEWER_SESSION_DURATION_SECONDS', '600', 'Duration in seconds for each viewer session')
        stringParam('GIT_HASH', 'clean_viewer_test', 'Git branch/tag/commit to use')
        booleanParam('RUN_STORAGE_WITH_VIEWER', true, 'Run StorageWithViewer test (1 viewer)')
        booleanParam('RUN_STORAGE_TWO_VIEWERS', true, 'Run StorageTwoViewers test (2 viewers)')
        booleanParam('RUN_STORAGE_THREE_VIEWERS', true, 'Run StorageThreeViewers test (3 viewers)')
        booleanParam('RUN_STORAGE_PERIODIC', true, 'Run StoragePeriodic test (master + consumer, 156s)')
        booleanParam('RUN_STORAGE_SUB_RECONNECT', true, 'Run StorageSubReconnect test (master + consumer, 45 min)')
        booleanParam('RUN_STORAGE_SINGLE_RECONNECT', true, 'Run StorageSingleReconnect test (master + consumer, 65 min)')
        booleanParam('KEEP_RECORDING', false, 'Keep viewer video recordings after verification')
        booleanParam('RESCHEDULE', true, 'Enable continuous testing (reschedule after each run completes)')
    }
    throttleConcurrentBuilds {
        maxTotal(1)
    }
    logRotator {
        numToKeep(NUM_LOGS)
    }
    definition {
        cps {
            script(readFileFromWorkspace("${JOBS_DIR}/gamma_orchestrator.groovy"))
            sandbox()
        }
    }
}

// Create a dedicated gamma runner job
pipelineJob("${NAMESPACE}-runner") {
    description("""
        <h2>Gamma Test Runner</h2>
        <p>Dedicated runner for gamma tests. Do not run directly - use the orchestrator.</p>
    """)
    logRotator {
        numToKeep(NUM_LOGS)
    }
    definition {
        cps {
            script(readFileFromWorkspace("${JOBS_DIR}/gamma_runner.groovy"))
            sandbox()
        }
    }
}

// Gamma seed completed! Created jobs:
//   - webrtc-gamma-orchestrator
//   - webrtc-gamma-runner
// To run gamma tests, go to webrtc-gamma-orchestrator and click 'Build with Parameters'
