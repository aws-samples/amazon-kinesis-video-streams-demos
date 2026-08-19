#!/bin/bash
# install-twcc-viewer-node.sh - make an EC2 JS-viewer node ready for the EGRESS
# TWCC canary (media service -> viewer). Installs the downlink shaper delta:
#   - iptables                      (netns NAT)
#   - /usr/local/bin/twcc-viewer-net (root-owned downlink shaper; creates/destroys
#                                     the viewerns namespace + tc/netem downlink)
#   - /etc/sudoers.d/twcc-viewer    (<grant-user> NOPASSWD: ONLY /usr/local/bin/twcc-viewer-net)
#
# This is the EC2/viewer counterpart to install-twcc-node.sh (which is Pi/master
# specific). Kept separate on purpose: different OS/user (EC2 viewer may run the
# Jenkins agent as `ubuntu`, the Pi runs `jenkins`), different WAN iface (auto-
# detected by the wrapper), and NO gstreamer (the viewer is a browser).
#
# Usage (run as root -- SSM send-command already is; else sudo):
#   sudo ./install-twcc-viewer-node.sh [grant-user] [path-to-twcc-viewer-net.sh]
# grant-user defaults to jenkins; pass e.g. ubuntu if the agent runs as ubuntu.
# Wrapper source defaults to the twcc-viewer-net.sh sibling of this script.
set -euo pipefail

GRANT_USER="${1:-jenkins}"
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_SRC="${2:-$SELF_DIR/twcc-viewer-net.sh}"

if [ "$(id -u)" -ne 0 ]; then
  echo "Not root -- re-executing via sudo..."
  exec sudo -E "$0" "$@"
fi

if [ ! -f "$WRAPPER_SRC" ]; then
  echo "ERROR: twcc-viewer-net source not found: $WRAPPER_SRC" >&2
  exit 1
fi
id "$GRANT_USER" >/dev/null 2>&1 || { echo "ERROR: grant-user '$GRANT_USER' does not exist on this host" >&2; exit 1; }

echo "== apt: iptables (idempotent) =="
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq || true
apt-get install -y iptables || true

echo "== install /usr/local/bin/twcc-viewer-net (root:root 0755) =="
install -o root -g root -m 0755 "$WRAPPER_SRC" /usr/local/bin/twcc-viewer-net

echo "== scoped sudoers grant for '$GRANT_USER' (/etc/sudoers.d/twcc-viewer) =="
printf '%s ALL=(root) NOPASSWD: /usr/local/bin/twcc-viewer-net\n' "$GRANT_USER" > /etc/sudoers.d/twcc-viewer
chown root:root /etc/sudoers.d/twcc-viewer
chmod 0440 /etc/sudoers.d/twcc-viewer
visudo -cf /etc/sudoers.d/twcc-viewer

echo "== smoke: up / status / down (verifies netns + tc + iptables downlink shaping) =="
/usr/local/bin/twcc-viewer-net up && /usr/local/bin/twcc-viewer-net status && /usr/local/bin/twcc-viewer-net down || {
  echo "WARN: twcc-viewer-net up/down smoke failed -- check iptables/netns support on this host" >&2
}

echo ">>> TWCC viewer-node provisioning done on $(hostname) (grant-user=$GRANT_USER)"
