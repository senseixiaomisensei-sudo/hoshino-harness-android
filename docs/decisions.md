# M0 自主决定

日期：2026-10-23 至 2026-10-24。仅推进 M0；后续里程碑不在本次范围。

1. **锁定 dsh 0.2.1-rc.2**：`npm view @deepseek-ai/dsh@latest` 实测结果。根包及完整解析树由 `node-host/package-lock.json` 固定。升级须独立提交，不使用版本范围替代根版本。
2. **使用方案 B**：对应发布标签根 `package.json` 要求 Node `^22.19.0 || >=24.0.0`；nodejs-mobile 最新 v18.20.4 不符合。没有重编或修改 dsh 来迁就旧 Node。选择 Alpine 3.23 官方 nodejs 24.18.1-r0，分别打包 arm64 和 x86_64。运行时不需要下载 rootfs 或安装依赖。
3. **原版最小 Cordis 组合**：`dsh-app-boot.boot()` 是 `apps/cli` 同样使用的启动器底层接口；加载原版 `dsh-agent-loop`、`dsh-session-persistence-jsonl`、`dsh-tools`、`dsh-llm-pi-ai` 及 Session Controller。自定义组合减少桌面、浏览器、shell、遥测和市场功能，不修改上游文件，不自建 agent loop。
4. **复用模型适配**：现有 `@deepseek-ai/dsh-llm-pi-ai` 已包含原生 `anthropic-messages` 与 `openai-completions`。不创建重复的 provider 插件。自定义 provider id 映射到适配器 route；Claude 配置 thinking budget 1024，输出上限 4096；OpenAI 兼容接口不强制发送 reasoning effort，以兼容不支持该参数的模型。
5. **原生 UI**：HTML 仅作参考，保留 Hoshino 标题、粉色标记、浅蓝用户气泡、底部圆角输入区、模型菜单、折叠 thinking 与工具卡片。使用 Compose/Material 3；不装 WebView、不执行原型 JS，不包含演示工具结果或无效的插件市场按钮。
6. **凭据引用**：JSON 的 `apiKey` 字段持有 `KEY_<PROVIDER>` 引用。Android Keystore 的非导出 AES key 以 AES-GCM 封装用户 API key；密文存应用私有 preferences。Keystore 不能直接存任意字符串，因而采用此常规封装方式。明文只在内存和鉴权的回环回调中存在，不通过 argv、环境变量、rootfs 或配置文件传递。
7. **控制协议不另造**：Android 使用 dsh 原有 `/api/session/*` Connection RPC、`/api/remote.mux` WebSocket、`session/follow`。仅手机能力回调和 provider 刷新使用独立插件的窄接口。
8. **一次性引导认证**：向 Kotlin 的私有回环回调返回随机端口与随机 token；`/mobile/connect` 消耗 token 一次，再调用 dsh `HostConnectionService.authorizeConnect` 取得它自己的 API Cookie。dsh 内部 launch token 不交给 UI。下次内核启动重新生成全部引导凭据。所有 API/回调仅监听 `127.0.0.1`。
9. **M0 权限最小化**：只注册设备信息、原版 read/write 文件工具。修改默认逐次人工审批；每个模型请求也逐次审批。禁止其他工具；路径检查同时检查规范路径与符号链接，限制 workspace。proot 是兼容运行层，不能宣称为内核级安全沙箱；M0 不开放 shell。
10. **独立 GPL 二进制**：Termux proot 作为 `libproot.so` 独立执行，不使用 JNI 链接。Android APK 的 native 库文件名限制要求将 talloc 文件名规范成 `.so`，仅调整独立 proot 的 DT_NEEDED，不改 dsh。二进制及依赖 SHA256 记录在 `rootfs/termux-lock.json`。
11. **诊断日志**：第三方进程的原始输出可能含凭据或用户内容，因此仅保留分类诊断，最多 50 行；不把原始 stderr 显示到 UI 或 logcat。dsh 的会话历史是功能数据，不作诊断日志输出。
12. **前台运行**：非导出的 specialUse 前台服务持有内核，使用 15 分钟有界 wake lock 验证后台五分钟；不增加开机常驻、自启动或电池优化豁免。会话来源为 dsh JSONL。
13. **Android SDK 使用 API 37**：深入查询 sdkmanager 的平台清单，确认新版包名为 `platforms;android-37.0`，并非 `android-37`。据官方兼容矩阵采用 AGP 9.1.1、Gradle 9.4.1、Kotlin/Compose compiler 2.2.20，targetSdk 37；用显式 minorApiLevel=0 选平台。外部 Kotlin 插件组合显式关闭 AGP 内置 Kotlin 与新 DSL，便于保持两个模块一致。API 36 模拟器仍可验证 minSdk 29 应用。
14. **自动验收不能假通过**：桌面 Node 测试与 APK 仪器测试分层记录；任一基础设施或运行失败都会保留失败状态，不用远程桌面内核、模拟内核或预制 UI 回答替代 APK 内运行。
15. **rootfs 资产用 `.tgz`**：AAPT 对 `.gz` 后缀会自动解压并去掉后缀，实测导致启动时找不到归档。使用 `.tgz` 加 noCompress，启动器继续按 gzip 解包；两个 ABI 的资产检查保留为构建前置条件。
16. **断连诊断只记录结构**：保留 WebSocket 关闭码、异常类、固定插件标识和回调 HTTP 状态。拒绝记录原始关闭原因、错误消息和 RPC 内容，便于区分传输、协议与插件启动失败，同时遵守凭据和用户内容不进日志的要求。

