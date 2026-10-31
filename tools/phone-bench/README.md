# 手机操作真机基准（WP-0）

量 Hoshino 手机操作在真机上的每步耗时，并和 Mika 0.1.13 在同一台手机上取基线（mika-phone-gap §5 WP-0 / A-7）。
**它只测量，不设门槛**：A-1/A-2/A-5 的验收门槛要等两边基线出来后再定。

输出：`docs/verification/phone-bench-<date>.json`（`schema: hoshino-phone-bench/1`）

| 字段 | 内容 |
|---|---|
| `hoshino.settings_20` | 设置 App 固定 20 步 × `rounds` 轮：`per_step`（total/resolve/act/wait/read 各自 P50/P90）、`per_index`（第 i 步的 P50/P90）、`by_kind` |
| `hoshino.delayed_render` | 延迟渲染夹具 50 次点击（600/1200 ms 后才换页）：`effect_no_effect` = 结果判“没反应”的次数，`late_page_missing` = 返回的屏幕里没有新页面的次数 |
| `hoshino.flutter` | 可选：Flutter 自绘夹具，点击后 `effect=done` 的比例（正确答案永远是 done） |
| `hoshino.preview` | 有人看、画面在动时的预览 fps（上限 `cap_fps`） |
| `hoshino.display_create` | 建虚拟屏 ms（3 次） |
| `hoshino.open_app` | 所有 open_app 步的 ms |
| `hoshino.phone_status_step_timing` | `phone_status` 里同样的最近 50 步统计 |
| `mika` | Mika 基线（由 `mika_baseline.py` 从 Mika 运行记录生成；没有的数字留 null，不估算） |

每步耗时的分段（`StepRunner` 只加计时，不改行为）：`resolve` 找目标与不可逆检查；`act` 执行动作；`wait` 动作后等界面；`read` 动作后读屏；`total` 整步。
`do_steps` 结果的每一步多了 `ms`，`phone_status` 多了 `step_timing`（最近 50 步 P50/P90），本地审计 `phone-audit.log` 每步一行 `step kind=… ok=… resolve=… act=… wait=… read=… total=…`（只有步骤类型和毫秒，不含屏幕内容和输入文字）。

## 跑 Hoshino（小米 15S Pro 或任意 Android 13+）

1. 构建同一把签名的 **fast 包 + 仪器测试包**（fast 不可调试，耗时才有代表性；需要和平时打包一样先有 `rootfs/prepare.py` 的产物）：
   ```
   ./gradlew -Phoshino.testBuildType=fast :app:assembleArm64Fast :app:assembleArm64FastAndroidTest
   ```
   仪器测试包必须和手机上已装的 Hoshino 同签名；手机上的包是别的签名时只能先卸载再装（会丢数据，要重新设置手机操作）。
2. 手机：在 Hoshino 里完成 设置 → 设置手机操作（无障碍 + 内置助手），打开 USB 调试，解锁并保持亮屏。
3. 电脑：
   ```
   python3 tools/phone-bench/run_bench.py --install \
     app/build/outputs/apk/arm64/fast/app-arm64-fast.apk \
     app/build/outputs/apk/androidTest/arm64/fast/app-arm64-fast-androidTest.apk
   ```
   大约 5–10 分钟，期间别动手机。可选参数：`--rounds 3`、`--fixture-trials 50`、`--settings-targets "显示与亮度,声音与振动,电池,我的设备"`（自动挑选失败时用）、`--flutter-package <包名>`。
4. 生成的 JSON 提交到 `docs/verification/`。

手机操作没准备好时测试会被跳过（`Assume`），脚本会报错退出并打印原因。CI 不跑真机部分：`verify.yml` 只编译 `PhoneBenchTest`，模拟器任务都是按类名列出测试，不会带上它；测试本身还要求 `-e phoneBench true` 才会运行。

### 设置 App 固定 20 步

