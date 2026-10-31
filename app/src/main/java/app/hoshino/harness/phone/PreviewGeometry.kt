package app.hoshino.harness.phone

import kotlin.math.roundToInt

/** A rectangle in pixels (x, y = top-left). */
data class PxRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Geometry of the resizable preview window (pure, unit-tested). The preview always keeps the agent display's aspect
 * ratio, so its size is fully described by its width. Resizing is continuous (corner drag or pinch), clamped to
 * [Limits]. Touches on the preview map back to agent-display pixels proportionally (letterbox-aware).
 */
object PreviewGeometry {
    /** minW/maxW bound the width; maxH bounds the height that follows from the aspect ratio. */
    data class Limits(val minW: Int, val maxW: Int, val maxH: Int)

    fun heightFor(width: Int, srcW: Int, srcH: Int): Int = if (srcW <= 0) 0 else ((width.toLong() * srcH + srcW / 2) / srcW).toInt()

    /** Width clamped so that both the width and the aspect-derived height fit [l]. The minimum wins over the maximum. */
    fun clampWidth(width: Int, srcW: Int, srcH: Int, l: Limits): Int {
        val byHeight = if (srcH <= 0) l.maxW else (l.maxH.toLong() * srcW / srcH).toInt()
        val max = minOf(l.maxW, byHeight)
        return width.coerceAtMost(max).coerceAtLeast(l.minW)
    }

    /**
     * Bottom-right corner drag by (dx, dy) screen pixels since the gesture started at [startW]. The finger's movement is
     * projected onto the diagonal of the aspect ratio, so dragging along either axis or diagonally all feel continuous.
     */
    fun resizeByCorner(startW: Int, dx: Float, dy: Float, srcW: Int, srcH: Int, l: Limits): Int {
        val dyAsW = if (srcH <= 0) 0f else dy * srcW / srcH
        val delta = (dx + dyAsW) / 2f
        return clampWidth((startW + delta).roundToInt(), srcW, srcH, l)
    }

    /** Two-finger pinch: [scale] = current span / span at the start. */
    fun pinch(startW: Int, scale: Float, srcW: Int, srcH: Int, l: Limits): Int =
        clampWidth((startW * scale.coerceIn(0.05f, 20f)).roundToInt(), srcW, srcH, l)

