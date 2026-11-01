// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface WakeLockPort {
    fun acquire()
    fun release()
}

/**
 * m2.10 「任务期间保持亮屏并调暗」: while a phone task works the screen must not time out (a dark screen drops injected
 * input and the keyguard follows, which pauses the task at [LockGate]). Mika keeps the screen on with a bright lock
 * at the user's brightness; we hold a *dim* screen lock instead: the screen stays on but the system may dim it, which
 * saves battery during long tasks. No system setting and no brightness value is changed; the power key still turns the
 * screen off (no ACQUIRE_CAUSES_WAKEUP), and then the gate waits for unlock.
 *
 * [Demand.Hold]: acquire. [Demand.Grace]: the task waits for the user (approval, needs-you, takeover, waiting for unlock)
 * or the model is between steps without a running turn: keep it [GRACE_MS] more, then release. [Demand.Off]: the task
 * ended (agent display released) or the switch is off: release at once. After [close] nothing re-acquires.
 */
class KeepAwake(private val lock: WakeLockPort, private val scope: CoroutineScope, private val graceMs: Long = GRACE_MS) {
    enum class Demand { Hold, Grace, Off }

    private var held = false
    private var pendingRelease: Job? = null
    private var closed = false

    val isHeld: Boolean @Synchronized get() = held

    @Synchronized
    fun on(demand: Demand) {
        if (closed) return
        when (demand) {
            Demand.Hold -> {
                pendingRelease?.cancel(); pendingRelease = null
                if (!held) { lock.acquire(); held = true }
            }
            Demand.Off -> release()
            Demand.Grace -> if (held && pendingRelease == null) {
                pendingRelease = scope.launch {
                    delay(graceMs)
                    synchronized(this@KeepAwake) { pendingRelease = null; release() }
                }
            }
        }
    }

    @Synchronized
    fun release() {
        pendingRelease?.cancel(); pendingRelease = null
        if (held) { lock.release(); held = false }
    }

    @Synchronized
    fun close() { closed = true; release() }

    companion object {
        const val GRACE_MS = 30_000L

        /**
         * What the task needs right now. [displayExists]: the phone task is on (the agent display lives exactly as long
         * as the task, `phone_finish` / idle release removes it). [turnRunning]: the session's model turn is still going
         * (the controller reads Idle between steps while the model thinks).
         */
        fun demand(enabled: Boolean, displayExists: Boolean, status: PhoneTaskState.Status, turnRunning: Boolean): Demand = when {
            !enabled || !displayExists -> Demand.Off
            status == PhoneTaskState.Status.Working -> Demand.Hold
            status == PhoneTaskState.Status.Idle && turnRunning -> Demand.Hold
            else -> Demand.Grace
        }
    }
}

/**
 * SCREEN_DIM_WAKE_LOCK is deprecated in favour of a window's FLAG_KEEP_SCREEN_ON, but the task runs in the background
 * without a window of ours; a foreground service ([PhoneTaskService] runs while the agent display exists) may still
 * hold it. Capped at [MAX_HOLD_MS] in case a path forgets to release; the lock also dies with the process.
 */
class ScreenDimWakeLock(context: Context) : WakeLockPort {
    @Suppress("DEPRECATION")
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "hoshino:phone-awake")
        .apply { setReferenceCounted(false) }

    override fun acquire() = lock.acquire(MAX_HOLD_MS)
    override fun release() { if (lock.isHeld) lock.release() }

    private companion object { const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L }
}

/** The persisted switch (SharedPreferences `phone_display`, key `keepAwakeDim`, default on like Mika). Not a system change. */
class PhoneKeepAwakeSetting private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("phone_display", Context.MODE_PRIVATE)
    private val _on = MutableStateFlow(prefs.getBoolean(KEY, true))
    val on: StateFlow<Boolean> = _on.asStateFlow()

    fun set(value: Boolean) {
        prefs.edit().putBoolean(KEY, value).apply()
        _on.value = value
    }

    companion object {
        private const val KEY = "keepAwakeDim"
        @Volatile private var instance: PhoneKeepAwakeSetting? = null
        fun of(context: Context): PhoneKeepAwakeSetting = instance ?: synchronized(this) { instance ?: PhoneKeepAwakeSetting(context.applicationContext).also { instance = it } }
    }
}
