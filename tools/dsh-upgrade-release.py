import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True

size_line=''
sizes=pathlib.Path('artifacts/docs/dsh-upgrade-apk-sizes.json')
if sizes.exists():
 arm=next((r for r in json.loads(sizes.read_text()) if r.get('abi')=='arm64-v8a'),None)
 if arm:
  delta=arm['bytes']-77116309
  size_line=f"\n\n本版 arm64 APK 实测 {arm['bytes']:,} 字节（m2.9 为 77,116,309 字节，{'+' if delta>=0 else ''}{delta:,} 字节），SHA-256 `{arm['sha256']}`。"
text='''## Hoshino 0.2.1-m2.9.1（热修复）

包名 `dev.hoshino.app`，versionCode 214（高于 m2.9 的 213），沿用原证书（SHA-256 `e20b0b97…8fd1`），**直接覆盖安装，配置与会话保留，不要卸载**。手机请装 `Hoshino-Harness-0.2.1-m2.9.1-arm64-v8a.apk`；`x86_64-debug` 仅供模拟器验收。{SIZE_LINE}

### 修复

对话或任务跑一会儿后，顶部报「内核拒绝请求：session "…" is already owned…」，或过程卡显示「已运行 0 步 · 0s」+「请求未完成（TRANSPORT）：Connection error.」。原因是内核被系统杀掉或崩溃后，残留的 node 进程仍占着会话锁，新内核打不开同一会话；崩溃/重启期间本地连接断开也会落到同一张错误卡。

- **启动前清理残留 node**：内核启动前回收所有残留的内核进程，释放会话锁。
- **内核崩溃自动重启**：内核意外退出后自动重启（1 分钟内最多 3 次）。
- **本地连接心跳与断线重连**：App 与内核之间的本地连接加心跳，断开后自动重连。
- **会话被占 / 忙 / 丢失时自动恢复并重试一次**：会话被占用或本地连接断开时重启内核并重试一次（提示「内核已重启，已自动恢复」）；会话忙时先取消残留回合、等空闲；会话未打开时重新加载后重试。
- **node 堆上限**：按手机总内存的 1/4 设置（512–2048 MiB），内存不足会在错误分类里明确显示。
- **完整错误弹窗**：内核错误和长错误改为对话框显示完整文本，可长按选择复制，带「重试」按钮，不再被 Toast 截断。
- **内核审计日志**：每次恢复记录到本机 `kernel-audit.log`（已脱敏，含崩溃原因），不上传。

### CI 已验证 / 未验证

- CI：编译与单测（含错误分类、重试与忙状态机单测）、插件与评测门、arm64 真内核启动 + 插件市场 + ZIP 导入、x86_64 模拟器从旧版覆盖安装保留会话与配置、聊天与工具调用。
- **需要小米 15S Pro 真机验收（CI 未覆盖）**：长时间对话/任务后不再出现「session … is already owned」和「TRANSPORT / 0 步 0s」；HyperOS 杀掉内核后能自动重启并接着对话；切后台、锁屏后回来连接自动恢复；错误弹窗可复制与重试。
- 审批规则与「付款、密码、验证码、人机验证交给你」不变。
'''.replace('{SIZE_LINE}',size_line)
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
