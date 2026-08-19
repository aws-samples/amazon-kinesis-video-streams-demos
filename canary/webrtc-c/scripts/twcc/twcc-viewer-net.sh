#!/bin/bash
# =============================================================================
# twcc-viewer-net - root-owned wrapper for the EGRESS TWCC canary (media service
# -> JS viewer). Shapes the *viewer's downlink* so the media service's own
# congestion control must adapt its send bitrate; the browser (real Chromium)
# provides the RTCP/REMB/transport-cc feedback that drives it.
#
# This is the VIEWER-SIDE counterpart to twcc-net (which shapes the master's
# UPLINK on the Pi). Differences:
#   - runs on the EC2 viewer node (not the Pi); WAN iface is auto-detected.
#   - namespace `viewerns` + its own subnet (10.202.0.0/24) so it can't clash
#     with the master's kvsns/10.200.0.0/24.
#   - shapes the DOWNLINK: a plain root `netem` qdisc on veth-vhost EGRESS
#     (traffic host -> namespace -> browser). No IFB/ingress-mirror needed
#     (that trick is only for shaping an *uplink* that arrives as ingress).
#
# INSTALL: /usr/local/bin/twcc-viewer-net, root:root 0755 (see install-twcc-viewer-node.sh)
# GRANT:   /etc/sudoers.d/twcc-viewer -> <user> ALL=(root) NOPASSWD: /usr/local/bin/twcc-viewer-net
#
# Subcommands mirror twcc-net:
#   up | down | throttle-start [stageSecs] [loss] | throttle-hold PROFILE [loss]
#   throttle-stop | run --cwd DIR --env-file F -- CMD... | status
# =============================================================================
set -euo pipefail

NS=viewerns
HOSTVETH=veth-vhost
NSVETH=veth-vns
HOSTIP=10.202.0.1
NSIP=10.202.0.2
SUBNET=10.202.0.0/24
# Drop back to the invoking user for `run` (EC2 viewer may run the agent as
# ubuntu, the Pi runs jenkins). SUDO_USER is set by the sudoers-gated invocation.
RUNUSER="${SUDO_USER:-jenkins}"
PIDFILE=/run/twcc-viewer-throttle.pid
THROTTLE_LOG=/var/log/twcc-viewer-throttle.log
# Currently-applied downlink cap (kbps); emitted viewer-side as ViewerAppliedBandwidthKbps.
CURRENT_KBPS_FILE=/run/twcc-viewer-current-kbps
DNS=${TWCC_DNS:-8.8.8.8}
LIMIT=1000
CORR=25%
WAN=$(ip route get 1.1.1.1 2>/dev/null | awk '{print $5; exit}')

# Profile table (same caps as the master):  LABEL  BW  LOSS%  DELAYms  JITTERms
PROFILES=(
  "GOOD        3mbit    0    20   5"
  "MEDIUM      1mbit    1    50   15"
  "CONGESTING  500kbit  1.5  75   20"
  "BAD         250kbit  2    100  30"
  "RECOVERING  1500kbit 0.5  35   10"
)

bw_to_kbps() {
  local n; n=$(printf '%s' "$1" | grep -oE '^[0-9]+')
  case "$1" in
    *mbit) echo $(( ${n:-0} * 1000 )) ;;
    *kbit) echo "${n:-0}" ;;
    *)     echo 0 ;;
  esac
}

write_current_kbps() {
  local k; k=$(bw_to_kbps "$1")
  echo "$k" > "$CURRENT_KBPS_FILE" 2>/dev/null && chmod 644 "$CURRENT_KBPS_FILE" 2>/dev/null || true
}

# Sleep until the next wall-clock multiple of $1 seconds. With stage=60 this pins
# every stage transition to :00 of a minute, so a stage fills exactly one CloudWatch
# 1-minute bucket (no straddle-blending of the Average). Self-correcting each call.
sleep_aligned() {
  local s="$1" now
  case "$s" in ''|*[!0-9]*) sleep "${s:-20}"; return;; esac
  [ "$s" -gt 0 ] || { sleep 1; return; }
  now=$(date +%s)
  sleep $(( (now / s + 1) * s - now ))
}

# Apply/replace the downlink netem on veth-vhost EGRESS. `replace` works whether
# or not a qdisc already exists and preserves the queue across stage changes.
apply_netem() {
  local bw="$1" loss="$2" delay="$3" jitter="$4"
  tc qdisc replace dev "$HOSTVETH" root handle 1: netem \
     delay "${delay}ms" "${jitter}ms" "$CORR" distribution normal \
     loss "${loss}%" rate "$bw" limit "$LIMIT"
}

