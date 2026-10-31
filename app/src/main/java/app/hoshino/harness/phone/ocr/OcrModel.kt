package app.hoshino.harness.phone.ocr

import app.hoshino.harness.phone.UiElement

/** One recognised line in pixels of the image it came from. */
data class OcrLine(val text: String, val confidence: Float, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx: Int get() = (left + right) / 2
    val cy: Int get() = (top + bottom) / 2
}

/** Open-source on-device text recognition (PL1: never ML Kit / GMS). */
interface PhoneOcr {
    /** Models present and verified; recognise() works offline. */
    val ready: Boolean
    suspend fun recognize(bitmap: android.graphics.Bitmap): List<OcrLine>
}

/**
 * When the accessibility tree is good enough we never run OCR (PH4): a tree that cannot be read at all
 * must be recognised; a thin one (few labels, many unlabeled controls: self-drawn UIs, games, Flutter/web
 * canvases) is recognised once; otherwise skip.
 */
object TreeQuality {
    enum class Verdict { Skip, Once, Must }

    fun judge(named: Int, unlabeledInteractive: Int, rootMissing: Boolean, secure: Boolean): Verdict = when {
        secure -> Verdict.Skip
        rootMissing || named == 0 -> Verdict.Must
        named < THIN_NAMED -> Verdict.Once
        unlabeledInteractive >= maxOf(MIN_UNLABELED, named) -> Verdict.Once
        else -> Verdict.Skip
    }

    fun judge(elements: List<UiElement>, rootMissing: Boolean, secure: Boolean): Verdict =
        judge(elements.count { it.name.isNotBlank() }, elements.count { it.interactive && it.name.isBlank() }, rootMissing, secure)

    const val THIN_NAMED = 4
    const val MIN_UNLABELED = 4
}

/**
 * Adds OCR text that the tree does not already carry: lines below [MIN_CONFIDENCE] are dropped, lines whose
 * centre sits inside a tree element showing the same text are dropped, the rest become non-clickable
 * `ocr` elements (tap by point). Coordinates are converted to the 0..999 space of the display.
 */
object OcrMerge {
    const val MIN_CONFIDENCE = 0.5f
    const val MAX_LINES = 120

    fun merge(tree: List<UiElement>, lines: List<OcrLine>, width: Int, height: Int): List<UiElement> {
        fun norm(v: Int, size: Int) = if (size <= 0) 0 else (v.toLong() * 999 / size).toInt().coerceIn(0, 999)
        fun key(s: String) = s.filterNot { it.isWhitespace() }.lowercase()
        val added = lines.asSequence()
            .filter { it.confidence >= MIN_CONFIDENCE && it.text.isNotBlank() }
            .filter { line ->
                val k = key(line.text)
                tree.none { e ->
                    val inside = line.cx in e.pxLeft(width)..e.pxRight(width) && line.cy in e.pxTop(height)..e.pxBottom(height)
                    inside && (e.name.isNotBlank() && (key(e.name).contains(k) || k.contains(key(e.name))))
                }
            }
            .take(MAX_LINES)
            .map { l ->
                UiElement(
                    role = "OcrText", name = l.text.trim(),
                    bounds = listOf(norm(l.left, width), norm(l.top, height), norm(l.right, width), norm(l.bottom, height)),
                    px = l.cx, py = l.cy, ocr = true,
                )
            }.toList()
        return tree + added
    }

    private fun UiElement.pxLeft(w: Int) = if (bounds.size == 4) bounds[0] * w / 999 else 0
    private fun UiElement.pxRight(w: Int) = if (bounds.size == 4) bounds[2] * w / 999 else 0
    private fun UiElement.pxTop(h: Int) = if (bounds.size == 4) bounds[1] * h / 999 else 0
    private fun UiElement.pxBottom(h: Int) = if (bounds.size == 4) bounds[3] * h / 999 else 0
}
