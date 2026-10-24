# M1 preview.3：请求兼容与 Shizuku 探测

用户反馈：正常模型发送“你好”后只有通用失败提示；手机已安装 Shizuku，应用仍提示安装。

修复范围：

- 无推理模型省略 `reasoningEffort`。原版 dsh 会拒绝对此类模型显式传入 `off`，错误码为 `session/model-unavailable`。
- 不再由 Anthropic profile 强制开启 medium thinking；原生 Messages API 不变。
- 处理常见根地址、`/v1` 和完整 Messages/Chat Completions 端点，避免重复拼接。
- 已有 API key 输入框留空保留原值，修复旧占位符和复制的 Bearer 前缀；只在内存与 Keystore 加密存储间传递。
- 读取原版 `TurnEndReasonMap.error.error: LlmFailure`，错误卡片显示脱敏详情和状态，不把响应正文写入应用日志。
- 用官方 MIT Shizuku API/Provider 13.1.5 替换固定“未安装”占位状态。添加 Android 包可见性声明、真实 Binder/授权监听、安装变化监听和返回前台刷新。安装、运行和授权分别判断，点击按钮才请求授权。

dsh 仍为未修改的 `0.2.1-rc.2`，未改变 Agent loop、原版 JSONL 持久化或 KernelLauncher。此修复不新增系统命令执行通道。

| 验证 | 当前证据 |
|---|---|
| 桌面原版 dsh，两种协议共 6 轮流式、设备工具、write 和切换 | `.research/m1-provider-fix-node-test.log`；真实厂商因没有凭据跳过 |
| 请求兼容修复的双 ABI 构建、单测、测试 APK | `.research/m1-provider-fix-build.log`，成功 |
| Shizuku 修复后的双 ABI 最终构建 | `.research/m1-shizuku-fix-build.log`，进行中 |
| 官方 Shizuku 验收夹具 | `tools/test-fixtures/shizuku-manager.json` 固定原始 APK 哈希和签名；不打入主包 |
| 原生聊天和实际安装探测回归 | `M1ConversationSmokeTest`、`ShizukuDetectionTest`，待最终包执行 |

仅确认有记录的结果。用户特定接口和 arm64 真机现场尚未复测；完整 M1 验收未完成。


最终包 versionCode 102，沿用原 Debug 签名 e20b0b97868f158e8570f712b4d488b4f7671ecfc7f5ad72cd66b04a8a5e8fd1。arm64-v8a 82,231,030 字节，SHA256 e5e3f29dbfdde363256852ec54aa1f24979fff2c5fcc760568fc8beac6176370；x86_64 84,685,280 字节，SHA256 3528e3361a6b914e8b11c734a4a23468ec8d40dbad090bd69ccb500d3f1edcc9。服务端上传 digest 与本地相同。

最新 CI 40065685848 在独立 proot 源码构建阶段失败，原因是对 GitHub codeload 使用已下载文件的 Range 续传。已改为完整下载并保留 SHA 校验，增加不支持 Range 的 HTTP 本地回归，等待新 CI；不将本地成功写成 CI 全绿或完整 M1 通过。
