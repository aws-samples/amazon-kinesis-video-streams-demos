#!/bin/bash
# =============================================================================
# twcc-net - consolidated root-owned wrapper for the TWCC network-shaping canary.
#
# INSTALL: copy to /usr/local/bin/twcc-net, owned root:root mode 0755.
# GRANT:   /etc/sudoers.d/twcc-canary ->  jenkins ALL=(root) NOPASSWD: /usr/local/bin/twcc-net
#
# This is the ONLY thing the (non-sudo) jenkins user is allowed to run as root.
# All privileged operations (netns / veth / NAT / tc) are FIXED here with a
# hard-coded namespace + interface names. The `run` subcommand drops to the
# jenkins user (sudo -u jenkins) BEFORE exec'ing the caller's command, so the
# command argument is NOT a root-escalation vector.
#
# Subcommands:
#   up                              create kvsns netns + veth + NAT (mid-path router)
#   down                            tear everything down (idempotent; also stops throttle)
#   throttle-start [stageSecs] [loss]   background the GOOD..RECOVERING netem cycler
#   throttle-hold PROFILE [loss]    apply ONE steady profile (per-condition canary)
#   throttle-stop                   stop the cycler/shaping + remove IFB
#   run --cwd DIR --env-file F -- CMD [ARGS...]   run CMD as jenkins inside kvsns
#   status                          show current state
#
# Mirrors the manual scripts netns_router.sh + netns_profiles.sh, distilled into
# one stable root-owned file (the repo scripts are jenkins-writable and must not
# be the sudoers target).
# =============================================================================
set -euo pipefail

NS=kvsns
HOSTVETH=veth-host
NSVETH=veth-ns
HOSTIP=10.200.0.1
NSIP=10.200.0.2
SUBNET=10.200.0.0/24
IFB=ifb-kvs
RUNUSER=jenkins
PIDFILE=/run/twcc-throttle.pid
THROTTLE_LOG=/var/log/twcc-throttle.log
# State file: the currently-applied netem bandwidth cap in kbps. The canary master
# reads this each metrics interval and emits it as the AppliedBandwidthKbps CloudWatch
# metric (so a dashboard can overlay the cap vs the encoder bitrate). Cleared on stop.
CURRENT_KBPS_FILE=/run/twcc-current-kbps

# Convert a tc rate token (e.g. "3mbit", "500kbit") to integer kbps.
bw_to_kbps() {
  local n; n=$(printf '%s' "$1" | grep -oE '^[0-9]+')
  case "$1" in
    *mbit) echo $(( ${n:-0} * 1000 )) ;;
    *kbit) echo "${n:-0}" ;;
    *)     echo 0 ;;
  esac
}

# Publish the applied cap (kbps) to the state file, world-readable so the (non-root)
# canary master can read it. Called on every stage change.
write_current_kbps() {
  local k; k=$(bw_to_kbps "$1")
  echo "$k" > "$CURRENT_KBPS_FILE" 2>/dev/null && chmod 644 "$CURRENT_KBPS_FILE" 2>/dev/null || true
}

# Sleep until the next wall-clock multiple of $1 seconds. With stage=60 this pins
# every stage transition to :00 of a minute, so each transition aligns with
# CloudWatch's 1-minute metric buckets and a stage fills exactly one bucket (no
# straddle-blending of the Average). Self-correcting each call, so sleep drift
# can't accumulate. Falls back to a plain sleep for a non-positive-integer stage.
sleep_aligned() {
  local s="$1" now
  case "$s" in ''|*[!0-9]*) sleep "${s:-20}"; return;; esac
  [ "$s" -gt 0 ] || { sleep 1; return; }
  now=$(date +%s)
  sleep $(( (now / s + 1) * s - now ))
}
DNS=${TWCC_DNS:-8.8.8.8}
LIMIT=1000
CORR=25%
WAN=$(ip route get 1.1.1.1 2>/dev/null | awk '{print $5; exit}')

# Profile table (same as netns_profiles.sh):  LABEL  BW  LOSS%  DELAYms  JITTERms
PROFILES=(
  "GOOD        3mbit    0    20   5"
  "MEDIUM      1mbit    1    50   15"
  "CONGESTING  500kbit  1.5  75   20"
  "BAD         250kbit  2    100  30"
  "RECOVERING  1500kbit 0.5  35   10"
)

