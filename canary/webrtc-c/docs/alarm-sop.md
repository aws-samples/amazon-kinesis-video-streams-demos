# KVS Storage Canary Alarm SOP

## 1. Alarm Types

Two categories of alarms:

| Type | Meaning | Example |
|---|---|---|
| **Liveness** | A canary stage heartbeat stopped emitting — we are blind | `MasterStarted` not received for 2 hours |
| **Metric** | A threshold was breached — canary detected a problem | `PeerConnectionAvailability` < 95% for 2 consecutive periods |

---

## 2. Liveness Alarm Response

A stage heartbeat stopped emitting. The canary is stuck or dead.

### Stage dependency chain

```
GitCheckoutComplete → MasterStarted → MasterFinished
                                           ↓
                      ViewerPrepareComplete → ViewerSessionComplete → CleanupComplete
```

### Which stage stopped?

| Missing Stage | What broke | First thing to check |
|---|---|---|
| `GitCheckoutComplete` | Repo inaccessible, network issue, wrong branch | Jenkins job status; git fetch logs |
| `MasterStarted` | Build failed, binary missing, cert expired | Build logs; check if binary exists on host |
| `MasterFinished` | Binary crashed or hung mid-test | CloudWatch Logs for the master; check if process is still running on host |
| `ViewerPrepareComplete` | npm install hung, viewer node offline | Viewer node reachability; prepare script logs |
| `ViewerSessionComplete` | Chrome crashed, viewer stuck | Viewer process logs; check Chrome headless |
| `CleanupComplete` | Previous stage crashed before reaching cleanup | Check which earlier stage failed; Jenkins build result |

### Step 1: Check Jenkins job status

**Jenkins URL:** https://aws-acuity-sdk-jenkins-60001.pdx1.corp.amazon.com:1443/ (requires VPN)

**Job locations:**
- Prod storage canaries: `webrtc-storage-canary-runner`
- Gamma storage canaries: `webrtc-gamma-runner`

**How to check:**
1. Navigate to the job page
2. Open **Blue Ocean** view for the latest build
3. Check which step it's stuck on

**Jenkins build statuses:**

| Status | Meaning | Action |
|---|---|---|
| SUCCESS | Completed normally | Not the problem — check if cron triggered next run |
| FAILURE | A step threw an error | Check console output for the failing step |
| UNSTABLE | Test ran but reported errors (non-fatal) | Check if it's the metric that fired the alarm |
| ABORTED | Job was killed (timeout or manual) | Check if timeout fired — likely a hung process |
| IN_PROGRESS | Currently running | Check how long it's been running; if it's in "building binary" step, that's normal (can take several minutes) |
| NOT_BUILT | Job never ran | Check if cron trigger is configured correctly (`crontab -l` on the node) |

**How to check last successful build:**
- On the job page, look at "Last Successful Build" link in the left sidebar
- Compare its timestamp to current time — if it's hours old, canary has been stuck since then

### Step 2: Check the node

**How to access canary nodes:**

1. Download PEM key from AWS Secrets Manager: https://tiny.amazon.com/10i1h5giw/IsenLink
2. SSH to jump host: `ssh -i <path-to-your-local-pem-key> ubuntu@54.185.49.98`
3. From jump host, SSH to target node: `ssh -i ~/.ssh/ec2-key.pem ubuntu@<private-ip>`

