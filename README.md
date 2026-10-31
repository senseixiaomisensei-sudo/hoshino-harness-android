# Hoshino Harness · Android

开源原生 Android AI Agent：Kotlin Compose 界面 + 原版 DeepSeek Harness（DSH）/ Cordis 内核，本地运行，使用你自己的模型 API Key。MIT。

## 最新版本：0.2.1-m2.9.2（2026-10-31）

包名 `dev.hoshino.app`，versionCode 215，沿用原证书，**直接覆盖安装，配置与会话保留，不要卸载**。

- [下载 arm64-v8a APK](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/download/v0.2.1-m2.9.2/Hoshino-Harness-0.2.1-m2.9.2-arm64-v8a.apk)（非调试 fast 构建）
- [全部产物、SHA256 与 Release 说明](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/tag/v0.2.1-m2.9.2)

主要内容（详见 [docs/m2.9.2-noapproval-preview.md](docs/m2.9.2-noapproval-preview.md)）：
- **无审批 = 彻底无审批**（替代 M2.8 规则）：全部审批入口自动放行并审计；子 Agent 继承无审批；付款 / 密码 / 验证码 / 人机验证在无审批下不再停下，只记审计。默认仍是手动审批。
- **虚拟屏预览**：缩放 64dp–近全屏、44dp 手柄、捏合、尺寸记忆、缩放复位；显示与触摸同一矩阵（≤1px）；单击开控制面板 / 双击最小化（「发送此刻屏幕」已移到面板按钮）；横屏 / 竖屏 / 跟随 App（首次需在「设置 → 设置手机操作」重连助手）；触摸即接管、停手 4 秒自动交还。

需小米 15S Pro 真机验收（CI 未覆盖）：上述无审批与预览各项。CI 已验证：编译与单测、评测门、arm64 真内核启动、x86_64 模拟器覆盖安装保留会话与配置、聊天与工具调用。

上一版 [0.2.1-m2.9.1](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/tag/v0.2.1-m2.9.1)（2026-10-31，热修复：会话被占用与内核断线自动恢复）：arm64 APK 77,150,769 字节，SHA-256 `d3deeb296942ce8f54b904c67ef509dd030afda7f22f8da7fd61030ed298e958`。

[0.2.1-m2.9](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/tag/v0.2.1-m2.9)（2026-10-31：上下文循环与压缩、评测门、复合读工具、剪贴板打字、首配与无障碍自愈、悬浮球面板与接管、帧率可调、国内不开代理连接修复）：arm64 APK 77,116,309 字节，SHA-256 `d84bfba7e388c70e980c9069944394c129cb6cf3f04019c43db4939f7c8658ad`。

[0.2.1-m2.8.2](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/tag/v0.2.1-m2.8.2)（2026-10-31，热修复：`phone_*` 工具加载、主屏命令守卫、虚拟屏任务保活）：arm64 APK 75,924,673 字节，SHA-256 `f69a949734c5acbec591fd81d64e5175cc08bd89c6462a18287c335d0c751e80`。

[0.2.1-m2.8.1](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/tag/v0.2.1-m2.8.1)（2026-10-31，热修复：悬浮胶囊稳定、系统命令权限与报错）：arm64 APK 75,881,665 字节，SHA-256 `dd05836796d1fca75c9093004cc1efa918966efe5e8d9058df151b8f565cbfb3`。

[0.2.1-m2.8](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/tag/v0.2.1-m2.8)（2026-10-31）：**手机操作**——后台虚拟屏、无障碍读屏、开源 OCR、接管与悬浮胶囊，审批沿用 m2.7 审批档；清单见 [docs/m2.8-phone-acceptance.md](docs/m2.8-phone-acceptance.md)。arm64 APK 75,818,353 字节，SHA-256 `33e6ff3f1e78ecd3516afe9fa16ccf41c5caa687515c46f674fe11223cabbacc`。

构建与发布：推送 `codex/dsh-upgrade` 触发 `.github/workflows/dsh-upgrade.yml`，含 arm64 真内核启动、x86_64 覆盖安装 + 聊天 + 工具调用验收后自动发布。

