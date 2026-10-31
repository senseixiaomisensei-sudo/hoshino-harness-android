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


## 2026-10-24 M1 preview.3 请求兼容修复

- 仅在 android-api Cordis 插件边界整理常见完整端点和 /v1 地址；继续由原版 pi-ai 发送 Anthropic Messages / OpenAI Chat Completions，不修改 dsh。设置页模型探测使用同一规则。
- 取消 profile 强制 medium thinking，只接受会话显式选择的 effort。
- 已保存密钥不再作为可编辑占位符写入输入框；旧版占位符前缀及复制的 Bearer 前缀在 Keystore 解密后内存规范化，不导出明文。
- 依据原版 packages/core/session/src/types.ts 的 reason.error: LlmFailure 显示脱敏错误，避免所有失败都变成检查模型与凭据；不把响应正文写日志。
- 截图不足以确定用户特定接口的最终错误；先修复可复现的应用端缺陷，未经真实接口复测不声称用户案例已验收。
- CI 后打包检查固定使用 amd64 辅助容器，目标 ARM Node 交给已注册 binfmt；避免 Docker classic image store 对同一 manifest digest 的多架构覆盖报错。

- 补充复现：对模型 metadata.reasoning=false 传 reasoningEffort=off，原版 session/selectModel 返回 session/model-unavailable（does not support reasoning effort off）。Kotlin 现在仅为已声明推理能力的模型附加 effort；无推理模型省略字段。双协议无推理回归通过。


## 2026-10-24 Shizuku 状态检测修复

- 根因：生产 DshHarnessClient 未覆盖 UnavailableHarnessClient 的固定 NotInstalled 值和未实现刷新函数，显示与实际安装状态无关。
- 使用 Maven Central 的官方 MIT Shizuku API / Provider 13.1.5，依照官方 README、demo manifest 和 sources.jar 的真实 pingBinder/checkSelfPermission/requestPermission/监听接口接入。SDK 不承担 Agent loop 或内核存储。
- Android 11+ 显式 queries 指定 moe.shizuku.privileged.api；受 INTERACT_ACROSS_USERS_FULL 保护的官方 Provider 接收服务 Binder。仅查询安装不认定为授权。
- 安装变化、Binder 连接/死亡、授权结果及 Activity.onResume 更新 StateFlow。用户明确点击才请求授权；本次不增加系统命令执行通道，也不自动开启完全访问。
- 官方 Shizuku 管理器仅作为独立模拟器验收夹具，不打包进主 APK。


## 2026-10-24 preview.4 系统执行、发送失败与历史搜索修复

- 用户截图中的完全访问档由 UI 在 Shizuku Ready 后开放，但当前 Cordis session-options 和 DshHarnessClient 明确不支持该档位，发送前 createSession 会拒绝。这是权限能力同步遗漏。
- 用户明确要求实际使用手机支持的 Shizuku，因此生产客户端接通官方 UserService 和 Cordis `system_shell`，不把禁用完全访问当作完成。`HarnessClient.supportedPermissionTiers` 保留给其他可替换宿主；生产档位要求 Binder 真实运行且已授权。
- 系统命令使用真实 Android shell、`/data/local/tmp` 及 Shizuku 的 adb/root UID；普通 bash/PTY 继续在 Alpine 中运行，不将 proot 的 UID 当作系统 root。工具沿用 Cordis 审批；拒绝不执行，超时或取消只终止本应用所建进程组。UserService 校验调用应用 UID，经文件描述符写入应用私有工作区，不放宽文件目录权限，不停止用户的全局 Shizuku 服务。
- ViewModel 切档前校验能力与授权，原版 dsh JSONL 和 KernelLauncher seam 保留。系统工具通过 `plugins/android-shizuku` 注册，未改 dsh 上游。
- 请求确认接收后才清空输入和对应附件；失败保留草稿，等待期间用互斥锁防止重复提交。界面显示经过 Keystore 凭据脱敏的具体异常。
- 新增 PermissionCapabilityTest：其他宿主未实现的档位仍禁用；createSession 拒绝后输入和具体原因均保留。ShizukuExecutionTest 使用官方服务器验证实际 UID、人工拒绝/允许、超时和取消，结果以测试日志为准。
- 历史列表的真实命名参数是 `_request`：依据锁定包 `@deepseek-ai/dsh-api-session-controller/lib/typert.host.js` 的 session/list 元数据修正，其他接口继续使用其原有 request。标题从 `projections.values.title` 读取，工作区分组从原 cwd 和 UI 元数据恢复；follow 去重，避免刷新多次开启同名 Mux 流。
- 全文搜索调用原版 session/search。原 profile 的 `session-query-sqlite.openAt=never` 明确禁止搜索；改为原接口支持的 `first-search`，保留 `path=:memory:`。SQLite 只作为可重建的内存查询索引；原版 `session-persistence-jsonl` 仍拥有会话持久化，没有替换存储。搜索合并标题与正文结果，展示片段，展开匹配的归档/折叠分组并显示错误。
- 冷重启测试暴露进程销毁时日志管道抛出 InterruptedIOException；启动器只捕获 I/O 关闭并等待原进程退出，不把未捕获线程异常传播为应用崩溃。
- mock 明确选择测试用户输入：dsh 可能在用户消息后追加 runtime context 和 skill catalog，不能把最后一个 user 角色消息直接当成真实请求。测试仍使用真实 Agent loop 和原厂协议适配器。

