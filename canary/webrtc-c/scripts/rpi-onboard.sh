#!/bin/zsh
# =============================================================================
# rpi-onboard.sh - One-shot onboarding of a Raspberry Pi into the WebRTC-C
# canary Jenkins fleet. Runs Phases 2,3,4,6 automatically over SSH/AWS and
# prints the Phase-5 Jenkins node Groovy for you to paste into Script Console.
#
#   Usage:   zsh rpi-onboard.sh <IDX> <user@ip> <tunnel_port>
#   Example: zsh rpi-onboard.sh 007 pi@172.16.0.110 2228
#
# See canary/webrtc-c/docs/rpi5-setup-sop.md for the full step-by-step rationale
# and canary/webrtc-c/docs/reverse-tunnel-key-diagram.md for the key/tunnel model.
#
# -----------------------------------------------------------------------------
# PREREQUISITES (ambient - set up ONCE, not per-Pi)
# -----------------------------------------------------------------------------
#   1. The Pi already has 64-bit Raspberry Pi OS flashed, SSH enabled, on WiFi,
#      and you can `ssh <user@ip>` to it. Confirm 64-bit: `uname -m` = aarch64.
#      Use NVMe/quality-SSD boot media, NOT a cheap SD card (silent bit-rot has
#      bricked a Pi here before - see the incident log in the roadmap).
#   2. AWS temporary creds for account 232283333863 are exported and working
#      (`aws sts get-caller-identity` succeeds). Needed for SSM + IoT calls.
#   3. Jump SSH key at $JKEY lets you reach the jump host $JUMP.
#   4. The SHARED IoT layer exists (created once by the first Pi's
#      rpi5-iot-bootstrap.sh and REUSED by every Pi): bootstrap role
#      rpi5-canary-bootstrap, role alias rpi5-canary_role_alias, IoT policy
#      rpi5-canary_policy. Canary-STS's trust policy ALREADY includes the
#      bootstrap role, so this script never edits IAM/trust. Per-Pi we only add
#      a new thing + certificate (independent identity, individually revocable).
#
# -----------------------------------------------------------------------------
# PER-PI INPUTS you MUST pick uniquely (no auto-allocation, to avoid clobbering)
# -----------------------------------------------------------------------------
#   IDX   - zero-padded number, e.g. 007. Drives hostname (007-pi), thing
#           (rpi5-007_thing), node/label (rpi5-007), SSM name (007-pi-canary).
#   PORT  - jump reverse-tunnel port. MUST be unique per Pi or tunnels clobber
#           each other. In use: 2222(yuqi-pi/001), 2223..2227(002..006).
#           Next new Pi -> 2228, and so on.
#   sudo password - prompted at runtime (not stored). All Pis here share one.
#
# -----------------------------------------------------------------------------
# GOTCHAS BAKED IN (do not "simplify" these away - each cost real debugging)
# -----------------------------------------------------------------------------
#   * jenkins user is LOCKED-PASSWORD / NON-SUDO by design (least privilege).
#     Never `sudo -iu jenkins` then sudo; never set a password. System steps run
#     as the login user via `echo $PW | sudo -S`; jenkins-owned work via
#     `sudo -u jenkins`.
#   * credhelper.sh uses RESP=$(curl ...) then a SEPARATE `echo "$RESP" | jq`
#     line. A single long `curl ... | jq '{...}'` line gets split at `jq ` on
#     paste, putting the filter on its own line -> "command not found".
#   * File pushes to the Pi go Mac->jump->Pi as SINGLE ssh/scp commands, NEVER a
#     nested `ssh "..." <<heredoc` that itself runs `ssh/scp` (that pattern
#     silently no-ops yet still returns 0, so `set -e` + "ALL DONE" LIE).
#   * SSM agent is installed from the .deb (debian_arm64), NOT snap - Raspberry
#     Pi OS has no snapd by default.
#   * AWS CLI must be the aarch64 bundle; the x86_64 one won't run.
#   * JDK 21 (17 is unavailable on this OS; Jenkins agents support 21).
#   * Jenkins node Remote root dir = /home/jenkins/Jenkins (lowercase). Copying
#     the EC2 nodes' /home/ubuntu causes AccessDeniedException.
#   * Reverse tunnel: autossh + systemd Restart=always; Key A on the jump is
#     prefixed `restrict,port-forwarding` (can only forward ports).
#
# -----------------------------------------------------------------------------
# GOTCHAS YOU MUST HANDLE YOURSELF (environment / interactive)
# -----------------------------------------------------------------------------
#   * This is a zsh script. read prompt syntax is `read -rs "VAR?prompt"`, NOT
#     bash's `-p`. Unquoted vars do NOT word-split in zsh (use arrays).
#   * Do NOT paste a block that starts with `read` into the terminal - `read`
#     eats the next pasted line as the password. Run scripts as files.
#   * Local-network flakiness bites SSH: a VPN (utun / 192.168.x tunnel iface)
#     steals routes to 172.16.0.x -> "Network is unreachable". Disconnect VPN;
#     `ping <ip>` before blaming SSH. Mac and Pi must be on the same LAN for
#     direct SSH; .local (mDNS) also needs same LAN.
#   * Reusing an IP across re-flashes trips "REMOTE HOST IDENTIFICATION CHANGED"
#     -> `ssh-keygen -R <ip>` then reconnect.
#   * SSM cannot fix SSM: if the agent dies (TargetNotConnected), recover via the
#     reverse tunnel (ssh through the jump) then restart amazon-ssm-agent.
#   * macOS `cat` has no `-A`; use `cat -e` or `grep -n '' file` to inspect.
#
# -----------------------------------------------------------------------------
# AFTER ONBOARDING - canary job params that MUST be set for a Pi master
# -----------------------------------------------------------------------------
#   * STS_DURATION_SECONDS=3600  - role chaining (IoT->bootstrap->Canary-STS) is
#     hard-capped at 1h; the pipeline default 43200 fails on a Pi.
#   * VIEWER_WAIT_MINUTES=120     - a from-scratch Pi build far exceeds the 20-min
#     default viewer wait; the master build itself has no timeout.
#   * SCENARIO_LABEL must be a value the Java consumer recognizes (e.g.
#     StorageWithViewer); metric isolation is via RUNNER_LABEL, not new labels.
#   * The storage build script builds ONLY kvsWebrtcStorageSample (a full make
#     fails to link the unused canary signaling/webrtc targets); camera scenarios
#     derive gst=ON from CANARY_MEDIA_SOURCE=devicesrc - keep it stable or every
#     run rebuilds.
# =============================================================================
set -e
setopt err_return 2>/dev/null || true

