// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** One accessibility read of a display, before stable refs are assigned. [nodes] is parallel to [elements]. */
class RawRead(
    val elements: List<UiElement>,
    val nodes: List<AccessibilityNodeInfo>,
    val hash: Long,
    val packageName: String?,
    val overlayPackages: List<String>,
    val keyboardShown: Boolean,
    /** An application window exists but its root could not be read (tree unavailable: OCR must run). */
    val rootMissing: Boolean,
    val texts: List<String>,
)

/**
 * Reads the windows of one display into [UiElement]s (PH3): visible nodes with their own label, or
 * interactive ones; clickable containers without a label take their children's text. Bounds are clipped to
 * the display and normalised to 0..999. Password fields never expose their text. The [RawRead.hash] covers
 * every visible node including focus and selection, so a focus change also counts as "the screen changed".
 */
object AccessibilityReader {
    private const val MIN_SIZE = 5
    private const val MAX_DEPTH = 80
    private const val AGGREGATE_MAX = 60
    private const val MAX_ELEMENTS = 600
    private val SKIPPED = setOf(
        AccessibilityWindowInfo.TYPE_INPUT_METHOD,
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY,
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
    )

    fun contentWindows(windows: List<AccessibilityWindowInfo>): List<AccessibilityWindowInfo> =
        windows.filter { it.type !in SKIPPED }.sortedByDescending { it.layer }

    fun topPackage(windows: List<AccessibilityWindowInfo>): String? =
        contentWindows(windows).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { runCatching { it.root?.packageName?.toString() }.getOrNull() }

