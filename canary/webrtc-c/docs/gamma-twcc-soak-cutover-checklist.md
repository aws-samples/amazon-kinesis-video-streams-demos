# Gamma cutover checklist — `integration-bitrate-latency` → `twcc-canary`

**Goal:** move gamma off `integration-bitrate-latency` onto `twcc-canary`, and start running
both the TWCC network-shaped canaries and the continuous soak on gamma alongside the existing
periodic jobs.

**Scope of the change.** 53 files, +7,910/−461 between the two branches. It is not only new cron
entries: it adds a GStreamer live-encoder path to the master (`GstMedia.cpp`, `CMakeLists.txt`
`gst=ON`), a TWCC bitrate controller, netns/tc network shaping with a root wrapper, continuous
(soak) run modes across all three components, a new `SoakStreamVerifier` in the consumer, an
SSIM/OCR rewrite of `verify.py`, and two new npm dependencies.

Legend: **[B]** blocking — cutover will misbehave without it. **[R]** risk-reducing.
**[F]** follow-up, can land after cutover.

---

## 0. Facts to work from (verified, not assumed)

These determine most of the checklist, so confirm they still hold before planning around them.

| Fact | Where |
|---|---|
| Channel name **and** stream name are both `"${JOB_NAME}-${RUNNER_LABEL}"` | `gamma_runner.groovy:374, :430, :483, :659` |
| An unrecognized `SCENARIO_LABEL` makes the consumer **throw and die** | `WebrtcStorageCanaryConsumer.java:820` `default: throw new Exception("Improper canary label …")` |
| Gamma separation for master/viewer metrics is `METRIC_SUFFIX`, default `-gamma` | `gamma_runner.groovy:751` |
| The Java consumer **never reads `METRIC_SUFFIX`** — its only separation is `CANARY_LABEL` (= `SCENARIO_LABEL`) and the stream name | zero hits in `canary/consumer-java/**/*.java` |
| The soak already runs on `gamma_runner.groovy`, not `storage_runner.groovy` | `Build was aborted, skipping reschedule` exists only at `gamma_runner.groovy:1306`; the 11h log shows `Gamma Runner Summary` |
| That 11h soak ran against **prod us-east-1 with an empty `ENDPOINT`**, while still emitting `METRIC_SUFFIX=-gamma` | `11-hour-viewer.log:71-73, :88` |
| TWCC cron currently targets `AWS_DEFAULT_REGION=us-east-1`, gamma camera cron targets `us-west-2` + a gamma control-plane URI | `twcc_cron.txt`, `gamma_camera_cron.txt` |

> **Resolved open question:** the `METRIC_SUFFIX=-gamma` seen on `RpiSoak` runs comes from
> `gamma_runner.groovy:751`'s `defaultValue`, not from anything gamma-specific. Today it
> mislabels prod-region runs as gamma. Fix the labelling *before* adding more jobs, or the
> gamma-vs-prod split becomes unreadable exactly when you start needing it.

---

## 1. Code gaps in `twcc-canary` that block the cutover

