// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.metrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OB3 小结的口径：同一份 dsh 会话日志重算出来的数字要和 Mika `RunMetrics` 的定义一致（OB3 验收「小结字段与 session log 重算结果一致」）。
 */
class RunMetricsTest {
    @Test fun typicalTurnSplitsTimeAndCountsCalls() {
        val m = RunMetrics.of(Dsh.turn(goal = "查一下 example.com 的标题"), "s#1")!!
        assertEquals("s#1", m.runId)
        assertEquals("查一下 example.com 的标题", m.goal)
        assertEquals(2_220L, m.totalMs)
        assertTrue(m.ended)
        assertEquals("succeeded", m.outcome)
        assertNull(m.failKind)
        // 第一次反馈 = 第一个 token(带名字的工具调用片段)
        assertEquals(300L, m.firstFeedbackMs)
        assertEquals("log", m.feedbackSource)
        assertEquals(300L, m.firstTokenMs)
        assertEquals(2, m.modelCalls)
        assertEquals(2, m.steps)
        assertEquals(1_200L, m.modelMs)
        assertEquals(1_000L, m.observeMs)
        assertEquals(0L, m.actMs)
        assertEquals(1, m.observeCalls)
        assertEquals(1_000L, m.toolMs)
        assertEquals(20L, m.otherMs)
        assertEquals(200L, m.inputTokens)
        assertEquals(40L, m.outputTokens)
        assertEquals(240L, m.tokens)
        assertEquals(listOf(RunMetrics.ToolTiming("web_fetch", 1_000)), m.slowestTools)
        assertTrue(m.succeeded)
    }

    /** dsh 的用量是分开记的(inputTokens 只含未命中缓存),换成 Mika 口径:输入含缓存读写,命中率 = cached / input。 */
    @Test fun tokensFollowMikaConvention() {
        val events = Dsh().turnStart().user("hi").stepStart(1).after(100)
            .assistant(1, "你好", firstTokenAt = 1_000_050, usage = Dsh.usage(input = 200, output = 30, cacheRead = 700, cacheWrite = 100, reasoning = 12))
            .stepEnd(1).turnEnd().events
        val m = RunMetrics.of(events)!!
        assertEquals(1_000L, m.inputTokens)
        assertEquals(700L, m.cachedTokens)
        assertEquals(100L, m.cacheWriteTokens)
        assertEquals(12L, m.reasoningTokens)
        assertEquals(0.7, m.cacheHitRate!!, 1e-9)
        // 估费:未命中 300×1 + 缓存 700×0.1 + 输出 30×2,单位每百万
        assertEquals((300 * 1.0 + 700 * 0.1 + 30 * 2.0) / 1_000_000, m.cost(1.0, 2.0, 0.1), 1e-12)
    }

    @Test fun missingUsageIsCountedNotEstimated() {
        val events = Dsh().turnStart().user("hi").stepStart(1).after(100).assistant(1, "你好", usage = null).stepEnd(1).turnEnd().events
        val m = RunMetrics.of(events)!!
        assertEquals(1, m.callsWithoutUsage)
        assertEquals(0L, m.tokens)
        assertNull(m.cacheHitRate)
        // 流里没有时间:日志里没有第一次反馈
        assertNull(m.firstFeedbackMs)
        assertNull(m.feedbackSource)
    }

    @Test fun uiFeedbackWinsOverLog() {
        val b = RunMetrics.Builder("x")
        Dsh.turn().forEach(b::add)
        b.uiFirstFeedback(1_000_250)
        val m = b.build()!!
        assertEquals(250L, m.firstFeedbackMs)
        assertEquals("ui", m.feedbackSource)
        assertEquals(300L, m.firstTokenMs)
    }

