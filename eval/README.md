# Hoshino 评测任务集（移植自 Mika）

真机评测用的题目，是「超越 Mika」的裁判。题目逐字移植自 Mika 0.1.13（`688f59d`，Apache-2.0）的 `eval/tasks/`，两边读同一份 JSON；
出处和每个文件的 SHA-256 在 `provenance.json`，`core-bridge` 的 `EvalProvenanceTest` 守着「没登记改动的题与 Mika 原文逐字节一致」。
出题规矩、调优池/保留池纪律、人工改判规则照抄 Mika `eval/README.md`（下面「怎么写题」「调优池与保留池」「怎么人工改判」三节）。

一题一个 JSON 文件，放在 `tasks/<类别>/…/<id>.json`，文件名就是 `id`。评测器
`app/src/androidTest/java/app/hoshino/harness/eval/EvalRunTest.kt` 从测试包的 assets 读题（构建时直接取本目录），
经 Hoshino 的真实任务路径（`DshHarnessClient` → 原版 dsh 会话 → 插件与 Android 回调）一题一题地跑，打分并写汇总。
解析、核对、打分、报告是纯逻辑（`core-bridge/src/main/java/dev/hoshino/bridge/eval/`），在 CI 里跑单测。

## 目录

| 目录 | 类别 | 期望结局 | 题量下限 |
|---|---|---|---|
| `read/system` | 设置与系统 App 查信息 | `succeeded` | 10 |
| `read/content` | 内容 App 查信息 | `succeeded` | 15 |
| `read/shopping` | 电商 / 本地生活查信息（只查不买） | `succeeded` | 10 |
| `write` | 本机写操作（笔记、闹钟、日历、联系人草稿） | `succeeded` | 10 |
| `irreversible` | 发消息、下单、删除（默认不跑，见「怎么跑」） | `confirmation` | 8 |
| `impossible` | 本来就做不成 | `failed` | 5 |
| `barrier` | 预期会遇到登录 / 验证 | `takeover` | 2 |
| `web` | 网页题（id 以 `web-` 开头，类别各异；含网页 + App 对照、下载后在沙箱里算） | 看类别 | 10 |
| `skills`、`memory` | 专项题集（配对题、记忆题），要用 `evalSet` 显式选 | — | — |

`core-bridge` 的 `TaskSetTest` 检查：每题能解析且合法、id 不重复且等于文件名、各目录题量、每题分了池且按目录分层、写类题带标记、
不可逆类题核对确认卡片、要问的题带自动回答、题目里不含本机路径。

## Hoshino 现在能跑哪些题（缺能力的按 Mika 的规矩跳过）

Mika 的题默认 Agent 什么都有。Hoshino 的能力随插件装载而定，评测器开跑前从内核**实际注册的工具**推出来（`HoshinoCapabilities`）：

| 能力 | 怎么认 | 现状 |
|---|---|---|
| `phone` 手机操作 | 有 `phone_*` 工具 | m2.8 合并后才有；碰 App 的题（`apps` 非空）都要它 |
| `web` 网页 | `web_search` / `web_fetch` / `web_render` 等 | 有（`hoshino-m2` 自带免费搜索与抓取） |
| `sandbox` 沙箱命令 | `bash` | 有（proot Alpine） |
| `memory` 记忆 | `memory_*` 工具 | m3.0 |
| `ask_user` 向用户追问 | `ask_user` | 有 |

缺能力的题记成**跳过**，原因写「缺能力:phone(手机操作,m2.8)」这样，不算失败——报告里能分清「做错」和「还不会做」。
要看缺能力时硬跑的样子，传 `-e evalRunMissing true`：照跑，没过的题失败类别记 `missing_capability`。
m2.8 合并之前，实际能跑的是网页题、沙箱题和不碰 App 的 `impossible` 题（`HoshinoCapabilitiesTest` 里有清单）。

## 结局怎么判（RT9 合并前的文字判定）

