package dev.hoshino.bridge.phone.shell

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-9 / D-3: self-heal decisions (opt-in, the user's own "off" wins, bounded, inside the 10 s budget). */
class SelfHealDecisionTest {
    private val me = "app.hoshino.harness/app.hoshino.harness.phone.PhoneAccessibilityService"
    private val stuck = SelfHeal.Observation(listed = true, connected = false)
    private val base = SelfHeal.Input(consent = true, userDisabled = false, everConnected = true, observation = stuck, canWrite = true, recent = emptyList(), now = 1_000_000)

    @Test fun firstRepairFitsTheTenSecondBudget() {
        assertTrue(SelfHeal.FIRST_REPAIR_BUDGET_MS <= 10_000)
        assertTrue(SelfHeal.GRACE_MS + SelfHeal.gapMs(1) + SelfHeal.COMMAND_BUDGET_MS + SelfHeal.CONNECT_TIMEOUT_MS <= 10_000)
        assertEquals(2, SelfHeal.MAX_ATTEMPTS)
    }

    @Test fun repairsAListedButUnboundService() {
        assertEquals(SelfHeal.Decision.Readd, SelfHeal.decide(base))
    }

    @Test fun neverWithoutConsent() {
        assertEquals(SelfHeal.Decision.Skip(SelfHeal.SkipReason.NoConsent), SelfHeal.decide(base.copy(consent = false)))
    }

    @Test fun healthyWhenConnectedWhateverElse() {
        assertEquals(SelfHeal.Decision.Healthy, SelfHeal.decide(base.copy(consent = false, observation = SelfHeal.Observation(listed = true, connected = true))))
    }

    @Test fun userTurnedOffWins() {
        assertEquals(SelfHeal.Decision.Skip(SelfHeal.SkipReason.UserTurnedOff), SelfHeal.decide(base.copy(userDisabled = true)))
    }

    @Test fun neverEnablesAServiceThatNeverRan() {
        assertEquals(SelfHeal.Decision.Skip(SelfHeal.SkipReason.NeverEnabled), SelfHeal.decide(base.copy(everConnected = false)))
    }

    @Test fun appNeverPutsAMissingEntryBack() {
        // That is either the user's own off (seen live) or a force-stop while we were dead (the helper's job).
        assertEquals(SelfHeal.Decision.Skip(SelfHeal.SkipReason.NotListed), SelfHeal.decide(base.copy(observation = SelfHeal.Observation(listed = false, connected = false))))
    }

    @Test fun needsAWriter() {
        assertEquals(SelfHeal.Decision.Skip(SelfHeal.SkipReason.NoWriter), SelfHeal.decide(base.copy(canWrite = false)))
    }

    @Test fun stopsFightingAfterThreeRepairsInTenMinutes() {
        val now = base.now
        val three = listOf(now - 9 * 60_000L, now - 5 * 60_000L, now - 1_000L)
        assertEquals(SelfHeal.Decision.Skip(SelfHeal.SkipReason.TooMany), SelfHeal.decide(base.copy(recent = three)))
        val old = listOf(now - 11 * 60_000L, now - 5 * 60_000L, now - 1_000L)
        assertEquals(SelfHeal.Decision.Readd, SelfHeal.decide(base.copy(recent = old)))
        assertEquals(2, SelfHeal.prune(old, now).size)
    }

    @Test fun userTurnedOffIsAnEntryVanishingAfterItWasBound() {
        assertTrue(SelfHeal.userTurnedOff(beforeListed = true, nowListed = false, boundWhileListed = true, selfWriting = false))
        // Our own remove-then-add.
        assertFalse(SelfHeal.userTurnedOff(beforeListed = true, nowListed = false, boundWhileListed = true, selfWriting = true))
        // Never bound in this process (we were restarted after a kill): not evidence of a user action.
        assertFalse(SelfHeal.userTurnedOff(beforeListed = true, nowListed = false, boundWhileListed = false, selfWriting = false))
        assertFalse(SelfHeal.userTurnedOff(beforeListed = false, nowListed = false, boundWhileListed = true, selfWriting = false))
    }

    @Test fun listEditingKeepsOtherServices() {
        val other = "com.other/.Svc"
        assertEquals(other, SelfHeal.without("$other:$me", me))
        assertEquals("", SelfHeal.without(me, me))
        assertEquals("", SelfHeal.without("null", me))
        assertTrue(SelfHeal.contains("$other:app.hoshino.harness/.phone.PhoneAccessibilityService", me))
        assertFalse(SelfHeal.contains(other, me))
    }

    @Test fun readdRemovesThenAddsWithAPause() {
        val other = "com.other/.Svc"
        val cmds = SelfHeal.commands("$other:$me", me, readd = true, gapMs = 500)
        assertEquals("settings put secure enabled_accessibility_services '$other'", cmds[0])
        assertEquals("sleep 0.5", cmds[1])
        assertEquals("settings put secure enabled_accessibility_services '$other:$me'", cmds[2])
        assertEquals("settings put secure accessibility_enabled 1", cmds[3])
        // Only Hoshino in the list: delete, then add back.
        assertEquals("settings delete secure enabled_accessibility_services", SelfHeal.commands(me, me, readd = true, gapMs = 1000)[0])
        // Missing entry (helper after a force-stop): just add.
        val add = SelfHeal.commands(other, me, readd = false, gapMs = 500)
        assertEquals(listOf("settings put secure enabled_accessibility_services '$other:$me'", "settings put secure accessibility_enabled 1"), add)
    }

    @Test fun helperRepairsOnlyWhenWantedAndTheAppStaysDead() {
        val now = 10_000_000L
        assertTrue(HelperHeal.shouldRepair(wanted = true, appBack = false, attempt = 1, recent = emptyList(), now = now))
        assertFalse(HelperHeal.shouldRepair(wanted = false, appBack = false, attempt = 1, recent = emptyList(), now = now))
        assertFalse(HelperHeal.shouldRepair(wanted = true, appBack = true, attempt = 1, recent = emptyList(), now = now))
        assertFalse(HelperHeal.shouldRepair(wanted = true, appBack = false, attempt = 3, recent = emptyList(), now = now))
        assertFalse(HelperHeal.shouldRepair(wanted = true, appBack = false, attempt = 1, recent = listOf(now - 3, now - 2, now - 1), now = now))
        // The second attempt of the same repair does not count against the window again.
        assertTrue(HelperHeal.shouldRepair(wanted = true, appBack = false, attempt = 2, recent = listOf(now - 3, now - 2, now - 1), now = now))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityHealTest {
    private class FakeState(var wanted: Boolean = true) : SelfHealState {
        val audits = mutableListOf<String>()
        override fun consent() = wanted
        override var userDisabled = false
        override var everConnected = true
        override fun audit(event: String) { audits += event }
    }

    @Test fun restoresWithinTenSecondsWhenTheSystemRebindsAfterTheWrite() = runTest {
        val state = FakeState()
        var obs = SelfHeal.Observation(listed = true, connected = false)
        var writes = 0
        val heal = AccessibilityHeal(state, { true }, { _ -> writes++; launch { kotlinx.coroutines.delay(800); obs = obs.copy(connected = true) }; true }, { testScheduler.currentTime })
        val job = launch { heal.maybeRepair { obs } }
        advanceTimeBy(SelfHeal.GRACE_MS - 1); runCurrent()
        assertEquals(0, writes) // grace first
        advanceTimeBy(10_000); runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, writes)
        assertTrue(heal.last.value!!.restored)
        assertTrue(heal.last.value!!.atMillis <= 10_000)
        assertTrue(state.audits.any { it.contains("restored") })
    }

    @Test fun systemRebindsDuringGraceSoNothingIsWritten() = runTest {
        val state = FakeState()
        var obs = SelfHeal.Observation(listed = true, connected = false)
        var writes = 0
        val heal = AccessibilityHeal(state, { true }, { _ -> writes++; true }, { testScheduler.currentTime })
        val job = launch { heal.maybeRepair { obs } }
        advanceTimeBy(500); obs = obs.copy(connected = true)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(0, writes)
        assertNull(heal.last.value)
    }

    @Test fun givesUpAfterTwoAttempts() = runTest {
        val state = FakeState()
        val obs = SelfHeal.Observation(listed = true, connected = false)
        var writes = 0
        val heal = AccessibilityHeal(state, { true }, { _ -> writes++; true }, { testScheduler.currentTime })
        launch { heal.maybeRepair { obs } }
        advanceTimeBy(SelfHeal.WORST_CASE_MS + 5_000); runCurrent()
        assertEquals(SelfHeal.MAX_ATTEMPTS, writes)
        assertFalse(heal.last.value!!.restored)
        assertTrue(state.audits.any { it.contains("gave up") })
    }

    @Test fun doesNothingWithoutConsent() = runTest {
        val heal = AccessibilityHeal(FakeState(wanted = false), { true }, { _ -> error("must not write") }, { testScheduler.currentTime })
        heal.maybeRepair { SelfHeal.Observation(listed = true, connected = false) }
        assertNull(heal.last.value)
    }

    @Test fun userSwitchingOffPausesAndSwitchingOnResumes() = runTest {
        val state = FakeState()
        val heal = AccessibilityHeal(state, { true }, { _ -> error("must not write") }, { testScheduler.currentTime })
        heal.observe(SelfHeal.Observation(listed = true, connected = true))
        // System unbinds first, then drops the entry: still the user.
        heal.observe(SelfHeal.Observation(listed = true, connected = false))
        heal.observe(SelfHeal.Observation(listed = false, connected = false))
        assertTrue(state.userDisabled)
        assertTrue(state.audits.any { it.contains("paused") })
        heal.maybeRepair { SelfHeal.Observation(listed = true, connected = false) }
        heal.observe(SelfHeal.Observation(listed = true, connected = true))
        assertFalse(state.userDisabled)
        assertTrue(state.audits.any { it.contains("resumed") })
    }

    @Test fun ownRemoveThenAddIsNotBlamedOnTheUser() = runTest {
        val state = FakeState()
        var obs = SelfHeal.Observation(listed = true, connected = true)
        lateinit var heal: AccessibilityHeal
        heal = AccessibilityHeal(state, { true }, { _ ->
            heal.observe(SelfHeal.Observation(listed = false, connected = false))
            heal.observe(SelfHeal.Observation(listed = true, connected = false))
            obs = SelfHeal.Observation(listed = true, connected = true)
            true
        }, { testScheduler.currentTime })
        heal.observe(obs)
        obs = SelfHeal.Observation(listed = true, connected = false)
        heal.observe(obs)
        heal.maybeRepair { obs }
        // Echo of our own write delivered late (settings observers are asynchronous).
        heal.observe(SelfHeal.Observation(listed = false, connected = false))
        assertFalse(state.userDisabled)
    }

    @Test fun helperRepairIsAudited() {
        val state = FakeState()
        val heal = AccessibilityHeal(state, { true }, { _ -> true }, { 42L })
        heal.helperHealed(0)
        assertNull(heal.last.value)
        heal.helperHealed(1)
        assertTrue(heal.last.value!!.byHelper)
        assertTrue(state.audits.single().contains("helper re-enabled"))
    }
}
