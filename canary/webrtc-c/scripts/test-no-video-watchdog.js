#!/usr/bin/env node
// Regression suite for makeNoVideoWatchdog() in chrome-headless.js -- the rule that decides a
// joined viewer session is not receiving media and should be recycled.
//
// Worth testing on its own because both ways of getting it wrong are expensive and neither shows
// up quickly in a soak. Too eager and it recycles healthy sessions during the ~9-31s it takes for
// the first frame to decode after a reconnect, which costs egress coverage in the name of
// protecting it. Too lax and it reproduces the bug: a session that negotiates audio+video, reaches
// `connected`, and then receives nothing for the full 40-minute recycle interval while reporting
// success (docs/viewer-no-video-investigation.md).
//
//   node test-no-video-watchdog.js
//
// Extraction is by marker so edits elsewhere in chrome-headless.js don't break it.

const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(path.join(__dirname, 'chrome-headless.js'), 'utf-8');
// Includes the NO_VIDEO_TIMEOUT_SECONDS declaration, so the "60s" the tests below assert is the
// real default rather than a copy of it that can drift.
const start = src.indexOf('const NO_VIDEO_TIMEOUT_SECONDS');
const end = src.indexOf('// Capture unhandled errors');
if (start < 0 || end < 0 || end <= start) {
  console.error('FAIL: could not locate makeNoVideoWatchdog in chrome-headless.js');
  process.exit(1);
}
// eslint-disable-next-line no-eval
const makeNoVideoWatchdog = eval(
  `(function () { ${src.slice(start, end)}\n return makeNoVideoWatchdog; })()`);

let fail = 0;
let count = 0;
const ok = (cond, msg) => {
  count++;
  console.log((cond ? '  ok    ' : '  FAIL  ') + msg);
  if (!cond) fail++;
};

// Drive a watchdog over a scripted timeline. `steps` is [[secondsFromStart, framesDecoded], ...]
// where framesDecoded of null means "no video stats to read" (peer connection gone).
// Returns the seconds at which a stall was reported and at which video resumed.
function run(steps, timeoutSec = 60) {
  const t0 = 1_000_000;
  const w = makeNoVideoWatchdog(timeoutSec, t0);
  const stalls = [];
  const resumes = [];
  for (const [sec, frames] of steps) {
    const r = w.observe(frames, t0 + sec * 1000);
    if (r.stalled) stalls.push(sec);
    if (r.resumed) resumes.push([sec, Math.round(r.resumedGapSec)]);
  }
  return { stalls, resumes, w };
}

// A poll every 2s, matching the real loop, for `sec` seconds with frames advancing at 30fps.
const healthy = (sec) => {
  const steps = [];
  for (let t = 2; t <= sec; t += 2) steps.push([t, t * 30]);
  return steps;
};

// ---- the healthy cases: these are the ones that cost coverage if they misfire ----------------
{
  const { stalls } = run(healthy(600));
  ok(stalls.length === 0, `10 min of 30fps never stalls (${stalls.length} reports)`);
}
{
  // The measured post-reconnect startup window: nothing decodes for 31s, then video starts.
  // 10/10 of the 2026-09-03 soak's viewer reconnects landed between +9s and +31s.
  const steps = [];
  for (let t = 2; t <= 30; t += 2) steps.push([t, null]);
  for (let t = 32; t <= 120; t += 2) steps.push([t, (t - 30) * 30]);
  const { stalls } = run(steps);
  ok(stalls.length === 0, 'a 31s post-reconnect startup gap does not stall (measured +9s..+31s)');
}
{
  // Frame counters do not advance every poll at low fps; a repeated value inside the timeout is
  // not a stall. STORAGE_FPS=10 is a real cron setting.
  const steps = [];
  for (let t = 2; t <= 300; t += 2) steps.push([t, Math.floor(t * 10)]);
  const { stalls } = run(steps);
  ok(stalls.length === 0, '10fps (counter repeats between polls) never stalls');
}

