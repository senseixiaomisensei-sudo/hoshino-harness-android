# Third-party notices

Hoshino Harness 主项目是 MIT。下面每一段由引入对应代码的分支各自追加；请只追加自己的段落，便于合并。

## Mika

- 项目：https://github.com/wangzan101/mika （参照基线 0.1.13 / 688f59d）
- 许可：Apache License 2.0，全文见 `licenses/mika-LICENSE`
- NOTICE：

```
Mika
Copyright 2026 wangzan101
```

- 被移植或参照改写的文件头部保留：
  `// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.`

### 审批档与会话管理（分支 feat/m2.7-approval-sessions）

- 使用方式：参照 Mika 的结构与界面文案在 dsh 事件之上重新实现（不是逐行移植；Mika 是自带 agent loop，本项目的 loop 是 dsh）。
- 文件清单（Mika 源文件 → 本仓库文件）：
  - `core/src/main/kotlin/com/wangzan101/mika/core/agent/StopSummary.kt`（停止后交代：按运行记录整理事实 → 有时限地请一次模型、无工具 → 失败/超时按记录拼一段的三段式结构与提示要点）→ `plugins/hoshino-m2/sessions.ts`（改为读 dsh 会话事件，结果作为不唤醒的 next-step inbox 消息注入 dsh）
  - `app/src/main/java/com/wangzan101/mika/task/TaskCenterState.kt`（`SteerState` 插话去向状态）、`app/src/main/java/com/wangzan101/mika/ui/ChatViewModel.kt`（`steerLabel` 文案）→ `app/src/main/java/app/hoshino/harness/core/Steering.kt`（状态改由 dsh 持久化的 `agent/inbox/spliced` / `user/message` 事件推出）

### ChatGPT 登录与模型目录（分支 feat/m2.7-chatgpt-search）

- 移植/改写的文件（文件头保留出处说明）：
  - `core-bridge/src/main/java/dev/hoshino/bridge/chatgpt/ChatGptOAuth.kt` ← Mika `core/.../chatgpt/ChatGptAuth.kt`（含 `HttpSupport.execute` 的写法）
  - `core-bridge/src/main/java/dev/hoshino/bridge/chatgpt/ChatGptSession.kt` ← Mika `core/.../chatgpt/ChatGptSession.kt`；失败说明文字改写自 Mika `values-zh/strings_models.xml`
  - `core-bridge/src/main/java/dev/hoshino/bridge/chatgpt/LoopbackCallback.kt` ← Mika `core/.../chatgpt/LoopbackCallback.kt`
  - `core-bridge/src/main/java/dev/hoshino/bridge/chatgpt/ChatGptModels.kt` ← Mika `core/.../model/ModelCatalog.kt`（OpenAI 兼容/ChatGPT 模型列表解析部分）
  - `core-bridge/src/test/java/dev/hoshino/bridge/chatgpt/ChatGptAuthTest.kt` ← Mika `core/src/test/.../chatgpt/ChatGptAuthTest.kt`
  - 参考而未逐行移植：Mika `ResponsesModel.kt`（`store:false`、include 重试、套餐路由不发可选参数、错误码含义）的规则在 `plugins/android-api/hosted-search.ts` 中以 TypeScript 重新实现。

### 手机操作能力（分支 feat/m2.8-phone）

