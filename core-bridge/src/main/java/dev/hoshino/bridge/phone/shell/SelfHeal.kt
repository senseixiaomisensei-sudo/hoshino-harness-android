// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Self-heal after HyperOS "一键清理" / force-stop / the system dropping our accessibility service (mika-gap D-3,
 * WP-9). Ported from Mika's `AccessibilityRepair` + `RecoveryPolicy` ideas, with two differences:
 *
 * - **Opt-in only.** Nothing here runs unless the user ticked "清理后台后自动恢复" in the setup wizard (PL3,
 *   default off). Mika repairs whenever the service is not running.
 * - **The user's own "off" wins.** When our process is alive and sees Hoshino's entry disappear from
 *   `enabled_accessibility_services`, the user (or another app acting for them) turned it off: self-heal pauses
 *   until the service is connected again. A force-stop kills our process before the system drops the entry, so
 *   a live process never sees that path; the helper (uid 2000, survives the app) handles it instead
 *   ([HelperHeal]).
 *
 * Budget: the first repair must have the service back within 10 s of the moment it went missing
 * ([FIRST_REPAIR_BUDGET_MS] is checked by a unit test).
 */
object SelfHeal {
    /** Wait this long first: a process that just restarted is usually re-bound by the system on its own. */
    const val GRACE_MS = 1_500L
    /** Pause between removing and re-adding our entry, grows per attempt (system may coalesce two writes). */
    const val GAP_MS = 500L
    /** How long to wait for the service to connect after a write. */
    const val CONNECT_TIMEOUT_MS = 4_000L
    /** Wait between two attempts. */
    const val BACKOFF_MS = 1_500L
    const val MAX_ATTEMPTS = 2
    /** Shell `settings put` spawns app_process: budget ~0.6 s per write sequence. */
    const val COMMAND_BUDGET_MS = 1_000L
    /** Stop fighting: at most this many repairs per [WINDOW_MS] (a user who keeps force-stopping us means it). */
    const val MAX_REPAIRS_PER_WINDOW = 3
    const val WINDOW_MS = 10 * 60_000L

    const val FIRST_REPAIR_BUDGET_MS = GRACE_MS + GAP_MS + COMMAND_BUDGET_MS + CONNECT_TIMEOUT_MS
    const val WORST_CASE_MS = GRACE_MS + MAX_ATTEMPTS * (COMMAND_BUDGET_MS + CONNECT_TIMEOUT_MS) + GAP_MS * 3 + BACKOFF_MS

    fun gapMs(attempt: Int): Long = GAP_MS * attempt.coerceAtLeast(1)

    /** What the app knows about Hoshino's accessibility service right now. */
    data class Observation(
        /** Our component is in `enabled_accessibility_services`. */
        val listed: Boolean,
        /** The service instance is connected in this process. */
        val connected: Boolean,
    )

    data class Input(
        /** User ticked "清理后台后自动恢复". */
        val consent: Boolean,
        /** User turned the service off while we were alive ([userTurnedOff]); cleared when it connects again. */
        val userDisabled: Boolean,
        /** The service was connected at least once since the consent (we never enable a service that never ran). */
        val everConnected: Boolean,
        val observation: Observation,
        /** Helper running, or the app itself holds WRITE_SECURE_SETTINGS. */
        val canWrite: Boolean,
        /** Repairs started in the last [WINDOW_MS], newest last (elapsed ms). */
        val recent: List<Long>,
        val now: Long,
    )

    sealed interface Decision {
        data object Healthy : Decision
        data class Skip(val reason: SkipReason) : Decision
        /** Entry is present but the service is not bound: remove it, then add it back (forces a re-bind). */
        data object Readd : Decision
    }

    /**
     * [NotListed]: the app never puts a missing entry back by itself. Our process sees the entry vanish only when
     * the user turns the service off, or (after a force-stop) when it was dead; the second case is the helper's job
     * ([HelperHeal]), and doing it from the app on the next start could undo an "off" the user chose while we were dead.
     */
    enum class SkipReason { NoConsent, UserTurnedOff, NeverEnabled, NotListed, NoWriter, TooMany }

    fun decide(i: Input): Decision = when {
        i.observation.connected -> Decision.Healthy
        !i.consent -> Decision.Skip(SkipReason.NoConsent)
        i.userDisabled -> Decision.Skip(SkipReason.UserTurnedOff)
        !i.everConnected -> Decision.Skip(SkipReason.NeverEnabled)
        !i.observation.listed -> Decision.Skip(SkipReason.NotListed)
        !i.canWrite -> Decision.Skip(SkipReason.NoWriter)
        !withinBudget(i.recent, i.now) -> Decision.Skip(SkipReason.TooMany)
        else -> Decision.Readd
    }

