package app.hoshino.harness.phone

import kotlin.math.abs

/**
 * Gives elements refs that stay the same across reads of one session, so the model can keep using
 * `e12` after a scroll or a small update. Matching: same role+name+resource id nearest first; then the
 * same control whose text changed (inputs, counters, toggles) at almost the same place; everything else
 * gets a new ref. Refs are never reused for a different element within a session.
 */
class RefStabilizer {
    private var counter = 0
    private var previous: List<UiElement> = emptyList()

    @Synchronized
    fun assign(elements: List<UiElement>): List<UiElement> {
        val prev = previous
        val taken = BooleanArray(prev.size)
        val out = arrayOfNulls<UiElement>(elements.size)
        fun key(e: UiElement) = "${e.role}|${e.name}|${e.resourceId.orEmpty()}"
        val prevByKey = prev.indices.groupBy { key(prev[it]) }
        val newByKey = elements.indices.groupBy { key(elements[it]) }
        // Pass 1: identical identity, nearest position (scrolling moves things, identity stays).
        for (i in elements.indices) {
            val e = elements[i]
            val k = key(e)
            val candidates = prevByKey[k].orEmpty().filter { !taken[it] }
            val unique = candidates.size == 1 && newByKey[k]?.size == 1
            val best = candidates.minByOrNull { dist(prev[it], e) } ?: continue
            if (unique || dist(prev[best], e) <= MOVE_LIMIT) {
                taken[best] = true
                out[i] = e.copy(ref = prev[best].ref)
            }
        }
        // Pass 2: same control, content changed (typed text, counters, check state) at nearly the same place.
        for (i in elements.indices) {
            if (out[i] != null) continue
            val e = elements[i]
            val best = prev.indices.filter { !taken[it] && sameControl(prev[it], e) }.minByOrNull { dist(prev[it], e) }
            if (best != null && dist(prev[best], e) <= CHANGE_LIMIT) {
                taken[best] = true
                out[i] = e.copy(ref = prev[best].ref)
            }
        }
        val result = elements.indices.map { i -> out[i] ?: elements[i].copy(ref = "e${++counter}") }
        previous = result
        return result
    }

    @Synchronized
    fun reset() { previous = emptyList() }

    private fun sameControl(a: UiElement, b: UiElement): Boolean {
        if (a.role != b.role) return false
        if (a.resourceId != null && a.resourceId == b.resourceId) return true
        return (a.editable && b.editable) || (a.checked != null && b.checked != null && a.name == b.name)
    }

    private fun dist(a: UiElement, b: UiElement) = abs(a.cx - b.cx) + abs(a.cy - b.cy)

    companion object {
        const val MOVE_LIMIT = 400
        const val CHANGE_LIMIT = 60
    }
}

