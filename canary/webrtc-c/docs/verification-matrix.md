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

## Soak 的媒体源必须是无尽的

一个会 EOS 的源在 soak 里不会结束 run —— master 仍然活着(`sampleDuration=0`),只是不再有媒体。
2026-08-31 那次 soak 就是这样:framesrc 在 163s EOS,之后 42 分钟 RTP 计数器一动不动,
consumer 侧 `FragmentReceived` 掉 0,长得和服务端 ingest 故障一模一样。

现在 `framesrc` 在 `CANARY_CONTINUOUS` 下会循环(`multifilesrc loop=true`)。有界 run 的
pipeline 完全没变 —— 它反而**需要**源 EOS 来触发时长终止。

音轨在 soak 下换成 `audiotestsrc wave=white-noise`(`wavparse` 接不了循环字节流,回卷时会在
数据中间看到第二个 RIFF 头)。音频**内容**无人校验(verify.py 纯视频,soak 的 ffmpeg 用 `-an`),
所以换合成音不损失覆盖。

**但波形的选择不是随意的**,因为音频码率本身是一条真实的 TWCC 信号:2026-08-31 那次 soak 里
wav 音轨跟着 TWCC 目标从 108 降到 16.5 kbps,包率恒定 50pps,只有每包字节数在缩。

`opusenc` 默认 `bitrate-type=constrained-vbr`,所以 `bitrate` 是**上限而非强制值**——Opus 只花
内容需要的比特数,目标只有在成为**紧约束**时才会出现在输出里。实测 5s / 48kHz 立体声,
目标 16k → 128k(8×):

| wave | 16k → 产出 | 128k → 产出 | |
|---|---|---|---|
| `ticks` | 9.7k | **43.1k** | 4.5×,且削顶:它不需要 ~43k 以上,`MAX_AUDIO_BITRATE_BPS`=128k 的上半量程完全不可见 |
| `white-noise` | 14.4k | 129.2k | ≈1:1 读出目标 |
| `sine` | 16.6k | 129.4k | 同样可用 |

白噪声不可压缩 → 永远想要比任何目标更多 → 始终被目标钳住。这也优于真实音频(其码率是内容
相关的,安静段落无论目标多高都读得低)。

> ⚠️ **`is-live=TRUE` 必须加**,和 testsrc/camerasrc 两条管道一致。不加的话 audiotestsrc
> 是非 live 源,会以下游能接受的最快速度灌 buffer,配上 `queue leaky=2` 和 `sync=TRUE` 的
> sink,这条分支只送出一两个包就卡死。同一视频分支下实测 25 秒:不加 → **574 字节**;
> 加了 → **199,640 字节(约 64 kbps)**;有界路径的 wav 分支 → 196,645 字节。
> soak build #5281 就是漏了它,**整个 run 一包音频都没发出去**
> (`RtpAudioPacketsSentPerSecond` 取值集合只有 `{0}`,音频 SSRC 恒为 0 packets/0 bytes),
> 而同期视频发了 384,498 包 / 331 MB / 2.3 Mbps。

> ⚠️ 既有问题:`testsrc` / `camerasrc` 一直用 `wave=ticks`,所以四个 TWCC 每条件 job
> (全部跑在 `CANARY_MEDIA_SOURCE=testsrc`)的音频自适应量程是削顶的。一行改动可修,
> 但会移动那些 job 的音频指标基线。

| 源 | 能做 soak? |
|---|---|
| `framesrc` | ✅ 循环(唯一同时支持 SSIM 的) |
| `testsrc`, `devicesrc`, `camerasrc`, `rtspsrc` | ✅ 活源,永不结束(仅 presence 模式) |
| `filesrc` | ❌ 文件放完即静默(会打 WARN);`disk` 非 gst 路径,自身会循环但没有 encoder 给 TWCC 调 |

## Drift metric的发射路径(容易混)

`verify.py` ssim 模式总会在 JSON 里输出 `avg/max_drift_seconds`;但发成 CloudWatch 指标
(`FrameTimestampDriftSeconds`/`Max`)**只有 consumer 的 `runVerifyScript()`(soak 路径)做**。
bounded 的 runner verify stage 只解析 `storage_availability` —— drift 只在日志/JSON 里可见。

## Soak 验证的非阻塞保证

拉流线程只做 I/O;ffmpeg 与 verify.py 都是 `nice -19` 独立子进程;段 worker 单线程
fixed-delay(verify 永不并发);积压 >5 段丢最旧(`SoakSegmentSkipped`);verify 600s 超时强杀。
ListFragments/heartbeat 线程完全隔离。
