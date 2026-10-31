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
 * Double-tap sends the current main screen to the conversation (CU5). m2.8.1 rules kept: window add/remove is
 * debounced ([CapsuleVisibility]) and the ball stays up for the whole task (between steps too).
 *
 * Tap expands it into the floating panel ([BallPanelModel]):
 *  - live picture of the agent display, zero-copy through [PreviewRelay] (GPU into a TextureView, at the user's frame
 *    rate 5–60 fps, [PhoneDisplayFps]); Bitmap fallback on ROMs where the relay is unavailable. Rendering stops when
 *    the panel collapses;
 *  - the picture resizes continuously (drag the corner handle or pinch), keeps the aspect ratio, and the panel moves by
 *    its header; size and position are remembered;
 *  - the current process: current step, the last few steps, status and elapsed time, stop;
 *  - approvals / questions / the agent's own handoff (CU4) and the steer box (CU3/RT18), as before;
 *  - 「接管」 pauses the agent ([ManualTakeover]): taps, long presses and swipes on the picture and text from the
 *    input row are mapped proportionally ([PreviewGeometry]) and injected into the agent display through the helper's
 *    existing inject calls; 「交还」 lets the agent continue and tells it what changed in one sentence.
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
        private val pad = px(12)
        private val title = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 1 }
        private val elapsed = TextView(ctx).apply { setTextColor(Color.parseColor("#B3FFFFFF")); textSize = 12f; setPadding(px(6), 0, px(6), 0) }
        private val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f)); addView(elapsed)
            addView(button("收起") { collapse() })
            contentDescription = "拖动这里移动面板"
        }
        private val liveView = TextureView(ctx).apply { isOpaque = true }
        private val fallbackView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        private val placeholder = TextView(ctx).apply { text = "后台屏还没打开"; setTextColor(Color.GRAY); textSize = 12f; gravity = Gravity.CENTER }
        private val takeoverFrame = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(px(3), ORANGE) }
        private val frameOverlay = View(ctx).apply { background = takeoverFrame; visibility = View.GONE }
        private val resizeHandle = TextView(ctx).apply {
            text = "⤡"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = px(8).toFloat(); setColor(Color.parseColor("#99000000")) }
            contentDescription = "拖动调整预览大小"
        }
        private val previewBox = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            addView(placeholder, FrameLayout.LayoutParams(-1, -1))
            addView(liveView, FrameLayout.LayoutParams(-1, -1))
            addView(fallbackView, FrameLayout.LayoutParams(-1, -1))
            addView(frameOverlay, FrameLayout.LayoutParams(-1, -1))
            addView(resizeHandle, FrameLayout.LayoutParams(px(32), px(32), Gravity.END or Gravity.BOTTOM))
        }
        private val fpsLabel = TextView(ctx).apply { setTextColor(Color.parseColor("#80FFFFFF")); textSize = 10f }
        private val current = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 13f }
        private val steps = TextView(ctx).apply { setTextColor(Color.parseColor("#CCFFFFFF")); textSize = 12f }
        private val reason = TextView(ctx).apply { setTextColor(Color.parseColor("#FFFFB74D")); textSize = 13f }
        private val actions = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val input = editText("插话 / 回答（发给当前任务）").apply { imeOptions = EditorInfo.IME_ACTION_SEND }
        private val steerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f)); addView(button("发送") { submit() })
        }
        private val takeInput = editText("输入到后台屏的文字").apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        private val takeoverBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(label("接管中：在画面上点按 / 长按 / 滑动，AI 已暂停").apply { setTextColor(ORANGE) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(takeInput, LinearLayout.LayoutParams(0, -2, 1f)); addView(button("输入") { sendTakeoverText() })
            })
            addView(row(button("返回") { sendKey(KeyEvent.KEYCODE_BACK) }, button("回车") { sendKey(KeyEvent.KEYCODE_ENTER) }, button("删除") { sendKey(KeyEvent.KEYCODE_DEL) }))
        }
        private val takeButton = button("接管") { toggleTakeover() }
        private val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, px(10), pad, px(10))
            background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor(Color.parseColor("#F2202027")) }
            addView(header)
            addView(previewBox, LinearLayout.LayoutParams(px(DEFAULT_PREVIEW_DP), px(DEFAULT_PREVIEW_DP) * 2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(6) })
            addView(fpsLabel)
            addView(current); addView(steps); addView(reason); addView(actions)
            addView(takeoverBar); addView(steerRow)
            addView(row(button("停止") { stopTask() }, takeButton, button("发此刻屏幕") { sendScreen() }))
        }
        private val cardParams = overlayParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        }
        private var previewW = prefs.getInt("previewW", px(DEFAULT_PREVIEW_DP))
        private var dotAdded = false
        private var cardAdded = false

        /** Which display the preview is bound to, and whether through Bitmaps (fallback) or the zero-copy relay. */
        private var bound: AgentDisplay? = null
        private var usingFallback = false
        private var liveSurface: Surface? = null
        private var lastFrames = 0L
        private var lastFramesAt = 0L
        private var lastRate = 0L

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { screenOn.value = i.action != Intent.ACTION_SCREEN_OFF }
        }

        fun start() {
            ctx.registerReceiver(screenReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON) })
            installDrag()
            installPanelDrag()
            installPreviewTouch()
            installResize()
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
                    model.takeoverChanged(m != null)
                    if (m != null && dotAdded && !cardAdded) expand()
                    showTakeover(m != null)
                    refreshVisibility()
                }
            }
            scope.launch { runCatching { Hoshino.client.sessions.collect { refreshVisibility() } } }
            scope.launch { screenOn.collect { refreshVisibility() } }
            scope.launch { PhoneConsent.of(ctx).state.collect { refreshVisibility() } }
            scope.launch { controller.display.collect { bindPreview() } }
        }

        fun foreground(pkg: String) { foreground.value = pkg; refreshVisibility() }

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

        /** (want, hideImmediately). Screen off / locked hide at once; everything else goes through the debounce. */
        private fun wanted(): Pair<Boolean, Boolean> {
            val keyguard = runCatching { ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)
            if (!screenOn.value || keyguard) return false to true
            val manual = controller.manual.active.value != null
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
                    if (controller.manual.active.value != null) controller.endManualTakeover()
                    if (immediate) model.screenOff()
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
            takeButton.text = if (manual) "交还" else "接管"
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

        private fun toggleTakeover() {
            if (controller.manual.active.value != null) {
                controller.endManualTakeover()
                focusable(false)
                toast("已交还，AI 继续（已告诉它你改了什么）")
                return
            }
            if (!controller.beginManualTakeover()) toast(if (PhoneTakeover.of(ctx).pending.value != null) "AI 正在请你处理一步，用上面的按钮" else "后台屏还没打开，暂时不能接管")
        }

        // ---------- user input while taken over ----------

        private fun inject(kind: ManualTakeover.Input, call: suspend (IHoshinoPhoneShell, Int) -> Boolean) {
            scope.launch { if (!controller.manualInput(kind, call)) toast("没有送达后台屏") }
        }

        private fun sendKey(code: Int) = inject(ManualTakeover.Input.Key) { sh, id -> sh.injectKey(id, code) }

        /** Printable text key by key; anything else (e.g. Chinese) through the helper's borrowed clipboard + Ctrl+V. */
        private fun sendTakeoverText() {
            val text = takeInput.text?.toString().orEmpty()
            if (text.isEmpty()) return
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

        private fun installPreviewTouch() {
            val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pinching = true; stroking = false; pinchStartW = previewW; pinchScale = 1f; return true }
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    pinchScale *= d.scaleFactor
                    val disp = controller.display.value ?: return true
                    applyPreviewSize(PreviewGeometry.pinch(pinchStartW, pinchScale, disp.width, disp.height, limits()))
                    return true
                }
                override fun onScaleEnd(d: ScaleGestureDetector) { savePanel() }
            })
            previewBox.setOnTouchListener { _, e ->
                scale.onTouchEvent(e)
                val disp = controller.display.value
                val manual = controller.manual.active.value != null
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pinching = false
                        stroking = false
                        if (manual && disp != null) PreviewGeometry.mapToDisplay(e.x, e.y, previewBox.width, previewBox.height, disp.width, disp.height)?.let { (x, y) ->
                            strokeX.clear(); strokeY.clear(); strokeT.clear()
                            strokeDown = e.eventTime; strokeMoved = 0f; strokeStartX = e.x; strokeStartY = e.y
                            strokeX += x; strokeY += y; strokeT += 0L
                            stroking = true
                        }
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> stroking = false
                    MotionEvent.ACTION_MOVE -> if (stroking && !pinching && disp != null) {
                        for (h in 0 until e.historySize) addPoint(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalEventTime(h), disp)
                        addPoint(e.x, e.y, e.eventTime, disp)
                    }
                    MotionEvent.ACTION_UP -> if (stroking && !pinching && disp != null) { addPoint(e.x, e.y, e.eventTime, disp); finishStroke(e.eventTime - strokeDown) }
                    MotionEvent.ACTION_CANCEL -> stroking = false
                }
                true
            }
        }

        private fun addPoint(vx: Float, vy: Float, time: Long, disp: AgentDisplay) {
            strokeMoved = maxOf(strokeMoved, hypot(vx - strokeStartX, vy - strokeStartY))
            val (x, y) = PreviewGeometry.mapClamped(vx, vy, previewBox.width, previewBox.height, disp.width, disp.height)
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

        private fun limits(): PreviewGeometry.Limits {
            val dm = ctx.resources.displayMetrics
            val maxW = (dm.widthPixels - 2 * pad - px(8)).coerceAtLeast(px(MIN_PREVIEW_DP))
            val maxH = (dm.heightPixels - px(PANEL_CHROME_DP)).coerceAtLeast(px(MIN_PREVIEW_DP) * 2)
            return PreviewGeometry.Limits(px(MIN_PREVIEW_DP), maxW, maxH)
        }

        private fun applyPreviewSize(width: Int) {
            val disp = controller.display.value
            val (sw, sh) = if (disp != null) disp.width to disp.height else ctx.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) to maxOf(it.widthPixels, it.heightPixels) }
            previewW = PreviewGeometry.clampWidth(width, sw, sh, limits())
            val lp = previewBox.layoutParams as LinearLayout.LayoutParams
            lp.width = previewW
            lp.height = PreviewGeometry.heightFor(previewW, sw, sh)
            previewBox.layoutParams = lp
            cardParams.width = maxOf(previewW + 2 * pad, px(MIN_PANEL_DP))
            if (cardAdded) runCatching { wm.updateViewLayout(card, cardParams) }
        }

        private fun placePanel() {
            val dm = ctx.resources.displayMetrics
            val w = cardParams.width.takeIf { it > 0 } ?: px(MIN_PANEL_DP)
            val savedX = prefs.getInt("panelX", -1)
            val savedY = prefs.getInt("panelY", -1)
            cardParams.x = if (savedX >= 0) savedX else (dm.widthPixels - w) / 2
            cardParams.y = if (savedY >= 0) savedY else dm.heightPixels / 8
            clampPanel()
        }

        private fun clampPanel() {
            val dm = ctx.resources.displayMetrics
            val h = card.height.takeIf { it > 0 } ?: (PreviewGeometry.heightFor(previewW, 9, 20) + px(PANEL_CHROME_DP) / 2)
            val (x, y) = PreviewGeometry.clampPosition(cardParams.x, cardParams.y, cardParams.width.coerceAtLeast(1), h, dm.widthPixels, dm.heightPixels)
            cardParams.x = x; cardParams.y = y
            if (cardAdded) runCatching { wm.updateViewLayout(card, cardParams) }
        }

        private fun savePanel() {
            clampPanel()
            prefs.edit().putInt("previewW", previewW).putInt("panelX", cardParams.x).putInt("panelY", cardParams.y).apply()
        }

        /** Header drag (Mika OverlayFrame rule: a drag only after the touch slop, deltas in screen coordinates). */
        private fun installPanelDrag() {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
            header.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = cardParams.x; startY = cardParams.y; dragging = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                        if (dragging) { cardParams.x = startX + dx.toInt(); cardParams.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(card, cardParams) } }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) savePanel()
                }
                true
            }
        }

        private fun installResize() {
            var downX = 0f; var downY = 0f; var startW = 0
            resizeHandle.setOnTouchListener { _, e ->
                val disp = controller.display.value
                val (sw, sh) = if (disp != null) disp.width to disp.height else 9 to 20
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startW = previewW }
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
            if (bound === d) return
            unbindPreview()
            bound = d
            placeholder.text = ""
            applyPreviewSize(previewW)
            if (d!!.zeroCopy) {
                usingFallback = false
                liveView.visibility = View.VISIBLE
                fallbackView.visibility = View.GONE
                liveSurface?.let { s -> if (!d.attachPreview(s)) useFallback(d) }
            } else useFallback(d)
        }

        private fun useFallback(d: AgentDisplay) {
            liveSurface?.let { s -> if (!usingFallback) d.detachPreview(s) }
            usingFallback = true
            liveView.visibility = View.GONE
            fallbackView.visibility = View.VISIBLE
            d.previewWatchers++
            d.previewNow()?.let(fallbackView::setImageBitmap)
            frameJob?.cancel()
            frameJob = scope.launch { d.frames.collect { b -> if (b != null) fallbackView.setImageBitmap(b) } }
        }

        private fun unbindPreview() {
            val d = bound ?: return
            frameJob?.cancel(); frameJob = null
            if (usingFallback) d.previewWatchers = (d.previewWatchers - 1).coerceAtLeast(0)
            else liveSurface?.let { d.detachPreview(it) }
            usingFallback = false
            bound = null
            lastFrames = 0; lastFramesAt = 0; lastRate = 0
        }

        // ---------- expand / collapse ----------

        private fun expand() {
            if (cardAdded) return
            if (model.mode == BallPanelModel.Mode.Ball) model.tapBall()
            if (model.mode != BallPanelModel.Mode.Panel) return
            applyPreviewSize(previewW)
            placePanel()
            runCatching { wm.addView(card, cardParams); cardAdded = true }
            fadeJob?.cancel(); dot.alpha = 1f
            bindPreview()
            tickJob?.cancel()
            tickJob = scope.launch { while (isActive) { tick(); delay(1_000) } }
            card.post { clampPanel() }
        }

        /** 「收起」: refused while the user has taken over (the picture is the only way to operate). */
        private fun collapse() {
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

        private fun installDrag() {
            val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (cardAdded) collapse() else expand(); return true }
                override fun onDoubleTap(e: MotionEvent): Boolean { sendScreen(); return true }
            })
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
            dot.setOnTouchListener { _, e ->
                detector.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = dotParams.x; startY = dotParams.y; dragging = false; dot.alpha = 1f; fadeJob?.cancel() }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (dragging || dx * dx + dy * dy > touchSlop * touchSlop) {
                            dragging = true
                            dotParams.x = startX + dx.toInt(); dotParams.y = startY + dy.toInt()
                            runCatching { wm.updateViewLayout(dot, dotParams) }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) snap() else scheduleFade()
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
            const val MIN_PREVIEW_DP = 120
            const val MIN_PANEL_DP = 280
            /** Room left for header, process lines and buttons when the picture is made as tall as possible. */
            const val PANEL_CHROME_DP = 300
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
