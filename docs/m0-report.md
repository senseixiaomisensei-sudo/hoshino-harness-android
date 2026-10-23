# M0 可行性验证报告

状态：执行中，Android 完整验收尚未完成。桌面原版 dsh 集成测试及真实 JSONL 检查点已通过；APK 内原版 dsh 已启动、原生 UI 已连接。应用进程内文件与网络探针已证明 guest 兼容库解决 rename 的 ENOSYS；完整对话和后台测试正在执行，尚不宣称全部通过。

## 上游与锁定版本

- npm latest：`@deepseek-ai/dsh@0.2.1-rc.2`，实测于 2026-10-23，MIT。
- 对应源码标签 `dsh-v0.2.1-rc.2`，提交 `687f282e8625c6b8d11ff58211a90524be2e4317`。
- CLI 发布包没有声明 engines；对应源码仓库根 `package.json` 明确要求 `^22.19.0 || >=24.0.0`，不能把“没有 engines”当作 Node 18 可用。
- nodejs-mobile 最新发布版 v18.20.4，Node 18.20.4，Android ABI：armeabi-v7a、arm64-v8a、x86_64。不满足上游 Node 要求，方案 A 判定不符合。
- 选择 B：Alpine 3.23（多架构镜像 digest 已写 Dockerfile）、官方 apk 的 nodejs 24.18.1-r0；独立 proot 从固定 Termux/proot 提交 `ee6543334a67261d19e7023ae5eb30af5a7ad4f3` 编译。rootfs 与插件随 APK 交付，在手机上不安装包、不请求安装服务。

