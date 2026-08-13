#!/bin/bash
# =============================================================================
# netns_profiles.sh - Cycle full network PROFILES (bandwidth + loss + latency +
# jitter) on the netns master's uplink. This is the shaper used for the
# Good/Medium/Bad TWCC characterization.
#
# HOW IT SHAPES: netns_router.sh must be UP first. The master's uplink arrives
# in the root ns as INGRESS on veth-host. tc can't shape ingress directly, so we
# mirror it onto a dedicated IFB (Intermediate Functional Block) device and
# apply a single classless `netem` on the IFB's egress. Because veth-host only
# ever carries the namespace's traffic, this shapes ONLY the master - your SSM
# session (on wlan0/eth0) is untouched.
#
# Cycle (descend, then recover): GOOD -> MEDIUM -> CONGESTING -> BAD -> RECOVERING -> (loop)
#
# Usage:
#   sudo ./netns_profiles.sh start            # cycle all profiles, DURATION s each, forever (Ctrl+C cleans up)
#   sudo ./netns_profiles.sh set <name>       # hold one profile (good|medium|congesting|bad|recovering)
#   sudo ./netns_profiles.sh stop
#   sudo ./netns_profiles.sh status
#
# For TWCC testing we run with LOSS=0 (loss is decode damage, not congestion):
#   sudo LOSS=0 ./netns_profiles.sh start
#
# Env overrides:
#   DURATION  seconds per stage (default 20)
#   LIMIT     netem queue depth in pkts (default 1000; bounds max queue delay)
#   CORR      jitter correlation; HIGHER = less packet reordering (default 25%)
#   LOSS      if set (e.g. LOSS=0), overrides EVERY profile's loss %
#   HOSTVETH  (default veth-host)
# =============================================================================

HOSTVETH=${HOSTVETH:-veth-host}   # device whose ingress we mirror (the master's uplink)
IFB=ifb-kvs                       # intermediate device we attach netem to
DURATION=${DURATION:-20}          # seconds held per profile
LIMIT=${LIMIT:-1000}              # netem queue depth (packets)
CORR=${CORR:-25%}                 # jitter correlation (higher -> fewer reorders)

# Capture an optional loss override BEFORE the read loop reuses the name "LOSS".
if [ -n "${LOSS+x}" ]; then LOSS_OVERRIDE=$LOSS; else LOSS_OVERRIDE=""; fi

# Profile table:  LABEL  BW  LOSS%  DELAYms  JITTERms
# NOTE: jitter is < base delay on every stage, so netem reordering stays minimal.
PROFILES=(
  "GOOD        3mbit    0    20   5"
  "MEDIUM      1mbit    1    50   15"
  "CONGESTING  500kbit  1.5  75   20"
  "BAD         250kbit  2    100  30"
  "RECOVERING  1500kbit 0.5  35   10"
)

# plumb: create the IFB, mirror veth-host ingress onto it, attach an initial netem.
plumb() {
  modprobe ifb 2>/dev/null
  ip link add "$IFB" type ifb 2>/dev/null || true
  ip link set "$IFB" up
  tc qdisc del dev "$HOSTVETH" ingress 2>/dev/null
  tc qdisc add dev "$HOSTVETH" handle ffff: ingress
  tc filter add dev "$HOSTVETH" parent ffff: protocol all u32 match u32 0 0 \
     action mirred egress redirect dev "$IFB"
  tc qdisc del dev "$IFB" root 2>/dev/null
  tc qdisc add dev "$IFB" root handle 1: netem rate 10mbit limit "$LIMIT"
}

# apply BW LOSS DELAY JITTER  -> rewrite the netem line with all four knobs.
apply() {
  local loss=$2
  [ -n "$LOSS_OVERRIDE" ] && loss=$LOSS_OVERRIDE
  tc qdisc change dev "$IFB" root handle 1: netem \
     delay "${3}ms" "${4}ms" "$CORR" distribution normal \
     loss "${loss}%" \
     rate "$1" limit "$LIMIT"
  echo "$loss"
}

teardown() {
  tc qdisc del dev "$HOSTVETH" ingress 2>/dev/null
  tc qdisc del dev "$IFB" root 2>/dev/null
  ip link set "$IFB" down 2>/dev/null
  ip link del "$IFB" 2>/dev/null
  echo "PROFILES OFF (ifb removed, $HOSTVETH ingress cleared)"
}

find_profile() {
  local want=${1,,}
  for p in "${PROFILES[@]}"; do
    read -r LABEL BW LOSS DELAY JITTER <<< "$p"
    if [ "${LABEL,,}" = "$want" ]; then echo "$BW $LOSS $DELAY $JITTER"; return 0; fi
  done
  return 1
}

case "${1:-start}" in
  start)
    plumb
    trap 'echo; teardown; exit 0' INT TERM
    echo "Cycling profiles via $IFB ($HOSTVETH ingress), ${DURATION}s/stage, CORR=$CORR${LOSS_OVERRIDE:+ LOSS_OVERRIDE=${LOSS_OVERRIDE}%}. Ctrl+C to stop."
    while true; do
      for p in "${PROFILES[@]}"; do
        read -r LABEL BW LOSS DELAY JITTER <<< "$p"
        eff=$(apply "$BW" "$LOSS" "$DELAY" "$JITTER")
        echo "[$(date +%H:%M:%S)] $LABEL -> bw=$BW loss=${eff}% delay=${DELAY}ms jitter=${JITTER}ms"
        sleep "$DURATION"
      done
    done
    ;;
  set)
    vals=$(find_profile "${2:-good}") || { echo "unknown profile '${2}'. valid: good|medium|congesting|bad|recovering"; exit 1; }
    read -r BW LOSS DELAY JITTER <<< "$vals"
    plumb
    eff=$(apply "$BW" "$LOSS" "$DELAY" "$JITTER")
    echo "[$(date +%H:%M:%S)] HOLD ${2} -> bw=$BW loss=${eff}% delay=${DELAY}ms jitter=${JITTER}ms. Run '$0 stop' to clear."
    ;;
  stop) teardown ;;
  status)
    echo "--- $HOSTVETH ingress ---"; tc -s qdisc show dev "$HOSTVETH" ingress 2>/dev/null
    echo "--- $IFB ---"; tc -s qdisc show dev "$IFB" 2>/dev/null ;;
  *) echo "usage: $0 {start|stop|status|set <good|medium|congesting|bad|recovering>}"; exit 1 ;;
esac
