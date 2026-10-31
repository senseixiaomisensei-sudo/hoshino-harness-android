package app.hoshino.harness.phone

import org.json.JSONObject

/**
 * WP-4 (B-1) composite reads: query / extract / find / collect.
 *
 * Everything that decides what a screen *says* is a pure function here ([ScreenQueries]) so it is unit-tested on the JVM;
 * the loops that scroll ([ScreenReader]) only drive a [PhonePort], so long lists and chat histories are read to the end
 * on the phone and come back to the model as one structured result instead of one round-trip per screen.
 *
 * Beyond Mika's phone_collect (which merges plain text lines): rows are grouped structurally (list item containers first,
 * then vertical bands), every row carries a stable key, overlapping screens are aligned by key sequence (two identical
 * messages that are really two messages both survive), rows cut off at the list edge are matched against their full copy,
 * and a `cursor` lets the next call continue where this one stopped.
 */
data class ElementQuery(val text: String? = null, val id: String? = null, val role: String? = null, val inside: String? = null, val limit: Int = DEFAULT_LIMIT) {
    val empty: Boolean get() = text == null && id == null && role == null && inside == null

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 60
        fun parse(o: JSONObject): ElementQuery {
            fun str(k: String) = o.optString(k).trim().takeIf { o.has(k) && !o.isNull(k) && it.isNotEmpty() }?.take(200)
            val q = ElementQuery(str("text"), str("id"), str("role"), str("inside"), ScreenQueries.int(o, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT))
            require(!q.empty) { "give at least one of text, id, role, inside" }
            return q
        }
    }
}

enum class ExtractMode { text, rows, form }

data class ExtractParams(val mode: ExtractMode, val fromY: Int, val toY: Int) {
    companion object {
        fun parse(o: JSONObject): ExtractParams {
            val m = o.optString("mode").ifBlank { "text" }
            val mode = runCatching { ExtractMode.valueOf(m) }.getOrElse { throw IllegalArgumentException("mode must be text, rows or form") }
            val (from, to) = ScreenQueries.band(o)
            return ExtractParams(mode, from, to)
        }
    }
}

data class FindParams(val query: ElementQuery, val direction: String, val maxScrolls: Int) {
    companion object {
        const val DEFAULT_SCROLLS = 10
        const val MAX_SCROLLS = 30
        fun parse(o: JSONObject): FindParams =
            FindParams(ElementQuery.parse(o), ScreenQueries.direction(o, setOf("up", "down", "left", "right")), ScreenQueries.int(o, "max_scrolls", DEFAULT_SCROLLS, 0, MAX_SCROLLS))
    }
}

data class CollectParams(val direction: String, val fromY: Int, val toY: Int, val maxItems: Int, val maxScrolls: Int, val cursor: ReadCursor?) {
    companion object {
        const val DEFAULT_ITEMS = 80
        const val MAX_ITEMS = 300
        const val DEFAULT_SCROLLS = 15
        const val MAX_SCROLLS = 40
        fun parse(o: JSONObject): CollectParams {
            val direction = ScreenQueries.direction(o, setOf("up", "down"))
            val (from, to) = ScreenQueries.band(o)
            val cursor = o.optString("cursor").takeIf { o.has("cursor") && !o.isNull("cursor") && it.isNotBlank() }?.let {
                ReadCursor.parse(it) ?: throw IllegalArgumentException("bad cursor")
            }
            require(cursor == null || cursor.direction == direction) { "cursor was made for direction ${cursor?.direction}" }
            return CollectParams(direction, from, to, ScreenQueries.int(o, "max_items", DEFAULT_ITEMS, 1, MAX_ITEMS), ScreenQueries.int(o, "max_scrolls", DEFAULT_SCROLLS, 0, MAX_SCROLLS), cursor)
        }
    }
}

/** Where a collect stopped: direction plus the keys of the last rows it returned (in reading order). */
data class ReadCursor(val direction: String, val keys: List<String>) {
    fun encode(): String = "c1." + direction + "." + keys.joinToString(".")

