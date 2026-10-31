package app.hoshino.harness.bench

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.BuildConfig
import app.hoshino.harness.phone.AgentDisplay
import app.hoshino.harness.phone.PhoneController
import app.hoshino.harness.phone.StepStats
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WP-0 real-device phone benchmark (mika-phone-gap §5, A-7). Drives the real [PhoneController] on the agent
 * display exactly like the `android-phone` plugin does (same JSON actions, same StepRunner), and writes
 * `phone-bench-<date>.json` to the app's external files dir (`/sdcard/Android/data/dev.hoshino.app/files/phone-bench/`).
 * `tools/phone-bench/run_bench.py` runs this test, pulls the file and adds the Mika baseline.
 *
 * Sections: agent display creation ms; Settings app fixed 20-step plan × rounds (per-step P50/P90 per phase,
 * per step index, per kind); preview fps while the screen moves; open_app ms; delayed-render fixture (50 taps,
 * how often the result misses the late page); optional Flutter fixture (tree-blind taps judged `done`).
 *
 * Skipped (Assume) unless run with `-e phoneBench true` on a phone where phone control is ready (accessibility
 * service on + built-in helper running). CI never runs it: verify.yml only compiles it, and the emulator jobs list
 * their test classes explicitly. It measures, it does not gate: no latency threshold is asserted here.
 *
 * Instrumentation args: phoneBench=true (required), rounds (3), fixtureTrials (50), settingsPackage
 * (com.android.settings), settingsTargets (comma list, else picked from a safe allowlist), flutterPackage (optional),
 * flutterTrials (20).
 */
class PhoneBenchTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context = inst.targetContext
    private val phone = PhoneController.of(context)
    private val notes = JSONArray()

    private fun arg(name: String, default: String) = args.getString(name)?.takeIf { it.isNotBlank() } ?: default
    private fun intArg(name: String, default: Int) = arg(name, default.toString()).toIntOrNull()?.coerceAtLeast(1) ?: default

    @Before fun onlyOnAReadyPhoneWhenAsked() {
        assumeTrue("phone bench only runs when asked: -e phoneBench true", args.getString("phoneBench") == "true")
        val status = runBlocking { phone.handle(JSONObject().put("action", "status")) }
        assumeTrue("phone control is not ready (accessibility + helper needed): $status", status.optBoolean("ready"))
    }

    @After fun releaseAgentDisplay() {
        runBlocking { runCatching { call("release") } }
    }

    // ---------- plumbing ----------

    private suspend fun call(action: String, extra: JSONObject.() -> Unit = {}): JSONObject =
        phone.handle(JSONObject().put("action", action).put("sessionId", SESSION).apply(extra))

    private suspend fun steps(list: List<JSONObject>): JSONObject = call("steps") { put("steps", JSONArray(list)) }

    private fun step(action: String, fill: JSONObject.() -> Unit = {}) = JSONObject().put("action", action).apply(fill)
    private fun tap(target: String) = step("tap") { put("target", target) }
    private fun tapAt(x: Int, y: Int) = step("tap") { put("x", x).put("y", y) }
    private fun scroll(direction: String) = step("scroll") { put("direction", direction) }
    private fun back() = step("back")
    private fun openApp(pkg: String, expect: JSONObject) = step("open_app") { put("app", pkg).put("expect", expect) }
    private fun waitFor(text: String, timeoutMs: Long) = step("wait") { put("expect", JSONObject().put("text", text).put("timeout_ms", timeoutMs)) }

    /** One acted step out of a `steps` response: kind, index and its A-7 milliseconds. */
    private data class Sample(val kind: String, val index: Int, val ok: Boolean, val ms: JSONObject)

    private fun samples(response: JSONObject, kinds: List<String>): List<Sample> {
        val out = mutableListOf<Sample>()
        val results = response.optJSONArray("results") ?: return out
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val ms = r.optJSONObject("ms") ?: continue
            val index = r.optInt("index")
            out += Sample(kinds.getOrElse(index) { r.optString("step").substringBefore(' ') }, index, r.optBoolean("ok"), ms)
        }
        return out
    }

    private fun summary(values: List<Long>) = StepStats.summarize(values).json()

    private fun phases(list: List<Sample>): JSONObject {
        val ok = list.filter { it.ok }
        return JSONObject().apply { for (p in PHASES) put(p, summary(ok.map { it.ms.optLong(p) })) }
    }

    private fun response(r: JSONObject) = JSONObject().put("completed", r.optBoolean("completed")).put("stopped_at", r.opt("stopped_at") ?: JSONObject.NULL)
        .put("reason", r.optString("reason").ifBlank { r.optString("error") }.take(200).ifBlank { null } ?: JSONObject.NULL)

    // ---------- the benchmark ----------

    @Test fun benchmarkOnThisPhone() = runBlocking {
        val openAppMs = mutableListOf<Long>()
        val report = JSONObject()
            .put("schema", SCHEMA)
            .put("date", day())
            .put("generated_at", iso(Date()))
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                .put("release", Build.VERSION.RELEASE).put("incremental", Build.VERSION.INCREMENTAL))
            .put("app", JSONObject().put("package", context.packageName).put("version_name", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
                .put("build_type", BuildConfig.BUILD_TYPE))
        val hoshino = JSONObject()
        hoshino.put("display_create", displayCreate())
        val settings = settings20(openAppMs)
        hoshino.put("settings_20", settings)
        hoshino.put("preview", previewFps())
        val delayed = delayedRender(openAppMs)
        hoshino.put("delayed_render", delayed)
        hoshino.put("flutter", flutter(openAppMs))
        hoshino.put("open_app", JSONObject().put("samples_ms", JSONArray(openAppMs)).put("total_ms", summary(openAppMs)))
        hoshino.put("phone_status_step_timing", phone.telemetry.summary())
        report.put("hoshino", hoshino).put("mika", JSONObject.NULL).put("notes", notes)

        val dir = File(context.getExternalFilesDir(null), "phone-bench").apply { mkdirs() }
        val text = report.toString(2)
        File(dir, "phone-bench-${day()}.json").writeText(text)
        File(dir, "latest.json").writeText(text)
        Log.i(TAG, "wrote ${File(dir, "latest.json").absolutePath}")

        // It measures; these only prove each section really ran on the phone.
        assertTrue("settings plan produced no timed step: ${settings.optJSONArray("rounds")}", settings.getJSONObject("per_step").getJSONObject("total").getInt("n") > 0)
        assertEquals(intArg("fixtureTrials", 50), delayed.getInt("trials"))
    }

    /** Agent display creation: release, then the first observe creates it again (PhoneController times AgentDisplay.create). */
    private suspend fun displayCreate(): JSONObject {
        val samples = mutableListOf<Long>()
        repeat(3) {
            call("release")
            val r = call("observe") { put("full", true) }
            if (!r.optBoolean("ok")) { notes.put("display_create: observe failed: ${r.optString("error").take(200)}"); return@repeat }
            phone.telemetry.displayCreateMs?.let { samples += it }
        }
        return JSONObject().put("samples_ms", JSONArray(samples)).put("ms", summary(samples))
    }

    /** Fixed 20-step plan in the Settings app; the four tap targets are picked once from a safe allowlist (or given). */
    private suspend fun settings20(openAppMs: MutableList<Long>): JSONObject {
        val pkg = arg("settingsPackage", "com.android.settings")
        val out = JSONObject().put("package", pkg)
        val open = steps(listOf(openApp(pkg, JSONObject().put("app", pkg).put("timeout_ms", 8_000))))
        if (!open.optBoolean("completed")) return out.put("error", "could not open $pkg: ${response(open)}").put("rounds", JSONArray()).put("per_step", JSONObject().put("total", summary(emptyList())))
        val screen = call("observe") { put("full", true) }.optString("screen")
        val given = args.getString("settingsTargets")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val targets = given.ifEmpty { SettingsPlan.pickTargets(screen) }
        out.put("targets", JSONArray(targets)).put("targets_given", given.isNotEmpty())
        if (targets.isEmpty()) {
            notes.put("settings_20: none of the allowlisted entries is on the first Settings screen; pass -e settingsTargets")
            return out.put("error", "no safe tap target").put("rounds", JSONArray()).put("per_step", JSONObject().put("total", summary(emptyList())))
        }
        val plan = SettingsPlan.build(pkg, targets)
        val kinds = plan.map { it.getString("action") }
        val rounds = JSONArray()
        val all = mutableListOf<Sample>()
        repeat(intArg("rounds", 3)) {
            steps(List(4) { back() }) // leave Settings so step 1 really opens it (back on the empty agent display is harmless)
            val r = steps(plan)
            val got = samples(r, kinds)
            all += got
            got.filter { it.ok && it.kind == "open_app" }.forEach { openAppMs += it.ms.optLong("total") }
            rounds.put(response(r).put("timed_steps", got.size))
        }
        val perIndex = JSONArray()
        for (i in plan.indices) {
            val at = all.filter { it.index == i && it.ok }
            perIndex.put(JSONObject().put("index", i).put("step", kinds[i] + (plan[i].optString("target").takeIf { it.isNotEmpty() }?.let { " \"$it\"" } ?: plan[i].optString("direction").takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""))
                .put("total_ms", summary(at.map { it.ms.optLong("total") })))
        }
        val byKind = JSONObject()
        for (k in kinds.distinct()) byKind.put(k, phases(all.filter { it.kind == k }))
        return out.put("steps_in_plan", plan.size).put("rounds", rounds).put("per_step", phases(all)).put("per_index", perIndex).put("by_kind", byKind)
    }

    /** Preview frames while somebody watches and the screen moves (frames are only produced on change, capped at PREVIEW_FPS). */
    private suspend fun previewFps(): JSONObject {
        val pkg = arg("settingsPackage", "com.android.settings")
        steps(listOf(openApp(pkg, JSONObject().put("app", pkg).put("timeout_ms", 8_000))))
        val display = phone.display.value ?: return JSONObject().put("error", "no agent display")
        val stamps = Collections.synchronizedList(mutableListOf<Long>())
        val started = SystemClock.uptimeMillis()
        coroutineScope {
            display.previewWatchers++
            val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { display.frames.collect { if (it != null) stamps += SystemClock.uptimeMillis() } }
            try {
                steps(List(8) { i -> scroll(if (i % 2 == 0) "down" else "up") })
            } finally {
                job.cancelAndJoin()
                display.previewWatchers--
            }
        }
        val window = SystemClock.uptimeMillis() - started
        val list = synchronized(stamps) { stamps.toList() }
        return JSONObject().put("fps", StepStats.fps(list)?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL).put("frames", list.size)
            .put("window_ms", window).put("cap_fps", AgentDisplay.PREVIEW_FPS).put("while", "8 scrolls in $pkg")
    }

    /** Taps whose page appears only after 600 / 1200 ms; counts results that missed the late page (A-1 baseline). */
    private suspend fun delayedRender(openAppMs: MutableList<Long>): JSONObject {
        val pkg = inst.context.packageName // the test APK holds the fixture activity
        val trials = intArg("fixtureTrials", 50)
        val out = JSONObject().put("package", pkg).put("trials", 0)
        val open = steps(listOf(openApp(pkg, JSONObject().put("text", DelayedRenderActivity.TITLE).put("timeout_ms", 8_000))))
        samples(open, listOf("open_app")).filter { it.ok }.forEach { openAppMs += it.ms.optLong("total") }
        if (!open.optBoolean("completed")) return out.put("error", "could not open fixture: ${response(open)}")
        val byDelay = JSONObject()
        val taps = mutableListOf<Sample>()
        var ran = 0
        var noEffect = 0
        var missed = 0
        for (delay in listOf(600L, 1200L)) {
            var dRan = 0; var dNoEffect = 0; var dMissed = 0
            val n = trials / 2 + if (delay == 600L) trials % 2 else 0
            repeat(n) {
                val r = steps(listOf(tap(DelayedRenderActivity.label(delay))))
                taps += samples(r, listOf("tap"))
                val effectNo = r.optString("effect") == "no_effect"
                val pageMissing = !r.optString("screen").contains(DelayedRenderActivity.LOADED)
                ran++; dRan++
                if (effectNo) { noEffect++; dNoEffect++ }
                if (pageMissing) { missed++; dMissed++ }
                // Reset for the next trial: let the late page land, then go home.
                steps(listOf(waitFor(DelayedRenderActivity.LOADED, 5_000), tap(DelayedRenderActivity.HOME_BUTTON).put("expect", JSONObject().put("text", DelayedRenderActivity.label(delay)).put("timeout_ms", 5_000))))
            }
            byDelay.put(delay.toString(), JSONObject().put("trials", dRan).put("effect_no_effect", dNoEffect).put("late_page_missing", dMissed))
        }
        return out.put("trials", ran).put("effect_no_effect", noEffect).put("late_page_missing", missed).put("by_delay_ms", byDelay)
            .put("tap", phases(taps))
            .put("meaning", "effect_no_effect: the steps result said nothing changed although the page did change later; late_page_missing: the returned screen did not contain the loaded page")
    }

    /** Optional: a Flutter app whose tap target has no semantics (tools/phone-bench/flutter-fixture). */
    private suspend fun flutter(openAppMs: MutableList<Long>): JSONObject {
        val pkg = args.getString("flutterPackage")?.takeIf { it.isNotBlank() } ?: return JSONObject().put("skipped", "no -e flutterPackage")
        val trials = intArg("flutterTrials", 20)
        val open = steps(listOf(openApp(pkg, JSONObject().put("app", pkg).put("timeout_ms", 8_000))))
        samples(open, listOf("open_app")).filter { it.ok }.forEach { openAppMs += it.ms.optLong("total") }
        if (!open.optBoolean("completed")) return JSONObject().put("package", pkg).put("error", "could not open: ${response(open)}")
        val taps = mutableListOf<Sample>()
        var done = 0
        var tapsTreeBlind = 0
        repeat(trials) {
            val r = steps(listOf(tapAt(500, 500)))
            taps += samples(r, listOf("tap"))
            if (r.optString("effect") == "done") done++
            if (r.optBoolean("tree_blind")) tapsTreeBlind++
        }
        return JSONObject().put("package", pkg).put("trials", trials).put("effect_done", done).put("tree_blind", tapsTreeBlind)
            .put("done_rate", Math.round(done * 1000.0 / trials) / 1000.0).put("tap", phases(taps))
            .put("meaning", "every tap visibly changes the painted page, so the correct effect is always done")
    }

    companion object {
        const val TAG = "PhoneBench"
        const val SESSION = "phone-bench"
        const val SCHEMA = "hoshino-phone-bench/1"
        val PHASES = listOf("total", "resolve", "act", "wait", "read")
        private fun day(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        private fun iso(d: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(d)
    }
}
