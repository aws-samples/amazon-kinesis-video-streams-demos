import java.lang.reflect.*;
import jenkins.model.*;
import org.jenkinsci.plugins.scriptsecurity.scripts.*;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.*;

NAMESPACE="webrtc-canary"
WORKSPACE="canary/webrtc-c"
JOBS_DIR="$WORKSPACE/jobs"
NUM_LOGS=9

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
