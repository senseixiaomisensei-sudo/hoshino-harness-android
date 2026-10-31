// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.hoshino.harness.phone.ocr.OcrMerge
import app.hoshino.harness.phone.ocr.PhoneOcr
import app.hoshino.harness.phone.ocr.TreeQuality
import dev.hoshino.bridge.phone.IHoshinoPhoneShell
import dev.hoshino.bridge.phone.shell.ClipSwap
import dev.hoshino.bridge.phone.shell.ShellText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * [PhonePort] on one display: the agent's virtual display (normal case) or the main display (only used for
 * reading the user's own screen, CU5). Reading goes through [AccessibilityReader] + [RefStabilizer] and, when
 * the tree is thin, open-source OCR on the display's own frame. Actions prefer the accessibility node
 * (click / set text / scroll); points and keys are injected by the helper into our own display only.
 * There is no jitter, curve or "human-like" delay (PL4): gestures are straight, with fixed functional timings.
 */
class AndroidPhoneScreen(
    private val context: Context,
    private val displayId: Int,
    private val shell: () -> IHoshinoPhoneShell?,
    private val display: AgentDisplay?,
    private val ocr: () -> PhoneOcr?,
    val refs: RefStabilizer = RefStabilizer(),
) : PhonePort {
    @Volatile private var nodes: Map<String, AccessibilityNodeInfo> = emptyMap()
    @Volatile var last: ScreenSnapshot? = null
        private set
    /** OCR result for an unchanged tree hash: scrolling back to a seen screen does not recognise again. */
    private var ocrCache: Pair<Long, List<app.hoshino.harness.phone.ocr.OcrLine>>? = null

    private fun service(): PhoneAccessibilityService = PhoneAccessibilityService.instance.value
        ?: throw PhoneUnavailable("accessibility", "Hoshino 的无障碍服务没有开启")

    private val width: Int get() = display?.width ?: context.resources.displayMetrics.widthPixels
    private val height: Int get() = display?.height ?: context.resources.displayMetrics.heightPixels

    override fun now(): Long = SystemClock.uptimeMillis()

    override suspend fun read(): ScreenSnapshot = withContext(Dispatchers.Default) {
        val raw = AccessibilityReader.read(service().windowsOn(displayId), width, height)
        var elements = raw.elements
        var ocrUsed = false
        var secure = false
        val verdict = TreeQuality.judge(elements, raw.rootMissing, false)
        val engine = ocr()
        if (verdict != TreeQuality.Verdict.Skip) {
            val frame = frame()
            if (frame != null) try {
                secure = FrameMath.isBlack(frame.width, frame.height) { x, y -> frame.getPixel(x, y) }
                if (!secure && engine?.ready == true) {
                    val cached = ocrCache?.takeIf { it.first == raw.hash }?.second
                    val lines = cached ?: engine.recognize(frame).also { ocrCache = raw.hash to it }
                    elements = OcrMerge.merge(elements, lines, width, height)
                    ocrUsed = true
                }
            } finally { frame.recycle() }
        }
        val stable = refs.assign(elements)
        val map = HashMap<String, AccessibilityNodeInfo>()
        stable.forEachIndexed { i, e -> raw.nodes.getOrNull(i)?.let { if (!e.ocr) map[e.ref] = it } }
        nodes = map
        ScreenSnapshot(
            snapshotId = UUID.randomUUID().toString().take(8), packageName = raw.packageName, width = width, height = height,
            elements = stable, hash = raw.hash, overlayPackages = raw.overlayPackages, keyboardShown = raw.keyboardShown,
            ocrUsed = ocrUsed, secure = secure,
        ).also { last = it }
    }

    /** Full-size frame of this display: own ImageReader for the agent display, accessibility screenshot for the main one. */
    suspend fun frame(): Bitmap? {
        display?.let { return it.latestFrameBitmap() }
        return when (val shot = service().screenshot(displayId)) {
            is PhoneAccessibilityService.Shot.Ok -> shot.bitmap
            else -> null
        }
    }

    override suspend fun perform(step: PhoneStep, target: UiElement?): ActionOutcome = withContext(Dispatchers.Default) {
        val node = target?.ref?.let { nodes[it] }?.takeIf { runCatching { it.refresh() }.getOrDefault(false) }
        when (step.kind) {
            StepKind.tap -> outcome(click(node, point(step, target)), "tap")
            StepKind.long_press -> outcome(longPress(node, point(step, target)), "long_press")
            StepKind.type -> typeStep(step, target, node, step.text.orEmpty(), step.enter)
            StepKind.clear -> typeStep(step, target, node, "", false)
            StepKind.scroll -> outcome(scroll(node, step.direction ?: "down"), "scroll")
            StepKind.swipe -> outcome(swipe(px(step.x!!), py(step.y!!), px(step.x2!!), py(step.y2!!), step.durationMs ?: SWIPE_MS), "swipe")
            StepKind.back -> outcome(key(KeyEvent.KEYCODE_BACK), "back")
            StepKind.key -> outcome(key(KEYS.getValue(step.key!!)), "key")
            StepKind.open_app -> openApp(step.app!!, step.cold)
            StepKind.wait -> ActionOutcome(true)
        }
    }

    override suspend fun awaitChange(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { service().changesOn(displayId).first(); true } ?: false

    /**
     * A-1 (WP-1): tree hash and frame signature are taken and the event subscription is live before [block] acts; the
     * wait then follows [ChangeWait] against that baseline. Mika's verified()/withEventClock, with the tree hash only
     * recomputed when events arrived (or every [TREE_RECHECK_MS]) so polling stays cheap.
     */
    override suspend fun <T> watch(block: suspend (ChangeProbe) -> T): T = coroutineScope {
        val svc = service()
        val beforeTree = treeHash()
        val beforeFrame = frameSignature()
        val clock = EventClock(now())
        // UNDISPATCHED: the collector is registered on the shared flow before this line returns, i.e. before the action.
        val job = launch(start = CoroutineStart.UNDISPATCHED) { svc.changesOn(displayId).collect { clock.mark(now()) } }
        var hashAt = Long.MIN_VALUE
        var hashEvents = -1L
        var hash = beforeTree
        fun currentTree(): Long {
            val t = now()
            val n = clock.count.value
            if (n != hashEvents || t - hashAt >= TREE_RECHECK_MS) { hash = treeHash(); hashEvents = n; hashAt = t }
            return hash
        }
        val probe = object : ChangeProbe {
            override suspend fun awaitStableChange(): ChangeWait.Result = ChangeWait.awaitStableChange(
                now = ::now,
                sleep = { delay(it) },
                frame = ::frameSignature,
                quietForMs = { now() - clock.last.get() },
                treeChanged = { currentTree() != beforeTree },
                beforeFrame = beforeFrame,
            )
            override fun events(): Long = clock.count.value
            override suspend fun awaitEventAfter(seen: Long, timeoutMs: Long): Boolean =
                withTimeoutOrNull(minOf(timeoutMs, EVENT_RECHECK_MS).coerceAtLeast(1)) { clock.count.first { it > seen }; true } ?: false
        }
        try { block(probe) } finally { job.cancel() }
    }

    /** Time of the last UI event on this display (starts at the subscription) and how many were seen. */
    private class EventClock(start: Long) {
        val last = AtomicLong(start)
        val count = MutableStateFlow(0L)
        fun mark(at: Long) { last.set(at); count.value = count.value + 1 }
    }

    override suspend fun waitQuiet(quietMs: Long, timeoutMs: Long): Boolean {
        val deadline = now() + timeoutMs
        var hash = AccessibilityReader.hash(service().windowsOn(displayId))
        while (now() < deadline) {
            val changed = awaitChange(minOf(quietMs, deadline - now()))
            val h = AccessibilityReader.hash(service().windowsOn(displayId))
            if (!changed && h == hash) return true
            hash = h
        }
        return false
    }

    // ---------- actions ----------

    private fun outcome(ok: Boolean, what: String) = ActionOutcome(ok, if (ok) null else "$what failed")

    private fun point(step: PhoneStep, target: UiElement?): Pair<Int, Int>? = when {
        target != null && (target.px > 0 || target.py > 0) -> target.px to target.py
        target != null && target.bounds.size == 4 -> px(target.cx) to py(target.cy)
        step.x != null && step.y != null -> px(step.x) to py(step.y)
        else -> null
    }

    private fun px(v: Int) = (v.toLong() * width / 999).toInt().coerceIn(0, width - 1)
    private fun py(v: Int) = (v.toLong() * height / 999).toInt().coerceIn(0, height - 1)

    private suspend fun click(node: AccessibilityNodeInfo?, at: Pair<Int, Int>?): Boolean {
        val clickable = node?.let { ancestorOrSelf(it) { n -> n.isClickable && n.isEnabled } }
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val (x, y) = at ?: return false
        return touch(x, y, TAP_MS)
    }

    private suspend fun longPress(node: AccessibilityNodeInfo?, at: Pair<Int, Int>?): Boolean {
        val target = node?.let { ancestorOrSelf(it) { n -> n.isLongClickable && n.isEnabled } }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        val (x, y) = at ?: return false
        return touch(x, y, LONG_PRESS_MS)
    }

    private suspend fun touch(x: Int, y: Int, holdMs: Long): Boolean {
        val s = shell()
        if (display != null && s != null) {
            val ok = runCatching {
                if (holdMs <= TAP_MS) s.injectTap(displayId, x, y)
                else s.injectStroke(displayId, intArrayOf(x, x), intArrayOf(y, y), longArrayOf(0, holdMs))
            }.getOrDefault(false)
            if (ok) return true
        }
        return service().tap(displayId, x.toFloat(), y.toFloat(), holdMs)
    }

    private suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val s = shell()
        if (display != null && s != null) {
            val n = SWIPE_POINTS
            val xs = IntArray(n) { x1 + (x2 - x1) * it / (n - 1) }
            val ys = IntArray(n) { y1 + (y2 - y1) * it / (n - 1) }
            val ts = LongArray(n) { durationMs * it / (n - 1) }
            if (runCatching { s.injectStroke(displayId, xs, ys, ts) }.getOrDefault(false)) return true
        }
        return service().swipe(displayId, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)
    }

    private suspend fun scroll(node: AccessibilityNodeInfo?, direction: String): Boolean {
        val forward = direction == "down" || direction == "right"
        val scrollable = node?.let { ancestorOrSelf(it) { n -> n.isScrollable } } ?: largestScrollable()
        if (scrollable != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollable.performAction(action)) return true
        }
        val cx = width / 2
        val cy = height / 2
        val d = height / 3
        val dx = width / 3
        return when (direction) {
            "down" -> swipe(cx, cy + d / 2, cx, cy - d / 2, SWIPE_MS)
            "up" -> swipe(cx, cy - d / 2, cx, cy + d / 2, SWIPE_MS)
            "right" -> swipe(cx + dx / 2, cy, cx - dx / 2, cy, SWIPE_MS)
            else -> swipe(cx - dx / 2, cy, cx + dx / 2, cy, SWIPE_MS)
        }
    }

    private fun largestScrollable(): AccessibilityNodeInfo? = nodes.values.filter { it.isScrollable }
        .maxByOrNull { n -> android.graphics.Rect().also(n::getBoundsInScreen).let { it.width().toLong() * it.height() } }

    private fun key(code: Int): Boolean {
        val s = shell()
        if (display != null && s != null) return runCatching { s.injectKey(displayId, code) }.getOrDefault(false)
        if (displayId == Display.DEFAULT_DISPLAY && code == KeyEvent.KEYCODE_BACK) return service().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        return false
    }

    /**
     * Input strategy (PH5, WP-2). A point with no field under it → [typeAt]. Otherwise the field (ref/target,
     * else the focused one) gets focus first, then [TypeFlow] writes and reads back, retrying once:
     * native field SET_TEXT → paste → keys; WebView field paste → keys → SET_TEXT (unverified).
     * The clipboard is borrowed through the helper only when the user's clip could be read (else untouched).
     * No field at all → `no_focus` (or, with the keyboard up, the unseen path as at a point).
     */
    private suspend fun typeStep(step: PhoneStep, target: UiElement?, node: AccessibilityNodeInfo?, text: String, enter: Boolean): ActionOutcome {
        val field = node?.let { editableIn(it) }
        val point = point(step, target)
        if (field == null && step.ref == null && step.target == null && step.x != null && step.y != null && point != null) {
            return typeAt(point.first, point.second, text, enter)
        }
        val f = field ?: focusedEditable()
        if (f == null) {
            if (display != null && keyboardUp()) return typeUnseen(text, enter, atPoint = false)
            return ActionOutcome(false, "${TypeFlow.NO_FOCUS}: no input field is focused; tap the field first")
        }
        return typeInto(f, text, enter)
    }

    /**
     * Typing at a point (Mika typeAt): tap it, let the screen settle; a focused field that shows up in the tree is
     * typed into as usual, otherwise (self-drawn field) paste/keys are sent to the focus and the screen text is
     * compared before/after. Nothing changed → `point_not_typed`.
     */
    private suspend fun typeAt(x: Int, y: Int, text: String, enter: Boolean): ActionOutcome {
        val before = topPackage()
        if (!touch(x, y, TAP_MS)) return ActionOutcome(false, "${TypeFlow.POINT_NOT_TYPED}: the tap at that point failed")
        waitQuiet(TYPE_AT_QUIET_MS, TYPE_AT_SETTLE_MS)
        val after = topPackage()
        if (before != null && after != null && after != before) {
            return ActionOutcome(false, "${TypeFlow.POINT_NOT_TYPED}: the tap opened $after instead of an input field; nothing was typed")
        }
        focusedEditable()?.let { return typeInto(it, text, enter) }
        if (display == null) return ActionOutcome(false, "${TypeFlow.POINT_NOT_TYPED}: no input field took focus at that point")
        return typeUnseen(text, enter, atPoint = true)
    }

    private suspend fun typeInto(start: AccessibilityNodeInfo, text: String, enter: Boolean): ActionOutcome {
        var field = start
        if (field.isPassword) return ActionOutcome(false, "password: typing passwords is left to the user")
        val web = inWebView(field)
        // WebView editors only take paste/keys into the focused element: make sure the field holds focus first.
        if (!ensureFocus(field, web)) return ActionOutcome(false, "${TypeFlow.NO_FOCUS}: the input field did not take focus; tap it first")
        if (text.isEmpty()) return clearField(field, web)
        val io = object : TypeFlow.Io {
            override suspend fun setText(text: String): Boolean {
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            override suspend fun paste(text: String): TypeFlow.Paste = withBorrowedClip(text) { s ->
                val before = fieldTextOf(field)
                selectAll(field)
                val sent = field.performAction(AccessibilityNodeInfo.ACTION_PASTE) ||
                    (display != null && runCatching { s.injectCtrlKey(displayId, KeyEvent.KEYCODE_A) && s.injectCtrlKey(displayId, KeyEvent.KEYCODE_V) }.getOrDefault(false))
                // The app reads the clipboard asynchronously: keep ours until the field changed (or a short cap).
                if (sent) pollUntil(PASTE_WAIT_MS) { fieldTextOf(field) != before }
                sent
            }
            override suspend fun keys(text: String): TypeFlow.Keys {
                val s = shell() ?: return TypeFlow.Keys.Failed
                if (display == null) return TypeFlow.Keys.Failed
                if (!ShellText.typeable(text)) return TypeFlow.Keys.Unmappable
                if (!selectAll(field)) runCatching { s.injectCtrlKey(displayId, KeyEvent.KEYCODE_A) }
                return keysResult(runCatching { s.injectText(displayId, text) }.getOrDefault(ShellText.TEXT_FAILED))
            }
            override suspend fun check(text: String): TypeFlow.Check {
                delay(READBACK_MS)
                if (shows(field, text)) return TypeFlow.Check.Shown
                // Some apps swap the input view when its content changes: follow the focused one.
                val now = focusedEditable()
                if (shows(now, text)) { field = now!!; return TypeFlow.Check.Shown }
                return TypeFlow.Check.NotShown
            }
        }
        val r = TypeFlow.run(io, text, web = web)
        if (!r.ok) return ActionOutcome(false, r.detail)
        if (enter) pressEnter(field)
        return ActionOutcome(true, r.detail)
    }

    /** Fields not in the tree (self-drawn): paste/keys go to whatever holds focus on our display; checked by screen text. */
    private suspend fun typeUnseen(text: String, enter: Boolean, atPoint: Boolean): ActionOutcome {
        val s = shell() ?: return ActionOutcome(false, "helper_not_running")
        if (text.isEmpty()) {
            val ok = runCatching { s.injectCtrlKey(displayId, KeyEvent.KEYCODE_A) && s.injectKey(displayId, KeyEvent.KEYCODE_DEL) }.getOrDefault(false)
            return ActionOutcome(ok, if (ok) "cleared_unverified" else "${TypeFlow.POINT_NOT_TYPED}: could not clear")
        }
        var beforeTexts = visibleTexts()
        var beforeHash = treeHash()
        var beforeFrame = frameSignature()
        val io = object : TypeFlow.Io {
            override suspend fun setText(text: String) = false
            override suspend fun paste(text: String): TypeFlow.Paste = withBorrowedClip(text) { sh ->
                runCatching { sh.injectCtrlKey(displayId, KeyEvent.KEYCODE_A) }
                val sent = runCatching { sh.injectCtrlKey(displayId, KeyEvent.KEYCODE_V) }.getOrDefault(false)
                if (sent) pollUntil(PASTE_WAIT_MS) { FrameMath.differs(beforeFrame, frameSignature()) || treeHash() != beforeHash }
                sent
            }
            override suspend fun keys(text: String): TypeFlow.Keys {
                if (!ShellText.typeable(text)) return TypeFlow.Keys.Unmappable
                runCatching { s.injectCtrlKey(displayId, KeyEvent.KEYCODE_A) }
                return keysResult(runCatching { s.injectText(displayId, text) }.getOrDefault(ShellText.TEXT_FAILED))
            }
            override suspend fun check(text: String): TypeFlow.Check {
                waitQuiet(READBACK_QUIET_MS, READBACK_CAP_MS)
                val texts = visibleTexts()
                val hash = treeHash()
                val frame = frameSignature()
                val result = when {
                    TypeFlow.appeared(text, beforeTexts, texts) -> TypeFlow.Check.Shown
                    hash != beforeHash || FrameMath.differs(beforeFrame, frame) -> TypeFlow.Check.Unverified
                    else -> TypeFlow.Check.NotShown
                }
                beforeTexts = texts; beforeHash = hash; beforeFrame = frame
                return result
            }
        }
        val r = TypeFlow.run(io, text, unseen = true, atPoint = atPoint)
        if (!r.ok) return ActionOutcome(false, r.detail)
        if (enter) key(KeyEvent.KEYCODE_ENTER)
        return ActionOutcome(true, r.detail)
    }

    private suspend fun clearField(field: AccessibilityNodeInfo, web: Boolean): ActionOutcome {
        val ok = if (web) {
            // SET_TEXT("") would not reach a web editor: select everything and delete.
            val s = shell()
            fieldTextOf(field).isEmpty() || (s != null && display != null &&
                (selectAll(field) || runCatching { s.injectCtrlKey(displayId, KeyEvent.KEYCODE_A) }.getOrDefault(false)) &&
                runCatching { s.injectKey(displayId, KeyEvent.KEYCODE_DEL) }.getOrDefault(false))
        } else {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        delay(READBACK_MS)
        if (!ok || !shows(field, "")) return ActionOutcome(false, "${TypeFlow.READBACK_MISMATCH}: the field is not empty")
        return ActionOutcome(true, "cleared")
    }

    /**
     * Borrows the clipboard through the helper for one paste and always gives it back. When the helper cannot read
     * the user's clip it refuses ([TypeFlow.Paste.Unavailable]) and nothing on the clipboard was changed.
     * One paste at a time across all screens. The app itself never reads or writes the clipboard.
     */
    private suspend fun withBorrowedClip(text: String, paste: suspend (IHoshinoPhoneShell) -> Boolean): TypeFlow.Paste {
        val s = shell() ?: return TypeFlow.Paste.Failed
        if (display == null) return TypeFlow.Paste.Failed
        return CLIP_MUTEX.withLock {
            val code = runCatching { s.setClipText(text) }.getOrDefault(ClipSwap.CLIP_FAILED)
            // 0 = an older helper without this method: treat as unavailable, never as "set".
            if (code != ClipSwap.CLIP_SET) return@withLock if (code == ClipSwap.CLIP_FAILED) TypeFlow.Paste.Failed else TypeFlow.Paste.Unavailable
            PhoneLedger.borrowClipboard(context)
            try {
                if (paste(s)) TypeFlow.Paste.Done else TypeFlow.Paste.Failed
            } finally {
                runCatching { s.restoreClip() }
                PhoneLedger.returnClipboard(context)
            }
        }
    }

    private fun keysResult(code: Int) = when (code) {
        ShellText.TEXT_TYPED -> TypeFlow.Keys.Done
        ShellText.TEXT_FAILED -> TypeFlow.Keys.Failed
        // TEXT_UNMAPPABLE, or 0 from an older helper without injectText.
        else -> TypeFlow.Keys.Unmappable
    }

    private suspend fun ensureFocus(field: AccessibilityNodeInfo, web: Boolean): Boolean {
        if (refreshed(field)?.isFocused == true) return true
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(FOCUS_WAIT_MS)
        if (refreshed(field)?.isFocused == true) return true
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(FOCUS_WAIT_MS)
        if (refreshed(field)?.isFocused == true) return true
        if (!web) return true // native SET_TEXT works without focus; paste/keys are verified by read-back anyway
        // A web editor: tap its centre, which is what moves the DOM focus.
        val r = android.graphics.Rect().also(field::getBoundsInScreen)
        if (!r.isEmpty && display != null) { touch(r.centerX().coerceIn(0, width - 1), r.centerY().coerceIn(0, height - 1), TAP_MS); delay(FOCUS_WAIT_MS * 2) }
        return refreshed(field)?.isFocused == true || focusedEditable()?.let { inWebView(it) } == true
    }

    private fun refreshed(n: AccessibilityNodeInfo): AccessibilityNodeInfo? = n.takeIf { runCatching { it.refresh() }.getOrDefault(false) }

    private fun selectAll(field: AccessibilityNodeInfo): Boolean {
        val len = fieldTextOf(field).length
        if (len == 0) return true
        val range = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range)
    }

    private fun fieldTextOf(n: AccessibilityNodeInfo): String {
        runCatching { n.refresh() }
        return if (AccessibilityReader.showingHint(n)) "" else n.text?.toString().orEmpty()
    }

    private fun pressEnter(field: AccessibilityNodeInfo) {
        val ime = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        if (!field.performAction(ime)) key(KeyEvent.KEYCODE_ENTER)
    }

    private suspend fun pollUntil(capMs: Long, done: () -> Boolean) {
        val deadline = now() + capMs
        while (now() < deadline && !runCatching(done).getOrDefault(false)) delay(POLL_MS)
    }

    private fun inWebView(n: AccessibilityNodeInfo) = ancestorOrSelf(n) { it.className?.toString() == WEB_VIEW } != null

    private fun keyboardUp(): Boolean = service().windowsOn(displayId).any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun topPackage(): String? = runCatching { AccessibilityReader.topPackage(service().windowsOn(displayId)) }.getOrNull()

    private fun treeHash(): Long = runCatching { AccessibilityReader.hash(service().windowsOn(displayId)) }.getOrDefault(0L)

    private fun visibleTexts(): List<String> = runCatching { AccessibilityReader.read(service().windowsOn(displayId), width, height).texts }.getOrDefault(emptyList())

    /** Luma grid of the newest agent-display frame, read in place (no full-size copy); null without a picture. */
    private fun frameSignature(): IntArray? = display?.frameSignature()

    private fun shows(n: AccessibilityNodeInfo?, text: String): Boolean {
        n ?: return false
        if (!runCatching { n.refresh() }.getOrDefault(false)) return false
        val shown = AccessibilityReader.fieldText(n) ?: return false
        return if (text.isEmpty()) shown.isEmpty() else shown.replace("\u00a0", " ").contains(text.trim().take(64))
    }

    private fun editableIn(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        return descendant(n) { it.isEditable }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        for (w in AccessibilityReader.contentWindows(service().windowsOn(displayId))) {
            val focused = runCatching { w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: continue
            if (focused.isEditable) return focused
            descendant(focused) { it.isFocused && it.isEditable }?.let { return it }
        }
        return null
    }

    /**
     * Opens [app] on the agent display and returns only once it is usable (A-5): in front and past its splash screen
     * ([LaunchReady]), or a chooser asks which copy to open, or [LaunchReady.CAP_MS] passed. [cold]: force-stop first
     * (helper restartApp, only when every task of the app is on our display) so it starts from its first page.
     */
    private suspend fun openApp(app: String, cold: Boolean = false): ActionOutcome {
        val s = shell() ?: return ActionOutcome(false, "helper_not_running")
        if (display == null) return ActionOutcome(false, "apps are only opened on the agent display")
        val pkg = PhoneApps.resolve(context, app) ?: return ActionOutcome(false, "no installed app matches \"$app\"; use phone_list_apps")
        if (pkg == context.packageName) return ActionOutcome(false, "own_app: Hoshino does not operate itself")
        val mainBefore = runCatching { s.topPackage(Display.DEFAULT_DISPLAY) }.getOrNull()
        val started = now()
        // startApp/restartApp only ever target our display (PhoneShellService checks ownership; no main-screen fallback).
        var note: String? = null
        var code = if (cold) runCatching { s.restartApp(displayId, pkg) }.getOrDefault(3) else runCatching { s.startApp(displayId, pkg) }.getOrDefault(3)
        if (cold) when (code) {
            RESTART_COLD -> { code = 0; note = "cold start" }
            RESTART_WARM -> { code = 0; note = "cold ignored: $pkg also has a task outside the agent display, so it was not force-stopped" }
            // An older helper without restartApp answers 0: it did nothing, so start warm and say so.
            0 -> { code = runCatching { s.startApp(displayId, pkg) }.getOrDefault(3); note = "cold not supported by this helper; started warm (restart the helper to update it)" }
        }
        return when (code) {
            0 -> placeOnAgent(s, pkg, mainBefore) ?: run { PhoneLedger.mute(context, s, pkg); awaitUsable(s, pkg, started, note) }
            1 -> ActionOutcome(false, "in_use: the user is using $pkg on the main screen; ask before taking it over")
            2 -> ActionOutcome(false, "no launcher entry for $pkg")
            7 -> ActionOutcome(false, "busy: the agent display is used by another task")
            else -> ActionOutcome(false, "could not start $pkg")
        }
    }

    /** A-5: polls the agent display until [LaunchReady] says the app is usable, a chooser is up, or the cap. */
    private suspend fun awaitUsable(s: IHoshinoPhoneShell, pkg: String, started: Long, note: String?): ActionOutcome {
        val tracker = LaunchReady.Tracker(pkg, started)
        while (true) {
            val windows = runCatching { service().windowsOn(displayId) }.getOrDefault(emptyList())
            val raw = runCatching { AccessibilityReader.read(windows, width, height) }.getOrNull()
            val snap = raw?.let { ScreenSnapshot("launch", it.packageName, width, height, it.elements, it.hash, it.overlayPackages) }
            val look = LaunchReady.Look(
                atMs = now(),
                taskPackage = runCatching { s.topPackage(displayId) }.getOrNull(),
                activity = runCatching { s.topActivity(displayId) }.getOrNull(),
                windowPackage = raw?.packageName,
                clickable = raw?.elements?.count { it.clickable || it.editable } ?: 0,
                named = raw?.elements?.count { it.name.isNotBlank() } ?: 0,
                treeHash = raw?.hash ?: 0L,
                chooser = snap?.let { SystemScreens.chooser(it) != null } ?: false,
            )
            val v = tracker.next(look)
            val ms = now() - started
            val tail = note?.let { "; $it" }.orEmpty()
            when (v.state) {
                LaunchReady.State.Ready -> return ActionOutcome(true, "opened $pkg on the agent display in ${ms}ms (${v.why})$tail", ms)
                LaunchReady.State.Chooser -> return ActionOutcome(true, "opened $pkg: the system asks which app to use; pick an option$tail", ms)
                LaunchReady.State.Timeout -> return when {
                    v.inFront -> ActionOutcome(true, "opened $pkg but it was not interactive after ${ms}ms (still loading?); read the screen before acting$tail", ms)
                    // Something else is confirmed in front: say so instead of reporting success.
                    look.taskPackage != null && look.taskPackage != pkg -> ActionOutcome(false, "app_not_in_front: $pkg did not come to front within ${ms}ms; in front now: ${look.taskPackage}", ms)
                    else -> ActionOutcome(true, "started $pkg but could not confirm it is in front after ${ms}ms; read the screen$tail", ms)
                }
                LaunchReady.State.Waiting -> delay(LaunchReady.POLL_MS)
            }
        }
    }

    /** m2.9: null when the app is on the agent display (or not visible anywhere yet); an error outcome if it landed on the main screen and could not be moved back. */
    private suspend fun placeOnAgent(s: IHoshinoPhoneShell, pkg: String, mainBefore: String?): ActionOutcome? {
        val deadline = SystemClock.uptimeMillis() + PLACEMENT_WAIT_MS
        while (true) {
            val agentTop = runCatching { s.topPackage(displayId) }.getOrNull()
            val mainTop = runCatching { s.topPackage(Display.DEFAULT_DISPLAY) }.getOrNull()
            when (LaunchPlacement.verdict(pkg, mainBefore, agentTop, mainTop)) {
                Placement.OnAgent -> return null
                Placement.LeakedToMain -> {
                    val task = runCatching { s.topTaskId(Display.DEFAULT_DISPLAY) }.getOrDefault(-1)
                    val moved = task >= 0 && runCatching { s.moveTask(task, displayId) }.getOrDefault(false)
                    return if (moved) null
                    else ActionOutcome(false, "opened_on_main_screen: Android put $pkg on the user's main screen and it could not be moved to the agent display; tell the user, do not retry with system_shell")
                }
                Placement.Pending -> if (SystemClock.uptimeMillis() >= deadline) return null
            }
            delay(PLACEMENT_POLL_MS)
        }
    }

    private fun ancestorOrSelf(n: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth++ < 30) { if (test(cur)) return cur; cur = cur.parent }
        return null
    }

    private fun descendant(n: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 200) {
            val c = queue.removeFirst()
            if (test(c)) return c
            for (i in 0 until c.childCount) c.getChild(i)?.let(queue::add)
        }
        return null
    }

    companion object {
        const val TAP_MS = 60L
        /** A-1: the tree hash is recomputed when events arrived, and at least this often anyway. */
        const val TREE_RECHECK_MS = 400L
        /** Expectation waits re-read the screen at least this often even without an event (changes without events). */
        const val EVENT_RECHECK_MS = 750L
        /** IHoshinoPhoneShell.restartApp results (PhoneShellService.RESTART_*). */
        const val RESTART_COLD = 10
        const val RESTART_WARM = 11
        /** Long enough for every launcher/app to see a long press. */
        const val LONG_PRESS_MS = 650L
        const val SWIPE_MS = 350L
        const val SWIPE_POINTS = 12
        const val PLACEMENT_WAIT_MS = 1500L
        const val PLACEMENT_POLL_MS = 150L
        const val READBACK_MS = 150L
        const val READBACK_QUIET_MS = 300L
        const val READBACK_CAP_MS = 1_500L
        /** Mika waits up to 1.5 s for the app to read the clipboard before restoring it. */
        const val PASTE_WAIT_MS = 1_500L
        const val POLL_MS = 50L
        const val FOCUS_WAIT_MS = 120L
        const val TYPE_AT_QUIET_MS = 300L
        const val TYPE_AT_SETTLE_MS = 1_500L
        const val WEB_VIEW = "android.webkit.WebView"
        /** One clipboard borrow at a time across every screen. */
        private val CLIP_MUTEX = Mutex()
    }
}

class PhoneUnavailable(val code: String, message: String) : Exception(message)
