import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True
text='修复：发消息报“unknown prompt variable {{cwd}} in section deployment:persona-suffix”，整轮请求失败。现在补上 cwd（=工作区）、home、os、platform 变量；人设或预设里其他未注册的 {{变量}} 原样保留，不再中断对话。保留 m2.3 的 Claude 第三方中转兼容。\n沿用原证书，versionCode 206，直接覆盖安装，会话与配置保留。\n验收：双 ABI 构建签名通过；arm64 内核就绪；ZIP 插件导入通过；覆盖安装连接、聊天和工具调用通过。\n'
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
