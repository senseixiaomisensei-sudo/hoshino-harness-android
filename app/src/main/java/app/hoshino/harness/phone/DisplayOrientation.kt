package app.hoshino.harness.phone

/** Shape of the agent display (m2.9.2). */
enum class DisplayOrientation(val key: String) { Portrait("portrait"), Landscape("landscape") }

/** What the user picked in the panel: fixed portrait, fixed landscape, or follow the app in front. */
enum class OrientationMode(val key: String, val label: String) {
    Portrait("portrait", "竖屏"), Landscape("landscape", "横屏"), FollowApp("follow", "跟随 App");

    companion object { fun of(key: String?): OrientationMode = entries.firstOrNull { it.key == key } ?: FollowApp }
}

/**
 * Portrait ⇄ landscape decisions for the agent display (pure, unit-tested). The display is resized with
 * VirtualDisplay.resize (width and height swapped, same dpi), so the 0..999 coordinates the model uses always refer to
 * the current orientation and [PreviewTransform] picks up the new aspect.
 *
 * Follow-App reads two signals of the activity in front on the agent display:
 *  - its manifest `screenOrientation` (landscape / portrait families);
 *  - its window bounds: a virtual display does not rotate, so an app that asks for the other orientation at runtime
 *    (setRequestedOrientation, e.g. a video going full screen, a game) is letterboxed into a window of the other shape.
 * The window signal wins (it reflects runtime requests); unspecified / sensor-only apps keep the current shape.
 */
object OrientationPolicy {
    // android.content.pm.ActivityInfo.SCREEN_ORIENTATION_* (kept as plain ints so this stays JVM-testable).
    private val LANDSCAPE = setOf(0, 6, 8, 11)   // LANDSCAPE, SENSOR_LANDSCAPE, REVERSE_LANDSCAPE, USER_LANDSCAPE
    private val PORTRAIT = setOf(1, 7, 9, 12)    // PORTRAIT, SENSOR_PORTRAIT, REVERSE_PORTRAIT, USER_PORTRAIT

    fun of(width: Int, height: Int): DisplayOrientation = if (width > height) DisplayOrientation.Landscape else DisplayOrientation.Portrait

    /** width × height for [o] given the display's short and long side (dpi unchanged). */
    fun sizeFor(o: DisplayOrientation, shortSide: Int, longSide: Int): Pair<Int, Int> =
        if (o == DisplayOrientation.Landscape) longSide to shortSide else shortSide to longSide

    fun fromManifest(screenOrientation: Int?): DisplayOrientation? = when (screenOrientation) {
        null -> null
        in LANDSCAPE -> DisplayOrientation.Landscape
        in PORTRAIT -> DisplayOrientation.Portrait
        else -> null
    }

    /**
     * The app window's own shape when it is clearly letterboxed against the display (the other orientation, and
     * noticeably smaller than the display on the long side); null when it fills the display or is ambiguous.
     */
    fun fromWindow(appW: Int, appH: Int, displayW: Int, displayH: Int): DisplayOrientation? {
        if (appW <= 0 || appH <= 0 || displayW <= 0 || displayH <= 0) return null
        val app = of(appW, appH)
        if (app == of(displayW, displayH)) return null
        if (maxOf(appW, appH) < MIN_WINDOW_RATIO * minOf(displayW, displayH)) return null // a small dialog, not the app
        if (kotlin.math.abs(appW - appH) < SQUARE_TOLERANCE * maxOf(appW, appH)) return null
        return app
    }

    fun target(mode: OrientationMode, current: DisplayOrientation, manifest: DisplayOrientation?, window: DisplayOrientation?): DisplayOrientation = when (mode) {
        OrientationMode.Portrait -> DisplayOrientation.Portrait
        OrientationMode.Landscape -> DisplayOrientation.Landscape
        OrientationMode.FollowApp -> window ?: manifest ?: current
    }

    private const val MIN_WINDOW_RATIO = 0.6
    private const val SQUARE_TOLERANCE = 0.1
}

/**
 * Debounce for Follow-App: a change is applied only after [needed] consecutive observations agree (so a transition
 * animation or a dialog does not flip the display back and forth). A manual pick applies at once ([force]).
 */
class OrientationFollower(private val needed: Int = 2) {
    private var candidate: DisplayOrientation? = null
    private var count = 0

    /** Returns the orientation to switch to now, or null to keep the current one. */
    fun observe(current: DisplayOrientation, target: DisplayOrientation): DisplayOrientation? {
        if (target == current) { candidate = null; count = 0; return null }
        if (candidate != target) { candidate = target; count = 0 }
        count++
        if (count < needed) return null
        candidate = null; count = 0
        return target
    }

    fun reset() { candidate = null; count = 0 }
}
