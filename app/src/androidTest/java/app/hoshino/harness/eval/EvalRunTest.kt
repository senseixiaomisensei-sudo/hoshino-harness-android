// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.eval

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.core.ApprovalDecision
import app.hoshino.harness.core.DshHarnessClient
import app.hoshino.harness.core.HarnessEvent
import app.hoshino.harness.core.Hoshino
import app.hoshino.harness.core.ModelRef
import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.core.ReasoningEffort
import app.hoshino.harness.core.TurnOptions
import dev.hoshino.bridge.eval.AppCapabilities
import dev.hoshino.bridge.eval.Check
import dev.hoshino.bridge.eval.Checks
import dev.hoshino.bridge.eval.DshRunFacts
import dev.hoshino.bridge.eval.EvalCategory
import dev.hoshino.bridge.eval.EvalReport
import dev.hoshino.bridge.eval.EvalTask
import dev.hoshino.bridge.eval.EvalTasks
import dev.hoshino.bridge.eval.HoshinoCapabilities
import dev.hoshino.bridge.eval.Observed
import dev.hoshino.bridge.eval.Prices
import dev.hoshino.bridge.eval.Scoring
import dev.hoshino.bridge.eval.TaskScore
import dev.hoshino.bridge.metrics.DshEvent
import dev.hoshino.bridge.metrics.DshSessionLog
import dev.hoshino.bridge.metrics.DshTurn
import dev.hoshino.bridge.metrics.RunMetrics
import dev.hoshino.bridge.metrics.RunTimingTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真机评测（EV2）：经 Hoshino 的真实任务路径（[DshHarnessClient] → 原版 dsh 会话 → 插件与 Android 回调）一题一题地跑 Mika 的题集，
 * 打分并写汇总。只在显式传 `-e evalRun <名字>` 时运行（CI 只编译它）。参数与 Mika `EvalRunTest` 对齐，便于 `tools/eval/dual-run.py` 同机双跑：
 *
 * `-e evalRun <名字> [-e evalSet <目录或 id 前缀,如 read/system、web>] [-e evalSplit tune|holdout] [-e evalChannel web|app]
 *  [-e evalOnTakeover cancel|wait] [-e evalAllowIrreversible true] [-e evalPriceIn <每百万输入单价> -e evalPriceOut <输出单价> [-e evalPriceCached <缓存单价>]]
 *  [-e evalProvider <供应商 id> -e evalModel <模型 id>] [-e evalEffort off|low|medium|high|max] [-e evalRunMissing true] [-e evalNoAnswer wait|nudge]`
 *
 * - 题目在测试包 assets 的 `tasks/<类别>/…`（构建时直接取仓库 `eval/`）。不传 evalSet 跑除专项题集（skills、memory）以外的全部。
 * - 模型：evalProvider/evalModel，没给用设置里的主模型（模型路由 main）。两边双跑时给同一个模型。
 * - 能力：从内核实际注册的工具推出（[HoshinoCapabilities]）。缺能力的题按 Mika 的规矩跳过，原因写明「缺能力:…」；
 *   `-e evalRunMissing true` 时照跑，没过的记成 missing_capability 类。
 * - 审批：Hoshino 默认每个改动/联网工具都要审批；评测器自动「允许一次」并计数（报告里「评测器自动回答的审批」）。
 *   不可逆类题默认不跑（要跑须传 evalAllowIrreversible=true，会真的发出/删除）。
 * - 追问（ask_user）：按题目 answers 的关键字自动回答；问的是登录/验证且没有预设回答时按「接管」处理（evalOnTakeover，默认取消）；
 *   其余没有预设回答的照 Mika 不回答、等到超时（`-e evalNoAnswer nudge` 时改回一句固定的话让 Agent 按常理自己定，两边就不再可比）。
 * - 结局：RT9 结局判定合并前按回答文字判（[DshRunFacts.judgeFromText]），报告里标注。
 * - 结果写到 App 外部文件目录 `eval/<evalRun>/`：summary.md、scores.jsonl、metrics.json、run.json（本次运行的元数据，对比报告用）、
 *   `<题 id>/session.jsonl`（这一题的 dsh 原版会话日志副本）。不要提交进仓库。
 */