## 2026-10-24 M1 声明式原生 UI 与回滚

- 通过 HarnessClient 的类型化 StateFlow 传递 UI 覆盖，不在 Compose 中转换 Cordis 配置或调用 Node。未提供覆盖时继续使用 ZIP 原始 Token 和布局；覆盖强调色、字号、标语、胶囊、命令及现有分组内的侧栏顺序/显隐。设置入口持续可达，便于恢复误配置。
- 自定义胶囊和命令只填入草稿，用户确认发送后仍经过原有模型及工具审批；覆盖中不能携带可执行代码、任意路由组件或 WebView。
- 原版 Cordis 控制插件校验覆盖 JSON，原生映射再检查颜色和字号。无效数据回到原始界面并显示提示，原文件保留以便检查或回滚。
- 内核设置页读取实际快照清单并经确认调用现有 Cordis rollback；不用 UI 自建一套快照或会话存储。新增仪器测试验证实际窗口颜色、胶囊、命令、隐藏侧栏、原生回滚按钮和非法文件回退，结果按日志记录。

## 2026-10-24 用户要求的 max / 上下文 / 折叠修复

- 用户明确确认自己的模型支持 max。接口省略能力元数据时不阻挡手动选档、不标成“不支持”；显式选择通过既有 provider 配置刷新原版 pi-ai 的真实能力声明。
- max 使用实际最高档位参数，适用于两种已实现协议。Anthropic 显式 max 使用 adaptive thinking；DeepSeek 中转地址显式选择其原生 thinking 与 max_tokens 字段，通用兼容接口沿用相应标准预算字段。max 将旧 4K 输出上限提升到至少 32K、受配置上下文约束，保留更高设置，给思考与回答留出空间。
- 上下文采用缺失的原版 token-meter，而非 UI 自造统计。原生客户端仅映射其投影和真实 TokenUsage；估算行显式标注。新增原版插件不改 dsh 源码或会话持久化。
- 按用户追加要求，思考全文没有长度或高度限制，只折叠；终端默认整卡折叠，展开/复制保留完整结果。
- 先交付已在 Android 实测通过的新增体验修复测试包，继续完成仪器与 CI 收尾。未完成的长模型列表测试定位不改记为全绿，完整 M1 状态仍为开发中。

## 2026-10-24 最终交接与停止

- 用户明确要求发布 GitHub 仓库、写报告后收工，因此停止后续 M1 功能开发，不集成未验证的安全模式草稿。
- 默认分支采用 `codex/m1`，让仓库首页与 clone 获取本次交付源码；M0 的历史基线保留在 `main`。已发布 preview.5 的 tag、APK 和源码提交保持不变。
- 报告分别记录本地 8 项通过与远端 CI 两项失败。整理测试夹具和说明不改变已发布 APK，也不将阶段性交付写成完整 M1 验收通过。

## 2026-10-31 M2.7 审批档（含无审批）与会话管理