    fun withinBudget(recent: List<Long>, now: Long): Boolean = recent.count { now - it in 0 until WINDOW_MS } < MAX_REPAIRS_PER_WINDOW

    fun prune(recent: List<Long>, now: Long): List<Long> = recent.filter { now - it in 0 until WINDOW_MS }.takeLast(MAX_REPAIRS_PER_WINDOW * 2)

    /**
     * Seen live by our own process: the entry went away after the service had been bound while listed
     * ([boundWhileListed], sticky: the system may unbind first and drop the entry a moment later), and we were not
     * the ones writing ([selfWriting] covers our own remove-then-add). That is the user switching it off.
     */
    fun userTurnedOff(beforeListed: Boolean, nowListed: Boolean, boundWhileListed: Boolean, selfWriting: Boolean): Boolean =
        !selfWriting && beforeListed && !nowListed && boundWhileListed

    /** `enabled_accessibility_services` without [service]; empty string when nothing is left. */
    fun without(current: String?, service: String): String =
        PermissionCommands.parse(current).filterNot { PermissionCommands.same(it, service) }.joinToString(":")

    fun contains(current: String?, service: String): Boolean = PermissionCommands.parse(current).any { PermissionCommands.same(it, service) }

    /** Shell commands that put [service] back. [readd]: remove first, sleep [gapMs], then add (forces a re-bind). */
    fun commands(current: String?, service: String, readd: Boolean, gapMs: Long): List<String> {
        val base = if (readd) without(current, service) else PermissionCommands.parse(current).joinToString(":")
        val added = PermissionCommands.withService(base, service) ?: base
        val out = mutableListOf<String>()
        if (readd && contains(current, service)) {
            out += if (base.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services ${HelperHandoff.quote(base)}"
            out += "sleep ${"%.1f".format(java.util.Locale.ROOT, gapMs / 1000.0)}"
        }
        out += PermissionCommands.writeAccessibility(added)
        return out
    }
}

/** Persistent self-heal state; the app keeps it in its own preferences. */
interface SelfHealState {
    fun consent(): Boolean
    var userDisabled: Boolean
    var everConnected: Boolean
    fun audit(event: String) {}

