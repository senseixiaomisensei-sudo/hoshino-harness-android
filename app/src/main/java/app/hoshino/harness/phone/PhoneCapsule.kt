// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.hoshino.harness.core.ApprovalDecision
import app.hoshino.harness.core.approvalChoices
import app.hoshino.harness.core.Attachment
import app.hoshino.harness.core.AttachmentKind
import app.hoshino.harness.core.HarnessEvent
import app.hoshino.harness.core.Hoshino
import app.hoshino.harness.core.ReasoningEffort
import app.hoshino.harness.core.TurnOptions
import dev.hoshino.bridge.phone.IHoshinoPhoneShell
import dev.hoshino.bridge.phone.shell.ShellText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.hypot

/**
 * Floating ball (m2.9; was the capsule of CU1–CU5, RT18) drawn as an accessibility overlay (no "draw over other apps"
 * permission), plain Android views (no Compose runtime in the overlay window).
 *
 * Ball: the state ring (green = working, orange breathing = needs you, grey = idle) and the number of things waiting;
 * fades to 40 % after 3 s, can be dragged and snaps to the nearest edge (side and height ratio are remembered).
 * m2.8.1 rules kept: window add/remove is debounced ([CapsuleVisibility]) and the ball stays up for the whole task.
 *
 * Gestures (m2.9.2, [TapClassifier]) on the ball, the title bar and the panel border only: single tap = control panel
 * (stop, take over / hand back, frame rate, portrait / landscape / follow app, zoom reset, send screen), double tap =
 * minimize to the ball (rendering stops). The single tap waits for the double-tap timeout; a drag never clicks.
 *
 * The panel ([BallPanelModel]):
 *  - live picture of the agent display, zero-copy through [PreviewRelay] or the Bitmap fallback, both drawn through the
 *    same [PreviewTransform] the touches are mapped with; portrait ⇄ landscape follows the display ([AgentDisplay.shape]);
 *  - resizes from about ball size to nearly full screen (corner handle, 44 dp, or pinch), live LayoutParams updates,
 *    size remembered per orientation, with or without a takeover;
 *  - 自由操作 ([AutoTakeoverModel]): one-finger touches on the picture are injected at any time — the first touch takes
 *    over and pauses the agent ([ManualTakeover]), and a few seconds after the user stops the agent continues (told what
 *    changed in one sentence). 「接管」 keeps the takeover until 「交还」;
 *  - approvals / questions / the agent's own handoff (CU4) and the steer box (CU3/RT18).
 * Hidden while Hoshino is in front, while the screen is locked, and when there is nothing to show (unless "常驻胶囊").
 *
 * Panel dragging follows Mika's OverlayFrame rule (cap/phone/a11y/OverlayHost.kt): it only becomes a drag after the
 * finger passed the system touch slop, measured in screen coordinates so the window following the finger never jitters.
 */
object PhoneCapsule {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()
    private var host: Host? = null

    fun attach(service: PhoneAccessibilityService) {
        host?.dispose()
        host = Host(service).also { it.start() }
    }

    fun detach() {
        host?.dispose()
        host = null
        _visible.value = false
    }

    fun foregroundChanged(pkg: String) { host?.foreground(pkg) }

    /**
     * m2.9.4: in-app virtual screen surfaces (page / chat mini window) currently shown. While any is shown the floating
     * ball and its panel stay hidden, so the in-app screen never pops up the overlay on top of itself; a takeover made
     * in the app is not ended by that hiding.
     */
    private val _inApp = MutableStateFlow(0)
    val inApp: StateFlow<Int> = _inApp.asStateFlow()
    fun inAppShown() { _inApp.value = _inApp.value + 1; host?.foreground(null) }
    fun inAppHidden() { _inApp.value = (_inApp.value - 1).coerceAtLeast(0); host?.foreground(null) }

