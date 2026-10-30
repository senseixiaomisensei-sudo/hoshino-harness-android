// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.eval

import org.junit.Assert.assertTrue
import org.junit.Test

class EvalReportTimingTest {
    private fun pair(id: String, calls: Int, input: Long, cached: Long, modelMs: Long, toolMs: Long) =
        TaskScore(id, true, false, false, emptyList(), null) to
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), calls, 10_000, input, 10, cached, null,
                modelCalls = calls, modelMs = modelMs, toolMs = toolMs)

    @Test fun reportsCallMedianAndCacheRatio() {
        val scores = listOf(
            pair("a", 4, 400, 100, 5_000, 2_000),
            pair("b", 6, 300, 50, 5_000, 2_000),
            pair("c", 10, 300, 100, 5_000, 1_000),
        )
        val md = EvalReport.markdown("r1", "m", scores, emptyList(), null, flags = "inlineScreen,replay")
        assertTrue(md, "| 模型调用次数中位数 | 6 |" in md)
        assertTrue(md, "| 模型调用次数 P90 | 10 |" in md)
        assertTrue(md, "| 缓存命中率(cached/input) | 25.0% |" in md)
        // 总耗时 30 秒:模型 15 秒、工具 5 秒
        assertTrue(md, "| 模型等待占比 | 50.0% |" in md)
        assertTrue(md, "| 工具执行占比 | 16.7% |" in md)
        // Hoshino 没有回放:不出回放行
        assertTrue(md, "回放" !in md)
        assertTrue(md, "inlineScreen,replay" in md)
    }

    // 占比加起来是 100%:模型、工具之外的是「其他/等用户」;没量到等界面时写「(暂无数据)」
    @Test fun sharesSumToHundredWithOtherRow() {
        val scores = listOf(pair("a", 4, 400, 100, 5_000, 2_000), pair("b", 6, 300, 50, 5_000, 2_000), pair("c", 10, 300, 100, 5_000, 1_000))
        val md = EvalReport.markdown("r1", "m", scores, emptyList(), null)
        assertTrue(md, "| 其他/等用户占比 | 33.3% |" in md)
        assertTrue(md, "| 其中等界面占比 | (暂无数据) |" in md)
    }
}
