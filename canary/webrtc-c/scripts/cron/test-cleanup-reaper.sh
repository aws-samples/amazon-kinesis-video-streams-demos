#!/bin/bash
# Regression suite for the cleanup-common.sh workspace reaper. Builds fake workspaces
# in a throwaway $HOME and asserts which ones survive. Run before deploying any change
# to the reaper:  bash test-cleanup-reaper.sh ./cleanup-common.sh
set -uo pipefail

LIB="$1"
T=$(mktemp -d /tmp/reaper-test.XXXXXX)
export HOME="$T"
mkdir -p "$T/Jenkins/workspace"

PASS=0; FAIL=0
PIDS=()

# Make a workspace. $1=name  $2=age_min  $3=in_use(none|fresh|stale)
#                   $4=durable(none|finished|running|crashed)  $5=durable_age_min
mkws() {
  local name="$1" age="$2" inuse="$3" dur="$4" durage="${5:-30}"
  local ws="$T/Jenkins/$name"
  mkdir -p "$ws"
  case "$inuse" in
    fresh) touch "$ws/.in_use" ;;
    stale) touch "$ws/.in_use"; touch -t "$(date -v-300M +%Y%m%d%H%M)" "$ws/.in_use" ;;
  esac
  if [ "$dur" != none ]; then
    local dd="$ws@tmp/durable-$(openssl rand -hex 4)"
    mkdir -p "$dd"
    printf 'sleep 120\n' > "$dd/script.sh"
    case "$dur" in
      finished) echo 0 > "$dd/jenkins-result.txt" ;;
      running)  ( cd / && exec sh "$dd/script.sh" ) >/dev/null 2>&1 & PIDS+=($!) ;;
      crashed)  : ;;   # no result file, no process
    esac
    touch -t "$(date -v-${durage}M +%Y%m%d%H%M)" "$dd"
    touch -t "$(date -v-${age}M +%Y%m%d%H%M)" "$ws@tmp"
  fi
  touch -t "$(date -v-${age}M +%Y%m%d%H%M)" "$ws"
  echo "$ws"
}

check() { # $1=path $2=expect(gone|kept) $3=label
  local what="kept"; [ -d "$1" ] || what="gone"
  if [ "$what" = "$2" ]; then PASS=$((PASS+1)); printf '  ok    %-46s -> %s\n' "$3" "$what"
  else FAIL=$((FAIL+1)); printf '  FAIL  %-46s -> %s (expected %s)\n' "$3" "$what" "$2"; fi
}

echo "=== Case group 1: per-workspace vetoes (no long-running step on node) ==="
A=$(mkws live-soak            200 fresh running 200)   # will re-test in group 2
B=$(mkws finished-bounded     120 none  finished 100)
C=$(mkws fresh-inuse          120 fresh none)
D=$(mkws stale-inuse-no-proc  120 stale crashed 100)
E=$(mkws no-marker-old        120 none  none)
F=$(mkws too-young             10 none  none)
# Remove the long-running live one so group 1 tests the per-workspace path only.
pkill -f "$T" 2>/dev/null; sleep 0.6
rm -rf "$A" "$A@tmp"

