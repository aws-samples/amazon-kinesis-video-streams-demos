# TWCC test on the Pi — adapting master (ingest path)

Manual runbook for exercising **Media Service TWCC feedback on the ingest path**
(C-SDK master → media service) using the Raspberry Pi as a **mid-path router**.

> **What this tests.** On the ingest path the media service is the *receiver*; its
> TWCC job is to emit correct transport-wide-cc RTCP **feedback**. The only way to
> see whether that feedback is good is to put an *adapting sender* on the other
> end and watch its encoder track a throttle. That adapting sender is the SDK
> GStreamer sample below — **not** the canary's `kvsWebrtcStorageSample`, which
> streams fixed pre-encoded frames and whose TWCC callback only logs.
>
> To test the media service's **own congestion-control algorithm**, that's the
> *egress* path (media service → viewer) — a browser on a throttled downlink, a
> separate setup. See the companion viewer scripts (TODO) / the source doc.

Why a mid-path router (not shaping the Pi's own NIC): if we shaped the master's
egress NIC, its TWCC send-timestamp would be stamped *after* the queue, hiding
queue delay from the estimator. Running the master in a namespace and shaping
the forwarding hop stamps the send time *before* the bottleneck — like a real
router. Your SSM/SSH/reverse-tunnel session stays on the unshaped interface.

## Files

| Script | Role |
|---|---|
| `build-twcc-sample.sh` | Clone SDK `@rr-extension`, lower encoder floor 384→100 kbps, build `kvsWebrtcStorageVideoOnlyMasterGstSample`. |
| `netns_router.sh` | Create the `kvsns` namespace + veth + NAT (the mid-path router). |
| `netns_profiles.sh` | Cycle GOOD→MEDIUM→CONGESTING→BAD→RECOVERING on the master's uplink. |
| `run-master.sh` | Run the master inside `kvsns`, teeing to a log. |
| `analyze-twcc-log.py` | Parse the log; print a per-profile bitrate / delayTrend table. |

## Prereqs / who runs what

- Run everything as your **sudo-user** (e.g. `yuqi`), **not** `jenkins` (the
  namespace needs root; `jenkins` is non-sudo by design).
- Copy this folder to the Pi, e.g.
  `scp -P 2222 -r scripts/twcc jenkins@localhost:/home/<you>/twcc` via the jump,
  or `git pull` on the Pi.
- Don't run this while the scheduled canary is mid-build — software x264enc will
  fight it for the 4 cores.
- **Credentials/endpoint for gamma are yours to provide** (export them before
  `run-master.sh`; see below).

## Steps

```bash
cd twcc
chmod +x *.sh

# 1. Build the adapting sample (~20-40 min; deps build from source)
./build-twcc-sample.sh
#   -> ~/twcc-sdk/build/samples/kvsWebrtcStorageVideoOnlyMasterGstSample

# 2. Bring the mid-path router up
sudo ./netns_router.sh up
sudo ip netns exec kvsns ping -c1 1.1.1.1          # sanity: namespace has internet

# 3. Export gamma creds + endpoint (whatever the sample needs to reach gamma)
export AWS_ACCESS_KEY_ID=...  AWS_SECRET_ACCESS_KEY=...  AWS_SESSION_TOKEN=...
export AWS_DEFAULT_REGION=us-west-2
export AWS_KVS_LOG_LEVEL=2                          # DEBUG — required for the BWE/TWCC log lines
# export CONTROL_PLANE_URI=...                      # if that's how you point at gamma

# 4. Start the master in the namespace (shell A)
BIN=~/twcc-sdk/build/samples/kvsWebrtcStorageVideoOnlyMasterGstSample \
  ./run-master.sh twccTestPi1 testsrc
#   log -> ~/twcc-logs/master-<ts>.log

# 5. Start the throttle cycle (shell B) and save its timeline
sudo LOSS=0 ./netns_profiles.sh start | tee ~/twcc-logs/throttle-$(date +%Y%m%d-%H%M%S).log
#   let it run ≥2 full cycles (GOOD..RECOVERING = ~100s/cycle at 20s/stage)

# 6. Stop (Ctrl+C both), then tear down
sudo ./netns_router.sh down

# 7. Analyze
./analyze-twcc-log.py ~/twcc-logs/master-<ts>.log ~/twcc-logs/throttle-<ts>.log
```

## What "TWCC works" looks like

- Header says **TWCC enabled** (remote advertised the extension). If it says
  *not advertised*, the media service didn't offer TWCC → nothing to test.
- In the per-profile table, mean `video kbps` **tracks each cap**: ~2.2 Mbps at
  GOOD, down toward the ~100 kbps floor at BAD, climbing back on RECOVERING.
- `delayTrend` is ~0 at GOOD and **rises** during CONGESTING/BAD (queue building)
  — that's the delay signal the media service's feedback carried and the sender
  reacted to.

## Log lines (for manual grep)

- `BWE: pktLoss=... delayTrend=... factor=... | video=<N> kbps` — sender adaptation (`Common.c:778`)
- `TWCC trendline: delayTrend=... queueDelay=... (n=...)` — SDK estimator (`Rtcp.c:405`)
- `TWCC enabled, ext id: N` / `TWCC not advertised by remote ...` (`PeerConnection.c:1512-1517`)

## Notes / gotchas

- `testsrc` = `videotestsrc pattern=ball` → `x264enc`. Fine for proving
  adaptation via the logs. The ball is mostly static, so *service-side* byte
  counters will read lower than the SDK's estimate; switch to a Big Buck Bunny
  `filesrc` later if you need those to match (see source doc).
- Floor 384→100 (`build-twcc-sample.sh`, `Samples.h:108`) is what lets the
  encoder actually reach the 250 kbps BAD profile.
- The namespace is not persistent — re-run `netns_router.sh up` after a reboot.
