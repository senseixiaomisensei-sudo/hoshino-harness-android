# 超越 Mika 的执行路线

> 条目编号与细节见 [`mika-gap.md`](mika-gap.md)。本文件只排先后、分工作包、写每一版的出门标准。基线：Mika `688f59d`（0.1.13），我方 `codex/m2` `bce6285`（0.2.1-m2.6）。

## 0. 排序原则

1. **先能量，再超越。** 没有评测与度量就无法证明「超越」，所以 m2.9 的第一个工作包是评测底座（EV1/EV2/OB3）。m2.8 一合并就开始双跑 Mika 与 Hoshino。
2. **先用 dsh 已有的，再自己写。** 压缩、重复调用守卫、工具超时、会话统计、MCP 客户端、时间上下文都在 `node-host/package-lock.json` 里已有原版包，只需在 `node-host/src/main.ts` 装载（不 fork、不改版本）。见文末附录。
3. **手机与网页的「动作」先进唯一执行入口，再谈能力。** 所有手机/网页动作经 RT13 的执行入口 + 默认审批 + 风险判定（AP2）。
4. **Mika 的纯逻辑连同单测一起移植。** 例如 `WheelMath`、`MainScreenSpill`、`LaunchChooser`、`PagePacing`、`OutwardPacing`、`IrreversibleDetector`、`ConfirmationGate`、`TriggerRuntime` 的窗口去重、`RunMetrics`，它们与安卓解耦、测试齐全，移植成本低、风险小。
5. **不照搬违规项。** ML Kit 换开源 OCR、默认审批不放松、静默改系统设置改为显式同意、不做规避式拟人（`mika-gap.md` §2）。

依赖关系（→ 表示前者是后者前提）：

```
m2.8 手机核心(PH1-5,8,9,17,RT13) ─→ m2.9 手机复合(PH10-16,19) ─→ m3.0 沙箱调手机(PH23) ─→ m3.1 并行(RT1)
m2.9 评测底座(EV1,EV2,OB3) ─→ 每一版的出门标准
m2.9 结局判定(RT9) + 规则核对(RT17 轻量) ─→ m3.1 学习/回放(KS6-8)
m3.0 浏览器(WB1-4) ─→ m3.0 风控/节奏(WB10-12) ─→ m3.1 条件任务读网页(SC3)
m3.0 记忆(MM) + 知识(KS1-5) ─→ m3.0 开局上下文(CX4)
m2.9 计划页接通(SC1,SC6) ─→ m3.1 条件/可靠调度(SC3-5,7)
```

## 1. m2.7（已派，三个分支）

| 分支 | 覆盖条目 | 本清单给它加的超越门槛 |
|---|---|---|
| `feat/m2.7-perf-files`（性能 + 文件卡归位 + 审批折叠） | UX17、SB6、UX3、AP1（折叠） | 流式长回答掉帧率 ≤ 5%；完成后折叠行预留 OB3 小结位；审批折叠后审批卡仍可一键展开到完整参数 |
| `feat/m2.7-chatgpt-search`（ChatGPT 登录 + 自带搜索 + 中转站探测） | MD3、WB14、MD4（探测部分） | PKCE/nonce/JWKS 验签/刷新互斥对齐 Mika `core/chatgpt/*`；额度用尽自动切备用路由；探测同时报首字延迟 |
| `feat/m2.7-approval-sessions`（无审批 + 删除会话 + 插话 + 停止交代） | AP6、UX1（删除）、SB9、RT2、RT3 | 插话回执 P95 ≤ 300 ms、可撤回；停止→「已停止」≤ 200 ms、交代 ≤ 5 s；删除会话级联删定时与工作区、晚到写入不复活；「无审批」只能用户主动开 |

## 2. m2.8（已派，`feat/m2.8-phone`）

覆盖：PH1、PH2、PH3、PH4（开源 OCR，PL1）、PH5、PH8、PH9、PH17、PH18、PH22、PH24、PH25、PH26、PH27、RT6、RT13、RT14、AP7、CU1、CU2、CU3、CU4、CU7、RT18（胶囊回答）。