- **无审批只能由用户开启**：`approval.json` 新增 `mode: "none"`，出厂默认仍是 `manual`，测试锁定这一点。开启时（本会话或设为默认）首次弹风险说明，可勾选不再提示。无审批下 `resolveApproval` 直接 allow，不再走 Android 审批卡；只有用户自己写的 `deny` 规则仍生效（那是用户自设护栏，不是确认卡），`ask` 规则不会把卡带回来。每次决定照常写审计 `approvals.jsonl`，`approver=auto-none` 并记录 `mode`。
- **权限档先于审批档**：`android-policy` 的 `tools/pre-execute` 里先跑 `tierGate`（抽到 `tier.ts` 便于测试），再问审批。因此无审批放宽不了只读 / 工作区 / 完全访问的边界；`system_shell` 仍只在完全访问会话可用，工作区路径校验不变。安卓系统自身的确认（装 APK、运行时权限、Shizuku 授权）无法也不去绕过，界面写明。
- **审批档的作用域**：全局默认 + 每会话覆盖，沿用 `approval.json` 已有的 `scopes.sessions`，不新建配置文件。新会话尚未创建时选的「仅本会话」先记在 ViewModel，创建后立即写入。切到无审批时，该会话还挂着的审批卡按「允许一次」放行。
- **插话走 dsh 原生 steer**：运行中继续发送调用 `session/prompt mode=steer`（消息进 inbox 的 next-step，下一次模型请求前被当前任务认领），不另开任务；内核不再接受插话时退回原有排队。核实锁定的 dsh 0.2.2-alpha.2：`ReactLoopInbox.mutate` 把每次变化写成持久化会话事件 `agent/inbox/spliced`（`target/start/removedCount/inserted/outcome`），认领时以同一消息 id 追加 `user/message`；`session/follow` 的实时尾巴通过 `session/event` 转发**所有**会话事件，快照的 records 是按消息预算切出的连续事件段，`projections.values.inbox` 带当时完整的待处理列表。所以 Android 端不需要额外订阅：`SteerTracker` 镜像 inbox（快照后用 `inbox` 投影校正），插话气泡在进 inbox 时画出（「已收到，下一步处理」），认领成 `user/message` 时只改状态为「已加入当前任务」，不重复画气泡。
- **停止 = 取消 + 作废未认领插话 + 交代**：dsh 的 `session/cancel` 用 `keepInbox: true`，未认领的插话会留在 inbox；因此 Android 停止后对每条未认领插话调用官方 `session/updateQueue {kind:"remove"}`（产生 `outcome: "canceled"` 的 splice，界面标「已停止，这句没有处理」）。随后插件操作 `stop-summary` 等本轮停稳，按本轮事件整理事实，优先用压缩模型/当前模型在 12 秒内写 3–6 行交代（材料加标签当数据、不给工具），失败或超时按记录拼一段；结果用 `agent.inject`（next-step、不唤醒）写进 inbox，下一轮「继续」能读到。界面从 `agent/inbox/spliced` 画出交代；若那条事件不在已加载的一页里，则在它被认领成 `user/message`（来源 `hoshino-stop-note`）时补画。结构参照 Mika `StopSummary`，见 THIRD_PARTY_NOTICES.md。
- **删除会话不改 dsh**：dsh 没有删除 RPC。删除立即写墓碑并隐藏，停止、取消订阅、归档，清插件侧（审批覆盖、会话提示词、策略选项、定时任务；内核未连接时排队，连上后补做）与 Android 侧（交付记录、附件原件、缓存、元数据、等待消息）。dsh 的会话 JSONL 目录只在内核进程不在时删除（下一次启动内核前；确认 `/proc/<pid>` 的启动时刻与命令行，避免误判复用的 pid），连同 `origin=subagent` 的子会话，保留用户分叉出的会话。工作区里的用户文件不删。
- **置顶**：本地记录为准（时间戳排序），连接时同步 dsh 原有 `workspace/pinSession`；归档会取消置顶，已归档不能置顶。
- **导出**：插件读 dsh `sessionQuery.readSession`，Markdown 优先用 dsh 自带的 `formatSessionMarkdown`（精简运行时没有时用本地格式化），JSON 是完整事件日志；Android 去掉已知密钥后保存到「下载/Hoshino」并打开系统分享。

# M2.7 ChatGPT 登录与模型自带联网搜索

日期：2026-10-31。

