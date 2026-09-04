#!/usr/bin/env node
// Regression suite for the async video-verification queue in chrome-headless.js.
//
// The queue is what keeps the viewer's egress coverage continuous: if it ever blocks
// again, the recycle loop stalls at the segment boundary and whole master sessions go
// unwatched (soak #5281 recorded segments 2h+ apart against a 40 min setting). Run
// this before changing anything in that block:
//
//   node test-verify-queue.js
//
// It extracts the real queue code from chrome-headless.js between the VERIFY_QUEUE_MAX
// declaration and the log() definition, then exercises it with stubbed execFile /
// CloudWatchMetrics / log. Extraction is by marker, not line number, so edits above
// the block don't break it.

const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(path.join(__dirname, 'chrome-headless.js'), 'utf-8');
const start = src.indexOf('const VERIFY_QUEUE_MAX');
const end = src.indexOf('function log(message)');
if (start < 0 || end < 0 || end <= start) {
  console.error('FAIL: could not locate the queue block markers in chrome-headless.js');
  process.exit(1);
}
const queueCode = src.slice(start, end);

// ---- stubs -----------------------------------------------------------------
const logs = [];
const pubCalls = [];
const skipCalls = [];
let running = 0;
let maxConcurrent = 0;
const completed = [];

function log(m) { logs.push(m); }
const CloudWatchMetrics = {
  publishCountMetric: async (n, c, v) => {
    pubCalls.push([n, v]);
    if (String(n).includes('Skipped')) skipCalls.push(v);
  },
  publishPercentageMetric: async () => {},
};
let execFile = (cmd, args, opts, cb) => {
  running++;
  maxConcurrent = Math.max(maxConcurrent, running);
  const id = args[args.indexOf('--recording') + 1];
  setTimeout(() => {
    running--;
    completed.push(id);
    cb(null, JSON.stringify({ storage_availability: 1, segments: 1 }));
  }, 60);
};

// Evaluate the real block in this scope so it closes over the stubs above.
// eslint-disable-next-line no-eval
const scope = eval(`(function () { ${queueCode}
  return { enqueueVerification, runVerifyJob, get busy() { return verifyWorkerBusy; },
           get depth() { return verifyQueue.length; } }; })`);
const q = scope.call({});

// ---- assertions ------------------------------------------------------------
let fail = 0;
const ok = (cond, msg) => {
  console.log((cond ? '  ok    ' : '  FAIL  ') + msg);
  if (!cond) fail++;
};
const mk = (id) => ({
  recordings: [id], venvPython: 'py', verifyScript: 'v', sourceFrames: 's',
  mode: 'ssim', channelName: 'ch',
  metrics: { availability: 'Avail', ssimAvg: 'A', ssimMin: 'B', ssimMax: 'C', skipped: 'ViewerVerifySkipped' },
});

(async () => {
  const t0 = Date.now();
  q.enqueueVerification(mk('j1'));
  const elapsed = Date.now() - t0;
  ok(elapsed < 20, `enqueue returns immediately (${elapsed}ms; one job takes 60ms)`);

  q.enqueueVerification(mk('j2'));
  q.enqueueVerification(mk('j3'));
  q.enqueueVerification(mk('j4'));
  q.enqueueVerification(mk('j5'));   // pushes past VERIFY_QUEUE_MAX

  await new Promise((r) => setTimeout(r, 700));
  ok(maxConcurrent === 1, `never more than one verify at a time (peak ${maxConcurrent})`);
  ok(skipCalls.length >= 1, `backpressure emitted ViewerVerifySkipped (${skipCalls.length})`);
  ok(!completed.includes('j2'), `oldest job dropped, not the newest (ran: ${completed.join(',')})`);
  ok(completed.includes('j5'), 'newest job still ran');
  ok(pubCalls.some(([n, v]) => n === 'Avail' && v === 1), 'ViewerStorageAvailability published');

  // A failing verify must not wedge the worker, and must still leave a datapoint --
  // silence here is what let 18 of 19 timed-out segments look like "no data" on the
  // 2026-09-03 soak.
  execFile = (c, a, o, cb) => setTimeout(() => cb(new Error('boom')), 10);
  const before = logs.length;
  const pubBefore = pubCalls.length;
  q.enqueueVerification(mk('j6'));
  await new Promise((r) => setTimeout(r, 300));
  ok(logs.slice(before).some((l) => l.includes('no usable verdict')), 'a failing verify is caught, not thrown');
  ok(pubCalls.slice(pubBefore).some(([n, v]) => n === 'Avail' && v === 0),
    'a failing verify still publishes availability=0');
  ok(!q.busy, 'worker released after a failure');
  ok(q.depth === 0, 'queue drained');

  // A non-zero exit that still printed a verdict must be honoured, not discarded:
  // verify.py's own hard-failure paths emit {"storage_availability": 0, ...}.
  execFile = (c, a, o, cb) => setTimeout(
    () => cb(Object.assign(new Error('exit 1'), {}), JSON.stringify({ storage_availability: 0, segments: 1 })), 10);
  const pubBefore2 = pubCalls.length;
  q.enqueueVerification(mk('j7'));
  await new Promise((r) => setTimeout(r, 300));
  ok(pubCalls.slice(pubBefore2).some(([n, v]) => n === 'Avail' && v === 0),
    'a verdict on stdout is used even when the exit code is non-zero');

  console.log(`\nPASS=${11 - fail} FAIL=${fail}`);
  process.exit(fail ? 1 : 0);
})();