- [ ] **[R] `rescheduleParams` is incomplete — dead code today, blocking for `soak-watchdog`.**
      The list in `gamma_runner.groovy` ends at `JS_BRANCH` (`:1349`) and closes at `:1350`. Not
      forwarded: `STS_DURATION_SECONDS`, `STS_FETCH_NODE_LABEL`, `KEEP_RECORDING`,
      `CANARY_MEDIA_SOURCE`, `CANARY_GST_FILE`, `CANARY_TWCC_SHAPING`, `VIEWER_TWCC_SHAPING`,
      `TWCC_MIN_VIDEO_BITRATE_KBPS`, `TWCC_STAGE_SECONDS`, `TWCC_METRICS_PERIOD_SECONDS`,
      `TWCC_THROTTLE_LOSS`, `TWCC_PROFILE`, `USE_IOT_CREDENTIALS`, all four `IOT_CORE_*`,
      `CONSUMER_AUTO_REFRESH_CREDS`, `CONSUMER_CONTINUOUS`, `SOAK_MODE` — 18 in all.

      **Not a cutover blocker.** `RESCHEDULE` is `defaultValue: false` (`:765`), the gate is
      `else if (params.RESCHEDULE)` (`:1309`), and no cron entry in `jobs/cron/*.txt` sets it.
      The block never executes today.

      **It is a blocker for soak self-recovery.** `soak-self-recovery-design.md:187` rejects
      cron/reschedule in favour of a `soak-watchdog` job whose core action is
      `build job: 'webrtc-test-runner', parameters: [...soak 参数...], wait: false` (`:113`) —
      i.e. the watchdog must maintain its own complete soak parameter list, and
      `rescheduleParams` is the only ready-made "everything needed to re-trigger this job" list
      in the repo, so it is the obvious thing to copy. Copy it as-is and the restarted build is
      **not a soak**: losing `SOAK_MODE` makes the master bounded, the consumer non-continuous,
      the viewer non-recycling and restores the default Jenkins timeouts; losing
      `USE_IOT_CREDENTIALS` kills a Pi master after 1h when its credentials stop refreshing.
      Meanwhile `SoakRestarted=1` has already been emitted, so the metrics read as a successful
      self-heal. Harder to debug than the original failure.

      Fix once, benefits both: complete the 18 entries, then have the watchdog reuse the list.
- [ ] **[R]** Confirm on the controller that the *stored* `RESCHEDULE` default is still false.
      The Groovy `defaultValue` is only the declared value; Jenkins persists parameter values in
      the job's `config.xml`, so a job previously built or hand-edited with `RESCHEDULE=true`
      can have a different effective default. Not visible from the repo.
- [x] **[B] Gamma label strategy — decided and implemented for the soak.** `GammaRpiSoak` now
      exists as `CanaryConstants.GAMMA_SOAK_LABEL` with a `case` alongside `SOAK_LABEL` in the
      consumer's switch (both take the identical path; the run mode is driven by
      `CANARY_CONTINUOUS`, not by the label). Chose the twin over reusing `RpiSoak` because the
      consumer never reads `METRIC_SUFFIX`, so the label is its only gamma/prod separation — and
      the prod soak's alarms are tuned to tolerate exactly 10 `MasterStreamingAvailability=0`
      events per day from *one* soak's by-design hourly reconnect, a threshold a second soak
      sharing the label would silently double. `gamma_soak_cron.txt` sets it.
      `METRIC_SUFFIX=-gamma-soak` handles the same problem one layer out, keeping the gamma soak's
      viewer metrics out of the gamma *periodic* aggregates, which also carry `-gamma`.
      **For the TWCC scenarios the decision still stands open** — `twcc_cron.txt` sets the prod
      `SCENARIO_LABEL=StorageWithViewer`; use the already-defined, already-allowlisted
      `GammaStorageWithViewer` in the gamma copy.
- [ ] **[B] `gamma_camera_cron.txt` will kill the consumer as written.** Same trap, already
      committed: line 18 sets `IS_STORAGE=true` + `IS_STORAGE_SINGLE_NODE=true` +
      `CONSUMER_NODE_LABEL` with `SCENARIO_LABEL=CameraStoragePeriodic`, and neither
      `CameraStoragePeriodic` nor `CameraStorageWithViewer` exists in `CanaryConstants` — so the
      consumer hits `default:` and throws `Improper canary label`. Either add both constants and
      cases, or point those entries at an allowlisted label, before that file is used. (Line 21
      has no consumer, so only line 18 dies today.) The file still carries
      `REPLACE-WITH-GAMMA-CONTROL-PLANE-URI` and `GIT_HASH=rpi5-sample`, so it looks like it was
      never actually deployed.
