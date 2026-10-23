# M0 插件与替换指南

## 新增工具插件

以 `plugins/android-device-info/index.ts` 为最小范例，导出 Cordis 的 `name`、`inject`、`apply(ctx)`；使用原版 `@deepseek-ai/dsh-tools` 的 `defineTool` 与 `ctx.tools.register`。参数 schema 用 dsh 定义的类型；输出 object 必须明确 `additionalProperties`。execute 的 `exec.signal` 要传到异步 IO。

设备信息流程：dsh 调用 `get_device_info` → `AndroidBridge.call('device-info')` → 127.0.0.1 上带随机 Bearer token 的 Kotlin `AndroidCallbackServer` → Android Build / BatteryManager → 原版工具结果 → 原版 agent loop 发起下一步。没有在 UI 层伪造设备结果。

添加手机能力时同时在 Kotlin 的方法白名单增加实现；不要开放任意 Java 反射、任意 Intent 或任意文件路径。callback 的请求/响应不写日志。把插件列入 `node-host/src/main.ts` 的 Cordis rows，然后重新编译、重打 rootfs。插件包用 `engines.dsh: "0.2.1-rc.2"` 声明兼容范围。

## 模型层

已有两种协议都由 `@deepseek-ai/dsh-llm-pi-ai` 实现。Android provider JSON 样例：

```json
[
  {"id":"claude","type":"anthropic","baseURL":"https://api.anthropic.com","apiKey":"KEY_CLAUDE","models":["用户填写的模型 ID"]},
  {"id":"local","type":"openai-compatible","baseURL":"http://127.0.0.1:11434/v1","apiKey":"KEY_LOCAL","models":["用户安装的模型 ID"]}
]
```

`apiKey` 引用而非明文；通过设置页保存真实 key。`android-api` 将它映射到 dsh provider profile 的 `apiKeyEnv`。`android-credentials` 每次解析引用时请求 Kotlin，用 Keystore 封装的密文解密。不加载 `dsh-credentials-local`，避免创建明文 `.credentials.yaml`。浏览器/API Cookie 签名 grant 仅驻内存。

如果新增 dsh 现有适配不支持的协议：在新的 Cordis 插件里实现 `@deepseek-ai/dsh-llm.LlmAdapter`，使用 `ctx.llm.registerAdapter([route], adapter)`。必须按锁定版本的 `prepareCall`、`StreamChunk`、模型目录及 replay state 契约实现；不要直接将自写 HTTP loop 接到 Compose。先查对应源码 `packages/llm/llm*/src` 和 cookbook。

## 更换内核启动方式

`core-bridge/KernelLauncher.kt` 声明 `KernelLauncher`。`ProotKernelLauncher` 独立启动 proot，在 Alpine 内执行原版 Node 与 dsh；输入仅是私有 stdin JSON 引导信息。替换为兼容 Node 版本的嵌入式实现时，保持回环桥接及 dsh 原始 Session API，更新 `KernelRuntime` 的 launcher 绑定。不能为了更换启动器重写 agent loop。

## 会话与 UI

会话历史使用原版 `dsh-session-persistence-jsonl`，位于应用私有 `dsh-home/sessions`。客户端只使用 Session Controller 的 create / prompt / selectModel / follow。消息从 durable event.data.message.content 渲染，增量从 assistant-stream 的 text-delta / reasoning-delta 渲染，工具卡片由 tool/call 与 tool/result 配对。

Compose 读取 ViewModel 暴露的 StateFlow；HTML 不加载进运行时。替换 UI 可以重用 core-bridge 的 `SessionClient`。替换会话存储应写 dsh SessionPersistence 服务插件，不在 Android UI 另做一份会话事实来源。

## 权限与生命周期

`android-policy` 的工具白名单与目录约束是 M0 部署决定，新增工具必须有明确的审批及路径/网络策略。`KernelService` 是非导出前台服务，Activity 销毁不停止它。Kernel 崩溃后界面显示状态与最多 50 行分类日志；不承诺自动恢复未结算的模型请求。

编译 `cd node-host && npm run build`，验证 `npm test`。已准备过两种 ABI 的运行环境时，运行 `python rootfs/prepare.py --refresh-host` 更新归档内的自研 host/plugins；依赖或系统层变更仍需完整运行 prepare。归档指纹改变后，启动器会替换运行环境，workspace 和会话目录独立保留。不要修改 node_modules 或 dsh 源码来让插件兼容。

Linux guest 的 `rootfs/android-compat.c` 只解决 Android seccomp 与 musl 旧式文件调用的兼容，随 rootfs 编译并通过 guest 的 LD_PRELOAD 加载，不链接到 Android 主程序。更换 KernelLauncher 时可以按所选运行环境替换该平台适配。会话的磁盘检查点由原版 `dsh-session-checkpoint-policy` 执行，不在 Kotlin 中另造日志。
