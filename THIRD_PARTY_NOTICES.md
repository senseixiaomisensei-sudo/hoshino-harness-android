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
