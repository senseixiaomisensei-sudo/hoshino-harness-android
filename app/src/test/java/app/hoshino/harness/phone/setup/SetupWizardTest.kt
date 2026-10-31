package app.hoshino.harness.phone.setup

import app.hoshino.harness.phone.PhoneConsentState
import app.hoshino.harness.phone.setup.PhoneSetupFlow.Primary
import app.hoshino.harness.phone.setup.PhoneSetupFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-9 / D-1: the first-run wizard as a state machine (consent first, one main action per state, ≤ 3 trips out). */
class SetupWizardTest {
    private val fresh = PhoneSetupFlow.Input(
        sdk = 35, consentConfirmed = false, shizukuReady = false, developerOptions = false, wirelessDebugging = false,
        paired = false, helperRunning = false, accessibility = false, ocrReady = false,
    )
    private val flow = PhoneSetupFlow

    @Test fun everyConsentItemStartsUnticked() {
        val d = PhoneConsentState()
        assertFalse(d.confirmed)
        assertFalse(d.enableAccessibility)
        assertFalse(d.writeSecureSettings)
        assertFalse(d.batteryExemption)
        assertFalse(d.autoWirelessDebugging)
        assertFalse(d.muteAgentApps)
        assertFalse(d.allowSelf)
        assertFalse(d.capsuleAlways)
    }

    @Test fun selfHealNeedsConsentPageAndAutoAccessibility() {
        assertFalse(SelfHealSettings.effective(confirmed = false, autoAccessibility = true, wanted = true))
        assertFalse(SelfHealSettings.effective(confirmed = true, autoAccessibility = false, wanted = true))
        assertFalse(SelfHealSettings.effective(confirmed = true, autoAccessibility = true, wanted = false))
        assertTrue(SelfHealSettings.effective(confirmed = true, autoAccessibility = true, wanted = true))
    }

    @Test fun firstScreenIsConsentAndNothingElseIsOffered() {
        assertEquals(Step.Consent, flow.current(fresh))
        assertEquals(Primary.Confirm, flow.primary(fresh))
        // Even a ready helper does not skip the consent page.
        assertEquals(Primary.Confirm, flow.primary(fresh.copy(helperRunning = true, paired = true)))
    }

    @Test fun confirmContinuesStraightToTheNextSystemPage() {
        assertEquals(Primary.OpenDeveloperOptions, flow.afterConsent(fresh.copy(consentConfirmed = true)))
        assertEquals(Primary.StartPairing, flow.afterConsent(fresh.copy(consentConfirmed = true, developerOptions = true)))
        assertEquals(Primary.StartPairing, flow.afterConsent(fresh.copy(consentConfirmed = true, developerOptions = null)))
        assertEquals(Primary.StartPairing, flow.afterConsent(fresh.copy(consentConfirmed = true, userSaysDeveloperOn = true)))
        assertEquals(Primary.Unsupported, flow.afterConsent(fresh.copy(sdk = 32, consentConfirmed = true)))
    }

    @Test fun wirelessDebuggingAndPairingAreOneVisit() {
        val devOn = fresh.copy(consentConfirmed = true, developerOptions = true, wirelessDebugging = false)
        assertEquals(Step.Developer, flow.current(devOn))
        assertEquals(Primary.StartPairing, flow.primary(devOn))
        val wifiOn = devOn.copy(wirelessDebugging = true)
        assertEquals(Step.Pairing, flow.current(wifiOn))
        assertEquals(Primary.StartPairing, flow.primary(wifiOn))
    }

    @Test fun afterPairingTheHelperTurnsAccessibilityOnOnlyWithConsent() {
        val paired = fresh.copy(consentConfirmed = true, developerOptions = true, wirelessDebugging = true, paired = true, helperRunning = true)
        // Not ticked: the user is sent to the system page.
        assertEquals(Primary.OpenAccessibility, flow.primary(paired))
        assertFalse(flow.autoPending(paired))
        // Ticked: wait for the helper, no trip to settings.
        val auto = paired.copy(autoAccessibility = true, helperUpWithoutA11yMs = 1_000)
        assertTrue(flow.autoPending(auto))
        assertEquals(Primary.WaitAutoAccessibility, flow.primary(auto))
        // Ticked but the consent page was never confirmed: never auto.
        assertFalse(flow.autoPending(auto.copy(consentConfirmed = false)))
        // Helper did not manage it in time: offer the manual path.
        assertEquals(Primary.OpenAccessibility, flow.primary(auto.copy(helperUpWithoutA11yMs = PhoneSetupFlow.AUTO_A11Y_WAIT_MS)))
        // Done.
        assertEquals(Primary.Done, flow.primary(auto.copy(accessibility = true)))
        assertEquals(Step.Done, flow.current(auto.copy(accessibility = true)))
    }

    @Test fun newUserLeavesHoshinoAtMostThreeTimes() {
        // Developer options off, nothing ticked: dev options + wireless debugging/pairing + accessibility.
        assertEquals(3, flow.remainingTrips(fresh))
        // Auto accessibility ticked: one trip fewer.
        assertEquals(2, flow.remainingTrips(fresh.copy(consentConfirmed = true, autoAccessibility = true)))
        // Developer options already on and auto accessibility: a single trip (pair).
        assertEquals(1, flow.remainingTrips(fresh.copy(consentConfirmed = true, autoAccessibility = true, developerOptions = true)))
        assertEquals(0, flow.remainingTrips(fresh.copy(consentConfirmed = true, helperRunning = true, accessibility = true)))
    }

    @Test fun walkThroughFromScratchToDone() {
        var i = fresh
        val seen = mutableListOf(flow.primary(i))
        i = i.copy(consentConfirmed = true, autoAccessibility = true); seen += flow.primary(i)
        i = i.copy(developerOptions = true); seen += flow.primary(i)
        i = i.copy(wirelessDebugging = true, paired = true, helperRunning = true, helperUpWithoutA11yMs = 0); seen += flow.primary(i)
        i = i.copy(accessibility = true); seen += flow.primary(i)
        assertEquals(listOf(Primary.Confirm, Primary.OpenDeveloperOptions, Primary.StartPairing, Primary.WaitAutoAccessibility, Primary.Done), seen)
    }

    @Test fun keepAliveOnlyWhileATaskRunsAndTheServiceIsDown() {
        assertTrue(PhoneKeepAlive.needsStart(taskBusy = true, serviceRunning = false))
        assertFalse(PhoneKeepAlive.needsStart(taskBusy = true, serviceRunning = true))
        assertFalse(PhoneKeepAlive.needsStart(taskBusy = false, serviceRunning = false))
    }
}