    private data class Approval(val sessionId: String, val id: String, val title: String, val command: String, val oneShot: Boolean)
    private data class RenderKey(val state: PhoneTaskState, val count: Int, val takeover: PhoneTakeover.Pending?, val approvalId: String?, val questionId: String?, val manual: Boolean)
    private data class Question(val sessionId: String, val id: String, val text: String, val options: List<String>)

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private class Host(private val service: PhoneAccessibilityService) {
        private val ctx: Context = service
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val wm = service.getSystemService(WindowManager::class.java)
        private val prefs = ctx.getSharedPreferences("phone_capsule", Context.MODE_PRIVATE)
        private val controller = PhoneController.of(ctx)
        private val approvals = MutableStateFlow<List<Approval>>(emptyList())
        private val questions = MutableStateFlow<List<Question>>(emptyList())
        private val foreground = MutableStateFlow<String?>(null)
        private val screenOn = MutableStateFlow(true)
        private val model = BallPanelModel()
        private var fadeJob: Job? = null
        private var breath: Job? = null
        private var hideJob: Job? = null
        private var tickJob: Job? = null
        private var frameJob: Job? = null
        private val visibility = CapsuleVisibility()
        private var lastNeedsYou = false
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val dp = ctx.resources.displayMetrics.density
        private fun px(v: Int) = (v * dp).toInt()

        // ---------- ball ----------
        private val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#F21B1B22")); setStroke(px(3), GREY) }
        private val badge = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER }
        private val dot = FrameLayout(ctx).apply {
            background = ring
            addView(TextView(ctx).apply { text = "H"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            addView(badge, FrameLayout.LayoutParams(px(18), px(18), Gravity.END or Gravity.TOP))
            contentDescription = "Hoshino 悬浮球"
        }
        private val dotParams = overlayParams(px(52), px(52)).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = ctx.resources.displayMetrics
            x = if (prefs.getBoolean("left", false)) 0 else dm.widthPixels - px(52)
            y = (prefs.getFloat("yRatio", 0.35f) * dm.heightPixels).toInt()
        }

        // ---------- panel ----------
        private val pad = px(10)
        private val title = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        private val elapsed = TextView(ctx).apply { setTextColor(Color.parseColor("#B3FFFFFF")); textSize = 12f; setPadding(px(6), 0, px(6), 0); maxLines = 1 }
        private val minimizeButton = TextView(ctx).apply {
            text = "—"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            contentDescription = "最小化为悬浮球"
            setOnClickListener { collapse() }
        }
        /** Title bar: drag = move, single tap = control panel, double tap = minimize (m2.9.2, [TapClassifier]). */
        private val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(HEADER_DP)
            addView(title, LinearLayout.LayoutParams(0, -2, 1f)); addView(elapsed)
            addView(minimizeButton, LinearLayout.LayoutParams(px(36), px(36)))
            contentDescription = "拖动移动面板；单击打开控制面板；双击收回悬浮球"
        }
        private val liveView = TextureView(ctx).apply { isOpaque = true }
        /** Bitmap fallback drawn through the same [PreviewTransform] as the touch mapping (ScaleType.MATRIX). */
        private val fallbackView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.MATRIX; visibility = View.GONE }
        private val placeholder = TextView(ctx).apply { text = "后台屏还没打开"; setTextColor(Color.GRAY); textSize = 12f; gravity = Gravity.CENTER }
        private val takeoverFrame = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(px(3), ORANGE) }
        private val frameOverlay = View(ctx).apply { background = takeoverFrame; visibility = View.GONE }
        /** Always-visible corner handle: ≥ 44 dp touch area, 28 dp visible mark (m2.9.2; was a dim 32 dp glyph). */
        private val resizeHandle = FrameLayout(ctx).apply {
            addView(TextView(ctx).apply {
                text = "⤡"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = px(8).toFloat(); setColor(Color.parseColor("#CC2F5BEA")); setStroke(px(1), Color.WHITE) }
            }, FrameLayout.LayoutParams(px(28), px(28), Gravity.END or Gravity.BOTTOM).apply { rightMargin = px(4); bottomMargin = px(4) })
            contentDescription = "拖动调整预览大小（也可双指捏合）"
            elevation = px(4).toFloat()
        }
        private val previewBox = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            addView(placeholder, FrameLayout.LayoutParams(-1, -1))
            addView(liveView, FrameLayout.LayoutParams(-1, -1))
            addView(fallbackView, FrameLayout.LayoutParams(-1, -1))
            addView(frameOverlay, FrameLayout.LayoutParams(-1, -1))
            addView(resizeHandle, FrameLayout.LayoutParams(px(HANDLE_DP), px(HANDLE_DP), Gravity.END or Gravity.BOTTOM))
        }
        private val fpsLabel = TextView(ctx).apply { setTextColor(Color.parseColor("#80FFFFFF")); textSize = 10f }
        private val current = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 13f }
        private val steps = TextView(ctx).apply { setTextColor(Color.parseColor("#CCFFFFFF")); textSize = 12f }
        private val reason = TextView(ctx).apply { setTextColor(Color.parseColor("#FFFFB74D")); textSize = 13f }
        // Clickable so taps on their text are not taken as taps on the panel border (which toggle the control panel).
        private val actions = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; isClickable = true }
        private val input = editText("插话 / 回答（发给当前任务）").apply { imeOptions = EditorInfo.IME_ACTION_SEND }
        private val steerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f)); addView(button("发送") { submit() })
        }
        private val takeInput = editText("输入到后台屏的文字").apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        private val takeoverHint = label("你在操作：直接在画面上点按 / 长按 / 滑动，AI 已暂停").apply { setTextColor(ORANGE) }
        private val takeoverBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(takeoverHint)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(takeInput, LinearLayout.LayoutParams(0, -2, 1f)); addView(button("输入") { sendTakeoverText() })
            })
            addView(row(button("返回") { sendKey(KeyEvent.KEYCODE_BACK) }, button("回车") { sendKey(KeyEvent.KEYCODE_ENTER) }, button("删除") { sendKey(KeyEvent.KEYCODE_DEL) }))
        }
        private val takeButton = button("接管") { toggleTakeover() }
        private val orientationButtons = OrientationMode.entries.associateWith { m -> button(m.label) { controller.setOrientationMode(m) } }
        private val fpsValue = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER }
        /** The control panel (single tap on the ball / title bar): stop, take over / hand back, fps, orientation, zoom reset. */
        private val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; isClickable = true
            addView(fpsLabel)
            addView(current); addView(steps); addView(reason)
            addView(row(*orientationButtons.values.toTypedArray()))
            addView(row(button("帧率 −") { stepFps(-FpsMath.STEP) }, fpsValue, button("帧率 +") { stepFps(FpsMath.STEP) }))
            addView(row(button("停止") { stopTask() }, takeButton, button("缩放复位") { resetZoom() }))
            addView(takeoverBar); addView(steerRow)
            addView(row(button("发此刻屏幕") { sendScreen() }))
        }
        private val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, px(6), pad, pad)
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(Color.parseColor("#F2202027")) }
            addView(header)
            addView(previewBox, LinearLayout.LayoutParams(px(DEFAULT_PREVIEW_DP), px(DEFAULT_PREVIEW_DP) * 2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(4) })
            addView(actions)
            addView(controls)
        }
        private val cardParams = overlayParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        }
        private var previewW = savedPreviewW(DisplayOrientation.Portrait)
        private var dotAdded = false
        private var cardAdded = false
        private var controlsShown = false
        private val autoTakeover = AutoTakeoverModel()
        private var autoJob: Job? = null
        private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

        /** Which display the preview is bound to, and whether through Bitmaps (fallback) or the zero-copy relay. */
        private var bound: AgentDisplay? = null
        private var boundShape: Pair<Int, Int>? = null
        private var usingFallback = false
        private var liveSurface: Surface? = null
        private var lastFrames = 0L
        private var lastFramesAt = 0L
        private var lastRate = 0L
        private var lastBitmap: Bitmap? = null

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { screenOn.value = i.action != Intent.ACTION_SCREEN_OFF }
        }

        fun start() {
            ctx.registerReceiver(screenReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON) })
            installDrag()
            installPanelDrag()
            installPreviewTouch()
            installResize()
            fallbackView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyFallbackMatrix() }
            liveView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    val s = Surface(st)
                    liveSurface = s
                    val d = bound
                    if (d != null && !usingFallback && !d.attachPreview(s)) useFallback(d)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    liveSurface?.let { s -> if (!usingFallback) bound?.detachPreview(s); s.release() }
                    liveSurface = null
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
            for (field in listOf(input, takeInput)) field.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) focusable(true); false }
            input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
            takeInput.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { sendTakeoverText(); true } else false }
            scope.launch { runCatching { Hoshino.client.events.collect(::onEvent) } }
            scope.launch {
                // Visibility is driven separately (debounced); render only when what is drawn changes.
                combine(controller.state, approvals, questions, PhoneTakeover.of(ctx).pending, controller.manual.active) { s, a, q, t, m ->
                    RenderKey(s, a.size + q.size + (if (t != null) 1 else 0), t, a.firstOrNull()?.id, q.firstOrNull()?.id, m != null)
                }.distinctUntilChanged().collect { k -> render(k.state, k.count, k.takeover, k.manual) }
            }
            scope.launch {
                combine(controller.state, approvals, questions, PhoneTakeover.of(ctx).pending, foreground) { _, _, _, _, _ -> Unit }
                    .collect { refreshVisibility() }
            }
            scope.launch {
                controller.manual.active.collect { m ->
                    if (m == null) { autoTakeover.ended(); autoJob?.cancel(); autoJob = null }
                    else autoTakeover.startedElsewhere(System.currentTimeMillis())
                    model.takeoverChanged(m != null)
                    if (m != null && dotAdded && !cardAdded) expand()
                    showTakeover(m != null)
                    refreshVisibility()
                }
            }
            scope.launch { controller.orientationMode.collect { m -> orientationButtons.forEach { (k, b) -> b.alpha = if (k == m) 1f else 0.55f } } }
            scope.launch { PhoneDisplayFps.of(ctx).fps.collect { fpsValue.text = "$it fps" } }
            scope.launch { runCatching { Hoshino.client.sessions.collect { refreshVisibility() } } }
            scope.launch { screenOn.collect { refreshVisibility() } }
            scope.launch { PhoneConsent.of(ctx).state.collect { refreshVisibility() } }
            // m2.9.2: rebind (and resize the window to the new aspect) whenever the display turns portrait ⇄ landscape.
            scope.launch {
                var shapeJob: Job? = null
                controller.display.collect { d ->
                    shapeJob?.cancel()
                    bindPreview()
                    shapeJob = d?.let { disp -> scope.launch { disp.shape.collect { shapeChanged(disp) } } }
                }
            }
        }

        fun foreground(pkg: String?) { if (pkg != null) foreground.value = pkg; refreshVisibility() }

        private fun onEvent(e: HarnessEvent) {
            when (e) {
                is HarnessEvent.ApprovalRequested -> approvals.value = approvals.value + Approval(e.sessionId, e.id, e.title, e.command, e.oneShot)
                is HarnessEvent.ApprovalResolved -> approvals.value = approvals.value.filterNot { it.id == e.id }
                is HarnessEvent.QuestionAsked -> questions.value = questions.value + Question(e.sessionId, e.id, e.question, e.options.map { it.label })
                is HarnessEvent.QuestionAnswered -> questions.value = questions.value.filterNot { it.id == e.id }
                else -> Unit
            }
        }

        private fun turnRunning(): Boolean {
            val sid = controller.state.value.sessionId ?: return false
            return runCatching { Hoshino.client.sessions.value.firstOrNull { it.id == sid }?.running == true }.getOrDefault(false)
        }

        private fun ownApp(): Boolean = _inApp.value > 0 || foreground.value == ctx.packageName

        /** (want, hideImmediately). Screen off / locked hide at once; everything else goes through the debounce. */
        private fun wanted(): Pair<Boolean, Boolean> {
            val keyguard = runCatching { ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)
            if (!screenOn.value || keyguard) return false to true
            val manual = controller.manual.active.value != null
            // m2.9.4: inside Hoshino (or while an in-app virtual screen is shown) the overlay never appears, even during a takeover.
            if (ownApp()) return false to true
            if (foreground.value == ctx.packageName && !manual) return false to false
            val s = controller.state.value
            // The controller drops to Idle between steps; while its session's turn is still running the task is on.
            val busy = s.status != PhoneTaskState.Status.Idle || turnRunning() || approvals.value.isNotEmpty() || questions.value.isNotEmpty() || PhoneTakeover.of(ctx).pending.value != null || manual
            // An open panel is never pulled away from under the user's finger.
            return (busy || cardAdded || PhoneConsent.of(ctx).state.value.capsuleAlways) to false
        }

        /** The ball window is added once and removed once per debounced change; state only updates its content. */
        private fun refreshVisibility() {
            val (want, immediate) = wanted()
            val d = visibility.update(want, System.currentTimeMillis(), immediate)
            hideJob?.cancel(); hideJob = null
            d.recheckAt?.let { at -> hideJob = scope.launch { delay((at - System.currentTimeMillis()).coerceAtLeast(0) + 16); refreshVisibility() } }
            if (d.changed) {
                if (d.shown && !dotAdded) { runCatching { wm.addView(dot, dotParams); dotAdded = true }; scheduleFade() }
                if (!d.shown) {
                    // Screen off / locked: nobody can operate the picture any more, so hand back before hiding.
                    val own = ownApp()
                    if (!own && controller.manual.active.value != null) controller.endManualTakeover()
                    if (immediate && !own) model.screenOff()
                    closePanel()
                    if (dotAdded) { runCatching { wm.removeView(dot) }; dotAdded = false }
                }
            } else if (d.shown && !dotAdded) {
                runCatching { wm.addView(dot, dotParams); dotAdded = true }
            }
            model.visibility(dotAdded)
            _visible.value = dotAdded
            bindPreview()
        }

        private fun render(s: PhoneTaskState, count: Int, takeover: PhoneTakeover.Pending?, manual: Boolean) {
            val needsYou = count > 0 || s.status == PhoneTaskState.Status.NeedsUser || s.status == PhoneTaskState.Status.Approval || takeover != null
            ring.setStroke(px(3), when { needsYou || manual -> ORANGE; s.status == PhoneTaskState.Status.Working -> GREEN; else -> GREY })
            badge.text = if (count > 0) count.toString() else ""
            badge.background = if (count > 0) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ORANGE) } else null
            breathe(needsYou)
            // Only wake the ball when it starts needing you; re-flashing on every emission was the "blinking".
            if (needsYou && !lastNeedsYou) scheduleFade()
            lastNeedsYou = needsYou
            model.taskActivity(s.status != PhoneTaskState.Status.Idle || turnRunning(), System.currentTimeMillis())
            title.text = when (s.status) {
                PhoneTaskState.Status.Working -> "正在操作 " + (s.app ?: "")
                PhoneTaskState.Status.NeedsUser -> "需要你处理"
                PhoneTaskState.Status.Approval -> "等你同意"
                PhoneTaskState.Status.TakenOver -> if (manual) "你在操作 · AI 已暂停" else "交给你了"
                PhoneTaskState.Status.Idle -> if (count > 0) "共 $count 件事" else if (turnRunning()) "思考中 " + (s.app ?: "") else "Hoshino"
            } + if (count > 1) " · 共 $count 件" else ""
            current.text = "当前：" + (s.recent.lastOrNull() ?: if (turnRunning()) "等待模型下一步" else "无")
            steps.text = s.recent.dropLast(1).reversed().joinToString("\n") { "· $it" }
            steps.visibility = if (s.recent.size > 1) View.VISIBLE else View.GONE
            reason.text = s.reason.orEmpty()
            reason.visibility = if (s.reason.isNullOrBlank()) View.GONE else View.VISIBLE
            takeButton.text = when { !manual -> "接管"; autoTakeover.mode == AutoTakeoverModel.Mode.Auto -> "保持接管"; else -> "交还" }
            takeButton.isEnabled = manual || (takeover == null && controller.display.value != null)
            tick()
            actions.removeAllViews()
            // Only the most urgent thing gets buttons (CU4): takeover, then approval, then question.
            if (takeover != null) {
                actions.addView(label("这一步要你来：" + takeover.reason.ifBlank { "请在 App 里完成" }))
                actions.addView(row(
                    if (!takeover.movedToMain) button("我来处理") { PhoneTakeover.of(ctx).choose(PhoneTakeover.Choice.Take) } else null,
                    button("处理好了") { PhoneTakeover.of(ctx).choose(PhoneTakeover.Choice.Done) },
                    button("跳过") { PhoneTakeover.of(ctx).choose(PhoneTakeover.Choice.Skip) },
                ))
            } else approvals.value.firstOrNull()?.let { a ->
                actions.addView(label("${a.title}：${a.command}".take(240)))
                // Same choices and labels as the chat approval card and the notification (approvalChoices).
                actions.addView(row(*approvalChoices(a.oneShot).map { d -> button(d.label) { respond(a, d) } }.toTypedArray()))
            } ?: questions.value.firstOrNull()?.let { q ->
                actions.addView(label(q.text.take(300)))
                if (q.options.isNotEmpty()) actions.addView(row(*q.options.take(4).map { o -> button(o.take(12)) { answer(q, o) } }.toTypedArray()))
            }
        }

        /** Elapsed time and the measured preview rate (zero-copy frames drawn per second). */
        private fun tick() {
            val now = System.currentTimeMillis()
            elapsed.text = model.elapsedMs(now)?.let { BallPanelModel.formatElapsed(it) }.orEmpty()
            val d = bound
            fpsLabel.text = when {
                d == null -> ""
                usingFallback -> "兼容预览 · 上限 ${PhoneDisplayFps.of(ctx).fps.value} fps"
                else -> {
                    // Measured over >= ~1 s windows only (render() also calls tick, at arbitrary moments).
                    if (lastFramesAt == 0L || now - lastFramesAt >= 900) {
                        val frames = d.previewFrames
                        if (lastFramesAt > 0) lastRate = (frames - lastFrames) * 1000 / (now - lastFramesAt)
                        lastFrames = frames; lastFramesAt = now
                    }
                    "零拷贝预览 · ${lastRate} fps（上限 ${PhoneDisplayFps.of(ctx).fps.value}，画面不动时不重画）"
                }
            }
        }

        private fun showTakeover(on: Boolean) {
            takeoverBar.visibility = if (on) View.VISIBLE else View.GONE
            steerRow.visibility = if (on) View.GONE else View.VISIBLE
            frameOverlay.visibility = if (on) View.VISIBLE else View.GONE
            if (!on) takeInput.setText("")
        }

        /** 「接管」 makes the takeover sticky (no automatic hand-back); 「交还」 ends it. */
        private fun toggleTakeover() {
            when (autoTakeover.button(System.currentTimeMillis())) {
                AutoTakeoverModel.Action.Begin -> if (!controller.beginManualTakeover()) { autoTakeover.beginFailed(); toast(takeoverRefusal()) }
                AutoTakeoverModel.Action.End -> { controller.endManualTakeover(); focusable(false); toast("已交还，AI 继续（已告诉它你改了什么）") }
                AutoTakeoverModel.Action.None -> toast("已保持接管：点「交还」前 AI 不会继续")
            }
            render(controller.state.value, approvals.value.size + questions.value.size, PhoneTakeover.of(ctx).pending.value, controller.manual.active.value != null)
        }

        private fun takeoverRefusal() = if (PhoneTakeover.of(ctx).pending.value != null) "AI 正在请你处理一步，用上面的按钮" else "后台屏还没打开，暂时不能操作"

        /**
         * 「自由操作」: a touch on the picture takes over at once (AI paused). Returns false when it cannot (no display,
         * the agent's own hand-over pending); then nothing is injected.
         */
        private fun touchTakeover(now: Long): Boolean {
            if (autoTakeover.touchDown(now) == AutoTakeoverModel.Action.Begin && !controller.beginManualTakeover()) {
                autoTakeover.beginFailed(); toast(takeoverRefusal()); return false
            }
            autoJob?.cancel(); autoJob = null
            val active = controller.manual.active.value != null
            if (!active && now - lastTakeoverHint > 3_000) { lastTakeoverHint = now; toast("先点「接管」才能操作虚拟屏") }
            return active
        }
        private var lastTakeoverHint = 0L

        private fun touchReleased(now: Long) {
            autoTakeover.touchUp(now)
            scheduleAutoReturn()
        }

        /** Hands the display back to the AI after the idle time of an automatic takeover. */
        private fun scheduleAutoReturn() {
            autoJob?.cancel(); autoJob = null
            val at = autoTakeover.nextCheckAt() ?: return
            autoJob = scope.launch {
                delay((at - System.currentTimeMillis()).coerceAtLeast(0) + 16)
                if (autoTakeover.tick(System.currentTimeMillis()) == AutoTakeoverModel.Action.End) {
                    controller.endManualTakeover(); focusable(false); toast("你停下了，AI 继续")
                } else scheduleAutoReturn()
            }
        }

        private fun ensureTakeover(): Boolean {
            val now = System.currentTimeMillis()
            if (controller.manual.active.value != null) { autoTakeover.activity(now); scheduleAutoReturn(); return true }
            val ok = touchTakeover(now)
            touchReleased(now)
            return ok
        }

        // ---------- user input while taken over ----------

        private fun inject(kind: ManualTakeover.Input, call: suspend (IHoshinoPhoneShell, Int) -> Boolean) {
            scope.launch { if (!controller.manualInput(kind, call)) toast("没有送达后台屏") }
        }

        private fun sendKey(code: Int) { if (ensureTakeover()) inject(ManualTakeover.Input.Key) { sh, id -> sh.injectKey(id, code) } }

        /** Printable text key by key; anything else (e.g. Chinese) through the helper's borrowed clipboard + Ctrl+V. */
        private fun sendTakeoverText() {
            val text = takeInput.text?.toString().orEmpty()
            if (text.isEmpty() || !ensureTakeover()) return
            takeInput.setText("")
            inject(ManualTakeover.Input.Text) { sh, id ->
                if (ShellText.typeable(text) && sh.injectText(id, text) == ShellText.TEXT_TYPED) true
                else if (text.length > ShellText.MAX_CHARS || sh.setClipText(text) != 1) false
                else try { sh.injectCtrlKey(id, KeyEvent.KEYCODE_V).also { delay(PASTE_SETTLE_MS) } } finally { runCatching { sh.restoreClip() } }
            }
        }

        private val strokeX = ArrayList<Int>()
        private val strokeY = ArrayList<Int>()
        private val strokeT = ArrayList<Long>()
        private var strokeDown = 0L
        private var strokeMoved = 0f
        private var strokeStartX = 0f
        private var strokeStartY = 0f
        private var stroking = false
        private var pinching = false
        private var pinchStartW = 0
        private var pinchScale = 1f

        /**
         * Picture touches. One finger: operates the agent display at any time (自由操作 takes over automatically on the
         * first touch); two fingers: pinch to resize, never injected. Mapping goes through [PreviewTransform], the same
         * transform the picture is drawn with.
         */
        private fun installPreviewTouch() {
            val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pinching = true; stroking = false; pinchStartW = previewW; pinchScale = 1f; return true }
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    pinchScale *= d.scaleFactor
                    val (sw, sh) = sourceSize()
                    applyPreviewSize(PreviewGeometry.pinch(pinchStartW, pinchScale, sw, sh, limits()))
                    autoTakeover.activity(System.currentTimeMillis())
                    return true
                }
                override fun onScaleEnd(d: ScaleGestureDetector) { savePanel() }
            })
            previewBox.setOnTouchListener { _, e ->
                scale.onTouchEvent(e)
                val disp = controller.display.value
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pinching = false
                        stroking = false
                        val hit = disp?.let { transform(it).toDisplay(e.x, e.y) }
                        if (hit != null && touchTakeover(System.currentTimeMillis())) {
                            strokeX.clear(); strokeY.clear(); strokeT.clear()
                            strokeDown = e.eventTime; strokeMoved = 0f; strokeStartX = e.x; strokeStartY = e.y
                            strokeX += hit.first; strokeY += hit.second; strokeT += 0L
                            stroking = true
                        } else if (hit != null) autoTakeover.touchUp(System.currentTimeMillis())
                        touchCounted = hit != null
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> stroking = false
                    MotionEvent.ACTION_MOVE -> if (stroking && !pinching && disp != null) {
                        for (h in 0 until e.historySize) addPoint(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalEventTime(h), disp)
                        addPoint(e.x, e.y, e.eventTime, disp)
                    }
                    MotionEvent.ACTION_UP -> {
                        if (stroking && !pinching && disp != null) { addPoint(e.x, e.y, e.eventTime, disp); finishStroke(e.eventTime - strokeDown) }
                        stroking = false
                        if (touchCounted) { touchCounted = false; touchReleased(System.currentTimeMillis()) }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        stroking = false
                        if (touchCounted) { touchCounted = false; touchReleased(System.currentTimeMillis()) }
                    }
                }
                true
            }
        }

        private var touchCounted = false

        private fun transform(d: AgentDisplay) = PreviewTransform(d.width, d.height, previewBox.width, previewBox.height)

        private fun addPoint(vx: Float, vy: Float, time: Long, disp: AgentDisplay) {
            strokeMoved = maxOf(strokeMoved, hypot(vx - strokeStartX, vy - strokeStartY))
            val (x, y) = transform(disp).toDisplayClamped(vx, vy)
            strokeX += x; strokeY += y; strokeT += time - strokeDown
        }

        private fun finishStroke(durationMs: Long) {
            stroking = false
            val x0 = strokeX.first(); val y0 = strokeY.first()
            when (PreviewGeometry.classify(strokeMoved, durationMs, touchSlop.toFloat())) {
                PreviewGeometry.Gesture.Tap -> inject(ManualTakeover.Input.Tap) { sh, id -> sh.injectTap(id, x0, y0) }
                PreviewGeometry.Gesture.LongPress -> {
                    val hold = durationMs.coerceIn(PreviewGeometry.LONG_PRESS_MS, PreviewGeometry.MAX_STROKE_MS)
                    inject(ManualTakeover.Input.LongPress) { sh, id -> sh.injectStroke(id, intArrayOf(x0, x0), intArrayOf(y0, y0), longArrayOf(0, hold)) }
                }
                PreviewGeometry.Gesture.Swipe -> {
                    val (xs, ys, ts) = PreviewGeometry.thinStroke(strokeX.toIntArray(), strokeY.toIntArray(), strokeT.toLongArray())
                    inject(ManualTakeover.Input.Swipe) { sh, id -> sh.injectStroke(id, xs, ys, ts) }
                }
            }
        }

        // ---------- panel size and position ----------

        private fun sourceSize(): Pair<Int, Int> {
            val disp = controller.display.value
            return if (disp != null) disp.width to disp.height
            else ctx.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) to maxOf(it.widthPixels, it.heightPixels) }
        }

        private fun currentOrientation(): DisplayOrientation = sourceSize().let { (w, h) -> OrientationPolicy.of(w, h) }

        private fun prefKey(o: DisplayOrientation) = "previewW_" + o.key

        private fun savedPreviewW(o: DisplayOrientation): Int =
            prefs.getInt(prefKey(o), if (o == DisplayOrientation.Portrait) prefs.getInt("previewW", px(DEFAULT_PREVIEW_DP)) else px(DEFAULT_LANDSCAPE_DP))

        /**
         * From about ball size up to nearly the whole screen. Only the title bar (and the control panel or a pending
         * question, when shown) is subtracted — before m2.9.2 a fixed ~300 dp of process lines left almost no range.
         */
        private fun limits(): PreviewGeometry.Limits {
            val dm = ctx.resources.displayMetrics
            val extra = (if (controlsShown) controls.height.takeIf { it > 0 } ?: px(CONTROLS_DP) else 0) + (if (actions.childCount > 0) actions.height else 0)
            val chromeH = px(HEADER_DP) + px(6) + pad + px(4) + px(SCREEN_MARGIN_DP) + extra
            return PreviewGeometry.limitsFor(dm.widthPixels, dm.heightPixels, 2 * pad + px(SCREEN_MARGIN_DP), chromeH, px(MIN_PREVIEW_DP))
        }

        /** Updates the picture box and the window's LayoutParams in place (live while dragging / pinching). */
        private fun applyPreviewSize(width: Int) {
            val (sw, sh) = sourceSize()
            previewW = PreviewGeometry.clampWidth(width, sw, sh, limits())
            val lp = previewBox.layoutParams as LinearLayout.LayoutParams
            val h = PreviewGeometry.heightFor(previewW, sw, sh)
            if (lp.width != previewW || lp.height != h) {
                lp.width = previewW
                lp.height = h
                previewBox.layoutParams = lp
            }
            cardParams.width = previewW + 2 * pad
            elapsed.visibility = if (previewW < px(COMPACT_DP)) View.GONE else View.VISIBLE
            if (cardAdded) runCatching { wm.updateViewLayout(card, cardParams) }
            applyFallbackMatrix()
        }

        private fun resetZoom() {
            prefs.edit().remove(prefKey(currentOrientation())).apply()
            applyPreviewSize(if (currentOrientation() == DisplayOrientation.Portrait) px(DEFAULT_PREVIEW_DP) else px(DEFAULT_LANDSCAPE_DP))
            savePanel()
        }

        private fun stepFps(delta: Int) {
            val fps = PhoneDisplayFps.of(ctx)
            fps.set(fps.fps.value + delta)
        }

        private fun showControls(on: Boolean) {
            controlsShown = on
            controls.visibility = if (on) View.VISIBLE else View.GONE
            if (on) focusable(false)
            // Re-fit: the controls take room; the saved size comes back when they are folded away again.
            applyPreviewSize(savedPreviewW(currentOrientation()))
            card.post { clampPanel() }
        }

        private fun placePanel() {
            val dm = ctx.resources.displayMetrics
            val w = cardParams.width.takeIf { it > 0 } ?: px(DEFAULT_PREVIEW_DP)
            val savedX = prefs.getInt("panelX", -1)
            val savedY = prefs.getInt("panelY", -1)
            cardParams.x = if (savedX >= 0) savedX else (dm.widthPixels - w) / 2
            cardParams.y = if (savedY >= 0) savedY else dm.heightPixels / 8
            clampPanel()
        }

        private fun clampPanel() {
            val dm = ctx.resources.displayMetrics
            val h = card.height.takeIf { it > 0 } ?: (previewBox.layoutParams.height + px(HEADER_DP) + 2 * pad)
            val (x, y) = PreviewGeometry.clampPosition(cardParams.x, cardParams.y, cardParams.width.coerceAtLeast(1), h, dm.widthPixels, dm.heightPixels)
            cardParams.x = x; cardParams.y = y
            if (cardAdded) runCatching { wm.updateViewLayout(card, cardParams) }
        }

        private fun savePanel() {
            clampPanel()
            val e = prefs.edit().putInt("panelX", cardParams.x).putInt("panelY", cardParams.y)
            // The size is remembered per orientation, and only the size the user chose without the controls open.
            if (!controlsShown) e.putInt(prefKey(currentOrientation()), previewW)
            e.apply()
        }

        /**
         * Title bar and border (the card's padding): drag moves the panel (Mika OverlayFrame rule: only past the touch
         * slop, deltas in screen coordinates); single tap toggles the control panel; double tap minimizes to the ball.
         * The single tap waits for the double-tap timeout, so a double tap never opens the controls first.
         */
        private fun installPanelDrag() {
            val taps = TapClassifier(touchSlop.toFloat(), doubleTapTimeout)
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
            var pending: Job? = null
            val listener = View.OnTouchListener { _, e ->
                val now = e.eventTime
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { pending?.cancel(); taps.down(now, e.rawX, e.rawY); downX = e.rawX; downY = e.rawY; startX = cardParams.x; startY = cardParams.y }
                    MotionEvent.ACTION_MOVE -> if (taps.move(e.rawX, e.rawY)) {
                        cardParams.x = startX + (e.rawX - downX).toInt(); cardParams.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(card, cardParams) }
                    }
                    MotionEvent.ACTION_UP -> {
                        val wasDrag = taps.dragging
                        when (taps.up(now)) {
                            TapClassifier.Click.Double -> collapse()
                            else -> taps.pendingUntil?.let { at ->
                                pending = scope.launch {
                                    delay((at - now).coerceAtLeast(0) + 8)
                                    if (taps.flush(at) == TapClassifier.Click.Single) showControls(!controlsShown)
                                }
                            }
                        }
                        if (wasDrag) savePanel()
                    }
                    MotionEvent.ACTION_CANCEL -> { if (taps.dragging) savePanel(); taps.cancel() }
                }
                true
            }
            header.setOnTouchListener(listener)
            card.setOnTouchListener(listener)
        }

        private fun installResize() {
            var downX = 0f; var downY = 0f; var startW = 0
            resizeHandle.setOnTouchListener { v, e ->
                val (sw, sh) = sourceSize()
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.parent?.requestDisallowInterceptTouchEvent(true); downX = e.rawX; downY = e.rawY; startW = previewW }
                    MotionEvent.ACTION_MOVE -> applyPreviewSize(PreviewGeometry.resizeByCorner(startW, e.rawX - downX, e.rawY - downY, sw, sh, limits()))
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePanel()
                }
                true
            }
        }

        // ---------- preview binding: rendering only while the panel is open ----------

        private fun bindPreview() {
            val d = controller.display.value
            val want = model.rendering && cardAdded && d != null
            if (!want) {
                unbindPreview()
                placeholder.text = if (d == null) "后台屏还没打开（任务开始后出现）" else ""
                return
            }
            if (bound === d && boundShape == d!!.shape.value) return
            unbindPreview()
            bound = d
            boundShape = d!!.shape.value
            placeholder.text = ""
            applyPreviewSize(savedPreviewW(d.orientation))
            if (d.zeroCopy) {
                usingFallback = false
                liveView.visibility = View.VISIBLE
                fallbackView.visibility = View.GONE
                liveSurface?.let { s -> if (!d.attachPreview(s)) useFallback(d) }
            } else useFallback(d)
        }

        /** The display turned portrait ⇄ landscape: new aspect, new saved size, rebind (the relay may be gone). */
        private fun shapeChanged(d: AgentDisplay) {
            if (bound === d && boundShape != d.shape.value) { unbindPreview(); bindPreview() }
            else if (bound == null && cardAdded) applyPreviewSize(savedPreviewW(d.orientation))
            card.post { clampPanel() }
        }

        private fun useFallback(d: AgentDisplay) {
            liveSurface?.let { s -> if (!usingFallback) d.detachPreview(s) }
            usingFallback = true
            liveView.visibility = View.GONE
            fallbackView.visibility = View.VISIBLE
            d.previewWatchers++
            d.previewNow()?.let(::showFrame)
            frameJob?.cancel()
            frameJob = scope.launch { d.frames.collect { b -> if (b != null) showFrame(b) } }
        }

        private fun showFrame(b: Bitmap) {
            lastBitmap = b
            fallbackView.setImageBitmap(b)
            applyFallbackMatrix()
        }

        /** Draws the preview Bitmap into exactly the rectangle the touches are mapped through ([PreviewTransform]). */
        private fun applyFallbackMatrix() {
            val b = lastBitmap ?: return
            val d = controller.display.value ?: return
            if (fallbackView.width <= 0 || fallbackView.height <= 0) return
            val t = PreviewTransform(d.width, d.height, fallbackView.width, fallbackView.height)
            fallbackView.imageMatrix = android.graphics.Matrix().apply { setValues(t.bitmapMatrix(b.width, b.height)) }
        }

        private fun unbindPreview() {
            val d = bound ?: return
            frameJob?.cancel(); frameJob = null
            if (usingFallback) d.previewWatchers = (d.previewWatchers - 1).coerceAtLeast(0)
            else liveSurface?.let { d.detachPreview(it) }
            usingFallback = false
            bound = null
            boundShape = null
            lastBitmap = null
            lastFrames = 0; lastFramesAt = 0; lastRate = 0
        }

        // ---------- expand / collapse ----------

        private fun expand(withControls: Boolean = false) {
            if (cardAdded) { if (withControls && !controlsShown) showControls(true); return }
            if (model.mode == BallPanelModel.Mode.Ball) model.tapBall()
            if (model.mode != BallPanelModel.Mode.Panel) return
            controlsShown = withControls
            controls.visibility = if (withControls) View.VISIBLE else View.GONE
            applyPreviewSize(savedPreviewW(currentOrientation()))
            placePanel()
            runCatching { wm.addView(card, cardParams); cardAdded = true }
            fadeJob?.cancel(); dot.alpha = 1f
            bindPreview()
            tickJob?.cancel()
            tickJob = scope.launch { while (isActive) { tick(); delay(1_000) } }
            card.post { clampPanel() }
        }

        /**
         * Minimize to the ball (double tap / 「—」): stops rendering. An automatic takeover (自由操作) is handed back first;
         * a takeover the user made sticky with 「接管」 is refused (the picture is the only way to operate).
         */
        private fun collapse() {
            if (autoTakeover.mode == AutoTakeoverModel.Mode.Auto && controller.manual.active.value != null) {
                controller.endManualTakeover()
                model.takeoverChanged(false)
            }
            if (!model.collapse()) { toast("接管中不能收起，先点「交还」"); return }
            closePanel()
            refreshVisibility()
        }

        private fun closePanel() {
            focusable(false)
            tickJob?.cancel(); tickJob = null
            unbindPreview()
            if (cardAdded) { runCatching { wm.removeView(card) }; cardAdded = false }
            model.takeoverChanged(controller.manual.active.value != null)
            if (model.mode == BallPanelModel.Mode.Panel) model.collapse()
            scheduleFade()
        }

        private fun respond(a: Approval, d: ApprovalDecision) {
            approvals.value = approvals.value.filterNot { it.id == a.id }
            scope.launch(Dispatchers.IO) { runCatching { Hoshino.client.respondApproval(a.sessionId, a.id, d) } }
        }

        private fun answer(q: Question, text: String) {
            questions.value = questions.value.filterNot { it.id == q.id }
            scope.launch(Dispatchers.IO) { runCatching { Hoshino.client.answerQuestion(q.sessionId, q.id, text) } }
        }

        /** Panel input: answer the open question, else steer the running task (CU3/RT18). */
        private fun submit() {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return
            input.setText("")
            focusable(false)
            questions.value.firstOrNull()?.let { answer(it, text); return }
            val sid = controller.state.value.sessionId ?: Hoshino.client.preferredSessionId ?: return toast("先在 Hoshino 里开一个对话")
            scope.launch(Dispatchers.IO) {
                val running = Hoshino.client.sessions.value.firstOrNull { it.id == sid }?.running == true
                runCatching { if (running) Hoshino.client.steer(sid, text) else Hoshino.client.send(sid, text, emptyList(), options(sid)) }
                    .onFailure { withContext(Dispatchers.Main) { toast("没有发出去：" + (it.message ?: "")) } }
            }
        }

        private fun options(sid: String): TurnOptions {
            val row = Hoshino.client.sessions.value.firstOrNull { it.id == sid }
            return TurnOptions(row?.presetId ?: "standard", row?.tier ?: app.hoshino.harness.core.PermissionTier.entries.first(), Hoshino.client.routing.value.main, ReasoningEffort.Medium, false)
        }

        private fun stopTask() {
            // Stop wins over a takeover: hand back first so the waiting action sees the stop and ends.
            if (controller.manual.active.value != null) controller.endManualTakeover()
            val sid = controller.state.value.sessionId
            controller.stop(sid, "用户在悬浮球里停止")
            if (sid != null) scope.launch(Dispatchers.IO) { runCatching { Hoshino.client.stop(sid) } }
        }

        /**
         * CU5: hide the overlay, wait two frames so it is not in the picture, read the main screen's tree
         * (OCR when thin) and screenshot, then send both to the conversation. Secure screens send only the app name.
         */
        private fun sendScreen() {
            val sid = Hoshino.client.preferredSessionId ?: return toast("先在 Hoshino 里开一个对话")
            scope.launch {
                if (controller.manual.active.value == null) collapse()
                dot.visibility = View.INVISIBLE
                card.visibility = View.INVISIBLE
                delay(2 * 17L + 30)
                val read = runCatching { withContext(Dispatchers.Default) { controller.readMainScreen() } }
                dot.visibility = View.VISIBLE
                card.visibility = View.VISIBLE
                val (snap, frame) = read.getOrElse { return@launch toast("读不到当前屏幕：" + (it.message ?: "")) }
                withContext(Dispatchers.IO) {
                    val attachments = mutableListOf<Attachment>()
                    frame?.let { bmp ->
                        val name = UUID.randomUUID().toString() + ".jpg"
                        val file = File(File(ctx.cacheDir, "imports").apply { mkdirs() }, name)
                        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        bmp.recycle()
                        attachments += Attachment(name, "此刻的屏幕.jpg", AttachmentKind.Image, "${file.length() / 1024} KB")
                    }
                    val text = UserScreenText.build(snap)
                    runCatching { Hoshino.client.send(sid, text, attachments, options(sid)) }
                        .onFailure { withContext(Dispatchers.Main) { toast("没有发出去：" + (it.message ?: "")) } }
                }
                toast("已把此刻的屏幕发给 Hoshino")
            }
        }

        private fun focusable(on: Boolean) {
            if (!cardAdded) return
            cardParams.flags = if (on) cardParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else cardParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            cardParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            runCatching { wm.updateViewLayout(card, cardParams) }
        }

        private fun scheduleFade() {
            fadeJob?.cancel()
            dot.alpha = 1f
            fadeJob = scope.launch { delay(FADE_AFTER_MS); if (!cardAdded) dot.animate().alpha(FADED).setDuration(300).start() }
        }

        private fun breathe(on: Boolean) {
            if (on == (breath != null)) return
            breath?.cancel(); breath = null
            if (on) breath = scope.launch { while (true) { dot.animate().scaleX(1.08f).scaleY(1.08f).setDuration(700).start(); delay(700); dot.animate().scaleX(1f).scaleY(1f).setDuration(700).start(); delay(700) } }
        }

        /**
         * Ball: drag moves it (snaps to the edge, never a click); single tap opens the panel with its control panel; double
         * tap minimizes (an open panel goes back to the ball and stops rendering). The single tap waits for the double-tap
         * timeout ([ViewConfiguration.getDoubleTapTimeout]) so the two never both fire.
         */
        private fun installDrag() {
            val taps = TapClassifier(touchSlop.toFloat(), doubleTapTimeout)
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
            var pending: Job? = null
            dot.setOnTouchListener { _, e ->
                val now = e.eventTime
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { pending?.cancel(); taps.down(now, e.rawX, e.rawY); downX = e.rawX; downY = e.rawY; startX = dotParams.x; startY = dotParams.y; dot.alpha = 1f; fadeJob?.cancel() }
                    MotionEvent.ACTION_MOVE -> if (taps.move(e.rawX, e.rawY)) {
                        dotParams.x = startX + (e.rawX - downX).toInt(); dotParams.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(dot, dotParams) }
                    }
                    MotionEvent.ACTION_UP -> {
                        val wasDrag = taps.dragging
                        when (taps.up(now)) {
                            TapClassifier.Click.Double -> { if (cardAdded) collapse(); scheduleFade() }
                            else -> taps.pendingUntil?.let { at ->
                                pending = scope.launch {
                                    delay((at - now).coerceAtLeast(0) + 8)
                                    if (taps.flush(at) == TapClassifier.Click.Single) { if (cardAdded) showControls(!controlsShown) else expand(withControls = true) }
                                }
                            }
                        }
                        if (wasDrag) snap() else scheduleFade()
                    }
                    MotionEvent.ACTION_CANCEL -> { if (taps.dragging) snap(); taps.cancel() }
                }
                true
            }
        }

        /** Snap to the nearest side; remember side and height ratio (survives rotation and restarts). */
        private fun snap() {
            val dm = ctx.resources.displayMetrics
            val left = dotParams.x + px(26) < dm.widthPixels / 2
            dotParams.x = if (left) 0 else dm.widthPixels - px(52)
            dotParams.y = dotParams.y.coerceIn(0, dm.heightPixels - px(52))
            runCatching { wm.updateViewLayout(dot, dotParams) }
            prefs.edit().putBoolean("left", left).putFloat("yRatio", dotParams.y.toFloat() / dm.heightPixels).apply()
            scheduleFade()
        }

        private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

        private fun button(text: String, onClick: () -> Unit) = Button(ctx).apply {
            this.text = text; textSize = 13f; isAllCaps = false; minWidth = 0; minimumWidth = 0
            setOnClickListener { onClick() }
        }
        private fun editText(hint: String) = EditText(ctx).apply {
            this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT; isFocusable = true
        }
        private fun label(text: String) = TextView(ctx).apply { this.text = text; setTextColor(Color.WHITE); textSize = 13f }
        private fun row(vararg views: View?) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; views.filterNotNull().forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) } }
        private fun toast(text: String) { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }

        fun dispose() {
            hideJob?.cancel()
            visibility.reset()
            runCatching { ctx.unregisterReceiver(screenReceiver) }
            if (controller.manual.active.value != null) controller.endManualTakeover()
            model.takeoverChanged(false)
            closePanel()
            if (dotAdded) runCatching { wm.removeView(dot) }
            dotAdded = false
            scope.cancel()
        }

        companion object {
            val GREEN = Color.parseColor("#FF43A047")
            val ORANGE = Color.parseColor("#FFFB8C00")
            val GREY = Color.parseColor("#FF8E8E93")
            const val FADE_AFTER_MS = 3_000L
            const val FADED = 0.4f
            const val DEFAULT_PREVIEW_DP = 180
            const val DEFAULT_LANDSCAPE_DP = 320
            /** Smallest picture: about the size of the ball (52 dp) plus a little, so it can be shrunk almost away. */
            const val MIN_PREVIEW_DP = 64
            const val HEADER_DP = 36
            /** Corner handle touch area (≥ 32 dp asked; 44 dp given). */
            const val HANDLE_DP = 44
            /** Fallback height of the control panel before it was measured. */
            const val CONTROLS_DP = 300
            const val SCREEN_MARGIN_DP = 8
            /** Below this picture width the title bar drops the elapsed time. */
            const val COMPACT_DP = 150
            const val PASTE_SETTLE_MS = 300L
        }
    }
}

/**
 * Text that goes with "此刻的屏幕" (CU5): the app, the compressed screen, and the rules for the model
 * (CU6, light version): read-only questions are answered directly; writing, sending or paying is asked first.
 */
object UserScreenText {
    fun build(s: ScreenSnapshot): String = buildString {
        append("【此刻的屏幕】App：").append(s.packageName ?: "未知").append('\n')
        if (s.secure) append("（这一页禁止截屏，只记下了 App 名）\n")
        else append(ScreenCompressor.render(s, 60)).append('\n')
        append("（我双击胶囊发来的当前屏幕。只读/查询类直接回答；要写入、发出或付款，先问我。）")
    }
}