- [ ] **[B] The TWCC scenarios still need their gamma label.** The soak half is done (above); the
      four TWCC entries are not. `twcc_cron.txt` sets `SCENARIO_LABEL=StorageWithViewer` — the
      **prod** label — so running them on gamma pollutes prod's
      `StorageWebRTCSDKCanaryLabel=StorageWithViewer` aggregate, which is what prod alarms on.
      Every pre-existing scenario has a `Gamma*` twin (`CanaryConstants.java`) for exactly this
      reason. Use `GammaStorageWithViewer` in the gamma copy: it already exists as a constant and
      already has a `case`, so this is a cron edit with **zero code change**. If you invent any
      other new label instead, it needs both a constant and a `case` — the switch's `default:`
      throws and the consumer dies on startup.
- [x] **[B] Credential leak in the viewer's log — fixed at the chokepoint.**
      `buildTestUrl()` puts `accessKeyId` / `secretAccessKey` / `sessionToken` into the sample
      page's query string and `initializePage()` logged the whole URL (`Opening URL: …`) — ~17
      times in one 11h run.

      Two publication paths, and the second one was about to get worse: `log()` writes to
      `console.log` (→ Jenkins build log → the `JenkinsBuildLogs` group via the controller agent)
      **and** to `CloudWatchLogger.log()`. That second path was inert only because
      `CANARY_LOG_GROUP_NAME` was unset; setting it for per-run viewer streams (see §5) is
      precisely what would have made live credentials durable in `WebrtcSDK`. Fixed together, not
      separately.

      Fix: `redactSecrets()` in `chrome-headless.js`, applied inside `log()` so both paths are
      covered at once. Two passes — literal replacement of the `AWS_SECRET_ACCESS_KEY` /
      `AWS_SESSION_TOKEN` / `AWS_ACCESS_KEY_ID` values read from the environment *on every call*
      (so mid-run credential rotation cannot leak the new value), plus a parameter-name regex for
      the percent-encoded and JSON forms and for secrets the page mints itself. Because
      `setupConsoleListener()` also funnels the page's own console output through `log()`,
      anything the JS SDK sample prints about its config is scrubbed by the same pass. Verified
      with 17 assertions including that ordinary lines and harmless `=` signs are untouched.

      **Not covered:** the 7 raw `console.log` calls that bypass `log()` (none currently carries a
      credential), and any credential already written to CloudWatch or to a retained build record
      before this change — see the note at the end of this document.
- [ ] **[R] Log `verify.py`'s stdout.** `chrome-headless.js:80` captures it via `execFile` and
      discards everything but the summary line, so per-segment `duration=/frames=` and the
      per-gate PASS/FAIL are gone. Without it you cannot diagnose a gamma verification failure.
- [ ] **[R] Pass `--expected-duration`.** It is never passed, so the availability gates stay
      hard-wired at 120s / 3176 frames regardless of the clip actually recorded — the cause of
      the false `availability=0` verdicts. Ship this before the gamma alarms go live, or the
      first week of gamma data will contain known-bad datapoints.
- [ ] **[F]** `ActiveViewersPerSession heartbeat: 0` reported while video is flowing.
- [ ] **[F]** The three OCR counters emitted as `StandardUnit.Seconds` in `runVerifyScript`.

---

## 2. AWS resources

- [ ] **[B]** One signaling channel **per `RUNNER_LABEL`**, named `${JOB_NAME}-${RUNNER_LABEL}`.
      For the planned set that is 5: `RpiTwccGood`, `RpiTwccBad`, `RpiTwccCongesting`,
      `RpiTwccRecovering`, `RpiSoak`. Renaming the gamma job later renames every channel.
- [ ] **[B]** One KVS stream per channel, same name.
- [ ] **[B]** `UpdateMediaStorageConfiguration` on each channel to associate its stream —
      without it the master connects but nothing persists, and the consumer sees an empty stream.
