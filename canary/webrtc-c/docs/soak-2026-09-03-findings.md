# Soak Findings — 2026-09-03, 16.6 h Continuous Run

**What this run was for:** the first `SOAK_MODE=true` run long enough to say anything about
continuous operation. Every component ran unbounded (master, consumer with periodic
verification, viewer recycling 40 min segments) against the storage path.

**Headline:** ingest held up; the run's *measurement* did not. Ingest was continuous apart from
a structural 1.17% lost to hourly master reconnects. Egress produced **no verdict at all** for
the whole 16.6 h — not because it failed, but because the verifier could not finish inside its
timeout and was killed 18 times out of 19. That is the finding that mattered most, and it is
the reason this run generated ten fixes rather than a green tick.

---

## 1. What the run measured

| | Result | Notes |
|---|---|---|
| Ingest continuity | continuous except reconnect gaps | `FragmentReceived`, `PersistenceStreamingAvailability` = 1.0 throughout |
| Structural ingest downtime | **1.17 %** of wall time | 17 master reconnects × ~40 s each |
| Egress coverage (viewer attached and watching) | **75.6 %** | the rest was spent in verification and segment turnaround, not watching |
| Egress verdicts produced | **0 of 19 segments** | 18 killed at the 600 s verify timeout, mid-SSIM |
| Consumer-side segments verified | 858 | one per 60 s segment |
| Segments skipped for lack of a free verifier | 141 (**16.4 %**) | capacity, not media — see §3 |
| Segments reported undecodable | 46 (5.4 %) | 35 were reconnect-boundary artefacts; real rate ~1.3 % |

The ingest numbers are trustworthy. The egress numbers are a measurement of the harness.

---

## 2. Structural gaps — expected, documented, not alarmable

Two gaps recur on every soak and must not be turned into pages. Both are in
[alarm-sop.md](alarm-sop.md) §7 as well, because that is where someone will look at 3 a.m.

### 2.1 Hourly master reconnect: ~40 s, 1.17 % of the run

The master reconnects roughly hourly (credential/session lifecycle). Each reconnect costs about
40 s during which no fragment is ingested. Over 17 reconnects that is 1.17 % of wall time. Any
ingest alarm with a period under ~5 min will see it, which is why the ingest alarms are
SUM-over-5-min rather than per-datapoint.

It also has a second-order effect worth knowing: each reconnect reseeds the ffmpeg segment
muxer's `-segment_start_number` to the current epoch, so the dying generation's truncated tail
stops being "the newest segment" and becomes eligible for verification. Two such segments per
reconnect were being scored as undecodable. `SoakSegmentBoundaryDiscarded` now counts and
discards them; that is 35 of the 46 zeros above.

### 2.2 ~30 s of no video after the viewer reconnects — **escalation candidate**

After the viewer reconnects, video stays unavailable for roughly 30 s. Across the run this
correlated **10 out of 10** times, in a tight band of **+9 s to +31 s** after the reconnect.

This is not obviously ours. The viewer completes signalling, negotiates audio+video, and reaches
`connected`; the master is ingesting throughout. A consistent tens-of-seconds delay before the
first frame arrives on a freshly-negotiated peer connection points at the media-server fan-out,
not at the JS viewer. It is the milder relative of
[viewer-no-video-investigation.md](viewer-no-video-investigation.md), where the same shape never
recovered at all and the media server itself reported 0 % loss from the master while GetClip
returned the complete stream.

**Recommendation:** raise this with the KVS ingestion / media-server team together with the
existing no-video report, as one question — *how long after `JoinStorageSessionAsViewer` returns
should the first frame arrive, and what accounts for 9-31 s?* Do not tune it away canary-side
until that is answered; the 60 s watchdog threshold below is deliberately set *above* this window
so we do not paper over it.

---

## 3. Why egress produced nothing: fixed cost, not sampling