**How to find the private IP of a node:**
- **Fast path:** See the **Appendix: Node IP Reference** table at the bottom of this doc.
- **Fallback (if table is outdated):** Go to Jenkins → Manage → Nodes (https://aws-acuity-sdk-jenkins-60001.pdx1.corp.amazon.com:1443/manage/computer/) → find the node by label → click Configure → look at Launch Method → the private IP is in the SSH command. Example: `ssh -o StrictHostKeyChecking=no -i /local/jenkins/.ssh/ec2-key.pem ubuntu@ec2-54-185-49-98.us-west-2.compute.amazonaws.com ssh -o StrictHostKeyChecking=no -i /home/ubuntu/.ssh/ec2-key.pem ubuntu@172.31.67.30 java -jar /home/ubuntu/agent.jar` — the private IP here is `172.31.67.30`

**What to check on the node:**

| Check | Command | What to look for |
|---|---|---|
| Disk space | `df -h` | If `/` or `/home` is >90% full, cleanup is failing |
| What's using disk | `du -sh /home/ubuntu/* \| sort -rh \| head -10` | Large build directories, core dumps, old workspaces |
| Cron status | `crontab -l` | Verify cleanup cron entries exist and look correct |
| Cron actually running | `grep CRON /var/log/syslog \| tail -20` | Check if cron daemon is executing the cleanup scripts |
| Running processes | `ps aux \| grep -E 'kvsWebrtc\|chrome\|java'` | Zombie/orphan processes from crashed sessions |
| Memory | `free -h` | OOM conditions — Chrome headless can leak memory |
| Network connectivity | `curl -s https://kinesisvideo.us-west-2.amazonaws.com` | Can the node reach KVS endpoints? |
| Time sync | `timedatectl` | Clock drift causes STS/cert failures |
| Workspace lock files | `find /home/ubuntu -name '.in_use' -mmin +120` | Stale lock files from crashed builds (>2 hours old) |

**Common node issues and fixes:**

| Issue | Symptom | Fix |
|---|---|---|
| Disk full | `df -h` shows >95% | Check what's using space (`du -sh` on the node). Common culprits: stale Jenkins workspaces (`~/Jenkins/workspace/webrtc-*`), old IoT certs (`~/webrtc-c-storage-master/certs/`), GetClip MP4s (`clip-*.mp4`), core dumps. Reference `canary/webrtc-c/scripts/cron/cleanup-master.sh` (or `cleanup-viewer.sh`, `cleanup-consumer.sh`) to see what should be auto-cleaned. If cron isn't cleaning: check `crontab -l` exists, check if the cleanup script is deployed on the node, check cleanup logs: master/consumer nodes at `~/webrtc-c-storage-master/logs/cleanup.log`, viewer nodes at `~/JS-viewer-build/cleanup.log` |
| Orphan processes | `kvsWebrtcStorageSample` or `chrome` still running from a previous crashed session | `kill` the orphan processes; they may be holding ports or file locks |
| Memory exhaustion | `free -h` shows nearly 0 available | Kill leaked Chrome processes; consider rebooting node |
| Stale .in_use lock | `.in_use` file older than 2 hours | Remove it: `rm /path/to/.in_use` — it's preventing cron cleanup |
| Network issue | `curl` to KVS endpoint times out | Check NAT gateway, security groups, or VPC connectivity |
| NTP drift | `timedatectl` shows clock is off | `sudo systemctl restart systemd-timesyncd` or `sudo ntpdate pool.ntp.org` |
| Cron wiped | `crontab -l` shows nothing | Re-deploy cleanup scripts — they're synced from the repo during build, but if the build hasn't run they won't be there. Manually copy from repo: `canary/webrtc-c/scripts/cron/` |

### Step 3: Re-trigger the canary

Storage canaries are scheduled via Jenkins cron (not the orchestrator). In most cases, **you should not need to manually trigger a run.**

**How auto-scheduling works:**
- Cron triggers the runner job on a fixed schedule
- If a job with the same `SCENARIO_LABEL` is already running, the new trigger will skip (no duplicate builds)
- Once you fix the stuck job (kill it, fix the node, etc.), the next cron cycle will automatically schedule a new run

**So the fix is:** resolve the stuck/failed job → cron handles the rest.

**If a specific scenario is not being rescheduled:**
- Most likely cause: the previous build for that scenario is still stuck. Cron skips if a job with the same `SCENARIO_LABEL` is already running.
- Open the Blue Ocean UI of the runner itself → look for a stuck/hung build with that scenario label → check the Jenkins console log to identify the root cause (see Step 1 and Step 2 above) → fix or kill the stuck build → cron will reschedule on next cycle.

**If cron itself stopped working (liveness alarm firing but no stuck job visible):**

| Symptom | How to check | Fix |
|---|---|---|
| Liveness alarm firing, no stuck/running build in Blue Ocean | Check the job's "Build Triggers" config in Jenkins — is the cron expression still there? | Re-add the cron schedule in job configuration |
| Jenkins itself is down | Jenkins URL not reachable | Check if the EC2 host running Jenkins is healthy |

**How to tell if it's just a slow build vs stuck:**
- Open Blue Ocean → if the current step says "building binary" or "cmake" → normal, can take 5-10 minutes on first build
- If it's on "running kvsWebrtcStorageSample" for longer than the test duration (156s for short, 45min for SubReconnect, 65min for SingleReconnect) + 15 min buffer → it's stuck
- If it's on "git fetch" for more than 5 minutes → likely a network issue
- If it's on "flock" → another job is currently building the binary on the same node (normal if build takes a few minutes; only a problem if stuck for 30+ minutes — may indicate the lock-holding job crashed without releasing)

---

## 3. Metric Alarm Response

A metric threshold was breached. The canary is running but detected a problem.

### Step 1: Scope

- Is it firing in **prod only**? → likely prod service issue or prod-specific infra
- Is it firing in **gamma only**? → likely gamma deployment or gamma endpoint issue
- Is it firing in **both**? → likely SDK/canary code change (affects both), or widespread service issue

### Step 2: Recent changes check

Before diving into logs, check if something changed recently.

#### Where to check for code changes

| What to check | Where to look | What the canary is currently running |
|---|---|---|
| WebRTC C SDK | https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-c | Branch `develop` — pinned in `canary/webrtc-c/CMakeLists.txt` via `GIT_TAG` in `FetchContent_Declare(webrtc ...)` |
| WebRTC JS SDK | https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-js | Prod: uses public test page from `master` branch. Gamma: configurable via `JS_BRANCH` param (default `master`) |
| Canary code | https://github.com/aws-samples/amazon-kinesis-video-streams-demos | Branch specified by `GIT_HASH` in cron config — currently `reschedule-logic`. See `canary/webrtc-c/jobs/prod_storage_cron.txt` |
| Media service deployment | https://pipelines.amazon.dev/pipelines-wip/AWSAcuityMediaService — check last successful deployment date under each stage (ignore OneBox stages) | — |

#### How to check which versions are running

- **Canary code branch (`GIT_HASH`):** Check the cron trigger config in Jenkins or look at `prod_storage_cron.txt` — all entries have `GIT_HASH=reschedule-logic`
- **WebRTC C SDK branch:** Check `canary/webrtc-c/CMakeLists.txt` line `GIT_TAG` under `FetchContent_Declare(webrtc ...)` — currently `develop`
- **JS SDK branch (prod):** Always clones `master` branch of the JS SDK repo (set via `JS_PAGE_URL` defaulting to `master` in `prepare-storage-viewer.sh`)
- **JS SDK branch (gamma):** Set via `JS_BRANCH` parameter in the cron schedule or job parameters — currently `master`, but can be overridden per run

#### What to look for in recent commits

- Check the last few commits on the relevant branch since the last successful canary run
- For WebRTC C SDK (`develop`): any changes to signaling, ICE, peer connection, or media pipeline code
- For JS SDK (`master`): any changes to viewer connection logic, WebRTC handling, or metric emission
- For canary code (`reschedule-logic`): any changes to `canary/webrtc-c/` scripts, Groovy jobs, or configuration

### Step 3: Change correlates?

- **Yes** → investigate that specific change. Consider rollback/revert.
- **No** → proceed to investigation steps (Section 4)

---

## 4. Investigation Steps

### Dashboards (open these first)

| Dashboard | Link |
|---|---|
| **Prod High-Level** | https://w.amazon.com/bin/view/Acuity/SDK/Dashboard/KVS_SDK_HighLevel_Prod_Dashboard/ |
| **Prod Diagnose** | https://w.amazon.com/bin/view/Acuity/SDK/Dashboard/KVS_SDK_MediaService_Diagnose_Prod_Dashboard/ |
| **Gamma High-Level** | https://w.amazon.com/bin/view/Acuity/SDK/Dashboard/KVS_SDK_HighLevel_Gamma_Dashboard/ |
| **Gamma Diagnose** | https://w.amazon.com/bin/view/Acuity/SDK/Dashboard/KVS_SDK_MediaService_Diagnose_Gamma_Dashboard/ |

Use the high-level dashboard to see master and viewer metrics side-by-side — this is how you determine if master is also degraded when a viewer alarm fires.

### Step 1: Check canary logs (rule out SDK/canary issue)

There are three log groups in CloudWatch. Start with the one relevant to the alarm:

| Log Group | What it contains | When to check |
|---|---|---|
| `WebrtcSDK` | C Master SDK logs (signaling, ICE, media streaming) | Master-side metric alarms (PeerConnectionAvailability, ConsumerStorageAvailability, JoinSessionTime, etc.) |
| `JSSDK` | JS Viewer logs (connection, media reception, SSIM) | Viewer-side metric alarms (ViewerConnectionSuccessRate, ViewerStorageAvailability, ViewerSSIM, etc.) |
| `JenkinsBuildLogs` | Full pipeline stage logs — build, setup, cert generation, all stages including pre/post test | When you need to see what happened before the test program started (build failures, cert issues, environment setup) or after it finished (cleanup, video verification) |

**CloudWatch console link:** https://tiny.amazon.com/12sqcnb50/IsenLink

**Log stream naming:** `{RunnerLabel}-StorageMaster-{timestamp}` (for WebrtcSDK), similar pattern for JSSDK.

**What to look for:**
- ERROR / FATAL level messages → likely SDK or configuration bug
- "Timeout" or "timed out" → SDK or service not responding
- Repeated retries in quick succession → connection instability
- Stack traces or segfaults → SDK crash
- Normal log flow with no errors, but metric is bad → **not an SDK issue**, proceed to Step 2
- No logs at all for the expected time window → canary didn't run, check liveness alarms / Jenkins
- Logs start but stop abruptly mid-session → process crashed or was killed (check `JenkinsBuildLogs` for timeout/abort)

### Step 2: Identify root component (check upstream first)

Dependency chain: **Master → Viewer → Consumer (GetClip)**

If master is broken, viewer and consumer metrics will often (but not always) fail as a downstream consequence — a session without a master will cause the viewer to be kicked out.

**Which metric fired determines your starting point:**

| What fired | Check | Interpretation |
|---|---|---|
| **Master metric** (PeerConnectionAvailability Master, JoinStorageSessionAvailability, JoinSessionTime, CMasterRetryCount, etc.) | Investigate master directly | Master problem confirmed by the metric itself |
| **Viewer metric** (ViewerConnectionSuccessRate, ViewerStorageAvailability, PeerConnectionAvailability Viewer, ViewerSSIM, etc.) | Check if master metrics are also degraded at the same time | If master is also down → fix master first, then re-check if viewer recovers. If master is fine → it's a genuine viewer-side issue |
| **Consumer metric** (ConsumerStorageAvailability) | Check if master metrics are also degraded at the same time | If master is also down → master is likely the root cause (no video to archive). If master is fine → consumer-specific issue (GetClip API, video verification logic) |

**Important:** Master and viewer issues are correlated but not 100% — viewer can fail independently even when master is healthy (JS bug, Chrome crash, viewer node issue). If you fix master but viewer doesn't recover, investigate viewer separately.

### Step 3: Dig into the responsible component

#### Master issue

| Check | How | What it tells you |
|---|---|---|
| CloudWatch Logs | `WebrtcSDK` log group → find the relevant session's log stream | SDK-level errors, signaling failures, ICE issues |
| Jenkins build log | `JenkinsBuildLogs` log group, or Blue Ocean console output | Build failures, cert issues, environment problems |
| SDK version | Check `canary/webrtc-c/CMakeLists.txt` → `GIT_TAG` under `FetchContent_Declare(webrtc ...)` | Is a new SDK commit the culprit? |
| IoT certs | SSH to master node → check `~/webrtc-c-storage-master/certs/{JOB_NAME}-{RUNNER_LABEL}/` (e.g., `.../webrtc-storage-canary-runner-StoragePeriodic/`). Certs are regenerated every run via `cert_setup.sh`. Check: do the cert files exist? Is the timestamp recent (from the last run)? If they're old/missing, the cert creation step likely failed — check `JenkinsBuildLogs` for `aws iot create-keys-and-certificate` errors (permissions, IoT policy/thing/role-alias deleted, API throttling) |
| Stale binary | SSH to node → `ls -la ~/webrtc-c-storage-master/build/kvsWebrtcStorageSample` → check timestamp | If binary is old, build may be failing silently |
| Node health | SSH to node → `df -h`, `free -h`, `ps aux` | Disk/memory/process issues (see Section 2, Step 2) |

#### Viewer issue

| Check | How | What it tells you |
|---|---|---|
| CloudWatch Logs | `JSSDK` log group → find the relevant session's log stream | JS viewer connection errors, media issues |
| Jenkins build log | `JenkinsBuildLogs` or Blue Ocean console output for viewer stage | npm install failures, Chrome launch issues |
| JS SDK version | Check `prepare-storage-viewer.sh` behavior — prod always uses `master` branch of JS SDK | Did a new commit land on `master`? |
| Chrome status | SSH to viewer node → `ps aux \| grep chrome` | Zombie Chrome processes, crashes |
| Viewer node health | SSH to viewer node → check disk, memory, processes | Same checks as master node |

#### Service issue

If canary logs show normal SDK behavior but metrics are still bad (e.g., JoinStorageSession returns errors, peer connection succeeds but media quality degrades), the problem is likely on the service side.

**Investigating service logs:**

Before escalating, you can check the media service processor logs yourself:

1. **Find the AWS account for the service region:** Look at the media service pipeline (https://pipelines.amazon.dev/pipelines-wip/AWSAcuityMediaService), find the stage for the affected region (ignore OneBox). Click into any target deploying to that stage — the account ID will show up on the top left of the AWS console.
2. **Access the processor logs:** The log group is `/var/log/messages` in that account. Filter by the **client ID** from the canary session.
3. **Get the client ID:** Only the JS viewer has a client ID. Find it in the `JSSDK` CloudWatch log group — search for `FORM_VALUES`. The log line looks like: `[FORM_VALUES] Running the sample with the following options: {"region":"...","channelName":"...","clientId":"mqsobce0-qkuipjgf5gs",...}`. Extract the `clientId` value from that JSON. The C master does not use a client ID for service log correlation.
4. **Context about the media service:** See https://code.amazon.com/packages/AWSAcuityMediaServiceDevAgent/trees/mainline for service architecture context.

**What to look for in service logs:**
- Errors on the service side handling the canary's session
- Session termination reasons initiated by the service (not the client)
- Throttling or resource exhaustion on the media processor

**Evidence to collect before escalating:**
- Timestamps of when the issue started
- Which scenarios are affected (all? just one?)
- Specific error codes from the canary logs (e.g., HTTP 5xx from JoinStorageSession API)
- Whether gamma is also affected (same service or different endpoint?)
- Client ID and any relevant service-side log excerpts you found
- Link to the recent media service deployment if one correlates: https://pipelines.amazon.dev/pipelines-wip/AWSAcuityMediaService

**Escalation:** Cut a ticket to the media service team with the above evidence. *(See Section 5 for escalation details.)*

---

## 5. Escalation Matrix

| Root cause | Action | Where to file | Who to contact |
|---|---|---|---|
| Canary infra issue (Jenkins, node, cron) | Fix ourselves — restart job, reboot node, fix config | No ticket needed unless recurring | SDK team |
| SDK bug (C SDK or JS SDK) | File internal ticket, assign to SDK team backlog | Taskei — SDK team room | SDK team |
| Canary code bug | Fix in `amazon-kinesis-video-streams-demos`, deploy via updating `GIT_HASH` | Taskei — SDK team room | SDK team |
| Service-side issue | Cut ticket with evidence (timestamps, client ID, error codes, service logs) | SIM — CTI: `AWS / Kinesis - Video Ingestion / Media Server` | Ingestion team oncall |
| Host/node failure (unrecoverable) | Replace node, update Jenkins node config | Taskei — SDK team room | SDK team |

**How to find ingestion team oncall:** Look up the oncall rotation for the ingestion team.

**When to escalate immediately vs investigate further:**
- **Escalate immediately** if: all scenarios are failing, both prod and gamma are affected, and service logs confirm server-side errors
- **Investigate further first** if: only one scenario is affected, or the issue is intermittent — likely a canary/SDK issue, not service-side

---

## 6. Resolution & Follow-up

1. **Document** what happened (brief note in alarm ticket or Slack thread)
2. **Verify** the alarm resolves after fix
3. **Adjust threshold** if it was a false positive (update metrics-baseline doc)
4. **File follow-up ticket** if the root cause needs a longer-term fix
5. **Update this SOP** if a new failure mode was discovered

---

## Appendix: Node IP Reference

**Jump host (public):** `54.185.49.98` (instance: `testStorageMaster(jump_instance)`)

**Prod storage nodes (NAT-restricted, accessible from jump host):**

| Jenkins Node Name | Role | Private IP |
|---|---|---|
| prodStorageWebrtcMaster | Master (mapped to `prodStorageMaster_NAT`) | `172.31.69.95` |
| prodStorageWebrtcViewer | Viewer (mapped to `prodStorageViewer_NAT`) | `172.31.64.201` |
| prodStorageWebrtcConsumer | Consumer (mapped to `prodStorageConsumer_NAT`) | `172.31.74.53` |

**Gamma storage nodes (NAT-restricted, accessible from jump host):**

| Jenkins Node Name | Role | Private IP |
|---|---|---|
| gammaStrorageWebrtcMaster | Master (mapped to `gammaStorageMaster_NAT`) | `172.31.67.30` |
| gammaStorageWebrtcViewer | Viewer (mapped to `gammaStorageViewer_NAT`) | `172.31.79.62` |
| gammaStorageWebrtcConsumer | Consumer (mapped to `gammaStorageConsumer_NAT`) | `172.31.76.69` |

**How to fill in / update IPs:** Go to Jenkins → Manage → Nodes → click the node → Configure → Launch Method → extract private IP from the SSH command (see Section 2, Step 2 for the full example).

**SSH cheat sheet:**
```bash
# From your laptop → jump host
ssh -i <path-to-your-local-pem-key> ubuntu@54.185.49.98

# From jump host → any storage node
ssh -i ~/.ssh/ec2-key.pem ubuntu@<private-ip-from-table-above>
```