[0.2.1-m2.7](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/tag/v0.2.1-m2.7)（2026-10-31）：流畅度、无审批档、插话与停止交代、会话管理、与 Mika 相同的 ChatGPT 登录、模型自带联网搜索。arm64 APK 68,189,245 字节，SHA-256 `67c1340cd44bd03fbdc215833b491ecf6dbcda840b412c2e69892b744a731241`。

## M2（2026-10-27，历史）

M2 版本为 **0.2.0-m2**，包名 `dev.hoshino.app`，保持原证书。APK 由 GitHub Actions 构建。保留旧 M1 记录供追溯；M2 的实现、未完成项和验证以 [M2 报告](docs/m2-report.md) 为准，不把原型页面或 boot 成功算作全量验收。

- [M2 手机 APK（arm64-v8a）](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/download/v0.2.0-m2/Hoshino-Harness-0.2.0-m2-arm64-v8a-debug.apk)
- [M2 全部产物与 SHA256](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/tag/v0.2.0-m2)
- [插件兼容记录](docs/m2-plugin-compat.md) · [资源/体积](docs/m2-slim.md) · [设备清单](docs/m2-device-checklist.md) · [指标](m2-metrics.json)

安装：下载匹配 ABI 的 APK 覆盖安装，无需卸载旧版。设置 → M2 提供审批、模式、系统提示词、UI DSL、搜索、插件、配置同步和源码构建。模型使用用户自己的供应商；免费联网搜索无需额外 Key。

自身源码构建：填写自己的 GitHub token 和仓库，将原签名密钥放入仓库 Secret `HOSHINO_DEBUG_KEYSTORE`；使用 `m2.yml` 工作流。Token 加密保存在 Android Keystore。`self_build` 提交新分支，`self_update` 跟进同一提交的已通过构建并核验下载产物。签名不符的包会被拒绝。

开发：`npm --prefix node-host ci --ignore-scripts`、`npm --prefix node-host run build`、`npm --prefix node-host test`；Android 构建见 `.github/workflows/m2.yml`。dsh 固定原版 `0.2.1-rc.2`，主 UI 保留 Compose ZIP 的组件和 Token；不 fork 内核。

以下为 M1 历史交付说明。

原生 Compose Android 宿主，原版 DeepSeek Harness 0.2.1-rc.2 + Cordis。
当前交付为 **M1 preview.5 测试版**，使用原生 Kotlin / Compose / Material 3 界面；模型、工具和手机能力通过原版 dsh / Cordis 接入。本轮已按用户要求发布源码并收尾，完整 M1 里程碑仍有未完成项。

- [手机 APK（arm64-v8a）](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/download/v0.1.0-m1-preview.5/Hoshino-Harness-0.1.0-m1-arm64-v8a-debug.apk)
- [全部 APK、SHA256 与验证记录](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/tag/v0.1.0-m1-preview.5)
- [本轮交付报告](docs/delivery-report-2026-10-24.md) · [M1 过程记录](docs/m1-report.md) · [M0 历史基线](docs/m0-report.md)

测试版包含双协议流式聊天与思考、工具审批、工作区读写、终端、Shizuku 系统命令、历史正文搜索、声明式 UI 覆盖和配置快照回滚。preview.5 修正 max 的真实请求与预算，思考全文可折叠，终端默认折叠，上下文展示原版 token-meter 数据。