up() {
  [ -n "$WAN" ] || { echo "twcc-net: could not detect WAN interface" >&2; exit 1; }
  ip netns add "$NS" 2>/dev/null || true
  ip link add "$NSVETH" type veth peer name "$HOSTVETH" 2>/dev/null || true
  ip link set "$NSVETH" netns "$NS" 2>/dev/null || true
  ip addr add "$HOSTIP/24" dev "$HOSTVETH" 2>/dev/null || true
  ip link set "$HOSTVETH" up
  ip netns exec "$NS" ip addr add "$NSIP/24" dev "$NSVETH" 2>/dev/null || true
  ip netns exec "$NS" ip link set "$NSVETH" up
  ip netns exec "$NS" ip link set lo up
  ip netns exec "$NS" ip route add default via "$HOSTIP" 2>/dev/null || true
  mkdir -p "/etc/netns/$NS"
  printf "nameserver %s\nnameserver 1.1.1.1\n" "$DNS" > "/etc/netns/$NS/resolv.conf"
  sysctl -qw net.ipv4.ip_forward=1
  sysctl -qw "net.ipv4.conf.$WAN.rp_filter=2" 2>/dev/null || true
  sysctl -qw "net.ipv4.conf.$HOSTVETH.rp_filter=2" 2>/dev/null || true
  iptables -t nat -C POSTROUTING -s "$SUBNET" -o "$WAN" -j MASQUERADE 2>/dev/null \
    || iptables -t nat -A POSTROUTING -s "$SUBNET" -o "$WAN" -j MASQUERADE
  iptables -C FORWARD -i "$HOSTVETH" -o "$WAN" -j ACCEPT 2>/dev/null \
    || iptables -A FORWARD -i "$HOSTVETH" -o "$WAN" -j ACCEPT
  iptables -C FORWARD -i "$WAN" -o "$HOSTVETH" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null \
    || iptables -A FORWARD -i "$WAN" -o "$HOSTVETH" -m state --state RELATED,ESTABLISHED -j ACCEPT
  echo "twcc-net up: netns=$NS ($NSIP) -> $HOSTVETH/$HOSTIP -> NAT -> $WAN, DNS=$DNS"
}

down() {
  throttle_stop || true
  iptables -t nat -D POSTROUTING -s "$SUBNET" -o "$WAN" -j MASQUERADE 2>/dev/null || true
  iptables -D FORWARD -i "$HOSTVETH" -o "$WAN" -j ACCEPT 2>/dev/null || true
  iptables -D FORWARD -i "$WAN" -o "$HOSTVETH" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true
  ip netns del "$NS" 2>/dev/null || true
  ip link del "$HOSTVETH" 2>/dev/null || true
  rm -rf "/etc/netns/$NS"
  echo "twcc-net down"
}

# Mirror veth-host ingress onto an IFB and attach a benign starting netem.
plumb_ifb() {
  modprobe ifb 2>/dev/null || true
  ip link add "$IFB" type ifb 2>/dev/null || true
  ip link set "$IFB" up
  tc qdisc del dev "$HOSTVETH" ingress 2>/dev/null || true
  tc qdisc add dev "$HOSTVETH" handle ffff: ingress
  tc filter add dev "$HOSTVETH" parent ffff: protocol all u32 match u32 0 0 \
     action mirred egress redirect dev "$IFB"
  tc qdisc del dev "$IFB" root 2>/dev/null || true
  tc qdisc add dev "$IFB" root handle 1: netem rate 10mbit limit "$LIMIT"
}

throttle_start() {
  local stage="${1:-20}" lossov="${2:-}"
  plumb_ifb
  # Detach the cycler into its own session so it survives this sudo invocation.
  setsid "$0" __throttle_loop "$stage" "$lossov" >>"$THROTTLE_LOG" 2>&1 &
  echo $! > "$PIDFILE"
  echo "twcc-net throttle started (pid $(cat "$PIDFILE"), ${stage}s/stage, loss=${lossov:-per-profile})"
}

__throttle_loop() {
  local stage="$1" lossov="${2:-}"
  trap 'exit 0' TERM INT
  # Align the first transition to a wall-clock boundary so every stage fills whole
  # CloudWatch minute buckets (avoids Average-blending across stage boundaries).
  sleep_aligned "$stage"
  while true; do
    for p in "${PROFILES[@]}"; do
      read -r L BW LOSS D J <<< "$p"
      [ -n "$lossov" ] && LOSS="$lossov"
      tc qdisc change dev "$IFB" root handle 1: netem \
         delay "${D}ms" "${J}ms" "$CORR" distribution normal \
         loss "${LOSS}%" rate "$BW" limit "$LIMIT" || exit 0
      echo "[$(date +%H:%M:%S)] $L -> bw=$BW loss=${LOSS}% delay=${D}ms jitter=${J}ms"
      write_current_kbps "$BW"
      sleep_aligned "$stage"
    done
  done
}

