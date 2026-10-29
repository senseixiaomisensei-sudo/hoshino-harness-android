import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True
text='新增：插件市场。插件 → 发现，读取与电脑版 DSH「发现」相同的 format 1 插件索引，默认内置 Awesome DSH Plugin 精选（3900+）与开放插件市场两个源，可添加/移除索引源；支持模糊搜索、分类、排序、分页、版本列表、安装前确认来源（npm 与 git 子目录都支持），已安装/可更新状态自动标注；Agent 也获得只读 plugin_market 工具。\n流畅度：arm64 安装包改为非调试构建（同签名，可直接覆盖），启用 ART 预编译与 Compose 基线配置；流式输出按帧合并，长回复不再每个字都重排整条消息；市场列表懒加载、图标缓存。\n沿用原证书，versionCode 207，直接覆盖安装，会话与配置保留。\n验收：双 ABI 构建签名通过；arm64 真实内核启动、ZIP 导入、两个索引读取与 npm/git 插件安装通过；x86_64 覆盖安装连接、聊天、工具调用通过；非调试构建覆盖安装后启动并连上内核。\n'
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
