# Raspberry Pi 5 Canary Node — Setup SOP (from OS flash to online node)

A reproducible, step-by-step procedure for building a Raspberry Pi 5 (aarch64)
canary node from scratch and enrolling it in the WebRTC-C canary Jenkins fleet.

This is the **operational manual** (copy-paste steps). For the *why* behind the
design decisions, see the companion `RASPBERRY_PI_CANARY_ROADMAP.md` at the repo
root.

> **Current scope:** synthetic-media node (streams pre-encoded
> `assets/h264SampleFrames/*.h264` from disk). **No physical camera is
> installed.** All camera-specific steps are marked **[OPTIONAL / FUTURE]** and
> can be skipped for the current build.

---

## Two identities — read this first

Everything below hinges on two Linux users. Mixing them up is the #1 source of
wasted time.

| User | Purpose | How you invoke it |
|---|---|---|
| `<sudo-user>` (device primary user, e.g. `yuqi`) | install packages, write `/etc/systemd/...`, restart services | `sudo <cmd>` |
| `jenkins` | owns/runs the Jenkins agent and the build | `sudo -u jenkins <cmd>` |

> ⚠️ **The `jenkins` user is locked-password and NOT in sudoers — by design
> (least privilege).** Any `sudo` from a `jenkins` shell fails. Do **not**
> `sudo -iu jenkins` and then `sudo`; do **not** "fix" it by setting a password.
> System changes run as `<sudo-user>`; agent/build work runs as
> `sudo -u jenkins`.

---

## Phase 0 — Hardware & media selection (incident prevention — do not skip)

> ⚠️ **Hardest-learned lesson: boot-media quality decides everything.** The prior
> Pi used a commodity USB disk and suffered **silent bit rot** — `libcrypto.so.3`
> was corrupted, every OpenSSL-linked program segfaulted, and `cp` of large files
> crashed the writing process (hardware-level). Diagnosis cost most of a day. See
> the Incident Log in `RASPBERRY_PI_CANARY_ROADMAP.md`.

- **Board:** Raspberry Pi 5, 8GB RAM (aarch64).
- **Boot media:** **NVMe SSD (via M.2 HAT)** or a high-quality USB SSD.
  **Do NOT** use a cheap USB stick or low-grade SD card. This is the one hardware
  choice that is non-negotiable.
- **Network:** wired preferred; WiFi works too (prior unit ran on office WiFi
  `172.16.0.x` behind NAT).
- **[OPTIONAL / FUTURE] Camera:** Pi Camera Module v3 or HQ on the CSI port —
  **not installed in the current build**; only needed for the real-camera
  scenario (Phase 9).

---

## Phase 1 — Flash 64-bit OS

1. Install **Raspberry Pi Imager** on your Mac.
2. Choose **Raspberry Pi OS (64-bit)** — must be 64-bit; the canary is aarch64.
3. Click the gear (advanced settings) to pre-configure (avoids needing a monitor):
   - hostname (e.g. `yuqi-pi`)
   - enable **SSH**, set `<sudo-user>` + password (or public key)
   - configure WiFi (SSID/password/country) + locale
4. Flash to the NVMe/SSD, insert, power on.
5. On first login, verify architecture:
   ```bash
   uname -m                    # expect: aarch64
   dpkg --print-architecture   # expect: arm64
   grep MemTotal /proc/meminfo # expect: ~8256512 kB
   ```

> 🔎 **Take a media-health baseline right after flashing** (for later comparison):
> ```bash
> sudo apt-get update && sudo apt-get install -y debsums
> sudo debsums -s        # should print nothing. Any output = files already
>                        #   corrupted → replace media and re-flash.
> ```

---

## Phase 2 — Remote access: SSM hybrid activation

The Pi is behind NAT — corp cannot dial in. Use **SSM hybrid activation** as the
primary remote channel (registers the Pi as an `mi-xxxx` managed instance).

