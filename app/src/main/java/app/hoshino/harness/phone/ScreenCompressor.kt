package app.hoshino.harness.phone

/**
 * Turns a screen into a short text the model can act on: one line per useful element, in reading order,
 * with a stable ref, a short role, the name, the 0..999 centre and only the flags that matter.
 * Redundant nodes (plain text already carried by the clickable container around it, empty decoration)
 * are dropped. A typical app screen goes from several KB of tree JSON to a few hundred tokens.
 */
object ScreenCompressor {
    const val MAX_LINES = 120
    const val MAX_NAME = 60

    fun render(s: ScreenSnapshot, maxLines: Int = MAX_LINES): String {
        val kept = essential(s.elements)
        val header = buildString {
            append("screen ").append(s.packageName ?: "unknown").append(' ').append(s.width).append('x').append(s.height)
            append(" · ").append(kept.size).append(" items")
            if (s.keyboardShown) append(" · keyboard")
            if (s.ocrUsed) append(" · ocr")
            if (s.secure) append(" · secure")
            if (s.overlayPackages.isNotEmpty()) append(" · overlay ").append(s.overlayPackages.joinToString(","))
        }
        val lines = kept.take(maxLines).map(::line)
        val more = kept.size - lines.size
        return (listOf(header) + lines + if (more > 0) listOf("… $more more (scroll or use phone_read_screen with query)") else emptyList()).joinToString("\n")
    }

    /** Elements worth showing, in reading order. */
    fun essential(elements: List<UiElement>): List<UiElement> {
        val clickables = elements.filter { it.clickable && it.name.isNotBlank() }
        return elements.filter { e ->
            if (e.bounds.size != 4 || e.bounds[2] <= e.bounds[0] || e.bounds[3] <= e.bounds[1]) return@filter false
            if (e.name.isBlank() && !e.interactive) return@filter false
            if (!e.interactive && e.name.isNotBlank()) {
                // Plain text inside a clickable container whose name already contains it.
                val n = norm(e.name)
                if (clickables.any { c -> c !== e && inside(e, c) && norm(c.name).contains(n) }) return@filter false
            }
            // Large scroll containers without a name only matter as scroll targets: keep, they are cheap.
            true
        }.sortedWith(compareBy({ it.bounds[1] / 12 }, { it.bounds[0] }))
    }

    fun line(e: UiElement): String = buildString {
        append(e.ref).append(' ').append(role(e))
        val name = e.name.replace('\n', ' ').trim()
        if (name.isNotEmpty()) {
            append(' ')
            if (e.hint) append("hint:")
            append('"').append(if (name.length > MAX_NAME) name.take(MAX_NAME) + "…" else name).append('"')
        } else if (!e.resourceId.isNullOrBlank()) append(" #").append(e.resourceId)
        append(" @").append(e.cx).append(',').append(e.cy)
        val flags = flags(e)
        if (flags.isNotEmpty()) append(' ').append(flags.joinToString(" "))
    }

    fun flags(e: UiElement): List<String> = buildList {
        if (e.editable) add("input") else if (e.clickable) add("click")
        if (e.longClickable) add("long")
        if (e.scrollable) add("scroll")
        e.checked?.let { add(if (it) "on" else "off") }
        if (e.selected) add("selected")
        if (e.focused) add("focused")
        if (!e.enabled) add("disabled")
        if (e.password) add("password")
        if (e.ocr) add("ocr")
    }

    fun role(e: UiElement): String {
        val r = e.role.lowercase()
        return when {
            e.editable -> "input"
            "checkbox" in r -> "check"
            "switch" in r || "toggle" in r -> "switch"
            "radio" in r -> "radio"
            "button" in r -> "btn"
            "image" in r -> if (e.clickable) "btn" else "img"
            "recycler" in r || "listview" in r || "gridview" in r -> "list"
            "scroll" in r || "pager" in r -> "scroll"
            "tab" in r -> "tab"
            "web" in r -> "web"
            e.ocr -> "ocr"
            r.endsWith("textview") || r == "text" -> if (e.clickable) "btn" else "text"
            e.clickable -> "btn"
            else -> "view"
        }
    }

    private fun norm(s: String) = s.filterNot(Char::isWhitespace).lowercase()

    internal fun inside(child: UiElement, parent: UiElement): Boolean {
        val c = child.bounds; val p = parent.bounds
        return c.size == 4 && p.size == 4 && c[0] >= p[0] && c[1] >= p[1] && c[2] <= p[2] && c[3] <= p[3]
    }
}