1. **ChatGPT 登录改为与 Mika 相同的「用 ChatGPT 登录」**（用户明确要求"GPT 登录规则与 Mika 一样"）。旧实现借用 Codex CLI 的公开客户端（`app_EMoamEEZ73f0CkXaXp7hrann`、固定端口 1455、`/oauth/authorize`）并调用 chatgpt.com 的 Codex 后端：客户端身份属于另一个产品，固定端口会与本机其他 Codex 登录冲突，后端也不是公开 API。新方式是 OpenAI 为开源、本机运行的 App 提供的路径：issuer `https://auth.openai.com`，授权 `/api/accounts/authorize`；首次用 `dynamic_agent_client` 并带 `agent_name_hint=Hoshino` 注册，回调返回本账号专属 client_id，之后换令牌、刷新、再登录都沿用；`ext_agent_host_id` 为每台设备生成一次的 `urn:uuid`；回调 `http://127.0.0.1:<系统挑的端口>/auth/callback`；scope `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`，resource `https://api.openai.com/v1`；PKCE S256 + state + nonce；ID 令牌按 JWKS 验 RS256 签名与 iss/aud/exp/nonce；必须授予 `chatgpt.tokens.use.direct` 才算登录成功；刷新串行（刷新令牌一次性）、终止错误清令牌要求重登、临时错误保留令牌；退出登录先 revoke 再清本机令牌（注册与身份保留，再登录带 `id_token_hint`/`login_hint`）。实现移植自 Mika（Apache-2.0），见 `core-bridge/src/main/java/dev/hoshino/bridge/chatgpt/`。
2. **会话整份加密保存**：`ChatGptSession` 以 JSON 作为一个 Keystore 封装的秘密（`CHATGPT_SESSION`）保存；设备标识与注册得到的 client_id 不是秘密，与 Mika 一样明文存应用私有 preferences。`ProviderStore.redact` 会把 JSON 秘密的各字段也加入脱敏表。授权 URL（可能含 ID 令牌提示）、令牌不进日志。
3. **调用改走 `api.openai.com/v1/responses`，不改 dsh**：锁定的 pi-ai 1.4.0 自带标准 `openai-responses` 适配器（dsh-llm-pi-ai 的声明路由表也支持它），因此 `openai-codex` 路由改为 `api: openai-responses`、baseURL 为存储的 `https://api.openai.com/v1`；套餐访问令牌每次请求经 Android 凭据回调取得，Kotlin 负责提前刷新，API 回 401 时插件要求一次强制刷新后重试。pi-ai 只在 provider id 恰为 `openai` 时识别 ChatGPT 令牌，所以 Mika ResponsesModel 的规则在 `plugins/android-api/hosted-search.ts` 的 fetch 边界补齐：`store:false`、流式、带 `reasoning.encrypted_content`；套餐路由不发温度、输出上限等字段；服务端说不收 `include` 时去掉重试；套餐错误码（不符合资格、额度用完、工作区上限等）改写为说明，免费版/Go 明确提示需要 Plus/Pro。
4. **旧 Codex 登录用户**：升级后首次启动清除四个旧令牌引用，provider 改指新地址，设置页提示"重新登录一次"。
5. **模型列表**：登录后 `GET /v1/models`（同 Mika ModelCatalog，兼容 `data`/`models` 两种形状），与 `codex-models.json` 离线元数据合并；接口列出什么就显示什么。离线表补 `gpt-6.1-terra`（上下文 1,050,000、输出 128,000、看图、推理 none/low/medium(默认)/high/xhigh/max，来源 https://developers.openai.com/api/docs/models/gpt-6.1-terra ）。App 推理档新增 `xhigh`；不支持的协议上发送其最强已知档。
6. **模型自带联网搜索默认优先，Hoshino 搜索退为备用**：托管搜索就是同一个已审批的模型请求，不新增目的地，网络策略与审批不变。ChatGPT/Responses 路由加 `{"type":"web_search"}`（被拒再试 `web_search_preview`）；Chat Completions 用检测到的 `web_search_options`、`plugins:[{id:"web"}]` 或模型列表明确列出的联网变体名（不猜改模型名）；Anthropic 用 `web_search_20250305` 服务端工具。启用托管搜索时本次请求不提供 Hoshino 的 `web_search` 函数工具（不重复搜）；提供商拒绝托管搜索（HTTP 4xx，或流中在首个输出前失败）时自动改发原请求，Hoshino 搜索恢复可用，并记住该模型不可用。没有改动 `plugins/hoshino-m2` 的搜索工具注册。
7. **流的展示**：pi-ai 不认识 `web_search_call`、`url_citation`、`server_tool_use`。插件把搜索调用改写为一段以 `🌐 ` 开头的思考/推理内容（App 显示为过程中的"联网搜索"步骤，回放给模型时剔除），把引用改写为回答末尾的 Markdown「来源」列表，均不报错。
8. **能力探测**：Settings 的"检测"、或模型首次使用时，Kotlin `WebSearchProbe` 按 Responses web_search → web_search_preview → Chat web_search_options → plugins web → 列表中的联网变体（OpenAI 兼容）/ Anthropic 服务端工具的顺序发很小的请求，必须在回复里看到真实搜索证据（`web_search_call`、`url_citation`、`server_tool_use`…）才算可用，网关静默忽略未知字段不会被误判；429/401/5xx/网络错误记为"未定"而不是"不可用"。结果作为 provider 模型元数据 `webSearchMethod` 保存，设置里可查看并覆盖为自动/强制开/关；未知的 Chat 路由在已审批请求内顺带检测。中转站模型若检测到 Responses 方式，额外生成 `<id>--responses` 路由（同样用 pi-ai 标准 openai-responses），App 对该模型选这条路由。

## 2026-10-31 M2.1 模型请求不再单独审批（补记）

- **事实**：M2.1 提交 `03db186`（feat: add M2.1 instant sessions…）删除了 `plugins/android-policy` 里 `agent/request` 的逐次「模型网络请求」审批，并在原处写明理由：「Sending a user prompt to an explicitly enabled provider is already authorized. Tool/network side effects still use the tool approval policy above.」此后 M2.2–M2.6 五个发布版都沿用这一行为，当时没有在本文件记录。
- **判断为有意的设计变更**：同一提交以注释替换而非误删；M2.1 的目标是即时会话（发送即开始，不再每条消息先弹卡）；用户在设置里显式添加并启用 provider、自己填 key，本身就是对「把提示词发给这个 provider」的授权。AGENTS.md 铁律「会改动用户文件、执行 shell、发网络请求的**工具**默认走审批」不受影响：`web_fetch`/`web_search`/写文件/shell 等工具调用仍按 `approval.json`（出厂 `manual`）审批；M2.7 的模型自带联网搜索也只是同一个模型请求，不新增目的地。
- **与 M0 决定的关系**：M0 第 9 条「每个模型请求也逐次审批」是 M0 权限最小化阶段的决定，被 M2.1 取代。README「模型网络请求与 Agent 写操作默认审批」一句随之过时，待主代理发版时更新。
- **测试**：`node-host/test/kernel.test.mjs` 原断言「出现 network 审批」自 M2.1 起一直失败（基线失败）。改为断言当前设计：普通模型请求**不**出现 `kind: network` 审批，同时**仍**出现 `write` 工具审批。没有删除断言。Android 侧 `DshHarnessClient` 里处理 `kind=network` 审批卡的分支保留（兼容旧插件/第三方插件仍可能发起该类审批）。

