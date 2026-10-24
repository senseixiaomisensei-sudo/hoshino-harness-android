# M1 实现记录（开发中）

尚未完成 M1 验收，不能将开发构建称为正式交付或全量通过。

## preview.1 已确认失败，暂缓使用

2026-10-24 12:03 发布 preview.1，晚于 12:00 要求 3 分钟；两包下载、签名和 SHA256 已核验，但随后首次真实 APK 仪器冒烟发现内核启动退出 1（`.research/m1-ui-smoke.log`）。已经通知用户暂缓安装并将 Release 说明改为警告，未删除复现产物，也未宣称 M1 通过。

根因是部署瘦身过滤器把 `yaml/dist/doc/*.js` 当作说明文档删除。锁定版本 YAML 2.9.1 的 composer 实际 require `../doc/directives.js`，导致原版 skill-filesystem 导入失败。没有修改 dsh 上游。修复为保留运行目录，并在刷新旧缓存时从 npm lock 的原始 tarball 恢复 5 个 JS 文件、校验 SHA512；新增打包后实际组合模块导入检查与 Android 原生冒烟 CI。

在自有模拟器临时恢复这 5 个原始文件后，诊断性仪器测试成功：`.research/m1-diagnostic-ui-smoke.log`，32.707 秒，真实 PTY、PixelCopy、局部 FLAG_SECURE、消息/代码复制、长图尺寸与 MediaStore。**这是修改过的诊断环境，不能作为原始 preview.1 或尚未重装的修复 APK 通过证据。** 修复包将重新安装、触发完整 rootfs 解包并重跑后再推荐下载。

## 2026-10-24 午间测试包

用户要求 12 点前先交付 APK，并在交付后继续开发。本次为预发布测试快照；完整 M1AcceptanceTest、arm64 真机、厂商后台、完整文件管理交互与全部 self 回滚验收仍未完成，不作为 M1 通过结论。

- 开发运行时 CI 整体成功：[40058143620](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/actions/runs/40058143620)，提交 `52f9a2ed992082ce365423e4c097b2c38dedb37b`。仅证明该提交的运行时构建、容器 PTY/Sharp 自检、APK 构建及单测；不替后续功能快照背书。
- 后续原版 dsh 双协议集成本地成功：`.research/m1-delivery-node-test.log`（6 turns，2 次设备回调，真实厂商凭据未配置而跳过）。
- 后续功能提交 `e7d5920486ba13ce2856032b346adf742ff8e7be` 的开发 CI 整体成功：[40059973214](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/actions/runs/40059973214)。仍然没有仪器验收，不能称 M1 通过。
- 自述手册生成读取真实 50 个 boot rows、19 个工具、29 个路由：`.research/m1-delivery-node-build.log`。
- 终端/文件/截图原生源码编译成功：`.research/m1-media-build.log`、`.research/m1-files-build.log`。实际 Android 冒烟及本次 APK 构建结果另行补证；编译不等同交互验收。

测试快照包含真实 DshHarnessClient、原版 PTY 远程接口、私有文件后端与编辑页、图片/文件附件、截图预览、自研 android-ui、hoshino-self 与审批/Diff 通道。部分流程仍在联调。没有手机实测就不填真机耗时、内存或 OEM 兼容结论。自述手册里尚未接通的预设/回滚 UI、UI 覆盖应用和源代码打包需在后续完成，不能将工具后端存在写成完整体验通过。

午间本地首次最终构建中的 7 个 core 用例有 1 个失败：Windows 符号链接未被 canonicalFile 拒绝（`.research/m1-priority-arm64-build.log`、`core-bridge/build/test-results/testDebugUnitTest/TEST-dev.hoshino.bridge.WorkspaceFilesTest.xml`）。发布脚本拒绝了该包；改用额外 NIO 祖先链接检查后重新构建。不得将首次失败删改成通过。

已核对 M0 四项软件门槛，依据见 `m1-decisions.md`。完整 ZIP 的源码、资源和品牌已合并；SessionStore 未修改，原始摘要见 `m1-ui-origin.json`。ZIP 未包含 design/SPEC.md，不能声称读过缺失文件。

| 当前项目 | 状态 | 证据 |
|---|---|---|
| 原 ZIP UI / 双 ABI APK 编译 | 本地成功 | `.research/m1-ui-build-2.log` |
| 消息/代码/工具复制、密钥局部窗口保护 | 已实现，仪器交互未验证 | `.research/m1-copy-build.log` |
| 真实 dsh 事件适配 / SessionStore | 已接入，完整 Android 回归待跑 | core/DshHarnessClient.kt；SessionStore 摘要比对待 CI 固化 |
| 本地 assemble / 单测 | 成功 | `.research/m1-foundation-build.log` |
| 扩展后 dsh 桌面集成 | 第二次成功：6 turns、2 次设备回调、两个厂商真实测试无凭据跳过 | `.research/m1-node-test-2.log` |
| musl node-pty / Sharp | 构建流程已准备，目标运行未验证 | rootfs/Dockerfile、Node header 锁文件 |
| 完整终端、文件、截图、self、M1AcceptanceTest | 未完成 | 后续实施 |
| arm64 真机 / 厂商系统 / 3 秒指标 | 未验证 | 不能用模拟器补记 |

UI 迁移时移除了 M0 Activity 的全局 FLAG_SECURE；旧消息使用普通 Text，缺少 SelectionContainer。现在仅 provider 密钥编辑页启用窗口保护；复制复用 ZIP helper，统一凭据脱敏与 Android 13 敏感标记。源码审查不代替系统截图与剪贴板仪器测试。

