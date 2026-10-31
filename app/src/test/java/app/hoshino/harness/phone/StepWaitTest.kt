package app.hoshino.harness.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-1 (A-1) at the [StepRunner] level, on a virtual-time screen: an action schedules the next screen (or only a new
 * picture) some milliseconds later, with UI events while it draws. [legacy] = the port does not override
 * [PhonePort.watch], i.e. the pre-WP-1 wait (subscribe after the action, quiet 400 ms, compare with that moment).
 */
private class TimelinePort(start: ScreenSnapshot, private val legacy: Boolean = false) : PhonePort {
    var clock = 0L
    private var screen = start
    private var next: ScreenSnapshot? = null
    private var nextAt = Long.MAX_VALUE
    /** Events fire from [nextAt] for [drawMs]. */
    var drawMs = 150L
    private var frame: IntArray = IntArray(FrameMath.GRID_W * FrameMath.GRID_H) { 200 }
    private var nextFrame: IntArray? = null
    private var nextFrameAt = Long.MAX_VALUE
    var onTap: (TimelinePort) -> Unit = {}
    val performed = mutableListOf<String>()

    fun schedule(s: ScreenSnapshot, afterMs: Long) { next = s; nextAt = clock + afterMs }
    fun scheduleFrame(afterMs: Long) { nextFrame = IntArray(FrameMath.GRID_W * FrameMath.GRID_H) { 20 }; nextFrameAt = clock + afterMs }

    private fun current(): ScreenSnapshot { if (clock >= nextAt) next?.let { screen = it; next = null }; return screen }
    private fun currentFrame(): IntArray { if (clock >= nextFrameAt) nextFrame?.let { frame = it; nextFrame = null }; return frame }
    /** Time of the newest event at or before now (events run during drawing). */
    private var drawStart = Long.MIN_VALUE
    private fun lastEvent(): Long { if (clock >= nextAt && drawStart == Long.MIN_VALUE) drawStart = nextAt; return if (drawStart == Long.MIN_VALUE) Long.MIN_VALUE / 2 else minOf(clock, drawStart + drawMs) }
    private fun eventsBetween(from: Long, to: Long): Boolean { val s = nextAt.takeIf { it != Long.MAX_VALUE } ?: drawStart; if (s == Long.MIN_VALUE || s == Long.MAX_VALUE) return false; return to >= s && from <= s + drawMs }

    override suspend fun read(): ScreenSnapshot = current()
    override suspend fun perform(step: PhoneStep, target: UiElement?): ActionOutcome {
        performed += step.kind.name
        if (step.kind == StepKind.tap) onTap(this)
        return ActionOutcome(true)
    }
    override suspend fun awaitChange(timeoutMs: Long): Boolean {
        val from = clock
        val until = clock + timeoutMs
        if (nextAt in from..until) { clock = maxOf(clock, nextAt); return true }
        clock = until
        return eventsBetween(from, until)
    }
    /** Pre-WP-1 waitQuiet: from now on, return once no event came for [quietMs]. */
    override suspend fun waitQuiet(quietMs: Long, timeoutMs: Long): Boolean {
        val deadline = clock + timeoutMs
        while (clock < deadline) {
            val from = clock
            clock = minOf(deadline, clock + quietMs)
            if (!eventsBetween(from, clock)) return true
        }
        return false
    }
    override fun now() = clock

    override suspend fun <T> watch(block: suspend (ChangeProbe) -> T): T {
        if (legacy) return super.watch(block)
        val beforeHash = current().hash
        val beforeTexts = current().texts
        val beforeFrame = currentFrame()
        val subscribedAt = clock
        return block(object : ChangeProbe {
            override suspend fun awaitStableChange() = ChangeWait.awaitStableChange(
                now = { clock }, sleep = { clock += it }, frame = { currentFrame() },
                quietForMs = { clock - maxOf(subscribedAt, lastEvent()) },
                treeChanged = { current().let { it.hash != beforeHash || it.texts != beforeTexts } },
                beforeFrame = beforeFrame,
            )
            override fun events(): Long = if (clock >= nextAt || drawStart != Long.MIN_VALUE) 1 else 0
            override suspend fun awaitEventAfter(seen: Long, timeoutMs: Long) = awaitChange(timeoutMs)
        })
    }
}

class StepWaitTest {
    private val list = snap(el("e1", "设置", listOf(0, 0, 999, 80), clickable = false), el("e2", "WLAN", listOf(0, 100, 999, 180)), el("e3", "蓝牙", listOf(0, 200, 999, 280))).copy(hash = 1)
    private val wlan = snap(el("e4", "WLAN", listOf(0, 0, 999, 80), clickable = false), el("e5", "开启 WLAN", listOf(0, 100, 999, 180))).copy(hash = 2)
    private val noApproval = ApprovalGate { false }

