// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All cases of Mika's ChangeWaitTest (same scenarios, same assertions; the "normal page" window is expressed through
 * [ChangeWait.QUIET_MS] because ours is 250 ms instead of 400 ms), plus WP-1 cases for slow pages and static pages.
 */
class ChangeWaitTest {
    private val cells = FrameMath.GRID_W * FrameMath.GRID_H
    private val before = IntArray(cells) { 200 }
    private val after = IntArray(cells) { 20 }
    /** A different picture every 50 ms (still drawing, playing). */
    private fun moving(t: Long) = IntArray(cells) { ((t / 50 * 37 + it * 11) % 256).toInt() }

    /** Fake clock: sleep advances time; frameAt/lastEventAt give the picture and the newest event time at each moment. */
    private class Fake(
        val frameAt: (Long) -> IntArray?,
        val lastEventAt: (Long) -> Long = { -10_000 },
        val treeChangedAt: Long = Long.MAX_VALUE,
    ) {
        var now = 0L
        suspend fun result(before: IntArray?) = ChangeWait.awaitStableChange(
            now = { now },
            sleep = { now += it },
            frame = { frameAt(now) },
            quietForMs = { now - lastEventAt(now) },
            treeChanged = { now >= treeChangedAt },
            beforeFrame = before,
        )
        suspend fun run(before: IntArray?) = result(before).changed
    }

    // ---------- Mika ChangeWaitTest, ported ----------

    @Test fun slowPageChangeIsReportedAfterFramesSettle() = runTest {
        // The page starts drawing at 1.2 s and finishes at 1.6 s; events stay quiet and the tree never changes (self-drawn UI).
        val f = Fake(frameAt = { t -> when { t < 1_200 -> before; t < 1_600 -> moving(t); else -> after } })
        assertTrue(f.run(before))
        assertTrue("waited for the frame to settle, at ${f.now}", f.now >= 1_600)
        assertTrue(f.now <= ChangeWait.CAP_MS)
    }

    @Test fun fastPathReturnsAsSoonAsChangeIsStable() = runTest {
        val f = Fake(frameAt = { t -> if (t < 100) before else after })
        assertTrue(f.run(before))
        assertTrue("returned early, at ${f.now}", f.now <= 2 * ChangeWait.POLL_MS)
    }

    @Test fun normalPageChangeReturnsSoonAfterEventsQuiet() = runTest {
        // Ordinary page change: events until 100 ms, tree and picture both changed.
        val f = Fake(frameAt = { t -> if (t < 100) before else after }, lastEventAt = { t -> minOf(t, 100) }, treeChangedAt = 50)
        assertTrue(f.run(before))
        assertTrue("returned at ${f.now}", f.now in (100 + ChangeWait.QUIET_MS)..(100 + ChangeWait.QUIET_MS + 20))
    }

    @Test fun neverStillPageWithTreeChangeReturnsFast() = runTest {
        // Video / live stream: the picture never stops, but events are quiet and the tree changed: do not wait for stillness.
        val f = Fake(frameAt = ::moving, treeChangedAt = 0)
        assertTrue(f.run(before))
        assertTrue("returned at ${f.now}", f.now <= ChangeWait.POLL_MS)
    }

    @Test fun nothingChangesReturnsFalseAtCap() = runTest {
        val f = Fake(frameAt = { before })
        assertFalse(f.run(before))
        assertTrue(f.now >= ChangeWait.CAP_MS)
        assertTrue(f.now < ChangeWait.CAP_MS + ChangeWait.POLL_MS)
    }

    @Test fun waitsForEventQuietBeforeJudging() = runTest {
        // The tree changed at once, but events keep coming until 1 s.
        val f = Fake(frameAt = { after }, lastEventAt = { t -> minOf(t, 1_000) }, treeChangedAt = 0)
        assertTrue(f.run(before))
        assertTrue("judged at ${f.now}", f.now >= 1_000 + ChangeWait.QUIET_MS)
    }

    @Test fun treeChangeWithoutFrameCounts() = runTest {
        // No picture (preview frames unavailable): only the tree counts.
        val f = Fake(frameAt = { null }, treeChangedAt = 900)
        assertTrue(f.run(null))
        assertTrue(f.now in 900..ChangeWait.CAP_MS)
    }

