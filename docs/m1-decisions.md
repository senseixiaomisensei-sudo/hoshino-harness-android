# M1 自主决定

1. **M0 门槛按本次任务书执行**：完整全绿运行 40050125544，提交 c171800decf633d1c012cc8516a22e69a4e0ce7f；自动指标、ABI 分包与真机清单已存在。本次任务书要求的四项软件门槛满足，继续 M1；不将此前未签认的真机条目补记为通过。
2. **交付采用 GitHub Release 直链**：沿用现有公开源码仓库，无新后端、付费存储或手机读取本地路径；发布前核对 APK 签名、字节数、SHA256 与 HTTP 下载响应。
3. **ZIP 原件只读**：在 .research/m1-ui-input 中检查副本，按 README 方式 B 合入工程；SessionStore 保持原样。ZIP 未包含其引用的 design/SPEC.md，以实际交付的 README、Token、组件、路由、资源和源代码作为可核对依据，不编造缺失规范。
4. **保持已有应用标识便于升级**：UI 源码和 namespace 使用 ZIP 的 app.hoshino.harness；applicationId 保留 dev.hoshino.app，沿用现有签名与私有数据，避免新包导致会话和 Keystore 配置分离。核心桥接包与 KernelLauncher seam 不改名。
5. **先保留已验证构建链**：不因 ZIP README 的工具版本声明就升级现有 AGP、Gradle 或 dsh。先用实际可构建的工具链合并；确有 API 兼容问题再做必要调整，并记录验证。
6. **M1 在 codex/m1 分支实施**：保留已验收的 M0 主分支，M1 的完整 UI 替换、运行时扩展与测试在独立分支迭代；不重置或移动用户未跟踪的原始 ZIP 与任务书。
7. **截图与复制先修复**：移除旧 Activity 即移除了它的全局 FLAG_SECURE；ZIP 的消息默认没有文本选择容器。只在 provider 密钥编辑页面生命周期启用 FLAG_SECURE，复制通过原 ZIP helper 扩展，统一脱敏与 Android 13 敏感标记。
8. **开发 CI 不等于验收通过**：开发阶段先验证新增 musl PTY、Sharp 的两个 ABI 构建并上传带版本名的开发 APK；完整 M1AcceptanceTest、截图及指标尚未执行时，报告明确标记未完成。最终交付必须补成完整无条件的仪器验收流水线。
9. **复用原版 shell 与 PTY 分工**：Agent 的 bash 使用原版 tool-bash + bash-local，以保留超时、取消和溢出文件；交互终端复用原版 api-terminal-controller / subprocess-local 的 PTY。android-subprocess 仅继承原版 provider，将 spillDir 配置到私有 workspace/.hoshino-output，不改上游执行代码。
10. **锁定完整 Node 编译依赖**：开发运行 40056749850 中 arm64 的 PTY/Sharp 容器自检成功，x86_64 因 nodejs-dev 24.18.1 要求同版本 nodejs-libs，而仓库索引默认选择 24.22.1 失败。补齐官方同版本 libs APK 的 URL、SHA256 与长度，不升级 Node 或跳过签名。删除编译依赖后再测 PTY/Sharp；npm 用 apk del 正常卸载，避免后续按需安装看到损坏的包数据库。
11. **原生运行时按 ABI 独立缓存与上传**：两种架构分开构建，单个 ABI 失败不会丢失另一架构的构建产物。最终打包必须下载两份、刷新本项目 host/plugins 并重算校验；缓存不作为 Android 验收通过的证据。
12. **12 点前先交付公开测试 APK，之后继续施工**：按用户最新时间要求，先发布带 0.1.0-m1 版本名的可安装测试包及下载直链；未完成完整 M1AcceptanceTest 前明确标为预发布，不写 M1 验收通过。用户明确要求交付后不停工，继续完成剩余功能和测试。
13. **自述手册取自真实组合**：build 脚本在无凭据的本地 dsh 实例里读取实际 boot rows 和 ctx.tools.schemas()，结合 ZIP 路由、主题字段、事件源码生成手册。self 写操作和用户插件通过独立 Cordis 插件实施，不修改内核源码或 JSONL 持久化。
14. **附件复用原生 dsh 入站接口**：图片使用 session/prompt 的原生 image 内容块；普通文件走 /api/session/uploadFileBinary 和 file receipt。Android 保留原图，发送图长边不超过 1568。截图使用 PixelCopy，密钥页和后台调用拒绝；不申请录屏或全盘存储权限。
15. **路径验证以单测为准，不跳过失败**：午间打包已产出 APK，但 Windows 的 File.canonicalFile 没有按预期解析目录链接，符号链接拒绝用例失败；发布流程因此停止。补上逐祖先的 NIO isSymbolicLink 拒绝，再重跑构建和测试。Linux 开发 CI 全绿不能替代本地失败记录。
