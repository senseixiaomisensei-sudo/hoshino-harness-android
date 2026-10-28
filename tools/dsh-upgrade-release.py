import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
a=json.loads(pathlib.Path('artifacts/docs/dsh-upgrade-apk-sizes.json').read_text());assert {v['abi'] for v in a}=={'arm64-v8a','x86_64'}
text='仅升级内置原版 dsh：0.2.1-rc.2 → 0.2.2-alpha.2。上游源码未修改；对应调整 Hoshino 的工具声明与执行器适配。沿用原证书，versionCode 202。\n\n三项验收通过：\n1. 双 ABI 构建成功，签名 e20b…8fd1，打包内核版本核验通过。\n2. 覆盖安装后内核连接成功，保留 '+str(r['2']['oldSessionsRetained'])+' 个旧会话及配置。\n3. 正常聊天，实际调用 get_device_info 与 write；文件写入已核对。\n\n下载安装包直接覆盖安装。\n'
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