- [x] **[B] TWCC shadow-mode allowlist — no longer required. TWCC has gone GA** (confirmed by the
      canary owner, 2026-09-13), so there is no per-account, per-region `twcc-shadow-mode`
      allowlist to wait on and no external dependency here. This was previously the longest-lead
      item on the whole checklist; it is now simply gone. Any comment in `twcc_cron.txt` or the
      TWCC docs that still tells the reader to request allowlisting is stale and should be removed
      the next time that file is touched — leaving it in place costs the next person a support
      request that will be answered with "that is GA now".
- [ ] **[B]** Set data retention on the new streams. The soak ingests continuously
      (~1617 kbps measured), so an unbounded retention is a standing cost.
- [x] **[R] `WebrtcSDK` retention — measured 2026-09-13, account 232283333863, ReadOnly; decided
      to leave alone:**

      | Region | Group | Stored | Retention |
      |---|---|---|---|
      | us-west-2 | `WebrtcSDK` | **294 GB** (created 2020-10-06) | **None** |
      | us-east-1 | `WebrtcSDK` | **18.3 GB** | **None** |
      | us-east-1 | `JSSDK` | 1.49 GB | **None** |

      **Decision: leave retention at `None`. Do not set it.** An earlier revision of this item
      promoted it to `[B]` and recommended 60 days. That was wrong, and the accounting is worth
      keeping so nobody re-escalates it:

      - **Cost is negligible and not the argument.** ~$0.03/GB-month puts 294 GB at roughly
        **$9/month**. The group has grown 294 GB since 2020-10-06, i.e. ~50 GB/year. The two new
        writers add little: the consumer measures ~0.24 MB/h (~2 GB/year for a continuous soak)
        and the viewer is the same order, so the rate goes to perhaps ~65 GB/year — about
        $0.15/month more per year. **Ingestion ($0.50/GB) is charged regardless of retention**, so
        a retention policy saves nothing on the dominant cost.
      - **Security is not the argument either.** The credentials the viewer used to log came from
        `AWS_*`, which are Canary-STS *temporary* sessions expiring within 12 hours. Retaining
        them forever is not an active risk, so "old logs hold live credentials" does not hold.
      - **The one real problem is stream count, not bytes.** Enumerating this group's streams
        times out: `DescribeLogStreams` pages 50 at a time at 5 TPS, and one stream per run per
        component — now three components instead of one — triples the count.
        **But the fix for that is querying by stream-name prefix, or using Insights (which scans
        by time range and never enumerates streams), not deleting history.** Trading six years of
        irreversible history for faster pagination is not a good trade.

      If a bound is ever wanted for its own sake, pick one long enough to be practically lossless
      (a year or more) rather than one tuned to a soak's length.

      Stream naming is confirmed live and matches what the new code assumes:
      `StorageWithViewer-StorageMaster-<ts>`, `StorageThreeViewers-StorageMaster-<ts>`,
      `StoragePeriodic-StorageMaster-<ts>`, plus non-storage canaries as
      `WebrtcPeriodic*-Master-<ts>` / `-Viewer-<ts>`. The new `-StorageConsumer-<ts>` and
      `-JSViewer-<ts>` slot in alongside.

      **Correction to two earlier claims in this document.** (1) An earlier revision said
      us-east-1 had no `WebrtcSDK` and that the us-west-2 group held only ~4.8 MB. Both readings
      came from the wrong AWS account: `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` were set in the
      environment, which silently overrides `AWS_PROFILE`, so the queries hit a personal account
      that happens to have a same-named group. Always `env -u` those variables before using a
      profile against this account. (2) `JSSDK` was dismissed as "not written by these canaries
      since the name appears nowhere in the repo". The name is indeed absent from the code, but
      the group exists with 1.49 GB and no retention — so something writes it, and it is a
      candidate holder of the historical credential exposure. Identify the writer before deciding
      anything about that group; it is not covered by the decision above.
