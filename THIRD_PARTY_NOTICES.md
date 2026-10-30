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
- 移植文件清单（Mika 源文件 → 本仓库文件）：
  - `eval/tasks/**/*.json` → `eval/tasks/**/*.json`（原样）
  - `eval/README.md` → `eval/README.md`（改写为 Hoshino 的跑法，保留出题与人工改判规则）
  - `core/eval/EvalTask.kt` → `core-bridge/src/main/java/dev/hoshino/bridge/eval/EvalTask.kt`
  - `core/eval/Checks.kt`、`core/eval/BarrierSignals.kt` → `core-bridge/.../eval/Checks.kt`
  - `core/eval/Scoring.kt` → `core-bridge/.../eval/Scoring.kt`
  - `core/eval/EvalReport.kt` → `core-bridge/.../eval/EvalReport.kt`
  - `core/eval/RunFacts.kt` → `core-bridge/.../eval/DshRunFacts.kt`（改为从 dsh 会话日志取事实）
  - `core/verify/Grounding.kt` → `core-bridge/.../eval/Grounding.kt`
  - `core/record/RunMetrics.kt`（含 `RunStats`）→ `core-bridge/.../metrics/RunMetrics.kt`、`core-bridge/.../metrics/RunStats.kt`（口径照抄；输入换成 dsh 事件）
  - `core/record/RunMetricsStore.kt` → `core-bridge/.../metrics/RunMetricsStore.kt`
  - `core/test/.../eval/{TaskSetTest,EvalTaskTest,ChecksTest,ScoringTest,EvalReportTest}.kt`、`core/test/.../verify/Grounding*Test.kt`、`core/test/.../record/{RunMetricsTest,RunStatsTest,RunMetricsStoreTest}.kt` → `core-bridge/src/test/java/dev/hoshino/bridge/{eval,metrics}/*.kt`（按移植后的接口改写，断言保留）
  - `core/test/.../capability/PackageDependencyTest.kt` → `core-bridge/src/test/java/dev/hoshino/bridge/arch/ArchitectureGuardTest.kt`（规则换成本仓库的分层）
  - `app/androidTest/.../eval/EvalRunTest.kt`、`EvalProbes.kt` → `app/src/androidTest/java/app/hoshino/harness/eval/EvalRunTest.kt`、`EvalProbes.kt`（驱动换成 dsh 会话：`HarnessClient.send` + 事件流 + 会话日志）
  - `app/ui/RunMetricsUi.kt` → `app/src/main/java/app/hoshino/harness/ui/chat/RunSummary.kt`（重写为 Compose 组件，文字口径一致）