- 使用方式：直接移植并改写（包名改为 `dev.hoshino.bridge.phone.*` / `app.hoshino.harness.phone.*`），文件头部保留上面的出处说明。
- 移植文件清单（Mika 源文件 → 本仓库文件）：
  - `core/adb/{AdbConnectPort,AdbConnection,AdbIdentity,AdbMessage,AdbPairing,AdbTls,Ed25519,LocalPortScan,PairingAuth,Spake2}.kt` → `core-bridge/src/main/java/dev/hoshino/bridge/phone/adb/*.kt`（无线调试配对 SPAKE2、TLS、ADB 连接协议）
  - `core/src/test/.../adb/{CryptoTest,AdbConnectPortTest,LocalPortScanTest}.kt` → `core-bridge/src/test/java/dev/hoshino/bridge/phone/adb/*.kt`
  - `app/cap/phone/shell/adb/{AndroidAdb,NsdAdbServiceFinder}.kt` → `core-bridge/.../phone/shell/{AndroidAdb,NsdAdbServiceFinder}.kt`
  - `core/shell/{HelperHandoff,BuiltinShell,BuiltinShellEngine,PairingScreen,RecoveryPermissions}.kt` → `core-bridge/.../phone/shell/{HelperHandoff,BuiltinShell,BuiltinShellEngine,PermissionCommands}.kt`（精简：去掉 USB 启动脚本，自动打开无线调试合并进引擎）
  - `app/cap/phone/shell/{HelperProcess,HelperHub,ShellService}.kt`、`app/cap/phone/setup/BuiltinShellManager.kt` → `core-bridge/.../phone/shell/{HelperMain,HelperHub,PhoneShellService,BuiltinShellManager}.kt`（ShellService 精简为本项目需要的接口，并可同时由 Shizuku UserService 承载）
  - `app/cap/phone/shell/ShellContext.kt` → `core-bridge/.../phone/shell/ShellContext.kt`（该文件同时保留 scrcpy 出处，见下）
  - `core/uistate/*`（ScreenSnapshot/UiElement 模型）、`core/action/IrreversibleDetector.kt`、`core/uistate/LaunchChooser.kt` 与 `MainScreenSpill.needsUser` 的判断 → `app/src/main/java/app/hoshino/harness/phone/{ScreenModel,IrreversibleDetector,SystemScreens}.kt`（及 `app/src/test/.../IrreversibleDetectorTest.kt`）
  - `app/cap/phone/a11y/ScreenAccessibility.kt` → `app/.../phone/PhoneAccessibilityService.kt`
  - `app/cap/phone/a11y/ScreenReader.kt` → `app/.../phone/AccessibilityReader.kt`
  - `app/cap/phone/a11y/AndroidScreen.kt`（点按/输入/滚动/开 App 的底层操作；去掉所有拟人节奏与抖动）→ `app/.../phone/AndroidPhoneScreen.kt`
  - `app/cap/phone/display/{AgentDisplay,FrameMath}.kt` → `app/.../phone/{AgentDisplay,FrameMath}.kt`
  - `core/uistate/LimitNotice.kt`、动作效果判定 → `app/.../phone/ActionEffect.kt`
  - `app/cap/phone/shell/AudioMuteLedger.kt`、`app/task/TaskRecovery.kt`（思路）→ `app/.../phone/PhoneLedger.kt`
  - `app/takeover/Takeover.kt`、`TakeoverQueue` → `app/.../phone/PhoneTakeover.kt`
  - `app/capsule/*`（CapsuleController、CapsuleDock、DoubleTap、CapsuleFade 的行为）、`app/cap/phone/a11y/OverlayHost.kt` → `app/.../phone/PhoneCapsule.kt`（改用普通 View 绘制）
  - `app/cap/phone/setup/{PairingCoordinator,PairingCodeReader,PairingNotifier}.kt` → `app/.../phone/setup/Pairing.kt`
  - `core/shell/PhoneSetupFlow.kt`、`app/cap/phone/setup/DeveloperSettings.kt` → `app/.../phone/setup/PhoneSetupFlow.kt`
  - `app/ui/AgentPip.kt`、`PipPhysics.kt` → `app/src/main/java/app/hoshino/harness/ui/chat/PhonePip.kt`
  - 没有移植：`core/cap/phone/{GesturePacing,TypingPacing,AppPacing}.kt`、`core/uistate/IdleScroll.kt`（规避式拟人，mika-gap §2 PL4）；`core/shell/WirelessDebuggingAutoOn.kt` 的静默开启与 `RecoveryPermissions` 的静默授权改为向导里逐项同意（PL3）；ML Kit 的 `PhoneOcr` 换成下面的开源 OCR（PL1）。

### WP9-B 上下文与循环（`feat/m2.9-context-loop`）移植/改写的文件

被移植或改写的文件头保留 `Portions derived from Mika … Apache-2.0. Modified by Hoshino Harness contributors.`。

