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

    /** Largest centred rectangle with the source's aspect ratio inside dstW×dstH (letterbox / pillarbox). */
    fun fitViewport(srcW: Int, srcH: Int, dstW: Int, dstH: Int): PxRect {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return PxRect(0, 0, maxOf(dstW, 0), maxOf(dstH, 0))
        return if (dstW.toLong() * srcH <= dstH.toLong() * srcW) {
            val h = (dstW.toLong() * srcH / srcW).toInt()
            PxRect(0, (dstH - h) / 2, dstW, h)
        } else {
            val w = (dstH.toLong() * srcW / srcH).toInt()
            PxRect((dstW - w) / 2, 0, w, dstH)
        }
    }

    /**
     * A touch at (tx, ty) inside a viewW×viewH preview → pixel on the srcW×srcH agent display, or null when it falls in
     * the black bars outside the picture. Results are clamped to the display (an edge touch never maps outside it).
     */
    fun mapToDisplay(tx: Float, ty: Float, viewW: Int, viewH: Int, srcW: Int, srcH: Int): Pair<Int, Int>? {
        val vp = fitViewport(srcW, srcH, viewW, viewH)
        if (vp.w <= 0 || vp.h <= 0) return null
        val rx = tx - vp.x
        val ry = ty - vp.y
        if (rx < 0 || ry < 0 || rx > vp.w || ry > vp.h) return null
        val x = (rx * srcW / vp.w).toInt().coerceIn(0, srcW - 1)
        val y = (ry * srcH / vp.h).toInt().coerceIn(0, srcH - 1)
        return x to y
    }

    /** Like [mapToDisplay] but clamps points in the bars to the nearest picture edge (for the middle of a swipe). */
    fun mapClamped(tx: Float, ty: Float, viewW: Int, viewH: Int, srcW: Int, srcH: Int): Pair<Int, Int> {
        val vp = fitViewport(srcW, srcH, viewW, viewH)
        val rx = (tx - vp.x).coerceIn(0f, vp.w.toFloat())
        val ry = (ty - vp.y).coerceIn(0f, vp.h.toFloat())
        val x = if (vp.w <= 0) 0 else (rx * srcW / vp.w).toInt().coerceIn(0, srcW - 1)
        val y = if (vp.h <= 0) 0 else (ry * srcH / vp.h).toInt().coerceIn(0, srcH - 1)
        return x to y
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