    fun read(windows: List<AccessibilityWindowInfo>, width: Int, height: Int): RawRead {
        val content = contentWindows(windows)
        val elements = mutableListOf<UiElement>()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val texts = mutableListOf<String>()
        val seen = HashSet<String>()
        var hash = FNV_OFFSET
        val rect = Rect()

        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || elements.size >= MAX_ELEMENTS) return
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(rect)
                hash = mix(hash, n.className, if (n.isPassword) null else n.text, n.contentDescription, rect.flattenToString(), states(n))
                val own = if (n.isPassword) n.hintText?.toString()?.trim()?.ifEmpty { null } else ownLabel(n)
                own?.let(texts::add)
                if (!rect.intersect(0, 0, width, height)) rect.setEmpty()
                val usable = rect.width() >= MIN_SIZE && rect.height() >= MIN_SIZE
                val interactive = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable
                if (usable && (own != null || interactive)) {
                    val label = own ?: if (n.isClickable) descendantLabel(n) else ""
                    val role = roleOf(n)
                    if (seen.add("$role|$label|${rect.flattenToString()}")) {
                        val hint = own != null && showingHint(n)
                        elements += UiElement(
                            role = role, name = label,
                            bounds = listOf(norm(rect.left, width), norm(rect.top, height), norm(rect.right, width), norm(rect.bottom, height)),
                            px = rect.centerX(), py = rect.centerY(),
                            resourceId = if (label.isEmpty() || n.isEditable) readableResourceId(n.viewIdResourceName) else null,
                            clickable = n.isClickable, longClickable = n.isLongClickable, editable = n.isEditable, scrollable = n.isScrollable,
                            focused = n.isFocused, checked = if (n.isCheckable) checked(n) else null, selected = n.isSelected, enabled = n.isEnabled,
                            hint = hint, placeholder = if (n.isEditable) n.hintText?.toString()?.trim()?.ifEmpty { null } else null,
                            password = n.isPassword,
                            searchField = n.isEditable && n.className?.toString()?.contains("Search", ignoreCase = true) == true,
                        )
                        nodes += n
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }

        var rootMissing = false
        val overlays = mutableListOf<String>()
        var top: String? = null
        for (w in content) {
            val root = runCatching { w.root }.getOrNull()
            val pkg = root?.packageName?.toString()
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                if (root == null) { if (top == null) rootMissing = true } else if (top == null) top = pkg
            } else if (pkg != null && pkg !in overlays) overlays += pkg
            if (root != null) visit(root, 0)
        }
        // A system/application dialog above the app (resolver, permission dialog) also counts as an overlay.
        content.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.drop(1).forEach { w ->
            runCatching { w.root?.packageName?.toString() }.getOrNull()?.let { if (it != top && it !in overlays) overlays += it }
        }
        return RawRead(
            elements, nodes, hash, top, overlays,
            keyboardShown = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD },
            rootMissing = rootMissing, texts = texts,
        )
    }

    /** Fingerprint only (quiet detection between actions). */
    fun hash(windows: List<AccessibilityWindowInfo>): Long {
        var hash = FNV_OFFSET
        val rect = Rect()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(rect)
                hash = mix(hash, n.className, if (n.isPassword) null else n.text, n.contentDescription, rect.flattenToString(), states(n))
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        for (w in contentWindows(windows)) runCatching { w.root }.getOrNull()?.let { visit(it, 0) }
        return hash
    }

    /** All texts of the windows of [packageName] (pairing-code reader); bounded breadth-first walk. */
    fun texts(windows: List<AccessibilityWindowInfo>, packageName: String, maxNodes: Int = 400): List<List<String>> =
        windows.mapNotNull { w ->
            val root = runCatching { w.root }.getOrNull() ?: return@mapNotNull null
            if (root.packageName?.toString() != packageName) return@mapNotNull null
            val out = mutableListOf<String>()
            val queue = ArrayDeque(listOf(root))
            var seen = 0
            while (queue.isNotEmpty() && seen++ < maxNodes) {
                val n = queue.removeFirst()
                n.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
            }
            out
        }

    fun ownLabel(n: AccessibilityNodeInfo): String? {
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return n.hintText?.toString()?.trim()?.ifEmpty { null }
    }

    fun showingHint(n: AccessibilityNodeInfo): Boolean {
        if (n.isShowingHintText) return true
        val hint = n.hintText?.toString()?.trim()
        if (hint.isNullOrEmpty()) return false
        val text = n.text?.toString()?.trim()
        return if (text.isNullOrEmpty()) n.contentDescription.isNullOrBlank() else text == hint
    }

    /** Text the user sees in a field; "" while it shows its hint, null for passwords. */
    fun fieldText(n: AccessibilityNodeInfo): String? {
        if (n.isPassword) return null
        if (showingHint(n)) return ""
        return n.text?.toString().orEmpty()
    }

    private fun descendantLabel(n: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        fun walk(c: AccessibilityNodeInfo, depth: Int) {
            if (depth > 6 || parts.sumOf { it.length } > AGGREGATE_MAX) return
            for (i in 0 until c.childCount) {
                val child = c.getChild(i) ?: continue
                if (!child.isVisibleToUser) continue
                if (!child.isPassword) ownLabel(child)?.let { if (it !in parts) parts += it }
                walk(child, depth + 1)
            }
        }
        walk(n, 0)
        val joined = parts.joinToString(" ")
        return if (joined.length > AGGREGATE_MAX) joined.take(AGGREGATE_MAX) + "…" else joined
    }

    private fun roleOf(n: AccessibilityNodeInfo) = n.className?.toString()?.substringAfterLast('.')?.ifEmpty { null } ?: "View"

    @Suppress("DEPRECATION")
    private fun checked(n: AccessibilityNodeInfo): Boolean = n.isChecked

    @Suppress("DEPRECATION")
    private fun states(n: AccessibilityNodeInfo) = "${n.isChecked}${n.isSelected}${n.isEnabled}${n.isFocused}${n.textSelectionStart}:${n.textSelectionEnd}"

    private fun readableResourceId(id: String?): String? {
        val short = id?.substringAfterLast('/') ?: return null
        return short.takeIf { Regex("^[A-Za-z0-9_]{4,}$").matches(it) && Regex("[A-Za-z]{3}").containsMatchIn(it) }
    }

    fun norm(v: Int, size: Int): Int = if (size <= 0) 0 else (v.toLong() * 999 / size).toInt().coerceIn(0, 999)

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    private fun mix(h0: Long, vararg parts: Any?): Long {
        var h = h0
        for (p in parts) {
            val s = p?.toString() ?: "\u0000"
            for (c in s) h = (h xor c.code.toLong()) * FNV_PRIME
            h = (h xor 0x1f) * FNV_PRIME
        }
        return h
    }
}