## 2026-10-31 M2.3 第三方 Anthropic 兼容端点用通用 Messages 形状（补记）

- **事实**：M2.3 提交 `064eaa0`（fix: send plain Anthropic Messages requests to third-party Anthropic-compatible endpoints (relay 502)）新增 `plugins/android-api/anthropic-compat.ts`：对 `api.anthropic.com` 以外的 Anthropic 兼容地址，去掉 pi-ai 的 Claude 专用字段（`output_config`、adaptive thinking、beta 头、工具 eager streaming 等），推理档换算成显式 `thinking.budget_tokens`（max=16000）。官方地址不改。文件头写明了理由（中转站/DeepSeek 等对这些字段回 4xx/502），当时没有在本文件记录。
- **测试**：`kernel.test.mjs` 的 claude-max 断言仍按官方形状（`output_config.effort=max`、`thinking.type=adaptive`）检查发往本机 mock 的请求；本机 mock 属于「第三方兼容端点」，因此自 M2.3 起该断言不成立，只是被前面 M2.1 的 network 断言失败挡住、一直没跑到。改为断言当前设计：无 `output_config`、`thinking.type=enabled`、`budget_tokens=16000`；其余 max 预算断言（`max_tokens>=32768`）不变。官方形状没有离线可测的端点，未覆盖，如需覆盖应给 `sanitizeAnthropicBody`/地址判定单独写单测。


## 2026-10-31 M2.8 手机操作接入 m2.7 审批档（含无审批）

背景：`feat/m2.8-phone` 基于 m2.7 之前的 `bce6285` 开发，m2.7 新增了审批档（manual / rules / model / auto / none）、无审批和审批折叠。集成分支 `integ/m2.8` 合并时按下面的规则统一语义。依据：mika-gap（PR #1，`docs/mika-gap.md`）PL2「保留默认审批，付款永远交用户亲手做；无审批只能用户主动开」、AP2「风险判定在无人在场、自主、无审批模式下照样运行并记审计」、AP4「付款本身永远交用户亲手做」、AP6（Mika 自主模式连付款也不问——我们不跟），以及本文件 M2.7「无审批下 resolveApproval 直接 allow，只有用户自己的 deny 规则仍生效，`ask` 规则不会把卡带回来」。

- **普通 phone_* 工具走同一个审批口子**：android-policy 的 `tools/pre-execute`（先权限档，后审批），所有 phone_* 共享一个「本会话允许」。审批由 hoshino-m2 的 `resolveApproval` 决定，因此：出厂 manual 下首次弹卡；无审批下自动放行、不弹卡，审计 `approver=auto-none`。
- **不可逆步骤（发送 / 删除 / 下单 / 提交）**：Android 侧按「这一步 + 这个屏」算指纹停下，android-phone 以 `tool=phone_irreversible` 发一次性审批（永不记为本会话允许）。
  - `phone_irreversible` 算**高危**：开着「高危确认」（出厂默认开）时，manual / rules / model / **auto** 档都逐步问一次；用户的 allow 规则也越不过高危确认（与 rm、git reset 等一致）。
  - **无审批下不再弹一次性确认，直接放行并记审计**（`approver=auto-none`、`risk=irreversible:<kind>`、`app=<包名>`，不记步骤文字、输入内容或屏上文字）。理由：无审批是用户看过风险说明后主动开启的，M2.7 已定「ask 规则不把卡带回来」；在无审批里对一类工具单独保留确认，会让「无审批」名不副实，也和 AP2 的表述（照样运行并记审计，而不是照样拦截）不符。用户仍可写 `{"tool":"phone_irreversible","decision":"deny"}` 规则，在无审批下也生效。无审批的风险说明（`ApprovalModeRules.PHONE_LIMITS`）写明这一点。
- **付款与「要你亲手来」的屏，任何审批档都绕不过**（与审批无关，在 Android 执行入口里判定）：
  - 付款步骤从不送去审批（android-phone 直接返回 `reason=payment` 要求 `phone_handoff`），Android `StepRunner` 也**不接受付款授权**：即使有人对付款指纹调用 `grant`，也直接停下交给用户。
  - 在需要用户的屏（密码 / 登录 / 验证码 / 人机验证 / 付款）上，`type`/`clear` 一律停下；在人机验证和付款屏上 `tap`/`long_press`/`swipe` 也停下（滑块、支付密码键盘）。返回、按键、等待、打开别的 App 仍允许，以便交接或离开。
  - 交接（`phone_handoff`）由用户点「处理好了 / 跳过」，与审批档无关。
