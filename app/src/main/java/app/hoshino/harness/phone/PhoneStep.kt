package app.hoshino.harness.phone

import org.json.JSONArray
import org.json.JSONObject

enum class StepKind { tap, long_press, type, clear, scroll, swipe, back, key, open_app, wait }

/**
 * Wait condition checked by code after a step (no model round-trip while the app is loading):
 * [text] appears, [gone] disappears, [app] is in front, or the screen stays [quiet].
 */
data class Expectation(val text: String? = null, val gone: String? = null, val app: String? = null, val quiet: Boolean = false, val timeoutMs: Long = DEFAULT_TIMEOUT) {
    companion object {
        const val DEFAULT_TIMEOUT = 8_000L
        const val MAX_TIMEOUT = 30_000L
        fun parse(o: JSONObject?): Expectation? {
            o ?: return null
            val e = Expectation(
                text = o.optString("text").takeIf { it.isNotBlank() },
                gone = o.optString("gone").takeIf { it.isNotBlank() },
                app = o.optString("app").takeIf { it.isNotBlank() },
                quiet = o.optBoolean("quiet", false),
                timeoutMs = o.optLong("timeout_ms", DEFAULT_TIMEOUT).coerceIn(500, MAX_TIMEOUT),
            )
            return if (e.text == null && e.gone == null && e.app == null && !e.quiet) null else e
        }
    }
}

/**
 * One phone action. Targets: [ref] (from the last read), [target] (visible text to find), or a point
 * [x],[y] in 0..999. Swipes use [x]/[y] → [x2]/[y2].
 */
data class PhoneStep(
    val kind: StepKind,
    val ref: String? = null,
    val target: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null,
    val enter: Boolean = false,
    val direction: String? = null,
    val key: String? = null,
    val app: String? = null,
    val durationMs: Long? = null,
    val expect: Expectation? = null,
    /** open_app only (A-5): force-stop first so the app starts from its first page (helper decides if that is safe). */
    val cold: Boolean = false,
) {
    fun describe(): String = buildString {
        append(kind.name)
        ref?.let { append(' ').append(it) }
        target?.let { append(" \"").append(it.take(40)).append('"') }
        if (x != null && y != null) append(" @").append(x).append(',').append(y)
        if (kind == StepKind.type) text?.let { append(" text(").append(it.length).append(" chars)") }
        if (enter) append(" +enter")
        direction?.let { append(' ').append(it) }
        key?.let { append(" key=").append(it) }
        app?.let { append(" app=").append(it) }
        if (cold) append(" cold")
    }

    companion object {
        fun parse(o: JSONObject): PhoneStep {
            val action = o.optString("action").ifBlank { o.optString("kind") }
            val kind = runCatching { StepKind.valueOf(action) }.getOrElse { throw IllegalArgumentException("unknown action '$action'") }
            fun int(k: String) = if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { !it.isNaN() }?.toInt()?.coerceIn(0, 999) else null
            fun str(k: String) = o.optString(k).takeIf { o.has(k) && !o.isNull(k) && it.isNotEmpty() }
            val step = PhoneStep(
                kind = kind, ref = str("ref"), target = str("target"), x = int("x"), y = int("y"), x2 = int("x2"), y2 = int("y2"),
                text = if (o.has("text") && !o.isNull("text")) o.optString("text") else null, enter = o.optBoolean("enter", false),
                direction = str("direction")?.lowercase(), key = str("key")?.lowercase(), app = str("app") ?: str("package"),
                durationMs = if (o.has("duration_ms")) o.optLong("duration_ms").coerceIn(50, 5_000) else null,
                expect = Expectation.parse(o.optJSONObject("expect")),
                cold = kind == StepKind.open_app && o.optBoolean("cold", false),
            )
            step.validate()
            return step
        }

        fun parseAll(a: JSONArray): List<PhoneStep> {
            require(a.length() in 1..MAX_STEPS) { "steps must contain 1..$MAX_STEPS actions" }
            return (0 until a.length()).map { i -> runCatching { parse(a.getJSONObject(i)) }.getOrElse { throw IllegalArgumentException("step ${i + 1}: ${it.message}") } }
        }

        const val MAX_STEPS = 20
        val SCROLL_DIRECTIONS = setOf("up", "down", "left", "right")
    }

    fun validate() {
        val hasTarget = ref != null || target != null || (x != null && y != null)
        when (kind) {
            StepKind.tap, StepKind.long_press -> require(hasTarget) { "$kind needs ref, target or x/y" }
            StepKind.type -> require(text != null) { "type needs text" }
            StepKind.scroll -> require(direction in SCROLL_DIRECTIONS) { "scroll direction must be up/down/left/right" }
            StepKind.swipe -> require(x != null && y != null && x2 != null && y2 != null) { "swipe needs x,y,x2,y2" }
            StepKind.key -> require(key != null && key in KEYS) { "key must be one of ${KEYS.keys.joinToString()}" }
            StepKind.open_app -> require(app != null) { "open_app needs app (package or label)" }
            StepKind.wait -> require(expect != null) { "wait needs expect" }
            else -> Unit
        }
        text?.let { require(it.length <= 4_000) { "text too long" } }
    }
}

/** Keys the model may press (Android key codes); never power/volume/home. */
val KEYS: Map<String, Int> = linkedMapOf(
    "back" to 4, "enter" to 66, "tab" to 61, "del" to 67, "forward_del" to 112, "space" to 62, "search" to 84,
    "up" to 19, "down" to 20, "left" to 21, "right" to 22, "center" to 23, "page_up" to 92, "page_down" to 93, "escape" to 111,
    "move_home" to 122, "move_end" to 123,
)