本清单给它加的门槛：
- 动作只经执行入口（EV4 架构测试守住），并挂进现有审批接缝；Agent 不能操作 `dev.hoshino.app` 自己。
- OCR：无 `com.google.mlkit`/`gms`/`firebase`/`datatransport` 依赖；整屏 P50 ≤ 350 ms；运行时零非预期出站连接。
- 胶囊：常驻时前台 App 掉帧增量 ≤ 1%，进程内存增量 ≤ 15 MB；卡片里能做完整审批（四档）。
- 移植 Mika 文件按 `RULES.md` 保留文件头，`licenses/mika-LICENSE` 与 `THIRD_PARTY_NOTICES.md` 首次移植时建立。
- **需要 m2.8 确认的边界**：CU5「双击胶囊发此刻屏幕」是否在 m2.8 内；不在则归 m2.9 WP9-D3。

## 3. m2.9 「能量 + 循环质量 + 手机复合能力」（未派）

> 体量最大（63 项），建议按下表 7 个工作包并行派发；WP9-A/B/C/E 不依赖 m2.8，可立即开始；WP9-D1/D2/D3 等 m2.8 合并；若一次发不完，可拆成 m2.9a（A/B/C/E/F）与 m2.9b（D1/D2/D3）。

| 工作包 | 条目 | 主要落点 | 前提 |
|---|---|---|---|
| **WP9-A 评测与度量底座**（最先做） | EV1、EV2、EV3、EV4、EV7、OB3、OB5、PL1 的 CI 许可证门 | `app/src/androidTest/.../eval/`、`eval/tasks/`（移植 Mika 128 题）、`tools/`、`.github/workflows/`、P 小结插件、D `dsh-session-stats`/`dsh-session-turn-outline` | 无（手机题等 m2.8 合并后再跑） |
| **WP9-B 上下文与循环质量** | CX1、CX2、CX5、CX6、RT9、RT10、RT11、RT12、RT16、RT17（规则核对轻量版）、SB4 | D `dsh-compaction`/`-basic`/`-tool-result-pruner`/`-image-offload`、`dsh-repeat-tool-reminder`、`dsh-tool-call-timeout-policy`、`dsh-time-context`、`dsh-command-compact`；P `plugins/hoshino-outcome`、`plugins/hoshino-turn-policy`、`plugins/hoshino-context-pins`；A `DshHarnessClient.compact()` | 无 |
| **WP9-C 来源、用量、画面、状态** | OB1、OB2、OB4、OB6、OB7、CH5、UX6 | P 证据账本服务、计量（`plugins/android-api` 出口）；A 来源页、用量页、三通知频道、设置总状态卡 | OB2/OB7 的画面依赖 m2.8 帧 |
| **WP9-D1 手机系统动作与跳转** | PH12、PH13、PH14、PH15、PH16 | A `app/.../phone/`（移植 `SystemIntents`、`WheelDriver`、`MainScreenWatch`）、core-bridge 纯逻辑（`WheelMath`、`LaunchChooser`、`MainScreenSpill`）；Manifest `SET_ALARM` + `<queries>` | m2.8 合并 |
| **WP9-D2 手机复合工具与看图** | PH6、PH10、PH11、PH19、PH20（steer） | P `plugins/android-phone` 新工具；A `BatchRunner`/`UntilWait` 移植；看图走 `routing.vision` | m2.8 合并 |
| **WP9-D3 风险判定与修复** | AP2、AP3、AP4、AP5、PH21（显式同意）、PH28、CU5、CU6 | core-bridge 移植 `IrreversibleDetector`/`ConfirmationGate`；P `plugins/android-policy` 接入审批；A 恢复与修复页、USB 启动脚本 | m2.8 合并 |
| **WP9-E 模型与服务商** | MD1、MD2、MD4（其余）、MD5、MD6、MD7（轻量用途）、MD8、MD9、MD10 | P `plugins/android-api`；A `ProvidersScreens.kt`、预设 JSON | m2.7 chatgpt-search 合并 |
| **WP9-F 会话、计划页与恢复** | UX1（置顶）、UX2、UX5、UX15、SB2、SB10、SC1、SC2、SC6、RT4、RT5、RT7 | A `Sidebar.kt`、`AutomationScreens.kt`、`DshHarnessClient`（schedule 接通、resumeInterrupted 加闸）、Manifest 相机声明；P provenance | m2.7 approval-sessions 合并（删除/会话相关） |

