#!/bin/bash
# install-twcc-node.sh — make an ALREADY-ONBOARDED rpi5 canary node TWCC-ready.
#
# A plain canary node (jenkins user + toolchain + gstreamer + tunnel + Jenkins
# node) can run the normal canary but NOT the network-shaped TWCC canary, which
# needs a netns "mid-path router" + tc/netem. This script installs exactly that
# delta and nothing else — so you never hand-configure it, and you don't re-run
# full onboarding (which would rebuild the reverse tunnel / node).
#
# Installs (all idempotent):
#   - iptables                      (netns NAT; RPi OS Bookworm may ship nft-only)
#   - /usr/local/bin/twcc-net       (root-owned root wrapper; creates/destroys the
#                                     kvsns namespace + tc shaping at run time)
#   - /etc/sudoers.d/twcc-canary    (jenkins NOPASSWD: ONLY /usr/local/bin/twcc-net)
#   - gstreamer x264enc + avdec     (re-encode sources: testsrc/filesrc/framesrc)
#
# This is the same work as rpi-onboard.sh "Phase 3b"; kept standalone so it can be
# applied to a node that was onboarded before TWCC existed.
#
# Usage (run as root — SSM send-command already runs as root; otherwise sudo):
#   sudo ./install-twcc-node.sh [path-to-twcc-net.sh]
# Default wrapper source is the twcc-net.sh sibling of this script.
set -euo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_SRC="${1:-$SELF_DIR/twcc-net.sh}"

# Re-exec via sudo if not already root (SSM runs as root, so this is a no-op there).
if [ "$(id -u)" -ne 0 ]; then
  echo "Not root — re-executing via sudo..."
  exec sudo -E "$0" "$@"
fi

if [ ! -f "$WRAPPER_SRC" ]; then
  echo "ERROR: twcc-net source not found: $WRAPPER_SRC" >&2
  echo "       Pass the path explicitly, e.g. ./install-twcc-node.sh /path/to/twcc-net.sh" >&2
  exit 1
fi

echo "== apt: iptables + re-encode plugins (idempotent) =="
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq || true
# iptables is the only hard TWCC-only dep; the gstreamer packages are usually
# already present on a canary node but installing them again is a harmless no-op.
apt-get install -y iptables gstreamer1.0-plugins-ugly gstreamer1.0-libav || true

echo "== install /usr/local/bin/twcc-net (root:root 0755) =="
install -o root -g root -m 0755 "$WRAPPER_SRC" /usr/local/bin/twcc-net

echo "== scoped sudoers grant (/etc/sudoers.d/twcc-canary) =="
printf 'jenkins ALL=(root) NOPASSWD: /usr/local/bin/twcc-net\n' > /etc/sudoers.d/twcc-canary
chown root:root /etc/sudoers.d/twcc-canary
chmod 0440 /etc/sudoers.d/twcc-canary
visudo -cf /etc/sudoers.d/twcc-canary

echo "== smoke: twcc-net up/status/down (verifies netns+tc+iptables work) =="
/usr/local/bin/twcc-net up && /usr/local/bin/twcc-net status && /usr/local/bin/twcc-net down || {
  echo "WARN: twcc-net up/down smoke test failed — check iptables/netns support on this host" >&2
}

echo ">>> TWCC node provisioning done on $(hostname)"
