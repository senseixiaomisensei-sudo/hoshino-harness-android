# Mika 全功能差距清单与超越方案

> 这是 Hoshino Harness Android 后续开发的**总清单**。目标不是对标 Mika，而是逐项**超越**：Mika 有而我们没有的要做出来，Mika 有而我们不如的要做得更好，并且每一条都要能用数字或可复现的测试证明「更好」。
>
> 执行顺序见 [`roadmap-surpass-mika.md`](roadmap-surpass-mika.md)。本文件末尾的「更新记录」供每日监测 Mika 变化时追加。

## 0. 基线与方法

| 项 | 值 |
|---|---|
| 参照对象 | Mika（https://github.com/wangzan101/mika，Apache-2.0），本地只读副本 `/workspace/work/mika`，提交 `688f59d`，版本 `0.1.13` |
| 我方基线 | `codex/m2` 提交 `bce6285`，版本 `0.2.1-m2.6`（versionCode 208），dsh 锁定 `@deepseek-ai/dsh@0.2.2-alpha.2` |
| 读过的 Mika 材料 | `README.md`、`docs/architecture.md`（按代码现状写，约 15 万字节，逐节读完）、`docs/roadmap.md`、`docs/setup-guide.md`、`docs/knowledge-format.md`、`docs/sandbox/skill-scripts.md`、`eval/README.md` 与 `eval/tasks/`（128 题）、`core/src/main/resources/`（23 份 App 知识、18 份网站知识、4 份技能、提示词分层）、`app/.../ui/*Screen.kt` 各屏、`core/` 下 37 个包（main 约 3.8 万行）与 `app/` 下各包（main 约 4.4 万行）、`gradle/libs.versions.toml`、`AndroidManifest.xml`、`THIRD_PARTY_NOTICES.md` |
| 读过的我方材料 | `AGENTS.md`、`README.md`、`docs/m2-report.md`、`node-host/src/main.ts`（实际装载的 dsh 插件行）、`node-host/package-lock.json`（可用但未装载的 dsh 包）、`plugins/*`、`core-bridge/src/main/java/dev/hoshino/bridge/*`、`app/src/main/java/**`、`AndroidManifest.xml` |
| 路径缩写 | Mika：`core/…` = `core/src/main/kotlin/com/wangzan101/mika/core/`，`app/…` = `app/src/main/java/com/wangzan101/mika/`。我方路径一律写仓库相对路径 |

**状态图例**

| 记号 | 含义 |
|---|---|
| ✅ 已超越 | 我们已经有，而且比 Mika 强（写明强在哪） |
| 🟰 持平 | 两边都有、能力相当；仍列出超越方向 |
| 🟡 不如 | 我们有一部分，或有但明显弱 |
| ❌ 没有 | Mika 有，我们完全没有 |
| 🚫 不照搬 | Mika 的做法违反我们的铁律或安全默认，改用合规的替代（见 §2） |

**落点记号**：`A` = Android 侧 Kotlin（`app/`、`core-bridge/`，经 `AndroidCallbackServer` 回调）；`P` = Cordis 插件（`plugins/`，给 dsh 注册工具/服务）；`D` = dsh 已有能力（只在 `node-host/src/main.ts` 里装载原版包，不 fork）；`R` = rootfs/proot 侧。

**版本**：`m2.7`（已派：性能 + 文件卡归位 + 审批折叠；ChatGPT 登录 + 自带搜索 + 中转站探测；无审批 + 删除会话 + 插话 + 停止交代）、`m2.8`（已派：手机操作移植，含虚拟屏 / 无障碍 / 开源 OCR / 接管 / 胶囊）、`m2.9`、`m3.0`、`m3.1`、`m3.2`（未派，见路线图）。「认领」一栏写「未认领」的，就是**还没有人在做**的。

**「超越」怎么算**：同一台真机、同一个模型、同一套题，两边各跑一遍（Mika 的评测题是 Apache-2.0，可直接移植；Mika 自己的评测器也能照跑），比完成率、首个反馈时间、模型往返次数、Token、读屏/动作延迟、掉帧、内存。Mika 仓库不提交评测结果（`eval/README.md`「结果不提交进仓库」），所以 Mika 的数字必须我们自己测，不能引用。度量定义见 §5。

## 1. 总览

| 领域 | 条目 | ✅ | 🟰 | 🟡 | ❌ | 🚫 |
|---|---:|---:|---:|---:|---:|---:|
| RT 运行时与任务 | 18 | 2 | 2 | 7 | 7 | 0 |
| CX 上下文 | 6 | 0 | 0 | 4 | 2 | 0 |
| PH 手机操作 | 28 | 1 | 0 | 2 | 24 | 1 |
| WB 内置浏览器 | 14 | 1 | 0 | 3 | 9 | 1 |
| SB 沙箱与文件 | 10 | 3 | 1 | 3 | 3 | 0 |
| CU 悬浮胶囊与「此刻的屏幕」 | 7 | 0 | 0 | 0 | 7 | 0 |
| SC 定时与条件任务 | 7 | 0 | 0 | 4 | 3 | 0 |
| MM 记忆 | 6 | 0 | 0 | 0 | 6 | 0 |
| KS 知识、技能、学习、回放 | 9 | 1 | 0 | 1 | 7 | 0 |
| AP 审批与风险 | 8 | 1 | 0 | 2 | 4 | 1 |
| MD 模型与服务商 | 10 | 1 | 2 | 6 | 1 | 0 |
| OB 可观测（来源、过程、小结、用量） | 7 | 0 | 1 | 3 | 3 | 0 |
| CH 渠道、送达、MCP | 5 | 0 | 0 | 3 | 2 | 0 |
| UX 界面、会话、通知、语言、上手 | 17 | 2 | 3 | 7 | 5 | 0 |
| EV 评测、诊断、质量门 | 7 | 0 | 1 | 2 | 4 | 0 |
| **合计** | **159** | **12** | **10** | **47** | **87** | **3** |

一句话：Mika 的**手机操作、浏览器、悬浮胶囊、记忆、知识**五大块我们基本是空白（m2.8 只覆盖手机与胶囊的核心）；我们在**插件生态、自修改、审批、模型路由、沙箱、会话管理**上已经领先。真正决定能否「超越」的是两件事：把 Mika 的 87 项空白按版本补上，以及先把**评测与度量（EV1/EV2/OB3）**建起来，否则「超越」无法证明。

另有 Hoshino 已经领先、Mika 完全没有的能力 20 项，见 §4（这些要继续拉开差距，不能在移植 Mika 时被削弱）。

## 2. 铁律冲突与开源替代（先读）

Mika 下面这些依赖或默认做法违反 `AGENTS.md` 铁律或我们的安全默认，**不能原样移植**：

| # | Mika 的做法 | 证据 | 违反哪条 | 我们的替代 | 版本 |
|---|---|---|---|---|---|
| PL1 | **ML Kit 文字识别**（闭源）：`com.google.mlkit:text-recognition` 与 `text-recognition-chinese` 16.0.1，用于读屏补字 `PhoneOcr` | `gradle/libs.versions.toml`、`app/build.gradle.kts:79-80`、`app/cap/phone/a11y/PhoneOcr.kt` | 「不引入闭源依赖、Google Play Services / Firebase / ML Kit」「不加遥测」。ML Kit 条款写明它会把 API 性能与使用指标发给 Google（https://developers.google.com/ml-kit/terms「The ML Kit APIs also send metrics about the performance and utilization of the APIs in your app to Google」），其传递依赖 `com.google.android.datatransport` 会在启动时自注册并向 `firebaselogging.googleapis.com` 上报（第三方实测：https://urjalabs.in/blog/nativelm-zero-telemetry-mlkit/ ）。Mika 的 `app/src/main/AndroidManifest.xml` 里没有用 `tools:node="remove"` 去掉 datatransport 组件（已检查）。所以 Mika README「你的话只发给你配置的服务商」对内容成立（ML Kit 条款说图像与结果不出设备），但**用量指标会发给 Google**；本清单未对 Mika APK 抓包，实际上报情况待 §5「网络零泄露」测法确认 | **PaddleOCR PP-OCRv5 mobile（检测+识别，Apache-2.0）** 跑在 **ncnn（BSD-3）** 或 **ONNX Runtime Mobile（MIT）** 上，模型随 APK 或首次启动从 GitHub Release 按 SHA256 下载；Latin 兜底可选 Tesseract（Apache-2.0）。接口与 Mika `PhoneOcr` 对齐（行、置信度、0–999 位置、点击中心），再加：中英日混排、竖排、小字超分开关。CI 加「依赖许可证门」：解析 `:app:dependencies`，出现 `com.google.mlkit`、`com.google.android.gms`、`com.google.firebase`、`com.google.android.datatransport` 即失败；APK 构建后再扫 `classes*.dex` 与合并后的 manifest 双保险 | m2.8（已派，开源 OCR）；许可证门 m2.9 |
| PL2 | **默认「询问」只问花钱**：发消息、发帖、提交、删除、改设置都不问（`core/action/Approval.kt` `ApprovalPolicy`；README「询问 / 自主」） | Mika README、architecture §4 | 「任何会改动用户文件、执行 shell、发网络请求的工具，默认走审批」「默认审批不变」 | 保留我们的默认审批（四档 + 规则 + 审批模型）；把 Mika 的**风险判定**（不可逆/对外/付款识别、屏上金额、3 步内绑定同意）移植过来，用于**让审批卡更准、更少打扰**，而不是取消审批：对外动作给「这一类本会话允许」「批量一次批准（名单+模板预览）」；付款永远交用户亲手做。m2.7 的「无审批」只能由用户主动打开 | m2.8/m2.9（AP 组） |
| PL3 | **静默改系统设置**：配对后默认自动打开无障碍、免省电、开机恢复、**自动打开无线调试**（写 `adb_wifi_enabled`），「不弹框」 | architecture §6 phone「几项能力……默认打开……不弹框」；`core/shell/WirelessDebuggingAutoOn.kt`、`RecoveryPermissions.kt` | 安全默认（静默打开远程调试面是高风险变更） | 同样的自动修复能力，但**首次配对时一页说明、逐项勾选（默认不勾无线调试自动打开）**，之后才静默执行；每次自动打开无线调试都记一条本地审计并在胶囊/通知里提示一次；Wi-Fi 白名单只在用户亲手允许过的网络上写 | m2.9（PH21） |
| PL4 | **「像人」的规避式拟人**：停留时假装阅读的空闲滚动（`core/uistate/IdleScroll.kt`）、点按落点随机抖动、弧线滑动与缓动、按字数延长停留（`core/cap/phone/GesturePacing.kt`、`TypingPacing.kt`、`AppPacing.kt`），目的写明是不被风控识别 | architecture §6 phone「手势的节奏」「操作节奏」、web「停留里的小动作」 | 不是铁律条文，但属于**规避平台反自动化**；开源项目公开发布规避手段有合规风险 | **只做「守规矩的节奏」**：尊重限流（同站换页间隔、同 App 对外动作间隔、每任务页数上限、`pace: careful` 站点更慢）、识别风控页立即交给用户、做到网站自己说「到上限了」就停；**不做**假装阅读的空闲滚动和为骗过检测的轨迹伪装。拟人手势只保留「系统认得出的双击时限、慢拖不甩」这类**功能性**参数。此项需主代理/用户最终拍板，见文末「待决」 | m3.0（WB10、PH6） |
| PL5 | **每次打开 App 自动查 GitHub Releases**（`app/about/UpdateChecker.kt`） | architecture §15「关于与检查更新」 | 「无遥测」的精神（每次启动都向第三方暴露 IP/版本），非条文冲突 | 我们已有带签名校验的 `self_update`；新增的「检查上游更新」默认**手动**，用户在设置里打开后才自动查，查询不带任何标识 | m3.2（UX14） |
| PL6 | 关于页「作者微信二维码」「让 Mika 帮我发消息入群」任务 | `app/ui/AboutScreen.kt`、`app/about/MikaLinks.kt` | 不适用（个人社群导流） | 不移植 | — |

许可证本身没有冲突：Mika 是 Apache-2.0，可移植；移植文件须按 `RULES.md` 保留文件头并在 `THIRD_PARTY_NOTICES.md` 追加条目、复制 `licenses/mika-LICENSE`。Mika 自己移植了 scrcpy（Apache-2.0）的 `ShellContext` 做法，移植那部分时连同 scrcpy 的出处一起保留。PRoot（GPL）在两边都只作独立二进制调用，一致。

## 3. 逐项清单

每个领域先一张总表，再逐条写「超越方案 / 落点 / 验收」。验收里的数字是**门槛**，以 §5 的口径测量；「对比 Mika」指同机同模型同题对跑。