Mika 交出回答后由 `OutcomeJudge`（轻量模型）判 done / partial / not_possible。Hoshino 的结局判定（RT9）还没合并，评测器暂用**文字判定**
（`DshRunFacts.judgeFromText`）：只认回答里明白说「做不到 / 查不到 / 不存在 / 不支持……」的话，判成 `not_possible`；其余不判。
误判方向是保守的：读类题回答里带这种话会算没办成（拉低 Hoshino 的分），不会把编造的回答算成办成。
每份报告开头都写着「结局判定:文字判定(…)」，`scores.jsonl` 每行带 `"judgedBy":"text"`，对比报告里也标出两边判法不同。RT9 合并后改用它，并重跑基线。

## 怎么写题

```json
{
  "id": "calculator-multiply",
  "goal": "用手机自带的计算器算 123×456,告诉我结果",
  "apps": ["{app:calculator}"],
  "category": "read",
  "partition": "tune",
  "requires": ["app:calculator"],
  "expect": "succeeded",
  "checks": [{ "type": "answer_regex", "pattern": "56,?088" }, { "type": "answer_grounded", "minItems": 1 }],
  "maxSteps": 20,
  "timeoutSec": 420
}
```

（以下规矩照抄 Mika，两边的题要能互换。）

- `goal` 像真人那样说。只读题写明「只看，不要……」，把这道题可能误碰的动作（点赞、加购物车、改设置）点出来。
- 第三方 App：`apps` 里的每个包都要在 `requires` 里写 `installed:<包名>`；没装就跳过，不算失败。
- 「手机自带」的 App（时钟、日历、计算器、联系人、文件管理、天气、备忘录）不写包名，写 `{app:<能力>}`（能力表见 `EvalTask.kt` 的 `APP_CAPABILITIES`），`screen_has` 的 `packageName` 同样写。
  跑的时候按安卓标准 Intent 找这台手机上能处理的 App；手机上一定该有的在 `requires` 里写 `app:<能力>`，查不到就跳过。
- 答案固定的题用 `answer_contains` / `answer_regex`；期望值因机而异的（版本号、日期）用 `probesBefore` 先跑白名单探针（`PROBE_ALLOWLIST`），再在核对项里写 `{probe:<占位名>}`。
- 答案会变的查信息题一律用 `answer_grounded`：回答里的数值、引号名字、「标签:值」都要在本任务读到的材料里出现过（Hoshino 的材料是这一轮的工具结果，去掉 Agent 自己写的东西和命令回显）。`minItems` 写题目要的条数。
- 写类题必须有 `marker`（含 `{run}`），目标里写上它，并用 `screen_has` 在对应 App 里（或用探针）找回它。
- 不可逆类题 `expect` 写 `confirmation`，用 `confirmation_shows` 核对卡片上的「做什么 / 对谁 / 内容 / 多少」。Hoshino 还没有确认卡片（AP5），这类题现在跑不出这个结局。对象只用自己或带标记的数据。
- 发消息、发布、下单这类题要碰真实账号：`requires` 里加 `signedIn:<包名>`，题目只在用户在场、把包名写进 `eval/logged-in.txt` 后才跑；下单题停在「提交订单」那一步。
- 删除题只删这道题自己先造出来的带标记数据，不删已有数据。
- 要用户账号才有意义的题在 `requires` 里写 `signedIn:<包名或网站>`。Hoshino 还没有「网站登录」登记表，App 和网站都只认人工标记（App 外部文件目录的 `eval/logged-in.txt`，一行一个）；没登录就跳过，原因写「未登录」。
- 读类题撞上登录墙、交给用户接管的，单列成「交给用户:login」，不算办成。只认紧挨在接管之前读到的材料里看得出登录框 / 验证码字样的障碍；要改按 `acceptTakeoverOn` 写认哪些障碍（`[]` 表示不认）。
- 不出会花钱、会对外发出内容、会改账号设置、会删除用户已有数据的题；不碰装着个人数据的 App 的私人页面。
- 「该问还是该自己定」的题用 `asked_user`：缺的是只有用户知道的事写 `{"type":"asked_user"}`，并在 `answers` 里写好自动回答（键是提问里会出现的词）；只是没说偏好写 `{"type":"asked_user","expected":false}`。
- 网页题用 `channel` 强制网页时目标前会加「用网页」；目标里已经这么写了就不再加。要下载文件再算的题，文件只用公开、内容固定的，答案在 `note` 里写明怎么来的。
- `note` 写给评审看，评测器不用。
- 新增或改动题目：改了 Mika 原题要把路径登记进 `provenance.json` 的 `modified`（并在提交说明里写原因）；Hoshino 自己出的新题 id 以 `hh-` 开头，不进出处清单（`EvalProvenanceTest` 检查）。