| 本仓库文件 | 对应 Mika 源文件 | 说明 |
|---|---|---|
| `plugins/hoshino-context/core.ts` | `core/agent/ContextCompressor.kt`、`core/context/StepSummary.kt`、`core/observation/Untrusted.kt` | 无模型检查点格式（目标/已确认事实/进展/下一步）、不可信内容包裹，改写为 TypeScript 并接入 dsh 压缩钩子 |
| `plugins/hoshino-turn-policy/language.ts` | `core/agent/ReplyLanguage.kt` | 回答语言判定，逐条移植 |
| `plugins/hoshino-turn-policy/rules.ts` | `core/agent/IntentGate.kt`、`core/agent/TurnPolicy.kt`、`core/agent/StopRules.kt`、`core/agent/StuckAdvice.kt`、`core/guard/GuardRules.kt`、`core/cap/sandbox/SandboxGuard.kt`、`core/model/ModelAccessErrors.kt`、`resources/prompts/roles/intent_gate.md`、`resources/prompts/feedback/loop.md` | 意图提示词、交答前提醒措辞、守卫阶梯、沙箱守卫、模型访问错误分类与中文原因 |
| `plugins/hoshino-turn-policy/index.ts` | 同上 | 接入 dsh `tools/pre-execute`、`tools/post-execute`、`agent/turn-stopping`、`agent/request-error` |
| `plugins/hoshino-outcome/index.ts` | `core/general/OutcomeTools.kt`、`core/agent/OutcomeJudge.kt`、`resources/prompts/roles/outcome_judge.md`、`resources/prompts/tools/end_not_done.md` | `end_not_done` 工具与结局判定 |
| `plugins/hoshino-outcome/grounding.ts` | `core/verify/Grounding.kt` | 规则核对的轻量子集（引文、带单位/小数/三位以上的数、日期时刻、带数字的编号） |

### 评测与度量（分支 feat/m2.9-eval，移植基线 0.1.13 / `688f59d5a18d99d0231504598da4059f3feb226e`）

- 使用方式：
  - 评测题集直接复制、不改一字：Mika `eval/tasks/**`（128 题）→ `eval/tasks/**`。JSON 不能写注释，出处与每个文件的 SHA-256 记在 `eval/provenance.json`；`EvalProvenanceTest` 守着「没登记改动的文件与 Mika 原文逐字节一致」。
  - Kotlin 代码移植并改写（包名改为 `dev.hoshino.bridge.eval` / `dev.hoshino.bridge.metrics`，kotlinx.serialization 换成 org.json，运行记录从 Mika 的 `RunEvent` 换成 dsh 原版会话日志事件）。被移植或改写的文件头部保留：
    `// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.`
- 移植文件清单（Mika 源文件 → 本仓库文件；Mika 路径相对其仓库根，`…/core/` 指 `core/src/main/kotlin/com/wangzan101/mika/core/`，`…/core-test/` 指 `core/src/test/kotlin/com/wangzan101/mika/core/`）：
  - `eval/tasks/**/*.json` → `eval/tasks/**/*.json`（原样，128 题，SHA-256 见 `eval/provenance.json`）
  - `eval/README.md` → `eval/README.md`（出题规矩、调优池/保留池、人工改判规则照抄；跑法改写为 Hoshino 与双跑）
  - `…/core/eval/EvalTask.kt` → `core-bridge/src/main/java/dev/hoshino/bridge/eval/EvalTask.kt`
  - `…/core/eval/Checks.kt`、`…/core/eval/BarrierSignals.kt` → `core-bridge/src/main/java/dev/hoshino/bridge/eval/Checks.kt`
  - `…/core/eval/Scoring.kt` → `core-bridge/src/main/java/dev/hoshino/bridge/eval/Scoring.kt`
  - `…/core/eval/EvalReport.kt` → `core-bridge/src/main/java/dev/hoshino/bridge/eval/EvalReport.kt`
  - `…/core/eval/RunFacts.kt`、`…/core/eval/RecordedEvidence.kt` → `core-bridge/src/main/java/dev/hoshino/bridge/eval/DshRunFacts.kt`（改为从 dsh 会话日志取事实）
  - `…/core/verify/Grounding.kt` → `core-bridge/src/main/java/dev/hoshino/bridge/eval/Grounding.kt`
  - `…/core/record/RunMetrics.kt`（含 `RunStats`）→ `core-bridge/src/main/java/dev/hoshino/bridge/metrics/RunMetrics.kt`、`RunStats.kt`（口径照抄；输入换成 dsh 事件）
  - `…/core/record/RunMetricsStore.kt` → `core-bridge/src/main/java/dev/hoshino/bridge/metrics/RunMetricsStore.kt`
  - `app/src/main/java/com/wangzan101/mika/ui/RunMetricsUi.kt`（小结一行、展开、运行统计的窗口/复制/分享与文字口径）→ `core-bridge/src/main/java/dev/hoshino/bridge/metrics/RunSummaryFormat.kt`（文字）、`app/src/main/java/app/hoshino/harness/ui/chat/RunSummary.kt`（OB3 小结）、`app/src/main/java/app/hoshino/harness/ui/settings/RunStatsScreen.kt`（OB5 运行统计），均为 Compose 重写
  - `…/core-test/eval/{TaskSetTest,EvalTaskTest,ChecksTest,ScoringTest,EvalReportTest,EvalReportMetricsTest,EvalReportTimingTest}.kt` → `core-bridge/src/test/java/dev/hoshino/bridge/eval/` 同名文件（按移植后的接口改写，断言保留）
  - `…/core-test/verify/{GroundingClaimsTest,GroundingFormatsTest}.kt` → `core-bridge/src/test/java/dev/hoshino/bridge/eval/GroundingTest.kt`；`…/core-test/verify/GroundingNumbersTest.kt` → `GroundingNumbersTest.kt`
  - `…/core-test/record/{RunMetricsTest,RunStatsTest,RunMetricsStoreTest}.kt` → `core-bridge/src/test/java/dev/hoshino/bridge/metrics/` 同名文件（RunMetricsTest 按 dsh 事件重写场景）
  - `…/core-test/capability/PackageDependencyTest.kt` → `core-bridge/src/test/java/dev/hoshino/bridge/arch/ArchitectureGuardTest.kt`（规则换成本仓库的分层）
  - `app/src/androidTest/java/com/wangzan101/mika/eval/EvalRunTest.kt`、`EvalProbes.kt` → `app/src/androidTest/java/app/hoshino/harness/eval/EvalRunTest.kt`、`EvalProbes.kt`（驱动换成 dsh 会话：`HarnessClient.send` + 事件流 + 会话日志；探针改用 UiAutomation/UiAutomator）
