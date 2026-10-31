package app.hoshino.harness.phone

/**
 * What the floating ball shows (pure, clock injected, unit-tested). The window add/remove debounce stays in
 * [CapsuleVisibility]; this model only decides between nothing, the small ball and the expanded panel, and whether
 * the live preview should be rendered (only while the panel is open: collapsing stops rendering to save power).
 *
 *  - Hidden: the debounced visibility says no (or the screen went off). An open panel is closed with it, unless a
 *    manual takeover is running (then the panel is kept, see [visibility]).
 *  - Ball ↔ Panel: tap the ball to expand, 「收起」 / tap the ball again to collapse.
 *  - While the user has taken over, the panel cannot be collapsed (the preview is the only way to operate); hand back
 *    first. Starting a takeover always opens the panel.
 *  - The task clock starts when the task becomes active and is shown as elapsed time; it resets when it goes idle.
 */
class BallPanelModel {
    enum class Mode { Hidden, Ball, Panel }

    var mode = Mode.Hidden
        private set
    var takeover = false
        private set
    private var taskStartedAt: Long? = null

    val rendering: Boolean get() = mode == Mode.Panel

    fun visibility(shown: Boolean): Mode {
        mode = when {
            shown && mode == Mode.Hidden -> Mode.Ball
            shown -> mode
            takeover -> mode.takeIf { it == Mode.Panel } ?: Mode.Hidden
            else -> Mode.Hidden
        }
        return mode
    }

    /** Screen off / keyguard: everything goes, a takeover included (the caller hands back first). */
    fun screenOff(): Mode { mode = Mode.Hidden; return mode }

    fun tapBall(): Mode {
        mode = when (mode) {
            Mode.Ball -> Mode.Panel
            Mode.Panel -> if (takeover) Mode.Panel else Mode.Ball
            Mode.Hidden -> Mode.Hidden
        }
        return mode
    }

    /** Returns false when the panel stays open (takeover running). */
    fun collapse(): Boolean {
        if (mode != Mode.Panel) return true
        if (takeover) return false
        mode = Mode.Ball
        return true
    }

    fun takeoverChanged(on: Boolean): Mode {
        takeover = on
        if (on && mode != Mode.Hidden) mode = Mode.Panel
        return mode
    }

    fun taskActivity(active: Boolean, now: Long) {
        taskStartedAt = if (active) taskStartedAt ?: now else null
    }

    fun elapsedMs(now: Long): Long? = taskStartedAt?.let { (now - it).coerceAtLeast(0) }

    companion object {
        fun formatElapsed(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
