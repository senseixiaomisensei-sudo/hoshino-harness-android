// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * m2.10: the task gate for a locked or dark screen (Mika `TaskGate` rule 3: "屏幕熄灭或锁屏 → 等待解锁，亮屏并解锁后自动继续").
 * Before, a `phone_*` action on a locked phone failed at once with `locked`. Now an action that changes the agent display
 * waits here: the task shows 「等待解锁」 and continues by itself after the user unlocks.
 *
 * Differences from Mika, because our actions arrive as HTTP callbacks from the dsh kernel (node fetch gives up on a
 * response after 300 s):
 * - one call waits at most until [callBudgetMs] after it started, then answers [Outcome.StillWaiting]; the
 *   `android-phone` plugin calls again by itself (nothing was done yet, so the call is safe to repeat);
 * - the whole wait of one session is capped at [timeoutMs] (default 10 minutes) across those calls, then the task
 *   fails honestly with `locked` ([Outcome.TimedOut]); while the phone stays locked after that, further calls fail fast;
 * - stopping the task (capsule / notification 「停止」, or the kernel aborting the tool call) ends the wait at once.
 *
 * The no-approval mode does not bypass this: nothing ever acts on a locked phone.
 */
class LockGate(
    private val locked: StateFlow<Boolean>,
    private val now: () -> Long,
    val timeoutMs: Long = TIMEOUT_MS,
    val callBudgetMs: Long = CALL_BUDGET_MS,
) {
    sealed interface Outcome {
        /** Unlocked (at once or after [waitedMs]); go on. */
        data class Clear(val waitedMs: Long) : Outcome
        /** Still locked at the end of this call's budget; nothing was done, call again. */
        data class StillWaiting(val waitedMs: Long, val leftMs: Long) : Outcome
        /** Locked for [timeoutMs] in total: give up and say so. */
        data class TimedOut(val waitedMs: Long) : Outcome
        /** The task was stopped while waiting. */
        data class Stopped(val why: String) : Outcome
    }

    /** When each session started waiting (cleared when it sees the phone unlocked). */
    private val since = ConcurrentHashMap<String, Long>()

    /**
     * Wait until the phone is unlocked. [callStartedAt] (same clock as [now]) bounds this call to [callBudgetMs].
     * [stopped] is read whenever [wake] emits (the controller bumps it on stop). [onWaiting] is called with true right
     * before an actual wait starts and with false when it ends (unlock, timeout, stop or end of budget is reported by
     * the outcome; false is sent only on [Outcome.Clear] after waiting).
     */
    suspend fun await(
        key: String,
        callStartedAt: Long,
        stopped: () -> String?,
        wake: Flow<Any?>,
        onWaiting: (Boolean) -> Unit = {},
    ): Outcome {
        if (!locked.value) { since.remove(key); return Outcome.Clear(0) }
        stopped()?.let { return Outcome.Stopped(it) }
        val start = since.getOrPut(key) { now() }
        val t0 = now()
        val total = start + timeoutMs - t0
        if (total <= 0) return Outcome.TimedOut(t0 - start)
        val budget = minOf(total, callStartedAt + callBudgetMs - t0)
        if (budget <= 0) return Outcome.StillWaiting(t0 - start, total)
        onWaiting(true)
        val unlocked = withTimeoutOrNull(budget) {
            combine(locked, wake) { l, _ -> l }.first { !it || stopped() != null }
            true
        } ?: false
        val t1 = now()
        stopped()?.let { return Outcome.Stopped(it) }
        if (unlocked && !locked.value) {
            since.remove(key)
            onWaiting(false)
            return Outcome.Clear(t1 - t0)
        }
        val left = start + timeoutMs - t1
        return if (left <= 0) Outcome.TimedOut(t1 - start) else Outcome.StillWaiting(t1 - start, left)
    }

    /** Forget a session's wait (task released). */
    fun forget(key: String) { since.remove(key) }

    companion object {
        /** Whole wait for unlock of one session before the task fails with `locked`. */
        const val TIMEOUT_MS = 10 * 60_000L
        /** One callback's share of the wait: well under node fetch's 300 s response timeout. */
        const val CALL_BUDGET_MS = 240_000L
        /** What the user sees on the capsule / notification / in-app page while the task waits. */
        const val WAITING_TEXT = "等待解锁：解锁手机后自动继续（最多等 10 分钟）"
        const val TIMED_OUT_TEXT = "手机锁屏或熄屏超过 10 分钟没有解锁，这一步没有执行；解锁后让 Hoshino 接着做"
    }
}