    companion object {
        const val KEYS = 4
        private val KEY = Regex("[0-9a-f]{8}")
        fun parse(s: String): ReadCursor? {
            val parts = s.trim().split('.')
            if (parts.size < 3 || parts[0] != "c1" || parts[1] !in setOf("up", "down")) return null
            val keys = parts.drop(2)
            if (keys.size > KEYS || keys.any { !KEY.matches(it) }) return null
            return ReadCursor(parts[1], keys)
        }
    }
}

/**
 * One list row. [key] is a short hash of the normalised text (the same row has the same key on every screen);
 * [ref] is the element to act on while it is on screen; [clipTop]/[clipBottom] = cut off by (or touching) that list edge.
 */
data class ListRow(val key: String, val text: String, val top: Int, val bottom: Int, val ref: String?, val clipTop: Boolean = false, val clipBottom: Boolean = false) {
    val clipped: Boolean get() = clipTop || clipBottom
}

data class MergeResult(val rows: List<ListRow>, val added: Int, val gap: Boolean)

data class QueryMatch(val element: UiElement, val path: List<String>, val row: String?)

object ScreenQueries {
    const val MAX_ROW_CHARS = 300
    /** Collect output budget in characters; past it the result is cut and a cursor returned. */
    const val MAX_CHARS = 12_000
    const val MIN_BAND = 50
    private const val ANCHOR_WINDOW = 60
    private const val SEAM_MIN = 6
    private const val ANCHOR_MIN = 10

    // ---------- parameters ----------

    fun int(o: JSONObject, k: String, default: Int, min: Int, max: Int): Int {
        if (!o.has(k) || o.isNull(k)) return default
        val d = o.optDouble(k)
        require(!d.isNaN() && d == Math.floor(d)) { "$k must be an integer" }
        require(d >= min && d <= max) { "$k must be $min..$max" }
        return d.toInt()
    }

    fun direction(o: JSONObject, allowed: Set<String>): String {
        val d = o.optString("direction").trim().lowercase().ifEmpty { "down" }
        require(d in allowed) { "direction must be ${allowed.joinToString("/")}" }
        return d
    }

    fun band(o: JSONObject): Pair<Int, Int> {
        val from = int(o, "from_y", 0, 0, 999)
        val to = int(o, "to_y", 999, 0, 999)
        require(to - from >= MIN_BAND) { "to_y must be at least $MIN_BAND above from_y" }
        return from to to
    }

    // ---------- small helpers ----------

    fun norm(s: String): String = s.filterNot(Char::isWhitespace).lowercase()

    private fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /** FNV-1a 32 over the normalised text: same row text → same key on every screen and every call. */
    fun key(text: String): String {
        var h = 0x811c9dc5.toInt()
        for (b in norm(text).toByteArray(Charsets.UTF_8)) { h = h xor (b.toInt() and 0xff); h *= 0x01000193 }
        return "%08x".format(h)
    }

    private fun valid(e: UiElement) = e.bounds.size == 4 && e.bounds[2] > e.bounds[0] && e.bounds[3] > e.bounds[1]
    private fun area(e: UiElement): Long = (e.bounds[2] - e.bounds[0]).toLong() * (e.bounds[3] - e.bounds[1])
    private fun contains(outer: UiElement, inner: UiElement) = outer !== inner && valid(outer) && valid(inner) && ScreenCompressor.inside(inner, outer)
    private fun label(e: UiElement) = if (e.password) "" else clean(e.name)

    /** The main list inside the band: the largest scrollable element whose centre lies in it (≥ 10% of the screen). */
    fun listIn(s: ScreenSnapshot, fromY: Int = 0, toY: Int = 999): UiElement? =
        s.elements.filter { valid(it) && it.scrollable && it.cy in fromY..toY && area(it) >= 99_800L }.maxByOrNull(::area)

    /** Area rows are read from: the main list clipped to the band, else the band across the whole width. */
    fun region(s: ScreenSnapshot, fromY: Int = 0, toY: Int = 999): IntArray {
        val list = listIn(s, fromY, toY) ?: return intArrayOf(0, fromY, 999, toY)
        val b = list.bounds
        val top = maxOf(b[1], fromY); val bottom = minOf(b[3], toY)
        return if (bottom - top < MIN_BAND) intArrayOf(0, fromY, 999, toY) else intArrayOf(b[0], top, b[2], bottom)
    }

