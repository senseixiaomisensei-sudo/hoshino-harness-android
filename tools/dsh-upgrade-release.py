import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
text='修复：配置了 Anthropic（Claude）供应商时内核启动失败、一直连不上。原因是 android-api 预加载了不存在的 pi-ai/api/anthropic，应为 anthropic-messages；预加载失败也不再拖垮内核。\n沿用原证书 e20b…8fd1，versionCode 203，直接覆盖安装，会话与配置保留。\n验收：双 ABI 构建签名通过；arm64 真机架构上带 Anthropic+OpenAI 供应商内核就绪；覆盖安装连接、聊天和工具调用通过。\n'
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