- **审批折叠与胶囊一致**：聊天审批卡、通知、胶囊三处都用同一个 `approvalChoices(oneShot)`：一次性审批（`phone_irreversible`，以及剪贴板/分享/应用截图这些 `onceOnly` 工具）只给「允许一次 / 拒绝」，其余给「允许一次 / 本会话总是允许 / 拒绝」，标签一致；Android 对一次性审批返回 `remember=false`。在任一处处理后发 `ApprovalResolved`，聊天按 m2.7 规则把已处理的审批折叠进过程组，胶囊同时移除。手机不可逆审批卡标题为「手机不可逆操作」，通知显示插件给的摘要。
- 测试：`node-host/test/phone.test.mjs`（各审批档对 phone_irreversible 的结果、无审批端到端放行且审计不含屏上文字、付款不进审批口子、其他会话仍弹卡）；`StepRunnerTest`（付款授权无效、验证码/人机验证在「全部批准」下仍停）；`SessionStorePerformanceTest`（一次性审批三处选项一致并照常折叠）；`ApprovalSessionsTest`（风险说明含手机边界）。

## 2026-10-31 m2.8.1 打开 App 不再要求 Shizuku；完全访问认内置无线调试助手

背景：小米 15S Pro（m2.8）上让 AI「打开抖音」，模型回答「没有授权的完全访问会话，配置 Shizuku 后我再试」；用户没装也不想依赖 Shizuku。另一张截图里完全访问 + 无审批会话执行 `system_shell monkey -p com.ss.android.ugc.aweme 1`，0 秒失败，只有 `Android callback failed (500)`。

决定：
- **打开 / 操作 App 一律走 `phone_*`**（`phone_launch_app`、`phone_do_steps` 等），不需要 Shizuku、system_shell 或完全访问。`android-phone` 系统提示、`phone_launch_app` / `phone_status` 描述、`android-policy` 对 system_shell 的拒绝原因（`SYSTEM_SHELL_DENY_REASON`）、`android-shizuku` 的描述与报错都写明这一点：未完成手机操作设置时调用 `phone_status`，引导用户到「设置 → 设置手机操作」，不要让用户去配 Shizuku。
- **非完全访问会话不暴露 system_shell**：`hoshino-m2` 的 `syncTools` 在模式白名单之外再叠加 `tierHiddenTools(tier)`（纯函数 `sessionToolDeny`）；`hoshino-self` 的 `session-options` 切换档位后已会调用 `syncTools`。运行时 tier gate 仍然拒绝，双保险。
- **完全访问的系统执行器 = Shizuku 或 内置无线调试助手**：`system-access` 回调在 Shizuku Ready 或 内置助手「已配对且 helper 运行」时 `authorized=true`（纯函数 `SystemAccess.route`）。`system-shell` 回调先试 Shizuku；Shizuku 未 Ready、或绑定/超时（新 `ShizukuUnavailable`，表示一条命令都没执行，可安全换执行器）时回退到 `BuiltinSystemShell`（经 `BuiltinShellManager.run` → helper `runCommand`）；两者都不可用时报「Shizuku 未运行/未授权，且内置无线调试助手未就绪：请到 设置 → 设置手机操作」。
  - 内置路径保留超时（toybox `timeout -s KILL`，受 helper 单条 15 s 上限约束，钳到 14 s 并在结果里注明）、取消（pid 文件 + `pkill -P`/`kill -9`，只能由同一会话取消）、输出截断（预览 32 KiB，完整输出写入工作区文件，helper 本身最多 64 KiB）、终端回显和本地审计行（只记 exit/timedOut/cancelled，不记命令和输出）。stdout/stderr 在 helper 里合并，结果标 `stderrMerged`。
  - 审批不变：system_shell 仍走用户的审批档；完全访问仍须在会话中手动确认风险，不能设为预设默认；不自动开启无线调试，同意项默认不勾选；付款 / 密码 / 验证码仍交给用户。
- **会话档位以内核为准**：`send()` 每轮把输入框的档位发给内核，但本地 `sessionRows` 不更新，导致 system-shell 回调里的 `tier==FullAccess` 校验失败（截图里的 0 秒 500）。现在插件在 system-shell 请求里带上内核已验证的 `tier`，Android 以它为准（`SystemAccess.sessionAllowsSystemShell`：内核报告的档位优先；内核报告其他档位一律拒绝；旧内核不带时退回本地行），并在 `send()` / `setSessionTier` / 回调后同步本地行。
- **回调错误不再丢失**：`AndroidCallbackServer` 500 时返回 `{"error": 脱敏后的 failure.message}`（`ProviderStore.redact` → `redactCredentials`，最多 2000 字，空消息退回异常类名；诊断日志仍只记类名）；`node-host/src/platform.ts` 的 `callbackError` 读出后抛 `Android callback failed (500): <message>`。
- `phone_*` 失败时（`code` 为 helper / accessibility / unsupported）错误文本带「设置 → 设置手机操作」和「Shizuku 与完全访问都不需要」（`phoneError`）；UI（权限弹层、风险确认、引导弹层、设置页、预设页、`PermissionTier.FullAccess` 描述）改为「需要 Shizuku 或 内置无线调试助手」，引导弹层首推「去设置手机操作」。