同一发布 APK 的本地现有 8 项仪器回归通过（226.002 秒），原版 dsh 11 轮集成通过。交付时远端 CI 未全绿，后续状态以 [Actions](https://github.com/senseixiaomisensei-sudo/hoshino-harness-android/actions) 为准。完整 M1AcceptanceTest、真机量化与部分高级功能尚未完成，具体边界见交付报告。旧 preview.1 保留为历史故障复现，安装请使用 preview.5。

## 构建

需要 JDK 17、Android SDK `platforms;android-37.0`、Android NDK `27.3.13750724`、Node 24、Python 3、Docker（跨架构需 QEMU）、curl、make。proot 的可复现交叉编译在 Linux 上执行，Windows 可使用 CI 生成的运行资产再本地构建 APK。

```sh
cd node-host
npm ci --ignore-scripts
npm run build
npm test
cd ..
python3 rootfs/prepare.py
python3 rootfs/verify-packed.py
./gradlew assembleDebug test :app:assembleX86DebugAndroidTest
```

`prepare.py` 校验已锁定的 Termux 依赖与 proot 源码 SHA256，从固定提交编译独立 proot，构建 arm64-v8a / x86_64 Alpine，生成 APK 资产及独立 native executables。**不要只编译 UI 空壳就视为可运行 APK。** 若下载端移除了锁定包，构建应失败，不能自动取未锁定替代版本。`--lock` 仅用于有意更新并审查 `termux-lock.json`。

Windows 中文目录运行 Gradle 测试时须使用 ASCII 路径别名，例如空闲盘符 `subst H: E:\手机端Agent` 后从 `H:\` 执行 `gradlew.bat`。这不复制或移动源码。

## M1 原生冒烟（完整验收仍在补齐）

```sh
node tools/mock-llm/server.mjs
# 在另一个终端，启动 API 36 的 AOSP x86_64 模拟器后：
adb reverse tcp:8099 tcp:8099
./gradlew :app:connectedX86DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.hoshino.harness.M1UiSmokeTest
```

M1 冒烟覆盖 APK 自带内核、真实 PTY、密钥保护、复制、截图与长图；它不代替完整 M1AcceptanceTest。开发流程见 `.github/workflows/m1.yml`。旧 M0 测试界面已替换，旧 M0 仪器类须迁移后才能重新使用。测试不会把桌面内核转发进手机；`adb reverse` 只连接 mock 模型。正式应用直接连接用户接口。

## 使用

首次启动解压 rootfs，前台服务启动内核。设置里添加 provider、协议、base URL、模型和自己的 key。Anthropic 默认 `https://api.anthropic.com`，OpenAI 兼容的 `/v1` 根地址依服务约定填写。等待“已连接”后聊天。发给已启用模型服务商的请求不单独审批（自 M2.1 起，添加并启用服务商即视为授权）；会改文件、执行 shell、联网抓取等工具调用默认审批，「无审批」档只能由用户在设置或会话中主动开启。终端、文件和 self 的能力边界以交付报告为准。

不要将服务、密钥、用户 workspace 或 `.research` 缓存提交到仓库。JSON 中的 `apiKey` 是 Keystore 凭据引用。无遥测插件、无 Google Play Services、无远程业务后端。

[架构决定](docs/decisions.md) · [插件 DIY](docs/plugins.md) · [第三方许可](THIRD_PARTY.md)

## ABI 包与量化验收

`assembleDebug` 生成 arm64 和 x86 两个 flavor，每包只含对应的 native 库和一个 xz rootfs。产物分别在 `app/build/outputs/apk/arm64/debug/`、`app/build/outputs/apk/x86/debug/`。`python3 tools/check-apks.py` 记录实际字节数、SHA256、最大文件及 150 MB 目标结果。

`rootfs/pack.py` 按锁定依赖图选择运行闭包；M1 包含源码编译的 musl node-pty 及 Sharp/libvips。仅移除包根目录的文档/测试目录，不能误删 `yaml/dist/doc` 等运行代码。相同 tar 同时生成 xz 与 gzip 基线。`verify-packed.py` 对打包后的每个 ABI 导入实际组合的全部模块，Android 冒烟再验证 APK 内执行。

`benchmark-driver` 仅为测试伴随应用，不属于 Agent 内核。它在另一个仪器进程中 force-stop Hoshino，实际点击 Launcher 图标 5 次，记录就绪/首个可见文本 token 的时间与中位数，以及每次空闲/流式阶段的 Java 应用、proot、Node PSS 与 Java/Native heap 分类。计时包含输入、审批操作；rootfs 已安装，不等于首次安装解包时间。最终 JSON 位于伴随应用的 `files/m0-metrics.json`，原始 meminfo 在 `files/m0-memory/`，CI 自动导出为 artifact。

真机操作与厂商后台策略记录见 [arm64 清单](docs/m0-device-checklist.md)。模拟器指标只代表其记录的 ABI、API 与测试条件。
