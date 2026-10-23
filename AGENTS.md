项目：开源手机端通用 AI Agent（Android 原生 APK）

一、项目目标
• 做一个体验接近电脑端 Codex 的 Android 通用 Agent：能聊天、规划、调用工具、读写文件、跑 shell、写代码、操作手机。
• 原生 APK，禁止用 WebView 做主界面。
• 绝对 0 成本：不写任何后端服务、不接入任何付费 SaaS、不加遥测。所有东西在手机本地运行，模型由用户自带 API key。
• 全部开源（主项目 MIT）。
• 一切皆插件：模型、工具、手机能力、会话、UI 面板都必须可插拔替换。

二、架构（必须遵守）
1. UI 层：Kotlin + Jetpack Compose + Material 3。负责会话列表、流式消息、工具调用卡片、diff、审批弹窗、设置。不包含任何 Agent 业务逻辑。
2. 内核层：DeepSeek Harness（dsh，npm 包 @deepseek-ai/dsh，MIT，基于 Cordis）以无头方式运行。Agent loop、session log、模型适配、工具注册全部复用 dsh。
   • 方案 A（首选）：nodejs-mobile 把 Node 运行时嵌进 APK。
   • 方案 B（备选）：proot + Alpine rootfs 里安装完整 Node 再跑 dsh。
3. 桥接层：UI 与内核只通过 127.0.0.1 上的 HTTP/WebSocket 通信，端口随机、带一次性 token 鉴权。优先使用 dsh 自己的 session/API 协议，不自创协议。
4. 手机能力：文件、通知、截图、无障碍、Intent 等必须写成 dsh（Cordis）插件，插件通过桥接回调 Kotlin 实现。
5. 模型配置：参考 opencode 的 provider 配置风格，一份 JSON：provider id、baseURL、apiKey、models 列表。支持 OpenAI 兼容、Anthropic、Gemini、DeepSeek、本地 Ollama。apiKey 只存 Android Keystore，绝不写日志、不写明文文件。

三、铁律
• 不要 fork 或修改 dsh 源码。所有扩展用插件实现。确实绕不开时，写清原因停下来问我。
• dsh 是开发者预览版，会有破坏性更新：在 package.json 里锁死精确版本（不要 ^ 或 ~），升级必须单独提交。
• 不要编造 dsh 的 API。先读它的源码和 docs（重点：apps/cli、packages/boot、packages/api/session-controller、docs/architecture.md），引用真实的包名和接口。
• 任何会改动用户文件、执行 shell、发网络请求的工具，默认走审批。
• 不引入任何闭源依赖、Google Play Services、Firebase。
• GPL 组件（如 proot）只能作为独立二进制调用，不链接进主程序。
• 一次只做当前任务，不要顺手做后续里程碑的事。

四、仓库结构
app/                Compose UI 与 Android 服务
core-bridge/        Kotlin 端桥接客户端（启动内核、连接、消息模型）
node-host/          Node 宿主项目：package.json（锁定 dsh）、启动脚本、profile 配置
plugins/            自研 dsh 插件，每个插件一个子目录
rootfs/             方案 B 与 shell 环境的脚本（proot + Alpine）
docs/               设计文档与里程碑报告
.github/workflows/  CI

五、构建与测试
• 构建：./gradlew assembleDebug
• 单测：./gradlew test；node-host 内 npm test
• 目标：arm64-v8a，minSdk 29，targetSdk 用当前稳定版
• CI：GitHub Actions，每次 push 构建 debug APK 并上传为 artifact；打 tag 时发 Release。
• 每次交付前必须本地构建通过，并在 docs/ 里更新对应说明。

六、代码规范
• Kotlin：协程 + Flow，MVVM，单向数据流，不用 Hilt 以外的重型框架。
• TypeScript：严格模式，插件遵循 Cordis 的 service/event 写法。
• 日志里不得出现 API key、token、用户文件内容。
• 提交信息用 Conventional Commits。

七、里程碑
M0 可行性验证 → M1 MVP（聊天/工具/文件/shell/自定义 API）→ M2 Codex 体验（diff/审批/多会话/子 Agent）→ M3 插件生态（Android 插件/市场/MCP）→ M4 打磨上架（F-Droid）
