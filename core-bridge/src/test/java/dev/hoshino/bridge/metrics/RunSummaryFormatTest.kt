package dev.hoshino.bridge.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSummaryFormatTest {
    private val m = RunMetrics(
        runId = "s#1", totalMs = 12_340, firstFeedbackMs = 1_100, feedbackSource = "ui", modelCalls = 3, inputTokens = 4_000, cachedTokens = 2_500,
        outputTokens = 210, modelMs = 6_000, observeMs = 3_000, actMs = 1_000, observeCalls = 2, actCalls = 1, toolCalls = 3, outcome = "succeeded",
        slowestTools = listOf(RunMetrics.ToolTiming("web_fetch", 2_500), RunMetrics.ToolTiming("bash", 1_000)),
    )

    @Test fun oneLine() {
        assertEquals("用时 12.3 秒 · 来回 3 次 · Token 4.2k · 首个反馈 1.1 秒 · 缓存 63%", RunSummaryFormat.line(m))
        assertEquals("用时 0.5 秒 · 来回 1 次", RunSummaryFormat.line(RunMetrics(totalMs = 500, modelCalls = 1)))
    }

    @Test fun breakdownSumsToTheTotal() {
        val b = RunSummaryFormat.breakdown(m)
        assertEquals(listOf("模型思考", "读", "操作", "其他"), b.map { it.label })
        assertEquals(m.totalMs, b.sumOf { it.ms })
        assertEquals(1f, b.map { it.fraction }.sum(), 0.001f)
    }

    @Test fun detailsCarryCostSlowestToolsAndOutcome() {
        val d = RunSummaryFormat.details(m, RunSummaryFormat.Prices(2.0, 8.0, 0.5)).toMap()
        assertEquals("完成", d["结局"])
        assertEquals("1.1 秒（界面实测）", d["第一次反馈"])
        assertEquals("4k（缓存 2.5k）", d["输入 Token"])
        // (4000-2500)*2 + 2500*0.5 + 210*8 = 3000+1250+1680 = 5930 / 1e6
        assertEquals("0.0059", d["预估费用"])
        assertEquals("web_fetch 2.5 秒", d["最慢的工具"])
        assertEquals("没办成", RunSummaryFormat.outcome(m.copy(judged = "not_possible")))
        assertEquals("失败（error）", RunSummaryFormat.outcome(m.copy(outcome = "failed", failKind = "error")))
    }

    @Test fun statsRowsUseRunStats() {
        val st = RunStats.of(listOf(m, m.copy(runId = "s#2", outcome = "failed", firstFeedbackMs = 900, totalMs = 20_000)))
        val rows = RunSummaryFormat.stats(st).toMap()
        assertEquals("1/2（50%）", rows["办成率"])
        assertEquals("16.2 秒", rows["用时中位数"])
        assertTrue(rows.getValue("第一次反馈中位数").startsWith("1.0 秒"))
        assertEquals(listOf("运行次数" to "0"), RunSummaryFormat.stats(RunStats.of(emptyList())))
    }

    @Test fun units() {
        assertEquals("999", RunSummaryFormat.tokens(999)); assertEquals("1.5M", RunSummaryFormat.tokens(1_500_000))
        assertEquals("2 分 5 秒", RunSummaryFormat.seconds(125_000))
    }
}