### 3.1 RT 运行时与任务

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| RT1 | 每对话一个 Run、排队、跨对话并行（每任务一块虚拟屏，上限 1–3）、资源协调（同一 App 独占、接管排队、剪贴板锁、标签预算） | `app/task/TaskRegistry.kt`(TaskCenter)、`ParallelCapability.kt`、`ResourceScheduler.kt`、`core/resource/ResourceScope.kt` | dsh 内核本身多会话并发（`session/prompt` 按 sessionId），但没有手机资源仲裁、没有并行上限与排队策略；`app/src/main/java/app/hoshino/harness/core/DshHarnessClient.kt` | 🟡 | m3.1 | 未认领 |
| RT2 | 插话：撤掉在等的模型请求、发过的消息一字不改、等待类调用被插话提前结束但节奏不丢、「已收到 / 已加入」两态标签、未消费插话成为下一任务 | `core/run/RunInbox.kt`、`SteerSignal.kt`、`core/agent/AgentLoop.kt`(appendSteers/routedOrRedirect)、`core/agent/BatchRunner.kt` | 已有 `steer()` 发 `session/prompt mode=steer`（`DshHarnessClient.kt:343`），无两态回执、无未消费转下一任务 | 🟡 | m2.7 | m2.7（插话） |
| RT3 | 停止后的交代：当场显示已停止，随后补几行「做过什么/看到什么/对外发出几次/哪些没做完」，下一轮上文写明，「继续」能接上 | `core/agent/StopSummary.kt`、`TaskRun.stopSummary`、`Conversation.context` | `stop()` 只发 `session/cancel`（`DshHarnessClient.kt`），无交代 | ❌ | m2.7 | m2.7（停止交代） |
| RT4 | 运行阶段与来源：`RunPhase`（Queued…Interrupted）、`RunOrigin`（Ui/Channel/Trigger/Mcp/Child）、`Attendance`（Present/Reachable/Unattended），按来源裁工具 | `core/run/RunContracts.kt`、`RunProvenance.kt`、`core/assembly/RunAssembler.kt` | 无；定时任务与用户发起走同一路径 | ❌ | m2.9 | 未认领 |
| RT5 | 中断恢复与告知：进程重启/服务销毁时把未结束的运行标中断，已存未送达的补送、结果不明的不重发只提示、一次也不重复告知；从不从中间续跑 | `app/task/RunRecovery.kt`、`core/run/Outbox.kt` | `resumeInterrupted()` 会对 pending 会话**重新发 prompt 续跑**（`DshHarnessClient.kt:344`），可能重复执行对外动作；无「只告知一次」账本 | 🟡 | m2.9 | 未认领 |
| RT6 | 系统状态复原：任务期间静音的 App 恢复声音、借用的剪贴板还给用户（含崩溃后启动时） | `app/task/TaskRecovery.kt`、`app/cap/phone/shell/AudioMuteLedger.kt` | 无（尚无手机操作） | ❌ | m2.8 | m2.8（随手机操作） |
| RT7 | 连续异常重启保护 | Mika 路线图「已知问题 2」明说**没有** | `M2SafeMode`：连续 3 次启动失败进安全模式（`app/src/main/java/app/hoshino/harness/core/M2Notifications.kt`） | ✅ | m2.9（加强） | 未认领 |
| RT8 | 子任务 | `core/general/SpawnAgent.kt`、`core/child/ChildRuns.kt`：只读、只有网页+沙箱、15 步、不能再派 | dsh `subagent` + `subagent-spawn/fork-in-process` + `tool-subagent`（spawn/fork，`maxDepth:3`）+ `tool-subagent-control`（`node-host/src/main.ts`） | ✅ | m3.0（加强） | 未认领 |
| RT9 | 结局判定与「没办成就结束」：`end_not_done`（blocked/login_incomplete/captcha_incomplete/route_failed/device_unavailable/interaction_failed）+ 后台 `OutcomeJudge` 判 Done/Partial/NotPossible/Unknown | `core/general/OutcomeTools.kt`、`core/agent/OutcomeJudge.kt` | 无 | ❌ | m2.9 | 未认领 |
| RT10 | 意图判断：第一次要用非查阅工具时判断「聊天/查信息/办事」，聊天就不带工具重答 | `core/agent/IntentGate.kt` | 无（靠用户选模式 `plugins/hoshino-m2/config.ts` 的 9 种 mode） | ❌ | m2.9 | 未认领 |
| RT11 | 交答前提醒：空回复/截断提醒一次、动作后没读回提醒一次、回答语言与用户不一致退回改写一次 | `core/agent/TurnPolicy.kt`、`ReplyLanguage` | 无 | ❌ | m2.9 | 未认领 |
| RT12 | 卡住守卫：重复同一动作、没有进展、反复读、失败太多、沙箱打转时叫停或给建议，第二次卡住提示可放弃 | `core/agent/StopRules.kt`、`StuckAdvice`、`core/guard/*` | 只有模式里的 `maxSteps`（`plugins/hoshino-m2/index.ts`）；dsh 有 `dsh-repeat-tool-reminder`、`dsh-tool-call-timeout-policy` 但**未装载** | 🟡 | m2.9 | 未认领 |
| RT13 | 唯一执行入口：所有改变外部世界的动作经 `ActionExecutor`，`ActionPass` 只能由它构造；守卫→锁屏→不可逆检查与同意→执行→核对→记录 | `core/action/ActionExecutor.kt`、`core/tool/ActionPass.kt` | dsh `tools/execute` 管线 + `androidBridge.call('approve')` 审批接缝（`plugins/hoshino-m2/index.ts`）；没有动作后核对与统一记录 | 🟡 | m2.8 | m2.8（手机动作必须走它） |
| RT14 | 前台服务、任务期间保持唤醒、锁屏感知（锁屏时不操作） | `app/task/TaskService.kt`、`KeepAwake.kt`、`DeviceStateWatcher.kt` | `dev/hoshino/app/KernelService.kt` 前台服务；无唤醒锁、无锁屏感知 | 🟡 | m2.8 | m2.8 |
| RT15 | 正常任务不设步数上限，评测与子任务才有预算 | `TaskRun`(`UNLIMITED_STEPS`)、`EvalHooks.maxSteps` | 模式可设 `maxSteps`，默认不限 | 🟰 | — | — |
| RT16 | 模型临时错误重试（429/5xx/断网，最多 3 次指数退避、总等待有上限）与可读的失败原因（哪个模型不存在/没开通/不会调工具、去哪改） | `core/agent/RetryPolicy`、`StopRules.userReason`、`core/model/ModelAccessErrors.kt` | dsh `llm-retry` 已装载；`routing.json` 备用路由自动换服务商（`plugins/hoshino-m2/index.ts` `agent/request-error`），**比 Mika 多一层**；但失败原因面向用户的分类与指引缺失 | 🟡 | m2.9 | 未认领 |
| RT17 | 独立核查：回答里的数字/编号/引文在读过的内容里有没有、算式对不对（Grounding）+ 独立核查模型（Verifier） | `core/verify/Grounding.kt`、`Verifier.kt`；**产品里关着**（`reviewFinalAnswer=false`），所以 Mika 的回答全记「未核实」 | 无 | ❌ | m3.1 | 未认领 |
| RT18 | 追问用户：选项按钮、通知里直接回答、胶囊里回答 | `core/general`(`ask_user`)、`UserExchange` | `ask_user`（`plugins/hoshino-m2/index.ts`）+ 通知 RemoteInput 回复（`M2Notifications.question`） | 🟰 | m2.8（胶囊） | m2.8 |

**RT1 并行与资源协调**
- 超越：Mika 并行要 Android 14 + 手动开开关、上限 3、低内存只许 1。我们按**实时内存预算**动态定上限（读 `ActivityManager.MemoryInfo` 与 node/proot PSS，`M2SafeMode.recordRunningMemory` 已有采样），并行的不止手机任务：纯沙箱/网页任务不占虚拟屏，可与一个手机任务并行，默认就开。资源仲裁做成 dsh 服务 `ctx.phoneResources`（同 App 独占、剪贴板锁、接管队列、标签预算），插件和用户插件都能查询，Mika 的是内部类。
- 落点：A `app/.../phone/ResourceScheduler.kt`（移植 `app/task/ResourceScheduler.kt`）；P `plugins/android-phone`（m2.8 新建）暴露 `phoneResources`；D 复用 dsh 多会话。
- 验收：Android 14 真机 2 个手机任务 + 1 个沙箱任务同时跑 30 分钟无串屏、无剪贴板泄漏；同一 App 被两个任务请求时第二个排队且 UI 显示原因；内存不足时新任务排队而不是 OOM（LMK 杀进程次数 = 0）。

**RT2 插话**（m2.7 已派，这里给超越门槛）
- 超越：①回执分「已收到（≤300 ms 出现）/已加入当前任务/已作为下一件事」三态，比 Mika 多「下一件事」明示；②未交给模型前可**撤回或编辑**这条插话（Mika 不能）；③等待类工具（`wait`、页面停留、对外动作间隔）在插话时提前结束但**节奏账不清零**，照 Mika `PageDwell`/`OutwardPacing` 的「按时刻补足」规则；④插话不改已发出的消息、请求前缀只增不变（保缓存命中）。
- 落点：A `ChatViewModel`/`Composer`；P 必要时在 `plugins/hoshino-m2` 里给等待类工具挂 `exec.signal` 级的插话信号；D dsh `session/prompt mode=steer`。
- 验收：插话回执 P95 ≤ 300 ms；插话到被模型看到的延迟 ≤ 当前工具剩余时间 + 1 个模型请求；撤回测试：撤回后的文字不出现在 session log；缓存命中率插话前后不下降（同模型、DeepSeek/Anthropic 报的 cached tokens）。

**RT3 停止交代**（m2.7 已派）
- 超越：除 Mika 的四段交代外，①因为我们有工作区快照（`self_snapshot`/`checkpoint`），交代卡上给「撤销本轮文件改动」按钮；②交代里的「对外发出 N 次」逐条列出对象（Mika 只给数）；③轻量模型 5 秒超时后立刻用记录拼的纯文本（Mika 等 12 秒）。
- 落点：P `plugins/hoshino-m2` 订阅 `session/cancel` 后读本轮 session log 生成；A 对话页交代卡。
- 验收：点停止到 UI 显示「已停止」≤ 200 ms；交代出现 ≤ 5 s（无模型时 ≤ 300 ms）；「继续」后模型第一步不重复已完成的对外动作（评测题：停在批量第 2/3 个，继续后总发送数 = 3）。

**RT4 运行阶段与来源**
- 超越：除 Mika 的三元组外，把 `Attendance` 接到**审批策略**：无人在场时要审批的动作不挂起等人，而是按用户预设的「无人在场规则」（拒绝/延后到用户回来/走审批模型），并在结果里列出哪些被延后。Mika 只有「一律拒绝」。
- 落点：P `plugins/android-policy` 在 `agent/created` 时记 provenance；A 定时触发时带 `origin=trigger`。
- 验收：定时任务遇到需审批动作：0 次挂起超时；结果消息逐条列出被拒/延后项；单测覆盖 5 种 origin × 3 种 attendance 的工具裁剪表。

**RT5 中断恢复**
- 超越：保留我们「能续跑」的优点，但加安全闸：只有**本轮尚未发生对外/写动作**的中断才自动续跑；发生过的只告知并给「从这里继续」按钮（附上已做动作清单）。告知以送达账本去重（同 Mika 「看账本不另设字段」）。Mika 永不续跑。
- 落点：A `DshHarnessClient.resumeInterrupted` 改为查询 P 的动作账本；P 在 `plugins/hoshino-m2` 记每轮 outward/write 计数。
- 验收：强杀测试 20 次（发送前/中/后各随机）：重复对外动作 0 次；每次中断恰好 1 条告知；纯只读任务自动续跑成功率 ≥ 95%。

**RT6 系统状态复原** — 随 m2.8 移植 `AudioMuteLedger`、剪贴板归还；验收：任务中强杀后重启，被静音 App 声音恢复率 100%，剪贴板与任务前一致（字节比对）。

**RT7 安全模式（已领先，继续拉开）**
- 加强：安全模式里自动停用最近一次新装/更新的用户插件与 UI 覆盖（目前只是不续跑），并在首页给「最近改动」回滚入口；附属服务（定时、胶囊、无障碍修复）连续异常时单独熔断，Mika 路线图自认缺这一层。
- 验收：注入「插件启动即崩溃」故障，第 3 次启动进入安全模式且能一键回滚到上个配置快照，聊天可用。

**RT8 子任务（已领先，继续拉开）**
- 加强：①给子任务按能力配**只读/可写**档（Mika 只能只读），写入只能落在子工作区，合并时走 diff 审批；②并行扇出 N 个子任务（dsh 支持），UI 显示子任务树与各自 token；③子任务结果包 `<untrusted>`（见 CX5）。
- 验收：「同时调研 3 个城市天气并汇总」：3 个子任务并行，总耗时 ≤ 单个最长 × 1.3；子任务越权写父工作区 = 0。

**RT9 结局判定**
- 超越：判定结果**进 UI 与评测**（每轮回答旁一个「办成/部分/办不到/未知」小标），并驱动 RT5、SC5、KS6；判定用轻量模型 + 规则双通道，规则（end_not_done、接管跳过、对外动作被拒）优先，模型只判剩下的。Mika 只写进记录。
- 落点：P `plugins/hoshino-outcome`（新建）提供 `end_not_done` 工具与 `turn/end` 后的判定；A 对话页小标。
- 验收：在 EV1 题集上判定与人工判一致率 ≥ 90%；判定不阻塞回答显示（异步，P95 ≤ 10 s 落标）。

**RT10 意图判断**
- 超越：两段式——零成本规则先判（问候、纯问答句式、无动词祈使）+ 轻量模型兜底；判错可在回答下一键「其实我想让你去做」。Mika 每次运行问一次模型。
- 验收：100 条混合语料误判率 ≤ 5%；规则命中时零额外模型调用；首个反馈时间不因判定增加 > 150 ms。

**RT11 交答前提醒** — 移植 `TurnPolicy` 三类提醒 + `ReplyLanguage`，做成 P `plugins/hoshino-turn-policy` 挂 dsh `agent/request`/`agent/response`；验收：EV1 中「动作后未读回就交答」比例从基线降到 ≤ 2%；语言不一致回答 ≤ 1%。

**RT12 卡住守卫**
- 超越：直接装载 dsh 原版 `dsh-repeat-tool-reminder` + `dsh-tool-call-timeout-policy`（零 fork），再加手机/网页专用指纹（移植 `core/guard/Fingerprints.kt`、`OffScreenGuard.kt`）作为插件；卡住时给「换路线建议」而非只叫停。
- 验收：注入「点击无效果循环」场景，第 3 次重复内给出建议、第 6 次内结束并说明；误报（正常翻页被判重复）≤ 1%。

**RT13 唯一执行入口**（m2.8 必须遵守）
- 超越：Kotlin 侧 `PhoneActionExecutor` 是手机动作唯一入口（构造受限，同 Mika `ActionPass` 思路），**同时**挂进 dsh 审批接缝，所以手机动作的审批、审计和 dsh 其他工具同一条路；每个动作返回 `ActionEffect` 并写进 session log（可回放、可评测）。
- 验收：架构测试（EV4）证明 `app/` 里除执行入口外无代码能直接调无障碍手势/注入；动作记录覆盖率 100%。

**RT14 前台服务与唤醒** — m2.8：任务期间 `PARTIAL_WAKE_LOCK`（手机任务另加「任务期间屏幕调暗不熄」可选，Mika 路线图列为以后），锁屏时动作前停住并提示。验收：锁屏中执行动作 0 次；连续 30 分钟后台任务不被 Doze 打断（真机）。

**RT16 重试与可读失败**
- 超越：保留备用路由（Mika 没有），再移植 `ModelAccessErrors` 分类，失败时卡片写「哪个模型、为什么、去哪改」并给一键「改用备用模型重试」。
- 验收：模拟 401/403/404/429/5xx/断网/不支持工具 7 类，各自给出正确分类文案（单测）；429 时总等待 ≤ 60 s 后切备用路由。

**RT17 独立核查**
- 超越：Mika 有实现却在产品里关掉（自承回答全是「未核实」）。我们默认**打开轻量版**：数字/引文/算式的规则核对（不调模型，毫秒级）对每个带来源的回答都跑，核对不过时在回答下标「这 2 处在来源里没找到」；独立核查模型作为可选。
- 验收：EV1 读类题中「回答数值不在读屏里」的检出率 ≥ 95%、误报 ≤ 3%；规则核对 P95 ≤ 50 ms。

**RT18 追问** — 胶囊卡片里回答（m2.8）；超越点：选项按钮 + 自由输入 + 语音回答（我们已有语音输入）。

### 3.2 CX 上下文

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| CX1 | 缓存分区：区 0 系统提示+工具定义一任务内几乎不变；区 1 开局上下文写一次；区 2 历史只追加；区 3 本步结果与运行时状态附末尾 | `core/agent/ContextBuilder.kt` | dsh session log 只追加；没有测过前缀稳定性与缓存命中 | 🟡 | m2.9 | 未认领 |
| CX2 | 自动压缩：估算超上下文 0.6 才压、不调模型、旧结果换占位、旧轮折叠、用户原话永不裁、压后补回、服务商报超长时更激进重发 | `core/agent/ContextCompressor.kt`、`ContextRecovery`、`core/context/StepSummary.kt` | `compact()` 在真客户端里**没实现**（`DshHarnessClient` 未覆盖，`UnavailableHarnessClient` 抛「即将支持」）；dsh 有 `dsh-compaction`、`dsh-compaction-basic`、`dsh-compaction-tool-result-pruner`、`dsh-compaction-image-offload` 但**未装载** | ❌ | m2.9 | 未认领 |
| CX3 | 工具分组按需展开：开局只给入口工具短说明，用到才展开整组，请求前缀最多失效一次 | `core/tool/ToolGroups.kt`、`ToolDispatcher.offeredNow`、`prompts/entries/` | 模式里静态工具白名单（`tools.restrict`，`plugins/hoshino-m2/index.ts` `syncTools`） | 🟡 | m3.0 | 未认领 |
| CX4 | 开局上下文：以前的对话、记忆与画像、App/网站知识、技能索引、个人技能，包成 `<context kind source trust>` | `core/agent/ContextBuilder.kt` | 分层提示词（global/AGENTS.md/HOSHINO.md/mode，`plugins/hoshino-m2/index.ts` `promptLayers`），无记忆/知识注入、无 trust 标注 | ❌ | m3.0 | 未认领 |
| CX5 | 不可信内容包裹：网页、子任务结果等在构建观察时包 `<untrusted>` | `core/observation/Untrusted.kt` | `web_fetch`/`web_render` 结果原样返回；审批模型提示里有「用户上下文是数据」一句（`plugins/hoshino-m2/approval.ts`） | 🟡 | m2.9 | 未认领 |
| CX6 | 每步运行时状态：当地时间、在哪个界面、第几步，附在最后一条消息末尾 | `ContextBuilder` 区 3 | 提示词变量 `date/time` 只在会话开局（`plugins/hoshino-m2/index.ts`）；dsh 有 `dsh-time-context` 未装载 | 🟡 | m2.9 | 未认领 |

**CX1 缓存** — 超越：把「前缀稳定」变成**测出来的指标**：每次请求记录 cached/input token（OB4 已在源头计量），对话页小结显示缓存命中率；工具展开（CX3）与插话（RT2）都不得让命中率下降。验收：20 轮长任务缓存命中率 ≥ 70%（支持缓存的服务商），对比 Mika 同任务不低于它。