- [ ] **[R] Confirm the consumer node's role can write logs.** The appender calls
      `CreateLogGroup` / `CreateLogStream` / `PutLogEvents`. The C master already makes exactly
      these three calls against the same group (`src/CloudwatchLogs.cpp:20-24`,
      `src/Cloudwatch.cpp:55-57`) with `Canary-STS` credentials, and the consumer resolves to the
      same role — via `CANARY_CREDENTIALS_ROLE_ARN` under `SOAK_MODE`, or the ambient `AWS_*`
      session otherwise — so this should already hold. Worth one check anyway, because the failure
      is silent by design: `attach()` catches, warns, returns null, and the run continues
      stdout-only. Grep the first consumer log for `CloudWatch Logs shipping enabled`.
- [ ] **[B]** IAM: the new viewer and consumer EC2 instance profiles must be trusted by
      `Canary-STS`. `CONSUMER_AUTO_REFRESH_CREDS` re-assumes `CANARY_STS_ROLE_ARN` using the
      consumer node's instance profile as base and will fail closed without the trust edit.
- [ ] **[R]** Confirm `STS_DURATION_SECONDS` per node type: 3600 only where credentials are
      role-chained (the Pi); EC2 nodes can mint up to 43200. Getting this wrong caps the soak
      at 1h.

---

## 3. Hosts and Jenkins nodes

- [ ] **[B] New EC2 for the soak viewer + consumer, dedicated.** A soak occupies one executor
      *indefinitely*; if it shares a node with the periodic gamma jobs, those queue forever.
      This is the gamma-pileup failure mode already seen once.
- [ ] **[B] Dedicate the TWCC node.** TWCC needs `gst=ON` (`CANARY_MEDIA_SOURCE=testsrc`); the
      disk-frame canary is `gst=OFF`. They share one `~/webrtc-c-storage-master/build`, so a
      node alternating between them rebuilds from scratch every run when the `.build-flags`
      stamp flips (`rpi5-setup-sop.md:453-456`). Use its own label (`rpi5-twcc`).
- [ ] **[B]** Node labels + executor counts settled for: TWCC master, soak master, soak viewer,
      soak consumer, periodic viewer, periodic consumer. The 4 TWCC jobs are staggered 5 min
      apart on a single executor by design — preserve that when re-timing for gamma.
- [ ] **[B]** TWCC node provisioning via `rpi-onboard.sh` Phase 3b: `/usr/local/bin/twcc-net`,
      `/etc/sudoers.d/twcc-canary` (validated with `visudo -c`), `iptables`,
      `gstreamer1.0-plugins-ugly` (x264enc), gst dev packages. Verify with the one-liner in
      `rpi5-setup-sop.md:460` — no password prompt plus a successful ping in `kvsns`.
- [ ] **[B]** Install the cleanup crons on every new node: `cleanup-common.sh` (new on this
      branch), `cleanup-viewer.sh`, `cleanup-consumer.sh`, `cleanup-master.sh`. A soak node
      without cleanup fills its disk.
- [ ] **[R] Reconcile cleanup retention with soak diagnosis.** `cleanup-viewer.sh:53` deletes
      `recordings/viewer-*` at `-mmin +60`; that is why the 09-08 recordings were gone before
      they could be examined. Decide `KEEP_RECORDING` / a longer window for the soak node
      specifically, and size the disk for it.
- [ ] **[R]** Disk sizing for the soak node: continuous recording plus per-segment mp4s plus
      Chrome profile churn.
- [ ] **[R]** Consumer node toolchain: Java + maven, and pick up the `Makefile` `mvn clean` fix
      (`3d9c00ba`) so a stale delombok orphan cannot poison the node.
