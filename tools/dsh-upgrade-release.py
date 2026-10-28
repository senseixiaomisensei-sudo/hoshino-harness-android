import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True
text='新增：插件页右上角 + →「从本地 ZIP 导入」。ZIP 根目录或唯一子目录里有 package.json（或 index.js）即可，依赖自动安装，导入后立即加载。\n沿用原证书，versionCode 204，直接覆盖安装，会话与配置保留。\n验收：双 ABI 构建签名通过；arm64 内核带 Anthropic 供应商就绪；arm64 上 ZIP 插件导入与加载通过；覆盖安装连接、聊天和工具调用通过。\n'
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
