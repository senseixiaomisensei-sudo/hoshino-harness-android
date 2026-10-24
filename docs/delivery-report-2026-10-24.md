# Hoshino Harness 阶段性交付报告

日期：2026-10-24。按用户最后要求，发布当前 GitHub 源码、APK 和报告，停止本轮后续开发。

## 交付结果

源码仓库：[senseixiaomisensei-sudo/hoshino-harness-android](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android)。交付分支为 `codex/m1`，仓库默认分支指向本次源码；`main` 保留 M0 历史基线。

发布版本：[v0.1.0-m1-preview.5](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/tag/v0.1.0-m1-preview.5)，versionCode `104`。APK 对应源码提交 `53e842dce73cb009693edf3f59d22c7848b8ddea`；后续收尾提交整理说明及测试夹具，APK 内容不变。

| 产物 | 大小 | SHA256 |
|---|---:|---|
| [arm64-v8a 手机包](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/download/v0.1.0-m1-preview.5/Hoshino-Harness-0.1.0-m1-arm64-v8a-debug.apk) | 109,620,802 字节 | `b5a2803513ddf2fa0eee336a38de9bca135fee3d8ea9aee63d368949f2bf8406` |
| [x86_64 测试包](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/download/v0.1.0-m1-preview.5/Hoshino-Harness-0.1.0-m1-x86_64-debug.apk) | 114,512,420 字节 | `45bda29722cf945dca32f19629c7ddfc1c182a9fe39bc3b32e7f876fcc58b586` |

两包均低于 150 MB。原 Debug 证书 SHA256 为 `e20b0b97868f158e8570f712b4d488b4f7671ecfc7f5ad72cd66b04a8a5e8fd1`，apksigner 核验成功；GitHub 附件 digest 与本地一致，可覆盖此前相同签名的测试版。

## 本轮完成内容

- 修复模型请求兼容：完整端点整理、密钥编辑占位符、无推理元数据时误传 off，以及错误详情脱敏展示。
- 接入官方 Shizuku SDK 的安装、运行和授权检测；Cordis `system_shell` 经审批调用真实 Android UserService，返回实际 adb/root UID，支持拒绝、超时和取消。
- 修复历史列表真实命名参数、标题投影、工作区恢复和重复订阅；使用原版全文搜索，支持冷重启后的归档正文搜索。
- 原生界面接通强调色、字号、标语、胶囊、自定义命令、侧栏分组内显隐/顺序，以及实际配置快照回滚。
- max 不再被缺失的模型能力元数据阻挡。验证了通用 OpenAI 兼容、DeepSeek、Anthropic 三类线路的最高档位及预算；DeepSeek 中转明确使用 `max_tokens`，Anthropic max 使用 adaptive thinking。max 输出预算至少 32K，受配置上下文容量约束，保留更高设置。
- 加载缺失的原版 token-meter，展示实际输入、容量和估算明细；思考全文只折叠、不限制展开长度；终端结果默认整卡折叠并可完整复制。

仍使用未修改的 dsh `0.2.1-rc.2` 和原版 JSONL 持久化，保留 KernelLauncher / Cordis 替换边界。SessionStore 保持原文件摘要 `8705808b6e3bdbb366d5d323e833e40b37a90f4f16cbbe49eff86263c9cd9911`。当前实际组合为 52 个 boot 插件、20 个工具、29 个路由。

## 验证证据

| 检查 | 实证结果与依据 |
|---|---|
| 最终双 ABI APK、单测与仪器 APK 构建 | 本地成功，`.research/m1-max-final-build.log`：`BUILD SUCCESSFUL in 56s` |
| 原版 dsh 集成 | 本地 mock-llm 经原版内核跑 11 轮，2 项通过，2 项可选真实接口因无凭据跳过；`.research/m1-full-regression-node-test.log` |
| 同一发布 APK 的现有 8 项 Android 回归 | 226.002 秒、`OK (8 tests)`；`.research/m1-full-regression-native.log`，[公开原始记录](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/download/v0.1.0-m1-preview.5/Hoshino-preview5-regression-8.txt) |
| 回归范围 | 截图/复制/PTY/长图；双协议流式与 thinking、设备工具、write/Diff、切换；Shizuku 检测及真实执行；发送失败保留草稿；冷重启归档搜索；UI 覆盖/回滚；max 实发、32K 预算、上下文与全文折叠 |
| 源码与 APK 对应 | 重新计算两种 APK 的 SHA256，与已发布附件及保留副本一致；收尾测试修正未改 APK 内容 |
| 最近已完成远端 CI | [40077416444](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/actions/runs/40077416444)，源码 `53e842dce73cb009693edf3f59d22c7848b8ddea`：**失败**。内核/运行时/构建成功，8 项仪器中 Diff 事件检查与归档结果滚动定位两项失败 |
| CI 后的本地收尾 | 改进可重复运行的文件写入夹具，保留严格 Diff 检查；同一 APK 本地 8 项全部通过。没有以本地结果替换远端失败状态 |
| 用户真机反馈 | 用户表示其余功能正常、模型支持 max、手机支持 Shizuku；属于对会话中已有版本的使用反馈，新包未现场逐项复测，未替代正式真机签认 |

本地完整回归的公开记录 SHA256：`f92027fde2badfcaaa427f30fbf7e0e05e02c6a7387d38ab2ce58a82ff8de144`。原先失败日志和历史发布附件保留，未覆盖成“通过”。

## 未完成及未验证事项

- 远端 CI 全绿尚未确认；最终整理提交会触发正常工作流，可从仓库 Actions 查看。
- 现有 8 项回归不等同于完整 M1AcceptanceTest。M1 专用性能/内存指标、后台五分钟完整验收和正式 arm64/OEM 量化签认尚未补齐。
- 安全模式的开发草稿未集成或发布；高级文件管理、终端交互补项、插件设置面板、源码级自动 DIY 构建链仍有未完成项。
- 部分后续功能页保留原型或占位交互，不能按完整功能验收。详细过程与边界见 [M1 记录](m1-report.md)、[本次 max 修复](m1-max-fix.md)、[真机清单](m1-device-checklist.md)。

本轮已完成当前测试版发布与报告交接；**不宣布完整 M1 里程碑通过**。按用户要求在此收工，保留源码、证据和未完成清单供以后继续。