**m2.9 出门标准**
- EV2 双跑报告自动产出（Hoshino 与 Mika 同机同模型），基线数字写进 `m2-metrics.json` 的后继文件，不再是 null。
- read/system 与 write 类完成率 ≥ Mika − 10 个百分点（手机刚移植，先追平到可比）；系统动作类（闹钟/日程）≥ Mika。
- 200 轮长会话无上下文超长错误；`compact` 可用。
- 每轮回答带结局小标与小结行；来源覆盖率 ≥ 95%（读类题）。
- 依赖许可证门在 CI 生效。

## 4. m3.0 「浏览器 + 记忆 + 知识」（未派）

| 工作包 | 条目 | 主要落点 |
|---|---|---|
| **WP10-A 内置浏览器** | WB1、WB2、WB3、WB4、WB5、WB6、WB7、WB8（含补 Mika 已知 4 个漏洞）、WB9、WB10、WB11、WB12（批量一次审批） | A `app/.../web/`（移植 `WebHost`、`WebTab`、`WebTabPool`、`cdp/*`、`DevtoolsSocket`、`SiteRegistry`、`LoginProbe`）；P `plugins/android-web`（经 dsh `ctx.web` 接缝，先读源码）；A 用户浏览器页与「网站登录」页 |
| **WP10-B 记忆** | MM1–MM6 | P `plugins/hoshino-memory`（`ctx.memory`）；A Keystore 加密存储（移植 `EncryptedMemoryFile`）、记忆页 |
| **WP10-C 知识与技能** | KS1、KS2、KS3、KS4、KS5、KS9（知识包）、SB8、CX3、CX4 | 移植 41 份知识与 4 份技能（保留出处）到仓库 `knowledge/`；P `plugins/hoshino-knowledge`；构建期校验脚本；`run_bundled_script` |
| **WP10-D 子代理增强与沙箱调手机** | RT8、PH23 | P 子代理可写档与扇出；R `hh phone` CLI + 一次性令牌 |

**m3.0 出门标准**
- web 类、read/content、read/shopping、memory 类完成率 ≥ Mika（同机同模型）。
- 带知识 vs 不带知识：读类题模型往返下降 ≥ 20%。
- 风控页识别率 ≥ 95%；careful 站点触发风控次数 ≤ Mika。
- 记忆磁盘密文扫描通过；敏感信息拦截 100%。

## 5. m3.1 「无人值守 + 多渠道 + 学习」（未派）

| 工作包 | 条目 | 主要落点 |
|---|---|---|
| **WP11-A 定时与条件** | SC3、SC4、SC5、SC7 | A `M2Scheduler` 移植 `TriggerRuntime` 窗口去重与重布、`SCHEDULE_EXACT_ALARM` 引导；P 条件检查（只读网页/沙箱/手机）、阈值/变化/冷却 |
| **WP11-B 渠道、送达、MCP** | CH1、CH2、CH3、CH4 | P Outbox 状态机、渠道插件（Telegram/邮件/Matrix，用户自带凭据）；D `dsh-mcp-client`、`dsh-mcp-resources`、评估 `dsh-acp`；A 外部发起 Intent（白名单包） |
| **WP11-C 学习、回放、核查** | RT17（完整）、KS6、KS7、KS8 | P 草稿起草/试用/转正/停用；回放引擎复用 RT13 `ActionEffect` |
| **WP11-D 并行与资源** | RT1 | A `ResourceScheduler` 移植 + 内存预算；P `ctx.phoneResources` |
| **WP11-E 沙箱默认断网** | SB3 | R 移植 Mika `mikarun`（seccomp 拒 AF_INET）；P 会话级联网审批 |

**m3.1 出门标准**
- 全集（tune + holdout）完成率 ≥ Mika + 5 个百分点；首个反馈时间中位数 ≤ Mika；模型往返中位数 ≤ Mika。
- 同一手机任务第二次执行：往返下降 ≥ 50%、用时下降 ≥ 40%（回放/技能生效）。
- 定时任务 24 小时真机测试：同窗口重复执行 0 次，准点误差如实显示。
- MCP 可用（Mika 至今只有骨架）；至少一个外部渠道端到端可用。

