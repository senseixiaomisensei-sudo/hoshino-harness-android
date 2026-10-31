// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.metrics

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次运行（Hoshino 里是会话的一轮）的小结。口径照抄 Mika `core/record/RunMetrics.kt`（字段同名同义，便于两边直接比），
 * 输入换成 dsh 原版会话日志事件（[DshEvent]），全部是纯函数，界面与评测器用同一个 [Builder]。
 *
 * - 时间从 `turn/start` 算起（Mika 从 TaskStarted 算起，准备工作不计）；[totalMs] 到 `turn/end`，没结束的到最后一个事件。
 * - [firstFeedbackMs]：开始到用户第一次看到这次运行的进展。界面实测到了（[feedbackSource] = "ui"，第一段流式文字/思考/工具卡出现）
 *   就用实测；否则用日志里第一个 token 或第一个工具调用的时间（"log"）。[firstTokenMs] 总是日志值。
 * - [modelCalls]：主循环的模型请求次数 = 这一轮的 dsh `step/end` 数（dsh 每个 step 正好一次模型调用；与 dsh-session-stats 同口径）。
 *   [callsWithoutUsage] 是其中服务商没报用量的。
 * - Token：以服务商返回的 usage 为准。dsh 的 usage 是分开记的（`inputTokens` 只含未命中缓存的部分），这里换成 Mika 的口径：
 *   [inputTokens] = 未命中 + 缓存读 + 缓存写，[cachedTokens] = 缓存读，所以缓存命中率 = cached / input 两边可比。
 * - [modelMs]：各 step 从 `step/start` 到 `assistant/message` 之和，减去其中的重试等待（[retryWaitMs] 另记，`llm/retry`→`llm/retry-started`）。
 * - [observeMs] / [actMs]：工具调用 `tool/call`→`tool/result` 的时长，按 [ToolKinds] 分读和操作；控制类（ask_user、todo）不算。
 *   等用户的时间（审批、回答问题；由界面告诉 [Builder.userWait]）从工具时长里扣掉，记在 [userWaitMs]，落进 [otherMs]。
 * - [otherMs]：总时长减模型、读、操作：等用户、重试退避、控制类调用和其他开销。
 * - [outcome]：`turn/end.reason.kind` → succeeded / failed / cancelled / unfinished；[failKind] 记原始的 kind（error、max-tokens……）。
 */