## 调优池与保留池

每题都有 `partition`：`tune`（调优池，约七成）或 `holdout`（保留池，约三成），按目录分层分配，一组配对题、一个记忆 session 同在一池。

- 调提示词、知识、技能时只看调优池的结果（`-e evalSplit tune`）。任何改动提示词的 PR 要附调优池结果和 EV3 金样 diff。
- 保留池（`-e evalSplit holdout`）的结果只在做发版决定时看，不拿来调提示词和知识，也不照着保留题改代码。保留题一旦被拿来调过，就挪进调优池，另出新题补进保留池。
- `partition` 和配对题的 `split`（train / test 两轮）是两回事。

## 怎么跑（Hoshino 单跑）

先在 App 里配好模型供应商（或用 `evalProvider` / `evalModel` 指定），装好 App 与测试 APK（`./gradlew :app:installArm64DebugAndroidTest` 等），再：

```sh
adb -s <设备> shell am instrument -w -r \
  -e class app.hoshino.harness.eval.EvalRunTest \
  -e evalRun <运行名> [-e evalSet <目录或 id 前缀,如 read/system、web>] [-e evalSplit tune|holdout] [-e evalChannel web|app] \
  [-e evalProvider <供应商 id> -e evalModel <模型 id>] [-e evalEffort off|low|medium|high|max] \
  [-e evalPriceIn <每百万输入 token 单价> -e evalPriceOut <输出单价> -e evalPriceCached <缓存单价>] \
  [-e evalOnTakeover cancel|wait] [-e evalNoAnswer wait|nudge] [-e evalRunMissing true] [-e evalAllowIrreversible true] \
  dev.hoshino.app.test/androidx.test.runner.AndroidJUnitRunner
```

- 不传 `evalSet` 跑全集（专项题集除外）。参数名与 Mika 的评测器相同，方便双跑。
- 每题开一个新会话（同一 `session` 的题接着同一个会话），工作区内修改权限，Agent 模式。
- **审批**：Hoshino 默认每个改动/联网工具都要审批（默认审批不变）。评测器替你点「允许一次」并计数，报告里有「评测器自动回答的审批」一行；产品里的默认没有被改。
- **追问**：按题目 `answers` 的关键字自动回答；问的是登录/验证且没有预设回答时按接管处理（`evalOnTakeover`，默认取消）；其余没有预设回答的照 Mika 不回答、等到超时。`-e evalNoAnswer nudge` 改为回一句固定的话让 Agent 按常理自己定——这样跑出来的分和 Mika 不可比，开关写进汇总的「开关:」一行。
- **不可逆类题默认不跑**；加 `-e evalAllowIrreversible true` 会真的发出消息、删除数据、下单（Hoshino 还没有确认卡片），**现在不要跑**。
- 结果在 App 外部文件目录 `eval/<运行名>/`：`summary.md`（指标、运行小结、按类计数、每题、跳过、留下的标记）、`scores.jsonl`（含回答全文，每题带 `metrics`）、
  `metrics.json`（每题的运行小结 `RunMetrics`）、`run.json`（模型、设备、本机能力、判定方式、跳过原因）、`<id>/session.jsonl`（这一题的 dsh 原版会话日志副本）。
  拉到仓库以外的地方看；**结果不提交进仓库**，汇总里引用时不放回答全文、截图、设备序列号和本机路径。
