# M2 桌面 dsh 插件兼容记录

dsh 固定 `0.2.1-rc.2`。以下为实际组合中的十个常见插件；“已加载”仅证明 boot/依赖注入，不冒充功能或真机验收。

| 原版插件 | 当前结论 | 已有证据/边界 |
|---|---|---|
| dsh-tool-fs | 宿主调用通过 | 原版 write/read、JSONL 和凭据回归；Android write/Diff 另由仪器检查 |
| dsh-tool-todo | 宿主调用通过 | mock-llm 经原版 Session/Mux 实际调用 todo_write |
| dsh-tool-bash | Ubuntu 调用/Android 适配 | Windows 跳过 Linux shell；使用已有 subprocess/沙箱接口 |
| dsh-terminal-bash | 已加载，需 Android 原生依赖 | 原 PTY 链；musl node-pty 目标打包检查及 M1 原生冒烟 |
| dsh-session-query-sqlite | 宿主调用通过 | 实际 session/search；使用 Node 内置原生 SQLite，真机冷恢复另测 |
| dsh-skill | 已加载 | 原 registry、文件式技能；没有伪造已调用用户自定义技能 |
| dsh-tool-skill | 已加载 | schema/boot；完整第三方技能组合未逐项验收 |
| dsh-subagent-spawn-in-process | 已加载 | 原版 provider 与 subagent 工具；完整并行模型任务仍需验收 |
| dsh-goal / goal-round-driver | 已加载 | 原版目标持久化与延续模块；长期真机测试未做 |
| dsh-schedule | 已加载 | 原版持久化及 Android 唤醒桥；OEM/重启矩阵待实测 |

npm/git/local/tgz 安装通过 npm 原格式处理；原生依赖优先使用包自带 musl arm64 产物。没有预编译产物时可安装 native-build 工具再重试。桌面 GUI、浏览器或不可映射绝对路径的包可能只部分可用，错误会隔离；未声称任意 npm 插件原样兼容。

M2 不满足“从市场安装并成功调用三个桌面插件且含一个外部原生模块”的完整真机验收；这是明确缺口。