1. **On a machine with AWS permissions** (e.g. your Mac, with temporary creds),
   create an activation:
   ```bash
   aws ssm create-activation --region us-west-2 \
     --iam-role <SSMManagedInstanceRole> \
     --registration-limit 1 --default-instance-name rpi5-canary
   # note the ActivationId and ActivationCode from the output
   ```
2. **On the Pi** (as `<sudo-user>`), install the agent and register:
   ```bash
   sudo snap install amazon-ssm-agent --classic   # or the aarch64 .deb
   sudo amazon-ssm-agent -register -code "<ActivationCode>" -id "<ActivationId>" -region us-west-2
   sudo snap restart amazon-ssm-agent
   ```
3. **From the Mac**, verify online:
   ```bash
   aws ssm describe-instance-information --region us-west-2 \
     --query "InstanceInformationList[?PingStatus=='Online'].[InstanceId,ComputerName]" --output table
   aws ssm start-session --target <mi-xxxx> --region us-west-2
   ```

> 📌 **You cannot fix SSM through SSM.** If the agent dies, `start-session` and Run
> Command are both dead. The out-of-band recovery path is the reverse tunnel
> (Phase 4): SSH in via the jump host, then
> `sudo systemctl restart amazon-ssm-agent` (or `sudo snap restart ...`). That is
> why both channels must exist.

---

## Phase 3 — Host prep: jenkins user + toolchain

> ⚠️ See **"Two identities"** above. `jenkins` is locked-password / non-sudo on
> purpose. System installs run as `<sudo-user>`; agent-owned files use
> `sudo -u jenkins`.

```bash
# 1. Create the jenkins user (locked password, not in sudoers)
sudo useradd -m -s /bin/bash jenkins 2>/dev/null || true

# 2. JDK (Jenkins agent needs it; 17 unavailable on this OS — use 21, supported)
sudo apt-get update
sudo apt-get install -y openjdk-21-jre-headless

# 3. Build toolchain
sudo apt-get install -y git cmake build-essential gdb m4 jq unzip \
  libssl-dev libcurl4-openssl-dev pkg-config

# 4. AWS CLI v2 — MUST be the aarch64 bundle (the x86_64 one won't run on the Pi)
cd /tmp && curl -s "https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip" -o awscliv2.zip
unzip -q awscliv2.zip && sudo ./aws/install --update
aws --version
```

---

## Phase 4 — Reverse tunnel (the node's lifeline AND the SSM backup channel)

The Pi cannot be dialed into and cannot reach corp. It keeps a persistent reverse
SSH tunnel to the **jump host**; the controller then launches the agent through
the jump — identical to every other fleet node.

```
Pi ──outbound autossh──▶ jump:2222 (≈ Pi:22)
controller ──SSH──▶ jump(public) ──ssh -p 2222 localhost──▶ Pi ──▶ java -jar agent.jar
```

Jump host: `ec2-54-185-49-98.us-west-2.compute.amazonaws.com`
(public `54.185.49.98` = private `172.31.31.19`).

### Step 1 — Pi → jump key (Key A)

```bash
sudo -u jenkins ssh-keygen -t ed25519 -N '' -f /home/jenkins/.ssh/id_ed25519
sudo -u jenkins cat /home/jenkins/.ssh/id_ed25519.pub
# append this pubkey to ubuntu@jump:~/.ssh/authorized_keys
```

> 🔐 **Security follow-up (top item from the security review):** on the jump's
> `authorized_keys`, prefix Key A with `restrict,port-forwarding` so it can only
> forward ports (not open a shell / run commands).

### Step 2 — jump → Pi key (Key B, for the launch command's 2nd hop)

```bash
# on the jump host
ssh-keygen -t ed25519 -N '' -f ~/.ssh/rpi-key
# append ~/.ssh/rpi-key.pub to /home/jenkins/.ssh/authorized_keys on the Pi
```

### Step 3 — Persistent tunnel (autossh + systemd)

