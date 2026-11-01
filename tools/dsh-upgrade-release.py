import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True

size_line=''
sizes=pathlib.Path('artifacts/docs/dsh-upgrade-apk-sizes.json')
if sizes.exists():
 arm=next((r for r in json.loads(sizes.read_text()) if r.get('abi')=='arm64-v8a'),None)
 if arm:
  delta=arm['bytes']-77434865
  size_line=f"\n\n本版 arm64 APK 实测 {arm['bytes']:,} 字节（m2.9.5 为 77,434,865 字节，{'+' if delta>=0 else ''}{delta:,} 字节），SHA-256 `{arm['sha256']}`。"
text='''## Hoshino 0.2.1-m2.10

包名 `dev.hoshino.app`，versionCode 219（高于 m2.9.5 的 218），沿用原证书（SHA-256 `e20b0b97…8fd1`），**直接覆盖安装，配置与会话保留，不要卸载**。手机请装 `Hoshino-Harness-0.2.1-m2.10-arm64-v8a.apk`。{SIZE_LINE}

### 新功能

- **语音输入**：输入栏按住说话，边说边出字，上滑取消；任务运行中松手即作为插话，只说「停」按停止处理。设置 › 模型 › 语音转文字可单独选模型（自动试转验证、记住写法）；悬浮胶囊长按说话，可拖到「改一改」「取消」。3 秒无响应提示，单段 30 秒自动切段。
- **锁屏自动续**：锁屏或熄屏时任务暂停并显示「等待解锁」（胶囊、通知、应用内虚拟屏），解锁自动继续；累计锁 10 分钟才如实失败。新增「任务期间保持亮屏并调暗」，默认开。
- **插话撤回 / 编辑**：长按模型还没取走的插话可撤回或编辑，与模型取走只会一个成功。
- **测试连接真调模型**：真发一次极小请求，5xx/零额度自动换候选，全 5xx 才判整家不可用，逐个写明原因。
- **用账号登录 OpenRouter**：供应商页最上方，免填 Key（PKCE，Key 存 Keystore）。
- **关于页**：显示真实版本号；「检查更新」从本仓库 Releases 下载 arm64 包，核对大小、包名、版本、签名后再交系统安装（本版起可用，下一版开始能从这里升级）。

### 修复

- 安卓 10–12 点「配对」崩溃（`BigInteger.TWO`），并加守卫防同类 API。
- 部分设备后台中文输入失败（移植 ShellClipboard，直接调 IClipboard）。
- 防截屏错误码版本判断改为 API 34；配对引导离开再回来重新计时 10 分钟；无障碍重连后事件不丢。
- 断流重试：清掉已显示的半句；结束原因已到但连接被重置也算完成；提示改为「连接在上游被断开」。
- 自学技能保存：结构校验、版本指纹、写后读回一致才算存好。

### CI 已验证 / 未验证

- CI：编译与单测（新增约 100 项）、插件与评测门、真 dsh 内核端到端（撤回、断流、技能保存）、arm64 真内核启动、x86_64 模拟器覆盖安装保留会话与配置。
- **需要真机验收**：按住说话手感与真实转写；锁屏后解锁自动续、调暗是否生效；长按插话撤回；OpenRouter 登录回调；检查更新整条安装路径；Android 10–12 配对；小米后台中文输入。
'''.replace('{SIZE_LINE}',size_line)
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
