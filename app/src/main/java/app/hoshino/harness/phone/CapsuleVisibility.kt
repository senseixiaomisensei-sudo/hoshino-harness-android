package app.hoshino.harness.phone

/**
 * Debounced visibility of the floating capsule (pure, no Android types, clock injected so it is unit-tested).
 *
 * The raw "wanted" signal flickers: the controller drops to Idle after every step and back to Working on the next
 * tool call, window-state events from our own overlay / the IME / SystemUI briefly look like "Hoshino in front",
 * and Xiaomi's keyguard flag can blink. Adding and removing the overlay window on every such edge made the dot
 * flash and vanish on tap. Rules:
 *  - want = true shows at once and cancels any pending hide;
 *  - want = false hides only after it stayed false for [hideDelayMs] AND the dot has been up for [minShowMs];
 *  - [update] with `immediate = true` (screen off, locked) hides at once;
 *  - repeated identical inputs are no-ops (callers get [Decision.changed] = false and must not touch the window).
 */
class CapsuleVisibility(
    private val hideDelayMs: Long = HIDE_DELAY_MS,
    private val minShowMs: Long = MIN_SHOW_MS,
) {
    data class Decision(val shown: Boolean, val changed: Boolean, val recheckAt: Long?)

    var shown = false
        private set
    private var shownAt = 0L
    private var falseSince: Long? = null

    fun update(want: Boolean, now: Long, immediate: Boolean = false): Decision {
        if (want) {
            falseSince = null
            if (shown) return Decision(true, false, null)
            shown = true; shownAt = now
            return Decision(true, true, null)
        }
        if (!shown) { falseSince = null; return Decision(false, false, null) }
        if (immediate) { shown = false; falseSince = null; return Decision(false, true, null) }
        val since = falseSince ?: now.also { falseSince = it }
        val hideAt = maxOf(since + hideDelayMs, shownAt + minShowMs)
        if (now >= hideAt) { shown = false; falseSince = null; return Decision(false, true, null) }
        return Decision(true, false, hideAt)
    }

    fun reset() { shown = false; falseSince = null; shownAt = 0L }

    companion object {
        const val HIDE_DELAY_MS = 800L
        const val MIN_SHOW_MS = 1_500L

        /**
         * Whether a TYPE_WINDOW_STATE_CHANGED counts as "the user switched app". Only real application windows do
         * ([windowIsApplication] = null when the window could not be resolved); our own accessibility overlay, the
         * keyboard and SystemUI panels do not, otherwise opening the card reads as "Hoshino is in front" and hides it.
         */
        fun countsAsForeground(pkg: String?, windowIsApplication: Boolean?): Boolean =
            !pkg.isNullOrBlank() && pkg != "com.android.systemui" && windowIsApplication != false
    }
}