17. **原子解包**：先解包到应用私有 `.installing` 目录，完成后原子改名。重试仅清理这两个固定生成目录，遍历不跟随符号链接，workspace 和会话目录不参与清理。实测中断首次解包后，旧做法会遇到已存在的链接。
18. **Linux 系统调用兼容层**：应用进程内探针证明 musl 的 `rename` 返回 ENOSYS，文件写入及 fsync 正常；run-as 环境不能复现。选用独立 MIT 的小型 LD_PRELOAD 库，把旧式文件操作映射到同语义的 *at 系统调用，仅用于 Linux guest。保留真实原子 rename 和错误返回，不改 dsh、不改 Node，不用复制文件或内存会话规避持久化。

19. **运行环境按归档指纹更新**：打包时生成每个 ABI 的 SHA256 manifest；启动器比较私有安装标记，变更时原子替换运行环境，保留 workspace 与 dsh-home。避免 APK 更新后仍运行旧插件。
20. **加载原版持久化检查点策略**：从 `dsh-session-checkpoint-policy` 源码确认 loop 本身不在每个边界刷新日志。加载该原版 Cordis 插件，在模型和工具副作用之前落盘；集成测试必须在真实 JSONL 中看到用户消息和工具结果。
21. **分离缓存依赖与自研代码**：CI 缓存锁定版本的运行时，缓存命中时只替换归档里的编译后 host/plugins 并刷新指纹；不缓存过期自研代码，不因 Kotlin/UI 或插件迭代重复在 QEMU 内安装整棵 npm 树。
22. **从固定上游源码编译独立 proot**：Termux 5.1.107.99 预编译包在真正的 Android 应用域里创建模拟硬链接成功、删除时 tracer SIGSEGV；移除 link2symlink 则原版 dsh JSONL 的原子发布返回 EACCES，移除 fake-root 也未解决。固定 Termux/proot 提交 `ee6543334a67261d19e7023ae5eb30af5a7ad4f3` 与归档 SHA256，使用 NDK 27.3.13750724 为两个 ABI 编译。最小应用域测试已通过；不将 run-as 的成功当作应用成功。仅对缺失 string.h 的 ashmem 编译单元补充头文件声明，不修改 dsh、Node 或持久化语义。未将差异归因到未经证实的某个上游提交。
23. **工具卡片使用事件序号作稳定键**：模型的 tool call ID 可以在后续轮次重复。Compose 项目键取 dsh 的 session event seq，工具结果仍按原版 toolCallId 与最近工具调用配对，避免重复键崩溃；不改模型返回数据。
24. **先停止测试内核再清理 fixture**：桌面集成测试的 SIGTERM 会触发原版服务清理与落盘。等待子进程 exit 后才删除临时目录，避免 CI 中 ENOTEMPTY 的清理竞态，不忽略测试失败。
25. **完整扫描保持字节等价**：API key 测试 canary 为 ASCII。将每个二进制块按 Latin-1 一对一映射为字符串做匹配，并保留块间尾部，替代逐字节创建 Kotlin 迭代器的慢路径；继续覆盖整个应用存储，不缩减扫描范围。
26. **指标在测试进程结束前采集**：上次 CI 的仪器任务已成功，失败发生在后续指标文件读取。核对 AGP 9.1.1 的 BooleanOption 后启用保留 APK 安装选项；测试中记录当前应用及其后代进程的内存统计，CI 结束后读取保存的报告，不将原始内存内容或用户文件写入日志。
27. **人工反馈与自动化证据分别记录**：用户于 2026-10-24 反馈实际使用没有明显问题。报告保留该反馈，并注明未提供设备及逐项测试条件；CI 状态按真实运行结果填写，缺少的性能指标保持待测。
28. **ABI flavor 同时筛选二进制与资产**：仅拆 JNI 仍会携带两份 rootfs。使用 arm64 / x86 flavor，每包仅打入对应 CPU 的独立 proot 和一份 xz；构建后检查 ZIP 内部 ABI、归档数量、真实文件字节数及 SHA256。
29. **部署闭包裁剪**：从实际 Cordis 插件入口和静态 import 所需包出发，保守保留 dependencies/peer 闭包；root CLI 包与锁定版本元数据保留。只筛选部署文件，不编辑上游实现或替换持久化。删除未组合插件、系统文档/man/locale/头文件及声明文件。
30. **Sharp 延迟路径依据**：实读原版 attachment-local 的 `createLazyRequire("sharp")` 及 dsh-lazy-require，确认首次栅格操作才 require。M0 UI/工具不暴露图像能力，排除 Sharp 与 vips；保留原版附件服务，并要求裁剪后的完整 M0 测试通过。恢复图像能力时必须重新部署其原生包。
31. **xz 与 gzip 比较同一内容**：同一个 tar 流同时喂给 gzip 和 xz，生产包用 xz，gzip 仅在仪器 APK 中作为基线。两者使用同一个 RuntimeArchive 解包实现，比较读入 tar 的指纹与实际文件系统耗时；不把桌面压缩耗时当成 Android 解包耗时。
32. **独立测试进程测冷启动**：同进程仪器测试不能 force-stop 自己。增加仅测试用途的 benchmark-driver，实际点 Launcher 图标五次，Android 单调时钟测就绪及首个可见文本 token；每次停止应用进程，保留已安装 rootfs。首 token 时间包含输入、模型选择与审批操作，不等于纯网络 TTFT。
33. **区分内存阶段与进程**：记录每次流式输出中及 turn 结束后的空闲 dumpsys；按 Java 应用、独立 proot、Node native 子进程保存 PSS、Java/Native heap PSS 与原始报告。流式采集前后都检查仍在输出，CI 再验证五次数据、median 与所有原始文件完整。
34. **不因移动索引升级 Node**：Alpine x86_64 索引已改列 24.22.1，旧的 24.18.1 官方 APK 仍存在。锁定两 ABI 原 APK URL 和 SHA256，下载校验后交给 apk 执行官方签名检查；没有使用 allow-untrusted，没有变更 Node/dsh 版本或放宽版本范围。
35. **真机签认门槛**：提供 arm64 同套仪器测试命令、逐项清单和 OEM 默认/调整后后台策略表。未连接真机及未有逐项签认时，将 arm64/OEM 结果记为未完成，不能用 x86_64 结果或一般使用反馈补填。
36. **从实际 HOME 组件定位图标**：AndroidX 的 launcher 查询使用测试应用上下文，受包可见性影响；CI 也不能假定启动器总有滚动容器。改用测试 shell 解析 HOME，再查当前 UI 根节点，在导航栏上方滑动并有限重试准备；计时仍从点击真实图标开始，不用 am start 计时替代。
37. **fixture 首段必须匹配本轮**：原版 dsh 会追加 user 角色上下文，因此 mock 在完整消息集合查找唯一基准标记。第一段文本回显该标记，仪器必须看到对应标记才结束首 token 计时；不读取历史消息或伪造就绪事件。
38. **不下载未使用的旧 proot 二进制**：旧 Termux proot 包已返回 404，当前实际使用固定上游源码构建。源码构建流程只下载其必需的 talloc / shmem，保留锁定的来源与校验，不改用未知镜像或升级 dsh。
39. **先编译后启动 CI 模拟器**：预构建两个 ABI APK、两个测试 APK 与单测，再停止构建守护进程、启动模拟器，减少构建与内存测量相互争用。完整工作流继续校验指标导出、30 份 meminfo 及 APK 大小，任一失败都不能记全绿。