// ---- the failure cases ------------------------------------------------------------------------
{
  // The docs/viewer-no-video-investigation.md shape: joined, negotiated, zero frames ever.
  const steps = [];
  for (let t = 2; t <= 300; t += 2) steps.push([t, 0]);
  const { stalls } = run(steps);
  // 62s, not 60s: the first reading is a baseline (there is nothing to compare it against), so the
  // clock starts at the first poll rather than at session join. One poll of slack, deliberately not
  // special-cased -- treating a first reading of 0 as "already stalled" would misjudge every
  // session that simply has not started yet.
  ok(stalls.length === 1 && stalls[0] === 62,
    `a session that never decodes a frame stalls once, one poll after 60s (got ${JSON.stringify(stalls)})`);
}
{
  // Absent video stats must not read as "no news": this is the peer connection going away.
  const steps = [];
  for (let t = 2; t <= 300; t += 2) steps.push([t, null]);
  const { stalls } = run(steps);
  ok(stalls.length === 1 && stalls[0] === 60,
    `null video stats still stall at 60s (got ${JSON.stringify(stalls)})`);
}
{
  // Healthy for 100s, then the media stops while the <video> element stays "active".
  const steps = healthy(100);
  for (let t = 102; t <= 300; t += 2) steps.push([t, 3000]);
  const { stalls } = run(steps);
  ok(stalls.length === 1 && stalls[0] === 160,
    `mid-session silence stalls 60s after the last frame (got ${JSON.stringify(stalls)})`);
}
{
  // One report per stall, not one per 2s poll -- otherwise a 40 min outage publishes 1200 points
  // and the log is unreadable.
  const steps = [];
  for (let t = 2; t <= 2400; t += 2) steps.push([t, 0]);
  const { stalls } = run(steps);
  ok(stalls.length === 1, `a 40 min outage reports once, not per poll (${stalls.length} reports)`);
}

// ---- recovery and re-arming -------------------------------------------------------------------
{
  // Stall, then video comes back, then it stops again: both stalls must be reported, and the
  // recovery must be logged with the length of the outage that ended (not the time since reset).
  const steps = [];
  for (let t = 2; t <= 100; t += 2) steps.push([t, 0]);          // stall at 60
  for (let t = 102; t <= 160; t += 2) steps.push([t, t]);        // resumes at 102
  for (let t = 162; t <= 300; t += 2) steps.push([t, 160]);      // stalls again at 220
  const { stalls, resumes } = run(steps);
  // 62 for the first (baseline poll, as above) and 220 for the second, which is 60s after the last
  // frame at 160 -- the second stall is timed from real progress, not from the first report.
  ok(stalls.length === 2 && stalls[0] === 62 && stalls[1] === 220,
    `a second stall is reported after recovery (got ${JSON.stringify(stalls)})`);
  ok(resumes.length === 1 && resumes[0][0] === 102 && resumes[0][1] === 100,
    `recovery reports the outage length, 100s from the baseline poll (got ${JSON.stringify(resumes)})`);
}
{
  // inStall is what suppresses the trailing healthy-path 0. A run that ends inside a stall must
  // not publish a 0 that contradicts the 1 it just published.
  const a = run([[2, 0], [62, 0]]);
  ok(a.w.inStall, 'inStall is true when the run ends mid-stall');
  const b = run([[2, 0], [62, 0], [64, 10]]);
  ok(!b.w.inStall, 'inStall clears once video resumes');
}

// ---- boundary + configurability ---------------------------------------------------------------
{
  const below = run([[58, null]]);
  const at = run([[60, null]]);
  ok(below.stalls.length === 0 && at.stalls.length === 1,
    'the threshold is inclusive at exactly 60s and quiet at 58s');
}
{
  // VIEWER_NO_VIDEO_TIMEOUT_SECONDS exists so a scenario with a slower startup can be given room
  // without editing code; the state machine must honour whatever it is handed.
  const { stalls } = run([[100, null], [200, null]], 150);
  ok(stalls.length === 1 && stalls[0] === 200,
    `a 150s timeout stalls at 200s, not 100s (got ${JSON.stringify(stalls)})`);
}
{
  // A decreasing counter is a fresh peer connection's stats, not progress in reverse; it must not
  // be read as a stall either, since a new stream restarts framesDecoded at 0.
  const steps = healthy(100);
  for (let t = 102; t <= 160; t += 2) steps.push([t, (t - 100) * 30]);
  const { stalls } = run(steps);
  ok(stalls.length === 0, 'a counter reset to a lower value is not a stall');
}

console.log(`\nPASS=${count - fail} FAIL=${fail}`);
process.exit(fail ? 1 : 0);