    // ---------- rows ----------

    /**
     * Rows of the list in the band, top to bottom. List item containers (wide clickable elements) are rows with the
     * text inside them; remaining text is grouped into vertical bands (chat bubbles, plain text lists).
     */
    fun rows(s: ScreenSnapshot, fromY: Int = 0, toY: Int = 999): List<ListRow> {
        val a = region(s, fromY, toY)
        val list = listIn(s, fromY, toY)
        val h = a[3] - a[1]; val w = a[2] - a[0]
        val pool = s.elements.filter { e ->
            valid(e) && e !== list && !e.password && e.cx in a[0]..a[2] && e.cy in a[1]..a[3] &&
                (e.bounds[3] - e.bounds[1]) <= h * 6 / 10
        }
        val named = pool.filter { label(it).isNotEmpty() }
        val candidates = pool.filter { c ->
            c.interactive && !c.editable && (c.bounds[2] - c.bounds[0]) * 10 >= w * 6 &&
                (label(c).isNotEmpty() || named.any { contains(c, it) })
        }
        val containers = candidates.filter { c -> candidates.none { o -> o !== c && contains(o, c) && !(o.bounds == c.bounds && candidates.indexOf(o) > candidates.indexOf(c)) } }
        val out = mutableListOf<ListRow>()
        for (c in containers) {
            val cells = named.filter { contains(c, it) }.sortedWith(compareBy({ it.bounds[1] / 12 }, { it.bounds[0] }))
            val text = label(c).ifEmpty { cells.map(::label).distinct().joinToString(" · ") }.take(MAX_ROW_CHARS)
            if (text.isNotEmpty()) out += ListRow(key(text), text, c.bounds[1], c.bounds[3], c.ref.ifEmpty { null }, c.bounds[1] <= a[1] + 1, c.bounds[3] >= a[3] - 1)
        }
        val loose = named.filter { e -> e !in containers && containers.none { contains(it, e) } }.sortedBy { it.bounds[1] }
        var band = mutableListOf<UiElement>(); var bandBottom = -1
        fun flush() {
            if (band.isEmpty()) return
            val cells = band.sortedWith(compareBy({ it.bounds[0] }, { it.bounds[1] }))
            val text = cells.map(::label).distinct().joinToString(" · ").take(MAX_ROW_CHARS)
            val top = band.minOf { it.bounds[1] }
            val ref = (cells.firstOrNull { it.interactive } ?: cells.first()).ref.ifEmpty { null }
            out += ListRow(key(text), text, top, bandBottom, ref, top <= a[1] + 1, bandBottom >= a[3] - 1)
            band = mutableListOf(); bandBottom = -1
        }
        for (e in loose) {
            if (band.isNotEmpty() && e.bounds[1] >= bandBottom - 1) flush()
            band += e; bandBottom = maxOf(bandBottom, e.bounds[3])
        }
        flush()
        return out.sortedWith(compareBy({ it.top }, { it.bottom }))
    }

    /** Same row on two screens by key, or (≥ [SEAM_MIN] chars) one clipped copy containing the other. */
    fun sameRow(a: ListRow, b: ListRow): Boolean {
        if (a.key == b.key) return true
        if (!a.clipped && !b.clipped) return false
        return containsEither(a, b, SEAM_MIN)
    }

    private fun containsEither(a: ListRow, b: ListRow, min: Int): Boolean {
        val x = norm(a.text); val y = norm(b.text)
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short.length >= min && long.contains(short)
    }

    private fun distinctive(r: ListRow) = norm(r.text).length >= SEAM_MIN

    /** The fuller copy of a row seen twice (a clipped copy shows only part of its text). */
    private fun better(a: ListRow, b: ListRow): ListRow = if (b.text.length > a.text.length) b else a