    /** Keeps a w×h window fully on a screenW×screenH screen (top-left anchored when it is larger than the screen). */
    fun clampPosition(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Pair<Int, Int> =
        x.coerceAtMost(screenW - w).coerceAtLeast(0) to y.coerceAtMost(screenH - h).coerceAtLeast(0)

    /**
     * Largest centred rectangle with the source's aspect ratio inside dstW×dstH (letterbox / pillarbox), rounded to the
     * nearest pixel. m2.9.2: this one rectangle is what the picture is drawn into (Bitmap matrix and GL viewport) AND what
     * touches are mapped through ([PreviewTransform]), so display and input can no longer disagree.
     */
    fun fitViewport(srcW: Int, srcH: Int, dstW: Int, dstH: Int): PxRect {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return PxRect(0, 0, maxOf(dstW, 0), maxOf(dstH, 0))
        return if (dstW.toLong() * srcH <= dstH.toLong() * srcW) {
            val h = ((dstW.toLong() * srcH + srcW / 2) / srcW).toInt().coerceAtMost(dstH)
            PxRect(0, (dstH - h) / 2, dstW, h)
        } else {
            val w = ((dstH.toLong() * srcW + srcH / 2) / srcH).toInt().coerceAtMost(dstW)
            PxRect((dstW - w) / 2, 0, w, dstH)
        }
    }

    /**
     * A touch at (tx, ty) inside a viewW×viewH preview → pixel on the srcW×srcH agent display, or null when it falls in
     * the black bars outside the picture. Results are clamped to the display (an edge touch never maps outside it).
     */
    fun mapToDisplay(tx: Float, ty: Float, viewW: Int, viewH: Int, srcW: Int, srcH: Int): Pair<Int, Int>? =
        PreviewTransform(srcW, srcH, viewW, viewH).toDisplay(tx, ty)

    /** Like [mapToDisplay] but clamps points in the bars to the nearest picture edge (for the middle of a swipe). */
    fun mapClamped(tx: Float, ty: Float, viewW: Int, viewH: Int, srcW: Int, srcH: Int): Pair<Int, Int> =
        PreviewTransform(srcW, srcH, viewW, viewH).toDisplayClamped(tx, ty)

    /**
     * Window limits for the resizable preview (m2.9.2). Before, the picture could only grow until the panel's fixed
     * ~300 dp of process lines and buttons hit the screen bottom (on a 6.7" phone roughly 120–265 dp wide, and the card
     * never got wider than its 280 dp minimum), so a corner drag or pinch barely changed anything. Now the controls are
     * folded away unless asked for, so the picture ranges from about ball size up to the screen minus [chromeW]×[chromeH].
     */
    fun limitsFor(screenW: Int, screenH: Int, chromeW: Int, chromeH: Int, minW: Int): Limits {
        val maxW = (screenW - chromeW).coerceAtLeast(minW)
        val maxH = (screenH - chromeH).coerceAtLeast(minW)
        return Limits(minW, maxW, maxH)
    }

    /** What a finished one-finger gesture on the preview was. */
    enum class Gesture { Tap, LongPress, Swipe }

    fun classify(moved: Float, durationMs: Long, slop: Float): Gesture = when {
        moved > slop -> Gesture.Swipe
        durationMs >= LONG_PRESS_MS -> Gesture.LongPress
        else -> Gesture.Tap
    }

    /**
     * Evenly thins a recorded stroke to at most [max] points, always keeping the first and the last, and makes the times
     * strictly increasing (the helper replays them as one finger stroke).
     */
    fun thinStroke(xs: IntArray, ys: IntArray, ts: LongArray, max: Int = MAX_STROKE_POINTS): Triple<IntArray, IntArray, LongArray> {
        val n = xs.size
        require(ys.size == n && ts.size == n && n >= 2 && max >= 2)
        val idx = if (n <= max) IntArray(n) { it } else IntArray(max) { (it.toLong() * (n - 1) / (max - 1)).toInt() }
        val ox = IntArray(idx.size) { xs[idx[it]] }
        val oy = IntArray(idx.size) { ys[idx[it]] }
        val ot = LongArray(idx.size)
        val base = ts[idx[0]]
        for (i in idx.indices) {
            val t = (ts[idx[i]] - base).coerceAtMost(MAX_STROKE_MS)
            ot[i] = if (i == 0) 0 else maxOf(t, ot[i - 1] + 1)
        }
        return Triple(ox, oy, ot)
    }

    const val LONG_PRESS_MS = 500L
    const val MAX_STROKE_POINTS = 48
    const val MAX_STROKE_MS = 3_000L
}

/**
 * The one display ↔ preview transform (m2.9.2). Fit-centre into the rounded [PreviewGeometry.fitViewport]; the Bitmap
 * fallback draws through [bitmapMatrix] and the GL relay sets its viewport to [viewport], touches go back through
 * [toDisplay]. Round trip display → view → display is exact for every pixel centre (error ≤ 1 px by construction).
 */
data class PreviewTransform(val srcW: Int, val srcH: Int, val viewW: Int, val viewH: Int) {
    val viewport: PxRect = PreviewGeometry.fitViewport(srcW, srcH, viewW, viewH)
    private val valid = srcW > 0 && srcH > 0 && viewport.w > 0 && viewport.h > 0

    /** Centre of display pixel (x, y) in view coordinates. */
    fun toView(x: Int, y: Int): Pair<Float, Float> =
        if (!valid) 0f to 0f
        else (viewport.x + (x + 0.5f) * viewport.w / srcW) to (viewport.y + (y + 0.5f) * viewport.h / srcH)

    /** View point → display pixel, or null outside the picture (in the letterbox bars). */
    fun toDisplay(tx: Float, ty: Float): Pair<Int, Int>? {
        if (!valid) return null
        val rx = tx - viewport.x
        val ry = ty - viewport.y
        if (rx < 0 || ry < 0 || rx > viewport.w || ry > viewport.h) return null
        return px(rx, viewport.w, srcW) to px(ry, viewport.h, srcH)
    }

    /** Like [toDisplay] but points in the bars are clamped to the nearest picture edge. */
    fun toDisplayClamped(tx: Float, ty: Float): Pair<Int, Int> {
        if (!valid) return 0 to 0
        val rx = (tx - viewport.x).coerceIn(0f, viewport.w.toFloat())
        val ry = (ty - viewport.y).coerceIn(0f, viewport.h.toFloat())
        return px(rx, viewport.w, srcW) to px(ry, viewport.h, srcH)
    }

    /**
     * android.graphics.Matrix values (row-major 3×3) that draw a bmpW×bmpH preview Bitmap of the display exactly into
     * [viewport]. Each axis is scaled separately, so a preview whose height was rounded (360 × 801.3 → 801) still lands
     * on the same rectangle the touches use.
     */
    fun bitmapMatrix(bmpW: Int, bmpH: Int): FloatArray {
        val sx = if (bmpW > 0) viewport.w.toFloat() / bmpW else 1f
        val sy = if (bmpH > 0) viewport.h.toFloat() / bmpH else 1f
        return floatArrayOf(sx, 0f, viewport.x.toFloat(), 0f, sy, viewport.y.toFloat(), 0f, 0f, 1f)
    }

    private fun px(r: Float, span: Int, size: Int): Int = (r.toDouble() * size / span).toInt().coerceIn(0, size - 1)
}
