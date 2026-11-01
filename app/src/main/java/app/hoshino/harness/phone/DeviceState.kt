// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * m2.10: the one place that reads whether the main screen can be operated (screen on and not on the keyguard).
 * The task gate in [PhoneController] and the floating capsule ([PhoneCapsule]) both read [locked] from here, so
 * they can never disagree (before, the capsule tracked SCREEN_ON/OFF itself and read the keyguard once at
 * USER_PRESENT; the controller threw `locked`).
 *
 * Broadcasts (SCREEN_ON / SCREEN_OFF / USER_PRESENT) are only a signal to read again: the state itself is always
 * read from the system, never inferred from broadcast order. A screen that is off counts as locked: the system
 * drops injected input and locks shortly after.
 *
 * Process-wide singleton: the receiver stays registered for the life of the process (three cheap broadcasts).
 */
class DeviceState private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = LockState({ read() }, scope)

    /** True while the screen is off or the keyguard is showing. */
    val locked: StateFlow<Boolean> = lock.locked

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { lock.refresh() }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else appContext.registerReceiver(receiver, filter)
        lock.refresh() // whatever changed between construction and registration
    }

    /** Read the system again now (for diagnostics). */
    fun refresh(): Boolean { lock.refresh(); return locked.value }

    private fun read(): LockState.Reading = LockState.Reading(
        interactive = runCatching { power.isInteractive }.getOrDefault(true),
        keyguardLocked = runCatching { keyguard.isKeyguardLocked }.getOrDefault(false),
    )

    companion object {
        /** While the screen is on but the keyguard still reads locked, re-read this often (only while someone watches). */
        const val POLL_MS = 500L

        @Volatile private var instance: DeviceState? = null
        fun of(context: Context): DeviceState = instance ?: synchronized(this) { instance ?: DeviceState(context.applicationContext).also { instance = it } }
    }
}

/**
 * Whether the main screen is unusable (off or locked), from [read]. A broadcast calls [refresh]. On unlock the system
 * sends USER_PRESENT before the keyguard has gone away, so that read still says locked and no further broadcast comes
 * (Mika 0.1.27, 736b53d). Therefore, while the last read was "screen on but locked" and someone collects [locked], it
 * re-reads every [DeviceState.POLL_MS] until it reads unlocked or screen off. With the screen off it does not read at
 * all and waits for SCREEN_ON. Without collectors it never polls.
 */
internal class LockState(private val read: () -> Reading, scope: CoroutineScope, private val pollMs: Long = DeviceState.POLL_MS) {
    data class Reading(val interactive: Boolean, val keyguardLocked: Boolean) {
        val locked: Boolean get() = !interactive || keyguardLocked
    }

    @Volatile private var last = read()
    private val _locked = MutableStateFlow(last.locked)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun refresh() {
        val r = read()
        last = r
        _locked.value = r.locked
    }

    init {
        scope.launch {
            _locked.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { watched ->
                while (watched) {
                    delay(pollMs)
                    val l = last
                    if (l.interactive && l.keyguardLocked) refresh()
                }
            }
        }
    }
}