IDX="$1"; PITARGET="$2"; PORT="$3"
if [[ -z "$IDX" || -z "$PITARGET" || -z "$PORT" ]]; then
  echo "Usage: zsh rpi-onboard.sh <IDX> <user@ip> <tunnel_port>"; exit 1
fi

# ---- shared / fixed config -------------------------------------------------
REGION=us-west-2
JUMP="ubuntu@54.185.49.98"
JKEY=~/Desktop/keys/ec2-key.pem
RK=/home/ubuntu/.ssh/rpi-key
SSM_ROLE=service-role/AmazonEC2RunCommandRoleForManagedInstances
IOT_ALIAS=rpi5-canary_role_alias
IOT_POLICY=rpi5-canary_policy
HOSTNAME_NEW="${IDX}-pi"
THING="rpi5-${IDX}_thing"
NODE="rpi5-${IDX}"
LABEL="rpi5-${IDX}"

echo "### Onboarding rpi5-${IDX}  target=$PITARGET  port=$PORT ###"
read -rs "PW?sudo password for $PITARGET: "; echo

# ---- 0. preconditions ------------------------------------------------------
echo "== 0. preconditions =="
aws --region $REGION sts get-caller-identity >/dev/null || { echo "AWS creds not working"; exit 1; }
ssh -o ConnectTimeout=8 -o StrictHostKeyChecking=accept-new "$PITARGET" 'echo pi-ok; uname -m' || { echo "cannot ssh to $PITARGET"; exit 1; }
ENDPOINT=$(aws --region $REGION iot describe-endpoint --endpoint-type iot:CredentialProvider --output text)
echo "endpoint=$ENDPOINT"

# ---- Phase 2. SSM activation + agent + hostname ----------------------------
echo "== Phase 2: SSM + hostname =="
ACT=$(aws --region $REGION ssm create-activation --iam-role "$SSM_ROLE" \
      --registration-limit 1 --default-instance-name "${IDX}-pi-canary" --output json)
