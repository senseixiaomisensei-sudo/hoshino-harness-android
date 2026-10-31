package app.hoshino.harness.phone

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * What the user explicitly allowed in the "设置手机操作" wizard (mika-gap §2 PL3). Mika silently enables its
 * accessibility service, grants itself WRITE_SECURE_SETTINGS, exempts itself from battery optimisation and
 * turns wireless debugging back on by itself on every Wi-Fi. Hoshino does none of these until the user ticked
 * the item on the consent page; every item starts unticked, and "自动打开无线调试" also needs
 * WRITE_SECURE_SETTINGS. Nothing is ever changed before [confirmed] is true.
 */
data class PhoneConsentState(
    /** The user went through the consent page at least once (items below are meaningful only then). */
    val confirmed: Boolean = false,
    /** Helper writes enabled_accessibility_services to add Hoshino's service (skips the restricted-settings dance). */
    val enableAccessibility: Boolean = false,
    /** pm grant WRITE_SECURE_SETTINGS (only needed for [autoWirelessDebugging]). */
    val writeSecureSettings: Boolean = false,
    /** cmd deviceidle whitelist +pkg: long tasks are not cut by Doze. */
    val batteryExemption: Boolean = false,
    /** Turn wireless debugging back on (once per Wi-Fi, stops if the system refuses). Default off. */
    val autoWirelessDebugging: Boolean = false,
    /** Mute apps while they run on the agent display (PLAY_AUDIO app-op, restored afterwards). */
    val muteAgentApps: Boolean = false,
    /** AP7: let the agent operate Hoshino itself. Off: every phone action on Hoshino is refused. */
    val allowSelf: Boolean = false,
    /** Show the capsule even when no task runs (double-tap sends the current screen: CU5). Not a system change. */
    val capsuleAlways: Boolean = false,
)

class PhoneConsent private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("phone_consent", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<PhoneConsentState> = _state.asStateFlow()

    val muteAgentApps get() = _state.value.confirmed && _state.value.muteAgentApps
    val allowSelf get() = _state.value.allowSelf
    fun allowsAccessibility() = _state.value.confirmed && _state.value.enableAccessibility
    fun allowsWriteSecureSettings() = _state.value.confirmed && _state.value.writeSecureSettings
    fun allowsBatteryExemption() = _state.value.confirmed && _state.value.batteryExemption
    fun allowsAutoWirelessDebugging() = _state.value.confirmed && _state.value.writeSecureSettings && _state.value.autoWirelessDebugging

    private fun load() = PhoneConsentState(
        confirmed = prefs.getBoolean("confirmed", false),
        enableAccessibility = prefs.getBoolean("enableAccessibility", false),
        writeSecureSettings = prefs.getBoolean("writeSecureSettings", false),
        batteryExemption = prefs.getBoolean("batteryExemption", false),
        autoWirelessDebugging = prefs.getBoolean("autoWirelessDebugging", false),
        muteAgentApps = prefs.getBoolean("muteAgentApps", false),
        allowSelf = prefs.getBoolean("allowSelf", false),
        capsuleAlways = prefs.getBoolean("capsuleAlways", false),
    )

    @Synchronized
    fun save(s: PhoneConsentState) {
        val v = if (!s.writeSecureSettings) s.copy(autoWirelessDebugging = false) else s
        prefs.edit().putBoolean("confirmed", v.confirmed).putBoolean("enableAccessibility", v.enableAccessibility)
            .putBoolean("writeSecureSettings", v.writeSecureSettings).putBoolean("batteryExemption", v.batteryExemption)
            .putBoolean("autoWirelessDebugging", v.autoWirelessDebugging).putBoolean("muteAgentApps", v.muteAgentApps)
            .putBoolean("allowSelf", v.allowSelf).putBoolean("capsuleAlways", v.capsuleAlways).commit()
        _state.value = v
        audit("consent " + listOf("a11y" to v.enableAccessibility, "wss" to v.writeSecureSettings, "battery" to v.batteryExemption, "autoWifiDebug" to v.autoWirelessDebugging, "mute" to v.muteAgentApps, "self" to v.allowSelf).joinToString(",") { "${it.first}=${if (it.second) 1 else 0}" })
    }

    /** Local, append-only audit of automatic system changes (no content, no network). Capped at 64 KB. */
    fun audit(event: String) {
        runCatching {
            val f = File(context.filesDir, "phone-audit.log")
            if (f.length() > 64 * 1024) f.writeText(f.readLines().takeLast(200).joinToString("\n") + "\n")
            f.appendText("${System.currentTimeMillis()} ${event.replace('\n', ' ').take(200)}\n")
        }
    }

    fun auditLines(max: Int = 50): List<String> = runCatching { File(context.filesDir, "phone-audit.log").readLines().takeLast(max) }.getOrDefault(emptyList())

    companion object {
        @Volatile private var instance: PhoneConsent? = null
        fun of(context: Context): PhoneConsent = instance ?: synchronized(this) { instance ?: PhoneConsent(context.applicationContext).also { instance = it } }
    }
}
