# Third-party notices

各分支只在本文件末尾追加自己的段落（`docs/roadmap-surpass-mika.md` §8）。

## Mika

- 来源：https://github.com/wangzan101/mika （参照提交 `688f59d`，0.1.13）
- 许可：Apache License 2.0，全文见 [`licenses/mika-LICENSE`](licenses/mika-LICENSE)
- NOTICE 原文：

```
Mika
Copyright 2026 wangzan101
```

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