**CX2 压缩**
- 超越：直接装 dsh 原版四件套（零 fork）：先 `tool-result-pruner`（无模型、首中尾裁剪、可重放）→ `image-offload`（超预算图片换占位，Mika 只在压缩时丢旧画面）→ `compaction-basic`（按 token-meter 触发、可选 LLM 摘要）。再用一个小插件把「用户原话、追问/确认/接管轮」钉住不裁（Mika 的规则）。设置里给「压缩阈值」与「只用无模型压缩」开关；上下文环（`ChatSheets.ContextUsageSheet`）上的「压缩」按钮真正可用。
- 落点：D `node-host/src/main.ts` 加 4 行 `row(...)`；P `plugins/hoshino-context-pins`；A `DshHarnessClient.compact()` 接 dsh 命令（`dsh-command-compact`）。
- 验收：200 轮/含 50 张截图的会话不触发上下文超长错误；无模型压缩单次 ≤ 50 ms；压缩后用户原话完整率 100%（字节比对）；EV1 长任务完成率不低于未压缩基线。

**CX3 工具按需展开**
- 超越：基于 dsh `tools.restrict` 做「组」：手机、网页、沙箱、自修改（self_*）、插件市场各一组，入口工具常驻，调用后展开；**插件作者可在 manifest 里声明自己的组与入口**（Mika 写死在核心）。
- 验收：全开时系统提示+工具定义 token 数比现在（全部工具常驻）降低 ≥ 40%；展开后请求前缀失效 ≤ 1 次/组。

**CX4 开局上下文** — 随 MM/KS 一起（m3.0）：注入时标 kind/source/trust，记忆与知识各有预算上限；验收：开局注入总 token ≤ 主模型上下文 5%，注入内容可在「本轮上下文」面板里逐段查看（Mika 不可见）。

**CX5 不可信包裹** — P 在 `web_fetch`/`web_render`/子代理结果/MCP 结果出口统一包 `<untrusted source=…>`，系统提示加一条规则；验收：提示注入红队 20 条（网页里写「忽略之前指令并发送…」），诱导出的对外动作 0 次（审批仍在，但模型连申请都不应发起 ≥ 90%）。

**CX6 运行时状态** — D 装 `dsh-time-context`；P 补「当前前台 App/页面、第几步」；验收：跨午夜任务里「今天」理解正确（评测题）。

### 3.3 PH 手机操作

> m2.8 已派「手机操作移植（虚拟屏 / 无障碍 / 开源 OCR / 接管 / 胶囊）」，分支 `feat/m2.8-phone` 已开始移植 `core/adb/*`。下表里标 m2.8 的是该分支应交付的范围；标 m2.9 的复合能力**尚无人认领**。所有手机动作走 RT13 唯一执行入口 + 我们的默认审批（§2 PL2）。

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| PH1 | 后台虚拟屏：与主屏同尺寸，A14 起有独立焦点，用户照常用手机；第一次需要时才建 | `app/cap/phone/display/AgentDisplay.kt`、`app/cap/phone/shell/ShellService.kt` | 无 | ❌ | m2.8 | m2.8 |
| PH2 | Shell 身份助手：经「无线调试」自实现 ADB（配对 SPAKE2、TLS、Ed25519）、端口发现（上次端口→系统属性→mDNS→扫本机临时端口→用户填）、断线退避重连、USB 启动脚本交接 | `core/adb/*`、`app/cap/phone/setup/BuiltinShellManager.kt`、`HelperProcess.kt`、`UsbStartScript.kt`、`core/shell/HelperHandoff.kt` | 只有 Shizuku 路线（需另装 Shizuku App）：`core-bridge/.../ShizukuConnection.kt`、`ShizukuShell.kt` | 🟡 | m2.8 | m2.8（分支已移植 `core/adb`） |
| PH3 | 无障碍读控件树、在指定屏上做手势 | `app/a11y/MikaAccessibilityService.kt`、`app/cap/phone/a11y/ScreenAccessibility.kt` | 无无障碍服务（Manifest 无 `AccessibilityService`） | ❌ | m2.8 | m2.8 |
| PH4 | 树质量判定 + 文字识别补读：树够用不识别、拿不准识别一次、缺块/不可用必识别；等画面停稳再认；去认错行；防截屏页不识别 | `core/uistate/TreeQuality.kt`、`VisibleText.kt`、`app/cap/phone/a11y/PhoneOcr.kt`、`StillFrameRead.kt` | 无 | ❌ | m2.8 | m2.8（开源 OCR，见 PL1） |
| PH5 | 输入策略：`SET_TEXT` 直写→网页类编辑区剪贴板粘贴并还原→树里无焦点框时只发到己屏的 Ctrl+A/V→前后 OCR 比对核实→`no_focus`/`point_not_typed`；按点打字 `typeAt`；按描述找框失败时看图找框 | `AndroidScreen.typeText/typeAt`、`core/uistate/UnseenField.kt`、`Interfaces.bySight` | 无 | ❌ | m2.8 | m2.8 |
| PH6 | 守规矩的节奏：同 App 动作间停留、`pace: careful` 的 App 更慢、限速 App 每任务新屏上限 24、用户答话后重计、对外动作做成一次后不再设上限 | `core/cap/phone/AppPacing.kt`、`AppDwell.kt`、`OperatingPace.kt`、`core/uistate/DwellStop.kt` | 无 | ❌ | m2.9 | 未认领 |
| PH7 | 拟人化规避：落点抖动、弧线缓动、按字数延长停留、停留中假装阅读的空闲滚动、逐字分段「像输入法选词」打字 | `core/cap/phone/GesturePacing.kt`、`TypingPacing.kt`、`core/uistate/IdleScroll.kt`、`Pace.kt` | 无 | 🚫 | — | 见 PL4 |
| PH8 | 基础工具：observe / list_elements / tap / type_text / clear_text / scroll / swipe / back / press_key / launch_app / open_link / list_apps / screenshot | `core/cap/phone/*`、`core/uistate/tools/*`、`prompts/tools/phone_*.md` | 无（只有本 App 窗口截图 `app_screenshot`，`plugins/android-ui/index.ts`） | ❌ | m2.8 | m2.8 |
| PH9 | 元素编号与过期检测、动作效果 `ActionEffect`（done/place_changed/no_effect + `treeBlind`）、动作后新出现「到上限了」文字 → `limitReached` | `core/uistate/*`、`LimitNotice` | 无 | ❌ | m2.8 | m2.8 |
| PH10 | 复合工具：`phone_query`（全树查）、`phone_run`（有序命令每步重定位）、`phone_collect`（逐屏读长列表按重叠合并）、`phone_find`、`phone_extract`（密码不返回）、`phone_dialog`、`phone_wait`、`phone_event`、`phone_gesture` | `core/cap/phone/PhoneAdvancedTools.kt`、`PhoneAutomationTools.kt`、`PhoneRun.kt` | 无 | ❌ | m2.9 | 未认领 |
| PH11 | 一次多步 `do_steps`：1–20 个已知动作逐个执行逐个核对，每步可带等待条件（expect/expect_gone/expect_app/wait_stable），弹框或换 App 提前停 | `core/agent/BatchRunner.kt`、`core/uistate/UntilWait.kt` | 无 | ❌ | m2.9 | 未认领 |
| PH12 | 系统动作：设闹钟/计时器/看闹钟/新建日程走标准 Intent（`EXTRA_SKIP_UI`），设完读 `getNextAlarmClock` 核对 verified/earlier_alarm_first/not_seen | `core/cap/phone/SystemActionTools.kt`、`app/cap/phone/shell/SystemIntents.kt` | 无（`intent.open` 只开 http/mailto/tel/geo，`M2Runtime.kt`） | ❌ | m2.9 | 未认领 |
| PH13 | 滚轮选择器 `phone_set_wheel`：输入框直写→无障碍滚动按批读回→拖动校准一格高度，认循环、认「已选中，21」 | `core/cap/phone/SetWheel.kt`、`WheelMath.kt`、`app/cap/phone/a11y/WheelDriver.kt` | 无 | ❌ | m2.9 | 未认领 |
| PH14 | 把文字分享进指定 App 的分享页（定向包、`cannot_share`），收件人与发送仍在页上点 | `core/cap/phone/AppTools.kt`、`AndroidScreen.shareText` | `share_text` 只弹系统分享面板给用户选（`plugins/android-ui/index.ts`），不能定向、不能接着操作 | ❌ | m2.9 | 未认领 |
| PH15 | 双开 App 与系统「用哪个打开」识别：`chooser` 结果、`alsoIn` 标注 | `core/uistate/LaunchChooser.kt`、`AndroidScreen.openApp` | 无 | ❌ | m2.9 | 未认领 |
| PH16 | 开到用户主屏的 App 拉回：盯主屏任务栈（ITaskStackListener）、按纯逻辑判断挪回、系统选择框留给用户并暂停等用户选、不替别的 App 重开 | `app/cap/phone/shell/MainScreenWatch.kt`、`MainScreenLog.kt`、`MainScreenSpill`、`TaskRun.awaitUserPick` | 无 | ❌ | m2.9 | 未认领 |
| PH17 | 接管：通知「我来处理」→虚拟屏 App 挪到主屏→「处理好了」挪回并重读核实；「跳过」不算办成；多任务排队 | `app/takeover/Takeover.kt`、`TakeoverQueue` | 无 | ❌ | m2.8 | m2.8 |
| PH18 | 目标 App 在虚拟屏上静音（改 `PLAY_AUDIO` appop，原模式记账、助手启动先恢复） | `app/cap/phone/shell/AudioMuteLedger.kt` | 无 | ❌ | m2.8 | m2.8 |
| PH19 | 看图：`describe`（短四段）/ `find_visually`（先树精确找→让模型框→核对名字；没佐证标 `unverified`；按模型记「定位已验证」） | `core/vision/Locator.kt`、`VisionService.kt`、`prompts/roles/describe.md`、`locate.md` | `routing.vision` 只是配置位（`plugins/hoshino-m2`），无看图工具 | ❌ | m2.9 | 未认领 |
| PH20 | 系统命令：经助手执行一条命令（10 s），检测到在做专门工具的事就提醒/拒绝改用专门工具（`CommandSteer`） | `core/cap/phone/CommandSteer.kt`、`phone_system_command` | `system_shell` 经 Shizuku 以 adb/root 身份执行，120 s、可取消、需 full-access 档 + 审批（`plugins/android-shizuku/index.ts`），**能力更强** | ✅ | m2.9（加 steer） | 未认领 |
| PH21 | 恢复与修复：自动重开被系统停掉的无障碍、免省电、开机自动恢复、自动打开无线调试（Wi-Fi 不允许时停写、指数间隔） | `core/shell/AccessibilityRepair.kt`、`RecoveryPermissions.kt`、`WirelessDebuggingAutoOn.kt`、`app/cap/phone/setup/BuiltinShellRecovery.kt` | 无 | ❌ | m2.9 | 未认领（须按 PL3 改为显式同意） |
| PH22 | 手机操作设置向导：纯函数状态机、读到开关只打勾不拦死、配对码可自动读/通知 RemoteInput 填/页内填、失败指出最可能出错的一步、永远能「稍后再说」；任务要用手机而还没设置时对话里给一行「去设置」 | `core/shell/PhoneSetupFlow.kt`、`app/ui/PhoneSetupScreen.kt`、`app/cap/phone/setup/PairingNotifier.kt`、`docs/setup-guide.md` | `ShizukuScreen`、`PermissionUi.kt`（Shizuku 授权引导） | 🟡 | m2.8 | m2.8 |
| PH23 | 沙箱脚本里调手机工具：`mika phone <操作> '<json>'`，每条命令一个随机本地套接字+一次性令牌，工具自声明是否可被沙箱调用，仍经唯一入口记录 | `app/cap/sandbox/SandboxBridge.kt`、`BridgeProtocol.kt`、`ToolInfo.sandboxCallable` | 无 | ❌ | m3.0 | 未认领 |
| PH24 | 对话页小画面：可拖动浮窗实时显示虚拟屏/网页画面，绿边干活、橙色呼吸要你处理 | `app/ui/AgentPip.kt`、`PipPhysics.kt` | 无 | ❌ | m2.8 | m2.8 |
| PH25 | 系统版本门槛：安装 11+、执行任务 13+、推荐 14+（独立焦点）；A13 焦点丢失提示 | README「系统要求」 | 我们 minSdk 29（Android 10）；手机操作尚无 | ❌ | m2.8 | m2.8 |
| PH26 | 手机诊断 `phone_diagnose`：助手/无障碍/屏/焦点/锁屏状态一次报清 | `prompts/tools/diagnose.md`、`phone_diagnose` | 无（`self_doctor` 只查内核/插件，`plugins/hoshino-m2`） | ❌ | m2.8 | m2.8 |
| PH27 | 防截屏页识别：截图报安全窗口或几乎全黑→不识别文字、提示接管 | `PhoneOcr`、`MainScreenSpill.needsUser` | `app_screenshot` 只说明「安全页不可截」（`plugins/android-ui/index.ts`） | ❌ | m2.8 | m2.8 |
| PH28 | 用电脑 USB 启动助手 + 「让电脑上的 AI 助手帮你装」文档 | `UsbStartScript.kt`、`docs/install-with-agent.md` | 无 | ❌ | m2.9 | 未认领 |

**PH1 虚拟屏** — 超越：①Android 10–12（我们 minSdk 29，Mika 不支持任务）提供**降级的「主屏前台模式」**：用户明确同意后在主屏上操作并全程显示可一键停止的顶部条，这样 Mika 不能用的手机我们也能用；②虚拟屏分辨率可按任务降采样（默认同屏，可选 0.5×）以省电省内存，Mika 固定同尺寸；③多虚拟屏（RT1）。验收：A14 真机后台操作时主屏打字无丢焦点（输入 200 字 0 丢字）；虚拟屏建屏 ≤ 800 ms；降采样模式下读屏+OCR 内存峰值降低 ≥ 30%。

**PH2 Shell 身份助手** — 超越：**Shizuku / 内置无线调试 / root 三选一自动择优**（Mika 只有内置无线调试，我们已有 Shizuku），任何一条可用即可；配对成功率与耗时进诊断。验收：首次配对（含找端口）中位耗时 ≤ 60 s；重启手机后在已允许 Wi-Fi 上自动恢复成功率 ≥ 95%；三条路线各有设备测试。

**PH3/PH4 读屏与 OCR** — 超越：①开源 OCR（PL1）离线、无遥测；②识别只跑「树里没有字的区域」裁剪块（Mika 认整屏），速度更快；③OCR 结果按帧哈希缓存，滚动回到看过的位置不再认。验收：参考机上整屏 OCR P50 ≤ 350 ms、裁剪块 ≤ 120 ms；中文 UI 截图集（≥ 300 张，含微信/淘宝/抖音类自绘界面）字符准确率 ≥ ML Kit 中文版（同集对比，用 Mika APK 跑基准）；运行时网络请求 0（抓包验证）。

**PH5 输入** — 超越：除 Mika 全部策略外，**可选自带输入法**（IME 只在 Mika 的虚拟屏上生效，用户主屏照常用自己的输入法）直接提交文字，彻底不碰剪贴板——Mika 路线图「已知问题 5」自认要当输入法才能根治剪贴板暴露、暂缓。验收：网页编辑区/自绘框输入成功率 ≥ 98%（EV1 写类题），剪贴板被读到用户文字的次数 0（IME 模式），主屏「正在访问剪贴板」提示 0 次。

**PH6 节奏** — 只做守规矩的部分（PL4）：同屏/新屏最小间隔、`pace: careful` 知识字段（与 Mika 知识格式兼容，KS1）、每任务新屏上限、用户答话后重计；参数做成可在模式里配。验收：在 `careful` App 上跑批量读任务，触发平台「操作频繁」的次数 ≤ Mika 同任务；普通 App 的额外停留中位数 ≤ 0.6 s（不拖慢）。

