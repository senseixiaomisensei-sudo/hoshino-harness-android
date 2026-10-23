# Hoshino Harness · Android M0

原生 Compose Android 宿主，原版 DeepSeek Harness 0.2.1-rc.2 + Cordis。
仅验证本地无头内核、Anthropic / OpenAI 兼容流式工具会话。进度及已知失败见 [M0 报告](docs/m0-report.md)。

## 构建

需要 JDK 17、Android SDK `platforms;android-37.0`、Node 24、Python 3、Docker（跨架构需 QEMU）、curl。

```sh
cd node-host
npm ci --ignore-scripts
npm run build
npm test
cd ..
python3 rootfs/prepare.py
./gradlew assembleDebug test
```

`prepare.py` 校验已锁定的 Termux deb SHA256，构建 arm64-v8a / x86_64 Alpine，生成 APK 资产及独立 native executables。**不要只编译 UI 空壳就视为可运行 APK。** 若下载端移除了锁定包，构建应失败，不能自动取未锁定替代版本。`--lock` 仅用于有意更新并审查 `termux-lock.json`。

Windows 中文目录运行 Gradle 测试时须使用 ASCII 路径别名，例如空闲盘符 `subst H: E:\手机端Agent` 后从 `H:\` 执行 `gradlew.bat`。这不复制或移动源码。

## 仪器验收

```sh
node tools/mock-llm/server.mjs
# 在另一个终端，启动 API 36 的 AOSP x86_64 模拟器后：
adb reverse tcp:8099 tcp:8099
./gradlew connectedDebugAndroidTest
```

测试实际在后台等待五分钟。完整 GitHub Actions 流程见 `.github/workflows/m0.yml`，失败时也上传 APK 与测试报告。测试不会把桌面内核转发进手机；`adb reverse` 只连接 mock 模型服务。正式应用直接连接用户指定的模型接口。

## 使用

首次启动会解压内置 rootfs，前台通知持有内核。设置里添加 provider（小写 ID）、协议、base URL、model ID 和自己的 key。默认 Anthropic 地址 `https://api.anthropic.com`；OpenAI 兼容常用 `/v1` 根地址依服务约定填写。等待顶部“已连接”后聊天。每次模型请求和文件修改都需允许；M0 不开放 shell。

不要将服务、密钥、用户 workspace 或 `.research` 缓存提交到仓库。JSON 中的 `apiKey` 是 Keystore 凭据引用。无遥测插件、无 Google Play Services、无远程业务后端。

[架构决定](docs/decisions.md) · [插件 DIY](docs/plugins.md) · [第三方许可](docs/third-party.md)