测试：`node-host/test/m281.test.mjs`（tier gate 文案、system_shell 隐藏、phoneError、手机提示词、callbackError 及端到端 500 消息）；`SystemAccessTest`（执行器判定、内核档位优先、回退顺序）；`BuiltinSystemShellTest`（命令包装/引号、超时钳制、结果形状、执行写文件、取消）；`AndroidCallbackServerTest`（500 带脱敏 JSON 错误）。未真机验证：内置助手执行 system_shell、取消杀进程、Shizuku 绑定超时回退。

## 2026-10-31 M2.9 App 操作只在后台虚拟屏；修复 phone_* 不在会话工具列表

背景：用户在 m2.8.1 上看到两件事：① AI 用 `system_shell` 跑 `monkey -p com.ss.android.ugc.aweme 1`，抖音在**主屏**打开，打断了用户；② AI 回答「当前会话提供给我的工具列表里没有虚拟屏的启动工具」。

根因（② 是 ① 的直接原因）：
- **android-phone 插件从未激活**。`@deepseek-ai/dsh-tools` 的 `defineTool` 要求每个 `type:'object'` 参数显式写 `additionalProperties: true|false`，否则抛 `JsonSchemaError: unsupported JSON schema: parameters.expect.additionalProperties must be explicitly true or false`。`phone_tap` 等的 `expect` 和 `phone_do_steps` 的 `steps.items` 都没写，`apply()` 抛错；该条目是可选插件，dsh 只在 stderr 打一行「1 entry did not activate」，于是**所有会话里都没有任何 phone_* 工具**（m2.8 / m2.8.1 一直如此；单测只测了 `steps.ts` 纯函数，没加载插件）。模型只剩 system_shell 能「打开 App」，就用了 monkey。新增内核级测试 `node-host/test/m29-phone-tools.test.mjs` 启动真内核 + mock LLM，读取模型实际收到的工具列表，修复前即复现（standard 模式缺 phone_launch_app）。
- **次要：模式白名单会屏蔽新工具**。`DshHarnessClient.upsertPreset` 总写 `"tools": JSONArray(preset.tools)`，编辑/复制内置预设时空列表写成 `[]`（内核读作「禁用全部工具」）；用户自定义预设的白名单（且预设页 `ALL_TOOLS` 多是 UI 标签而非真实工具名）不含 m2.8 新增的 phone_*。

决定：
- `android-phone` 的 `expect` / step 参数改为严格 schema（`EXPECT_SCHEMA`、`STEP_SCHEMA`，`additionalProperties:false`，字段与 Android `PhoneStep` / `Expectation` 一致）。
- 模式白名单语义（`hoshino-m2/tool-mask.ts`）：非数组 = 全部工具；空数组且 `id!=='chat'` = 全部工具（兼容旧 app 写入的 `[]`）；chat 的 `[]` = 无工具；其余白名单照常生效，但 `phone_` 前缀工具族永远放行（`ALWAYS_ALLOWED_PREFIXES`），chat 除外。`syncTools`（隐藏）和 `tools/pre-execute`（拒绝）共用 `modeAllows`。写入 `modes.json` 后立即对现有会话重新 `syncTools`。
- `upsertPreset` 改用 `PresetRows.row`：空选择不写 `tools` 字段（chat 保留 `[]`），并保留 `readOnly`（原来保存会丢掉只读）。
- **插件激活失败不再静默**：`self_doctor` 按每个内置插件的标志工具（`BUILTIN_TOOL_MARKERS`）检查，缺失时 `kernel:'degraded'` 并列出 `inactivePlugins`；设置里的 M2 诊断页直接显示该 JSON。
- **system_shell 不能操作主屏**（`android-policy/shell-guard.ts`，纯函数 `guardSystemShell`）：在 tier gate 之后、审批弹窗之前判定；`android-shizuku` 执行前再判一次。解析普通 shell 语法（引号、`; && || | &`、换行、`$( )`、`sh -c`/`su -c`/`su 0`/`adb shell`/`env`/`nohup`/`timeout`/`busybox` 前缀、绝对路径）。拒绝：`monkey`（没有 display 参数，一律拒绝）、`am start|start-activity` / `cmd activity start…`（指向 phone_launch_app）、`input tap|swipe|text|keyevent…` / `cmd input`（指向 phone_tap / phone_do_steps）、`cmd statusbar`、`am display move-stack` 等跨屏移动（指向 phone_handoff）。只放行显式 `--display <agent>` / `-d <agent>` 且等于当前 agent display 的命令（通过新 bridge 动作 `phone {action:'display'}` 取 id；display 0 永远拒绝）。不自动补 `--display`：直接改写命令会绕过 phone_* 的「主屏正在用该 App」检查、静音与不可逆步骤审批，所以只在拒绝原因里给出 agent display id。这是防模型习惯的护栏，不是沙箱。
- **phone_launch_app 永远只用 agent display**：`PhoneShellService.startApp` 已校验 display 归属、无主屏回退（反射失败时退到 `am start --display <agent>`）。新增启动后放置检查（纯函数 `LaunchPlacement.verdict`）：若 App 新出现在主屏前台（Android 复用了它在主屏的后台任务），立即 `moveTask` 挪回 agent display；挪不回就报 `opened_on_main_screen` 错误，不重试、不回退。
- **后台照常运行**：`PhoneController` / `AgentDisplay` 只用 application context，不依赖 Activity；内核由 `KernelService`（specialUse 前台服务）保活。新增 `PhoneTaskService`（specialUse 前台服务，常驻通知「Hoshino 正在后台操作手机 · 包名」+「停止」），**只在 agent display 存在期间运行**（`PhoneController.display` 非空时启动，`phone_finish` / 空闲 5 分钟释放后停止）。Android 12+ 后台启动前台服务可能被拒，失败只记入本地审计，任务本身不受影响（进程仍由 KernelService 保活）。锁屏时步骤仍按原设计等待解锁（`locked` 守卫未改）。
- 系统提示：`PHONE_PROMPT` 写明所有 App 操作只在后台虚拟屏、与主屏并发、Hoshino 退到后台也继续，不要用 system_shell 操作 App；虚拟屏只在任务运行时创建，看不到额外 display 不代表没有该功能，直接调用 phone_launch_app。`FULL_ACCESS_PROMPT`、`system_shell` 与 `phone_launch_app` 描述同步。`phone_status` 未就绪时返回逐项设置指引（`phoneStatusText`）。