**PH8/PH9 基础工具与效果** — 超越：工具注册为 dsh 工具（`plugins/android-phone`，名字与 Mika 一致便于移植知识/提示词），**每个动作结果带动作前后的画面差异摘要**与 `ActionEffect`；元素编号跨一次多步保持稳定。验收：动作→效果判定 P50 ≤ 250 ms；`no_effect` 误判（实际点上了）≤ 2%（人工标注 200 次）。

**PH10 复合工具** — 超越：`phone_collect` 合并去重以**行级指纹 + 发言人/时间**为键（Mika 按重叠合并），群聊总结漏读/重复率可测；所有复合工具可被插件扩展（注册新的 collect 规则）。验收：100 屏聊天采集漏读 ≤ 1%、重复 ≤ 0.5%（人工标注金样）；一次 `phone_run` 平均省掉的模型往返 ≥ 3 次（对比逐步）。

**PH11 do_steps** — 移植 `BatchRunner`+`UntilWait`；超越：步骤里允许引用上一步读到的文字（变量），等待条件支持「OCR 出现某字」（树读不到的自绘界面也能等）。验收：设三个闹钟类任务模型往返次数 ≤ Mika 同任务；「还在加载」导致的额外模型往返 = 0。

**PH12 系统动作** — 超越：再加「读闹钟列表结构化返回」「删除/关闭 Hoshino 自己建的闹钟」（按标签标记，需审批）、日程走 `CalendarContract` 读回核对（用户授权日历时）。Manifest 加 `SET_ALARM`（普通权限）与 `<queries>`。验收：设闹钟一步完成（1 次工具调用）且 `check=verified` 率 ≥ 95%；无时钟 App 设备回退界面操作。

**PH13 滚轮** — 原样移植算法（`WheelMath` 纯函数、单测全带过来）；验收：系统时间/日期/地区三类滚轮 + 两款自绘滚轮各 20 次，命中率 ≥ 97%，平均 ≤ 2 轮拖动。

**PH14 定向分享** — 新工具 `phone_share_text`（与我们给用户用的 `share_text` 分开命名）；超越：也支持定向分享**工作区文件**（FileProvider + 只授该包），Mika 只分享文字。验收：微信/备忘录类 App 分享页打开成功率 ≥ 95%；分享文件后接收方只拿到临时授权（`grantUriPermissions` 审计）。

**PH15/PH16 选择器与拉回** — 原样移植纯逻辑 `LaunchChooser`/`MainScreenSpill` 及其单测；超越：等用户选择时胶囊显示**选项预览**并可在胶囊里直接点选（选项来自无障碍树），省去切屏。验收：购物→支付跳转场景 20 次，误挪付款页 0 次、该挪回的挪回率 ≥ 95%。

**PH17 接管** — 超越：接管时把**这一步要你做什么**（来自模型的 hand_to_user 原因 + 截图上的高亮框）显示在顶部条；交还后自动重读核实并把差异告诉模型。验收：接管→挪主屏 ≤ 500 ms；交还后核实成功率 ≥ 95%；排队顺序与 UI 显示一致（多任务测试）。

**PH19 看图** — 超越：看图模型走我们已有的 `routing.vision`，没有看图模型时用**主模型 + OCR 坐标**降级定位（Mika 没看图模型就没有这组工具）；定位准度按模型自动累计（正确点击/总点击），够阈值自动标「已验证」，不靠开发者开关。验收：EV1 中「看图定位」题命中率 ≥ Mika 同模型；无看图模型时 OCR 定位成功率 ≥ 80%。

**PH20 系统命令（已领先）** — 加 `CommandSteer` 规则（发现命令在做点/滑/输入/打开 App 时提示改用专门工具）；验收：规则单测移植 + 新增 Shizuku 场景。

**PH21 恢复与修复** — 按 PL3 改为首次逐项同意；其余照搬（只开不关、Wi-Fi 未允许即停写、两次失败后停）。验收：手动强停无障碍服务后 ≤ 10 s 自动恢复；未同意项 0 次被写。

**PH22 向导** — 超越：一页同时检测 Shizuku/无线调试/root，推荐最省事的那条；所有步骤带「复制诊断」。验收：新手可用性测试（5 人）全部在 5 分钟内完成或得到明确失败原因。

**PH23 沙箱调手机** — `hh phone …` CLI 放进 rootfs，经 `AndroidCallbackServer` 的一次性令牌回到 RT13 执行入口；超越：脚本里的每个手机动作同样过审批（Mika 不审批）并能批量一次批准。验收：令牌一次性（重放被拒）、超时与直接调用一致、动作记录 100%。

**PH24 小画面** — 超越：画面帧同时供胶囊（CU2）与过程回看（OB7）复用，一路解码多路消费；验收：小画面开启时对话页滚动掉帧率 ≤ 5%（gfxinfo janky frames），帧率上限 5 fps、内存增量 ≤ 30 MB。

**PH25 版本** — 超越见 PH1（Android 10–12 降级模式）。验收：Android 10/12/13/14/15 模拟器矩阵冒烟通过。

**PH26/PH27** — 诊断合并进 `self_doctor` 输出（一个入口看内核+手机）；防截屏页统一走接管。验收：诊断输出不含包名以外的用户内容（脱敏单测）。

**PH28 USB 启动** — 移植脚本与文档，包名换成 `dev.hoshino.app`；验收：x86_64 模拟器 CI 用 `adb shell sh …usb-start.sh` 启动助手并跑一条手机冒烟。

### 3.4 WB 内置浏览器

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| WB1 | 常驻内置浏览器：WebView 渲染在 App 自建的私有虚拟显示上，不占屏、不要助手；标签池每任务 4 / 全局 8；页面弹框（alert/confirm/prompt/离开）处理；`web_tabs`/`web_interact` | `app/cap/web/WebHost.kt`、`WebTabPool.kt`、`TabBudget.kt`、`core/cap/web/PageDialogs.kt` | `web_render` 每次新建一个 1080×1600 WebView、30 s 超时、用完销毁，`domStorageEnabled=false`（`app/src/main/java/app/hoshino/harness/core/M2WebRenderer.kt`） | 🟡 | m3.0 | 未认领 |
| WB2 | 经 WebView DevTools 协议（CDP）发真实鼠标键盘事件；从不 `Runtime.enable`（避免被页面察觉）、读脚本只在隔离世界；连不上退回注入脚本；动作后等页面安定 | `app/cap/web/cdp/WebDevtools.kt`、`core/shell/DevtoolsSocket.kt`、`core/cap/web/SettlePolicy.kt` | 无（`web_render` 只能注入一段脚本） | ❌ | m3.0 | 未认领 |
| WB3 | 桌面版/手机版身份按站记忆（`web_site_mode`）；界面语言非跟随系统时网页按该语言请求（Accept-Language、navigator.language） | `app/cap/web/cdp/DesktopProfile.kt`、`PageLanguage.kt`、`SiteModes` | 固定手机 UA（`plugins/hoshino-m2/web.ts`） | ❌ | m3.0 | 未认领 |
| WB4 | 登录保持：Agent 与用户共用 Cookie/存储；站点登记表记访问过、是否登录、是否被验证拦过；设置「网站登录」可登录、单站退出、全部退出；`LoginProbe` 看登录状态 | `app/cap/web/SiteRegistry.kt`、`WebLogin.kt`、`LoginProbe.kt`、`app/ui/WebSitesSection.kt` | 无（每次渲染都是无状态 WebView） | ❌ | m3.0 | 未认领 |
| WB5 | 用户自用浏览器（侧栏「浏览器」）：起始页搜索+联想、常用网站（已登录小绿点）、最近打开、「给 Mika 登录」横幅、电脑版/手机版切换、地址栏判断、分享/在其他浏览器打开 | `app/ui/BrowserScreen.kt`、`BrowserStart.kt`、`BrowserPageParts.kt`、`app/browser/UserBrowser.kt` | 「Web 预览（实验性）」只有地址栏+刷新（`app/src/main/java/app/hoshino/harness/ui/tools/ToolScreens.kt` `WebPreviewScreen`），与 Agent 不共享登录 | 🟡 | m3.0 | 未认领 |
| WB6 | 读网页：正文、可交互元素（树+探针融合）、页面摘要、**页面自己收到的 JSON/文本响应**（`web_capture`，网络账本） | `core/cap/web/PageReads.kt`、`ElementFusion.kt`、`ElementProbe.kt`、`PageTree.kt`、`PageDigest.kt`、`NetworkLedger.kt` | `web_fetch`（HTML→Markdown 启发式，`plugins/hoshino-m2/web.ts`）+ `web_render` 返回 innerText/HTML 前 1 MB | 🟡 | m3.0 | 未认领 |
| WB7 | 下载落当前对话工作区 `downloads/`（不打开不安装）、上传只交工作区文件、下载地址策略 | `app/cap/web/WebDownloads.kt`、`UploadStaging.kt`、`core/cap/web/DownloadAddressPolicy.kt`、`Upload.kt` | 无 | ❌ | m3.0 | 未认领 |
| WB8 | 只读页面脚本 `web_evaluate_js`：隔离世界、页面对象只读包装、禁止一切联网、密码框读出 `***`；站点命令 `web_run_site_command`；`web_script` 只能经动作入口操作 | `core/cap/web/ReadScript.kt`、`ReadScriptTool.kt`、`WebScriptTool.kt`、`BrowserScript.kt`、`core/knowledge/SiteCommands.kt` | `web_render` 可跑任意脚本（审批后），非只读、可联网 | ❌ | m3.0 | 未认领 |
| WB9 | 网页接管：把同一个 WebView 挪到全屏页，表单与登录流程原样保留 | `app/web/WebTakeoverActivity.kt`、`app/cap/web/WebHandover.kt` | 无 | ❌ | m3.0 | 未认领 |
| WB10 | 翻页节奏与页数上限：同站最近 10 分钟第二页起停 4–7 s 递增至 12 s（careful 10–16 s 至 30 s）；每任务同站 20 页（careful 12）上限，用户答话重计，对外动作不受上限拦 | `core/cap/web/PagePacing.kt`、`PageDwell.kt`、`WebLimits.kt` | 只有全局请求最小间隔（`plugins/hoshino-m2/web.ts` `lastRequest`） | ❌ | m3.0 | 未认领 |
| WB11 | 障碍与风控识别：限流状态码、人机验证框架、字少的风控拦截页（访问异常/操作过于频繁/unusual traffic）→ `access_blocked` 交用户；`web_capture` 响应里的风控字段 → `riskControl`；`handle_obstacle` 上报 | `core/cap/web/WebBarrier.kt`、`core/eval/BarrierSignals.kt`、`prompts/tools/handle_obstacle.md` | 无 | ❌ | m3.0 | 未认领 |
| WB12 | 对外动作间隔：同站（可注册域名）/同 App（包名）的发送、提交、「打招呼」「投递」「Easy Apply」连着做时 8–20 s 随机，近 30 分钟越多越长、至多 30 s；成批做事不等名单确认、做到网站报上限即停 | `core/action/OutwardPacing.kt`、`ActionExecutor` | 无 | ❌ | m3.0 | 未认领 |
| WB13 | 停留中的空闲滚动（假装在读） | `core/uistate/IdleScroll.kt`、`WebPort.idleScroll` | 无 | 🚫 | — | 见 PL4 |
| WB14 | 联网搜索 | **没有搜索工具**：靠浏览器打开必应搜索 | 免 Key 多源搜索 + 故障切换 + 中文优先 + 公共 SearXNG 发现（`plugins/hoshino-m2/web.ts` `search`），m2.7 再加自带搜索 | ✅ | m2.7（加强） | m2.7（自带搜索） |

**WB1/WB2 浏览器内核** — 超越：①同样私有虚拟显示 + CDP 真实输入，但浏览器做成 **dsh 的 `ctx.web` 实现**（dsh 有 `dsh-web`/`dsh-tool-web` 接缝），`web_search`/`web_fetch`/浏览器工具同一服务，插件可复用；②标签池上限按内存动态定（RT1）；③CDP 会话断开自动重连并恢复标签（Mika 退回注入脚本）。
- 落点：A `app/.../web/`（移植 `WebHost`、`WebTab`、`WebTabPool`、`cdp/*`，`core/shell/DevtoolsSocket.kt`）；P `plugins/android-web` 注册 `web_*` 工具（名字与 Mika 一致）；D `dsh-web` 接缝（先读源码确认接口再接，不编造）。
- 验收：打开 → 可交互 P50 ≤ 2.5 s（同站第二页受 WB10 节奏约束除外）；CDP 点击成功率 ≥ 99%；`Runtime.enable` 调用次数 = 0（CDP 日志断言）；4 标签并行内存增量 ≤ 250 MB。

**WB3 身份** — 移植 `DesktopProfile`/`PageLanguage`；验收：桌面版站点（Google Flights、BOSS 直聘网页）按桌面布局渲染；切语言后 `navigator.language` 与请求头一致（CDP 断言）。

**WB4 登录保持** — 超越：①Cookie 存储随我们的**备份/恢复**（M2 已有加密备份）可选择带走，Mika 不导出；②「网站登录」页显示每站最后登录时间与 Mika 式「被验证拦过」标记，可一键「给 Agent 登录」；③登录态只在本机，CookieManager 数据不进 session log。验收：登录后杀进程重开，Agent 任务仍为登录态（10 站抽测 ≥ 9）；单站退出后该站 Cookie 清空（CDP `Network.getCookies` 断言）。

**WB5 用户浏览器** — 用 Compose 重写 Mika 的起始页/网页两态（不照抄 UI 代码也可以，行为对齐）；超越：在用户浏览器里**选中文字/长按图片直接「问 Hoshino」**、一键把当前页交给 Agent 继续（带登录态）。验收：用户在浏览器登录的站，Agent 任务直接可用；「交给 Agent」从点按到任务开始 ≤ 1 s。

**WB6 读网页** — 超越：Readability 级正文提取（m2-report 自认是启发式，换成 Mozilla Readability（Apache-2.0）的 JS 版注入隔离世界），`web_capture` 移植网络账本。验收：50 个新闻/文档页正文 F1 ≥ 0.9（对人工金样）；`web_capture` 能取回 SPA 的列表 JSON（B 站热门、知乎热榜类评测题）。

**WB7 下载上传** — 下载落 `<会话工作区>/downloads/`，自动生成文件卡（我们已有更多类型预览）；上传只从工作区挑。验收：EV1 `web-download-pdf`、`web-sandbox-pdf-text` 两题通过；下载 APK 不触发安装（策略单测）。

**WB8 只读脚本** — 移植 `ReadScript` 并**补上 Mika 路线图「已知问题 3」四个漏洞**：选择器先解析再判 `[value`、秘密输入框属性白名单（valueAsNumber/selectionStart/files…）、fetch 请求头白名单、建环境前已被改成 text 的密码框也认得（共享「曾是密码框」标记）。验收：针对四个漏洞各写攻击用例，全部被拦；Mika 同用例可绕过（作为超越证据记录）。

**WB9 网页接管** — 移植；超越：接管页顶部显示 Agent 想让你做什么（同 PH17）。验收：接管前后表单内容、滚动位置不变（CDP 比对 DOM 值）。

**WB10 翻页节奏** — 移植 `PagePacing`/`PageDwell`/`WebLimits`（守规矩部分，PL4）；超越：节奏参数与上限写在站点知识里可由社区维护（KS2），而非硬编码；用户可在模式里放宽（需确认）。验收：careful 站点 20 页浏览任务触发风控次数 ≤ Mika；普通站点额外等待中位数 ≤ 4 s/页。

**WB11 风控识别** — 移植 `WebBarrier`；超越：识别到风控后**自动暂停该站的所有并行任务**（Mika 只停当前），胶囊提示一次。验收：风控页样本集（≥ 40 张，含中英文）识别率 ≥ 95%，误报 ≤ 3%。

**WB12 对外动作间隔与成批** — 移植 `OutwardPacing` 间隔规则；与 PL2 合规：成批对外动作走**一次批量审批卡**（列出名单前 N 个 + 模板全文 + 预计总时长，可逐个剔除），批准后按间隔执行，进度在胶囊显示「第 3/10 个」，任何一次网站报上限即停。Mika 是不确认直接发。验收：10 人批量打招呼：审批卡 1 次、实际发送 = 批准名单、间隔分布符合 8–30 s、遇到「今日已达上限」后发送 0 次。

