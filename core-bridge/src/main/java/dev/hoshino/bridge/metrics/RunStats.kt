// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.metrics

import org.json.JSONObject
import java.util.Locale

/**
 * 多次运行的汇总（OB5「运行统计」与 EV2 报告共用同一口径，照抄 Mika `RunStats`）：办成率、用时与第一次反馈的中位数、
 * 平均来回次数与 token，以及时间的去向（各项之和占总时长之和）。第一次反馈只在有这一项的运行里取中位数。
 * Hoshino 补了：首个反馈 P90、缓存命中率、来回次数中位数（Mika 只给平均）。
 */
data class RunStats(
    val runs: Int,
    val succeeded: Int,
    val medianTotalMs: Long,
    val medianFirstFeedbackMs: Long?,
    val avgModelCalls: Double,
    val avgTokens: Double,
    val totalMs: Long,
    val modelMs: Long,
    val observeMs: Long,
    val actMs: Long,
    val otherMs: Long,
    val p90FirstFeedbackMs: Long? = null,
    val medianModelCalls: Long? = null,
    val inputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    val successRate: Double get() = if (runs == 0) 0.0 else succeeded.toDouble() / runs
    val cacheHitRate: Double? get() = if (inputTokens <= 0) null else cachedTokens.toDouble() / inputTokens

    fun toJson(): JSONObject = JSONObject().apply {
        put("runs", runs); put("succeeded", succeeded); put("successRate", round(successRate, 4))
        put("medianTotalMs", medianTotalMs); put("medianFirstFeedbackMs", medianFirstFeedbackMs ?: JSONObject.NULL)
        put("p90FirstFeedbackMs", p90FirstFeedbackMs ?: JSONObject.NULL); put("avgModelCalls", round(avgModelCalls, 2))
        put("medianModelCalls", medianModelCalls ?: JSONObject.NULL); put("avgTokens", round(avgTokens, 1))
        put("inputTokens", inputTokens); put("cachedTokens", cachedTokens); put("outputTokens", outputTokens)
        put("cacheHitRate", cacheHitRate?.let { round(it, 4) } ?: JSONObject.NULL)
        put("totalMs", totalMs); put("modelMs", modelMs); put("observeMs", observeMs); put("actMs", actMs); put("otherMs", otherMs)
    }

    companion object {
        fun of(runs: List<RunMetrics>): RunStats = RunStats(
            runs = runs.size,
            succeeded = runs.count { it.succeeded },
            medianTotalMs = median(runs.map { it.totalMs }) ?: 0,
            medianFirstFeedbackMs = median(runs.mapNotNull { it.firstFeedbackMs }),
            avgModelCalls = if (runs.isEmpty()) 0.0 else runs.sumOf { it.modelCalls }.toDouble() / runs.size,
            avgTokens = if (runs.isEmpty()) 0.0 else runs.sumOf { it.tokens }.toDouble() / runs.size,
            totalMs = runs.sumOf { it.totalMs },
            modelMs = runs.sumOf { it.modelMs },
            observeMs = runs.sumOf { it.observeMs },
            actMs = runs.sumOf { it.actMs },
            otherMs = runs.sumOf { it.otherMs },
            p90FirstFeedbackMs = p90(runs.mapNotNull { it.firstFeedbackMs }),
            medianModelCalls = median(runs.map { it.modelCalls.toLong() }),
            inputTokens = runs.sumOf { it.inputTokens },
            cachedTokens = runs.sumOf { it.cachedTokens },
            outputTokens = runs.sumOf { it.outputTokens },
        )

        /** 中位数；偶数个取中间两个的平均（向下取整）。空表为 null。 */
        fun median(xs: List<Long>): Long? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }

        /** 最近秩法：排序后第 ceil(0.9n) 个（与 Mika EvalReport.p90 相同）。空表为 null。 */
        fun p90(xs: List<Long>): Long? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            return s[kotlin.math.ceil(0.9 * s.size).toInt() - 1]
        }

        private fun round(x: Double, digits: Int): Double = String.format(Locale.ROOT, "%.${digits}f", x).toDouble()
    }
}
