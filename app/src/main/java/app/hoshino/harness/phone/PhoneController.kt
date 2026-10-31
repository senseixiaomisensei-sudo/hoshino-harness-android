package app.hoshino.harness.phone

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import app.hoshino.harness.phone.ocr.OcrModels
import app.hoshino.harness.phone.ocr.PhoneOcr
import dev.hoshino.bridge.phone.IHoshinoPhoneShell
import dev.hoshino.bridge.phone.shell.BuiltinShellManager
import dev.hoshino.bridge.phone.shell.PhoneServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What the capsule, the chat picture-in-picture and notifications show about the running phone task. */
data class PhoneTaskState(
    val sessionId: String? = null,
    val status: Status = Status.Idle,
    val app: String? = null,
    /** Last three step descriptions (no typed text, see [PhoneStep.describe]). */
    val recent: List<String> = emptyList(),
    /** Why the user is needed / why it stopped. */
    val reason: String? = null,
) {
    enum class Status { Idle, Working, NeedsUser, Approval, TakenOver }
}

/**
 * The single execution entry for phone actions (RT13). Every tool call from the `android-phone` plugin
 * arrives here through the `phone` bridge callback and goes: guards (setup ready, screen not locked, one task
 * at a time, never Hoshino itself unless allowed — AP7) → [StepRunner] (irreversible check: stops with a
 * pending approval unless the exact step on the exact screen was granted once) → execute → verify by code
 * (expectations, [ActionEffects], [ScreenDiff]) → record (local audit, no content). Approval itself is decided in
 * the kernel by the existing hoshino-m2 approval policy; this class only consumes one-shot grants.
 */