**WB14 联网搜索（已领先）** — 继续拉开：搜索结果进证据账本（OB1）作为来源；m2.7 的自带搜索完成后补「搜索→读页→回答」三步的首个反馈时间指标。

### 3.5 SB 沙箱与文件

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| SB1 | Linux 运行环境 | PRoot + Alpine 3.22，**仅 arm64**；Python/Node/shell；`run_command`、`read_file`、`write_file`、`search_files`（`app/cap/sandbox/*`、`core/cap/sandbox/*`） | PRoot（自编、锁源码 SHA）+ Alpine，arm64 与 x86_64；完整 bash、**真 PTY 终端界面**、Node 24、dsh 原版 `tool-bash`/`tool-fs`/`terminal`；`ensure_tools` 按需装媒体/文档/语音/编译工具（`rootfs/*`、`plugins/hoshino-m2`） | ✅ | — | — |
| SB2 | 每个对话一个工作区（`/workspace`，跨消息保留） | `core/workspace/*`、`app/cap/sandbox/SandboxDirs.kt` | 多工作区可选，默认共用 `/root/workspace`（`DshHarnessClient.createSession`） | 🟡 | m2.9 | 未认领 |
| SB3 | 默认断网，设置里「允许脚本联网」；设备无法隔离时只有打开才能跑 | `app/cap/sandbox/AndroidSandbox.kt`、`ProcRunner.kt`、原生启动器 `app/src/main/cpp/mikarun/mikarun.c`（seccomp 过滤拒绝 AF_INET 套接字、只留 AF_UNIX；另带超时、nice、进程数与文件大小上限；自检不通过即报 `sandbox_network_isolation_unavailable`）、`app/ui/AdvancedSection.kt` | `sandbox-policy mode: danger-full-access`，脚本随时联网（`node-host/src/main.ts`） | ❌ | m3.1 | 未认领 |
| SB4 | 每任务累计运行时间预算、同一命令反复失败叫停、进程数/文件大小上限 | `core/cap/sandbox/SandboxGuard.kt`、`mikarun`（`--max-procs`/`--max-file-mb`/`--nice`） | bash 单条超时 120 s/最长 600 s；无累计预算、无重复失败守卫 | ❌ | m2.9 | 未认领 |
| SB5 | 附件进工作区 `inbox/`：单个 ≤ 200 MB、最多 9 个；消息里写成路径行 | `app/cap/sandbox/Attachments.kt`、`app/ui/AttachmentMessage.kt` | 流式导入、单个上限 1 GiB（m2-report）；`AttachmentPicker.kt` | ✅ | — | — |
| SB6 | 文件出：`share_file` 存到「下载/Mika」+ 对话文件卡 | `app/cap/sandbox/ShareExporter.kt`、`core/cap/sandbox/ShareRules.kt` | `deliver_file` 文件卡 + `file.save` 存到「Download/Hoshino」+ `file.share`（`M2Runtime.kt`）；m2.7 修文件卡归位 | 🟰 | m2.7 | m2.7（文件卡归位） |
| SB7 | 文件查看页：网页、Markdown、PDF、图片、文本（前 1 MB） | `app/ui/viewer/FileViewerActivity.kt`、`ViewerKind.kt` | 音视频/图片/PDF/ZIP/DOCX 文本预览、流式保存分享（m2-report、`DeliveredFiles.kt`） | ✅ | — | — |
| SB8 | 知识/技能自带脚本 `run_bundled_script`：`scripts/manifest.json`（runnable/needs/network），按扩展名选解释器，不经 shell | `core/knowledge/ScriptManifest.kt`、`BundledScripts.kt`、`docs/sandbox/skill-scripts.md` | 无（技能只有文本） | ❌ | m3.0 | 未认领 |
| SB9 | 删除对话时删它的工作区：按句柄删、不跟链接、拿树锁、与重置互斥 | `app/chat/ConversationDeleter.kt`、`core/workspace/SandboxTree.kt` | 无删除会话（m2.7 做） | 🟡 | m2.7 | m2.7（删除会话） |
| SB10 | 工作区文件闸门：路径检查、规范化、树锁 | `core/workspace/WorkspaceFileGate.kt`、`WorkspacePathCheck.kt`、`FdSys.kt` | `plugins/android-policy/paths.ts` `workspacePath`、`M2Runtime.workspaceFile` 规范路径前缀检查；无 fd 级防 TOCTOU | 🟡 | m2.9 | 未认领 |

**SB1（已领先）** — 继续拉开：Mika 的沙箱不支持 x86_64、没有终端界面、没有按需工具；我们保持并补「每任务资源占用」（CPU 时间、内存峰值）进小结（OB3）。

**SB2 每对话工作区** — 超越：默认每个会话一个子目录（`/root/workspace/<会话短 id>`），但可「把这个会话挂到某个项目工作区」（我们的多工作区优势保留）；删除会话按 SB9 级联。验收：两个会话互相看不到对方文件（工具层 + bash 层各测）；挂项目后 AGENTS.md 正常生效。

**SB3 默认断网** — 超越：断网不是全局开关而是**按会话、按命令审批放行**：命令首次联网时弹「允许本会话脚本联网」审批（符合「网络请求默认审批」铁律），并显示目标主机；实现直接移植 Mika 的 `mikarun` 启动器（Apache-2.0，C，seccomp 拒 AF_INET/AF_INET6、留 AF_UNIX，同时给超时、nice、最大进程数、最大文件大小），作为独立可执行文件放在 proot 前面启动；设备装不上 seccomp 过滤时照 Mika 规则拒跑并说明原因。`ensure_tools` 的下载走 Kotlin 侧受控下载，不需要给脚本放网。验收：默认会话里 `curl`/`python urllib`/`node fetch`/`nc` 4 种方式联网全部失败并给出可读提示；批准后仅本会话可联网；`ensure_tools` 仍可用。

**SB4 运行预算** — D 装 `dsh-tool-call-timeout-policy`；P 移植 `SandboxGuard`（累计 CPU 时间、同命令连续失败 3 次叫停）。验收：死循环脚本在预算处被停并报告；同命令第 3 次失败后模型收到建议而非继续重试。

**SB8 自带脚本** — 超越：知识/技能脚本可以由**插件市场分发**（KS9），manifest 兼容 Mika 格式（`file/runnable/needs/network`）；`needs: apk:xxx` 由 `ensure_tools` 自动装（Mika 要求预装进 rootfs）。验收：移植 Mika 的 `skills/documents/scripts/extract_pdf.py` 跑通 EV1 `web-sandbox-pdf-text`；`network:true` 脚本在未批准联网时被拒（与 SB3 联动）。

**SB10 文件闸门** — 移植 `WorkspaceFileGate`/`FdSys` 的「按句柄、不跟链接」做法到 Kotlin 侧所有交付/保存/分享路径；验收：符号链接逃逸、`..`、竞态替换三类用例全部被拒。

### 3.6 CU 悬浮胶囊与「此刻的屏幕」

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| CU1 | 悬浮胶囊：借无障碍浮层画（不要悬浮窗权限）；一颗/展开胶囊两态、状态环、件数、3 s 淡到 40%、拖动贴边、记住边与高度比例、转屏复位、Mika 在前台/锁屏/开关关时不显示 | `app/capsule/*`、`app/cap/phone/a11y/OverlayHost.kt`、`CapsuleModel`、`CapsuleDock.kt`、`CapsuleFade` | 无（只有快捷磁贴、桌面小组件，`M2TileService`、`M2Widget`） | ❌ | m2.8 | m2.8 |
| CU2 | 胶囊卡片：任务卡（小画面 ≤ 5 fps、最近三步、停止、接管理由、同意/不同意、提问选项）与对话卡（同一份对话记录，最近 10 轮、上滑再加载） | `app/capsule/CapsuleCardContent.kt`、`CapsuleCardModel.kt`、`CapsuleThread.kt`、`CapsulePreview.kt` | 无 | ❌ | m2.8 | m2.8 |
| CU3 | 卡片里输入：插话/回答/接着说/空闲时接着最近对话或开新对话；点了才可聚焦、输入法弹出时整体避让 | `CapsuleController.submit`、`OverlayWindow.setFocusable`、`OverlayHost.imeTop` | 无 | ❌ | m2.8 | m2.8 |
| CU4 | 多件事与接管队列：按要紧程度只说一件、「共 N 件」「1 件要你处理」、接管先后读共享 `TakeoverQueue` | `CapsuleCards`、`TakeoverQueue` | 无 | ❌ | m2.8 | m2.8 |
| CU5 | 双击胶囊把此刻主屏发给 Mika；卡片截屏后补一句再发；截前藏浮层等两帧、截完读主屏树、树不够用时 OCR、防截屏页只记 App | `app/capsule/*`、`DoubleTap`、`ScreenAccessibility.takeMainScreenshot`、`MainScreenCapture.build`、`core/userscreen/UserScreenText.kt` | 无（`ScreenshotScreen.kt` 只截本 App） | ❌ | m2.9 | 未认领（若 m2.8 胶囊已含则并入） |
| CU6 | 「此刻的屏幕」给模型的规矩：只读/查直接做；要写入/发出/付款先 `ask_user` 问一次并给选项；之前的屏幕只留一行、`view_user_screen` 再看；屏幕存会话工作区 `screens/` | `core/agent/UserScreenTurns.kt`、`core/userscreen/*`、`app/chat/UserScreenFiles.kt`、`prompts/roles/user_screen.md` | 无 | ❌ | m2.9 | 未认领 |
| CU7 | 通知与胶囊协同：胶囊在亮屏上显示时「需要你」照发但静默进通知栏（分组提醒规则），锁屏/胶囊不在时照旧弹出 | architecture §15「通知」 | 单一通知频道（`M2Notifications.kt`） | ❌ | m2.8 | m2.8 |

**CU1–CU4 胶囊**（m2.8）— 超越门槛：①胶囊卡片支持**审批卡完整内容**（我们的四档审批 + 「本会话允许」），不只 Mika 的同意/不同意；②一颗的形态和动效可由 UI DSL 覆盖（`self_ui_override` 已支持 capsule 字段，见 `plugins/hoshino-self/overrides.ts`），用户可自定义——Mika 写死；③语音插话（我们已有语音输入）。验收：胶囊常驻时前台 App 滚动掉帧率增量 ≤ 1%（gfxinfo 对比）；展开动画 240 ms 内无掉帧（≥ 95% 帧 < 16.7 ms）；胶囊进程内存增量 ≤ 15 MB；拖动松手贴边误差 ≤ 2 dp。

**CU5 双击发屏** — 移植全部纯逻辑（`DoubleTap`、`UserScreenText`、帧提交后两帧再截）；超越：发屏后在卡片上**框选区域**只发那一块（省 token、少泄露隐私），框外打码。验收：双击→卡片出现截图气泡 ≤ 700 ms；防截屏页 100% 不留画面；框选后发给模型的图只含选区（像素断言）。

**CU6 屏幕规矩** — 移植提示词与上下文规则；与 PL2 一致：写入类先问一次（Mika 也是），之后仍走审批。验收：EV1 新增 10 题「只截屏不说话」：只读类直接答 ≥ 90%，写入类先问 100%。

**CU7 通知协同** — 拆三频道「需要你处理」（高）、「任务结果」（高）、「任务进度」（安静），移植 `NotificationOverview` 状态判断（与 CH5 一起）。验收：胶囊可见时「需要你」不弹横幅；锁屏时弹出（设备测试）。

### 3.7 SC 定时与条件任务

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| SC1 | `schedule_task` 工具：create/list/pause/resume/delete/run_now/reschedule | `core/general/ScheduleTask.kt` | dsh 原版 `schedule` + `tool-schedule` 已装载（`node-host/src/main.ts`，延迟装载组） | 🟡 | m2.9 | 未认领 |
| SC2 | 时间规则：一次性、固定间隔（错过合并并计数）、当地时间每天/每周几（跨夏令时钟点不变） | `core/trigger/Triggers.kt` | 依 dsh-schedule 语义，未做 Android 侧核对 | 🟡 | m2.9 | 未认领 |
| SC3 | 三种做法：固定提醒到点直接送达不调模型；办事任务到点作为无人在场运行；带条件的先只读检查（网页+沙箱），**只在不满足→满足时**执行；`whenIdle` 等空闲 | `core/trigger/ConditionOutcome.kt`、`TriggerRuntime` | 无 | ❌ | m3.1 | 未认领 |
| SC4 | 调度可靠：系统闹钟只是唤醒信号、文件是真源；派发前写好下次时间与窗口编号（同窗口不派两次）；能精确就精确，没权限引导去开并告诉模型「可能不准时」；开机/改时间/改时区/权限变化重布；进程中途被杀不重复操作 | `app/task/TriggerRuntime.kt`、`FileTriggerStore.kt`、`app/setup/BootRestoreReceiver.kt` | `M2Scheduler`：`setAndAllowWhileIdle`（非精确）+ WorkManager 15 分钟兜底 + 开机唤醒内核（`M2Scheduler.kt`）；无窗口去重、无精确闹钟引导、Manifest 无 `SCHEDULE_EXACT_ALARM` | 🟡 | m3.1 | 未认领 |
| SC5 | 结果回原对话：在创建它的对话里运行，结果/提醒/失败写回，通知点开就是它 | `TriggerRuntime`、`TaskCenter.queueInConversation` | 未核实 dsh-schedule 结果落在哪个会话；通知只打开主界面（`M2Notifications.open`） | ❌ | m3.1 | 未认领 |
| SC6 | 计划页：进行中/已停用两段、每条三行（做什么、规则与下次时间、上次结果）、开关即暂停恢复、看对话/立即执行/改时间/删除 | `app/ui/PlansScreen.kt`、`app/task/LocalScheduleAdmin.kt` | `SchedulesScreen` 有界面，但真客户端 `upsertSchedule/runScheduleNow/deleteSchedule` 未实现（`UnavailableHarnessClient` 抛「即将支持」） | 🟡 | m2.9 | 未认领 |
| SC7 | 无人在场规则：去掉追问/确认/接管/建定时工具；询问模式下花钱一步直接拒绝并照实说哪步没做成，其余照常做完 | `RunAssembler`、`Approval`(`approval_unavailable`) | 无 | ❌ | m3.1 | 未认领 |

**SC1/SC6 接通 dsh-schedule** — 超越：计划页直接读写 dsh `schedule` 服务（不另造调度器），每条显示**下 3 次触发时间预览**与**上次运行的结局小标（RT9）**——Mika 路线图把「预览接下来几次」列为未做。验收：新建/暂停/恢复/立即执行/改时间/删除 6 个操作在 UI 与 dsh 状态一致（集成测试）；跨日/跨周/夏令时 3 类规则下次时间计算单测。

**SC3 条件任务** — 超越：Mika 路线图列为未做的「条件结果带观测值、数值明显变化才提醒与冷却、免打扰时段、连续失败合并提醒、登录态 App 内条件监控」全部做：条件检查结果结构化（值、单位、来源），规则支持阈值/变化率/冷却；检查可用手机读屏（只读，m2.8 后可行），Mika 只能网页+沙箱。验收：「每 30 分钟查一次某商品价，降到 X 以下提醒，同一价格 6 小时内不重复」端到端跑 24 小时：提醒次数与人工预期一致、误提醒 0；检查失败不算「不满足」。

**SC4 可靠调度** — 移植 `TriggerRuntime` 的窗口去重与重布逻辑到 `M2Scheduler`；Manifest 加 `SCHEDULE_EXACT_ALARM`（A13 起需用户在系统里允许，引导页说明）；超越：任务开始与结束都写本地审计，计划页显示「准点 / 晚了 N 分钟（原因：系统限制）」。验收：Doze 下精确闹钟允许时准点误差 ≤ 1 分钟（真机 24 h，24 次）；未允许时误差如实显示；强杀后同一窗口重复执行 0 次。

