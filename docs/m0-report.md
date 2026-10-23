# M0 可行性验证报告

更新日期：2026-10-24。

结论：M0 基础可用性已得到运行验证，APK 已交付，用户反馈实际使用没有明显问题。原版 dsh 在 APK 内以方案 B 无头运行，由原生 Compose UI 驱动。桌面集成测试通过；2026-10-23 的 CI Android 仪器测试任务也通过，但随后读取指标文件失败，因此该次工作流整体仍为失败。指标采集收尾与增强断言的 CI 正在验证。

## 交付与用户反馈

- 交付文件：`app/build/outputs/apk/debug/Hoshino-M0-debug.apk`，Debug APK，版本 `0.0.1-m0` / versionCode 1。
- 交付记录：2026-10-23 约 21:56（北京时间）在会话中提供本地 APK 路径。本地构建、APK v2 签名校验和模拟器安装均已通过。
- 目标环境：minSdk 29、targetSdk 37，包含 arm64-v8a 和 x86_64；没有 WebView 主界面。
- 用户于 2026-10-24 反馈：“我用的没什么问题”。将此记为实际试用反馈；用户未提供机型、Android 版本、provider/model 和逐项测试清单，覆盖范围尚未细分。
- 本次范围为 M0：聊天与流式展示、thinking、模型切换、工具卡片、审批、设备信息工具、内置文件工具及凭据保护。M1 的 shell、完整文件管理等功能未在本次交付中开放。
- APK SHA256：`dde75a9590d9444dfe59fbeb47ac650f62192e10fc9dbfb22f53656cf66367c0`。

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

Android 仪器测试 `M0AcceptanceTest` 覆盖冷启动、两种协议及 thinking 流式增量、设备工具、内置 write、同一会话切换 provider、真实五分钟后台等待、凭据存储及 logcat 扫描。2026-10-23 的 [CI 运行 39963618593](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/actions/runs/39963618593) 在提交 `aee9f26d61af15814486161a766fe542ec5bfdc0` 上完成 1 个仪器测试，`connectedDebugAndroidTest` 显示 BUILD SUCCESSFUL（7 分 55 秒）。之后 `cat m0-metrics.json` 报文件不存在，工作流整体失败；不能将该次记录称为全绿 CI。

后续增强了设备工具结果必须进入模型最终答复的断言，并将全应用存储扫描改为逐字节等价的 Latin-1 字符串搜索，保留跨块匹配。桌面集成测试增强后通过：6 个 turn、2 次设备回调；两个真实厂商测试因无凭据跳过。最终本地仪器重跑未留存完成结果，不能补记为通过。CI 中双 ABI proot 源码构建和 guest 兼容库编译均已通过。

| 项目 | 当前证据 |
|---|---|
| 已交付 APK 体积 | 454,782,895 字节，约 454.78 MB / 433.71 MiB；双 ABI Debug 构建 |
| APK 冷启动 | 未测；由仪器测试写 `m0-metrics.json` |
| Android 内存 | 未测；由仪器测试和 `dumpsys meminfo` 采集 |
| 全绿 CI | 上述运行仪器测试通过，后续指标采集失败；修正后待验证 |
| 人工试用 | 用户反馈实际使用没有明显问题；未细分测试覆盖范围 |
| 真实厂商模型自动化 | 未提供 CI 测试凭据，两项测试按规则跳过 |

## 问题与绕过

- Windows 中文目录被 AGP 的路径预检拒绝：只为本项目加 `android.overridePathCheck=true`，实际编译继续验证，未搬动用户目录。
- 无头运行无需浏览器 UI 包；禁用安装脚本避免不用的 PTY 等编译，同时显式补入必要的 musl 预编译包。
- Anthropic SDK beta 接口包含查询参数；mock 按 URL pathname 匹配，不能用严格字符串匹配整个 URL。
- 自定义模型的 thinking 必须通过 dsh 的 `reasoningEfforts` 声明，不能编造 `reasoning: true` 配置。
- SDK 37 的包名实际为 `platforms;android-37.0`，最初用旧名称 `android-37` 查询才显示不存在；已改为 compile 37.0 / target 37，AGP 9.1.1、Gradle 9.4.1、Kotlin 2.2.20，本地编译通过。
- AAPT 会解开后缀 `.gz` 的资产并去掉后缀：最初 APK 只含 `assets/kernel/x86_64.tar`，导致 `open(...tar.gz)` 抛 FileNotFoundException。改为 `.tgz` 并设置 noCompress，保留 gzip 供启动器读取；模拟器已实际解包成功。
- 本机多个构建守护进程、Docker 与模拟器并行曾导致内存不足。限制构建 workers 为 2、Kotlin heap 为 768 MiB，关闭本任务启动的空闲守护进程和 Docker；未改用户的全局构建配置。
- Android 应用内探针发现：musl 的 write/fsync 正常，rename 返回 ENOSYS；run-as 不受同一 zygote seccomp 策略约束，不能替代原生验收。这影响原版 dsh 的 storage-json 原子保存。独立 MIT guest 兼容库将 legacy 文件操作映射到相应 *at 调用，保留原子性与错误处理；应用内探针已通过（4.073 秒），完整验收仍需继续。参见 [Android 16 bionic syscall 清单](https://github.com/aosp-mirror/platform_bionic/blob/android-16.0.0_r1/libc/SYSCALLS.TXT) 和 [seccomp 构建输入](https://android.googlesource.com/platform/bionic/+/main/libc/Android.bp)。
- Termux proot 5.1.107.99 预编译包在应用域中，`fs.link` 成功后 `fs.unlink` 触发 tracer SIGSEGV；移除 link2symlink 会让原版 JSONL 原子发布的 link 返回 EACCES。移除 fake-root 也无效。改用固定上游归档、固定 NDK 从源码构建独立 proot，最小创建/删除测试 1.057 秒通过，随后已跑到两种协议的模型与工具调用。归档校验和、编译参数及头文件兼容处理全部公开在 `rootfs/build-proot.py`，没有修改 dsh 或替换其持久化实现。
- mock 会在不同轮次复用 tool call ID，Compose LazyColumn 因此出现重复键错误。界面键改用原版事件 seq，工具结果按最近的 call ID 配对，保留原始数据；两协议同一会话的重复 ID 路径作为集成回归继续验证。
- 原版检查点策略退出时仍会落盘；测试立即删目录可触发 ENOTEMPTY。桌面测试先等待内核退出再清理，CI 不再把清理竞态混入 Agent 验收。
- AGP 仪器测试结束后的默认清理使指标路径不可用。根据本地 AGP 9.1.1 的真实 BooleanOption，CI 使用 `android.injected.androidTest.leaveApksInstalledAfterRun=true` 保留测试安装；内存报告在仪器测试结束前采集，随后与 JSON 指标一起取出，避免读取已退出进程的内存。

## 对 M1 的建议

只有 APK 内核运行与全部验收被实际证明后才进入 M1。优先压缩 rootfs、评估 proot 在厂商系统上的 ptrace/后台限制，再扩展 shell 和文件编辑；保持 `KernelLauncher` 与 Cordis seam 可替换。不要以桌面测试替代 arm64 真机测试。
