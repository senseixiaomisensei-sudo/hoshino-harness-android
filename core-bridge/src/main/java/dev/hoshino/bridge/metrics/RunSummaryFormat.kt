// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.metrics

import java.util.Locale

/**
 * 对话页小结（OB3）与「关于 › 运行统计」（OB5）的文字。纯函数，界面只管排版。
 * 口径与 [RunMetrics]、[RunStats] 一致（EV2 报告也用它们），这里只决定怎么说。
 */
object RunSummaryFormat {
    /** 一行小结：用时 · 来回 · Token · 第一次反馈 · 缓存命中（服务商报了缓存才有）。 */
    fun line(m: RunMetrics): String = buildList {
        add("用时 ${seconds(m.totalMs)}")
        add("来回 ${m.modelCalls} 次")
        if (m.tokens > 0) add("Token ${tokens(m.tokens)}")
        m.firstFeedbackMs?.let { add("首个反馈 ${seconds(it)}") }
        m.cacheHitRate?.takeIf { m.cachedTokens > 0 }?.let { add("缓存 ${percent(it)}") }
    }.joinToString(" · ")

    /** 结局小标（RT9 结局判定合并前按内核的结束原因说）。 */
    fun outcome(m: RunMetrics): String = when {
        m.judged == "partial" -> "只办成一部分"
        m.judged == "not_possible" -> "没办成"
        m.outcome == "succeeded" -> "完成"
        m.outcome == "cancelled" -> "已停止"
        m.outcome == "failed" -> "失败" + (m.failKind?.let { "（$it）" } ?: "")
        else -> "未结束"
    }

    data class Share(val label: String, val ms: Long, val fraction: Float)

    /** 时间去向：模型思考 / 读 / 操作 / 其他（等用户、重试退避、控制类调用），各占总时长的比例。 */
    fun breakdown(m: RunMetrics): List<Share> {
        val total = m.totalMs.coerceAtLeast(1)
        return listOf("模型思考" to m.modelMs, "读" to m.observeMs, "操作" to m.actMs, "其他" to m.otherMs)
            .map { (label, ms) -> Share(label, ms, (ms.toDouble() / total).toFloat().coerceIn(0f, 1f)) }
    }

    /** 展开后的细节行。 */
    fun details(m: RunMetrics, prices: Prices? = null): List<Pair<String, String>> = buildList {
        add("结局" to outcome(m))
        add("和模型来回" to "${m.modelCalls} 次" + (if (m.retries > 0) "（重试 ${m.retries} 次，等待 ${seconds(m.retryWaitMs)}）" else ""))
        add("工具调用" to "${m.toolCalls} 次（读 ${m.observeCalls} · 操作 ${m.actCalls}）")
        m.firstFeedbackMs?.let { add("第一次反馈" to seconds(it) + if (m.feedbackSource == "ui") "（界面实测）" else "（按日志）") }
        m.firstTokenMs?.let { add("第一个 token" to seconds(it)) }
        if (m.tokens > 0) {
            add("输入 Token" to tokens(m.inputTokens) + (if (m.cachedTokens > 0) "（缓存 ${tokens(m.cachedTokens)}）" else ""))
            add("输出 Token" to tokens(m.outputTokens) + (if (m.reasoningTokens > 0) "（思考 ${tokens(m.reasoningTokens)}）" else ""))
        }
        if (m.callsWithoutUsage > 0) add("没报用量的请求" to "${m.callsWithoutUsage} 次")
        if (m.userWaitMs > 0) add("等你处理" to seconds(m.userWaitMs))
        prices?.let { p -> if (m.tokens > 0) add("预估费用" to money(m.cost(p.inputPerM, p.outputPerM, p.cachedPerM))) }
        m.slowestTools.forEachIndexed { i, t -> add((if (i == 0) "最慢的工具" else "") to "${t.name} ${seconds(t.ms)}") }
    }

    /** 每百万 token 单价（用户自己填，只存在本机）。 */
    data class Prices(val inputPerM: Double, val outputPerM: Double, val cachedPerM: Double)

    /** 运行统计的一组行（OB5）；与 EV2 报告「运行小结」同口径。 */
    fun stats(st: RunStats): List<Pair<String, String>> = buildList {
        add("运行次数" to "${st.runs}")
        if (st.runs == 0) return@buildList
        add("办成率" to "${st.succeeded}/${st.runs}（${percent(st.successRate)}）")
        add("用时中位数" to seconds(st.medianTotalMs))
        add("第一次反馈中位数" to (st.medianFirstFeedbackMs?.let(::seconds) ?: "—") + (st.p90FirstFeedbackMs?.let { " · P90 ${seconds(it)}" } ?: ""))
        add("和模型来回" to "平均 ${num(st.avgModelCalls)} · 中位数 ${st.medianModelCalls ?: 0}")
        add("平均 Token" to tokens(st.avgTokens.toLong()))
        add("缓存命中率" to (st.cacheHitRate?.let(::percent) ?: "—"))
        val total = st.totalMs.coerceAtLeast(1).toDouble()
        add("时间去向" to "模型 ${percent(st.modelMs / total)} · 读 ${percent(st.observeMs / total)} · 操作 ${percent(st.actMs / total)} · 其他 ${percent(st.otherMs / total)}")
    }

    fun seconds(ms: Long): String = if (ms < 60_000) String.format(Locale.ROOT, "%.1f 秒", ms / 1000.0)
        else "${ms / 60_000} 分 ${(ms % 60_000) / 1000} 秒"

    fun tokens(n: Long): String = when {
        n < 1_000 -> "$n"
        n < 1_000_000 -> trim(String.format(Locale.ROOT, "%.1f", n / 1000.0)) + "k"
        else -> trim(String.format(Locale.ROOT, "%.2f", n / 1_000_000.0)) + "M"
    }

    fun percent(x: Double): String = String.format(Locale.ROOT, "%.0f%%", x * 100)

    private fun money(x: Double) = String.format(Locale.ROOT, "%.4f", x)
    private fun num(x: Double) = trim(String.format(Locale.ROOT, "%.1f", x))
    private fun trim(s: String) = if ('.' in s) s.trimEnd('0').trimEnd('.') else s
}