    /**
     * Merges a newly read screen ([page], reading order) into [acc] when new content arrives at the end (scrolling down).
     *
     * Accessibility scrolls move a whole page, so two screens usually share only the row cut by the list edge, often with
     * different visible text. Alignment, in order:
     * 1. Sequence overlap: the longest tail of [acc] equal to a run of [page] (starting at its first row, or after at most two
     *    leading rows that were already read, e.g. a sticky header). A pair matches by key, or — at the seam, where the old row
     *    was cut at the leading edge or the new one at the trailing edge — when one text contains the other. A run of one short
     *    unclipped row ("好的") is not taken as overlap: under a page scroll that is a second, identical message.
     * 2. Anchor: the last page row with distinctive text that occurs once in the recent tail of [acc], when every page row
     *    before it was already read.
     * 3. Otherwise everything is new ([MergeResult.gap] = no overlap evidence; normal for an exact page scroll).
     */
    fun mergeDown(acc: List<ListRow>, page: List<ListRow>): MergeResult = merge(acc, page, { it.clipBottom }, { it.clipTop })

    /** New content arrives at the start (scrolling up through a chat history). */
    fun mergeUp(acc: List<ListRow>, page: List<ListRow>): MergeResult {
        val r = merge(acc.asReversed(), page.asReversed(), { it.clipTop }, { it.clipBottom })
        return MergeResult(r.rows.asReversed().toList(), r.added, r.gap)
    }

    private fun merge(acc: List<ListRow>, page: List<ListRow>, oldLead: (ListRow) -> Boolean, newTrail: (ListRow) -> Boolean): MergeResult {
        if (page.isEmpty()) return MergeResult(acc, 0, false)
        if (acc.isEmpty()) return MergeResult(page, page.size, false)
        val window = acc.takeLast(ANCHOR_WINDOW)
        fun seen(r: ListRow) = window.any { sameRow(it, r) }
        for (len in minOf(acc.size, page.size) downTo 1) {
            for (q in 0..minOf(2, page.size - len)) {
                if ((0 until q).any { !seen(page[it]) }) continue
                val tail = acc.size - len
                var lenient = false
                val ok = (0 until len).all { k ->
                    val a = acc[tail + k]; val b = page[q + k]
                    when {
                        sameRow(a, b) -> true
                        (oldLead(a) || newTrail(b)) && (len > 1 || q == 0) && containsEither(a, b, 1) -> { lenient = true; true }
                        else -> false
                    }
                }
                if (!ok) continue
                if (len == 1 && !lenient && !distinctive(page[q]) && !(oldLead(acc.last()) || newTrail(page[q]))) continue
                val merged = acc.toMutableList()
                for (i in 0 until len) merged[tail + i] = better(acc[tail + i], page[q + i])
                val rest = page.drop(q + len)
                return MergeResult(merged + rest, rest.size, false)
            }
        }
        val counts = window.groupingBy { it.key }.eachCount()
        val anchor = page.indices.lastOrNull { j -> counts[page[j].key] == 1 && norm(page[j].text).length >= ANCHOR_MIN }
        if (anchor != null && (0 until anchor).all { seen(page[it]) || page[it].clipped }) {
            val rest = page.drop(anchor + 1)
            return MergeResult(acc + rest, rest.size, false)
        }
        return MergeResult(acc + page, page.size, true)
    }

    fun merge(direction: String, acc: List<ListRow>, page: List<ListRow>): MergeResult = if (direction == "up") mergeUp(acc, page) else mergeDown(acc, page)

    /** Keys shown to the model: the row key, with -2, -3 … for repeated identical rows (two "好的" messages). */
    fun displayKeys(rows: List<ListRow>): List<String> {
        val seen = HashMap<String, Int>()
        return rows.map { r -> val n = (seen[r.key] ?: 0) + 1; seen[r.key] = n; if (n == 1) r.key else "${r.key}-$n" }
    }

    /**
     * Where a [cursor] continues on [page]: the cursor's rows found there plus everything after them (down: below; up: above),
     * in reading order, and how many of those are the cursor's own rows (already returned; kept so the next screen still has
     * an overlap to align on). Null when none of its rows is on the page. The longest run of cursor keys is searched anywhere on the page; a single short key
     * alone is not trusted ("好的" can be anywhere).
     */
    fun resume(cursor: ReadCursor, page: List<ListRow>): Pair<List<ListRow>, Int>? {
        val up = cursor.direction == "up"
        val keys = if (up) cursor.keys.asReversed() else cursor.keys
        val rows = if (up) page.asReversed() else page
        for (len in keys.size downTo 1) {
            val run = keys.takeLast(len)
            val at = (rows.size - len downTo 0).firstOrNull { p -> (0 until len).all { rows[p + it].key == run[it] } } ?: continue
            if (len == 1 && keys.size > 1 && !distinctive(rows[at])) continue
            val kept = rows.drop(at)
            return (if (up) kept.asReversed() else kept) to len
        }
        return null
    }