- [ ] **[F] CloudWatch agent on the consumer node** — *demoted from [R]*: the in-process
      `CloudWatchLogsAppender` (see §5) now delivers the consumer's log4j output per run, so the
      agent is no longer the mechanism for that. What it would still add is the residue an
      appender structurally cannot see: the three `log4j:ERROR` lines printed before log4j is
      configured, raw stderr, and JVM fatal-error output. Those are already durable on the node
      in the tee'd file for 7 days, so this is now a convenience, not a gap.
      It would tail `$HOME/canary-logs/consumer-*.log`
      into `WebrtcSDK` as `{instance_id}-StorageConsumer`. Needs a
      `timestamp_format` matching the log4j `PatternLayout` (`%d{yyyy-MM-dd HH:mm:ss.SSS}`),
      `"timezone": "UTC"` to pair with the stage's `-Duser.timezone=UTC`, and
      `multi_line_start_pattern` so stack traces stay one event. Also needs
      `CloudWatchAgentServerPolicy` on the instance profile. There is no consumer onboarding
      script yet — `scripts/` has only `rpi-onboard.sh`, `setup-storage-viewer.sh`,
      `cert_setup.sh` — so this is a new `setup-storage-consumer.sh` or an SOP entry. Verify the
      fractional-second `timestamp_format` against the installed agent version before relying
      on it.
- [ ] **[R]** Reverse tunnel / SSM reachability confirmed for every new node before it is
      enrolled, per the 2-hop launch path.

---

## 4. Jenkins jobs and cron

- [ ] **[B]** Re-seed the gamma job from the `twcc-canary` `gamma_runner.groovy`. New
      parameters only materialize after one build of the updated pipeline definition — cron
      entries referencing them before that silently do nothing.
- [ ] **[B] Write `gamma_twcc_cron.txt`.** Do not copy `twcc_cron.txt`: it hard-codes
      `AWS_DEFAULT_REGION=us-east-1`, no `ENDPOINT`, `MASTER_NODE_LABEL=rpi5-twcc`,
      `STORAGE_VIEWER_NODE_LABEL=webrtc-storage-viewer`, and the prod `SCENARIO_LABEL`. Every
      one of those is wrong for gamma.
- [x] **[B] `gamma_soak_cron.txt` written**, with every parameter traced against
      `gamma_runner.groovy`'s declared defaults and the reasoning recorded inline: what is set and
      would be wrong on the default, what is omitted *because* the gamma runner's default is
      already right (`AWS_DEFAULT_REGION`, `LOG_GROUP_NAME`, the `gamma-*` node labels), and what
      is omitted because it does nothing under `SOAK_MODE` (`DURATION_IN_SECONDS`,
      `VIEWER_SESSION_RECYCLE_SECONDS`, the two consumer flags `SOAK_MODE` already implies).
      Three placeholders remain and cannot be guessed: the gamma control-plane URI, the three
      dedicated node labels, and the master Pi's IoT thing name. The Pi-specific trio
      (`USE_IOT_CREDENTIALS`, `IOT_CORE_THING_NAME`, `STS_DURATION_SECONDS=3600`) is documented to
      be **deleted** if the gamma soak master turns out to be EC2.
- [ ] **[R]** Align `soak_cron.txt` — done: `0,30` → `H/30`, matching what the live controller
      already ran. Same frequency, but the offset is hashed per job so the tick that actually
      starts a soak (clone, possible `gst=ON` rebuild, three nodes coming up) does not land on the
      same instant as every other cron.
- [ ] **[B] Apply the cron-line discipline to every parameter**, per the standing rule: (1) is
      it declared in the runner, (2) does it equal the `defaultValue` — delete it if so,
      (3) is it actually read in this mode — trace every use site, (4) what is the failure mode
      if it is wrong. Specifically confirm `METRIC_SUFFIX` is set deliberately rather than
      inherited from the `-gamma` default, and that `DURATION_IN_SECONDS` is omitted under
      `SOAK_MODE` (all three components ignore it).