来源：[npm 元数据](https://registry.npmjs.org/@deepseek-ai/dsh/0.2.1-rc.2)、[锁定源码](https://github.com/deepseek-ai/deepseek-harness/tree/687f282e8625c6b8d11ff58211a90524be2e4317)、[nodejs-mobile 发布](https://github.com/nodejs-mobile/nodejs-mobile/releases/tag/v18.20.4)。

## 真实接口及源码路径

以下路径均相对上述锁定的 dsh 仓库；实际实现未改上游源码。

| 能力 | 真实包 / 接口 | 源码路径 |
|---|---|---|
| CLI 启动 | dsh `runProfile`（Desktop 公开 profile-boot 入口）；内部共享 boot | `apps/cli/src/bin.ts`, `apps/cli/src/profile-boot.ts` |
| 无 UI 组合 | `@deepseek-ai/dsh-app-boot.boot`，按配置加载 Cordis plugin tree | `packages/boot/app-boot/src/index.ts` |
| 官方无头 profiles | `dsh --profile sdk-minimal`、headless runner；原版 minimal 仅配置 DeepSeek，不能直接覆盖本项目两种接口 | `packages/bundle/sdk-minimal/cordis.patch.yml`, `packages/bundle/headless/src/index.ts` |
| 创建会话 | `session/create`，`SessionCreateRequest {cwd?, sessionId?, agentPreset?}` | `packages/api/session-controller/src/index.ts`, `types.ts`, `agent.ts` |
| 发消息 | `session/prompt`，`{sessionId,requestId,mode:'queue',content:[{type:'text',text}]}` | 同上 `index.ts`, `commands.ts` |
| 切模型 | `session/selectModel {sessionId,provider,model}`；同一原版 Agent/Session | 同上 `index.ts`, `commands.ts` |
| 流式跟随 | `session/follow {address:{kind:'session',sessionId},assistantStream:true}`；snapshot/event/assistant-stream | 同上 `history.ts`, `assistant-stream.ts`, `types.ts` |
| HTTP 外壳 | `/api/<namespace>/<method>`，`client-request` / `server-response`，`payload.args.request` | `packages/client/connection/src/rpc-host.ts`, `rpc-schema.ts` |
| WS 流 | `/api/remote.mux`；`{type:'open',streamId,endpoint,payload:{args:{request}}}`，`item` 帧 | `packages/api/gateway/src/stream-protocol.ts`, `index.ts` |
| 模型适配 | `ctx.llm.registerAdapter`；复用 `@deepseek-ai/dsh-llm-pi-ai` | `packages/llm/llm-pi-ai/src/index.ts`, `config.ts`, `adapter.ts`, `provider.ts` |
| 工具 | `ctx.tools.register(defineTool(...))`；写文件使用原版 `write` | `packages/core/tools/src/schema.ts`, `packages/fs/tool-fs/src/write.ts` |
| 持久化检查点 | 原版 `dsh-session-checkpoint-policy`，模型/工具副作用之前调用 `ctx.sessions.flush` | `packages/session/session-checkpoint-policy/src/index.ts` |
| 原理 | loop 与事件、会话日志、模型/工具 seam | `docs/architecture.md` |

Anthropic 使用 pi-ai 的 `anthropic-messages` 原生适配及 Anthropic SDK，支持 thinking、SSE、tool_use / tool_result；OpenAI 兼容使用 `openai-completions`，支持 SSE 和 tool_calls。不是将 Claude 转成 OpenAI 协议。自定义 baseURL 由用户填写。

## 原生依赖

完整锁文件中平台/安装脚本清单见 `native-dependencies.json`；本机安装后的二进制路径清单见 `native-module-files.txt`（Windows 快照，不能冒充 Android 装载证据）。

| 模块族 | 处理 |
|---|---|
| `@deepseek-ai/node-addon-system` 0.1.2 | dsh JSONL writer 的 POSIX flock；官方 linux-x64 / linux-arm64 包包含 glibc 和 musl Node-API v8，Alpine 中选 musl；不可拿 Android/Bionic 直接加载 |
| sharp 0.35.5、libvips 1.3.4 | 附件服务依赖；rootfs 显式安装 linuxmusl 两种 CPU 预编译包；不在 Android 进程中链接 |
| node-pty 1.2.0-beta.15 | 终端插件未组合，M0 不调用；安装脚本关闭，不把桌面预编译 PTY 宣称为 Android 可用 |
| koffi 3.1.1（含 @koromix 平台包） | 桌面原生命令/探测的间接依赖；M0 不使用桌面打开功能 |
| sherpa-onnx / sherpa-onnx-* 1.13.8 | 语音插件未组合，optional 不打包 |
| @deepseek-ai/libreoffice-kit-* 0.1.5 | Office 插件未组合，optional 不打包；Linux wasm 亦不需要 |
| node-addon-require-builtin 0.1.9 及平台 N-API 包 | 上游 CLI 运行时辅助；M0 不走 terminal/SQLite 搜索；发布文件保留原样，不伪造 Bionic 支持 |
| protobufjs、@google/genai 安装脚本 | 不等价于原生模块；忽略 install scripts，未组合 Gemini/遥测功能 |

上游原生支持矩阵：`native/system/docs/support-matrix.md`。Landlock 依赖宿主内核，不能在 Android proot 中假定存在。M0 使用自研工具审批和 workspace 规范路径检查，不声称 proot 提供 Landlock 沙箱。

## 自研插件

- `android-device-info`：注册 `get_device_info`，通过鉴权回调获取 Kotlin 的设备机型、版本、电量。
- `android-credentials`：实现 dsh `CredentialProvider`；模型 key 每次从 Kotlin 解封装；Connection 签名记录仅驻内存。
- `android-policy`：限制工具及 workspace，修改、模型网络请求逐次审批。
- `android-api`：原版模型插件配置、一次性引导 token 到 dsh API Cookie 的交换、刷新 provider。

## 验证记录

本地 `node-host/npm test`：已通过。使用实际 dsh 启动器、真实 Session Controller 和 Gateway，mock 仅替代模型 HTTP 服务和 Android 回调。验证同一会话 6 个 turn、两次设备回调、内置 write 创建 `hello.txt`、两种 SSE、thinking、跨 provider 延续、匿名 API 拒绝、引导 token 重放拒绝、会话目录中无 key 明文。该测试不能证明 proot、APK 冷启动或后台五分钟。

Android 仪器测试 `M0AcceptanceTest` 已编写，包含 8 项标准、实际五分钟后台等待、Keystore 明文扫描、logcat 扫描和指标采集。测试最终状态及实际产物指标将在运行后更新。

| 项目 | 当前证据 |
|---|---|
| APK 体积 | 未测，不填估计值 |
| APK 冷启动 | 未测；由仪器测试写 `m0-metrics.json` |
| Android 内存 | 未测；由仪器测试和 `dumpsys meminfo` 采集 |
| 全绿 CI | 尚未证明 |
| 真实厂商模型 | 未提供测试凭据，mock 验证不等于真实厂商验收 |

## 问题与绕过

- Windows 中文目录被 AGP 的路径预检拒绝：只为本项目加 `android.overridePathCheck=true`，实际编译继续验证，未搬动用户目录。
- 无头运行无需浏览器 UI 包；禁用安装脚本避免不用的 PTY 等编译，同时显式补入必要的 musl 预编译包。
- Anthropic SDK beta 接口包含查询参数；mock 按 URL pathname 匹配，不能用严格字符串匹配整个 URL。
- 自定义模型的 thinking 必须通过 dsh 的 `reasoningEfforts` 声明，不能编造 `reasoning: true` 配置。
- SDK 37 的包名实际为 `platforms;android-37.0`，最初用旧名称 `android-37` 查询才显示不存在；已改为 compile 37.0 / target 37，AGP 9.1.1、Gradle 9.4.1、Kotlin 2.2.20，本地编译通过。
- AAPT 会解开后缀 `.gz` 的资产并去掉后缀：最初 APK 只含 `assets/kernel/x86_64.tar`，导致 `open(...tar.gz)` 抛 FileNotFoundException。改为 `.tgz` 并设置 noCompress，保留 gzip 供启动器读取；模拟器已实际解包成功。
- 本机多个构建守护进程、Docker 与模拟器并行曾导致内存不足。限制构建 workers 为 2、Kotlin heap 为 768 MiB，关闭本任务启动的空闲守护进程和 Docker；未改用户的全局构建配置。
- Android 应用内探针发现：musl 的 write/fsync 正常，rename 返回 ENOSYS；run-as 不受同一 zygote seccomp 策略约束，不能替代原生验收。这影响原版 dsh 的 storage-json 原子保存。独立 MIT guest 兼容库将 legacy 文件操作映射到相应 *at 调用，保留原子性与错误处理；应用内探针已通过（4.073 秒），完整验收仍需继续。参见 [Android 16 bionic syscall 清单](https://github.com/aosp-mirror/platform_bionic/blob/android-16.0.0_r1/libc/SYSCALLS.TXT) 和 [seccomp 构建输入](https://android.googlesource.com/platform/bionic/+/main/libc/Android.bp)。

## 对 M1 的建议

只有 APK 内核运行与全部验收被实际证明后才进入 M1。优先压缩 rootfs、评估 proot 在厂商系统上的 ptrace/后台限制，再扩展 shell 和文件编辑；保持 `KernelLauncher` 与 Cordis seam 可替换。不要以桌面测试替代 arm64 真机测试。
