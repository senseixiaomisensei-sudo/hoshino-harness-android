import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True

size_line=''
sizes=pathlib.Path('artifacts/docs/dsh-upgrade-apk-sizes.json')
if sizes.exists():
 arm=next((r for r in json.loads(sizes.read_text()) if r.get('abi')=='arm64-v8a'),None)
 if arm:
  delta=arm['bytes']-68189245
  size_line=f"\n\n本版 arm64 APK 实测 {arm['bytes']:,} 字节（m2.7 为 68,189,245 字节，{'+' if delta>=0 else ''}{delta:,} 字节），SHA-256 `{arm['sha256']}`。"
text='''## Hoshino 0.2.1-m2.8

包名 `dev.hoshino.app`，versionCode 210（高于 m2.7 的 209），沿用原证书（SHA-256 `e20b0b97…8fd1`），**直接覆盖安装，配置与会话保留，不要卸载**。手机请装 `Hoshino-Harness-0.2.1-m2.8-arm64-v8a.apk`（非调试 fast 构建）；`x86_64-debug` 仅供模拟器验收。

### 新功能：手机操作（m2.8）

让 Hoshino 在**后台虚拟屏**上替你操作 App，你可以照常用主屏。

- **设置向导**「设置 → 设置手机操作」：版本检查 → 同意页 → 开发者选项 → 配对 → 无障碍 → 文字识别（可选），附 HyperOS 提示和「复制诊断」（只含开关状态，不含屏幕内容）。
- **内置手机助手**：移植 Mika 的无线调试配对与 ADB 连接（SPAKE2 配对码、TLS），配对后助手以 shell 身份运行；已装并授权 Shizuku 时直接走 Shizuku。
- **后台虚拟屏 + 无障碍读屏**：压缩的元素列表、稳定的 ref、只返回变化的差异读屏；一次可连续执行 1–20 步并由代码等待（文字出现/消失、App 在前台、画面停稳），少走模型来回。
- **开源文字识别**：ONNX Runtime（MIT）+ PaddleOCR PP-OCRv4（Apache-2.0），只在界面树缺字时补读，完全在本机运行；模型**不随 APK**，在向导里点下载时才获取（约 15.6 MB）并按 SHA-256 校验。不使用 ML Kit / Google Play 服务 / Firebase。
- **接管**：登录、验证码、密码、付款等交给你：通知和胶囊里「我来处理 / 处理好了 / 跳过」，App 可在后台屏和主屏之间挪动；「跳过」不会被当成已完成。
- **悬浮胶囊**：任务运行时出现，可看小画面、最近几步、停止任务、审批、回答问题、插话；双击把当前屏幕发给对话。对话页右上角有画中画小窗。
- **插件** `plugins/android-phone`：`phone_status / phone_observe / phone_list_apps / phone_tap / phone_type_text / phone_scroll / phone_swipe / phone_back / phone_launch_app / phone_do_steps / phone_screenshot / phone_handoff / phone_finish` 等，全部经过同一个 Android 执行入口。

### 权限与安全

- **同意页所有项默认全不勾**：自动开启无障碍、授予 WRITE_SECURE_SETTINGS、电池优化白名单、自动打开无线调试（另需 WRITE_SECURE_SETTINGS）、后台屏静音、允许操作 Hoshino 自己。没点「保存并应用」之前不改任何系统设置；每次自动改动写入本机审计 `phone-audit.log`（不含屏幕内容）。
- 不做 Mika 的「像人」规避手段（假装阅读的空闲滚动、落点抖动等）。
- **审批**（沿用 m2.7 的审批档，出厂默认手动）：
  - 首个 phone_* 工具弹审批卡，可选「本会话总是允许」。
  - 发送、删除、下单、提交这类**不可逆步骤**按「这一步 + 这个屏」单独再问一次（只有「允许一次 / 拒绝」，聊天卡、通知、胶囊一致）；开着「高危确认」时自动档也会问。
  - 你主动开启「无审批」时，不可逆步骤也直接执行，每一步写审计（只记类型和 App 包名）；你自己写的拒绝规则仍生效。
  - **付款、密码、验证码、人机验证永远交给你亲手完成**，任何审批档（包括无审批）都绕不过。
- 其他：修复遗留 `m2.yml` 每次推 `codex/m2` 都去重建旧 Release 的问题；升级验收的种子测试不再依赖数据类构造器。

### APK 体积代价

ONNX Runtime 的原生库让 arm64 APK 增加约 6.6 MB（安装后解压约 +18.3 MB）；OCR 模型按需下载，不计入 APK。{SIZE_LINE}

### 需要小米 15S Pro 真机验收（CI 未覆盖）

清单见仓库 `docs/m2.8-phone-acceptance.md`，主要项：
- 向导：同意页默认全不勾、未保存前系统设置不变、HyperOS 提示、复制诊断不含屏幕内容。
- 无线调试配对（自动读配对码 / 通知里输入）、首次配对耗时、杀进程后助手自动重连、重启后无线调试保持关闭（除非勾了自动打开）、Shizuku 路径。
- 后台虚拟屏：主屏不被打断、Android 14+ 打字不丢字、建屏耗时、差异读屏、OCR 补字与耗时、防截屏页处理、OCR 运行时零出站（抓包）。
- 各类动作、输入恢复剪贴板、不操作 Hoshino 自己、锁屏不执行、30 分钟后台任务不被 Doze 打断。
- 审批：默认手动下的首次审批与「发送」单独确认、拒绝后不重试、付款页交给你、自动档与无审批下的行为和审计行。
- 接管（挪屏、处理好了/跳过、排队）、静音与恢复、悬浮胶囊（淡出、贴边、审批与插话、双击发屏幕）与画中画的掉帧/内存。
- 实测 APK 与安装后体积增量。

### 已知限制

- 以上手机操作**全部未在真机上验证**；CI 只证明能编译、单测通过，以及 x86_64 模拟器从旧版覆盖安装保留会话与配置、聊天与工具调用、arm64 真内核能启动。
- CI 的覆盖安装验收是从 0.2.0-m2（vc200）直接覆盖到本版，没有单独跑「m2.7 → m2.8」这一跳；本版没有新的数据迁移（手机操作只新增自己的偏好与审计文件）。
- 手机操作需要 Android 13 及以上；HyperOS 上无线调试与「USB 调试（安全设置）」需按向导提示手动打开。
- Android 系统自身的确认（安装 APK、运行时权限、Shizuku 授权）无法被「无审批」绕过。
'''.replace('{SIZE_LINE}',size_line)
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
