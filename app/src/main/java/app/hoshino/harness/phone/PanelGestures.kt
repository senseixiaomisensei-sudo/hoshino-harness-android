package app.hoshino.harness.phone

import kotlin.math.hypot

/**
 * Single tap / double tap / drag on the floating ball, the panel's title bar and its border (pure, clock injected,
 * unit-tested; m2.9.2). Taps on the picture itself never come here: there a touch operates the agent display.
 *
 *  - Drag: once the finger moved past [touchSlop] the gesture is a drag (move the ball / panel) and never a click.
 *  - Double tap: a second press within [doubleTapTimeoutMs] of the first release, near it → [Click.Double] on its release.
 *  - Single tap: only after [doubleTapTimeoutMs] passed without a second press → [Click.Single] from [flush]
 *    (the host posts a delayed check at [pendingUntil]); so a double tap never also fires a single tap.
 *  - A press held longer than [longPressMs] without moving is neither (no accidental click while hesitating).
 */
class TapClassifier(
    private val touchSlop: Float,
    private val doubleTapTimeoutMs: Long,
    private val doubleTapSlop: Float = touchSlop * 4,
    private val longPressMs: Long = 500,
) {
    enum class Click { Single, Double }

    var dragging = false
        private set
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var pressed = false
    private var second = false
    private var firstUpAt = 0L
    private var firstX = 0f
    private var firstY = 0f
    /** Time at which a pending single tap becomes final, or null. */
    var pendingUntil: Long? = null
        private set

    fun down(now: Long, x: Float, y: Float) {
        pressed = true; dragging = false; downAt = now; downX = x; downY = y
        val p = pendingUntil
        second = p != null && now <= p && hypot(x - firstX, y - firstY) <= doubleTapSlop
        if (p != null && !second && now > p) pendingUntil = null
    }

    /** Returns true while the gesture is a drag (the caller moves the window). */
    fun move(x: Float, y: Float): Boolean {
        if (!pressed) return false
        if (!dragging && hypot(x - downX, y - downY) > touchSlop) {
            dragging = true
            pendingUntil = null; second = false
        }
        return dragging
    }

    fun up(now: Long): Click? {
        if (!pressed) return null
        pressed = false
        if (dragging) { dragging = false; return null }
        if (now - downAt > longPressMs) { pendingUntil = null; second = false; return null }
        if (second) { second = false; pendingUntil = null; return Click.Double }
        firstUpAt = now; firstX = downX; firstY = downY
        pendingUntil = now + doubleTapTimeoutMs
        return null
    }

    fun cancel() { pressed = false; dragging = false; second = false; pendingUntil = null }

    /** The pending single tap, once its double-tap window has closed and no second press is in progress. */
    fun flush(now: Long): Click? {
        val p = pendingUntil ?: return null
        if (pressed && second) return null
        if (now < p) return null
        pendingUntil = null
        return Click.Single
    }
}

/**
 * m2.9.4: by default only the 「接管」 button takes over (user request); touches on the picture are ignored until then.
 * With touchTakesOver = true (the m2.9.2 「自由操作」 behaviour): the user can touch the preview at any time. The first touch takes over automatically and pauses
 * the AI ([Action.Begin]); after the finger is lifted and nothing happened for [idleMs] the AI gets the display back
 * ([Action.End]). The 「接管」 button makes the takeover sticky (no auto hand-back) until 「交还」. Pure, clock injected.
 */
class AutoTakeoverModel(
    private val idleMs: Long = DEFAULT_IDLE_MS,
    /** m2.9.4: off by default — only the 「接管」 button takes over; a touch on the picture never does. */
    private val touchTakesOver: Boolean = false,
) {
    enum class Mode { Off, Auto, Sticky }
    enum class Action { None, Begin, End }

    var mode = Mode.Off
        private set
    private var touching = 0
    private var lastActivity = 0L

    /** A finger went down on the picture. */
    fun touchDown(now: Long): Action {
        touching++
        lastActivity = now
        return if (mode == Mode.Off && touchTakesOver) { mode = Mode.Auto; Action.Begin } else Action.None
    }

    fun touchUp(now: Long) { touching = (touching - 1).coerceAtLeast(0); lastActivity = now }

    /** Typing, a key button, a pinch: keeps an automatic takeover alive. */
    fun activity(now: Long) { if (mode != Mode.Off) lastActivity = now }

    /** 「接管」 / 「交还」 button. */
    fun button(now: Long): Action = when (mode) {
        Mode.Off -> { mode = Mode.Sticky; lastActivity = now; Action.Begin }
        Mode.Auto -> { mode = Mode.Sticky; lastActivity = now; Action.None }
        Mode.Sticky -> { mode = Mode.Off; touching = 0; Action.End }
    }

    /** The takeover could not start (no display, the agent's own hand-over pending): nothing is active. */
    fun beginFailed() { mode = Mode.Off; touching = 0 }

    /** The takeover ended elsewhere (stop, screen off, panel closed, the controller ended it). */
    fun ended() { mode = Mode.Off; touching = 0 }

    /** The takeover started elsewhere (e.g. the chat): treated as sticky. */
    fun startedElsewhere(now: Long) { if (mode == Mode.Off) { mode = Mode.Sticky; lastActivity = now } }

    fun tick(now: Long): Action {
        if (mode != Mode.Auto || touching > 0 || now - lastActivity < idleMs) return Action.None
        mode = Mode.Off
        return Action.End
    }

    /** When [tick] should run next (null = nothing to wait for). */
    fun nextCheckAt(): Long? = if (mode == Mode.Auto && touching == 0) lastActivity + idleMs else null

    companion object {
        const val DEFAULT_IDLE_MS = 4_000L
    }
}
