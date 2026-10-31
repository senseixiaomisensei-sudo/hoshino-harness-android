package app.hoshino.harness.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleVisibilityTest {
    private val v = CapsuleVisibility(hideDelayMs = 800, minShowMs = 1_500)

    @Test fun showsImmediatelyOnce() {
        val a = v.update(true, 0)
        assertTrue(a.shown); assertTrue(a.changed)
        val b = v.update(true, 10)
        assertTrue(b.shown); assertFalse(b.changed)
    }

    @Test fun idleBlipBetweenStepsDoesNotHide() {
        v.update(true, 0)
        val gap = v.update(false, 2_000)          // controller drops to Idle after a step
        assertTrue(gap.shown); assertFalse(gap.changed); assertEquals(2_800L, gap.recheckAt)
        val back = v.update(true, 2_300)           // next step starts within the grace window
        assertTrue(back.shown); assertFalse(back.changed)
        val later = v.update(false, 2_900)         // grace restarts from the new false edge
        assertTrue(later.shown); assertEquals(3_700L, later.recheckAt)
    }

    @Test fun hidesAfterDelay() {
        v.update(true, 0)
        v.update(false, 2_000)
        val still = v.update(false, 2_500)
        assertTrue(still.shown); assertFalse(still.changed)
        val gone = v.update(false, 2_800)
        assertFalse(gone.shown); assertTrue(gone.changed); assertNull(gone.recheckAt)
        val again = v.update(false, 3_000)
        assertFalse(again.changed)
    }

    @Test fun minimumShowTimeHonoured() {
        v.update(true, 0)
        val d = v.update(false, 100)
        assertTrue(d.shown); assertEquals(1_500L, d.recheckAt)
        assertTrue(v.update(false, 1_000).shown)
        assertFalse(v.update(false, 1_500).shown)
    }

    @Test fun immediateHideForScreenOffOrLock() {
        v.update(true, 0)
        val d = v.update(false, 50, immediate = true)
        assertFalse(d.shown); assertTrue(d.changed)
    }

    @Test fun rapidToggleNeverRemovesWindow() {
        var changes = 0
        var t = 0L
        repeat(50) { i -> if (v.update(i % 2 == 0, t).changed) changes++; t += 300 }
        assertEquals(1, changes) // only the first show
    }

    @Test fun foregroundFilter() {
        assertTrue(CapsuleVisibility.countsAsForeground("com.tencent.mm", true))
        assertTrue(CapsuleVisibility.countsAsForeground("com.tencent.mm", null))
        assertFalse(CapsuleVisibility.countsAsForeground("app.hoshino.harness", false)) // our own overlay window
        assertFalse(CapsuleVisibility.countsAsForeground("com.android.systemui", true))
        assertFalse(CapsuleVisibility.countsAsForeground(null, true))
    }
}
