import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True

size_line=''
sizes=pathlib.Path('artifacts/docs/dsh-upgrade-apk-sizes.json')
if sizes.exists():
 arm=next((r for r in json.loads(sizes.read_text()) if r.get('abi')=='arm64-v8a'),None)
 if arm:
  delta=arm['bytes']-75924673
  size_line=f"\n\n本版 arm64 APK 实测 {arm['bytes']:,} 字节（m2.8.2 为 75,924,673 字节，{'+' if delta>=0 else ''}{delta:,} 字节），SHA-256 `{arm['sha256']}`。"
text='''## Hoshino 0.2.1-m2.9

包名 `dev.hoshino.app`，versionCode 213（高于 m2.8.2 的 212），沿用原证书（SHA-256 `e20b0b97…8fd1`），**直接覆盖安装，配置与会话保留，不要卸载**。手机请装 `Hoshino-Harness-0.2.1-m2.9-arm64-v8a.apk`；`x86_64-debug` 仅供模拟器验收。{SIZE_LINE}

### Agent 循环与评测

- **上下文循环与压缩**：长对话自动压缩，压缩时你的原话逐字保留；模型不可用时有无模型回退；回合策略与结局判定。
- **评测门**：移植 Mika 128 题评测、提示词金样与体积上限、架构守卫、依赖许可证门，CI 每次都跑。

### 手机操作

- **动作后等待判定**：每步操作后按页面实际变化判断是否生效，慢页面不再被误判为“没反应”。
- **复合读工具** `phone_query` / `phone_extract` / `phone_find` / `phone_collect`：在手机上一次完成查找、提取、滚动查找与滚动收集，返回一份结构化结果，少走很多轮。
- **剪贴板安全打字与 `typeAt`**：中文等文本经剪贴板输入并回读校验，用完恢复你原来的剪贴板；`typeAt` 先点输入框再打字。
- **首配 ≤ 2 分钟与无障碍自愈**：同意页并入设置向导（全部默认不勾、逐项勾选）；可选「清理后台后自动恢复」，被强停后由助手恢复无障碍（你手动关闭则暂停，10 分钟最多 3 次）。
- **每步测速**：记录每步耗时，`phone_status` 给出最近 50 步 P50/P90。
- **悬浮球面板**：实时虚拟屏画面 + 当前进程 + 停止按钮；预览**无极缩放**。
- **手动接管 / 交还**：在面板里直接操作虚拟屏，AI 动作暂停等你；交还时把你做了什么告诉 AI。
- **虚拟屏帧率 5–60 fps 可调**（默认 30）。

### 国内网络（不开代理也能连）

- 内核 DNS 改为系统 DNS + 国内备用 DNS（223.5.5.5 / 119.29.29.29 / 180.76.76.76）；网络切换（Wi-Fi ↔ 移动数据）自动更新；IPv4 优先。
- 设置 › 网络与代理：应用内 HTTP 代理，或「继承系统代理」（默认开）；SOCKS5 只用于 shell 工具。改后重启内核生效。
- 连接失败时按错误类型提示（DNS 失败 / 连接超时 / TLS / 被拒绝 / 被重置），并给出换中转站或填代理的建议。
- 注意：**官方 ChatGPT（openai-codex）在国内仍需代理**；DeepSeek 等国内可直连的服务商不需要。

### 实验

- 零拷贝预览（GL 直传）**默认关闭**，可在「设置 → 设置手机操作 → 实验：零拷贝预览」手动打开。

### CI 已验证 / 未验证

- CI：编译与单测、插件与提示词金样测试、评测与许可证门、arm64 真内核启动 + 插件市场 + ZIP 导入、x86_64 模拟器从旧版覆盖安装保留会话与配置、聊天与工具调用。
- **需要小米 15S Pro 真机验收（CI 未覆盖）**：悬浮球面板实时画面、缩放、接管与交还；帧率调节；复合读工具与等待判定在真实 App 上的表现；剪贴板打字；首配时长与 HyperOS 下无障碍自愈；国内 Wi-Fi / 移动数据不开代理连接与网络切换。
- 审批规则与「付款、密码、验证码、人机验证交给你」不变。
'''.replace('{SIZE_LINE}',size_line)
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
