import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True
text='修复：Claude（Anthropic）接口接第三方中转/DeepSeek 时报 502 或请求失败。对非 api.anthropic.com 的地址改发标准 Messages 请求：去掉 adaptive 思考、eager 工具流、beta 头等 Claude 专属字段，思考改为标准 budget 形式。官方 Anthropic 地址不变。\n沿用原证书，versionCode 205，直接覆盖安装，会话与配置保留。\n验收：双 ABI 构建签名通过；arm64 内核带 Anthropic 供应商就绪；ZIP 插件导入通过；覆盖安装连接、聊天和工具调用通过。\n'
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
