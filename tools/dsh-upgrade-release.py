import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True

size_line=''
sizes=pathlib.Path('artifacts/docs/dsh-upgrade-apk-sizes.json')
if sizes.exists():
 arm=next((r for r in json.loads(sizes.read_text()) if r.get('abi')=='arm64-v8a'),None)
 if arm:
  delta=arm['bytes']-77221729
  size_line=f"\n\n本版 arm64 APK 实测 {arm['bytes']:,} 字节（m2.9.2 为 77,221,729 字节，{'+' if delta>=0 else ''}{delta:,} 字节），SHA-256 `{arm['sha256']}`。"
text='''## Hoshino 0.2.1-m2.9.3

包名 `dev.hoshino.app`，versionCode 216（高于 m2.9.2 的 215），沿用原证书（SHA-256 `e20b0b97…8fd1`），**直接覆盖安装，配置与会话保留，不要卸载**。手机请装 `Hoshino-Harness-0.2.1-m2.9.3-arm64-v8a.apk`；`x86_64-debug` 仅供模拟器验收。{SIZE_LINE}

> **首次使用前**：到「设置 → 设置手机操作」**重新连接助手**，新的启动重试逻辑才会生效（旧助手进程仍按旧逻辑运行）。

### 修复：AI 无法打开 App（意图误判）

现象：让 AI「打开微信」时，工具立即被拒，提示「The user is discussing or asking about this…」。原因是内核意图门把这一轮误判为“讨论”，请求根本没到手机侧。根因为代码推断（未拿到出问题那一轮的会话日志），以下四条可能路径全部修复：

- **无审批会话完全跳过意图门**（子 Agent 继承），只记审计。
- **讨论改为一次性软提醒**：不再硬拒；第一次被拦的调用收到提醒，模型如坚持再调一次即执行。旧硬拒保留为可选配置，默认不用。
- **插话重判**：每条用户消息都参与判定，运行中插话「打开微信」会重新判定并放行；先发指令后补一句「好的」也不会被误判。
- **规则补强**：补充「发消息 / 回复 / 点开 / 进入 / 去微信… / 用微信给…」等动作词；命令后跟条件从句（「打开微信，万一有新消息就告诉我」）不再被当成假设；手机只读工具不受门控。
- **App 名解析**：支持 WeChat / weixin / 微信app / 「微 信」等写法和 22 个常用 App 别名；多个候选不猜，列出候选。
- **启动被系统拒绝**：系统返回拒绝码时不再当成功，改用 `am start --display` 在虚拟屏重试一次；仍被拒时明确报 `blocked_by_system`（例如 HyperOS 关联启动 / 自启动拦截），模型不会反复重试。

### 新增：应用内虚拟屏

- **入口**：聊天页在手机任务运行时出现「虚拟屏」栏，可全屏或开小窗；侧栏 → 工具 → 虚拟屏；设置 → 权限与系统 → 虚拟屏。无任务时可「启动虚拟屏」并在里面打开任意 App。
- **手势**：双指捏合 1×–8× 缩放、双指平移、双击在适配与 2.5× 之间切换，「复位」一键回到适配；显示与触摸共用同一变换，放大后点按仍对齐。
- **自由操作**：单指点按 / 长按 / 滑动直接操作虚拟屏，**一碰即接管**、AI 暂停，停手 4 秒自动交还并告诉 AI 你做了什么；也可锁定接管。另有查看模式、输入（中文经剪贴板并立即还原）、返回键。
- **小窗**：可拖动、拖角缩放，横 / 竖屏分别记住位置大小。
- **画面设置**：竖屏 / 横屏 / 跟随 App、帧率 5–60（与悬浮球同一设置）。
- **省电**：App 退到后台即停止应用内渲染；与悬浮球**共用同一帧源**，不重复编码。

### CI 已验证 / 未验证

- CI：编译与单测（含意图判定 17 句指令、插话重判、软提醒与二次放行、无审批跳过、审计不含原话；真内核端到端「什么是闭包？」收到软提醒且不写文件、坚持再调则执行；App 名别名解析；启动结果码分类；缩放往返 ≤1px；小窗布局）、插件与评测门、arm64 真内核启动 + 插件市场 + ZIP 导入、x86_64 模拟器从旧版覆盖安装保留会话与配置、聊天与工具调用。
- **需要小米 15S Pro 真机验收（CI 未覆盖）**：默认档与无审批会话说「打开微信」直接打开；运行中插话「打开微信」执行；「打开 WeChat」「打开微信app」可用；HyperOS 拦截时报 `blocked_by_system`；应用内虚拟屏全屏 / 小窗、捏合 / 平移 / 双击、放大后点按对齐、触摸即接管与 4 秒交还、输入中文、横竖屏与帧率、后台停渲染、与悬浮球同时显示。
'''.replace('{SIZE_LINE}',size_line)
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
