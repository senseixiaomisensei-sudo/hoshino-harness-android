import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True
text='修复：插件市场两个索引源都显示“索引读取失败”。现在内核读取失败时会自动改用 Android 系统网络（走系统代理 / VPN）下载索引，jsDelivr 源还会依次尝试 fastly / gcore / testingcf 镜像；跟随 https 跳转，超时放宽到 30 秒，失败时会写明每个地址的具体原因。安装插件时也会把系统代理传给 npm / git。\n包含 m2.5 的插件市场与流畅度优化。沿用原证书，versionCode 208，直接覆盖安装，会话与配置保留。\n'
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