data class RunMetrics(
    val runId: String = "",
    val goal: String = "",
    val startedAtMs: Long = 0,
    val totalMs: Long = 0,
    val ended: Boolean = false,
    val firstFeedbackMs: Long? = null,
    val modelCalls: Int = 0,
    val lightCalls: Int = 0,
    val callsWithoutUsage: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val allCalls: Int? = null,
    val allInputTokens: Long? = null,
    val allOutputTokens: Long? = null,
    val modelMs: Long = 0,
    val retryWaitMs: Long = 0,
    val observeMs: Long = 0,
    val actMs: Long = 0,
    val uiWaitMs: Long = 0,
    val observeCalls: Int = 0,
    val actCalls: Int = 0,
    val steps: Int = 0,
    val outcome: String = UNFINISHED,
    val failKind: String? = null,
    val judged: String? = null,
    // ---- 以下是 Hoshino 补的（Mika 小结里没有） ----
    val engine: String = ENGINE,
    val feedbackSource: String? = null,
    val firstTokenMs: Long? = null,
    val toolMs: Long = 0,
    val toolCalls: Int = 0,
    val retries: Int = 0,
    val reasoningTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val userWaitMs: Long = 0,
    /** 本轮最慢的 3 个工具调用（名字与秒数），小结展开时显示。 */
    val slowestTools: List<ToolTiming> = emptyList(),
) {
    data class ToolTiming(val name: String, val ms: Long)

    val otherMs: Long get() = (totalMs - modelMs - observeMs - actMs).coerceAtLeast(0)

    /** 输入加输出（缓存命中含在输入里）。 */
    val tokens: Long get() = inputTokens + outputTokens

    /** 缓存命中率（0–1）；没报输入时为 null。 */
    val cacheHitRate: Double? get() = if (inputTokens <= 0) null else cachedTokens.toDouble() / inputTokens

    /** 办成了：报了成功（含没能核实的），也没判成只办了一部分或办不到。 */
    val succeeded: Boolean get() = outcome in SUCCESS && judged !in NOT_DONE

    /** 按每百万 token 单价估算的费用；单价由用户填，不写进仓库。 */
    fun cost(inputPerM: Double, outputPerM: Double, cachedPerM: Double): Double =
        ((inputTokens - cachedTokens).coerceAtLeast(0) * inputPerM + cachedTokens * cachedPerM + outputTokens * outputPerM) / 1_000_000

    fun toJson(): JSONObject = JSONObject().apply {
        put("runId", runId); put("goal", goal); put("startedAtMs", startedAtMs); put("totalMs", totalMs); put("ended", ended)
        put("firstFeedbackMs", firstFeedbackMs ?: JSONObject.NULL); put("modelCalls", modelCalls); put("lightCalls", lightCalls)
        put("callsWithoutUsage", callsWithoutUsage); put("inputTokens", inputTokens); put("outputTokens", outputTokens); put("cachedTokens", cachedTokens)
        put("allCalls", allCalls ?: JSONObject.NULL); put("allInputTokens", allInputTokens ?: JSONObject.NULL); put("allOutputTokens", allOutputTokens ?: JSONObject.NULL)
        put("modelMs", modelMs); put("retryWaitMs", retryWaitMs); put("observeMs", observeMs); put("actMs", actMs); put("uiWaitMs", uiWaitMs)
        put("observeCalls", observeCalls); put("actCalls", actCalls); put("steps", steps); put("outcome", outcome)
        put("failKind", failKind ?: JSONObject.NULL); put("judged", judged ?: JSONObject.NULL)
        put("engine", engine); put("feedbackSource", feedbackSource ?: JSONObject.NULL); put("firstTokenMs", firstTokenMs ?: JSONObject.NULL)
        put("toolMs", toolMs); put("toolCalls", toolCalls); put("retries", retries); put("reasoningTokens", reasoningTokens)
        put("cacheWriteTokens", cacheWriteTokens); put("userWaitMs", userWaitMs)
        put("slowestTools", JSONArray(slowestTools.map { JSONObject().put("name", it.name).put("ms", it.ms) }))
        put("otherMs", otherMs)
    }

    /**
     * 按事件顺序累计一轮；[build] 随时可调。只留每个工具调用的几项数字，不留参数和结果原文。
     * [runId] 一般是 `<会话 id>#<turn>`。
     */
    class Builder(private val runId: String = "") {
        private class Step(val start: Long, var firstToken: Long? = null)
        private class Call(val name: String, val start: Long)
        private class Done(val name: String, val start: Long, val end: Long, val kind: String?)

        private var goal = ""
        private var started: Long? = null
        private var last = 0L
        private var endReason: String? = null
        private var endTime: Long? = null
        private var steps = 0
        private var noUsage = 0
        private var input = 0L
        private var output = 0L
        private var cacheRead = 0L
        private var cacheWrite = 0L
        private var reasoning = 0L
        private var modelMs = 0L
        private var retryMs = 0L
        private var retries = 0
        private var firstToken: Long? = null
        private var firstCall: Long? = null
        private var uiFeedback: Long? = null
        private val open = HashMap<Int, Step>()
        private val retryOpen = HashMap<String, Long>()
        private val pending = HashMap<String, Call>()
        private val done = mutableListOf<Done>()
        private val waits = mutableListOf<LongRange>()
        private var judged: String? = null

        fun add(e: DshEvent) {
            if (e.time > 0) last = maxOf(last, e.time)
            when (e.type) {
                "turn/start" -> if (started == null) started = e.time
                "user/message" -> if (goal.isEmpty() && e.data.optJSONObject("source")?.optString("kind") == "user")
                    goal = DshEvent.text(e.data.optJSONArray("content")).trim().take(GOAL_MAX)
                "step/start" -> open[e.step ?: 0] = Step(e.time)
                "assistant/attempt" -> open[e.step ?: 0]?.let { s -> if (s.firstToken == null) s.firstToken = DshEvent.firstTokenTime(e.data.optJSONArray("stream")) }
                "assistant/message" -> {
                    val s = open.remove(e.step ?: 0)
                    val token = s?.firstToken ?: DshEvent.firstTokenTime(e.data.optJSONArray("stream"))
                    token?.let { t -> if (firstToken == null || t < firstToken!!) firstToken = t }
                    if (s != null) modelMs += (e.time - s.start).coerceAtLeast(0)
                    val u = e.data.optJSONObject("usage")
                    if (u == null) noUsage++ else {
                        input += u.optLong("inputTokens"); output += u.optLong("outputTokens")
                        cacheRead += u.optLong("cacheReadTokens"); cacheWrite += u.optLong("cacheWriteTokens"); reasoning += u.optLong("reasoningTokens")
                    }
                }
                "step/end" -> { steps++; open.remove(e.step ?: 0) }
                "llm/retry" -> { retries++; retryOpen[e.data.optString("retryId")] = e.time }
                "llm/retry-started" -> retryOpen.remove(e.data.optString("retryId"))?.let { retryMs += (e.time - it).coerceAtLeast(0) }
                "tool/call" -> {
                    val id = e.data.optString("callId")
                    if (id.isNotEmpty()) pending[id] = Call(e.data.optString("name"), e.time)
                    if (firstCall == null) firstCall = e.time
                }
                "tool/result" -> {
                    val message = e.data.optJSONObject("message")
                    val id = message?.optJSONObject("source")?.optString("callId")?.takeIf { it.isNotEmpty() } ?: message?.optString("toolCallId").orEmpty()
                    pending.remove(id)?.let { c -> done += Done(c.name, c.start, e.time, ToolKinds.kindOf(c.name)) }
                }
                "turn/end" -> if (endReason == null) {
                    endReason = e.data.optJSONObject("reason")?.optString("kind") ?: "completed"
                    endTime = e.time
                    pending.clear()
                }
            }
        }

        /** 界面实测到的第一次反馈（墙钟毫秒）。 */
        fun uiFirstFeedback(atMs: Long) { if (uiFeedback == null || atMs < uiFeedback!!) uiFeedback = atMs }

        /** 一段等用户的时间（审批、回答问题），从落在其中的工具时长里扣掉。 */
        fun userWait(startMs: Long, endMs: Long) { if (endMs > startMs) waits += startMs until endMs }

        /** 交出回答后另判的结局（RT9 合并后由判定填）。 */
        fun judged(outcome: String?) { judged = outcome }

        /** 没有 `turn/start`（日志不完整）时为 null。 */
        fun build(): RunMetrics? {
            val start = started ?: return null
            val end = endTime ?: last
            // 等用户的时间段先合并，避免重叠的审批/提问被算两次
            val merged = mergeRanges(waits)
            var observe = 0L; var act = 0L; var observeN = 0; var actN = 0; var tool = 0L; var userWait = 0L
            for (d in done) {
                val raw = (d.end - d.start).coerceAtLeast(0)
                val wait = overlap(d.start, d.end, merged)
                val net = (raw - wait).coerceAtLeast(0)
                tool += raw; userWait += wait
                when (d.kind) {
                    ToolKinds.OBSERVE -> { observe += net; observeN++ }
                    ToolKinds.ACT -> { act += net; actN++ }
                }
            }
            val logFeedback = listOfNotNull(firstToken, firstCall).minOrNull()
            val feedback = uiFeedback ?: logFeedback
            val reason = endReason
            return RunMetrics(
                runId = runId,
                goal = goal,
                startedAtMs = start,
                totalMs = (end - start).coerceAtLeast(0),
                ended = reason != null,
                firstFeedbackMs = feedback?.let { (it - start).coerceAtLeast(0) },
                modelCalls = steps,
                callsWithoutUsage = noUsage,
                inputTokens = input + cacheRead + cacheWrite,
                outputTokens = output,
                cachedTokens = cacheRead,
                modelMs = (modelMs - retryMs).coerceAtLeast(0),
                retryWaitMs = retryMs,
                observeMs = observe,
                actMs = act,
                observeCalls = observeN,
                actCalls = actN,
                steps = steps,
                outcome = outcomeOf(reason),
                failKind = reason?.takeIf { it != "completed" },
                judged = judged,
                feedbackSource = when { uiFeedback != null -> "ui"; logFeedback != null -> "log"; else -> null },
                firstTokenMs = firstToken?.let { (it - start).coerceAtLeast(0) },
                toolMs = tool,
                toolCalls = done.size,
                retries = retries,
                reasoningTokens = reasoning,
                cacheWriteTokens = cacheWrite,
                userWaitMs = userWait,
                slowestTools = done.sortedByDescending { it.end - it.start }.take(3).map { ToolTiming(it.name, (it.end - it.start).coerceAtLeast(0)) },
            )
        }
    }

    companion object {
        const val UNFINISHED = "unfinished"
        const val ENGINE = "hoshino"
        const val GOAL_MAX = 80
        private val SUCCESS = setOf("succeeded", "unverified")
        private val NOT_DONE = setOf("partial", "not_possible")

        /** dsh `turn/end.reason.kind` → Mika 的结局名。 */
        fun outcomeOf(kind: String?): String = when (kind) {
            null -> UNFINISHED
            "completed" -> "succeeded"
            "aborted", "interrupted" -> "cancelled"
            "forked" -> UNFINISHED
            else -> "failed" // error、blocked、max-tokens 以及以后新增的
        }

        /** 一轮的小结；日志里没有这一轮的 `turn/start` 时为 null。 */
        fun of(events: List<DshEvent>, runId: String = ""): RunMetrics? = Builder(runId).apply { events.forEach(::add) }.build()

        /** 读 [toJson] 写出的对象；也认 Mika 的 metrics（没有的字段取默认值）。 */
        fun fromJson(o: JSONObject): RunMetrics {
            fun long(k: String) = o.optLong(k, 0)
            fun longOrNull(k: String) = if (o.has(k) && !o.isNull(k)) o.optLong(k) else null
            fun intOrNull(k: String) = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null
            fun strOrNull(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k) else null
            return RunMetrics(
                runId = o.optString("runId"), goal = o.optString("goal"), startedAtMs = long("startedAtMs"), totalMs = long("totalMs"),
                ended = o.optBoolean("ended"), firstFeedbackMs = longOrNull("firstFeedbackMs"), modelCalls = o.optInt("modelCalls"),
                lightCalls = o.optInt("lightCalls"), callsWithoutUsage = o.optInt("callsWithoutUsage"), inputTokens = long("inputTokens"),
                outputTokens = long("outputTokens"), cachedTokens = long("cachedTokens"), allCalls = intOrNull("allCalls"),
                allInputTokens = longOrNull("allInputTokens"), allOutputTokens = longOrNull("allOutputTokens"), modelMs = long("modelMs"),
                retryWaitMs = long("retryWaitMs"), observeMs = long("observeMs"), actMs = long("actMs"), uiWaitMs = long("uiWaitMs"),
                observeCalls = o.optInt("observeCalls"), actCalls = o.optInt("actCalls"), steps = o.optInt("steps"),
                outcome = o.optString("outcome", UNFINISHED), failKind = strOrNull("failKind"), judged = strOrNull("judged"),
                engine = o.optString("engine", "mika"), feedbackSource = strOrNull("feedbackSource"), firstTokenMs = longOrNull("firstTokenMs"),
                toolMs = long("toolMs"), toolCalls = o.optInt("toolCalls"), retries = o.optInt("retries"), reasoningTokens = long("reasoningTokens"),
                cacheWriteTokens = long("cacheWriteTokens"), userWaitMs = long("userWaitMs"),
                slowestTools = o.optJSONArray("slowestTools")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { ToolTiming(it.optString("name"), it.optLong("ms")) } }.orEmpty(),
            )
        }

        internal fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
            val out = mutableListOf<LongRange>()
            for (r in ranges.sortedBy { it.first }) {
                val prev = out.lastOrNull()
                if (prev != null && r.first <= prev.last + 1) out[out.lastIndex] = prev.first..maxOf(prev.last, r.last) else out += r
            }
            return out
        }

        internal fun overlap(start: Long, end: Long, ranges: List<LongRange>): Long =
            ranges.sumOf { r -> (minOf(end, r.last + 1) - maxOf(start, r.first)).coerceAtLeast(0) }
    }
}

