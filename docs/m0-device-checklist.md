# M0 arm64 真机验收清单

状态：**待真机逐项签认**。x86_64 模拟器结果与“平时使用正常”的反馈分别记录，不能代签本清单。执行人：________　日期：________。

## 设备与安装包

| 项目 | 实测记录 |
|---|---|
| 厂商 / 型号 | |
| ROM 名称与完整版本（HyperOS/MIUI、ColorOS、OriginOS、HarmonyOS/EMUI 等） | |
| Android 版本 / API / 安全补丁日期 | |
| `ro.product.cpu.abilist`，须包含 arm64-v8a | |
| APK 文件名、字节数、SHA256、代码提交 | |
| 电池策略、自启动设置、是否锁屏、是否充电 | |
| provider 协议 / model（不记 API key） | |

安装 `app/build/outputs/apk/arm64/debug/app-arm64-debug.apk`。它只含 arm64 运行环境；普通未 root 手机可执行本清单。首次测试保留厂商默认的后台与电池设置。

## 推荐先跑零费用的同套仪器测试

测试中的模型为本地 mock，内核、原版持久化、设备数据、文件与 Android 服务均真实运行在手机。PC 需要 Android SDK/adb，手机开启开发者选项与 USB 调试，授权本台 PC。

PowerShell，仓库根目录执行；中文目录使用 README 中的 ASCII 路径别名。

```powershell
./gradlew.bat :app:assembleArm64Debug :app:assembleArm64DebugAndroidTest
adb devices
$deviceSerial = '替换为自己的设备序列号'
adb -s $deviceSerial shell getprop ro.product.cpu.abilist
adb -s $deviceSerial shell getprop ro.build.version.sdk
adb -s $deviceSerial install -r app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
$testApk = (Get-ChildItem app/build/outputs/apk/androidTest/arm64/debug/*.apk | Select-Object -First 1).FullName
adb -s $deviceSerial install -r $testApk
adb -s $deviceSerial reverse tcp:8099 tcp:8099
# 另开一个终端运行：node tools/mock-llm/server.mjs
adb -s $deviceSerial shell am instrument -w -e class dev.hoshino.app.M0AcceptanceTest dev.hoshino.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s $deviceSerial pull /sdcard/Android/data/dev.hoshino.app/files/m0-metrics.json
adb -s $deviceSerial pull /sdcard/Android/data/dev.hoshino.app/files/m0-memory.txt
```

- [ ] 输出 `OK (1 test)`；保存原始结果及提交哈希。
- [ ] `measurementAbi` 或设备属性证明确为 arm64-v8a；记录 Android/ROM 条件。
- [ ] `backgroundSeconds=300`、`sessionPreserved=true`、`backgroundContinuation=true`。
- [ ] gzip / xz 比较 `sameTar=true`，两种耗时及字节数已保存。

更换 provider 设置前自行保留原来的配置记录；仪器测试会保存名为 claude / compatible 的测试配置。真实 key 仅在设置 UI 中输入，不放进终端、adb 参数、截图或报告。

## 人工逐项检查

| 项目 | 勾选与实测结果 |
|---|---|
| 完全结束应用进程后，点桌面图标；显示“启动中”后成为“已连接” | [ ] |
| Anthropic 类型：自定义 baseURL、model 与 key；“你好”持续输出，thinking 可展开 | [ ] |
| OpenAI 兼容类型：同样收到流式回复，测试模型的 thinking 可展开 | [ ] |
| 两种协议分别调用 get_device_info，参数、设备型号/系统/电量结果可见，最终答复使用该结果 | [ ] |
| 两种协议分别批准内置 write，workspace/hello.txt 存在且内容正确 | [ ] |
| 修改文件与模型请求均先出现审批，拒绝时不执行相应操作 | [ ] |
| 会话中切换 provider/model，能继续刚才的上下文，工具卡片不重复键崩溃 | [ ] |
| 按 Home 退后台，真实等待 5 分钟，再点图标，可在同一会话继续发消息 | [ ] |
| 单独锁屏 5 分钟后重复上一项，记录与按 Home 的差异 | [ ] |
| 测试 canary 在应用全量存储和当前应用 logcat 中无明文匹配（以仪器断言为证） | [ ] |

真实厂商模型的 thinking 需所选模型本身支持。请分别记下协议、model、时间及结果，不能将 mock 或另一个协议的结果代填。截图须避开 key 输入区。

## 从图标起算的冷启动测量

每次结束应用进程，保留已安装 rootfs 与会话文件，再从桌面图标启动。使用同一模型与网络条件，审批及时点击；首 token 时长包含输入和审批的操作耗时。首次安装解包另记，不能和已解包的进程冷启动混算。

| 次数 | 点图标至内核就绪 ms | 点图标至第一个可见文本 token ms | 异常 / 条件 |
|---|---:|---:|---|
| 1 | | | |
| 2 | | | |
| 3 | | | |
| 4 | | | |
| 5 | | | |
| 中位数 | | | |

CI 的独立 `benchmark-driver` 执行同样的五次图标点击，在 Android 仪器进程内写出 `startup.samples` 和两个 median；它的结果只代表记录的模拟器 ABI。

## 国产 ROM 的 ptrace 与后台限制

先记录默认设置的结果，发生问题后再单独测试系统提供的“电池不限制”等选项。每个条件独立填表。无需 root，也不要关闭 SELinux 或修改系统安全策略。

| 条件 | 默认策略 | 调整电池策略后的复测 |
|---|---|---|
| proot / Node 能启动、dsh 内核已连接 | | |
| 设备工具及 write 能完成 | | |
| Home 后 5 分钟能否续聊 | | |
| 锁屏 5 分钟能否续聊 | | |
| 系统是否结束进程 / 清除前台服务 | | |

出现失败，在状态栏打开最近 50 行分类诊断，保存**原文错误码**与时间：

- `proot: ptrace EPERM`：ptrace 报 Operation not permitted。
- `proot: ptrace EACCES`：ptrace 报 Permission denied。
- `proot: ptrace failed`：确认失败，但未取得上面两种 errno；不要补猜 errno。
- `proot: tracer SIGSYS` / `SIGSEGV`：分别记录信号 31 / 11。
- `内核退出 (数字)`、`dsh: turn failure (errno; operation)`：记录实际退出码、固定 errno 与操作名。
- 应用 / 服务被系统结束：记录发生前是否在前台、是否锁屏、距离退后台的秒数、前台通知状态。重新打开后新建的会话不能算原会话保活成功。

| 失败项 | 日期时间 | 具体分类诊断 / 退出码 | ROM 条件 | 可重复次数 | 证据文件 |
|---|---|---|---|---|---|
| | | | | | |

不要用 run-as 环境替代应用域验证；两者的 SELinux / zygote seccomp 条件不同。向项目提交证据时仅提供必要的分类诊断与统计，勿粘贴真实 key 或用户文件内容。

## 真机签认

- [ ] 所有条目已测，失败项有明确错误和复现步骤。
- [ ] 当前 ROM 默认策略可通过；或明确注明必须调整哪些系统选项。
- [ ] 将以上清单、仪器结果、metrics、版本信息附到本次提交的证据记录。

结论：□ 通过　□ 不通过　□ 未完成。签认人：________。
