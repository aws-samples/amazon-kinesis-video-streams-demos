# Soak 自恢复设计（Phase 17）

检测 soak 组件死亡 → 彻底终止 → 重启。写于 2026-09-03，基于 soak build #5281 的审计。

---

## 0. 为什么现在必须做

两件事让这个从「nice to have」变成「必需」：

1. **master 的 reconnect 行为被有意保持与 SDK sample 一致**（裸 `CHK_STATUS`，15s 超时预算，无外层重试）。这是个明确决定 —— 好处是升级 SDK 时不用反复对齐补丁，代价是**进程内部没有任何韧性**。韧性因此只能来自编排层，也就是本文。
2. **组件死亡是静默的。** #5281 里 master 从 01:29 起实质死亡，consumer 和 viewer 空转 **8h43m**，靠人手动 abort。所有信号都在（`FragmentReceived` 01:29 就掉 0），没有任何东西去读。

`SOAK_MODE` 目前只做了一件事：把 Jenkins 超时抬到 30 天。它让 soak 能长跑，没给它任何自愈能力。

---

## 1. 先分清「死亡」是什么

三个组件各自会死，而且**互相隔离**（这是好的设计，别改）：

- `Single Viewer with Continuous Master` 那个 parallel 块**没有 `failFast`**
- `runViewerSessions` 用 `unstable err.toString()` 而不是 `error`

所以 viewer 挂了不会毁掉 ingest 测试 —— build 只是 UNSTABLE。**但这也意味着 viewer 的死亡完全静默**：可能第 3 小时 OOM，soak 继续跑 6 天，egress 一个数据点都没有，而 build 状态只是 UNSTABLE，没人会看。

| 层 | 现象 | Jenkins 认为 | 现在会自愈吗 |
|---|---|---|---|
| **L1** 单段/单会话失败 | 指标出现 0 | BUILDING | ✅ viewer 有 `while(true)` 循环，2 秒后重试 |
| **L2** 媒体停了但进程活着 | RTP 计数器冻结 | **BUILDING** | ❌ **#5281 就是这层** |
| **L3** 进程死了 | 无 | BUILDING（其他 stage 还在） | ❌ |
| **L4** pipeline 崩了 / 被 abort | FAILURE/ABORTED | 已结束 | ❌ |
| **L5** 节点掉线 | agent offline | stage 挂起 | ❌ |

**L2 是关键**：任何基于 `isBuilding()` 的判断（包括 `Skip if duplicate` 的 dedup）在这一层必然说谎。这就是为什么**周期性 cron 触发 + dedup 挡重复是错的机制** —— 它测的是「pipeline 在不在 building」，而失效形态恰好是「building 但没媒体」。

---

## 2. 检测判据（逐组件）

| 组件 | 指标 | 发射周期 | 判据 | 为什么用这个 |
|---|---|---|---|---|
| **master 在推流** | `FragmentReceived` | 20s | 最近 5 min SUM == 0 | 唯一端到端证明「媒体真的进了 KVS」 |
| **consumer 活着** | `PersistenceStreamingAvailability` | 60s | 连续 3 个周期无数据点 | consumer 自己发的，它死了就停 |
| **viewer 活着** | `ActiveViewersPerSession` | 60s | 跨 **2 个 recycle 周期**（~80 min）SUM == 0 | viewer 自己发的；必须跨 recycle 周期，否则段间隙会误报 |

### 不要用的指标

- **`MasterStreamingAvailability`** —— 判据是 `ATOMIC_LOAD_BOOL(&connected)`（`Common.cpp:463`），量的是 peer connection 状态，**不是有没有帧在流**。名实不符。会话 connected 但管道停滞时它照报 1.0。
- **`PipelineKeepAlive`** —— stage 心跳，soak 下那些 stage 永不完成，所以根本不发（`alarm-sop.md` §7 已记）。

### 诊断矩阵（区分是谁死了）

`FragmentReceived` 停了可能是 master 不推了，也可能是 consumer 死了没人发。用第二个指标消歧：

| `PersistenceStreamingAvailability` | `FragmentReceived` | `ActiveViewersPerSession` | 结论 |
|---|---|---|---|
| 有 | 有 | 有 | 健康 |
| 有 | **0/缺** | 有 | **master 不在推流**（L2/L3） |
| **缺** | 缺 | 有 | **consumer 死了** |
| 有 | 有 | **缺** | **viewer 死了** |
| 全缺 | | | 节点/controller 级故障（L5），watchdog 自己可能也受影响 |

---

## 2.5 前置改动：让组件失败真的结束 build（已实现）

在做 watchdog 之前必须先修一个根本阻塞点。`withRunnerWrapper` 过去把**所有**组件失败
都吞成 UNSTABLE：

```groovy
try { fn() }
catch (FlowInterruptedException err) { HAS_ERROR = true; unstable err.toString() }
catch (err)                          { HAS_ERROR = true; unstable err.toString() }
```