class PhoneController private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val services get() = PhoneServices.of(context)
    private val consent get() = PhoneConsent.of(context)
    private val lock = Mutex()
    private val _state = MutableStateFlow(PhoneTaskState())
    val state: StateFlow<PhoneTaskState> = _state.asStateFlow()
    private val _display = MutableStateFlow<AgentDisplay?>(null)
    /** The agent display while it exists (frames for the capsule / picture-in-picture). */
    val display: StateFlow<AgentDisplay?> = _display.asStateFlow()
    private var screen: AndroidPhoneScreen? = null
    private var owner: String? = null
    private val shown = ConcurrentHashMap<String, ScreenSnapshot>()
    private val grants = ConcurrentHashMap<String, Long>()
    private val stops = ConcurrentHashMap<String, String>()
    private var idleJob: Job? = null
    /** A-7: recent step timings (numbers and step kinds only) for `phone_status` and the benchmark. */
    val telemetry = StepTelemetry()
    val ocr: PhoneOcr? get() = OcrModels.engine(context)

    init {
        // m2.9: a foreground service (with its ongoing notice) lives exactly as long as the agent display.
        scope.launch {
            _display.map { it != null }.distinctUntilChanged().collect { exists ->
                PhoneTaskForeground.sync(context, exists)?.let { consent.audit("phone task foreground service not started: ${it.javaClass.simpleName}") }
            }
        }
    }

    suspend fun handle(body: JSONObject): JSONObject = try {
        when (val action = body.optString("action")) {
            "status" -> status()
            "diagnose" -> diagnose()
            "observe" -> observe(session(body), body.optBoolean("full", false))
            "screenshot" -> screenshot(session(body))
            "steps" -> steps(session(body), body)
            "grant" -> grant(session(body), body.getString("fingerprint"))
            "list_apps" -> listApps(body.optString("query"))
            // m2.9: lets the kernel's system_shell guard accept only commands that explicitly target the agent display.
            "display" -> JSONObject().put("ok", true).put("display", agentDisplayId() ?: JSONObject.NULL)
            "handoff" -> PhoneTakeover.of(context).request(session(body), body.optString("reason").take(300), this)
            "stop" -> { stop(session(body), "stopped by request"); JSONObject().put("ok", true) }
            "release" -> { release(session(body)); JSONObject().put("ok", true) }
            else -> error("unknown phone action '$action'")
        }
    } catch (e: PhoneUnavailable) {
        JSONObject().put("ok", false).put("error", e.message).put("code", e.code).put("setup", "设置 → 设置手机操作")
    }

    private fun session(body: JSONObject): String = body.optString("sessionId").also { require(it.isNotBlank()) { "sessionId required" } }

    // ---------- guards ----------

    private fun requireReady(): IHoshinoPhoneShell {
        if (Build.VERSION.SDK_INT < BuiltinShellManager.MIN_SDK) throw PhoneUnavailable("unsupported", "手机操作需要 Android 13 或更高版本（推荐 14+，后台屏有独立焦点）")
        PhoneAccessibilityService.instance.value ?: throw PhoneUnavailable("accessibility", "Hoshino 的无障碍服务没有开启")
        return services.shell.value ?: run {
            scope.launch { services.ensure() }
            throw PhoneUnavailable("helper", "手机助手没有运行：请在 设置 → 设置手机操作 配对内置无线调试助手（不需要 Shizuku）；已配对的话确认连着 Wi-Fi 且无线调试开着")
        }
    }

    private fun locked(): Boolean = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private suspend fun claim(sessionId: String) {
        val current = owner
        if (current != null && current != sessionId && _state.value.status != PhoneTaskState.Status.Idle) throw PhoneUnavailable("busy", "另一个会话正在使用手机，先等它完成或在胶囊里停止")
        owner = sessionId
    }

    private suspend fun screenFor(shell: IHoshinoPhoneShell): AndroidPhoneScreen {
        screen?.let { s -> if (_display.value != null) return s }
        val createStart = SystemClock.elapsedRealtime()
        val d = withContext(Dispatchers.Main) { AgentDisplay.create(context, shell) }
        telemetry.recordDisplayCreate(SystemClock.elapsedRealtime() - createStart)
        _display.value = d
        val s = AndroidPhoneScreen(context, d.displayId, { services.shell.value }, d, { ocr })
        screen = s
        return s
    }

    /** Port wrapper: AP7 and lock checks before every single action, and the audit line after it. */
    private inner class GuardedPort(private val inner: AndroidPhoneScreen, private val sessionId: String) : PhonePort by inner {
        override suspend fun perform(step: PhoneStep, target: UiElement?): ActionOutcome {
            PhoneGuards.refuse(inner.last?.packageName, context.packageName, consent.allowSelf, locked(), step)?.let { return ActionOutcome(false, it) }
            val out = inner.perform(step, target)
            _state.update { it.copy(recent = (it.recent + step.describe()).takeLast(3), app = inner.last?.packageName ?: it.app) }
            consent.audit("action ${step.kind} pkg=${inner.last?.packageName ?: "?"} ok=${out.ok}")
            return out
        }
    }

    // ---------- actions ----------

    private suspend fun observe(sessionId: String, full: Boolean): JSONObject = lock.withLock {
        val s = screenFor(requireReady())
        claim(sessionId)
        touchIdle()
        val snap = s.read()
        val prev = if (full) null else shown[sessionId]
        shown[sessionId] = snap
        _state.update { it.copy(sessionId = sessionId, app = snap.packageName) }
        screenJson(prev, snap)
    }

    private fun screenJson(prev: ScreenSnapshot?, snap: ScreenSnapshot): JSONObject = JSONObject()
        .put("ok", true)
        .put("app", snap.packageName ?: JSONObject.NULL)
        .put("screen", ScreenDiff.render(prev, snap))
        .put("secure", snap.secure)
        .put("ocr", snap.ocrUsed)
        .apply {
            SystemScreens.chooser(snap)?.let { c -> put("chooser", JSONObject().put("title", c.title).put("options", JSONArray(c.options.map { o -> JSONObject().put("ref", o.ref).put("label", o.label).put("clone", o.clone ?: JSONObject.NULL) }))) }
            SystemScreens.needsUser(snap)?.let { n -> put("needs_user", JSONObject().put("reason", n.reason)) }
            if (snap.secure) put("hint", "secure screen (FLAG_SECURE or black frame): no picture/text; use phone_handoff so the user handles it")
        }

    private suspend fun screenshot(sessionId: String): JSONObject = lock.withLock {
        val s = screenFor(requireReady())
        claim(sessionId)
        val frame = s.frame() ?: error("no frame yet; open an app first")
        try {
            if (FrameMath.isBlack(frame.width, frame.height) { x, y -> frame.getPixel(x, y) }) return@withLock JSONObject().put("ok", false).put("error", "secure: the app forbids screenshots (FLAG_SECURE); use phone_handoff")
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "phone-screen-${SystemClock.uptimeMillis()}.jpg")
            try {
                file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                app.hoshino.harness.core.AppMedia(context).imageBlock(file, "phone-screen.jpg")
            } finally { file.delete() }
        } finally { frame.recycle() }
    }

    private suspend fun steps(sessionId: String, body: JSONObject): JSONObject = lock.withLock {
        val shell = requireReady()
        if (locked()) throw PhoneUnavailable("locked", "手机锁屏中，解锁后再继续")
        val steps = PhoneStep.parseAll(body.getJSONArray("steps"))
        val s = screenFor(shell)
        claim(sessionId)
        touchIdle()
        stops.remove(sessionId)
        val wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hoshino:phone-task")
        wake.acquire(WAKE_MS)
        _state.update { it.copy(sessionId = sessionId, status = PhoneTaskState.Status.Working, reason = null) }
        try {
            val before = s.last
            val runner = StepRunner(GuardedPort(s, sessionId), { fp -> consumeGrant(sessionId, fp) }, { stops[sessionId] })
            val r = runner.run(steps, body.optString("app_label").takeIf { it.isNotBlank() })
            for (x in r.results) x.timing?.let { t ->
                val kind = steps[x.index].kind.name
                telemetry.record(kind, x.ok, t)
                consent.audit(t.auditLine(kind, x.ok))
            }
            val after = r.screen
            val effect = ActionEffects.of(before, after, r.frameChanged)
            val prev = shown[sessionId]
            after?.let { shown[sessionId] = it }
            val out = JSONObject().put("ok", r.completed).put("completed", r.completed)
                .put("results", JSONArray(r.results.map { x -> JSONObject().put("index", x.index).put("step", x.step).put("ok", x.ok).put("detail", x.detail ?: JSONObject.NULL).put("waited_ms", x.waitedMs).put("met", x.met ?: JSONObject.NULL).apply { x.timing?.let { put("ms", it.json()) }; x.change?.let { put("changed", it.changed); if (it.frameChanged && !it.treeChanged) put("frame_only", true) }; x.openedInMs?.let { put("opened_in_ms", it) } } }))
                .put("effect", effect.effect.name).put("tree_blind", effect.treeBlind)
            effect.limitReached?.let { out.put("limit_reached", it) }
            r.stoppedAt?.let { out.put("stopped_at", it).put("reason", r.reason ?: "") }
            r.pending?.let { p ->
                out.put("pending", JSONObject().put("index", p.index).put("step", p.step).put("action", p.action.name).put("matched", p.matched).put("fingerprint", p.fingerprint).put("app", p.packageName ?: JSONObject.NULL))
                _state.update { it.copy(status = PhoneTaskState.Status.Approval, reason = "${p.action.name}: ${p.matched}") }
            }
            r.chooser?.let { c -> out.put("chooser", JSONObject().put("title", c.title).put("options", JSONArray(c.options.map { o -> JSONObject().put("ref", o.ref).put("label", o.label).put("clone", o.clone ?: JSONObject.NULL) }))) }
            r.needsUser?.let { n ->
                out.put("needs_user", JSONObject().put("reason", n.reason))
                _state.update { it.copy(status = PhoneTaskState.Status.NeedsUser, reason = n.reason) }
                PhoneNotifier.needsUser(context, sessionId, n.reason)
            }
            if (after != null) out.put("app", after.packageName ?: JSONObject.NULL).put("screen", ScreenDiff.render(prev, after)).put("secure", after.secure)
            if (r.pending == null && r.needsUser == null) _state.update { it.copy(status = PhoneTaskState.Status.Idle) }
            out
        } catch (e: Exception) {
            _state.update { it.copy(status = PhoneTaskState.Status.Idle, reason = e.message) }
            throw e
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    /** Called by the plugin after the kernel's approval policy approved one pending irreversible step. */
    private fun grant(sessionId: String, fingerprint: String): JSONObject {
        require(fingerprint.matches(Regex("[0-9a-f]{32}"))) { "bad fingerprint" }
        grants["$sessionId:$fingerprint"] = SystemClock.elapsedRealtime() + GRANT_MS
        _state.update { if (it.status == PhoneTaskState.Status.Approval) it.copy(status = PhoneTaskState.Status.Working, reason = null) else it }
        return JSONObject().put("ok", true).put("expires_in_ms", GRANT_MS)
    }

    /** One-shot: the grant is removed whether or not it is still valid. */
    private fun consumeGrant(sessionId: String, fp: String): Boolean {
        val until = grants.remove("$sessionId:$fp") ?: return false
        return SystemClock.elapsedRealtime() <= until
    }

    private fun listApps(query: String): JSONObject {
        val apps = PhoneApps.list(context).filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }.take(300)
        return JSONObject().put("ok", true).put("apps", JSONArray(apps.map { JSONObject().put("label", it.label).put("package", it.packageName) }))
    }

    fun stop(sessionId: String?, why: String) {
        val sid = sessionId ?: owner ?: return
        stops[sid] = why
        _state.update { if (it.sessionId == sid) it.copy(status = PhoneTaskState.Status.Idle, reason = why) else it }
    }

    fun release(sessionId: String?) {
        if (sessionId != null && owner != null && owner != sessionId) return
        owner = null
        shown.clear()
        grants.clear()
        screen = null
        _display.value?.release()
        _display.value = null
        PhoneLedger.restoreAll(context, services.shell.value)
        _state.value = PhoneTaskState()
    }

    fun setTakenOver(on: Boolean, reason: String? = null) {
        _state.update { it.copy(status = if (on) PhoneTaskState.Status.TakenOver else PhoneTaskState.Status.Working, reason = reason) }
    }

    /** Re-read the agent screen after the user handed it back (takeover). */
    suspend fun reread(sessionId: String): JSONObject? = screen?.let { s ->
        val snap = s.read()
        val prev = shown[sessionId]
        shown[sessionId] = snap
        screenJson(prev, snap)
    }

    fun agentDisplayId(): Int? = _display.value?.displayId

    /** The idle agent display is released after [IDLE_MS] so it costs nothing between tasks. */
    private fun touchIdle() {
        idleJob?.cancel()
        idleJob = scope.launch { delay(IDLE_MS); lock.withLock { if (_state.value.status == PhoneTaskState.Status.Idle) release(null) } }
    }

    private fun status(): JSONObject {
        val sdk = Build.VERSION.SDK_INT
        val a11y = PhoneAccessibilityService.instance.value != null
        val shell = services.shell.value
        return JSONObject().put("ok", true).put("sdk", sdk).put("supported", sdk >= BuiltinShellManager.MIN_SDK)
            .put("own_focus", sdk >= 34).put("accessibility", a11y).put("helper", shell != null).put("channel", services.channel.value.name)
            .put("builtin", services.builtin.engine.status.value.toString()).put("ocr", ocr?.ready == true)
            .put("ready", sdk >= BuiltinShellManager.MIN_SDK && a11y && shell != null)
            .put("task", _state.value.status.name)
            .put("step_timing", telemetry.summary())
    }

    /** PH26: one report with helper, accessibility, display, focus and lock state. Package names only, no content. */
    private fun diagnose(): JSONObject {
        val c = consent.state.value
        return status().put("locked", locked()).put("display", agentDisplayId() ?: JSONObject.NULL)
            .put("main_screen_app", PhoneAccessibilityService.instance.value?.foreground?.value ?: JSONObject.NULL)
            .put("muted", JSONArray(PhoneLedger.mutedPackages(context)))
            .put("setup_issues", JSONArray(services.setupIssues.value))
            .put("helper_last_error", services.builtin.engine.lastError ?: JSONObject.NULL)
            .put("consent", JSONObject().put("confirmed", c.confirmed).put("accessibility", c.enableAccessibility).put("write_secure_settings", c.writeSecureSettings)
                .put("battery", c.batteryExemption).put("auto_wireless_debugging", c.autoWirelessDebugging).put("mute", c.muteAgentApps).put("allow_self", c.allowSelf))
            .put("ocr_models", OcrModels.state(context).name)
            .put("hint", if (Build.VERSION.SDK_INT == 33) "Android 13: the agent display has no own focus; typing may move focus away from the user's app" else JSONObject.NULL)
    }

    /** Main-screen read for "send what I see" (CU5); never acts on the main screen. */
    suspend fun readMainScreen(): Pair<ScreenSnapshot, Bitmap?> {
        val s = AndroidPhoneScreen(context, Display.DEFAULT_DISPLAY, { null }, null, { ocr })
        val snap = s.read()
        val frame = if (snap.secure) null else s.frame()
        return snap to frame
    }

    companion object {
        const val GRANT_MS = 120_000L
        const val WAKE_MS = 10 * 60_000L
        const val IDLE_MS = 5 * 60_000L
        @Volatile private var instance: PhoneController? = null
        fun of(context: Context): PhoneController = instance ?: synchronized(this) { instance ?: PhoneController(context.applicationContext).also { instance = it } }
    }
}

/** Pure guard rules (AP7, RT14), unit-tested. */
object PhoneGuards {
    fun refuse(screenPackage: String?, ownPackage: String, allowSelf: Boolean, locked: Boolean, step: PhoneStep): String? = when {
        locked -> "locked: the phone is locked; actions wait until it is unlocked"
        !allowSelf && screenPackage == ownPackage && step.kind != StepKind.open_app && step.kind != StepKind.wait -> "own_app: Hoshino does not operate its own screens (allow it in 设置手机操作 if you really want this)"
        !allowSelf && step.kind == StepKind.open_app && step.app == ownPackage -> "own_app: Hoshino does not operate its own screens"
        else -> null
    }
}