`verify.py` in `ssim` mode builds a reference video from the 4676 source H.264 frames before it
compares anything. Measured locally: **18.0 s of a 34.0 s run** on a 60 s segment — 53 % of the
work — and it was recomputed, identically, on every single invocation.

That is what starved both verifiers:

- **Viewer** (40 min segments): verification could not finish inside the 600 s timeout, so 18 of
  19 segments were killed mid-SSIM. Nothing was published, so no egress alarm could breach —
  a metric that is never emitted never breaches.
- **Consumer** (60 s segments, one arriving every 60 s): ~34 s locally is ~83 s on the node
  (~2.4× slower), so the worker could never catch up and shed 141 of 858 segments.

The fix is `--reference-cache`: the reference is a pure function of the source frames, so it is
built once and reused while it is newer than them. 34.0 s → **16.2 s (2.1×)**, i.e. ~40 s on the
node, inside the 60 s arrival rate. Sampling (`--max-samples`) was the other candidate and is
also in place, but it could not have fixed this on its own — the dominant cost was fixed, not
per-sample.

**Corollary for anyone reading `SoakSegmentSkipped`:** on a build without the cache that metric
measures the verifier, not the stream. Confirm the cache is deployed before believing it.

---

## 4. Defects this run exposed

Ten, in the order they were fixed. Every one of them was invisible until a run lasted long
enough to hit it.

| # | Defect | Why a soak found it |
|---|---|---|
| 1 | `verify.py` crashed in `os.remove` on a path it had not created | only reachable on the failure path, which a short run never takes |
| 2 | hard-failure paths exited without emitting JSON | the caller then had nothing to publish, so a crash looked like health |
| 3 | `chrome-headless.js` returned silently instead of publishing 0 | same class: silence where a datapoint was needed |
| 4 | verification wall time unbounded (`--max-samples`) | only bites when a segment is long |
| 5 | OCR misreads decided the verdict (see §5) | needs enough samples for a 1.7 % error to become certain |
| 6 | absolute-epoch PTS made reconnect-boundary segments undecodable | needs a reconnect |
| 7 | consumer log had no wall clock (`log4j.properties`) | fine for 156 s, unusable for correlating 16.6 h |
| 8 | master had no SIGTERM handler | only matters when something tries to stop it gracefully |
| 9 | no soak alarm set; skip metric not yet meaningful | §3 |
| 10 | this document, plus the viewer no-video watchdog (§6) | — |

---

## 5. OCR is the weakest link in the media verdict

Measured against ground truth (300 frames of the pristine source, where frame *N* is source frame
*N* by construction, so the correct answer is known):

| Outcome | Rate |
|---|---|
| correct | 92.0 % |
| unreadable — counted by `ocr_failures` | 3.0 % |
| wrong, out of range — counted by `ocr_failures` | 3.3 % |
| **wrong, in range, silent** | **1.7 %** |

`ocr_failures` sees 6.3 % and misses exactly the 1.7 % that changes a verdict. And because
`min_ssim` and `max_drift_seconds` are *extremes* over all samples, a per-sample error rate
becomes a per-verdict one: P(at least one silent misread) is 28.5 % at 20 samples, 63.5 % at 60,
86.7 % at 120, **98.2 % at 240**. At the sample cap, roughly 4 misreads per verification are
expected.

Concretely: on a clip rebuilt losslessly from the source frames — true `min_ssim` 1.0, true drift
0 — the verifier read `min_ssim` 0.0426 and max drift 33.3 s. Entirely OCR.

`reject_ocr_outliers` now discards counters that leave their level and come back, on the physical
argument that a stream cannot lose 13 s of content and then get it back. After it: `min_ssim`
0.9924, drift 0/0.

**Open items this leaves:**
- Do **not** alarm on the drift metrics yet. There is no trustworthy baseline until the filter has
  run a full clean soak.
