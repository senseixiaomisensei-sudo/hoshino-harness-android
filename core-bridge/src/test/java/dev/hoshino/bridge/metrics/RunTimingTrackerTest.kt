package dev.hoshino.bridge.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunTimingTrackerTest {
    private fun builder(events: List<DshEvent>) = RunMetrics.Builder("s#1").apply { events.forEach(::add) }

    @Test fun uiFeedbackAndUserWaitsApplyToALiveTurn() {
        val events = Dsh.turn() // turn/start 在 1_000_000
        val start = events.first().time
        val t = RunTimingTracker()
        t.turnStarted("s", start + 30)
        t.feedback("s", start + 120)
        t.feedback("s", start + 900) // 只记第一次
        t.waitStarted("s", "a1", start + 420); assertTrue(t.waiting("s"))
        t.waitEnded("s", "a1", start + 900); assertFalse(t.waiting("s"))
        val b = builder(events)
        assertTrue(t.apply("s", start, b))
        val m = b.build()!!
        assertEquals("ui", m.feedbackSource)
        assertEquals(120L, m.firstFeedbackMs)
        // web_fetch 从 start+410 到 start+1410；其中 420..900 在等用户
        assertEquals(480L, m.userWaitMs)
        assertEquals(1_000L - 480L, m.observeMs)
        assertFalse("记录用完即丢", t.apply("s", start, builder(events)))
    }

    /** 打开旧会话时的回放：界面看到「开始」的时刻和日志里的开始差得远，不用界面实测，只按日志算。 */
    @Test fun replayedTurnsKeepLogTiming() {
        val events = Dsh.turn()
        val start = events.first().time
        val t = RunTimingTracker()
        t.turnStarted("s", start + 3_600_000)
        t.feedback("s", start + 3_600_010)
        val b = builder(events)
        assertFalse(t.apply("s", start, b))
        val m = b.build()!!
        assertEquals("log", m.feedbackSource)
        assertEquals(RunMetrics.of(events, "s#1"), m)
    }

    @Test fun eventsForUnknownSessionsAreIgnored() {
        val t = RunTimingTracker()
        t.feedback("x", 1); t.waitStarted("x", "q", 1); t.waitEnded("x", "q", 2)
        assertFalse(t.waiting("x"))
        assertFalse(t.apply("x", 0, RunMetrics.Builder()))
    }
}
