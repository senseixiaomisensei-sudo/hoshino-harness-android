// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.hoshino.harness.phone.ocr.OcrMerge
import app.hoshino.harness.phone.ocr.PhoneOcr
import app.hoshino.harness.phone.ocr.TreeQuality
import dev.hoshino.bridge.phone.IHoshinoPhoneShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

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
            StepKind.type -> type(node, step.text.orEmpty(), step.enter)
            StepKind.clear -> type(node, "", false)
            StepKind.scroll -> outcome(scroll(node, step.direction ?: "down"), "scroll")
            StepKind.swipe -> outcome(swipe(px(step.x!!), py(step.y!!), px(step.x2!!), py(step.y2!!), step.durationMs ?: SWIPE_MS), "swipe")
            StepKind.back -> outcome(key(KeyEvent.KEYCODE_BACK), "back")
            StepKind.key -> outcome(key(KEYS.getValue(step.key!!)), "key")
            StepKind.open_app -> openApp(step.app!!)
            StepKind.wait -> ActionOutcome(true)
        }
    }

    override suspend fun awaitChange(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { service().changesOn(displayId).first(); true } ?: false

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
     * Input strategy (PH5): set the text on the node → if the app ignores it (web editors, self-drawn
     * fields), paste through the clipboard into our own display and give the user's clipboard back →
     * verify by reading the field again. No field → `no_focus`.
     */
    private suspend fun type(target: AccessibilityNodeInfo?, text: String, enter: Boolean): ActionOutcome {
        val field = target?.let { editableIn(it) } ?: focusedEditable()
            ?: return ActionOutcome(false, "no_focus: no input field is focused; tap the field first")
        if (field.isPassword) return ActionOutcome(false, "password: typing passwords is left to the user")
        if (!field.isFocused) { field.performAction(AccessibilityNodeInfo.ACTION_FOCUS); field.performAction(AccessibilityNodeInfo.ACTION_CLICK); delay(150) }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        var how = "set_text"
        val set = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(120)
        if (!set || !shows(field, text)) {
            if (text.isEmpty() || display == null) return ActionOutcome(false, "field did not accept the text")
            how = "paste"
            if (!paste(text)) return ActionOutcome(false, "field did not accept the text (set_text and paste failed)")
            delay(200)
            if (!shows(field, text) && !shows(focusedEditable(), text)) return ActionOutcome(false, "point_not_typed: pasted, but the field does not show the text")
        }
        if (enter) {
            val ime = android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            if (!field.performAction(ime)) key(KeyEvent.KEYCODE_ENTER)
        }
        return ActionOutcome(true, how)
    }

    private fun shows(n: AccessibilityNodeInfo?, text: String): Boolean {
        n ?: return false
        if (!runCatching { n.refresh() }.getOrDefault(false)) return false
        val shown = AccessibilityReader.fieldText(n) ?: return false
        return if (text.isEmpty()) shown.isEmpty() else shown.replace("\u00a0", " ").contains(text.trim().take(64))
    }

    /** Clipboard paste into our own display; the user's clip is restored afterwards (RT6). */
    private suspend fun paste(text: String): Boolean {
        val s = shell() ?: return false
        val cm = context.getSystemService(ClipboardManager::class.java)
        val saved = withContext(Dispatchers.Main) { runCatching { cm.primaryClip }.getOrNull() }
        PhoneLedger.borrowClipboard(context)
        try {
            withContext(Dispatchers.Main) { cm.setPrimaryClip(ClipData.newPlainText("hoshino", text)) }
            delay(80)
            val ok = runCatching { s.injectCtrlKey(displayId, KeyEvent.KEYCODE_A) && s.injectCtrlKey(displayId, KeyEvent.KEYCODE_V) }.getOrDefault(false)
            delay(150)
            return ok
        } finally {
            withContext(Dispatchers.Main) { runCatching { if (saved != null) cm.setPrimaryClip(saved) else cm.clearPrimaryClip() } }
            PhoneLedger.returnClipboard(context)
        }
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

    private suspend fun openApp(app: String): ActionOutcome {
        val s = shell() ?: return ActionOutcome(false, "helper_not_running")
        if (display == null) return ActionOutcome(false, "apps are only opened on the agent display")
        val pkg = PhoneApps.resolve(context, app) ?: return ActionOutcome(false, "no installed app matches \"$app\"; use phone_list_apps")
        if (pkg == context.packageName) return ActionOutcome(false, "own_app: Hoshino does not operate itself")
        val mainBefore = runCatching { s.topPackage(Display.DEFAULT_DISPLAY) }.getOrNull()
        // startApp only ever targets our display (PhoneShellService checks ownership; no main-screen fallback).
        val code = runCatching { s.startApp(displayId, pkg) }.getOrDefault(3)
        return when (code) {
            0 -> placeOnAgent(s, pkg, mainBefore) ?: run { PhoneLedger.mute(context, s, pkg); ActionOutcome(true, "opened $pkg on the agent display") }
            1 -> ActionOutcome(false, "in_use: the user is using $pkg on the main screen; ask before taking it over")
            2 -> ActionOutcome(false, "no launcher entry for $pkg")
            7 -> ActionOutcome(false, "busy: the agent display is used by another task")
            else -> ActionOutcome(false, "could not start $pkg")
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
        /** Long enough for every launcher/app to see a long press. */
        const val LONG_PRESS_MS = 650L
        const val SWIPE_MS = 350L
        const val SWIPE_POINTS = 12
        const val PLACEMENT_WAIT_MS = 1500L
        const val PLACEMENT_POLL_MS = 150L
    }
}

class PhoneUnavailable(val code: String, message: String) : Exception(message)