    @Test fun stillAnimatingAtCapButChangedCountsAsChanged() = runTest {
        val f = Fake(frameAt = ::moving)
        assertTrue(f.run(before))
        assertTrue(f.now >= ChangeWait.CAP_MS)
    }

    @Test fun eventsNeverQuietDecidesAtCapWithCurrentState() = runTest {
        // Events never stop (a progress bar): decide at the cap with the state at that moment.
        val changed = Fake(frameAt = { after }, lastEventAt = { it }, treeChangedAt = 0)
        assertTrue(changed.run(before))
        assertTrue(changed.now >= ChangeWait.CAP_MS)
        assertFalse(Fake(frameAt = { before }, lastEventAt = { it }).run(before))
    }

    // ---------- WP-1: slow pages are not misjudged, static pages return fast ----------

    /** The pre-WP-1 rule: quiet for 400 ms after the action (subscribed after it), compared only with the screen at that point. */
    private fun oldRuleSaysNoEffect(firstChangeAtMs: Long) = firstChangeAtMs > StepRunner.QUIET_MS

    @Test fun slowTreePageIsNotMisjudgedAsNoEffect() = runTest {
        // A list page reacts after 600 / 1200 ms with no event before that (network), then events for 150 ms.
        for (delay in listOf(600L, 1_200L, 2_000L)) {
            val f = Fake(frameAt = { t -> if (t < delay) before else after }, lastEventAt = { t -> if (t < delay) -10_000 else minOf(t, delay + 150) }, treeChangedAt = delay)
            val r = f.result(before)
            assertTrue("change at $delay ms seen", r.changed)
            assertTrue(r.treeChanged)
            assertTrue(r.settled)
            assertTrue("returned after the page settled ($delay ms): ${f.now}", f.now >= delay + 150 + ChangeWait.QUIET_MS)
            assertTrue(f.now <= ChangeWait.CAP_MS)
            assertTrue("the old rule would have said no_effect for $delay ms", oldRuleSaysNoEffect(delay))
        }
    }

    @Test fun slowSelfDrawnPageCountsAsFrameOnlyChange() = runTest {
        val f = Fake(frameAt = { t -> if (t < 1_200) before else after })
        val r = f.result(before)
        assertTrue(r.changed)
        assertTrue(r.frameChanged)
        assertFalse(r.treeChanged)
        assertTrue(f.now in 1_200L..(1_200 + 2 * ChangeWait.POLL_MS))
    }

    @Test fun eventsBeforeTheWaitStartedStillCount() = runTest {
        // The action's events came while the action itself ran (before the wait was called): the subscription predates
        // the action, so the quiet window is measured from them and the tree change is judged against "before".
        val f = Fake(frameAt = { after }, lastEventAt = { -30 }, treeChangedAt = -40)
        val r = f.result(before)
        assertTrue(r.changed)
        assertTrue("quiet window counted from the event at -30 ms, returned at ${f.now}", f.now in (ChangeWait.QUIET_MS - 30)..ChangeWait.QUIET_MS)
    }

    @Test fun staticPageAfterAChangeReturnsQuickly() = runTest {
        // A settings page: tap, the next page is there at 80 ms, last event at 120 ms, nothing animates.
        val f = Fake(frameAt = { t -> if (t < 80) before else after }, lastEventAt = { t -> minOf(t, 120) }, treeChangedAt = 80)
        val r = f.result(before)
        assertTrue(r.changed)
        assertTrue("returned at ${f.now}", f.now <= 120 + ChangeWait.QUIET_MS + ChangeWait.POLL_MS)
        assertTrue("faster than Mika's 400 ms quiet window", f.now < 120 + 400)
    }

    @Test fun cursorBlinkIsNotAChange() = runTest {
        // Two cells flicker (cursor): below FrameMath's threshold, so a missed tap still ends as "no change".
        val blink = before.copyOf().also { it[5] = 0; it[6] = 0 }
        val f = Fake(frameAt = { t -> if ((t / 500) % 2 == 0L) before else blink })
        val r = f.result(before)
        assertFalse(r.changed)
        assertTrue(f.now >= ChangeWait.CAP_MS)
    }
}
