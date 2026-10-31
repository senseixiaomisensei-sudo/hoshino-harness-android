import pathlib,json
r=json.loads(pathlib.Path('acceptance/reports/dsh-upgrade-acceptance.json').read_text());assert set(r)=={'2','3'} and all(v.get('passed') is True for v in r.values())
b=json.loads(pathlib.Path('boot/arm64-anthropic-boot.json').read_text());assert b.get('ready') is True
z=json.loads(pathlib.Path('boot/arm64-zip-import.json').read_text());assert z.get('zipImport') is True

size_line=''
sizes=pathlib.Path('artifacts/docs/dsh-upgrade-apk-sizes.json')
if sizes.exists():
 arm=next((r for r in json.loads(sizes.read_text()) if r.get('abi')=='arm64-v8a'),None)
 if arm:
  delta=arm['bytes']-75818353
  size_line=f"\n\n本版 arm64 APK 实测 {arm['bytes']:,} 字节（m2.8 为 75,818,353 字节，{'+' if delta>=0 else ''}{delta:,} 字节），SHA-256 `{arm['sha256']}`。"
text='''## Hoshino 0.2.1-m2.8.1（热修复）

包名 `dev.hoshino.app`，versionCode 211（高于 m2.8 的 210），沿用原证书（SHA-256 `e20b0b97…8fd1`），**直接覆盖安装，配置与会话保留，不要卸载**。手机请装 `Hoshino-Harness-0.2.1-m2.8.1-arm64-v8a.apk`；`x86_64-debug` 仅供模拟器验收。{SIZE_LINE}

### 修复

- **悬浮胶囊闪烁、点了就消失**：自己的浮层/输入法/系统栏事件不再被当成「切回 Hoshino」；显隐加去抖（隐藏需持续约 0.8 秒、最短显示 1.5 秒），任务运行期间常驻；只在可见性真正变化时增删窗口，不再每步重建、反复重置透明度；点击改为展开/收起。
- **系统命令 `Android callback failed (500)`**：根因是发送时档位已同步给内核、但本地会话行没更新，完全访问校验误判；现以内核已验证的档位为准并同步本地。回调出错时返回脱敏后的真实报错，不再只有 500。
- **完全访问不再只认 Shizuku**：内置无线调试助手（已配对且运行）也可用于完全访问与 `system_shell`；Shizuku 未就绪或绑定失败时自动回退内置助手（单条命令最长 14 秒）。两者都不可用时提示去「设置 → 设置手机操作」。
- **打开/操作 App 走手机工具**：模型改用 `phone_launch_app` / `phone_*`，不再要求你去配 Shizuku；非完全访问会话不再暴露 `system_shell`；手机工具报错附设置入口。

### 未真机验证

- 胶囊在小米 15S Pro 上的稳定性；内置助手执行 `monkey` / `am start`、超时与取消；Shizuku 绑定超时回退。
- 完全访问仍须在会话中手动确认，审批与「付款、密码、验证码交给你」规则不变。
'''.replace('{SIZE_LINE}',size_line)
pathlib.Path('build').mkdir(exist_ok=True);pathlib.Path('build/dsh-upgrade-release.md').write_text(text)