    /** 审批、提问等用户的时间从工具时长里扣掉,落进「其他」,不算成读或操作。 */
    @Test fun userWaitIsTakenOutOfToolTime() {
        val d = Dsh().turnStart().user("写个文件").stepStart(1).after(100).assistant(1, "", firstTokenAt = 1_000_050, toolCall = "write")
            .toolCall("w", "write", """{"path":"a.txt"}""")
        val callAt = d.now
        d.after(5_000).toolResult("w", "ok").stepEnd(1).after(10).stepStart(2).after(100).assistant(2, "写好了").stepEnd(2).turnEnd()
        val b = RunMetrics.Builder()
        d.events.forEach(b::add)
        b.userWait(callAt + 100, callAt + 4_100) // 4 秒在等审批
        b.userWait(callAt + 3_000, callAt + 4_600) // 与上一段重叠:合并后 4.5 秒
        val m = b.build()!!
        assertEquals(5_000L, m.toolMs)
        assertEquals(4_500L, m.userWaitMs)
        assertEquals(500L, m.actMs)
        assertEquals(1, m.actCalls)
        assertEquals(m.totalMs - m.modelMs - m.actMs, m.otherMs)
    }

    @Test fun controlToolsAreNeitherObserveNorAct() {
        val events = Dsh().turnStart().user("q").stepStart(1).after(10).assistant(1, "", toolCall = "ask_user", firstTokenAt = 1_000_005)
            .toolCall("a", "ask_user", """{"question":"哪个?"}""").after(3_000).toolResult("a", """{"answer":"第一个"}""").stepEnd(1).turnEnd().events
        val m = RunMetrics.of(events)!!
        assertEquals(0L, m.observeMs + m.actMs)
        assertEquals(0, m.observeCalls + m.actCalls)
        assertEquals(3_000L, m.toolMs)
        assertEquals(1, m.toolCalls)
    }

    @Test fun retryWaitIsNotModelTime() {
        val events = Dsh().turnStart().user("q").stepStart(1).after(100).retry("r1").after(2_000).retryStarted("r1").after(300)
            .assistant(1, "ok", firstTokenAt = 1_002_350).stepEnd(1).turnEnd().events
        val m = RunMetrics.of(events)!!
        assertEquals(1, m.retries)
        assertEquals(2_000L, m.retryWaitMs)
        assertEquals(400L, m.modelMs)
    }

    @Test fun outcomesMapFromTurnEnd() {
        fun end(kind: String) = RunMetrics.of(Dsh().turnStart().user("q").stepStart(1).after(10).assistant(1, "x").stepEnd(1).turnEnd(kind).events)!!
        assertEquals("succeeded", end("completed").outcome)
        assertEquals("cancelled", end("aborted").outcome)
        assertEquals("cancelled", end("interrupted").outcome)
        assertEquals("failed", end("error").outcome)
        assertEquals("error", end("error").failKind)
        assertEquals("failed", end("max-tokens").outcome)
        assertFalse(end("error").succeeded)
        val open = RunMetrics.of(Dsh().turnStart().user("q").stepStart(1).after(10).events)!!
        assertEquals(RunMetrics.UNFINISHED, open.outcome)
        assertFalse(open.ended)
        assertNull(RunMetrics.of(Dsh().user("q").events))
    }

    @Test fun judgedNotDoneIsNotSuccess() {
        val b = RunMetrics.Builder(); Dsh.turn().forEach(b::add); b.judged("partial")
        assertFalse(b.build()!!.succeeded)
    }

    @Test fun injectedContextIsNotTheGoal() {
        val events = Dsh().turnStart().user("AGENTS.md 内容", kind = "inject").user("真正的问题").stepStart(1).after(5).assistant(1, "a").stepEnd(1).turnEnd().events
        assertEquals("真正的问题", RunMetrics.of(events)!!.goal)
    }

    @Test fun slowestToolsAreTopThree() {
        val d = Dsh().turnStart().user("q").stepStart(1).after(5).assistant(1, "", toolCall = "bash")
        listOf("a" to 100L, "b" to 900L, "c" to 300L, "d" to 700L).forEach { (id, ms) -> d.toolCall(id, if (id == "b") "web_fetch" else "bash").after(ms).toolResult(id, "o") }
        d.stepEnd(1).turnEnd()
        val m = RunMetrics.of(d.events)!!
        assertEquals(listOf(900L, 700L, 300L), m.slowestTools.map { it.ms })
        assertEquals("web_fetch", m.slowestTools.first().name)
        assertEquals(900L, m.observeMs)
        assertEquals(1_100L, m.actMs)
    }