- [x] **[B]** `SOAK_MODE`'s timeout lift **is** present in `gamma_runner.groovy`, verified
      2026-09-14: `:589` lifts the master/stage timeout and `:853` the whole-pipeline `options`
      timeout, both to `2592000` seconds (30 days) when `SOAK_MODE=true`. Since the soak already
      runs on this runner rather than `storage_runner.groovy`, this was the one that mattered.
- [ ] **[R]** Build retention: soak builds are few but enormous. Raise `Max` to ~500 and add
      `keepLog(true)` on soak failure so the evidence survives.
- [ ] **[R]** Verify no cron entry shares a `RUNNER_LABEL` with an existing gamma job — a
      collision means two masters on one channel.
- [ ] **[R]** Sanity-check the whole cron file for the malformed-parameterized-cron trap
      (a blank line in a job config) that previously produced `configSubmit` 500s.

---

## 5. Observability

- [ ] **[B]** TWCC alarms: the 4 steady-state jobs map 1:1 to a condition with an expected
      value (`GOOD` ~2 Mbps, `CONGESTING` well under 500 kbit, `BAD` near the 100 kbps floor,
      `RECOVERING` ~1.5 Mbps). Alarm on those. Do **not** alarm on a cycling profile — read it
      as a graph.
- [ ] **[B]** Soak alarms must tolerate the by-design hourly reconnect: **10
      `MasterStreamingAvailability=0` events per day per soak**, cycle 3621 ± 2s
      (`soak-reconnect-cycle-analysis.md`). A naive "any zero" alarm pages 10×/day forever.
- [ ] **[R]** Dashboard sliced by `RUNNER_LABEL` for the new labels, and a gamma-vs-prod view
      that works given the two *different* separation mechanisms (`METRIC_SUFFIX` for
      master/viewer, `SCENARIO_LABEL` for the consumer).
- [ ] **[R]** `alarm-sop.md` updated with the new stages/labels so the liveness chain table
      covers TWCC and soak.
- [x] **[F] Ship the consumer's log to CloudWatch — landed, one stream per run.** The consumer
      used to push **no logs at all**, only metrics: its sole appender was `ConsoleAppender` →
      stdout (`consumer-java/src/main/resources/log4j.properties`) and no CloudWatch Logs client
      had ever existed in that source tree, so on a 24/7 soak losing one build record lost the
      evidence. Now three things, together:

      1. **`$HOME/canary-logs/consumer-<BUILD_NUMBER>.log`** — both runners tee the consumer
         stage (`#!/bin/bash` + `set -eo pipefail`, so a crashed consumer still fails the stage),
         reaped by `cleanup-consumer.sh` (`CONSUMER_LOG_MAX_AGE_MIN`, 7d, newest file vetoed
         while a consumer process is up). This is the only copy of what an appender cannot see.
      2. **`CloudWatchLogsAppender`** (`consumer-java/src/main/java/.../CloudWatchLogsAppender.java`,
         `aws-java-sdk-logs` added to `pom.xml`; `tmp_jar` is generated by
         `dependency:build-classpath` so the node picks it up with no runner change). Attached
         from `main()` — *not* from `log4j.properties`, because log4j initializes in a static
         initializer before `main()`, when the soak's assume-role provider does not exist yet —
         so it shares `mCredentialsProvider` and inherits the soak's credential refresh.
         Timer-only 5s flush, deliberately not the count-triggered flush the C master still has
         (`webrtc-c/src/CloudwatchLogs.cpp`), which strands the tail of a wedged run in memory.
      3. **`CANARY_LOG_GROUP_NAME` / `CANARY_LOG_STREAM_NAME` in `consumerEnvs`** in both
         runners, `<RUNNER_LABEL>-StorageConsumer-<START_TIMESTAMP>` — the twin of the master's
         `-StorageMaster-` and the viewer's `-JSViewer-`. `START_TIMESTAMP` is per build, so a
         soak appends to ONE stream for its whole life.

      Tail coverage: `shutdownCanaryResources()` closes the appender last, and `attach()` also
      registers a JVM shutdown hook, so the tail is flushed on a bounded return from `main()`,
      on `System.exit(1)` from the uncaught-exception handler (which never reaches
      `shutdownCanaryResources()`), and on SIGTERM — including Jenkins' SIGTERM before it
      escalates. Only a bare SIGKILL or a JVM crash loses the buffer, bounded by the 5s interval.
      Client timeouts are bounded for exactly this reason: on SDK defaults the hook's flush could
      outlive the SIGTERM grace period and be killed mid-flush anyway.