后果链：master 二进制以 0x0f 退出 → `sh` 失败 → 抛异常 → **被吞** → build 继续 →
永远不会变成 FAILURE → **`failFast` 永不触发**（它只对 FAILURE 生效）→ 其他组件继续跑。
这就是 #5281 里 master 明明退出了、日志却还接着推 `MasterFinished` 心跳的原因。

改动（`storage_runner.groovy` + `gamma_runner.groovy`）：

1. `withRunnerWrapper` 在 `SOAK_MODE=true` 时**重新抛出**，包括
   `FlowInterruptedException`（不重抛中断的话 failFast 无法中止兄弟分支，用户也无法
   abort build）。有界 run 保持原样 —— 那里一个 viewer 失败不该阻止其余部分报出自己的
   per-run 指标。
2. 四个 continuous-master 的 parallel 块加 `failFast true`。对有界 run 是**休眠的**：
   它们的失败仍被吞成 UNSTABLE，永远到不了 FAILURE，所以 failFast 不会触发。只有
   SOAK_MODE 下才活起来。

于是「任一组件失败 → 整个 build 迅速结束」成立，watchdog 才有一个明确的信号可用。

---

## 3. 机制：Jenkins 主动轮询 CloudWatch

一个独立的小 Jenkins job（`soak-watchdog`），cron `*/5`，跑在**任意有 AWS 凭证的 EC2 节点**上。

```
soak-watchdog（*/5）
 ├─ 1. aws cloudwatch get-metric-statistics × 3（上表三个指标，dimension RunnerLabel=<soak>）
 ├─ 2. 查诊断矩阵 → 健康 / 谁死了
 ├─ 3. 健康 → 什么都不做，退出（几秒，释放 executor）
 └─ 4. 不健康：
      ├─ a. 查重启预算（见 §4）；超了 → 发 SoakRestartBudgetExhausted=1，不重启，退出
      ├─ b. 查上一个 build 是不是【人】主动 abort 的（见 §5）；是 → 不重启
      ├─ c. abort 正在跑的那个 build   ← 必需，见下
      ├─ d. build job: 'webrtc-test-runner', parameters: [...soak 参数...], wait: false
      └─ e. 发 SoakRestarted=1（带 dimension 标明是哪个组件触发的）
```

### 为什么方向是「出向」而不是告警→Lambda

「CloudWatch Alarm → SNS → Lambda → 调 Jenkins API」是教科书答案，但在这个拓扑里成本高一个数量级：Pi 在 NAT 后、Jenkins controller 只有 UI 暴露在 corp:1443（见 `project_jenkins-fleet-topology`）。Lambda 要进 corp 网需要 VPC 连接 + 路由 + API token 管理。

Jenkins 主动查 CloudWatch 是**出向**的，不需要开任何入口。代价是检测延迟 5–15 分钟（告警可以做到 1–2 分钟）。**先做轮询，如果延迟不够再上告警驱动。**

### 为什么必须先 abort 再触发（步骤 c）

`Skip if duplicate` 用 `RUNNER_LABEL` + `isBuilding()` 判重。L2/L3 时 build 还在 BUILDING，所以直接触发会被当成重复跳掉。**必须先 abort。**

### 一个前提条件，现在才刚满足

abort 要能**及时**完成。在 `b30f9daa` 之前，master 的 teardown 可能卡在 `THREAD_JOIN` 上（#5281 卡了 3h12m）—— abort 一个这样的 build 会让它继续占着两个 executor 好几小时，watchdog 每 5 分钟醒来一次，队列越堆越长。

`b30f9daa` 把 teardown 上限压到 ~16 秒，**这才让 Q4 可行**。这是个真实的依赖关系，不是巧合。

---

## 4. 重启预算（必需，不是可选）

没有预算，一次服务端故障会变成无限重启循环，而且把真正的问题藏起来。

- **上限**：滚动 24 小时内最多 **3 次**（建议值，待定）
- **状态存哪**：不需要额外存储 —— 查我们自己发的 `SoakRestarted` 指标在过去 24h 的 SUM。**无状态，天然幂等**
- **超预算时**：停止重启，发 `SoakRestartBudgetExhausted=1`

> 「soak 反复起不来」比「soak 死了一次」更需要人介入。所以 `SoakRestartBudgetExhausted` 才是应该 page 人的那个指标，而不是 `SoakRestarted`。

这是 Q4 对 Phase 9 的**唯一**依赖 —— 检测本身不依赖告警（watchdog 直接查指标）。所以 **Q4 不被 Phase 9 阻塞，可以现在就做**，只是升级路径要等 Phase 9。

---

## 5. 区分主动停止和崩溃

有人手动 abort 去做维护时，watchdog 不能跟人抢。