```bash
sudo apt-get install -y autossh

# Long autossh command goes in a script — pasting long lines into a unit file
# through an SSM terminal mangles them.
sudo tee /usr/local/bin/rpi-tunnel.sh >/dev/null <<'EOF'
#!/bin/bash
exec /usr/bin/autossh -M 0 -N \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=accept-new \
  -R 2222:localhost:22 \
  ubuntu@ec2-54-185-49-98.us-west-2.compute.amazonaws.com
EOF
sudo chmod +x /usr/local/bin/rpi-tunnel.sh

sudo tee /etc/systemd/system/rpi-jenkins-tunnel.service >/dev/null <<'EOF'
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
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now rpi-jenkins-tunnel
```

### Step 4 — Verify from the jump

```bash
ssh -p 2222 jenkins@localhost hostname   # should print the Pi's hostname
```

Notes:
- `-R 2222:localhost:22` parks the Pi's SSH port on the jump's `localhost:2222`.
- `ServerAlive*` + `Restart=always` handle WiFi blips and reboots
  (~40s worst-case reconnect; full reboot ~2 min).
- The jump's `sshd` must allow TCP forwarding (`AllowTcpForwarding yes`, default).

---

## Phase 5 — Agent + Jenkins node registration

```bash
sudo -u jenkins mkdir -p /home/jenkins/agent
# The Pi can't reach the controller to download agent.jar — copy from an existing
# node via the jump:
#   on jump: scp -i ~/.ssh/ec2-key.pem ubuntu@<existing-node-private-ip>:/home/ubuntu/agent.jar /tmp/
#            scp -P 2222 /tmp/agent.jar jenkins@localhost:/home/jenkins/agent/
```

**Jenkins UI → Manage Jenkins → Nodes → New Node:**

- **Name:** `rpi5-01`, type **Permanent Agent**
- **Remote root directory:** `/home/jenkins/Jenkins`
  ⚠️ lowercase `jenkins`; copying the EC2 `/home/ubuntu` causes
  `AccessDeniedException`.
- **Labels:** `rpi5-master`
- **Usage:** "Only build jobs with label expressions matching this node"
- **Launch method:** "Launch agent via execution of command on the controller":
  ```
  ssh -o StrictHostKeyChecking=no -i /local/jenkins/.ssh/ec2-key.pem ubuntu@ec2-54-185-49-98.us-west-2.compute.amazonaws.com \
      ssh -o StrictHostKeyChecking=no -i /home/ubuntu/.ssh/rpi-key -p 2222 jenkins@localhost \
      java -jar /home/jenkins/agent/agent.jar
  ```

**Verify:**
- Node `rpi5-01` shows **online** in Manage Jenkins → Nodes.
- Smoke test: a throwaway job restricted to label `rpi5-master` running
  `uname -a && java -version`; confirm `Executed on: rpi5-01`.
- Reboot the Pi; confirm the tunnel service comes back and the node reconnects.

---

## Phase 6 — Credentials: X.509 / zero static keys

> ⚠️ **Hard constraint: NO static IAM keys on the device** (they trigger security
> tickets). Use the IoT credential-provider chain.

`aws sts assume-role` needs base credentials to call with. EC2 nodes get them from
an instance profile; the Pi is a physical device AWS doesn't recognize → it needs
a verifiable, revocable, non-static identity:

```
X.509 cert (Pi /home/jenkins/.aws-iot/)
   │ mutual TLS (curl --cert --key --cacert)
   ▼
IoT credential provider: c1a3vjg139zfzv.credentials.iot.us-west-2.amazonaws.com
   /role-aliases/rpi5-canary_role_alias/credentials
   ▼
1h temp credentials → role rpi5-canary-bootstrap (only permission: assume Canary-STS)
   │ injected automatically via credential_process in ~/.aws/config
   ▼
aws sts assume-role Canary-STS  (pipeline unchanged)
```

One-time bootstrap (run `rpi5-iot-bootstrap.sh` on the Mac with human temp creds):
creates IoT thing `rpi5-canary_thing`, role alias `rpi5-canary_role_alias`, IoT
policy, and bootstrap role `rpi5-canary-bootstrap`; issues the X.509 cert. Then:

- scp cert/key/`AmazonRootCA1.pem` to the Pi's `/home/jenkins/.aws-iot/` (700/600)
- write `credhelper.sh` (curl + jq → CLI JSON credential format)
- `~/.aws/config`: `credential_process = /home/jenkins/.aws-iot/credhelper.sh`
- add `rpi5-canary-bootstrap` as a principal in the `Canary-STS` trust policy

> ⚠️ **Role chaining is hard-capped at 3600s.** The pipeline default passes
> `--duration-seconds 43200` (12h). Role-to-role chaining
> (IoT→bootstrap→Canary-STS) caps at 3600s (EC2 instance profiles are exempt,
> which is why EC2 nodes can do 12h). Pi scenarios must pass
> `STS_DURATION_SECONDS=3600`.

**Verify:**
```bash
sudo -u jenkins aws sts get-caller-identity
# → arn:aws:sts::232283333863:assumed-role/rpi5-canary-bootstrap/...
```

---

## Phase 7 — Build cache + build only the target you run

From-scratch SDK builds take ~20–40 min on the Pi, and the runner loops.
`scripts/build-storage-master.sh` keeps a persistent repo/build in
`~/webrtc-c-storage-master/` and skips the rebuild when the canary commit, the
resolved SDK SHA, and the build flags are all unchanged.

> ⚠️ **Two known pitfalls (both fixed on branch `rpi5-sample`):**
>
> 1. **Build only the storage target.** A full `make` fails at link time on
>    `kvsWebrtcCanaryWebrtc` / `kvsWebrtcCanarySignaling` with `undefined
>    reference` to `writeFirstFrameSentTimeToFile` /
>    `calculateDisconnectToFrameSentTime` — those functions are defined only in
>    the storage sample source, not in the shared `kvsWebrtcCanary` library. The
>    storage scenario only runs `kvsWebrtcStorageSample`, which links cleanly, so
>    the script uses `make -j"$(nproc)" kvsWebrtcStorageSample`.
> 2. **gst flag must stay stable or you rebuild every run.** The script derives
>    `ENABLE_GST_MEDIA_SOURCE` from `CANARY_MEDIA_SOURCE` (`disk` → OFF; anything
>    else → ON). If the value flips between runs, the `.build-flags` stamp
>    changes and forces a full rebuild. **For the current no-camera build**, the
>    disk media source yields `gst=OFF` — that's correct and consistent.
>    **[FUTURE]** when the camera arrives, `CANARY_MEDIA_SOURCE=devicesrc` yields
>    `gst=ON`; keep it consistent across Jenkins and manual warm-ups.

**Manual warm-up (nohup — survives closing the SSM session):**

Current no-camera build (disk media, `gst=OFF` derived automatically):
```bash
cd /home/jenkins/webrtc-c-storage-master && nohup bash ./repo/canary/webrtc-c/scripts/build-storage-master.sh https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git rpi5-sample > logs/clean-$(date +%s).log 2>&1 & echo "started pid=$!"
```

**[FUTURE]** camera build (pin `gst=ON` so the stamp stays stable):
```bash
cd /home/jenkins/webrtc-c-storage-master && ENABLE_GST_MEDIA_SOURCE=ON nohup bash ./repo/canary/webrtc-c/scripts/build-storage-master.sh https://github.com/aws-samples/amazon-kinesis-video-streams-demos.git rpi5-sample > logs/clean-$(date +%s).log 2>&1 & echo "started pid=$!"
```