    companion object {
        val NONE = object : SelfHealState {
            override fun consent() = false
            override var userDisabled = false
            override var everConnected = false
        }
    }
}

/** Last repair, for the setup page ("上次自动恢复：…"). In memory only. */
data class HealOutcome(val restored: Boolean, val attempts: Int, val atMillis: Long, val byHelper: Boolean)

/**
 * App-side watcher: our process is alive, our entry is listed, but the service is not bound (process was killed
 * and HyperOS did not let the system re-bind it). [observe] is called for every change (cheap, never suspends) and
 * keeps the sticky flags; [maybeRepair] is called from a conflated collector and does the slow part.
 * [restart] performs one remove-then-add sequence and returns true when it ran.
 */
class AccessibilityHeal(
    private val state: SelfHealState,
    private val canWrite: () -> Boolean,
    private val restart: suspend (gapMs: Long) -> Boolean,
    private val clock: () -> Long,
) {
    private val _last = MutableStateFlow<HealOutcome?>(null)
    val last: StateFlow<HealOutcome?> = _last.asStateFlow()
    private val recent = ArrayList<Long>()
    private var previous: SelfHeal.Observation? = null
    private var boundWhileListed = false
    /**
     * True while we remove and re-add our own entry, and for [SELF_WRITE_ECHO_MS] after (settings observers are
     * delivered asynchronously), so [SelfHeal.userTurnedOff] does not blame the user for our own write.
     */
    @Volatile private var writing = false
    @Volatile private var writeEchoUntil = Long.MIN_VALUE
    val selfWriting: Boolean get() = writing || clock() < writeEchoUntil

    fun input(o: SelfHeal.Observation) = SelfHeal.Input(state.consent(), state.userDisabled, state.everConnected, o, canWrite(), synchronized(recent) { recent.toList() }, clock())

    /** Every observation change, in order. */
    @Synchronized
    fun observe(o: SelfHeal.Observation) {
        val before = previous
        previous = o
        if (o.connected && o.listed) boundWhileListed = true
        if (o.connected) {
            if (!state.everConnected) state.everConnected = true
            if (state.userDisabled) { state.userDisabled = false; state.audit("self-heal resumed: accessibility on again") }
            return
        }
        if (before != null && SelfHeal.userTurnedOff(before.listed, o.listed, boundWhileListed, selfWriting)) {
            boundWhileListed = false
            if (!state.userDisabled) { state.userDisabled = true; state.audit("self-heal paused: accessibility turned off by the user") }
        }
    }

    /** Repairs when [current] needs it; returns when the service is back, the repair gave up, or nothing was needed. */
    suspend fun maybeRepair(current: () -> SelfHeal.Observation) {
        if (SelfHeal.decide(input(current())) != SelfHeal.Decision.Readd) return
        delay(SelfHeal.GRACE_MS)
        for (attempt in 1..SelfHeal.MAX_ATTEMPTS) {
            val d = SelfHeal.decide(input(current()))
            if (d != SelfHeal.Decision.Readd) {
                if (d == SelfHeal.Decision.Healthy && attempt > 1) record(true, attempt - 1)
                return
            }
            if (attempt == 1) synchronized(recent) { recent += clock(); val keep = SelfHeal.prune(recent, clock()); recent.clear(); recent += keep }
            writing = true
            val ran = try { restart(SelfHeal.gapMs(attempt)) } finally { writeEchoUntil = clock() + SELF_WRITE_ECHO_MS; writing = false }
            if (ran && waitConnected(current)) return record(true, attempt)
            if (attempt < SelfHeal.MAX_ATTEMPTS) delay(SelfHeal.BACKOFF_MS)
        }
        record(false, SelfHeal.MAX_ATTEMPTS)
    }

    private suspend fun waitConnected(current: () -> SelfHeal.Observation): Boolean =
        withTimeoutOrNull(SelfHeal.CONNECT_TIMEOUT_MS) { while (!current().connected) delay(100); true } ?: false

    private fun record(restored: Boolean, attempts: Int) {
        _last.value = HealOutcome(restored, attempts, clock(), byHelper = false)
        state.audit(if (restored) "self-heal accessibility restored (attempts=$attempts)" else "self-heal accessibility gave up (attempts=$attempts)")
    }

    companion object {
        const val SELF_WRITE_ECHO_MS = 2_000L
    }

    /** Helper reported repairs it did while we were dead (see [HelperHeal.KEY_HEALED]). */
    fun helperHealed(count: Int) {
        if (count <= 0) return
        _last.value = HealOutcome(true, count, clock(), byHelper = true)
        state.audit("self-heal helper re-enabled accessibility after the app was killed (x$count)")
    }
}

/**
 * Helper-side part (runs in the helper, uid 2000). After a force-stop our process is dead and the system has
 * removed our accessibility entry, so only the helper can bring it back: it asks the app's [HealToken] whether
 * the user wants self-heal while the app is alive, and when the app dies it waits [SelfHeal.GRACE_MS], then
 * re-adds the entry. Writing the entry makes the system bind the service, which starts our process again; the
 * helper hands its binder back as usual (uid importance listener) and reports the repair in the next handoff
 * ([KEY_HEALED]) so the app can write the audit line.
 */
object HelperHeal {
    /** Transaction code on the app's handoff token: reply int 1 = repair wanted, 0 = not. */
    const val QUERY = IBinder.FIRST_CALL_TRANSACTION + 41
    /** Handoff extra: repairs the helper performed since the last accepted handoff. */
    const val KEY_HEALED = "healed"
    /** Re-ask the app this often while it is alive (consent can change in the wizard). */
    const val POLL_MS = 10_000L

    /** Asks the app's token; false on any error (never repair on doubt). */
    fun wanted(token: IBinder?): Boolean {
        if (token == null) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            token.transact(QUERY, data, reply, 0) && reply.readInt() == 1
        } catch (t: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Pure decision for the helper after the app died. */
    fun shouldRepair(wanted: Boolean, appBack: Boolean, attempt: Int, recent: List<Long>, now: Long): Boolean =
        wanted && !appBack && attempt in 1..SelfHeal.MAX_ATTEMPTS && (attempt > 1 || SelfHeal.withinBudget(recent, now))
}

/**
 * The binder the app gives the helper at handoff (the helper links to its death to see the app die). It also
 * answers [HelperHeal.QUERY]: only the shell uid may ask, and the answer is a single boolean.
 */
class HealToken(private val wanted: () -> Boolean) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != HelperHeal.QUERY) return super.onTransact(code, data, reply, flags)
        if (Binder.getCallingUid() != HelperHandoff.SHELL_UID) return false
        reply?.writeInt(if (runCatching { wanted() }.getOrDefault(false)) 1 else 0)
        return true
    }
}
