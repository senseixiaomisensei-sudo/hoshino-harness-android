import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True

size_line=''
sizes=pathlib.Path('artifacts/docs/dsh-upgrade-apk-sizes.json')
if sizes.exists():
 arm=next((r for r in json.loads(sizes.read_text()) if r.get('abi')=='arm64-v8a'),None)
 if arm:
  delta=arm['bytes']-75881665
  size_line=f"\n\n本版 arm64 APK 实测 {arm['bytes']:,} 字节（m2.8.1 为 75,881,665 字节，{'+' if delta>=0 else ''}{delta:,} 字节），SHA-256 `{arm['sha256']}`。"
text='''## Hoshino 0.2.1-m2.8.2（热修复）

包名 `dev.hoshino.app`，versionCode 212（高于 m2.8.1 的 211），沿用原证书（SHA-256 `e20b0b97…8fd1`），**直接覆盖安装，配置与会话保留，不要卸载**。手机请装 `Hoshino-Harness-0.2.1-m2.8.2-arm64-v8a.apk`；`x86_64-debug` 仅供模拟器验收。{SIZE_LINE}

### 修复

- **`phone_*` 手机工具从未加载**（m2.8 / m2.8.1 都受影响）：`android-phone` 插件里 `expect` 和 `phone_do_steps` 步骤的 JSON Schema 缺少 `additionalProperties`，dsh-tools 注册时抛错，插件「未激活」，任何会话都拿不到 `phone_*`；AI 于是改用 `system_shell`（`monkey` / `am start`）在**主屏**上开 App。现已补全严格 Schema；构建时若内置工具缺失直接失败；`self_doctor` 会列出未激活的内置插件。
- **旧的模式白名单屏蔽 `phone_*`**：m2.8.1 及更早保存的自定义/编辑过的模式只列了当时的工具，或被写成空列表 `tools: []`；现在白名单不再隐藏 `phone_*`，空列表视为「全部工具」（仅「聊天」模式保持无工具），App 保存模式时也不再写空列表。
- **`system_shell` 拒绝主屏启动/输入命令**：`monkey`、`am start` / `cmd activity start`、`input`（点击、滑动、输入文字、按键）、`cmd statusbar` 以及跨屏移动任务，未显式指向后台虚拟屏的一律在审批前拒绝，并提示改用 `phone_launch_app` / `phone_do_steps`（`sh -c`、`su -c`、`adb shell` 等包装也会识别）。这是对模型习惯的防护，不是沙盒；`system_shell` 仍只在完全访问会话可用且走审批。
- **App 只在后台虚拟屏打开**：`phone_launch_app` 打开后检查 App 落在哪块屏，若被 Android 拉到主屏会挪回虚拟屏，挪不回则报错并要求告知你、不得改用 `system_shell` 重试。
- **虚拟屏任务前台服务保活**：后台虚拟屏存在期间运行一个前台服务，常驻通知「Hoshino 正在后台操作手机」（只显示状态和 App 包名，不含屏幕内容）带「停止」按钮，Hoshino 退到后台或你在主屏忙别的时任务不被系统回收；任务结束或虚拟屏释放即停止。Android 12+ 若系统不允许从后台启动前台服务，会写入本机审计，任务本身照常进行。

### CI 已验证 / 未验证

- CI：编译与单测、插件测试（启动内核检查模型实际收到的工具列表含 `phone_*`、`system_shell` 守卫用例）、arm64 真内核启动、x86_64 模拟器从旧版覆盖安装保留会话与配置、聊天与工具调用。
- **需要小米 15S Pro 真机验收（CI 未覆盖）**：说「打开抖音」时 AI 调用 `phone_launch_app`、App 出现在后台虚拟屏而主屏不被打断；完全访问会话里让 AI 用 `system_shell` 开 App 会被拒绝；前台服务通知出现/消失与「停止」按钮；Hoshino 在后台、锁屏/熄屏前后长时间任务不被 HyperOS 杀掉；已有后台任务的 App 被拉到主屏时能否挪回。
- 审批规则与「付款、密码、验证码、人机验证交给你」不变。
'''.replace('{SIZE_LINE}',size_line)
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
