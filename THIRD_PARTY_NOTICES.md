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
  - 其余手机能力文件在后续提交中追加到本清单。

## scrcpy（经由 Mika 的 ShellContext）

- 项目：https://github.com/Genymobile/scrcpy
- 许可：Apache License 2.0（Copyright (C) 2018 Genymobile, Copyright (C) 2018-2024 Romain Vimont）
- 使用方式：以 shell 身份取得系统 Context（ActivityThread、伪装 com.android.shell 包名）的做法仿照 scrcpy。源文件头部保留 scrcpy 的版权与许可声明：`core-bridge/src/main/java/dev/hoshino/bridge/phone/shell/ShellContext.kt`。
