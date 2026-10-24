# M1 实现记录（开发中）

尚未完成 M1 验收，不能将开发构建称为正式交付或全量通过。

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
