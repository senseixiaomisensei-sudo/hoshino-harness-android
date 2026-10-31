import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True

size_line=''
sizes=pathlib.Path('artifacts/docs/dsh-upgrade-apk-sizes.json')
if sizes.exists():
 arm=next((r for r in json.loads(sizes.read_text()) if r.get('abi')=='arm64-v8a'),None)
 if arm:
  delta=arm['bytes']-77418333
  size_line=f"\n\n本版 arm64 APK 实测 {arm['bytes']:,} 字节（m2.9.3 为 77,418,333 字节，{'+' if delta>=0 else ''}{delta:,} 字节），SHA-256 `{arm['sha256']}`。"
text='''## Hoshino 0.2.1-m2.9.4

包名 `dev.hoshino.app`，versionCode 217（高于 m2.9.3 的 216），沿用原证书（SHA-256 `e20b0b97…8fd1`），**直接覆盖安装，配置与会话保留，不要卸载**。手机请装 `Hoshino-Harness-0.2.1-m2.9.4-arm64-v8a.apk`；`x86_64-debug` 仅供模拟器验收。{SIZE_LINE}

### 只有按「接管」才接管

- 在虚拟屏画面上触摸**不再自动接管**，也不再 4 秒自动交还。只有点「接管」后 AI 才暂停、你的点按 / 滑动 / 输入才会送到虚拟屏；点「交还」AI 继续，并收到你操作的摘要。
- 未接管时触摸画面会提示「先点「接管」才能操作虚拟屏」；应用内页面、聊天小窗和悬浮球面板行为一致。

### 应用内虚拟屏与悬浮球彻底分开

- 修复：在软件内操作虚拟屏（尤其接管后）会再弹出悬浮球的显示层、无法使用。现在只要应用内虚拟屏页面或小窗在显示、或 Hoshino 在前台，悬浮球与其面板一律隐藏，且隐藏不会结束你在应用内的接管。离开 Hoshino 后悬浮球照常出现。

### 放大的是虚拟屏本身

- 应用内双指捏合改为**放大 / 缩小虚拟屏窗口本身**（全屏页 25%–100%，聊天小窗按宽度），不再放大画面内容；尺寸会记住，页面右上角「复位」回到最大。双击直接送给 App。

### CI 已验证 / 未验证

- CI：编译与单测（含「默认只有按钮才接管」状态机）、插件与评测门、arm64 真内核启动 + 插件市场 + ZIP 导入、x86_64 模拟器覆盖安装保留会话与配置、聊天与工具调用。
- **需要小米 15S Pro 真机验收**：未接管时触摸不操作、点「接管」后可操作且 AI 暂停、「交还」后 AI 继续；软件内接管时不再弹出悬浮层；捏合放大的是虚拟屏窗口；离开软件后悬浮球正常。
'''.replace('{SIZE_LINE}',size_line)
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
