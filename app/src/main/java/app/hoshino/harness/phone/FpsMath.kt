package app.hoshino.harness.phone

import kotlin.math.roundToInt

/**
 * Frame-rate rules for the agent display (pure, unit-tested). The VirtualDisplay itself redraws only when its content
 * changes; what costs power is how often we take a frame from it (the capture throttle in [AgentDisplay] and the GL
 * relay's blit into the ImageReader) and how often the preview is drawn. The user picks one number in
 * 设置 → 设置手机操作 (5–60, default 30); it applies while somebody watches. Nobody watching: the preview is not drawn
 * at all and capture drops to at most [IDLE_CAPTURE_FPS] (the pre-setting cadence that OCR, screenshots and WP-1's
 * frame signature poll were tuned for), or lower if the user chose lower.
 */
object FpsMath {
    const val MIN_FPS = 5
    const val MAX_FPS = 60
    const val DEFAULT_FPS = 30
    /** Slider step in the settings page. */
    const val STEP = 5
    const val IDLE_CAPTURE_FPS = 10
    /** Number of intermediate stops of a 5..60 slider with step 5 (Compose `Slider.steps`). */
    const val SLIDER_STEPS = (MAX_FPS - MIN_FPS) / STEP - 1

    fun clamp(fps: Int): Int = fps.coerceIn(MIN_FPS, MAX_FPS)

    /**
     * Minimum spacing between two frames in ms. Rounded down, so the throttle never undershoots the chosen rate:
     * 60 fps → 16 ms (frames arriving every 16.7 ms all pass), 30 → 33, 5 → 200. Out-of-range values are clamped first.
     */
    fun intervalMs(fps: Int): Long = 1000L / clamp(fps)

    /** Capture rate: the user's choice while watched, otherwise at most [IDLE_CAPTURE_FPS]. */
    fun captureFps(userFps: Int, watching: Boolean): Int = if (watching) clamp(userFps) else minOf(clamp(userFps), IDLE_CAPTURE_FPS)

    /** Preview rate: the user's choice while watched, 0 (not drawn) when nobody looks. */
    fun previewFps(userFps: Int, watching: Boolean): Int = if (watching) clamp(userFps) else 0

    /** Slider position → stored value: nearest multiple of [STEP], clamped. */
    fun snap(value: Float): Int = clamp(((value / STEP).roundToInt()) * STEP)
}
