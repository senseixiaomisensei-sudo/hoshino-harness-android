// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.eval

import dev.hoshino.bridge.metrics.RunMetrics
import dev.hoshino.bridge.metrics.RunStats
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil

/** 每百万 token 单价；运行时传入，不写进仓库。 */
data class Prices(val inputPerM: Double, val outputPerM: Double, val cachedPerM: Double)

/**
 * 运行汇总 markdown 与机器可读的 scores.jsonl / metrics.json。版式与指标名照抄 Mika `core/eval/EvalReport.kt`（两边的汇总可以并排看，
 * `tools/eval/compare.py` 两边都读），去掉了 Hoshino 没有的回放行。只放指标和每题结论，不放回答全文和截图。
 */
object EvalReport {
    fun markdown(
        run: String,
        model: String,
        scores: List<Pair<TaskScore, Observed>>,
        skipped: List<Pair<String, String>>,
        prices: Prices?,
        flags: String = "none",
        totalWallMs: Long? = null,
        parallel: Int = 1,
        /** 结局是怎么判的（Hoshino：RT9 合并前按回答文字判，见 [DshRunFacts.judgeFromText]）。 */
        judging: String? = null,
        /** 这台设备上的 Hoshino 有哪些能力（决定哪些题被跳过）。 */
        capabilities: Set<String>? = null,
    ): String {
        val n = scores.size
        val passed = scores.count { it.first.passed }
        val reportedSuccess = scores.count { Scoring.recorded(it.second).outcome == "succeeded" }
        val falseSuccess = scores.count { it.first.falseSuccess }
        val unverified = scores.count { it.first.unverified }
        val steps = scores.map { it.second.steps.toDouble() }
        val secs = scores.map { it.second.wallMs / 1000.0 }
        val inT = scores.sumOf { it.second.inputTokens }
        val outT = scores.sumOf { it.second.outputTokens }
        val cachedT = scores.sumOf { it.second.cachedTokens }
        val calls = scores.map { it.second.modelCalls.toDouble() }
        val wallT = scores.sumOf { it.second.wallMs }

        val sb = StringBuilder()
        sb.appendLine("# 评测汇总 $run")
        sb.appendLine()
        sb.appendLine("模型:$model;开关:$flags;实跑 $n 题,跳过 ${skipped.size} 题" + (if (parallel > 1) ";并行 $parallel 题。" else "。"))
        if (judging != null) sb.appendLine().appendLine("结局判定:$judging")
        if (capabilities != null) sb.appendLine().appendLine("本机能力:${capabilities.sorted().joinToString("、").ifEmpty { "无" }}")
        sb.appendLine()
        sb.appendLine("## 指标")
        sb.appendLine()
        sb.appendLine("| 指标 | 值 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| 办成率 | ${ratio(passed, n)} |")
        if (totalWallMs != null) sb.appendLine("| 总墙钟时间(秒) | ${num(totalWallMs / 1000.0)} |")
        sb.appendLine("| 假成功率(占报成功) | ${ratio(falseSuccess, reportedSuccess)} |")
        sb.appendLine("| 假成功率(占全部) | ${ratio(falseSuccess, n)} |")
        sb.appendLine("| 未核实成功 | $unverified |")
        sb.appendLine("| 未核实且没过 | ${scores.count { it.first.failureClass == FailureClass.unverified_wrong }} |")
        scores.mapNotNull { it.first.handedOver }.groupingBy { it }.eachCount().toSortedMap()
            .forEach { (kind, c) -> sb.appendLine("| 交给用户:$kind(不算办成) | $c |") }
        sb.appendLine("| 步数中位数 | ${num(median(steps))} |")
        sb.appendLine("| 步数 P90 | ${num(p90(steps))} |")
        sb.appendLine("| 耗时中位数(秒) | ${num(median(secs))} |")
        sb.appendLine("| 耗时 P90(秒) | ${num(p90(secs))} |")
        sb.appendLine("| 模型调用次数中位数 | ${num(median(calls))} |")
        sb.appendLine("| 模型调用次数 P90 | ${num(p90(calls))} |")
        sb.appendLine("| 模型等待占比 | ${percent(scores.sumOf { it.second.modelMs }, wallT)} |")
        sb.appendLine("| 工具执行占比 | ${percent(scores.sumOf { it.second.toolMs }, wallT)} |")
        val waitT = scores.sumOf { it.second.waitMs }
        sb.appendLine("| 其中等界面占比 | ${if (waitT == 0L) "(暂无数据)" else percent(waitT, wallT)} |")
        val otherT = (wallT - scores.sumOf { it.second.modelMs } - scores.sumOf { it.second.toolMs }).coerceAtLeast(0)
        sb.appendLine("| 其他/等用户占比 | ${percent(otherT, wallT)} |")
        val approvals = scores.sumOf { it.second.approvals }
        if (approvals > 0) sb.appendLine("| 评测器自动回答的审批 | $approvals |")
        sb.appendLine("| 输入 token | $inT |")
        sb.appendLine("| 其中缓存 token | $cachedT |")
        sb.appendLine("| 输出 token | $outT |")
        sb.appendLine("| 缓存命中率(cached/input) | ${percent(cachedT, inT)} |")
        val perTask = scores.filter { it.second.inputTokens > 0 }.map { it.second.cachedTokens * 100.0 / it.second.inputTokens }
        sb.appendLine("| 每题缓存命中率中位数 | ${if (perTask.isEmpty()) "—" else num(median(perTask)) + "%"} |")
        if (prices != null) {
            val cost = ((inT - cachedT) * prices.inputPerM + cachedT * prices.cachedPerM + outT * prices.outputPerM) / 1_000_000
            sb.appendLine("| 花费 | ${num(cost)} |")
        }
        sb.appendLine()

        metricsSection(sb, scores)

        sb.appendLine("## 按类计数")
        sb.appendLine()
        val byClass = scores.mapNotNull { it.first.failureClass }.groupingBy { it }.eachCount()
        if (byClass.isEmpty()) sb.appendLine("无失败。")
        else {
            sb.appendLine("| 类别 | 数 |")
            sb.appendLine("|---|---|")
            FailureClass.entries.filter { it in byClass }.forEach { sb.appendLine("| ${it.name} | ${byClass.getValue(it)} |") }
        }
        sb.appendLine()

        sb.appendLine("## 每题")
        sb.appendLine()
        sb.appendLine("| id | 结局 | 通过 | 类别 | 核查 | 步数 | 秒数 | 输入 token | 缓存命中率 | 输出 token |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        scores.forEach { (s, o) ->
            val judged = o.judged?.takeIf { Scoring.recorded(o) !== o }?.let { "(判:$it)" }.orEmpty()
            sb.appendLine("| ${s.id} | ${o.outcome}$judged${s.handedOver?.let { "(交给用户:$it)" }.orEmpty()} | ${if (s.passed) "是" else "否"} | ${s.failureClass?.name ?: ""} | ${o.verdict ?: ""} | ${o.steps} | " +
                "${num(o.wallMs / 1000.0)} | ${o.inputTokens} | ${percent(o.cachedTokens, o.inputTokens)} | ${o.outputTokens} |")
        }
        sb.appendLine()

        sb.appendLine("## 跳过")
        sb.appendLine()
        if (skipped.isEmpty()) sb.appendLine("无。") else skipped.forEach { (id, why) -> sb.appendLine("- $id:$why") }
        sb.appendLine()

        sb.appendLine("## 离开题目范围的 App(只记录,不算失败)")
        sb.appendLine()
        val drift = scores.filter { it.first.leftScope.isNotEmpty() }
        if (drift.isEmpty()) sb.appendLine("无。") else drift.forEach { (s, _) -> sb.appendLine("- ${s.id}:${s.leftScope.joinToString("、")}") }
        sb.appendLine()

        sb.appendLine("## 留下的测试数据标记(需人工清理)")
        sb.appendLine()
        val markers = scores.mapNotNull { (s, _) -> s.marker?.let { s.id to it } }
        if (markers.isEmpty()) sb.appendLine("无。") else markers.forEach { (id, m) -> sb.appendLine("- $id:$m") }
        sb.appendLine()
        sb.appendLine("## 人工改判")
        sb.appendLine()
        sb.appendLine("(无。改判写法见 eval/README.md「怎么人工改判」。)")
        return sb.toString()
    }

