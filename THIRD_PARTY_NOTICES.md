# Third-party notices

Hoshino Harness 主项目是 MIT。下面每一段由引入对应代码的分支各自追加；请只追加自己的段落，便于合并。

## Mika（评测与度量，分支 feat/m2.9-eval）

- 项目：https://github.com/wangzan101/mika （移植基线 0.1.13 / `688f59d5a18d99d0231504598da4059f3feb226e`）
- 许可：Apache License 2.0，全文见 `licenses/mika-LICENSE`
- NOTICE：

```
Mika
Copyright 2026 wangzan101
```

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
