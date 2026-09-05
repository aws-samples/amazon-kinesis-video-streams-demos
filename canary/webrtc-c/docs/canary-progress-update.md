# WebRTC SDK Canary — Progress Update (Phases 8+)

> **Purpose of this file.** Drop-in replacement content for the
> [WebRTC SDK Canary — Metrics, Coverage & Dashboard](https://quip-amazon.com/6OQaAgyvMJGG) Quip doc.
> Contains: (1) a rewritten Milestones Summary, (2) rewritten Scenarios table,
> (3) new detailed sections for Phases 8–16, and (4) corrections to apply to the
> existing Phase 1–7 sections.
>
> Phases 1–7 are **not** rewritten here — only the corrections listed at the end apply to them.
>
> Items marked **`TODO`** need input that isn't recoverable from the repo.

---

## Status vocabulary

The existing Milestones table mixes dates, `DONE`, `In Progress`, blanks, and prose.
This rewrite uses one consistent set:

| Status | Meaning |
|---|---|
| **Done** | Canary work complete and validated |
| **Blocked** | Canary work complete, but held on a dependency outside the canary — no canary work pending |
| **In Progress** | Actively being built |
| **Not Started** | Scoped but no implementation |

`Done` describes the canary deliverable, not necessarily continuous scheduling. Where a
completed phase is not yet running on a schedule, the reason is stated in that phase's
section — distinguishing "we still have work to do" from "we are waiting on someone else."

Phase numbers are **capability buckets, not chronological order** — Phase 8 completed
before Phases 6 and 7. Delivered and in-flight work is numbered ahead of unstarted work, so
Phases 18 and 19 sit last as the two phases with nothing delivered. Phases 14–17 are the
long-running soak work, split by concern.

---

## Milestones Summary (rewritten)

| Phase | Milestone | Status | Completed | Notes |
|---|---|---|---|---|
| 0 | Existing canary infrastructure (C Master + Storage Verifier, no viewer) | Done | Pre-existing | — |
| 1 | WebRTC C Master and single JS Viewer | Done | 1/6/2026 | — |
| 2 | WebRTC C Master and multiple JS Viewers | Done | 2/18/2026 | 2 and 3 viewer scenarios |
| 3 | Gamma expansion | Done | 3/27/2026 | Independent gamma job tree |
| 4 | Media verification (SSIM) | Done | 4/22/2026 | Viewer MediaRecorder + storage GetClip |
| 5 | Low-FPS coverage and canary stability | Done | 6/21/2026 | Cron migration; `StorageLowFps` |
| 6 | WebRTC C Master video-only expansion | Done | 6/30/2026 | `VOMasterMixedViewer` |
| 7 | JS Viewer audio-only expansion | Done | 6/30/2026 | Completes bidirectional media |
| 8 | SOP for canary metric investigation | Done | 6/16/2026 | **TODO** — scope needs confirming |
| 9 | CloudWatch alarms on high-level metrics (Sev3 to SDK oncall) | Not Started | — | No alarms deployed; SOPs drafted |
| 10 | WebRTC C Master on Raspberry Pi | Done | 7/26/2026 | First ARM/edge node in the fleet |
| 11 | TWCC congestion-control coverage | Done | — | 5-profile cycling test + 4 steady-state per-condition jobs |
| 12 | Live media sources (camera / RTSP / file) | Done | 8/18/2026 | Enables TWCC; adds camera, RTSP, and file ingest |
| 13 | Bitrate variant coverage | Done | — | Not scheduled — blocked on service-side adaptive bitrate deploy |
| 14 | Long-running soak: continuous execution | In Progress | — | Modes built; no standing soak scheduled yet |
| 15 | Long-running soak: credential lifetime | Done | 8/31/2026 | All components on auto-refreshing credentials |
| 16 | Long-running soak: continuous video verification | Done | 8/31/2026 | Full-coverage GetMedia segmenting, replaced 17% sampling |
| 17 | Long-running soak: self-recovery | Not Started | — | No liveness detection or auto-restart for a dead soak |
| 18 | JS Master with JS Viewer | Not Started | — | Nothing delivered |
| 19 | Android / iOS as Master with (JS, Android, iOS) Viewer | Not Started | — | Nothing delivered |

---

## All Scenarios covered in SDK Canary (rewritten)

> All scenarios also run in gamma with identical configuration against the gamma
> control plane endpoint, using a `-gamma` metric suffix to separate them from prod
> in CloudWatch. Gamma labels are the prod label prefixed with `Gamma`.

| Phase | Scenario | Frequency | Duration | Description |
|---|---|---|---|---|
| 0 | `StoragePeriodic` | 12 min | 153s | 1 C Master streaming video + audio from sample frames |
| 0 | `StorageSubReconnect` | 1 hour | 45 min | Stream under the 60 min max session duration |
| 0 | `StorageSingleReconnect` | 2 hours | 65 min | Stream past 60 min to verify SDK reconnect behavior |
| 1 | `StorageWithViewer` | 12 min | 153s | 1 C Master + 1 receive-only JS viewer; viewer joins first |
| 2 | `StorageTwoViewers` | 12 min | 153s | 1 C Master + 2 JS viewers (15s stagger); per-viewer metric isolation |
| 2 | `StorageThreeViewers` | 12 min | 153s | 1 C Master + 3 JS viewers (15s stagger); max concurrent viewers |
| 5 | `StorageLowFps` | 12 min | 153s | Master paced at 10 fps instead of 30 (`STORAGE_FPS=10`) |
| 6 | `VOMasterMixedViewer` | 12 min | 153s | Video-only master + 2 audio-only viewers + 1 receive-only viewer |
| 10 | `Rpi5StoragePeriodic` | On demand | 156s | `StoragePeriodic` with the master on a Raspberry Pi 5 |
| 11 | `RpiTwccCycling` (+ `CameraCycle` / `FileCycle` variants) | Per cron | 600s | Cycles all 5 profiles at 20s/stage (6 sweeps); read as a graph, not a threshold |
| 11 | `RpiTwccGood` / `Congesting` / `Bad` / `Recovering` | Every 20 min each | 156s | One network profile held for the whole run; alarm-friendly |
| 12 | `CameraStoragePeriodic` | Not live | 153s | Master ingests from a physical CSI camera via GStreamer |
| 12 | `CameraStorageWithViewer` | Not live | 153s | Camera ingest with a JS viewer attached |
| 13 | `StoragePeriodic-500kbps` / `-1mbps` / `-5mbps` | Blocked | 153s | Periodic run per encoded-bitrate asset set |
| 13 | `ShortVAMasterROViewer-500kbps` / `-1mbps` / `-5mbps` | Blocked | 153s | Video+audio master + read-only viewer + co-resident consumer, per bitrate |

**Changes from the previous table**

- Added `StorageLowFps` — delivered in Phase 5 and live in prod cron, but never documented.
- Corrected `VOMasterMixedViewer` from "Phase 4" to **Phase 6**.
- Added Phase 10 / 11 / 12 / 13 scenario rows.
- Removed the per-row `Expected Frequency` vs `Duration` ambiguity: `Frequency` is how
  often cron fires, `Duration` is stream length.

> **Note on `StorageLowFps`.** Lowering fps replays the *same* pre-encoded frames more
> slowly. Bytes per frame are unchanged, so effective bitrate drops roughly proportionally —
> at 10 fps the master sends about ⅓ the bitrate of the 30 fps default. A bitrate dip on
> this scenario is expected behavior, not a regression. Phase 13 is the isolated bitrate
> axis (bitrate varies, fps held at 30).

---

## Phase 8 — SOP for Canary Metric Investigation

**Date Completed:** 6/16/2026
**Status:** Done

Delivered a written runbook so that an oncall engineer seeing a canary metric dip can
determine whether it reflects a real service regression or a canary-side fault, without
prior canary context.

**TODO** — the following need confirming from whoever wrote the SOP:

- Which document is the canonical SOP, and where does it live (wiki vs Quip vs `docs/`)?
- Which metrics does it provide decision trees for?
- Does it cover both prod and gamma, and both master-side and viewer-side dips?

Related artifacts now in the repo: `docs/alarm-sop.md`, plus per-incident investigation
write-ups (`fragment-received-dip-0727-investigation.md`,
`session-health-dip-investigation.md`, `viewer-no-video-investigation.md`,
`gamma-queue-pileup-investigation.md`). Per the doc's own convention, investigation
detail is maintained separately from this progress record.

---

## Phase 9 — Alarms on High-Level Metrics

**Status:** Not Started

**No CloudWatch alarms are currently configured.** The intent is to alarm on the
high-level availability metrics already surfaced on the HighLevel dashboards and page the
SDK oncall at Sev3.

Groundwork that exists:

- `docs/alarm-sop.md` — response runbook for when alarms do fire.
- A soak-specific alarm SOP (commit `6c0e17cd`).
- The five high-level availability metrics are already emitted and dashboarded:
  `MasterStreamingAvailability`, `PeerConnectionAvailability` (master and viewer),
  `ViewerConnectionSuccessRate`, `ViewerStorageAvailability`.
- Phase 11's steady-state TWCC profiles were deliberately designed to hold one network
  condition per job so each has a stable expected bitrate — i.e. alarm-friendly by design.

**TODO** — needs decisions before implementation:

- Which metrics get alarms, and at what thresholds?
- Evaluation periods and datapoints-to-alarm (canary runs are short and sparse, so
  single-run noise must not page).
- Prod only, or gamma too? Gamma paging on a bad service deployment may be desirable
  or may be noise.
- Confirm the Sev3 → SDK oncall routing was agreed.

Prior status noted a review with the SDK team and Babu Prasad Dhandapani, with the
deadline moved to end of July. That date has passed with no alarms deployed — this phase
needs rescoping or reprioritizing.

---

## Phase 10 — WebRTC C Master on Raspberry Pi

**Date Completed:** 7/26/2026
**Date Started:** ~7/23/2026 (commit `f35bef26`)
**Status:** Done

Every canary node before this was an EC2 instance. This phase put the C Master on real
edge hardware — a Raspberry Pi 5 (8GB, aarch64) — so the canary exercises the SDK on the
class of device customers actually deploy: ARM, software-only H.264 encode, no hardware
encoder, consumer-grade uplink.

### Tests Added

| Test Name | Duration | Description |
|---|---|---|
| `Rpi5StoragePeriodic` | 156s | `StoragePeriodic` equivalent with the master on a Pi 5, streaming to KVS storage with consumer-side fragment verification |

### Features Delivered

| Category | Feature | Details |
|---|---|---|
| Infrastructure | Pi 5 enrolled as a Jenkins node | Node `rpi5-01`, label `rpi5-master`, launched via the same two-hop jump-host path as EC2 agents so the fleet model is unchanged |
| Infrastructure | Reverse SSH tunnel under systemd | `autossh` + `rpi-jenkins-tunnel.service` keeps the Pi reachable from Jenkins without inbound ports or a public IP; survives reboot and network loss |
| Infrastructure | aarch64 toolchain and AWS CLI | Native ARM build environment plus aarch64 AWS CLI on the node |
| Security | IoT X.509 credential chain (zero static keys) | IoT thing → role alias → `rpi5-canary-bootstrap` → `Canary-STS`, surfaced to the AWS CLI/SDK through `credential_process`. No long-lived access keys on the device |
| Stability | `STS_DURATION_SECONDS` parameter | Role chaining hard-caps STS sessions at 1 hour (EC2 instance profiles are exempt). Parameterized in `storage_runner.groovy` and `gamma_runner.groovy`; the Pi passes `3600` while EC2 nodes keep the 12 hour default |
| Stability | Persistent build with code fingerprinting | `build-storage-master.sh` keeps a persistent repo and build directory and rebuilds only when `src/` or `CMakeLists.txt` changes (commit `1f312869`). A cold build on the Pi is 20–40 minutes, so this is the difference between a viable and unviable cadence |
| Stability | Build retry on SDK dependency race | Retry once and capture exit status to absorb a parallel-dependency race in the SDK build (commit `06a1a3a9`) |

### Validation

First successful end-to-end run on **2026-07-26** via `gamma_runner`: 156s stream from the
Pi through the media server into KVS storage. The consumer observed fragments 1→16 with
`FragmentReceived=1.0` throughout, confirming continuous persistence with no gaps.

### Known Issues / Follow-ups

- **Scenario label borrowing.** Runs use `SCENARIO_LABEL=StoragePeriodic` because the Java
  consumer rejects unrecognized labels, with `RUNNER_LABEL=Rpi5StoragePeriodic` providing
  channel and log-stream isolation. Adding `Rpi5StoragePeriodic` to `CanaryConstants`
  would give the Pi proper label-level metric separation.
- **`cert_setup.sh` runs unconditionally.** Its IoT calls fail harmlessly on the Pi;
  should be wrapped in `if (params.USE_IOT)`.
- **Boot disk reliability.** Two libcrypto corruption failures traced to the USB boot
  disk; replacement scheduled.
- **Jump-host key scoping.** The tunnel key should be narrowed to the minimum required
  forwarding.

### Documentation Produced

`docs/rpi5-setup-sop.md`, `docs/rpi5-security-review.md`,
`docs/reverse-tunnel-key-diagram.md`, `docs/iot-credential-rotation.md`.
The security review is drafted and pending senior engineer sign-off.

---

## Phase 11 — TWCC Congestion-Control Coverage

**Date Started:** 8/13/2026 (commit `af706aa3`)
**Status:** Done

Every prior scenario streams over an unconstrained network, so the SDK's bandwidth
adaptation was never exercised. This phase shapes the network path and asserts that
sender-side bitrate tracks the imposed cap — validating TWCC end to end rather than
just confirming a connection survives.

A pre-encoded frame set cannot adapt, so every TWCC scenario requires a live GStreamer
encoder from Phase 12 — `testsrc` for the steady-state jobs, `devicesrc` / `filesrc` for
the cycling jobs.

### Network Profiles

`scripts/twcc/netns_profiles.sh` defines five profiles, each setting bandwidth, loss,
delay, and jitter together on the master's uplink:

| Profile | Bandwidth | Loss | Delay | Jitter |
|---|---|---|---|---|
| GOOD | 3 mbit | 0% | 20 ms | 5 ms |
| MEDIUM | 1 mbit | 1% | 50 ms | 15 ms |
| CONGESTING | 500 kbit | 1.5% | 75 ms | 20 ms |
| BAD | 250 kbit | 2% | 100 ms | 30 ms |
| RECOVERING | 1500 kbit | 0.5% | 35 ms | 10 ms |

TWCC runs override loss to 0 (`TWCC_THROTTLE_LOSS=0`) — packet loss is decode damage, not
congestion, and would confound the bitrate signal that TWCC actually controls.

### Tests Added

Two modes, selected by `TWCC_PROFILE`: empty cycles all five profiles, set holds one.

**Cycling (primary characterization test)** — sweeps
GOOD → MEDIUM → CONGESTING → BAD → RECOVERING and loops, 20s per stage
(`TWCC_STAGE_SECONDS`), 100s per sweep. `DURATION_IN_SECONDS=600` gives 6 full sweeps.
One `RUNNER_LABEL` sees the whole sawtooth, so these are read on a **graph** for adaptation
shape rather than against a single threshold. Covers all 5 profiles.

| Test Name | Source | Duration |
|---|---|---|
| `RpiTwccCycling` | `testsrc` | 600s |
| `RpiTwccCameraCycle` | `devicesrc` (Pi CSI camera) | 600s |
| `RpiTwccFileCycle` | `filesrc` (local video file) | 600s |

Validated: `docs/rpi-twcc-master.log` captures an `RpiTwccCycling` run with the shaper
reporting `20s/stage, loss=0`, sweeping the full profile set.

**Steady-state (alarm-friendly variant)** — each job holds one condition for the whole
run, so `RUNNER_LABEL` maps 1:1 to a profile with a single expected bitrate that a
threshold alarm can use.

| Test Name | Profile Held | Expected Bitrate | Duration |
|---|---|---|---|
| `RpiTwccGood` | GOOD | ~2 Mbps (encoder near MAX) | 156s |
| `RpiTwccCongesting` | CONGESTING | well under 500 kbit | 156s |
| `RpiTwccBad` | BAD | near the 100 kbps floor | 156s |
| `RpiTwccRecovering` | RECOVERING | ~1.5 Mbps range | 156s |

MEDIUM has no dedicated steady-state job — it is covered by the cycling test, which sweeps
all five profiles.

All four steady jobs share the `rpi5-twcc` node label and one `gst=ON` cached build, and
are staggered 5 minutes apart so they don't collide on the node's single executor.

The two modes are complementary: cycling characterizes adaptation dynamics across
transitions, while the steady-state jobs give each condition a single stable expected
bitrate that a threshold alarm can use.

### Features Delivered

| Category | Feature | Details |
|---|---|---|
| Canary | Ingress shaping — master uplink | `CANARY_TWCC_SHAPING` builds a network-namespace mid-path router with `tc`/`netem`, applying a bandwidth cap the master must adapt to. Requires the `/usr/local/bin/twcc-net` wrapper and a sudoers grant on the node (commit `f556d65c`) |
| Canary | Egress shaping — viewer downlink | `VIEWER_TWCC_SHAPING` shapes the JS viewer's downlink so the *media service's* send-side congestion control adapts. Kept independent of master shaping so ingress and egress are isolated (commit `5158b333`) |
| Canary | TWCC-driven encoder bitrate adaptation | The master adjusts encoder bitrate from TWCC feedback (commit `af706aa3`) |
| Canary | `TwccBitrateController` | AIMD policy extracted into a pure function (`computeAdaptedBitrate`) with no SDK, GStreamer, or CloudWatch types, making the control law unit-testable in isolation. Stateful concerns (loss EMA, interval gating, locked hand-off to the media thread) stay in `Common.cpp` |
| Canary | Five named network profiles | GOOD / MEDIUM / CONGESTING / BAD / RECOVERING, selectable steady-state via `TWCC_PROFILE` or cycled with `TWCC_STAGE_SECONDS` (commit `806f9828`) |
| Canary | Encoder bitrate floor | `TWCC_MIN_VIDEO_BITRATE_KBPS` (default 100) lets the encoder reach the 250 kbps BAD profile instead of stalling above it |
| Canary | Loss injection control | `TWCC_THROTTLE_LOSS` defaults to 0 — injected packet loss is decode damage, not congestion, and would confound the bitrate signal |
| Observability | Configurable metrics period | `TWCC_METRICS_PERIOD_SECONDS` (default 5, vs the usual 60) so CloudWatch resolves individual cap steps (commit `9d6defb1`) |
| Observability | Wall-clock-aligned throttle stages | Stage transitions align to wall-clock boundaries so they land in clean CloudWatch buckets (commit `72735f1e`) |
| Tooling | Node provisioning and log analysis | `install-twcc-node.sh`, `install-twcc-viewer-node.sh`, `scripts/twcc/analyze-twcc-log.py`, netns router and profile scripts (commits `7530114f`, `99f479d5`) |

### Metrics Introduced

| Metric Name | Namespace | Type | Description |
|---|---|---|---|
| `EstimatedBitrate` | KinesisVideoSDKCanary | Kbps | TWCC-estimated available bandwidth as seen by the sender |
| `DelayTrend` | KinesisVideoSDKCanary | None | TWCC delay-gradient trend, the congestion signal driving adaptation |
| `AppliedBandwidthKbps` | KinesisVideoSDKCanary / ViewerApplication | Kbps | The `netem` cap actually in force, so measured bitrate can be compared against ground truth (commits `f26f616b`, `994329db`) |

### Prerequisites and Dependencies

- **Media-service `twcc-shadow-mode` allowlist.** The channel-owner account
  (232283333863) must be on the allowlist for the run's region or **TWCC will not
  negotiate at all**. This is a service-side dependency, in the same class as the
  Phase 13 blocker.
- **Node provisioning.** The node must be onboarded via `rpi-onboard.sh`, which installs
  `/usr/local/bin/twcc-net`, `/etc/sudoers.d/twcc-canary`, iptables rules, and
  `gstreamer1.0-plugins-ugly`.
- **Dedicated node label.** `rpi5-twcc`, kept off disk-path nodes so the `gst=ON` build
  cache isn't thrashed.

### Follow-ups

- `jobs/cron/twcc_cron.txt` has uncommitted modifications (expanded cycling examples for
  the camera and file sources) — commit them.
- Jobs pin `GIT_HASH=twcc-canary` and run in `us-east-1`. Merging the branch to mainline is
  a prerequisite for treating this as part of the standing suite.
- Per-profile expected bitrate ranges live in the cron header; move them somewhere durable
  before Phase 9 alarms on them.

---

## Phase 12 — Live Media Sources (GStreamer)

**Date Started:** 7/27/2026 (commit `f61ea722`)
**Date Started:** 7/27/2026 (commit `f61ea722`)
**Date Completed:** 8/18/2026 (commit `b5a309b7`)
**Status:** Done

All prior scenarios replay pre-encoded frames from disk, which cannot exercise a live
encoder, cannot adapt bitrate, and does not represent how customers actually produce media.
This phase adds live GStreamer sources — including a real camera.

### Tests Added

| Test Name | Duration | Description |
|---|---|---|
| `CameraStoragePeriodic` | 153s | Master ingests from a physical CSI camera on the Pi |
| `CameraStorageWithViewer` | 153s | Camera ingest with a JS viewer attached |

The live sources are also what makes Phase 11 possible — TWCC needs an encoder that can
actually change bitrate, which a pre-encoded frame set cannot do.

### Features Delivered

| Category | Feature | Details |
|---|---|---|
| Canary | `ENABLE_GST_MEDIA_SOURCE` build option | Off by default, so existing non-GStreamer builds and nodes are unaffected. Jenkins derives the flag from `CANARY_MEDIA_SOURCE` rather than requiring a separate parameter (commit `5b6ccf51`) |
| Canary | `CANARY_MEDIA_SOURCE` selector | `disk` (default) / `testsrc` / `devicesrc` / `camerasrc` / `rtspsrc` / `filesrc` / `framesrc`. A non-disk value on a build without GStreamer support logs a warning and falls back to disk rather than failing |
| Canary | `camerasrc` via libcamera | Physical CSI camera capture on the Pi (commit `b5a309b7`). The Pi 5 has no hardware H.264 encoder, so encoding is software `x264enc` |
| Canary | `filesrc` and `framesrc` | Local video file, decoded and re-encoded through `x264enc` so TWCC can drive bitrate. `CANARY_GST_FILE` gives the path; the file must outlast the run since it does not loop (commits `1c4f14f8`, `69efc81a`) |
| Canary | `audio-source.wav` for framesrc audio | Provides an audio track for live-source runs (commit `0142164b`) |
| Verification | Presence-mode video verification | SSIM against static reference frames is meaningless for a live camera, so live-source runs verify media presence and decodability instead of frame-exact similarity (commit `e9bfc7f9`) |
| Stability | Keyframe interval cap | `key-int-max=30` bounds the GStreamer keyframe interval to 1s, matching fragment expectations (commit `b08e5a4f`) |
| Stability | 90-minute pipeline timeout for gst runs | Live-source runs build GStreamer support and need more headroom than disk-mode runs (commit `0c1b24fb`) |

### Follow-ups

- Decide whether `rtspsrc` gets a scheduled scenario or stays an on-demand capability.

---

## Phase 13 — Bitrate Variant Coverage

**Status:** Done (canary side) — **not scheduled: blocked on service-side dependency**

Frame rate and bitrate were previously coupled: the only way to change ingest bitrate was
`StorageLowFps`, which lowers fps and drops bitrate as a side effect. This phase adds an
isolated bitrate axis — same content, same 30 fps, re-encoded at different bitrates — so
service behavior across ingest bitrates can be measured independently.

> **Why these scenarios are not running.** The canary side is complete and validated, but
> the service-side adaptive bitrate change is **not yet deployed**. Until it is, these
> scenarios would exercise a code path that does not exist yet and produce no actionable
> signal, so scheduling them now has no value. This is a deliberate hold on an external
> dependency, not outstanding canary work.

### Tests Added

| Test Name | Duration | Status | Description |
|---|---|---|---|
| `StoragePeriodic-500kbps` / `-1mbps` / `-5mbps` | 153s | Not scheduled | Periodic run per encoded-bitrate asset set |
| `ShortVAMasterROViewer-500kbps` / `-1mbps` / `-5mbps` | 153s | Not scheduled | Video+audio master + read-only viewer + co-resident consumer, per bitrate |

All six have `Gamma`-prefixed equivalents. All twelve labels are registered in the Java
consumer's `CanaryConstants`.

### Features Delivered

| Category | Feature | Details |
|---|---|---|
| Canary | Three bitrate-variant asset sets | `h264SampleFrames-500kbps`, `-1mbps`, `-5mbps` — the same 1280×720 30fps 156s source content (4676 frames each) re-encoded at different bitrates |
| Canary | `CANARY_ASSET_SET` / `STORAGE_ASSET_SET` selector | Resolved independently in `main()` (availability probe) and `sendVideoPackets` (replay path), defaulting to `h264SampleFrames` so existing runs are unaffected |
| Canary | S3-hosted asset delivery | The variants are gitignored, so a clean Jenkins checkout has only the default set. `fetch-asset-set.sh` downloads `<set>.tar.gz` from `s3://${CANARY_ASSET_BUCKET}/${CANARY_ASSET_PREFIX}/`, streaming directly into `tar` with no intermediate file. Currently hosted at `s3://sdk-canary-assets-bucket/webrtc-canary/frame-sets/v1/` |
| Canary | Idempotent fetch with verification | Skips when the set is already present with all 4676 frames, refetches when incomplete, and verifies both frame count and the presence of `frame-0001.h264` (the IDR entry point the canary requires). The default set is an explicit no-op |
| Canary | Separate asset-bucket region | `CANARY_ASSET_REGION` may differ from the run's `AWS_DEFAULT_REGION`, falling back to it when unset (commit `1e82e1c2`) |
| Canary | Fetch diagnostics | Logs caller identity and performs a `head-object` preflight so permission or region failures are self-explanatory in the build log (commit `7292addc`) |
| Canary | Co-resident consumer for viewer scenarios | Added to the single-viewer stage and mirrored into two- and three-viewer stages, enabling combined bitrate + viewer verification (commits `385c1c56`, `bcb85df8`) |

### Metrics Introduced

| Metric Name | Namespace | Type | Description |
|---|---|---|---|
| `IngestionIncomingBitrateKbps` | KinesisVideoSDKCanary | Kbps | Consumer-observed ingest bitrate, letting measured throughput be compared against the asset set's nominal bitrate (commit `1dad8ce2`) |

### Validation

Two runs on **2026-07-07** exercised the 500kbps set end to end. Logs confirm the fetch
resolved (`already present ... 4676 frames, skipping`), the master selected the set in both
the probe and replay paths, and streaming proceeded normally.

**TODO** — confirm whether `-1mbps` and `-5mbps` have been exercised, or only uploaded.

### Blocked On

- **Service-side adaptive bitrate change to be deployed.** Owner: service team. No canary
  work is pending on this — the scenarios are ready to enable once the service supports it.

### To Do When Unblocked

- Add cron entries for the six prod scenarios (and six gamma equivalents).
- Nodes running these scenarios need `CANARY_ASSET_BUCKET` and `CANARY_ASSET_PREFIX` set,
  and read access to the asset bucket.
- Document expected `IngestionIncomingBitrateKbps` ranges per asset set, for Phase 9.
- Confirm `-1mbps` and `-5mbps` run clean (only 500kbps has been exercised so far).

---

## Phase 14 — Long-Running Soak: Continuous Execution

**Date Started:** 8/27/2026 (commit `a65331ca`)
**Status:** In Progress — the execution modes are built; no standing soak is scheduled yet

Short 153s runs catch connection-establishment bugs but cannot catch slow leaks, gradual
drift, or anything that only appears after hours of streaming. This phase makes every
canary component capable of running until explicitly killed.

### Features Delivered

| Category | Feature | Details |
|---|---|---|
| Canary | Genuinely unbounded master | `CANARY_CONTINUOUS=true` sets `sampleDuration = 0`, disabling the elapsed-time termination check in `sessionCleanupWait()`. This is an unbounded producer, not a very long fixed duration (commit `569974cf`) |
| Canary | Consumer run-until-killed mode | Consumer honors `CANARY_CONTINUOUS` and polls indefinitely instead of exiting after a fixed window (commit `f4e2bfe4`) |
| Canary | Continuous mode for the JS viewer | Viewer holds its session open indefinitely rather than running fixed join/leave cycles (commit `f7ff13ba`) |
| Canary | `SOAK_MODE` master switch | One flag implies `CANARY_CONTINUOUS` for all three components plus `CONSUMER_AUTO_REFRESH_CREDS`, and lifts the Jenkins master and whole-pipeline timeouts to a 30-day backstop. `DURATION_IN_SECONDS` is ignored by all three (commit `4351eb1e`) |
| Stability | Bounded consumer `ListFragments` | Scoped to a trailing window so the call doesn't grow without limit as the stream accumulates fragments over hours (commit `8429763b`) |
| Stability | Chrome process and shared-memory cleanup | Reap orphaned Chrome processes, clean `/dev/shm`, and match leading-dot Chromium temp dirs. Over a long run these accumulate until the viewer node exhausts memory or disk (commits `b26559dc`, `97bf7744`) |

### First Real Soak: 2026-09-03, 16.6 h

Written up in [soak-2026-09-03-findings.md](soak-2026-09-03-findings.md). Ingest held (continuous
apart from a structural 1.17 % lost to hourly master reconnects); egress produced no verdict at
all, because the verifier's fixed cost exceeded its timeout and it was killed 18 of 19 times.
That produced ten fixes, including the reference-video cache that made continuous verification
affordable, OCR outlier rejection, and a viewer no-video watchdog. Read that document before
scheduling the next soak — in particular §7 on why `DURATION_IN_SECONDS` must be left at its
default, and §2.2, which is an escalation candidate rather than a canary bug.

### Remaining Work

- Establish a standing soak schedule and target duration (24h, 7d, continuous?).
- Confirm the full pipeline survives a multi-day run now that Phases 15 and 16 have landed.
- Re-run after deploying the reference cache: `SoakSegmentSkipped` is not meaningful until then.

---

## Phase 15 — Long-Running Soak: Credential Lifetime

**Date Started:** 8/20/2026 (commit `f540cad7`)
**Date Completed:** 8/31/2026 (commit `dbffb484`)
**Status:** Done

Making the components unbounded exposed that the real constraint on a long run is
credential lifetime, not streaming. Static STS sessions expire mid-run, and role-chained
nodes such as the Pi are capped at 1 hour, so every component needed credentials that
refresh themselves.

### Features Delivered

| Category | Feature | Details |
|---|---|---|
| Master | Runtime IoT credential provider | `USE_IOT_CREDENTIALS` feeds the master the auto-refreshing IoT provider instead of static STS, required for any run past the 1 hour role-chaining cap (commit `f540cad7`) |
| Master | Dedicated `rpi5-canary-kvs_role_alias` | A role alias vending a KVS-capable role directly, distinct from the assume-only bootstrap alias the device credential helper uses (commit `279dafad`) |
| Master | IoT provider keyed on device thing name | The thing name is a device identity, not the channel name; conflating them yielded auth failures (commit `1b7f9879`) |
| Master | `IotBackedCredentialsProvider` for CloudWatch clients | The master's own CloudWatch clients held static STS creds and stopped publishing metrics at the 1 hour mark, so a soak run went blind while still streaming. This was the last component still on fixed-lifetime credentials (commit `dbffb484`) |
| Consumer | Auto-refreshing assume-role credentials | Consumer re-assumes its role instead of holding one fixed-lifetime session, so it survives past expiry. Wired through the runners as `CONSUMER_AUTO_REFRESH_CREDS` (commits `baa59f9c`, `a65331ca`) |
| Runners | `STS_FETCH_NODE_LABEL` | Mints the shared assume-role session on a nominated EC2 node rather than the master, so consumer and viewer get credentials valid for a full run even when the master is a role-chained Pi (commit `a9513b4f`) |
| Safety | Static-creds default restored | Runtime IoT selection had regressed the default path; static credentials remain the default so non-soak scenarios are unaffected (commit `694d5681`) |

Reference: `docs/iot-credential-rotation.md` covers the IAM changes the IoT path requires.

---

## Phase 16 — Long-Running Soak: Continuous Video Verification

**Date Started:** 8/27/2026 (commit `6c0e17cd`)
**Date Completed:** 8/31/2026 (commit `70244a0f`)
**Status:** Done

A soak run that streams for days is worthless if nobody checks the media is still correct.
The short scenarios verify once at the end via GetClip, which does not translate to an
unbounded run. This phase built verification that runs continuously alongside the stream.

The approach changed mid-phase. The first cut sampled — a 156s GetClip probe every 15
minutes, roughly 17% coverage. That was replaced with full-coverage continuous
verification, so no window of ingested media goes unchecked.

### Features Delivered

| Category | Feature | Details |
|---|---|---|
| Verification | Periodic soak verification (initial approach) | `verify.py` SSIM run on an interval during the soak rather than only at the end, non-blocking so a verification failure does not tear down the run (commits `6c0e17cd`, `349998ea`) |
| Verification | `SoakStreamVerifier` — GetMedia segmenting | Pulls the stream via GetMedia, pipes it into ffmpeg which splits it into 60s mp4 segments (`-c:v copy`, no transcode), and a single-threaded worker verifies every finished segment with `verify.py`. Every minute of ingested media is content-checked, with no sampling gaps (commit `70244a0f`) |
| Verification | Non-blocking by construction | The pull thread does blocking I/O only; ffmpeg and `verify.py` run as `nice-19` subprocesses; the worker is fixed-delay and single-threaded so verifications never overlap. The `ListFragments` and heartbeat threads are untouched (commit `70244a0f`) |
| Verification | Backpressure instead of disk exhaustion | When verification falls behind, the oldest pending segments are skipped and counted rather than accumulating until the disk fills (commit `70244a0f`) |
| Verification | Reconnect and restart safety | GetMedia reconnects with backoff on EOF, re-resolving the endpoint and picking up auto-refreshed credentials. Segment numbering is epoch-seeded so restarts never collide with pending files (commit `70244a0f`) |
| Verification | Frame-timestamp drift measurement | Measured alongside SSIM to catch gradual A/V or pacing drift that per-frame similarity alone will not reveal. Drift re-anchors on source-frame counter wrap so short segments spanning a loop boundary do not report false drift (commits `a2a2ae61`, `70244a0f`) |
| Verification | Duration-scaled thresholds | `verify.py` duration and frame-count thresholds scale with `--expected-duration`, reducing to the original fixed thresholds at the default ~156s so short scenarios behave exactly as before (commit `70244a0f`) |
| Stability | ffmpeg on the consumer | Required for the segmenting decodability probe (commit `4bb368ab`) |

### Metrics Introduced

| Metric Name | Namespace | Type | Description |
|---|---|---|---|
| `SoakVideoDecodable` | KinesisVideoSDKCanary | Count (0/1) | Emitted per verified 60s segment, so decodability is tracked continuously across the whole soak |
| `SoakSegmentSkipped` | KinesisVideoSDKCanary | Count | Segments dropped under backpressure, so sampling gaps are visible rather than silent |

Reference: `docs/verification-matrix.md` (commit `fd9ad91c`) records which job triggers
which check.

---

## Phase 17 — Long-Running Soak: Self-Recovery

**Status:** Not Started

An unbounded run has no natural restart point. The short scenarios rely on cron to fire the
next run, which is exactly what does not happen for a job intended to run forever — if a
soak dies, nothing brings it back, and coverage silently stops until someone notices.

Today `SOAK_MODE` only lifts the Jenkins timeouts to a 30-day backstop. There is no
detection of a dead soak and no automatic restart.

### Scope

- **Liveness detection.** Decide the signal that means "the soak is down." Candidates
  already emitted: `MasterStreamingAvailability` and `ViewerStreamingAvailability`
  heartbeats (every 20s), `PipelineKeepAlive` stage checkpoints, `FragmentReceived`
  continuity, and now `SoakVideoDecodable` per segment.
- **Automatic restart.** Have Jenkins relaunch the soak job when liveness is lost. Note
  Phase 5 deliberately removed self-rescheduling in favour of cron because it grew an
  unbounded build queue, so this needs a bounded mechanism rather than a revival of that
  pattern.
- **Distinguish crash from intentional stop**, so a deliberate teardown is not fought by
  the watchdog.
- **Restart budget and alerting.** Cap restarts per interval and surface repeated restarts
  rather than masking a persistent failure behind an endless restart loop.
- **Continuity across restarts.** Confirm segment numbering, verification state, and
  metric labels survive a restart cleanly — the epoch-seeded segment numbering from
  Phase 16 was built with this in mind.

### Open Questions

- Jenkins-native (a watchdog job on a schedule) or external (a CloudWatch alarm triggering
  a rebuild)?
- Does the watchdog restart the whole pipeline or only the failed component?
- Interaction with Phase 9 alarms: should a restart suppress the page, or page anyway?

---

## Phase 18 — JS Master with JS Viewer

**Status:** Not Started

Every existing scenario uses the C SDK as master. This phase would add a JS master paired
with a JS viewer, covering the browser-to-browser ingest path.

Nothing delivered. **TODO** — scope, target scenarios, and priority relative to Phase 19.

---

## Phase 19 — Android / iOS Master

**Status:** Not Started

Mobile SDKs as master, with JS, Android, and iOS viewers. Nothing delivered.

The repo contains `android-webrtc/URLVideoCapturer`, an Android library for capturing video
from a URL source. **TODO** — confirm whether this is intended groundwork for this phase or
unrelated.

---

## Corrections to apply to existing sections

These are inconsistencies in the current doc, listed separately since Phases 1–7 are not
rewritten above.

### 1. Phase numbering

Two numbering changes, both reflected in the rewritten tables above.

**Phase 8 heading collision.** The trailing empty heading `Phase 8 Raspberry Pi Test`
conflicts with Phase 8 in the Milestones table (Investigation SOP). **Renumber it to
Phase 10** and replace with the Phase 10 section above.

**Phases 11–16 reordered by status.** The original doc numbered JS Master as Phase 12,
which placed an unstarted phase in the middle of delivered work. Phases now run
completed → in-flight → unstarted, so soak sits after the three completed phases that
followed it chronologically, and the two phases with nothing delivered sit last:

| Milestone | Was | Now | Status |
|---|---|---|---|
| TWCC congestion-control coverage | 13 | **11** | Done |
| Live media sources (GStreamer) | 14 | **12** | Done |
| Bitrate variant coverage | 15 | **13** | Done — blocked on service side |
| Long-running / soak canary | 11 | **14–17** | Split into four phases; see below |
| JS Master with JS Viewer | 12 | **18** | Not Started |
| Android / iOS as Master | unnumbered in original | **19** | Not Started — last |

**Soak split into four phases.** The original single "Long Running Canary (24/7)" milestone
bundled four separable concerns with different states, which made the whole thing read as
one unfinished block:

| Phase | Concern | Status |
|---|---|---|
| 14 | Continuous execution — unbounded master, consumer, viewer | In Progress |
| 15 | Credential lifetime — auto-refreshing creds for all components | Done |
| 16 | Continuous video verification — full-coverage segment checking | Done |
| 17 | Self-recovery — detect a dead soak and restart it | Not Started |

Note that phase numbers no longer track start dates in this range: soak (Phases 14–17) began
2026-08-27, after TWCC (Phase 11, 2026-08-13) and live media sources (Phase 12,
2026-07-27). The Commit Ranges table below records actual dates.

Nothing references the old Phase 12 number, since that work never started.

### 2. Completion dates disagree between the table and section headers

| Phase | Milestones table | Section header | Use |
|---|---|---|---|
| 5 | 6/21/2026 | Jun 10, 2026 | **TODO** — confirm which |
| 6 | 6/30/2026 | Jun 29, 2026 | **TODO** — confirm which |
| 7 | 6/30/2026 | Jun 29, 2026 | **TODO** — confirm which |

### 3. Prod region

The dashboard section lists prod as **PDX / us-west-2** (confirmed correct), but all eight
entries in `jobs/cron/prod_storage_cron.txt` set `AWS_DEFAULT_REGION=us-east-2`, and the
Phase 6 section also states us-east-2 for `VOMasterMixedViewer`.

If prod runs emit metrics in us-east-2, a us-west-2 dashboard will not display them.
**Action:** reconcile the cron files against the intended region, and correct the Phase 6
section.

### 4. `VOMasterMixedViewer` phase attribution

Scenarios table says "Phase Added 4"; the detailed section says Phase 6. **Phase 6 is
correct** — fixed in the rewritten table above.

### 5. Scenario durations

Phase 1 and 2 sections state 600s / 10 min for `StorageWithViewer`, `StorageTwoViewers`,
and `StorageThreeViewers`; the Scenarios table says 153s. Both are true at different
times. **Suggested fix:** add a line to each phase section noting the duration as
delivered, and state that the Scenarios table reflects current configuration.

### 6. Phase 2 title

Heading reads "WebRTC C Master and Three JS Viewers"; the milestone says "Multiple JS
Viewers." Since the phase delivered both two- and three-viewer scenarios, **use "Multiple."**

### 7. Commit Ranges table

Covers only Phases 5–7. Anchors now recoverable from git for the newer phases:

| Phase | Start | Notes |
|---|---|---|
| 10 | `f35bef26` (2026-07-23) | Through first green run 2026-07-26 |
| 12 | `f61ea722` (2026-07-27) | Latest `b5a309b7` (2026-08-18) |
| 11 | `af706aa3` (2026-08-13) | Latest `994329db` (2026-08-19) |
| 14 | `a65331ca` (2026-08-27) | Continuous execution modes |
| 15 | `f540cad7` (2026-08-20) | Through `dbffb484` (2026-08-31) |
| 16 | `6c0e17cd` (2026-08-27) | Through `70244a0f` (2026-08-31), plus `fd9ad91c` docs |

Phases 1–4 still have no commit ranges recorded.

### 8. Stale metric coverage claim

`docs/metric-coverage-mapping.md` lists `Active Viewers Per Session` as the one
unimplemented metric. It was implemented on 2026-07-09 (commit `799fab1a`, a JS viewer
heartbeat). That file predates the commit and should be refreshed — its summary counts are
also stale relative to the metrics added in Phases 11 and 13.

---

## Consolidated backlog

Open items gathered from the phases above, for the "what's left" view.

### Blocked on others — no canary work pending

1. **Phase 13 (bitrate)** — waiting on the **service-side adaptive bitrate change to be
   deployed**. The canary scenarios are built and validated; enabling them before the
   service supports adaptive bitrate would produce no actionable signal. Owner: service
   team. Once deployed, see "To Do When Unblocked" in Phase 13.

### Blocking continuous coverage — canary work

2. **Phase 12** — make the camera scenarios deployable: substitute the real gamma
   control-plane URI, commit the untracked `gamma_camera_cron.txt`, register the two
   `Camera*` labels in `CanaryConstants`, repoint `GIT_HASH` off `rpi5-sample`, and enable
   presence-mode verification.
3. **Phase 14 (soak execution)** — set a standing soak schedule and target duration, and
   confirm the full pipeline survives a multi-day run now that credential lifetime
   (Phase 15) and continuous verification (Phase 16) have landed.
4. **Phase 17 (soak self-recovery)** — nothing built. Without liveness detection and
   automatic restart, a dead soak stops producing coverage silently. Needs a design
   decision first: Jenkins-native watchdog versus alarm-triggered rebuild.
5. **Phase 11** — commit the pending `twcc_cron.txt` changes and merge `twcc-canary` to
   mainline; the cron jobs pin `GIT_HASH=twcc-canary`.

### Blocking alarms (Phase 9)

6. Choose metrics, thresholds, and evaluation periods that tolerate sparse short runs.
7. Decide prod-only versus prod-and-gamma paging.
8. Move the per-profile expected TWCC bitrates out of the cron header into a durable
   location, and document expected ranges per bitrate asset set.

### Correctness and hygiene

9. Reconcile the prod region discrepancy (us-west-2 versus us-east-2 in cron).
10. Pin the WebRTC SDK `GIT_TAG` in `CMakeLists.txt` — it currently tracks `develop`, which
    defeats build caching and makes runs non-reproducible.
11. Add `Rpi5StoragePeriodic` to the consumer's `CanaryConstants` so Pi runs stop borrowing
    the `StoragePeriodic` label.
12. Wrap `cert_setup.sh` in `if (params.USE_IOT)`.
13. Refresh `docs/metric-coverage-mapping.md`.
14. Deduplicate `CANARY_DEFAULT_LOG_GROUP_NAME` and `CANARY_METADATA_SIZE` in `Include.h`
    (both defined twice, identical values).
15. `NO_LOOP_FRAMES=true` is inert on `StorageLowFps` — at 10 fps the 4676 frames are 467s
    of content but the run is bounded to 156s, so the no-loop branch never triggers.

### Hardware and access

16. Replace the Pi USB boot disk (two libcrypto corruption failures).
17. Scope the jump-host tunnel key to minimum required forwarding.
18. Complete senior engineer review of `docs/rpi5-security-review.md`.

### Unstarted phases

19. **Phase 18** — JS Master with JS Viewer: needs scoping.
20. **Phase 19** — Android / iOS master: needs scoping; clarify whether
    `android-webrtc/URLVideoCapturer` is groundwork.