    /** 「运行小结」一节：有运行记录的题按 [RunMetrics] 汇总，再逐题一行。没有一题有记录时不出这一节。 */
    private fun metricsSection(sb: StringBuilder, scores: List<Pair<TaskScore, Observed>>) {
        val rows = scores.mapNotNull { (s, o) -> o.metrics?.let { s.id to it } }
        if (rows.isEmpty()) return
        val st = RunStats.of(rows.map { it.second })
        sb.appendLine("## 运行小结")
        sb.appendLine()
        sb.appendLine("按运行记录算(${rows.size} 题):时间从这一轮开始(turn/start)算起,读、操作按工具种类分,等用户的时间不算进工具。")
        sb.appendLine()
        sb.appendLine("| 指标 | 值 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| 用时中位数(秒) | ${num(st.medianTotalMs / 1000.0)} |")
        sb.appendLine("| 第一次反馈中位数(秒) | ${st.medianFirstFeedbackMs?.let { num(it / 1000.0) } ?: "—"} |")
        sb.appendLine("| 平均和模型来回 | ${num(Math.round(st.avgModelCalls * 10) / 10.0)} |")
        sb.appendLine("| 模型思考占比 | ${percent(st.modelMs, st.totalMs)} |")
        sb.appendLine("| 读屏占比 | ${percent(st.observeMs, st.totalMs)} |")
        sb.appendLine("| 操作占比 | ${percent(st.actMs, st.totalMs)} |")
        sb.appendLine("| 其他(等待、重试)占比 | ${percent(st.otherMs, st.totalMs)} |")
        sb.appendLine()
        sb.appendLine("| id | 秒数 | 第一次反馈(秒) | 来回 | 全部请求 | 模型(秒) | 读屏(秒) | 操作(秒) | 其他(秒) | 读 / 操作次数 | token |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        rows.forEach { (id, m) ->
            sb.appendLine("| $id | ${secs(m.totalMs)} | ${m.firstFeedbackMs?.let(::secs) ?: "—"} | ${m.modelCalls} | ${m.allCalls ?: "—"} | " +
                "${secs(m.modelMs)} | ${secs(m.observeMs)} | ${secs(m.actMs)} | ${secs(m.otherMs)} | ${m.observeCalls} / ${m.actCalls} | ${m.tokens} |")
        }
        sb.appendLine()
    }

