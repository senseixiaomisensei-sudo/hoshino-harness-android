# Third-party notices

各分支只追加自己的段落，便于合并。

## Mika（ChatGPT 登录与模型目录移植，分支 feat/m2.7-chatgpt-search）

- Project: https://github.com/wangzan101/mika （本地对照提交 688f59d，版本 0.1.13）
- License: Apache-2.0，全文见 `licenses/mika-LICENSE`
- NOTICE:

```
Mika
Copyright 2026 wangzan101
```

- 移植/改写的文件（文件头保留出处说明）：
  - `core-bridge/src/main/java/dev/hoshino/bridge/chatgpt/ChatGptOAuth.kt` ← Mika `core/.../chatgpt/ChatGptAuth.kt`（含 `HttpSupport.execute` 的写法）
  - `core-bridge/src/main/java/dev/hoshino/bridge/chatgpt/ChatGptSession.kt` ← Mika `core/.../chatgpt/ChatGptSession.kt`；失败说明文字改写自 Mika `values-zh/strings_models.xml`
  - `core-bridge/src/main/java/dev/hoshino/bridge/chatgpt/LoopbackCallback.kt` ← Mika `core/.../chatgpt/LoopbackCallback.kt`
  - `core-bridge/src/main/java/dev/hoshino/bridge/chatgpt/ChatGptModels.kt` ← Mika `core/.../model/ModelCatalog.kt`（OpenAI 兼容/ChatGPT 模型列表解析部分）
  - `core-bridge/src/test/java/dev/hoshino/bridge/chatgpt/ChatGptAuthTest.kt` ← Mika `core/src/test/.../chatgpt/ChatGptAuthTest.kt`
  - 参考而未逐行移植：Mika `ResponsesModel.kt`（`store:false`、include 重试、套餐路由不发可选参数、错误码含义）的规则在 `plugins/android-api/hosted-search.ts` 中以 TypeScript 重新实现。