    /** The rows a cursor continues from: the newest returned rows in reading order (end for down, start for up). */
    fun cursorFor(direction: String, returned: List<ListRow>): ReadCursor =
        ReadCursor(direction, (if (direction == "up") returned.take(ReadCursor.KEYS) else returned.takeLast(ReadCursor.KEYS)).map { it.key })

    // ---------- query ----------

    /** Elements matching every given filter, with their containers (nearest first) and their list row. */
    fun query(s: ScreenSnapshot, q: ElementQuery): Pair<List<QueryMatch>, Int> {
        val screenRows by lazy { rows(s) }
        fun rowOf(e: UiElement): ListRow? = screenRows.firstOrNull { r -> e.cy in r.top..r.bottom && (r.ref == e.ref || r.text.contains(label(e).take(40)) || label(e).isEmpty()) }
        val all = s.elements.filter(::valid).filter { e ->
            val name = label(e)
            (q.text == null || norm(name).contains(norm(q.text)) || e.placeholder?.let { norm(it).contains(norm(q.text)) } == true) &&
                (q.id == null || e.resourceId?.contains(q.id, ignoreCase = true) == true) &&
                (q.role == null || ScreenCompressor.role(e) == q.role.lowercase() || e.role.contains(q.role, ignoreCase = true)) &&
                (q.inside == null || s.elements.any { c -> contains(c, e) && area(c) > area(e) && norm(label(c)).contains(norm(q.inside)) } ||
                    rowOf(e)?.let { norm(it.text).contains(norm(q.inside)) && !norm(name).contains(norm(q.inside)) } == true)
        }
        val shown = all.take(q.limit).map { e ->
            val path = s.elements.filter { c -> contains(c, e) && area(c) > area(e) && label(c).isNotEmpty() }.sortedBy(::area).take(2).map { label(it).take(40) }
            val row = rowOf(e)?.text?.takeIf { r -> path.none { r.startsWith(it) } && r != label(e) }?.take(80)
            QueryMatch(e, path, row)
        }
        return shown to all.size
    }

    fun renderMatches(ms: List<QueryMatch>): String = ms.joinToString("\n") { m ->
        ScreenCompressor.line(m.element) +
            (if (m.path.isNotEmpty()) " · in " + m.path.joinToString(" < ") { "\"$it\"" } else "") +
            (m.row?.let { " · row \"$it\"" } ?: "")
    }

    // ---------- extract ----------

    fun extract(s: ScreenSnapshot, p: ExtractParams): String = when (p.mode) {
        ExtractMode.text -> ScreenCompressor.essential(s.elements)
            .filter { it.cy in p.fromY..p.toY && label(it).isNotEmpty() && !it.hint }
            .let { kept -> kept.filter { e -> kept.none { c -> contains(c, e) && area(c) > area(e) && norm(label(c)).contains(norm(label(e))) } } }
            .map(::label).fold(mutableListOf<String>()) { acc, t -> if (acc.lastOrNull() != t) acc += t; acc }.joinToString("\n")
        ExtractMode.rows -> rows(s, p.fromY, p.toY).joinToString("\n") { r -> (r.ref ?: "-") + " " + r.text }
        ExtractMode.form -> form(s, p.fromY, p.toY).joinToString("\n")
    }