@RunWith(AndroidJUnit4::class)
class EvalRunTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    @Test
    fun runEval() {
        val run = args.getString("evalRun")
        assumeTrue(run != null)
        require(RUN_NAME.matches(run!!)) { "evalRun must match $RUN_NAME" }
        val onTakeover = args.getString("evalOnTakeover") ?: "cancel"
        require(onTakeover == "cancel" || onTakeover == "wait") { "evalOnTakeover must be cancel or wait" }
        val allowIrreversible = args.getString("evalAllowIrreversible") == "true"
        val runMissing = args.getString("evalRunMissing") == "true"
        val noAnswer = args.getString("evalNoAnswer") ?: "wait"
        require(noAnswer == "wait" || noAnswer == "nudge") { "evalNoAnswer must be wait or nudge" }
        val evalSplit = args.getString("evalSplit")?.takeIf { it.isNotBlank() }
        require(evalSplit == null || evalSplit in EvalTasks.PARTITIONS) { "evalSplit must be tune or holdout" }
        val split = args.getString("split")
        require(split == null || split == "train" || split == "test") { "split must be train or test" }
        val channel = args.getString("evalChannel")?.takeIf { it.isNotBlank() }
        val effort = args.getString("evalEffort")?.let { e -> ReasoningEffort.entries.first { it.key == e } } ?: ReasoningEffort.Off
        val prices = prices()

        val client = Hoshino.client as DshHarnessClient
        if (client.runtime.state.value.phase != "已连接") client.startKernel()
        check(waitFor(KERNEL_WAIT_MS) { client.runtime.state.value.phase == "已连接" }) { "kernel not connected: ${client.runtime.state.value.phase}" }
        val model = model(client)

        // 能力从内核实际注册的工具来（inspect 的 tools 与生成的 manifest 取并集）
        val inspect = runBlocking { client.control("inspect") }
        val tools = (HoshinoCapabilities.toolsInManifest(inspect.toString()) + HoshinoCapabilities.toolsInManifest(inspect.optJSONObject("manifest")?.toString() ?: "{}")).toSet()
        val have = HoshinoCapabilities.detect(tools)

        val tasks = loadTasks(args.getString("evalSet")).filter { split == null || it.split == split }
            .filter { evalSplit == null || it.partition == evalSplit }
            .map { t -> if (channel != null) t.copy(channel = channel) else t }
            .sortedWith(compareBy({ it.session.orEmpty() }, { it.id }))
        check(tasks.isNotEmpty()) { "no eval tasks matched evalSet=${args.getString("evalSet")}" }
        val errors = EvalTasks.validateAll(tasks)
        check(errors.isEmpty()) { "invalid eval tasks:\n" + errors.joinToString("\n") }

        val ext = context.getExternalFilesDir(null) ?: error("no external files dir")
        val out = File(ext, "eval/$run").apply { deleteRecursively(); mkdirs() }
        val probes = EvalProbes(File(ext, "eval/logged-in.txt"))
        val scores = mutableListOf<Pair<TaskScore, Observed>>()
        val skipped = mutableListOf<Pair<String, String>>()
        val scoresFile = File(out, "scores.jsonl")
        val startedAt = System.currentTimeMillis()
        val sessions = mutableMapOf<String, String>()

        for (t in tasks) {
            if (t.category == EvalCategory.Irreversible && !allowIrreversible) {
                skipped += t.id to "不可逆类题默认不跑(要跑须传 evalAllowIrreversible=true)"; continue
            }
            val missing = HoshinoCapabilities.missing(t, have)
            if (missing.isNotEmpty() && !runMissing) { skipped += t.id to HoshinoCapabilities.skipReason(missing); continue }
            val unmet = t.requires.map(probes::requirementMet).filter { !it.first }.map { it.second }
            if (unmet.isNotEmpty()) { skipped += t.id to unmet.joinToString(";"); continue }

            val (task, observed0) = runOne(client, t, run, model, effort, onTakeover, noAnswer, probes, File(out, t.id), t.session?.let { s -> sessions[s] }) { sid -> t.session?.let { sessions[it] = sid } }
            val observed = if (missing.isNotEmpty() && observed0.failReason == null && Scoring.score(task, observed0).failedChecks.isNotEmpty())
                observed0.copy(failReason = HoshinoCapabilities.skipReason(missing)) else observed0
            val score = Scoring.score(task, observed)
            scores += score to observed
            scoresFile.appendText(EvalReport.scoreLine(task, score, observed, JUDGED_BY) + "\n")
        }

        File(out, "summary.md").writeText(EvalReport.markdown(run, "${model.providerId}/${model.modelId}", scores, skipped, prices,
            flags = "effort=${effort.key},noAnswer=$noAnswer", judging = JUDGING, capabilities = have))
        File(out, "metrics.json").writeText(EvalReport.metricsJson(scores))
        File(out, "run.json").writeText(JSONObject().apply {
            put("engine", RunMetrics.ENGINE); put("run", run); put("model", "${model.providerId}/${model.modelId}"); put("effort", effort.key)
            put("device", Build.MANUFACTURER + " " + Build.MODEL); put("android", Build.VERSION.RELEASE); put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
            put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName); put("dsh", client.kernel.value.dshVersion)
            put("capabilities", JSONArray(have.sorted())); put("judging", JUDGING); put("startedAt", startedAt); put("endedAt", System.currentTimeMillis())
            put("evalSet", args.getString("evalSet") ?: JSONObject.NULL); put("evalSplit", evalSplit ?: JSONObject.NULL); put("channel", channel ?: JSONObject.NULL); put("noAnswer", noAnswer)
            put("ran", scores.size); put("skipped", JSONArray(skipped.map { (id, why) -> JSONObject().put("id", id).put("reason", why) }))
        }.toString(2))
    }

    /** 跑一题：开新会话（同一 session 的题接着同一个会话），发目标，自动处理审批与追问，等这一轮结束，从会话日志取事实。 */
    private fun runOne(
        client: DshHarnessClient, t: EvalTask, run: String, model: ModelRef, effort: ReasoningEffort, onTakeover: String, noAnswer: String,
        probes: EvalProbes, dir: File, reuse: String?, remember: (String) -> Unit,
    ): Pair<EvalTask, Observed> {
        val before = mutableMapOf<String, String>()
        for ((name, probe) in t.probesBefore) {
            val v = runCatching { probes.run(probe) }.getOrNull()
            if (v.isNullOrBlank() || v == "null") return t to harness("探针 $probe 读不到")
            before[name] = v
        }
        val task = EvalTasks.substitute(t, run, before)
        val sid = reuse ?: runCatching { runBlocking { client.createSession("ws-default", "standard", PermissionTier.WorkspaceWrite).id } }
            .getOrElse { return task to harness("开不了会话:${it.javaClass.simpleName}") }
        remember(sid)

        val tracker = RunTimingTracker()
        val finished = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val tookOver = AtomicBoolean(false)
        val approvals = AtomicInteger()
        val answered = ConcurrentHashMap.newKeySet<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { e ->
                if (e.sessionId != sid) return@collect
                val now = System.currentTimeMillis()
                when (e) {
                    is HarnessEvent.TurnStarted -> { started.set(true); tracker.turnStarted(sid, now) }
                    is HarnessEvent.AssistantDelta -> if (e.delta.isNotEmpty()) tracker.feedback(sid, now)
                    is HarnessEvent.ThinkingDelta -> if (e.delta.isNotEmpty()) tracker.feedback(sid, now)
                    is HarnessEvent.ToolCallStarted -> tracker.feedback(sid, now)
                    is HarnessEvent.ApprovalRequested -> {
                        tracker.waitStarted(sid, e.id, now); approvals.incrementAndGet()
                        launch { runCatching { client.respondApproval(sid, e.id, ApprovalDecision.AllowOnce) } }
                    }
                    is HarnessEvent.ApprovalResolved -> tracker.waitEnded(sid, e.id, now)
                    is HarnessEvent.QuestionAsked -> if (answered.add(e.id)) {
                        tracker.waitStarted(sid, e.id, now)
                        val preset = task.answers.entries.firstOrNull { it.key in e.question }?.value
                        when {
                            preset != null -> launch { runCatching { client.answerQuestion(sid, e.id, preset) } }
                            BARRIER_QUESTION.containsMatchIn(e.question) -> {
                                tookOver.set(true)
                                if (onTakeover == "cancel") launch { runCatching { client.stop(sid) } }
                            }
                            noAnswer == "nudge" -> launch { runCatching { client.answerQuestion(sid, e.id, NO_PRESET_ANSWER) } }
                            else -> Unit // 照 Mika：没有预设回答就不回答，等到超时
                        }
                    }
                    is HarnessEvent.QuestionAnswered -> tracker.waitEnded(sid, e.id, now)
                    is HarnessEvent.TurnFinished -> if (started.get()) finished.set(true)
                    else -> Unit
                }
            }
        }
        try {
            val t0 = System.currentTimeMillis()
            runCatching { runBlocking { client.send(sid, task.goal, emptyList(), TurnOptions("standard", PermissionTier.WorkspaceWrite, model, effort, false)) } }
                .onFailure { return task to harness("发送失败:${it.javaClass.simpleName}") }
            if (!waitFor(START_WAIT_MS) { started.get() }) return task to harness("任务没有开始")
            val timeoutMs = (task.timeoutSec ?: DEFAULT_TIMEOUT_SEC) * 1000L
            var timedOut = false
            while (!finished.get()) {
                val elapsed = System.currentTimeMillis() - t0
                if (!timedOut && elapsed > timeoutMs) { timedOut = true; runCatching { runBlocking { client.stop(sid) } } }
                if (timedOut && elapsed > timeoutMs + BUSY_WAIT_MS) return task to harness("超时取消后任务一直没有收尾")
                Thread.sleep(POLL_MS)
            }
            val wallMs = System.currentTimeMillis() - t0

            // 这一轮在 dsh 原版会话日志里的事件（日志写下 turn/end 可能比界面收到稍晚）
            val root = File(context.filesDir, "dsh-home/sessions")
            var turn: DshTurn? = null
            var logFile: File? = null
            waitFor(LOG_WAIT_MS) {
                logFile = DshSessionLog.find(root, sid)
                turn = logFile?.let { f -> DshTurn.split(DshSessionLog.read(f)).lastOrNull { it.ended && it.events.first().time >= t0 - CLOCK_SLACK_MS } }
                turn != null
            }
            logFile?.let { f -> dir.mkdirs(); f.copyTo(File(dir, "session.jsonl"), overwrite = true) }
            val events = turn?.events
            val metrics = events?.let { ev ->
                RunMetrics.Builder(DshSessionLog.runId(sid, turn!!.turn)).apply { ev.forEach(::add); tracker.apply(sid, ev.first().time, this) }.build()
            }
            val resolved = resolveApps(task, probes)
            return resolved to observe(resolved, events, metrics, timedOut, tookOver.get(), wallMs, approvals.get(), probes)
        } finally {
            scope.cancel()
        }
    }

    /** 没有手机操作能力时没有「Agent 打开过的 App」，`{app:<能力>}` 取这台手机上的默认候选。 */
    private fun resolveApps(task: EvalTask, probes: EvalProbes): EvalTask {
        val chosen = EvalTasks.appCapabilities(task).mapNotNull { cap ->
            AppCapabilities.choose(probes.appCandidates(cap), emptyList(), context.packageName)?.let { cap to it }
        }.toMap()
        return EvalTasks.resolveApps(task, chosen)
    }

    /** 一题结束后：按这一轮的会话日志和结束后的探针整理出观察到的事实。打分只看它。 */
    private fun observe(
        task: EvalTask, events: List<DshEvent>?, metrics: RunMetrics?, timedOut: Boolean, tookOver: Boolean, wallMs: Long, approvals: Int, probes: EvalProbes,
    ): Observed {
        if (events == null) return harness("找不到这一轮的会话日志").copy(wallMs = wallMs, approvals = approvals)
        val reason = DshRunFacts.endReason(events)
        val kind = reason?.optString("kind")
        val answer = DshRunFacts.answer(events)
        val outcome = when {
            timedOut -> "timeout"
            tookOver -> "takeover"
            kind == "completed" -> "succeeded"
            kind == "aborted" || kind == "interrupted" -> "cancelled"
            else -> "failed"
        }
        val screenHas = mutableMapOf<String, Boolean>()
        val webHas = mutableMapOf<String, Boolean>()
        val after = mutableMapOf<String, String>()
        for (c in task.checks) when (c) {
            is Check.ScreenHas -> screenHas[Checks.screenKey(c)] = runCatching { probes.screenHas(c.packageName, c.text, c.deepLink) }.getOrDefault(false)
            is Check.WebHas -> webHas[Checks.webKey(c)] = runCatching { probes.webHas(c.url, c.text) }.getOrDefault(false)
            is Check.ProbeEquals -> after[c.probe] = runCatching { probes.run(c.probe) }.getOrDefault("")
            else -> Unit
        }
        val m = metrics
        return Observed(
            outcome = outcome,
            answer = answer,
            evidence = DshRunFacts.evidence(events),
            confirmation = null, // Hoshino 还没有确认卡片（AP5）
            screenHas = screenHas,
            probes = after,
            steps = events.filter { it.type == "tool/call" }.mapNotNull { it.step }.distinct().size,
            wallMs = wallMs,
            inputTokens = m?.inputTokens ?: 0,
            outputTokens = m?.outputTokens ?: 0,
            cachedTokens = m?.cachedTokens ?: 0,
            failReason = if (timedOut || tookOver) null else DshRunFacts.failReason(reason),
            goal = task.goal,
            modelCalls = m?.modelCalls ?: 0,
            modelMs = m?.modelMs ?: 0,
            toolMs = m?.toolMs ?: 0,
            webHas = webHas,
            takeoverBarrier = DshRunFacts.takeoverBarrier(events),
            askedUser = DshRunFacts.askedUser(events),
            today = java.time.LocalDate.now(),
            judged = if (outcome == "succeeded") DshRunFacts.judgeFromText(answer) else null,
            metrics = m?.copy(judged = if (outcome == "succeeded") DshRunFacts.judgeFromText(answer) else null),
            approvals = approvals,
        )
    }

    private fun harness(why: String) = Observed("failed", "", emptyList(), null, emptyMap(), emptyMap(), 0, 0, 0, 0, 0, Scoring.HARNESS_PREFIX + why)

    private fun model(client: DshHarnessClient): ModelRef {
        val provider = args.getString("evalProvider")
        val modelId = args.getString("evalModel")
        val ref = if (provider != null && modelId != null) ModelRef(provider, modelId) else client.routing.value.main
            ?: client.providers.value.firstOrNull { it.enabled && it.models.isNotEmpty() }?.let { ModelRef(it.id, it.models.first().id) }
            ?: error("no model: configure a provider first or pass -e evalProvider -e evalModel")
        val p = client.providers.value.firstOrNull { it.id == ref.providerId } ?: error("provider ${ref.providerId} not configured")
        check(p.models.any { it.id == ref.modelId }) { "model ${ref.modelId} not in provider ${ref.providerId}" }
        return ref
    }

    private fun prices(): Prices? {
        val inP = args.getString("evalPriceIn")?.toDouble() ?: return null
        val outP = args.getString("evalPriceOut")?.toDouble() ?: error("evalPriceOut missing")
        return Prices(inP, outP, args.getString("evalPriceCached")?.toDouble() ?: inP)
    }

    /** 测试包 assets 下 `tasks/` 里的题；[set] 是相对 `tasks/` 的目录（如 read/system、web）或 id 前缀。 */
    private fun loadTasks(set: String?): List<EvalTask> {
        val assets = instrumentation.context.assets
        fun walk(path: String): List<String> {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) return if (path.endsWith(".json")) listOf(path) else emptyList()
            return children.sorted().flatMap { walk("$path/$it") }
        }
        return walk(TASKS_DIR).map { it.removePrefix("$TASKS_DIR/") }
            .filter { rel -> set != null || SUITE_DIRS.none { rel.startsWith("$it/") } }
            .map { rel -> rel to runCatching { EvalTasks.parse(assets.open("$TASKS_DIR/$rel").bufferedReader().use { it.readText() }) }
                .getOrElse { throw IllegalStateException("cannot parse eval task $rel", it) } }
            .filter { (rel, task) -> set == null || rel.startsWith("$set/") || task.id.startsWith(set) }
            .map { it.second }
    }

    private fun waitFor(timeoutMs: Long, test: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!test()) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    companion object {
        private val RUN_NAME = Regex("^[A-Za-z0-9_-]+$")
        private const val TASKS_DIR = "tasks"
        /** 专项题集：配对题、记忆题。不传 evalSet 时不跑。 */
        private val SUITE_DIRS = listOf("skills", "memory")
        private const val DEFAULT_TIMEOUT_SEC = 600
        private const val POLL_MS = 250L
        private const val START_WAIT_MS = 30_000L
        private const val BUSY_WAIT_MS = 60_000L
        private const val LOG_WAIT_MS = 10_000L
        private const val KERNEL_WAIT_MS = 300_000L
        /** 设备时钟与内核时钟同源；留一点余量认「这一轮」。 */
        private const val CLOCK_SLACK_MS = 2_000L
        private val BARRIER_QUESTION = Regex("登录|登入|验证码|人机验证|安全验证|sign ?in|log ?in|captcha", RegexOption.IGNORE_CASE)
        const val NO_PRESET_ANSWER = "（评测器：这个问题没有预设回答。请按常理自己决定，并在回答里说明你的假设。）"
        const val JUDGED_BY = "text"
        const val JUDGING = "文字判定(RT9 结局判定合并前的保守替代:回答里明白说做不成/查不到/不存在才判 not_possible,其余不判;人工改判规则见 eval/README.md)"
    }
}
