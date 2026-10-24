# preview.4：完全访问与历史搜索修复

本次针对用户报告的完全访问发送失败、Shizuku 已安装却提示安装、历史内容搜索不到。手机是否支持 Shizuku 不能从通用 Toast 判断；已定位到应用接入缺失与真实接口参数错误。

## 修改

- 生产客户端通过官方开源 Shizuku UserService 执行 Android `system_shell`，Cordis 插件注册工具并沿用审批。普通 bash/PTY 保持 Alpine 环境。返回真实 UID；拒绝不运行，超时/取消清理本应用的命令进程组。
- 安装、服务运行、授权分别检测，返回前台刷新。未运行/未授权显示具体状态，发送失败保留草稿和附件，显示脱敏后的实际异常。
- 修复原版 `session/list` 的 `_request` 命名参数、标题投影读取、历史工作区恢复和重复 follow。
- 使用原版 `session/search` 搜正文，启用原版 SQLite 的 first-search 内存索引。归档和折叠组内的匹配可以找到并打开；不修改原版 JSONL 持久化或 SessionStore。
- 内核正常停止时捕获日志管道 I/O 关闭，避免冷重启产生未捕获线程异常。

## 验证范围

| 项目 | 证据与结果 |
|---|---|
| 双 ABI 本地 APK、单测、x86 仪器 APK | `.research/m1-preview4-packed-build.log`，BUILD SUCCESSFUL |
| 桌面原版 dsh 8 轮：两种协议、设备、write、切换、原版标题/正文搜索、系统工具允许/拒绝 | `.research/m1-system-shell-node-test.log`，通过；该层系统回调为测试夹具，不代替 Android UID 证明 |
| Android 官方 Shizuku 服务器、真实系统执行、拒绝/允许、超时/取消 | `.research/m1-preview4-native.log`，通过，实际 UID 断言为 adb 2000 或 root 0；本地为官方 adb 服务 |
| 冷启动读取原 JSONL、归档正文搜索和实际侧栏打开 | 同上，通过；标题不含正文关键词，刷新两次不重复订阅，停止内核后用新客户端从原日志恢复 |
| GitHub CI 全工作流 | 尚无本次提交全绿记录 |
| 用户 arm64 真机及其具体模型接口 | 未现场复测 |

验证失败记录保留。完整 M1 的其余功能与验收仍在继续，不把本次修复测试包称为 M1 全量通过。

最终测试包 versionCode 103、versionName `0.1.0-m1-preview.4`，原 Debug 证书 SHA256 `e20b0b97868f158e8570f712b4d488b4f7671ecfc7f5ad72cd66b04a8a5e8fd1`，两个 ABI 均由 apksigner 核验。

| ABI | 字节数 | SHA256 |
|---|---:|---|
| arm64-v8a | 109,495,566 | `8a9fb1d2d5c8fce1d8899a0b3325fae95301d2cb1fbbc141f7aed7aa57ef09c3` |
| x86_64 | 114,387,692 | `63add06481e81e54d8ac419df6ef772c389057947d39b0149852a5238ba0c4c5` |

首次完整回归发现正常停止内核的管道异常；修复后第二轮 6 项中 5 项通过，唯一失败为系统命令超时导致输出读取线程抛出 ExecutionException，日志 `.research/m1-preview4-native-before-timeout-fix.log`。已限制为主动终止时保留部分输出、正常路径仍上报 I/O 错误。最终原始 APK 在 x86_64 API 36 上全部 6 项通过，`Time: 150.168 / OK (6 tests)`，汇总 `.research/m1-preview4-validation.log` 的 `PREVIEW4_NATIVE_ALL_SIX_OK`。发布附件 `Hoshino-preview4-validation.txt` 保存 Node 与 Android 原始验证输出。
