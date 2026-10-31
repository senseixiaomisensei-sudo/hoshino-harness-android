package app.hoshino.harness.phone

import kotlin.math.roundToInt

/**
 * m2.9.3: geometry of the in-app picture-in-picture window over the chat (pure, unit-tested). The window is a header
 * strip plus the picture, which keeps the agent display's aspect ratio, so it is fully described by (x, y, width). It
 * always stays inside the chat area; the width is clamped to [Limits.minW] … what fits.
 */
object PipLayout {
    const val MIN_DP = 72
    const val DEFAULT_DP = 132
    const val MARGIN_PX = 24

    /** [areaW]×[areaH] = the chat area in px, [headerH] = the title strip in px. */
    data class Limits(val minW: Int, val areaW: Int, val areaH: Int, val headerH: Int)
    data class Win(val x: Int, val y: Int, val w: Int, val pictureH: Int)

    private fun maxWidth(srcW: Int, srcH: Int, availW: Int, availH: Int): Int =
        if (srcW <= 0 || srcH <= 0) availW else minOf(availW, (availH.toLong() * srcW / srcH).toInt())

    /** Clamped window for a wanted width and top-left; NaN position = default (top right, a margin from the edges). */
    fun place(w: Float, x: Float, y: Float, srcW: Int, srcH: Int, l: Limits): Win {
        val maxW = maxWidth(srcW, srcH, l.areaW, l.areaH - l.headerH).coerceAtLeast(l.minW.coerceAtMost(l.areaW.coerceAtLeast(1)))
        val width = (if (w.isNaN()) DEFAULT_DP.toFloat() else w).roundToInt().coerceIn(minOf(l.minW, maxW), maxW)
        val ph = PreviewGeometry.heightFor(width, srcW, srcH)
        val totalH = ph + l.headerH
        val wantX = if (x.isNaN()) (l.areaW - width - MARGIN_PX).toFloat() else x
        val wantY = if (y.isNaN()) MARGIN_PX.toFloat() else y
        val px = wantX.roundToInt().coerceAtMost(l.areaW - width).coerceAtLeast(0)
        val py = wantY.roundToInt().coerceAtMost(l.areaH - totalH).coerceAtLeast(0)
        return Win(px, py, width, ph)
    }

    /**
     * Bottom-right handle dragged by (dx, dy) since the last event: new width (top-left stays, never past the area). Kept
     * in Float so slow drags (sub-pixel steps) still add up; the movement is projected on the aspect diagonal like the
     * floating panel's corner ([PreviewGeometry.resizeByCorner]).
     */
    fun resizeWidth(w: Float, dx: Float, dy: Float, srcW: Int, srcH: Int, x: Int, y: Int, l: Limits): Float {
        val maxW = maxWidth(srcW, srcH, l.areaW - x, l.areaH - y - l.headerH).coerceAtLeast(l.minW)
        val dyAsW = if (srcH <= 0) 0f else dy * srcW / srcH
        return (w + (dx + dyAsW) / 2f).coerceIn(l.minW.toFloat(), maxW.toFloat())
    }
}