    /** Inputs and toggles with their labels; password values never leave the phone. */
    fun form(s: ScreenSnapshot, fromY: Int = 0, toY: Int = 999): List<String> {
        val texts = s.elements.filter { valid(it) && !it.interactive && label(it).isNotEmpty() }
        fun labelFor(e: UiElement): String? {
            val left = texts.filter { t -> t.bounds[2] <= e.bounds[0] + 10 && t.cy in e.bounds[1]..e.bounds[3] }.maxByOrNull { it.bounds[2] }
            val above = texts.filter { t -> t.bounds[3] <= e.bounds[1] + 5 && e.bounds[1] - t.bounds[3] <= 80 && t.bounds[0] < e.bounds[2] && t.bounds[2] > e.bounds[0] }.maxByOrNull { it.bounds[3] }
            return (left ?: above)?.let(::label)
        }
        return s.elements.filter { valid(it) && it.cy in fromY..toY && (it.editable || it.checked != null) }.map { e ->
            if (e.editable) {
                val value = when { e.password -> "(hidden)"; e.hint -> ""; else -> label(e) }
                val name = labelFor(e) ?: e.placeholder ?: (if (e.hint) label(e) else null) ?: e.resourceId ?: ""
                "${e.ref} input \"${name.take(40)}\" = \"${value.take(MAX_ROW_CHARS)}\"" + (if (e.focused) " focused" else "") + (if (!e.enabled) " disabled" else "")
            } else {
                val name = label(e).ifEmpty { labelFor(e) ?: "" }
                "${e.ref} ${ScreenCompressor.role(e)} \"${name.take(40)}\" ${if (e.checked == true) "on" else "off"}" + (if (!e.enabled) " disabled" else "")
            }
        }
    }
}

/** Why a [ScreenReader] loop stopped. */
object ReadStop {
    const val FOUND = "found"
    const val END = "end"
    const val MAX_SCROLLS = "max_scrolls"
    const val MAX_ITEMS = "max_items"
    const val MAX_CHARS = "max_chars"
    const val NEEDS_USER = "needs_user"
    const val SECURE = "secure"
    const val APP_CHANGED = "app_changed"
    const val SCROLL_FAILED = "scroll_failed"
    const val STOPPED = "stopped"
}

data class FindResult(val matches: List<QueryMatch>, val total: Int, val scrolls: Int, val stop: String, val detail: String?, val screen: ScreenSnapshot)

data class CollectResult(
    val rows: List<ListRow>,
    val keys: List<String>,
    val screens: Int,
    val scrolls: Int,
    val stop: String,
    val detail: String?,
    /** Screens merged without overlap evidence (normal after an exact page scroll; not reported to the model). */
    val unaligned: Int,
    val cursor: ReadCursor?,
    /** A cursor was given but none of its rows is on the screen any more: reading restarted from the current screen. */
    val cursorLost: Boolean,
    val screen: ScreenSnapshot,
)

/**
 * The scrolling loops behind phone_find and phone_collect. Scrolls go through [PhonePort.perform] (so the controller's
 * guards — locked screen, Hoshino's own screens — apply to every one), then wait for the list to settle.
 */
class ScreenReader(private val port: PhonePort, private val stopRequested: () -> String? = { null }) {

    private suspend fun scroll(s: ScreenSnapshot, direction: String, fromY: Int, toY: Int): String? {
        val list = ScreenQueries.listIn(s, fromY, toY)
        val out = port.perform(PhoneStep(StepKind.scroll, ref = list?.ref?.ifEmpty { null }, direction = direction), list)
        if (!out.ok) return out.detail ?: "scroll failed"
        port.waitQuiet(QUIET_MS, SETTLE_MS)
        return null
    }

    private fun blocked(s: ScreenSnapshot, app: String?): Pair<String, String?>? = when {
        stopRequested() != null -> ReadStop.STOPPED to stopRequested()
        s.secure -> ReadStop.SECURE to "secure screen: use phone_handoff"
        SystemScreens.needsUser(s) != null -> ReadStop.NEEDS_USER to SystemScreens.needsUser(s)?.reason
        app != null && s.packageName != app -> ReadStop.APP_CHANGED to s.packageName
        else -> null
    }

    suspend fun find(p: FindParams): FindResult {
        var s = port.read()
        val app = s.packageName
        var scrolls = 0
        while (true) {
            blocked(s, app)?.let { (stop, why) -> return FindResult(emptyList(), 0, scrolls, stop, why, s) }
            val (ms, total) = ScreenQueries.query(s, p.query)
            if (ms.isNotEmpty()) return FindResult(ms, total, scrolls, ReadStop.FOUND, null, s)
            if (scrolls >= p.maxScrolls) return FindResult(emptyList(), 0, scrolls, ReadStop.MAX_SCROLLS, null, s)
            scroll(s, p.direction, 0, 999)?.let { return FindResult(emptyList(), 0, scrolls, ReadStop.SCROLL_FAILED, it, s) }
            scrolls++
            val next = port.read()
            if (unchanged(s, next)) return FindResult(emptyList(), 0, scrolls, ReadStop.END, "content stopped moving", next)
            s = next
        }
    }