> 🧹 **Build discipline (learned the hard way):** run exactly ONE build at a time.
> Killing a build leaves orphan `cmake`/`make`/`git` children that `pkill -f
> build-storage-master.sh` won't catch and can corrupt a half-written `.a`
> (`malformed archive` at link time). To fully reset:
> ```bash
> pkill -TERM -f build-storage-master.sh; sleep 3; pkill -KILL -f build-storage-master.sh
> pkill -KILL -f 'cmake.*webrtc-c-storage-master'; pkill -KILL -f 'index-pack'
> ps aux | grep -E 'build-storage|cmake|index-pack' | grep -v grep   # must be empty
> rm -rf /home/jenkins/webrtc-c-storage-master/build
> rm -f  /home/jenkins/webrtc-c-storage-master/.last-commit \
>        /home/jenkins/webrtc-c-storage-master/.webrtc-c-version \
>        /home/jenkins/webrtc-c-storage-master/.build-flags
> ```

**Verify the binary is healthy (guards against bit rot):**
```bash
ls -la /home/jenkins/webrtc-c-storage-master/build/kvsWebrtcStorageSample
/home/jenkins/webrtc-c-storage-master/build/kvsWebrtcStorageSample 2>&1 | head -3; echo "exit=$?"
# exit code just needs to NOT be a segfault (139). A clean/optional-usage exit is fine.
```

---

## Phase 8 — Cron trigger + viewer wait window

A first-time full rebuild far exceeds the default 20-minute viewer wait window, so
the viewer times out before the master is ready. Widen it in the parameterized
cron line.

Current no-camera synthetic-media scenario:
```
H/12 * * * * %RUNNER_LABEL=RpiStorageWithViewer;SCENARIO_LABEL=StorageWithViewer;JS_STORAGE_VIEWER_JOIN=true;DURATION_IN_SECONDS=156;MASTER_NODE_LABEL=rpi5-master;STORAGE_VIEWER_NODE_LABEL=test-webrtc-storage-viewer;AWS_DEFAULT_REGION=us-west-2;VIEWER_WAIT_MINUTES=120;GIT_HASH=rpi5-sample;STS_DURATION_SECONDS=3600
```

Key params:
- `VIEWER_WAIT_MINUTES=120` — viewer waits up to 2h for the master to build. The
  master build itself has no timeout wrapping it; this is the only gate.
- `STS_DURATION_SECONDS=3600` — role-chaining cap (Phase 6).
- **[FUTURE]** add `CANARY_MEDIA_SOURCE=devicesrc` once the camera is installed
  (Phase 9) to switch from disk frames to live capture (and derive `gst=ON`).

> 📌 `SCENARIO_LABEL` must be a value the Java consumer recognizes (e.g.
> `StorageWithViewer`); the consumer rejects unknown labels. Metric isolation is
> done via `RUNNER_LABEL`, not by inventing new `SCENARIO_LABEL` values.

> 📌 Because the build stamps are only written on a *successful* build, a failing
> build never caches — every run re-attempts a full rebuild until it succeeds.
> That's why Phase 7's "build only the storage target" fix matters: it lets the
> build return 0, write the stamps, and signal `MASTER_READY`.

---

## Phase 9 — [OPTIONAL / FUTURE] Real-camera scenario

**Not applicable to the current build — no camera is installed.** Kept here so the
SOP is complete when hardware arrives.

**Why:** every current scenario streams pre-encoded frames from disk — no node
exercises a real camera, hardware encoder, or live-capture pipeline. A Pi with a
camera would uniquely provide real-device + real-camera + real-network coverage.

1. Install the CSI camera (v3 or HQ), then software. Note the split between
   **build-time** (dev headers) and **runtime** (the actual GStreamer elements):
   ```bash
   # build-time (needed for gst=ON to compile):
   sudo apt-get install -y libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev
   # runtime elements (needed for the pipeline to actually run):
   sudo apt-get install -y libcamera-tools \
     gstreamer1.0-plugins-base gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
     gstreamer1.0-plugins-ugly gstreamer1.0-tools
   ```
   ⚠️ **`x264enc` lives in `gstreamer1.0-plugins-ugly`** (libx264 is GPL / H.264
   is patent-encumbered, so Debian isolates it in the "ugly" set). A clean
   `gst=ON` build only needs the dev headers, so it succeeds even without the
   runtime encoder — then at run time the pipeline dies with
   `no element "x264enc"` and every sender report is `0 packets 0 bytes`.
   Verify the encoder is present before running:
   ```bash
   gst-inspect-1.0 x264enc   # must print element details, not "No such element"
   ```