`open_app 设置` → 下/上/下/上滚 → 点 T1 → 下滚 → 返回 → 点 T2 → 返回 → 点 T3 → 下滚 → 上滚 → 返回 → 点 T4 → 返回 → 下/上滚 → 点 T1 → 返回。
T1–T4 只从“点了只会进子页面”的白名单里挑（我的设备、显示与亮度、声音与振动、电池、关于手机…；从不点 WLAN/蓝牙这类带开关的行），实际用到的写在 JSON 的 `targets` 里。每轮开始先按 4 次返回离开设置，所以第 1 步每轮都是真的打开。

### 延迟渲染夹具

在仪器测试包里（`dev.hoshino.app.test`，桌面名「Hoshino 延迟渲染夹具」）：三个按钮「立即」「延迟600毫秒」「延迟1200毫秒」，点后分别在 0/600/1200 ms 时换成「已加载 N」页，延迟期间屏幕完全不变。

### Flutter 夹具（可选）

`flutter-fixture/` 是一个整页自绘、没有任何语义节点的 Flutter App：每点一次换颜色和数字，无障碍树不变、只有画面变。

```
cd tools/phone-bench/flutter-fixture
flutter create --org dev.hoshino.bench --project-name hoshino_flutter_fixture --platforms android .
flutter build apk --release && adb install -r build/app/outputs/flutter-apk/app-release.apk
python3 tools/phone-bench/run_bench.py --flutter-package dev.hoshino.bench.hoshino_flutter_fixture
```

## 同机跑 Mika 0.1.13 取基线

Mika 不带这样的基准，但它的运行记录 `events.jsonl` 对每个动作都记了 `durationMs`（含等界面和默认「标准」节奏的停留）和 `waitMs`；`phone_run` 里的每一步单独记一条（`source: "batch"`）。只有 **debug 包** 会把记录复制到外部存储（`RunStore.mirrorToExternal`），所以：

1. 构建 Mika 0.1.13 debug 包：`git clone https://github.com/wangzan101/mika && cd mika && git checkout 688f59d && ./gradlew :app:assembleDebug`，装到同一台手机，按它的 `docs/setup-guide.md` 完成手机操作设置，节奏保持默认「标准」。
   - 注意：debug 包界面比发布包慢，但动作耗时主要是节奏停留和等界面，影响较小；报告里用 `--mika-build debug` 标明。
   - 只有发布包时：在 Mika「运行统计」里分享导出 JSON，脚本只能算每次操作的平均值（`run_metrics.mean_act_ms`），没有 P50/P90。
2. 在 Mika 里发 3 次同一个任务（目标用 Hoshino 那份 JSON 里的 `targets`，下面以 HyperOS 为例）：
   > 用一次连续操作（phone_run）依次完成，不要额外读屏：打开「设置」；向下滚、向上滚、向下滚、向上滚；点「我的设备」；向下滚；返回；点「显示与亮度」；返回；点「声音与振动」；向下滚；向上滚；返回；点「电池」；返回；向下滚；向上滚；点「我的设备」；返回。
3. 拉记录并合并：
   ```
   adb pull /sdcard/Android/data/com.wangzan101.mika/files/runs ./mika-runs
   python3 tools/phone-bench/run_bench.py --merge-only docs/verification/phone-bench-<date>.json --mika ./mika-runs --mika-build debug
   ```
   `mika.batch_step_ms` 对应 Hoshino 的 `settings_20.per_step.total`，`mika.open_app_ms` 对应 `open_app`。Mika 不记录建屏耗时和预览 fps，这两项留 null。

## 文件

- `run_bench.py`：装包（可选）→ `am instrument` 跑 `PhoneBenchTest` → 拉 JSON → 合并 Mika → 写 `docs/verification/`
- `mika_baseline.py`：Mika 运行记录 → `mika` 段
- `benchstats.py`：P50/P90（最近秩法，与 `StepStats.kt` 一致；`python3 benchstats.py` 自检）
- `flutter-fixture/`：可选 Flutter 夹具源码
- `app/src/androidTest/java/app/hoshino/harness/bench/PhoneBenchTest.kt`、`DelayedRenderActivity.kt`；固定步骤在 `app/src/sharedTest/java/app/hoshino/harness/bench/SettingsPlan.kt`（JVM 单测覆盖）
