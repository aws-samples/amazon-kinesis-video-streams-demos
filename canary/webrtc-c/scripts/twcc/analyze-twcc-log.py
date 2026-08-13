#!/usr/bin/env python3
"""
analyze-twcc-log.py - Prove TWCC adaptation from a master run log.

Parses the adapting master's log for the SDK's BWE lines:
    BWE: pktLoss=%.2f%% delayTrend=%.4f ms factor=%.2f | video=%llu kbps ...
        (samples/common/Common.c:778)
and, if you pass the throttle log too, buckets each BWE sample by the profile
that was active at its timestamp, printing a doc-style per-profile table:

    Profile | Cap | n | video kbps (mean/min/max) | delayTrend ms (mean/max) | pktLoss%

Success looks like: mean video kbps tracks each cap (~2.2 Mbps at GOOD down to
the floor at BAD), and delayTrend rises during CONGESTING/BAD.

Usage:
    ./analyze-twcc-log.py master.log [throttle.log]

Both logs are matched on HH:MM:SS, so run them on the same host/clock.
"""
import re
import sys

# `factor=` is present in the SDK sample's log line but omitted by the canary port,
# so it's optional here — this regex matches both.
BWE_RE = re.compile(
    r"BWE:\s*pktLoss=([-\d.]+)%\s*delayTrend=([-\d.]+)\s*ms\s*(?:factor=[-\d.]+\s*)?\|"
    r"\s*video=(\d+)\s*kbps"
)
# Profile line emitted by netns_profiles.sh:  [HH:MM:SS] GOOD -> bw=3mbit ...
PROF_RE = re.compile(r"\[(\d{2}:\d{2}:\d{2})\]\s+(\w+)\s*->\s*bw=(\S+)")
TIME_RE = re.compile(r"(\d{2}:\d{2}:\d{2})")


def mean(xs):
    return sum(xs) / len(xs) if xs else 0.0


def load_bwe(path):
    """Return list of (time_str|None, pktLoss, delayTrend, video_kbps)."""
    out = []
    with open(path, errors="replace") as f:
        for line in f:
            m = BWE_RE.search(line)
            if not m:
                continue
            t = TIME_RE.search(line)
            out.append((
                t.group(1) if t else None,
                float(m.group(1)), float(m.group(2)), int(m.group(3)),
            ))
    return out


def load_profiles(path):
    """Return sorted list of (time_str, label, cap) profile-change events."""
    events = []
    with open(path, errors="replace") as f:
        for line in f:
            m = PROF_RE.search(line)
            if m:
                events.append((m.group(1), m.group(2), m.group(3)))
    return events


def profile_at(time_str, events):
    """Latest profile whose start <= time_str (HH:MM:SS string compare)."""
    active = None
    for t, label, cap in events:
        if time_str is not None and t <= time_str:
            active = (label, cap)
        else:
            break
    return active


def twcc_health(path):
    txt = open(path, errors="replace").read()
    if "TWCC enabled, ext id" in txt:
        return "TWCC enabled (remote advertised the extension)"
    if "TWCC not advertised by remote" in txt:
        return "WARNING: TWCC NOT advertised by remote - the media service did not offer it"
    if "TWCC disabled by local configuration" in txt:
        return "WARNING: TWCC disabled by local configuration"
    return "note: no explicit TWCC enable/disable line found (check log level = 2/DEBUG)"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    master_log = sys.argv[1]
    throttle_log = sys.argv[2] if len(sys.argv) > 2 else None

    print(f"== {twcc_health(master_log)} ==\n")

    bwe = load_bwe(master_log)
    if not bwe:
        print("No 'BWE:' lines found. Was the sample built from rr-extension and run with "
              "AWS_KVS_LOG_LEVEL=2? (that line is samples/common/Common.c:778)")
        sys.exit(2)

    v = [b[3] for b in bwe]
    d = [b[2] for b in bwe]
    print(f"Overall: {len(bwe)} BWE samples | video kbps mean/min/max = "
          f"{mean(v):.0f}/{min(v)}/{max(v)} | delayTrend ms mean/max = {mean(d):.3f}/{max(d):.3f}\n")

    if not throttle_log:
        print("Pass the throttle log as arg 2 for the per-profile table.")
        return

    events = load_profiles(throttle_log)
    if not events:
        print("No profile lines found in throttle log; skipping per-profile table.")
        return

    # Bucket in first-seen profile order.
    buckets = {}
    order = []
    for t, loss, dt, vid in bwe:
        p = profile_at(t, events)
        if p is None:
            continue
        label, cap = p
        if label not in buckets:
            buckets[label] = {"cap": cap, "v": [], "d": [], "l": []}
            order.append(label)
        buckets[label]["v"].append(vid)
        buckets[label]["d"].append(dt)
        buckets[label]["l"].append(loss)

    hdr = f"{'Profile':<12}{'Cap':<9}{'n':>5}  {'video kbps mean/min/max':<26}{'delayTrend mean/max':<22}{'pktLoss%':>8}"
    print(hdr)
    print("-" * len(hdr))
    for label in order:
        b = buckets[label]
        vv, dd, ll = b["v"], b["d"], b["l"]
        vstr = f"{mean(vv):.0f}/{min(vv)}/{max(vv)}"
        dstr = f"{mean(dd):.3f}/{max(dd):.3f}"
        print(f"{label:<12}{b['cap']:<9}{len(vv):>5}  {vstr:<26}{dstr:<22}{mean(ll):>8.2f}")


if __name__ == "__main__":
    main()
