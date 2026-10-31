package app.hoshino.harness.phone

import org.json.JSONObject
import kotlin.math.ceil

/**
 * Where the time of one phone step went (A-7, WP-0). Pure numbers, never screen content:
 * - [resolveMs]: chooser / target resolution / irreversible check before the action;
 * - [actMs]: the action itself ([PhonePort.perform]);
 * - [waitMs]: waiting for the UI after the action ([PhonePort.waitQuiet] / [PhonePort.awaitChange]);
 * - [readMs]: reading the screen after the action ([PhonePort.read]);
 * - [totalMs]: from the start of the step until its result was known (>= the sum, the rest is bookkeeping).
 */
data class StepTiming(val resolveMs: Long, val actMs: Long, val waitMs: Long, val readMs: Long, val totalMs: Long) {
    fun json(): JSONObject = JSONObject().put("resolve", resolveMs).put("act", actMs).put("wait", waitMs).put("read", readMs).put("total", totalMs)

    /** One local audit line: step kind, ok flag and milliseconds only. */
    fun auditLine(kind: String, ok: Boolean): String = "step kind=$kind ok=${if (ok) 1 else 0} resolve=$resolveMs act=$actMs wait=$waitMs read=$readMs total=$totalMs"
}

/** Summary of one series of milliseconds. Percentiles use the nearest-rank method (always an observed value). */
data class MsSummary(val n: Int, val p50: Long?, val p90: Long?, val min: Long?, val max: Long?, val mean: Double?) {
    fun json(): JSONObject = JSONObject().put("n", n).put("p50", p50 ?: JSONObject.NULL).put("p90", p90 ?: JSONObject.NULL)
        .put("min", min ?: JSONObject.NULL).put("max", max ?: JSONObject.NULL).put("mean", mean?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
}

/** Statistics shared by the step telemetry, `phone_status` and the real-device benchmark (PhoneBenchTest). */
object StepStats {
    /** Nearest-rank percentile of [values] for [p] in (0, 100]; null when empty. */
    fun percentile(values: Collection<Long>, p: Double): Long? {
        require(p > 0.0 && p <= 100.0) { "percentile must be in (0, 100]" }
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun summarize(values: Collection<Long>): MsSummary {
        if (values.isEmpty()) return MsSummary(0, null, null, null, null, null)
        return MsSummary(values.size, percentile(values, 50.0), percentile(values, 90.0), values.min(), values.max(), values.sum().toDouble() / values.size)
    }

    /**
     * Frames per second over the span of [timestampsMs] (monotonic, any order): (frames - 1) / span.
     * Null with fewer than two frames or a zero span, since one frame says nothing about a rate.
     */
    fun fps(timestampsMs: List<Long>): Double? {
        if (timestampsMs.size < 2) return null
        val span = timestampsMs.max() - timestampsMs.min()
        if (span <= 0) return null
        return (timestampsMs.size - 1) * 1000.0 / span
    }
}

/**
 * The last [capacity] step timings in memory (for `phone_status`), plus the last agent-display creation time.
 * Thread-safe; holds step kinds and numbers only.
 */
class StepTelemetry(private val capacity: Int = DEFAULT_CAPACITY) {
    data class Sample(val kind: String, val ok: Boolean, val timing: StepTiming)

    private val samples = ArrayDeque<Sample>()
    @Volatile var displayCreateMs: Long? = null
        private set

    @Synchronized fun record(kind: String, ok: Boolean, timing: StepTiming) {
        samples.addLast(Sample(kind, ok, timing))
        while (samples.size > capacity) samples.removeFirst()
    }

    fun recordDisplayCreate(ms: Long) { displayCreateMs = ms }

    @Synchronized fun recent(): List<Sample> = samples.toList()

    /** P50/P90 of the recent steps per phase, and `open_app` separately (it dominates a run when it is slow). */
    fun summary(): JSONObject {
        val all = recent()
        val ok = all.filter { it.ok }
        fun series(pick: (StepTiming) -> Long) = StepStats.summarize(ok.map { pick(it.timing) }).json()
        return JSONObject().put("window", capacity).put("steps", all.size).put("failed", all.count { !it.ok })
            .put("total", series { it.totalMs }).put("resolve", series { it.resolveMs }).put("act", series { it.actMs })
            .put("wait", series { it.waitMs }).put("read", series { it.readMs })
            .put("open_app", StepStats.summarize(ok.filter { it.kind == StepKind.open_app.name }.map { it.timing.totalMs }).json())
            .put("display_create_ms", displayCreateMs ?: JSONObject.NULL)
    }

    companion object { const val DEFAULT_CAPACITY = 50 }
}
