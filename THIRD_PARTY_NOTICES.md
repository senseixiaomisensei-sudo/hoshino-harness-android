# Third-party notices

Hoshino Harness 主项目是 MIT。下面每一段由引入对应代码的分支各自追加；请只追加自己的段落，便于合并。

## Mika（手机操作能力，分支 feat/m2.8-phone）

- 项目：https://github.com/wangzan101/mika （移植基线 0.1.13 / 688f59d）
- 许可：Apache License 2.0，全文见 `licenses/mika-LICENSE`
- NOTICE：

```
Mika
Copyright 2026 wangzan101
```

- 使用方式：直接移植并改写（包名改为 `dev.hoshino.bridge.phone.*` / `app.hoshino.harness.phone.*`）。被移植或改写的文件头部保留：
  `// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.`
- 移植文件清单（Mika 源文件 → 本仓库文件）：
  - `core/adb/{AdbConnectPort,AdbConnection,AdbIdentity,AdbMessage,AdbPairing,AdbTls,Ed25519,LocalPortScan,PairingAuth,Spake2}.kt` → `core-bridge/src/main/java/dev/hoshino/bridge/phone/adb/*.kt`（无线调试配对 SPAKE2、TLS、ADB 连接协议）
  - `core/src/test/.../adb/{CryptoTest,AdbConnectPortTest,LocalPortScanTest}.kt` → `core-bridge/src/test/java/dev/hoshino/bridge/phone/adb/*.kt`
  - `app/cap/phone/shell/adb/{AndroidAdb,NsdAdbServiceFinder}.kt` → `core-bridge/.../phone/shell/{AndroidAdb,NsdAdbServiceFinder}.kt`
  - `core/shell/{HelperHandoff,BuiltinShell,BuiltinShellEngine,PairingScreen,RecoveryPermissions}.kt` → `core-bridge/.../phone/shell/{HelperHandoff,BuiltinShell,BuiltinShellEngine,PermissionCommands}.kt`（精简：去掉 USB 启动脚本，自动打开无线调试合并进引擎）
  - `app/cap/phone/shell/{HelperProcess,HelperHub,ShellService}.kt`、`app/cap/phone/setup/BuiltinShellManager.kt` → `core-bridge/.../phone/shell/{HelperMain,HelperHub,PhoneShellService,BuiltinShellManager}.kt`（ShellService 精简为本项目需要的接口，并可同时由 Shizuku UserService 承载）
  - `app/cap/phone/shell/ShellContext.kt` → `core-bridge/.../phone/shell/ShellContext.kt`（该文件同时保留 scrcpy 出处，见下）
  - `core/uistate/*`（ScreenSnapshot/UiElement 模型）、`core/action/IrreversibleDetector.kt`、`core/uistate/LaunchChooser.kt` 与 `MainScreenSpill.needsUser` 的判断 → `app/src/main/java/app/hoshino/harness/phone/{ScreenModel,IrreversibleDetector,SystemScreens}.kt`（及 `app/src/test/.../IrreversibleDetectorTest.kt`）
  - `app/cap/phone/a11y/ScreenAccessibility.kt` → `app/.../phone/PhoneAccessibilityService.kt`
  - `app/cap/phone/a11y/ScreenReader.kt` → `app/.../phone/AccessibilityReader.kt`
  - `app/cap/phone/a11y/AndroidScreen.kt`（点按/输入/滚动/开 App 的底层操作；去掉所有拟人节奏与抖动）→ `app/.../phone/AndroidPhoneScreen.kt`
  - `app/cap/phone/display/{AgentDisplay,FrameMath}.kt` → `app/.../phone/{AgentDisplay,FrameMath}.kt`
  - `core/uistate/LimitNotice.kt`、动作效果判定 → `app/.../phone/ActionEffect.kt`
  - `app/cap/phone/shell/AudioMuteLedger.kt`、`app/task/TaskRecovery.kt`（思路）→ `app/.../phone/PhoneLedger.kt`
  - `app/takeover/Takeover.kt`、`TakeoverQueue` → `app/.../phone/PhoneTakeover.kt`
  - `app/capsule/*`（CapsuleController、CapsuleDock、DoubleTap、CapsuleFade 的行为）、`app/cap/phone/a11y/OverlayHost.kt` → `app/.../phone/PhoneCapsule.kt`（改用普通 View 绘制）
  - `app/cap/phone/setup/{PairingCoordinator,PairingCodeReader,PairingNotifier}.kt` → `app/.../phone/setup/Pairing.kt`
  - `core/shell/PhoneSetupFlow.kt`、`app/cap/phone/setup/DeveloperSettings.kt` → `app/.../phone/setup/PhoneSetupFlow.kt`
  - `app/ui/AgentPip.kt`、`PipPhysics.kt` → `app/src/main/java/app/hoshino/harness/ui/chat/PhonePip.kt`
  - 没有移植：`core/cap/phone/{GesturePacing,TypingPacing,AppPacing}.kt`、`core/uistate/IdleScroll.kt`（规避式拟人，mika-gap §2 PL4）；`core/shell/WirelessDebuggingAutoOn.kt` 的静默开启与 `RecoveryPermissions` 的静默授权改为向导里逐项同意（PL3）；ML Kit 的 `PhoneOcr` 换成下面的开源 OCR（PL1）。

## scrcpy（经由 Mika 的 ShellContext）

- 项目：https://github.com/Genymobile/scrcpy
- 许可：Apache License 2.0（Copyright (C) 2018 Genymobile, Copyright (C) 2018-2024 Romain Vimont）
- 使用方式：以 shell 身份取得系统 Context（ActivityThread、伪装 com.android.shell 包名）的做法仿照 scrcpy。源文件头部保留 scrcpy 的版权与许可声明：`core-bridge/src/main/java/dev/hoshino/bridge/phone/shell/ShellContext.kt`。

## ONNX Runtime（手机文字识别推理，分支 feat/m2.8-phone）

- 项目：https://github.com/microsoft/onnxruntime ，Maven `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`
- 许可：MIT（Copyright (c) Microsoft Corporation）
- 使用方式：作为依赖库链接（`app/build.gradle.kts`），只在本机推理，不联网、无遥测。APK 体积代价见 `docs/m2.8-phone-acceptance.md`。

## PaddleOCR PP-OCRv4 模型（经 RapidOCR 导出的 ONNX，分支 feat/m2.8-phone）

- 模型：PaddleOCR PP-OCRv4 mobile 检测 `ch_PP-OCRv4_det_infer.onnx`、识别 `ch_PP-OCRv4_rec_infer.onnx`（https://github.com/PaddlePaddle/PaddleOCR ，Apache License 2.0）
- ONNX 导出与托管：RapidOCR（https://github.com/RapidAI/RapidOCR ，Apache License 2.0），固定到 https://huggingface.co/SWHL/RapidOCR 的提交 `1cfba2e90fc938db55889873735088de210cc173`
- 使用方式：**不随 APK 分发**；用户在「设置手机操作」里点下载时才获取，按 SHA-256 校验（det `d2a7720d…f49da9`，rec `48fc40f2…83683b`，完整值见 `app/src/main/java/app/hoshino/harness/phone/ocr/OcrModels.kt`）。字符表取自识别模型自带的 `character` 元数据。
- 本仓库实现的前后处理（DB 后处理、CTC 解码、缩放归一化）按 PaddleOCR/RapidOCR 公开的参数独立编写：`app/src/main/java/app/hoshino/harness/phone/ocr/OcrPipeline.kt`。