AID=$(echo "$ACT" | jq -r .ActivationId)
ACODE=$(echo "$ACT" | jq -r .ActivationCode)
echo "ActivationId=$AID"
ssh "$PITARGET" "PW='$PW' AID='$AID' ACODE='$ACODE' REGION='$REGION' HN='$HOSTNAME_NEW' bash -s" <<'EOF'
set -e
S(){ echo "$PW" | sudo -S -p '' "$@"; }
cd /tmp
if ! command -v amazon-ssm-agent >/dev/null; then
  wget -q https://s3.amazonaws.com/ec2-downloads-windows/SSMAgent/latest/debian_arm64/amazon-ssm-agent.deb
  S dpkg -i amazon-ssm-agent.deb
fi
S systemctl stop amazon-ssm-agent 2>/dev/null || true
echo "$PW" | sudo -S -p '' -E amazon-ssm-agent -register -code "$ACODE" -id "$AID" -region "$REGION" -y
S systemctl enable --now amazon-ssm-agent
S hostnamectl set-hostname "$HN"
S sed -i "s/raspberrypi/$HN/g" /etc/hosts 2>/dev/null || true
echo ">>> Phase2 done on $(hostname)"
EOF

# ---- Phase 3. jenkins user + toolchain -------------------------------------
echo "== Phase 3: jenkins user + toolchain =="
ssh "$PITARGET" "PW='$PW' bash -s" <<'EOF'
set -e
S(){ echo "$PW" | sudo -S -p '' "$@"; }
S useradd -m -s /bin/bash jenkins 2>/dev/null || true
S apt-get update -qq
S env DEBIAN_FRONTEND=noninteractive apt-get install -y \
    openjdk-21-jre-headless git cmake build-essential gdb m4 jq unzip \
    libssl-dev libcurl4-openssl-dev pkg-config autossh \
    iptables libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
    gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly gstreamer1.0-libav
if ! command -v aws >/dev/null; then
  cd /tmp
  curl -s "https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip" -o awscliv2.zip
  unzip -q -o awscliv2.zip
  S ./aws/install --update
fi
echo ">>> Phase3 done: $(java -version 2>&1|head -1); $(aws --version 2>&1)"
EOF

# ---- Phase 3b. TWCC network-shaping wrapper + scoped sudoers ----------------
# Installs the ONE root-capable wrapper the TWCC canary job needs (netns/tc/NAT)
# and a tightly-scoped sudoers grant (jenkins may run ONLY /usr/local/bin/twcc-net
# as root - nothing broader). Preserves the "jenkins is otherwise non-sudo" model.
echo "== Phase 3b: TWCC shaping wrapper (netns/tc) =="
WRAPPER_SRC="${0:A:h}/twcc/twcc-net.sh"
if [[ -f "$WRAPPER_SRC" ]]; then
  scp -o StrictHostKeyChecking=accept-new "$WRAPPER_SRC" "$PITARGET":/tmp/twcc-net
  ssh "$PITARGET" "PW='$PW' bash -s" <<'EOF'
set -e
S(){ echo "$PW" | sudo -S -p '' "$@"; }
S install -o root -g root -m 0755 /tmp/twcc-net /usr/local/bin/twcc-net
rm -f /tmp/twcc-net
printf 'jenkins ALL=(root) NOPASSWD: /usr/local/bin/twcc-net\n' > /tmp/twcc.sudoers
S install -o root -g root -m 0440 /tmp/twcc.sudoers /etc/sudoers.d/twcc-canary
rm -f /tmp/twcc.sudoers
S visudo -cf /etc/sudoers.d/twcc-canary
echo ">>> Phase3b done: twcc-net installed + sudoers validated"
EOF
else
  echo "WARN: $WRAPPER_SRC not found; skipping TWCC wrapper install"
fi