完整交付时补齐 ZIP README 页面逐行对照、截图、全绿 CI、指标、未完成项、两个 APK 的 SHA256 及公开 Release 下载直链。

首次扩展后的本地启动等待 60 秒超时，无错误输出；加入只含固定服务名与 ready/pending 的启动诊断后，第二次完整集成 35.39 秒成功。尚未证明首次超时的根因，未将其归因于未经证实的组件。


## preview.3 请求失败修复（2026-10-24）

用户报告正常接口在聊天中仅显示通用失败。已复现应用对无推理模型强传 off 的兼容缺陷；原版 dsh 明确返回 session/model-unavailable。改为没有推理能力时省略 effort，同时去除 provider 强制 medium、整理完整端点地址、修复密钥编辑占位符和 Bearer 前缀。未修改 dsh 源码、持久化或 Agent loop。

桌面回归 .research/m1-provider-fix-node-build.log、.research/m1-provider-fix-node-test.log：实际原版 dsh 双协议 6 轮通过，包含设备工具、内置 write、流式及会话中切换；两个厂商真实接口因没有凭据跳过。Android 整包构建及仪器复验正在进行。未获得用户具体接口错误详情，不能把这轮回归写成用户真机已通过。

CI 40063104406 的 Android 作业未执行：ARM 后打包模块检查通过，x86 辅助容器因为 Docker manifest digest 多架构缓存冲突失败；验证脚本已调整为固定宿主架构，等待后续运行。

## preview.4 完全访问与历史搜索（2026-10-24）

针对手机已支持 Shizuku 却无法在完全访问档发送，以及历史会话搜不到，接通官方 UserService 的真实 Android `system_shell` Cordis 工具，修复原版 session/list 命名参数、标题投影、工作区恢复与全文搜索配置。原版 dsh `0.2.1-rc.2`、JSONL 持久化、SessionStore 均保持原样；KernelLauncher 与 Cordis seam 可替换。

双 ABI 本地构建和单测成功，arm64 109,495,566 字节、x86_64 114,387,692 字节，两个包原签名核验成功。桌面原版 dsh 8 轮回归成功，原生最终回归 150.168 秒、`OK (6 tests)`，涵盖系统 UID、拒绝/超时/取消和冷重启归档正文搜索。完整过程、失败记录、SHA256 和边界见 [本次修复报告](m1-preview4-fix.md)。CI 未有本次提交全绿记录时保持未验证；不将修复测试包称为完整 M1 通过。

公开测试包 [preview.4](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/tag/v0.1.0-m1-preview.4)，源码 `e6932a4cb93379f59c8b1ffa3b040e29cd5191e4`；两个 ABI 和原始验证输出、SHA256SUMS 已上传，服务端 digest 均与本地一致。

## 声明式 UI 与回滚：后续开发快照

已将现有 Cordis UI 覆盖接到 HarnessClient 的类型化流，原生界面实际应用强调色/字号/标语/胶囊/自定义命令，以及现有侧栏分组内的顺序与显隐。内核设置页显示实际快照并确认恢复；非法覆盖退回原始 UI 并提示。此部分属于后续版本，**不包含在已发布 preview.4 中**。

| 检查 | 实测结果 |
|---|---|
| 双 ABI assembleDebug、单测、仪器 APK | `.research/m1-ui-customization-build.log`，成功 3m34 |
| 共享主题修改后的截图、复制、PTY、长图及双协议聊天 | `.research/m1-ui-customization-first-attempt.log`，对应两项通过；该轮第三项因测试误选屏幕外搜索框失败 |
| 可见输入框定位修正、独立 provider 夹具的测试 APK | `.research/m1-ui-customization-test-build.log`，成功 38s |
| 实际窗口颜色、胶囊/命令、隐藏侧栏、原生回滚、非法配置回退 | `.research/m1-ui-customization-native.log`，40.898 秒、`OK (1 test)` |

完整 M1AcceptanceTest、安全模式、文件管理/终端补项及指标仍未完成，继续实施。没有新的整工作流结果就不记本阶段 CI 通过。

## preview.5：用户追加的体验修复

用户反馈其余功能正常，要求修复上下文、思考/终端折叠及所有已实现协议的 max。新增体验仪器用例已通过实际 max 请求、32K 预算、原版 token-meter 上下文、完整思考和终端折叠检查；同轮聊天切换测试卡在长模型列表的定位，已修正脚本，仍不能记整套仪器通过。

最终双 ABI 本地构建与单测成功（`.research/m1-max-final-build.log`）；原版 dsh 11 轮验证三类 max 线路、工具与真实投影（`.research/m1-max-node-test.log`）。详见 [修复报告](m1-max-fix.md)。版本 104，沿用原签名；arm64 109,620,802 字节，x86_64 114,512,420 字节，完整 SHA256 见 `m1-apk-sizes.json`。

CI `40075673848` 已解决 KVM 权限问题并运行全部 7 个用例，仍有剪贴板焦点、重复内容 Diff 和 Shizuku 授权对话框定位相关的 3 项失败；报告保留失败状态。测试脚本已针对真实焦点、实际文件修改及真实 SDK Ready 状态修正，新增体验用例将纳入 8 项回归，等待新运行，不记全绿。

## 本轮收工交接

按用户最后要求，发布当前源码并停止后续开发。相同 preview.5 APK 的本地现有 8 项回归最终通过，226.002 秒；远端 `40077416444` 仍有两项仪器失败，不记完整 CI 或 M1 通过。最终下载、哈希、公开验证记录和未完成清单统一见 [交付报告](delivery-report-2026-10-24.md)。