up() {
  [ -n "$WAN" ] || { echo "twcc-viewer-net: could not detect WAN interface" >&2; exit 1; }
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
  echo "twcc-viewer-net up: netns=$NS ($NSIP) -> $HOSTVETH/$HOSTIP -> NAT -> $WAN, DNS=$DNS"
}

down() {
  throttle_stop || true
  iptables -t nat -D POSTROUTING -s "$SUBNET" -o "$WAN" -j MASQUERADE 2>/dev/null || true
  iptables -D FORWARD -i "$HOSTVETH" -o "$WAN" -j ACCEPT 2>/dev/null || true
  iptables -D FORWARD -i "$WAN" -o "$HOSTVETH" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true
  ip netns del "$NS" 2>/dev/null || true
  ip link del "$HOSTVETH" 2>/dev/null || true
  rm -rf "/etc/netns/$NS"
  echo "twcc-viewer-net down"
}

throttle_start() {
  local stage="${1:-20}" lossov="${2:-}"
  # Benign starting shape so the qdisc exists immediately.
  apply_netem 10mbit 0 20 5
  setsid "$0" __throttle_loop "$stage" "$lossov" >>"$THROTTLE_LOG" 2>&1 &
  echo $! > "$PIDFILE"
  echo "twcc-viewer-net throttle started (pid $(cat "$PIDFILE"), ${stage}s/stage, loss=${lossov:-per-profile})"
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
      apply_netem "$BW" "$LOSS" "$D" "$J" || exit 0
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
  tc qdisc del dev "$HOSTVETH" root 2>/dev/null || true
  rm -f "$CURRENT_KBPS_FILE" 2>/dev/null || true
  echo "twcc-viewer-net throttle stopped"
}

throttle_hold() {
  local want lossov="${2:-}"
  want=$(printf '%s' "${1:-}" | tr '[:lower:]' '[:upper:]')
  [ -n "$want" ] || { echo "twcc-viewer-net throttle-hold: profile required (GOOD|MEDIUM|CONGESTING|BAD|RECOVERING)" >&2; exit 2; }
  local found=""
  for p in "${PROFILES[@]}"; do
    read -r L BW LOSS D J <<< "$p"
    if [ "$L" = "$want" ]; then
      [ -n "$lossov" ] && LOSS="$lossov"
      apply_netem "$BW" "$LOSS" "$D" "$J"
      echo "twcc-viewer-net hold $L -> bw=$BW loss=${LOSS}% delay=${D}ms jitter=${J}ms"
      write_current_kbps "$BW"
      found=1; break
    fi
  done
  [ -n "$found" ] || { echo "twcc-viewer-net throttle-hold: unknown profile '$want'" >&2; exit 2; }
}

# run --cwd DIR --env-file F -- CMD [ARGS...]  (runs CMD as $RUNUSER inside viewerns)
run() {
  local cwd="" envfile=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --cwd)      cwd="$2"; shift 2;;
      --env-file) envfile="$2"; shift 2;;
      --)         shift; break;;
      *)          echo "twcc-viewer-net run: unexpected arg '$1'" >&2; exit 2;;
    esac
  done
  [ $# -ge 1 ] || { echo "twcc-viewer-net run: no command given" >&2; exit 2; }
  local envargs=()
  if [ -n "$envfile" ] && [ -f "$envfile" ]; then
    while IFS= read -r line; do [ -n "$line" ] && envargs+=("$line"); done < "$envfile"
  fi
  exec ip netns exec "$NS" sudo -u "$RUNUSER" \
       env ${envargs[@]+"${envargs[@]}"} \
       sh -c 'cd "$1" && shift && exec "$@"' _ "${cwd:-/}" "$@"
}

status() {
  echo "--- netns ---"; ip netns list 2>/dev/null | grep -w "$NS" || echo "(no $NS)"
  echo "--- $HOSTVETH ---"; ip -br addr show "$HOSTVETH" 2>/dev/null || echo "(none)"
  echo "--- throttle ---"; [ -f "$PIDFILE" ] && echo "running pid $(cat "$PIDFILE")" || echo "(stopped)"
  echo "--- downlink qdisc ($HOSTVETH root) ---"; tc -s qdisc show dev "$HOSTVETH" 2>/dev/null || echo "(none)"
  echo "--- applied cap ---"; cat "$CURRENT_KBPS_FILE" 2>/dev/null || echo "(none)"
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
  *) echo "usage: twcc-viewer-net {up|down|throttle-start [stageSecs] [loss]|throttle-hold PROFILE [loss]|throttle-stop|run --cwd DIR --env-file F -- CMD...|status}" >&2; exit 2 ;;
esac
