import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True
text='''## Hoshino 0.2.1-m2.7

包名 `dev.hoshino.app`，versionCode 209（高于 m2.6 的 208），沿用原证书（SHA-256 `e20b0b97…8fd1`），**直接覆盖安装，配置与会话保留，不要卸载**。手机请装 `Hoshino-Harness-0.2.1-m2.7-arm64-v8a.apk`（非调试 fast 构建）；`x86_64-debug` 仅供模拟器验收。

### 新功能

**流畅度与文件卡**
- 流式输出改为只追加，长会话按轮缓存行、稳定 key，Markdown 增量渲染；单个工具输出只在内存保留 64 KiB 尾部，需要时再从 dsh 日志读全量；会话按 LRU 释放内存。
- 文件卡跟随产生它的那一轮回答（插话的轮次也一样），预览图在 IO 线程降采样。
- 已处理的审批卡折叠进该轮的过程组（设置里可关，默认开）。

**审批档、插话与停止**
- 新增「无审批」档：只能由你主动开启（本会话或设为默认），首次开启有风险说明；出厂默认仍是手动审批。无审批下仍受权限档（只读 / 工作区 / 完全访问）限制，你自己写的 deny 规则仍生效，每次自动放行都写入审计日志，过程组显示「自动放行 N 次」。
- 运行中继续发送 = 插话：走 dsh 原生 steer，消息在下一步被当前任务读取（「已收到，下一步处理」→「已加入当前任务」），不另开任务。
- 停止后会作废没被处理的插话，并写一段 3–6 行的「停止交代」，下次「继续」能接上。

**会话管理**
- 会话删除（支持多选、左滑）、置顶、重命名、导出 Markdown / JSON 到「下载/Hoshino」并分享（已知密钥会被去掉）。删除不改 dsh：先隐藏，内核停止后再清理会话目录，工作区里的文件不删。

**ChatGPT 登录与模型联网搜索**
- 「用 ChatGPT 登录」改为与 Mika 相同的方式（OpenAI 为本机开源应用提供的动态客户端、本机回环、PKCE、JWKS 校验），调用标准 `api.openai.com/v1/responses`。**之前用旧方式登录过的，升级后需要在设置里重新登录一次。**免费版 / Go 会提示需要 Plus / Pro。
- 登录后动态读取套餐可用的模型列表，离线表新增 `gpt-6.1-terra`（1,050,000 上下文、128,000 输出），推理档新增 xhigh。
- 模型自带联网搜索优先（ChatGPT / Responses、部分 Chat Completions 中转、Anthropic 服务端工具），过程中显示「联网搜索」步骤，回答末尾附来源；服务商不支持时自动回退到 Hoshino 自己的搜索。设置里可「检测」并强制开 / 关。
- 发给你已启用的模型服务商的请求本身不再单独弹审批（M2.1 起如此，本版写入文档）；会改文件、跑 shell、联网抓取的工具调用仍默认审批。

### 需要真机验收（CI 未覆盖）
- 流畅度：长会话滚动、大段流式输出时的帧率和内存。
- ChatGPT 真账号登录、套餐模型列表、`gpt-6.1-terra` 实际调用、模型联网搜索及其回退。
- 无审批（风险弹窗、自动放行、审计行）、插话从「已收到」到「已加入当前任务」、停止后的交代。
- 会话删除（含内核重启后清理目录）、多选删除、置顶、重命名、导出 Markdown / JSON 并分享。
- 文件卡在应用重启后仍归在正确的轮次；从 m2.6 覆盖安装后旧交付记录的归轮。

### 已知限制
- CI 验收在 x86_64 模拟器上用本地模拟模型完成（覆盖安装保留旧会话与配置、聊天、设备信息工具、写文件），并在 arm64 真内核上验证启动；以上真机项尚未验证。
- 官方 `api.anthropic.com` 的 Claude 请求形状没有离线测试覆盖。
- 删除会话时，dsh 的会话目录要等内核下次重启前才真正删除。
- Android 系统自身的确认（安装 APK、运行时权限、Shizuku 授权）无法被「无审批」绕过。
'''
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