throttle_stop() {
  if [ -f "$PIDFILE" ]; then
    kill "$(cat "$PIDFILE")" 2>/dev/null || true
    rm -f "$PIDFILE"
  fi
  tc qdisc del dev "$HOSTVETH" ingress 2>/dev/null || true
  tc qdisc del dev "$IFB" root 2>/dev/null || true
  ip link set "$IFB" down 2>/dev/null || true
  ip link del "$IFB" 2>/dev/null || true
  rm -f "$CURRENT_KBPS_FILE" 2>/dev/null || true
  echo "twcc-net throttle stopped"
}

# throttle-hold <PROFILE> [loss] — apply ONE profile steady (no cycling), for
# per-condition canaries (RpiTwccGood/Bad/Congesting/Recovering).
throttle_hold() {
  local want lossov="${2:-}"
  want=$(printf '%s' "${1:-}" | tr '[:lower:]' '[:upper:]')
  [ -n "$want" ] || { echo "twcc-net throttle-hold: profile required (GOOD|MEDIUM|CONGESTING|BAD|RECOVERING)" >&2; exit 2; }
  plumb_ifb
  local found=""
  for p in "${PROFILES[@]}"; do
    read -r L BW LOSS D J <<< "$p"
    if [ "$L" = "$want" ]; then
      [ -n "$lossov" ] && LOSS="$lossov"
      tc qdisc change dev "$IFB" root handle 1: netem \
         delay "${D}ms" "${J}ms" "$CORR" distribution normal \
         loss "${LOSS}%" rate "$BW" limit "$LIMIT"
      echo "twcc-net hold $L -> bw=$BW loss=${LOSS}% delay=${D}ms jitter=${J}ms"
      write_current_kbps "$BW"
      found=1; break
    fi
  done
  [ -n "$found" ] || { echo "twcc-net throttle-hold: unknown profile '$want'" >&2; exit 2; }
}

# run --cwd DIR --env-file F -- CMD [ARGS...]
# Executes CMD as $RUNUSER inside the namespace, with env from the file.
run() {
  local cwd="" envfile=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --cwd)      cwd="$2"; shift 2;;
      --env-file) envfile="$2"; shift 2;;
      --)         shift; break;;
      *)          echo "twcc-net run: unexpected arg '$1'" >&2; exit 2;;
    esac
  done
  [ $# -ge 1 ] || { echo "twcc-net run: no command given" >&2; exit 2; }
  local envargs=()
  if [ -n "$envfile" ] && [ -f "$envfile" ]; then
    while IFS= read -r line; do [ -n "$line" ] && envargs+=("$line"); done < "$envfile"
  fi
  # ip netns exec needs root; sudo -u drops to jenkins BEFORE the command runs.
  exec ip netns exec "$NS" sudo -u "$RUNUSER" \
       env ${envargs[@]+"${envargs[@]}"} \
       sh -c 'cd "$1" && shift && exec "$@"' _ "${cwd:-/}" "$@"
}

status() {
  echo "--- netns ---"; ip netns list 2>/dev/null | grep -w "$NS" || echo "(no $NS)"
  echo "--- $HOSTVETH ---"; ip -br addr show "$HOSTVETH" 2>/dev/null || echo "(none)"
  echo "--- throttle ---"; [ -f "$PIDFILE" ] && echo "running pid $(cat "$PIDFILE")" || echo "(stopped)"
  echo "--- $IFB ---"; tc -s qdisc show dev "$IFB" 2>/dev/null || echo "(none)"
}

cmd="${1:-}"; shift 2>/dev/null || true
case "$cmd" in
  up)             up ;;
  down)           down ;;
  throttle-start) throttle_start "$@" ;;
  throttle-hold)  throttle_hold "$@" ;;
  throttle-stop)  throttle_stop ;;
  run)            run "$@" ;;
  status)         status ;;
  __throttle_loop) __throttle_loop "$@" ;;   # internal: backgrounded cycler
  *) echo "usage: twcc-net {up|down|throttle-start [stageSecs] [loss]|throttle-hold PROFILE [loss]|throttle-stop|run --cwd DIR --env-file F -- CMD...|status}" >&2; exit 2 ;;
esac
