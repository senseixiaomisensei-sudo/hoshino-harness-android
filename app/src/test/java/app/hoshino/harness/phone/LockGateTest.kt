// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** m2.10: screen/keyguard state ([LockState]) and the unlock gate ([LockGate]) that replaced the instant `locked` failure. */
@OptIn(ExperimentalCoroutinesApi::class)
class LockStateTest {
    private var interactive = false
    private var keyguardLocked = true
    private var reads = 0

    private fun TestScope.lockState() = LockState({ reads++; LockState.Reading(interactive, keyguardLocked) }, backgroundScope)

    private fun TestScope.tick() {
        advanceTimeBy(DeviceState.POLL_MS)
        runCurrent()
    }

    @Test fun screenOffOrKeyguardCountsAsLocked() {
        assertTrue(LockState.Reading(interactive = false, keyguardLocked = false).locked)
        assertTrue(LockState.Reading(interactive = true, keyguardLocked = true).locked)
        assertFalse(LockState.Reading(interactive = true, keyguardLocked = false).locked)
    }

    // Mika 736b53d: USER_PRESENT arrives before the keyguard is gone; no later broadcast. The gate must still see the unlock.
    @Test fun unlockSeenWithoutAnotherBroadcast() = runTest {
        val lock = lockState()
        val gate = LockGate(lock.locked, { testScheduler.currentTime })
        var outcome: LockGate.Outcome? = null
        launch { outcome = gate.await("s", testScheduler.currentTime, { null }, MutableStateFlow(0)) }
        runCurrent()
        assertNull(outcome)

        // Screen off: no reads at all, wait for SCREEN_ON.
        val dark = reads
        repeat(5) { tick() }
        assertEquals(dark, reads)

        interactive = true
        lock.refresh() // SCREEN_ON / USER_PRESENT: screen on, keyguard still up
        tick()
        assertNull(outcome)
        keyguardLocked = false // keyguard goes away, no broadcast
        tick()
        assertTrue(outcome is LockGate.Outcome.Clear)

        // Unlocked: no more reads.
        val unlocked = reads
        repeat(10) { tick() }
        assertEquals(unlocked, reads)
    }

    @Test fun noReadsWithoutSubscribers() = runTest {
        interactive = true
        val lock = lockState()
        runCurrent()
        val start = reads
        repeat(10) { tick() }
        assertEquals(start, reads)
        assertTrue(lock.locked.value)
    }

