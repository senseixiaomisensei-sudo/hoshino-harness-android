package app.hoshino.harness.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * User-initiated takeover of the agent display from the floating panel (pure state machine, no Android types,
 * unit-tested). Unlike [PhoneTakeover] (the agent asks the user to do one step on the main screen), here the user
 * presses 「接管」 while the agent works:
 *  - while active the AI must not act: every phone action of the agent waits at [awaitAiAllowed] (see PhoneController);
 *  - only while active the user's taps / swipes / text in the preview are injected ([record] counts them);
 *  - 「交还」 ([end]) lets the waiting action through and leaves one sentence for the model ([takeNote]): how long, what
 *    the user did, and whether the app in front changed. Nothing the user typed is ever put in the sentence.
 */
class ManualTakeover(private val clock: () -> Long = System::currentTimeMillis) {
    enum class Input { Tap, LongPress, Swipe, Text, Key, Launch }

    data class Session(val sessionId: String?, val startedAt: Long, val appBefore: String?, val counts: Map<Input, Int> = emptyMap())

    /** [allowed] = the agent may act now; [waited] = it had to wait for a takeover (its refs / plan may be stale). */
    data class Wait(val allowed: Boolean, val waited: Boolean)

    private val _active = MutableStateFlow<Session?>(null)
    val active: StateFlow<Session?> = _active.asStateFlow()
    private var note: String? = null

    val aiMayAct: Boolean get() = _active.value == null

    /** Starts a takeover; false when one is already running. */
    @Synchronized
    fun begin(sessionId: String?, appBefore: String?): Boolean {
        if (_active.value != null) return false
        _active.value = Session(sessionId, clock(), appBefore)
        return true
    }

    /** Counts one user input; false (and the input must not be injected) when no takeover is active. */
    @Synchronized
    fun record(input: Input): Boolean {
        val s = _active.value ?: return false
        _active.value = s.copy(counts = s.counts + (input to (s.counts[input] ?: 0) + 1))
        return true
    }

    /** Hands back. Returns the sentence for the model (also kept for [takeNote]), or null when nothing was active. */
    @Synchronized
    fun end(appAfter: String?): String? {
        val s = _active.value ?: return null
        val text = summary(clock() - s.startedAt, s.counts, s.appBefore, appAfter)
        note = text
        _active.value = null
        return text
    }

    /** The hand-back sentence, once (the next agent action result carries it). */
    @Synchronized
    fun takeNote(): String? = note.also { note = null }

    /** Suspends the agent while a takeover is active, at most [timeoutMs]. */
    suspend fun awaitAiAllowed(timeoutMs: Long): Wait {
        if (_active.value == null) return Wait(allowed = true, waited = false)
        val ok = withTimeoutOrNull(timeoutMs) { _active.first { it == null } } != null || _active.value == null
        return Wait(allowed = ok, waited = true)
    }

    companion object {
        /** How long an agent action waits for the user to hand back before it gives up (nothing is done then). */
        const val GATE_TIMEOUT_MS = 10 * 60_000L

        fun summary(durationMs: Long, counts: Map<Input, Int>, appBefore: String?, appAfter: String?): String {
            val secs = (durationMs.coerceAtLeast(0) + 500) / 1000
            val did = listOf(Input.Tap to "点按", Input.LongPress to "长按", Input.Swipe to "滑动", Input.Text to "输入文字", Input.Key to "按键", Input.Launch to "打开 App")
                .mapNotNull { (k, label) -> counts[k]?.takeIf { it > 0 }?.let { "$label $it 次" } }
            val actions = if (did.isEmpty()) "没有在预览里操作" else did.joinToString("、")
            val app = when {
                appBefore != null && appAfter != null && appBefore != appAfter -> "，前台 App 从 $appBefore 变成了 $appAfter"
                appAfter != null -> "，前台 App 仍是 $appAfter"
                else -> ""
            }
            return "用户手动接管了后台屏约 $secs 秒（$actions）$app；画面可能已变化，先按最新画面重新判断再继续，不要沿用接管前的 ref。"
        }
    }
}
