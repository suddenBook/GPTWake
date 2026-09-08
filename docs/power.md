# 功耗分析 / Power analysis

> 版本范围：下文记录中英 Zipformer KWS 的功耗分析。1.1.0 新增的日语模式使用
> Silero VAD 和独立的 Moonshine 识别线程，不能直接套用下文的推理频率或测量结论。
> 日语的录音线程统计只覆盖 VAD；完整推理开销请结合 `JA_ASR_STATS`、
> `japanese-asr` 线程及进程 CPU 统计。日语长期耗电尚未完成测量。

> 中英模式没有 VAD 或能量门控，encoder 对 100% 的音频无条件推理，
> 包括一整天的安静时间。这是最大的一块可省的功耗，而且它不是"实现得不好"，是**完全不存在**。
>
> The Chinese/English encoder has no VAD or energy gate and runs unconditionally on 100%
> of wall-clock audio, silence included. That is the single largest available saving, and it is not
> partially implemented — it is entirely absent.

本文只做分析和给出选项，**没有改动音频/推理链路的任何代码**（这是本轮明确的范围决定）。
本轮只修了测量工具本身的缺陷，见最后一节。

---

## 1. 现在的链路是什么样

| 项目 | 值 | 位置 |
|---|---|---|
| 采样率 | 16 kHz mono `PCM_16BIT` | `AudioProbe.java:15`, `:123-127` |
| 音源 | `VOICE_RECOGNITION` | `AudioProbe.java:122` |
| 每帧 | `FRAME_SAMPLES = 1280` = **80 ms** | `AudioProbe.java:16` |
| 读取节奏 | 阻塞 `ar.read()` → **约 12.5 次唤醒/秒** | `AudioProbe.java:154` |
| 线程优先级 | `Thread.MAX_PRIORITY - 1` = **9** | `AudioProbe.java:63-68` |
| `Process.setThreadPriority` / 亲和性 | **没有** | — |
| Wakelock | **一个都没持有**（`WAKE_LOCK` 权限声明了但没用）；AP 是被 AudioFlinger 的 record-thread wakelock 拖住的 | `AndroidManifest.xml:9` |
| ONNX 线程 | `numThreads=1`, provider `cpu` | `KwsEngine.java:78-81` |

从 encoder 的 ONNX metadata 读出来的真实节奏：

```
model_type=zipformer2  decode_chunk_len=32  T=45
encoder_dims=128×6  left_context_len=64,32,16,8,16,32
```

- fbank shift 10 ms → 一个 80 ms 帧 = 8 个 fbank 帧
- 每次 decode 前进 `decode_chunk_len = 32` 帧 = **320 ms 音频**
- 所以 **encoder 每 4 次 `accept()` 才真正跑一次 → 3.125 次前向/秒**
- 每次 beam search ≤ 4 条 active path → 最多 **200 次 joiner/秒**

`numThreads=1` 是对的，ORT 没有起线程池（JSONL 里 35 个线程枚举中确实没有 ORT worker）。
**这里没什么可优化的。**

## 2. 最讽刺的一点

RMS **已经在每帧算好了**（`AudioProbe.java:168-175`），但它的消费者只有 UI 的电平条和两条日志。

> 门控 encoder 所需要的信号，每秒被计算 12.5 次，然后被丢掉。

## 3. 现有测量数据为什么不能用

`measurements/` 是 gitignore 的（是本地产物，不进仓库）。写这份文档时，本地只有一个文件
`20260729T010417Z-SMOKE-KWS_EVAL.jsonl`，**5 行，15.108 秒**，内容如下。

```
wallDeltaMs=10002  cpuDeltaMs=1420  cpuOneCorePct=14.20
chargeUAh=6132000  currentNowUA=420000  currentAvgUA=399000
plugged=2          ← 关键
rssKb=250708  totalPssKb=124617  nativeHeapAllocKb=61909
threadCpuDeltaMs={"audio-capture":-1,"power-logger":-1}
```

四个问题，每一个都足以让它作废：