## 6. m3.2 「打磨与上架准备」（未派，对应 AGENTS.md M4）

| 工作包 | 条目 |
|---|---|
| **WP12-A 多语言与无障碍** | UX8、EV6、UX13 |
| **WP12-B 上手、关于、许可证、诊断** | UX7、UX9、UX10、UX14（默认手动，PL5）、EV5 |
| **WP12-C F-Droid 准备** | 可复现构建核对、`fastlane/metadata`、依赖全开源复核（PL1 门已在） |

**m3.2 出门标准**：所有评测类别完成率 ≥ Mika；英文系统无中文残留；TalkBack 走查 30 项通过；新机从安装到第一条回答中位 ≤ 3 分钟。

## 7. 还没有人在做的功能（按版本，供派子代理）

> m2.7、m2.8 已派分支覆盖的条目不列。条目后括号是建议的工作包。

**m2.9（63 项，另含 RT17 规则核对轻量版与 PL1 依赖门禁）**
- 评测与度量：EV1 题集移植、EV2 评测器与双跑报告、EV3 提示词金样、EV4 架构守卫、EV7 基准并入、OB3 每任务小结、OB5 运行统计、PL1 依赖许可证门（WP9-A）
- 上下文与循环：CX1 缓存命中度量、CX2 自动压缩、CX5 不可信包裹、CX6 运行时状态、RT9 结局判定、RT10 意图判断、RT11 交答前提醒、RT12 卡住守卫、RT16 可读失败原因、RT17 规则核对（轻量）、SB4 运行预算（WP9-B）
- 可观测：OB1 来源、OB2 逐步画面、OB4 用量页、OB6 画面脱敏、OB7 回看画面、CH5 三通知频道、UX6 总状态卡（WP9-C）
- 手机复合：PH12 系统动作、PH13 滚轮、PH14 定向分享、PH15 选择器、PH16 主屏拉回（WP9-D1）；PH6 守规矩节奏、PH10 复合工具、PH11 do_steps、PH19 看图、PH20 命令转向（WP9-D2）；AP2 风险识别、AP3 同意绑定、AP4 付款交用户、AP5 确认卡、PH21 恢复与修复、PH28 USB 启动、CU5 双击发屏（若 m2.8 未含）、CU6 屏幕规矩（WP9-D3）
- 模型：MD1 协议、MD2 预设、MD4 探测其余、MD5 多协议、MD6 思考回传核实、MD7 轻量用途、MD8 请求头、MD9 明文提醒、MD10 未确认标签（WP9-E）
- 会话与计划：UX1 置顶、UX2 侧栏分段、UX5 附件面板、UX15 相机声明、SB2 每会话工作区、SB10 文件闸门、SC1/SC2/SC6 计划页接通 dsh-schedule、RT4 运行来源、RT5 中断续跑加闸、RT7 安全模式加强（WP9-F）

**m3.0（29 项）**
- 浏览器：WB1–WB12（WP10-A）
- 记忆：MM1–MM6（WP10-B）
- 知识与技能：KS1–KS5、KS9 知识包、SB8 自带脚本、CX3 工具按需展开、CX4 开局上下文（WP10-C）
- RT8 子代理增强、PH23 沙箱调手机（WP10-D）

**m3.1（14 项）**
- SC3、SC4、SC5、SC7（WP11-A）；CH1–CH4（WP11-B）；RT17 完整、KS6、KS7、KS8（WP11-C）；RT1（WP11-D）；SB3（WP11-E）

**m3.2（8 项 + F-Droid 准备）**
- UX8、EV6、UX13（WP12-A）；UX7、UX9、UX10、UX14、EV5（WP12-B）；F-Droid 准备（WP12-C）

## 8. 并行开发的冲突热点

多个工作包会同时改下面这些文件。约定：新增能力尽量放**新插件目录**和**新 Kotlin 文件**；对共享文件只做追加式小改动。