2. Build with `gst=ON` (Phase 7); the media source becomes
   `libcamerasrc ! x264enc ! SDK` instead of `readFile(frame-NNNN.h264)`.
   `x264enc` is a **runtime** plugin loaded dynamically — installing it does NOT
   require rebuilding the binary; just re-run.
3. ⚠️ **The Pi 5 has NO hardware H.264 encoder** (unlike the Pi 4) — encoding is
   software (`x264enc`, H.264/AVC) on 4 cores. Do NOT use `x265enc` (H.265/HEVC):
   WebRTC and KVS only negotiate/accept H.264. Budget CPU: drop resolution/fps as
   needed and leave headroom for the canary's other work.
4. **Verification must change:** SSIM-vs-source (`verify.py`) needs a reference
   frame, which a live camera lacks. Replace with liveness checks (frames
   arriving, fps/bitrate thresholds, fragment continuity), or aim the camera at a
   known test pattern.
5. New labels + a separate cron line so camera metrics stay distinct from the
   synthetic-media metrics.

---

## Phase 10 — [OPTIONAL] TWCC network-shaping scenario

Exercises the media service's TWCC (bitrate adaptation) on the ingest path by
shaping the master's uplink with a netns "mid-path router" + `tc/netem`, so the
(TWCC-aware) master's encoder must track the available bandwidth. Wired into the
canary via the `CANARY_TWCC_SHAPING` param on `storage_runner.groovy` /
`gamma_runner.groovy`; no separate runner.

**Provisioning (done automatically by `rpi-onboard.sh` Phase 3b):**
- `/usr/local/bin/twcc-net` — the single root-owned wrapper (`up|down|throttle-start|
  throttle-stop|run`) that does all the netns/tc/NAT. Source: `scripts/twcc/twcc-net.sh`.
- `/etc/sudoers.d/twcc-canary` — `jenkins ALL=(root) NOPASSWD: /usr/local/bin/twcc-net`
  (validated with `visudo -c`). This is the ONLY root capability granted to `jenkins`;
  the wrapper's `run` drops back to `jenkins` before exec'ing the master, so it is not
  a general root-escalation vector.
- `iptables` + `gstreamer1.0-plugins-ugly` (x264enc) + gst dev packages (added to the
  Phase 3 apt install) — required for NAT and the live software encoder.

> ⚠️ **Dedicate a node.** TWCC needs the GStreamer build (`CANARY_MEDIA_SOURCE=testsrc`,
> `gst=ON`); the disk-frame canary is `gst=OFF`. They share one `~/webrtc-c-storage-master/build`,
> so a node that alternates between them rebuilds from scratch every run (the `.build-flags`
> stamp flips). Run TWCC on its own label (e.g. `rpi5-twcc`), not a disk-path node.

**Verify the grant (as jenkins, e.g. through the reverse tunnel):**
```bash
sudo /usr/local/bin/twcc-net up && sudo ip netns exec kvsns ping -c1 1.1.1.1 && sudo /usr/local/bin/twcc-net down
```
No password prompt + a successful ping = wrapper + sudoers + NAT are good.

**Cron params** (in addition to the Pi defaults `STS_DURATION_SECONDS=3600`,
`VIEWER_WAIT_MINUTES=120`): `CANARY_TWCC_SHAPING=true;CANARY_MEDIA_SOURCE=testsrc;
TWCC_MIN_VIDEO_BITRATE_KBPS=100;RUNNER_LABEL=TwccShaped` (isolate the TWCC metrics via
`RUNNER_LABEL`; keep `SCENARIO_LABEL=StorageWithViewer`, a consumer-recognized value).
Metrics emitted: `OutgoingBitrate` / `EstimatedBitrate` / `DelayTrend`.

