#!/bin/bash
# =============================================================================
# netns_router.sh - Build a "mid-path router" test rig on the Raspberry Pi.
#
# WHY: We shape the master's uplink the way a real router BETWEEN the sender and
# the far peer would. If we shaped the master's own NIC egress, its TWCC send
# timestamp (localTimeKvs) would be stamped AFTER the shaped queue, hiding the
# queue delay from the estimator. Instead we run the master in its own network
# namespace (kvsns) whose only exit is a veth pair into the root namespace; the
# root ns NATs it out to the WAN. Shaping is applied on that forwarding hop
# (see netns_profiles.sh), so the master stamps its send time BEFORE the
# bottleneck - just like real life.
#
# SSM/SSH SAFETY: your shell + the SSM agent + the reverse-tunnel run in the
# ROOT namespace on the primary NIC (wlan0/eth0). They never traverse the veth,
# so throttling the veth can never choke your management session.
#
# Adapted for the Pi from the Media-Service-TWCC doc's EC2 version:
#   - DNS defaults to a PUBLIC resolver (the Pi is NOT in a VPC, so the doc's
#     172.31.0.2 VPC resolver is unreachable here). Override with DNS=...
#   - WAN interface is auto-detected (wlan0 on WiFi, eth0 on wired).
#
# Usage:
#   sudo ./netns_router.sh up       # create the namespace + veth + NAT
#   sudo ./netns_router.sh down     # tear it all down
#   sudo ./netns_router.sh status   # show current state
#
# Then run the master INSIDE the namespace (see README.md / run-master.sh).
# NOTE: the namespace is NOT persistent across reboots - re-run `up` after a reboot.
# =============================================================================

NS=kvsns                 # network namespace name for the master
HOSTVETH=veth-host       # veth end that stays in the root ns (we shape THIS device's ingress)
NSVETH=veth-ns           # veth end that moves into the namespace
HOSTIP=10.200.0.1        # root-ns side address (the namespace's default gateway)
NSIP=10.200.0.2          # namespace side address
SUBNET=10.200.0.0/24     # private subnet for the veth link
DNS=${DNS:-8.8.8.8}      # public resolver (Pi is not in a VPC); override with DNS=1.1.1.1 etc
WAN=$(ip route get 1.1.1.1 2>/dev/null | awk '{print $5; exit}')  # auto-detect WAN iface (wlan0/eth0)

up() {
  if [ -z "$WAN" ]; then echo "ERROR: could not auto-detect WAN interface"; exit 1; fi

  # --- create the namespace and the veth pair linking it to the root ns ---
  ip netns add $NS 2>/dev/null
  ip link add $NSVETH type veth peer name $HOSTVETH
  ip link set $NSVETH netns $NS               # move one end into the namespace

  # --- address + bring up the root-ns end (acts as the namespace's gateway) ---
  ip addr add $HOSTIP/24 dev $HOSTVETH
  ip link set $HOSTVETH up

  # --- address + bring up the namespace end, and default-route it via the host ---
  ip netns exec $NS ip addr add $NSIP/24 dev $NSVETH
  ip netns exec $NS ip link set $NSVETH up
  ip netns exec $NS ip link set lo up
  ip netns exec $NS ip route add default via $HOSTIP

  # --- give the namespace its own DNS (resolv.conf is namespace-scoped) ---
  mkdir -p /etc/netns/$NS
  printf "nameserver %s\nnameserver 1.1.1.1\n" "$DNS" > /etc/netns/$NS/resolv.conf

  # --- enable IP forwarding so the root ns routes namespace traffic to the WAN ---
  sysctl -qw net.ipv4.ip_forward=1
  # loose reverse-path filtering (rp_filter=2): packets arrive on veth-host and
  # leave on the WAN (asymmetric path) - strict mode would drop them.
  sysctl -qw net.ipv4.conf.$WAN.rp_filter=2 2>/dev/null
  sysctl -qw net.ipv4.conf.$HOSTVETH.rp_filter=2 2>/dev/null

  # --- NAT the namespace subnet out the WAN, and allow forwarding both ways ---
  iptables -t nat -C POSTROUTING -s $SUBNET -o $WAN -j MASQUERADE 2>/dev/null \
    || iptables -t nat -A POSTROUTING -s $SUBNET -o $WAN -j MASQUERADE
  iptables -C FORWARD -i $HOSTVETH -o $WAN -j ACCEPT 2>/dev/null \
    || iptables -A FORWARD -i $HOSTVETH -o $WAN -j ACCEPT
  iptables -C FORWARD -i $WAN -o $HOSTVETH -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null \
    || iptables -A FORWARD -i $WAN -o $HOSTVETH -m state --state RELATED,ESTABLISHED -j ACCEPT

  echo "ROUTER UP: netns=$NS ($NSIP) -> $HOSTVETH/$HOSTIP -> NAT -> $WAN. DNS=$DNS"
  echo "Verify: sudo ip netns exec $NS ping -c1 1.1.1.1 ; sudo ip netns exec $NS getent hosts kinesisvideo.us-west-2.amazonaws.com"
}

down() {
  iptables -t nat -D POSTROUTING -s $SUBNET -o $WAN -j MASQUERADE 2>/dev/null
  iptables -D FORWARD -i $HOSTVETH -o $WAN -j ACCEPT 2>/dev/null
  iptables -D FORWARD -i $WAN -o $HOSTVETH -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null
  ip netns del $NS 2>/dev/null
  ip link del $HOSTVETH 2>/dev/null
  rm -rf /etc/netns/$NS
  echo "ROUTER DOWN ($NS removed)"
}

case "${1:-up}" in
  up) up ;;
  down) down ;;
  status)
    echo "--- WAN iface ---"; echo "$WAN"
    echo "--- netns ---"; ip netns list
    echo "--- host veth ---"; ip -br addr show $HOSTVETH 2>/dev/null
    echo "--- ns addr/route ---"; ip netns exec $NS ip -br addr 2>/dev/null; ip netns exec $NS ip route 2>/dev/null
    echo "--- nat ---"; iptables -t nat -S POSTROUTING | grep "$SUBNET"
    echo "--- forward ---"; iptables -S FORWARD | grep "$HOSTVETH" ;;
  *) echo "usage: $0 {up|down|status}"; exit 1 ;;
esac