    /** 每题的运行小结，一个 JSON 数组（metrics.json），每项是 `id` 和 `metrics`；没有运行记录的题不列。 */
    fun metricsJson(scores: List<Pair<TaskScore, Observed>>): String = JSONArray(scores.mapNotNull { (s, o) ->
        o.metrics?.let { m -> JSONObject().put("id", s.id).put("metrics", m.toJson()) }
    }).toString()

    /** scores.jsonl 的一行：字段与 Mika EvalRunTest 的 scoreLine 一致，另加 category / partition / judgedBy 方便对比。 */
    fun scoreLine(t: EvalTask, s: TaskScore, o: Observed, judgedBy: String? = null): String = JSONObject().apply {
        put("id", s.id); put("passed", s.passed); put("falseSuccess", s.falseSuccess); put("unverified", s.unverified)
        put("failedChecks", JSONArray(s.failedChecks)); put("failureClass", s.failureClass?.name ?: JSONObject.NULL)
        put("outcome", o.outcome); put("judged", o.judged ?: JSONObject.NULL); put("answer", o.answer.take(2000))
        put("failReason", o.failReason ?: JSONObject.NULL); put("verdict", o.verdict ?: JSONObject.NULL)
        put("leftScope", JSONArray(s.leftScope)); put("steps", o.steps); put("wallMs", o.wallMs)
        put("inputTokens", o.inputTokens); put("outputTokens", o.outputTokens); put("cachedTokens", o.cachedTokens)
        put("modelCalls", o.modelCalls); put("modelMs", o.modelMs); put("toolMs", o.toolMs); put("waitMs", o.waitMs)
        o.metrics?.let { put("metrics", it.toJson()) }
        put("category", t.category.wire); put("partition", t.partition ?: JSONObject.NULL); put("handedOver", s.handedOver ?: JSONObject.NULL)
        put("approvals", o.approvals); judgedBy?.let { put("judgedBy", it) }
    }.toString()

    private fun secs(ms: Long) = num(Math.round(ms / 100.0) / 10.0)

    internal fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** 最近秩法：排序后第 ceil(0.9n) 个。 */
    internal fun p90(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return s[ceil(0.9 * s.size).toInt() - 1]
    }

    private fun ratio(a: Int, b: Int): String =
        if (b == 0) "$a/0(—)" else "$a/$b(${String.format(Locale.ROOT, "%.1f", a * 100.0 / b)}%)"

    private fun percent(a: Long, b: Long): String =
        if (b == 0L) "—" else String.format(Locale.ROOT, "%.1f%%", a * 100.0 / b)

    private fun num(x: Double): String =
        if (x == Math.floor(x) && !x.isInfinite()) x.toLong().toString()
        else String.format(Locale.ROOT, "%.2f", x).trimEnd('0').trimEnd('.')
}
