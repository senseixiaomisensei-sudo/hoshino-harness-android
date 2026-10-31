package app.hoshino.harness.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port whose every call costs scripted time, and which logs the calls so tests can prove timing adds none. */
private class TimedPort(var screen: ScreenSnapshot) : PhonePort {
    var clock = 1_000L
    var readCost = 30L
    var performCost = 50L
    var quietCost = 400L
    var performOk = true
    val calls = mutableListOf<String>()
    val pending = ArrayDeque<ScreenSnapshot>()
    override suspend fun read(): ScreenSnapshot { calls += "read"; clock += readCost; return screen }
    override suspend fun perform(step: PhoneStep, target: UiElement?): ActionOutcome { calls += "perform"; clock += performCost; return ActionOutcome(performOk, if (performOk) null else "boom") }
    override suspend fun awaitChange(timeoutMs: Long): Boolean {
        calls += "await"
        val next = pending.removeFirstOrNull()
        clock += if (next != null) 200 else timeoutMs
        if (next != null) screen = next
        return next != null
    }
    override suspend fun waitQuiet(quietMs: Long, timeoutMs: Long): Boolean { calls += "quiet"; clock += quietCost; return true }
    override fun now() = clock
}

class StepTelemetryTest {
    private val home = snap(el("e1", "搜索", listOf(0, 0, 800, 100), editable = true, role = "EditText"), el("e2", "张三", listOf(0, 200, 999, 300)))
    private val loaded = snap(el("e3", "已加载", listOf(0, 0, 999, 100), clickable = false))
    private val noApproval = ApprovalGate { false }

    // ---------- statistics ----------

    @Test fun nearestRankPercentiles() {
        val v = (1L..10L).shuffled()
        assertEquals(5L, StepStats.percentile(v, 50.0))
        assertEquals(9L, StepStats.percentile(v, 90.0))
        assertEquals(10L, StepStats.percentile(v, 100.0))
        assertEquals(1L, StepStats.percentile(v, 1.0))
        assertEquals(700L, StepStats.percentile(listOf(700L), 90.0))
        assertNull(StepStats.percentile(emptyList(), 50.0))
        // Always an observed value, never an interpolation.
        assertEquals(400L, StepStats.percentile(listOf(400L, 600L), 50.0))
        assertEquals(600L, StepStats.percentile(listOf(400L, 600L), 90.0))
        for (bad in listOf(0.0, -1.0, 100.1)) try { StepStats.percentile(v, bad); fail("accepted $bad") } catch (expected: IllegalArgumentException) {}
    }

    @Test fun summaryOfASeries() {
        val s = StepStats.summarize(listOf(500L, 100L, 300L, 900L, 200L))
        assertEquals(5, s.n)
        assertEquals(300L, s.p50)
        assertEquals(900L, s.p90)
        assertEquals(100L, s.min)
        assertEquals(900L, s.max)
        assertEquals(400.0, s.mean!!, 0.0001)
        val empty = StepStats.summarize(emptyList())
        assertEquals(0, empty.n)
        assertNull(empty.p50); assertNull(empty.p90); assertNull(empty.mean)
        assertTrue(empty.json().isNull("p50"))
        assertEquals(300L, s.json().getLong("p50"))
    }

    @Test fun framesPerSecondOverTheSpan() {
        assertEquals(5.0, StepStats.fps(listOf(0L, 200L, 400L, 600L, 800L, 1000L))!!, 0.0001)
        assertEquals(10.0, StepStats.fps(listOf(1100L, 1000L, 1200L))!!, 0.0001)
        assertNull(StepStats.fps(listOf(42L)))
        assertNull(StepStats.fps(emptyList()))
        assertNull(StepStats.fps(listOf(5L, 5L)))
    }

    @Test fun telemetryKeepsTheLastFiftyAndSkipsFailuresInPercentiles() {
        val t = StepTelemetry()
        repeat(60) { i -> t.record(StepKind.tap.name, true, StepTiming(1, 10, 400, 30, 441L + i)) }
        t.record(StepKind.tap.name, false, StepTiming(1, 99_999, 0, 0, 100_000))
        t.record(StepKind.open_app.name, true, StepTiming(2, 300, 900, 40, 1_250))
        t.recordDisplayCreate(87)
        val j = t.summary()
        assertEquals(50, j.getInt("steps"))
        assertEquals(1, j.getInt("failed"))
        assertEquals(48, j.getJSONObject("total").getInt("n"))
        assertTrue(j.getJSONObject("act").getLong("max") < 99_999)
        assertEquals(1_250L, j.getJSONObject("open_app").getLong("p50"))
        assertEquals(1, j.getJSONObject("open_app").getInt("n"))
        assertEquals(87L, j.getLong("display_create_ms"))
        assertTrue(StepTelemetry().summary().isNull("display_create_ms"))
    }