---

## Quick troubleshooting

| Symptom | First check |
|---|---|
| Node offline in Jenkins | `systemctl status rpi-jenkins-tunnel` (the tunnel is the lifeline) |
| SSM `TargetNotConnected` | SSH in via the reverse tunnel, then `sudo systemctl restart amazon-ssm-agent` (or `sudo snap restart ...`) |
| Programs segfault en masse | `ldd` the victims for a common shared lib → verify against the official `.deb` (wget + `dpkg -x` + md5) → repair with `dd` (NOT `cp`) → plan media replacement |
| Build rebuilds every run | check `.build-flags` is stable (gst value not flipping) |
| Build fails at link (`undefined reference`) | confirm the script builds only the `kvsWebrtcStorageSample` target |
| Build fails at link (`malformed archive`) | orphaned/interrupted build left a half-written `.a` → full reset (Phase 7 discipline block) |
| Viewer times out waiting for master | `VIEWER_WAIT_MINUTES` too low for a from-scratch build (use 120) |
| Pipeline error `no element "x264enc"`, sender `0 packets 0 bytes` | runtime H.264 encoder missing → `sudo apt-get install -y gstreamer1.0-plugins-ugly`; verify `gst-inspect-1.0 x264enc`. No rebuild needed. |
| `/dev/dri/*` Permission denied (MESA/Vulkan) | jenkins not in GPU groups → `sudo usermod -aG video,render jenkins` (new session to take effect). Usually a non-fatal SW-fallback warning for software encode. |
| `sudo` fails as jenkins | expected — jenkins is non-sudo by design; run system commands as `<sudo-user>` |

---

## Cross-references

- `RASPBERRY_PI_CANARY_ROADMAP.md` (repo root) — design rationale, network
  topology, credential-chain internals, and the full incident log.
- `canary/webrtc-c/docs/rpi5-security-review.md` — trust chains and security
  follow-ups (e.g. scoping Key A with `restrict,port-forwarding`).
- `canary/webrtc-c/scripts/build-storage-master.sh` — the cached build script.
- `canary/webrtc-c/jobs/storage_runner.groovy` — the runner (viewer wait loop,
  `VIEWER_WAIT_MINUTES`, `MASTER_READY` signaling).

---

## Appendix — one-line checklist (for the experienced operator)

Terse core-path checklist. Each item maps to a phase above; open the phase for the
actual commands and caveats. Camera items omitted (not installed).

- [ ] **P0** Boot media is NVMe/SSD (not a cheap USB stick / SD card).
- [ ] **P1** Flashed 64-bit Raspberry Pi OS; `uname -m` = aarch64; SSH + WiFi preset.
- [ ] **P1** `debsums -s` baseline clean right after flash.
- [ ] **P2** SSM agent registered; `describe-instance-information` shows Online.
- [ ] **P3** `jenkins` user created (locked-password, non-sudo); JDK 21 + toolchain + **aarch64** AWS CLI v2.
- [ ] **P4** Key A (Pi→jump) + Key B (jump→Pi) exchanged; `rpi-jenkins-tunnel.service` enabled; `ssh -p 2222 jenkins@localhost hostname` works from jump.
- [ ] **P5** `agent.jar` copied; node `rpi5-01` created (remote root `/home/jenkins/Jenkins`, label `rpi5-master`); shows online; survives reboot.
- [ ] **P6** IoT X.509 chain wired (`credential_process`); `sts get-caller-identity` → `rpi5-canary-bootstrap`. No static keys anywhere.
- [ ] **P7** One-at-a-time warm-up build succeeds; `kvsWebrtcStorageSample` exists and doesn't segfault; stamps written.
- [ ] **P8** Cron line set with `VIEWER_WAIT_MINUTES=120` + `STS_DURATION_SECONDS=3600`; `SCENARIO_LABEL` is a consumer-recognized value.
- [ ] **P9** [FUTURE] camera — skip until hardware installed.
