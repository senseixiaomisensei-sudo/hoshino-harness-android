package app.hoshino.harness.core

import android.content.Context
import dev.hoshino.bridge.metrics.DshSessionLog
import dev.hoshino.bridge.metrics.DshTurn
import dev.hoshino.bridge.metrics.RunMetrics
import dev.hoshino.bridge.metrics.RunMetricsStore
import dev.hoshino.bridge.metrics.RunSummaryFormat
import dev.hoshino.bridge.metrics.RunTimingTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 每轮运行的小结（OB3）与运行统计（OB5）的来源。独立组件：只在 [HoshinoApplication] 里装一行，不改内核适配层。
 *
 * - 数字从 dsh 原版会话日志重算（[DshSessionLog]，`filesDir/dsh-home/sessions`，只读），和评测器（EV2）同一套 [RunMetrics]。
 * - 界面一侧实测的两样（第一次看到进展的时刻、等你审批/回答的时间）从 [HarnessClient.events] 收集（[RunTimingTracker]）。
 * - 这台设备上实时跑完的轮次记进 `filesDir/run-metrics.jsonl`（[RunMetricsStore]，只存数字和目标前 80 字，最多 500 份）；
 *   打开旧会话时按日志补算显示，但不记进统计（统计只算在这台设备上看着跑完的）。
 * - 不联网、不上报；单价只存本机，供「预估费用」用。
 */
object RunMetricsHub {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val tracker = RunTimingTracker()
    private val paths = ConcurrentHashMap<String, File>()
    @Volatile private var store: RunMetricsStore? = null
    @Volatile private var sessionsRoot: File? = null
    @Volatile private var prefs: android.content.SharedPreferences? = null

    private val latestFlow = MutableStateFlow<Map<String, RunMetrics>>(emptyMap())
    /** 每个会话最近一个结束了的轮次的小结。 */
    val latest: StateFlow<Map<String, RunMetrics>> = latestFlow.asStateFlow()

    private val revisionFlow = MutableStateFlow(0)
    /** 统计记录变了就加一（运行统计页据此刷新）。 */
    val revision: StateFlow<Int> = revisionFlow.asStateFlow()

    fun install(context: Context) {
        if (store != null) return
        val app = context.applicationContext
        store = RunMetricsStore(File(app.filesDir, RunMetricsStore.FILE_NAME))
        sessionsRoot = File(app.filesDir, "dsh-home/sessions")
        prefs = app.getSharedPreferences("run-metrics", Context.MODE_PRIVATE)
        val client = runCatching { Hoshino.client }.getOrNull() ?: return
        scope.launch { client.events.collect { e -> runCatching { observe(e) } } }
    }

    private fun observe(e: HarnessEvent) {
        val now = System.currentTimeMillis()
        val sid = e.sessionId
        when (e) {
            is HarnessEvent.TurnStarted -> tracker.turnStarted(sid, now)
            is HarnessEvent.AssistantDelta -> if (e.delta.isNotEmpty()) tracker.feedback(sid, now)
            is HarnessEvent.ThinkingDelta -> if (e.delta.isNotEmpty()) tracker.feedback(sid, now)
            is HarnessEvent.ToolCallStarted -> tracker.feedback(sid, now)
            is HarnessEvent.ApprovalRequested -> tracker.waitStarted(sid, e.id, now)
            is HarnessEvent.ApprovalResolved -> tracker.waitEnded(sid, e.id, now)
            is HarnessEvent.QuestionAsked -> tracker.waitStarted(sid, e.id, now)
            is HarnessEvent.QuestionAnswered -> tracker.waitEnded(sid, e.id, now)
            is HarnessEvent.TurnFinished -> scope.launch { finish(sid) }
            else -> Unit
        }
    }

    /** 轮次结束：日志里 `turn/end` 写下后（最多等 3 秒）重算这一轮。 */
    private suspend fun finish(sessionId: String) {
        repeat(FINISH_ATTEMPTS) {
            val turn = lastEnded(sessionId)
            if (turn != null) {
                val runId = DshSessionLog.runId(sessionId, turn.turn)
                val builder = RunMetrics.Builder(runId).apply { turn.events.forEach(::add) }
                val live = tracker.apply(sessionId, turn.events.first().time, builder)
                val metrics = builder.build() ?: return
                val st = store ?: return
                val shown = if (live) {
                    st.append(metrics); revisionFlow.update { it + 1 }; metrics
                } else st.get(runId) ?: metrics
                latestFlow.update { it + (sessionId to shown) }
                return
            }
            delay(FINISH_POLL_MS)
        }
    }

    /** 界面打开一个会话时调用：内存里没有就按日志补算最近一轮（有本机记录的用记录，含界面实测）。 */
    fun request(sessionId: String) {
        if (sessionId.isBlank() || latestFlow.value.containsKey(sessionId)) return
        scope.launch {
            val turn = lastEnded(sessionId) ?: return@launch
            val runId = DshSessionLog.runId(sessionId, turn.turn)
            val m = store?.get(runId) ?: RunMetrics.of(turn.events, runId) ?: return@launch
            latestFlow.update { if (it.containsKey(sessionId)) it else it + (sessionId to m) }
        }
    }

    private fun lastEnded(sessionId: String): DshTurn? {
        val root = sessionsRoot ?: return null
        val file = paths[sessionId]?.takeIf { it.isFile } ?: DshSessionLog.find(root, sessionId)?.also { paths[sessionId] = it } ?: return null
        return DshTurn.split(DshSessionLog.read(file)).lastOrNull { it.ended }
    }

    /** 统计用的记录（旧的在前）。 */
    fun runs(): List<RunMetrics> = store?.all().orEmpty()

    /** 导出 JSON（含 10/30/100/全部的汇总和每次小结），供复制或分享。 */
    fun exportJson(): String = RunMetricsStore.export(runs())

    fun clear() { store?.clear(); revisionFlow.update { it + 1 } }

    /** 用户填的每百万 token 单价（输入、输出、缓存）；没填为 null。 */
    fun prices(): RunSummaryFormat.Prices? {
        val p = prefs ?: return null
        val input = p.getString("priceIn", null)?.toDoubleOrNull() ?: return null
        val output = p.getString("priceOut", null)?.toDoubleOrNull() ?: return null
        return RunSummaryFormat.Prices(input, output, p.getString("priceCached", null)?.toDoubleOrNull() ?: input)
    }

    fun setPrices(input: String, output: String, cached: String) {
        prefs?.edit()?.putString("priceIn", input.trim())?.putString("priceOut", output.trim())?.putString("priceCached", cached.trim())?.apply()
        revisionFlow.update { it + 1 }
    }

    fun priceText(): Triple<String, String, String> {
        val p = prefs ?: return Triple("", "", "")
        return Triple(p.getString("priceIn", "").orEmpty(), p.getString("priceOut", "").orEmpty(), p.getString("priceCached", "").orEmpty())
    }

    private const val FINISH_ATTEMPTS = 20
    private const val FINISH_POLL_MS = 150L
}
