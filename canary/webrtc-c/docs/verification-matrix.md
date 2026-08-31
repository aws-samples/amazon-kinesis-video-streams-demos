# Media Verification Matrix — which job triggers which verification

As of 2026-08-31 (branch `twcc-canary`). "Verification" = checking the *ingested media content*,
beyond the always-on ListFragments metrics.

## Always on (every job, no flag needed)

Consumer (Java), regardless of verification flags:

| Check | Cadence | Metric |
|---|---|---|
| Fragment continuity (trailing-window ListFragments) | 20s | `FragmentReceived` (1/0) + `IngestionIncomingBitrateKbps` |
| KVS reachability probe | 60s | `PersistenceStreamingAvailability` (1/0 — "KVS up", NOT "ingesting") |
| Time-to-first-frame (one-shot GetMedia at start, periodic labels only) | once | TTFF metric |

## Matrix by job type

| Job type | Video verification | Mechanism | Metrics |
|---|---|---|---|
| **Bounded run, `VIDEO_VERIFY_ENABLED=false`** (most cron canaries) | none | — | ListFragments metrics only |
| **Bounded run, `VIDEO_VERIFY_ENABLED=true`** | **end-of-run, once** | Consumer `downloadClip()` (GetClip, canary start→end, ≤100MB/~10min cap) → runner verify stage runs `verify.py` once | `ConsumerStorageAvailability` (0/1, pushed by runner shell `aws`) |
| **Soak (`SOAK_MODE=true`) + `VIDEO_VERIFY_ENABLED=true`** | **continuous, every 60s of media** | Consumer `SoakStreamVerifier`: GetMedia 连续拉流 → ffmpeg(`-c:v copy`)切 60s 段 → 每段 `verify.py` | `SoakVideoDecodable` (per segment) + `FrameTimestampDriftSeconds`/`Max` + `SoakSegmentSkipped` |
| **Soak without `VIDEO_VERIFY_ENABLED`** | none | — | ListFragments metrics only |
| **Viewer scenarios (`JS_STORAGE_VIEWER_JOIN=true`)** | egress side, end-of-session | chrome-headless MediaRecorder 录 `recordings/viewer-*.mp4`(重连分段)→ viewer 节点跑 `verify.py` | viewer availability 指标(egress 路径,和 consumer 的 ingest 验证互相独立) |

Note: in soak, the end-of-run GetClip + runner verify stage **never run** (there is no end);
`ConsumerStorageAvailability` is NOT emitted — alarm on `SoakVideoDecodable` + `FragmentReceived`
instead (alarm-sop.md §7).

## verify.py mode — decided by media source

Runner picks the mode (bounded: verify stage `verifyMode`; soak: `CANARY_VERIFY_MODE` env):

| `CANARY_MEDIA_SOURCE` | Mode | What is checked |
|---|---|---|
| `disk`, `framesrc` (deterministic, burned-in frame counter) | **ssim** | OCR counter → per-frame SSIM vs source frames; duration + frame-count thresholds (scale with `--expected-duration`); **+ frame-timestamp drift** (`avg/max_drift_seconds`, counter-wrap aware) |
| `testsrc`, `devicesrc`, `camerasrc`, `filesrc`, `rtspsrc` (no reference) | **presence** | decodable + duration + ~expected frame count; no content comparison, no drift |
| (soak, `CANARY_VERIFY_SCRIPT` unset) | ffprobe fallback | container decodable + duration>0 only |

**框架取舍:** TWCC 自适应需要 live encoder(`framesrc`/camera/testsrc 走 x264enc;`disk` 不适应);
SSIM 需要烧录计数器(`disk`/`framesrc`)。**两者兼得 = `framesrc`** —— soak 推荐。

## Drift metric的发射路径(容易混)

`verify.py` ssim 模式总会在 JSON 里输出 `avg/max_drift_seconds`;但发成 CloudWatch 指标
(`FrameTimestampDriftSeconds`/`Max`)**只有 consumer 的 `runVerifyScript()`(soak 路径)做**。
bounded 的 runner verify stage 只解析 `storage_availability` —— drift 只在日志/JSON 里可见。

## Soak 验证的非阻塞保证

拉流线程只做 I/O;ffmpeg 与 verify.py 都是 `nice -19` 独立子进程;段 worker 单线程
fixed-delay(verify 永不并发);积压 >5 段丢最旧(`SoakSegmentSkipped`);verify 600s 超时强杀。
ListFragments/heartbeat 线程完全隔离。
