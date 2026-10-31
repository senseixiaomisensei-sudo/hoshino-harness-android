package app.hoshino.harness.phone

import android.content.Context
import app.hoshino.harness.phone.setup.PhoneKeepAlive
import app.hoshino.harness.phone.setup.SelfHealSettings
import dev.hoshino.bridge.phone.shell.PhoneServices
import dev.hoshino.bridge.phone.shell.SetupConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Process start for phone control (called from HoshinoApplication): give the helper the user's consent
 * choices, start the helper watchdog (it only reconnects a helper the user paired; it never pairs or turns
 * settings on by itself), restore system state left over from a crash (RT6), and re-apply consented setup
 * when the user changes a choice in the wizard.
 * WP-9: start self-heal (only acts after the user ticked "清理后台后自动恢复") and keep the foreground service up
 * while a phone task runs.
 */
object PhoneBoot {
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val consent = PhoneConsent.of(app)
        val services = PhoneServices.of(app)
        services.consent = object : SetupConsent {
            override fun accessibility() = consent.allowsAccessibility()
            override fun writeSecureSettings() = consent.allowsWriteSecureSettings()
            override fun batteryExemption() = consent.allowsBatteryExemption()
            override fun autoWirelessDebugging() = consent.allowsAutoWirelessDebugging()
            override fun audit(event: String) = consent.audit(event)
            override fun wirelessDebuggingTurnedOn() = PhoneNotifier.wirelessDebuggingTurnedOn(app)
        }
        PhoneLedger.recover(app)
        if (!services.supported()) return
        services.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // RT6: apps muted by a task that crashed get their sound back as soon as a helper is available.
        scope.launch { services.shell.filterNotNull().collect { s -> PhoneLedger.restoreAll(app, s) } }
        scope.launch { consent.state.drop(1).collect { services.applyConsent() } }
        // D-3 self-heal: the helper repairs after a force-stop, this process repairs a listed-but-unbound service.
        val connected = PhoneAccessibilityService.instance.map { it != null }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, PhoneAccessibilityService.instance.value != null)
        services.startSelfHeal(connected, SelfHealSettings.of(app))
        // Foreground service while a phone task is busy (no-op when KernelService already runs, the normal case).
        scope.launch {
            PhoneController.of(app).state.map { it.status != PhoneTaskState.Status.Idle }.distinctUntilChanged()
                .collect { busy -> PhoneKeepAlive.ensure(app, busy) }
        }
    }
}