1. **`plugged: 2` — 全程插着 USB。** `currentNowUA = +420000` 是**充电电流**，不是 app 耗电。
   **这个仓库里没有任何一条真实的电池放电数据。**
2. **只有一个 CPU 窗口**，而且这个窗口包含模型加载 + ORT session 创建 + JIT 预热
   （`Jit thread pool` 累计 1630 ms）。`cpuOneCorePct = 14.20` 是**冷启动均值**，不是稳态。
3. **`threadCpuDeltaMs` 全是 `-1`** —— 第一个窗口没有基线，而 run 在第二个采样前就结束了。
4. PSS / native heap 是可信的：122 MB PSS、60 MB native heap（对应 5.4 MB 权重，是 ORT
   arena 过度分配且不归还）。

> ⚠️ README 里写的 **"常驻推理约占 23% 单核"**，在整个仓库里找不到出处。
> 唯一在磁盘上的 CPU 数字是 14.2%（冷启动、插电）。

`testkit.sh:57-87` 里那套 ABBA 协议（`A` = `mic_only=true` 只采集不推理，`B` = 全量 KWS，
每段 10 min 预热 + 30 min 记录，拔掉 USB）**是完全正确的实验设计，但从来没跑过**。

## 4. 可选方案，按性价比排序

### ① 在 `accept()` 前加能量门 / VAD —— 预计省掉 70-90% 的推理 CPU

一个房间一天里有人说话的时间大概只占 5-15%，而 encoder 现在跑 100%。

- **免费版**：直接用 `AudioProbe.java:174` 已经算好的 RMS。自适应噪声底
  （比如 30 s 窗口的 10 分位数），超过 底噪×4 开门，静音 ~800 ms 后关门。
  零新增 CPU、零新依赖，大约 30 行。
- **稳健版**：Silero VAD（~1 MB int8，约 0.5% 单核）。sherpa-onnx v1.13.4 的 `.so` 里
  **已经带了 `VadModelConfig`**，不需要引入新的 native 依赖。

**代价，说清楚：**

- **延迟：如果保留 pre-roll ring buffer，则为零。** 不带 pre-roll 的裸门控会切掉唤醒词的起音，
  召回会崩 —— RMS 通常在第一个音节进行到 50-150 ms 才越过阈值。
- pre-roll 要覆盖的是 **encoder 的循环状态**，不只是音频：`left_context_len` 最大 64 帧、
  `decode_chunk_len` 32 帧，所以**完全干净**的状态需要 96 个 fbank 帧 = **960 ms**
  （12 × 80 ms 帧 = 15 KB `short[]`，可以忽略）。实测通常 300-500 ms 就够，**需要自己测**。
- 另一种做法是每次开门 `newStream()`，状态绝对干净，但每次开门多烧 3 次 encoder 前向 ——
  如果门抖动就是负收益，必须配合 hangover。
- **失败模式是安全的**：门开太松 = 退化成今天的行为，不会更差。

### ② 不要把采集线程钉在大核上 —— CPU 时间不变，但能量可能降 30-50%

`AudioProbe.java:67` 设了 Java 优先级 9（nice ≈ −6…−8），EAS 会把它放到大核并抬高调频。
全项目没有任何 `Process.setThreadPriority` 调用。

预算其实很宽松：每 **320 ms** 的 chunk 只需要约 70 ms 的活。就算换成慢 3 倍的小核
（≈210 ms）也塞得下。这台机器是 **MT6878**，是 big.LITTLE，每指令能耗差通常 2-4 倍。

**代价：** p95/p99 解码延迟上升，`droppedFrames`（`AudioProbe.java:193`）会变成真实风险，必须盯。

> ⚠️ **测量陷阱**：`ThreadCpu` 报的是 CPU **时间**，所以搬到小核之后 `cpuDeltaMs` 会
> **看起来更差**，而实际 mAh 是降的。只有拔掉 USB 的 ABBA 能裁决这件事。

### ③ 一次喂 320 ms 而不是 80 ms —— CPU 约 1-3%，但线程唤醒少 4 倍

