import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True

size_line=''
sizes=pathlib.Path('artifacts/docs/dsh-upgrade-apk-sizes.json')
if sizes.exists():
 arm=next((r for r in json.loads(sizes.read_text()) if r.get('abi')=='arm64-v8a'),None)
 if arm:
  delta=arm['bytes']-77425609
  size_line=f"\n\n本版 arm64 APK 实测 {arm['bytes']:,} 字节（m2.9.4 为 77,425,609 字节，{'+' if delta>=0 else ''}{delta:,} 字节），SHA-256 `{arm['sha256']}`。"
text='''## Hoshino 0.2.1-m2.9.5

包名 `dev.hoshino.app`，versionCode 218（高于 m2.9.4 的 217），沿用原证书（SHA-256 `e20b0b97…8fd1`），**直接覆盖安装，配置与会话保留，不要卸载**。手机请装 `Hoshino-Harness-0.2.1-m2.9.5-arm64-v8a.apk`。{SIZE_LINE}

### 修复：局域网 Ollama

- **点「从接口拉取」闪退**：拉取失败的异常没有被接住，直接让 App 崩溃回桌面。现在失败只显示原因。
- **测试连接连不上**：探测超时从 2 秒放宽到连接 6 秒 / 读取 12 秒；地址可不写 `http://`（如 `192.168.1.5:11434`，自动补 `/v1`）；`/v1/models` 返回 404 时自动改用 Ollama 原生 `/api/tags`。
- **说清楚为什么连不上**：连接被拒、超时、找不到主机、没有路由、TLS 失败分别给出中文原因；填 `127.0.0.1` 时提醒那是手机自己。
- **开了代理也能连局域网模型**：内核的 NO_PROXY 自动加入局域网供应商地址（192.168.x、10.x、172.16–31、100.64–127、.local）。

### 电脑端需要的设置

Ollama 默认只监听电脑本机。请在电脑设置环境变量 `OLLAMA_HOST=0.0.0.0`（Windows：系统环境变量后从托盘退出并重开 Ollama；macOS：`launchctl setenv OLLAMA_HOST 0.0.0.0` 后重开），防火墙放行 11434 端口，手机与电脑连同一个 Wi-Fi，然后在 Hoshino 填 `http://电脑IP:11434/v1`。

### CI 已验证 / 未验证

- CI：编译与单测（含地址补全、局域网主机判断、NO_PROXY、错误原因）、插件与评测门、arm64 真内核启动、x86_64 模拟器覆盖安装保留会话与配置。
- **需要真机验收**：拉取失败不闪退；局域网 Ollama 测试连接成功、拉到模型并能对话。
'''.replace('{SIZE_LINE}',size_line)
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
