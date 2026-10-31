package app.hoshino.harness.phone

import android.content.Context
import dev.hoshino.bridge.phone.shell.PhoneServices
import dev.hoshino.bridge.phone.shell.SetupConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Process start for phone control (called from HoshinoApplication): give the helper the user's consent
 * choices, start the helper watchdog (it only reconnects a helper the user paired; it never pairs or turns
 * settings on by itself), restore system state left over from a crash (RT6), and re-apply consented setup
 * when the user changes a choice in the wizard.
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
        if (!services.supported()) return
        services.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // RT6: apps muted by a task that crashed get their sound back as soon as a helper is available.
        scope.launch { services.shell.filterNotNull().collect { s -> PhoneLedger.restoreAll(app, s); PhoneLedger.recoverClipboard(app, s) } }
        scope.launch { consent.state.drop(1).collect { services.applyConsent() } }
    }
}
