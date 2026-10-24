# preview.5：上下文、折叠与 max 推理

用户确认其模型支持 max、其余功能正常，要求快速修复体验。模型列表未报告能力不再被当作“不支持推理”：保留所有推理档位，用户明确选档后启用对应模型的原版 dsh 推理适配。

- **上下文窗口**：加载缺失的原版 `@deepseek-ai/dsh-token-meter`，读取 `contextPressure`、`contextBreakdown` 及请求容量。提供方回报的用量与估算明细分别标注；小于 1% 的用量不再截成整数 0%。每轮按真实 `assistant/message.usage` 汇总统计。移除尚未接通的压缩按钮。
- **思考过程**：默认折叠，展开保留全文，支持选择复制，没有长度或高度上限。
- **终端结果**：默认仅显示命令与状态，点击标题或箭头展开完整输出，可再次收起和复制完整结果。
- **max**：适用于 Anthropic 和 OpenAI 兼容协议。用户显式选档后，修正缺少能力元数据的模型配置；DeepSeek 兼容请求发送 `thinking.type=enabled` 与 `reasoning_effort=max`；Anthropic max 使用 adaptive thinking 与 `output_config.effort=max`。通用兼容接口也直接传所选档位。max 的输出预算至少 32K，受已配置上下文容量约束，保留更高的原配置，供思考和回答共同使用。

参数依据：[DeepSeek 思考模式](https://api-docs.deepseek.com/guides/thinking_mode/)、[Anthropic effort](https://platform.claude.com/docs/en/build-with-claude/effort)、[OpenAI reasoning](https://developers.openai.com/api/docs/guides/reasoning)。具体思考耗时由模型与任务决定，验证使用实际请求参数和预算。

dsh 保持未修改的 `0.2.1-rc.2`，Agent loop、JSONL、SessionStore 与 KernelLauncher seam 不变。当前组合为 52 个插件、20 个工具、29 个路由。

| 验证 | 当前证据 |
|---|---|
| 原版 dsh、DeepSeek / Anthropic / 通用兼容接口最高档位、32K 预算与真实上下文投影 | `.research/m1-max-node-test.log`，11 轮通过；两项可选真实接口测试未提供凭据而跳过 |
| 双 ABI 最终构建、单测、仪器 APK | `.research/m1-max-final-build.log`，成功 56s |
| Android 双协议聊天/工具/切换回归 | `.research/m1-max-native-first-attempt.log` 中聊天用例通过；同轮新增用例未正确定位横向栏中的模型按钮而失败，记录保留 |
| max 菜单、真实请求/预算、上下文与全文折叠 | `.research/m1-max-native.log`，新增 ChatExperienceTest 通过（对应 status code 0） |
| 完整仪器回归 | 同次 2 项中聊天切换用例卡在长列表未滚动到目标，新增体验用例通过，不能记 `OK (2 tests)`；已修正定位脚本，继续跑完整回归 |
| arm64 真机、用户自带接口 | 本次未现场复测 |

本地 Docker Linux 引擎未运行，缓存缺少新启用的纯 JavaScript token-meter。构建通过锁文件中的原始 npm tarball 补齐它，校验 SHA512 和精确版本、拒绝原生文件，保留各 ABI 的原有 Node/原生依赖字节；实现见 `rootfs/hydrate-locked-js.py`。常规 CI 从原 Dockerfile 构建完整运行时，不需要该缓存补齐步骤。

本包是修复测试版本，完整 M1 验收仍在继续。未有完整全绿记录时不记 CI 通过。

## 收尾复验

同一 APK 在修正测试定位和可重复写入夹具后，本地现有 8 项仪器回归全部通过，226.002 秒、`OK (8 tests)`。远端运行 `40077416444` 仍为两项失败，未改记全绿。公开复验记录及停止开发交接见 [阶段性交付报告](delivery-report-2026-10-24.md)。