现在 `accept()` 每秒调 12.5 次，encoder 只跑 3.125 次 —— 75% 的 JNI 往返、`isReady`、
`float[]` 拷贝纯粹是为了被告知"还没到"。把 `FRAME_SAMPLES` 提到 5120 就对齐了。

**代价：** 最坏情况检测延迟 +240 ms；UI 电平条更粗糙。唤醒次数 12.5/s → 3.1/s，
对 AP idle residency 的意义比 CPU% 大。

### ④ 热路径小清理 —— CPU <1%，主要是减少 GC 压力

- `AudioProbe.java:261` 每帧一次 `String.format` —— 每秒 12.5 次、约 60+ 个垃圾对象，
  在一个永不退出的进程里。改成存 `volatile double`，读的时候再格式化。
- RMS 用 `long` 累加 + 平方阈值比较，省掉 `Math.sqrt`。
- `KwsEngine.java:129,136,138` 每帧 3 次 `AtomicLong` RMW（ARM 上是 LL/SC + 屏障），
  release 里可以编译掉。
- `Measure.java:95` 把 `out == null` 的判断挪到 Runnable 外面。

### ⑤ 两级级联：小模型常开 → 3M zipformer 确认 —— 推理省 5-10 倍

**代价：** 需要自己训或找一个小模型（本文唯一带真正 ML 工作量的选项）；
+320 ms 确认延迟；级联召回是两级召回的**乘积**，stage-1 召回 0.95 就把上限压到
0.95 × 0.80 = 0.76（对比现在实测的 8/10）。复杂度高。**做完 ①② 再考虑。**

### ⑥ `SoundTrigger` / `AlwaysOnHotwordDetector`（DSP 卸载）—— 10-50 倍，但此路不通

这是唯一能让 AP 真正睡下去的方案。但它要求持有 `VoiceInteractionService` / assistant 角色
—— 而这个角色**必须留给 ChatGPT**，否则 ChatGPT 在锁屏下拿不到麦克风（README 已实测）。
另外厂商固定的 keyphrase 集合也会杀掉自定义唤醒词这个功能。

**列在这里是为了让人不要花一周去重新发现它不可用。**

### ⑦ 麦克风占空比（比如开 500 ms / 关 500 ms）—— 省约 50%，但**不要做**

这是唯一能连 AudioFlinger wakelock 一起放掉的做法，所以看起来很诱人。
但 50% 占空比意味着约 50% 的概率切掉唤醒词起音，召回直接崩。
**这个想法讲得通的版本就是 ①**：采集一直开（本来就便宜、wakelock 反正也持有着），
只对**推理**做占空比，而且由信号内容驱动，不是盲目定时器。

---

## 5. 建议的下一步

1. 先跑一次 `./testkit.sh power A1 / B1 / B2 / A2`，**拔掉 USB**，按脚本里写的来。
   没有这个，"encoder 占大头"是从架构推出来的，不是从数据。
2. **`A` 段（`mic_only=true`）决定了天花板**：如果光采集就占了总耗电的 60%，
   那么门控推理最多也就能省 40%。先知道这个数，再决定值不值得做 ①。
3. 然后再实现 ①，最好放在 `Cfg` 开关后面，这样同一次 ABBA 就能直接 A/B。

## 6. 本轮实际改了什么（只动测量工具，没动音频链路）

`PowerLogger.java`：

- 每条 SAMPLE 增加 **`onBattery`** 布尔字段；插电时 logcat 行尾会打
  `PLUGGED_IN_NOT_A_DRAIN_MEASUREMENT`。就是为了让第 3 节那个错误不可能再犯。
- `Sys.rssKb()` 原本在同一次采样里被调用两次（`:102` 和 `:131`），改成算一次。
- **`Debug.getMemoryInfo()`（全量 smaps 遍历）和 `ThreadCpu.procTaskCpuMs()`（35 个线程 ×
  2 个文件 = 70 次文件读）从记录窗口里移走了**，改成 run 开始和结束各采一次
  （新的 `HEAVY` 事件）。这两个东西原本每 60 s 跑一次，会扰动它自己正在测的量。

`WakeService.java` / UI 相关的改动与功耗无关，见 git log。