    // The capsule collects the same state: after unlock it sees false and comes back (the "stuck hidden" bug).
    @Test fun capsuleCollectorSeesUnlockAfterUserPresent() = runTest {
        interactive = true
        val lock = lockState()
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { lock.locked.collect { seen += it } }
        runCurrent()
        lock.refresh() // USER_PRESENT with keyguard still reading locked
        keyguardLocked = false
        tick()
        assertEquals(listOf(true, false), seen)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LockGateTest {
    private val locked = MutableStateFlow(false)
    private val ticks = MutableStateFlow(0L)
    private var stop: String? = null

    private fun TestScope.gate(timeout: Long = LockGate.TIMEOUT_MS, budget: Long = LockGate.CALL_BUDGET_MS) =
        LockGate(locked, { testScheduler.currentTime }, timeout, budget)

    @Test fun unlockedPassesAtOnceWithoutShowingWaiting() = runTest {
        val shown = mutableListOf<Boolean>()
        val o = gate().await("s", 0, { stop }, ticks) { shown += it }
        assertEquals(LockGate.Outcome.Clear(0), o)
        assertTrue(shown.isEmpty())
    }

    @Test fun lockedWaitsShowsWaitingAndContinuesByItselfAfterUnlock() = runTest {
        locked.value = true
        val g = gate()
        val shown = mutableListOf<Boolean>()
        var o: LockGate.Outcome? = null
        launch { o = g.await("s", testScheduler.currentTime, { stop }, ticks) { shown += it } }
        runCurrent()
        assertEquals(listOf(true), shown)
        advanceTimeBy(30_000)
        assertNull(o)
        locked.value = false
        runCurrent()
        assertEquals(LockGate.Outcome.Clear(30_000), o)
        assertEquals(listOf(true, false), shown)
    }

    @Test fun oneCallWaitsOnlyItsBudgetThenAsksToBeCalledAgain() = runTest {
        locked.value = true
        val g = gate()
        var o: LockGate.Outcome? = null
        launch { o = g.await("s", testScheduler.currentTime, { stop }, ticks) }
        advanceTimeBy(LockGate.CALL_BUDGET_MS + 1)
        runCurrent()
        val w = o as LockGate.Outcome.StillWaiting
        assertEquals(LockGate.CALL_BUDGET_MS, w.waitedMs)
        assertEquals(LockGate.TIMEOUT_MS - LockGate.CALL_BUDGET_MS, w.leftMs)
    }

    // The total cap spans the re-calls: it is counted from the first time the session found the phone locked.
    @Test fun totalWaitIsCappedAcrossCallsThenFailsAndFailsFastWhileStillLocked() = runTest {
        locked.value = true
        val g = gate()
        var last: LockGate.Outcome? = null
        var calls = 0
        while (last == null || last is LockGate.Outcome.StillWaiting) {
            calls++
            val start = testScheduler.currentTime
            var o: LockGate.Outcome? = null
            launch { o = g.await("s", start, { stop }, ticks) }
            advanceTimeBy(LockGate.CALL_BUDGET_MS + 1)
            runCurrent()
            last = o
        }
        assertTrue(last is LockGate.Outcome.TimedOut)
        assertEquals(LockGate.TIMEOUT_MS, (last as LockGate.Outcome.TimedOut).waitedMs)
        assertEquals(3, calls) // 240 s + 240 s + 120 s
        // Still locked: the next call fails at once instead of starting another 10 minutes.
        assertTrue(g.await("s", testScheduler.currentTime, { stop }, ticks) is LockGate.Outcome.TimedOut)
        // After an unlock the session starts afresh.
        locked.value = false
        assertEquals(LockGate.Outcome.Clear(0), g.await("s", testScheduler.currentTime, { stop }, ticks))
        locked.value = true
        var again: LockGate.Outcome? = null
        launch { again = g.await("s", testScheduler.currentTime, { stop }, ticks) }
        advanceTimeBy(1_000); runCurrent()
        assertNull(again)
        locked.value = false; runCurrent()
        assertEquals(LockGate.Outcome.Clear(1_000), again)
    }

    @Test fun stopEndsTheWaitAtOnce() = runTest {
        locked.value = true
        val g = gate()
        var o: LockGate.Outcome? = null
        launch { o = g.await("s", testScheduler.currentTime, { stop }, ticks) }
        advanceTimeBy(5_000); runCurrent()
        assertNull(o)
        stop = "stopped by request"
        ticks.value = 1
        runCurrent()
        assertEquals(LockGate.Outcome.Stopped("stopped by request"), o)
        // Unlocking later does not revive the stopped action.
        locked.value = false; runCurrent()
        assertEquals(LockGate.Outcome.Stopped("stopped by request"), o)
    }

    @Test fun anEarlierStopIsHonouredBeforeWaiting() = runTest {
        locked.value = true
        stop = "stopped"
        assertEquals(LockGate.Outcome.Stopped("stopped"), gate().await("s", 0, { stop }, ticks))
    }

    @Test fun sessionsWaitIndependently() = runTest {
        locked.value = true
        val g = gate(timeout = 60_000, budget = 60_000)
        var a: LockGate.Outcome? = null
        launch { a = g.await("a", testScheduler.currentTime, { stop }, ticks) }
        advanceTimeBy(50_000); runCurrent()
        var b: LockGate.Outcome? = null
        launch { b = g.await("b", testScheduler.currentTime, { stop }, ticks) }
        advanceTimeBy(10_001); runCurrent()
        assertTrue(a is LockGate.Outcome.TimedOut)
        assertNull(b)
        locked.value = false; runCurrent()
        assertEquals(LockGate.Outcome.Clear(10_001), b)
    }

    @Test fun midBatchWaitUsesWhatIsLeftOfTheCallBudget() = runTest {
        locked.value = true
        val g = gate()
        val callStart = testScheduler.currentTime
        advanceTimeBy(LockGate.CALL_BUDGET_MS) // the call already used its whole share
        assertTrue(g.await("s", callStart, { stop }, ticks) is LockGate.Outcome.StillWaiting)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KeepAwakeTest {
    private class FakeLock : WakeLockPort {
        var held = false; var acquires = 0; var releases = 0
        override fun acquire() { held = true; acquires++ }
        override fun release() { held = false; releases++ }
    }

    @Test fun holdsWhileWorkingAndReleasesAtOnceWhenTheTaskEnds() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.on(KeepAwake.Demand.Hold)
        awake.on(KeepAwake.Demand.Hold)
        assertTrue(lock.held); assertEquals(1, lock.acquires)
        awake.on(KeepAwake.Demand.Off)
        assertFalse(lock.held); assertEquals(1, lock.releases)
    }

    @Test fun waitingForTheUserKeepsItForTheGraceThenReleases() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.on(KeepAwake.Demand.Hold)
        awake.on(KeepAwake.Demand.Grace)
        advanceTimeBy(KeepAwake.GRACE_MS - 1); runCurrent()
        assertTrue(lock.held)
        advanceTimeBy(2); runCurrent()
        assertFalse(lock.held)
    }

    @Test fun backToWorkWithinTheGraceKeepsTheSameLock() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.on(KeepAwake.Demand.Hold)
        awake.on(KeepAwake.Demand.Grace)
        advanceTimeBy(KeepAwake.GRACE_MS / 2); runCurrent()
        awake.on(KeepAwake.Demand.Hold)
        advanceTimeBy(KeepAwake.GRACE_MS); runCurrent()
        assertTrue(lock.held)
        assertEquals(1, lock.acquires); assertEquals(0, lock.releases)
    }

    @Test fun graceWithoutHoldingNeverAcquiresAndCloseIsFinal() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.on(KeepAwake.Demand.Grace)
        assertEquals(0, lock.acquires)
        awake.on(KeepAwake.Demand.Hold)
        awake.close()
        assertFalse(lock.held)
        awake.on(KeepAwake.Demand.Hold)
        assertFalse(lock.held)
    }