    suspend fun collect(p: CollectParams): CollectResult {
        var s = port.read()
        val app = s.packageName
        var acc = emptyList<ListRow>()
        var screens = 0; var scrolls = 0; var gaps = 0; var stall = 0
        var cursorLost = false
        var seen = 0
        var stop = ReadStop.END; var detail: String? = null
        while (true) {
            val b = blocked(s, app)
            if (b != null) { stop = b.first; detail = b.second; break }
            val page = ScreenQueries.rows(s, p.fromY, p.toY)
            screens++
            val added: Int
            if (screens == 1 && p.cursor != null) {
                // Continue after the cursor's rows; when none of them is on screen any more, start over from what is visible.
                val found = ScreenQueries.resume(p.cursor, page)
                if (found == null) cursorLost = true
                acc = found?.first ?: page
                seen = found?.second ?: 0
                added = acc.size - seen
            } else {
                val m = ScreenQueries.merge(p.direction, acc, page)
                if (m.gap) gaps++
                acc = m.rows
                added = m.added
            }
            stall = if (screens > 1 && added == 0) stall + 1 else 0
            if (acc.size - seen >= p.maxItems) { stop = ReadStop.MAX_ITEMS; break }
            if (acc.sumOf { it.text.length + ROW_OVERHEAD } >= ScreenQueries.MAX_CHARS) { stop = ReadStop.MAX_CHARS; break }
            if (stall >= 2) { stop = ReadStop.END; detail = "no new rows"; break }
            if (scrolls >= p.maxScrolls) { stop = ReadStop.MAX_SCROLLS; break }
            val failed = scroll(s, p.direction, p.fromY, p.toY)
            if (failed != null) { stop = ReadStop.SCROLL_FAILED; detail = failed; break }
            scrolls++
            val next = port.read()
            if (unchanged(s, next)) { s = next; stop = ReadStop.END; detail = "content stopped moving"; break }
            s = next
        }
        // Rows of the cursor itself were returned by the previous call.
        var rows = if (p.direction == "up") acc.dropLast(seen) else acc.drop(seen)
        // Up: the newest returned rows are at the start of the list; the cut keeps the ones nearest the starting point.
        var cut = false
        if (rows.size > p.maxItems) { rows = if (p.direction == "up") rows.takeLast(p.maxItems) else rows.take(p.maxItems); cut = true }
        var chars = 0
        val within = if (p.direction == "up") rows.asReversed().takeWhile { r -> chars += r.text.length + ROW_OVERHEAD; chars <= ScreenQueries.MAX_CHARS }.asReversed()
            else rows.takeWhile { r -> chars += r.text.length + ROW_OVERHEAD; chars <= ScreenQueries.MAX_CHARS }
        if (within.size < rows.size) { rows = within; cut = true; stop = ReadStop.MAX_CHARS }
        val more = stop != ReadStop.END && stop != ReadStop.NEEDS_USER && stop != ReadStop.SECURE && stop != ReadStop.APP_CHANGED
        val cursor = if ((more || cut) && rows.isNotEmpty()) ScreenQueries.cursorFor(p.direction, rows) else null
        return CollectResult(rows, ScreenQueries.displayKeys(rows), screens, scrolls, stop, detail, gaps, cursor, cursorLost, s)
    }

    private fun unchanged(a: ScreenSnapshot, b: ScreenSnapshot): Boolean =
        if (a.hash != 0L && b.hash != 0L) a.hash == b.hash
        else a.elements.map { it.name to it.bounds } == b.elements.map { it.name to it.bounds }

    companion object {
        const val QUIET_MS = 300L
        const val SETTLE_MS = 1_500L
        /** Characters a row costs in the output besides its text (key, separator, newline). */
        const val ROW_OVERHEAD = 10
    }
}