/**
 * 工具调用分读和操作（Mika 按执行入口给的风险定种类；Hoshino 还没有手机执行入口，先按工具名分）。
 * 读：只看不改的（读文件、搜索、抓网页、看设备信息、自省）；控制：问用户、待办；其余（写文件、跑命令、改配置……）都算操作。
 * 不认识的名字按名字里的动词猜；猜不出的按操作算（宁可把读算成操作，也不把改动算成读）。
 */
object ToolKinds {
    const val OBSERVE = "observe"
    const val ACT = "act"
    private val OBSERVE_NAMES = setOf(
        "read", "read_image", "grep", "glob", "ls", "list", "find", "web_search", "web_fetch", "get_device_info",
        "self_read", "self_diff", "self_doctor", "self_inspect", "self_config_read", "skill", "plugin_market", "app_screenshot",
        "clipboard_read", "session_search",
    )
    private val CONTROL_NAMES = setOf("ask_user", "todo_write", "todo_read", "subagent_wait", "goal_status")
    private val OBSERVE_HINT = Regex("(^|_)(read|get|list|search|fetch|inspect|observe|screenshot|query|status|find|view|peek)(_|$)")

    /** "observe" / "act"；控制类返回 null。 */
    fun kindOf(name: String): String? = when {
        name in CONTROL_NAMES -> null
        name in OBSERVE_NAMES -> OBSERVE
        OBSERVE_HINT.containsMatchIn(name) -> OBSERVE
        else -> ACT
    }
}