**SC5 结果回原对话** — P 在触发时以 `origin=trigger` 恢复原 sessionId 执行；A 通知 deep link 到该会话。验收：通知点开 100% 落在原会话。

**SC7 无人在场** — 见 RT4（我们的规则更细：拒绝/延后/审批模型三选一）。验收：定时任务遇到下单步骤：不挂起、结果写明哪步没做、用户回来后可一键「现在做这一步」。

### 3.8 MM 记忆

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| MM1 | 三类记忆：事实、偏好（记下即用、可撤销）、做法（要用户批准） | `core/memory/MemoryItem.kt`、`MemoryStore.kt` | 无（提示词变量 `memory:''` 恒空，`plugins/hoshino-m2/index.ts`） | ❌ | m3.0 | 未认领 |
| MM2 | 只从用户自己说的话里记，不从屏幕/网页记；密码、验证码、卡号、证件号挡掉 | `core/memory/UserUtterances.kt`、`SensitiveFilter.kt` | 无 | ❌ | m3.0 | 未认领 |
| MM3 | 任务结束后后台自动提取（读过网页的运行不提取；限对话界面发起且交出回答） | `core/memory/MemoryExtractor.kt`、`app/task/RunLearning.kt`、`prompts/roles/memory_extract.md` | 无 | ❌ | m3.0 | 未认领 |
| MM4 | 开局注入一小块相关记忆 + `memory_save`/`memory_search` 工具 | `core/memory/MemoryContext.kt`、`MemoryIndex.kt` | 无 | ❌ | m3.0 | 未认领 |
| MM5 | 用户画像（单独存、`profile_edit`、下一个任务生效） | `core/memory/ProfileStore.kt` | 无 | ❌ | m3.0 | 未认领 |
| MM6 | 加密存储（Keystore 密钥）+ 记忆页（按类分组、改/置顶/删除、待确认、开关「使用记忆」「自动记住」、全部清除） | `app/memory/EncryptedMemoryFile.kt`、`app/ui/MemoryScreen.kt`、`MemoryViewModel.kt`、`app/settings/MemorySettings.kt` | 无 | ❌ | m3.0 | 未认领 |

**MM 整体超越方案**（m3.0，建议一个子代理整组做）
- 做成 dsh 服务 `ctx.memory`（插件 `plugins/hoshino-memory`），工具 `memory_save`/`memory_search`/`profile_edit` 与 Mika 同名；存储在 Kotlin 侧经 Keystore 加密（移植 `EncryptedMemoryFile`），插件经桥读写，**明文不落 node 侧磁盘**。
- 超越点：①每条记忆带**来源回链**（哪个会话哪一轮说的，可点回去看），Mika 只有内容；②**作用域**：全局 / 某个工作区（项目）/ 某个模式，项目里的偏好不污染别处；③检索用本地 BM25 + 可选本地小向量（不联网），Mika 是关键词索引；④加密导出/导入（Mika 路线图「已知问题 8」自认不做），走我们已有的备份通道，口令派生密钥；⑤「做法」批准后自动转成技能草稿（接 KS6）。
- 验收：EV1 记忆题 10 题（Mika 的 `eval/tasks/memory/*` 5 对 tell/use 直接移植）全过；敏感信息过滤单测（卡号/身份证/验证码/密码样本各 20）拦截率 100%；记忆文件在磁盘上为密文（无明文关键词，扫描断言）；开局注入 ≤ 400 token；记忆检索 P95 ≤ 30 ms（1 万条）。

### 3.9 KS 知识、技能、学习、回放

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| KS1 | 内置 App 知识 23 份（Android 设置、百家号、B 站、BOSS、Chrome、得到、抖音、飞书、Gmail、即刻、快手、领英、QQ 邮箱、Telegram、头条、Uber、Uber Eats、微信、微博、WhatsApp、闲鱼、小红书、知乎），头部含 packages/aliases/sites/open/pace/links | `core/src/main/resources/knowledge/apps/*/APP.md`、`docs/knowledge-format.md` | 无 | ❌ | m3.0 | 未认领 |
| KS2 | 内置网站知识 18 份（Amazon、B 站、Google Calendar、豆瓣、抖音、Instagram、Gmail、Google Maps、Telegram、贴吧、微博、搜狗微信、WhatsApp、小红书、小宇宙、YouTube、知乎、BOSS），含 mode/pace/接口 | `knowledge/sites/*/SITE.md`、`core/knowledge/SiteMatch.kt` | 无 | ❌ | m3.0 | 未认领 |
| KS3 | 技能：内置 4 类（内容转文字、PDF 文档、查清一件事、消息与邮件）+ 个人技能，格式统一 | `core/src/main/resources/skills/*/SKILL.md` | dsh 原版技能系统（`skill`、`tool-skill`、`android-skills` 文件系统提供者）+ `self_create_skill`；**没有随包内置技能内容** | 🟡 | m3.0 | 未认领 |
| KS4 | 知识自动补给：开局按任务文字匹配（≤ 3 条索引 + ≤ 1 份个人技能），进 App（读过界面）/网站（首次打开）时补正文，每份一次、每任务 ≤ 3 份；建议走 App 还是网页 | `core/agent/NoticePolicy.kt`、`GuideRecommender.kt`、`core/knowledge/KnowledgeStore.kt`、`ChannelAdvisor.kt` | 无 | ❌ | m3.0 | 未认领 |
| KS5 | 查阅工具 `knowledge_search`/`knowledge_read`（含 references 附件、列可运行脚本） | `core/general/LookupTools.kt` | 无 | ❌ | m3.0 | 未认领 |
| KS6 | 个人技能自学：办成且核实的任务按条件起草→用户在「待确认」收下→试用→核实成功转正式、失败停用；正式技能近 3 次 2 次不好自动停用（**Mika 因核查关着，这条在产品里不会发生**） | `core/skills/SkillDrafter.kt`、`SkillOutcomes.kt`、`PersonalSkills.kt`、`SkillRedactor.kt` | 无（只有手动 `self_create_skill`） | ❌ | m3.1 | 未认领 |
| KS7 | App 知识草稿学习（同上流程，试用期注入标「未核实」） | `core/knowledge/AppKnowledgeLearning.kt` | 无 | ❌ | m3.1 | 未认领 |
| KS8 | 经验回放：成功路线记录→同类任务直接回放、每步轻量核对、对不上交还模型（**同样因核查关着现在记不下**）；旧工具名映射 | `core/replay/*`、`core/tool/LegacyNames.kt` | 无 | ❌ | m3.1 | 未认领 |
| KS9 | 知识与技能的分发 | 只能随 App 发版、靠 PR 补 | 插件市场（DSH format-1 索引、npm/git 安装、更新提示，`plugins/hoshino-m2/market.ts`、`MarketScreen.kt`）可分发插件与技能 | ✅ | m3.0（知识包） | 未认领 |

**KS1/KS2 内置知识** — 超越：①**直接移植 Mika 全部 41 份知识**（Apache-2.0，文件头保留出处），格式 100% 兼容 `docs/knowledge-format.md`，构建期校验（移植 `KnowledgeIndex` 思路到 Node 脚本）；②知识做成**可通过插件市场分发的「知识包」**（KS9），社区不用等我们发版；③知识带「最后在哪个 App 版本上核对过」与评测覆盖标记，过时自动降权。验收：41 份全部通过校验；评测里带知识与不带知识对比，读类题模型往返次数下降 ≥ 20%；知识包装/卸载热生效（无需重启内核）。

**KS3 技能** — 移植 Mika 4 份技能为 dsh `SKILL.md`（dsh 技能格式与 Mika 不同处写转换器）；验收：`skill` 工具能检索到 4 份并在对应评测题中被使用。

**KS4 自动补给** — 做成插件 `plugins/hoshino-knowledge`：订阅手机/网页能力发出的「进入 App/站点」事件补正文；超越：补给决策可在「本轮上下文」面板看到并手动移除。验收：每任务注入 ≤ 3 份、每份 ≤ 2500 字；进入微信后第一步就使用知识中的入口（评测题人工核对）。

**KS6/KS7/KS8 学习与回放** — 前提是 RT9 结局判定 + RT17 核对**默认开着**（Mika 关着所以这三条在它的产品里是死代码）。超越：①草稿起草后在「待确认」里显示**脱敏后的步骤与适用条件**，可编辑再收下；②技能/路线可导出为插件分享（KS9）；③回放每步核对用 RT13 的 `ActionEffect`，对不上立即交还模型并记录分叉点，下次合并成更稳的路线。验收：同一手机任务第二次执行时模型往返次数下降 ≥ 50%、用时下降 ≥ 40%；回放失败交还后任务最终完成率不低于不回放；正式技能连续 2/3 失败自动停用（单测）。

### 3.10 AP 审批与风险

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| AP1 | 审批模型与档位 | 只有全局「询问/自主」一个开关（`app/settings/ApprovalControl.kt`、`ApprovalStore.kt`） | 四档审批 + 规则组合 + 作用域覆盖 + 高危确认开关 + 审批模型（超时/失败回退）+ 无参数无凭据的审计记录 + 通知「允许/拒绝/本会话允许」（`plugins/hoshino-m2/approval.ts`、`config.ts`、`M2Notifications.approval`） | ✅ | m2.7（折叠） | m2.7（审批折叠） |
| AP2 | 不可逆/对外/付款识别：按按钮文字与语境认出发送、提交、联系别人、投递、下单、付款；屏上有带币种金额时提高风险 | `core/gate/IrreversibleDetector.kt`、`core/risk/Irreversible.kt`、`core/action/RiskJudge.kt`、`ClassifiedRisk` | 只有按工具名/命令/路径的规则（`plugins/hoshino-m2/config.ts` `resolveApproval`），无界面语义识别 | ❌ | m2.9 | 未认领 |
| AP3 | 同意绑定：一次同意只放行紧接着的那一个匹配操作（同 App、同类、对象在屏、金额在屏、3 步内、中间无其他写） | `core/gate/ConfirmationGate.kt`、`core/action/ConfirmDecision.kt` | 「允许一次」按工具调用放行，无屏上对象/金额绑定 | ❌ | m2.9 | 未认领 |
| AP4 | 付款本身永远交用户亲手做（`hand_to_user` reason=payment） | `core/action/Approval.kt` | 无 | ❌ | m2.9 | 未认领 |
| AP5 | 下单前请用户同意 `request_confirmation`：必须写出屏上金额，写不出直接退回；卡片写明在哪、买什么、多少钱；对话页/胶囊/通知三处可答 | `prompts/tools/request_confirmation.md`、`app/confirm/*` | 审批卡 + 通知审批已有（`PermissionUi.kt`、`M2Notifications.approval`），卡片无「在哪/买什么/多少钱」结构 | 🟡 | m2.9 | 未认领 |
| AP6 | 「自主」全局开关（连付款也不问）；中途切换对正在跑的任务生效规则 | `ApprovalControl.setAutonomous` | m2.7 做「无审批」（只能用户主动开） | 🟡 | m2.7 | m2.7（无审批） |
| AP7 | 不对自己动手：询问模式下 Agent 不操作 Mika 自身界面（自主模式下 Mika 这层保护失效，路线图自认） | `ConfirmDecision.judge`(`ownApp`) | 无（尚无手机操作） | ❌ | m2.8 | m2.8 |
| AP8 | 默认只问花钱，发消息/删除/改设置都不问 | `Approval.kt` | — | 🚫 | — | 见 PL2 |

**AP1（已领先）** — 继续拉开：m2.7 审批折叠后，把 AP2 的语义风险作为审批规则的新维度（`risk: outward|payment|irreversible`），用户可写「对外发送在 BOSS 直聘本会话允许」这种细规则。

**AP2/AP3/AP4 语义风险与绑定** — 移植 `IrreversibleDetector`（含中英按钮词表）、`ConfirmationGate` 纯逻辑与单测；作为 P `plugins/android-policy` 的风险判定器输入给审批。超越：①风险判定在**无人在场、自主、无审批模式下照样运行并记审计**（Mika 自主时 `ownApp` 保护失效，我们永不失效）；②付款页识别后自动接管（PH17），并在接管条上显示金额。验收：按钮词表样本 300 条（中英，含「立即沟通」「Easy Apply」）识别准确率 ≥ 97%；同意绑定越界（同意 A 后点 B）放行 0 次；付款由 Agent 完成的次数 0。

**AP5 确认卡** — 超越：卡片附**截图局部高亮**（金额、商品、地址所在区域，来自 OCR 坐标），Mika 只有文字。验收：确认卡金额与屏上金额一致率 100%（写不出金额的申请被退回，单测）。

**AP7 不对自己动手** — 执行入口拒绝目标包名为 `dev.hoshino.app` 的手机动作（任何模式），除非用户在设置里显式允许「让 Agent 操作 Hoshino 自己」。验收：越权用例 0 放行。

### 3.11 MD 模型与服务商

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| MD1 | 四种协议：OpenAI 兼容（Chat Completions）、OpenAI Responses、Anthropic、Gemini | `core/model/*`（`OpenAiCompatModel`、`ResponsesModel`、`AnthropicModel`、`GeminiModel`） | `openai-completions`、`anthropic-messages`、`openai-codex`（ChatGPT）三种映射（`plugins/android-api/index.ts`）；底层 pi-ai 支持更多但未映射 | 🟡 | m2.9 | 未认领 |
| MD2 | 服务商预设 20 家：DeepSeek、通义千问、Kimi、智谱、硅基流动、火山方舟、MiniMax、阶跃星辰、腾讯混元、百度千帆、OpenRouter、OpenCode Go、Anthropic、Gemini、OpenAI、xAI、Mistral、Groq、Ollama、LM Studio + 自定义 | `app/settings/ProviderPresets.kt` | 5 项：Anthropic、OpenAI、DeepSeek、Ollama、自定义（`DshHarnessClient.kt` `CatalogProvider`） | 🟡 | m2.9 | 未认领 |
| MD3 | 用 ChatGPT 登录（PKCE S256、动态 client 注册、JWKS 验签、刷新互斥、注销、套餐额度错误分类） | `core/chatgpt/*` | `core-bridge/.../ChatGptAuth.kt`、`ChatGptSubscription.kt` 已有基础 | 🟡 | m2.7 | m2.7（ChatGPT 登录） |
| MD4 | 测试并保存：先列模型→再用无参工具 `get_time` 真调用，最多试 3 个候选（自家模型优先、跳过向量/重排/语音/画图），通过记 `verifiedModels`；错误分类（Key/网络/地址/没开通/没有这个模型/不会调工具） | `ModelSetupViewModel`、`ProviderStore.probe`、`core/model/ModelCatalog.kt`、`ModelAccessErrors.kt` | `testProvider` + `ProviderProbe.kt`（45 行） | 🟡 | m2.7 + m2.9 | m2.7（中转站探测）；其余未认领 |
| MD5 | 一个服务商多协议：模型列表里每个模型带 `api` 表（chat-completions/messages/generate-content/responses），按会说的第一种选适配器 | `ModelCatalog`(`ModelEndpoint`)、`ProviderStore.modelFor` | 无 | ❌ | m2.9 | 未认领 |
| MD6 | 思考内容回传：`reasoning_content`/`reasoning`/分块 thinking 按收到的地址与位置原样放回，只回传同一地址收到的 | `OpenAiCompatModel`(`ReasoningVia`) | 依 dsh pi-ai，**未核实**多家思考字段回传是否正确 | 🟡 | m2.9 | 未认领 |
| MD7 | 模型用途：主 / 看图 / 轻量（判结局、提记忆、意图、路线匹配）；「简单步骤交给轻量模型」 | `ModelRole`、`RoleChoices`、`core/agent/ModelRouter.kt` | `routing.json`：compact / approval / vision 用途 + **备用路由链** + 按模式指定模型（`plugins/hoshino-m2/index.ts`） | ✅ | m2.9（加轻量用途） | 未认领 |
| MD8 | 统一请求头：`User-Agent: Mika/<版本>`、按预设声明的会话头（如 `x-opencode-session`，UUID v3 不可逆） | `core/model/ClientHeaders.kt`、`ModelSession` | 每服务商可配自定义 headers（`plugins/android-api/index.ts`） | 🟰 | m2.9 | 未认领 |
| MD9 | 明文 http 提醒：本机/局域网不提示，其他明文地址提醒一句 | `LocalHosts.isLocal`、`AddForm.unencryptedWarning` | 允许明文（`usesCleartextTraffic=true`）但无提醒 | 🟡 | m2.9 | 未认领 |
| MD10 | 能力标签：接口给了照实显示，没给沿用预设并标「未确认」 | `app/settings/ModelOptions.kt` | `metadata.models` 记 vision/reasoning/context（`ChatSheets.kt` 显示上下文长度） | 🟰 | m2.9 | 未认领 |