- 未移植、本仓库新写（不带移植文件头）：`HoshinoCapabilities.kt`、`DshSessionLog.kt`、`RunTimingTracker.kt`、`DshRunFactsTest.kt`、`EvalProvenanceTest.kt`、`HoshinoCapabilitiesTest.kt`、`app/.../core/RunMetricsHub.kt`、`node-host/test/prompt-surface.test.mjs`（EV3）、`node-host/test/architecture.test.mjs`、`tools/eval/dual-run.py`、`tools/eval/compare.py`、`tools/license-gate.py`。

## scrcpy（经由 Mika 的 ShellContext）

- 项目：https://github.com/Genymobile/scrcpy
- 许可：Apache License 2.0（Copyright (C) 2018 Genymobile, Copyright (C) 2018-2024 Romain Vimont）
- 使用方式：以 shell 身份取得系统 Context（ActivityThread、伪装 com.android.shell 包名）的做法仿照 scrcpy。源文件头部保留 scrcpy 的版权与许可声明：`core-bridge/src/main/java/dev/hoshino/bridge/phone/shell/ShellContext.kt`。

## ONNX Runtime（手机文字识别推理，分支 feat/m2.8-phone）

- 项目：https://github.com/microsoft/onnxruntime ，Maven `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`
- 许可：MIT（Copyright (c) Microsoft Corporation）
- 使用方式：作为依赖库链接（`app/build.gradle.kts`），只在本机推理，不联网、无遥测。APK 体积代价见 `docs/m2.8-phone-acceptance.md`。

## PaddleOCR PP-OCRv4 模型（经 RapidOCR 导出的 ONNX，分支 feat/m2.8-phone）

- 模型：PaddleOCR PP-OCRv4 mobile 检测 `ch_PP-OCRv4_det_infer.onnx`、识别 `ch_PP-OCRv4_rec_infer.onnx`（https://github.com/PaddlePaddle/PaddleOCR ，Apache License 2.0）
- ONNX 导出与托管：RapidOCR（https://github.com/RapidAI/RapidOCR ，Apache License 2.0），固定到 https://huggingface.co/SWHL/RapidOCR 的提交 `1cfba2e90fc938db55889873735088de210cc173`
- 使用方式：**不随 APK 分发**；用户在「设置手机操作」里点下载时才获取，按 SHA-256 校验（det `d2a7720d…f49da9`，rec `48fc40f2…83683b`，完整值见 `app/src/main/java/app/hoshino/harness/phone/ocr/OcrModels.kt`）。字符表取自识别模型自带的 `character` 元数据。
- 本仓库实现的前后处理（DB 后处理、CTC 解码、缩放归一化）按 PaddleOCR/RapidOCR 公开的参数独立编写：`app/src/main/java/app/hoshino/harness/phone/ocr/OcrPipeline.kt`。