    @Test fun jsonRoundTripKeepsEveryField() {
        val m = RunMetrics.of(Dsh.turn(), "s#9")!!.copy(judged = "done", feedbackSource = "ui")
        val back = RunMetrics.fromJson(JSONObject(m.toJson().toString()))
        assertEquals(m, back)
        // Mika 的 metrics 也能读(没有的字段取默认)
        val mika = RunMetrics.fromJson(JSONObject("""{"runId":"m1","totalMs":5000,"firstFeedbackMs":800,"modelCalls":3,"inputTokens":10,"outputTokens":2,"outcome":"succeeded","ended":true}"""))
        assertEquals("mika", mika.engine)
        assertEquals(800L, mika.firstFeedbackMs)
        assertTrue(mika.succeeded)
    }

    @Test fun toolKindsByName() {
        assertEquals(ToolKinds.OBSERVE, ToolKinds.kindOf("read"))
        assertEquals(ToolKinds.OBSERVE, ToolKinds.kindOf("web_search"))
        assertEquals(ToolKinds.OBSERVE, ToolKinds.kindOf("phone_read_screen"))
        for (read in listOf("phone_query", "phone_extract", "phone_find", "phone_collect")) assertEquals(read, ToolKinds.OBSERVE, ToolKinds.kindOf(read))
        assertEquals(ToolKinds.ACT, ToolKinds.kindOf("bash"))
        assertEquals(ToolKinds.ACT, ToolKinds.kindOf("write"))
        assertEquals(ToolKinds.ACT, ToolKinds.kindOf("phone_tap"))
        // 名字里不带只读动词的未知工具按操作算
        assertEquals(ToolKinds.ACT, ToolKinds.kindOf("frobnicate"))
        assertNull(ToolKinds.kindOf("ask_user"))
    }

    @Test fun turnsSplitAndKnowTheirUserMessage() {
        val d = Dsh()
        d.turnStart(1).user("第一问").stepStart(1).after(5).assistant(1, "a").stepEnd(1).turnEnd(turn = 1)
        d.turnStart(2).user("第二问", turn = 2).stepStart(1, turn = 2).after(5)
        val turns = DshTurn.split(d.events.shuffled(java.util.Random(7)))
        assertEquals(listOf(1, 2), turns.map { it.turn })
        assertTrue(turns[0].ended)
        assertFalse(turns[1].ended)
        assertEquals(2, turns[0].userSeq)
        assertEquals("第二问", RunMetrics.of(turns[1].events)!!.goal)
    }

    @Test fun parsesDiskLineShapes() {
        val wrapped = DshEvent.parse("""{"event":{"seq":3,"type":"turn/start","time":1730000000000,"data":{"turn":1}}}""")!!
        assertEquals(3, wrapped.seq); assertEquals(1, wrapped.turn); assertEquals(1_730_000_000_000L, wrapped.time)
        val bare = DshEvent.parse("""{"seq":4,"type":"step/start","time":"2026-10-31T00:00:00Z","data":{"turn":1,"step":2}}""")!!
        assertEquals(2, bare.step); assertTrue(bare.time > 0)
        assertNull(DshEvent.parse("""{"id":"session-header","version":4}"""))
        assertNull(DshEvent.parse("not json"))
    }

    @Test fun firstTokenFollowsDshRules() {
        fun t(json: String) = DshEvent.firstTokenTime(org.json.JSONArray(json))
        // 空片段不算;dt 累加
        assertEquals(1_015L, t("""[{"type":"text-chunks","time0":1000,"index":0,"dt":[5,10],"texts":["","",  "a"]}]"""))
        // 原样 chunk:只有非空 delta 才算
        assertEquals(2_000L, t("""[{"type":"chunk","time":1500,"chunk":{"type":"usage"}},{"type":"chunk","time":2000,"chunk":{"type":"text-delta","text":"h"}}]"""))
        // 带名字的工具调用片段从第一片算
        assertEquals(700L, t("""[{"type":"tool-call-chunks","time0":700,"index":0,"dt":[],"id":"c","name":"bash","args":[""]}]"""))
        assertNull(t("""[{"type":"chunk","time":1500,"chunk":{"type":"finish"}}]"""))
    }
}