# ---- Phase 4. reverse tunnel ----------------------------------------------
echo "== Phase 4: reverse tunnel (port $PORT) =="
# 4a. Key A on Pi (as jenkins), collect pubkey
KEYA=$(ssh "$PITARGET" "PW='$PW' bash -s" <<'EOF'
echo "$PW" | sudo -S -p '' -u jenkins bash -c '
  mkdir -p /home/jenkins/.ssh && chmod 700 /home/jenkins/.ssh
  [ -f /home/jenkins/.ssh/id_ed25519 ] || ssh-keygen -t ed25519 -N "" -f /home/jenkins/.ssh/id_ed25519 >/dev/null 2>&1
  cat /home/jenkins/.ssh/id_ed25519.pub'
EOF
)
echo "KeyA=${KEYA:0:40}..."
# 4b. authorize Key A on jump (restrict,port-forwarding); fetch Key B pubkey
KEYB=$(ssh -i "$JKEY" "$JUMP" "
  mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys
  grep -qF '$KEYA' ~/.ssh/authorized_keys || echo 'restrict,port-forwarding $KEYA' >> ~/.ssh/authorized_keys
  [ -f ~/.ssh/rpi-key ] || ssh-keygen -t ed25519 -N '' -f ~/.ssh/rpi-key >/dev/null 2>&1
  cat ~/.ssh/rpi-key.pub")
echo "KeyB=${KEYB:0:40}..."
# 4c. authorize Key B on Pi jenkins
ssh "$PITARGET" "PW='$PW' KEYB='$KEYB' bash -s" <<'EOF'
echo "$PW" | sudo -S -p '' -u jenkins bash -c "
  mkdir -p /home/jenkins/.ssh && chmod 700 /home/jenkins/.ssh
  touch /home/jenkins/.ssh/authorized_keys && chmod 600 /home/jenkins/.ssh/authorized_keys
  grep -qF '$KEYB' /home/jenkins/.ssh/authorized_keys || echo '$KEYB' >> /home/jenkins/.ssh/authorized_keys"
EOF
# 4d. autossh tunnel service (single-line autossh; only PORT varies)
ssh "$PITARGET" "PW='$PW' PORT='$PORT' bash -s" <<'EOF'
set -e
S(){ echo "$PW" | sudo -S -p '' "$@"; }
S bash -c "printf '%s\n' '#!/bin/bash' 'exec /usr/bin/autossh -M 0 -N -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=accept-new -R ${PORT}:localhost:22 ubuntu@54.185.49.98' > /usr/local/bin/rpi-tunnel.sh"
S chmod +x /usr/local/bin/rpi-tunnel.sh
echo "$PW" | sudo -S -p '' tee /etc/systemd/system/rpi-jenkins-tunnel.service >/dev/null <<UNIT
[Unit]
Description=Reverse SSH tunnel to Jenkins jump host
After=network-online.target
Wants=network-online.target
[Service]
User=jenkins
Environment=AUTOSSH_GATETIME=0
ExecStart=/usr/local/bin/rpi-tunnel.sh
Restart=always
RestartSec=10
[Install]
WantedBy=multi-user.target
UNIT
S systemctl daemon-reload
S systemctl enable --now rpi-jenkins-tunnel
sleep 3
echo -n ">>> tunnel active? "; S systemctl is-active rpi-jenkins-tunnel
EOF
# 4e. verify tunnel from jump
echo -n "tunnel check (jump -> port $PORT): "
ssh -i "$JKEY" "$JUMP" "ssh -o StrictHostKeyChecking=accept-new -i $RK -p $PORT jenkins@localhost hostname"

# ---- Phase 5. agent.jar + node Groovy --------------------------------------
echo "== Phase 5: agent.jar =="
ssh -i "$JKEY" "$JUMP" "ssh -o StrictHostKeyChecking=accept-new -i $RK -p $PORT jenkins@localhost 'mkdir -p /home/jenkins/agent'"
ssh -i "$JKEY" "$JUMP" "scp -o StrictHostKeyChecking=accept-new -i $RK -P $PORT ~/agent.jar jenkins@localhost:/home/jenkins/agent/agent.jar"
echo ">>> agent.jar staged"

# ---- Phase 6. IoT identity -------------------------------------------------
echo "== Phase 6: IoT cert + credhelper =="
# 6a. thing + cert on AWS, attach shared policy + thing
aws --region $REGION iot create-thing --thing-name "$THING" >/dev/null 2>&1 || true
aws --region $REGION iot create-keys-and-certificate --set-as-active \
  --certificate-pem-outfile /tmp/rpi5-${IDX}_certificate.pem \
  --public-key-outfile      /tmp/rpi5-${IDX}_public.key \
  --private-key-outfile     /tmp/rpi5-${IDX}_private.key > /tmp/rpi5-${IDX}_cert.json
CERTARN=$(jq -r '.certificateArn' /tmp/rpi5-${IDX}_cert.json)
aws --region $REGION iot attach-policy --policy-name "$IOT_POLICY" --target "$CERTARN"
aws --region $REGION iot attach-thing-principal --thing-name "$THING" --principal "$CERTARN"
# 6b. CA + credhelper (newline-safe) + config, locally
[ -f /tmp/AmazonRootCA1.pem ] || curl -s https://www.amazontrust.com/repository/AmazonRootCA1.pem -o /tmp/AmazonRootCA1.pem
cat > /tmp/rpi5-${IDX}_credhelper.sh <<HELPER
#!/bin/bash
D=/home/jenkins/.aws-iot
RESP=\$(curl -s --cert "\$D/certificate.pem" --key "\$D/private.key" --cacert "\$D/AmazonRootCA1.pem" -H "x-amzn-iot-thingname: ${THING}" "https://${ENDPOINT}/role-aliases/${IOT_ALIAS}/credentials")
echo "\$RESP" | jq '{Version:1, AccessKeyId:.credentials.accessKeyId, SecretAccessKey:.credentials.secretAccessKey, SessionToken:.credentials.sessionToken, Expiration:.credentials.expiration}'
HELPER
cat > /tmp/aws_config <<'AWSCFG'
[default]
credential_process = /home/jenkins/.aws-iot/credhelper.sh
region = us-west-2
AWSCFG
# 6c. push files Mac->jump->Pi (single ssh commands, no nested heredoc)
push() {  # $1 local  $2 dest-on-pi
  scp -i "$JKEY" "$1" "$JUMP":/tmp/_onb
  ssh -i "$JKEY" "$JUMP" "scp -o StrictHostKeyChecking=accept-new -i $RK -P $PORT /tmp/_onb jenkins@localhost:'$2'; rm -f /tmp/_onb"
}
ssh -i "$JKEY" "$JUMP" "ssh -o StrictHostKeyChecking=accept-new -i $RK -p $PORT jenkins@localhost 'mkdir -p /home/jenkins/.aws-iot /home/jenkins/.aws && chmod 700 /home/jenkins/.aws-iot'"
push /tmp/rpi5-${IDX}_certificate.pem /home/jenkins/.aws-iot/certificate.pem
push /tmp/rpi5-${IDX}_private.key      /home/jenkins/.aws-iot/private.key
push /tmp/AmazonRootCA1.pem            /home/jenkins/.aws-iot/AmazonRootCA1.pem
push /tmp/rpi5-${IDX}_credhelper.sh    /home/jenkins/.aws-iot/credhelper.sh
push /tmp/aws_config                   /home/jenkins/.aws/config
ssh -i "$JKEY" "$JUMP" "ssh -o StrictHostKeyChecking=accept-new -i $RK -p $PORT jenkins@localhost 'chmod 700 /home/jenkins/.aws-iot/credhelper.sh; chmod 600 /home/jenkins/.aws-iot/certificate.pem /home/jenkins/.aws-iot/private.key'"
# 6d. verify credential chain
echo -n ">>> sts identity: "
ssh -i "$JKEY" "$JUMP" "ssh -o StrictHostKeyChecking=accept-new -i $RK -p $PORT jenkins@localhost 'aws sts get-caller-identity --output text 2>&1'"

# ---- clean local secrets ---------------------------------------------------
rm -f /tmp/rpi5-${IDX}_private.key /tmp/rpi5-${IDX}_certificate.pem \
      /tmp/rpi5-${IDX}_public.key /tmp/rpi5-${IDX}_cert.json /tmp/rpi5-${IDX}_credhelper.sh

# ---- Phase 5 hand-off: Jenkins node Groovy ---------------------------------
cat <<GROOVY

================================================================================
Phase 5 hand-off: paste this into Manage Jenkins -> Script Console to create the
Jenkins node (the only step not automatable over SSH):

import hudson.model.*; import hudson.slaves.*; import jenkins.model.Jenkins
def j = Jenkins.instance
def cmd = "ssh -o StrictHostKeyChecking=no -i /local/jenkins/.ssh/ec2-key.pem ubuntu@54.185.49.98 ssh -o StrictHostKeyChecking=no -i /home/ubuntu/.ssh/rpi-key -p ${PORT} jenkins@localhost java -jar /home/jenkins/agent/agent.jar"
def s = new DumbSlave("${NODE}", "/home/jenkins/Jenkins", new hudson.slaves.CommandLauncher(cmd))
s.nodeDescription = "Raspberry Pi 5 canary node ${IDX} (tunnel port ${PORT})"
s.numExecutors = 1; s.labelString = "${LABEL}"; s.mode = Node.Mode.EXCLUSIVE
s.retentionStrategy = new RetentionStrategy.Always()
def ex = j.getNode("${NODE}"); if (ex) j.removeNode(ex)
j.addNode(s); println "Created node ${NODE} (label=${LABEL}, port=${PORT})"
================================================================================

DONE. rpi5-${IDX}: hostname=${HOSTNAME_NEW}, tunnel port=${PORT}, label=${LABEL},
thing=${THING}. SSM name=${IDX}-pi-canary.
GROOVY
