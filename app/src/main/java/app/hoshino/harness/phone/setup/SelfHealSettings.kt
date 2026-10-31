package app.hoshino.harness.phone.setup

import android.content.Context
import app.hoshino.harness.phone.PhoneConsent
import dev.hoshino.bridge.phone.shell.SelfHealState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's "清理后台后自动恢复" choice (WP-9 / mika-gap D-3) and the self-heal bookkeeping. Kept apart from
 * [PhoneConsent] so this package owns it; same rules: default off, nothing happens before the user ticks it, and
 * it only counts together with the consent page's "配对后自动开启无障碍" item (it re-enables that same service).
 */
class SelfHealSettings private constructor(context: Context) : SelfHealState {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("phone_self_heal", Context.MODE_PRIVATE)
    private val _wanted = MutableStateFlow(prefs.getBoolean(KEY_WANTED, false))
    /** The checkbox value as saved (the effective consent is [consent]). */
    val wanted: StateFlow<Boolean> = _wanted.asStateFlow()
    private val _paused = MutableStateFlow(prefs.getBoolean(KEY_USER_DISABLED, false))
    /** Self-heal paused because the user turned the service off. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    override fun consent(): Boolean = effective(PhoneConsent.of(app).state.value.confirmed, PhoneConsent.of(app).state.value.enableAccessibility, _wanted.value)

    override var userDisabled: Boolean
        get() = _paused.value
        set(v) { prefs.edit().putBoolean(KEY_USER_DISABLED, v).apply(); _paused.value = v }

    override var everConnected: Boolean
        get() = prefs.getBoolean(KEY_EVER_CONNECTED, false)
        set(v) { prefs.edit().putBoolean(KEY_EVER_CONNECTED, v).apply() }

    override fun audit(event: String) = PhoneConsent.of(app).audit(event)

    /** Saved from the wizard. Re-confirming clears a pause: the user just said again that they want it. */
    fun save(wanted: Boolean) {
        prefs.edit().putBoolean(KEY_WANTED, wanted).apply()
        val changed = wanted != _wanted.value
        _wanted.value = wanted
        if (wanted) userDisabled = false
        if (changed) audit("consent selfHeal=${if (wanted) 1 else 0}")
    }

    companion object {
        private const val KEY_WANTED = "wanted"
        private const val KEY_USER_DISABLED = "userDisabled"
        private const val KEY_EVER_CONNECTED = "everConnected"

        /** Pure rule (unit-tested): self-heal counts only after the consent page and with auto-accessibility ticked. */
        fun effective(confirmed: Boolean, autoAccessibility: Boolean, wanted: Boolean) = confirmed && autoAccessibility && wanted

        @Volatile private var instance: SelfHealSettings? = null
        fun of(context: Context): SelfHealSettings = instance ?: synchronized(this) { instance ?: SelfHealSettings(context).also { instance = it } }
    }
}