判据：上一个 build 的中断原因。用户主动 abort 会带 `hudson.model.CauseOfInterruption$UserInterruption`（#5281 的 viewer 日志尾部就有 `Aborted by yuuqih`）；watchdog 自己 abort 的可以带一个可识别的 cause 或 build description 标记。

规则：
- 上一个 build 是 **用户** abort → **不重启**（人在操作）
- 上一个 build 是 **watchdog** abort → 重启（预期流程）
- FAILURE / 系统性 ABORTED → 重启

---

## 6. Executor 需求（比之前估计的低）

| | 需要 |
|---|---|
| soak master 节点（Pi） | **2** —— 顶层 agent + 嵌套 `ViewerContinuousMaster` 的 agent（这就是历史上那个 1-executor 自死锁，见 `project_gamma-pileup-root-cause`） |
| watchdog | **1 个瞬时**，在任意 EC2 节点上（**不要放 Pi**） |

### 为什么不用 Jenkins cron 做重启器

考虑过让 soak job 自己挂一条定时触发行，靠已有的 `Skip if duplicate` 判重来实现重启
（§2.5 之后 `isBuilding()` 变准了，所以这个方案在原理上是可行的）。**放弃了**，因为它
和 watchdog 冗余：

- watchdog 的第一条分支「build 没在跑 → 触发」就是 cron 的全部作用
- cron 覆盖不了 L2（媒体停但进程活着，build 仍是 BUILDING，dedup 必然挡掉），而 watchdog
  顺手就覆盖
- 两者都能触发重启 → 重启预算要在两处协调，且会出现重复触发（dedup 挡得住，但日志和
  计数变浑浊）
- cron 触发的 build 需要在 **Pi 上**再占一个 executor 来跑 dedup 检查（→ 需要 3 个）；
  watchdog 在 EC2 上，Pi 保持 2 个就够

结论：**只做 watchdog，不加 cron。**

---

## 7. 要发的新指标

| 指标 | 单位 | 何时发 | 用途 |
|---|---|---|---|
| `SoakRestarted` | Count | 每次重启 | 预算计算（无状态）+ 可见性 |
| `SoakRestartTrigger` | Count | 每次重启 | 带 dimension（master/consumer/viewer）标明谁触发的 |
| `SoakRestartBudgetExhausted` | Count | 预算耗尽 | **这个才是 page 人的** |
| `SoakWatchdogHealthy` | Count | watchdog 每次成功执行 | 看门狗自己的存活（谁看着看门狗？） |

最后一条不能省 —— 一个死掉的看门狗和一个健康的系统在指标上长得一样。

---

## 8. 实施顺序

0. ✅ **§2.5 的两项**（`withRunnerWrapper` 在 SOAK_MODE 下重抛 + 四个 parallel 块加
   `failFast true`）。做完这一步，L3/L4/L5 已经会让 build **干净地结束** —— 还不会自动
   重启，但失效从"静默继续"变成"可见地失败"，这本身就消掉了 #5281 那种 12 小时无人察觉
   的情况。**建议在这里停一下观察一段时间**：`b30f9daa` 把 teardown 压到 ~16 秒之后，
   #5281 那类 L2 会在 16 秒内变成 L3，所以真正剩下的 L2 可能比想象的罕见得多。先看数据
   再决定 watchdog 的判据要多灵敏。
1. **watchdog job 的骨架**：只查指标、只记录判断结果、**不做任何 abort/重启**（`DRY_RUN` 模式）。跑几天，确认判据不误报
2. 加 `SoakWatchdogHealthy` + `SoakRestartTrigger`（仍不重启），验证诊断矩阵在真实故障上给出正确结论
3. 打开 abort + 重启，预算设 1 次/24h
4. 观察一周后放宽到 3 次/24h

第 1、2 步是**只读**的，零风险，但能验证整套判据 —— 这比直接上线自动重启安全得多。

---

## 9. 待定（需要决策）

1. **重启预算**：3 次/24h 是猜的。取决于你能接受多频繁的重启 vs 多快想被叫醒
2. **viewer 死了要不要重启整个 soak？** 重启 master 会中断 ingest 覆盖。也可以只重启 viewer stage —— 但 Jenkins 的 parallel 分支不支持单独重跑，所以实际上只能重启整条 pipeline。**倾向：viewer 死了先只告警不重启**，因为它不影响 ingest 结论
3. **多个 soak 时的 watchdog**：一个 job 轮询所有 `RUNNER_LABEL`，还是每个 soak 一个 watchdog job？倾向前者（一个 job 遍历列表）
4. **检测延迟 5–15 分钟够不够**？如果不够就要上告警驱动，代价是打通 Lambda → Jenkins 的网络路径

---

## 相关

- `alarm-sop.md` §7 —— soak 的告警模型（missing datapoint 视为 breaching；不要对 viewer 每 40 分钟的 recycle blip 报警）
- `verification-matrix.md` —— 哪个 job 触发哪种校验
- `canary-progress-update.md` Phase 17 —— 本文是它的 scope 展开