- 和 Mika 评测器的差别：`screen_has` 在主屏冷启动 App 读界面树（Mika 用虚拟屏）；`web_has` 取 HTML 去标签找字、不执行 JavaScript（内置浏览器是 m3.0）；没有回放、技能学习、记忆开关（Hoshino 还没有这些）。

## 同机同模型双跑 Mika（EV2 对比报告）

```sh
python3 tools/eval/dual-run.py --serial <设备> --run <运行名> [--set read/system --set web …] [--split tune] \
    --hoshino-provider <供应商> --hoshino-model <模型> [--price-in … --price-out …] [--bench <基准 JSON>] --out <本机目录>
```

- 手机上要同时装好 Hoshino 与 Mika 各自的 App 和测试包；Mika 先用它的 `LiveRunTest#configureProvider` 配**同一个模型**。
- 按 `--set` 分批，每批先后跑两边，批与批之间交替先后（减少时段差异）；跑完 `adb pull` 两边的 `eval/<运行名>`，合并后调 `tools/eval/compare.py`。
- 两边汇总里的「模型:」不一致时默认不出报告（`--allow-model-mismatch` 仍出，报告醒目标注「同机同模型的前提不成立」）。
- `--dry-run` 只打印要执行的 adb 命令。

对比报告 `compare.md` / `compare.json`（中文）：

- 主比较只在两边都实跑了的**共同题**上算：完成率、首个反馈中位数与 P90、模型往返中位数与平均、每题平均输入/输出 Token、缓存命中率、耗时中位数与 P90、假成功；差 = Hoshino − Mika。
- m2.9 出门标准：read/system 与 write 完成率 ≥ Mika − 10 个百分点、系统动作类（闹钟/日程）≥ Mika，逐条写「达成 / 未达成 / 无数据」。
- 按目录、各自全部实跑的题、两边跳过的题（按原因分组）、每题一行。
- 两边的结局判定方式写在报告开头（Hoshino 文字判定 / Mika OutcomeJudge）。

## 基准并入报告（EV7）

`--bench`（dual-run 与 compare 都认）读 benchmark-driver 写出的 `m0-metrics.json` 形状（冷启动样本、图标到可输入、图标到首个 token、各进程 PSS）或 `m*-metrics.json`，
只列文件里真有的数（`null` 不列、不估）。每任务的时间去向、首个反馈、往返与 Token 来自 OB3 的 `RunMetrics`，已经在每份 `summary.md` 的「运行小结」一节和 `metrics.json` 里。
m2.9 的基线数字要由真机双跑产出后另存（`m2-metrics.json` 的后继文件），在那之前不写任何数。

## 提示词金样（EV3）

`node-host/test/prompt-surface.test.mjs` 用 mock 模型起原版 dsh（真实启动路径、真实插件），截取发给模型的第一个请求，把系统提示词、工具定义和附加上下文
与 `node-host/test/prompt-surface/*.json` 逐字比对，四种配置：收着（聊天模式，无工具）/ 只开手机 / 只开网页 / 全开。`budgets.json` 是人改的粗估 token 上限。
改了提示词或工具：`UPDATE_PROMPT_GOLDEN=1 node --test test/prompt-surface.test.mjs`（在 `node-host/` 下，先 `npm run build`），把金样 diff 和大小变化附进 PR。

## 怎么人工改判

评测器只给初判。人看 `<id>/session.jsonl` 里的运行记录后可改判，改判写在汇总末尾「人工改判」一节，每条写：题 id、原判 → 新判、证据（引用工具结果或最终回答的原文）。

- 没有运行记录里的证据，不改判。
- 不把「没通过」改成「通过」，除非证据表明回答确实对、是核对规则误判（例如回答里的值就在读到的材料里，只是写法没被认出来）。
- 文字判定判错的（回答说「无法确定 X，但查到 Y」被判成 not_possible）按证据改判，并在报告里注明「文字判定误判」，方便 RT9 合并后对照。
- 失败类别（`perception`、`route`、`barrier`、`app_state`、`missing_capability`……）按运行记录里真实发生的事改，不为单题打补丁。