测试：`m29-phone-tools.test.mjs`（真内核：standard / auto / goal 含 phone_*，chat 无工具；旧 `[]` 的 standard 与旧自定义白名单仍含 phone_*，旧白名单其余限制不变）；`m29-vd-guard.test.mjs`（命令守卫各写法、显式 agent display 放行、提示词、phone_status 指引、插件加载、模式白名单、doctor）；`m281.test.mjs` 中「白名单屏蔽 phone_*」的两条断言按新语义改为期望值（未删）；`PresetRowsTest`、`VirtualDisplayGuardTest`。未真机验证：前台服务后台启动是否被系统拒绝、App 复用主屏任务时的挪回、Hoshino 退后台/熄屏时虚拟屏持续渲染。

## 2026-10-31 m2.9.2：无审批 = 彻底无审批（取代 M2.8「付款/密码/验证码任何档位都交给用户」）
- 用户明确要求：选了无审批的会话（含其子 Agent）里任何审批/确认都不再弹出，全部自动通过并写审计；付款、密码、验证码、人机验证页面在无审批下也不再自动停下交给用户，照常执行（逐条审计，只记原因与包名）。
- 默认仍是手动审批；无审批只能由用户主动开启，开启时弹一次风险说明，确认后不再问。
- 不变：权限档、用户自写的 deny 规则、system_shell 主屏守卫、Android 系统自身的确认（APK 安装、运行时权限、Shizuku 授权）。
- 详见 docs/m2.9.2-noapproval-preview.md。

## 2026-10-31 m2.9.3：意图门不挡明确指令；无审批跳过；DISCUSS 默认软提醒一次
- 用户真机反馈：无审批会话里「打开微信」被 `DISCUSS_DENIAL` 拒绝。意图门（RT10）只是减少误操作的辅助，不能挡住用户的明确指令。
- 决定：无审批会话（含子 Agent 继承）完全跳过意图门；其他档位 DISCUSS 默认改为一次性软提醒（同一条用户消息下第二次被拦的工具调用直接放行），旧的硬拒只作为 `config/context.json` 的 `intent.discuss = "deny"` 可选项；本轮每条用户消息都参与判定，新插话会重判；提醒/放行/拒绝写 `audit/intent.jsonl`（不记原话）。
- 详见 docs/m2.9.3-intent-inapp-screen.md。

## 2026-10-31 m2.9.3：应用内虚拟屏
- 用户说的缩放/查看是指 App 内（Compose），不是悬浮球。新增全屏页「虚拟屏」与聊天页小窗，复用 m2.9.2 的同一变换（叠加缩放/平移）、同一接管状态机、同一帧源（GL 中继改为多预览面）。悬浮球行为不变。

## 2026-10-31 M2.9.4 只有按「接管」才接管
- 用户要求：虚拟屏（应用内页面、小窗、悬浮球面板）上的触摸不再自动接管；只有按下「接管」按钮才暂停 AI 并允许操作，按「交还」恢复，不再 4 秒自动交还。
- 实现：`AutoTakeoverModel(touchTakesOver = false)` 为默认；未接管时触摸只提示「先点「接管」才能操作虚拟屏」（3 秒限一次），捏合缩放/平移照常。旧行为保留为参数，单测显式开启。