    @Test fun auditLineHoldsKindAndNumbersOnly() {
        val line = StepTiming(3, 45, 410, 28, 490).auditLine("type", true)
        assertEquals("step kind=type ok=1 resolve=3 act=45 wait=410 read=28 total=490", line)
        assertTrue(line.matches(Regex("step kind=[a-z_]+ ok=[01]( [a-z]+=\\d+)+")))
    }

    // ---------- StepRunner timing ----------

    @Test fun stepWithoutExpectationSplitsActWaitAndRead() = runBlocking {
        val port = TimedPort(home)
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, ref = "e2")))
        assertTrue(r.completed)
        assertEquals(30L, r.initialReadMs)
        val t = r.results.single().timing!!
        assertEquals(0L, t.resolveMs)
        assertEquals(50L, t.actMs)
        assertEquals(400L, t.waitMs)
        assertEquals(30L, t.readMs)
        assertEquals(480L, t.totalMs)
        // waited_ms keeps its old meaning: from the action until the result was known.
        assertEquals(480L, r.results.single().waitedMs)
        // Timing adds no port call: still read, act, wait quiet, read.
        assertEquals(listOf("read", "perform", "quiet", "read"), port.calls)
    }

    @Test fun expectationLoopSumsEveryWaitAndRead() = runBlocking {
        val port = TimedPort(home)
        port.pending += snap(el("x", "加载中", listOf(0, 0, 999, 999), clickable = false))
        port.pending += loaded
        val step = PhoneStep(StepKind.tap, ref = "e2", expect = Expectation(text = "已加载", timeoutMs = 5_000))
        val r = StepRunner(port, noApproval).run(listOf(step))
        assertTrue(r.reason ?: "", r.completed)
        val t = r.results.single().timing!!
        // reads: after act, after 1st change, after 2nd change = 3 × 30; waits: 2 changes × 200.
        assertEquals(90L, t.readMs)
        assertEquals(400L, t.waitMs)
        assertEquals(50L, t.actMs)
        assertEquals(t.resolveMs + t.actMs + t.waitMs + t.readMs, t.totalMs)
        assertEquals(listOf("read", "perform", "read", "await", "read", "await", "read"), port.calls)
    }

    @Test fun timingIsPerStepNotCumulative() = runBlocking {
        val port = TimedPort(home)
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, ref = "e2"), PhoneStep(StepKind.back), PhoneStep(StepKind.back)))
        assertTrue(r.completed)
        assertEquals(3, r.results.size)
        for (x in r.results) {
            val t = x.timing!!
            assertEquals(30L, t.readMs)
            assertEquals(400L, t.waitMs)
            assertEquals(480L, t.totalMs)
        }
    }

    @Test fun failedActionReportsActTimeAndNoWait() = runBlocking {
        val port = TimedPort(home).apply { performOk = false }
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.back)))
        assertFalse(r.completed)
        val x = r.results.single()
        assertFalse(x.ok)
        assertEquals(StepTiming(0, 50, 0, 0, 50), x.timing)
    }

    @Test fun stepsStoppedBeforeActingHaveNoTiming() = runBlocking {
        val port = TimedPort(home)
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, target = "不存在的按钮")))
        assertEquals(0, r.stoppedAt)
        assertNull(r.results.single().timing)
        assertEquals(listOf("read"), port.calls)
    }

    @Test fun typedTextNeverReachesTheTimingOrAudit() = runBlocking {
        val port = TimedPort(home)
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.type, ref = "e1", text = "我的秘密口令123")))
        assertTrue(r.completed)
        val t = r.results.single().timing!!
        val out = t.json().toString() + t.auditLine("type", true)
        assertFalse(out.contains("秘密")); assertFalse(out.contains("口令"))
    }
}