- [x] **[F] Viewer log durability — the CloudWatch half now actually runs.** `storage_runner.groovy:340`
      uses `returnStdout: true`, so an aborted run (i.e. every soak) discards the whole
      application log.

      **Correction to an earlier version of this item, which claimed the viewer's own
      CloudWatch push covered most of that.** It did not. `chrome-headless.js:306` reads
      `process.env.CANARY_LOG_GROUP_NAME || ''`, nothing in either runner set it, and
      `CloudWatchLogger.init()` returns at its first guard on an empty group name — leaving
      `initialized` false, which makes `log()`'s `CloudWatchLogger.log()` call a no-op. So the JS
      viewer has been shipping **zero** logs to CloudWatch, not "one stream per recycle segment":
      the `Date.now()` stream-name fallback at `:307` was computed and then never used, and no
      stream was ever created. (`JSSDK` appears nowhere in this repo — whatever fills that group
      is not this viewer, so don't reason about this viewer from `JSSDK`'s contents.)

      Now fixed: both runners export `CANARY_LOG_GROUP_NAME` and `CANARY_LOG_STREAM_NAME`
      (`<RUNNER_LABEL>[-<viewerId>]-JSViewer-<START_TIMESTAMP>`) in `viewerExports`, so the
      viewer ships to the same `WebrtcSDK` group as the master, one stream per run.
      `CloudWatchLogger.init()` also gained a re-entry guard — `initializeCloudWatch()` is called
      once per recycle segment, so with a now-constant stream name it would otherwise overwrite
      `this.flushInterval` and orphan a `setInterval` per segment (~1080 over a 30-day soak).
      That leak was **latent, not pre-existing**: the early return meant the timer was never
      created at all before this change.

      Remaining gap, unchanged: pre-`init` output, node/Chrome stderr, `verify.py` stdout, and
      the 7 raw `console.log` calls that bypass `log()`.

---

## 6. Cutover sequence

- [ ] **[R]** Capture a baseline on `integration-bitrate-latency` first — current metric values
      per label — so a post-cutover shift is attributable.
- [ ] **[B]** Switch `GIT_HASH` **per cron entry**, not globally. The TWCC entries already pin
      `GIT_HASH=twcc-canary`; the existing gamma periodic entries must be moved deliberately
      and can be moved one at a time.
- [ ] **[R]** Dry run each new job manually, once, with `RESCHEDULE=false`, before enabling its
      cron. Confirm on the master log that TWCC actually negotiated (not just that the job
      went green).
- [ ] **[R]** Run the soak in shadow for one full cycle (>2h, so it crosses at least two
      forced reconnects) before its alarms go live.
- [ ] **[R]** Rollback plan: revert `GIT_HASH` per entry. Note this does **not** roll back the
      channels/streams, the IAM trust edits, or the node provisioning — those are additive and
      safe to leave.
- [ ] **[R]** Re-verify conclusions drawn from `very-long-viewer.log` and `20-hour-viewer.log`
      before quoting them as gamma baselines — both contain zero viewer output.

---

## What is *not* on this list, deliberately

- Purging the historical credential exposure from the `JSSDK` log group. That is a destructive
  operation on a production log group and is a separate, explicit decision.
- The >2100s undecodable-recording bug. Root cause is still unknown and diagnosis is blocked on
  §1's discarded `verify.py` stdout; it degrades soak *verification coverage* but does not block
  the cutover.
