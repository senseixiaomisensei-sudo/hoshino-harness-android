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
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Floating capsule (CU1–CU4, CU5, RT18) drawn as an accessibility overlay (no "draw over other apps"
 * permission). Plain Android views keep it light (no Compose runtime in the overlay window). One dot shows
 * the state ring (green = working, orange breathing = needs you, grey = idle) and the number of things
 * waiting; it fades to 40 % after 3 s, can be dragged and snaps to the nearest edge (side and height ratio are
 * remembered). Tap opens the card: live picture of the agent display (≤ 5 fps), last three steps, stop, the
 * full approval choices (once / this session / deny), answers to questions, takeover, and a box to steer the
 * running task. Double-tap sends the current main screen to the conversation (CU5). Hidden while Hoshino is
 * in front, while the screen is locked, and when there is nothing to show (unless "常驻胶囊" is on).
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
    private data class RenderKey(val state: PhoneTaskState, val count: Int, val takeover: PhoneTakeover.Pending?, val approvalId: String?, val questionId: String?)
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
        private var expanded = false
        private var watchingFrames = false
        private var frameJob: Job? = null
        private var watched: AgentDisplay? = null
        private var fadeJob: Job? = null
        private var breath: Job? = null
        private var hideJob: Job? = null
        private val visibility = CapsuleVisibility()
        private var lastNeedsYou = false
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val dp = ctx.resources.displayMetrics.density
        private fun px(v: Int) = (v * dp).toInt()

        private val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#F21B1B22")); setStroke(px(3), GREY) }
        private val badge = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER }
        private val dot = FrameLayout(ctx).apply {
            background = ring
            addView(TextView(ctx).apply { text = "H"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            addView(badge, FrameLayout.LayoutParams(px(18), px(18), Gravity.END or Gravity.TOP))
            contentDescription = "Hoshino 胶囊"
        }
        private val dotParams = overlayParams(px(52), px(52)).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = ctx.resources.displayMetrics
            x = if (prefs.getBoolean("left", false)) 0 else dm.widthPixels - px(52)
            y = (prefs.getFloat("yRatio", 0.35f) * dm.heightPixels).toInt()
        }

        private val title = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 15f }
        private val preview = ImageView(ctx).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; maxHeight = px(240) }
        private val steps = TextView(ctx).apply { setTextColor(Color.parseColor("#CCFFFFFF")); textSize = 12f }
        private val reason = TextView(ctx).apply { setTextColor(Color.parseColor("#FFFFB74D")); textSize = 13f }
        private val actions = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val input = EditText(ctx).apply {
            hint = "插话 / 回答（发给当前任务）"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEND; isFocusable = true
        }
        private val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(12))
            background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor(Color.parseColor("#F2202027")) }
            addView(title); addView(preview); addView(steps); addView(reason); addView(actions)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(input, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("发送") { submit() })
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("停止") { stopTask() }); addView(button("发此刻屏幕") { sendScreen() }); addView(button("收起") { collapse(); refreshVisibility() })
            })
        }
        private val cardParams = overlayParams(px(312), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
        private var dotAdded = false
        private var cardAdded = false
        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { screenOn.value = i.action != Intent.ACTION_SCREEN_OFF }
        }

        fun start() {
            ctx.registerReceiver(screenReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON) })
            installDrag()
            input.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) focusable(true); false }
            input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
            scope.launch { runCatching { Hoshino.client.events.collect(::onEvent) } }
            scope.launch {
                // Visibility is driven separately (debounced); render only when what is drawn changes.
                combine(controller.state, approvals, questions, PhoneTakeover.of(ctx).pending) { s, a, q, t -> RenderKey(s, a.size + q.size + (if (t != null) 1 else 0), t, a.firstOrNull()?.id, q.firstOrNull()?.id) }
                    .distinctUntilChanged()
                    .collect { k -> render(k.state, k.count, k.takeover) }
            }
            scope.launch {
                combine(controller.state, approvals, questions, PhoneTakeover.of(ctx).pending, foreground) { _, _, _, _, _ -> Unit }
                    .collect { refreshVisibility() }
            }
            scope.launch { runCatching { Hoshino.client.sessions.collect { refreshVisibility() } } }
            scope.launch { screenOn.collect { refreshVisibility() } }
            scope.launch { PhoneConsent.of(ctx).state.collect { refreshVisibility() } }
            scope.launch { controller.display.collect { watchFrames() } }
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

        /** (want, hideImmediately). Screen off / locked hide at once; everything else goes through the debounce. */
        private fun wanted(): Pair<Boolean, Boolean> {
            val keyguard = runCatching { ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)
            if (!screenOn.value || keyguard) return false to true
            if (foreground.value == ctx.packageName) return false to false
            val s = controller.state.value
            // The controller drops to Idle between steps; while its session's turn is still running the task is on.
            val turnRunning = s.sessionId != null && runCatching { Hoshino.client.sessions.value.firstOrNull { it.id == s.sessionId }?.running == true }.getOrDefault(false)
            val busy = s.status != PhoneTaskState.Status.Idle || turnRunning || approvals.value.isNotEmpty() || questions.value.isNotEmpty() || PhoneTakeover.of(ctx).pending.value != null
            // An open card is never pulled away from under the user's finger.
            return (busy || cardAdded || PhoneConsent.of(ctx).state.value.capsuleAlways) to false
        }

        /** The dot window is added once and removed once per debounced change; state only updates its content. */
        private fun refreshVisibility() {
            val (want, immediate) = wanted()
            val d = visibility.update(want, System.currentTimeMillis(), immediate)
            hideJob?.cancel(); hideJob = null
            d.recheckAt?.let { at -> hideJob = scope.launch { delay((at - System.currentTimeMillis()).coerceAtLeast(0) + 16); refreshVisibility() } }
            if (d.changed) {
                if (d.shown && !dotAdded) { runCatching { wm.addView(dot, dotParams); dotAdded = true }; scheduleFade() }
                if (!d.shown) { collapse(); if (dotAdded) { runCatching { wm.removeView(dot) }; dotAdded = false } }
            } else if (d.shown && !dotAdded) {
                runCatching { wm.addView(dot, dotParams); dotAdded = true }
            }
            _visible.value = dotAdded
            watchFrames()
        }

        private fun render(s: PhoneTaskState, count: Int, takeover: PhoneTakeover.Pending?) {
            val needsYou = count > 0 || s.status == PhoneTaskState.Status.NeedsUser || s.status == PhoneTaskState.Status.Approval || takeover != null
            ring.setStroke(px(3), when { needsYou -> ORANGE; s.status == PhoneTaskState.Status.Working -> GREEN; else -> GREY })
            badge.text = if (count > 0) count.toString() else ""
            badge.background = if (count > 0) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ORANGE) } else null
            breathe(needsYou)
            // Only wake the dot when it starts needing you; re-flashing on every emission was the "blinking".
            if (needsYou && !lastNeedsYou) scheduleFade()
            lastNeedsYou = needsYou
            title.text = when (s.status) {
                PhoneTaskState.Status.Working -> "正在操作 " + (s.app ?: "")
                PhoneTaskState.Status.NeedsUser -> "需要你处理"
                PhoneTaskState.Status.Approval -> "等你同意"
                PhoneTaskState.Status.TakenOver -> "交给你了"
                PhoneTaskState.Status.Idle -> if (count > 0) "共 $count 件事" else "Hoshino"
            } + if (count > 1) " · 共 $count 件" else ""
            steps.text = s.recent.joinToString("\n") { "· $it" }
            reason.text = s.reason.orEmpty()
            reason.visibility = if (s.reason.isNullOrBlank()) View.GONE else View.VISIBLE
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

        private fun respond(a: Approval, d: ApprovalDecision) {
            approvals.value = approvals.value.filterNot { it.id == a.id }
            scope.launch(Dispatchers.IO) { runCatching { Hoshino.client.respondApproval(a.sessionId, a.id, d) } }
        }

        private fun answer(q: Question, text: String) {
            questions.value = questions.value.filterNot { it.id == q.id }
            scope.launch(Dispatchers.IO) { runCatching { Hoshino.client.answerQuestion(q.sessionId, q.id, text) } }
        }

        /** Card input: answer the open question, else steer the running task (CU3/RT18). */
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
            val sid = controller.state.value.sessionId
            controller.stop(sid, "用户在胶囊里停止")
            if (sid != null) scope.launch(Dispatchers.IO) { runCatching { Hoshino.client.stop(sid) } }
        }

        /**
         * CU5: hide the overlay, wait two frames so it is not in the picture, read the main screen's tree
         * (OCR when thin) and screenshot, then send both to the conversation. Secure screens send only the app name.
         */
        private fun sendScreen() {
            val sid = Hoshino.client.preferredSessionId ?: return toast("先在 Hoshino 里开一个对话")
            scope.launch {
                collapse()
                dot.visibility = View.INVISIBLE
                delay(2 * 17L + 30)
                val read = runCatching { withContext(Dispatchers.Default) { controller.readMainScreen() } }
                dot.visibility = View.VISIBLE
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

        private fun expand() {
            if (cardAdded) return
            expanded = true
            runCatching { wm.addView(card, cardParams); cardAdded = true }
            fadeJob?.cancel(); dot.alpha = 1f
            controller.display.value?.previewNow()?.let(preview::setImageBitmap)
            watchFrames()
        }

        private fun collapse() {
            expanded = false
            focusable(false)
            if (cardAdded) { runCatching { wm.removeView(card) }; cardAdded = false }
            watchFrames()
            scheduleFade()
        }

        /** Frames are only produced while the card is open (one decoder, many consumers: PH24). */
        private fun watchFrames() {
            val d = controller.display.value
            val want = cardAdded && d != null
            if (want == watchingFrames && watched === d) return
            frameJob?.cancel(); frameJob = null
            watched?.let { it.previewWatchers = (it.previewWatchers - 1).coerceAtLeast(0) }
            watched = null
            watchingFrames = want
            if (!want || d == null) { if (d == null) preview.visibility = View.GONE; return }
            d.previewWatchers++
            watched = d
            preview.visibility = View.VISIBLE
            frameJob = scope.launch { d.frames.collect { b -> if (b != null) preview.setImageBitmap(b) } }
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
            fadeJob = scope.launch { delay(FADE_AFTER_MS); if (!expanded) dot.animate().alpha(FADED).setDuration(300).start() }
        }

        private fun breathe(on: Boolean) {
            if (on == (breath != null)) return
            breath?.cancel(); breath = null
            if (on) breath = scope.launch { while (true) { dot.animate().scaleX(1.08f).scaleY(1.08f).setDuration(700).start(); delay(700); dot.animate().scaleX(1f).scaleY(1f).setDuration(700).start(); delay(700) } }
        }

        private fun installDrag() {
            val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (cardAdded) { collapse(); refreshVisibility() } else expand(); return true }
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
        private fun label(text: String) = TextView(ctx).apply { this.text = text; setTextColor(Color.WHITE); textSize = 13f }
        private fun row(vararg views: View?) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; views.filterNotNull().forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) } }
        private fun toast(text: String) { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }

        fun dispose() {
            hideJob?.cancel()
            visibility.reset()
            runCatching { ctx.unregisterReceiver(screenReceiver) }
            collapse()
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