- Revisit digit template matching as a *replacement* reader: measured 78 % correct with **0 wrong
  in 300**, i.e. it fails closed rather than lying. The 22 % refusal rate is why it is not in yet,
  but that is the right failure direction and residual filtering would become unnecessary.
- After a clean soak, decide whether to tighten `min_ssim > 0.03`. Deliberately not changed in the
  same commit that changed the OCR semantics.

---

## 6. New viewer defence: `ViewerSessionNoVideo`

The run had no way to notice a viewer that joined and then received nothing — every other viewer
metric is a verdict on a *finished* segment, and `ViewerStorageAvailability` only ever asked
whether the session was joined. A silent 40 min segment and a healthy one looked identical.

The viewer now watches inbound `framesDecoded` (not `hasActiveVideo`, which is a `readyState`
check that stays true after the media stops). After 60 s with no decoded frame it publishes
`ViewerSessionNoVideo=1` and `ViewerStreamingAvailability=0`, and — in soak mode only — ends the
segment so the recycle loop reconnects. Healthy segments publish an explicit 0, so the series is
continuous and an alarm on it never has to guess about missing data.

Bounded runs report but keep watching: there is no recycle loop to reconnect them, so cutting the
run short would only shorten the egress window it exists to measure.

The 60 s threshold has a floor and a ceiling from measurement, and clears both: the media server
reaps idle viewer sessions at ~60-66 s, and a healthy reconnect takes up to ~31 s to first frame
(§2.2). Anything under ~35 s would recycle healthy sessions. Override with
`VIEWER_NO_VIDEO_TIMEOUT_SECONDS`. Behaviour is pinned by `scripts/test-no-video-watchdog.js`,
which drives the state machine over timelines taken from these logs.

---

## 7. Operating a soak: `DURATION_IN_SECONDS`

**Do not set `DURATION_IN_SECONDS` on a soak job.** Leave it at its `156` default.

Under `SOAK_MODE=true` it is inert for all three components, and setting it to something
soak-shaped (the 2026-09-03 run carried `276`) reads like a duration that is being honoured when
nothing honours it. Traced through `storage_runner.groovy`:

- **Master** — `SOAK_MODE` sets `CANARY_CONTINUOUS=true`, so the binary runs with
  `sampleDuration=0` and never self-terminates; `CANARY_DURATION_IN_SECONDS` is not consulted.
- **Consumer** — `CANARY_DURATION_IN_SECONDS` is still derived from it
  (`DURATION_IN_SECONDS + 120`) but `CANARY_CONTINUOUS=true` overrides the fixed-duration exit.
- **Viewer** — each session is bounded by `VIEWER_SESSION_RECYCLE_SECONDS`, and
  `monitorConnection` uses `config.duration` (the recycle interval) rather than the
  `MASTER_DURATION` cap in continuous mode.
- **Jenkins timeouts** — both the master stage and the pipeline branch to a 30-day backstop
  instead of deriving from it.

It cannot simply be dropped from the parameter list: the runner calls
`params.DURATION_IN_SECONDS.toInteger()` on the non-soak paths, so the parameter must stay and
must stay parseable. The default is what makes it harmless. `VIEWER_SESSION_RECYCLE_SECONDS` is
the only duration knob that does anything on a soak.

---

## 8. Follow-ups

1. Escalate §2.2 to the KVS ingestion / media-server team alongside
   [viewer-no-video-investigation.md](viewer-no-video-investigation.md).
2. Deploy a build so the nodes pick up the reference cache, then re-run the soak and read
   `SoakSegmentSkipped` — it is only meaningful after that.
3. From that clean run, set thresholds for `FrameTimestampDriftSeconds` /
   `FrameTimestampDriftMaxSeconds` and decide on `min_ssim > 0.03`.
4. Phase 17 self-recovery watchdog (see [soak-self-recovery-design.md](soak-self-recovery-design.md));
   the master SIGTERM handler from defect 8 is its prerequisite.
5. Evaluate digit template matching as a replacement OCR reader (§5).
