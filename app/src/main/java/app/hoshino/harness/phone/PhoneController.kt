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
import kotlinx.coroutines.flow.combine
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
    /** Floating-panel takeover: while active every agent action waits (AI paused) and only the user's input goes in. */
    val manual = ManualTakeover()
    private var beforeManual: PhoneTaskState.Status? = null
    private val shapePrefs = context.getSharedPreferences("phone_capsule", Context.MODE_PRIVATE)
    private val _orientationMode = MutableStateFlow(OrientationMode.of(shapePrefs.getString("orientation", null)))
    /** m2.9.2: 竖屏 / 横屏 / 跟随 App for the agent display (panel control, persisted). */
    val orientationMode: StateFlow<OrientationMode> = _orientationMode.asStateFlow()
    private val follower = OrientationFollower()
    private var followJob: Job? = null

    init {
        // m2.9: a foreground service (with its ongoing notice) lives exactly as long as the agent display.
        scope.launch {
            _display.map { it != null }.distinctUntilChanged().collect { exists ->
                PhoneTaskForeground.sync(context, exists)?.let { consent.audit("phone task foreground service not started: ${it.javaClass.simpleName}") }
            }
        }
        // User frame-rate setting (5–60 fps) follows the display as it is created and recreated.
        scope.launch { combine(_display, PhoneDisplayFps.of(context).fps) { d, fps -> d to fps }.collect { (d, fps) -> d?.userFps = fps } }
        // m2.9.2: a new display starts in the picked orientation; Follow-App watches the app in front while it exists.
        scope.launch {
            combine(_display, _orientationMode) { d, m -> d to m }.collect { (d, m) ->
                followJob?.cancel(); followJob = null; follower.reset()
                if (d == null) return@collect
                when (m) {
                    OrientationMode.Portrait -> applyOrientation(d, DisplayOrientation.Portrait)
                    OrientationMode.Landscape -> applyOrientation(d, DisplayOrientation.Landscape)
                    OrientationMode.FollowApp -> followJob = scope.launch { while (true) { delay(FOLLOW_MS); followApp(d) } }
                }
            }
        }
    }

    /** Panel pick. Fixed orientations apply at once; Follow-App applies from the next observation. */
    fun setOrientationMode(mode: OrientationMode) {
        shapePrefs.edit().putString("orientation", mode.key).apply()
        _orientationMode.value = mode
    }

    private fun applyOrientation(d: AgentDisplay, o: DisplayOrientation): Boolean {
        if (d.orientation == o) return true
        val (w, h) = OrientationPolicy.sizeFor(o, d.shortSide, d.longSide)
        val ok = d.resize(w, h)
        consent.audit("agent display ${o.key} ${w}x$h ok=$ok")
        if (!ok) _state.update { it.copy(reason = "后台屏横竖切换需要新版手机助手：到 设置 → 设置手机操作 重新连接助手后再试") }
        return ok
    }

    private suspend fun followApp(d: AgentDisplay) {
        if (_display.value !== d) return
        val shell = services.shell.value ?: return
        val pkg = runCatching { shell.topPackage(d.displayId) }.getOrNull()
        val cls = runCatching { shell.topActivity(d.displayId) }.getOrNull()
        val manifest = if (pkg != null && cls != null) runCatching {
            @Suppress("DEPRECATION") context.packageManager.getActivityInfo(android.content.ComponentName(pkg, if (cls.startsWith(".")) pkg + cls else cls), 0).screenOrientation
        }.getOrNull() else null
        val window = appWindowSize(d.displayId)?.let { (w, h) -> OrientationPolicy.fromWindow(w, h, d.width, d.height) }
        val target = OrientationPolicy.target(OrientationMode.FollowApp, d.orientation, OrientationPolicy.fromManifest(manifest), window)
        val change = follower.observe(d.orientation, target) ?: return
        // Never reshape the display under a running step: try again on the next observation.
        if (!lock.tryLock()) return
        try { applyOrientation(d, change) } finally { lock.unlock() }
    }

    /** Bounds of the largest application window on [displayId] (accessibility windows), or null. */
    private fun appWindowSize(displayId: Int): Pair<Int, Int>? {
        val svc = PhoneAccessibilityService.instance.value ?: return null
        return runCatching {
            svc.windowsOn(displayId).filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .map { android.graphics.Rect().also(it::getBoundsInScreen) }
                .maxByOrNull { it.width().toLong() * it.height() }?.let { it.width() to it.height() }
        }.getOrNull()
    }

    suspend fun handle(body: JSONObject): JSONObject = try {
        when (val action = body.optString("action")) {
            "status" -> status()
            "diagnose" -> diagnose()
            "observe" -> gated { observe(session(body), body.optBoolean("full", false)) }
            "screenshot" -> gated { screenshot(session(body)) }
            "steps" -> gated(staleAfterWait = session(body)) { steps(session(body), body) }
            // WP-4 (B-1): composite reads that loop on the phone and return one structured result.
            // find/collect scroll the agent display, so they wait behind a manual takeover like observe/steps.
            "query", "extract", "find", "collect" -> gated { read(session(body), action, body) }
            "grant" -> grant(session(body), body.getString("fingerprint"))
            "list_apps" -> listApps(body.optString("query"))
            // m2.9: lets the kernel's system_shell guard accept only commands that explicitly target the agent display.
            "display" -> JSONObject().put("ok", true).put("display", agentDisplayId() ?: JSONObject.NULL)
            "handoff" -> PhoneTakeover.of(context).request(session(body), body.optString("reason").take(300), this)
            "stop" -> { stop(session(body), "stopped by request"); JSONObject().put("ok", true) }
            "release" -> gated { release(session(body)); JSONObject().put("ok", true) }
            else -> error("unknown phone action '$action'")
        }
    } catch (e: PhoneUnavailable) {
        JSONObject().put("ok", false).put("error", e.message).put("code", e.code).put("setup", "设置 → 设置手机操作")
    }

    /**
     * Manual takeover gate: the agent's action waits while the user operates the display by hand. When it had to wait,
     * the hand-back sentence rides on the result (`user_takeover`). A step batch that waited is not run at all
     * ([staleAfterWait] = its session): its refs and plan were made for the screen before the user changed it.
     */
    private suspend fun gated(staleAfterWait: String? = null, run: suspend () -> JSONObject): JSONObject {
        val w = manual.awaitAiAllowed(ManualTakeover.GATE_TIMEOUT_MS)
        if (!w.allowed) return JSONObject().put("ok", false).put("code", "user_takeover")
            .put("error", "user_takeover: the user is operating the agent display by hand; nothing was done. Wait, then phone_observe again.")
        if (w.waited && staleAfterWait != null) {
            val out = JSONObject().put("ok", false).put("completed", false).put("results", JSONArray()).put("stopped_at", 0).put("reason", "user_takeover")
            manual.takeNote()?.let { out.put("user_takeover", it) }
            runCatching { reread(staleAfterWait) }.getOrNull()?.let { out.put("screen", it.optString("screen")).put("app", it.opt("app")) }
            return out
        }
        val out = run()
        manual.takeNote()?.let { out.put("user_takeover", it) }
        return out
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
            // The user took over mid-batch: this step is not done; the runner stops and re-reads (the note rides on the result).
            val w = manual.awaitAiAllowed(ManualTakeover.GATE_TIMEOUT_MS)
            if (w.waited || !w.allowed) return ActionOutcome(false, "user_takeover: the user operated the screen by hand before this step; it was NOT done. Read the new screen and decide again")
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
            // m2.9.2: the kernel marks sessions under the user-enabled no-approval mode; nothing then stops for the user.
            val bypass = if (body.optBoolean("no_approval", false)) NoApprovalBypass { why, pkg -> consent.audit("no-approval pass reason=$why pkg=${pkg ?: "?"}") } else null
            val runner = StepRunner(GuardedPort(s, sessionId), { fp -> consumeGrant(sessionId, fp) }, noApproval = bypass, stopRequested = { stops[sessionId] })
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

    /**
     * WP-4 composite reads. query/extract read the current screen once; find/collect scroll on the phone (each scroll goes
     * through [GuardedPort], so the lock and own-app guards apply) until done and come back once. Parameters are validated
     * before the screen is touched. The final screen becomes the session's last shown screen, so the next phone_observe
     * is a small diff and refs in the result stay valid.
     */
    private suspend fun read(sessionId: String, action: String, body: JSONObject): JSONObject {
        val query = if (action == "query") ElementQuery.parse(body) else null
        val extract = if (action == "extract") ExtractParams.parse(body) else null
        val find = if (action == "find") FindParams.parse(body) else null
        val collect = if (action == "collect") CollectParams.parse(body) else null
        return lock.withLock {
            val shell = requireReady()
            val scrolling = find != null || collect != null
            if (scrolling && locked()) throw PhoneUnavailable("locked", "手机锁屏中，解锁后再继续")
            val s = screenFor(shell)
            claim(sessionId)
            touchIdle()
            stops.remove(sessionId)
            val wake = if (scrolling) context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hoshino:phone-read").also { it.acquire(WAKE_MS) } else null
            if (scrolling) _state.update { it.copy(sessionId = sessionId, status = PhoneTaskState.Status.Working, reason = null) }
            try {
                val reader = ScreenReader(GuardedPort(s, sessionId)) { stops[sessionId] }
                val out = JSONObject().put("ok", true)
                val last: ScreenSnapshot = when {
                    query != null -> s.read().also { snap ->
                        val (ms, total) = ScreenQueries.query(snap, query)
                        out.put("total", total).put("shown", ms.size).put("screen", ScreenQueries.renderMatches(ms).ifEmpty { "no match" })
                    }
                    extract != null -> s.read().also { snap -> out.put("mode", extract.mode.name).put("screen", ScreenQueries.extract(snap, extract).ifEmpty { "(empty)" }) }
                    find != null -> reader.find(find).let { r ->
                        out.put("ok", r.matches.isNotEmpty()).put("found", r.matches.isNotEmpty()).put("total", r.total).put("scrolls", r.scrolls).put("stop", r.stop)
                        r.detail?.let { out.put("detail", it) }
                        if (r.matches.isNotEmpty()) out.put("screen", ScreenQueries.renderMatches(r.matches))
                        r.screen
                    }
                    else -> reader.collect(collect!!).let { r ->
                        out.put("items", r.rows.size).put("screens", r.screens).put("scrolls", r.scrolls).put("stop", r.stop)
                        r.detail?.let { out.put("detail", it) }
                        if (r.cursorLost) out.put("cursor_lost", true)
                        r.cursor?.let { out.put("cursor", it.encode()) }
                        out.put("screen", r.rows.indices.joinToString("\n") { i -> r.keys[i] + " " + r.rows[i].text }.ifEmpty { "(no rows)" })
                        r.screen
                    }
                }
                out.put("app", last.packageName ?: JSONObject.NULL)
                if (last.secure) out.put("secure", true).put("hint", "secure screen: use phone_handoff")
                SystemScreens.needsUser(last)?.let { n -> out.put("needs_user", JSONObject().put("reason", n.reason)) }
                shown[sessionId] = last
                _state.update { it.copy(sessionId = sessionId, app = last.packageName) }
                out
            } finally {
                if (scrolling) _state.update { if (it.status == PhoneTaskState.Status.Working) it.copy(status = PhoneTaskState.Status.Idle) else it }
                if (wake?.isHeld == true) wake.release()
            }
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

    // ---------- manual takeover from the floating panel ----------

    /** 「接管」: pauses the agent. False when there is no agent display or the agent's own handoff is pending. */
    fun beginManualTakeover(): Boolean {
        if (PhoneTakeover.of(context).pending.value != null) return false
        val d = _display.value ?: return false
        val app = runCatching { services.shell.value?.topPackage(d.displayId) }.getOrNull()
        if (!manual.begin(_state.value.sessionId ?: owner, app)) return false
        beforeManual = _state.value.status
        _state.update { it.copy(status = PhoneTaskState.Status.TakenOver, reason = "你在手动操作后台屏，AI 已暂停") }
        consent.audit("manual takeover begin pkg=${app ?: "?"}")
        return true
    }

    /** 「交还」: the waiting agent action continues and gets the returned sentence. */
    fun endManualTakeover(): String? {
        val app = _display.value?.let { d -> runCatching { services.shell.value?.topPackage(d.displayId) }.getOrNull() }
        val note = manual.end(app) ?: return null
        val back = beforeManual?.takeIf { it != PhoneTaskState.Status.TakenOver } ?: PhoneTaskState.Status.Idle
        beforeManual = null
        _state.update { if (it.status == PhoneTaskState.Status.TakenOver) it.copy(status = back, reason = null) else it }
        consent.audit("manual takeover end pkg=${app ?: "?"}")
        return note
    }

    /** One user input from the preview, injected into the agent display only while the user has taken over. */
    suspend fun manualInput(kind: ManualTakeover.Input, inject: suspend (IHoshinoPhoneShell, Int) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        if (manual.aiMayAct) return@withContext false
        val shell = services.shell.value ?: return@withContext false
        val id = agentDisplayId() ?: return@withContext false
        val ok = runCatching { inject(shell, id) }.getOrDefault(false)
        if (ok) manual.record(kind)
        ok
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
            .put("agent_display", _display.value?.let { d -> JSONObject().put("orientation", d.orientation.key).put("width", d.width).put("height", d.height).put("mode", _orientationMode.value.key).put("coordinates", "0..999 of the current orientation") } ?: JSONObject().put("mode", _orientationMode.value.key))
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
        /** Follow-App observation interval (two agreeing observations switch, see [OrientationFollower]). */
        const val FOLLOW_MS = 800L
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
