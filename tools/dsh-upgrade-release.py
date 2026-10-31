import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True

size_line=''
sizes=pathlib.Path('artifacts/docs/dsh-upgrade-apk-sizes.json')
if sizes.exists():
 arm=next((r for r in json.loads(sizes.read_text()) if r.get('abi')=='arm64-v8a'),None)
 if arm:
  delta=arm['bytes']-77150769
  size_line=f"\n\n本版 arm64 APK 实测 {arm['bytes']:,} 字节（m2.9.1 为 77,150,769 字节，{'+' if delta>=0 else ''}{delta:,} 字节），SHA-256 `{arm['sha256']}`。"
text='''## Hoshino 0.2.1-m2.9.2

包名 `dev.hoshino.app`，versionCode 215（高于 m2.9.1 的 214），沿用原证书（SHA-256 `e20b0b97…8fd1`），**直接覆盖安装，配置与会话保留，不要卸载**。手机请装 `Hoshino-Harness-0.2.1-m2.9.2-arm64-v8a.apk`；`x86_64-debug` 仅供模拟器验收。{SIZE_LINE}

### 无审批 = 彻底无审批（替代 M2.8 规则）

- **全覆盖**：开启「无审批」后，写文件、shell、联网、分享、截图、手机不可逆步骤等所有审批入口一律自动放行，不再出现审批卡、通知或悬浮球审批按钮；每条放行都写审计。
- **子会话继承**：子 Agent（spawn / fork）沿父会话链继承无审批，不再在子会话里落回手动审批（这是此前“开了无审批还弹卡”的根因）。
- **手机停点放行仅审计**：付款、密码、验证码、人机验证在无审批下照常执行，不再停下交给你，只记一条审计（原因与包名，不记内容）。**这取代了 M2.8「任何档位都交给用户」的规则。**
- 默认仍是手动审批；第一次开无审批只弹一次风险说明。Android 系统自身的确认（安装 APK、运行时权限等）无法绕过。

### 虚拟屏预览

- **缩放**：控制面板收起后，预览可从约 64dp（悬浮球大小）拉到接近全屏；右下角 44dp 触摸区手柄（蓝底白边，看得见）；双指捏合随时可用；尺寸按横 / 竖屏分别记忆；控制面板里有「缩放复位」。
- **不再错位**：显示与触摸共用同一个变换矩阵，往返误差 ≤ 1px；修正图像行填充导致的横向错位与右侧黑边。
- **手势**：单击悬浮球 / 标题栏打开控制面板，双击最小化为悬浮球；拖动不误触。原「双击发送此刻屏幕」已移到控制面板按钮。
- **横屏 / 竖屏 / 跟随 App**：在控制面板切换，跟随 App 时视频全屏等场景自动转横。**首次使用需要到「设置 → 设置手机操作」重新连接助手**（旧助手进程不支持改尺寸，会提示）。
- **触摸即接管**：不用先点「接管」，直接在预览上操作即自动接管、AI 暂停；停手 4 秒自动交还，并告诉 AI 你做了什么。「接管」按钮仍可锁定接管直到交还。

### 其他

- 内核先写运行状态文件再显示「已连接」，避免读到旧版本记录。

### CI 已验证 / 未验证

- CI：编译与单测（含无审批 23 类动作全放行与审计、子 / 孙 Agent 继承、手机停点放行、预览变换往返 ≤1px、行填充裁剪、单击 / 双击判定、自动接管状态机）、插件与评测门、arm64 真内核启动 + 插件市场 + ZIP 导入、x86_64 模拟器从旧版覆盖安装保留会话与配置、聊天与工具调用。
- **需要小米 15S Pro 真机验收（CI 未覆盖）**：无审批会话（含子 Agent、微信发送、支付宝付款页、密码框、验证码页）全程不弹卡且有审计；手动审批会话不变；预览从小球大小拉到近全屏、拖角与捏合、尺寸记忆；放大后点按与画面对齐、右侧无黑边；单击 / 双击 / 拖动；横竖屏与跟随 App（需先重连助手）；触摸即接管与 4 秒交还。
'''.replace('{SIZE_LINE}',size_line)
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
