# Hoshino Harness · Android

原生 Compose Android 宿主，原版 DeepSeek Harness 0.2.1-rc.2 + Cordis。
当前在开发 M1，合入用户提供的原生 UI，持续接入终端、文件、截图、附件与 self 插件。完整 M1 尚未验收；进度和证据见 [M1 报告](docs/m1-report.md)，历史基线见 [M0 报告](docs/m0-report.md)。

**暂缓使用 preview.1：** 已确认其打包过程漏掉 YAML 运行代码，模拟器内核启动失败。Release 保留复现用产物并标记警告；修复版本正在重新安装验证。不要把开发 CI 全绿当作完整 M1 通过。

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

首次启动解压 rootfs，前台服务启动内核。设置里添加 provider、协议、base URL、模型和自己的 key。Anthropic 默认 `https://api.anthropic.com`，OpenAI 兼容的 `/v1` 根地址依服务约定填写。等待“已连接”后聊天；模型网络请求与 Agent 写操作默认审批。M1 终端/文件/self 仍在联调，未接通的扩展以报告为准。

不要将服务、密钥、用户 workspace 或 `.research` 缓存提交到仓库。JSON 中的 `apiKey` 是 Keystore 凭据引用。无遥测插件、无 Google Play Services、无远程业务后端。

[架构决定](docs/decisions.md) · [插件 DIY](docs/plugins.md) · [第三方许可](docs/third-party.md)

## ABI 包与量化验收

`assembleDebug` 生成 arm64 和 x86 两个 flavor，每包只含对应的 native 库和一个 xz rootfs。产物分别在 `app/build/outputs/apk/arm64/debug/`、`app/build/outputs/apk/x86/debug/`。`python3 tools/check-apks.py` 记录实际字节数、SHA256、最大文件及 150 MB 目标结果。

`rootfs/pack.py` 按锁定依赖图选择运行闭包；M1 包含源码编译的 musl node-pty 及 Sharp/libvips。仅移除包根目录的文档/测试目录，不能误删 `yaml/dist/doc` 等运行代码。相同 tar 同时生成 xz 与 gzip 基线。`verify-packed.py` 对打包后的每个 ABI 导入实际组合的全部模块，Android 冒烟再验证 APK 内执行。

`benchmark-driver` 仅为测试伴随应用，不属于 Agent 内核。它在另一个仪器进程中 force-stop Hoshino，实际点击 Launcher 图标 5 次，记录就绪/首个可见文本 token 的时间与中位数，以及每次空闲/流式阶段的 Java 应用、proot、Node PSS 与 Java/Native heap 分类。计时包含输入、审批操作；rootfs 已安装，不等于首次安装解包时间。最终 JSON 位于伴随应用的 `files/m0-metrics.json`，原始 meminfo 在 `files/m0-memory/`，CI 自动导出为 artifact。

真机操作与厂商后台策略记录见 [arm64 清单](docs/m0-device-checklist.md)。模拟器指标只代表其记录的 ABI、API 与测试条件。
