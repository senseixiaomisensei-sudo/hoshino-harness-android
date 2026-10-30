// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.metrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunMetricsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val file get() = tmp.root.resolve("files/${RunMetricsStore.FILE_NAME}")
    private fun m(id: String, total: Long = 1_000, outcome: String = "succeeded") = RunMetrics(runId = id, totalMs = total, ended = true, outcome = outcome)

    @Test fun keepsOnlyTheLatestOnes() {
        val s = RunMetricsStore(file, keep = 3)
        (1..5).forEach { s.append(m("r$it")) }
        assertEquals(listOf("r3", "r4", "r5"), s.all().map { it.runId })
        assertEquals(3, file.readLines().count { it.isNotBlank() })
    }

    @Test fun survivesANewInstance() {
        RunMetricsStore(file).append(m("r1", total = 4_200).copy(slowestTools = listOf(RunMetrics.ToolTiming("bash", 1200))))
        val back = RunMetricsStore(file).all().single()
        assertEquals("r1", back.runId)
        assertEquals(4_200L, back.totalMs)
        assertEquals(listOf(RunMetrics.ToolTiming("bash", 1200)), back.slowestTools)
    }

    @Test fun recentTakesTheNewestN() {
        val s = RunMetricsStore(file)
        (1..4).forEach { s.append(m("r$it")) }
        assertEquals(listOf("r3", "r4"), s.recent(2).map { it.runId })
        assertEquals(4, s.recent(null).size)
        assertEquals("r2", s.get("r2")?.runId)
    }

    @Test fun sameRunIsReplacedNotDuplicated() {
        val s = RunMetricsStore(file)
        s.append(m("r1", total = 1))
        s.append(m("r1", total = 2))
        assertEquals(listOf(2L), s.all().map { it.totalMs })
    }

    @Test fun updateChangesOnlyThatRun() {
        val s = RunMetricsStore(file)
        s.append(m("r1")); s.append(m("r2"))
        s.update("r1") { it.copy(judged = "partial") }
        s.update("missing") { it.copy(judged = "done") }
        assertEquals(listOf("partial", null), s.all().map { it.judged })
    }

    @Test fun skipsCorruptLines() {
        file.parentFile.mkdirs()
        file.writeText("{not json\n" + m("ok").toJson() + "\n\n")
        assertEquals(listOf("ok"), RunMetricsStore(file).all().map { it.runId })
    }

    @Test fun wholeFileCorruptReadsEmptyAndRecovers() {
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(0, 1, 2, -1, -2))
        val s = RunMetricsStore(file)
        assertTrue(s.all().isEmpty())
        s.append(m("r1"))
        assertEquals(listOf("r1"), s.all().map { it.runId })
    }

    @Test fun missingFileIsEmpty() {
        assertTrue(RunMetricsStore(file).all().isEmpty())
    }

    @Test fun clearRemovesEverything() {
        val s = RunMetricsStore(file); s.append(m("r1")); s.clear()
        assertTrue(s.all().isEmpty()); assertFalse(file.exists())
    }

    @Test fun exportHasStatsWindowsAndEveryField() {
        val out = JSONObject(RunMetricsStore.export(listOf(m("r1"), m("r2", outcome = "failed"))))
        val runs = out.getJSONArray("runs")
        assertEquals(2, runs.length())
        val first = runs.getJSONObject(0)
        assertEquals("r1", first.getString("runId"))
        // 默认值也写出来,分析时字段齐全
        for (k in listOf("firstFeedbackMs", "observeMs", "judged", "modelCalls", "cachedTokens", "otherMs")) assertTrue(k, first.has(k))
        val stats = out.getJSONObject("stats")
        for (k in listOf("10", "30", "100", "all")) assertTrue(k, stats.has(k))
        assertEquals(0.5, stats.getJSONObject("all").getDouble("successRate"), 0.0)
    }

    /** 小结里只有数字和目标的前 80 个字,不落回答、工具参数和结果。 */
    @Test fun storesNoAnswerOrToolContent() {
        val events = Dsh.turn(goal = "x".repeat(200), answer = "SECRET-ANSWER", toolResult = "SECRET-TOOL-OUTPUT")
        val metrics = RunMetrics.of(events, "s#1")!!
        RunMetricsStore(file).append(metrics)
        val text = file.readText()
        assertFalse(text, "SECRET" in text)
        assertEquals(80, metrics.goal.length)
    }
}