**MD1/MD5 协议** — 超越：在 `plugins/android-api` 映射 pi-ai 已支持的 `openai-responses`、`google-generative-ai`（以 pi-ai 实际导出的 api 名为准，先读源码），并支持按模型 `api` 表选协议；协议自动探测（同一 Key 依次试，m2.7 中转站探测的延伸）。验收：四协议各有 mock-llm 集成测试（流式、工具调用、图片输入、思考）；OpenCode Go 类多协议服务商可用。

**MD2 预设** — 超越：预设做成**可在线更新的 JSON**（随插件市场索引或 GitHub raw，带签名/哈希），新服务商不用发版；数量 ≥ Mika（20+），并为国内中转站提供「探测→建议预设」。验收：20 家预设各一条连通性用例（需真 Key 的标记为手动）；预设 JSON 改动无需重装 APK。

**MD3 ChatGPT**（m2.7）— 门槛：与 Mika 同等安全性（PKCE、nonce、JWKS 验签、刷新互斥、`invalid_grant` 清令牌）；超越：多账号切换、额度用尽时自动切备用路由（我们有，Mika 只报错）。

**MD4 测试并保存** — 移植探测算法（候选排序、`ModelAccessErrors` 说法表）；超越：探测同时测**首字延迟与吞吐**并显示，帮用户选快的。验收：7 类错误分类单测；探测总时长 ≤ 20 s；首字延迟显示与实际偏差 ≤ 20%。

**MD6 思考回传** — 写 mock 测试覆盖 DeepSeek（`reasoning_content`）、OpenRouter（`reasoning`）、分块 thinking 三种，确认 dsh/pi-ai 行为；若 pi-ai 不支持某种，经插件 `agent/request` 钩子补，不改 dsh。验收：三种格式带工具多轮对话不报 400。

**MD7 用途（已领先）** — 加「轻量」用途（判结局、提记忆、意图、标题），轻量模型不支持工具时不接手机步（Mika 规则）。验收：开启后主模型调用次数下降可测（OB3）。

**MD8–MD10** — 预设声明会话头 + 统一 `User-Agent: HoshinoHarness/<版本>`；明文提醒移植 `LocalHosts`；能力标签标「未确认」。验收：各一条单测。

### 3.12 OB 可观测：来源、过程、小结、用量

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| OB1 | 回答来源由系统生成：从证据账本挑支持回答的观察（≤ 6 条），回答下「来源」胶囊，来源页有截图显截图、无截图显原文摘录 | `core/verify/AnswerSources.kt`、`EvidenceLedger.kt`、`app/ui/SourceScreen.kt` | `web_search` 结果带 URL，回答里是否引用全靠模型；无来源 UI | 🟡 | m2.9 | 未认领 |
| OB2 | 过程记录与回看：每次运行 `run.json`+`events.jsonl`+截图（保留 30 次），时间线竖线步骤、点步骤看当时画面 | `app/record/RunStore.kt`、`core/record/RunEvent.kt`、`app/ui/RunTimelineScreen.kt` | 每轮过程组折叠（`ProcessTimeline.kt`）+ 轨迹页（`TrajectoryScreen`）+ dsh 完整 session log（不限 30 次）；**没有逐步画面** | 🟡 | m2.9 | 未认领 |
| OB3 | 每任务一行小结：总用时 · 和模型来回 N 次 · Token · 第一次反馈 X 秒；展开看模型思考/读屏/操作/其他的时间去向 | `core/record/RunMetrics.kt`、`RunMetricsStore.kt`、`app/ui/RunMetricsUi.kt` | 无（dsh 有 `dsh-session-stats`、`dsh-session-turn-outline` 未装载） | ❌ | m2.9 | 未认领 |
| OB4 | Token 用量页：源头计量（所有旁路调用都算）、按 UTC 整点×服务商×模型×用途存 400 天；24h/7/30/90 天/自定义；按服务商叠柱、服务商/模型排行 | `core/usage/*`、`app/ui/UsageScreen.kt` | 只有单会话上下文环（`ChatSheets.ContextUsageSheet`、dsh `token-meter`） | 🟡 | m2.9 | 未认领 |
| OB5 | 运行统计：最近 10/30/100 次办成率、用时与首个反馈中位数、平均来回与 Token、时间去向，复制/分享 JSON | `RunStats`、`app/ui/DeveloperOptionsScreen.kt` | 无 | ❌ | m2.9 | 未认领 |
| OB6 | 运行记录脱敏：密码框输入与像密钥的字符串写入前遮掉 | `core/record/Redactor.kt` | `CredentialRedaction.kt`、日志不含 Key（铁律） | 🟰 | m2.9（扩到画面） | 未认领 |
| OB7 | 回看过去的画面 `view_past_screens`（帧编号、画面差异） | `core/visual/FrameGrid.kt`、`Shots.kt`、`prompts/tools/view_past_screens.md` | 无 | ❌ | m2.9 | 未认领 |

**OB1 来源** — 超越：证据账本做成 dsh 级服务，网页、搜索、手机读屏、沙箱输出、MCP 资源都进账本；来源支持**跳回原位**（网页高亮那段、手机步骤看截图、文件打开到行号），Mika 只显示摘录或截图。验收：EV1 读类题回答带来源率 ≥ 95%，来源与回答数值匹配率 ≥ 95%（RT17 规则核对复用）。

**OB2 过程回看** — 超越：dsh session log 已是全量可重放记录（Mika 只留 30 次），补「逐步画面」（PH24 帧复用、JPEG 压缩、按会话存工作区外的私有目录、随删除会话清理），并支持**从任一步分叉新会话**（我们已有 fork）。验收：手机任务每步都有画面；1 小时任务记录 ≤ 50 MB；分叉后新会话上下文止于所选步。

**OB3 小结** — 装 D `dsh-session-stats`/`dsh-session-turn-outline`，P 补首个反馈时间与时间去向；口径照抄 Mika `RunMetrics`（便于对比，见 §5）。超越：小结里加**缓存命中率、预估费用（用户可填单价）、本轮工具调用最慢的 3 个**。验收：小结字段与 session log 重算结果一致（单测）；首个反馈时间与 UI 实测偏差 ≤ 100 ms。

**OB4 用量页** — 超越：计量在桥层（`plugins/android-api` 的 provider 出口 + dsh token-meter 事件），**插件自己发起的模型调用也计**；页面除 Mika 全部维度外加「按会话/按模式」排行与**预算提醒**（日/月上限，到 80% 提醒、到 100% 可选暂停非交互任务）——m2-report 自认「估算费用和任务预算 UI 尚未接通」。验收：与服务商控制台账单抽样核对误差 ≤ 2%（以服务商返回 usage 为准）；400 天数据文件 ≤ 2 MB；页面首屏 ≤ 300 ms。

**OB5 运行统计** — 放在设置 › 开发者（我们没有隐藏开发者选项，直接在「关于 › 运行统计」）；验收：与 EV2 的汇总口径一致。

**OB6 脱敏** — 扩到画面：逐步画面里密码框区域（无障碍 `isPassword` 或 CDP `type=password` 坐标）打码后再存。验收：含密码框的步骤截图像素断言已遮挡。

**OB7 回看画面** — 移植 `FrameGrid`/`Shots`；验收：模型调用 `view_past_screens` 能取回 N 步前画面并据此作答（评测题）。

### 3.13 CH 渠道、送达、MCP

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| CH1 | 送达账本：先记再发，Pending→Attempting→Delivered/Failed/Uncertain，同一编号只送一次，结果不明不自动重发 | `core/run/Outbox.kt`、`app/task/FileOutboxStore.kt` | 用户→内核方向有待发队列（`PendingKernelMessages.kt`、`DshHarnessClient` outbox）；结果→用户方向无账本 | 🟡 | m3.1 | 未认领 |
| CH2 | 渠道接口：主人身份、提问编号对应、付款确认只能在能确认的渠道做；目前只有本机渠道 | `core/run/Channel.kt`、`ChannelGateway.kt`、`app/task/LocalChannel.kt`、`prompts/capabilities/channels.md` | 无（Webhooks 界面有，真客户端未实现） | ❌ | m3.1 | 未认领 |
| CH3 | MCP 客户端（按注解定风险、工具列表开局冻结）——Mika **只有骨架、没有传输层，用户用不到** | `core/cap/mcp/McpClient.kt` | MCP 管理界面与接口已有（`ExtensionScreens.kt` `McpScreen`、`HarnessClient.mcpServers`），真客户端未实现；dsh 有 `dsh-mcp-client`、`dsh-mcp-resources` 未装载 | 🟡 | m3.1 | 未认领 |
| CH4 | 外部发起任务（MCP 服务端 / `ACTION_START_MCP`）——Mika 同样只有骨架 | `core/mcp/McpServer.kt`、`app/task/McpTaskHub.kt` | 无；dsh 有 `dsh-acp`（JSON-RPC stdio 自动化协议）、`api-remote-access` 已装载 | ❌ | m3.1 | 未认领 |
| CH5 | 本机通知：进度/需要你/结果三频道，频道被调低时提示并直达系统设置，通知设置页只看状态 | `TaskService.ensureChannels`、`app/settings/NotificationOverview.kt`、`SettingsSubPages` | 单频道 `hoshino-actions`（IMPORTANCE_DEFAULT）；审批与提问通知可直接操作（`M2Notifications.kt`） | 🟡 | m2.9 | 未认领 |

**CH1/CH2 渠道** — 超越：Mika 只有接口没有第二个渠道；我们做**零后端、用户自带凭据的渠道插件**：Telegram Bot（长轮询，手机直连）、邮件（IMAP IDLE/SMTP）、Matrix，作为可选插件（插件市场分发），每个渠道声明 `confirms`（能否承载付款/审批确认），主人身份绑定用一次性配对码。送达账本移植 Outbox 状态机。验收：Telegram 渠道发起任务→结果送回，进程被杀后恢复不重发（Uncertain 只提示）；非主人消息 100% 被拒；不能确认的渠道上审批请求转本机通知。

**CH3 MCP 客户端** — 超越：Mika 没有可用 MCP，我们装 D `dsh-mcp-client`/`dsh-mcp-resources`，把 `McpScreen` 接上（stdio 在 proot 里跑、HTTP/SSE 直连），MCP 工具按注解进审批规则，资源进证据账本。验收：接一个本地 stdio MCP（如 filesystem server）与一个远程 HTTP MCP，工具可调、可禁用单个工具、审批生效。

**CH4 外部发起** — D 评估 `dsh-acp`；A 暴露受签名限制的 Intent（`dev.hoshino.app.action.START_TASK`，只接受用户在设置里授权的调用方包名）+ Tasker/快捷指令兼容。验收：授权包可发起并取消任务；未授权包 100% 被拒。

**CH5 通知** — 与 CU7 一起拆三频道 + 状态页；验收：频道调低检测与跳转在 A13/A14 设备测试通过。

### 3.14 UX 界面、会话、通知、语言、上手

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| UX1 | 对话管理：长按菜单（抬起卡片动效、置顶/改名/删除）；删除级联（先删绑定定时→停任务等收尾→删工作区→删对话文件），晚到的补写不复活 | `app/ui/ConversationMenu.kt`、`app/chat/ConversationDeleter.kt`、`ConversationStore` | 改名、分叉、归档、全文搜索已有（`Sidebar.kt`、`DshHarnessClient.forkSession/searchSessions`）；删除由 m2.7 做；**无置顶** | 🟡 | m2.7 + m2.9 | m2.7（删除）；置顶未认领 |
| UX2 | 侧栏分段：进行中（含别的对话在跑）/置顶/最近；别处有任务时菜单按钮小圆点 | `app/ui/ChatDrawer.kt`(`drawerSections`) | 侧栏有会话列表与工作区（`Sidebar.kt`），无「进行中」分段与提示点 | 🟡 | m2.9 | 未认领 |
| UX3 | 对话页进度：一行状态（转圈、进展、用时）+ 竖线步骤；要你处理时停在当前步（橙点「已暂停」）+ 白卡写清做什么；完成后收成「做了 N 步 · 用时 · 查看过程」 | `app/ui/ChatScreen.kt`、`components/StepLine.kt`、`ChatCopy.kt` | 过程组默认折叠、审批/提问/文件卡独立（`ProcessTimeline.kt`、`Messages.kt`）；m2.7 做审批折叠 | 🟡 | m2.7 | m2.7（审批折叠） |
| UX4 | 回答与用户消息可长按选中复制，链接照常点，正在生长的回答不可选 | `ChatScreen`(`SelectionContainer`) | 长输出选择复制与查看全部（m2-report） | 🟰 | — | — |
| UX5 | 附件面板：占键盘位置、相机/相册/文件 + 四列最近照片（权限分级处理）、最多 9 个、照片格子渲染、只发附件也行 | `app/ui/ChatAttachments.kt`、`AttachSources.kt`、`AttachmentMessage.kt` | 相机/图片/文件选择（`AttachmentPicker.kt`、`Composer.kt`），无最近照片格子 | 🟡 | m2.9 | 未认领 |
| UX6 | 设置首页总状态卡：一切就绪 / 有问题时提示 + 修复按钮（模型、手机操作、通知、精确闹钟…） | `app/settings/Prerequisites.kt`、`SystemStatusMonitor.kt`、`app/ui/SettingsScreen.kt` | 无 | ❌ | m2.9 | 未认领 |
| UX7 | 首次上手：欢迎 → 配置模型 → 手机操作（可跳过、按「看过没有」判断） | `app/ui/WelcomeScreen.kt`、`Onboarding.kt` | 无 | ❌ | m3.2 | 未认领 |
| UX8 | 界面多语言：英文默认 + 中文 + 繁中复制；设置里跟随系统/中文/English；A13 起交给系统应用语言 | `app/i18n/*`、`res/values-zh/`、`app/build.gradle.kts`(`hantStrings`) | 中文硬编码（`res/values/strings.xml` 78 条，其余写在 Kotlin 里） | ❌ | m3.2 | 未认领 |
| UX9 | 关于页：版本、Beta、构建号（git 短哈希）、检查更新与更新记录、反馈模板（预填机型/安卓/版本） | `app/ui/AboutScreen.kt`、`app/about/*` | 关于页版本写死「版本 0.1.0 (1)」（`SettingsScreens.kt`）；反馈页可附内核日志 | 🟡 | m3.2 | 未认领 |
| UX10 | 许可证页：Apache 原文 + 第三方组件列表 | `app/ui/LicensesScreen.kt`、`assets/licenses/` | 关于页两行文字（`SettingsScreens.kt:422-426`），无原文与完整清单 | ❌ | m3.2 | 未认领 |
| UX11 | 主题令牌、深浅色跟随系统 | `app/ui/theme/*` | 主题 + UI DSL 可改颜色/字号/令牌（`Theme.kt`、`plugins/hoshino-self/overrides.ts`） | ✅ | — | — |
| UX12 | 对话标题自动起（轻量模型，用户改过的不覆盖） | `prompts/roles/conversation_title.md`、`ConversationStore.setTitle` | dsh `session-title` 已装载 | 🟰 | — | — |
| UX13 | 读屏无障碍：胶囊「移到另一边」、对话操作面板标题、按钮说法 | architecture §15 各处 | 未系统处理 | ❌ | m3.2 | 未认领 |
| UX14 | 检查上游更新：只问 GitHub releases/latest、红点、国内备用下载按钮 | `app/about/UpdateChecker.kt` | `self_update` 带签名/包名/SHA256 校验与 3 个回滚包（**比 Mika 强**），但没有「看上游有没有新版」 | 🟡 | m3.2 | 未认领（默认手动，见 PL5） |
| UX15 | 相机 `required=false` 声明（防系统按特征把 App 当游戏限刷新率）+ perf 构建 | `AndroidManifest.xml`、`ManifestProvidersTest`、`assemblePerf` | 已有非调试 fast 构建（m2.5）；Manifest 无相机特性声明 | 🟡 | m2.9 | 未认领 |
| UX16 | 开发者选项 / 工程开关（速度、实验） | `app/settings/FeatureFlags.kt`、`DeveloperOptionsScreen.kt` | 9 种模式、公开配置文件、`self_config_*`、UI DSL（远比开关灵活） | ✅ | — | — |
| UX17 | 流式 Markdown 渲染 | `app/ui/StreamingMarkdown.kt`、`MarkdownText.kt` | 流式按帧合并（m2.5）、Markdown 组件（`Markdown.kt`） | 🟰 | m2.7 | m2.7（性能） |