| 文件 | 谁会改 | 约定 |
|---|---|---|
| `node-host/src/main.ts` | WP9-A/B/E、WP10-*、WP11-B/E | 每个 `row(...)` 单独一行追加在已有行后；不重排已有行 |
| `plugins/hoshino-m2/index.ts` | m2.7 三个分支、WP9-B/C | 新逻辑放新插件；这里只改审批/路由接缝 |
| `app/src/main/java/app/hoshino/harness/core/DshHarnessClient.kt` | m2.7、WP9-B（compact）、WP9-F（schedule/resume） | 新方法放新文件的扩展函数或委托类 |
| `app/src/main/AndroidManifest.xml` | m2.8、WP9-D1（SET_ALARM/queries）、WP9-F（相机声明）、WP11-A（精确闹钟）、WP11-B（外部 Intent） | 每个分支只加自己的元素 |
| `THIRD_PARTY_NOTICES.md` | 所有移植 Mika 的分支 | 只追加自己的段落（`RULES.md`） |
| `app/src/main/java/app/hoshino/harness/ui/HoshinoApp.kt`、`ui/nav/Routes.kt` | 新页面（来源、用量、浏览器、记忆、网站登录、恢复与修复） | 每个页面一个路由常量一行 |
| `ui/settings/SettingsScreens.kt` | WP9-C（总状态卡）、WP9-D3、WP10-A/B、WP12-B | 新设置页放新文件，这里只加入口行 |

## 9. 每日监测流程

1. 拉取 Mika 最新提交（只读副本 `/workspace/work/mika` 不修改，另在临时目录 fetch 比较），与 `/workspace/work/hha-team/mika-watch.txt` 里的 sha 对比。
2. 有变化时读提交与 `docs/architecture.md`、`docs/roadmap.md` 的 diff，判断：新功能（在对应领域末尾追加新 ID）、已有功能增强（更新该条 Mika 列与验收门槛）、修了它的已知问题（更新「Mika 自认缺口」相关的超越点）。
3. 在 `mika-gap.md` §7「更新记录」追加一行，必要时调整本文件的版本归属，更新 `mika-watch.txt`。

## 附录：可直接装载、尚未装载的 dsh 原版包（描述取自 npm registry，版本均为锁定的 0.2.2-alpha.2）

| 包 | 用途（官方描述） | 对应条目 |
|---|---|---|
| `dsh-compaction` / `dsh-compaction-basic` / `dsh-compaction-tool-result-pruner` / `dsh-compaction-image-offload` | 压缩服务接缝；按 token-meter 触发的压缩与 LLM 摘要；无模型、可重放的首中尾裁剪；超预算图片换占位后重试 | CX2 |
| `dsh-command-compact` | 手动压缩命令 | CX2 |
| `dsh-repeat-tool-reminder` | 重复相同工具调用时给提醒 | RT12 |
| `dsh-tool-call-timeout-policy` | 每个工具调用的截止时间，超时返回 TOOL_TIMEOUT | RT12、SB4 |
| `dsh-time-context` | 每步附当前时间与已用时间 | CX6 |
| `dsh-session-stats` / `dsh-session-turn-outline` | 整段会话的计数与墙钟时间；每轮提纲 | OB3、OB5 |
| `dsh-mcp-client` / `dsh-mcp-resources` | 连接 MCP 服务器并把工具注册进 `ctx.tools`；按作用域发现与读取 MCP 资源 | CH3 |
| `dsh-acp` | 经 JSON-RPC stdio 驱动 Agent 的自动化协议服务端 | CH4（评估） |
| `dsh-cua` / `dsh-computer-use` | 共享的 Computer Use 应用对象、授权与带日志的工具执行；独占的命名 computer-use 提供者注册 | 评估作为 PH 系列工具的上层接缝（先读源码，不编造接口） |
| `dsh-experimental-session-search` | 让 Agent 搜索并读取当前工作区的早先会话 | MM（补充） |
| `dsh-workspace-changes` | 每轮工作区文件变化记录与对比 | RT3（撤销本轮） |
| `dsh-experimental-speech-to-text-sensevoice` | 本地 SenseVoice ONNX 转写（sherpa-onnx） | CU1 语音插话的离线方案 |

**不得装载**（遥测/匿名标识类）：`dsh-host-product-telemetry-otel`、`dsh-client-product-analytics`、`dsh-session-telemetry-otel`、`dsh-otel`、`dsh-anonymous-user-id`、`dsh-personalized-recommendations`。