( set -uo pipefail; CLEANUP_TAG=test; . "$LIB"; reap_workspaces "$HOME/Jenkins"/webrtc-* "$HOME/Jenkins"/*-* ) >/tmp/g1.log 2>&1
check "$B" gone "finished bounded build (has result file)"
check "$C" kept "fresh .in_use"
check "$D" gone "crashed build: stale .in_use, no live process"
check "$E" gone "no marker at all, old"
check "$F" kept "younger than WS_MIN_AGE_MIN"

echo
echo "=== Case group 2: STRUCTURAL soak guard (live step > LONG_RUN_MIN) ==="
G=$(mkws soak-ws              200 stale running 200)   # .in_use deliberately STALE
H=$(mkws bystander-stale      120 none  none)          # would be reaped in group 1
sleep 0.3
( set -uo pipefail; CLEANUP_TAG=test; . "$LIB"; reap_workspaces "$HOME/Jenkins"/*-* ) >/tmp/g2.log 2>&1
check "$G"      kept "SOAK workspace (stale .in_use, live 200min step)"
check "$G@tmp"  kept "SOAK @tmp sibling (no .in_use of its own)"
check "$H"      kept "bystander: whole tick skipped while node busy"
grep -q "skipping ALL workspace reaping" /tmp/g2.log && { PASS=$((PASS+1)); echo "  ok    global veto logged"; } || { FAIL=$((FAIL+1)); echo "  FAIL  global veto NOT logged"; }

echo
echo "=== Case group 2b: the 120-180min handoff window ==="
# A top-level agent workspace: NO durable dir of its own (no sh runs there during a
# soak) and a .in_use that expired at 120min. Its only possible protection is the
# node-wide veto, so that veto must already be firing before .in_use goes stale.
pkill -f "$T" 2>/dev/null; sleep 0.6
K=$(mkws toplevel-ws 150 stale none)              # stale .in_use, no durable dir
L=$(mkws soak-master-ws 150 fresh running 100)    # the soak's own sh, 100min old
sleep 0.3
( set -uo pipefail; CLEANUP_TAG=test; . "$LIB"; reap_workspaces "$HOME/Jenkins"/*-* ) >/tmp/g2b.log 2>&1
check "$K" kept "top-level ws at 150min (only the node-wide veto can save it)"
check "$L" kept "soak master ws"
grep -q "skipping ALL workspace reaping" /tmp/g2b.log && { PASS=$((PASS+1)); echo "  ok    node-wide veto fired at 100min (< IN_USE_MAX_AGE_MIN)"; } || { FAIL=$((FAIL+1)); echo "  FAIL  veto did NOT fire before .in_use expired"; }

echo
echo "=== Case group 2c: invariant violation is clamped, not silently accepted ==="
pkill -f "$T" 2>/dev/null; sleep 0.6
M=$(mkws bad-config-ws 150 stale none)
N=$(mkws bad-config-soak 150 fresh running 100)
sleep 0.3
( set -uo pipefail; CLEANUP_TAG=test; LONG_RUN_MIN=180; IN_USE_MAX_AGE_MIN=120; . "$LIB"; reap_workspaces "$HOME/Jenkins"/*-* ) >/tmp/g2c.log 2>&1
grep -q "clamping LONG_RUN_MIN" /tmp/g2c.log && { PASS=$((PASS+1)); echo "  ok    bad LONG_RUN_MIN clamped + warned"; } || { FAIL=$((FAIL+1)); echo "  FAIL  invariant not enforced"; }
check "$M" kept "top-level ws survives despite bad config"

echo
echo "=== Case group 3: REAP_WORKSPACES=0 ==="
pkill -f "$T" 2>/dev/null; sleep 0.6
I=$(mkws off-switch 120 none none)
( set -uo pipefail; CLEANUP_TAG=test; REAP_WORKSPACES=0; . "$LIB"; reap_workspaces "$HOME/Jenkins"/*-* ) >/tmp/g3.log 2>&1
check "$I" kept "reaping disabled by config"

echo
echo "=== Case group 4: DRY_RUN ==="
J=$(mkws dry-run 120 none none)
( set -uo pipefail; CLEANUP_TAG=test; DRY_RUN=1; . "$LIB"; reap_workspaces "$HOME/Jenkins"/*-* ) >/tmp/g4.log 2>&1
check "$J" kept "DRY_RUN deletes nothing"
grep -q "DRY_RUN would remove" /tmp/g4.log && { PASS=$((PASS+1)); echo "  ok    DRY_RUN logged intent"; } || { FAIL=$((FAIL+1)); echo "  FAIL  DRY_RUN did not log intent"; }

echo
echo "=== Case group 5: safe_rmrf path guards ==="
mkdir -p "$T/outside/thing" "$T/Jenkins/legit"
( CLEANUP_TAG=test; . "$LIB"; safe_rmrf "$T/outside/thing" ) >/tmp/g5a.log 2>&1
check "$T/outside/thing" kept "refuses path outside \$HOME/Jenkins"
( CLEANUP_TAG=test; . "$LIB"; safe_rmrf "$HOME/Jenkins" ) >/tmp/g5b.log 2>&1
check "$T/Jenkins" kept "refuses the Jenkins root itself"
ln -s "$T/Jenkins/legit" "$T/Jenkins/webrtc-link"
( CLEANUP_TAG=test; . "$LIB"; safe_rmrf "$HOME/Jenkins/webrtc-link" ) >/tmp/g5c.log 2>&1
check "$T/Jenkins/legit" kept "refuses a symlink"
( CLEANUP_TAG=test; HOME=""; . "$LIB"; safe_rmrf "/Jenkins/x" ) >/tmp/g5d.log 2>&1
grep -q "REFUSING" /tmp/g5d.log && { PASS=$((PASS+1)); echo "  ok    refuses when HOME is empty"; } || { FAIL=$((FAIL+1)); echo "  FAIL  did not refuse empty HOME"; }

pkill -f "$T" 2>/dev/null
echo
echo "PASS=$PASS FAIL=$FAIL"
rm -rf "$T"
[ "$FAIL" -eq 0 ]