    private suspend fun tapWithPageAfter(delayMs: Long, legacy: Boolean): Pair<StepsResult, TimelinePort> {
        val port = TimelinePort(list, legacy)
        port.onTap = { it.schedule(wlan, delayMs) }
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, ref = "e2")))
        return r to port
    }

    @Test fun slowPageIsNotMisjudgedAsNoEffect() = runBlocking {
        for (delay in listOf(600L, 1_200L)) {
            val (old, _) = tapWithPageAfter(delay, legacy = true)
            // Baseline: the pre-WP-1 wait returned on the old screen, and the run was reported as no_effect.
            assertEquals("legacy wait read the old page ($delay ms)", list, old.screen)
            assertEquals(Effect.no_effect, ActionEffects.of(list, old.screen, old.frameChanged).effect)

            val (r, port) = tapWithPageAfter(delay, legacy = false)
            assertTrue(r.completed)
            assertEquals("waited for the slow page ($delay ms)", wlan, r.screen)
            assertEquals(Effect.done, ActionEffects.of(list, r.screen, r.frameChanged).effect)
            val c = r.results.single().change!!
            assertTrue(c.changed); assertTrue(c.treeChanged); assertTrue(c.settled)
            assertTrue("returned ${port.clock} ms, within the cap", port.clock <= ChangeWait.CAP_MS)
        }
    }

    @Test fun fiftySlowPagesNeverReportNoEffect() = runBlocking {
        // A-1 acceptance shape (fixture: page after 600 / 1200 ms), 50 runs with delays spread over 300..2000 ms.
        var misjudged = 0
        for (i in 0 until 50) {
            val delay = 300L + i * 34
            val (r, _) = tapWithPageAfter(delay, legacy = false)
            if (ActionEffects.of(list, r.screen, r.frameChanged).effect == Effect.no_effect) misjudged++
        }
        assertEquals(0, misjudged)
    }

    @Test fun staticPageReturnsFast() = runBlocking {
        val (r, port) = tapWithPageAfter(50, legacy = false)
        assertEquals(wlan, r.screen)
        // Page at 50 ms, events until 200 ms, quiet window 250 ms, poll 100 ms.
        assertTrue("step took ${port.clock} ms", port.clock <= 50 + 150 + ChangeWait.QUIET_MS + ChangeWait.POLL_MS)
        val t = r.results.single().timing!!
        assertEquals(port.clock, t.totalMs)
    }

    @Test fun selfDrawnPageCountsByFrame() = runBlocking {
        // Flutter-like: the tree never changes, only the picture (at 900 ms).
        for (legacy in listOf(true, false)) {
            val port = TimelinePort(list, legacy)
            port.onTap = { it.scheduleFrame(900) }
            val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, ref = "e2")))
            val effect = ActionEffects.of(list, r.screen, r.frameChanged).effect
            if (legacy) assertEquals(Effect.no_effect, effect)
            else {
                assertEquals(Effect.done, effect)
                assertTrue(r.frameChanged)
                assertFalse(r.results.single().change!!.treeChanged)
            }
        }
    }

    @Test fun missedTapStillEndsAsNoEffectAtTheCap() = runBlocking {
        val port = TimelinePort(list)
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, ref = "e2")))
        assertEquals(Effect.no_effect, ActionEffects.of(list, r.screen, r.frameChanged).effect)
        val c = r.results.single().change!!
        assertFalse(c.changed)
        assertTrue(port.clock >= ChangeWait.CAP_MS)
    }

    @Test fun expectationStepsKeepTheirOwnWait() = runBlocking {
        val port = TimelinePort(list)
        port.onTap = { it.schedule(wlan, 1_500) }
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, ref = "e2", expect = Expectation(text = "开启 WLAN", timeoutMs = 5_000))))
        assertTrue(r.completed)
        assertEquals(true, r.results.single().met)
        assertNull(r.results.single().change)
    }

    @Test fun frameOnlyChangeIsAnEffect() {
        val a = snap(el("e1", "x", listOf(0, 0, 10, 10))).copy(hash = 5)
        assertEquals(Effect.no_effect, ActionEffects.of(a, a).effect)
        assertEquals(Effect.done, ActionEffects.of(a, a, frameChanged = true).effect)
        assertEquals(Effect.place_changed, ActionEffects.of(a, a.copy(packageName = "other"), frameChanged = true).effect)
    }
}