/** What changed between two reads, by stable ref. */
data class ScreenDelta(
    val added: List<UiElement>,
    val removed: List<UiElement>,
    val changed: List<Pair<UiElement, UiElement>>,
    val unchanged: Int,
    /** The difference is too large (or the app changed): send the full screen instead. */
    val full: Boolean,
    /** Dominant content shift (scrolling) in 0..999 units; elements that only moved with it are unchanged. */
    val shiftX: Int = 0,
    val shiftY: Int = 0,
) {
    val empty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

object ScreenDiff {
    const val MOVED = 30

    fun compute(prev: ScreenSnapshot?, cur: ScreenSnapshot): ScreenDelta {
        val a = prev?.let { ScreenCompressor.essential(it.elements) }.orEmpty()
        val b = ScreenCompressor.essential(cur.elements)
        if (prev == null || prev.packageName != cur.packageName) return ScreenDelta(b, a, emptyList(), 0, true)
        val before = a.associateBy { it.ref }
        val after = b.associateBy { it.ref }
        val added = b.filter { it.ref !in before }
        val removed = a.filter { it.ref !in after }
        val (sx, sy) = shift(b.mapNotNull { e -> before[e.ref]?.let { (e.cx - it.cx) to (e.cy - it.cy) } })
        val changed = b.mapNotNull { e -> before[e.ref]?.takeIf { differs(it, e, sx, sy) }?.let { it to e } }
        val unchanged = b.size - added.size - changed.size
        val churn = added.size + removed.size + changed.size
        val full = churn > FULL_RATIO * maxOf(a.size, b.size, 1) || prev.overlayPackages != cur.overlayPackages
        return ScreenDelta(added, removed, changed, unchanged, full, sx, sy)
    }

    /** The shift most moved elements share (a scroll), or 0,0 when there is no clear majority. */
    fun shift(all: List<Pair<Int, Int>>): Pair<Int, Int> {
        val moves = all.filter { (dx, dy) -> abs(dx) > MOVED || abs(dy) > MOVED }
        if (moves.size < 2) return 0 to 0
        val groups = moves.groupBy { (dx, dy) -> (dx / 8) to (dy / 8) }
        val (_, top) = groups.maxByOrNull { it.value.size } ?: return 0 to 0
        if (top.size * 2 <= moves.size) return 0 to 0
        return top.map { it.first }.sorted()[top.size / 2] to top.map { it.second }.sorted()[top.size / 2]
    }

    fun differs(a: UiElement, b: UiElement, sx: Int = 0, sy: Int = 0): Boolean =
        a.name != b.name || a.checked != b.checked || a.focused != b.focused || a.enabled != b.enabled || a.selected != b.selected || moved(a, b, sx, sy)

    /** Moved on its own: neither still in place nor moved together with the scrolled content. */
    fun moved(a: UiElement, b: UiElement, sx: Int, sy: Int): Boolean {
        val still = abs(a.cx - b.cx) <= MOVED && abs(a.cy - b.cy) <= MOVED
        val withScroll = abs(a.cx + sx - b.cx) <= MOVED && abs(a.cy + sy - b.cy) <= MOVED
        return !still && !withScroll
    }

    /**
     * Render for the model: the full compressed screen when [delta] says so, otherwise only the changes.
     * Unchanged refs stay valid, which is what keeps follow-up reads small.
     */
    fun render(prev: ScreenSnapshot?, cur: ScreenSnapshot, delta: ScreenDelta = compute(prev, cur)): String {
        if (delta.full) return ScreenCompressor.render(cur)
        if (delta.empty) return "screen ${cur.packageName ?: "unknown"} unchanged (${delta.unchanged} items, refs still valid)"
        val head = "screen ${cur.packageName ?: "unknown"} update: +${delta.added.size} -${delta.removed.size} ~${delta.changed.size}, ${delta.unchanged} unchanged (refs still valid)" +
            (if (delta.shiftX != 0 || delta.shiftY != 0) " · scrolled content shifted by ${delta.shiftX},${delta.shiftY}" else "") +
            (if (cur.keyboardShown != prev?.keyboardShown) if (cur.keyboardShown) " · keyboard shown" else " · keyboard hidden" else "")
        val lines = mutableListOf(head)
        delta.added.take(ScreenCompressor.MAX_LINES).forEach { lines += "+ " + ScreenCompressor.line(it) }
        delta.removed.take(40).forEach { lines += "- ${it.ref}" + if (it.name.isNotBlank()) " \"${it.name.take(ScreenCompressor.MAX_NAME)}\"" else "" }
        delta.changed.take(ScreenCompressor.MAX_LINES).forEach { (o, n) -> lines += "~ " + ScreenCompressor.line(n) + describe(o, n, delta) }
        return lines.joinToString("\n")
    }

    private fun describe(o: UiElement, n: UiElement, d: ScreenDelta): String {
        val parts = buildList {
            if (o.name != n.name) add("was \"${o.name.take(30)}\"")
            if (moved(o, n, d.shiftX, d.shiftY)) add("moved from @${o.cx},${o.cy}")
        }
        return if (parts.isEmpty()) "" else " (" + parts.joinToString(", ") + ")"
    }

    private const val FULL_RATIO = 0.6
}