    @Test fun demandFollowsTheSwitchTheDisplayAndTheTaskState() {
        val s = PhoneTaskState.Status.entries
        // Switch off or task ended (no agent display): never held.
        for (st in s) {
            assertEquals(KeepAwake.Demand.Off, KeepAwake.demand(enabled = false, displayExists = true, status = st, turnRunning = true))
            assertEquals(KeepAwake.Demand.Off, KeepAwake.demand(enabled = true, displayExists = false, status = st, turnRunning = true))
        }
        assertEquals(KeepAwake.Demand.Hold, KeepAwake.demand(true, true, PhoneTaskState.Status.Working, false))
        // Between steps while the model still thinks: keep the screen on.
        assertEquals(KeepAwake.Demand.Hold, KeepAwake.demand(true, true, PhoneTaskState.Status.Idle, true))
        assertEquals(KeepAwake.Demand.Grace, KeepAwake.demand(true, true, PhoneTaskState.Status.Idle, false))
        for (st in listOf(PhoneTaskState.Status.NeedsUser, PhoneTaskState.Status.Approval, PhoneTaskState.Status.TakenOver, PhoneTaskState.Status.WaitingUnlock))
            assertEquals(KeepAwake.Demand.Grace, KeepAwake.demand(true, true, st, true))
    }

    @Test fun waitingUnlockIsShownOnTheNotification() {
        val waiting = PhoneTaskState(status = PhoneTaskState.Status.WaitingUnlock)
        assertEquals("Hoshino 手机操作 · 等待解锁", PhoneTaskForeground.title(waiting))
        assertEquals(LockGate.WAITING_TEXT, PhoneTaskForeground.text(waiting))
        assertEquals("在后台虚拟屏进行，不占用你的主屏", PhoneTaskForeground.text(PhoneTaskState(status = PhoneTaskState.Status.Working)))
    }
}
