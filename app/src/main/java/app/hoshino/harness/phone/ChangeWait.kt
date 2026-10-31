// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

/**
 * After an action: wait until the screen has settled, then judge whether it changed compared with the screen
 * **before the action** (A-1, WP-1). Watching only for "events went quiet after the action" is not enough: a slow
 * page may be quiet because it has not started drawing yet, gets judged "no effect", and the model taps again.
 *
 * The caller captures the tree hash and the frame signature before the action and subscribes to UI events before
 * acting, so the first events caused by the action are never missed; this object only decides, with an injected
 * clock, sleep and probes (JVM-testable).
 *
 * - Events quiet for [QUIET_MS] and the tree differs from before: changed, returned at once without waiting for the
 *   picture to stop (videos and live streams never stop moving).
 * - Only the frame differs (self-drawn UI: Flutter, games, mini programs): changed once two consecutive frames are
 *   close, so a half-drawn transition is not taken as the result.
 * - "Not changed" needs events quiet and a still frame, and is only concluded at [CAP_MS]: a page that starts
 *   reacting after 1.2 s is still seen.
 * - At [CAP_MS], whatever events or animation are still going, the current state decides: tree or frame different
 *   from before = changed. Pages whose events never stop (progress bars, timers) cannot hold an action longer.
 *
 * Differences from Mika's ChangeWait: quiet window 250 ms instead of 400 ms and poll 100 ms instead of 150 ms
 * (faster return on ordinary pages), and the result says which signal changed so [ActionEffects] can count a
 * frame-only change as an effect.
 */
object ChangeWait {
    const val POLL_MS = 100L
    const val QUIET_MS = 250L
    const val CAP_MS = 2_500L
    private const val MIN_SLEEP_MS = 10L

    /**
     * @param changed the screen differs from before the action (tree or frame).
     * @param treeChanged the accessibility tree hash differs from before.
     * @param frameChanged the frame signature differs from before (false when no frame is available).
     * @param settled true when concluded on a settled screen; false when the cap decided.
     * @param waitedMs time spent waiting.
     */
    data class Result(val changed: Boolean, val treeChanged: Boolean, val frameChanged: Boolean, val settled: Boolean, val waitedMs: Long)

    /**
     * @param frame current frame signature ([FrameMath.lumaGrid]); null when there is no picture (then only the tree and
     *   event quiet count)
     * @param quietForMs how long since the last UI event on this display (counted from the subscription before the action)
     * @param treeChanged whether the tree hash differs from the one taken before the action
     * @param beforeFrame frame signature taken before the action
     */
    suspend fun awaitStableChange(
        now: () -> Long,
        sleep: suspend (Long) -> Unit,
        frame: () -> IntArray?,
        quietForMs: () -> Long,
        treeChanged: () -> Boolean,
        beforeFrame: IntArray?,
        capMs: Long = CAP_MS,
    ): Result {
        val start = now()
        val deadline = start + capMs
        var prev = frame()
        var wait = POLL_MS
        while (true) {
            sleep(minOf(wait, (deadline - now()).coerceAtLeast(0)))
            val cur = frame()
            val quietFor = quietForMs()
            val quiet = quietFor >= QUIET_MS
            val tree = treeChanged()
            val frameChanged = FrameMath.differs(beforeFrame, cur)
            if (quiet && tree) return Result(true, true, frameChanged, true, now() - start)
            val stable = if (prev == null || cur == null) prev == null && cur == null else !FrameMath.differs(prev, cur)
            if (quiet && stable && frameChanged) return Result(true, false, true, true, now() - start)
            if (now() >= deadline) return Result(frameChanged || tree, tree, frameChanged, quiet && stable, now() - start)
            wait = if (quiet) POLL_MS else minOf(POLL_MS, QUIET_MS - quietFor).coerceAtLeast(MIN_SLEEP_MS)
            prev = cur
        }
    }
}