**UX1/UX2** — 超越：置顶 + 归档 + 分叉 + 全文搜索四件齐全（Mika 无归档/分叉/全文搜索）；删除级联照 Mika 顺序（m2.7）。验收：删除绑定 2 条定时的会话后定时为 0、工作区目录不存在、晚到标题不复活（集成测试）。

**UX3 进度** — m2.7 门槛：完成后折叠行显示「N 步 · 用时 · Token · 首个反馈」（接 OB3）；要你处理时进度停在当前步。验收：长任务（100 步）滚动掉帧率 ≤ 5%。

**UX5 附件** — 移植面板交互（键盘位互换不跳动）；超越：附件可直接从**工作区文件**和**最近交付的文件卡**里挑。验收：键盘↔面板切换输入栏位移 0 px（截图比对）。

**UX6 总状态卡** — 移植 `Prerequisites` 思路，项目覆盖模型、内核、手机助手、无障碍、通知频道、精确闹钟、电池优化；验收：每项故障注入都出现对应修复按钮且一键直达。

**UX7 上手** — 超越：上手第二步直接复用 MD4 探测，第三步给 Shizuku/无线调试/root 自动择优（PH2）。验收：新机从安装到第一条回答中位时间 ≤ 3 分钟（不含输 Key）。

**UX8 多语言** — 先把全部界面文字抽到资源（脚本扫描 Kotlin 中文字面量），英文 + 简中 + 繁中（自动复制）三份，加 `StringResourcesTest` 同款校验（EV6）；超越：UI DSL 覆盖文字也走资源键。验收：英文系统下无中文残留（扫描断言）；切换语言不重启进程。

**UX9/UX10/UX14** — 关于页读 `BuildConfig.VERSION_NAME` 与 git 短哈希；许可证页列 `THIRD_PARTY.md` + `licenses/` 全文（含移植后的 Mika/scrcpy/PaddleOCR 等）；上游更新检查默认手动。验收：版本显示与 APK 一致（测试断言）。

**UX13 无障碍** — 关键交互全部有 `contentDescription`/语义操作；验收：TalkBack 走查清单 30 项全过。

**UX15** — Manifest 加 `<uses-feature android:name="android.hardware.camera" android:required="false"/>` 并加 Manifest 测试。

### 3.15 EV 评测、诊断、质量门

| ID | 功能 | Mika（代码） | 我们现状（代码） | 状态 | 版本 | 认领 |
|---|---|---|---|---|---|---|
| EV1 | 真机评测题集：128 题，按 read/system·content·shopping、write、irreversible、impossible、barrier、web、skills、memory 分类；tune/holdout 七三开；`answer_regex/answer_grounded/screen_has/asked_user/confirmation_shows` 等核对；探针、自动回答、标记清理 | `eval/tasks/**`、`eval/README.md`、`core/eval/EvalTask.kt`、`Checks.kt` | 无任务级评测（有 M1 仪器回归与 mock-llm） | ❌ | m2.9 | 未认领 |
| EV2 | 评测器：经真实任务路径一题一题跑、打分、`summary.md`/`scores.jsonl`/`metrics.json`、渠道强制（web/app）、接管时取消或等、人工改判规则 | `app/src/androidTest/.../eval/EvalRunTest`、`core/eval/Scoring.kt`、`EvalReport.kt`、`RunFacts.kt` | 无 | ❌ | m2.9 | 未认领 |
| EV3 | 假模型剧本单测 + 提示词金样（四种展开情形的全貌与大小） | `core` 单测、`prompt-surface/layers/` | `tools/mock-llm/server.mjs`、`FakeHarnessClient.kt`、原版 dsh 集成测试；无提示词金样 | 🟡 | m2.9 | 未认领 |
| EV4 | 架构依赖守卫：能力之间互不引用、工具引擎不引用界面状态 | `PackageDependencyTest` | 无 | ❌ | m2.9 | 未认领 |
| EV5 | 诊断信息：用户主动点才收集，先预览再复制/分享；固定英文分段；服务商只写类型与主机名；整段脱敏（IP+端口、邮箱、名=值、Redactor） | `app/about/DiagnosticsCollector.kt`、`Diagnostics.kt`、`app/ui/DiagnosticsSheet.kt` | `self_doctor`（内核/插件/桥能力）、反馈页附内核日志、`tools/diag` | 🟡 | m3.2 | 未认领 |
| EV6 | 字符串资源一致性测试（条目、占位符、英文里无中文） | `StringResourcesTest` | 无 | ❌ | m3.2 | 未认领 |
| EV7 | 性能/启动/内存基准 | perf 构建 + 每任务 `RunMetrics` | `benchmark-driver`：冷启动 5 次、首个可见 token、app/proot/node PSS 分类（`benchmark-driver/`、`tools/verify-metrics.py`） | 🟰 | m2.9 | 未认领 |

**EV1/EV2 评测** — 这是「超越」的裁判，**m2.9 第一优先**。超越：①直接移植 Mika 128 题（Apache-2.0，保留出处），JSON 格式兼容，评测器跑在我们的真实路径（dsh 会话 + 手机/网页插件）；②**双跑模式**：同机同模型下同时驱动 Mika APK（用它自带的 `EvalRunTest`）与 Hoshino，产出对比报告（完成率、首个反馈、往返、Token、用时）——这是 §0 定义的超越证据；③保留池纪律照搬（holdout 只用于发版决定）；④CI 用 x86_64 模拟器 + mock 模型跑「冒烟子集」，真模型全集由维护者手动触发。验收：128 题能解析并全部可执行（缺 App 的按 `requires` 跳过）；对比报告自动生成；任何 PR 改动提示词需附 tune 子集结果。

**EV3 金样** — 系统提示词与工具定义的全貌金样（收着/只开手机/只开网页/全开四种），PR 改动时 diff 一目了然、token 大小有门槛。验收：金样测试在 CI 运行。

**EV4 架构守卫** — 对 Kotlin（`app/.../phone` 不引用 `ui`，执行入口唯一）与插件（插件之间只经 Cordis 服务、不 import 彼此内部文件）各写一条依赖测试。验收：CI 失败可复现。

**EV5 诊断** — 合并 `self_doctor` + 手机/网页状态 + 运行小结，移植 Mika 的脱敏规则与分段顺序；验收：脱敏单测（IP:端口、邮箱、`token=…`、Bearer、API Key 样式）100% 遮挡。

**EV7 基准（持平，补齐）** — 把 benchmark-driver 的冷启动/首 token/PSS 与 OB3 的每任务小结合并进 EV2 报告；m2-metrics.json 至今全是 null（`m2-metrics.json`），m2.9 起必须由 CI 真数据填上。

## 4. 我们已经领先、Mika 没有的能力（移植时不得削弱）

| # | 能力 | 我方实现 | Mika 情况 | 继续拉开的方向 |
|---|---|---|---|---|
| L1 | 插件生态与插件市场（DSH format-1 索引、npm/git/本地/tgz 安装、更新提示、兼容电脑版 DSH 插件） | `plugins/hoshino-m2/market.ts`、`ui/extensions/MarketScreen.kt` | 无插件机制 | 手机/网页/知识/渠道全部以插件形态提供，社区可替换 |
| L2 | 自我修改与自举：`self_read/write/diff/apply/snapshot/rollback/doctor`、`self_scaffold_plugin`、`self_create_skill` | `plugins/hoshino-self`、`plugins/hoshino-m2` | 无 | 让 Agent 为某个 App 自动写知识包/技能并走审批安装 |
| L3 | 源码自构建与签名校验更新（GitHub 分支→构建→产物→SHA256/包名/证书校验→安装，保留 3 个回滚包） | `M2Runtime.kt`(`github`/`install`/`finishInstall`) | 只打开浏览器下载 | 增量更新、更新前自动备份 |
| L4 | 声明式 UI DSL（令牌、slot、侧栏、胶囊、命令覆盖，Schema 校验、局部降级） | `plugins/hoshino-self/overrides.ts`、`core/UiCustomization.kt` | 写死 | 胶囊/卡片外观可被 DSL 覆盖（CU1） |
| L5 | 多工作区、会话分叉、归档、全文历史搜索（sqlite） | `DshHarnessClient`(`forkSession`/`searchSessions`)、dsh `session-query-sqlite` | 只有改名/置顶/删除 | 从任一步分叉（OB2） |
| L6 | 文件改动 diff 审阅（接受/拒绝、版本守卫） | `plugins/android-policy/index.ts`(`resolveDiff`) | 无 | 手机/网页动作也给「前后对比」 |
| L7 | 四档审批 + 规则 + 作用域 + 审批模型 + 通知审批 | `plugins/hoshino-m2/approval.ts` | 一个开关 | 语义风险维度（AP1） |
| L8 | 9 种模式（聊天/标准/计划/问答/研究/评审/目标/自动/编排）：工具白名单、只读、温度、步数、输出预算、指定模型 | `plugins/hoshino-m2/config.ts` | 无 | 意图判断自动建议模式（RT10） |
| L9 | 模型备用路由链（失败自动换服务商重试同一轮） | `plugins/hoshino-m2/index.ts`(`agent/request-error`) | 无 | 额度用尽/限流自动切 |
| L10 | dsh Goal 目标驱动、todo、子代理 spawn/fork 深度 3 + 控制 | `node-host/src/main.ts` | 子任务只读 15 步 | 子代理可写档 + 树状 UI（RT8） |
| L11 | 真 PTY 终端界面、x86_64 支持、Node 24、按需工具安装 | `rootfs/*`、`ui/tools/ToolScreens.kt`(`TerminalScreen`)、`ensure_tools` | 无终端界面、仅 arm64 | 默认断网 + 审批放行（SB3） |
| L12 | Shizuku 系统 shell（adb/root 身份） | `plugins/android-shizuku` | 内置助手 10 s 命令 | 三路择优（PH2） |
| L13 | 免 Key 多源联网搜索 | `plugins/hoshino-m2/web.ts` | 无搜索工具 | 进证据账本（OB1） |
| L14 | 更多文件预览（音视频、ZIP、DOCX）、大附件 1 GiB 流式 | `ui/chat/DeliveredFiles.kt`、`AppMedia.kt` | 网页/MD/PDF/图片/文本，200 MB | — |
| L15 | 语音输入、快捷磁贴、桌面小组件、动态快捷方式 | `M2Intents.kt`、`M2TileService`、`M2Widget`、`shortcut.add` | 无 | 胶囊语音插话（CU1） |
| L16 | 系统分享进 App（`SEND */*`） | `AndroidManifest.xml` | 无分享入口 | 分享进来直接开任务 |
| L17 | 加密备份/恢复、配置快照与回滚 | `M2Runtime.restoreArchive`、`self_snapshot` | 记忆不导出（自认） | 记忆/网站登录可选带走（MM、WB4） |
| L18 | 连续崩溃安全模式 | `M2SafeMode` | 自认没有 | 附属服务单独熔断（RT7） |
| L19 | 分层提示词（global/AGENTS.md/HOSHINO.md/mode）+ 变量 + 来源预览 | `plugins/hoshino-m2/index.ts`(`promptLayers`) | 固定提示词 | 金样测试（EV3） |
| L20 | Android 10+（minSdk 29） | `app/build.gradle.kts` | 安装 11+、任务 13+ | 10–12 降级手机模式（PH1） |

## 5. 统一度量口径（所有「验收」里的数字按这里测）

| 指标 | 定义 | 测法 |
|---|---|---|
| 首个反馈时间 TTFF | 从用户点发送到界面第一次出现**这次运行的实际进展**（第一段流式文字、第一个步骤、或回放的第一步完成）。口径与 Mika `RunMetrics` 的「第一次反馈」一致，另记「首个可见 token」 | session log 时间戳 + Compose 首帧回调；benchmark-driver 驱动 5 次取中位 |
| 完成率 | EV1 题集上「办成」占比（办成 = 判定 Done，且核对项全过）；分 tune/holdout 报 | EV2 评测器；双跑模式同机对比 Mika |
| 模型往返 | 主循环模型请求次数（含被改派的轻量请求），旁路调用另计 | session log + 计量事件 |
| Token | 输入（含缓存命中）/输出/缓存命中，以服务商返回 usage 为准，没报的只计次数不估算 | OB4 账本 |
| 缓存命中率 | cached_input / input | 同上 |
| 读屏延迟 | 从发起读屏到拿到合并后的元素+文字 | 执行入口计时 |
| 动作→效果延迟 | 从动作开始到 `ActionEffect` 判定完成（不含节奏停留） | 执行入口计时，停留另记 `waitMs` |
| OCR 延迟/准确率 | 单屏/裁剪块耗时；字符准确率按人工金样 | 固定截图集离线跑 |
| 掉帧率 | 场景内 janky frames / total frames | `adb shell dumpsys gfxinfo dev.hoshino.app framestats`，场景：流式长回答、100 步过程、胶囊常驻时前台 App 滚动 |
| 内存 | App + node + proot 的 PSS 之和（空闲 / 流式 / 手机任务中 / 4 标签浏览） | benchmark-driver 现有 meminfo 采集 |
| 后台存活 | 30 分钟/24 小时后台任务不被杀的比例 | 真机 + 模拟器，记录 OEM 机型 |
| 网络零泄露 | 除用户配置的模型服务商、用户让访问的网站、用户启用的更新/市场源外，无任何出站连接 | 抓包（mitm/PCAPdroid）+ 依赖许可证门（PL1） |

参考设备：CI 用 AOSP x86_64 API 36 模拟器；真机以一台 arm64、Android 15 国产 ROM 主力机为主，另需 Android 13/14 各一台验证焦点与权限差异。真机数据只记真实观测，没测的写「未测」，不填估计值（沿用 `m2-metrics.json` 的规矩）。

## 6. 待决事项（需主代理/用户拍板）

1. **PL4 拟人化规避**：本清单默认不移植「假装阅读的空闲滚动」「为骗过检测的轨迹/落点随机化」，只做守规矩的节奏与上限。若要做，需单独评估合规风险。
2. **PL3 自动打开无线调试**：本清单默认改为首次显式勾选，且「自动打开无线调试」默认不勾。
3. **PL5 上游更新检查**：默认手动。
4. **SB3 默认断网**会改变现有用户习惯（现在脚本默认可联网），建议在 m3.1 发版说明里写明并提供一键恢复。
5. **RT5 续跑策略**：保留「无对外动作才自动续跑」，比 Mika 激进、比现状保守，需确认。

## 7. 更新记录

> 每日监测 Mika（`/workspace/work/hha-team/mika-watch.txt` 记录最新检查的提交与 tag）。发现新提交时在下面追加一条：日期、Mika 提交/版本、变化摘要、影响的条目 ID（新增条目编号接着各领域往下排）、是否需要改版本计划。不要改写旧记录。

| 日期 | Mika 提交 / 版本 | 变化 | 影响条目 | 处理 |
|---|---|---|---|---|
| 2026-10-31 | `688f59d` / 0.1.13 | 首版清单：逐项对比 159 条 Mika 能力 + 20 条我方领先项 | 全部 | 建立基线；路线见 `roadmap-surpass-mika.md` |
